package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Reads work facts and appends a report snapshot only when the reported metrics change. */
@Service
public class MonthlyWorkCompletionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TenantDatabaseContext database;
    private final WorkPackageService work;
    private final WorkVisibilityPolicy visibility;
    private final ObjectMapper json;
    public MonthlyWorkCompletionService(NamedParameterJdbcTemplate jdbc,AccessPolicy access,
            TenantDatabaseContext database,WorkPackageService work,WorkVisibilityPolicy visibility,ObjectMapper json) {
        this.jdbc=jdbc;this.access=access;this.database=database;this.work=work;this.visibility=visibility;this.json=json;
    }

    @Transactional
    public Map<String,Object> report(String month,UUID hotelId,UUID requestedAssignment,int page,int size) {
        if (page<0 || size<1 || size>200) throw badRequest("page须非负，size须为1至200");
        var context=context(month,hotelId,requestedAssignment);
        lockSnapshot(context);
        var query=work.completionExpectationQuery(context.assignment(),hotelId);
        var params=query.params().addValue("monthStart",context.start()).addValue("monthEnd",context.end())
                .addValue("now",context.asOf()).addValue("subjectAssignment",context.assignment());
        var taskScope=visibility.scope(context.principal(),params);
        String sql="""
                with source as materialized (
                """+query.sql()+" and x.due_at>=:monthStart and x.due_at<:monthEnd"+"""
                ), daily as (
                  select id,item_name as title,hotel_org_unit_id as hotel_id,hotel_name,'ROUTINE'::text as source,
                         due_at,completion_submitted_at as submitted_at,
                """+WorkBoardService.STATE_SQL+"""
                         as board_status,id as expectation_id,null::uuid as task_id,coalesce(completion_record_id,record_id) as record_id
                  from source where status not in ('WAIVED','CANCELLED') and item_required
                    and coalesce(execution_policy->>'repeatableDailyRecords','false')<>'true'
                    and coalesce(execution_policy->>'operationsPlanMaterial','false')<>'true'
                ), task_source as (
                  select t.id,t.title,coalesce(hotel.id,o.id) as hotel_id,coalesce(hotel.name,o.name) as hotel_name,
                    case when t.creation_source='WORK_PLAN' or exists(select 1 from management_work_plan_task_link link
                      where link.tenant_id=t.tenant_id and link.task_id=t.id) then 'PLAN' else 'ASSIGNED' end as source,
                    t.due_at,submission.occurred_at as submitted_at,
                    case when t.lifecycle_status='COMPLETED' then 'COMPLETED'
                         when t.lifecycle_status in ('RESULT_SUBMITTED','AWAITING_REVIEW') then 'REVIEW'
                         when t.due_at<=:now then 'OVERDUE' else 'PENDING' end as board_status,
                    null::uuid as expectation_id,t.id as task_id,null::uuid as record_id,
                    t.org_unit_id as target_org_unit_id,assignee.position_assignment_id
                  from management_task t
                  join org_unit o on o.tenant_id=t.tenant_id and o.id=t.org_unit_id
                  join task_participant assignee on assignee.tenant_id=t.tenant_id and assignee.task_id=t.id
                    and assignee.participant_type='ASSIGNEE' and assignee.valid_to is null
                  left join lateral(select h.id,h.name from org_unit_closure c
                    join org_unit h on h.tenant_id=c.tenant_id and h.id=c.ancestor_id and h.unit_type='HOTEL'
                    where c.tenant_id=t.tenant_id and c.descendant_id=t.org_unit_id order by c.depth limit 1) hotel on true
                  left join lateral(select tr.occurred_at from task_transition tr
                    where tr.tenant_id=t.tenant_id and tr.task_id=t.id and tr.command='SUBMIT_RESULT'
                    order by tr.task_version desc,tr.occurred_at desc limit 1) submission on true
                  where t.tenant_id=:tenantId and t.due_at>=:monthStart and t.due_at<:monthEnd
                    and t.lifecycle_status not in ('PROPOSED','CANCELLED')
                    and assignee.position_assignment_id=:subjectAssignment
                ), tasks as (select id,title,hotel_id,hotel_name,source,due_at,submitted_at,board_status,
                                    expectation_id,task_id,record_id from task_source
                  where
                """+taskScope.summary("task_source")+(hotelId==null ? "" : " and hotel_id=:targetOrgUnitId")+"""
                ) select * from daily union all select * from tasks order by due_at,id
                """;
        List<WorkCompletionMetrics.Fact> facts=jdbc.query(sql,params,(rs,n)->new WorkCompletionMetrics.Fact(
                rs.getObject("id",UUID.class),rs.getString("title"),rs.getObject("hotel_id",UUID.class),
                rs.getString("hotel_name"),rs.getString("source"),rs.getObject("due_at",OffsetDateTime.class),
                rs.getObject("submitted_at",OffsetDateTime.class),rs.getString("board_status"),
                rs.getObject("expectation_id",UUID.class),rs.getObject("task_id",UUID.class),rs.getObject("record_id",UUID.class)));
        Map<String,Object> result=WorkCompletionMetrics.summarize(facts,context.asOf());
        result.put("month",context.month().toString()); result.put("asOf",context.asOf());
        result.put("assignmentId",context.assignment());
        result.put("items",facts.stream().skip((long)page*size).limit(size).map(WorkCompletionMetrics.Fact::asMap).toList());
        result.put("totalItems",facts.size()); result.put("page",page);result.put("size",size);
        result.put("planCompliance",planCompliance(context));
        result.put("undatedTasks",jdbc.queryForObject("""
                select count(distinct undated.id) from (
                  select t.id,t.org_unit_id as target_org_unit_id,p.position_assignment_id
                  from management_task t join task_participant p on p.tenant_id=t.tenant_id and p.task_id=t.id
                    and p.participant_type='ASSIGNEE' and p.valid_to is null
                  where t.tenant_id=:tenantId and p.position_assignment_id=:subjectAssignment
                    and t.due_at is null and t.lifecycle_status not in ('PROPOSED','CANCELLED','COMPLETED')
                ) undated where
                """+taskScope.summary("undated")+(hotelId==null ? "" : " and exists(select 1 from org_unit_closure selected_hotel where selected_hotel.tenant_id=:tenantId and selected_hotel.ancestor_id=:targetOrgUnitId and selected_hotel.descendant_id=undated.target_org_unit_id)"),params,Long.class));
        result.put("historyCorrectionNote","按原截止月份归属；补交和抽查修正保留报表快照。快照自本功能启用后留存，不改写工作事实或历史薪酬。");
        result.put("snapshot",archive(context,result));
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> history(String month,UUID hotelId,UUID requestedAssignment) {
        var c=context(month,hotelId,requestedAssignment);
        return Map.of("items",jdbc.queryForList("""
                select id,revision_no as "revisionNo",captured_at as "capturedAt",
                  (report->>'qualifiedCompleted')::bigint as "qualifiedCompleted",
                  (report->>'onTimeCompleted')::bigint as "onTimeCompleted",
                  (report->>'denominator')::bigint as denominator,change_reason as "changeReason"
                from operations_monthly_work_snapshot where tenant_id=:tenantId and subject_assignment_id=:subject
                  and month_start=:month and scope_key=:scope order by revision_no desc limit 100
                """,snapshotParams(c)));
    }

    private Context context(String rawMonth,UUID hotelId,UUID requested) {
        var principal=access.principal(); database.apply(principal.tenantId());
        access.requireAnyPermission("work-record.read","work-record.team-read","work-record.review");
        access.requirePermission("task.read");
        String timezone=jdbc.queryForObject("select timezone from tenant where id=:tenantId",Map.of("tenantId",principal.tenantId()),String.class);
        ZoneId zone=ZoneId.of(timezone); OffsetDateTime now=OffsetDateTime.now(zone);
        YearMonth month;
        try {
            if (rawMonth!=null && !rawMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
            month=rawMonth==null ? YearMonth.from(now) : YearMonth.parse(rawMonth);
            if (month.getYear()<1 || month.getYear()>9999) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw badRequest("月份格式应为YYYY-MM"); }
        UUID subject=requested==null ? access.requireBusinessActorAssignment() : requested;
        boolean team=!subject.equals(principal.businessActorAssignmentId());
        var params=new MapSqlParameterSource("tenantId",principal.tenantId()).addValue("subject",subject);
        var scope=visibility.scope(principal,params);
        if (team) {
            access.requireAnyPermission("work-record.team-read","work-record.review");
            if (principal.assignmentIds().contains(subject)) throw new AccessDeniedException("请先切换到对应任职，再查看其工作完成率");
            if (!scope.administrator() && jdbc.queryForObject("select count(*) from employee_position_assignment where tenant_id=:tenantId and id=:subject and id in (:workManagedIds)",params,Integer.class)==0)
                throw new AccessDeniedException("该员工不属于当前任职的授权下属范围");
        }
        if (jdbc.queryForObject("select count(*) from employee_position_assignment where tenant_id=:tenantId and id=:subject",params,Integer.class)==0)
            throw badRequest("任职不存在或不属于当前租户");
        if (hotelId!=null) {
            access.requireOrgScope(hotelId);
            if (jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:hotel and unit_type='HOTEL'",params.addValue("hotel",hotelId),Integer.class)==0)
                throw badRequest("请选择有效门店");
        }
        String managedBoundary=params.getValue("workManagedIds").toString();
        if (params.getValue("workManagedIds") instanceof Collection<?> managed)
            managedBoundary=managed.stream().map(Object::toString).sorted().toList().toString();
        return new Context(principal,subject,hotelId,team,managedBoundary,month,zone,now,
                month.atDay(1).atStartOfDay(zone).toOffsetDateTime(),month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime());
    }

    private List<Map<String,Object>> planCompliance(Context c) {
        if (!c.assignment().equals(c.principal().businessActorAssignmentId()) && !c.principal().hasPermission("*")
                && !c.principal().hasPermission("work-plan.review") && !c.principal().hasPermission("work-plan.team-read")) return List.of();
        var params=snapshotParams(c).addValue("start",c.start()).addValue("end",c.end())
                .addValue("own",!c.team()).addValue("tenantScope",c.principal().hasTenantScope())
                .addValue("reviewer",c.principal().businessActorAssignmentId())
                .addValue("visibleHotels",c.principal().orgScopes().isEmpty()?Set.of(new UUID(0,0)):c.principal().orgScopes());
        return jdbc.queryForList("""
                select p.id,p.plan_type as type,p.period_start as "periodStart",p.period_end as "periodEnd",
                  p.status,r.revision_no as "revisionNo",r.submitted_at as "submittedAt",p.due_at as "dueAt",
                  coalesce(r.submitted_at>p.due_at,false) as late
                from management_work_plan p left join management_work_plan_revision r
                  on r.tenant_id=p.tenant_id and r.id=p.current_revision_id
                where p.tenant_id=:tenantId and p.owner_assignment_id=:subject and p.operations_plan
                  and p.due_at>=:start and p.due_at<:end
                  and (:own or (exists(select 1 from employee_position_assignment a where a.tenant_id=p.tenant_id
                    and a.id=p.owner_assignment_id and (a.manager_assignment_id=:reviewer or exists(
                      select 1 from position_assignment_reporting_relation relation where relation.tenant_id=a.tenant_id
                        and relation.subordinate_assignment_id=a.id and relation.leader_assignment_id=:reviewer
                        and relation.valid_from<=current_date and (relation.valid_to is null or relation.valid_to>=current_date))))
                    and (:tenantScope or not exists(select 1 from jsonb_array_elements_text(
                      coalesce(r.operations_payload->'hotelIds','[]'::jsonb)) as snapshot_hotel(id)
                      where snapshot_hotel.id::uuid not in (:visibleHotels)))))
                order by p.period_start,p.plan_type
                """,params);
    }

    private MapSqlParameterSource snapshotParams(Context c) {
        return new MapSqlParameterSource("tenantId",c.principal().tenantId()).addValue("subject",c.assignment())
                .addValue("month",c.month().atDay(1)).addValue("scope",scopeKey(c));
    }

    private void lockSnapshot(Context c) {
        var params=snapshotParams(c).addValue("lock",c.principal().tenantId()+":"+c.assignment()+":"+c.month()+":"+scopeKey(c));
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:lock,37))",params,(rs,n)->true);
    }

    // Different authorized viewpoints must never share an ALL-hotel history sequence.
    private String scopeKey(Context c) {
        var p=c.principal();
        String view=p.actorId()+"|"+p.businessActorAssignmentId()+"|"+p.hasTenantScope()+"|"+c.team()+"|"+c.managedBoundary()+"|"
                +p.orgScopes().stream().map(UUID::toString).sorted().toList()+"|"
                +p.roleCodes().stream().sorted().toList()+"|"+p.permissions().stream().sorted().toList();
        try {
            return (c.hotel()==null ? "ALL" : c.hotel())+":"+HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(view.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception error) { throw new IllegalStateException("月报授权范围校验失败",error); }
    }

    private Map<String,Object> archive(Context c,Map<String,Object> report) {
        var payload=new LinkedHashMap<>(report);
        for (String key:List.of("asOf","items","totalItems","page","size","historyCorrectionNote")) payload.remove(key);
        String content,digest;
        try {
            content=json.writeValueAsString(payload);
            digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("月报快照生成失败",error); }
        var params=snapshotParams(c).addValue("digest",digest).addValue("report",content).addValue("actor",c.principal().actorId());
        var previous=jdbc.queryForList("""
                select id,revision_no as "revisionNo",captured_at as "capturedAt",content_hash
                from operations_monthly_work_snapshot where tenant_id=:tenantId and subject_assignment_id=:subject
                  and month_start=:month and scope_key=:scope order by revision_no desc limit 1
                """,params);
        if (!previous.isEmpty() && digest.equals(previous.getFirst().get("content_hash"))) {
            var existing=new LinkedHashMap<>(previous.getFirst());existing.remove("content_hash");return existing;
        }
        int revision=previous.isEmpty() ? 1 : ((Number)previous.getFirst().get("revisionNo")).intValue()+1;
        return jdbc.queryForMap("""
                insert into operations_monthly_work_snapshot
                  (tenant_id,subject_assignment_id,month_start,scope_key,revision_no,content_hash,report,captured_by_account_id,change_reason)
                values(:tenantId,:subject,:month,:scope,:revision,:digest,cast(:report as jsonb),:actor,:reason)
                returning id,revision_no as "revisionNo",captured_at as "capturedAt"
                """,params.addValue("revision",revision).addValue("reason",revision==1 ? "首次生成统计快照" : "工作、计划状态或应完成数量发生变化"));
    }
    private record Context(TenantPrincipal principal,UUID assignment,UUID hotel,boolean team,String managedBoundary,YearMonth month,
                           ZoneId zone,OffsetDateTime asOf,OffsetDateTime start,OffsetDateTime end) {}
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
