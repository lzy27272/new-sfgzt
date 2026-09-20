package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static cn.sifangguan.hotelaios.storeassets.StoreLaundryController.*;

@Service
public class StoreLaundryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final AuditWriter audit;
    private final ObjectMapper mapper;
    private final StoreResourceService stock;

    // Tenant/hotel filters are inside the aggregate too. Cancelled orders remain readable but hold no stock.
    private static final String LINES = """
            select l.*,coalesce(s.accepted_quantity,0) as accepted_quantity,
              coalesce(s.written_off_quantity,0) as written_off_quantity,coalesce(s.fee,0) as fee,
              l.quantity-coalesce(s.accepted_quantity,0)-coalesce(s.written_off_quantity,0) as pending_quantity
            from store_laundry_line l left join (
              select x.order_line_id,sum(x.accepted_quantity) as accepted_quantity,
                sum(x.written_off_quantity) as written_off_quantity,sum(x.fee) as fee
              from store_laundry_receipt_line x join store_laundry_receipt r
                on r.tenant_id=x.tenant_id and r.id=x.receipt_id and r.status='POSTED'
              where x.tenant_id=:tenantId and x.hotel_org_unit_id=:hotelId group by x.order_line_id
            ) s on s.order_line_id=l.id where l.tenant_id=:tenantId and l.hotel_org_unit_id=:hotelId
            """;
    private static final String ORDERS = """
            with lines as (%s), totals as (
              select order_id,sum(quantity) as sent_quantity,sum(accepted_quantity) as accepted_quantity,
                sum(written_off_quantity) as written_off_quantity,sum(pending_quantity) as pending_quantity,
                sum(fee) as fee,count(*) as line_count from lines group by order_id
            ) select o.id,o.supplier,o.dispatched_on,o.note,o.row_version,o.created_at,
              a.display_name as operator_name,o.cancellation_reason,t.fee,t.line_count,
              t.sent_quantity,t.accepted_quantity,t.written_off_quantity,
              case when o.status='CANCELLED' then 0 else t.pending_quantity end as pending_quantity,
              case when o.status='CANCELLED' then 'CANCELLED' when t.pending_quantity=0 then 'COMPLETED'
                when exists(select 1 from store_laundry_receipt r where r.tenant_id=o.tenant_id and r.order_id=o.id
                  and r.status='POSTED') then 'PARTIAL' else 'SENT' end as status
            from store_laundry_order o join totals t on t.order_id=o.id
            join user_account a on a.tenant_id=o.tenant_id and a.id=o.created_by
            where o.tenant_id=:tenantId and o.hotel_org_unit_id=:hotelId
            """.formatted(LINES);

    public StoreLaundryService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,
                               AuditWriter audit,ObjectMapper mapper,StoreResourceService stock) {
        this.jdbc=jdbc; this.database=database; this.access=access; this.audit=audit; this.mapper=mapper; this.stock=stock;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> catalogue(UUID hotelId) {
        TenantPrincipal p=prepare("store-resource.read",hotelId);
        return jdbc.queryForList("""
                with lines as (%s), held as (
                  select l.item_id,sum(l.pending_quantity) as quantity from lines l join store_laundry_order o
                    on o.tenant_id=l.tenant_id and o.id=l.order_id and o.status='ACTIVE' group by l.item_id
                ) select i.id as item_id,i.name as item_name,i.unit as base_unit,i.row_version as item_version,
                  r.unit_price,coalesce(r.row_version,-1) as rate_version,
                  coalesce(b.quantity,0) as owned_quantity,coalesce(h.quantity,0) as pending_quantity,
                  coalesce(b.quantity,0)-coalesce(h.quantity,0) as available_quantity
                from store_resource_item i left join store_laundry_rate r on r.tenant_id=i.tenant_id and r.item_id=i.id
                left join store_inventory_balance b on b.tenant_id=i.tenant_id and b.item_id=i.id
                left join held h on h.item_id=i.id
                where i.tenant_id=:tenantId and i.hotel_org_unit_id=:hotelId and i.category='LINEN' and i.status='ACTIVE'
                order by i.name,i.id
                """.formatted(LINES),base(p,hotelId));
    }

    @Transactional
    public Map<String,Object> configure(UUID hotelId,UUID itemId,Rate input) {
        TenantPrincipal p=prepare("store-resource.item-manage",hotelId);
        Map<String,Object> item=lockItems(p,hotelId,List.of(itemId)).get(itemId);
        requireActiveLinen(item,input.expectedItemVersion());
        BigDecimal price=quantity(input.unitPrice(),"洗涤单价");
        MapSqlParameterSource params=base(p,hotelId).addValue("itemId",itemId);
        List<Map<String,Object>> before=jdbc.queryForList("select * from store_laundry_rate where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId",params);
        long version=before.isEmpty() ? -1 : version(before.getFirst());
        if(version!=input.expectedVersion()) throw conflict("洗涤单价已修改，请刷新核对后保存");
        jdbc.update("""
                insert into store_laundry_rate(tenant_id,hotel_org_unit_id,item_id,base_unit,unit_price,updated_by)
                values(:tenantId,:hotelId,:itemId,:unit,:price,:actorId)
                on conflict(tenant_id,hotel_org_unit_id,item_id) do update set unit_price=excluded.unit_price,
                  row_version=store_laundry_rate.row_version+1,updated_by=excluded.updated_by,updated_at=now()
                """,params.addValue("unit",item.get("unit")).addValue("price",price));
        audit.record("LAUNDRY_RATE_CHANGED","STORE_RESOURCE_ITEM",itemId,json(Map.of("hotelId",hotelId,"before",before,"unitPrice",price)));
        return jdbc.queryForMap("select * from store_laundry_rate where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId",params);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> orders(UUID hotelId,int page,String status) {
        TenantPrincipal p=prepare("store-resource.read",hotelId);
        if(page<0 || page>100000 || !Set.of("ALL","OPEN","COMPLETED","CANCELLED").contains(status)) throw new IllegalArgumentException("无效页码或洗涤状态");
        List<Map<String,Object>> rows=jdbc.queryForList("select * from ("+ORDERS+") orders where (:status='ALL' or status=:status or (:status='OPEN' and status in ('SENT','PARTIAL'))) order by dispatched_on desc,created_at desc,id limit 26 offset :offset",
                base(p,hotelId).addValue("status",status).addValue("offset",page*25));
        return Map.of("items",rows.size()>25 ? rows.subList(0,25) : rows,"page",page,"hasMore",rows.size()>25);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID hotelId,UUID orderId) {
        return detail(prepare("store-resource.read",hotelId),hotelId,orderId);
    }
    private Map<String,Object> detail(TenantPrincipal p,UUID hotelId,UUID orderId) {
        MapSqlParameterSource params=base(p,hotelId).addValue("orderId",orderId);
        List<Map<String,Object>> orders=jdbc.queryForList("select * from ("+ORDERS+") orders where id=:orderId",params);
        if(orders.isEmpty()) throw missing("洗涤单不存在");
        Map<String,Object> order=orders.getFirst();
        order.put("lines",lines(p,hotelId,orderId));
        List<Map<String,Object>> receipts=jdbc.queryForList("""
                select r.id,r.received_on,r.note,r.status,r.created_at,r.reversed_at,r.reversal_reason,
                  a.display_name as operator_name,rev.display_name as reversed_by_name
                from store_laundry_receipt r join user_account a on a.tenant_id=r.tenant_id and a.id=r.created_by
                left join user_account rev on rev.tenant_id=r.tenant_id and rev.id=r.reversed_by
                where r.tenant_id=:tenantId and r.hotel_org_unit_id=:hotelId and r.order_id=:orderId
                order by r.created_at desc,r.id
                """,params);
        Map<UUID,List<Map<String,Object>>> byReceipt=new HashMap<>();
        for(var line:jdbc.queryForList("""
                select x.*,l.item_name,l.base_unit,l.unit_price from store_laundry_receipt_line x join store_laundry_line l
                  on l.tenant_id=x.tenant_id and l.id=x.order_line_id
                where x.tenant_id=:tenantId and x.hotel_org_unit_id=:hotelId and x.order_id=:orderId order by l.item_name,l.id
                """,params)) byReceipt.computeIfAbsent((UUID)line.get("receipt_id"),ignored->new ArrayList<>()).add(line);
        for(var receipt:receipts) receipt.put("lines",byReceipt.getOrDefault(receipt.get("id"),List.of()));
        order.put("receipts",receipts); return order;
    }

    @Transactional
    public Map<String,Object> dispatch(UUID hotelId,String key,Dispatch input) {
        TenantPrincipal p=prepare("store-resource.movement-create",hotelId);
        lockKey(p,key,"dispatch"); String hash=hash(input);
        var existing=jdbc.queryForList("select * from store_laundry_order where tenant_id=:tenantId and idempotency_key=:key",base(p,hotelId).addValue("key",key));
        if(!existing.isEmpty()) { sameRequest(existing.getFirst(),hotelId,hash); return detail(p,hotelId,(UUID)existing.getFirst().get("id")); }
        validateDate(p,input.dispatchedOn());
        List<UUID> ids=unique(input.lines().stream().map(DispatchLine::itemId).toList());
        Map<UUID,Map<String,Object>> items=lockItems(p,hotelId,ids);
        UUID orderId=UUID.randomUUID();
        jdbc.update("""
                insert into store_laundry_order(id,tenant_id,hotel_org_unit_id,supplier,dispatched_on,note,idempotency_key,request_hash,created_by)
                values(:id,:tenantId,:hotelId,:supplier,:date,:note,:key,:hash,:actorId)
                """,base(p,hotelId).addValue("id",orderId).addValue("supplier",input.supplier().trim()).addValue("date",input.dispatchedOn())
                .addValue("note",text(input.note())).addValue("key",key).addValue("hash",hash));
        for(var line:input.lines()) {
            Map<String,Object> item=items.get(line.itemId()); requireActiveLinen(item,line.expectedItemVersion());
            var rates=jdbc.queryForList("select * from store_laundry_rate where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId",
                    base(p,hotelId).addValue("itemId",line.itemId()));
            if(rates.isEmpty() || version(rates.getFirst())!=line.expectedRateVersion()) throw conflict("请先配置并核对最新洗涤单价");
            BigDecimal quantity=quantity(line.quantity(),"送洗数量");
            BigDecimal available=owned(p,hotelId,line.itemId()).subtract(heldQuantity(jdbc,p,hotelId,line.itemId()));
            if(quantity.compareTo(available)>0) throw conflict(item.get("name")+" 可送洗数量不足（总库存减在洗数量）");
            jdbc.update("""
                    insert into store_laundry_line(id,tenant_id,hotel_org_unit_id,order_id,item_id,item_name,base_unit,quantity,unit_price,rate_version)
                    values(:id,:tenantId,:hotelId,:orderId,:itemId,:name,:unit,:quantity,:price,:rateVersion)
                    """,base(p,hotelId).addValue("id",UUID.randomUUID()).addValue("orderId",orderId).addValue("itemId",line.itemId())
                    .addValue("name",item.get("name")).addValue("unit",item.get("unit")).addValue("quantity",quantity)
                    .addValue("price",rates.getFirst().get("unit_price")).addValue("rateVersion",line.expectedRateVersion()));
        }
        audit.record("LAUNDRY_DISPATCHED","STORE_LAUNDRY_ORDER",orderId,json(input));
        return detail(p,hotelId,orderId);
    }

    @Transactional
    public Map<String,Object> receive(UUID hotelId,UUID orderId,String key,Receive input) {
        TenantPrincipal p=prepare("store-resource.movement-create",hotelId);
        lockKey(p,key,"receipt"); String hash=hash(input);
        Map<String,Object> order=lockOrder(p,hotelId,orderId);
        var existing=jdbc.queryForList("select * from store_laundry_receipt where tenant_id=:tenantId and idempotency_key=:key",base(p,hotelId).addValue("key",key));
        if(!existing.isEmpty()) {
            sameRequest(existing.getFirst(),hotelId,hash);
            if(!orderId.equals(existing.getFirst().get("order_id"))) throw conflict("重复请求不能改用另一张洗涤单");
            return detail(p,hotelId,orderId);
        }
        checkVersion(order,input.expectedVersion()); validateDate(p,input.receivedOn());
        if(input.receivedOn().isBefore(((java.sql.Date)order.get("dispatched_on")).toLocalDate())) throw new IllegalArgumentException("回收日期不能早于送洗日期");
        unique(input.lines().stream().map(ReceiveLine::lineId).toList());
        var lines=lines(p,hotelId,orderId); Map<UUID,Map<String,Object>> byId=new HashMap<>();
        for(var line:lines) byId.put((UUID)line.get("id"),line);
        lockItems(p,hotelId,lines.stream().map(l->(UUID)l.get("item_id")).toList());
        UUID receiptId=UUID.randomUUID();
        jdbc.update("""
                insert into store_laundry_receipt(id,tenant_id,hotel_org_unit_id,order_id,received_on,note,idempotency_key,request_hash,created_by)
                values(:id,:tenantId,:hotelId,:orderId,:date,:note,:key,:hash,:actorId)
                """,base(p,hotelId).addValue("id",receiptId).addValue("orderId",orderId).addValue("date",input.receivedOn())
                .addValue("note",text(input.note())).addValue("key",key).addValue("hash",hash));
        // Canonical item order matches all stock writers; the order row serializes competing receipts/reversals.
        for(var inputLine:input.lines().stream().sorted(Comparator.comparing(l->l.lineId().toString())).toList()) {
            var line=byId.get(inputLine.lineId()); if(line==null) throw new IllegalArgumentException("回收明细不属于当前洗涤单");
            BigDecimal accepted=quantity(inputLine.acceptedQuantity(),"合格数量"), rewash=quantity(inputLine.rewashQuantity(),"返洗数量"), lost=quantity(inputLine.writtenOffQuantity(),"报损数量");
            BigDecimal total=accepted.add(rewash).add(lost);
            if(total.signum()<=0 || total.compareTo(decimal(line.get("pending_quantity")))>0) throw conflict("回收、返洗及报损数量必须大于0且不得超过该项尚在洗数量");
            if(lost.signum()>0 && !input.confirmWriteOff()) throw new IllegalArgumentException("报损会扣减总库存，请单独确认报损");
            if(rewash.add(lost).signum()>0 && text(inputLine.differenceReason()).isBlank()) throw new IllegalArgumentException("返洗或报损必须填写差异原因");
            BigDecimal fee=StoreItemUnits.exact(accepted.multiply(decimal(line.get("unit_price"))).setScale(4,RoundingMode.HALF_UP),4,16,"洗涤金额");
            jdbc.update("""
                    insert into store_laundry_receipt_line(tenant_id,hotel_org_unit_id,order_id,receipt_id,order_line_id,
                      accepted_quantity,rewash_quantity,written_off_quantity,fee,difference_reason)
                    values(:tenantId,:hotelId,:orderId,:receiptId,:lineId,:accepted,:rewash,:lost,:fee,:reason)
                    """,base(p,hotelId).addValue("orderId",orderId).addValue("receiptId",receiptId).addValue("lineId",inputLine.lineId())
                    .addValue("accepted",accepted).addValue("rewash",rewash).addValue("lost",lost).addValue("fee",fee).addValue("reason",text(inputLine.differenceReason())));
        }
        // All receipt facts are visible before reducing inventory, so held-stock validation sees the released quantity.
        for(var line:receiptLines(p,hotelId,receiptId)) {
            if(decimal(line.get("written_off_quantity")).signum()>0) stock.postLaundryLoss(p,hotelId,receiptId,line,false,null);
        }
        increment(p,hotelId,orderId);
        audit.record("LAUNDRY_RECEIVED","STORE_LAUNDRY_ORDER",orderId,json(Map.of("receiptId",receiptId,"input",input)));
        return detail(p,hotelId,orderId);
    }

    @Transactional
    public Map<String,Object> cancel(UUID hotelId,UUID orderId,Correction input) {
        TenantPrincipal p=prepare("store-resource.movement-create",hotelId);
        var order=lockOrder(p,hotelId,orderId);
        if("CANCELLED".equals(order.get("status"))) {
            if(!text(input.reason()).equals(order.get("cancellation_reason"))) throw conflict("已取消的洗涤单不能变更原因");
            return detail(p,hotelId,orderId);
        }
        checkVersion(order,input.expectedVersion());
        if(jdbc.queryForObject("select count(*) from store_laundry_receipt where tenant_id=:tenantId and order_id=:orderId and status='POSTED'",
                base(p,hotelId).addValue("orderId",orderId),Long.class)>0) throw conflict("存在有效回收记录，请先冲销后再取消");
        lockItems(p,hotelId,lines(p,hotelId,orderId).stream().map(l->(UUID)l.get("item_id")).toList());
        jdbc.update("""
                update store_laundry_order set status='CANCELLED',row_version=row_version+1,cancelled_by=:actorId,
                  cancelled_at=now(),cancellation_reason=:reason where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:orderId
                """,base(p,hotelId).addValue("orderId",orderId).addValue("reason",text(input.reason())));
        audit.record("LAUNDRY_CANCELLED","STORE_LAUNDRY_ORDER",orderId,json(input)); return detail(p,hotelId,orderId);
    }

    @Transactional
    public Map<String,Object> reverse(UUID hotelId,UUID orderId,UUID receiptId,Correction input) {
        TenantPrincipal p=prepare("store-resource.movement-create",hotelId);
        var order=lockOrder(p,hotelId,orderId);
        var receipts=jdbc.queryForList("select * from store_laundry_receipt where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and order_id=:orderId and id=:receiptId",
                base(p,hotelId).addValue("orderId",orderId).addValue("receiptId",receiptId));
        if(receipts.isEmpty()) throw missing("回收记录不存在");
        var receipt=receipts.getFirst();
        if("REVERSED".equals(receipt.get("status"))) {
            if(!text(input.reason()).equals(receipt.get("reversal_reason"))) throw conflict("已冲销记录不能修改原因");
            return detail(p,hotelId,orderId);
        }
        checkVersion(order,input.expectedVersion());
        var lines=receiptLines(p,hotelId,receiptId);
        lockItems(p,hotelId,lines.stream().map(l->(UUID)l.get("item_id")).toList());
        // Reverse the original write-off at its original value, never at today's purchase/reference price.
        for(var line:lines) if(decimal(line.get("written_off_quantity")).signum()>0) {
            BigDecimal original=jdbc.queryForObject("select -value_delta from store_inventory_movement where tenant_id=:tenantId and source_type='LAUNDRY' and source_id=:receiptId and item_id=:itemId and movement_type='DAMAGE'",
                    base(p,hotelId).addValue("receiptId",receiptId).addValue("itemId",line.get("item_id")),BigDecimal.class);
            stock.postLaundryLoss(p,hotelId,receiptId,line,true,original);
        }
        jdbc.update("""
                update store_laundry_receipt set status='REVERSED',reversed_by=:actorId,reversed_at=now(),reversal_reason=:reason
                where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:receiptId
                """,base(p,hotelId).addValue("receiptId",receiptId).addValue("reason",text(input.reason())));
        for(var line:lines) {
            UUID itemId=(UUID)line.get("item_id");
            if(heldQuantity(jdbc,p,hotelId,itemId).compareTo(owned(p,hotelId,itemId))>0) throw conflict("该批布草已再次送洗或出库，当前数量不足以撤销回收；请先核对后续流水");
        }
        increment(p,hotelId,orderId);
        audit.record("LAUNDRY_RECEIPT_REVERSED","STORE_LAUNDRY_ORDER",orderId,json(Map.of("receiptId",receiptId,"reason",input.reason())));
        return detail(p,hotelId,orderId);
    }

    static BigDecimal heldQuantity(NamedParameterJdbcTemplate jdbc,TenantPrincipal p,UUID hotelId,UUID itemId) {
        return jdbc.queryForObject("select coalesce(sum(l.pending_quantity),0) from ("+LINES+") l join store_laundry_order o on o.tenant_id=l.tenant_id and o.id=l.order_id and o.status='ACTIVE' where l.item_id=:itemId",
                new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId).addValue("itemId",itemId),BigDecimal.class);
    }
    private List<Map<String,Object>> lines(TenantPrincipal p,UUID hotelId,UUID orderId) {
        return jdbc.queryForList("select * from ("+LINES+") lines where order_id=:orderId order by item_id",base(p,hotelId).addValue("orderId",orderId));
    }
    private List<Map<String,Object>> receiptLines(TenantPrincipal p,UUID hotelId,UUID receiptId) {
        return jdbc.queryForList("""
                select x.*,l.item_id,l.item_name,l.base_unit from store_laundry_receipt_line x join store_laundry_line l
                  on l.tenant_id=x.tenant_id and l.id=x.order_line_id
                where x.tenant_id=:tenantId and x.hotel_org_unit_id=:hotelId and x.receipt_id=:receiptId order by l.item_id
                """,base(p,hotelId).addValue("receiptId",receiptId));
    }
    private Map<UUID,Map<String,Object>> lockItems(TenantPrincipal p,UUID hotelId,List<UUID> ids) {
        Map<UUID,Map<String,Object>> result=new HashMap<>();
        for(var item:jdbc.queryForList("select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id in (:ids) order by id for update",
                base(p,hotelId).addValue("ids",ids))) result.put((UUID)item.get("id"),item);
        if(result.size()!=new HashSet<>(ids).size()) throw missing("门店布草不存在"); return result;
    }
    private Map<String,Object> lockOrder(TenantPrincipal p,UUID hotelId,UUID orderId) {
        var rows=jdbc.queryForList("select * from store_laundry_order where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:orderId for update",base(p,hotelId).addValue("orderId",orderId));
        if(rows.isEmpty()) throw missing("洗涤单不存在"); return rows.getFirst();
    }
    private BigDecimal owned(TenantPrincipal p,UUID hotelId,UUID itemId) {
        return jdbc.queryForObject("select quantity from store_inventory_balance where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId",base(p,hotelId).addValue("itemId",itemId),BigDecimal.class);
    }
    private void increment(TenantPrincipal p,UUID hotelId,UUID orderId) {
        jdbc.update("update store_laundry_order set row_version=row_version+1 where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:orderId",base(p,hotelId).addValue("orderId",orderId));
    }
    private TenantPrincipal prepare(String permission,UUID hotelId) {
        access.requirePermission(permission); TenantPrincipal p=access.principal(); database.apply(p.tenantId()); access.requireOrgScope(hotelId);
        if(jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:hotelId and unit_type='HOTEL' and status='ACTIVE'",base(p,hotelId),Long.class)!=1L) throw missing("门店不存在或已停用");
        return p;
    }
    private void validateDate(TenantPrincipal p,LocalDate date) {
        String timezone=jdbc.queryForObject("select timezone from tenant where id=:tenantId",new MapSqlParameterSource("tenantId",p.tenantId()),String.class);
        if(date.isAfter(LocalDate.now(ZoneId.of(timezone)))) throw new IllegalArgumentException("不能登记未来的实际送洗或回收日期");
    }
    private void requireActiveLinen(Map<String,Object> item,long expected) {
        if(!"LINEN".equals(item.get("category")) || !"ACTIVE".equals(item.get("status"))) throw conflict("仅启用的布草可配置单价或送洗");
        if(version(item)!=expected) throw conflict("物品配置已变化，请刷新核对后重试");
    }
    private void checkVersion(Map<String,Object> order,long expected) {
        if(!"ACTIVE".equals(order.get("status"))) throw conflict("洗涤单已取消");
        if(version(order)!=expected) throw conflict("洗涤单已变化，请刷新核对后重试");
    }
    private void sameRequest(Map<String,Object> row,UUID hotelId,String hash) {
        if(!hotelId.equals(row.get("hotel_org_unit_id")) || !hash.equals(row.get("request_hash"))) throw conflict("同一请求编号不能提交不同内容，请刷新核对原记录");
    }
    private void lockKey(TenantPrincipal p,String key,String kind) {
        if(key==null || key.isBlank() || key.length()>160) throw new IllegalArgumentException("缺少有效请求编号");
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,65))",new MapSqlParameterSource("key",p.tenantId()+":"+kind+":"+key));
    }
    private List<UUID> unique(List<UUID> ids) {
        if(new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("同一物品不能重复登记，请合并数量"); return ids;
    }
    private BigDecimal quantity(BigDecimal value,String label) {
        BigDecimal q=StoreItemUnits.exact(value,4,14,label); if(q.signum()<0) throw new IllegalArgumentException(label+"不能为负数"); return q;
    }
    private MapSqlParameterSource base(TenantPrincipal p,UUID hotelId) { return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId).addValue("actorId",p.actorId()); }
    private BigDecimal decimal(Object value) { return value instanceof BigDecimal d ? d : new BigDecimal(value.toString()); }
    private long version(Map<String,Object> value) { return ((Number)value.get("row_version")).longValue(); }
    private String text(String value) { return value==null ? "" : value.trim(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("无法序列化洗涤记录"); } }
    private String hash(Object value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private ResponseStatusException missing(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND,message); }
}
