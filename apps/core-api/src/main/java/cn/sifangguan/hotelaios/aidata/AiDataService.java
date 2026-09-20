package cn.sifangguan.hotelaios.aidata;

import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.tasks.TaskService;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AiDataService {
    static final int PAGE_SIZE=500;
    static final Map<String,String> PERMISSIONS=Map.of(
        "WORK_RECORDS","work-record.read","DAILY_REPORTS","daily-report.read",
        "TASKS","task.read","KPI_FACTS","kpi.metric.read","OTA_DAILY","kpi.metric.read",
        "OTA_HOURLY","kpi.metric.read","OTA_PERIODS","kpi.metric.read");
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext db;
    private final AccessPolicy access;
    private final WorkVisibilityPolicy visibility;
    private final TaskService tasks;
    private final ObjectMapper json;
    private final AuditWriter audit;
    public AiDataService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext db,AccessPolicy access,
                         WorkVisibilityPolicy visibility,TaskService tasks,ObjectMapper json,AuditWriter audit) {
        this.jdbc=jdbc;this.db=db;this.access=access;this.visibility=visibility;this.tasks=tasks;
        this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);this.audit=audit;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> catalog() {
        var p=prepare(); var params=base(p); var datasets=new ArrayList<Map<String,Object>>();
        Map<String,String> names=Map.of("WORK_RECORDS","工作执行记录","DAILY_REPORTS","日报提交与审核",
            "TASKS","任务执行状态","KPI_FACTS","绩效结构化指标","OTA_DAILY","盯盘助手每日经营事实",
            "OTA_HOURLY","盯盘助手小时快照（48个月）","OTA_PERIODS","盯盘助手月/季/半年/年度汇总（5年）");
        PERMISSIONS.keySet().stream().sorted().forEach(key->datasets.add(Map.of("id",key,"name",names.get(key),
            "project",key.startsWith("OTA")?"OTA":"PLATFORM","available",allowed(p,key))));
        var bindings=allowed(p,"OTA_DAILY")?jdbc.queryForList("""
            select b.id,b.org_unit_id,b.source_name from ai_data_source_binding b
            where b.tenant_id=:tenantId and b.enabled and (:tenantScope or b.org_unit_id in (:orgScopes)) order by b.source_name
            """,params):List.of();
        var orgs=jdbc.queryForList("""
            select id,name,unit_type from org_unit where tenant_id=:tenantId and status='ACTIVE'
              and (:tenantScope or id in (:orgScopes)) order by name limit 500
            """,params);
        return Map.of("datasets",datasets,"organizations",orgs,"otaBindings",bindings,"pageSize",PAGE_SIZE,
            "reportsAudience","仅本人，且需保留原数据权限", "automaticReview",Map.of("enabled",false,"status","EXECUTOR_NOT_CONFIGURED",
            "detail","尚未配置独立AI执行器及授权；取数和报告保存不依赖它。"),
            "notice","两个项目独立运行。仅开放结构化分析字段；计划、日报正文、附件及其他未列出字段尚未接入。未绑定门店不可查询，缺失数据不按零处理。");
    }
    @Transactional
    public Map<String,Object> query(AiDataModels.Query q) {
        validate(q); var p=prepare(); require(p,q.dataset());
        if(q.orgUnitId()!=null) access.requireOrgScope(q.orgUnitId());
        var params=base(p).addValue("from",q.from()).addValue("to",q.to())
            .addValue("org",q.orgUnitId()).addValue("offset",q.offset());
        List<Map<String,Object>> rows; String caveat=""; UUID binding=null;
        switch(q.dataset()) {
            case "WORK_RECORDS" -> {
                String scope=visibility.scope(p,params).record("w");
                rows=jdbc.queryForList("""
                    select w.id,w.business_date,w.status,w.record_kind,w.org_unit_id,w.target_org_unit_id,
                        w.position_assignment_id,w.submitted_at,w.occurred_at,w.work_package_item_id,w.attempt_no
                    from work_record w where w.tenant_id=:tenantId and w.business_date between :from and :to
                    and (cast(:org as uuid) is null or w.org_unit_id=:org or w.target_org_unit_id=:org) and
                    """+scope+" order by w.business_date,w.id limit 501 offset :offset",params);
                caveat="仅结构化执行状态，不含表单自由文本或客人信息；状态为取数时的当前状态，不是历史时点快照。";
            }
            case "DAILY_REPORTS" -> {
                String scope=visibility.reportScope(p,params).report("d");
                rows=jdbc.queryForList("""
                    select d.id,d.business_date,d.hotel_org_unit_id,d.org_unit_id,d.position_assignment_id,
                        d.report_status,d.review_status,d.current_revision_no,d.submitted_at
                    from daily_report d where d.tenant_id=:tenantId and d.business_date between :from and :to
                    and (cast(:org as uuid) is null or d.hotel_org_unit_id=:org or d.org_unit_id=:org) and
                    """+scope+" order by d.business_date,d.id limit 501 offset :offset",params);
                caveat="仅日报提交/审核元数据，不含日报正文；当前审核状态不能冒充过去某一时点状态。";
            }
            case "KPI_FACTS" -> {
                rows=jdbc.queryForList("""
                    select f.id,f.metric_version_id,d.code as metric_code,f.org_unit_id,f.position_assignment_id,
                        f.business_date,f.period_start,f.period_end,f.value,f.numerator,f.denominator,
                        f.data_state,f.source_type,f.revision_no,f.supersedes_fact_id,v.aggregation
                    from kpi_metric_fact f join metric_definition_version v on v.tenant_id=f.tenant_id and v.id=f.metric_version_id
                    join metric_definition d on d.tenant_id=v.tenant_id and d.id=v.metric_definition_id
                    where f.tenant_id=:tenantId and f.business_date between :from and :to
                      and v.sensitivity_level <> 'PAYROLL_SENSITIVE'
                      and (:tenantScope or f.org_unit_id in (:orgScopes) or f.position_assignment_id in (:assignments))
                      and (cast(:org as uuid) is null or f.org_unit_id=:org)
                      and not exists(select 1 from kpi_metric_fact newer where newer.tenant_id=f.tenant_id and newer.supersedes_fact_id=f.id)
                    order by f.business_date,f.id limit 501 offset :offset
                    """,params);
                caveat="不含薪资、人员联系方式和原始来源文本；按指标口径分别统计，不得把不同指标相加。";
            }
            case "TASKS" -> {
                var source=tasks.list("ALL",null,q.orgUnitId());
                var selected=new ArrayList<Map<String,Object>>();
                Set<String> fields=Set.of("id","org_unit_id","lifecycle_status","sla_status","priority","due_at","created_at","creation_source");
                for(var row:source) {
                    var date=dateOf(row.get("created_at"));
                    if(date==null || date.isBefore(q.from()) || date.isAfter(q.to())) continue;
                    var safe=new LinkedHashMap<String,Object>(); fields.stream().sorted().forEach(k->safe.put(k,row.get(k))); selected.add(safe);
                }
                rows=selected.stream().skip(q.offset()).limit(501).toList();
                caveat="按任务创建日期筛选，返回取数时状态，不提供自由文本。"+(source.size()>=500?" 上游最多最近500条，历史范围可能截断，不能据此计算全量完成率。":"");
            }
            case "OTA_DAILY" -> {
                binding=q.bindingId(); if(binding==null) throw new IllegalArgumentException("请选择已授权绑定的盯盘门店");
                requireBinding(p,binding); params.addValue("binding",binding);
                String payload=jdbc.queryForObject("select ai_read_ota_daily(:binding,cast(:from as date),cast(:to as date),501,:offset)::text",params,String.class);
                rows=json.convertValue(parse(payload),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
                caveat="每日按最新修订；PMS日末快照不等于财务夜审。缺失日不是零，满页需继续取数。出租率=已售房晚合计/有效可售房晚合计；ADR与RevPAR也须按分子分母重算。";
            }
            case "OTA_HOURLY","OTA_PERIODS" -> {
                binding=q.bindingId();if(binding==null) throw new IllegalArgumentException("请选择已授权绑定的盯盘门店");
                requireBinding(p,binding);params.addValue("binding",binding).addValue("kind",q.dataset());
                String payload=jdbc.queryForObject("select ai_read_ota_history(:binding,:kind,cast(:from as date),cast(:to as date),501,:offset)::text",params,String.class);
                rows=json.convertValue(parse(payload),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
                caveat=q.dataset().equals("OTA_HOURLY")?"小时记录是当日累计快照，不能逐小时相加。按业务日期筛选，按采集时间排序。仅返回现有记录，保留48个月不代表已有48个月历史。"
                    :"仅包含起止日完全落入查询范围的汇总期间；MONTH/QUARTER/HALF_YEAR/YEAR相互重叠，必须按同一period_type比较，不能相加。检查available_day_count、expected_day_count和completeness；缺失或未结束期间不作完整同比。";
            }
            default -> throw new IllegalArgumentException("不支持的数据集");
        }
        boolean more=rows.size()>PAGE_SIZE; var page=rows.stream().limit(PAGE_SIZE).toList();
        UUID snapshotId=UUID.randomUUID(); String payload=stringify(page);
        String dataHash=AiDataSupport.dataHash(json,parse(payload));
        if(payload.length()>2_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"数据超过单次上限，请缩小范围");
        // Expired temporary evidence can be removed: reports retain an independent immutable copy.
        jdbc.update("delete from ai_data_snapshot where id in (select id from ai_data_snapshot where tenant_id=:tenantId and account_id=:actorId and expires_at<now() limit 2000)",params);
        // Bounded audit snapshots, never the arbitrary source payloads or database credentials.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,70))",Map.of("key",p.tenantId()+":"+p.actorId()),Object.class);
        Integer count=jdbc.queryForObject("select count(*) from ai_data_snapshot where tenant_id=:tenantId and account_id=:actorId and created_at>now()-interval '1 day'",params,Integer.class);
        if(count!=null && count>=1000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已达到每日取数上限");
        params.addValue("id",snapshotId).addValue("scope",AiDataSupport.scope(p)).addValue("dataset",q.dataset())
            .addValue("binding",binding).addValue("query",stringify(q)).addValue("data",payload).addValue("hash",dataHash);
        jdbc.update("""
            insert into ai_data_snapshot(id,tenant_id,account_id,scope_hash,dataset,source_binding_id,query_json,data_json,data_hash)
            values(:id,:tenantId,:actorId,:scope,:dataset,:binding,cast(:query as jsonb),cast(:data as jsonb),:hash)
            """,params);
        audit.record("AI_DATA_READ","AI_DATA_SNAPSHOT",snapshotId,stringify(Map.of("dataset",q.dataset(),"rowCount",page.size())));
        var result=new LinkedHashMap<String,Object>(); result.put("snapshotId",snapshotId);result.put("dataset",q.dataset());
        result.put("query",q);result.put("capturedAt",OffsetDateTime.now(ZoneOffset.UTC));
        result.put("dataHash",dataHash);result.put("hashFormat","sorted-json-decimal-sha256-v1");
        result.put("rows",page);result.put("hasMore",more);result.put("nextOffset",more?q.offset()+PAGE_SIZE:null);result.put("notice",caveat);
        result.put("coverage",q.dataset().equals("OTA_DAILY")?coverage(page,q,more):Map.of("state","OBSERVED_RECORDS_ONLY","expectedTotalVerified",false));
        return result;
    }
    @Transactional
    public Map<String,Object> save(AiDataModels.Report input) {
        var p=prepare(); validatePeriod(input.from(),input.to());
        if(input.requestId()==null || input.title()==null || input.title().isBlank() || input.title().length()>160
            || input.body()==null || input.body().isBlank() || input.body().length()>60000 || input.snapshotIds()==null
            || input.snapshotIds().isEmpty() || input.snapshotIds().size()>8 || input.snapshotIds().stream().anyMatch(Objects::isNull)
            || input.authoringTool()==null || input.authoringTool().isBlank() || input.authoringTool().length()>120) throw new IllegalArgumentException("报告字段或来源数量无效");
        var params=base(p).addValue("request",input.requestId()).addValue("scope",AiDataSupport.scope(p));
        String requestHash=AiDataSupport.hash(stringify(input));
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,69))",Map.of("key",p.actorId()+":"+input.requestId()),Object.class);
        var existing=jdbc.queryForList("select id,request_hash,scope_hash from ai_analysis_report where tenant_id=:tenantId and account_id=:actorId and request_id=:request",params);
        if(!existing.isEmpty()) {
            if(!existing.getFirst().get("request_hash").equals(requestHash)) throw new ResponseStatusException(HttpStatus.CONFLICT,"相同请求标识不能保存不同报告");
            return report((UUID)existing.getFirst().get("id"));
        }
        var evidence=new ArrayList<Map<String,Object>>();boolean platform=false,ota=false,hasPrimaryPeriod=false;
        for(UUID id:new LinkedHashSet<>(input.snapshotIds())) {
            var source=jdbc.queryForList("""
                select id,dataset,source_binding_id,query_json::text,data_json::text,data_hash,created_at from ai_data_snapshot
                where tenant_id=:tenantId and account_id=:actorId and scope_hash=:scope and id=:id and expires_at>now()
                """,params.addValue("id",id));
            if(source.size()!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"取数快照已过期或不属于当前权限范围，请重新取数");
            var row=source.getFirst();String dataset=row.get("dataset").toString();require(p,dataset);
            if(row.get("source_binding_id")!=null) { requireBinding(p,(UUID)row.get("source_binding_id")); ota=true; } else platform=true;
            var query=parse(row.get("query_json").toString());
            // Comparison snapshots may refer to the previous month/year. Preserve their
            // actual periods, but require at least one snapshot for the reported period.
            hasPrimaryPeriod |= input.from().toString().equals(query.path("from").asText())
                && input.to().toString().equals(query.path("to").asText());
            JsonNode original=parse(row.get("data_json").toString());
            if(!AiDataSupport.dataHash(json,original).equals(row.get("data_hash"))) throw new IllegalStateException("分析证据校验失败");
            row.put("query_json",query);row.put("data_json",original);row.put("hash_format","sorted-json-decimal-sha256-v1");evidence.add(row);
        }
        if(!hasPrimaryPeriod) throw new IllegalArgumentException("至少一份快照须对应报告周期；对比周期可另附快照");
        String encoded=stringify(evidence);if(encoded.length()>4_000_000) throw new IllegalArgumentException("报告证据过大，请按门店或期间拆分");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into ai_analysis_report(id,tenant_id,account_id,scope_hash,request_id,title,body,source_type,
                period_start,period_end,evidence,request_hash,authoring_tool)
            values(:id,:tenantId,:actorId,:scope,:request,:title,:body,:type,:from,:to,cast(:evidence as jsonb),:hash,:tool)
            """,params.addValue("id",id).addValue("title",input.title().trim()).addValue("body",input.body())
            .addValue("type",platform&&ota?"COMBINED":ota?"OTA":"PLATFORM").addValue("from",input.from()).addValue("to",input.to())
            .addValue("evidence",encoded).addValue("hash",requestHash).addValue("tool",input.authoringTool()));
        audit.record("AI_REPORT_SAVED","AI_ANALYSIS_REPORT",id,"{\"reviewState\":\"DRAFT_FOR_HUMAN_REVIEW\"}");
        return report(id);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> reports() {
        var p=prepare();return jdbc.queryForList("""
            select id,title,source_type,period_start,period_end,authoring_tool,created_at from ai_analysis_report
            where tenant_id=:tenantId and account_id=:actorId and scope_hash=:scope order by created_at desc limit 100
            """,base(p).addValue("scope",AiDataSupport.scope(p)));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> report(UUID id) {
        var p=prepare();var rows=jdbc.queryForList("""
            select id,title,body,source_type,period_start,period_end,evidence::text,authoring_tool,created_at
            from ai_analysis_report where tenant_id=:tenantId and account_id=:actorId and scope_hash=:scope and id=:id
            """,base(p).addValue("scope",AiDataSupport.scope(p)).addValue("id",id));
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"报告不存在或当前账号/岗位已无原数据权限");
        var report=rows.getFirst();JsonNode sources=parse(report.get("evidence").toString());
        for(var item:sources) {
            require(p,item.path("dataset").asText());
            if(!item.path("source_binding_id").isNull() && !item.path("source_binding_id").isMissingNode())
                requireBinding(p,UUID.fromString(item.path("source_binding_id").asText()));
        }
        report.put("evidence",sources);report.put("reviewState","DRAFT_FOR_HUMAN_REVIEW");return report;
    }
    private void requireBinding(TenantPrincipal p,UUID binding) {
        var rows=jdbc.queryForList("select org_unit_id from ai_data_source_binding where tenant_id=:tenantId and id=:id and enabled",base(p).addValue("id",binding));
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"盯盘门店未绑定或已停用");
        access.requireOrgScope((UUID)rows.getFirst().get("org_unit_id"));
    }
    private TenantPrincipal prepare() { var p=TenantContext.require();db.apply(p.tenantId());return p; }
    private MapSqlParameterSource base(TenantPrincipal p) { return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("actorId",p.actorId())
        .addValue("tenantScope",p.hasTenantScope()).addValue("orgScopes",nonempty(p.orgScopes())).addValue("assignments",nonempty(p.assignmentIds())); }
    private Set<UUID> nonempty(Set<UUID> ids) { return ids.isEmpty()?Set.of(new UUID(0,0)):ids; }
    static boolean allowed(TenantPrincipal p,String dataset) {return PERMISSIONS.containsKey(dataset)&&(p.hasPermission("*")||p.hasPermission(PERMISSIONS.get(dataset)));}
    private void require(TenantPrincipal p,String dataset) { if(!allowed(p,dataset)) throw new AccessDeniedException("无该数据集读取权限"); }
    static void validate(AiDataModels.Query q) {
        if(q==null || !PERMISSIONS.containsKey(q.dataset()) || q.offset()<0 || q.offset()>100000) throw new IllegalArgumentException("查询参数无效");
        validatePeriod(q.from(),q.to());
        if(!q.dataset().equals("OTA_PERIODS") && ChronoUnit.DAYS.between(q.from(),q.to())>731)
            throw new IllegalArgumentException("明细每次最多732天，较长期间请分段查询或选择期间汇总");
        if(!q.dataset().startsWith("OTA_") && q.bindingId()!=null) throw new IllegalArgumentException("中台数据查询不可指定盯盘绑定");
        if(q.dataset().startsWith("OTA_") && q.orgUnitId()!=null) throw new IllegalArgumentException("盯盘数据按绑定门店查询，不接受额外组织筛选");
    }
    static void validatePeriod(LocalDate from,LocalDate to) {
        if(from==null || to==null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to)>1830) throw new IllegalArgumentException("期间须为有效日期且不超过1831天");
    }
    static Map<String,Object> coverage(List<Map<String,Object>> rows,AiDataModels.Query q,boolean more) {
        var days=new HashSet<String>();boolean complete=true;
        for(var row:rows) {
            days.add(String.valueOf(row.get("business_date")));
            complete &= "COMPLETE".equals(row.get("completeness")) && row.get("room_revenue")!=null
                && row.get("sold_room_nights")!=null && row.get("effective_sellable_room_nights")!=null;
        }
        long expected=ChronoUnit.DAYS.between(q.from(),q.to())+1;
        boolean closed=q.to().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        return Map.of("state",q.offset()==0&&!more&&complete&&days.size()==expected&&closed?"COMPLETE":"PARTIAL",
            "observedDaysInPage",days.size(),"expectedDays",expected,"periodClosed",closed,"missingIsZero",false);
    }
    private LocalDate dateOf(Object value) {
        if(value instanceof java.sql.Timestamp t) return t.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        if(value instanceof OffsetDateTime t) return t.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        try {return LocalDate.parse(String.valueOf(value).substring(0,10));}catch(RuntimeException e){return null;}
    }
    private String stringify(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法序列化分析数据",e);}}
    private JsonNode parse(String value) {try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("分析数据格式异常",e);}}
}
