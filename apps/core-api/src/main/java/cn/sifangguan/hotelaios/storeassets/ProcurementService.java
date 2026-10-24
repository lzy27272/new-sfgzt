package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.finance.FinanceCostDimensions;
import cn.sifangguan.hotelaios.finance.FinanceBudgetBridge;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Tenant-scoped procurement. Receipt approval and stock posting share one transaction. */
@Service
@Transactional
public class ProcurementService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final StoreResourceService stock;
    private final ObjectMapper mapper;
    private static final UUID NONE = new UUID(0,0);
    private static final List<String> ACTORS = List.of("manager_id","buyer_id","reviewer_id","finance_id","finance_director_id","deputy_id","general_id");
    private static final Set<String> EDITABLE = Set.of("DRAFT","RETURNED");
    private static final String SCOPE = "(:tenantScope or h.id in (:scopes))";

    public ProcurementService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,
                              StoreResourceService stock,ObjectMapper mapper) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.stock=stock;this.mapper=mapper;
    }
    public Map<String,Object> context() {
        var p=prepare();var b=base(p);
        var hotels=jdbc.queryForList("select h.id,h.name from org_unit h where h.tenant_id=:tenantId and h.unit_type='HOTEL' and h.status='ACTIVE' and "+SCOPE+" order by h.name,h.id",b);
        var out=new LinkedHashMap<String,Object>();out.put("hotels",hotels);
        out.put("canRequest",has(p,"procurement.request"));out.put("canConfigure",has(p,"procurement.configure"));
        if(has(p,"procurement.configure")) out.put("accounts",jdbc.queryForList("select id,display_name from user_account where tenant_id=:tenantId and status='ACTIVE' order by display_name,id",b));
        else out.put("accounts",List.of());
        return out;
    }
    public List<Map<String,Object>> todos() {
        var p=prepare();
        return jdbc.queryForList("""
            select o.id,o.hotel_id,o.title,o.status,o.source,h.name as hotel_name,
             case when o.status in ('DRAFT','RETURNED') then '待审核提交'
                  when o.status='SUBMITTED' then '待采购办理'
                  when c.reviewer_id=:actorId and exists(select 1 from procurement_receipt r where r.tenant_id=o.tenant_id and r.order_id=o.id and r.status='SUBMITTED') then '待复核入库'
                  when o.first_difference_at<=now()-interval '30 days' then '差异超30天未闭环' else '待收货或差异跟进' end as todo_label
            from procurement_order o join org_unit h on h.tenant_id=o.tenant_id and h.id=o.hotel_id
            join procurement_config c on c.tenant_id=o.tenant_id and c.hotel_id=o.hotel_id
            where o.tenant_id=:tenantId and o.status<>'COMPLETED' and
            """+SCOPE+"""
             and ((o.status in ('DRAFT','RETURNED') and ((o.source='LOW_STOCK' and c.manager_id=:actorId) or (o.source='REQUEST' and o.applicant_id=:actorId)))
               or (o.status='SUBMITTED' and c.buyer_id=:actorId)
               or (o.status in ('ORDERED','PARTIAL') and (:actorId in(c.manager_id,c.buyer_id,c.reviewer_id) or :actorId=any(c.receiver_ids)))
               or (o.first_difference_at<=now()-interval '30 days' and :actorId in(c.finance_id,c.finance_director_id,c.deputy_id,c.general_id)))
             and (:canRead or o.applicant_id=:actorId)
            order by o.first_difference_at nulls last,o.updated_at desc,o.id limit 100
            """,base(p).addValue("canRead",has(p,"procurement.read")||has(p,"procurement.configure")));
    }
    public Map<String,Object> hotel(UUID hotel) {
        var p=prepareHotel(hotel);var b=base(p).addValue("hotel",hotel);
        var out=new LinkedHashMap<String,Object>();var config=config(p.tenantId(),hotel,false);
        out.put("config",config);out.put("canConfigure",has(p,"procurement.configure"));
        out.put("canManage",is(p,config,"manager_id"));out.put("canPurchase",is(p,config,"buyer_id"));
        out.put("canReview",is(p,config,"reviewer_id"));out.put("canReceive",canReceive(p,config));
        out.put("canRequest",has(p,"procurement.request"));
        // Configuring a responsibility is not a permission grant. Explain missing setup instead of silently hiding work.
        out.put("items",jdbc.queryForList("""
            select i.id,i.sku,i.name,i.category,i.unit,i.specification,i.purchase_unit,i.purchase_factor,
             i.minimum_quantity,b.quantity as current_quantity,r.target_quantity,r.enabled as replenishment_enabled,
             coalesce(r.version,-1) as rule_version,
             coalesce((select sum(l.quantity-l.approved_quantity) from procurement_line l join procurement_order o
               on o.tenant_id=l.tenant_id and o.id=l.order_id where l.tenant_id=i.tenant_id and l.hotel_id=i.hotel_org_unit_id
                and l.item_id=i.id and o.status<>'COMPLETED'),0) as outstanding_quantity
            from store_resource_item i join store_inventory_balance b on b.tenant_id=i.tenant_id
              and b.hotel_org_unit_id=i.hotel_org_unit_id and b.item_id=i.id
            left join procurement_replenishment r on r.tenant_id=i.tenant_id and r.hotel_id=i.hotel_org_unit_id and r.item_id=i.id
            where i.tenant_id=:tenantId and i.hotel_org_unit_id=:hotel and i.status='ACTIVE' order by i.category,i.name,i.id
            """,b));out.put("financialMappingEnabled",FinanceCostDimensions.installed(jdbc));out.put("financialCategories",FinanceCostDimensions.categories(jdbc,p.tenantId(),hotel));out.put("financialDepartments",FinanceCostDimensions.departments(jdbc,p.tenantId(),hotel));return out;
    }
    public Map<String,Object> configure(UUID hotel,ProcurementModels.Configuration in) {
        var p=prepareHotel(hotel);access.requirePermission("procurement.configure");lock(p.tenantId(),"config:"+hotel);
        var old=config(p.tenantId(),hotel,false);expect(old.isEmpty()?-1:version(old),in.expectedVersion());
        var actors=new LinkedHashSet<>(List.of(in.managerId(),in.buyerId(),in.reviewerId(),in.financeId(),in.financeDirectorId(),in.deputyId(),in.generalId()));
        actors.addAll(in.receiverIds());
        if(jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and status='ACTIVE' and id in (:actors)",base(p).addValue("actors",actors),Integer.class)!=actors.size()) throw bad("责任人或预警接收人不存在、跨租户或已停用");
        var b=base(p).addValue("hotel",hotel).addValue("enabled",in.enabled()).addValue("manager",in.managerId())
            .addValue("buyer",in.buyerId()).addValue("reviewer",in.reviewerId()).addValue("finance",in.financeId())
            .addValue("director",in.financeDirectorId()).addValue("deputy",in.deputyId()).addValue("general",in.generalId())
            .addValue("receivers","{"+String.join(",",in.receiverIds().stream().distinct().map(UUID::toString).toList())+"}");
        jdbc.update("""
            insert into procurement_config(tenant_id,hotel_id,enabled,manager_id,buyer_id,reviewer_id,finance_id,finance_director_id,deputy_id,general_id,receiver_ids)
            values(:tenantId,:hotel,:enabled,:manager,:buyer,:reviewer,:finance,:director,:deputy,:general,cast(:receivers as uuid[]))
            on conflict(tenant_id,hotel_id) do update set enabled=excluded.enabled,manager_id=excluded.manager_id,buyer_id=excluded.buyer_id,
             reviewer_id=excluded.reviewer_id,finance_id=excluded.finance_id,finance_director_id=excluded.finance_director_id,
             deputy_id=excluded.deputy_id,general_id=excluded.general_id,receiver_ids=excluded.receiver_ids,
             version=procurement_config.version+1,updated_at=now()
            """,b);
        event(p.tenantId(),null,p.actorId(),"CONFIGURED",Map.of("hotelId",hotel,"before",old,"after",in));
        return hotel(hotel);
    }
    public Map<String,Object> rule(UUID hotel,UUID item,ProcurementModels.Replenishment in) {
        var p=prepareHotel(hotel);access.requirePermission("procurement.configure");lock(p.tenantId(),"replenish:"+hotel);
        var row=item(p.tenantId(),hotel,item);var target=amount(in.targetQuantity(),"补货目标");
        if(target.compareTo(decimal(row,"minimum_quantity"))<=0) throw bad("补货目标必须高于最低库存");
        var b=base(p).addValue("hotel",hotel).addValue("item",item);
        var prior=jdbc.queryForList("select version from procurement_replenishment where tenant_id=:tenantId and hotel_id=:hotel and item_id=:item",b);
        expect(prior.isEmpty()?-1:version(prior.getFirst()),in.expectedVersion());
        jdbc.update("""
            insert into procurement_replenishment(tenant_id,hotel_id,item_id,target_quantity,enabled) values(:tenantId,:hotel,:item,:target,:enabled)
            on conflict(tenant_id,hotel_id,item_id) do update set target_quantity=excluded.target_quantity,enabled=excluded.enabled,version=procurement_replenishment.version+1
            """,b.addValue("target",target).addValue("enabled",in.enabled()));
        event(p.tenantId(),null,p.actorId(),"REPLENISHMENT_CONFIGURED",Map.of("hotelId",hotel,"itemId",item,"rule",in));
        return hotel(hotel);
    }
    public List<Map<String,Object>> list(UUID hotel,String state,int page) {
        var p=prepare();if(hotel!=null)prepareHotel(hotel);if(page<0||page>10000)throw bad("页码无效");
        if(!Set.of("ALL","DRAFT","SUBMITTED","ORDERED","REVIEW","DIFFERENCE","COMPLETED").contains(state))throw bad("采购筛选无效");
        String filter=switch(state){case "REVIEW"->"exists(select 1 from procurement_receipt r where r.tenant_id=o.tenant_id and r.order_id=o.id and r.status='SUBMITTED')";
            case "DIFFERENCE"->"o.first_difference_at is not null and o.status<>'COMPLETED'";
            case "ALL"->"true";case "DRAFT"->"o.status in ('DRAFT','RETURNED')";
            case "ORDERED"->"o.status in ('ORDERED','PARTIAL')";default->"o.status=:state";};
        return jdbc.queryForList("""
            select o.*,h.name as hotel_name,
             (select count(*) from procurement_line l where l.tenant_id=o.tenant_id and l.order_id=o.id) as line_count,
             (select count(*) from procurement_receipt r where r.tenant_id=o.tenant_id and r.order_id=o.id and r.status='SUBMITTED') as pending_receipts,
             greatest(0,extract(day from now()-o.first_difference_at))::int as difference_days
            from procurement_order o join org_unit h on h.tenant_id=o.tenant_id and h.id=o.hotel_id
            where o.tenant_id=:tenantId and
            """+SCOPE+" and (cast(:hotel as uuid) is null or o.hotel_id=:hotel) and (:canRead or o.applicant_id=:actorId) and "+filter+
            " order by (o.first_difference_at<=now()-interval '30 days' and o.status<>'COMPLETED') desc nulls last,o.updated_at desc,o.id limit 51 offset :offset",
            base(p).addValue("hotel",hotel).addValue("canRead",has(p,"procurement.read")||has(p,"procurement.configure"))
                .addValue("state",state).addValue("offset",page*50));
    }
    public Map<String,Object> detail(UUID id) {
        var p=prepare();var row=order(p,id,false);var b=base(p).addValue("id",id);
        var result=new LinkedHashMap<>(row);var config=config(p.tenantId(),uuid(row,"hotel_id"),false);
        result.put("lines",jdbc.queryForList("""
            select l.*,b.quantity as current_quantity,b.inventory_value,
             coalesce((select sum(rl.accepted) from procurement_receipt_line rl join procurement_receipt r
               on r.tenant_id=rl.tenant_id and r.id=rl.receipt_id where rl.tenant_id=l.tenant_id and rl.line_id=l.id and r.status='SUBMITTED'),0) as pending_quantity
            from procurement_line l left join store_inventory_balance b on b.tenant_id=l.tenant_id and b.hotel_org_unit_id=l.hotel_id and b.item_id=l.item_id
            where l.tenant_id=:tenantId and l.order_id=:id order by l.item_id
            """,b));
        var receipts=jdbc.queryForList("select r.*,u.display_name as receiver_name from procurement_receipt r join user_account u on u.tenant_id=r.tenant_id and u.id=r.receiver_id where r.tenant_id=:tenantId and r.order_id=:id order by r.created_at desc,r.id",b);
        for(var receipt:receipts) receipt.put("lines",jdbc.queryForList("""
            select rl.*,l.item_name,l.line_kind,l.base_unit,m.quantity_before,m.quantity_after,coalesce(rl.accepted_value,m.value_delta) value_delta,m.unit_cost_snapshot
            from procurement_receipt_line rl join procurement_line l on l.tenant_id=rl.tenant_id and l.id=rl.line_id
            left join store_inventory_movement m on m.tenant_id=rl.tenant_id and m.id=rl.movement_id
            where rl.tenant_id=:tenantId and rl.receipt_id=:receipt order by l.item_id
            """,base(p).addValue("receipt",receipt.get("id"))));
        result.put("receipts",receipts);
        result.put("attachments",jdbc.queryForList("select id,receipt_id,original_name,media_type,size_bytes,scan_status,created_at,purged_at from procurement_attachment where tenant_id=:tenantId and order_id=:id order by created_at,id",b));
        var events=jdbc.queryForList("select e.event_type,e.created_at,e.detail::text as detail,u.display_name as actor_name from procurement_event e left join user_account u on u.tenant_id=e.tenant_id and u.id=e.actor_id where e.tenant_id=:tenantId and e.order_id=:id order by e.created_at,e.id",b);
        for(var e:events)e.put("detail",parse(Objects.toString(e.get("detail"))));result.put("events",events);
        result.put("notifications",jdbc.queryForList("""
            select n.title,n.created_at,u.display_name as recipient_name,n.read_at,
              case when u.status<>'ACTIVE' then 'INACTIVE'
                when exists(select 1 from notification_delivery d where d.tenant_id=n.tenant_id and d.notification_id=n.id and d.channel='WECHAT' and d.status='SENT') then 'SENT'
                when not exists(select 1 from wecom_user_binding w where w.tenant_id=n.tenant_id and w.account_id=n.recipient_account_id and w.status='ACTIVE') then 'UNBOUND'
                else coalesce((select d.status from notification_delivery d where d.tenant_id=n.tenant_id and d.notification_id=n.id and d.channel='WECHAT' order by d.created_at desc,d.id desc limit 1),'NOT_QUEUED') end as delivery_status
            from notification n join user_account u on u.tenant_id=n.tenant_id and u.id=n.recipient_account_id
            where n.tenant_id=:tenantId and n.source_type='PROCUREMENT' and n.source_id=:id order by n.created_at desc,n.id limit 100
            """,b));
        result.put("canEdit",EDITABLE.contains(row.get("status")) && (row.get("source").equals("LOW_STOCK")?is(p,config,"manager_id"):p.actorId().equals(row.get("applicant_id"))));
        result.put("canPurchase",is(p,config,"buyer_id"));result.put("canReview",is(p,config,"reviewer_id"));result.put("canReceive",canReceive(p,config));
        result.put("config",config);return result;
    }
    public Map<String,Object> create(UUID hotel,ProcurementModels.Request in) {
        var p=prepareHotel(hotel);access.requirePermission("procurement.request");config(p.tenantId(),hotel,true);
        UUID id=command(p,in.commandId(),"create:"+hotel,in,()->{
            insertOrder(p.tenantId(),hotel,in.commandId(),p.actorId(),"REQUEST",in.title());
            replaceLines(p.tenantId(),hotel,in.commandId(),in.lines());saveHeader(p,in.commandId(),in);
            event(p.tenantId(),in.commandId(),p.actorId(),"DRAFT_CREATED",in);return in.commandId();});
        return detail(id);
    }
    public Map<String,Object> save(UUID id,ProcurementModels.Request in) {
        var p=prepare();command(p,in.commandId(),"save:"+id,in,()->{
            var row=order(p,id,true);editable(p,row);expect(version(row),in.expectedVersion());
            replaceLines(p.tenantId(),uuid(row,"hotel_id"),id,in.lines());saveHeader(p,id,in);
            event(p.tenantId(),id,p.actorId(),"DRAFT_SAVED",in);return id;});return detail(id);
    }
    public Map<String,Object> submit(UUID id,ProcurementModels.Command in) {
        var p=prepare();command(p,in.commandId(),"submit:"+id,in,()->{
            var row=order(p,id,true);editable(p,row);expect(version(row),in.expectedVersion());var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            var lines=lines(p.tenantId(),id);if(lines.isEmpty())throw bad("采购清单至少有一项物品");
            for(var l:lines){if(!"INVENTORY".equals(text(l,"line_kind")))continue;var current=item(p.tenantId(),uuid(row,"hotel_id"),uuid(l,"item_id"));
                var units=StoreItemUnits.from(current);
                if(!l.get("base_unit").equals(current.get("unit"))||units.factorFor(text(l,"input_unit")).compareTo(decimal(l,"factor"))!=0)
                    throw conflict("物品单位配置变化，请重新保存并核对采购数量");}
            validateFinancialLines(p.tenantId(),uuid(row,"hotel_id"),id);updateStatus(p.tenantId(),id,"SUBMITTED");new FinanceBudgetBridge(jdbc).procurement(p,id,"SUBMIT");event(p.tenantId(),id,p.actorId(),"SUBMITTED",Map.of("lines",lines));
            notify(p.tenantId(),uuid(conf,"buyer_id"),id,"SUBMITTED","采购待办理",text(row,"title"),"submit:"+id+":"+version(row));return id;});return detail(id);
    }
    public Map<String,Object> purchase(UUID id,ProcurementModels.Purchase in) {
        var p=prepare();command(p,in.commandId(),"purchase:"+id,in,()->{
            var row=order(p,id,true);expect(version(row),in.expectedVersion());requireState(row,"SUBMITTED");
            var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);requireActor(p,conf,"buyer_id");
            // Handler is an explicit store responsibility, not a caller-supplied impersonation.
            if(!in.buyerId().equals(conf.get("buyer_id")))throw bad("经办人须为本门店配置的采购负责人");
            validateFinancialLines(p.tenantId(),uuid(row,"hotel_id"),id);var lines=lines(p.tenantId(),id);var prices=new HashMap<UUID,BigDecimal>();
            for(var price:in.prices())if(prices.put(price.lineId(),amount(price.unitPrice(),"采购单价"))!=null)throw bad("采购价格重复");
            if(prices.size()!=lines.size())throw bad("请逐项填写采购单价");
            for(var line:lines){var price=prices.get(uuid(line,"id"));if(price==null)throw bad("采购价格不属于本单");
                jdbc.update("update procurement_line set unit_price=:price where tenant_id=:tenantId and id=:line",base(p).addValue("price",price).addValue("line",line.get("id")));}
            jdbc.update("update procurement_order set status='ORDERED',supplier=:supplier,expected_on=:date,buyer_id=:buyer,ordered_at=now(),version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",
                base(p).addValue("id",id).addValue("supplier",in.supplier().trim()).addValue("date",in.expectedOn()).addValue("buyer",in.buyerId()));
            new FinanceBudgetBridge(jdbc).procurement(p,id,"APPROVE");event(p.tenantId(),id,p.actorId(),"PURCHASED",in);
            var recipients=new LinkedHashSet<UUID>(receiverIds(conf));recipients.add(uuid(conf,"manager_id"));
            for(UUID recipient:recipients)notify(p.tenantId(),recipient,id,"ORDERED","采购已办理，等待到货",text(row,"title"),"ordered:"+id);return id;});return detail(id);
    }
    public Map<String,Object> returnRequest(UUID id,ProcurementModels.Command in) {
        var p=prepare();command(p,in.commandId(),"return:"+id,in,()->{var row=order(p,id,true);expect(version(row),in.expectedVersion());requireState(row,"SUBMITTED");
            requireActor(p,config(p.tenantId(),uuid(row,"hotel_id"),true),"buyer_id");requireNote(in.note());updateStatus(p.tenantId(),id,"RETURNED");
            new FinanceBudgetBridge(jdbc).procurement(p,id,"RELEASE");event(p.tenantId(),id,p.actorId(),"REQUEST_RETURNED",in);notify(p.tenantId(),uuid(row,"applicant_id"),id,"RETURNED","采购申请退回补充",in.note(),"returned:"+in.commandId());return id;});return detail(id);
    }
    public Map<String,Object> saveReceipt(UUID id,UUID receiptId,ProcurementModels.Receipt in) {
        var p=prepare();command(p,in.commandId(),"receipt:"+id+":"+receiptId,in,()->{
            var row=order(p,id,true);requireState(row,"ORDERED","PARTIAL");var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            if(!canReceive(p,conf))throw forbidden();var b=base(p).addValue("id",id).addValue("receipt",receiptId);
            var prior=jdbc.queryForList("select * from procurement_receipt where tenant_id=:tenantId and order_id=:id and id=:receipt for update",b);
            if(prior.isEmpty()){
                expect(-1,in.expectedVersion());jdbc.update("insert into procurement_receipt(id,tenant_id,order_id,receiver_id,status) values(:receipt,:tenantId,:id,:actorId,'DRAFT')",b);
            }else{var r=prior.getFirst();if(!r.get("receiver_id").equals(p.actorId()))throw forbidden();requireState(r,"DRAFT","RETURNED");expect(version(r),in.expectedVersion());}
            var byId=new HashMap<UUID,Map<String,Object>>();for(var line:lines(p.tenantId(),id))byId.put(uuid(line,"id"),line);
            var seen=new HashSet<UUID>();for(var line:in.lines()){
                if(!seen.add(line.lineId())||!byId.containsKey(line.lineId()))throw bad("收货物品重复或不属于本采购单");
                var arrived=amount(line.arrived(),"到货数量");var accepted=amount(line.accepted(),"合格数量");var rejected=amount(line.rejected(),"拒收数量");
                if(arrived.signum()<=0||accepted.add(rejected).compareTo(arrived)!=0)throw bad("到货数量必须等于合格数量加拒收数量");
            }
            jdbc.update("delete from procurement_receipt_line where tenant_id=:tenantId and receipt_id=:receipt",b);
            for(var line:in.lines())jdbc.update("insert into procurement_receipt_line(tenant_id,order_id,receipt_id,line_id,arrived,accepted,rejected) values(:tenantId,:id,:receipt,:line,:arrived,:accepted,:rejected)",
                base(p).addValue("id",id).addValue("receipt",receiptId).addValue("line",line.lineId()).addValue("arrived",line.arrived()).addValue("accepted",line.accepted()).addValue("rejected",line.rejected()));
            jdbc.update("update procurement_receipt set status='DRAFT',note=:note,version=version+1 where tenant_id=:tenantId and id=:receipt",b.addValue("note",clean(in.note())));
            event(p.tenantId(),id,p.actorId(),"RECEIPT_SAVED",Map.of("receiptId",receiptId,"content",in));return id;});return detail(id);
    }
    public Map<String,Object> submitReceipt(UUID id,UUID receiptId,ProcurementModels.Command in) {
        var p=prepare();command(p,in.commandId(),"receipt-submit:"+receiptId,in,()->{
            var row=order(p,id,true);requireState(row,"ORDERED","PARTIAL");var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            if(!canReceive(p,conf))throw forbidden();var r=receipt(p.tenantId(),id,receiptId);if(!r.get("receiver_id").equals(p.actorId()))throw forbidden();
            requireState(r,"DRAFT");expect(version(r),in.expectedVersion());var b=base(p).addValue("receipt",receiptId).addValue("id",id);
            if(jdbc.queryForObject("select count(*) from procurement_attachment where tenant_id=:tenantId and order_id=:id and receipt_id=:receipt and media_type in ('image/jpeg','image/png') and purged_at is null",b,Integer.class)<1)throw bad("请至少上传一张实际收货照片，再提交复核");
            var receiptLines=receiptLines(p.tenantId(),receiptId);
            if(receiptLines.isEmpty())throw bad("请填写收货物品");
            for(var line:receiptLines){
                BigDecimal pending=jdbc.queryForObject("""
                    select coalesce(sum(rl.accepted),0) from procurement_receipt_line rl join procurement_receipt r on r.tenant_id=rl.tenant_id and r.id=rl.receipt_id
                    where rl.tenant_id=:tenantId and rl.line_id=:line and r.status='SUBMITTED'
                    """,base(p).addValue("line",line.get("line_id")),BigDecimal.class);
                if(decimal(line,"approved_quantity").add(pending).add(decimal(line,"accepted")).compareTo(decimal(line,"quantity"))>0)throw bad("合格收货累计超过采购数量（含其他待复核批次），请核对或拒收多到物品");
                if(decimal(line,"rejected").signum()>0)requireNote(text(r,"note"));
            }
            jdbc.update("update procurement_receipt set status='SUBMITTED',submitted_at=now(),version=version+1 where tenant_id=:tenantId and id=:receipt",b);
            jdbc.update("update procurement_order set first_difference_at=coalesce(first_difference_at,now()),version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
            event(p.tenantId(),id,p.actorId(),"RECEIPT_SUBMITTED",Map.of("receiptId",receiptId,"lines",receiptLines));
            for(UUID recipient:new LinkedHashSet<>(List.of(uuid(conf,"buyer_id"),uuid(conf,"reviewer_id"))))
                notify(p.tenantId(),recipient,id,"RECEIPT_SUBMITTED","已收货，待复核入库",text(row,"title"),"receipt:"+receiptId+":"+version(r));return id;});return detail(id);
    }
    public Map<String,Object> review(UUID id,UUID receiptId,ProcurementModels.Review in) {
        var p=prepare();command(p,in.commandId(),"review:"+receiptId,in,()->{
            var row=order(p,id,true);requireState(row,"ORDERED","PARTIAL");var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            requireActor(p,conf,"reviewer_id");var r=receipt(p.tenantId(),id,receiptId);requireState(r,"SUBMITTED");expect(version(r),in.expectedVersion());
            var b=base(p).addValue("receipt",receiptId).addValue("id",id).addValue("note",clean(in.note()));
            var snapshots=new ArrayList<Map<String,Object>>();
            if(in.approved()){
                // Stable item order across all inventory writers avoids multi-item lock inversions.
                for(var line:receiptLines(p.tenantId(),receiptId)){
                    var accepted=decimal(line,"accepted");if(accepted.signum()==0)continue;
                    if(decimal(line,"approved_quantity").add(accepted).compareTo(decimal(line,"quantity"))>0)throw conflict("累计入库不能超过采购数量");
                    // Cumulative rounding ensures split deliveries sum to the exact ordered value.
                    var approved=decimal(line,"approved_quantity");var price=decimal(line,"unit_price");var factor=decimal(line,"factor");
                    var value=approved.add(accepted).multiply(price).divide(factor,4,RoundingMode.HALF_UP)
                        .subtract(approved.multiply(price).divide(factor,4,RoundingMode.HALF_UP));
                    UUID movementId=null;
                    if("INVENTORY".equals(text(line,"line_kind"))){
                        var movement=stock.postProcurementReceipt(p,uuid(row,"hotel_id"),receiptId,uuid(line,"item_id"),text(line,"base_unit"),accepted,value);
                        snapshots.add(movement);movementId=uuid(movement,"id");
                    }else snapshots.add(Map.of("lineId",line.get("line_id"),"lineKind",line.get("line_kind"),"acceptedQuantity",accepted,"acceptedValue",value,"inventoryPosted",false));
                    jdbc.update("update procurement_receipt_line set movement_id=:movement,accepted_value=:value where tenant_id=:tenantId and receipt_id=:receipt and line_id=:line",
                        base(p).addValue("receipt",receiptId).addValue("line",line.get("line_id")).addValue("movement",movementId).addValue("value",value));
                    jdbc.update("update procurement_line set approved_quantity=approved_quantity+:quantity where tenant_id=:tenantId and id=:line",
                        base(p).addValue("line",line.get("line_id")).addValue("quantity",accepted));
                }
            }else requireNote(in.note());
            jdbc.update("update procurement_receipt set status=:status,reviewer_id=:actorId,review_note=:note,reviewed_at=now(),version=version+1 where tenant_id=:tenantId and id=:receipt",
                b.addValue("status",in.approved()?"APPROVED":"RETURNED"));
            boolean complete=in.approved() && readyToComplete(p.tenantId(),id);
            jdbc.update("update procurement_order set status=:status,completed_at=case when :complete then now() else null end,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",
                b.addValue("status",complete?"COMPLETED":"PARTIAL").addValue("complete",complete));
            if(in.approved())new FinanceBudgetBridge(jdbc).procurement(p,id,"REFRESH");event(p.tenantId(),id,p.actorId(),in.approved()?"RECEIPT_APPROVED":"RECEIPT_RETURNED",Map.of("receiptId",receiptId,"note",clean(in.note()),"movements",snapshots,"completed",complete));
            for(UUID recipient:new LinkedHashSet<>(List.of(uuid(r,"receiver_id"),uuid(conf,"manager_id"),uuid(conf,"buyer_id"))))
                notify(p.tenantId(),recipient,id,complete?"COMPLETED":"REVIEWED",complete?"采购已全部入库，流程完成":in.approved()?"本批已入库，采购数量尚未全部对齐":"收货复核退回补充",text(row,"title"),"review:"+in.commandId());return id;});return detail(id);
    }

    public Map<String,Object> voidReceiptDraft(UUID id,UUID receiptId,ProcurementModels.Command in) {
        var p=prepare();command(p,in.commandId(),"void-draft:"+receiptId,in,()->{
            var row=order(p,id,true);requireState(row,"ORDERED","PARTIAL");
            var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            if(!canReceive(p,conf))throw forbidden();
            var r=receipt(p.tenantId(),id,receiptId);
            if(!p.actorId().equals(r.get("receiver_id")))throw forbidden();
            requireState(r,"DRAFT");expect(version(r),in.expectedVersion());requireNote(in.note());
            if(r.get("submitted_at")!=null)throw conflict("已提交过的收货不能撤销，请按退回复核流程核对");
            var b=base(p).addValue("id",id).addValue("receipt",receiptId).addValue("note",in.note().trim());
            jdbc.update("update procurement_receipt set status='VOIDED',review_note=:note,version=version+1 where tenant_id=:tenantId and id=:receipt",b);
            boolean complete=readyToComplete(p.tenantId(),id);
            jdbc.update("update procurement_order set status=case when :complete then 'COMPLETED' else status end,completed_at=case when :complete then now() else completed_at end,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b.addValue("complete",complete));
            event(p.tenantId(),id,p.actorId(),"RECEIPT_DRAFT_VOIDED",Map.of("receiptId",receiptId,"note",in.note().trim(),"completed",complete));
            if(complete)for(UUID recipient:new LinkedHashSet<>(List.of(uuid(conf,"manager_id"),uuid(conf,"buyer_id"))))
                notify(p.tenantId(),recipient,id,"COMPLETED","采购已全部入库，流程完成",text(row,"title"),"void-close:"+in.commandId());
            return id;
        });return detail(id);
    }
    private boolean readyToComplete(UUID tenant,UUID order) {
        var b=new MapSqlParameterSource("tenantId",tenant).addValue("id",order);
        return jdbc.queryForObject("select count(*) from procurement_line where tenant_id=:tenantId and order_id=:id and quantity<>approved_quantity",b,Integer.class)==0
            && jdbc.queryForObject("select count(*) from procurement_receipt where tenant_id=:tenantId and order_id=:id and status in ('DRAFT','SUBMITTED','RETURNED')",b,Integer.class)==0;
    }

    public Map<String,Object> authorizeAttachment(UUID order,UUID receipt,boolean write) {
        var p=prepare();var row=order(p,order,write);
        if(write){var conf=config(p.tenantId(),uuid(row,"hotel_id"),true);
            if(receipt==null){requireState(row,"SUBMITTED","ORDERED","PARTIAL");requireActor(p,conf,"buyer_id");}
            else{var r=receipt(p.tenantId(),order,receipt);requireState(r,"DRAFT","RETURNED");if(!canReceive(p,conf)||!p.actorId().equals(r.get("receiver_id")))throw forbidden();}}
        return row;
    }
    public Map<String,Object> registerAttachment(UUID order,UUID receipt,UUID id,String name,String type,long size,String hash,String key,String scan) {
        authorizeAttachment(order,receipt,true);var p=access.principal();
        jdbc.update("insert into procurement_attachment(id,tenant_id,order_id,receipt_id,original_name,media_type,size_bytes,sha256,object_key,scan_status,uploaded_by) values(:id,:tenantId,:order,:receipt,:name,:type,:size,:hash,:key,:scan,:actorId)",
            base(p).addValue("id",id).addValue("order",order).addValue("receipt",receipt).addValue("name",name).addValue("type",type).addValue("size",size).addValue("hash",hash).addValue("key",key).addValue("scan",scan));
        event(p.tenantId(),order,p.actorId(),"ATTACHMENT_UPLOADED",Map.of("attachmentId",id,"name",name,"sha256",hash));return Map.of("id",id,"original_name",name,"media_type",type);
    }
    public Map<String,Object> attachment(UUID order,UUID id) {
        authorizeAttachment(order,null,false);var p=access.principal();
        var rows=jdbc.queryForList("select * from procurement_attachment where tenant_id=:tenantId and order_id=:order and id=:id",base(p).addValue("order",order).addValue("id",id));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }

    /** Background entry point accepts a configured tenant, never a caller-supplied HTTP tenant. */
    public Map<String,Integer> automate(UUID tenant) {
        database.apply(tenant);int generated=0,alerts=0;
        for(var conf:jdbc.queryForList("select * from procurement_config where tenant_id=:tenantId and enabled order by hotel_id",new MapSqlParameterSource("tenantId",tenant))){
            UUID hotel=uuid(conf,"hotel_id");lock(tenant,"replenish:"+hotel);
            var configs=config(tenant,hotel,true);
            var candidates=jdbc.queryForList("""
                select i.*,b.quantity as current_quantity,r.target_quantity from procurement_replenishment r
                join store_resource_item i on i.tenant_id=r.tenant_id and i.hotel_org_unit_id=r.hotel_id and i.id=r.item_id
                join store_inventory_balance b on b.tenant_id=r.tenant_id and b.hotel_org_unit_id=r.hotel_id and b.item_id=r.item_id
                where r.tenant_id=:tenantId and r.hotel_id=:hotel and r.enabled and i.status='ACTIVE' and b.quantity<=i.minimum_quantity order by i.id
                """,new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel));
            UUID draft=null;var existing=jdbc.queryForList("select id,edited from procurement_order where tenant_id=:tenantId and hotel_id=:hotel and source='LOW_STOCK' and status='DRAFT' for update",new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel));
            if(!existing.isEmpty()){if(Boolean.TRUE.equals(existing.getFirst().get("edited")))continue;draft=uuid(existing.getFirst(),"id");}
            int added=0;
            for(var item:candidates){
                var b=new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel).addValue("item",item.get("id"));
                BigDecimal outstanding=jdbc.queryForObject("select coalesce(sum(l.quantity-l.approved_quantity),0) from procurement_line l join procurement_order o on o.tenant_id=l.tenant_id and o.id=l.order_id where l.tenant_id=:tenantId and l.hotel_id=:hotel and l.item_id=:item and o.status<>'COMPLETED'",b,BigDecimal.class);
                BigDecimal needed=decimal(item,"target_quantity").subtract(decimal(item,"current_quantity")).subtract(outstanding);
                if(needed.signum()<=0)continue;
                // One open item reservation suppresses repeated replenishment, even while manager edits.
                if(outstanding.signum()>0)continue;
                if(draft==null){draft=UUID.randomUUID();insertOrder(tenant,hotel,draft,uuid(configs,"manager_id"),"LOW_STOCK","低库存自动补货");generated++;}
                var units=StoreItemUnits.from(item);BigDecimal quantity=units.purchaseUnit().equals(units.baseUnit())?needed:needed.divide(units.purchaseFactor(),0,RoundingMode.CEILING);
                insertLine(tenant,hotel,draft,item,quantity,units.purchaseUnit());added++;
            }
            if(added>0){jdbc.update("update procurement_order set version=version+1,updated_at=now() where tenant_id=:tenant and id=:id",new MapSqlParameterSource("tenant",tenant).addValue("id",draft));
                event(tenant,draft,null,"LOW_STOCK_GENERATED",Map.of("added",added));notify(tenant,uuid(configs,"manager_id"),draft,"LOW_STOCK","低库存采购待审核","请核对数量，可补充其他采购物品","low-stock:"+draft);}
        }
        // Skip already-notified recipients, not merely alerted orders: reconfiguration must reach new recipients,
        // and an old page of unresolved differences must not starve newer orders beyond the batch limit.
        var overdue=jdbc.queryForList("""
            select o.* from procurement_order o where o.tenant_id=:tenantId and o.status<>'COMPLETED'
             and o.first_difference_at<=now()-interval '30 days' and exists (
              select 1 from procurement_config c cross join lateral (values
                ('finance_id',c.finance_id),('finance_director_id',c.finance_director_id),('deputy_id',c.deputy_id),('general_id',c.general_id)
              ) target(role_name,account_id)
              where c.tenant_id=o.tenant_id and c.hotel_id=o.hotel_id and not exists (
                select 1 from notification n where n.tenant_id=o.tenant_id and n.recipient_account_id=target.account_id
                 and n.idempotency_key='procurement:overdue-30:'||o.id::text||':'||target.role_name))
            order by o.first_difference_at,o.id limit 500 for update of o
            """,new MapSqlParameterSource("tenantId",tenant));
        for(var row:overdue){var conf=config(tenant,uuid(row,"hotel_id"),false);if(conf.isEmpty())continue;
            var targets=new LinkedHashMap<String,Object>();
            for(String role:List.of("finance_id","finance_director_id","deputy_id","general_id")){
                UUID recipient=uuid(conf,role);targets.put(role,recipient);
                notify(tenant,recipient,uuid(row,"id"),"OVERDUE_30_DAYS","采购数量差异超过30天未闭环",text(row,"title")+"；请核对采购、入库及差异跟进记录。","overdue-30:"+row.get("id")+":"+role);
            }
            if(row.get("alerted_at")==null){jdbc.update("update procurement_order set alerted_at=now() where tenant_id=:tenantId and id=:id",new MapSqlParameterSource("tenantId",tenant).addValue("id",row.get("id")));
                event(tenant,uuid(row,"id"),null,"OVERDUE_30_DAYS",targets);alerts++;}
        }
        return Map.of("generated",generated,"alerts",alerts);
    }

    private TenantPrincipal prepare(){access.requireAnyPermission("procurement.read","procurement.request","procurement.configure");var p=access.principal();database.apply(p.tenantId());return p;}
    private TenantPrincipal prepareHotel(UUID hotel){var p=prepare();access.requireOrgScope(hotel);
        if(jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:hotel and unit_type='HOTEL' and status='ACTIVE'",base(p).addValue("hotel",hotel),Integer.class)!=1)throw missing();return p;}
    private MapSqlParameterSource base(TenantPrincipal p){return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("actorId",p.actorId()).addValue("tenantScope",p.hasTenantScope()).addValue("scopes",p.orgScopes().isEmpty()?List.of(NONE):p.orgScopes());}
    private Map<String,Object> config(UUID tenant,UUID hotel,boolean required){
        var rows=jdbc.queryForList("select * from procurement_config where tenant_id=:tenantId and hotel_id=:hotel",new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel));
        if(rows.isEmpty()){if(required)throw conflict("请先配置门店采购负责人、收货人及四类预警接收人");return Map.of();}
        var row=rows.getFirst();try{if(row.get("receiver_ids") instanceof java.sql.Array array)row.put("receiver_ids",Arrays.asList((UUID[])array.getArray()));}catch(java.sql.SQLException e){throw new IllegalStateException(e);}
        if(required&&!Boolean.TRUE.equals(row.get("enabled")))throw conflict("该门店采购流程尚未启用");return row;
    }
    private Map<String,Object> order(TenantPrincipal p,UUID id,boolean lock){
        var rows=jdbc.queryForList("select * from procurement_order where tenant_id=:tenantId and id=:id"+(lock?" for update":""),base(p).addValue("id",id));if(rows.isEmpty())throw missing();
        var row=rows.getFirst();access.requireOrgScope(uuid(row,"hotel_id"));
        if(!has(p,"procurement.read")&&!has(p,"procurement.configure")&&!p.actorId().equals(row.get("applicant_id")))throw forbidden();return row;
    }
    private Map<String,Object> item(UUID tenant,UUID hotel,UUID id){var rows=jdbc.queryForList("select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotel and id=:id and status='ACTIVE'",new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel).addValue("id",id));if(rows.isEmpty())throw bad("物品不存在、已停用或不属于本门店");return rows.getFirst();}
    private void editable(TenantPrincipal p,Map<String,Object> row){requireState(row,"DRAFT","RETURNED");var c=config(p.tenantId(),uuid(row,"hotel_id"),true);
        if(row.get("source").equals("LOW_STOCK"))requireActor(p,c,"manager_id");else{access.requirePermission("procurement.request");if(!p.actorId().equals(row.get("applicant_id")))throw forbidden();}}
    private List<Map<String,Object>> lines(UUID tenant,UUID order){return jdbc.queryForList("select * from procurement_line where tenant_id=:tenantId and order_id=:id order by item_id",new MapSqlParameterSource("tenantId",tenant).addValue("id",order));}
    private Map<String,Object> receipt(UUID tenant,UUID order,UUID id){var rows=jdbc.queryForList("select * from procurement_receipt where tenant_id=:tenantId and order_id=:order and id=:id for update",new MapSqlParameterSource("tenantId",tenant).addValue("order",order).addValue("id",id));if(rows.isEmpty())throw missing();return rows.getFirst();}
    private List<Map<String,Object>> receiptLines(UUID tenant,UUID id){return jdbc.queryForList("select rl.*,l.item_id,l.line_kind,l.base_unit,l.quantity,l.approved_quantity,l.factor,l.unit_price from procurement_receipt_line rl join procurement_line l on l.tenant_id=rl.tenant_id and l.id=rl.line_id where rl.tenant_id=:tenantId and rl.receipt_id=:id order by l.item_id nulls last,l.id",new MapSqlParameterSource("tenantId",tenant).addValue("id",id));}
    private void insertOrder(UUID tenant,UUID hotel,UUID id,UUID actor,String source,String title){jdbc.update("insert into procurement_order(id,tenant_id,hotel_id,source,status,applicant_id,title) values(:id,:tenantId,:hotel,:source,'DRAFT',:actor,:title)",new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel).addValue("id",id).addValue("actor",actor).addValue("source",source).addValue("title",title.trim()));}
    private void replaceLines(UUID tenant,UUID hotel,UUID order,List<ProcurementModels.Line> lines){var seen=new HashSet<UUID>();for(var line:lines)if(line.itemId()!=null&&!seen.add(line.itemId()))throw bad("同一物品不能重复添加，请合并数量");
        jdbc.update("delete from procurement_line where tenant_id=:tenantId and order_id=:id",new MapSqlParameterSource("tenantId",tenant).addValue("id",order));
        for(var line:lines){String kind=line.lineKind()==null?"INVENTORY":line.lineKind();UUID financialLine;
            if("INVENTORY".equals(kind)){if(line.itemId()==null)throw bad("存货采购须选择现有品目");financialLine=insertLine(tenant,hotel,order,item(tenant,hotel,line.itemId()),line.quantity(),line.unit());}
            else{if(!Set.of("SERVICE","ASSET","PROJECT").contains(kind)||line.itemId()!=null||clean(line.description()).isBlank())throw bad("服务、资产、工程采购须填写真实事项且不得伪造库存品目");BigDecimal quantity=amount(line.quantity(),"履约验收数量");if(quantity.signum()<=0)throw bad("履约数量须大于0");
                financialLine=UUID.randomUUID();jdbc.update("insert into procurement_line(id,tenant_id,hotel_id,order_id,item_id,line_kind,item_name,sku,specification,base_unit,input_unit,factor,input_quantity,quantity) values(:id,:tenantId,:hotel,:order,null,:kind,:description,'',:spec,:unit,:unit,1,:quantity,:quantity)",new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel).addValue("order",order).addValue("id",financialLine).addValue("kind",kind).addValue("description",line.description().trim()).addValue("spec",clean(line.specification())).addValue("unit",line.unit()).addValue("quantity",quantity));
            }
            if(FinanceCostDimensions.installed(jdbc)){if(line.budgetUnitPrice()!=null&&line.budgetUnitPrice().signum()<0)throw bad("申请预估单价不可为负");jdbc.update("update procurement_line set budget_unit_price=:price where tenant_id=:tenantId and id=:line",new MapSqlParameterSource("tenantId",tenant).addValue("line",financialLine).addValue("price",line.budgetUnitPrice()));String code=clean(line.financialCategoryCode());UUID department=line.departmentId();if(!code.isBlank()||department!=null){FinanceCostDimensions.validate(jdbc,tenant,hotel,code,department,financialTreatments(kind));jdbc.update("update procurement_line set financial_category_code=:code,financial_department_id=:department where tenant_id=:tenantId and id=:line",new MapSqlParameterSource("tenantId",tenant).addValue("line",financialLine).addValue("code",code).addValue("department",department));}}
            var project=FinanceCostDimensions.freezeProject(jdbc,access.principal(),hotel,line.projectKind(),line.projectId());if(FinanceCostDimensions.projectsInstalled(jdbc))jdbc.update("update procurement_line set project_kind=:projectKind,project_id=:projectId,project_key=:projectKey,project_snapshot=cast(:projectSnapshot as jsonb) where tenant_id=:tenantId and id=:line",new MapSqlParameterSource("tenantId",tenant).addValue("line",financialLine).addValue("projectKind",project.get("kind")).addValue("projectId",project.get("id")).addValue("projectKey",project.get("key")).addValue("projectSnapshot",json(project)));
        }}
    private UUID insertLine(UUID tenant,UUID hotel,UUID order,Map<String,Object> item,BigDecimal quantity,String unit){var units=StoreItemUnits.from(item);String selected=units.selectedUnit(unit);var factor=units.factorFor(selected);var input=amount(quantity,"采购数量");if(input.signum()<=0)throw bad("采购数量须大于0");UUID line=UUID.randomUUID();
        jdbc.update("""
            insert into procurement_line(id,tenant_id,hotel_id,order_id,item_id,item_name,sku,specification,base_unit,input_unit,factor,input_quantity,quantity)
            values(:id,:tenantId,:hotel,:order,:item,:name,:sku,:spec,:base,:unit,:factor,:input,:quantity)
            """,new MapSqlParameterSource("tenantId",tenant).addValue("hotel",hotel).addValue("order",order).addValue("id",line).addValue("item",item.get("id"))
            .addValue("name",item.get("name")).addValue("sku",item.get("sku")).addValue("spec",clean(text(item,"specification"))).addValue("base",units.baseUnit()).addValue("unit",selected).addValue("factor",factor).addValue("input",input).addValue("quantity",units.baseQuantity(input,selected)));return line;}
    private Set<String> financialTreatments(String kind){return switch(kind){case "SERVICE"->Set.of("EXPENSE");case "ASSET","PROJECT"->Set.of("ASSET");default->Set.of("EXPENSE","INVENTORY");};}
    private void validateFinancialLines(UUID tenant,UUID hotel,UUID order){if(!FinanceCostDimensions.installed(jdbc))return;for(var line:lines(tenant,order))FinanceCostDimensions.validate(jdbc,tenant,hotel,text(line,"financial_category_code"),uuid(line,"financial_department_id"),financialTreatments(text(line,"line_kind")));}
    private void saveHeader(TenantPrincipal p,UUID id,ProcurementModels.Request in){jdbc.update("update procurement_order set title=:title,note=:note,expected_on=:date,edited=true,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id).addValue("title",in.title().trim()).addValue("note",clean(in.note())).addValue("date",in.expectedOn()));}
    private void updateStatus(UUID tenant,UUID id,String state){jdbc.update("update procurement_order set status=:state,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",new MapSqlParameterSource("tenantId",tenant).addValue("id",id).addValue("state",state));}
    private UUID command(TenantPrincipal p,UUID request,String operation,Object body,Supplier<UUID> action){String hash=hash((operation+"|"+json(body)).getBytes(StandardCharsets.UTF_8));lock(p.tenantId(),"command:"+p.actorId()+":"+request);
        var b=base(p).addValue("request",request);var prior=jdbc.queryForList("select request_hash,result_id from procurement_command where tenant_id=:tenantId and actor_id=:actorId and request_id=:request",b);
        if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("request_hash")))throw conflict("同一请求编号不能用于不同操作或数据");return uuid(prior.getFirst(),"result_id");}
        UUID result=action.get();jdbc.update("insert into procurement_command(tenant_id,actor_id,request_id,request_hash,result_id) values(:tenantId,:actorId,:request,:hash,:result)",b.addValue("hash",hash).addValue("result",result));return result;}
    private void lock(UUID tenant,String key){jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,74))",new MapSqlParameterSource("key",tenant+":"+key));}
    private void event(UUID tenant,UUID order,UUID actor,String type,Object detail){jdbc.update("insert into procurement_event(tenant_id,order_id,actor_id,event_type,detail) values(:tenant,:order,:actor,:type,cast(:detail as jsonb))",new MapSqlParameterSource("tenant",tenant).addValue("order",order).addValue("actor",actor).addValue("type",type).addValue("detail",json(detail)));}
    private void notify(UUID tenant,UUID recipient,UUID order,String type,String title,String content,String key){jdbc.update("""
        insert into notification(id,tenant_id,recipient_account_id,notification_type,title,content,source_type,source_id,idempotency_key)
        select gen_random_uuid(),:tenant,:recipient,:type,:title,:content,'PROCUREMENT',:order,:key
        from user_account where tenant_id=:tenant and id=:recipient
        on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing
        """,new MapSqlParameterSource("tenant",tenant).addValue("recipient",recipient).addValue("order",order).addValue("type","PROCUREMENT_"+type).addValue("title",title).addValue("content",content).addValue("key","procurement:"+key));}
    private boolean is(TenantPrincipal p,Map<String,Object> conf,String key){return has(p,"procurement.read")&&p.actorId().equals(conf.get(key));}
    private boolean canReceive(TenantPrincipal p,Map<String,Object> conf){return is(p,conf,"manager_id")||(has(p,"procurement.read")&&receiverIds(conf).contains(p.actorId()));}
    private void requireActor(TenantPrincipal p,Map<String,Object> conf,String key){if(!is(p,conf,key))throw forbidden();}
    @SuppressWarnings("unchecked") private static List<UUID> receiverIds(Map<String,Object> conf){return (List<UUID>)conf.getOrDefault("receiver_ids",List.of());}
    private static boolean has(TenantPrincipal p,String key){return p.hasPermission(key)||p.hasPermission("*");}
    private static void requireState(Map<String,Object> row,String... states){if(!Set.of(states).contains(row.get("status")))throw conflict("当前状态不可执行此操作，请刷新查看最新进度");}
    private static void expect(long actual,long expected){if(actual!=expected)throw conflict("记录已被更新，请刷新后重新核对");}
    private static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    private static UUID uuid(Map<String,Object> row,String key){return (UUID)row.get(key);}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private static BigDecimal decimal(Map<String,Object> row,String key){return row.get(key)==null?BigDecimal.ZERO:new BigDecimal(row.get(key).toString());}
    private static BigDecimal amount(BigDecimal value,String label){if(value==null||value.signum()<0)throw bad(label+"不能为负或空");return StoreItemUnits.exact(value,4,14,label);}
    private static String clean(String value){return value==null?"":value.trim();}
    private static void requireNote(String value){if(clean(value).isEmpty())throw bad("请填写差异或退回原因");}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("采购记录无法保存",e);}}
    private Map<String,Object> parse(String value){try{return mapper.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("采购留痕数据损坏",e);}}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号不是该门店配置的办理人或缺少采购读取权限");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"采购记录或门店不存在");}
}
