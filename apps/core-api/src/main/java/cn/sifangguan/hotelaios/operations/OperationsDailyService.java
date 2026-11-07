package cn.sifangguan.hotelaios.operations;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/** Read projections over the existing work-expectation/record lifecycle. */
@Service
public class OperationsDailyService {
    private static final Set<String> DAILY_POSITIONS=Set.of("OPERATIONS_DIRECTOR","OPERATIONS_MANAGER");
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TenantDatabaseContext database;

    public OperationsDailyService(NamedParameterJdbcTemplate jdbc, AccessPolicy access, TenantDatabaseContext database) {
        this.jdbc=jdbc; this.access=access; this.database=database;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> context() {
        var p=prepare();
        var result=assignment(p);
        UUID assignmentId=(UUID)result.get("assignmentId");
        String position=String.valueOf(result.get("positionCode"));
        List<Map<String,Object>> hotels=assignmentId==null ? List.of() : jdbc.queryForList("""
                select hotel.id,hotel.name from employee_assignment_hotel_scope scope
                join org_unit hotel on hotel.tenant_id=scope.tenant_id and hotel.id=scope.hotel_org_unit_id
                where scope.tenant_id=:tenantId and scope.assignment_id=:assignmentId
                  and hotel.unit_type='HOTEL' and hotel.status='ACTIVE' order by hotel.name,hotel.id
                """,base(p).addValue("assignmentId",assignmentId));
        boolean daily=DAILY_POSITIONS.contains(position);
        result.put("hotels",hotels);
        result.put("roleCode",p.roleCode());
        result.put("canSubmitPlan",daily && permitted(p,"work-plan.submit"));
        result.put("canReviewPlan",permitted(p,"work-plan.review"));
        result.put("canReadPlan",(daily && permitted(p,"work-plan.read")) || permitted(p,"work-plan.review") || permitted(p,"work-plan.team-read"));
        result.put("dailyTemplateStatus",daily && !hotels.isEmpty() ? "READY" : "PENDING_CONFIGURATION");
        result.put("dailyTemplateNotice",!daily ? "该岗位日清清单尚未配置；不自动套用其他岗位工作。"
                : hotels.isEmpty() ? "请在人员与任职中配置负责门店，不会默认开放全店。" : "每家负责门店每日7项，两个岗位分别执行、分别提交。" );
        result.put("portfolioConfigurationNotice",Set.of("OTA_OPERATION_MANAGER","OTA_OPERATION_ASSISTANT").contains(position)
                && !"ASSIGNED_HOTELS".equals(result.get("authorizationScopeType"))
                ? "旧岗位已有当前或未来任职，已保留原数据范围；管理员需先在岗位与功能发布指定负责门店范围，再修改人员任职。" : "");
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> daily(LocalDate businessDate) {
        var p=prepare();
        var own=assignment(p);
        if (businessDate==null) {
            String timezone=jdbc.queryForObject("select timezone from tenant where id=:tenantId",base(p),String.class);
            businessDate=LocalDate.now(ZoneId.of(timezone));
        }
        UUID assignmentId=(UUID)own.get("assignmentId");
        if (assignmentId==null || !DAILY_POSITIONS.contains(String.valueOf(own.get("positionCode")))) {
            return Map.of("businessDate",businessDate,"items",List.of());
        }
        var params=base(p).addValue("assignmentId",assignmentId).addValue("date",businessDate);
        var rows=jdbc.query("""
                select x.id,x.position_assignment_id,x.target_org_unit_id,hotel.name as hotel_name,
                       i.item_code,i.name,i.form_version_id,i.execution_policy->>'plannedTarget' as target_time,
                       x.available_at,x.due_at,
                       x.due_at-make_interval(mins=>coalesce((i.reminder_policy->>'dueSoonMinutes')::integer,0)) as reminder_at,
                       x.due_at+make_interval(mins=>coalesce((i.reminder_policy->>'escalationMinutes')::integer,20)) as escalation_at,
                       r.id as record_id,
                       case when r.status='APPROVED' then 'COMPLETED'
                         when r.status='SUBMITTED' then 'REVIEW'
                         when x.due_at<=now() then 'OVERDUE' else 'PENDING' end as board_status
                from work_expectation x
                join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join org_unit hotel on hotel.tenant_id=x.tenant_id and hotel.id=x.target_org_unit_id
                left join lateral(select record.id,record.status from work_record record
                  where record.tenant_id=x.tenant_id and record.work_expectation_id=x.id
                  order by record.series_entry_no desc,record.attempt_no desc,record.id desc limit 1)r on true
                where x.tenant_id=:tenantId and x.position_assignment_id=:assignmentId and x.business_date=:date
                  and coalesce((i.execution_policy->>'operationsDaily')::boolean,false)
                  and x.status not in ('CANCELLED','WAIVED')
                order by hotel.name,i.sort_order,x.id
                """,params,(rs,row)->{
                    Map<String,Object> item=new LinkedHashMap<>();
                    item.put("expectationId",rs.getObject("id",UUID.class));
                    item.put("assignmentId",rs.getObject("position_assignment_id",UUID.class));
                    item.put("hotelId",rs.getObject("target_org_unit_id",UUID.class));
                    item.put("hotelName",rs.getString("hotel_name"));
                    item.put("itemCode",rs.getString("item_code"));
                    item.put("name",rs.getString("name"));
                    item.put("formVersionId",rs.getObject("form_version_id",UUID.class));
                    item.put("targetTime",rs.getString("target_time"));
                    item.put("availableAt",rs.getObject("available_at",OffsetDateTime.class));
                    item.put("dueAt",rs.getObject("due_at",OffsetDateTime.class));
                    item.put("reminderAt",rs.getObject("reminder_at",OffsetDateTime.class));
                    item.put("escalationAt",rs.getObject("escalation_at",OffsetDateTime.class));
                    item.put("recordId",rs.getObject("record_id",UUID.class));
                    item.put("boardStatus",rs.getString("board_status"));
                    return item;
                });
        return Map.of("businessDate",businessDate,"items",rows);
    }

    private Map<String,Object> assignment(TenantPrincipal p) {
        UUID id=p.businessActorAssignmentId();
        if(id==null && p.assignmentIds().size()==1)id=p.assignmentIds().iterator().next();
        if(id==null || !p.assignmentIds().contains(id)) return emptyAssignment(p);
        var rows=jdbc.queryForList("""
                select a.id as "assignmentId",p.code as "positionCode",p.name as "positionName",
                       profile.authorization_scope_type as "authorizationScopeType"
                from employee_position_assignment a join position_definition p on p.tenant_id=a.tenant_id and p.id=a.position_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                left join lateral(select v.authorization_scope_type from position_function_profile f
                  join position_function_profile_version v on v.tenant_id=f.tenant_id and v.profile_id=f.id and v.lifecycle_status='PUBLISHED'
                  where f.tenant_id=p.tenant_id and f.position_id=p.id and f.scope_type='GROUP'
                  order by v.version_no desc limit 1)profile on true
                where a.tenant_id=:tenantId and a.id=:assignmentId and e.account_id=:actorId
                  and a.status='ACTIVE' and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                  and p.status='ACTIVE' and p.deleted_at is null and p.permanently_deleted_at is null
                  and e.employment_status='ACTIVE' and e.deleted_at is null
                """,base(p).addValue("assignmentId",id).addValue("actorId",p.actorId()));
        return rows.isEmpty()?emptyAssignment(p):new LinkedHashMap<>(rows.getFirst());
    }

    private Map<String,Object> emptyAssignment(TenantPrincipal p) {
        Map<String,Object> empty=new LinkedHashMap<>();
        empty.put("assignmentId",null); empty.put("positionCode",p.roleCode());empty.put("positionName",p.roleCode());
        return empty;
    }
    private boolean permitted(TenantPrincipal p,String permission) { return p.hasPermission("*") || p.hasPermission(permission); }
    private TenantPrincipal prepare() {
        access.requireAnyPermission("work-record.read","work-record.submit","work-plan.read","work-plan.review","work-plan.team-read");
        var p=access.principal();database.apply(p.tenantId());return p;
    }
    private MapSqlParameterSource base(TenantPrincipal p) { return new MapSqlParameterSource("tenantId",p.tenantId()); }
}
