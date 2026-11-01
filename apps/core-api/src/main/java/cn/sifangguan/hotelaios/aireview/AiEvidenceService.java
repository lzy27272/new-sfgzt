package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shadow observations have no write path to formal work state, costs, payroll or KPI. */
@Service
public class AiEvidenceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext db;
    private final AccessPolicy access;
    private final WorkVisibilityPolicy visibility;
    private final AuditWriter audit;
    private final AttachmentService files;
    private final ObjectMapper json;
    private final AiVisionGateway gateway;
    private final int dailyLimit;
    private final Set<UUID> pilotHotels;
    private final Set<UUID> backfillHotels;
    private static final java.time.LocalDate BACKFILL_DATE=java.time.LocalDate.of(2026,10,27);
    private static final Set<String> BACKFILL_ITEMS=Set.of("GM_APPEARANCE_MORNING_MEETING",
            "GM_APPEARANCE_MORNING_MEETING_V2","GM_APPEARANCE_MEETING_V56",
            "AGM_NS_APPEARANCE_MEETING","AGM_WS_APPEARANCE_MEETING");
    private final String previewSecret=UUID.randomUUID().toString();

    public AiEvidenceService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext db,AccessPolicy access,
            WorkVisibilityPolicy visibility,AuditWriter audit,AttachmentService files,ObjectMapper json,
            AiVisionGateway gateway,@Value("${app.ai-evidence.daily-request-limit:100}") int dailyLimit,
            @Value("${app.ai-evidence.pilot-hotel-ids:}") String pilotHotels,
            @Value("${app.ai-evidence.backfill-hotel-ids:1aab2120-75bd-4f68-b360-92b39636980a,12000000-0000-4000-8000-000000000002}") String backfillHotels) {
        this.jdbc=jdbc;this.db=db;this.access=access;this.visibility=visibility;this.audit=audit;
        this.files=files;this.json=json;this.gateway=gateway;this.dailyLimit=Math.max(1,Math.min(10000,dailyLimit));
        this.pilotHotels=Set.copyOf(Arrays.stream(pilotHotels.split(",")).map(String::trim)
                .filter(s->!s.isEmpty()).map(UUID::fromString).toList());
        this.backfillHotels=Set.copyOf(Arrays.stream(backfillHotels.split(",")).map(String::trim)
                .filter(s->!s.isEmpty()).map(UUID::fromString).toList());
    }
    // Distributing standards to all hotels must never implicitly authorize outbound photos.
    private boolean pilotHotel(Object id) { return pilotHotels.contains(id); }
    private void requirePilotHotel(Object id) {
        if(!pilotHotel(id)) throw new AccessDeniedException("此门店不在已批准的AI试点范围");
    }
    private TenantPrincipal prepare() { var p=access.principal();db.apply(p.tenantId());return p; }
    private MapSqlParameterSource params() { var p=access.principal();return new MapSqlParameterSource("tenant",p.tenantId())
            .addValue("tenantId",p.tenantId()).addValue("actor",p.actorId()); }
    private void admin() {
        if(!access.principal().hasRole("PLATFORM_ADMIN") && !access.principal().hasRole("GROUP_ADMIN"))
            throw new AccessDeniedException("仅平台管理员可配置AI检查规则");
    }
    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
    private Map<String,Object> one(String sql,MapSqlParameterSource p) {
        var rows=jdbc.queryForList(sql,p);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或无权访问");
        return rows.getFirst();
    }
    JsonNode node(Object value) {
        try { return value instanceof JsonNode n ? n:json.readTree(String.valueOf(value)); }
        catch(Exception e) { throw new IllegalArgumentException("数据格式无效"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("数据格式无效"); }
    }
    private Map<String,Object> decoded(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);
        for(String key:List.of("configuration","evidence","result")) if(result.get(key)!=null) result.put(key,node(result.get(key)));
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> options() {
        prepare();admin();
        var hotels=jdbc.queryForList("select id,name from org_unit where tenant_id=:tenant and unit_type='HOTEL' and status='ACTIVE' order by name",params());
        return Map.of("hotels",hotels,"pilotHotels",hotels.stream().filter(h->pilotHotel(h.get("id"))).toList(),
                "positions",jdbc.queryForList("select id,name from position_definition where tenant_id=:tenant and status='ACTIVE' order by name",params()),
                "items",jdbc.queryForList("""
                    select distinct i.item_code as code,i.name from work_package_item i
                    join work_package_version v on v.tenant_id=i.tenant_id and v.id=i.work_package_version_id
                    where i.tenant_id=:tenant and v.lifecycle_status='PUBLISHED' order by i.name
                    """,params()),"providerReady",gateway.ready(),"dailyRequestLimit",dailyLimit);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> rules(UUID positionId) {
        prepare();admin();
        // An active version may precede many drafts; never report it as missing on the admin page.
        return jdbc.queryForList("""
                select * from (
                    (select * from ai_evidence_rule where tenant_id=:tenant and position_id=:position order by version_no desc limit 50)
                    union
                    select * from ai_evidence_rule where tenant_id=:tenant and position_id=:position and state='SHADOW'
                ) versions order by version_no desc
                """,
                params().addValue("position",positionId)).stream().map(this::decoded).toList();
    }
    private void position(UUID id) {
        one("select id from position_definition where tenant_id=:tenant and id=:id and status='ACTIVE'",params().addValue("id",id));
    }
    private void validate(AiEvidenceModels.SaveRule request) {
        position(request.positionId());
        var c=request.configuration();
        var codes=new HashSet<String>();
        for(var criterion:c.criteria()) {
            if(!codes.add(criterion.code())) throw new IllegalArgumentException("检查项不能重复");
            if(criterion.enabled() && (criterion.standard()==null || criterion.standard().isBlank()))
                throw new IllegalArgumentException("启用的检查项必须填写明确规范，不能由AI自行猜测");
        }
        if(c.criteria().stream().noneMatch(AiEvidenceModels.Criterion::enabled)) throw new IllegalArgumentException("至少启用一项客观检查");
        for(UUID hotel:new HashSet<>(c.hotelIds())) {
            // Publishing a readable standard does not authorize sending photos externally.
            // request(), claim() and images() still enforce the independent pilot allowlist.
            one("select id from org_unit where tenant_id=:tenant and id=:id and unit_type='HOTEL' and status='ACTIVE'",params().addValue("id",hotel));
        }
        for(String code:new HashSet<>(c.itemCodes())) {
            one("select id from work_package_item where tenant_id=:tenant and item_code=:code limit 1",params().addValue("code",code));
        }
        if(c.sampleIds()!=null) for(UUID id:new HashSet<>(c.sampleIds())) {
            one("""
                    select s.id from ai_evidence_sample s where s.tenant_id=:tenant and s.id=:id
                    and (s.position_id=:position or exists(select 1 from ai_evidence_rule r
                      where r.tenant_id=s.tenant_id and r.position_id=:position
                        and r.configuration->'sampleIds' @> jsonb_build_array(cast(:id as text))))
                    """,
                    params().addValue("id",id).addValue("position",request.positionId()));
            sampleContent(id); // Permanent retention does not revive deleted or missing sample bytes.
        }
        if(c.sourceRuleId()!=null || c.sourceRuleVersion()!=null) {
            one("""
                    select id from ai_evidence_rule where tenant_id=:tenant and position_id=:position
                      and configuration->>'sourceRuleId'=:source
                      and configuration->>'sourceRuleVersion'=:version limit 1
                    """,params().addValue("position",request.positionId()).addValue("source",String.valueOf(c.sourceRuleId()))
                    .addValue("version",String.valueOf(c.sourceRuleVersion())));
        }
    }
    @Transactional
    public Map<String,Object> saveRule(AiEvidenceModels.SaveRule request) {
        prepare();admin();validate(request);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,80))",params()
                .addValue("key",access.principal().tenantId()+":"+request.positionId()),Object.class);
        var versions=rules(request.positionId());
        UUID latest=versions.isEmpty()?null:(UUID)versions.getFirst().get("id");
        if(!Objects.equals(latest,request.expectedLatestId())) throw conflict("规则已被他人修改，请刷新后重试");
        int version=versions.isEmpty()?1:((Number)versions.getFirst().get("version_no")).intValue()+1;
        UUID id=UUID.randomUUID();
        if(request.shadow()) jdbc.update("update ai_evidence_rule set state='RETIRED' where tenant_id=:tenant and position_id=:position and state='SHADOW'",
                params().addValue("position",request.positionId()));
        jdbc.update("""
                insert into ai_evidence_rule(id,tenant_id,position_id,version_no,state,configuration,created_by)
                values(:id,:tenant,:position,:version,:state,cast(:config as jsonb),:actor)
                """,params().addValue("id",id).addValue("position",request.positionId()).addValue("version",version)
                .addValue("state",request.shadow()?"SHADOW":"DRAFT").addValue("config",encode(request.configuration())));
        audit.record("AI_EVIDENCE_RULE_VERSION_CREATED","AI_EVIDENCE_RULE",id,
                encode(Map.of("version",version,"mode",request.shadow()?"SHADOW":"DRAFT")));
        return rules(request.positionId()).getFirst();
    }
    private List<Map<String,Object>> activeRules() {
        return jdbc.queryForList("""
                select r.*,p.name as position_name,sp.name as source_position_name
                from ai_evidence_rule r join position_definition p on p.tenant_id=r.tenant_id and p.id=r.position_id
                left join ai_evidence_rule source on source.tenant_id=r.tenant_id and source.id::text=r.configuration->>'sourceRuleId'
                left join position_definition sp on sp.tenant_id=source.tenant_id and sp.id=source.position_id
                where r.tenant_id=:tenant and r.state='SHADOW' order by p.name,r.id
                """,params());
    }
    private boolean participants(Map<String,Object> rule) {
        return "WORK_PARTICIPANTS".equals(node(rule.get("configuration")).path("subjectScope").asText());
    }
    private boolean covers(Map<String,Object> rule,Map<String,Object> record) {
        var c=node(rule.get("configuration"));
        return contains(c.path("hotelIds"),String.valueOf(record.get("hotel_id")))
                && contains(c.path("itemCodes"),String.valueOf(record.get("item_code")));
    }
    private Map<String,Object> participantRule(Map<String,Object> record,List<Map<String,Object>> rules) {
        return rules.stream().filter(rule->participants(rule) && record.get("position_id").equals(rule.get("position_id"))
                && covers(rule,record)).findFirst().orElse(null);
    }
    private Map<String,Object> resolveRule(Map<String,Object> record,Object subjectPosition,List<Map<String,Object>> rules) {
        var taskRule=participantRule(record,rules);
        if(taskRule!=null) return taskRule;
        return rules.stream().filter(rule->!participants(rule) && subjectPosition.equals(rule.get("position_id"))
                && covers(rule,record)).findFirst().orElse(null);
    }
    /** Standards are deliberately separate from record/evidence APIs. A participant
     * may read their hotel's standard without gaining access to the submitter's record. */
    @Transactional(readOnly=true)
    public List<Map<String,Object>> myRuleReferences() {
        var principal=prepare();
        access.requireAnyPermission("work-record.read","work-record.submit","work-record.team-read","work-record.review");
        Set<UUID> own=principal.businessActorAssignmentId()==null ? principal.assignmentIds()
                : principal.assignmentIds().contains(principal.businessActorAssignmentId()) ? Set.of(principal.businessActorAssignmentId()) : Set.of();
        if(own.isEmpty()) return List.of();
        var assignments=jdbc.queryForList("""
                select a.position_id,h.id,h.name from employee_position_assignment a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join lateral (select o.id,o.name from org_unit_closure c
                  join org_unit o on o.tenant_id=c.tenant_id and o.id=c.ancestor_id
                  where c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and o.unit_type='HOTEL' and o.status='ACTIVE'
                  order by c.depth limit 1) h on true
                where a.tenant_id=:tenant and a.id in (:assignments) and e.account_id=:actor
                  and a.status='ACTIVE' and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                  and e.deleted_at is null and e.employment_status='ACTIVE'
                """,params().addValue("assignments",own));
        var result=new ArrayList<Map<String,Object>>();
        for(var rule:activeRules()) {
            var config=node(rule.get("configuration"));
            var hotels=assignments.stream().filter(a->contains(config.path("hotelIds"),String.valueOf(a.get("id")))
                    && (participants(rule) || a.get("position_id").equals(rule.get("position_id"))))
                    .map(a->Map.<String,Object>of("id",a.get("id"),"name",a.get("name"))).distinct().toList();
            if(!hotels.isEmpty()) result.add(reference(rule,hotels));
        }
        return result;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> recordRuleReferences(UUID recordId) {
        var r=record(recordId);
        var positions=subjects(recordId).stream().map(s->s.get("position_id")).collect(java.util.stream.Collectors.toSet());
        var applicable=new LinkedHashMap<Object,Map<String,Object>>();
        for(var rule:activeRules()) if(covers(rule,r) && (participants(rule)
                ? r.get("position_id").equals(rule.get("position_id")) : positions.contains(rule.get("position_id")))) applicable.put(rule.get("id"),rule);
        // Historical checks always retain access to the exact version used, including retired versions.
        jdbc.queryForList("""
                select distinct r.*,p.name as position_name,sp.name as source_position_name
                from ai_evidence_check c join ai_evidence_rule r on r.tenant_id=c.tenant_id and r.id=c.rule_id
                join position_definition p on p.tenant_id=r.tenant_id and p.id=r.position_id
                left join ai_evidence_rule source on source.tenant_id=r.tenant_id and source.id::text=r.configuration->>'sourceRuleId'
                left join position_definition sp on sp.tenant_id=source.tenant_id and sp.id=source.position_id
                where c.tenant_id=:tenant and c.work_record_id=:record order by r.version_no desc
                """,params().addValue("record",recordId)).forEach(rule->applicable.putIfAbsent(rule.get("id"),rule));
        var hotel=one("select id,name from org_unit where tenant_id=:tenant and id=:id",params().addValue("id",r.get("hotel_id")));
        return applicable.values().stream().map(rule->reference(rule,List.of(hotel))).toList();
    }
    private Map<String,Object> reference(Map<String,Object> rule,List<Map<String,Object>> hotels) {
        var config=node(rule.get("configuration"));
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","version_no","state","position_name","source_position_name")) result.put(key,rule.get(key));
        result.put("subjectScope",participants(rule)?"WORK_PARTICIPANTS":"POSITION");
        result.put("criteria",config.path("criteria"));result.put("exceptions",config.path("exceptions").asText(""));
        result.put("sourceRuleVersion",config.path("sourceRuleVersion").isIntegralNumber()?config.path("sourceRuleVersion").intValue():null);
        result.put("itemCodes",config.path("itemCodes"));result.put("hotels",hotels);
        result.put("pilotEnabled",hotels.stream().anyMatch(h->pilotHotel(h.get("id"))));
        var samples=new ArrayList<Map<String,Object>>();
        for(var id:config.path("sampleIds")) samples.add(Map.of("id",id.asText(),"source","SAMPLE",
                "media_type","image/jpeg","original_name","规范样例 "+(samples.size()+1)));
        result.put("samples",samples);
        return result;
    }
    @Transactional(readOnly=true)
    public AttachmentService.Download referenceSample(UUID ruleId,UUID sampleId,UUID recordId) {
        var readable=recordId==null?myRuleReferences():recordRuleReferences(recordId);
        var rule=readable.stream().filter(r->ruleId.equals(r.get("id"))).findFirst()
                .orElseThrow(()->new AccessDeniedException("此规则不属于当前任职或授权工作范围"));
        var samples=node(encode(rule.get("samples")));
        boolean linked=false;
        for(var sample:samples) if(sampleId.toString().equals(sample.path("id").asText())) linked=true;
        if(!linked) throw new AccessDeniedException("样例不属于此规则版本");
        return sampleDownload(sampleId);
    }
    @Transactional
    public Map<String,Object> uploadSample(UUID positionId,String kind,MultipartFile file) {
        prepare();admin();position(positionId);
        if(!Set.of("PASS","CONCERN").contains(kind)) throw new IllegalArgumentException("请选择合格或不合格样例");
        if(file.getSize()>5*1024*1024) throw new IllegalArgumentException("AI样例请压缩至5MB以内");
        var validated=files.prepareUpload(file);
        if(!Set.of("image/jpeg","image/png").contains(validated.mediaType())) throw new IllegalArgumentException("样例仅支持JPG、PNG图片");
        UUID id=UUID.randomUUID();
        var stored=files.storeObject(access.principal().tenantId()+"/ai-evidence-samples/"+id,file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) files.removeStoredObject(stored.objectKey()); }
        });
        if(!"CLEAN".equals(stored.scanStatus())) throw new IllegalArgumentException("样例尚未通过安全扫描");
        jdbc.update("""
                insert into ai_evidence_sample(id,tenant_id,position_id,kind,object_key,original_name,media_type,size_bytes,sha256,scan_status,created_by)
                values(:id,:tenant,:position,:kind,:key,:name,:media,:size,:sha,:scan,:actor)
                """,params().addValue("id",id).addValue("position",positionId).addValue("kind",kind)
                .addValue("key",stored.objectKey()).addValue("name",stored.originalName()).addValue("media",stored.mediaType())
                .addValue("size",stored.sizeBytes()).addValue("sha",stored.sha256()).addValue("scan",stored.scanStatus()));
        audit.record("AI_EVIDENCE_SAMPLE_UPLOADED","AI_EVIDENCE_SAMPLE",id,"{}");
        return Map.of("id",id,"kind",kind,"original_name",stored.originalName());
    }
    @Transactional(readOnly=true)
    public AttachmentService.Download sampleContent(UUID id) {
        prepare();admin();return sampleDownload(id);
    }
    private AttachmentService.Download sampleDownload(UUID id) {
        var s=one("select * from ai_evidence_sample where tenant_id=:tenant and id=:id",params().addValue("id",id));
        return files.openStoredObject((String)s.get("object_key"),(String)s.get("original_name"),(String)s.get("media_type"),
                ((Number)s.get("size_bytes")).longValue(),(String)s.get("scan_status"));
    }
    @Transactional
    public Map<String,Object> uploadPhoto(UUID recordId,String captureSource,MultipartFile file) {
        var r=record(recordId);access.requirePermission("work-record.submit");owner(r);
        if(!Set.of("SUBMITTED","APPROVED").contains(r.get("status"))) throw conflict("请先提交原工作");
        if(!Set.of("CAMERA","FILE_PICKER").contains(captureSource)) throw new IllegalArgumentException("拍摄来源无效");
        if(file.getSize()>5*1024*1024) throw new IllegalArgumentException("每张检查照片不能超过5MB");
        var validated=files.prepareUpload(file);
        if(!Set.of("image/jpeg","image/png").contains(validated.mediaType())) throw new IllegalArgumentException("检查照片仅支持JPG、PNG");
        UUID id=UUID.randomUUID();
        var stored=files.storeObject(access.principal().tenantId()+"/ai-evidence-photos/"+id,file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) files.removeStoredObject(stored.objectKey()); }
        });
        if(!"CLEAN".equals(stored.scanStatus())) throw new IllegalArgumentException("照片尚未通过安全扫描");
        jdbc.update("""
                insert into ai_evidence_photo(id,tenant_id,work_record_id,object_key,original_name,media_type,size_bytes,sha256,scan_status,capture_source,created_by)
                values(:id,:tenant,:record,:key,:name,:media,:size,:sha,:scan,:source,:actor)
                """,params().addValue("id",id).addValue("record",recordId).addValue("key",stored.objectKey())
                .addValue("name",stored.originalName()).addValue("media",stored.mediaType()).addValue("size",stored.sizeBytes())
                .addValue("sha",stored.sha256()).addValue("scan",stored.scanStatus()).addValue("source",captureSource));
        audit.record("AI_EVIDENCE_PHOTO_UPLOADED","AI_EVIDENCE_PHOTO",id,"{}");
        return Map.of("id",id,"source","AI","original_name",stored.originalName());
    }
    @Transactional(readOnly=true)
    public AttachmentService.Download photoContent(UUID id) {
        prepare();var s=one("select * from ai_evidence_photo where tenant_id=:tenant and id=:id",params().addValue("id",id));
        record((UUID)s.get("work_record_id"));
        return files.openStoredObject((String)s.get("object_key"),(String)s.get("original_name"),(String)s.get("media_type"),
                ((Number)s.get("size_bytes")).longValue(),(String)s.get("scan_status"));
    }
    private AttachmentService.Download evidenceContent(Map<String,Object> evidence) {
        return "AI".equals(evidence.get("source")) ? photoContent((UUID)evidence.get("id")) : files.download((UUID)evidence.get("id"));
    }
    private Map<String,Object> record(UUID id) {
        var p=prepare();access.requireAnyPermission("work-record.read","work-record.submit","work-record.team-read","work-record.review");
        visibility.requireRecord(p,id);
        return one("""
                select w.id,w.position_assignment_id,w.submitted_by_account_id,w.status,w.business_date,
                    w.target_org_unit_id,a.position_id,i.item_code,e.account_id,
                    coalesce((select u.display_name from user_account u where u.tenant_id=w.tenant_id and u.id=w.submitted_by_account_id),e.name) as owner_name,w.submitted_at,
                    coalesce((select o.id from org_unit_closure c join org_unit o on o.tenant_id=c.tenant_id and o.id=c.ancestor_id
                      where c.tenant_id=w.tenant_id and c.descendant_id=w.target_org_unit_id and o.unit_type='HOTEL'
                      order by c.depth limit 1),w.target_org_unit_id) as hotel_id
                from work_record w join employee_position_assignment a on a.tenant_id=w.tenant_id and a.id=w.position_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                left join work_package_item i on i.tenant_id=w.tenant_id and i.id=w.work_package_item_id
                where w.tenant_id=:tenant and w.id=:id
                """,params().addValue("id",id));
    }
    private void owner(Map<String,Object> record) {
        if(!access.principal().actorId().equals(record.get("account_id"))
                && !access.principal().actorId().equals(record.get("submitted_by_account_id")))
            throw new AccessDeniedException("只能由原记录所属员工或原提交人提交、补拍和申诉");
        if(access.principal().businessActorAssignmentId()!=null
                && !access.principal().businessActorAssignmentId().equals(record.get("position_assignment_id"))
                && !access.principal().actorId().equals(record.get("submitted_by_account_id")))
            throw new AccessDeniedException("请切换到原工作任职");
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> subjects(UUID recordId) {
        var r=record(recordId);var p=params().addValue("hotel",r.get("hotel_id"));
        // Explicit task-level rules authorize selecting this hotel's participants, not
        // reading their other work. Record ownership/visibility is checked above.
        String scope=participantRule(r,activeRules())!=null ? "true" : visibility.scope(access.principal(),p).summary("a");
        return jdbc.queryForList("""
                select a.position_assignment_id as id,a.position_id,e.id as employee_id,e.name,pd.name as position_name
                from (select a.*,a.id as position_assignment_id,cast(:hotel as uuid) as target_org_unit_id
                    from employee_position_assignment a join org_unit_closure c on c.tenant_id=a.tenant_id
                      and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel
                    where a.tenant_id=:tenant and a.status='ACTIVE' and a.valid_from<=current_date
                      and (a.valid_to is null or a.valid_to>=current_date)) a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pd on pd.tenant_id=a.tenant_id and pd.id=a.position_id
                where e.deleted_at is null and e.employment_status='ACTIVE' and
                """+scope+" order by e.name,a.position_assignment_id",p);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> context(UUID recordId) {
        var r=record(recordId);
        var activeRules=activeRules();
        var people=subjects(recordId).stream().map(subject->{
            var row=new LinkedHashMap<>(subject);
            row.put("configured",resolveRule(r,subject.get("position_id"),activeRules)!=null);
            return row;
        }).toList();
        var photos=new ArrayList<>(files.list(recordId));
        photos.addAll(jdbc.queryForList("select id,original_name,media_type,'AI' as source from ai_evidence_photo where tenant_id=:tenant and work_record_id=:record order by created_at desc",params().addValue("record",recordId)));
        return Map.of("subjects",people,"checks",checks(recordId),"providerReady",gateway.ready(),
                "attachments",photos,"canSubmit",pilotHotel(r.get("hotel_id")) && (access.principal().hasPermission("work-record.submit") || access.principal().hasPermission("*"))
                    && (access.principal().actorId().equals(r.get("account_id")) || access.principal().actorId().equals(r.get("submitted_by_account_id"))),
                "defaultSubjectId",r.get("position_assignment_id"),"pilotEnabled",pilotHotel(r.get("hotel_id")),
                "participantScope",participantRule(r,activeRules)!=null);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> checks(UUID recordId) {
        record(recordId);
        return jdbc.queryForList("""
                select c.*,e.name as subject_name,p.name as position_name,r.version_no as rule_version
                from ai_evidence_check c join ai_evidence_rule r on r.tenant_id=c.tenant_id and r.id=c.rule_id
                join employee_position_assignment a on a.tenant_id=c.tenant_id and a.id=c.subject_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition p on p.tenant_id=a.tenant_id and p.id=a.position_id
                where c.tenant_id=:tenant and c.work_record_id=:record order by c.created_at desc,c.id limit 100
                """,params().addValue("record",recordId)).stream().map(this::decoded).toList();
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> team(UUID hotelId,String state) {
        prepare();access.requireAnyPermission("work-record.team-read","work-record.review");
        var p=params().addValue("hotel",hotelId).addValue("status",state);
        String scope=visibility.scope(access.principal(),p).record("w");
        return jdbc.queryForList("""
                select c.id,c.work_record_id,c.state,c.result,c.human_outcome,c.created_at,c.row_version,
                       e.name as subject_name,pd.name as position_name,o.name as hotel_name,w.business_date
                from ai_evidence_check c join work_record w on w.tenant_id=c.tenant_id and w.id=c.work_record_id
                join employee_position_assignment a on a.tenant_id=c.tenant_id and a.id=c.subject_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pd on pd.tenant_id=a.tenant_id and pd.id=a.position_id
                join org_unit o on o.tenant_id=w.tenant_id and o.id=coalesce((
                  select h.id from org_unit_closure oc join org_unit h on h.tenant_id=oc.tenant_id and h.id=oc.ancestor_id
                  where oc.tenant_id=w.tenant_id and oc.descendant_id=w.target_org_unit_id and h.unit_type='HOTEL'
                  order by oc.depth limit 1),w.target_org_unit_id)
                where c.tenant_id=:tenant and (cast(:hotel as uuid) is null or o.id=:hotel)
                  and (cast(:status as text) is null or c.human_outcome=:status)
                  and not exists(select 1 from ai_evidence_check later where later.tenant_id=c.tenant_id and later.supersedes_id=c.id)
                  and
                """+scope+" order by case when c.human_outcome='PENDING' then 0 else 1 end,c.created_at desc limit 100",p)
                .stream().map(this::decoded).toList();
    }
    private List<Map<String,Object>> evidence(UUID recordId,List<UUID> ids) {
        if(new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("同一图片不能重复选择");
        var result=new ArrayList<Map<String,Object>>();
        for(UUID id:ids) {
            var a=one("""
                    select id,sha256,original_name,media_type,size_bytes,'WORK' as source from attachment
                    where tenant_id=:tenant and id=:id and work_record_id=:record and scan_status='CLEAN'
                    and media_type in ('image/jpeg','image/png')
                    union all
                    select id,sha256,original_name,media_type,size_bytes,'AI' as source from ai_evidence_photo
                    where tenant_id=:tenant and id=:id and work_record_id=:record and scan_status='CLEAN'
                    and media_type in ('image/jpeg','image/png')
                    """,params().addValue("id",id).addValue("record",recordId));
            if(((Number)a.get("size_bytes")).longValue()>5*1024*1024) throw new IllegalArgumentException("AI检查每张照片请压缩至5MB以内");
            evidenceContent(a);result.add(a);
        }
        return result;
    }
    private boolean backfillManager() {
        var p=access.principal();
        return p.hasRole("PLATFORM_ADMIN") || p.hasPermission("ai-evidence.backfill");
    }
    private void requireBackfill(Map<String,Object> r) {
        if(!backfillManager()) throw new AccessDeniedException("仅平台管理员或后台明确授权的门店负责人可发起AI补查");
        access.requireOrgScope((UUID)r.get("hotel_id"));
        requirePilotHotel(r.get("hotel_id"));
        if(!backfillHotels.contains(r.get("hotel_id"))) throw new AccessDeniedException("此门店不在本次历史补查的授权范围");
        if(!BACKFILL_DATE.equals(((java.sql.Date)r.get("business_date")).toLocalDate())
                || !BACKFILL_ITEMS.contains(String.valueOf(r.get("item_code"))))
            throw new AccessDeniedException("本次补查仅适用于两家试点店2026年10月27日的仪容仪表工作");
        if(!Set.of("SUBMITTED","APPROVED").contains(r.get("status"))) throw conflict("原工作尚未提交或状态已变化，请刷新后处理");
        boolean submitted=Boolean.TRUE.equals(jdbc.queryForObject("""
                select (submitted_at at time zone 'Asia/Shanghai')::date=date '2026-10-27'
                from work_record where tenant_id=:tenant and id=:record
                """,params().addValue("record",r.get("id")),Boolean.class));
        if(!submitted) throw new AccessDeniedException("原工作不属于2026年10月27日上传批次");
    }
    private int remainingBackfillCalls() {
        Long used=jdbc.queryForObject("""
                select (select count(*) from ai_evidence_event where tenant_id=:tenant and action='MODEL_STARTED'
                  and created_at>=date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')
                  +(select count(*) from ai_evidence_check where tenant_id=:tenant and state='QUEUED')
                """,params(),Long.class);
        return (int)Math.max(0,dailyLimit-(used==null?0:used));
    }
    private List<Map<String,Object>> backfillPhotos(UUID recordId) {
        return jdbc.queryForList("""
                select id,original_name,media_type,checkpoint_code,'WORK' as source,created_at,
                  (created_at at time zone 'Asia/Shanghai')::date=date '2026-10-27' as batch_eligible
                from attachment where tenant_id=:tenant and work_record_id=:record
                union all
                select id,original_name,media_type,null as checkpoint_code,'AI' as source,created_at,
                  (created_at at time zone 'Asia/Shanghai')::date=date '2026-10-27' as batch_eligible
                from ai_evidence_photo where tenant_id=:tenant and work_record_id=:record order by created_at,id
                """,params().addValue("record",recordId));
    }
    private List<Map<String,Object>> backfillSubjects(UUID recordId) {
        // Current validity is rechecked by subjects(); historical images cannot be assigned to a later hire.
        var historical=jdbc.queryForList("""
                select id from employee_position_assignment where tenant_id=:tenant
                  and valid_from<=date '2026-10-27' and (valid_to is null or valid_to>=date '2026-10-27')
                """,params(),UUID.class);
        var r=record(recordId);var rules=activeRules();
        var people=subjects(recordId).stream().filter(s->historical.contains(s.get("id"))).map(s->{
            var result=new LinkedHashMap<>(s);result.put("configured",resolveRule(r,s.get("position_id"),rules)!=null);return (Map<String,Object>)result;
        }).sorted(Comparator.comparing(s->!Boolean.TRUE.equals(s.get("configured")))).toList();
        var unique=new LinkedHashMap<Object,Map<String,Object>>();
        people.forEach(s->unique.putIfAbsent(s.get("employee_id"),s));
        return List.copyOf(unique.values());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> backfillContext(UUID recordId) {
        var r=record(recordId);
        try { requireBackfill(r); }
        catch(AccessDeniedException | ResponseStatusException denied) {
            return Map.of("canBackfill",false,"blockedReason",denied instanceof ResponseStatusException rs?Objects.toString(rs.getReason(),""):denied.getMessage());
        }
        var actor=one("select display_name as name from user_account where tenant_id=:tenant and id=:actor",params());
        var original=new LinkedHashMap<String,Object>();original.put("ownerName",r.get("owner_name"));
        original.put("submittedAt",r.get("submitted_at"));original.put("businessDate",r.get("business_date"));
        var result=new LinkedHashMap<String,Object>();
        result.put("canBackfill",true);result.put("blockedReason","");result.put("providerReady",gateway.ready());
        result.put("dailyRequestLimit",dailyLimit);result.put("remainingCalls",remainingBackfillCalls());result.put("original",original);result.put("actor",actor);
        result.put("attachments",backfillPhotos(recordId));result.put("subjects",backfillSubjects(recordId));
        var backfills=jdbc.queryForList("""
                select c.id,u.display_name as requester_name,event.reason as backfill_reason
                from ai_evidence_check c join ai_evidence_event event on event.tenant_id=c.tenant_id and event.check_id=c.id and event.action='BACKFILL_REQUESTED'
                join user_account u on u.tenant_id=c.tenant_id and u.id=c.requested_by
                where c.tenant_id=:tenant and c.work_record_id=:record
                """,params().addValue("record",recordId)).stream().collect(java.util.stream.Collectors.toMap(row->row.get("id"),row->row));
        result.put("backfillChecks",checks(recordId).stream().filter(c->backfills.containsKey(c.get("id"))).map(c->{
            var merged=new LinkedHashMap<>(c);merged.putAll(backfills.get(c.get("id")));return merged;
        }).toList());
        return result;
    }
    private record BackfillPlan(Map<String,Object> preview,List<Map<String,Object>> people) {}
    private List<Map<String,Object>> backfillEvidence(UUID recordId,List<UUID> ids) {
        var evidence=evidence(recordId,ids);
        for(var photo:evidence) try(var input=evidenceContent(photo).resource().getInputStream()) {
            var digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1) digest.update(buffer,0,read);
            if(!HexFormat.of().formatHex(digest.digest()).equals(photo.get("sha256"))) throw conflict("照片内容已变化，请人工复核");
        } catch(ResponseStatusException changed) { throw changed; }
        catch(Exception unavailable) { throw new IllegalArgumentException("照片文件当前不可用，请人工复核"); }
        return evidence;
    }
    private BackfillPlan planBackfill(UUID recordId,AiEvidenceModels.BackfillRequest request) {
        var r=record(recordId);requireBackfill(r);
        if(request.reason()==null || request.reason().isBlank() || request.reason().length()>1000
                || request.photos()==null || request.photos().isEmpty() || request.photos().size()>100)
            throw new IllegalArgumentException("请填写补查原因并确认照片用途");
        var attachments=backfillPhotos(recordId).stream().collect(java.util.stream.Collectors.toMap(a->(UUID)a.get("id"),a->a));
        var subjects=backfillSubjects(recordId).stream().collect(java.util.stream.Collectors.toMap(s->(UUID)s.get("id"),s->s));
        var groups=new TreeMap<UUID,List<UUID>>();var excluded=new ArrayList<Map<String,Object>>();var seen=new HashSet<UUID>();
        for(var photo:request.photos()) {
            if(photo==null || photo.attachmentId()==null || !seen.add(photo.attachmentId())) throw new IllegalArgumentException("照片不能重复选择");
            var attachment=attachments.get(photo.attachmentId());
            if(attachment==null) throw new AccessDeniedException("照片不属于原工作记录");
            if(!Set.of("SINGLE","MEETING","GROUP","OTHER","UNCONFIRMED").contains(Objects.toString(photo.purpose(),"")))
                throw new IllegalArgumentException("请选择有效照片用途");
            if(!"SINGLE".equals(photo.purpose())) {
                if(photo.subjectAssignmentId()!=null || photo.confirmed()) throw new IllegalArgumentException("非单人仪容照片不能关联被检查员工");
                excluded.add(Map.of("attachmentId",photo.attachmentId(),"reason",switch(photo.purpose()) {
                    case "MEETING"->"会议记录";case "GROUP"->"多人合照";case "OTHER"->"其他用途";default->"尚未确认用途";
                }));continue;
            }
            if("meeting_minutes".equals(attachment.get("checkpoint_code"))) throw new IllegalArgumentException("晨会纪要不能作为个人仪容照片");
            if(!Boolean.TRUE.equals(attachment.get("batch_eligible"))) throw new AccessDeniedException("只能选择2026年10月27日已上传的照片");
            if(photo.subjectAssignmentId()==null || !photo.confirmed()) throw new IllegalArgumentException("请明确选择员工并确认该照片为此员工的单人照片");
            if(!subjects.containsKey(photo.subjectAssignmentId())) throw new AccessDeniedException("被检查员工不在本门店有效任职及历史批次范围");
            groups.computeIfAbsent(photo.subjectAssignmentId(),key->new ArrayList<>()).add(photo.attachmentId());
        }
        var rules=activeRules();var people=new ArrayList<Map<String,Object>>();int images=0,duplicates=0;
        var tokenData=new ArrayList<Object>();tokenData.add(access.principal().tenantId());tokenData.add(access.principal().actorId());
        tokenData.add(Objects.toString(access.principal().businessActorAssignmentId(),""));tokenData.add(recordId);
        tokenData.add(r.get("submitted_at"));tokenData.add(r.get("position_assignment_id"));tokenData.add(request.reason().trim());tokenData.add(request.photos());
        for(var group:groups.entrySet()) {
            if(group.getValue().size()>4) throw new IllegalArgumentException("每名员工最多选择4张单人照片");
            var subject=subjects.get(group.getKey());var rule=resolveRule(r,subject.get("position_id"),rules);
            if(rule==null) throw new IllegalArgumentException("此员工或工作项尚未配置适用的仪容仪表规则");
            var evidence=backfillEvidence(recordId,group.getValue());
            // Samples must still exist and pass retention checks before a manager confirms outbound work.
            for(var sample:node(rule.get("configuration")).path("sampleIds")) sampleDownload(UUID.fromString(sample.asText()));
            var existing=jdbc.queryForList("""
                    select c.id from ai_evidence_check c join employee_position_assignment a
                      on a.tenant_id=c.tenant_id and a.id=c.subject_assignment_id
                    where c.tenant_id=:tenant and c.work_record_id=:record and a.employee_id=:employee
                    order by c.created_at desc,c.id limit 1
                    """,params().addValue("record",recordId).addValue("employee",subject.get("employee_id")));
            var person=new LinkedHashMap<String,Object>();person.put("subjectAssignmentId",group.getKey());person.put("name",subject.get("name"));
            person.put("attachmentIds",group.getValue());person.put("ruleId",rule.get("id"));person.put("ruleVersion",rule.get("version_no"));
            person.put("duplicate",!existing.isEmpty());person.put("evidence",evidence);person.put("existingId",existing.isEmpty()?null:existing.getFirst().get("id"));
            people.add(person);tokenData.add(rule.get("id"));tokenData.add(evidence);
            if(existing.isEmpty()) images+=evidence.size();else duplicates++;
        }
        int count=people.size()-duplicates,remaining=remainingBackfillCalls();
        String blocked=!gateway.ready()?"AI接口尚未配置或外发审批未完成":count==0?"所选员工均已有检查或尚未选择可检查照片":remaining<count?"当日可用调用额度不足，请稍后重新预览":"";
        var preview=new LinkedHashMap<String,Object>();preview.put("previewToken",hash(previewSecret+encode(tokenData)));
        preview.put("personCount",count);preview.put("imageCount",images);preview.put("excludedCount",excluded.size());preview.put("duplicateCount",duplicates);
        preview.put("remainingCalls",remaining);preview.put("canEnqueue",blocked.isEmpty());preview.put("blockedReason",blocked);
        preview.put("subjects",people.stream().map(p->{var copy=new LinkedHashMap<>(p);copy.remove("evidence");copy.remove("existingId");return copy;}).toList());
        preview.put("excluded",excluded);return new BackfillPlan(preview,people);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> backfillPreview(UUID recordId,AiEvidenceModels.BackfillRequest request) {
        return planBackfill(recordId,request).preview();
    }
    @Transactional
    public Map<String,Object> backfill(UUID recordId,AiEvidenceModels.BackfillRequest request) {
        // Same tenant quota lock as claim(); no network request occurs in this transaction.
        prepare();jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,81))",params()
                .addValue("key",access.principal().tenantId().toString()),Object.class);
        var selected=request.photos().stream().map(AiEvidenceModels.BackfillPhoto::subjectAssignmentId).filter(Objects::nonNull).distinct().toList();
        var lockSubjects=selected.isEmpty()?List.<UUID>of():jdbc.queryForList("""
                select a.id from employee_position_assignment a where a.tenant_id=:tenant and a.employee_id in (
                  select s.employee_id from employee_position_assignment s where s.tenant_id=:tenant and s.id in (:selected))
                order by a.id
                """,params().addValue("selected",selected),UUID.class);
        for(UUID subject:lockSubjects)
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,82))",params()
                    .addValue("key",access.principal().tenantId()+":"+recordId+":"+subject),Object.class);
        var plan=planBackfill(recordId,request);
        if(request.previewToken()==null || !MessageDigest.isEqual(request.previewToken().getBytes(StandardCharsets.UTF_8),
                String.valueOf(plan.preview().get("previewToken")).getBytes(StandardCharsets.UTF_8))) throw conflict("规则、照片或补查信息已变化，请重新预览后确认");
        int count=((Number)plan.preview().get("personCount")).intValue();
        if(count>0 && !Boolean.TRUE.equals(plan.preview().get("canEnqueue"))) throw conflict(String.valueOf(plan.preview().get("blockedReason")));
        int createdCount=0;
        for(var person:plan.people()) {
            if(Boolean.TRUE.equals(person.get("duplicate"))) continue;
            UUID id=UUID.randomUUID();
            @SuppressWarnings("unchecked") var evidence=(List<Map<String,Object>>)person.get("evidence");
            String signature=evidence.stream().map(a->a.get("id")+":"+a.get("sha256")).sorted().reduce("",(a,b)->a+"|"+b);
            String fingerprint=hash(recordId+":"+person.get("subjectAssignmentId")+":"+person.get("ruleId")+signature);
            jdbc.update("""
                    insert into ai_evidence_check(id,tenant_id,work_record_id,subject_assignment_id,rule_id,requested_by,
                      requested_assignment_id,evidence,fingerprint,state)
                    values(:id,:tenant,:record,:subject,:rule,:actor,:assignment,cast(:evidence as jsonb),:hash,'QUEUED')
                    """,params().addValue("id",id).addValue("record",recordId).addValue("subject",person.get("subjectAssignmentId"))
                    .addValue("rule",person.get("ruleId")).addValue("assignment",access.principal().businessActorAssignmentId())
                    .addValue("evidence",encode(evidence)).addValue("hash",fingerprint));
            event(id,"BACKFILL_REQUESTED",request.reason().trim());
            audit.record("AI_EVIDENCE_BACKFILL_MAPPING_CONFIRMED","AI_EVIDENCE_CHECK",id,
                    encode(Map.of("reason",request.reason().trim(),"photos",request.photos(),"ruleId",person.get("ruleId"),
                            "subjectAssignmentId",person.get("subjectAssignmentId"),"evidence",evidence,"mode","SHADOW")));
            createdCount++;
        }
        var selectedIds=plan.people().stream().map(p->p.get("subjectAssignmentId")).collect(java.util.stream.Collectors.toSet());
        var priorIds=plan.people().stream().map(p->p.get("existingId")).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return Map.of("checks",checks(recordId).stream().filter(c->selectedIds.contains(c.get("subject_assignment_id")) || priorIds.contains(c.get("id"))).toList(),
                "queuedCount",createdCount,"duplicateCount",plan.preview().get("duplicateCount"));
    }
    @Transactional
    public Map<String,Object> request(UUID recordId,AiEvidenceModels.CheckRequest request) {
        var r=record(recordId);access.requirePermission("work-record.submit");owner(r);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,82))",params()
                .addValue("key",access.principal().tenantId()+":"+recordId+":"+request.subjectAssignmentId()),Object.class);
        requirePilotHotel(r.get("hotel_id"));
        if(!Set.of("SUBMITTED","APPROVED").contains(r.get("status"))) throw new IllegalArgumentException("请先提交原工作，再进行AI检查");
        var subject=subjects(recordId).stream().filter(s->request.subjectAssignmentId().equals(s.get("id"))).findFirst()
                .orElseThrow(()->new AccessDeniedException("被检查人不在当前门店及授权人员范围"));
        Map<String,Object> rule;
        if(request.supersedesId()!=null) {
            var previous=one("select * from ai_evidence_check where tenant_id=:tenant and id=:id for update",params().addValue("id",request.supersedesId()));
            if(!recordId.equals(previous.get("work_record_id")) || !request.subjectAssignmentId().equals(previous.get("subject_assignment_id")))
                throw new IllegalArgumentException("补拍必须关联同一工作和员工");
            rule=one("select * from ai_evidence_rule where tenant_id=:tenant and id=:id",params().addValue("id",previous.get("rule_id")));
        } else {
            rule=resolveRule(r,subject.get("position_id"),activeRules());
            if(rule==null) throw new IllegalArgumentException("此员工或检查任务尚未配置适用规则");
        }
        JsonNode config=node(rule.get("configuration"));
        if(!contains(config.path("hotelIds"),String.valueOf(r.get("hotel_id"))) || !contains(config.path("itemCodes"),String.valueOf(r.get("item_code"))))
            throw new IllegalArgumentException("此门店、岗位或工作项尚未启用AI影子检查");
        var evidence=evidence(recordId,request.attachmentIds());
        String signature=evidence.stream().map(a->a.get("id")+":"+a.get("sha256")).sorted().reduce("",(a,b)->a+"|"+b);
        String hash=hash(recordId+":"+request.subjectAssignmentId()+":"+rule.get("id")+signature);
        var old=jdbc.queryForList("select * from ai_evidence_check where tenant_id=:tenant and fingerprint=:hash",params().addValue("hash",hash));
        if(!old.isEmpty()) {
            if(request.supersedesId()!=null && !request.supersedesId().equals(old.getFirst().get("supersedes_id")))
                throw conflict("所选证据已检查过；请补充新照片，或从原检查申请人工复核");
            return decoded(old.getFirst());
        }
        if(request.supersedesId()==null && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ai_evidence_check where tenant_id=:tenant and work_record_id=:record and subject_assignment_id=:subject)",
                params().addValue("record",recordId).addValue("subject",request.subjectAssignmentId()),Boolean.class)))
            throw conflict("此员工已有检查记录，请从最新记录补拍重提，不重复创建检查");
        if(request.supersedesId()!=null && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ai_evidence_check where tenant_id=:tenant and supersedes_id=:id)",params().addValue("id",request.supersedesId()),Boolean.class)))
            throw conflict("已有新的补拍版本，请刷新后从最新版本继续");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into ai_evidence_check(id,tenant_id,work_record_id,subject_assignment_id,rule_id,requested_by,
                 requested_assignment_id,evidence,fingerprint,state,failure_code,supersedes_id)
                values(:id,:tenant,:record,:subject,:rule,:actor,:assignment,cast(:evidence as jsonb),:hash,:state,:failure,:previous)
                on conflict(tenant_id,fingerprint) do nothing
                """,params().addValue("id",id).addValue("record",recordId).addValue("subject",request.subjectAssignmentId())
                .addValue("rule",rule.get("id")).addValue("assignment",access.principal().businessActorAssignmentId())
                .addValue("evidence",encode(evidence)).addValue("hash",hash).addValue("state",gateway.ready()?"QUEUED":"UNAVAILABLE")
                .addValue("failure",gateway.ready()?null:"MODEL_NOT_CONFIGURED").addValue("previous",request.supersedesId()));
        var created=one("select * from ai_evidence_check where tenant_id=:tenant and fingerprint=:hash",params().addValue("hash",hash));
        if(id.equals(created.get("id"))) event(id,"REQUESTED","已登记影子检查；不改变正式工作和绩效");
        return decoded(created);
    }
    private boolean contains(JsonNode list,String value) { for(JsonNode n:list) if(value.equals(n.asText())) return true;return false; }
    private String hash(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void event(UUID checkId,String action,String reason) {
        jdbc.update("insert into ai_evidence_event(id,tenant_id,check_id,action,actor_id,reason) values(:id,:tenant,:check,:action,:actor,:reason)",
                params().addValue("id",UUID.randomUUID()).addValue("check",checkId).addValue("action",action).addValue("reason",reason));
        audit.record("AI_EVIDENCE_"+action,"AI_EVIDENCE_CHECK",checkId,"{\"mode\":\"SHADOW\"}");
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(UUID id) {
        prepare();var c=one("select work_record_id from ai_evidence_check where tenant_id=:tenant and id=:id",params().addValue("id",id));
        record((UUID)c.get("work_record_id"));
        return jdbc.queryForList("select action,reason,created_at from ai_evidence_event where tenant_id=:tenant and check_id=:id order by created_at,id",params().addValue("id",id));
    }
    @Transactional
    public Map<String,Object> decide(UUID id,AiEvidenceModels.Decision decision) {
        prepare();var p=params().addValue("id",id);
        var c=one("select * from ai_evidence_check where tenant_id=:tenant and id=:id for update",p);
        var r=record((UUID)c.get("work_record_id"));
        if(((Number)c.get("row_version")).intValue()!=decision.expectedVersion()) throw conflict("检查结果已更新，请刷新");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ai_evidence_check where tenant_id=:tenant and supersedes_id=:id)",p,Boolean.class)))
            throw conflict("该检查已有新的补拍版本，请处理最新版本");
        p.addValue("reason",decision.reason().trim());
        if(Set.of("APPEAL","RETRY").contains(decision.action())) {
            access.requirePermission("work-record.submit");owner(r);
            if(decision.action().equals("RETRY")) {
                if(!gateway.ready()) throw conflict("模型尚未配置或外发审批未完成，请转人工复核");
                if(!Set.of("FAILED","UNAVAILABLE").contains(c.get("state")) || ((Number)c.get("attempt_count")).intValue()>=3)
                    throw conflict("此记录不允许继续重试，请人工复核");
                jdbc.update("update ai_evidence_check set state='QUEUED',failure_code=null,row_version=row_version+1 where tenant_id=:tenant and id=:id",p);
            } else {
                jdbc.update("update ai_evidence_check set appeal_reason=:reason,human_outcome='PENDING',row_version=row_version+1 where tenant_id=:tenant and id=:id",p);
            }
        } else {
            access.requirePermission("work-record.review");access.requireOrgScope((UUID)r.get("target_org_unit_id"));
            UUID subjectAccount=jdbc.queryForObject("select e.account_id from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where a.tenant_id=:tenant and a.id=:subject",
                    params().addValue("subject",c.get("subject_assignment_id")),UUID.class);
            if(access.principal().actorId().equals(c.get("requested_by")) || access.principal().actorId().equals(subjectAccount))
                throw new AccessDeniedException("提交人和被检查人不能复核自己的检查");
            if(Set.of("QUEUED","RUNNING").contains(c.get("state"))) throw conflict("检查尚在处理中，请稍后复核");
            jdbc.update("""
                    update ai_evidence_check set human_outcome=:outcome,human_reason=:reason,human_actor=:actor,
                      human_at=now(),row_version=row_version+1 where tenant_id=:tenant and id=:id
                    """,p.addValue("outcome",decision.action()));
        }
        event(id,decision.action(),decision.reason().trim());
        return decoded(one("select * from ai_evidence_check where tenant_id=:tenant and id=:id",p));
    }

    /** Only the explicit configured tenant list is scanned; never broad cross-tenant discovery. */
    @Transactional
    public List<Map<String,Object>> pending(UUID tenant) {
        db.apply(tenant);
        var p=new MapSqlParameterSource("tenant",tenant);
        jdbc.update("update ai_evidence_check set state='FAILED',failure_code='WORKER_INTERRUPTED',row_version=row_version+1 where tenant_id=:tenant and state='RUNNING' and lease_until<now()",p);
        return jdbc.queryForList("select id,requested_by,requested_assignment_id from ai_evidence_check where tenant_id=:tenant and state='QUEUED' order by created_at limit 5",p);
    }
    @Transactional
    public void invalidateRequester(UUID tenant,UUID id) {
        invalidateQueued(tenant,id,"IDENTITY_CHANGED");
    }
    @Transactional
    public void invalidateQueued(UUID tenant,UUID id,String failure) {
        db.apply(tenant);
        jdbc.update("update ai_evidence_check set state='UNAVAILABLE',failure_code=:failure,row_version=row_version+1 where tenant_id=:tenant and id=:id and state='QUEUED'",
                new MapSqlParameterSource("tenant",tenant).addValue("id",id).addValue("failure",failure));
    }
    @Transactional
    public Map<String,Object> claim(UUID id) {
        prepare();var p=params().addValue("id",id);
        // Tenant-wide quota is serialized only for this short database transaction, never for network I/O.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,81))",params().addValue("key",access.principal().tenantId().toString()),Object.class);
        var c=one("select * from ai_evidence_check where tenant_id=:tenant and id=:id for update",p);
        if(!"QUEUED".equals(c.get("state"))) return Map.of();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ai_evidence_check where tenant_id=:tenant and supersedes_id=:id)",p,Boolean.class))) {
            jdbc.update("update ai_evidence_check set state='UNAVAILABLE',failure_code='SUPERSEDED',row_version=row_version+1 where tenant_id=:tenant and id=:id",p);
            return Map.of();
        }
        if(!access.principal().actorId().equals(c.get("requested_by"))) throw new AccessDeniedException("检查发起人身份已变化");
        var r=record((UUID)c.get("work_record_id"));
        boolean backfill=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from ai_evidence_event where tenant_id=:tenant and check_id=:id and action='BACKFILL_REQUESTED')
                """,p,Boolean.class));
        if(backfill) {
            requireBackfill(r);
            if(!gateway.ready()) throw conflict("模型当前不可用，请转人工复核");
            var currentSubject=backfillSubjects((UUID)c.get("work_record_id")).stream()
                    .filter(s->c.get("subject_assignment_id").equals(s.get("id"))).findFirst()
                    .orElseThrow(()->new AccessDeniedException("被检查人任职或授权范围已变化"));
            var rule=resolveRule(r,currentSubject.get("position_id"),activeRules());
            if(rule==null || !c.get("rule_id").equals(rule.get("id"))) throw conflict("检查规则已变化，请由管理人员核对");
            var attachments=backfillPhotos((UUID)c.get("work_record_id"));
            for(var photo:node(c.get("evidence"))) {
                UUID photoId=UUID.fromString(photo.path("id").asText());
                var attachment=attachments.stream().filter(a->photoId.equals(a.get("id"))).findFirst()
                        .orElseThrow(()->new AccessDeniedException("补查照片已删除"));
                if(!Boolean.TRUE.equals(attachment.get("batch_eligible")) || "meeting_minutes".equals(attachment.get("checkpoint_code")))
                    throw new AccessDeniedException("补查照片批次或用途已变化");
                var actual=backfillEvidence((UUID)c.get("work_record_id"),List.of(photoId)).getFirst();
                if(!photo.path("sha256").asText().equals(actual.get("sha256"))) throw conflict("补查证据已变化");
            }
        } else { owner(r);access.requirePermission("work-record.submit"); }
        requirePilotHotel(r.get("hotel_id"));
        if(subjects((UUID)c.get("work_record_id")).stream().noneMatch(s->c.get("subject_assignment_id").equals(s.get("id"))))
            throw new AccessDeniedException("被检查人任职或授权范围已变化");
        Long calls=jdbc.queryForObject("select count(*) from ai_evidence_event where tenant_id=:tenant and action='MODEL_STARTED' and created_at>=date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'",params(),Long.class);
        if(calls!=null && calls>=dailyLimit) {
            jdbc.update("update ai_evidence_check set state='UNAVAILABLE',failure_code='DAILY_LIMIT',row_version=row_version+1 where tenant_id=:tenant and id=:id",p);
            return Map.of();
        }
        UUID lease=UUID.randomUUID();
        jdbc.update("update ai_evidence_check set state='RUNNING',lease_id=:lease,lease_until=now()+interval '3 minutes',attempt_count=attempt_count+1,row_version=row_version+1 where tenant_id=:tenant and id=:id",p.addValue("lease",lease));
        event(id,"MODEL_STARTED","开始影子观察，不变更正式工作状态");
        var result=decoded(c);result.put("lease_id",lease);
        result.put("configuration",node(one("select configuration from ai_evidence_rule where tenant_id=:tenant and id=:rule",params().addValue("rule",c.get("rule_id"))).get("configuration")));
        return result;
    }
    @Transactional(readOnly=true)
    public List<AiVisionGateway.Image> images(Map<String,Object> job) {
        prepare();UUID recordId=(UUID)job.get("work_record_id");requirePilotHotel(record(recordId).get("hotel_id"));
        var images=new ArrayList<AiVisionGateway.Image>();
        for(JsonNode evidence:node(job.get("evidence"))) {
            UUID id=UUID.fromString(evidence.path("id").asText());
            var current=evidence(recordId,List.of(id)).getFirst();
            if(!evidence.path("sha256").asText().equals(current.get("sha256"))) throw new IllegalArgumentException("EVIDENCE_CHANGED");
            images.add(image(id.toString(),evidenceContent(current),"本次待检查员工证据"));
        }
        JsonNode config=node(job.get("configuration"));
        for(JsonNode sample:config.path("sampleIds")) {
            UUID id=UUID.fromString(sample.asText());
            var row=one("select kind from ai_evidence_sample where tenant_id=:tenant and id=:id",params().addValue("id",id));
            images.add(image("sample-"+id,sampleDownload(id),row.get("kind").equals("PASS")?"规范合格样例，不是待检查员工":"规范不合格样例，不是待检查员工"));
        }
        return images;
    }
    private AiVisionGateway.Image image(String id,AttachmentService.Download download,String purpose) {
        try(var input=download.resource().getInputStream()) {
            var source=javax.imageio.ImageIO.read(input);
            if(source==null) throw new IllegalArgumentException("INVALID_IMAGE");
            double ratio=Math.min(1d,1024d/Math.max(source.getWidth(),source.getHeight()));
            var scaled=new java.awt.image.BufferedImage(Math.max(1,(int)(source.getWidth()*ratio)),Math.max(1,(int)(source.getHeight()*ratio)),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=scaled.createGraphics();
            try { graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,scaled.getWidth(),scaled.getHeight());
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source,0,0,scaled.getWidth(),scaled.getHeight(),null);
            } finally { graphics.dispose(); }
            var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(scaled,"jpg",output);
            return new AiVisionGateway.Image(id,"image/jpeg",output.toByteArray(),purpose);
        } catch(Exception e) { throw new IllegalArgumentException("EVIDENCE_UNAVAILABLE"); }
    }
    @Transactional
    public void finish(Map<String,Object> job,AiVisionGateway.Observation observation,String failure) {
        prepare();var p=params().addValue("id",job.get("id")).addValue("lease",job.get("lease_id"));
        var current=jdbc.queryForList("select id from ai_evidence_check where tenant_id=:tenant and id=:id and lease_id=:lease and state='RUNNING' for update",p);
        if(current.isEmpty()) return;
        JsonNode safe=json.createObjectNode();
        String state=failure==null?"COMPLETE":"FAILED";
        if(observation!=null) {
            try {
                Set<String> ids=new LinkedHashSet<>();
                for(JsonNode a:node(job.get("evidence"))) {
                    UUID aid=UUID.fromString(a.path("id").asText());ids.add(aid.toString());
                    var actual=evidence((UUID)job.get("work_record_id"),List.of(aid)).getFirst();
                    if(!a.path("sha256").asText().equals(actual.get("sha256"))) throw new IllegalArgumentException("EVIDENCE_CHANGED");
                }
                safe=AiEvidenceResultValidator.validate(observation.findings(),node(job.get("configuration")),ids);
            } catch(RuntimeException invalid) { state="STALE";failure="EVIDENCE_OR_OUTPUT_INVALID"; }
        }
        jdbc.update("""
                update ai_evidence_check set state=:state,result=cast(:result as jsonb),failure_code=:failure,
                  input_tokens=input_tokens+:input,output_tokens=output_tokens+:output,model_name=:model,
                  completed_at=now(),lease_until=null,row_version=row_version+1
                where tenant_id=:tenant and id=:id and lease_id=:lease and state='RUNNING'
                """,p.addValue("state",state).addValue("result",safe.toString()).addValue("failure",failure)
                .addValue("input",observation==null?0:observation.inputTokens()).addValue("output",observation==null?0:observation.outputTokens())
                .addValue("model",observation==null?null:observation.model()));
        event((UUID)job.get("id"),"MODEL_FINISHED",state.equals("COMPLETE")?"影子建议已生成，待人工核对":"检查未完成，不认定员工违规；请人工复核");
    }
}
