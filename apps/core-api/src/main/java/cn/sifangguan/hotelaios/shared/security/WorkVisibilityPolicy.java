package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Read-only work visibility, not organization authorization or review authority.
 * A hotel scope is a boundary, never by itself a grant to read every employee.
 * Callers must apply tenant context and require the relevant operation permission.
 */
@Component
public class WorkVisibilityPolicy {
    private static final Set<String> FRONT = Set.of("FRONT_DESK", "SERVICE_STEWARD",
            "NIGHT_SERVICE_ATTENDANT", "BREAKFAST_AREA_LEAD");
    private static final Set<String> HOUSEKEEPING = Set.of("HOUSEKEEPING_ATTENDANT");
    private static final Set<String> HOTEL_TEAM = union(FRONT, HOUSEKEEPING,
            Set.of("FRONT_OFFICE_SUPERVISOR", "HOUSEKEEPING_SUPERVISOR"));
    private static final Set<String> HOTEL_MANAGEMENT = union(HOTEL_TEAM,
            Set.of("GENERAL_MANAGER", "ASSISTANT_GENERAL_MANAGER"));
    private static final Set<String> GROUP_TEAM = union(HOTEL_MANAGEMENT, Set.of(
            "GROUP_VICE_PRESIDENT", "HR_ADMINISTRATION_SUPERVISOR", "HR_ADMINISTRATION",
            "OTA_OPERATION_MANAGER", "OTA_OPERATION_ASSISTANT", "OPERATIONS_DIRECTOR", "OPERATIONS_MANAGER"));
    // Compatibility for built-in positions without configured reporting links.
    // Custom positions require explicit reporting links; names/legacy levels grant nothing.
    private static final Map<String, Set<String>> SUBORDINATE_ROLES = Map.ofEntries(
            Map.entry("GROUP_CHAIRMAN", union(GROUP_TEAM, Set.of("CEO"))),
            Map.entry("CEO", GROUP_TEAM),
            Map.entry("GROUP_VICE_PRESIDENT", HOTEL_MANAGEMENT),
            Map.entry("GENERAL_MANAGER", union(HOTEL_TEAM, Set.of("ASSISTANT_GENERAL_MANAGER"))),
            Map.entry("ASSISTANT_GENERAL_MANAGER", HOTEL_TEAM),
            Map.entry("FRONT_OFFICE_SUPERVISOR", FRONT),
            Map.entry("HOUSEKEEPING_SUPERVISOR", HOUSEKEEPING),
            Map.entry("OTA_OPERATION_MANAGER", Set.of("OTA_OPERATION_ASSISTANT")),
            Map.entry("OPERATIONS_DIRECTOR", Set.of("OPERATIONS_MANAGER", "OTA_OPERATION_ASSISTANT")),
            Map.entry("HR_ADMINISTRATION_SUPERVISOR", Set.of("HR_ADMINISTRATION")));

    private final NamedParameterJdbcTemplate jdbc;

    public WorkVisibilityPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Scope scope(TenantPrincipal principal, MapSqlParameterSource params) {
        return scope(principal, params, principal.hasPermission("*") || principal.hasPermission("work-record.review")
                || principal.hasPermission("work-record.team-read"));
    }

    public Scope reportScope(TenantPrincipal principal, MapSqlParameterSource params) {
        return scope(principal, params, principal.hasPermission("*") || principal.hasPermission("daily-report.team-read"));
    }

