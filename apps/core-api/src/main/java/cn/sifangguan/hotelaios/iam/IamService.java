package cn.sifangguan.hotelaios.iam;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.features.GroupManagementFeatureGate;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class IamService {
    private static final Set<String> RESERVED_SYSTEM_ROLE_CODES = Set.of(
            "PLATFORM_ADMIN", "GROUP_ADMIN", "CEO", "GROUP_VICE_PRESIDENT",
            "GROUP_CHAIRMAN", "HR_ADMINISTRATION_SUPERVISOR", "HR_ADMINISTRATION",
            "GENERAL_MANAGER", "ASSISTANT_GENERAL_MANAGER", "OPERATIONS_DIRECTOR", "OPERATIONS_MANAGER", "OTA_OPERATION_MANAGER",
            "OTA_OPERATION_ASSISTANT", "FRONT_OFFICE_SUPERVISOR",
            "HOUSEKEEPING_SUPERVISOR", "HOUSEKEEPING_ATTENDANT", "FRONT_DESK",
            "HR_KPI_ADMIN"
    );
    private static final Set<String> ROLE_SCOPE_TYPES = Set.of("SELF", "ORG_UNIT", "ORG_TREE", "TENANT");

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final GroupManagementFeatureGate groupManagementFeatureGate;

    public IamService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            GroupManagementFeatureGate groupManagementFeatureGate
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.groupManagementFeatureGate = groupManagementFeatureGate;
    }

    @Transactional(readOnly = true)
    public IamModels.Me me() {
        TenantPrincipal principal = prepare();
        MapSqlParameterSource params = base(principal).addValue("accountId", principal.actorId());
        IamModels.Account account = jdbc.queryForObject("""
                select id, login_name, display_name, status
                from user_account
                where tenant_id = :tenantId and id = :accountId
                """, params, (rs, rowNum) -> new IamModels.Account(
                rs.getObject("id", UUID.class),
                rs.getString("login_name"),
                rs.getString("display_name"),
                rs.getString("status")
        ));

        List<IamModels.Employee> employees = jdbc.query("""
                select id, employee_no, name, employment_status
                from employee
                where tenant_id = :tenantId and account_id = :accountId
                """, params, (rs, rowNum) -> new IamModels.Employee(
                rs.getObject("id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("name"),
                rs.getString("employment_status")
        ));

        List<AssignmentRow> assignmentRows = jdbc.query("""
                select epa.id, epa.org_unit_id, ou.code as org_code, ou.name as org_name,
                       epa.position_id, pd.code as position_code, pd.name as position_name,
                       epa.is_primary, epa.assignment_type, epa.valid_from, epa.valid_to
                from employee e
                join employee_position_assignment epa
                  on epa.tenant_id = e.tenant_id and epa.employee_id = e.id
                join org_unit ou
                  on ou.tenant_id = epa.tenant_id and ou.id = epa.org_unit_id
                join position_definition pd
                  on pd.tenant_id = epa.tenant_id and pd.id = epa.position_id
                 and pd.status = 'ACTIVE'
                 and pd.deleted_at is null
                 and pd.permanently_deleted_at is null
                left join lateral (
                    select ancestor.id
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = epa.tenant_id
                      and closure.descendant_id = epa.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                      and ancestor.status = 'ACTIVE'
                    order by closure.depth
                    limit 1
                ) hotel_context on true
                where e.tenant_id = :tenantId
                  and e.account_id = :accountId
                  and e.employment_status = 'ACTIVE'
                  and e.deleted_at is null
                  and ou.status = 'ACTIVE'
                  and epa.status = 'ACTIVE'
                  and epa.valid_from <= current_date
                  and (epa.valid_to is null or epa.valid_to >= current_date)
                  and (
                    pd.applies_to_all_hotels = true
                    or (
                      hotel_context.id is not null
                      and exists (
                        select 1
                        from position_applicable_hotel applicable
                        where applicable.tenant_id = pd.tenant_id
                          and applicable.position_id = pd.id
                          and applicable.hotel_org_unit_id = hotel_context.id
                      )
                    )
                  )
                order by epa.is_primary desc, pd.name, ou.name
                """, params, (rs, rowNum) -> new AssignmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("org_unit_id", UUID.class),
                rs.getString("org_code"),
                rs.getString("org_name"),
                rs.getObject("position_id", UUID.class),
                rs.getString("position_code"),
                rs.getString("position_name"),
                rs.getBoolean("is_primary"),
                rs.getString("assignment_type"),
                rs.getObject("valid_from", java.time.LocalDate.class),
                rs.getObject("valid_to", java.time.LocalDate.class)
        ));
        List<IamModels.PositionAssignment> assignments = assignmentRows.stream().map(row -> {
            AssignmentFunctionProfile profile = assignmentFunctionProfile(principal, row.id());
            Set<String> assignmentPermissions = new LinkedHashSet<>(profile.permissionCodes());
            assignmentPermissions.addAll(supplementalAccountPermissions(principal, row.id()));
            return new IamModels.PositionAssignment(
                    row.id(), row.organizationId(), row.organizationCode(), row.organizationName(),
                    row.positionId(), row.positionCode(), row.positionName(), row.primary(),
                    row.assignmentType(), row.validFrom(), row.validTo(), Set.copyOf(assignmentPermissions),
                    profile.authorizationScopeType(), profile.wecomSelfSelectable()
            );
        }).toList();

        return new IamModels.Me(
                principal.tenantId(),
                account,
                employees.isEmpty() ? null : employees.getFirst(),
                principal.roleCode(),
                principal.roleCodes(),
                principal.permissions(),
                principal.hasTenantScope(),
                principal.orgScopes(),
                assignments,
                principal.businessActorAssignmentId(),
                new IamModels.Capabilities(new IamModels.GroupManagementCapabilities(
                        groupManagementFeatureGate.workPlansEnabled(principal.tenantId()),
                        groupManagementFeatureGate.executiveTasksEnabled(principal.tenantId())
                ))
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPermissions() {
        accessPolicy.requirePermission("iam.manage");
        prepare();
        return jdbc.queryForList("select id, code, resource, action, description from permission order by resource, action", Map.of());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles() {
        accessPolicy.requirePermission("iam.manage");
        TenantPrincipal principal = prepare();
        return jdbc.queryForList("""
                select r.id, r.code, r.name, r.role_type,
                       (r.role_type = 'CUSTOM') as editable,
                       count(rp.permission_id) as permission_count
                from app_role r
                left join role_permission rp on rp.tenant_id = r.tenant_id and rp.role_id = r.id
                where r.tenant_id = :tenantId
                group by r.id, r.code, r.name, r.role_type
                order by r.name
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createRole(IamModels.CreateRole request) {
        accessPolicy.requirePermission("iam.manage");
        TenantPrincipal principal = prepare();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String name = request.name().trim();
        String roleType = normalizeCustomRoleType(request.roleType());
        if (RESERVED_SYSTEM_ROLE_CODES.contains(code)) {
            throw new IllegalArgumentException("系统角色代码不可用于自定义角色");
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = base(principal)
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("roleType", roleType);
        jdbc.update("""
                insert into app_role (id, tenant_id, code, name, role_type)
                values (:id, :tenantId, :code, :name, :roleType)
                """, params);
        auditWriter.record("IAM_CUSTOM_ROLE_CREATED", "APP_ROLE", id, json(Map.of(
                "roleId", id,
                "roleCode", code,
                "roleName", name,
                "roleType", roleType
        )));
        return Map.of("id", id, "code", code, "name", name, "roleType", roleType);
    }

    @Transactional
    public void setPermissions(UUID roleId, IamModels.SetPermissions request) {
        accessPolicy.requirePermission("iam.manage");
        TenantPrincipal principal = prepare();
        RoleLock role = lockCustomRole(principal, roleId);
        Set<UUID> permissionIds = validatePermissionIds(request.permissionIds());
        List<String> beforePermissionCodes = permissionCodes(principal, roleId);
        jdbc.update("delete from role_permission where tenant_id = :tenantId and role_id = :roleId",
                base(principal).addValue("roleId", roleId));
        for (UUID permissionId : permissionIds) {
            jdbc.update("""
                    insert into role_permission (tenant_id, role_id, permission_id)
                    values (:tenantId, :roleId, :permissionId)
                    """, base(principal)
                    .addValue("roleId", roleId)
                    .addValue("permissionId", permissionId));
        }
        List<String> afterPermissionCodes = permissionCodes(principal, roleId);
        auditWriter.record("IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED", "APP_ROLE", roleId, json(Map.of(
                "roleId", roleId,
                "roleCode", role.code(),
                "roleType", role.roleType(),
                "beforePermissionCodes", beforePermissionCodes,
                "afterPermissionCodes", afterPermissionCodes,
                "beforePermissionCount", beforePermissionCodes.size(),
                "afterPermissionCount", afterPermissionCodes.size()
        )));
    }

    @Transactional
    public Map<String, Object> grantRole(IamModels.GrantRole request) {
        accessPolicy.requirePermission("iam.manage");
        TenantPrincipal principal = prepare();
        requireOwned("user_account", principal, request.accountId());
        RoleLock role = lockCustomRole(principal, request.roleId());
        if (request.scopeOrgUnitId() != null) {
            requireOwned("org_unit", principal, request.scopeOrgUnitId());
            accessPolicy.requireOrgScope(request.scopeOrgUnitId());
        }
        String scopeType = request.scopeType().trim().toUpperCase(Locale.ROOT);
        if (!ROLE_SCOPE_TYPES.contains(scopeType)) {
            throw new IllegalArgumentException("不支持的数据范围类型");
        }
        if (("ORG_UNIT".equals(scopeType) || "ORG_TREE".equals(scopeType))
                && request.scopeOrgUnitId() == null) {
            throw new IllegalArgumentException("组织范围授权必须指定组织");
        }
        if (("SELF".equals(scopeType) || "TENANT".equals(scopeType))
                && request.scopeOrgUnitId() != null) {
            throw new IllegalArgumentException("SELF或TENANT授权不能指定组织");
        }
        OffsetDateTime validFrom = request.validFrom() == null ? OffsetDateTime.now() : request.validFrom();
        if (request.validTo() != null && request.validTo().isBefore(validFrom)) {
            throw new IllegalArgumentException("授权结束时间不能早于开始时间");
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = base(principal)
                .addValue("id", id)
                .addValue("accountId", request.accountId())
                .addValue("roleId", request.roleId())
                .addValue("scopeOrgUnitId", request.scopeOrgUnitId())
                .addValue("scopeType", scopeType)
                .addValue("validFrom", validFrom)
                .addValue("validTo", request.validTo())
                .addValue("grantedBy", principal.actorId());
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id, scope_type,
                     valid_from, valid_to, granted_by)
                values
                    (:id, :tenantId, :accountId, :roleId, :scopeOrgUnitId, :scopeType,
                     :validFrom, :validTo, :grantedBy)
                """, params);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("assignmentId", id);
        audit.put("accountId", request.accountId());
        audit.put("roleId", role.id());
        audit.put("roleCode", role.code());
        audit.put("roleType", role.roleType());
        audit.put("scopeType", scopeType);
        if (request.scopeOrgUnitId() != null) {
            audit.put("scopeOrgUnitId", request.scopeOrgUnitId());
        }
        audit.put("validFrom", validFrom);
        if (request.validTo() != null) {
            audit.put("validTo", request.validTo());
        }
        auditWriter.record("IAM_CUSTOM_ROLE_GRANTED", "ROLE_ASSIGNMENT", id, json(audit));
        return Map.of("id", id, "accountId", request.accountId(), "roleId", request.roleId());
    }

    private String normalizeCustomRoleType(String requestedRoleType) {
        if (requestedRoleType == null || requestedRoleType.isBlank()) {
            return "CUSTOM";
        }
        String normalized = requestedRoleType.trim().toUpperCase(Locale.ROOT);
        if (!"CUSTOM".equals(normalized)) {
            throw new IllegalArgumentException("通用IAM只能创建CUSTOM角色");
        }
        return normalized;
    }

    private RoleLock lockCustomRole(TenantPrincipal principal, UUID roleId) {
        List<RoleLock> roles = jdbc.query("""
                select id, code, role_type
                from app_role
                where tenant_id = :tenantId and id = :roleId
                for update
                """, base(principal).addValue("roleId", roleId), (rs, rowNum) -> new RoleLock(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("role_type")
        ));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("资源不存在或不属于当前租户");
        }
        RoleLock role = roles.getFirst();
        if (!"CUSTOM".equals(role.roleType())) {
            throw new AccessDeniedException("SYSTEM角色只能通过已发布岗位方案或受控迁移维护");
        }
        return role;
    }

    private Set<UUID> validatePermissionIds(List<UUID> requestedPermissionIds) {
        if (requestedPermissionIds == null || requestedPermissionIds.isEmpty()
                || requestedPermissionIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("权限列表不能为空且不能包含空值");
        }
        Set<UUID> permissionIds = new LinkedHashSet<>(requestedPermissionIds);
        if (permissionIds.size() != requestedPermissionIds.size()) {
            throw new IllegalArgumentException("权限列表不能包含重复项");
        }
        Integer existingCount = jdbc.queryForObject(
                "select count(*) from permission where id in (:permissionIds)",
                new MapSqlParameterSource("permissionIds", permissionIds),
                Integer.class
        );
        if (existingCount == null || existingCount != permissionIds.size()) {
            throw new IllegalArgumentException("权限不存在");
        }
        return permissionIds;
    }

    private List<String> permissionCodes(TenantPrincipal principal, UUID roleId) {
        return jdbc.queryForList("""
                select permission.code
                from role_permission grant_item
                join permission on permission.id = grant_item.permission_id
                where grant_item.tenant_id = :tenantId and grant_item.role_id = :roleId
                order by permission.code
                """, base(principal).addValue("roleId", roleId), String.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成审计数据", exception);
        }
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private void requireOwned(String table, TenantPrincipal principal, UUID id) {
        if (!List.of("app_role", "user_account", "org_unit").contains(table)) {
            throw new IllegalArgumentException("不允许的实体类型");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("资源不存在或不属于当前租户");
        }
    }

    private AssignmentFunctionProfile assignmentFunctionProfile(TenantPrincipal principal, UUID assignmentId) {
        MapSqlParameterSource params = base(principal).addValue("assignmentId", assignmentId);
        Map<String, Object> profile = jdbc.queryForMap("""
                with assignment_context as (
                    select assignment.position_id, assignment.org_unit_id,
                           (
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
                           ) as hotel_id
                    from employee_position_assignment assignment
                    join employee employee_record
                      on employee_record.tenant_id = assignment.tenant_id
                     and employee_record.id = assignment.employee_id
                     and employee_record.employment_status = 'ACTIVE'
                     and employee_record.deleted_at is null
                    join org_unit assignment_org
                      on assignment_org.tenant_id = assignment.tenant_id
                     and assignment_org.id = assignment.org_unit_id
                     and assignment_org.status = 'ACTIVE'
                    join position_definition position
                      on position.tenant_id = assignment.tenant_id
                     and position.id = assignment.position_id
                     and position.status = 'ACTIVE'
                     and position.deleted_at is null
                     and position.permanently_deleted_at is null
                    where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                      and assignment.status = 'ACTIVE'
                      and assignment.valid_from <= current_date
                      and (assignment.valid_to is null or assignment.valid_to >= current_date)
                )
                select group_profile.default_role_id,
                       coalesce(hotel_version.id, group_version.id) as effective_version_id,
                       coalesce(hotel_version.authorization_scope_type,
                                group_version.authorization_scope_type, 'SELF') as authorization_scope_type,
                       coalesce(hotel_version.wecom_self_selectable,
                                group_version.wecom_self_selectable, false) as wecom_self_selectable
                from assignment_context context
                join position_function_profile group_profile
                  on group_profile.tenant_id = :tenantId
                 and group_profile.position_id = context.position_id
                 and group_profile.scope_type = 'GROUP'
                left join position_function_profile_version group_version
                  on group_version.tenant_id = group_profile.tenant_id
                 and group_version.profile_id = group_profile.id
                 and group_version.lifecycle_status = 'PUBLISHED'
                left join position_function_profile hotel_profile
                  on hotel_profile.tenant_id = group_profile.tenant_id
                 and hotel_profile.position_id = group_profile.position_id
                 and hotel_profile.scope_type = 'HOTEL'
                 and hotel_profile.hotel_org_unit_id = context.hotel_id
                left join position_function_profile_version hotel_version
                  on hotel_version.tenant_id = hotel_profile.tenant_id
                 and hotel_version.profile_id = hotel_profile.id
                 and hotel_version.lifecycle_status = 'PUBLISHED'
                where exists (
                    select 1
                    from position_definition position
                    where position.tenant_id = :tenantId
                      and position.id = context.position_id
                      and (
                        position.applies_to_all_hotels = true
                        or (
                          context.hotel_id is not null
                          and exists (
                            select 1
                            from position_applicable_hotel applicable
                            where applicable.tenant_id = position.tenant_id
                              and applicable.position_id = position.id
                              and applicable.hotel_org_unit_id = context.hotel_id
                          )
                        )
                      )
                )
                """, params);
        UUID versionId = (UUID) profile.get("effective_version_id");
        UUID roleId = (UUID) profile.get("default_role_id");
        Set<String> permissionCodes;
        if (versionId != null) {
            permissionCodes = new LinkedHashSet<>(jdbc.queryForList("""
                    select permission.code
                    from position_function_profile_permission item
                    join permission on permission.id = item.permission_id
                    where item.tenant_id = :tenantId and item.profile_version_id = :versionId
                    order by permission.code
                    """, params.addValue("versionId", versionId), String.class));
        } else {
            // Compatibility for pre-P1 positions until their first explicit profile publish.
            permissionCodes = new LinkedHashSet<>(jdbc.queryForList("""
                    select permission.code
                    from role_permission grant_item
                    join permission on permission.id = grant_item.permission_id
                    where grant_item.tenant_id = :tenantId and grant_item.role_id = :roleId
                      and permission.delegable_to_position = true
                    order by permission.code
                    """, params.addValue("roleId", roleId), String.class));
        }
        return new AssignmentFunctionProfile(
                Set.copyOf(permissionCodes),
                String.valueOf(profile.get("authorization_scope_type")),
                Boolean.TRUE.equals(profile.get("wecom_self_selectable"))
        );
    }

    private Set<String> supplementalAccountPermissions(TenantPrincipal principal, UUID assignmentId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select distinct permission.code
                from role_assignment assignment
                join app_role role
                  on role.tenant_id = assignment.tenant_id and role.id = assignment.role_id
                join role_permission grant_item
                  on grant_item.tenant_id = role.tenant_id and grant_item.role_id = role.id
                join permission on permission.id = grant_item.permission_id
                join employee employee_record
                  on employee_record.tenant_id = assignment.tenant_id
                 and employee_record.account_id = assignment.account_id
                 and employee_record.employment_status = 'ACTIVE'
                 and employee_record.deleted_at is null
                join employee_position_assignment position_assignment
                  on position_assignment.tenant_id = employee_record.tenant_id
                 and position_assignment.employee_id = employee_record.id
                 and position_assignment.id = :assignmentId
                 and position_assignment.status = 'ACTIVE'
                 and position_assignment.valid_from <= current_date
                 and (position_assignment.valid_to is null
                      or position_assignment.valid_to >= current_date)
                join position_definition position
                  on position.tenant_id = position_assignment.tenant_id
                 and position.id = position_assignment.position_id
                 and position.status = 'ACTIVE'
                 and position.deleted_at is null
                 and position.permanently_deleted_at is null
                left join lateral (
                    select ancestor.id
                    from org_unit_closure hotel_closure
                    join org_unit ancestor
                      on ancestor.tenant_id = hotel_closure.tenant_id
                     and ancestor.id = hotel_closure.ancestor_id
                    where hotel_closure.tenant_id = position_assignment.tenant_id
                      and hotel_closure.descendant_id = position_assignment.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                      and ancestor.status = 'ACTIVE'
                    order by hotel_closure.depth
                    limit 1
                ) hotel_context on true
                where assignment.tenant_id = :tenantId
                  and assignment.account_id = :accountId
                  and assignment.valid_from <= now()
                  and (assignment.valid_to is null or assignment.valid_to > now())
                  and role.code = 'HR_KPI_ADMIN'
                  and (assignment.source_assignment_id is null
                       or (assignment.source_type = 'POSITION_ASSIGNMENT'
                           and assignment.source_assignment_id = position_assignment.id))
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
                  and (
                    assignment.scope_type in ('TENANT', 'SELF')
                    or (assignment.scope_type = 'ORG_UNIT'
                        and assignment.scope_org_unit_id = position_assignment.org_unit_id)
                    or (assignment.scope_type = 'ORG_TREE'
                        and assignment.scope_org_unit_id is not null
                        and exists (
                          select 1
                          from org_unit_closure grant_scope
                          where grant_scope.tenant_id = assignment.tenant_id
                            and grant_scope.ancestor_id = assignment.scope_org_unit_id
                            and grant_scope.descendant_id = position_assignment.org_unit_id
                        ))
                  )
                order by permission.code
                """, base(principal).addValue("accountId", principal.actorId())
                .addValue("assignmentId", assignmentId), String.class));
    }

    private record AssignmentRow(
            UUID id, UUID organizationId, String organizationCode, String organizationName,
            UUID positionId, String positionCode, String positionName, boolean primary,
            String assignmentType, java.time.LocalDate validFrom, java.time.LocalDate validTo
    ) {
    }

    private record AssignmentFunctionProfile(
            Set<String> permissionCodes, String authorizationScopeType, boolean wecomSelfSelectable
    ) {
    }

    private record RoleLock(UUID id, String code, String roleType) {
    }
}
