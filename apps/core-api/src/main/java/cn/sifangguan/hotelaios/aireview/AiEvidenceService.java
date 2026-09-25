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

    public AiEvidenceService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext db,AccessPolicy access,
            WorkVisibilityPolicy visibility,AuditWriter audit,AttachmentService files,ObjectMapper json,
            AiVisionGateway gateway,@Value("${app.ai-evidence.daily-request-limit:100}") int dailyLimit,
            @Value("${app.ai-evidence.pilot-hotel-ids:}") String pilotHotels) {
        this.jdbc=jdbc;this.db=db;this.access=access;this.visibility=visibility;this.audit=audit;
        this.files=files;this.json=json;this.gateway=gateway;this.dailyLimit=Math.max(1,Math.min(10000,dailyLimit));
        this.pilotHotels=Set.copyOf(Arrays.stream(pilotHotels.split(",")).map(String::trim)
                .filter(s->!s.isEmpty()).map(UUID::fromString).toList());
    }
    private boolean pilotHotel(Object id) { return pilotHotels.isEmpty() || pilotHotels.contains(id); }
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
        return Map.of("hotels",jdbc.queryForList("select id,name from org_unit where tenant_id=:tenant and unit_type='HOTEL' and status='ACTIVE' order by name",params()).stream().filter(h->pilotHotel(h.get("id"))).toList(),
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
            requirePilotHotel(hotel);
            one("select id from org_unit where tenant_id=:tenant and id=:id and unit_type='HOTEL' and status='ACTIVE'",params().addValue("id",hotel));
        }
        for(String code:new HashSet<>(c.itemCodes())) {
            one("select id from work_package_item where tenant_id=:tenant and item_code=:code limit 1",params().addValue("code",code));
        }
        if(c.sampleIds()!=null) for(UUID id:new HashSet<>(c.sampleIds())) {
            one("select id from ai_evidence_sample where tenant_id=:tenant and id=:id and position_id=:position",
                    params().addValue("id",id).addValue("position",request.positionId()));
            sampleContent(id); // Permanent retention does not revive deleted or missing sample bytes.
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
        String scope=visibility.scope(access.principal(),p).summary("a");
        return jdbc.queryForList("""
                select a.position_assignment_id as id,a.position_id,e.name,pd.name as position_name
                from (select a.*,a.id as position_assignment_id,cast(:hotel as uuid) as target_org_unit_id
                    from employee_position_assignment a join org_unit_closure c on c.tenant_id=a.tenant_id
                      and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel
                    where a.tenant_id=:tenant and a.status='ACTIVE' and a.valid_from<=current_date
                      and (a.valid_to is null or a.valid_to>=current_date)) a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pd on pd.tenant_id=a.tenant_id and pd.id=a.position_id
                where e.deleted_at is null and e.employment_status='ACTIVE' and
                """+scope+" order by e.name limit 200",p);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> context(UUID recordId) {
        var r=record(recordId);
        var activeRules=jdbc.queryForList("select position_id,configuration from ai_evidence_rule where tenant_id=:tenant and state='SHADOW'",params());
        var people=subjects(recordId).stream().map(subject->{
            var row=new LinkedHashMap<>(subject);
            row.put("configured",pilotHotel(r.get("hotel_id")) && activeRules.stream().filter(rule->subject.get("position_id").equals(rule.get("position_id")))
                .anyMatch(rule->{var c=node(rule.get("configuration"));return contains(c.path("hotelIds"),String.valueOf(r.get("hotel_id"))) && contains(c.path("itemCodes"),String.valueOf(r.get("item_code")));}));
            return row;
        }).toList();
        var photos=new ArrayList<>(files.list(recordId));
        photos.addAll(jdbc.queryForList("select id,original_name,media_type,'AI' as source from ai_evidence_photo where tenant_id=:tenant and work_record_id=:record order by created_at desc",params().addValue("record",recordId)));
        return Map.of("subjects",people,"checks",checks(recordId),"providerReady",gateway.ready(),
                "attachments",photos,"canSubmit",pilotHotel(r.get("hotel_id")) && (access.principal().hasPermission("work-record.submit") || access.principal().hasPermission("*"))
                    && (access.principal().actorId().equals(r.get("account_id")) || access.principal().actorId().equals(r.get("submitted_by_account_id"))),
                "defaultSubjectId",r.get("position_assignment_id"));
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
            rule=one("select * from ai_evidence_rule where tenant_id=:tenant and position_id=:position and state='SHADOW'",
                    params().addValue("position",subject.get("position_id")));
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
        db.apply(tenant);
        jdbc.update("update ai_evidence_check set state='UNAVAILABLE',failure_code='IDENTITY_CHANGED',row_version=row_version+1 where tenant_id=:tenant and id=:id and state='QUEUED'",
                new MapSqlParameterSource("tenant",tenant).addValue("id",id));
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
        var r=record((UUID)c.get("work_record_id"));owner(r);access.requirePermission("work-record.submit");
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
