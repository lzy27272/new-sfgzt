package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Daily-report-owned service cases and a separate, versioned guest property custody ledger. */
@Service
public class DailyActivityService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final WorkVisibilityPolicy visibility;
    private final EffectiveIdentityService identities;
    private final NotificationService notifications;
    private final AuditWriter audit;
    private final ObjectMapper mapper;

    public DailyActivityService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,
            WorkVisibilityPolicy visibility,EffectiveIdentityService identities,NotificationService notifications,
            AuditWriter audit,ObjectMapper mapper) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.visibility=visibility;
        this.identities=identities;this.notifications=notifications;this.audit=audit;this.mapper=mapper;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> recipients(UUID reportId) {
        var p=prepare(); var report=report(p,reportId,false); requireOwner(p,report,false);
        var rows=jdbc.queryForList("""
            select a.id as "assignmentId", e.name as "employeeName", d.name as "positionName",
                   e.account_id as "accountId"
            from employee_position_assignment a
            join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
            join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
            join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id and d.status='ACTIVE'
            join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel
            where a.tenant_id=:tenant and a.status='ACTIVE' and e.employment_status='ACTIVE'
              and e.account_id<>:actor and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
            order by e.name,a.id
            """,base(p).addValue("hotel",report.get("hotel_org_unit_id")));
        return rows.stream().filter(row -> eligible(p,(UUID)row.get("accountId"),(UUID)row.get("assignmentId")))
                .map(row -> {row.remove("accountId");return row;}).toList();
    }
    private boolean eligible(TenantPrincipal p,UUID account,UUID assignment) {
        return identities.inspectAssignment(p.tenantId(),account,p.correlationId(),assignment)
                .map(target -> has(target,"lost-property.receive")&&has(target,"ui.module.lost-properties")).orElse(false);
    }
    /** Called while the report row is locked. Partial drafts are allowed; submission is strict. */
    public ObjectNode normalize(UUID reportId,JsonNode input,boolean submitting) {
        var p=prepare(); var report=report(p,reportId,false); requireOwner(p,report,true);
        if(input==null || !input.isObject() || input.toString().length()>200000) throw bad("服务案例与遗留物内容格式无效或过长");
        var out=mapper.createObjectNode(); out.put("schemaVersion",1);
        var service=out.putArray("serviceCases");var lost=out.putArray("lostProperties");
        JsonNode flag=input.get("lostPropertyOccurred");
        if(flag!=null && !flag.isNull() && !flag.isBoolean()) throw bad("请选择今日是否发现遗留物品");
        out.set("lostPropertyOccurred",flag==null?NullNode.instance:flag);
        var ids=new HashSet<UUID>();
        for(String field:List.of("serviceCases","lostProperties")) {
            JsonNode entries=input.path(field);
            if(!entries.isArray() || entries.size()>30) throw bad("服务案例和遗留物各最多填写30条");
            for(JsonNode entry:entries) {
                if(!entry.isObject()) throw bad("记录内容格式无效");
                UUID id=uuid(entry,"id",true); if(!ids.add(id)) throw bad("存在重复记录，请刷新后重试");
                var row=mapper.createObjectNode();row.put("id",id.toString());
                row.put("roomNumber",text(entry,"roomNumber",80,submitting));
                row.set("photoIds",photoIds(p,reportId,null,entry.path("photoIds"),submitting));
                if(field.equals("serviceCases")) {
                    row.put("serviceContent",text(entry,"serviceContent",2000,submitting));service.add(row);
                } else {
                    row.put("itemName",text(entry,"itemName",160,submitting));
                    JsonNode quantity=entry.get("quantity");
                    if(quantity!=null&&!quantity.isNull()&&(!quantity.isIntegralNumber()||quantity.asLong()<1||quantity.asLong()>10000)) throw bad("遗留物数量须为1至10000的整数");
                    if(submitting && (quantity==null||quantity.isNull())) throw bad("请填写遗留物数量");
                    row.set("quantity",quantity==null?NullNode.instance:quantity);
                    row.put("foundLocation",text(entry,"foundLocation",240,submitting));
                    String foundAt=text(entry,"foundAt",40,submitting);
                    if(!foundAt.isEmpty()) {
                        try {var time=OffsetDateTime.parse(foundAt); if(time.isAfter(OffsetDateTime.now().plusMinutes(5))) throw bad("发现时间不能晚于当前时间");}
                        catch(java.time.format.DateTimeParseException ex) {throw bad("发现时间格式无效");}
                    }
                    row.put("foundAt",foundAt);
                    row.put("notes",text(entry,"notes",1000,false));
                    UUID recipient=uuid(entry,"recipientAssignmentId",submitting);
                    row.put("recipientAssignmentId",recipient==null?"":recipient.toString());
                    var previous=jdbc.queryForList("select state,payload from lost_property_case where tenant_id=:tenant and report_id=:report and entry_id=:entry",
                            base(p).addValue("report",reportId).addValue("entry",id));
                    boolean alreadyRouted=!previous.isEmpty()&&!"RETURNED".equals(previous.getFirst().get("state"));
                    // Historical accepted entries do not become invalid when a recipient later leaves their post.
                    // submit() still compares every field and rejects edits to those entries.
                    if(submitting && !alreadyRouted && !validRecipient(p,report,recipient)) throw bad("接收人须为本店在职且有遗留物接收权限及入口的其他员工，请重新选择");
                    if(alreadyRouted)row.put("recipientLabel",parse(previous.getFirst().get("payload")).path("recipientLabel").asText());
                    else if(recipient!=null) {
                        var labels=jdbc.queryForList("select e.name||' · '||d.name from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel where a.tenant_id=:tenant and a.id=:recipient",
                                base(p).addValue("hotel",report.get("hotel_org_unit_id")).addValue("recipient",recipient),String.class);
                        row.put("recipientLabel",labels.isEmpty()?"":labels.getFirst());
                    } else row.put("recipientLabel","");
                    lost.add(row);
                }
            }
        }
        if(submitting && (flag==null||flag.isNull())) throw bad("请确认今日是否发现遗留物品");
        if(submitting && flag.asBoolean() && lost.isEmpty()) throw bad("已选择发现遗留物，请至少填写一条");
        if(flag!=null && flag.isBoolean()&&!flag.asBoolean()&&!lost.isEmpty()) throw bad("存在遗留物记录，请选择“有”或先移除未提交记录");
        return out;
    }
    private boolean validRecipient(TenantPrincipal p,Map<String,Object> report,UUID recipient) {
        if(recipient==null)return false;
        var accounts=jdbc.queryForList("""
            select e.account_id from employee_position_assignment a
            join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id and e.employment_status='ACTIVE'
            join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
            join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel
            where a.tenant_id=:tenant and a.id=:recipient and a.status='ACTIVE' and e.account_id<>:actor
              and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
            """,base(p).addValue("hotel",report.get("hotel_org_unit_id")).addValue("recipient",recipient),UUID.class);
        return !accounts.isEmpty()&&eligible(p,accounts.getFirst(),recipient);
    }
    /** Same transaction as daily report submit: either report, cases and notifications all commit, or none do. */
    public void submit(UUID reportId,UUID revisionId,JsonNode input) {
        var p=prepare();
        var existing=jdbc.queryForList("select * from lost_property_case where tenant_id=:tenant and report_id=:id order by id for update",
                base(p).addValue("id",reportId));
        // Older clients/reports may have no activity section; never erase any existing custody entries.
        if(input==null || input.isMissingNode() || input.isNull()) {
            if(!existing.isEmpty())throw bad("已有遗留物记录，请刷新日报后再提交");return;
        }
        ObjectNode normalized=normalize(reportId,input,true);
        var report=report(p,reportId,false);
        var incoming=new HashMap<UUID,JsonNode>();
        for(var entry:normalized.path("lostProperties")) incoming.put(uuid(entry,"id",true),entry);
        for(var old:existing) {
            UUID entryId=(UUID)old.get("entry_id");JsonNode next=incoming.get(entryId);
            if(next==null)throw bad("已流转的遗留物不能删除；如需更正请联系接收人退回");
            if(!"RETURNED".equals(old.get("state"))&&!parse(old.get("payload")).equals(next))
                throw bad("已送审或入库的遗留物不能直接修改；请联系接收人退回后修订");
        }
        for(var entry:normalized.path("lostProperties")) {
            UUID entryId=uuid(entry,"id",true);
            var old=existing.stream().filter(row -> entryId.equals(row.get("entry_id"))).findFirst().orElse(null);
            if(old!=null&&!"RETURNED".equals(old.get("state")))continue;
            UUID caseId=old==null?UUID.randomUUID():(UUID)old.get("id");
            int attempt=old==null?1:((Number)old.get("submission_no")).intValue()+1;
            var params=base(p).addValue("id",caseId).addValue("report",reportId).addValue("entry",entryId)
                    .addValue("revision",revisionId).addValue("hotel",report.get("hotel_org_unit_id"))
                    .addValue("reporter",report.get("position_assignment_id")).addValue("recipient",uuid(entry,"recipientAssignmentId",true))
                    .addValue("name",entry.path("itemName").asText()).addValue("quantity",entry.path("quantity").asInt())
                    .addValue("payload",entry.toString()).addValue("attempt",attempt);
            if(old==null) jdbc.update("""
                insert into lost_property_case(id,tenant_id,hotel_org_unit_id,report_id,entry_id,source_revision_id,
                  reporter_assignment_id,recipient_assignment_id,item_name,quantity,state,payload)
                values(:id,:tenant,:hotel,:report,:entry,:revision,:reporter,:recipient,:name,:quantity,'PENDING',cast(:payload as jsonb))
                """,params);
            else jdbc.update("""
                update lost_property_case set source_revision_id=:revision,recipient_assignment_id=:recipient,item_name=:name,
                  quantity=:quantity,payload=cast(:payload as jsonb),state='PENDING',review_note=null,
                  submission_no=:attempt,row_version=row_version+1,updated_at=now()
                where tenant_id=:tenant and id=:id and state='RETURNED'
                """,params);
            event(p,caseId,old==null?"SUBMITTED":"RESUBMITTED","submit:"+revisionId,entry);
            notifications.createForAssignment(uuid(entry,"recipientAssignmentId",true),"LOST_PROPERTY_REVIEW",
                    "遗留物品待接收审核","员工已随日报上报遗留物品，请核对实物、照片与数量后确认入库。",
                    "LOST_PROPERTY",caseId,"lost-review:"+caseId+":"+attempt);
        }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String state,String search,int page) {
        var p=prepare();access.requireAnyPermission("lost-property.read","lost-property.receive","lost-property.release");
        if(state!=null&&!state.isBlank()&&!Set.of("PENDING","RETURNED","IN_CUSTODY","COLLECTED").contains(state))throw bad("遗留物状态无效");
        if(page<0||page>100000||search.length()>80)throw bad("查询参数无效");
        var params=scopeParams(p).addValue("state",state==null||state.isBlank()?null:state)
                .addValue("search","%"+search.trim()+"%").addValue("offset",page*50);
        return jdbc.queryForList("""
            select c.id,c.item_name as "itemName",c.quantity,c.remaining_quantity as "remainingQuantity",
                   c.state,c.row_version as "rowVersion",c.created_at as "createdAt",c.storage_location as "storageLocation",
                   h.name as "hotelName",e.name as "reporterName",re.name as "recipientName"
            from lost_property_case c join org_unit h on h.tenant_id=c.tenant_id and h.id=c.hotel_org_unit_id
            join employee_position_assignment a on a.tenant_id=c.tenant_id and a.id=c.reporter_assignment_id
            join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
            join employee_position_assignment ra on ra.tenant_id=c.tenant_id and ra.id=c.recipient_assignment_id
            join employee re on re.tenant_id=ra.tenant_id and re.id=ra.employee_id
            where c.tenant_id=:tenant and (cast(:state as text) is null or c.state=:state)
              and (c.item_name ilike :search or c.payload->>'roomNumber' ilike :search) and
            """+scopeSql()+" order by c.created_at desc,c.id limit 51 offset :offset",params);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> entryAssignment(UUID id) {
        var p=prepare();var row=caseRow(p,id,false);
        UUID recipient=(UUID)row.get("recipient_assignment_id");
        var target=identities.inspectAssignment(p.tenantId(),p.actorId(),p.correlationId(),recipient);
        if(target.isPresent()&&has(target.get(),"lost-property.receive")&&has(target.get(),"ui.module.lost-properties")
                &&hotelAllowed(target.get(),(UUID)row.get("hotel_org_unit_id")))return Map.of("assignmentId",recipient);
        requireCaseRead(p,row);return Map.of();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID id) {
        var p=prepare();var row=caseRow(p,id,false);requireCaseRead(p,row);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",id);result.put("itemName",row.get("item_name"));result.put("quantity",row.get("quantity"));
        result.put("remainingQuantity",row.get("remaining_quantity"));result.put("state",row.get("state"));
        result.put("rowVersion",row.get("row_version"));result.put("reportId",row.get("report_id"));
        result.put("storageLocation",row.get("storage_location"));result.put("reviewNote",row.get("review_note"));
        result.put("payload",parse(row.get("payload"))); result.put("createdAt",row.get("created_at"));
        boolean recipient=Objects.equals(p.businessActorAssignmentId(),row.get("recipient_assignment_id"));
        result.put("canReceive","PENDING".equals(row.get("state"))&&recipient&&has(p,"lost-property.receive")&&hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")));
        result.put("canRelease","IN_CUSTODY".equals(row.get("state"))&&has(p,"lost-property.release")&&hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")));
        result.put("isReporter",Objects.equals(p.businessActorAssignmentId(),row.get("reporter_assignment_id")));
        var history=jdbc.queryForList("""
            select v.id,v.kind,v.payload,v.created_at as "createdAt",u.display_name as "actorName"
            from lost_property_event v join user_account u on u.tenant_id=v.tenant_id and u.id=v.actor_account_id
            where v.tenant_id=:tenant and v.case_id=:id order by v.created_at,v.id
            """,base(p).addValue("id",id));
        history.forEach(item -> item.put("payload",parse(item.get("payload"))));result.put("history",history);
        return result;
    }
    @Transactional
    public Map<String,Object> review(UUID id,String key,JsonNode request) {
        requireActionPayload(request);
        var p=prepare();access.requirePermission("lost-property.receive");
        var row=caseRow(p,id,true);
        if(!Objects.equals(row.get("recipient_assignment_id"),p.businessActorAssignmentId())||!hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")))
            throw denied("只有当前指定的本店接收人可以审核");
        if(replayed(p,id,key,request,Set.of("RECEIVED","RETURNED")))return detail(id);
        expected(row,request);
        if(!"PENDING".equals(row.get("state")))throw conflict("该遗留物已处理，请刷新");
        String outcome=text(request,"outcome",24,true);
        if(!Set.of("RECEIVED","RETURNED").contains(outcome))throw bad("审核结果无效");
        String location=text(request,"storageLocation",240,outcome.equals("RECEIVED"));
        String note=text(request,"note",1000,outcome.equals("RETURNED"));
        jdbc.update("""
            update lost_property_case set state=:state,remaining_quantity=:remaining,storage_location=:location,
              review_note=:note,received_at=case when :state='IN_CUSTODY' then now() else null end,
              received_by=case when :state='IN_CUSTODY' then :actor else null end,row_version=row_version+1,updated_at=now()
            where tenant_id=:tenant and id=:id
            """,base(p).addValue("id",id).addValue("state",outcome.equals("RECEIVED")?"IN_CUSTODY":"RETURNED")
                .addValue("remaining",outcome.equals("RECEIVED")?row.get("quantity"):0).addValue("location",location).addValue("note",note));
        event(p,id,outcome,key,request);
        notifications.createForAssignment((UUID)row.get("reporter_assignment_id"),"LOST_PROPERTY_"+outcome,
                outcome.equals("RECEIVED")?"遗留物已确认入库":"遗留物上报已退回",
                outcome.equals("RECEIVED")?"接收人已核对并登记保管。":"接收人已退回，请打开原日报发起修订，补充后再次提交。",
                "DAILY_REPORT",(UUID)row.get("report_id"),"lost-result:"+id+":"+row.get("submission_no")+":"+outcome);
        return detail(id);
    }
    @Transactional
    public Map<String,Object> release(UUID id,String key,JsonNode request) {
        requireActionPayload(request);
        var p=prepare();access.requirePermission("lost-property.release");var row=caseRow(p,id,true);
        if(!hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")))throw denied("不可操作其他门店的遗留物");
        if(replayed(p,id,key,request,Set.of("RELEASED")))return detail(id);
        expected(row,request);
        if(!"IN_CUSTODY".equals(row.get("state")))throw conflict("仅在库物品可以领取出库");
        JsonNode quantity=request.path("quantity");
        if(!quantity.isIntegralNumber()||quantity.asLong()<1||quantity.asLong()>((Number)row.get("remaining_quantity")).longValue())throw bad("领取数量必须大于0且不能超过在库数量");
        text(request,"verification",1000,true);
        text(request,"recipientName",80,true);
        photoIds(p,null,id,request.path("photoIds"),true);
        int remaining=((Number)row.get("remaining_quantity")).intValue()-quantity.asInt();
        jdbc.update("update lost_property_case set remaining_quantity=:remaining,state=:state,row_version=row_version+1,updated_at=now() where tenant_id=:tenant and id=:id",
                base(p).addValue("id",id).addValue("remaining",remaining).addValue("state",remaining==0?"COLLECTED":"IN_CUSTODY"));
        event(p,id,"RELEASED",key,request);return detail(id);
    }
    private void expected(Map<String,Object> row,JsonNode request) {
        if(!request.path("expectedVersion").isIntegralNumber()||request.path("expectedVersion").asLong()!=((Number)row.get("row_version")).longValue())
            throw conflict("记录已被其他人更新，请刷新后重试");
    }
    private void requireActionPayload(JsonNode request) {
        if(request==null||!request.isObject()||request.toString().length()>20000)throw bad("操作内容格式无效或过长");
    }
    private boolean replayed(TenantPrincipal p,UUID id,String key,JsonNode request,Set<String> kinds) {
        if(key==null||key.isBlank()||key.length()>180)throw bad("缺少有效的幂等请求编号");
        var rows=jdbc.queryForList("select actor_account_id,request_hash,kind from lost_property_event where tenant_id=:tenant and case_id=:id and request_key=:key",
                base(p).addValue("id",id).addValue("key",key));
        if(rows.isEmpty())return false;
        if(!kinds.contains(rows.getFirst().get("kind"))||!p.actorId().equals(rows.getFirst().get("actor_account_id"))||!hash(request.toString()).equals(rows.getFirst().get("request_hash")))
            throw conflict("同一请求编号不能用于不同操作");
        return true;
    }
    private void event(TenantPrincipal p,UUID id,String kind,String key,JsonNode payload) {
        jdbc.update("""
            insert into lost_property_event(id,tenant_id,case_id,kind,actor_account_id,actor_assignment_id,request_key,request_hash,payload)
            values(:event,:tenant,:id,:kind,:actor,:assignment,:key,:hash,cast(:payload as jsonb))
            """,base(p).addValue("event",UUID.randomUUID()).addValue("id",id).addValue("kind",kind)
                .addValue("assignment",p.businessActorAssignmentId()).addValue("key",key).addValue("hash",hash(payload.toString())).addValue("payload",payload.toString()));
        audit.record("LOST_PROPERTY_"+kind,"LOST_PROPERTY",id,payload.toString());
    }
    @Transactional(readOnly=true)
    public void authorizePhotoUpload(UUID reportId,UUID caseId) {
        var p=prepare();
        if(reportId!=null)requireOwner(p,report(p,reportId,false),true);
        else {access.requirePermission("lost-property.release");var row=caseRow(p,caseId,false);
            if(!hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")))throw denied("不可操作其他门店的遗留物");
            if(!"IN_CUSTODY".equals(row.get("state")))throw conflict("该遗留物当前不能上传领取凭证");}
    }
    @Transactional
    public Map<String,Object> registerPhoto(UUID reportId,UUID caseId,UUID id,String name,String type,long size,String hash,String key,String scan) {
        var p=prepare();
        // Recheck state under the same parent lock as submit/release. Slow file IO and scanning happened before this transaction.
        if(reportId!=null)requireOwner(p,report(p,reportId,true),true);
        else {var row=caseRow(p,caseId,true);access.requirePermission("lost-property.release");
            if(!hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")))throw denied("不可操作其他门店的遗留物");
            if(!"IN_CUSTODY".equals(row.get("state")))throw conflict("遗留物已出库");}
        var params=base(p).addValue("id",id).addValue("report",reportId).addValue("caseId",caseId);
        var existing=jdbc.queryForList("select * from daily_activity_photo where tenant_id=:tenant and id=:id",params);
        if(!existing.isEmpty()) {
            var old=existing.getFirst();
            if(!Objects.equals(old.get("report_id"),reportId)||!Objects.equals(old.get("case_id"),caseId)||!hash.equals(old.get("sha256"))||!p.actorId().equals(old.get("uploaded_by")))
                throw conflict("该照片编号已用于其他文件");
            return Map.of("id",id,"objectKey",old.get("object_key"));
        }
        Integer count=jdbc.queryForObject("select count(*) from daily_activity_photo where tenant_id=:tenant and ((report_id=:report) or (case_id=:caseId))",params,Integer.class);
        if(count!=null&&count>=500)throw bad("该记录照片已达500张安全上限，请联系管理员");
        jdbc.update("""
            insert into daily_activity_photo(id,tenant_id,report_id,case_id,object_key,original_name,media_type,size_bytes,sha256,scan_status,uploaded_by)
            values(:id,:tenant,:report,:caseId,:key,:name,:type,:size,:hash,:scan,:actor)
            """,params.addValue("key",key).addValue("name",name).addValue("type",type).addValue("size",size).addValue("hash",hash).addValue("scan",scan));
        audit.record("DAILY_ACTIVITY_PHOTO_UPLOADED","DAILY_ACTIVITY_PHOTO",id,"{}");
        return Map.of("id",id,"objectKey",key);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> photo(UUID reportId,UUID caseId,UUID id) {
        var p=prepare();
        if(reportId!=null) {access.requirePermission("daily-report.read");visibility.requireReport(p,reportId);}
        else {
            var row=caseRow(p,caseId,false);requireCaseRead(p,row);
            boolean linked=containsPhoto(parse(row.get("payload")),id)||jdbc.queryForList(
                    "select payload from lost_property_event where tenant_id=:tenant and case_id=:id and kind='RELEASED'",
                    base(p).addValue("id",caseId)).stream().anyMatch(event -> containsPhoto(parse(event.get("payload")),id));
            // The authorized releaser may preview their uploaded, not yet committed handover photo.
            if(!linked) {
                access.requirePermission("lost-property.release");
                if(!hotelAllowed(p,(UUID)row.get("hotel_org_unit_id")))throw denied("照片不可访问");
            }
        }
        var rows=jdbc.queryForList("select * from daily_activity_photo where tenant_id=:tenant and id=:id",base(p).addValue("id",id));
        if(rows.isEmpty())throw missing();
        var photo=rows.getFirst();
        if(reportId!=null && !Objects.equals(reportId,photo.get("report_id")))throw missing();
        if(caseId!=null) {
            var row=caseRow(p,caseId,false);
            boolean reported=Objects.equals(row.get("report_id"),photo.get("report_id"))&&containsPhoto(parse(row.get("payload")),id);
            boolean released=Objects.equals(caseId,photo.get("case_id"))&&(Objects.equals(p.actorId(),photo.get("uploaded_by"))||
                    jdbc.queryForList("select payload from lost_property_event where tenant_id=:tenant and case_id=:id and kind='RELEASED'",base(p).addValue("id",caseId))
                    .stream().anyMatch(event -> containsPhoto(parse(event.get("payload")),id)));
            if(!reported&&!released)throw missing();
        }
        return photo;
    }
    private ArrayNode photoIds(TenantPrincipal p,UUID reportId,UUID caseId,JsonNode input,boolean required) {
        if(!input.isArray()||input.size()>20||(required&&input.isEmpty()))throw bad("每条记录需1至20张照片，可从相册上传");
        var result=mapper.createArrayNode();var seen=new HashSet<UUID>();
        for(var value:input) {
            UUID id;try {id=UUID.fromString(value.asText());}catch(IllegalArgumentException ex){throw bad("照片编号无效");}
            if(!seen.add(id))throw bad("照片不能重复关联");
            var rows=jdbc.queryForList("""
                select id from daily_activity_photo where tenant_id=:tenant and id=:id
                 and ((report_id=:report) or (case_id=:caseId and uploaded_by=:actor))
                 and scan_status in ('CLEAN','BYPASSED_DEV','SANITIZED_IMAGE')
                """,base(p).addValue("id",id).addValue("report",reportId).addValue("caseId",caseId));
            if(rows.isEmpty())throw bad("照片未上传完成或不属于本记录，请重试");result.add(id.toString());
        }
        return result;
    }
    private Map<String,Object> report(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("""
            select d.*,e.account_id from daily_report d join employee e on e.tenant_id=d.tenant_id and e.id=d.employee_id
            where d.tenant_id=:tenant and d.id=:id
            """+(lock?" for update of d":""),base(p).addValue("id",id));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private void requireOwner(TenantPrincipal p,Map<String,Object> report,boolean writable) {
        access.requirePermission(writable?"daily-report.submit":"daily-report.read");
        if(!p.actorId().equals(report.get("account_id"))||!Objects.equals(p.businessActorAssignmentId(),report.get("position_assignment_id")))
            throw denied("请切换到本人的日报任职后操作");
        if(writable&&!"DRAFT".equals(report.get("report_status")))throw conflict("日报已提交；需要修改请发起修订");
        Boolean housekeeping=jdbc.queryForObject("select exists(select 1 from work_package_item where tenant_id=:tenant and work_package_version_id=:version and item_code='HKA_DAILY_REPORT')",
                base(p).addValue("version",report.get("work_package_version_id")),Boolean.class);
        if(!Boolean.TRUE.equals(housekeeping))throw bad("该表单只适用于客房服务员日报");
    }
    private Map<String,Object> caseRow(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("select * from lost_property_case where tenant_id=:tenant and id=:id"+(lock?" for update":""),base(p).addValue("id",id));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private void requireCaseRead(TenantPrincipal p,Map<String,Object> row) {
        boolean own=Objects.equals(p.businessActorAssignmentId(),row.get("reporter_assignment_id"))&&has(p,"daily-report.read");
        boolean recipient=Objects.equals(p.businessActorAssignmentId(),row.get("recipient_assignment_id"))&&has(p,"lost-property.receive");
        if(!own&&!(hotelAllowed(p,(UUID)row.get("hotel_org_unit_id"))&&(recipient||has(p,"lost-property.read")||has(p,"lost-property.release"))))throw denied("无权查看该遗留物记录");
    }
    private MapSqlParameterSource scopeParams(TenantPrincipal p) {
        var hotels=jdbc.queryForList("""
            select h.id from employee_position_assignment a join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id
            join org_unit h on h.tenant_id=c.tenant_id and h.id=c.ancestor_id and h.unit_type='HOTEL' and h.status='ACTIVE'
            where a.tenant_id=:tenant and a.id=:assignment and a.status='ACTIVE'
            """,base(p).addValue("assignment",p.businessActorAssignmentId()),UUID.class);
        var scopes=new HashSet<UUID>(p.orgScopes());scopes.addAll(hotels);if(scopes.isEmpty())scopes.add(new UUID(0,0));
        return base(p).addValue("scopes",scopes).addValue("all",p.hasTenantScope())
                .addValue("assignment",p.businessActorAssignmentId()).addValue("read",has(p,"lost-property.read")||has(p,"lost-property.release"))
                .addValue("receive",has(p,"lost-property.receive"));
    }
    private String scopeSql() {return " ((:all or c.hotel_org_unit_id in (:scopes)) and (:read or (:receive and c.recipient_assignment_id=:assignment))) "; }
    private boolean hotelAllowed(TenantPrincipal p,UUID hotel) {
        var params=scopeParams(p);return Boolean.TRUE.equals(params.getValue("all"))||((Collection<?>)params.getValue("scopes")).contains(hotel);
    }
    private static boolean has(TenantPrincipal p,String permission){return p.hasPermission("*")||p.hasPermission(permission);}
    private static boolean containsPhoto(JsonNode node,UUID id){for(var value:node.path("photoIds"))if(id.toString().equals(value.asText()))return true;return false;}
    private TenantPrincipal prepare(){var p=access.principal();database.apply(p.tenantId());return p;}
    private MapSqlParameterSource base(TenantPrincipal p){return new MapSqlParameterSource("tenant",p.tenantId()).addValue("actor",p.actorId());}
    private JsonNode parse(Object value){try{return mapper.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("Invalid stored activity payload",e);}}
    static String text(JsonNode node,String field,int max,boolean required) {
        JsonNode value=node.get(field);
        if(value!=null&&!value.isNull()&&!value.isTextual())throw bad("字段格式无效："+field);
        String result=value==null||value.isNull()?"":value.asText().trim();
        if(result.length()>max||(required&&result.isBlank()))throw bad("请检查必填内容或字数："+field);
        return result;
    }
    private UUID uuid(JsonNode node,String field,boolean required) {
        String value=text(node,field,40,required);if(value.isEmpty())return null;
        try{return UUID.fromString(value);}catch(IllegalArgumentException e){throw bad("记录编号无效："+field);}
    }
    static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException denied(String message){return new ResponseStatusException(HttpStatus.FORBIDDEN,message);}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或不在当前权限范围");}
}
