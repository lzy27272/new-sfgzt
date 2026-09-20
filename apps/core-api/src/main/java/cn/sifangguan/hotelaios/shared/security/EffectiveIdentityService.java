package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves roles, permissions, active positions and organization scope on every request. */
@Service
public class EffectiveIdentityService {
    private static final List<String> ROLE_PRIORITY = List.of(
            "PLATFORM_ADMIN", "GROUP_ADMIN", "CEO", "GENERAL_MANAGER",
            "GROUP_CHAIRMAN", "GROUP_VICE_PRESIDENT",
            "HR_ADMINISTRATION_SUPERVISOR", "HR_ADMINISTRATION",
            "ASSISTANT_GENERAL_MANAGER", "OTA_OPERATION_MANAGER",
            "FRONT_OFFICE_SUPERVISOR", "HOUSEKEEPING_SUPERVISOR",
            "OTA_OPERATION_ASSISTANT", "FRONT_DESK"
    );
    private static final Set<String> FULL_ACCOUNT_LEVEL_ROLES = Set.of(
            "PLATFORM_ADMIN", "GROUP_ADMIN", "CEO");
    private static final Set<String> SUPPLEMENTAL_ACCOUNT_ROLES = Set.of("HR_KPI_ADMIN");
    private static final Set<String> RESERVED_SYSTEM_ROLE_CODES = Set.of(
            "PLATFORM_ADMIN", "GROUP_ADMIN", "CEO", "GROUP_VICE_PRESIDENT",
            "GROUP_CHAIRMAN", "HR_ADMINISTRATION_SUPERVISOR", "HR_ADMINISTRATION",
            "GENERAL_MANAGER", "ASSISTANT_GENERAL_MANAGER", "OTA_OPERATION_MANAGER",
            "OTA_OPERATION_ASSISTANT", "FRONT_OFFICE_SUPERVISOR",
            "HOUSEKEEPING_SUPERVISOR", "HOUSEKEEPING_ATTENDANT", "FRONT_DESK",
            "HR_KPI_ADMIN"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;

    public EffectiveIdentityService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext databaseContext) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
    }

    /**
     * Compatibility entry point for trusted server-side flows that must inspect every active assignment.
     * HTTP requests must use the four-argument overload so a request is bound to one assignment.
     */
    @Transactional(readOnly = true)
    public TenantPrincipal resolve(UUID tenantId, UUID accountId, UUID correlationId) {
        return resolveInternal(tenantId, accountId, correlationId, null, false);
    }

    /** Resolve one request identity, optionally selecting an explicit active employee assignment. */
    @Transactional(readOnly = true)
    public TenantPrincipal resolve(
            UUID tenantId,
            UUID accountId,
            UUID correlationId,
            UUID requestedAssignmentId
    ) {
        return resolveInternal(tenantId, accountId, correlationId, requestedAssignmentId, true);
    }

    /** Trusted eligibility checks only. Invalid candidates must not roll back the caller's transaction. */
    @Transactional(readOnly = true)
    public java.util.Optional<TenantPrincipal> inspectAssignment(
            UUID tenantId, UUID accountId, UUID correlationId, UUID assignmentId) {
        if (assignmentId == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(resolveInternal(tenantId, accountId, correlationId, assignmentId, true));
        } catch (IdentityAuthenticationException | BusinessIdentityException ex) {
            return java.util.Optional.empty();
        }
    }

    private TenantPrincipal resolveInternal(
            UUID tenantId,
            UUID accountId,
            UUID correlationId,
            UUID requestedAssignmentId,
            boolean bindRequestToAssignment
    ) {
        databaseContext.apply(tenantId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("accountId", accountId);

        Integer activeAccounts = jdbc.queryForObject("""
                select count(*)
                from user_account
                where tenant_id = :tenantId and id = :accountId and status = 'ACTIVE'
                """, params, Integer.class);
        if (activeAccounts == null || activeAccounts != 1) {
            throw new IdentityAuthenticationException("账号不存在、已停用或不属于当前租户");
        }

        List<RoleGrant> grants = roleGrants(params);
        boolean fullAccountLevel = grants.stream().anyMatch(grant ->
                grant.systemRole() && FULL_ACCOUNT_LEVEL_ROLES.contains(grant.roleCode()));

        List<AssignmentScope> activeAssignments = activeAssignments(params);
        List<RoleGrant> effectiveGrants = grants.stream()
                .filter(grant -> grant.systemRole() && FULL_ACCOUNT_LEVEL_ROLES.contains(grant.roleCode())
                        || activeAssignments.stream().anyMatch(assignment ->
                        grantSupportsAssignment(tenantId, grant, assignment)))
                .toList();
        if (requestedAssignmentId != null
                && activeAssignments.stream().noneMatch(item -> item.assignmentId().equals(requestedAssignmentId))) {
            throw BusinessIdentityException.forbidden();
        }
        if (activeAssignments.isEmpty()) {
            Integer linkedEmployees = jdbc.queryForObject("""
                    select count(*)
                    from employee
                    where tenant_id = :tenantId and account_id = :accountId
                    """, params, Integer.class);
            if (!fullAccountLevel && linkedEmployees != null && linkedEmployees > 0) {
                throw new IdentityAuthenticationException("Employee account has no current active position assignment");
            }
        }

        if (!bindRequestToAssignment || fullAccountLevel || activeAssignments.isEmpty()) {
            return accountWidePrincipal(
                    tenantId, accountId, correlationId, effectiveGrants, activeAssignments,
                    bindRequestToAssignment ? requestedAssignmentId : null);
        }

        AssignmentScope selected = selectAssignment(activeAssignments, requestedAssignmentId);
        List<RoleGrant> selectedGrants = grants.stream()
                .filter(grant -> grantSupportsAssignment(tenantId, grant, selected))
                .toList();
        return assignmentPrincipal(
                tenantId, accountId, correlationId, selected, selectedGrants, requestedAssignmentId);
    }

    private List<RoleGrant> roleGrants(MapSqlParameterSource params) {
        return jdbc.query("""
                select distinct role.id as role_id, role.code, role.role_type,
                       assignment.scope_type, assignment.scope_org_unit_id,
                       assignment.source_type, assignment.source_assignment_id
                from role_assignment assignment
                join app_role role
                  on role.tenant_id = assignment.tenant_id
                 and role.id = assignment.role_id
                where assignment.tenant_id = :tenantId
                  and assignment.account_id = :accountId
                  and assignment.valid_from <= now()
                  and (assignment.valid_to is null or assignment.valid_to > now())
                """, params, (rs, rowNum) -> new RoleGrant(
                rs.getObject("role_id", UUID.class),
                rs.getString("code"),
                rs.getString("role_type"),
                rs.getString("scope_type"),
                rs.getObject("scope_org_unit_id", UUID.class),
                rs.getString("source_type"),
                rs.getObject("source_assignment_id", UUID.class)
        ));
    }

    private List<AssignmentScope> activeAssignments(MapSqlParameterSource params) {
        return jdbc.query("""
                select assignment.id, assignment.org_unit_id, assignment.is_primary,
                       group_profile.default_role_id, role.code as role_code, role.role_type,
                       coalesce(hotel_version.id, group_version.id) as effective_version_id,
                       coalesce(hotel_version.authorization_scope_type,
                                group_version.authorization_scope_type) as authorization_scope_type
                from employee
                join employee_position_assignment assignment
                  on assignment.tenant_id = employee.tenant_id
                 and assignment.employee_id = employee.id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                 and position.status = 'ACTIVE'
                 and position.deleted_at is null
                 and position.permanently_deleted_at is null
                join org_unit assignment_org
                  on assignment_org.tenant_id = assignment.tenant_id
                 and assignment_org.id = assignment.org_unit_id
                 and assignment_org.status = 'ACTIVE'
                join position_function_profile group_profile
                  on group_profile.tenant_id = assignment.tenant_id
                 and group_profile.position_id = assignment.position_id
                 and group_profile.scope_type = 'GROUP'
                join app_role role
                  on role.tenant_id = group_profile.tenant_id
                 and role.id = group_profile.default_role_id
                left join lateral (
                    select ancestor.id
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = assignment.tenant_id
                      and closure.descendant_id = assignment.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                    order by closure.depth
                    limit 1
                ) hotel_context on true
                left join position_function_profile_version group_version
                  on group_version.tenant_id = group_profile.tenant_id
                 and group_version.profile_id = group_profile.id
                 and group_version.lifecycle_status = 'PUBLISHED'
                left join position_function_profile hotel_profile
                  on hotel_profile.tenant_id = group_profile.tenant_id
                 and hotel_profile.position_id = group_profile.position_id
                 and hotel_profile.scope_type = 'HOTEL'
                 and hotel_profile.hotel_org_unit_id = hotel_context.id
                left join position_function_profile_version hotel_version
                  on hotel_version.tenant_id = hotel_profile.tenant_id
                 and hotel_version.profile_id = hotel_profile.id
                 and hotel_version.lifecycle_status = 'PUBLISHED'
                where employee.tenant_id = :tenantId
                  and employee.account_id = :accountId
                  and employee.employment_status = 'ACTIVE'
                  and employee.deleted_at is null
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and (
                    position.applies_to_all_hotels = true
                    or (
                      hotel_context.id is not null
                      and exists (
                        select 1
                        from position_applicable_hotel applicable
                        where applicable.tenant_id = position.tenant_id
                          and applicable.position_id = position.id
                          and applicable.hotel_org_unit_id = hotel_context.id
                      )
                    )
                  )
                order by assignment.is_primary desc, assignment.id
                """, params, (rs, rowNum) -> new AssignmentScope(
                rs.getObject("id", UUID.class),
                rs.getObject("org_unit_id", UUID.class),
                rs.getBoolean("is_primary"),
                rs.getObject("default_role_id", UUID.class),
                rs.getString("role_code"),
                rs.getString("role_type"),
                rs.getObject("effective_version_id", UUID.class),
                rs.getString("authorization_scope_type")
        ));
    }

    private TenantPrincipal accountWidePrincipal(
            UUID tenantId,
            UUID accountId,
            UUID correlationId,
            List<RoleGrant> grants,
            List<AssignmentScope> activeAssignments,
            UUID businessActorAssignmentId
    ) {
        Set<String> roles = new LinkedHashSet<>();
        boolean tenantScope = false;
        Set<UUID> directScopes = new LinkedHashSet<>();
        Set<UUID> treeRoots = new LinkedHashSet<>();
        for (RoleGrant grant : grants) {
            roles.add(identityRoleCode(grant.roleCode(), grant.roleType()));
            tenantScope = applyGrantScope(tenantId, grant, directScopes, treeRoots, tenantScope);
        }

        Set<UUID> roleIds = new LinkedHashSet<>();
        grants.forEach(grant -> roleIds.add(grant.roleId()));
        Set<String> permissions = roleIds.isEmpty()
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(jdbc.queryForList("""
                        select distinct permission.code
                        from role_permission grant_item
                        join permission on permission.id = grant_item.permission_id
                        where grant_item.tenant_id = :tenantId
                          and grant_item.role_id in (:roleIds)
                        order by permission.code
                        """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("roleIds", roleIds), String.class));
        if (grants.stream().anyMatch(grant -> grant.systemRole()
                && "PLATFORM_ADMIN".equals(grant.roleCode()))) {
            permissions.add("*");
        }

        boolean hasSelfRole = grants.stream().anyMatch(grant -> "SELF".equals(grant.scopeType()));
        if (hasSelfRole) {
            activeAssignments.forEach(assignment -> directScopes.add(assignment.orgUnitId()));
        }
        Set<UUID> scopes = expandScopes(tenantId, directScopes, treeRoots);
        Set<UUID> assignmentIds = new LinkedHashSet<>();
        activeAssignments.forEach(assignment -> assignmentIds.add(assignment.assignmentId()));
        return principal(tenantId, accountId, correlationId, roles, permissions, scopes,
                assignmentIds, businessActorAssignmentId, tenantScope);
    }

    private TenantPrincipal assignmentPrincipal(
            UUID tenantId,
            UUID accountId,
            UUID correlationId,
            AssignmentScope selected,
            List<RoleGrant> grants,
            UUID businessActorAssignmentId
    ) {
        Set<String> permissions;
        boolean tenantScope = false;
        Set<UUID> directScopes = new LinkedHashSet<>();
        Set<UUID> treeRoots = new LinkedHashSet<>();
        if (selected.effectiveVersionId() != null) {
            permissions = new LinkedHashSet<>(jdbc.queryForList("""
                    select permission.code
                    from position_function_profile_permission item
                    join permission on permission.id = item.permission_id
                    where item.tenant_id = :tenantId
                      and item.profile_version_id = :versionId
                    order by permission.code
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("versionId", selected.effectiveVersionId()), String.class));
            tenantScope = applyProfileScope(tenantId, selected.authorizationScopeType(),
                    selected.assignmentId(), selected.orgUnitId(),
                    directScopes, treeRoots);
        } else {
            permissions = new LinkedHashSet<>(jdbc.queryForList("""
                    select permission.code
                    from role_permission grant_item
                    join permission on permission.id = grant_item.permission_id
                    where grant_item.tenant_id = :tenantId
                      and grant_item.role_id = :roleId
                      and permission.delegable_to_position = true
                    order by permission.code
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("roleId", selected.roleId()), String.class));
            List<RoleGrant> matchingGrants = grants.stream()
                    .filter(grant -> grant.roleId().equals(selected.roleId()))
                    .toList();
            if (matchingGrants.isEmpty()) {
                directScopes.add(selected.orgUnitId());
            } else {
                for (RoleGrant grant : matchingGrants) {
                    tenantScope = applyGrantScope(tenantId, grant, directScopes, treeRoots, tenantScope);
                    if ("SELF".equals(grant.scopeType())) directScopes.add(selected.orgUnitId());
                }
            }
        }
        Set<String> roles = new LinkedHashSet<>();
        String selectedRoleCode = identityRoleCode(selected.roleCode(), selected.roleType());
        roles.add(selectedRoleCode);
        for (RoleGrant grant : grants) {
            if (!grant.systemRole() || !SUPPLEMENTAL_ACCOUNT_ROLES.contains(grant.roleCode())) continue;
            roles.add(identityRoleCode(grant.roleCode(), grant.roleType()));
            permissions.addAll(rolePermissions(tenantId, grant.roleId()));
            tenantScope = applyGrantScope(tenantId, grant, directScopes, treeRoots, tenantScope);
        }
        Set<UUID> scopes = expandScopes(tenantId, directScopes, treeRoots);
        return new TenantPrincipal(
                tenantId,
                accountId,
                selectedRoleCode,
                roles,
                permissions,
                scopes,
                Set.of(selected.assignmentId()),
                businessActorAssignmentId,
                tenantScope,
                correlationId
        );
    }

    private Set<String> rolePermissions(UUID tenantId, UUID roleId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select permission.code
                from role_permission grant_item
                join permission on permission.id = grant_item.permission_id
                where grant_item.tenant_id = :tenantId and grant_item.role_id = :roleId
                order by permission.code
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("roleId", roleId), String.class));
    }

    /**
     * A role grant may contribute to an operational identity only when a
     * current, applicable assignment proves its scope.  Position-default
     * grants additionally have to match the assignment's explicit role
     * mapping; supplemental HR grants are limited by their declared scope.
     */
    private boolean grantSupportsAssignment(
            UUID tenantId,
            RoleGrant grant,
            AssignmentScope assignment
    ) {
        if (!(grant.systemRole() && SUPPLEMENTAL_ACCOUNT_ROLES.contains(grant.roleCode()))
                && !grant.roleId().equals(assignment.roleId())) {
            return false;
        }
        if ("POSITION_ASSIGNMENT".equals(grant.sourceType())
                && !assignment.assignmentId().equals(grant.sourceAssignmentId())) {
            return false;
        }
        return switch (grant.scopeType()) {
            case "TENANT", "SELF", "ASSIGNED_HOTELS" -> true;
            case "ORG_UNIT" -> grant.scopeOrgUnitId() != null
                    && grant.scopeOrgUnitId().equals(assignment.orgUnitId());
            case "ORG_TREE" -> grant.scopeOrgUnitId() != null
                    && Boolean.TRUE.equals(jdbc.queryForObject("""
                            select exists (
                                select 1
                                from org_unit_closure
                                where tenant_id = :tenantId
                                  and ancestor_id = :ancestorId
                                  and descendant_id = :descendantId
                            )
                            """, new MapSqlParameterSource("tenantId", tenantId)
                            .addValue("ancestorId", grant.scopeOrgUnitId())
                            .addValue("descendantId", assignment.orgUnitId()), Boolean.class));
            default -> false;
        };
    }

    private AssignmentScope selectAssignment(List<AssignmentScope> activeAssignments, UUID requestedAssignmentId) {
        if (requestedAssignmentId != null) {
            return activeAssignments.stream()
                    .filter(item -> item.assignmentId().equals(requestedAssignmentId))
                    .findFirst()
                    .orElseThrow(() -> new IdentityAuthenticationException(
                            "所选任职无效、已暂停或不属于当前账号"));
        }
        List<AssignmentScope> primary = activeAssignments.stream().filter(AssignmentScope::primary).toList();
        if (primary.size() == 1) return primary.getFirst();
        if (primary.isEmpty()) return activeAssignments.getFirst();
        throw new IdentityAuthenticationException("账号存在多个主任职，请联系管理员修复任职数据");
    }

    private boolean applyGrantScope(
            UUID tenantId,
            RoleGrant grant,
            Set<UUID> directScopes,
            Set<UUID> treeRoots,
            boolean tenantScope
    ) {
        return switch (grant.scopeType()) {
            case "TENANT" -> true;
            case "ORG_TREE" -> {
                addIfPresent(treeRoots, grant.scopeOrgUnitId());
                yield tenantScope;
            }
            case "ORG_UNIT" -> {
                addIfPresent(directScopes, grant.scopeOrgUnitId());
                yield tenantScope;
            }
            case "ASSIGNED_HOTELS" -> {
                addAssignedHotelScopes(tenantId, grant.sourceAssignmentId(), treeRoots);
                yield tenantScope;
            }
            case "SELF" -> tenantScope;
            default -> throw new IdentityAuthenticationException("账号存在不支持的数据范围类型");
        };
    }

    private boolean applyProfileScope(
            UUID tenantId,
            String scopeType,
            UUID assignmentId,
            UUID assignmentOrgUnitId,
            Set<UUID> directScopes,
            Set<UUID> treeRoots
    ) {
        return switch (scopeType) {
            case "TENANT" -> true;
            case "ORG_TREE" -> {
                treeRoots.add(assignmentOrgUnitId);
                yield false;
            }
            case "ASSIGNED_HOTELS" -> {
                addAssignedHotelScopes(tenantId, assignmentId, treeRoots);
                yield false;
            }
            case "SELF", "ORG_UNIT" -> {
                directScopes.add(assignmentOrgUnitId);
                yield false;
            }
            default -> throw new IdentityAuthenticationException("岗位方案存在不支持的数据范围类型");
        };
    }

    private void addAssignedHotelScopes(UUID tenantId, UUID assignmentId, Set<UUID> treeRoots) {
        if (assignmentId == null) return;
        treeRoots.addAll(jdbc.queryForList("""
                select scope.hotel_org_unit_id
                from employee_assignment_hotel_scope scope
                join org_unit hotel
                  on hotel.tenant_id = scope.tenant_id
                 and hotel.id = scope.hotel_org_unit_id
                 and hotel.unit_type = 'HOTEL'
                 and hotel.status = 'ACTIVE'
                where scope.tenant_id = :tenantId and scope.assignment_id = :assignmentId
                order by hotel.name, hotel.id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("assignmentId", assignmentId), UUID.class));
    }

    private Set<UUID> expandScopes(UUID tenantId, Set<UUID> directScopes, Set<UUID> treeRoots) {
        Set<UUID> scopes = new LinkedHashSet<>(directScopes);
        if (!treeRoots.isEmpty()) {
            scopes.addAll(jdbc.queryForList("""
                    select distinct descendant_id
                    from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id in (:treeRoots)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("treeRoots", treeRoots), UUID.class));
        }
        return scopes;
    }

    private TenantPrincipal principal(
            UUID tenantId,
            UUID accountId,
            UUID correlationId,
            Set<String> roles,
            Set<String> permissions,
            Set<UUID> scopes,
            Set<UUID> assignmentIds,
            UUID businessActorAssignmentId,
            boolean tenantScope
    ) {
        String primaryRole = roles.stream()
                .min(Comparator.comparingInt(EffectiveIdentityService::priorityOf)
                        .thenComparing(String::compareTo))
                .orElse("UNASSIGNED");
        return new TenantPrincipal(tenantId, accountId, primaryRole, roles, permissions,
                scopes, assignmentIds, businessActorAssignmentId, tenantScope, correlationId);
    }

    private static int priorityOf(String role) {
        int index = ROLE_PRIORITY.indexOf(role);
        return index < 0 ? ROLE_PRIORITY.size() : index;
    }

    static String identityRoleCode(String roleCode, String roleType) {
        if (RESERVED_SYSTEM_ROLE_CODES.contains(roleCode) && !"SYSTEM".equals(roleType)) {
            return "CUSTOM";
        }
        return roleCode;
    }

    private static <T> void addIfPresent(Set<T> target, T value) {
        if (value != null) target.add(value);
    }

    private record RoleGrant(
            UUID roleId,
            String roleCode,
            String roleType,
            String scopeType,
            UUID scopeOrgUnitId,
            String sourceType,
            UUID sourceAssignmentId
    ) {
        private boolean systemRole() {
            return "SYSTEM".equals(roleType);
        }
    }

    private record AssignmentScope(
            UUID assignmentId,
            UUID orgUnitId,
            boolean primary,
            UUID roleId,
            String roleCode,
            String roleType,
            UUID effectiveVersionId,
            String authorizationScopeType
    ) {
    }
}
