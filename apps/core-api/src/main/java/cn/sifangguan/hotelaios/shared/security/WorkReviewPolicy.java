package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

/** Ordinary work/report management only. Operation permissions and special
 * inventory/cycle reviewers must be checked by the caller before this policy. */
public final class WorkReviewPolicy {
    public static String configuredModeSql(String item, String expectation) {
        return "work_review_configured_mode(" + item + ", " + expectation + ")";
    }

    /** Rejected exempt work remains manual until a manager approves a correction. */
    public static String effectiveModeSql(String item, String expectation) {
        return """
            case when %3$s = 'NONE' and (
              %1$s.item_type in ('REVIEW_APPROVAL','METRIC_REVIEW')
              or coalesce(%1$s.execution_policy ->> 'handoverConfirmationRequired','false') = 'true'
              or coalesce(%1$s.execution_policy ->> 'highRiskImmediateEscalation','false') = 'true'
              or coalesce(%1$s.execution_policy ->> 'inventoryUsage','false') = 'true'
              or coalesce(%1$s.submission_policy ->> 'inventoryUsage','false') = 'true'
              or nullif(btrim(%1$s.execution_policy ->> 'inventoryMovement'),'') is not null
              or %1$s.item_code in ('HKA_ITEM_ISSUE','HKA_USAGE_RETURN','HKA_DAILY_REPORT','HKA_LOST_PROPERTY','HKA_COMPLAINT_EVENT',
                                    'NSA_ITEM_ISSUE','NSA_DAILY_HANDOVER','NSA_LOST_PROPERTY')
              or ((coalesce(%1$s.execution_policy ->> 'requiresExceptionReview','false')='true'
                    or %1$s.item_code='HKA_CART_PREPARATION')
                and exists (select 1 from work_record exception_record
                  where exception_record.tenant_id=%2$s.tenant_id and exception_record.work_expectation_id=%2$s.id
                    and exception_record.id=(select latest.id from work_record latest
                      where latest.tenant_id=%2$s.tenant_id and latest.work_expectation_id=%2$s.id
                      order by latest.series_entry_no desc,latest.attempt_no desc,latest.id desc limit 1)
                    and (btrim(coalesce(exception_record.exception_statement,'')) not in ('','无','无异常','正常','未发现问题')
                      or exception_record.payload ->> 'issueFound' = 'true'
                      or btrim(coalesce(exception_record.payload ->> 'issueSummary','')) not in ('','无','无异常','正常','未发现问题'))))
              or (%2$s.status <> 'SATISFIED' and exists (select 1 from work_record failed_review
                where failed_review.tenant_id = %2$s.tenant_id
                  and failed_review.work_expectation_id = %2$s.id and failed_review.status = 'REJECTED'))
            ) then 'MANUAL' else %3$s end
            """.formatted(item, expectation, configuredModeSql(item, expectation));
    }

    private final NamedParameterJdbcTemplate jdbc;
    public WorkReviewPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean canManage(TenantPrincipal principal, UUID executorId, UUID targetOrgId) {
        UUID reviewerId = principal.businessActorAssignmentId();
        if (reviewerId == null && principal.assignmentIds().size() == 1) reviewerId = principal.assignmentIds().iterator().next();
        if (reviewerId == null || !principal.assignmentIds().contains(reviewerId)
                || reviewerId.equals(executorId) || targetOrgId == null
                || (!principal.hasTenantScope() && !principal.orgScopes().contains(targetOrgId))) return false;
        Boolean allowed = jdbc.queryForObject("""
                select exists (
                  select 1 from employee_position_assignment reviewer
                  join employee re on re.tenant_id = reviewer.tenant_id and re.id = reviewer.employee_id
                  join user_account account on account.tenant_id = re.tenant_id and account.id = re.account_id
                  join position_definition rp on rp.tenant_id = reviewer.tenant_id and rp.id = reviewer.position_id
                  join employee_position_assignment executor on executor.tenant_id = reviewer.tenant_id and executor.id = :executor
                  join employee ee on ee.tenant_id = executor.tenant_id and ee.id = executor.employee_id
                  left join position_function_profile rf on rf.tenant_id = reviewer.tenant_id and rf.position_id = reviewer.position_id and rf.scope_type = 'GROUP'
                  left join app_role rr on rr.tenant_id = rf.tenant_id and rr.id = rf.default_role_id and rr.role_type = 'SYSTEM'
                  left join position_function_profile ef on ef.tenant_id = executor.tenant_id and ef.position_id = executor.position_id and ef.scope_type = 'GROUP'
                  left join app_role er on er.tenant_id = ef.tenant_id and er.id = ef.default_role_id and er.role_type = 'SYSTEM'
                  where reviewer.tenant_id = :tenant and reviewer.id = :reviewer and re.account_id = :actor
                    and ee.account_id is distinct from :actor and ee.id <> re.id
                    and reviewer.status = 'ACTIVE' and reviewer.valid_from <= current_date
                    and (reviewer.valid_to is null or reviewer.valid_to >= current_date)
                    and re.employment_status = 'ACTIVE' and re.deleted_at is null and account.status = 'ACTIVE'
                    and rp.status = 'ACTIVE' and rp.deleted_at is null and rp.permanently_deleted_at is null
                    and (
                      executor.manager_assignment_id = reviewer.id
                      or exists (select 1 from position_assignment_reporting_relation rel
                        where rel.tenant_id = reviewer.tenant_id and rel.leader_assignment_id = reviewer.id
                          and rel.subordinate_assignment_id = executor.id and rel.valid_from <= current_date
                          and (rel.valid_to is null or rel.valid_to >= current_date))
                      or (rr.code = 'GROUP_VICE_PRESIDENT' and er.code = 'GENERAL_MANAGER'
                        and exists (select 1 from employee_assignment_hotel_scope portfolio
                          join org_unit_closure tree on tree.tenant_id = portfolio.tenant_id and tree.ancestor_id = portfolio.hotel_org_unit_id
                          where portfolio.tenant_id = reviewer.tenant_id and portfolio.assignment_id = reviewer.id
                            and tree.descendant_id = :target))
                      or (rr.code = 'GENERAL_MANAGER' and er.code in (
                          'ASSISTANT_GENERAL_MANAGER','FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR',
                          'FRONT_DESK','SERVICE_STEWARD','HOUSEKEEPING_ATTENDANT','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
                        and exists (select 1 from org_unit hotel
                          join org_unit_closure reviewer_tree on reviewer_tree.tenant_id = hotel.tenant_id and reviewer_tree.ancestor_id = hotel.id
                          join org_unit_closure executor_tree on executor_tree.tenant_id = hotel.tenant_id and executor_tree.ancestor_id = hotel.id
                          join org_unit_closure target_tree on target_tree.tenant_id = hotel.tenant_id and target_tree.ancestor_id = hotel.id
                          where hotel.tenant_id = reviewer.tenant_id and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                            and reviewer_tree.descendant_id = reviewer.org_unit_id
                            and executor_tree.descendant_id = executor.org_unit_id and target_tree.descendant_id = :target))
                    )
                )
                """, new MapSqlParameterSource("tenant", principal.tenantId()).addValue("reviewer", reviewerId)
                .addValue("actor", principal.actorId()).addValue("executor", executorId).addValue("target", targetOrgId), Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
