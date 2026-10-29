package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class WeeklyAssessmentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final WorkVisibilityPolicy visibility;
    private final ObjectMapper mapper;
    private final AuditWriter audit;
    public WeeklyAssessmentService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext database, AccessPolicy access,
                                   WorkVisibilityPolicy visibility, ObjectMapper mapper, AuditWriter audit) {
        this.jdbc=jdbc; this.database=database; this.access=access; this.visibility=visibility; this.mapper=mapper; this.audit=audit;
    }
    private TenantPrincipal prepare() { var p=access.principal(); database.apply(p.tenantId()); return p; }
    private MapSqlParameterSource params() { return new MapSqlParameterSource("tenantId", prepare().tenantId()); }

    public Map<String,Object> context(UUID expectation, boolean write) {
        var p=prepare();
        access.requireAnyPermission("work-record.read", "work-record.submit", "work-record.team-read", "work-record.review");
        visibility.requireExpectation(p, expectation);
        List<Map<String,Object>> rows=jdbc.queryForList("""
                select x.*, i.form_version_id, i.work_package_version_id, a.employee_id, a.org_unit_id,
                       e.account_id, h.name as hotel_name
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join org_unit h on h.tenant_id=x.tenant_id and h.id=x.target_org_unit_id and h.unit_type='HOTEL'
                where x.tenant_id=:tenantId and x.id=:id and i.item_code='GM_W_EMPLOYEE'
                """ + (write ? " for update of x" : ""), params().addValue("id", expectation));
        if (rows.isEmpty()) throw new IllegalArgumentException("员工周评任务不存在");
        Map<String,Object> row=rows.getFirst();
        if (write) {
            access.requirePermission("work-record.submit");
            if (!p.actorId().equals(row.get("account_id")) || !p.assignmentIds().contains(row.get("position_assignment_id"))
                    || (p.businessActorAssignmentId()!=null && !p.businessActorAssignmentId().equals(row.get("position_assignment_id"))))
                throw new AccessDeniedException("仅当前执行任职可以编辑员工周评");
            if (!Set.of("AVAILABLE","IN_PROGRESS","FAILED","MISSED").contains(row.get("status")))
                throw new IllegalArgumentException("本周评估已提交或已结束；退回后才可修改");
        }
        return row;
    }
    private MapSqlParameterSource scope(Map<String,Object> x) {
        return params().addValue("id",x.get("id")).addValue("hotel",x.get("target_org_unit_id"))
                .addValue("start",x.get("period_start")).addValue("end",x.get("period_end"))
                .addValue("executorEmployee",x.get("employee_id"));
    }
    private List<Map<String,Object>> employees(Map<String,Object> x) {
        return jdbc.queryForList("""
                select e.id, e.name, string_agg(distinct p.name,'、' order by p.name) as position_name,
                       string_agg(distinct o.name,'、' order by o.name) as department_name,
                       array_agg(distinct a.id) as assignment_ids
                from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition p on p.tenant_id=a.tenant_id and p.id=a.position_id
                join org_unit o on o.tenant_id=a.tenant_id and o.id=a.org_unit_id
                join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotel
                where a.tenant_id=:tenantId and a.status='ACTIVE' and a.valid_from<=:end
                  and (a.valid_to is null or a.valid_to>=:start) and e.employment_status='ACTIVE' and e.deleted_at is null
                  and e.id<>:executorEmployee and p.code not in ('GENERAL_MANAGER','GROUP_VICE_PRESIDENT','CEO','GROUP_CHAIRMAN')
                group by e.id,e.name order by e.name,e.id
                """, scope(x)).stream().map(row -> { row.remove("assignment_ids"); return row; }).toList();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID expectation) {
        var x=context(expectation,false);
        var assessments=jdbc.queryForList("""
                select employee_id, score, focus, row_version, employee_name_snapshot, position_name_snapshot
                from employee_weekly_assessment where tenant_id=:tenantId and work_expectation_id=:id order by employee_id
                """, scope(x));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("expectation",x); result.put("employees",employees(x)); result.put("assessments",assessments);
        var principal = access.principal();
        result.put("canEdit", principal.hasPermission("work-record.submit")
                && principal.actorId().equals(x.get("account_id"))
                && principal.assignmentIds().contains(x.get("position_assignment_id"))
                && (principal.businessActorAssignmentId() == null
                    || principal.businessActorAssignmentId().equals(x.get("position_assignment_id")))
                && Set.of("AVAILABLE","IN_PROGRESS","FAILED","MISSED").contains(x.get("status")));
        return result;
    }
    @Transactional
    public Map<String,Object> save(UUID expectation, UUID employee, BigDecimal score, String focus, Long expectedVersion) {
        var x=context(expectation,true);
        if (score==null || score.compareTo(BigDecimal.ZERO)<0 || score.compareTo(new BigDecimal("100"))>0 || score.scale()>2)
            throw new IllegalArgumentException("评估分数必须为0至100分，最多两位小数");
        String note=focus==null ? "" : focus.trim();
        if (note.length()>2000) throw new IllegalArgumentException("关注重点最多2000字");
        var employeeRow=employees(x).stream().filter(e -> employee.equals(e.get("id"))).findFirst()
                .orElseThrow(() -> new AccessDeniedException("请选择本门店、本周期的在职员工；不能评价自己"));
        var args=scope(x).addValue("employee",employee).addValue("score",score).addValue("focus",note)
                .addValue("name",employeeRow.get("name")).addValue("position",employeeRow.get("position_name"))
                .addValue("actor",access.principal().actorId()).addValue("version",expectedVersion);
        int changed;
        if (expectedVersion==null) {
            changed=jdbc.update("""
                insert into employee_weekly_assessment(tenant_id,hotel_id,employee_id,week_start,work_expectation_id,
                  score,focus,employee_name_snapshot,position_name_snapshot,assessed_by_account_id)
                values(:tenantId,:hotel,:employee,:start,:id,:score,:focus,:name,:position,:actor)
                on conflict (tenant_id,hotel_id,employee_id,week_start) do nothing
                """,args);
        } else {
            changed=jdbc.update("""
                update employee_weekly_assessment set score=:score, focus=:focus, assessed_by_account_id=:actor,
                  updated_at=now(), row_version=row_version+1
                where tenant_id=:tenantId and work_expectation_id=:id and employee_id=:employee and row_version=:version
                """,args);
        }
        if (changed!=1) throw new IllegalArgumentException("该员工本周评估已存在或已被修改，请刷新后重试");
        audit.record("EMPLOYEE_WEEKLY_ASSESSMENT_SAVED","WORK_EXPECTATION",expectation,
                "{\"employeeId\":\""+employee+"\"}");
        return Map.of("rowVersion",expectedVersion==null ? 0 : expectedVersion+1);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> reference(UUID expectation, UUID employee) {
        var x=context(expectation,false);
        if (employees(x).stream().noneMatch(e -> employee.equals(e.get("id"))) &&
                jdbc.queryForObject("select count(*) from employee_weekly_assessment where tenant_id=:tenantId and work_expectation_id=:id and employee_id=:employee",
                        scope(x).addValue("employee",employee),Integer.class)==0)
            throw new AccessDeniedException("该员工不属于此门店评估范围");
        var args=scope(x).addValue("employee",employee);
        var metrics=jdbc.queryForMap("""
                select count(*) as expected,
                  count(*) filter(where r.status in ('SUBMITTED','APPROVED')) as submitted,
                  count(*) filter(where r.status='APPROVED' or (r.status='SUBMITTED' and (
                """ + cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy.effectiveModeSql("i", "x") + """
                  )='NONE')) as completed,
                  count(*) filter(where r.status in ('SUBMITTED','APPROVED') and r.submitted_at<=x.due_at) as on_time,
                  count(*) filter(where r.status in ('SUBMITTED','APPROVED') and r.submitted_at>x.due_at) as late,
                  count(*) filter(where coalesce(r.status,'') not in ('SUBMITTED','APPROVED') and x.due_at<now()) as missed
                from work_expectation x
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id
                join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                left join lateral(select status,submitted_at from work_record r where r.tenant_id=x.tenant_id
                  and r.work_expectation_id=x.id order by r.attempt_no desc,r.created_at desc limit 1) r on true
                where x.tenant_id=:tenantId and a.employee_id=:employee and x.business_date between :start and :end
                  and x.status not in ('CANCELLED','WAIVED') and i.required
                  and coalesce(i.execution_policy->>'registrationMode','')<>'WHEN_OCCURRED'
                  and exists(select 1 from org_unit_closure c where c.tenant_id=x.tenant_id
                    and c.ancestor_id=:hotel and c.descendant_id=x.target_org_unit_id)
                """,args);
        var history=jdbc.queryForList("""
                select a.week_start,a.score,a.focus,x.status from employee_weekly_assessment a
                join work_expectation x on x.tenant_id=a.tenant_id and x.id=a.work_expectation_id
                where a.tenant_id=:tenantId and a.hotel_id=:hotel and a.employee_id=:employee
                  and a.week_start<:start order by a.week_start desc limit 160
                """,args);
        var records=jdbc.queryForList("""
                select x.id,i.name,x.status,x.business_date from work_expectation x
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id
                join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                where x.tenant_id=:tenantId and a.employee_id=:employee and x.business_date between :start and :end
                  and x.status not in ('CANCELLED','WAIVED')
                  and exists(select 1 from org_unit_closure c where c.tenant_id=x.tenant_id and c.ancestor_id=:hotel and c.descendant_id=x.target_org_unit_id)
                order by x.business_date desc,x.due_at limit 1000
                """,args);
        return Map.of("metrics",metrics,"history",history,"records",records);
    }
    public JsonNode snapshot(UUID expectation) {
        var x=context(expectation,true);
        var rows=jdbc.queryForList("""
                select employee_id::text as "employeeId",employee_name_snapshot as "employeeName",
                       position_name_snapshot as "positionName",score,focus
                from employee_weekly_assessment where tenant_id=:tenantId and work_expectation_id=:id order by employee_id
                """,scope(x));
        if (rows.isEmpty()) throw new IllegalArgumentException("请至少保存一位员工的评估；未评员工不会记为零分");
        var result=mapper.createObjectNode();
        result.set("employeeAssessments",mapper.valueToTree(rows));
        result.put("periodLabel",x.get("period_start")+" 至 "+x.get("period_end"));
        return result;
    }
    /** Also called by the generic record API, preventing empty/forged score submissions. */
    public void validateSubmission(String itemCode, UUID expectation, JsonNode payload) {
        if (!"GM_W_EMPLOYEE".equals(itemCode)) return;
        if (expectation==null) throw new IllegalArgumentException("员工周评必须关联周期工作");
        JsonNode actual=snapshot(expectation).path("employeeAssessments");
        if (!actual.equals(payload.path("employeeAssessments")))
            throw new IllegalArgumentException("员工评估已更新，请回到员工绩效评估页面汇总并提交");
    }
}
