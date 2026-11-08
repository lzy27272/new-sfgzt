package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;
import java.util.Set;

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
              or coalesce(%1$s.execution_policy ->> 'reworkReinspectionRequired','false') = 'true'
              or coalesce(%1$s.execution_policy ->> 'inventoryUsage','false') = 'true'
              or coalesce(%1$s.submission_policy ->> 'inventoryUsage','false') = 'true'
              or nullif(btrim(%1$s.execution_policy ->> 'inventoryMovement'),'') is not null
              or exists (select 1 from store_work_usage usage
                join work_record usage_record on usage_record.tenant_id=usage.tenant_id and usage_record.id=usage.work_record_id
                where usage_record.tenant_id=%2$s.tenant_id and usage_record.work_expectation_id=%2$s.id)
              or %1$s.item_code in ('HKA_ITEM_ISSUE','HKA_USAGE_RETURN','HKA_DAILY_REPORT','HKA_LOST_PROPERTY','HKA_COMPLAINT_EVENT',
                                    'NSA_ITEM_ISSUE','NSA_DAILY_HANDOVER','NSA_LOST_PROPERTY')
              or ((coalesce(%1$s.execution_policy ->> 'requiresExceptionReview','false')='true'
                    or work_review_routine_exemption_eligible(%1$s))
                and exists (select 1 from work_record exception_record
                  where exception_record.tenant_id=%2$s.tenant_id and exception_record.work_expectation_id=%2$s.id
                    and exception_record.id=(select latest.id from work_record latest
                      where latest.tenant_id=%2$s.tenant_id and latest.work_expectation_id=%2$s.id
                      order by latest.series_entry_no desc,latest.attempt_no desc,latest.id desc limit 1)
                    and work_review_record_has_exception(exception_record)))
              or (%2$s.status <> 'SATISFIED' and exists (select 1 from work_record failed_review
                where failed_review.tenant_id = %2$s.tenant_id
                  and failed_review.work_expectation_id = %2$s.id and failed_review.status = 'REJECTED'))
            ) then 'MANUAL' else %3$s end
            """.formatted(item, expectation, configuredModeSql(item, expectation));
    }

    private final NamedParameterJdbcTemplate jdbc;
    public WorkReviewPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    static boolean usesHotelManagementDefault(TenantPrincipal principal) {
        return principal.roleCodes().stream().noneMatch(Set.of("GROUP_CHAIRMAN", "CEO", "GROUP_VICE_PRESIDENT")::contains)
                && principal.roleCodes().stream().anyMatch(Set.of("GENERAL_MANAGER", "ASSISTANT_GENERAL_MANAGER",
                "FRONT_OFFICE_SUPERVISOR", "HOUSEKEEPING_SUPERVISOR")::contains);
    }

    static MapSqlParameterSource managementParameters(TenantPrincipal principal, MapSqlParameterSource params) {
        UUID assignment = principal.businessActorAssignmentId();
        if (assignment == null && principal.assignmentIds().size() == 1) assignment = principal.assignmentIds().iterator().next();
        return params.addValue("managementTenant", principal.tenantId()).addValue("managementActor", principal.actorId())
                .addValue("managementAssignment", assignment != null && principal.assignmentIds().contains(assignment) ? assignment : null)
                .addValue("managementTenantScope", principal.hasTenantScope())
                .addValue("managementScopes", principal.orgScopes().isEmpty() ? Set.of(new UUID(0, 0)) : principal.orgScopes());
    }

    /** Current appointments inherit ordinary hotel management; old reporting pointers do not own the role. */
    static String hotelManagementSql(String executor, String target) {
        return """
                exists (select 1 from employee_position_assignment current_manager
                  join employee manager_employee on manager_employee.tenant_id=current_manager.tenant_id
                    and manager_employee.id=current_manager.employee_id and manager_employee.account_id=:managementActor
                  join user_account manager_account on manager_account.tenant_id=manager_employee.tenant_id
                    and manager_account.id=manager_employee.account_id and manager_account.status='ACTIVE'
                  join position_definition manager_position on manager_position.tenant_id=current_manager.tenant_id
                    and manager_position.id=current_manager.position_id and manager_position.status='ACTIVE'
                    and manager_position.deleted_at is null and manager_position.permanently_deleted_at is null
                  join org_unit manager_org on manager_org.tenant_id=current_manager.tenant_id
                    and manager_org.id=current_manager.org_unit_id and manager_org.status='ACTIVE'
                  join position_function_profile manager_profile on manager_profile.tenant_id=current_manager.tenant_id
                    and manager_profile.position_id=current_manager.position_id and manager_profile.scope_type='GROUP'
                  join app_role manager_role on manager_role.tenant_id=manager_profile.tenant_id
                    and manager_role.id=manager_profile.default_role_id and manager_role.role_type='SYSTEM'
                  join employee target_employee on target_employee.tenant_id=%1$s.tenant_id
                    and target_employee.id=%1$s.employee_id and target_employee.account_id is distinct from :managementActor
                    and target_employee.id<>manager_employee.id
                  join position_function_profile target_profile on target_profile.tenant_id=%1$s.tenant_id
                    and target_profile.position_id=%1$s.position_id and target_profile.scope_type='GROUP'
                  join app_role target_role on target_role.tenant_id=target_profile.tenant_id and target_role.id=target_profile.default_role_id
                  join org_unit hotel on hotel.tenant_id=current_manager.tenant_id and hotel.unit_type='HOTEL' and hotel.status='ACTIVE'
                  join org_unit_closure manager_hotel on manager_hotel.tenant_id=hotel.tenant_id and manager_hotel.ancestor_id=hotel.id
                    and manager_hotel.descendant_id=current_manager.org_unit_id
                  join org_unit_closure employee_hotel on employee_hotel.tenant_id=hotel.tenant_id and employee_hotel.ancestor_id=hotel.id
                    and employee_hotel.descendant_id=%1$s.org_unit_id
                  join org_unit_closure target_hotel on target_hotel.tenant_id=hotel.tenant_id and target_hotel.ancestor_id=hotel.id
                    and target_hotel.descendant_id=%2$s
                  where current_manager.tenant_id=:managementTenant and %1$s.tenant_id=:managementTenant
                    and current_manager.id=:managementAssignment and current_manager.status='ACTIVE'
                    and current_manager.valid_from<=current_date and (current_manager.valid_to is null or current_manager.valid_to>=current_date)
                    and manager_employee.employment_status='ACTIVE' and manager_employee.deleted_at is null
                    and (:managementTenantScope or %2$s in (:managementScopes))
                    and ((manager_role.code='GENERAL_MANAGER' and (target_role.role_type='CUSTOM' or (target_role.role_type='SYSTEM'
                        and target_role.code in ('ASSISTANT_GENERAL_MANAGER','FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR',
                          'FRONT_DESK','SERVICE_STEWARD','HOUSEKEEPING_ATTENDANT','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD'))))
                      or (manager_role.code='ASSISTANT_GENERAL_MANAGER' and (target_role.role_type='CUSTOM' or (target_role.role_type='SYSTEM'
                        and target_role.code in ('FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR','FRONT_DESK','SERVICE_STEWARD',
                          'HOUSEKEEPING_ATTENDANT','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD'))))
                      or (target_role.role_type='SYSTEM' and ((manager_role.code='FRONT_OFFICE_SUPERVISOR'
                          and target_role.code in ('FRONT_DESK','SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD'))
                        or (manager_role.code='HOUSEKEEPING_SUPERVISOR' and target_role.code='HOUSEKEEPING_ATTENDANT'))
                        and exists(select 1 from org_unit_closure department where department.tenant_id=current_manager.tenant_id
                          and department.ancestor_id=current_manager.org_unit_id and department.descendant_id=%1$s.org_unit_id)
                        and not exists(select 1 from employee_position_assignment other_manager
                          join employee other_employee on other_employee.tenant_id=other_manager.tenant_id and other_employee.id=other_manager.employee_id
                            and other_employee.employment_status='ACTIVE' and other_employee.deleted_at is null
                          join user_account other_account on other_account.tenant_id=other_employee.tenant_id
                            and other_account.id=other_employee.account_id and other_account.status='ACTIVE'
                          join position_definition other_position on other_position.tenant_id=other_manager.tenant_id
                            and other_position.id=other_manager.position_id and other_position.status='ACTIVE'
                            and other_position.deleted_at is null and other_position.permanently_deleted_at is null
                          join org_unit other_org on other_org.tenant_id=other_manager.tenant_id and other_org.id=other_manager.org_unit_id and other_org.status='ACTIVE'
                          where other_manager.tenant_id=%1$s.tenant_id and other_manager.id<>current_manager.id
                            and other_manager.status='ACTIVE' and other_manager.valid_from<=current_date
                            and (other_manager.valid_to is null or other_manager.valid_to>=current_date)
                            and (other_manager.id=%1$s.manager_assignment_id or exists(select 1 from position_assignment_reporting_relation configured
                              where configured.tenant_id=%1$s.tenant_id and configured.subordinate_assignment_id=%1$s.id
                                and configured.leader_assignment_id=other_manager.id and configured.valid_from<=current_date
                                and (configured.valid_to is null or configured.valid_to>=current_date)))))))
                """.formatted(executor, target);
    }

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
                      or %s
                    )
                )
                """.formatted(hotelManagementSql("executor", ":target")), managementParameters(principal,
                new MapSqlParameterSource("tenant", principal.tenantId()).addValue("reviewer", reviewerId)
                .addValue("actor", principal.actorId()).addValue("executor", executorId).addValue("target", targetOrgId)), Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
