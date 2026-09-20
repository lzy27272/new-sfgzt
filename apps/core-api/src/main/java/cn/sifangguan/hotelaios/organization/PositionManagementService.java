package cn.sifangguan.hotelaios.organization;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.features.GroupManagementFeatureGate;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PositionManagementService {
    private static final Set<String> AUTHORIZATION_SCOPES =
            Set.of("SELF", "ORG_UNIT", "ORG_TREE", "ASSIGNED_HOTELS", "TENANT");
    private static final Map<String, Integer> SCOPE_RANK = Map.of(
            "SELF", 0, "ORG_UNIT", 1, "ORG_TREE", 2, "ASSIGNED_HOTELS", 3, "TENANT", 4
    );
    private static final Set<String> PROTECTED_SYSTEM_POSITION_ROLES =
            Set.of("CEO", "PLATFORM_ADMIN", "HR_KPI_ADMIN");
    private static final Set<String> ADMIN_ONLY_CONFIGURATION_PERMISSIONS = Set.of(
            "ui.module.rules", "ui.module.work-packages",
            "rule.manage", "rule.simulate", "rule.publish",
            "work-package.manage", "work-package.publish", "work-package.allocate"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final GroupManagementFeatureGate featureGate;

    public PositionManagementService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            GroupManagementFeatureGate featureGate
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.featureGate = featureGate;
    }

    @Transactional(readOnly = true)
    public List<PositionManagementModels.PositionSummary> list(boolean deleted) {
        accessPolicy.requirePermission("position-profile.read");
        TenantPrincipal principal = prepare();
        String lifecycle = deleted
                ? "position.deleted_at is not null and position.permanently_deleted_at is null"
                : "position.deleted_at is null and position.permanently_deleted_at is null";
        List<PositionRow> positions = jdbc.query("""
                select position.id, position.name, position.status, position.row_version,
                       position.applies_to_all_hotels, position.deleted_at,
                       actor.display_name as deleted_by,
                       count(assignment.id) filter (where assignment.status = 'ACTIVE') as assignment_count,
                       count(distinct assignment.employee_id) filter (where assignment.status = 'ACTIVE') as employee_count
                from position_definition position
                left join user_account actor
                  on actor.tenant_id = position.tenant_id and actor.id = position.deleted_by
                left join employee_position_assignment assignment
                  on assignment.tenant_id = position.tenant_id
                 and assignment.position_id = position.id
                where position.tenant_id = :tenantId and """ + " " + lifecycle + " " + """
                group by position.id, actor.display_name
                order by position.name, position.id
                """, base(principal), (rs, rowNum) -> new PositionRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getLong("row_version"),
                rs.getBoolean("applies_to_all_hotels"),
                rs.getObject("deleted_at", OffsetDateTime.class),
                rs.getString("deleted_by"),
                rs.getLong("assignment_count"),
                rs.getLong("employee_count")
        ));
        if (positions.isEmpty()) return List.of();

        Set<UUID> ids = new LinkedHashSet<>();
        positions.forEach(position -> ids.add(position.id()));
        Map<UUID, List<PositionManagementModels.ApplicableHotel>> hotels = loadHotels(principal, ids);
        Map<UUID, PositionManagementModels.ProfileSummary> profiles = loadProfiles(principal, ids, null);
        return positions.stream().map(position -> new PositionManagementModels.PositionSummary(
                position.id(), position.name(), position.status(), position.rowVersion(),
                position.appliesToAllHotels(), hotels.getOrDefault(position.id(), List.of()),
                position.assignmentCount(), position.employeeCount(), position.deletedAt(),
                position.deletedBy(), profiles.get(position.id())
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<PositionManagementModels.PermissionOption> functionOptions(UUID positionId) {
        accessPolicy.requirePermission("position-profile.read");
        TenantPrincipal principal = prepare();
        PositionLock position = positionId == null
                ? null : requirePosition(principal, positionId, false, false);
        return jdbc.query("""
                select code, coalesce(nullif(description, ''), code) as label,
                       function_category, delegable_to_position
                from permission
                where delegable_to_position = true
                order by function_category, code
                """, Map.of(), (rs, rowNum) -> new PositionManagementModels.PermissionOption(
                rs.getString("code"), rs.getString("label"),
                rs.getString("function_category"),
                rs.getBoolean("delegable_to_position")
                        && permissionAllowedForPosition(position, rs.getString("code")),
                permissionRestrictionReason(position, rs.getString("code"))
        ));
    }

    @Transactional(readOnly = true)
    public PositionManagementModels.ProfileContext profileContext(UUID positionId) {
        accessPolicy.requirePermission("position-profile.read");
        TenantPrincipal principal = prepare();
        PositionLock position = positionId == null
                ? null : requirePosition(principal, positionId, false, false);
        List<PositionManagementModels.PermissionOption> retained = position == null
                ? List.of() : retainedPermissions(principal, positionId);
        return new PositionManagementModels.ProfileContext(
                position == null ? "CUSTOM" : position.roleCode(),
                AUTHORIZATION_SCOPES.stream().sorted(Comparator.comparingInt(SCOPE_RANK::get)).toList(),
                retained,
                retained.isEmpty() ? null : "该岗位包含系统保留权限，不能开放员工自选；请由有权审核员分配任职",
                featureGate.workPlansEnabled(principal.tenantId()),
                featureGate.executiveTasksEnabled(principal.tenantId())
        );
    }

    private List<PositionManagementModels.PermissionOption> retainedPermissions(
            TenantPrincipal principal, UUID positionId
    ) {
        return jdbc.query("""
                select permission.code, coalesce(nullif(permission.description, ''), permission.code) as label,
                       permission.function_category
                from position_function_profile profile
                join role_permission grant_item
                  on grant_item.tenant_id = profile.tenant_id and grant_item.role_id = profile.default_role_id
                join permission on permission.id = grant_item.permission_id
                where profile.tenant_id = :tenantId and profile.position_id = :positionId
                  and profile.scope_type = 'GROUP' and permission.delegable_to_position = false
                order by permission.code
                """, base(principal).addValue("positionId", positionId),
                (rs, rowNum) -> new PositionManagementModels.PermissionOption(
                        rs.getString("code"), rs.getString("label"), rs.getString("function_category"),
                        false, "系统保留权限，不由岗位功能草稿增删；实际生效以账号和任职授权为准"
                ));
    }

    private void requireSelfSelectionAllowed(TenantPrincipal principal, UUID positionId, boolean selectable) {
        if (selectable && !retainedPermissions(principal, positionId).isEmpty()) {
            throw new IllegalArgumentException("该岗位包含系统保留权限，不能开放员工自选；请由有权审核员分配任职");
        }
    }

    @Transactional
    public PositionManagementModels.PositionSummary create(
            PositionManagementModels.CreatePositionRequest request
    ) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        String name = requireName(request.name());
        String authorizationScope = normalizeScope(request.authorizationScopeType());
        List<UUID> hotels = validateHotelScope(
                principal, request.appliesToAllHotels(), request.applicableHotelIds()
        );
        Set<String> selected;
        if (request.permissionCodes() != null) {
            selected = requireDelegablePermissions(request.permissionCodes());
        } else if (request.copyFromPositionId() != null) {
            requirePosition(principal, request.copyFromPositionId(), false, false);
            selected = effectiveTemplatePermissions(principal, request.copyFromPositionId());
        } else {
            selected = Set.of();
        }

        UUID positionId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        String internalCode = "CUSTOM_" + positionId.toString().replace("-", "").toUpperCase(Locale.ROOT);
        requirePositionPermissionBoundaries(internalCode, internalCode, selected);
        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("roleId", roleId)
                .addValue("profileId", profileId)
                .addValue("draftId", draftId)
                .addValue("code", internalCode)
                .addValue("name", name)
                .addValue("allHotels", request.appliesToAllHotels())
                .addValue("scope", authorizationScope)
                .addValue("selfSelectable", request.wecomSelfSelectable())
                .addValue("actorId", principal.actorId());

        jdbc.update("""
                insert into position_definition
                    (id, tenant_id, code, name, job_family, level_code, status,
                     applies_to_all_hotels)
                values
                    (:positionId, :tenantId, :code, :name, 'CUSTOM', null, 'ACTIVE', :allHotels)
                """, params);
        jdbc.update("""
                insert into app_role (id, tenant_id, code, name, role_type)
                values (:roleId, :tenantId, :code, :name, 'CUSTOM')
                """, params);
        jdbc.update("""
                insert into position_function_profile
                    (id, tenant_id, position_id, scope_type, default_role_id)
                values (:profileId, :tenantId, :positionId, 'GROUP', :roleId)
                """, params);
        jdbc.update("""
                insert into position_function_profile_version
                    (id, tenant_id, profile_id, version_no, lifecycle_status,
                     authorization_scope_type, wecom_self_selectable, created_by)
                values
                    (:draftId, :tenantId, :profileId, 1, 'DRAFT',
                     :scope, :selfSelectable, :actorId)
                """, params);
        replacePermissions(principal, draftId, selected);
        replaceHotels(principal, positionId, hotels);

        auditWriter.record("POSITION_CREATED", "POSITION", positionId, json(Map.of(
                "name", name,
                "appliesToAllHotels", request.appliesToAllHotels(),
                "applicableHotelCount", hotels.size(),
                "permissionCount", selected.size(),
                "authorizationScopeType", authorizationScope,
                "wecomSelfSelectable", request.wecomSelfSelectable(),
                "lifecycleStatus", "DRAFT"
        )));
        return getSummary(principal, positionId, false);
    }

    @Transactional
    public PositionManagementModels.PositionSummary update(
            UUID positionId,
            PositionManagementModels.UpdatePositionRequest request
    ) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, false);
        requireExpected(position.rowVersion(), request.expectedVersion());
        String name = requireName(request.name());
        List<UUID> hotels = validateHotelScope(
                principal, request.appliesToAllHotels(), request.applicableHotelIds()
        );
        if (isProtectedSystemPosition(position)
                && applicabilityChanged(principal, position, request.appliesToAllHotels(), hotels)) {
            throw new IllegalArgumentException("内置高权限岗位不允许收窄或变更适用门店范围");
        }
        int updated = jdbc.update("""
                update position_definition
                set name = :name, applies_to_all_hotels = :allHotels,
                    row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :positionId
                  and row_version = :expectedVersion
                  and deleted_at is null and permanently_deleted_at is null
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("name", name)
                .addValue("allHotels", request.appliesToAllHotels())
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) throw stale();
        replaceHotels(principal, positionId, hotels);
        List<AssignmentAccess> removedHotelAssignments = lockAssignmentAccess(
                principal, positionId, true
        );
        AccessRevocation revocation = revokePositionAccess(
                principal, positionId, removedHotelAssignments,
                "POSITION_NOT_APPLICABLE_TO_HOTEL",
                "岗位适用门店已调整，您在被移除门店的任职和企业微信身份已暂停，请联系管理员重新配置。",
                "applicability-v" + (request.expectedVersion() + 1)
        );
        jdbc.update("""
                update app_role role
                set name = :name, updated_at = now()
                from position_function_profile profile
                where profile.tenant_id = :tenantId and profile.position_id = :positionId
                  and profile.scope_type = 'GROUP'
                  and role.tenant_id = profile.tenant_id and role.id = profile.default_role_id
                """, base(principal).addValue("positionId", positionId).addValue("name", name));
        auditWriter.record("POSITION_UPDATED", "POSITION", positionId, json(Map.of(
                "name", name,
                "appliesToAllHotels", request.appliesToAllHotels(),
                "applicableHotelCount", hotels.size(),
                "previousVersion", request.expectedVersion(),
                "rowVersion", request.expectedVersion() + 1,
                "pausedAssignmentCount", revocation.assignmentCount(),
                "suspendedBindingCount", revocation.bindingCount(),
                "endedRoleGrantCount", revocation.roleGrantCount(),
                "groupPushChanged", false
        )));
        return getSummary(principal, positionId, false);
    }

    @Transactional(readOnly = true)
    public PositionManagementModels.ImpactPreview preview(
            UUID positionId,
            PositionManagementModels.ImpactPreviewRequest request
    ) {
        accessPolicy.requirePermission("position-profile.read");
        TenantPrincipal principal = prepare();
        PositionLock position = requirePosition(principal, positionId, false, true);
        String operation = request.operation().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DELETE", "RESTORE", "PUBLISH_PROFILE", "UPDATE_APPLICABILITY").contains(operation)) {
            throw new IllegalArgumentException("不支持的影响预览操作");
        }
        if ("UPDATE_APPLICABILITY".equals(operation)) {
            if (request.appliesToAllHotels() == null) {
                throw new IllegalArgumentException("适用门店变更预览必须提供appliesToAllHotels");
            }
            List<UUID> requestedHotels = validateHotelScope(principal,
                    request.appliesToAllHotels(), request.applicableHotelIds());
            if (isProtectedSystemPosition(position)
                    && applicabilityChanged(principal, position,
                    request.appliesToAllHotels(), requestedHotels)) {
                throw new IllegalArgumentException("内置高权限岗位不允许收窄或变更适用门店范围");
            }
            ApplicabilityImpact impact = applicabilityImpact(
                    principal, positionId, request.appliesToAllHotels(), requestedHotels
            );
            return new PositionManagementModels.ImpactPreview(
                    positionId, operation, impact.assignments(), impact.employees(),
                    impact.resultingHotelCount(), List.of(), List.of(), List.of(),
                    impact.removedHotels(), impact.activeBindings(), impact.roleGrants(),
                    position.rowVersion()
            );
        }
        AssignmentImpact assignmentImpact = assignmentImpact(principal, positionId);
        Set<String> current = publishedPermissions(principal, positionId, null);
        Set<String> proposed = request.permissionCodes() == null
                ? draftPermissions(principal, positionId, null)
                : normalizeCodes(request.permissionCodes());
        Set<String> blocked = new LinkedHashSet<>(blockedPermissions(proposed));
        blocked.addAll(restrictedPermissionCodes(
                position.positionCode(), position.roleCode(), proposed
        ));
        Set<String> allowedProposed = new LinkedHashSet<>(proposed);
        allowedProposed.removeAll(blocked);
        return new PositionManagementModels.ImpactPreview(
                positionId, operation,
                assignmentImpact.assignments(), assignmentImpact.employees(),
                applicableHotelCount(principal, positionId),
                difference(allowedProposed, current), difference(current, allowedProposed),
                List.copyOf(blocked), List.of(),
                "DELETE".equals(operation) ? assignmentImpact.activeBindings() : 0L,
                "DELETE".equals(operation) ? assignmentImpact.roleGrants() : 0L,
                position.rowVersion()
        );
    }

    @Transactional
    public void delete(UUID positionId, long expectedVersion) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, false);
        requireExpected(position.rowVersion(), expectedVersion);
        requireDeletablePosition(position);
        AssignmentImpact impact = assignmentImpact(principal, positionId);
        UUID deletionBatchId = UUID.randomUUID();
        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("batchId", deletionBatchId)
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", expectedVersion);

        // The ordered read gives concurrent assignment/deletion paths one lock order.
        List<AssignmentAccess> assignments = lockAssignmentAccess(principal, positionId, false);
        jdbc.update("""
                insert into position_assignment_recycle_snapshot
                    (tenant_id, position_id, assignment_id, deletion_batch_id,
                     previous_status, previous_valid_to, previous_is_primary)
                select tenant_id, position_id, id, :batchId, status, valid_to, is_primary
                from employee_position_assignment
                where tenant_id = :tenantId and position_id = :positionId and status = 'ACTIVE'
                order by id
                """, params);
        AccessRevocation revocation = revokePositionAccess(
                principal, positionId, assignments,
                "POSITION_DELETED",
                "岗位已删除，相关任职、岗位权限和企业微信身份已暂停，如需恢复请联系管理员。",
                "deleted-v" + (expectedVersion + 1)
        );
        int updated = jdbc.update("""
                update position_definition
                set status = 'INACTIVE', deleted_at = now(), deleted_by = :actorId,
                    deletion_batch_id = :batchId, row_version = row_version + 1,
                    updated_at = now()
                where tenant_id = :tenantId and id = :positionId
                  and row_version = :expectedVersion
                  and deleted_at is null and permanently_deleted_at is null
                """, params);
        if (updated != 1) throw stale();
        auditWriter.record("POSITION_SOFT_DELETED", "POSITION", positionId, json(Map.of(
                "pausedAssignmentCount", revocation.assignmentCount(),
                "affectedEmployeeCount", impact.employees(),
                "suspendedBindingCount", revocation.bindingCount(),
                "endedRoleGrantCount", revocation.roleGrantCount(),
                "groupPushChanged", false,
                "rowVersion", expectedVersion + 1
        )));
    }

    @Transactional
    public PositionManagementModels.RestoreResult restore(
            UUID positionId,
            PositionManagementModels.RestorePositionRequest request
    ) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, true);
        requireExpected(position.rowVersion(), request.expectedVersion());
        if (position.deletionBatchId() == null) {
            throw new IllegalArgumentException("岗位不在可恢复的已删除列表中");
        }
        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("batchId", position.deletionBatchId())
                .addValue("expectedVersion", request.expectedVersion());
        List<RecycleAssignment> snapshots = jdbc.query("""
                select snapshot.id, snapshot.assignment_id, snapshot.previous_status,
                       snapshot.previous_valid_to, snapshot.previous_is_primary,
                       assignment.employee_id
                from position_assignment_recycle_snapshot snapshot
                join employee_position_assignment assignment
                  on assignment.tenant_id = snapshot.tenant_id
                 and assignment.id = snapshot.assignment_id
                where snapshot.tenant_id = :tenantId
                  and snapshot.position_id = :positionId
                  and snapshot.deletion_batch_id = :batchId
                  and snapshot.restored_at is null
                order by snapshot.assignment_id
                for update of snapshot, assignment
                """, params, (rs, rowNum) -> new RecycleAssignment(
                rs.getObject("id", UUID.class),
                rs.getObject("assignment_id", UUID.class),
                rs.getString("previous_status"),
                rs.getObject("previous_valid_to", LocalDate.class),
                rs.getBoolean("previous_is_primary"),
                rs.getObject("employee_id", UUID.class)
        ));
        int restored = 0;
        int skipped = 0;
        for (RecycleAssignment snapshot : snapshots) {
            String outcome = "SKIPPED_BY_CHOICE";
            if (request.restoreAssignments()) {
                boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                            select 1
                            from employee_position_assignment assignment
                            join employee employee_record
                              on employee_record.tenant_id = assignment.tenant_id
                             and employee_record.id = assignment.employee_id
                            join org_unit org
                              on org.tenant_id = assignment.tenant_id
                             and org.id = assignment.org_unit_id
                            join position_definition position
                              on position.tenant_id = assignment.tenant_id
                             and position.id = assignment.position_id
                            left join lateral (
                                select ancestor.id
                                from org_unit_closure closure
                                join org_unit ancestor
                                  on ancestor.tenant_id = closure.tenant_id
                                 and ancestor.id = closure.ancestor_id
                                where closure.tenant_id = assignment.tenant_id
                                  and closure.descendant_id = assignment.org_unit_id
                                  and ancestor.unit_type = 'HOTEL'
                                  and ancestor.status = 'ACTIVE'
                                order by closure.depth
                                limit 1
                            ) hotel_context on true
                            where assignment.tenant_id = :tenantId
                              and assignment.id = :assignmentId
                              and assignment.status = 'INACTIVE'
                              and employee_record.employment_status = 'ACTIVE'
                              and employee_record.deleted_at is null
                              and org.status = 'ACTIVE'
                              and (cast(:previousValidTo as date) is null
                                   or cast(:previousValidTo as date) >= current_date)
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
                        )
                        """, base(principal)
                        .addValue("assignmentId", snapshot.assignmentId())
                        .addValue("previousValidTo", snapshot.previousValidTo()), Boolean.class));
                if (eligible) {
                    boolean primary = snapshot.previousPrimary() && !Boolean.TRUE.equals(jdbc.queryForObject("""
                            select exists (
                                select 1 from employee_position_assignment
                                 where tenant_id = :tenantId and employee_id = :employeeId
                                   and id <> :assignmentId and is_primary = true
                                   and status = 'ACTIVE'
                                   and valid_from <= current_date
                                   and (valid_to is null or valid_to >= current_date)
                             )
                            """, base(principal)
                            .addValue("employeeId", snapshot.employeeId())
                            .addValue("assignmentId", snapshot.assignmentId()), Boolean.class));
                    jdbc.update("""
                            update employee_position_assignment
                            set status = :status, valid_to = :validTo, is_primary = :primary,
                                updated_at = now()
                            where tenant_id = :tenantId and id = :assignmentId
                            """, base(principal)
                            .addValue("assignmentId", snapshot.assignmentId())
                            .addValue("status", snapshot.previousStatus())
                            .addValue("validTo", snapshot.previousValidTo())
                            .addValue("primary", primary));
                    outcome = primary == snapshot.previousPrimary()
                            ? "RESTORED" : "RESTORED_PRIMARY_DOWNGRADED";
                    restored++;
                } else {
                    outcome = "SKIPPED_INELIGIBLE";
                    skipped++;
                }
            } else {
                skipped++;
            }
            jdbc.update("""
                    update position_assignment_recycle_snapshot
                    set restored_at = now(), restore_outcome = :outcome
                    where tenant_id = :tenantId and id = :snapshotId and restored_at is null
                    """, base(principal)
                    .addValue("snapshotId", snapshot.id())
                    .addValue("outcome", outcome));
        }
        int updated = jdbc.update("""
                update position_definition
                set status = 'ACTIVE', deleted_at = null, deleted_by = null,
                    deletion_batch_id = null, row_version = row_version + 1,
                    updated_at = now()
                where tenant_id = :tenantId and id = :positionId
                  and row_version = :expectedVersion
                  and deleted_at is not null and permanently_deleted_at is null
                """, params);
        if (updated != 1) throw stale();
        PositionManagementModels.RestoreResult result = new PositionManagementModels.RestoreResult(
                positionId, request.expectedVersion() + 1, restored, skipped
        );
        auditWriter.record("POSITION_RESTORED", "POSITION", positionId, json(result));
        return result;
    }

    @Transactional
    public void permanentlyDelete(UUID positionId, long expectedVersion) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, true);
        requireExpected(position.rowVersion(), expectedVersion);
        requireDeletablePosition(position);
        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("actorId", principal.actorId())
                .addValue("tombstoneCode", "DELETED_" + positionId.toString().replace("-", "").toUpperCase(Locale.ROOT));
        jdbc.update("""
                delete from position_applicable_hotel
                where tenant_id = :tenantId and position_id = :positionId
                """, params);
        jdbc.update("""
                update position_function_profile_version version
                set lifecycle_status = 'ARCHIVED', updated_at = now(),
                    row_version = version.row_version + 1
                from position_function_profile profile
                where profile.tenant_id = :tenantId and profile.position_id = :positionId
                  and version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                  and version.lifecycle_status in ('DRAFT', 'PUBLISHED')
                """, params);
        endResidualDefaultRoleGrants(principal, positionId);
        jdbc.update("""
                delete from role_permission grant_item
                using position_function_profile profile, app_role role
                where profile.tenant_id = :tenantId and profile.position_id = :positionId
                  and profile.scope_type = 'GROUP'
                  and grant_item.tenant_id = profile.tenant_id
                  and grant_item.role_id = profile.default_role_id
                  and role.tenant_id = profile.tenant_id
                  and role.id = profile.default_role_id
                  and role.role_type = 'CUSTOM'
                  and not exists (
                    select 1
                    from position_function_profile other_profile
                    join position_definition other_position
                      on other_position.tenant_id = other_profile.tenant_id
                     and other_position.id = other_profile.position_id
                    where other_profile.tenant_id = profile.tenant_id
                      and other_profile.scope_type = 'GROUP'
                      and other_profile.default_role_id = profile.default_role_id
                      and other_profile.position_id <> profile.position_id
                      and other_position.permanently_deleted_at is null
                  )
                """, params);
        int updated = jdbc.update("""
                update position_definition
                set name = case when name like '%（已删除）' then name else name || '（已删除）' end,
                    code = :tombstoneCode, job_family = 'DELETED', level_code = null,
                    status = 'INACTIVE', applies_to_all_hotels = false,
                    permanently_deleted_at = now(), permanently_deleted_by = :actorId,
                    row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :positionId
                  and row_version = :expectedVersion
                  and deleted_at is not null and permanently_deleted_at is null
                """, params);
        if (updated != 1) throw stale();
        auditWriter.record("POSITION_PERMANENTLY_DELETED", "POSITION", positionId, json(Map.of(
                "historicalTombstoneRetained", true,
                "rowVersion", expectedVersion + 1
        )));
    }

    @Transactional
    public PositionManagementModels.ProfileSummary saveDraft(
            UUID positionId,
            UUID hotelId,
            PositionManagementModels.DraftProfileRequest request
    ) {
        accessPolicy.requirePermission("position-profile.manage");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, false);
        String authorizationScope = normalizeScope(request.authorizationScopeType());
        requireSelfSelectionAllowed(principal, positionId, request.wecomSelfSelectable());
        Set<String> permissions = requireDelegablePermissions(request.permissionCodes());
        requirePositionPermissionBoundaries(position.positionCode(), position.roleCode(), permissions);
        ProfileLock profile = hotelId == null
                ? lockGroupProfile(principal, positionId)
                : requireOrCreateHotelProfile(principal, positionId, hotelId);
        VersionLock draft = lockOrCreateDraft(principal, profile.id());
        requireExpected(draft.rowVersion(), request.expectedProfileVersion());

        UUID groupBaseline = null;
        if (hotelId != null) {
            VersionLock baseline = publishedVersion(principal, positionId, null);
            groupBaseline = baseline.id();
            requireHotelReduction(principal, permissions, authorizationScope,
                    request.wecomSelfSelectable(), baseline);
        }
        int updated = jdbc.update("""
                update position_function_profile_version
                set authorization_scope_type = :scope,
                    wecom_self_selectable = :selfSelectable,
                    based_on_group_version_id = :baselineId,
                    row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :draftId
                  and lifecycle_status = 'DRAFT' and row_version = :expectedVersion
                """, base(principal)
                .addValue("draftId", draft.id())
                .addValue("scope", authorizationScope)
                .addValue("selfSelectable", request.wecomSelfSelectable())
                .addValue("baselineId", groupBaseline)
                .addValue("expectedVersion", request.expectedProfileVersion()));
        if (updated != 1) throw stale();
        replacePermissions(principal, draft.id(), permissions);
        jdbc.update("""
                update position_function_profile
                set row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :profileId
                """, base(principal).addValue("profileId", profile.id()));
        auditWriter.record("POSITION_PROFILE_DRAFT_SAVED", "POSITION_PROFILE_VERSION", draft.id(), json(Map.of(
                "positionId", positionId,
                "hotelId", hotelId == null ? "GROUP" : hotelId.toString(),
                "permissionCount", permissions.size(),
                "authorizationScopeType", authorizationScope,
                "wecomSelfSelectable", request.wecomSelfSelectable(),
                "rowVersion", request.expectedProfileVersion() + 1
        )));
        return profileSummary(principal, positionId, hotelId);
    }

    @Transactional
    public PositionManagementModels.ProfileSummary publish(
            UUID positionId,
            UUID hotelId,
            PositionManagementModels.PublishProfileRequest request
    ) {
        accessPolicy.requirePermission("position-profile.publish");
        TenantPrincipal principal = prepareForManagement();
        PositionLock position = lockPosition(principal, positionId, false);
        requireExpected(position.rowVersion(), request.expectedPositionVersion());
        ProfileLock profile = hotelId == null
                ? lockGroupProfile(principal, positionId)
                : lockHotelProfile(principal, positionId, hotelId);
        VersionLock draft = lockDraft(principal, profile.id());
        requireExpected(draft.rowVersion(), request.expectedProfileVersion());
        requireSelfSelectionAllowed(principal, positionId, draft.wecomSelfSelectable());
        Set<String> selected = permissionsForVersion(principal, draft.id());
        requirePositionPermissionBoundaries(position.positionCode(), position.roleCode(), selected);

        if (hotelId != null) {
            VersionLock baseline = publishedVersion(principal, positionId, null);
            requireHotelReduction(principal, selected, draft.authorizationScopeType(),
                    draft.wecomSelfSelectable(), baseline);
            if (!baseline.id().equals(draft.basedOnGroupVersionId())) {
                throw conflict("门店草稿基于旧版集团标准，请保存刷新后的草稿再发布");
            }
        }

        jdbc.update("""
                update position_function_profile_version
                set lifecycle_status = 'ARCHIVED', row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and profile_id = :profileId
                  and lifecycle_status = 'PUBLISHED'
                """, base(principal).addValue("profileId", profile.id()));
        int promoted = jdbc.update("""
                update position_function_profile_version
                set lifecycle_status = 'PUBLISHED', published_by = :actorId,
                    published_at = now(), row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :draftId
                  and lifecycle_status = 'DRAFT' and row_version = :expectedVersion
                """, base(principal)
                .addValue("draftId", draft.id())
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", request.expectedProfileVersion()));
        if (promoted != 1) throw stale();

        UUID nextDraftId = UUID.randomUUID();
        int nextVersion = draft.versionNo() + 1;
        jdbc.update("""
                insert into position_function_profile_version
                    (id, tenant_id, profile_id, version_no, lifecycle_status,
                     copied_from_version_id, based_on_group_version_id,
                     authorization_scope_type, wecom_self_selectable, created_by)
                values
                    (:id, :tenantId, :profileId, :versionNo, 'DRAFT',
                     :publishedId, :baselineId, :scope, :selfSelectable, :actorId)
                """, base(principal)
                .addValue("id", nextDraftId)
                .addValue("profileId", profile.id())
                .addValue("versionNo", nextVersion)
                .addValue("publishedId", draft.id())
                .addValue("baselineId", draft.basedOnGroupVersionId())
                .addValue("scope", draft.authorizationScopeType())
                .addValue("selfSelectable", draft.wecomSelfSelectable())
                .addValue("actorId", principal.actorId()));
        replacePermissions(principal, nextDraftId, selected);

        if (hotelId == null) {
            replaceRolePermissions(principal, profile.defaultRoleId(), selected);
            jdbc.update("""
                    update role_assignment grant_record
                    set scope_type = :scope,
                        scope_org_unit_id = case
                            when :scope in ('ORG_UNIT', 'ORG_TREE') then assignment.org_unit_id
                            else null
                        end
                    from employee_position_assignment assignment
                    where grant_record.tenant_id = :tenantId
                      and assignment.tenant_id = grant_record.tenant_id
                      and assignment.id = grant_record.source_assignment_id
                      and grant_record.source_type = 'POSITION_ASSIGNMENT'
                      and assignment.position_id = :positionId
                    """, base(principal)
                    .addValue("scope", draft.authorizationScopeType())
                    .addValue("positionId", positionId));
            rebaseHotelProfiles(principal, positionId, draft.id(), draft.authorizationScopeType(),
                    draft.wecomSelfSelectable(), selected);
        }
        jdbc.update("""
                update position_function_profile
                set row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :profileId
                """, base(principal).addValue("profileId", profile.id()));
        int positionUpdated = jdbc.update("""
                update position_definition
                set row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :positionId
                  and row_version = :expectedPositionVersion
                  and deleted_at is null and permanently_deleted_at is null
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("expectedPositionVersion", request.expectedPositionVersion()));
        if (positionUpdated != 1) throw stale();
        auditWriter.record("POSITION_PROFILE_PUBLISHED", "POSITION_PROFILE_VERSION", draft.id(), json(Map.of(
                "positionId", positionId,
                "hotelId", hotelId == null ? "GROUP" : hotelId.toString(),
                "publishedVersion", draft.versionNo(),
                "permissionCount", selected.size(),
                "authorizationScopeType", draft.authorizationScopeType(),
                "wecomSelfSelectable", draft.wecomSelfSelectable(),
                "positionRowVersion", request.expectedPositionVersion() + 1
        )));
        return profileSummary(principal, positionId, hotelId);
    }

    @Transactional(readOnly = true)
    public List<PositionManagementModels.ProfileVersionSummary> versions(UUID positionId, UUID hotelId) {
        accessPolicy.requirePermission("position-profile.read");
        TenantPrincipal principal = prepare();
        requirePosition(principal, positionId, false, false);
        ProfileLock profile = hotelId == null
                ? requireGroupProfile(principal, positionId)
                : requireHotelProfile(principal, positionId, hotelId);
        List<VersionLock> versions = jdbc.query("""
                select id, version_no, lifecycle_status, row_version,
                       based_on_group_version_id, authorization_scope_type,
                       wecom_self_selectable, published_at,
                       cast(published_by as varchar) as published_by
                from position_function_profile_version
                where tenant_id = :tenantId and profile_id = :profileId
                order by version_no desc
                """, base(principal).addValue("profileId", profile.id()),
                (rs, rowNum) -> versionLock(rs));
        return versions.stream().map(version -> new PositionManagementModels.ProfileVersionSummary(
                version.id(), version.versionNo(), version.status(), version.rowVersion(),
                version.basedOnGroupVersionId(), version.publishedAt(), version.publishedBy(),
                List.copyOf(permissionsForVersion(principal, version.id())),
                version.authorizationScopeType(), version.wecomSelfSelectable()
        )).toList();
    }

    private PositionManagementModels.PositionSummary getSummary(
            TenantPrincipal principal, UUID positionId, boolean deleted
    ) {
        PositionRow row;
        try {
            row = jdbc.queryForObject("""
                    select position.id, position.name, position.status, position.row_version,
                           position.applies_to_all_hotels, position.deleted_at,
                           actor.display_name as deleted_by,
                           count(assignment.id) filter (where assignment.status = 'ACTIVE') as assignment_count,
                           count(distinct assignment.employee_id) filter (where assignment.status = 'ACTIVE') as employee_count
                    from position_definition position
                    left join user_account actor
                      on actor.tenant_id = position.tenant_id and actor.id = position.deleted_by
                    left join employee_position_assignment assignment
                      on assignment.tenant_id = position.tenant_id
                     and assignment.position_id = position.id
                    where position.tenant_id = :tenantId and position.id = :positionId
                      and position.permanently_deleted_at is null
                      and ((:deleted = true and position.deleted_at is not null)
                           or (:deleted = false and position.deleted_at is null))
                    group by position.id, actor.display_name
                    """, base(principal)
                    .addValue("positionId", positionId)
                    .addValue("deleted", deleted), (rs, rowNum) -> new PositionRow(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                    rs.getLong("row_version"), rs.getBoolean("applies_to_all_hotels"),
                    rs.getObject("deleted_at", OffsetDateTime.class), rs.getString("deleted_by"),
                    rs.getLong("assignment_count"), rs.getLong("employee_count")
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw notFound();
        }
        Map<UUID, List<PositionManagementModels.ApplicableHotel>> hotels =
                loadHotels(principal, Set.of(positionId));
        return new PositionManagementModels.PositionSummary(
                row.id(), row.name(), row.status(), row.rowVersion(), row.appliesToAllHotels(),
                hotels.getOrDefault(positionId, List.of()), row.assignmentCount(), row.employeeCount(),
                row.deletedAt(), row.deletedBy(), profileSummary(principal, positionId, null)
        );
    }

    private Map<UUID, List<PositionManagementModels.ApplicableHotel>> loadHotels(
            TenantPrincipal principal, Collection<UUID> positionIds
    ) {
        Map<UUID, List<PositionManagementModels.ApplicableHotel>> result = new LinkedHashMap<>();
        jdbc.query("""
                select link.position_id, org.id, org.code, org.name
                from position_applicable_hotel link
                join org_unit org
                  on org.tenant_id = link.tenant_id and org.id = link.hotel_org_unit_id
                where link.tenant_id = :tenantId and link.position_id in (:positionIds)
                order by org.name, org.id
                """, base(principal).addValue("positionIds", positionIds), rs -> {
            UUID positionId = rs.getObject("position_id", UUID.class);
            result.computeIfAbsent(positionId, ignored -> new ArrayList<>()).add(
                    new PositionManagementModels.ApplicableHotel(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")
                    )
            );
        });
        return result;
    }

    private Map<UUID, PositionManagementModels.ProfileSummary> loadProfiles(
            TenantPrincipal principal, Collection<UUID> positionIds, UUID hotelId
    ) {
        Map<UUID, PositionManagementModels.ProfileSummary> result = new LinkedHashMap<>();
        for (UUID positionId : positionIds) {
            try {
                result.put(positionId, profileSummary(principal, positionId, hotelId));
            } catch (ResponseStatusException ignored) {
                result.put(positionId, null);
            }
        }
        return result;
    }

    private PositionManagementModels.ProfileSummary profileSummary(
            TenantPrincipal principal, UUID positionId, UUID hotelId
    ) {
        ProfileView view;
        try {
            view = jdbc.queryForObject("""
                    select profile.id,
                           draft.id as draft_id, draft.version_no as draft_version,
                            draft.row_version as draft_row_version,
                            published.id as published_id, published.version_no as published_version,
                            draft.authorization_scope_type as draft_scope,
                            draft.wecom_self_selectable as draft_self_selectable,
                            published.authorization_scope_type as published_scope,
                            published.wecom_self_selectable as published_self_selectable,
                            coalesce(draft.authorization_scope_type, published.authorization_scope_type, 'SELF') as scope,
                           coalesce(draft.wecom_self_selectable, published.wecom_self_selectable, false) as self_selectable
                    from position_function_profile profile
                    left join position_function_profile_version draft
                      on draft.tenant_id = profile.tenant_id and draft.profile_id = profile.id
                     and draft.lifecycle_status = 'DRAFT'
                    left join position_function_profile_version published
                      on published.tenant_id = profile.tenant_id and published.profile_id = profile.id
                     and published.lifecycle_status = 'PUBLISHED'
                    where profile.tenant_id = :tenantId and profile.position_id = :positionId
                      and ((cast(:hotelId as uuid) is null and profile.scope_type = 'GROUP')
                           or (cast(:hotelId as uuid) is not null and profile.scope_type = 'HOTEL'
                               and profile.hotel_org_unit_id = cast(:hotelId as uuid)))
                    """, base(principal)
                    .addValue("positionId", positionId)
                    .addValue("hotelId", hotelId), (rs, rowNum) -> new ProfileView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("draft_id", UUID.class), (Integer) rs.getObject("draft_version"),
                    (Long) rs.getObject("draft_row_version"),
                    rs.getObject("published_id", UUID.class), (Integer) rs.getObject("published_version"),
                    rs.getString("draft_scope"), (Boolean) rs.getObject("draft_self_selectable"),
                    rs.getString("published_scope"), (Boolean) rs.getObject("published_self_selectable"),
                    rs.getString("scope"), rs.getBoolean("self_selectable")
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw notFound();
        }
        UUID shownVersion = view.draftId() == null ? view.publishedId() : view.draftId();
        List<String> permissions = shownVersion == null
                ? List.of() : List.copyOf(permissionsForVersion(principal, shownVersion));
        boolean draftDirty = view.draftId() != null && (view.publishedId() == null
                || !new LinkedHashSet<>(permissionsForVersion(principal, view.draftId())).equals(
                new LinkedHashSet<>(permissionsForVersion(principal, view.publishedId())))
                || !java.util.Objects.equals(view.draftScope(), view.publishedScope())
                || !java.util.Objects.equals(view.draftSelfSelectable(), view.publishedSelfSelectable()));
        String status = view.publishedId() == null
                ? (view.draftId() == null ? "EMPTY" : "DRAFT")
                : (draftDirty ? "PUBLISHED_WITH_DRAFT" : "PUBLISHED");
        return new PositionManagementModels.ProfileSummary(
                view.profileId(), view.draftVersion(), view.draftRowVersion(),
                view.publishedVersion(), status, draftDirty, permissions,
                view.authorizationScopeType(), view.wecomSelfSelectable()
        );
    }

    private List<UUID> validateHotelScope(
            TenantPrincipal principal, boolean allHotels, List<UUID> requested
    ) {
        LinkedHashSet<UUID> ids = requested == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(requested);
        if (allHotels) {
            if (!ids.isEmpty()) throw new IllegalArgumentException("适用全部门店时不能同时指定门店");
            return List.of();
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("未选择全部门店时至少指定一个适用门店");
        Integer count = jdbc.queryForObject("""
                select count(*)
                from org_unit
                where tenant_id = :tenantId and id in (:ids)
                  and unit_type = 'HOTEL' and status = 'ACTIVE'
                """, base(principal).addValue("ids", ids), Integer.class);
        if (count == null || count != ids.size()) {
            throw new IllegalArgumentException("适用门店包含不存在、停用或非门店组织");
        }
        return List.copyOf(ids);
    }

    private void replaceHotels(TenantPrincipal principal, UUID positionId, Collection<UUID> hotels) {
        jdbc.update("""
                delete from position_applicable_hotel
                where tenant_id = :tenantId and position_id = :positionId
                """, base(principal).addValue("positionId", positionId));
        for (UUID hotelId : hotels) {
            jdbc.update("""
                    insert into position_applicable_hotel
                        (tenant_id, position_id, hotel_org_unit_id)
                    values (:tenantId, :positionId, :hotelId)
                    """, base(principal)
                    .addValue("positionId", positionId)
                    .addValue("hotelId", hotelId));
        }
    }

    private List<AssignmentAccess> lockAssignmentAccess(
            TenantPrincipal principal,
            UUID positionId,
            boolean onlyOutsideCurrentApplicability
    ) {
        return jdbc.query("""
                select assignment.id, employee.account_id, assignment.org_unit_id
                from employee_position_assignment assignment
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                join position_function_profile group_profile
                  on group_profile.tenant_id = position.tenant_id
                 and group_profile.position_id = position.id
                 and group_profile.scope_type = 'GROUP'
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
                where assignment.tenant_id = :tenantId
                  and assignment.position_id = :positionId
                  and assignment.status = 'ACTIVE'
                  and (
                    :onlyOutside = false
                    or not (
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
                  )
                order by assignment.id
                for update of assignment
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("onlyOutside", onlyOutsideCurrentApplicability),
                (rs, rowNum) -> new AssignmentAccess(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class)
                ));
    }

    private AccessRevocation revokePositionAccess(
            TenantPrincipal principal,
            UUID positionId,
            List<AssignmentAccess> assignments,
            String reason,
            String staffMessage,
            String eventKey
    ) {
        if (assignments.isEmpty()) return new AccessRevocation(0, 0, 0);
        LinkedHashSet<UUID> assignmentIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> affectedAccounts = new LinkedHashSet<>();
        for (AssignmentAccess assignment : assignments) {
            assignmentIds.add(assignment.assignmentId());
            if (assignment.accountId() != null) {
                affectedAccounts.add(assignment.accountId());
            }
        }

        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("assignmentIds", assignmentIds)
                .addValue("actorId", principal.actorId())
                .addValue("reason", reason)
                .addValue("eventKey", eventKey)
                .addValue("staffMessage", staffMessage);
        int assignmentCount = jdbc.update("""
                update employee_position_assignment
                set status = 'INACTIVE', is_primary = false,
                    valid_to = case when valid_from > current_date then valid_from else current_date end,
                    updated_at = now()
                where tenant_id = :tenantId and id in (:assignmentIds) and status = 'ACTIVE'
                """, params);

        List<BindingImpact> bindings = jdbc.query("""
                select id, account_id
                from wecom_user_binding
                where tenant_id = :tenantId
                  and preferred_assignment_id in (:assignmentIds)
                  and status = 'ACTIVE'
                order by id
                for update
                """, params, (rs, rowNum) -> new BindingImpact(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class)));
        if (!bindings.isEmpty()) {
            jdbc.update("""
                    update wecom_user_binding
                    set status = 'SUSPENDED', status_reason = :reason,
                        assignment_selection_required = false, updated_by = :actorId,
                        row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and id in (:bindingIds)
                    """, params.addValue("bindingIds",
                    bindings.stream().map(BindingImpact::bindingId).toList()));
        }

        int endedRoleGrants = jdbc.update("""
                update role_assignment
                set valid_to = greatest(valid_from, now())
                where tenant_id = :tenantId
                  and source_type = 'POSITION_ASSIGNMENT'
                  and source_assignment_id in (:assignmentIds)
                  and (valid_to is null or valid_to > now())
                """, params);
        if (!affectedAccounts.isEmpty()) {
            jdbc.update("""
                    insert into notification
                        (tenant_id, recipient_account_id, notification_type, title, content,
                         source_type, source_id, idempotency_key)
                    select :tenantId, account.id, 'POSITION_ACCESS_AUTO_SUSPENDED',
                           '岗位访问已暂停', :staffMessage, 'POSITION', :positionId,
                           'position-access:' || cast(:positionId as text) || ':' || :eventKey
                           || ':account:' || account.id::text
                    from user_account account
                    where account.tenant_id = :tenantId and account.id in (:accountIds)
                    on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                    """, params.addValue("accountIds", affectedAccounts));
        }
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                select distinct :tenantId, role_grant.account_id, 'POSITION_ACCESS_AUTO_SUSPENDED',
                       '岗位任职已自动暂停',
                       '岗位适用范围变更导致部分任职、岗位权限及企业微信身份自动暂停；群推送开关未变更。',
                       'POSITION', :positionId,
                       'position-access:' || cast(:positionId as text) || ':' || :eventKey
                       || ':governance:' || role_grant.account_id::text
                from role_assignment role_grant
                join app_role role
                  on role.tenant_id = role_grant.tenant_id and role.id = role_grant.role_id
                where role_grant.tenant_id = :tenantId
                  and role.code in ('CEO', 'PLATFORM_ADMIN', 'HR_KPI_ADMIN')
                  and role_grant.valid_from <= now()
                  and (role_grant.valid_to is null or role_grant.valid_to > now())
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params);
        return new AccessRevocation(assignmentCount, bindings.size(), endedRoleGrants);
    }

    private int endResidualDefaultRoleGrants(TenantPrincipal principal, UUID positionId) {
        return jdbc.update("""
                update role_assignment grant_record
                set valid_to = greatest(grant_record.valid_from, now())
                from employee_position_assignment source_assignment
                where grant_record.tenant_id = :tenantId
                  and grant_record.source_type = 'POSITION_ASSIGNMENT'
                  and grant_record.source_assignment_id = source_assignment.id
                  and source_assignment.tenant_id = grant_record.tenant_id
                  and source_assignment.position_id = :positionId
                  and (grant_record.valid_to is null or grant_record.valid_to > now())
                """, base(principal).addValue("positionId", positionId));
    }

    private Set<String> requireDelegablePermissions(List<String> permissionCodes) {
        Set<String> normalized = normalizeCodes(permissionCodes);
        if (normalized.isEmpty()) return Set.of();
        Set<String> found = new LinkedHashSet<>(jdbc.queryForList("""
                select code from permission
                where code in (:codes) and delegable_to_position = true
                order by code
                """, new MapSqlParameterSource("codes", normalized), String.class));
        if (!found.equals(normalized)) {
            Set<String> rejected = new LinkedHashSet<>(normalized);
            rejected.removeAll(found);
            throw new IllegalArgumentException("包含不存在或受保护的功能权限：" + String.join(", ", rejected));
        }
        return found;
    }

    private Set<String> blockedPermissions(Set<String> requested) {
        if (requested.isEmpty()) return Set.of();
        Set<String> allowed = new LinkedHashSet<>(jdbc.queryForList("""
                select code from permission
                where code in (:codes) and delegable_to_position = true
                """, new MapSqlParameterSource("codes", requested), String.class));
        Set<String> blocked = new LinkedHashSet<>(requested);
        blocked.removeAll(allowed);
        return blocked;
    }

    private static boolean permissionAllowedForPosition(PositionLock position, String permissionCode) {
        if (position == null) {
            return !isPositionRestrictedPermission(permissionCode);
        }
        return restrictedPermissionCodes(
                position.positionCode(), position.roleCode(), Set.of(permissionCode)
        ).isEmpty();
    }

    private static boolean isPositionRestrictedPermission(String permissionCode) {
        return ADMIN_ONLY_CONFIGURATION_PERMISSIONS.contains(permissionCode)
                || "executive-task.read".equals(permissionCode)
                || "executive-task.assign".equals(permissionCode)
                || "dashboard.ceo".equals(permissionCode);
    }

    private static String permissionRestrictionReason(PositionLock position, String permissionCode) {
        if (("executive-task.read".equals(permissionCode)
                || "executive-task.assign".equals(permissionCode))
                && (position == null || !"GROUP_CHAIRMAN".equals(position.positionCode())
                || !"GROUP_CHAIRMAN".equals(position.roleCode()))) {
            return "仅集团董事长岗位可配置";
        }
        if ("dashboard.ceo".equals(permissionCode)
                && (position == null || !Set.of("CEO", "GROUP_CHAIRMAN").contains(position.roleCode()))) {
            return "仅集团总经理或董事长岗位可配置";
        }
        if (ADMIN_ONLY_CONFIGURATION_PERMISSIONS.contains(permissionCode)
                && (position == null || !Set.of("PLATFORM_ADMIN", "GROUP_ADMIN").contains(position.roleCode()))) {
            return "仅平台管理员岗位可配置";
        }
        return null;
    }

    private static Set<String> restrictedPermissionCodes(
            String positionCode,
            String roleCode,
            Collection<String> requested
    ) {
        Set<String> restricted = new LinkedHashSet<>();
        for (String permissionCode : requested) {
            if (ADMIN_ONLY_CONFIGURATION_PERMISSIONS.contains(permissionCode)
                    && !Set.of("PLATFORM_ADMIN", "GROUP_ADMIN").contains(roleCode)) {
                restricted.add(permissionCode);
            }
            if (("executive-task.read".equals(permissionCode)
                    || "executive-task.assign".equals(permissionCode))
                    && !("GROUP_CHAIRMAN".equals(positionCode)
                    && "GROUP_CHAIRMAN".equals(roleCode))) {
                restricted.add(permissionCode);
            }
            if ("dashboard.ceo".equals(permissionCode)
                    && !Set.of("CEO", "GROUP_CHAIRMAN").contains(roleCode)) {
                restricted.add(permissionCode);
            }
        }
        return restricted;
    }

    private static void requirePositionPermissionBoundaries(
            String positionCode,
            String roleCode,
            Collection<String> requested
    ) {
        Set<String> restricted = restrictedPermissionCodes(positionCode, roleCode, requested);
        if (restricted.isEmpty()) return;
        if (restricted.stream().anyMatch(ADMIN_ONLY_CONFIGURATION_PERMISSIONS::contains)) {
            throw new IllegalArgumentException("企业规则中心和工作包中心仅允许平台管理员岗位配置");
        }
        if (restricted.contains("executive-task.read")
                || restricted.contains("executive-task.assign")) {
            throw new IllegalArgumentException(
                    "集团任务的查看和派发权限仅允许配置给集团董事长岗位"
            );
        }
        throw new IllegalArgumentException("集团总览权限仅允许配置给集团总经理或董事长岗位");
    }

    private void replacePermissions(TenantPrincipal principal, UUID versionId, Collection<String> codes) {
        jdbc.update("""
                delete from position_function_profile_permission
                where tenant_id = :tenantId and profile_version_id = :versionId
                """, base(principal).addValue("versionId", versionId));
        if (codes.isEmpty()) return;
        int inserted = jdbc.update("""
                insert into position_function_profile_permission
                    (tenant_id, profile_version_id, permission_id)
                select :tenantId, :versionId, permission.id
                from permission
                where permission.code in (:codes) and permission.delegable_to_position = true
                """, base(principal)
                .addValue("versionId", versionId)
                .addValue("codes", codes));
        if (inserted != codes.size()) {
            throw new IllegalArgumentException("功能权限包含不存在或受保护项目");
        }
    }

    private void replaceRolePermissions(TenantPrincipal principal, UUID roleId, Collection<String> codes) {
        // A position profile controls only the explicitly delegable business-function subset.
        // Protected grants (platform administration, tenant-wide executive access, secrets, etc.)
        // are deliberately outside this editor and must survive every profile publication.
        jdbc.update("""
                delete from role_permission grant_item
                using permission
                where grant_item.tenant_id = :tenantId
                  and grant_item.role_id = :roleId
                  and permission.id = grant_item.permission_id
                  and permission.delegable_to_position = true
                """, base(principal).addValue("roleId", roleId));
        if (codes.isEmpty()) return;
        int inserted = jdbc.update("""
                insert into role_permission (tenant_id, role_id, permission_id)
                select :tenantId, :roleId, permission.id
                from permission
                where permission.code in (:codes) and permission.delegable_to_position = true
                """, base(principal).addValue("roleId", roleId).addValue("codes", codes));
        if (inserted != codes.size()) {
            throw new IllegalArgumentException("岗位角色权限同步失败：存在受保护或未知权限");
        }
    }

    private Set<String> permissionsForVersion(TenantPrincipal principal, UUID versionId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select permission.code
                from position_function_profile_permission item
                join permission on permission.id = item.permission_id
                where item.tenant_id = :tenantId and item.profile_version_id = :versionId
                order by permission.code
                """, base(principal).addValue("versionId", versionId), String.class));
    }

    private Set<String> publishedPermissions(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        try {
            return permissionsForVersion(principal, publishedVersion(principal, positionId, hotelId).id());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 404) return Set.of();
            throw exception;
        }
    }

    private Set<String> draftPermissions(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        try {
            ProfileLock profile = hotelId == null
                    ? requireGroupProfile(principal, positionId)
                    : requireHotelProfile(principal, positionId, hotelId);
            return permissionsForVersion(principal,
                    versionByStatus(principal, profile.id(), "DRAFT", false).id());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 404) return Set.of();
            throw exception;
        }
    }

    private Set<String> effectiveTemplatePermissions(TenantPrincipal principal, UUID positionId) {
        Set<String> published = publishedPermissions(principal, positionId, null);
        return published.isEmpty() ? draftPermissions(principal, positionId, null) : published;
    }

    private void rebaseHotelProfiles(
            TenantPrincipal principal,
            UUID positionId,
            UUID newGroupVersionId,
            String groupScope,
            boolean groupSelfSelectable,
            Set<String> groupPermissions
    ) {
        List<ProfileLock> hotels = jdbc.query("""
                select id, default_role_id
                from position_function_profile
                where tenant_id = :tenantId and position_id = :positionId
                  and scope_type = 'HOTEL'
                order by hotel_org_unit_id, id
                for update
                """, base(principal).addValue("positionId", positionId),
                (rs, rowNum) -> new ProfileLock(
                        rs.getObject("id", UUID.class), rs.getObject("default_role_id", UUID.class)
                ));
        for (ProfileLock hotel : hotels) {
            List<VersionLock> active = jdbc.query("""
                    select id, version_no, lifecycle_status, row_version,
                           based_on_group_version_id, authorization_scope_type,
                           wecom_self_selectable, published_at,
                           cast(published_by as varchar) as published_by
                    from position_function_profile_version
                    where tenant_id = :tenantId and profile_id = :profileId
                      and lifecycle_status in ('PUBLISHED', 'DRAFT')
                    order by case lifecycle_status when 'PUBLISHED' then 0 else 1 end, version_no
                    for update
                    """, base(principal).addValue("profileId", hotel.id()),
                    (rs, rowNum) -> versionLock(rs));
            for (VersionLock version : active) {
                String reducedScope = narrowerScope(version.authorizationScopeType(), groupScope);
                boolean selectable = version.wecomSelfSelectable() && groupSelfSelectable;
                Set<String> reducedPermissions = permissionsForVersion(principal, version.id());
                reducedPermissions.retainAll(groupPermissions);
                jdbc.update("""
                        update position_function_profile_version
                        set based_on_group_version_id = :baselineId,
                            authorization_scope_type = :scope,
                            wecom_self_selectable = :selfSelectable,
                            row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenantId and id = :versionId
                        """, base(principal)
                        .addValue("versionId", version.id())
                        .addValue("baselineId", newGroupVersionId)
                        .addValue("scope", reducedScope)
                        .addValue("selfSelectable", selectable));
                replacePermissions(principal, version.id(), reducedPermissions);
            }
            jdbc.update("""
                    update position_function_profile
                    set row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and id = :profileId
                    """, base(principal).addValue("profileId", hotel.id()));
        }
    }

    private void requireHotelReduction(
            TenantPrincipal principal,
            Set<String> hotelPermissions,
            String hotelScope,
            boolean hotelSelfSelectable,
            VersionLock groupBaseline
    ) {
        Set<String> groupPermissions = permissionsForVersion(principal, groupBaseline.id());
        if (!groupPermissions.containsAll(hotelPermissions)) {
            Set<String> expanded = new LinkedHashSet<>(hotelPermissions);
            expanded.removeAll(groupPermissions);
            throw new IllegalArgumentException("门店方案不能超出集团标准：" + String.join(", ", expanded));
        }
        if (SCOPE_RANK.get(hotelScope) > SCOPE_RANK.get(groupBaseline.authorizationScopeType())) {
            throw new IllegalArgumentException("门店方案不能扩大集团授权范围");
        }
        if (hotelSelfSelectable && !groupBaseline.wecomSelfSelectable()) {
            throw new IllegalArgumentException("门店方案不能扩大企业微信自助申请范围");
        }
    }

    private ProfileLock requireOrCreateHotelProfile(
            TenantPrincipal principal, UUID positionId, UUID hotelId
    ) {
        validateHotelForPosition(principal, positionId, hotelId);
        try {
            return lockHotelProfile(principal, positionId, hotelId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404) throw exception;
        }
        VersionLock baseline = publishedVersion(principal, positionId, null);
        UUID profileId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                insert into position_function_profile
                    (id, tenant_id, position_id, scope_type, hotel_org_unit_id)
                values (:id, :tenantId, :positionId, 'HOTEL', :hotelId)
                """, base(principal)
                .addValue("id", profileId)
                .addValue("positionId", positionId)
                .addValue("hotelId", hotelId));
        jdbc.update("""
                insert into position_function_profile_version
                    (id, tenant_id, profile_id, version_no, lifecycle_status,
                     based_on_group_version_id, authorization_scope_type,
                     wecom_self_selectable, created_by)
                values
                    (:id, :tenantId, :profileId, 1, 'DRAFT', :baselineId,
                     :scope, :selfSelectable, :actorId)
                """, base(principal)
                .addValue("id", draftId)
                .addValue("profileId", profileId)
                .addValue("baselineId", baseline.id())
                .addValue("scope", baseline.authorizationScopeType())
                .addValue("selfSelectable", baseline.wecomSelfSelectable())
                .addValue("actorId", principal.actorId()));
        replacePermissions(principal, draftId, permissionsForVersion(principal, baseline.id()));
        return new ProfileLock(profileId, null);
    }

    private void validateHotelForPosition(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        Boolean valid = jdbc.queryForObject("""
                select exists (
                    select 1
                    from position_definition position
                    join org_unit hotel
                      on hotel.tenant_id = position.tenant_id and hotel.id = :hotelId
                    where position.tenant_id = :tenantId and position.id = :positionId
                      and position.deleted_at is null and position.permanently_deleted_at is null
                      and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                      and (
                          position.applies_to_all_hotels = true
                          or exists (
                              select 1 from position_applicable_hotel link
                              where link.tenant_id = position.tenant_id
                                and link.position_id = position.id
                                and link.hotel_org_unit_id = hotel.id
                          )
                      )
                )
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("hotelId", hotelId), Boolean.class);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException("门店不是该岗位的有效适用门店");
        }
    }

    private ProfileLock lockGroupProfile(TenantPrincipal principal, UUID positionId) {
        return profileLock(principal, positionId, null, true);
    }

    private ProfileLock requireGroupProfile(TenantPrincipal principal, UUID positionId) {
        return profileLock(principal, positionId, null, false);
    }

    private ProfileLock lockHotelProfile(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        return profileLock(principal, positionId, hotelId, true);
    }

    private ProfileLock requireHotelProfile(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        return profileLock(principal, positionId, hotelId, false);
    }

    private ProfileLock profileLock(
            TenantPrincipal principal, UUID positionId, UUID hotelId, boolean lock
    ) {
        try {
            return jdbc.queryForObject("""
                    select id, default_role_id
                    from position_function_profile
                    where tenant_id = :tenantId and position_id = :positionId
                      and ((cast(:hotelId as uuid) is null and scope_type = 'GROUP')
                           or (cast(:hotelId as uuid) is not null and scope_type = 'HOTEL'
                               and hotel_org_unit_id = cast(:hotelId as uuid)))
                    """ + (lock ? " for update" : ""), base(principal)
                    .addValue("positionId", positionId)
                    .addValue("hotelId", hotelId), (rs, rowNum) -> new ProfileLock(
                    rs.getObject("id", UUID.class), rs.getObject("default_role_id", UUID.class)
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw notFound();
        }
    }

    private VersionLock lockDraft(TenantPrincipal principal, UUID profileId) {
        return versionByStatus(principal, profileId, "DRAFT", true);
    }

    private VersionLock lockOrCreateDraft(TenantPrincipal principal, UUID profileId) {
        try {
            return lockDraft(principal, profileId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404) throw exception;
        }

        VersionLock published = versionByStatus(principal, profileId, "PUBLISHED", false);
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                insert into position_function_profile_version
                    (id, tenant_id, profile_id, version_no, lifecycle_status,
                     copied_from_version_id, based_on_group_version_id,
                     authorization_scope_type, wecom_self_selectable, created_by)
                values
                    (:id, :tenantId, :profileId, :versionNo, 'DRAFT',
                     :publishedId, :baselineId, :scope, :selfSelectable, :actorId)
                """, base(principal)
                .addValue("id", draftId)
                .addValue("profileId", profileId)
                .addValue("versionNo", published.versionNo() + 1)
                .addValue("publishedId", published.id())
                .addValue("baselineId", published.basedOnGroupVersionId())
                .addValue("scope", published.authorizationScopeType())
                .addValue("selfSelectable", published.wecomSelfSelectable())
                .addValue("actorId", principal.actorId()));
        replacePermissions(principal, draftId, permissionsForVersion(principal, published.id()));
        return lockDraft(principal, profileId);
    }

    private VersionLock publishedVersion(TenantPrincipal principal, UUID positionId, UUID hotelId) {
        ProfileLock profile = hotelId == null
                ? requireGroupProfile(principal, positionId)
                : requireHotelProfile(principal, positionId, hotelId);
        return versionByStatus(principal, profile.id(), "PUBLISHED", false);
    }

    private VersionLock versionByStatus(
            TenantPrincipal principal, UUID profileId, String status, boolean lock
    ) {
        try {
            return jdbc.queryForObject("""
                    select id, version_no, lifecycle_status, row_version,
                           based_on_group_version_id, authorization_scope_type,
                           wecom_self_selectable, published_at,
                           cast(published_by as varchar) as published_by
                    from position_function_profile_version
                    where tenant_id = :tenantId and profile_id = :profileId
                      and lifecycle_status = :status
                    """ + (lock ? " for update" : ""), base(principal)
                    .addValue("profileId", profileId)
                    .addValue("status", status), (rs, rowNum) -> versionLock(rs));
        } catch (EmptyResultDataAccessException exception) {
            throw notFound();
        }
    }

    private static VersionLock versionLock(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VersionLock(
                rs.getObject("id", UUID.class), rs.getInt("version_no"),
                rs.getString("lifecycle_status"), rs.getLong("row_version"),
                rs.getObject("based_on_group_version_id", UUID.class),
                rs.getString("authorization_scope_type"), rs.getBoolean("wecom_self_selectable"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getString("published_by")
        );
    }

    private PositionLock lockPosition(TenantPrincipal principal, UUID positionId, boolean deleted) {
        return positionLock(principal, positionId, deleted, true);
    }

    private PositionLock requirePosition(
            TenantPrincipal principal, UUID positionId, boolean deleted, boolean includeEither
    ) {
        return positionLock(principal, positionId, deleted, !includeEither && false);
    }

    private PositionLock positionLock(
            TenantPrincipal principal, UUID positionId, boolean deleted, boolean lock
    ) {
        String deletedPredicate = deleted
                ? "deleted_at is not null and permanently_deleted_at is null"
                : "deleted_at is null and permanently_deleted_at is null";
        try {
            return jdbc.queryForObject("""
                    select position.id, position.row_version, position.deletion_batch_id,
                           position.applies_to_all_hotels, position.code as position_code,
                           role.code as role_code,
                           role.role_type
                    from position_definition position
                    join position_function_profile profile
                      on profile.tenant_id = position.tenant_id
                     and profile.position_id = position.id
                     and profile.scope_type = 'GROUP'
                    join app_role role
                      on role.tenant_id = profile.tenant_id
                     and role.id = profile.default_role_id
                    where position.tenant_id = :tenantId and position.id = :positionId and""" + " " + deletedPredicate
                    + (lock ? " for update" : ""), base(principal).addValue("positionId", positionId),
                    (rs, rowNum) -> new PositionLock(
                            rs.getObject("id", UUID.class), rs.getLong("row_version"),
                            rs.getObject("deletion_batch_id", UUID.class),
                            rs.getBoolean("applies_to_all_hotels"),
                            rs.getString("position_code"), rs.getString("role_code"),
                            rs.getString("role_type")
                    ));
        } catch (EmptyResultDataAccessException exception) {
            throw notFound();
        }
    }

    private AssignmentImpact assignmentImpact(TenantPrincipal principal, UUID positionId) {
        return jdbc.queryForObject("""
                select count(distinct assignment.id) filter (where assignment.status = 'ACTIVE')
                           as assignment_count,
                       count(distinct assignment.employee_id) filter (where assignment.status = 'ACTIVE')
                           as employee_count,
                       count(distinct binding.id) filter (
                         where assignment.status = 'ACTIVE' and binding.status = 'ACTIVE'
                       ) as binding_count,
                       count(distinct position_grant.id) filter (
                         where assignment.status = 'ACTIVE'
                           and position_grant.valid_from <= now()
                           and (position_grant.valid_to is null or position_grant.valid_to > now())
                       ) as role_grant_count
                from employee_position_assignment assignment
                left join wecom_user_binding binding
                  on binding.tenant_id = assignment.tenant_id
                 and binding.preferred_assignment_id = assignment.id
                left join role_assignment position_grant
                  on position_grant.tenant_id = assignment.tenant_id
                 and position_grant.source_type = 'POSITION_ASSIGNMENT'
                 and position_grant.source_assignment_id = assignment.id
                where assignment.tenant_id = :tenantId and assignment.position_id = :positionId
                """, base(principal).addValue("positionId", positionId), (rs, rowNum) ->
                new AssignmentImpact(
                        rs.getLong("assignment_count"), rs.getLong("employee_count"),
                        rs.getLong("binding_count"), rs.getLong("role_grant_count")));
    }

    private long applicableHotelCount(TenantPrincipal principal, UUID positionId) {
        Long count = jdbc.queryForObject("""
                select case when position.applies_to_all_hotels then (
                           select count(*) from org_unit hotel
                           where hotel.tenant_id = position.tenant_id
                             and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                       ) else (
                           select count(*) from position_applicable_hotel link
                           where link.tenant_id = position.tenant_id
                             and link.position_id = position.id
                       ) end
                from position_definition position
                where position.tenant_id = :tenantId and position.id = :positionId
                """, base(principal).addValue("positionId", positionId), Long.class);
        return count == null ? 0L : count;
    }

    private ApplicabilityImpact applicabilityImpact(
            TenantPrincipal principal,
            UUID positionId,
            boolean appliesToAllHotels,
            List<UUID> requestedHotels
    ) {
        if (appliesToAllHotels) {
            Long activeHotels = jdbc.queryForObject("""
                    select count(*)
                    from org_unit
                    where tenant_id = :tenantId and unit_type = 'HOTEL' and status = 'ACTIVE'
                    """, base(principal), Long.class);
            return new ApplicabilityImpact(0L, 0L, 0L, 0L,
                    activeHotels == null ? 0L : activeHotels, List.of());
        }
        MapSqlParameterSource params = base(principal)
                .addValue("positionId", positionId)
                .addValue("requestedHotels", requestedHotels);
        List<PositionManagementModels.ApplicableHotel> removedHotels = jdbc.query("""
                select hotel.id, hotel.code, hotel.name
                from position_definition position
                join org_unit hotel
                  on hotel.tenant_id = position.tenant_id
                 and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                where position.tenant_id = :tenantId and position.id = :positionId
                  and (
                    position.applies_to_all_hotels = true
                    or exists (
                      select 1
                      from position_applicable_hotel current_hotel
                      where current_hotel.tenant_id = position.tenant_id
                        and current_hotel.position_id = position.id
                        and current_hotel.hotel_org_unit_id = hotel.id
                    )
                  )
                  and hotel.id not in (:requestedHotels)
                order by hotel.name, hotel.id
                """, params, (rs, rowNum) -> new PositionManagementModels.ApplicableHotel(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")));
        ApplicabilityCounts counts = jdbc.queryForObject("""
                select count(distinct assignment.id) as assignment_count,
                       count(distinct assignment.employee_id) as employee_count,
                       count(distinct binding.id) filter (where binding.status = 'ACTIVE') as binding_count,
                       count(distinct position_grant.id) filter (
                         where position_grant.valid_from <= now()
                           and (position_grant.valid_to is null or position_grant.valid_to > now())
                       ) as role_grant_count
                from employee_position_assignment assignment
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
                left join wecom_user_binding binding
                  on binding.tenant_id = assignment.tenant_id
                 and binding.preferred_assignment_id = assignment.id
                left join role_assignment position_grant
                  on position_grant.tenant_id = assignment.tenant_id
                 and position_grant.source_type = 'POSITION_ASSIGNMENT'
                 and position_grant.source_assignment_id = assignment.id
                where assignment.tenant_id = :tenantId
                  and assignment.position_id = :positionId
                  and assignment.status = 'ACTIVE'
                  and (hotel_context.id is null or hotel_context.id not in (:requestedHotels))
                """, params, (rs, rowNum) -> new ApplicabilityCounts(
                rs.getLong("assignment_count"), rs.getLong("employee_count"),
                rs.getLong("binding_count"), rs.getLong("role_grant_count")));
        return new ApplicabilityImpact(
                counts == null ? 0L : counts.assignments(),
                counts == null ? 0L : counts.employees(),
                counts == null ? 0L : counts.activeBindings(),
                counts == null ? 0L : counts.roleGrants(),
                requestedHotels.size(), List.copyOf(removedHotels));
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private TenantPrincipal prepareForManagement() {
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope()) {
            throw new cn.sifangguan.hotelaios.shared.security.AccessDeniedException(
                    "岗位与功能方案仅允许租户级管理员维护"
            );
        }
        return principal;
    }

    private static MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private static String requireName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("岗位名称不能为空");
        if (normalized.length() > 120) throw new IllegalArgumentException("岗位名称不能超过120个字符");
        return normalized;
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null || scope.isBlank()
                ? "SELF" : scope.trim().toUpperCase(Locale.ROOT);
        if (!AUTHORIZATION_SCOPES.contains(normalized)) {
            throw new IllegalArgumentException("授权范围必须为SELF、ORG_UNIT、ORG_TREE或TENANT");
        }
        return normalized;
    }

    private static String narrowerScope(String first, String second) {
        return SCOPE_RANK.get(first) <= SCOPE_RANK.get(second) ? first : second;
    }

    private static Set<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("功能权限编码不能为空");
            }
            normalized.add(code.trim());
        }
        return normalized;
    }

    private static List<String> difference(Collection<String> left, Collection<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(new LinkedHashSet<>(right));
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static void requireExpected(long actual, long expected) {
        if (actual != expected) throw stale();
    }

    private boolean applicabilityChanged(
            TenantPrincipal principal,
            PositionLock position,
            boolean requestedAllHotels,
            Collection<UUID> requestedHotels
    ) {
        if (position.appliesToAllHotels() != requestedAllHotels) return true;
        if (requestedAllHotels) return false;
        Set<UUID> current = new LinkedHashSet<>(jdbc.queryForList("""
                select hotel_org_unit_id
                from position_applicable_hotel
                where tenant_id = :tenantId and position_id = :positionId
                order by hotel_org_unit_id
                """, base(principal).addValue("positionId", position.id()), UUID.class));
        return !current.equals(new LinkedHashSet<>(requestedHotels));
    }

    private static boolean isProtectedSystemPosition(PositionLock position) {
        return "SYSTEM".equals(position.roleType())
                && PROTECTED_SYSTEM_POSITION_ROLES.contains(position.roleCode());
    }

    private static void requireDeletablePosition(PositionLock position) {
        if (isProtectedSystemPosition(position)) {
            throw new IllegalArgumentException("内置高权限岗位不允许删除或永久删除");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成审计数据", exception);
        }
    }

    private static ResponseStatusException stale() {
        return conflict("数据已被其他操作更新，请刷新后重试");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "岗位或功能方案不存在");
    }

    private record PositionRow(
            UUID id,
            String name,
            String status,
            long rowVersion,
            boolean appliesToAllHotels,
            OffsetDateTime deletedAt,
            String deletedBy,
            long assignmentCount,
            long employeeCount
    ) {
    }

    private record PositionLock(
            UUID id,
            long rowVersion,
            UUID deletionBatchId,
            boolean appliesToAllHotels,
            String positionCode,
            String roleCode,
            String roleType
    ) {
    }

    private record ProfileLock(UUID id, UUID defaultRoleId) {
    }

    private record ProfileView(
            UUID profileId,
            UUID draftId,
            Integer draftVersion,
            Long draftRowVersion,
            UUID publishedId,
            Integer publishedVersion,
            String draftScope,
            Boolean draftSelfSelectable,
            String publishedScope,
            Boolean publishedSelfSelectable,
            String authorizationScopeType,
            boolean wecomSelfSelectable
    ) {
    }

    private record VersionLock(
            UUID id,
            int versionNo,
            String status,
            long rowVersion,
            UUID basedOnGroupVersionId,
            String authorizationScopeType,
            boolean wecomSelfSelectable,
            OffsetDateTime publishedAt,
            String publishedBy
    ) {
    }

    private record AssignmentImpact(
            long assignments,
            long employees,
            long activeBindings,
            long roleGrants
    ) {
    }

    private record ApplicabilityCounts(
            long assignments,
            long employees,
            long activeBindings,
            long roleGrants
    ) {
    }

    private record ApplicabilityImpact(
            long assignments,
            long employees,
            long activeBindings,
            long roleGrants,
            long resultingHotelCount,
            List<PositionManagementModels.ApplicableHotel> removedHotels
    ) {
    }

    private record AssignmentAccess(
            UUID assignmentId,
            UUID accountId,
            UUID orgUnitId
    ) {
    }

    private record BindingImpact(UUID bindingId, UUID accountId) {
    }

    private record AccessRevocation(
            int assignmentCount,
            int bindingCount,
            int roleGrantCount
    ) {
    }

    private record RecycleAssignment(
            UUID id,
            UUID assignmentId,
            String previousStatus,
            LocalDate previousValidTo,
            boolean previousPrimary,
            UUID employeeId
    ) {
    }
}