    private Scope scope(TenantPrincipal principal, MapSqlParameterSource params, boolean team) {
        Set<UUID> own = principal.businessActorAssignmentId() == null
                ? principal.assignmentIds()
                : principal.assignmentIds().contains(principal.businessActorAssignmentId())
                    ? Set.of(principal.businessActorAssignmentId()) : Set.of();
        boolean administrator = team && (principal.hasRole("PLATFORM_ADMIN") || principal.hasRole("GROUP_ADMIN"));
        Set<UUID> managed = new LinkedHashSet<>();
        params.addValue("workOwnIds", nonempty(own));
        params.addValue("workReadScopes", nonempty(principal.orgScopes()));
        params.addValue("workTenantScope", principal.hasTenantScope());
        if (team && !administrator) {
            Set<String> subordinateRoles = new LinkedHashSet<>();
            principal.roleCodes().forEach(role -> subordinateRoles.addAll(
                    SUBORDINATE_ROLES.getOrDefault(role, Set.of())));
            params.addValue("workSubordinateRoles", subordinateRoles.isEmpty()
                    ? Set.of("__NO_SUBORDINATES__") : subordinateRoles);
            // Headquarters' explicitly authorized hotel portfolio is independent of
            // a hotel's direct manager link. Local teams instead honor configured leaders.
            params.addValue("workPortfolioRoles", principal.hasRole("GENERAL_MANAGER")
                    ? SUBORDINATE_ROLES.get("GENERAL_MANAGER") : principal.roleCodes().stream().anyMatch(
                    Set.of("GROUP_CHAIRMAN", "CEO", "GROUP_VICE_PRESIDENT")::contains)
                    ? HOTEL_MANAGEMENT : Set.of("__NO_PORTFOLIO__"));
            managed.addAll(jdbc.queryForList("""
                    with recursive edges as (
                        select id as subordinate_id, manager_assignment_id as leader_id
                        from employee_position_assignment where tenant_id = :tenantId
                        union
                        select subordinate_assignment_id, leader_assignment_id
                        from position_assignment_reporting_relation
                        where tenant_id = :tenantId and valid_from <= current_date
                          and (valid_to is null or valid_to >= current_date)
                    ), roots(id) as (
                        select id from employee_position_assignment
                        where tenant_id = :tenantId and id in (:workOwnIds)
                        union
                        select a.id from employee_position_assignment a
                        join position_function_profile p on p.tenant_id = a.tenant_id
                          and p.position_id = a.position_id and p.scope_type = 'GROUP'
                        join app_role r on r.tenant_id = p.tenant_id and r.id = p.default_role_id
                        where a.tenant_id = :tenantId and r.role_type = 'SYSTEM'
                          and r.code in (:workSubordinateRoles)
                          and (:workTenantScope or a.org_unit_id in (:workReadScopes))
                          and (r.code in (:workPortfolioRoles) or (
                            a.manager_assignment_id is null and not exists (
                              select 1 from position_assignment_reporting_relation configured
                              where configured.tenant_id = a.tenant_id
                                and configured.subordinate_assignment_id = a.id)))
                    ), descendants(id) as (
                        select id from roots
                        union
                        select edge.subordinate_id from edges edge
                        join descendants leader on leader.id = edge.leader_id
                    )
                    select id from descendants
                    """, params, UUID.class));
            managed.removeAll(own);
        }
        params.addValue("workManagedIds", nonempty(managed));
        usageParameters(principal, params);
        return new Scope(administrator, principal.hasTenantScope(), !own.isEmpty(), !managed.isEmpty(), usageReviewer(principal));
    }

    private static boolean usageReviewer(TenantPrincipal principal) {
        return principal.businessActorAssignmentId() != null
                && principal.assignmentIds().contains(principal.businessActorAssignmentId())
                && principal.hasPermission("work-record.review") && (principal.hasPermission("store-resource.usage-review")
                    || (principal.hasPermission("store-resource.read") && principal.hasPermission("ui.module.store-resources")));
    }

    private static MapSqlParameterSource usageParameters(TenantPrincipal principal, MapSqlParameterSource params) {
        return params.addValue("workUsageActorId", principal.actorId())
                .addValue("workUsageAssignmentId", principal.businessActorAssignmentId())
                .addValue("workReadScopes", nonempty(principal.orgScopes()))
                .addValue("workTenantScope", principal.hasTenantScope());
    }

