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
            "OTA_OPERATION_MANAGER", "OTA_OPERATION_ASSISTANT"));
    // Compatibility for built-in positions without configured reporting links.
    // Custom positions require explicit reporting links; names/legacy levels grant nothing.
    private static final Map<String, Set<String>> SUBORDINATE_ROLES = Map.of(
            "GROUP_CHAIRMAN", union(GROUP_TEAM, Set.of("CEO")),
            "CEO", GROUP_TEAM,
            "GROUP_VICE_PRESIDENT", HOTEL_MANAGEMENT,
            "GENERAL_MANAGER", union(HOTEL_TEAM, Set.of("ASSISTANT_GENERAL_MANAGER")),
            "ASSISTANT_GENERAL_MANAGER", HOTEL_TEAM,
            "FRONT_OFFICE_SUPERVISOR", FRONT,
            "HOUSEKEEPING_SUPERVISOR", HOUSEKEEPING,
            "OTA_OPERATION_MANAGER", Set.of("OTA_OPERATION_ASSISTANT"),
            "HR_ADMINISTRATION_SUPERVISOR", Set.of("HR_ADMINISTRATION"));

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
        return new Scope(administrator, principal.hasTenantScope(), !own.isEmpty(), !managed.isEmpty());
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

    public record Scope(boolean administrator, boolean tenantScope, boolean hasOwn, boolean hasManaged) {
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
            return "(" + own(alias + ".position_assignment_id") + " or "
                    + managed(alias + ".target_org_unit_id", alias + ".position_assignment_id") + ")";
        }

        public String personal(String alias) { return own(alias + ".position_assignment_id"); }

        /** A shared work item never grants access to the owner's entire daily report. */
        public String report(String alias) {
            return "(" + personal(alias) + " or " + managed(alias + ".org_unit_id", alias + ".position_assignment_id") + ")";
        }

        /** Shared/delegated access is confined to the single expectation, not that employee. */
        public String expectation(String alias, boolean includeTeam) {
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
                    alias + ".target_org_unit_id", alias + ".position_assignment_id") : "") + ")";
        }

        public String record(String alias) {
            return "(" + summary(alias) + " or exists (select 1 from work_expectation linked_work "
                    + "where linked_work.tenant_id = " + alias + ".tenant_id and linked_work.id = "
                    + alias + ".work_expectation_id and " + expectation("linked_work", true) + "))";
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
