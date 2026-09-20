package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class StoreCostService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final ObjectMapper mapper;
    private final AuditWriter audit;
    private static final String SCOPE="tenant_id=:tenantId and hotel_org_unit_id=:hotelId";
    private static final String PERIOD=SCOPE+" and posting_date>=:start and posting_date<:end";
    private static final Set<String> KINDS=Set.of("ALL","CONSUMABLE","CLEANING_SUPPLY","OPERATING_ITEM","LINEN_USAGE","LAUNDRY","LOSS","ADJUSTMENT");

    public StoreCostService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,ObjectMapper mapper,AuditWriter audit) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.mapper=mapper;this.audit=audit;
    }

    // All queries in a preview observe the same committed facts; the token cannot describe a mixed snapshot.
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(UUID hotelId,String month) {
        TenantPrincipal p=prepare("store-resource.read",hotelId);
        LocalDate today=today(p); YearMonth period=month(month,today);
        var params=params(p,hotelId,period); var closed=closed(params);
        if(!closed.isEmpty()) return closedView(closed.getFirst(),today);
        var snapshot=snapshot(params);
        var result=new LinkedHashMap<String,Object>(snapshot);
        result.put("month",period.toString());result.put("currentMonth",YearMonth.from(today).toString());result.put("closed",false);
        result.put("previewToken",token(p,hotelId,period,snapshot));
        result.put("canClosePeriod",period.isBefore(YearMonth.from(today)) && ((Number)snapshot.get("pendingUsageCount")).longValue()==0);
        return result;
    }

    @Transactional
    public Map<String,Object> close(UUID hotelId,StoreCostController.Close input) {
        TenantPrincipal p=prepare("store-resource.cost-close",hotelId);
        access.requirePermission("store-resource.read");
        LocalDate today=today(p);YearMonth period=month(input.month(),today);
        if(!period.isBefore(YearMonth.from(today))) throw new IllegalArgumentException("本月尚未结束，只能预览；请在下月核对后月结");
        var params=params(p,hotelId,period);
        // Do not acquire item/order locks here. Writers only hold the shared counterpart of this lock.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:costScope,66))",
                new MapSqlParameterSource("costScope",p.tenantId()+":"+hotelId));
        var closed=closed(params);
        if(!closed.isEmpty()) {
            var row=closed.getFirst();
            if(!input.previewToken().equals(row.get("preview_token")) || !input.note().trim().equals(row.get("note")))
                throw conflict("本月已经关账，不能覆盖；后续补录及冲销会留在当前月份");
            return closedView(row,today);
        }
        var snapshot=snapshot(params);
        if(!input.previewToken().equals(token(p,hotelId,period,snapshot))) throw conflict("成本或待核对事项已变化，请刷新预览并重新核对后月结");
        if(((Number)snapshot.get("pendingUsageCount")).longValue()>0) throw conflict("该月仍有待审核耗用，请先审核或退回再关账");
        if(!input.acknowledgeWarnings()) throw new IllegalArgumentException("请确认已经核对在洗布草、零金额及历史分类提示；未验收洗涤费不作预估入账");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into store_cost_month_close(id,tenant_id,hotel_org_unit_id,month_start,preview_token,snapshot,note,closed_by)
                values(:id,:tenantId,:hotelId,:start,:token,cast(:snapshot as jsonb),:note,:actorId)
                """,params.addValue("id",id).addValue("token",input.previewToken()).addValue("snapshot",json(snapshot))
                .addValue("note",input.note().trim()).addValue("actorId",p.actorId()));
        audit.record("STORE_COST_MONTH_CLOSED","STORE_COST_MONTH_CLOSE",id,json(Map.of("hotelId",hotelId,"month",period.toString(),"previewToken",input.previewToken(),"note",input.note().trim())));
        return closedView(closed(params).getFirst(),today);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> entries(UUID hotelId,String month,int page,String kind,String day) {
        TenantPrincipal p=prepare("store-resource.read",hotelId);checkPage(page);
        if(!KINDS.contains(kind)) throw new IllegalArgumentException("未知成本分类");
        YearMonth period=month(month,today(p));var params=params(p,hotelId,period);
        LocalDate selected=null;
        if(day!=null && !day.isBlank()) {
            try { selected=LocalDate.parse(day); } catch(Exception e) { throw new IllegalArgumentException("日期格式应为年-月-日"); }
            if(!YearMonth.from(selected).equals(period)) throw new IllegalArgumentException("日期必须在查询月份内");
        }
        var rows=jdbc.queryForList("""
                select c.id,c.item_id,c.item_name,c.item_category,c.base_unit,c.cost_kind,c.quantity::text as quantity,
                  c.amount::text as amount,c.business_date,c.posting_date,c.source_kind,c.source_id,c.source_parent_id,
                  c.direction,c.reverses_entry_id,c.reason,c.legacy_classification,c.source_recorded_at,
                  a.display_name as operator_name,
                  (date_trunc('month',c.business_date)<>date_trunc('month',c.posting_date)) as prior_period
                from store_cost_entry c join user_account a on a.tenant_id=c.tenant_id and a.id=c.actor_account_id
                where c.tenant_id=:tenantId and c.hotel_org_unit_id=:hotelId and c.posting_date>=:start and c.posting_date<:end
                  and (:kind='ALL' or c.cost_kind=:kind) and (cast(:day as date) is null or c.posting_date=cast(:day as date))
                order by c.posting_date desc,c.created_at desc,c.id desc limit 51 offset :offset
                """,params.addValue("kind",kind).addValue("day",selected).addValue("offset",page*50));
        return Map.of("items",rows.size()>50?rows.subList(0,50):rows,"page",page,"hasMore",rows.size()>50);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> history(UUID hotelId,int page) {
        TenantPrincipal p=prepare("store-resource.read",hotelId);checkPage(page);
        var rows=jdbc.queryForList("""
                select c.id,to_char(c.month_start,'YYYY-MM') as month,c.note,c.closed_at,a.display_name as closed_by_name,
                  c.snapshot->>'usageTotal' as usage_total,c.snapshot->>'lossTotal' as loss_total,
                  c.snapshot->>'costTotal' as cost_total,c.snapshot->>'adjustmentTotal' as adjustment_total
                from store_cost_month_close c join user_account a on a.tenant_id=c.tenant_id and a.id=c.closed_by
                where c.tenant_id=:tenantId and c.hotel_org_unit_id=:hotelId order by c.month_start desc limit 13 offset :offset
                """,new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId).addValue("offset",page*12));
        return Map.of("items",rows.size()>12?rows.subList(0,12):rows,"page",page,"hasMore",rows.size()>12);
    }

    private Map<String,Object> snapshot(MapSqlParameterSource params) {
        var totals=jdbc.queryForMap("""
                select count(*) as "entryCount",
                  coalesce(sum(amount) filter(where cost_kind not in ('ADJUSTMENT','LOSS')),0)::text as "usageTotal",
                  coalesce(sum(amount) filter(where cost_kind='LOSS'),0)::text as "lossTotal",
                  coalesce(sum(amount) filter(where cost_kind<>'ADJUSTMENT'),0)::text as "costTotal",
                  coalesce(sum(amount) filter(where cost_kind='ADJUSTMENT'),0)::text as "adjustmentTotal",
                  count(*) filter(where amount=0 and direction=1) as "zeroAmountCount",
                  count(*) filter(where legacy_classification) as "legacyCount",
                  count(*) filter(where date_trunc('month',business_date)<>date_trunc('month',posting_date)) as "priorPeriodCount"
                from store_cost_entry where
                """+PERIOD,params);
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",1);result.putAll(totals);
        result.put("categories",jdbc.queryForList("select cost_kind,sum(amount)::text as amount,count(*) as entry_count from store_cost_entry where "+PERIOD+" group by cost_kind order by cost_kind",params));
        result.put("daily",jdbc.queryForList("select posting_date,coalesce(sum(amount) filter(where cost_kind<>'ADJUSTMENT'),0)::text as amount,coalesce(sum(amount) filter(where cost_kind='ADJUSTMENT'),0)::text as adjustment_amount from store_cost_entry where "+PERIOD+" group by posting_date order by posting_date",params));
        result.put("items",jdbc.queryForList("select item_id,item_name,base_unit,cost_kind,sum(quantity)::text as quantity,sum(amount)::text as amount,count(*) as entry_count from store_cost_entry where "+PERIOD+" group by item_id,item_name,base_unit,cost_kind order by cost_kind,item_name,item_id,base_unit",params));
        result.put("pendingUsageCount",jdbc.queryForObject("""
                select count(*) from store_work_usage u join work_record r on r.tenant_id=u.tenant_id and r.id=u.work_record_id
                where u.tenant_id=:tenantId and u.hotel_org_unit_id=:hotelId and u.status='PENDING'
                  and r.business_date>=:start and r.business_date<:end
                """,params,Long.class));
        // Current outstanding orders originating before month end are a reconciliation warning, not estimated fees.
        result.put("openLaundryCount",jdbc.queryForObject("""
                select count(*) from store_laundry_order o where o.tenant_id=:tenantId and o.hotel_org_unit_id=:hotelId
                  and o.status='ACTIVE' and o.dispatched_on<:end and exists(
                    select 1 from store_laundry_line l where l.tenant_id=o.tenant_id and l.order_id=o.id and l.quantity>(
                      select coalesce(sum(x.accepted_quantity+x.written_off_quantity),0) from store_laundry_receipt_line x
                      join store_laundry_receipt r on r.tenant_id=x.tenant_id and r.id=x.receipt_id and r.status='POSTED'
                      where x.tenant_id=l.tenant_id and x.order_line_id=l.id))
                """,params,Long.class));
        return result;
    }
    private List<Map<String,Object>> closed(MapSqlParameterSource params) {
        return jdbc.queryForList("""
                select c.*,a.display_name as closed_by_name from store_cost_month_close c
                join user_account a on a.tenant_id=c.tenant_id and a.id=c.closed_by
                where c.tenant_id=:tenantId and c.hotel_org_unit_id=:hotelId and c.month_start=:start
                """,params);
    }
    private Map<String,Object> closedView(Map<String,Object> row,LocalDate today) {
        try {
            Map<String,Object> result=mapper.readValue(row.get("snapshot").toString(),new TypeReference<LinkedHashMap<String,Object>>(){});
            result.put("month",row.get("month_start").toString().substring(0,7));result.put("currentMonth",YearMonth.from(today).toString());
            result.put("closed",true);result.put("canClosePeriod",false);result.put("previewToken",row.get("preview_token"));
            result.put("closedAt",row.get("closed_at"));result.put("closedByName",row.get("closed_by_name"));result.put("closeNote",row.get("note"));
            return result;
        } catch(Exception e) { throw new IllegalStateException("成本月结快照读取失败",e); }
    }
    private TenantPrincipal prepare(String permission,UUID hotelId) {
        access.requirePermission(permission);TenantPrincipal p=access.principal();database.apply(p.tenantId());access.requireOrgScope(hotelId);
        if(jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:hotelId and unit_type='HOTEL'",
                new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId),Long.class)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"门店不存在");
        return p;
    }
    private LocalDate today(TenantPrincipal p) {
        return jdbc.queryForObject("select (clock_timestamp() at time zone timezone)::date from tenant where id=:id",Map.of("id",p.tenantId()),LocalDate.class);
    }
    private YearMonth month(String value,LocalDate today) {
        YearMonth month;
        try { if(value!=null && !value.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException(); month=value==null?YearMonth.from(today):YearMonth.parse(value); }
        catch(Exception e) { throw new IllegalArgumentException("月份格式应为 YYYY-MM"); }
        if(month.getYear()<2000 || month.isAfter(YearMonth.from(today))) throw new IllegalArgumentException("只能查询2000年起至当月的成本");
        return month;
    }
    private MapSqlParameterSource params(TenantPrincipal p,UUID hotelId,YearMonth period) {
        return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId).addValue("start",period.atDay(1)).addValue("end",period.plusMonths(1).atDay(1));
    }
    private String token(TenantPrincipal p,UUID hotelId,YearMonth month,Map<String,Object> snapshot) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((p.tenantId()+":"+hotelId+":"+month+":"+json(snapshot)).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private void checkPage(int page) { if(page<0 || page>100000) throw new IllegalArgumentException("页码超出范围"); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