    // Explicit review grants apply to these rows, never to the employee's other work.
    private static String usageMember(String hotel) {
        return """
                exists (select 1 from store_usage_review_config usage_config
                  join employee_position_assignment usage_reviewer on usage_reviewer.tenant_id=usage_config.tenant_id
                    and usage_reviewer.id=:workUsageAssignmentId
                  join employee reviewer_employee on reviewer_employee.tenant_id=usage_reviewer.tenant_id
                    and reviewer_employee.id=usage_reviewer.employee_id and reviewer_employee.account_id=:workUsageActorId
                  join user_account reviewer_account on reviewer_account.tenant_id=reviewer_employee.tenant_id
                    and reviewer_account.id=reviewer_employee.account_id and reviewer_account.status='ACTIVE'
                  join position_definition reviewer_position on reviewer_position.tenant_id=usage_reviewer.tenant_id
                    and reviewer_position.id=usage_reviewer.position_id and reviewer_position.status='ACTIVE'
                    and reviewer_position.deleted_at is null and reviewer_position.permanently_deleted_at is null
                  join org_unit review_hotel on review_hotel.tenant_id=usage_config.tenant_id
                    and review_hotel.id=usage_config.hotel_org_unit_id and review_hotel.unit_type='HOTEL' and review_hotel.status='ACTIVE'
                  join org_unit_closure reviewer_hotel on reviewer_hotel.tenant_id=usage_reviewer.tenant_id
                    and reviewer_hotel.descendant_id=usage_reviewer.org_unit_id and reviewer_hotel.ancestor_id=review_hotel.id
                  where usage_config.tenant_id=:tenantId and usage_config.hotel_org_unit_id=%s
                    and (:workTenantScope or review_hotel.id in (:workReadScopes))
                    and usage_reviewer.status='ACTIVE' and usage_reviewer.valid_from<=current_date
                    and (usage_reviewer.valid_to is null or usage_reviewer.valid_to>=current_date)
                    and reviewer_employee.employment_status='ACTIVE' and reviewer_employee.deleted_at is null
                    and (exists (select 1 from store_usage_review_config_member member
                      where member.tenant_id=usage_config.tenant_id and member.hotel_org_unit_id=usage_config.hotel_org_unit_id
                        and member.reviewer_assignment_id=usage_reviewer.id)
                      or (usage_config.reviewer_assignment_id=usage_reviewer.id and not exists (
                        select 1 from store_usage_review_config_member member
                        where member.tenant_id=usage_config.tenant_id and member.hotel_org_unit_id=usage_config.hotel_org_unit_id))))
                """.formatted(hotel);
    }

    private static String usageRecord(String alias) {
        return """
                exists (select 1 from store_work_usage assigned_usage
                  join employee usage_employee on usage_employee.tenant_id=assigned_usage.tenant_id
                    and usage_employee.id=%1$s.employee_id and usage_employee.account_id is distinct from :workUsageActorId
                  join org_unit_closure usage_hotel on usage_hotel.tenant_id=assigned_usage.tenant_id
                    and usage_hotel.ancestor_id=assigned_usage.hotel_org_unit_id and usage_hotel.descendant_id=%1$s.target_org_unit_id
                  where assigned_usage.tenant_id=%1$s.tenant_id and assigned_usage.tenant_id=:tenantId
                    and assigned_usage.work_record_id=%1$s.id and %1$s.submitted_by_account_id is distinct from :workUsageActorId
                    and %2$s)
                """.formatted(alias, usageMember("assigned_usage.hotel_org_unit_id"));
    }

    private static String usageReport(String alias) {
        return """
                exists (select 1 from work_expectation usage_expectation
                  join work_package_item usage_item on usage_item.tenant_id=usage_expectation.tenant_id
                    and usage_item.id=usage_expectation.work_package_item_id and usage_item.item_code='HKA_DAILY_REPORT'
                  join work_package_version usage_version on usage_version.tenant_id=usage_item.tenant_id and usage_version.id=usage_item.work_package_version_id
                  join work_package_version report_version on report_version.tenant_id=usage_version.tenant_id
                    and report_version.id=%1$s.work_package_version_id
                    and report_version.work_package_definition_id=usage_version.work_package_definition_id
                  join employee_position_assignment report_owner on report_owner.tenant_id=usage_expectation.tenant_id
                    and report_owner.id=%1$s.position_assignment_id and report_owner.employee_id=%1$s.employee_id
                  join employee report_employee on report_employee.tenant_id=report_owner.tenant_id
                    and report_employee.id=report_owner.employee_id and report_employee.account_id is distinct from :workUsageActorId
                  join work_record report_work on report_work.tenant_id=usage_expectation.tenant_id
                    and report_work.work_expectation_id=usage_expectation.id and report_work.work_package_item_id=usage_item.id
                    and report_work.position_assignment_id=%1$s.position_assignment_id and report_work.employee_id=%1$s.employee_id
                    and report_work.business_date=%1$s.business_date and report_work.submitted_by_account_id is distinct from :workUsageActorId
                    and report_work.target_org_unit_id=usage_expectation.target_org_unit_id
                    and (nullif(report_work.payload->>'dailyReportId','') is null or report_work.payload->>'dailyReportId'=%1$s.id::text)
                  left join store_work_usage_report_link usage_link on usage_link.tenant_id=report_work.tenant_id and usage_link.work_record_id=report_work.id
                  join store_work_usage report_usage on report_usage.tenant_id=report_work.tenant_id
                    and report_usage.work_record_id=coalesce(usage_link.usage_record_id,report_work.id) and report_usage.hotel_org_unit_id=%1$s.hotel_org_unit_id
                  join work_record original_usage on original_usage.tenant_id=report_usage.tenant_id and original_usage.id=report_usage.work_record_id
                    and original_usage.employee_id=%1$s.employee_id and original_usage.business_date=%1$s.business_date
                  join work_package_item original_usage_item on original_usage_item.tenant_id=original_usage.tenant_id
                    and original_usage_item.id=original_usage.work_package_item_id and original_usage_item.item_code in ('HKA_USAGE_RETURN','HKA_DAILY_REPORT')
                  join org_unit_closure original_usage_hotel on original_usage_hotel.tenant_id=report_usage.tenant_id
                    and original_usage_hotel.ancestor_id=report_usage.hotel_org_unit_id and original_usage_hotel.descendant_id=original_usage.target_org_unit_id
                  join org_unit_closure report_hotel on report_hotel.tenant_id=report_work.tenant_id
                    and report_hotel.ancestor_id=report_usage.hotel_org_unit_id and report_hotel.descendant_id=report_work.target_org_unit_id
                  where usage_expectation.tenant_id=%1$s.tenant_id and usage_expectation.tenant_id=:tenantId
                    and usage_expectation.position_assignment_id=%1$s.position_assignment_id and usage_expectation.business_date=%1$s.business_date
                    and usage_expectation.status not in ('CANCELLED','WAIVED')
                    and not exists (select 1 from work_expectation other_expectation
                      join work_package_item other_item on other_item.tenant_id=other_expectation.tenant_id
                        and other_item.id=other_expectation.work_package_item_id and other_item.item_code='HKA_DAILY_REPORT'
                      join work_package_version other_version on other_version.tenant_id=other_item.tenant_id and other_version.id=other_item.work_package_version_id
                      where other_expectation.tenant_id=usage_expectation.tenant_id and other_expectation.id<>usage_expectation.id
                        and other_expectation.position_assignment_id=usage_expectation.position_assignment_id
                        and other_expectation.business_date=usage_expectation.business_date and other_expectation.status not in ('CANCELLED','WAIVED')
                        and other_version.work_package_definition_id=report_version.work_package_definition_id)
                    and %1$s.created_by_account_id is distinct from :workUsageActorId
                    and not exists (select 1 from daily_report_revision current_revision where current_revision.tenant_id=%1$s.tenant_id
                      and current_revision.id=%1$s.current_revision_id and current_revision.submitted_by_account_id=:workUsageActorId)
                    and %2$s)
                """.formatted(alias, usageMember("report_usage.hotel_org_unit_id"));
    }

    /** Caller retains the formal-report permission, self-review and revision-state checks. */
    public boolean canReviewUsageReportGroup(TenantPrincipal principal, UUID reportId, boolean lock) {
        if (!usageReviewer(principal)) return false;
        MapSqlParameterSource params = usageParameters(principal, new MapSqlParameterSource("tenantId", principal.tenantId()))
                .addValue("usageReportId", reportId);
        if (lock && jdbc.queryForList("""
                select review_config.hotel_org_unit_id from store_usage_review_config review_config
                  join daily_report usage_report on usage_report.tenant_id=review_config.tenant_id
                    and usage_report.hotel_org_unit_id=review_config.hotel_org_unit_id
                where usage_report.tenant_id=:tenantId and usage_report.id=:usageReportId
                  and (:workTenantScope or review_config.hotel_org_unit_id in (:workReadScopes))
                for share of review_config
                """, params, UUID.class).isEmpty()) return false;
        // Read members after acquiring the parent lock so a waited-for replacement is visible.
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from daily_report usage_report"
                + " where usage_report.tenant_id=:tenantId and usage_report.id=:usageReportId and "
                + usageReport("usage_report") + ")", params, Boolean.class));
    }

    public void requireExpectation(TenantPrincipal principal, UUID expectationId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("workReadId", expectationId);
        Scope scope = scope(principal, params);
        Boolean allowed = jdbc.queryForObject("""
                select exists (select 1 from work_expectation checked_work
                where checked_work.tenant_id = :tenantId and checked_work.id = :workReadId and
                """ + scope.expectation("checked_work", true) + ")", params, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw new AccessDeniedException("该工作不属于当前任职或授权下属范围");
    }

    public void requireRecord(TenantPrincipal principal, UUID recordId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("workReadId", recordId);
        Scope scope = scope(principal, params);
        Boolean allowed = jdbc.queryForObject("""
                select exists (select 1 from work_record checked_record
                where checked_record.tenant_id = :tenantId and checked_record.id = :workReadId and
                """ + scope.record("checked_record") + ")", params, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw new AccessDeniedException("该工作记录不属于当前任职或授权下属范围");
    }

    public void requireReport(TenantPrincipal principal, UUID reportId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("workReadId", reportId);
        Scope scope = reportScope(principal, params);
        Boolean allowed = jdbc.queryForObject("""
                select exists (select 1 from daily_report checked_report
                where checked_report.tenant_id = :tenantId and checked_report.id = :workReadId and
                """ + scope.report("checked_report") + ")", params, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw new AccessDeniedException("该日报不属于当前任职或授权下属范围");
    }

    public record Scope(boolean administrator, boolean tenantScope, boolean hasOwn, boolean hasManaged, boolean usageReviewer) {
        public Scope(boolean administrator, boolean tenantScope, boolean hasOwn, boolean hasManaged) {
            this(administrator, tenantScope, hasOwn, hasManaged, false);
        }
        private String own(String assignment) {
            return hasOwn ? assignment + " in (:workOwnIds)" : "false";
        }

        private String managed(String org, String assignment) {
            String orgScope = tenantScope ? "true" : org + " in (:workReadScopes)";
            String assignments = administrator ? "true"
                    : hasManaged ? assignment + " in (:workManagedIds)" : "false";
            return "(" + orgScope + " and " + assignments + ")";
        }

        /** Statistics include only owned work and authorized subordinates, not shared owners. */
        public String summary(String alias) {
            return summaryForResponsibility(alias, alias + ".position_assignment_id");
        }

        /** Monthly obligations follow the persisted submitter or effective delegation, never permission to assist. */
        public String summaryForResponsibility(String alias, String assignmentExpression) {
            return "(" + own(assignmentExpression) + " or "
                    + managed(alias + ".target_org_unit_id", assignmentExpression) + ")";
        }

        public String personal(String alias) { return own(alias + ".position_assignment_id"); }

        /** A shared work item never grants access to the owner's entire daily report. */
        public String report(String alias) {
            return "(" + personal(alias) + " or " + managed(alias + ".org_unit_id", alias + ".position_assignment_id")
                    + (usageReviewer ? " or " + usageReport(alias) : "") + ")";
        }

        /** Shared/delegated access is confined to the single expectation, not that employee. */
        public String expectation(String alias, boolean includeTeam) {
            return expectation(alias, includeTeam, true);
        }

        private String expectation(String alias, boolean includeTeam, boolean includeUsage) {
            String personal = own(alias + ".position_assignment_id");
            if (hasOwn) {
                personal += " or exists (select 1 from work_expectation_delegation wd "
                        + "where wd.tenant_id = " + alias + ".tenant_id and wd.work_expectation_id = "
                        + alias + ".id and wd.status = 'ACTIVE' and wd.delegate_assignment_id in (:workOwnIds))";
                personal += """
                         or (not exists (select 1 from work_expectation_delegation wd
                             where wd.tenant_id = %1$s.tenant_id and wd.work_expectation_id = %1$s.id
                               and wd.status = 'ACTIVE')
                           and exists (
                             select 1 from employee_position_assignment participant
                             join position_definition position on position.tenant_id = participant.tenant_id
                               and position.id = participant.position_id and position.status = 'ACTIVE'
                             join work_package_item policy on policy.tenant_id = participant.tenant_id
                               and policy.id = %1$s.work_package_item_id
                             join org_unit_closure within_hotel on within_hotel.tenant_id = participant.tenant_id
                               and within_hotel.descendant_id = participant.org_unit_id
                               and within_hotel.ancestor_id = %1$s.target_org_unit_id
                             where participant.tenant_id = %1$s.tenant_id
                               and participant.id in (:workOwnIds) and participant.status = 'ACTIVE'
                               and participant.valid_from <= %1$s.business_date
                               and (participant.valid_to is null or participant.valid_to >= %1$s.business_date)
                               and jsonb_exists(coalesce(policy.execution_policy -> 'allowedPositionCodes', '[]'::jsonb), position.code)
                           ))
                        """.formatted(alias);
            }
            return "(" + personal + (includeTeam ? " or " + managed(
                    alias + ".target_org_unit_id", alias + ".position_assignment_id") : "")
                    + (includeTeam && includeUsage && usageReviewer ? " or exists(select 1 from work_record assigned_record where assigned_record.tenant_id="
                        + alias + ".tenant_id and assigned_record.work_expectation_id=" + alias + ".id and " + usageRecord("assigned_record") + ")" : "") + ")";
        }

        public String record(String alias) {
            return "(" + summary(alias) + " or exists (select 1 from work_expectation linked_work "
                    + "where linked_work.tenant_id = " + alias + ".tenant_id and linked_work.id = "
                    + alias + ".work_expectation_id and " + expectation("linked_work", true, false) + ")"
                    + (usageReviewer ? " or " + usageRecord(alias) : "") + ")";
        }
    }

    @SafeVarargs
    private static <T> Set<T> union(Set<T>... sets) {
        Set<T> result = new LinkedHashSet<>();
        for (Set<T> set : sets) result.addAll(set);
        return Set.copyOf(result);
    }

    private static Set<UUID> nonempty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(new UUID(0, 0)) : ids;
    }
}
