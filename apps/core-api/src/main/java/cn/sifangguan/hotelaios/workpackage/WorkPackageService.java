package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.dataretention.EmployeePerformanceSnapshotService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;

@Service
public class WorkPackageService {
    private static final Set<String> PERIOD_TYPES = Set.of("SHIFT", "DAY", "WEEK", "MONTH", "QUARTER", "EVENT");
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final WorkVisibilityPolicy workVisibility;
    private final OperationalDailyAllocationService operationalAllocations;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final EmployeePerformanceSnapshotService employeePerformanceSnapshotService;

    public WorkPackageService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            WorkVisibilityPolicy workVisibility,
            OperationalDailyAllocationService operationalAllocations,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            EmployeePerformanceSnapshotService employeePerformanceSnapshotService
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.workVisibility = workVisibility;
        this.operationalAllocations = operationalAllocations;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.employeePerformanceSnapshotService = employeePerformanceSnapshotService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> definitions() {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.read");
        MapSqlParameterSource params = base(principal);
        String visibility = "";
        if (!principal.hasTenantScope()) {
            if (principal.orgScopes().isEmpty()) {
                return List.of();
            }
            params.addValue("scopeIds", principal.orgScopes());
            visibility = """
                    and exists (
                        select 1
                        from work_package_version vis_v
                        join work_package_allocation vis_a
                          on vis_a.tenant_id = vis_v.tenant_id
                         and vis_a.work_package_version_id = vis_v.id
                        join org_unit_closure vis_c
                          on vis_c.tenant_id = vis_a.tenant_id
                         and vis_c.descendant_id = vis_a.target_org_unit_id
                        where vis_v.tenant_id = d.tenant_id
                          and vis_v.work_package_definition_id = d.id
                          and vis_a.status = 'ACTIVE'
                          and vis_c.ancestor_id in (:scopeIds)
                    )
                    """;
        }
        return jdbc.queryForList("""
                select d.id, d.code, d.name, d.description, d.status,
                       d.position_id, p.name as position_name,
                       d.owner_org_unit_id, o.name as owner_org_unit_name,
                       v.id as latest_version_id, v.version_no, v.lifecycle_status,
                       v.effective_from, v.effective_to
                from work_package_definition d
                join position_definition p on p.tenant_id = d.tenant_id and p.id = d.position_id
                left join org_unit o on o.tenant_id = d.tenant_id and o.id = d.owner_org_unit_id
                left join lateral (
                    select pv.id, pv.version_no, pv.lifecycle_status, pv.effective_from, pv.effective_to
                    from work_package_version pv
                    where pv.tenant_id = d.tenant_id and pv.work_package_definition_id = d.id
                    order by pv.version_no desc limit 1
                ) v on true
                where d.tenant_id = :tenantId
                """ + visibility + " order by d.code", params);
    }

    @Transactional
    public Map<String, Object> createDefinition(WorkPackageModels.CreateDefinition request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.manage");
        requireOwned("position_definition", principal, request.positionId());
        if (request.ownerOrgUnitId() != null) {
            requireOwned("org_unit", principal, request.ownerOrgUnitId());
            accessPolicy.requireOrgScope(request.ownerOrgUnitId());
        } else if (!principal.hasTenantScope()) {
            throw new AccessDeniedException("仅集团级管理员可以创建集团统一工作包");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_definition
                    (id, tenant_id, code, name, description, position_id, owner_org_unit_id, created_by)
                values
                    (:id, :tenantId, :code, :name, :description, :positionId, :ownerOrgUnitId, :actorId)
                """, base(principal)
                .addValue("id", id)
                .addValue("code", normalizeCode(request.code()))
                .addValue("name", request.name().trim())
                .addValue("description", trimToNull(request.description()))
                .addValue("positionId", request.positionId())
                .addValue("ownerOrgUnitId", request.ownerOrgUnitId())
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WORK_PACKAGE_CREATED", "WORK_PACKAGE", id,
                "{\"code\":\"" + jsonEscape(normalizeCode(request.code())) + "\"}");
        return response("id", id, "code", normalizeCode(request.code()), "name", request.name().trim(), "status", "ACTIVE");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID workPackageId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.read");
        requireVisible(principal, workPackageId);
        MapSqlParameterSource params = base(principal).addValue("workPackageId", workPackageId);
        Map<String, Object> definition = new LinkedHashMap<>(jdbc.queryForMap("""
                select d.id, d.code, d.name, d.description, d.status, d.position_id,
                       p.name as position_name, d.owner_org_unit_id, o.name as owner_org_unit_name,
                       d.created_at, d.updated_at
                from work_package_definition d
                join position_definition p on p.tenant_id = d.tenant_id and p.id = d.position_id
                left join org_unit o on o.tenant_id = d.tenant_id and o.id = d.owner_org_unit_id
                where d.tenant_id = :tenantId and d.id = :workPackageId
                """, params));
        List<Map<String, Object>> versions = jdbc.queryForList("""
                select id, version_no, lifecycle_status, title, description, effective_from, effective_to,
                       content_hash, published_by, published_at, created_at, updated_at
                from work_package_version
                where tenant_id = :tenantId and work_package_definition_id = :workPackageId
                order by version_no desc
                """, params);
        definition.put("versions", versions);
        definition.put("publicationTimeZone", jdbc.queryForObject("select timezone from tenant where id=:tenantId", params, String.class));
        if (!versions.isEmpty()) {
            UUID versionId = (UUID) versions.getFirst().get("id");
            definition.put("latestVersion", versionConfiguration(principal, versionId));
        }
        return definition;
    }

    @Transactional
    public Map<String, Object> createVersion(UUID workPackageId, WorkPackageModels.CreateVersion request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.manage");
        requireManageableDefinition(principal, workPackageId);
        Integer nextVersion = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) + 1
                from work_package_version
                where tenant_id = :tenantId and work_package_definition_id = :workPackageId
                """, base(principal).addValue("workPackageId", workPackageId), Integer.class);
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_version
                    (id, tenant_id, work_package_definition_id, version_no, title, description, created_by)
                values
                    (:id, :tenantId, :workPackageId, :versionNo, :title, :description, :actorId)
                """, base(principal)
                .addValue("id", versionId)
                .addValue("workPackageId", workPackageId)
                .addValue("versionNo", nextVersion)
                .addValue("title", request.title().trim())
                .addValue("description", trimToNull(request.description()))
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WORK_PACKAGE_VERSION_CREATED", "WORK_PACKAGE_VERSION", versionId,
                "{\"workPackageId\":\"" + workPackageId + "\",\"versionNo\":" + nextVersion + "}");
        return response("id", versionId, "workPackageId", workPackageId, "versionNo", nextVersion, "status", "DRAFT");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> version(UUID workPackageId,UUID versionId) {
        TenantPrincipal principal=prepare();
        accessPolicy.requirePermission("work-package.read");
        requireVisible(principal,workPackageId);
        requireVersion(principal,workPackageId,versionId);
        return versionConfiguration(principal,versionId);
    }

    @Transactional
    public Map<String, Object> updateVersion(
            UUID workPackageId,
            UUID versionId,
            WorkPackageModels.UpdateVersion request
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.manage");
        requireDraftVersion(principal, workPackageId, versionId);

        MapSqlParameterSource versionParams = base(principal).addValue("versionId", versionId);
        jdbc.update("delete from work_package_scope where tenant_id = :tenantId and work_package_version_id = :versionId",
                versionParams);
        // Delete guarded relation rows while their draft parent item is still visible.
        // PostgreSQL executes ON DELETE CASCADE after the parent deletion has begun;
        // the child immutability trigger can no longer resolve the parent version then.
        jdbc.update("""
                delete from work_package_item_standard
                where tenant_id = :tenantId and work_package_item_id in (
                    select id from work_package_item
                    where tenant_id = :tenantId and work_package_version_id = :versionId
                )
                """, versionParams);
        jdbc.update("""
                delete from work_package_item_responsibility
                where tenant_id = :tenantId and work_package_item_id in (
                    select id from work_package_item
                    where tenant_id = :tenantId and work_package_version_id = :versionId
                )
                """, versionParams);
        jdbc.update("delete from work_package_item where tenant_id = :tenantId and work_package_version_id = :versionId",
                versionParams);
        jdbc.update("""
                update work_package_version set title = :title, description = :description
                where tenant_id = :tenantId and id = :versionId and work_package_definition_id = :workPackageId
                  and lifecycle_status = 'DRAFT'
                """, base(principal)
                .addValue("versionId", versionId)
                .addValue("workPackageId", workPackageId)
                .addValue("title", request.title().trim())
                .addValue("description", trimToNull(request.description())));

        for (WorkPackageModels.Scope scope : request.scopes()) {
            insertScope(principal, versionId, scope);
        }
        Set<String> itemCodes = new HashSet<>();
        for (WorkPackageModels.Item item : request.items()) {
            String itemCode = normalizeCode(item.itemCode());
            if (!itemCodes.add(itemCode)) {
                throw new IllegalArgumentException("工作包条目编码重复: " + itemCode);
            }
            insertItem(principal, versionId, item, itemCode);
        }
        auditWriter.record("WORK_PACKAGE_VERSION_UPDATED", "WORK_PACKAGE_VERSION", versionId,
                "{\"scopeCount\":" + request.scopes().size() + ",\"itemCount\":" + request.items().size() + "}");
        return response("id", versionId, "workPackageId", workPackageId, "status", "DRAFT",
                "scopeCount", request.scopes().size(), "itemCount", request.items().size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateVersion(UUID workPackageId, UUID versionId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.manage");
        requireDraftVersion(principal, workPackageId, versionId);
        List<String> issues = validationIssues(principal, versionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workPackageId", workPackageId);
        result.put("versionId", versionId);
        result.put("valid", issues.isEmpty());
        result.put("issues", issues);
        return result;
    }

    @Transactional
    public Map<String, Object> publishVersion(
            UUID workPackageId,
            UUID versionId,
            WorkPackageModels.PublishVersion request
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.publish");
        requireDraftVersion(principal, workPackageId, versionId);
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new IllegalArgumentException("失效时间不能早于生效时间");
        }
        List<String> issues = validationIssues(principal, versionId);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("工作包版本未通过发布校验: " + String.join("；", issues));
        }
        String contentHash = sha256(contentSnapshot(principal, versionId));
        boolean prospectiveWorkflow = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from work_package_item where tenant_id=:tenantId
                  and work_package_version_id=:versionId and execution_policy->>'workflowRevision'='THREE_ROLE_V88')
                """, base(principal).addValue("versionId", versionId), Boolean.class));
        if (prospectiveWorkflow) {
            ZoneId tenantZone = ZoneId.of(jdbc.queryForObject("select timezone from tenant where id=:tenantId",base(principal),String.class));
            var local = request.effectiveFrom().atZoneSameInstant(tenantZone);
            if (!local.toLocalDate().isAfter(LocalDate.now(tenantZone)) || !local.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                throw new IllegalArgumentException("三岗位优化须选择未来本地日期00:00生效，不得追溯替换已有工作");
            }
        }
        MapSqlParameterSource params = base(principal)
                .addValue("workPackageId", workPackageId)
                .addValue("versionId", versionId)
                .addValue("effectiveFrom", request.effectiveFrom())
                .addValue("effectiveTo", request.effectiveTo())
                .addValue("contentHash", contentHash)
                .addValue("prospectiveWorkflow", prospectiveWorkflow)
                .addValue("previousEnds", request.effectiveFrom().minusNanos(1000))
                .addValue("actorId", principal.actorId());
        jdbc.update("""
                update work_package_version
                set lifecycle_status = case when :prospectiveWorkflow then 'PUBLISHED' else 'RETIRED' end,
                    effective_to = case when :prospectiveWorkflow then :previousEnds else :effectiveFrom end
                where tenant_id = :tenantId and work_package_definition_id = :workPackageId
                  and id <> :versionId and lifecycle_status = 'PUBLISHED'
                  and (not :prospectiveWorkflow or (
                    effective_from < :effectiveFrom and (effective_to is null or effective_to > :previousEnds)
                    and coalesce((select max(i.applicability_policy->>'assistantWorkTemplateMode')
                      from work_package_item i where i.tenant_id=work_package_version.tenant_id
                        and i.work_package_version_id=work_package_version.id),'')
                      = coalesce((select max(i.applicability_policy->>'assistantWorkTemplateMode')
                      from work_package_item i where i.tenant_id=:tenantId and i.work_package_version_id=:versionId),'')
                  ))
                """, params);
        int changed = jdbc.update("""
                update work_package_version
                set lifecycle_status = 'PUBLISHED', effective_from = :effectiveFrom, effective_to = :effectiveTo,
                    content_hash = :contentHash, published_by = :actorId, published_at = now()
                where tenant_id = :tenantId and work_package_definition_id = :workPackageId
                  and id = :versionId and lifecycle_status = 'DRAFT'
                """, params);
        if (changed != 1) {
            throw new IllegalArgumentException("只有当前租户的草稿工作包版本可以发布");
        }
        auditWriter.record("WORK_PACKAGE_VERSION_PUBLISHED", "WORK_PACKAGE_VERSION", versionId,
                "{\"workPackageId\":\"" + workPackageId + "\",\"contentHash\":\"" + contentHash + "\"}");
        auditWriter.emit("WORK_PACKAGE", workPackageId, "WorkPackagePublished",
                "{\"workPackageId\":\"" + workPackageId + "\",\"versionId\":\"" + versionId + "\"}");
        return response("workPackageId", workPackageId, "versionId", versionId,
                "status", "PUBLISHED", "contentHash", contentHash);
    }

    @Transactional
    public Map<String, Object> retireVersion(
            UUID workPackageId,
            UUID versionId,
            WorkPackageModels.RetireVersion request
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.publish");
        OffsetDateTime effectiveTo = request == null || request.effectiveTo() == null
                ? OffsetDateTime.now() : request.effectiveTo();
        int changed = jdbc.update("""
                update work_package_version
                set lifecycle_status = 'RETIRED', effective_to = :effectiveTo
                where tenant_id = :tenantId and work_package_definition_id = :workPackageId
                  and id = :versionId and lifecycle_status = 'PUBLISHED'
                """, base(principal)
                .addValue("workPackageId", workPackageId)
                .addValue("versionId", versionId)
                .addValue("effectiveTo", effectiveTo));
        if (changed != 1) {
            throw new IllegalArgumentException("只有已发布的工作包版本可以停用");
        }
        auditWriter.record("WORK_PACKAGE_VERSION_RETIRED", "WORK_PACKAGE_VERSION", versionId, "{}");
        auditWriter.emit("WORK_PACKAGE", workPackageId, "WorkPackageRetired",
                "{\"workPackageId\":\"" + workPackageId + "\",\"versionId\":\"" + versionId + "\"}");
        return response("workPackageId", workPackageId, "versionId", versionId,
                "status", "RETIRED", "effectiveTo", effectiveTo);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> allocations(UUID workPackageId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.read");
        requireVisible(principal, workPackageId);
        MapSqlParameterSource params = base(principal).addValue("workPackageId", workPackageId);
        String visibility = orgVisibility(principal, params, "a.target_org_unit_id");
        return jdbc.queryForList("""
                select a.id, a.work_package_version_id, v.version_no, a.position_assignment_id,
                       e.name as employee_name, p.name as position_name,
                       a.target_org_unit_id, o.name as target_org_unit_name,
                       a.valid_from, a.valid_to, a.status, a.allocation_source, a.allocated_by,
                       a.created_at, a.updated_at
                from work_package_allocation a
                join work_package_version v on v.tenant_id = a.tenant_id and v.id = a.work_package_version_id
                join employee_position_assignment pa on pa.tenant_id = a.tenant_id and pa.id = a.position_assignment_id
                join employee e on e.tenant_id = pa.tenant_id and e.id = pa.employee_id
                join position_definition p on p.tenant_id = pa.tenant_id and p.id = pa.position_id
                join org_unit o on o.tenant_id = a.tenant_id and o.id = a.target_org_unit_id
                where a.tenant_id = :tenantId and v.work_package_definition_id = :workPackageId
                """ + visibility + " order by a.created_at desc", params);
    }

    @Transactional
    public Map<String, Object> createAllocation(
            UUID workPackageId,
            WorkPackageModels.CreateAllocation request
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        accessPolicy.requireOrgScope(request.targetOrgUnitId());
        lockAllocationRange(principal, workPackageId, request.positionAssignmentId(), request.targetOrgUnitId());
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new IllegalArgumentException("分配失效日期不能早于生效日期");
        }
        Integer applicable = jdbc.queryForObject("""
                select count(*)
                from work_package_version v
                join work_package_definition d
                  on d.tenant_id = v.tenant_id and d.id = v.work_package_definition_id
                join employee_position_assignment a
                  on a.tenant_id = v.tenant_id and a.id = :assignmentId
                where v.tenant_id = :tenantId and v.id = :versionId
                  and d.id = :workPackageId and v.lifecycle_status = 'PUBLISHED'
                  and d.status = 'ACTIVE' and d.position_id = a.position_id and a.status = 'ACTIVE'
                  and a.valid_from <= :validFrom and (a.valid_to is null or a.valid_to >= :validFrom)
                  and exists (
                      select 1 from work_package_scope s
                      where s.tenant_id = v.tenant_id and s.work_package_version_id = v.id
                        and (
                            s.scope_type = 'TENANT'
                            or (s.scope_type = 'POSITION' and s.position_id = a.position_id)
                            or (s.scope_type = 'ORG_UNIT' and s.org_unit_id = :targetOrgUnitId)
                            or (s.scope_type = 'ORG_TREE' and exists (
                                select 1 from org_unit_closure c
                                where c.tenant_id = s.tenant_id and c.ancestor_id = s.org_unit_id
                                  and c.descendant_id = :targetOrgUnitId
                            ))
                            or (s.scope_type = 'BRAND' and exists (
                                select 1 from hotel_profile h
                                where h.tenant_id = s.tenant_id and h.org_unit_id = :targetOrgUnitId
                                  and h.brand_id = s.brand_id
                            ))
                        )
                  )
                """, base(principal)
                .addValue("versionId", request.workPackageVersionId())
                .addValue("workPackageId", workPackageId)
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("validFrom", request.validFrom()), Integer.class);
        if (applicable == null || applicable != 1) {
            throw new IllegalArgumentException("工作包版本、任职、岗位或目标组织不匹配");
        }
        Integer overlaps = jdbc.queryForObject("""
                select count(*)
                from work_package_allocation a
                join work_package_version av on av.tenant_id = a.tenant_id and av.id = a.work_package_version_id
                where a.tenant_id = :tenantId
                  and av.work_package_definition_id = :workPackageId
                  and a.position_assignment_id = :assignmentId
                  and a.target_org_unit_id = :targetOrgUnitId
                  and a.status = 'ACTIVE'
                  and daterange(a.valid_from, coalesce(a.valid_to, 'infinity'::date), '[]')
                      && daterange(:validFrom, coalesce(cast(:validTo as date), 'infinity'::date), '[]')
                """, base(principal)
                .addValue("workPackageId", workPackageId)
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("validFrom", request.validFrom())
                .addValue("validTo", request.validTo()), Integer.class);
        if (overlaps != null && overlaps > 0) {
            throw new IllegalArgumentException("同一任职和目标组织已存在时间重叠的工作包分配");
        }
        UUID allocationId = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_allocation
                    (id, tenant_id, work_package_version_id, position_assignment_id, target_org_unit_id,
                     allocation_source, valid_from, valid_to, allocated_by)
                values
                    (:id, :tenantId, :versionId, :assignmentId, :targetOrgUnitId,
                     :allocationSource, :validFrom, :validTo, :actorId)
                """, base(principal)
                .addValue("id", allocationId)
                .addValue("versionId", request.workPackageVersionId())
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("allocationSource", defaultUpper(request.allocationSource(), "MANUAL"))
                .addValue("validFrom", request.validFrom())
                .addValue("validTo", request.validTo())
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WORK_PACKAGE_ALLOCATED", "WORK_PACKAGE_ALLOCATION", allocationId,
                "{\"workPackageId\":\"" + workPackageId + "\",\"targetOrgUnitId\":\""
                        + request.targetOrgUnitId() + "\"}");
        auditWriter.emit("WORK_PACKAGE_ALLOCATION", allocationId, "WorkPackageAllocated",
                "{\"allocationId\":\"" + allocationId + "\",\"positionAssignmentId\":\""
                        + request.positionAssignmentId() + "\",\"orgUnitId\":\"" + request.targetOrgUnitId() + "\"}");
        return response("id", allocationId, "workPackageId", workPackageId,
                "workPackageVersionId", request.workPackageVersionId(), "status", "ACTIVE");
    }

    private void lockAllocationRange(TenantPrincipal principal, UUID workPackageId, UUID assignmentId, UUID targetId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource("lockKey", "work-allocation:" + principal.tenantId() + ":"
                        + workPackageId + ":" + assignmentId + ":" + targetId), (rs, row) -> Boolean.TRUE);
    }

    /** Explicit, prospective replacement only; existing work and submitted facts are untouched. */
    @Transactional
    public Map<String, Object> replaceAllocation(UUID workPackageId, UUID allocationId,
                                                WorkPackageModels.CreateAllocation request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        accessPolicy.requireOrgScope(request.targetOrgUnitId());
        ZoneId tenantZone = ZoneId.of(jdbc.queryForObject("select timezone from tenant where id=:tenantId", base(principal), String.class));
        if (!request.validFrom().isAfter(LocalDate.now(tenantZone))) {
            throw new IllegalArgumentException("版本切换须选择未来日期，不得修改今日或历史下发");
        }
        lockAllocationRange(principal, workPackageId, request.positionAssignmentId(), request.targetOrgUnitId());
        List<Map<String, Object>> current = jdbc.queryForList("""
                select allocation.*,(version.effective_to at time zone :tenantTimezone)::date as version_last_date
                from work_package_allocation allocation
                join work_package_version version on version.tenant_id=allocation.tenant_id
                  and version.id=allocation.work_package_version_id
                where allocation.tenant_id=:tenantId and allocation.id=:allocationId
                  and version.work_package_definition_id=:workPackageId
                for update of allocation
                """, base(principal).addValue("allocationId", allocationId).addValue("workPackageId", workPackageId)
                .addValue("tenantTimezone", tenantZone.getId()));
        if (current.isEmpty()) throw new IllegalArgumentException("原下发不存在或不属于所选工作包");
        Map<String, Object> old = current.getFirst();
        if (!"ACTIVE".equals(old.get("status")) || !request.positionAssignmentId().equals(old.get("position_assignment_id"))
                || !request.targetOrgUnitId().equals(old.get("target_org_unit_id"))) {
            throw new IllegalArgumentException("版本切换必须保留原有效下发的任职及目标组织");
        }
        if (request.workPackageVersionId().equals(old.get("work_package_version_id")))
            throw new IllegalArgumentException("请选择与原下发不同的新版本");
        LocalDate previousFrom = ((java.sql.Date) old.get("valid_from")).toLocalDate();
        LocalDate previousTo = old.get("valid_to") == null ? null : ((java.sql.Date) old.get("valid_to")).toLocalDate();
        LocalDate previousEnds = request.validFrom().minusDays(1);
        if (!previousFrom.isBefore(request.validFrom())) throw new IllegalArgumentException("切换日期必须晚于原下发生效日期");
        MapSqlParameterSource params = base(principal).addValue("allocationId", allocationId)
                .addValue("workPackageId", workPackageId).addValue("versionId", request.workPackageVersionId())
                .addValue("assignmentId", request.positionAssignmentId()).addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("validFrom", request.validFrom()).addValue("validTo", request.validTo());
        if (previousEnds.equals(previousTo)) {
            var replay = jdbc.queryForList("""
                    select id from work_package_allocation where tenant_id=:tenantId
                      and work_package_version_id=:versionId and position_assignment_id=:assignmentId
                      and target_org_unit_id=:targetOrgUnitId and status='ACTIVE' and valid_from=:validFrom
                      and valid_to is not distinct from cast(:validTo as date)
                    """, params);
            if (replay.size() == 1) return response("id", replay.getFirst().get("id"), "status", "ACTIVE",
                    "previousAllocationId", allocationId, "workPackageVersionId", request.workPackageVersionId(),
                    "validFrom", request.validFrom(), "validTo", request.validTo(), "replayed", true);
        }
        if (previousTo != null && previousTo.isBefore(request.validFrom())) {
            throw new IllegalArgumentException("原下发已经结束或已有计划切换，请刷新核对，不能延长原有效期");
        }
        if (old.get("version_last_date") != null
                && ((java.sql.Date) old.get("version_last_date")).toLocalDate().isBefore(previousEnds)) {
            throw new IllegalArgumentException("原版本有效期无法覆盖切换前的日期，请让切换日期与新版本生效边界衔接");
        }
        Boolean ready = jdbc.queryForObject("""
                select exists(select 1 from work_package_version where tenant_id=:tenantId and id=:versionId
                  and work_package_definition_id=:workPackageId and lifecycle_status='PUBLISHED'
                  and effective_from<=:startsAt and (effective_to is null or effective_to>:startsAt))
                """, params.addValue("startsAt", request.validFrom().atStartOfDay(tenantZone).toOffsetDateTime()), Boolean.class);
        if (!Boolean.TRUE.equals(ready)) throw new IllegalArgumentException("新版本须已发布，并在切换日期有效");
        jdbc.update("""
                update work_package_allocation set valid_to=:previousEnds,updated_at=now()
                where tenant_id=:tenantId and id=:allocationId and status='ACTIVE'
                """, params.addValue("previousEnds", previousEnds));
        Map<String, Object> created = new LinkedHashMap<>(createAllocation(workPackageId, new WorkPackageModels.CreateAllocation(
                request.workPackageVersionId(), request.positionAssignmentId(), request.targetOrgUnitId(),
                request.validFrom(), request.validTo(), "MANUAL")));
        auditWriter.record("WORK_PACKAGE_ALLOCATION_REPLACED", "WORK_PACKAGE_ALLOCATION", allocationId,
                "{\"newAllocationId\":\"" + created.get("id") + "\",\"validFrom\":\"" + request.validFrom() + "\"}");
        created.put("previousAllocationId", allocationId); created.put("validFrom", request.validFrom());
        created.put("validTo", request.validTo()); created.put("replayed", false);
        return created;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> dutyPeriods(
            UUID positionAssignmentId,
            UUID targetOrgUnitId,
            LocalDate businessDate
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.read");
        MapSqlParameterSource params = base(principal);
        List<String> predicates = new ArrayList<>();
        if (positionAssignmentId != null) {
            predicates.add("d.position_assignment_id = :assignmentId");
            params.addValue("assignmentId", positionAssignmentId);
        }
        if (targetOrgUnitId != null) {
            accessPolicy.requireOrgScope(targetOrgUnitId);
            predicates.add("d.target_org_unit_id = :targetOrgUnitId");
            params.addValue("targetOrgUnitId", targetOrgUnitId);
        }
        if (businessDate != null) {
            predicates.add("d.business_date = :businessDate");
            params.addValue("businessDate", businessDate);
        }
        String visibility = orgVisibility(principal, params, "d.target_org_unit_id");
        String filters = predicates.isEmpty() ? "" : " and " + String.join(" and ", predicates);
        return jdbc.queryForList("""
                select d.id, d.position_assignment_id, e.name as employee_name, p.name as position_name,
                       d.target_org_unit_id, o.name as target_org_unit_name, d.business_date, d.period_type,
                       d.shift_code, d.planned_start_at, d.planned_end_at, d.status, d.source_record_id,
                       d.created_at, d.updated_at
                from work_duty_period d
                join employee_position_assignment a on a.tenant_id = d.tenant_id and a.id = d.position_assignment_id
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = d.tenant_id and o.id = d.target_org_unit_id
                where d.tenant_id = :tenantId
                """ + visibility + filters + " order by d.business_date desc, d.planned_start_at", params);
    }

    @Transactional
    public Map<String, Object> createDutyPeriod(WorkPackageModels.CreateDutyPeriod request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        accessPolicy.requireOrgScope(request.targetOrgUnitId());
        String periodType = request.periodType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SHIFT", "DAY", "WEEK").contains(periodType)) {
            throw new IllegalArgumentException("班次周期只支持SHIFT、DAY或WEEK");
        }
        if (!request.plannedEndAt().isAfter(request.plannedStartAt())) {
            throw new IllegalArgumentException("计划结束时间必须晚于开始时间");
        }
        requireActiveAssignment(principal, request.positionAssignmentId(), request.businessDate());
        UUID dutyPeriodId = UUID.randomUUID();
        jdbc.update("""
                insert into work_duty_period
                    (id, tenant_id, position_assignment_id, target_org_unit_id, business_date, period_type,
                     shift_code, planned_start_at, planned_end_at, source_record_id, created_by)
                values
                    (:id, :tenantId, :assignmentId, :targetOrgUnitId, :businessDate, :periodType,
                     :shiftCode, :plannedStartAt, :plannedEndAt, :sourceRecordId, :actorId)
                """, base(principal)
                .addValue("id", dutyPeriodId)
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("businessDate", request.businessDate())
                .addValue("periodType", periodType)
                .addValue("shiftCode", trimToNull(request.shiftCode()))
                .addValue("plannedStartAt", request.plannedStartAt())
                .addValue("plannedEndAt", request.plannedEndAt())
                .addValue("sourceRecordId", trimToNull(request.sourceRecordId()))
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WORK_DUTY_PERIOD_CREATED", "WORK_DUTY_PERIOD", dutyPeriodId,
                "{\"periodType\":\"" + periodType + "\"}");
        Map<String, Object> generated = generateExpectationsInternal(principal,
                new WorkPackageModels.GenerateExpectations(request.positionAssignmentId(), request.targetOrgUnitId(),
                        request.businessDate(), periodType, dutyPeriodId));
        Map<String, Object> result = response("id", dutyPeriodId, "status", "PLANNED",
                "businessDate", request.businessDate(), "periodType", periodType);
        result.put("expectations", generated);
        return result;
    }

    @Transactional
    public Map<String, Object> generateExpectations(WorkPackageModels.GenerateExpectations request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        accessPolicy.requireOrgScope(request.targetOrgUnitId());
        return generateExpectationsInternal(principal, request);
    }

    /** Internal activation hook; the organization/onboarding caller has authorized this assignment. */
    @Transactional
    public void initializeOperationalAssignment(UUID assignmentId) {
        TenantPrincipal principal = prepare();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        operationalAllocations.reconcile(principal, assignmentId, today);
        operationalAllocations.ensureManagerCycles(principal, today);
        generateDailyExpectations(principal, today, assignmentId);
    }

    /**
     * Materializes every active DAY allocation for the tenant. The method is intentionally
     * package-private: only the trusted SLA scheduler invokes it under a tenant-scoped system
     * principal. The expectation business key keeps repeated scheduler ticks idempotent.
     */
    int generateDailyExpectationsForAutomation(LocalDate businessDate) {
        TenantPrincipal principal = prepare();
        operationalAllocations.ensureInitialAllocations(principal, businessDate);
        operationalAllocations.ensureManagerCycles(principal, businessDate);
        return generateDailyExpectations(principal, businessDate, null);
    }

    private int generateDailyExpectations(TenantPrincipal principal, LocalDate businessDate, UUID assignmentId) {
        List<AllocationTarget> targets = jdbc.query("""
                select distinct a.position_assignment_id, a.target_org_unit_id, i.period_type
                from work_package_allocation a
                join work_package_version v
                  on v.tenant_id = a.tenant_id and v.id = a.work_package_version_id
                join work_package_item i
                  on i.tenant_id = v.tenant_id and i.work_package_version_id = v.id
                join employee_position_assignment assignment
                  on assignment.tenant_id = a.tenant_id and assignment.id = a.position_assignment_id
                join employee employee_row
                  on employee_row.tenant_id = assignment.tenant_id
                 and employee_row.id = assignment.employee_id
                where a.tenant_id = :tenantId
                  and (cast(:assignmentId as uuid) is null or a.position_assignment_id = :assignmentId)
                  and a.status = 'ACTIVE'
                  and a.valid_from <= :businessDate
                  and (a.valid_to is null or a.valid_to >= :businessDate)
                  and v.lifecycle_status = 'PUBLISHED'
                  and v.effective_from::date <= :businessDate
                  and (v.effective_to is null or v.effective_to::date >= :businessDate)
                  and (i.period_type = 'DAY' or (i.period_type in ('WEEK','MONTH','QUARTER')
                       and jsonb_typeof(i.execution_policy->'cycleSchedule') = 'object'))
                  and coalesce((i.execution_policy ->> 'enabled')::boolean, true)
                  and (cardinality(i.weekdays) = 0
                       or extract(isodow from cast(:businessDate as date))::smallint = any(i.weekdays))
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and employee_row.employment_status = 'ACTIVE'
                order by a.position_assignment_id, a.target_org_unit_id, i.period_type
                """, base(principal).addValue("businessDate", businessDate).addValue("assignmentId", assignmentId),
                (rs, rowNum) -> new AllocationTarget(
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("target_org_unit_id", UUID.class), rs.getString("period_type")));
        int created = 0;
        for (AllocationTarget target : targets) {
            Map<String, Object> result = generateExpectationsInternal(principal,
                    new WorkPackageModels.GenerateExpectations(
                            target.assignmentId(), target.targetOrgUnitId(), businessDate, target.periodType(), null));
            created += ((Number) result.getOrDefault("createdCount", 0)).intValue();
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teamExpectations(
            String status,
            LocalDate businessDate,
            LocalDate fromBusinessDate,
            LocalDate toBusinessDate,
            int page,
            int size
    ) {
        accessPolicy.requireAnyPermission("work-record.review", "work-record.team-read");
        return expectationsScoped(status, null, null, businessDate,
                fromBusinessDate, toBusinessDate, false, true, page, size);
    }

    @Transactional
    public Map<String, Object> teamWorkbenchSummary(LocalDate businessDate) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission("work-record.review", "work-record.team-read");
        ZoneId businessZone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(businessZone);
        LocalDate asOfDate = businessDate == null ? today : businessDate;
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        OffsetDateTime cutoff = asOfDate.equals(today)
                ? OffsetDateTime.now(businessZone)
                : asOfDate.plusDays(1).atStartOfDay(businessZone).minusNanos(1).toOffsetDateTime();
        MapSqlParameterSource params = base(principal)
                .addValue("monthStart", monthStart)
                .addValue("asOfDate", asOfDate);
        String visibility = " and " + workVisibility.scope(principal, params).summary("x");
        List<WorkbenchMetricRow> rows = jdbc.query("""
                select x.business_date, x.due_at, x.status,
                       first_record.submitted_at as first_submitted_at,
                       hotel_context.id as hotel_org_unit_id,
                       hotel_context.name as hotel_name,
                       department_context.id as department_org_unit_id,
                       department_context.name as department_name,
                       assignment.id as assignment_id,
                       employee_row.id as employee_id,
                       employee_row.name as employee_name,
                       position.name as position_name,
                       target_org.id as target_org_id, target_org.name as target_org_name
                from work_expectation x
                join employee_position_assignment assignment
                  on assignment.tenant_id = x.tenant_id
                 and assignment.id = x.position_assignment_id
                join employee employee_row
                  on employee_row.tenant_id = assignment.tenant_id
                 and employee_row.id = assignment.employee_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                join org_unit target_org
                  on target_org.tenant_id = x.tenant_id and target_org.id = x.target_org_unit_id
                left join lateral (
                    select min(w.submitted_at) as submitted_at
                    from work_record w
                    where w.tenant_id = x.tenant_id
                      and w.work_expectation_id = x.id
                      and w.submitted_at is not null
                ) first_record on true
                left join lateral (
                    select hotel.id, hotel.name
                    from org_unit_closure hotel_link
                    join org_unit hotel
                      on hotel.tenant_id = hotel_link.tenant_id
                     and hotel.id = hotel_link.ancestor_id
                     and hotel.unit_type = 'HOTEL'
                    where hotel_link.tenant_id = x.tenant_id
                      and hotel_link.descendant_id = x.target_org_unit_id
                    order by hotel_link.depth
                    limit 1
                ) hotel_context on true
                left join lateral (
                    select department.id, department.name
                    from org_unit_closure department_link
                    join org_unit department
                      on department.tenant_id = department_link.tenant_id
                     and department.id = department_link.ancestor_id
                     and department.unit_type = 'DEPARTMENT'
                    where department_link.tenant_id = assignment.tenant_id
                      and department_link.descendant_id = assignment.org_unit_id
                    order by department_link.depth
                    limit 1
                ) department_context on true
                where x.tenant_id = :tenantId
                  and x.business_date between :monthStart and :asOfDate
                  and x.status not in ('WAIVED', 'CANCELLED')
                """ + visibility + " order by x.business_date, hotel_context.name nulls last, x.due_at", params,
                (rs, rowNum) -> new WorkbenchMetricRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getString("status"),
                        rs.getObject("first_submitted_at", OffsetDateTime.class),
                        rs.getObject("hotel_org_unit_id", UUID.class),
                        rs.getString("hotel_name"),
                        rs.getObject("department_org_unit_id", UUID.class),
                        rs.getString("department_name"),
                        rs.getObject("assignment_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("employee_name"),
                        rs.getString("position_name"),
                        rs.getObject("target_org_id", UUID.class),
                        rs.getString("target_org_name")));

        WorkbenchMetric totalToday = new WorkbenchMetric();
        WorkbenchMetric totalMonth = new WorkbenchMetric();
        Map<UUID, HotelWorkbenchMetric> hotelMetrics = new LinkedHashMap<>();
        Map<UUID, HotelWorkbenchMetric> organizationMetrics = new LinkedHashMap<>();
        for (WorkbenchMetricRow row : rows) {
            totalMonth.add(row, cutoff);
            if (asOfDate.equals(row.businessDate())) {
                totalToday.add(row, cutoff);
            }
            if (row.hotelOrgUnitId() != null) {
                HotelWorkbenchMetric hotel = hotelMetrics.computeIfAbsent(row.hotelOrgUnitId(),
                        ignored -> new HotelWorkbenchMetric(row.hotelOrgUnitId(), row.hotelName()));
                hotel.add(row, cutoff, asOfDate.equals(row.businessDate()));
            } else {
                HotelWorkbenchMetric organization = organizationMetrics.computeIfAbsent(row.targetOrgUnitId(),
                        ignored -> new HotelWorkbenchMetric(row.targetOrgUnitId(), row.targetOrgName()));
                organization.add(row, cutoff, asOfDate.equals(row.businessDate()));
            }
        }
        List<Map<String, Object>> hotels = hotelMetrics.values().stream()
                .sorted(Comparator.comparing(HotelWorkbenchMetric::name, Comparator.nullsLast(String::compareTo)))
                .map(HotelWorkbenchMetric::toResponse)
                .toList();
        employeePerformanceSnapshotService.captureWorkbench(
                principal,
                asOfDate,
                cutoff,
                rows.stream().map(WorkbenchMetricRow::assignmentId).distinct().toList()
        );
        return response(
                "asOfDate", asOfDate,
                "monthStart", monthStart,
                "today", totalToday.toResponse(),
                "monthToDate", totalMonth.toResponse(),
                "hotels", hotels,
                "organizations", organizationMetrics.values().stream()
                        .sorted(Comparator.comparing(HotelWorkbenchMetric::name, Comparator.nullsLast(String::compareTo)))
                        .map(HotelWorkbenchMetric::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> expectations(
            String status,
            UUID positionAssignmentId,
            UUID targetOrgUnitId,
            LocalDate businessDate,
            LocalDate fromBusinessDate,
            LocalDate toBusinessDate,
            boolean mineOnly,
            int page,
            int size
    ) {
        return expectationsScoped(status, positionAssignmentId, targetOrgUnitId, businessDate,
                fromBusinessDate, toBusinessDate, mineOnly, false, page, size);
    }

    private List<Map<String, Object>> expectationsScoped(
            String status, UUID positionAssignmentId, UUID targetOrgUnitId, LocalDate businessDate,
            LocalDate fromBusinessDate, LocalDate toBusinessDate, boolean mineOnly,
            boolean teamOnly, int page, int size
    ) {
        ExpectationQuery query = expectationQuery(status, positionAssignmentId, targetOrgUnitId,
                businessDate, fromBusinessDate, toBusinessDate, mineOnly, page, size, false,
                null, null, teamOnly);
        return jdbc.queryForList("select legacy.* from (" + query.sql() + ") legacy order by case "
                + "when business_date = current_date and status = 'SUBMITTED' then 0 "
                + "when business_date = current_date then 1 "
                + "when period_type <> 'DAY' and status in ('OVERDUE', 'MISSED', 'FAILED') then 2 "
                + "when status = 'SUBMITTED' then 3 else 4 end, "
                + "latest_submitted_at desc nulls last, business_date desc, due_at desc, id desc "
                + "limit :pageSize offset :pageOffset", query.params());
    }

    record ExpectationQuery(String sql, MapSqlParameterSource params) {}

    // The board and legacy lists share tenant, organization, delegation and ownership checks.
    ExpectationQuery expectationQuery(
            String status, UUID positionAssignmentId, UUID targetOrgUnitId,
            LocalDate businessDate, LocalDate fromBusinessDate, LocalDate toBusinessDate,
            boolean mineOnly, int page, int size, boolean currentAssignmentOnly
    ) {
        return expectationQuery(status, positionAssignmentId, targetOrgUnitId, businessDate,
                fromBusinessDate, toBusinessDate, mineOnly, page, size, currentAssignmentOnly, null, null);
    }

    ExpectationQuery expectationQuery(
            String status, UUID positionAssignmentId, UUID targetOrgUnitId,
            LocalDate businessDate, LocalDate fromBusinessDate, LocalDate toBusinessDate,
            boolean mineOnly, int page, int size, boolean currentAssignmentOnly,
            LocalDate boardScanFrom, LocalDate boardScanTo
    ) {
        return expectationQuery(status, positionAssignmentId, targetOrgUnitId, businessDate,
                fromBusinessDate, toBusinessDate, mineOnly, page, size, currentAssignmentOnly,
                boardScanFrom, boardScanTo, !mineOnly);
    }

    private ExpectationQuery expectationQuery(
            String status, UUID positionAssignmentId, UUID targetOrgUnitId,
            LocalDate businessDate, LocalDate fromBusinessDate, LocalDate toBusinessDate,
            boolean mineOnly, int page, int size, boolean currentAssignmentOnly,
            LocalDate boardScanFrom, LocalDate boardScanTo, boolean teamOnly
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission(
                "work-package.read", "work-record.read", "work-record.review", "work-record.team-read");
        if (page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page必须大于等于0，size必须在1到200之间");
        }
        if (businessDate == null && fromBusinessDate != null && toBusinessDate != null
                && fromBusinessDate.isAfter(toBusinessDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fromBusinessDate不能晚于toBusinessDate");
        }
        MapSqlParameterSource params = base(principal)
                .addValue("actorId", principal.actorId())
                .addValue("pageSize", size)
                .addValue("pageOffset", (long) page * size);
        List<String> predicates = new ArrayList<>();
        if (boardScanFrom != null && boardScanTo != null) {
            params.addValue("boardScanFrom", boardScanFrom).addValue("boardScanTo", boardScanTo)
                    .addValue("boardRecentSince", OffsetDateTime.now().minusDays(7));
            // Keep cross-period review and continuing work, but avoid reading years of closed daily work.
            predicates.add("""
                    (x.business_date between :boardScanFrom and :boardScanTo
                     or x.status = 'SUBMITTED'
                     or (i.review_mode <> 'NONE' and first_record.pending_review_count > 0)
                     or latest_record.submitted_at >= :boardRecentSince
                     or x.status = 'FAILED' or latest_record.status in ('REJECTED','RETURNED')
                     or (i.required and i.review_mode = 'NONE' and latest_record.status in ('SUBMITTED','APPROVED'))
                     or (i.period_type <> 'DAY' and x.status in ('PLANNED','AVAILABLE','IN_PROGRESS','MISSED','FAILED')))
                    """);
        }
        if (status != null && !status.isBlank()) {
            predicates.add("x.status = :status");
            params.addValue("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (positionAssignmentId != null) {
            predicates.add("x.position_assignment_id = :assignmentId");
            params.addValue("assignmentId", positionAssignmentId);
        }
        if (targetOrgUnitId != null) {
            accessPolicy.requireOrgScope(targetOrgUnitId);
            predicates.add("""
                    exists (
                      select 1 from org_unit_closure requested_scope
                      where requested_scope.tenant_id = x.tenant_id
                        and requested_scope.ancestor_id = :targetOrgUnitId
                        and requested_scope.descendant_id = x.target_org_unit_id
                    )
                    """);
            params.addValue("targetOrgUnitId", targetOrgUnitId);
        }
        if (businessDate != null) {
            predicates.add("x.business_date = :businessDate");
            params.addValue("businessDate", businessDate);
        } else {
            if (fromBusinessDate != null) {
                predicates.add("x.business_date >= :fromBusinessDate");
                params.addValue("fromBusinessDate", fromBusinessDate);
            }
            if (toBusinessDate != null) {
                predicates.add("x.business_date <= :toBusinessDate");
                params.addValue("toBusinessDate", toBusinessDate);
            }
        }
        if (mineOnly) {
            if (currentAssignmentOnly) {
                params.addValue("businessAssignmentId", principal.businessActorAssignmentId());
                if (principal.businessActorAssignmentId() == null) {
                    predicates.add("false");
                } else {
                    // An account can hold several jobs. Only the selected assignment owns this board.
                    predicates.add("""
                            (
                              (x.position_assignment_id = :businessAssignmentId and not exists (
                                select 1 from work_expectation_delegation ad
                                where ad.tenant_id = x.tenant_id and ad.work_expectation_id = x.id
                                  and ad.status = 'ACTIVE'))
                              or exists (
                                select 1 from work_expectation_delegation ad
                                where ad.tenant_id = x.tenant_id and ad.work_expectation_id = x.id
                                  and ad.status = 'ACTIVE' and ad.delegate_assignment_id = :businessAssignmentId)
                              or (not exists (
                                select 1 from work_expectation_delegation ad
                                where ad.tenant_id = x.tenant_id and ad.work_expectation_id = x.id
                                  and ad.status = 'ACTIVE')
                                and exists (
                                  select 1 from employee_position_assignment sa
                                  join position_definition sp on sp.tenant_id = sa.tenant_id and sp.id = sa.position_id
                                  join org_unit_closure sc on sc.tenant_id = sa.tenant_id and sc.descendant_id = sa.org_unit_id
                                  where sa.tenant_id = x.tenant_id and sa.id = :businessAssignmentId
                                    and sc.ancestor_id = x.target_org_unit_id and sa.status = 'ACTIVE'
                                    and sa.valid_from <= x.business_date
                                    and (sa.valid_to is null or sa.valid_to >= x.business_date)
                                    and jsonb_exists(coalesce(i.execution_policy -> 'allowedPositionCodes', '[]'::jsonb), sp.code)))
                            )
                            """);
                }
            }
        }
        String filters = predicates.isEmpty() ? "" : " and " + String.join(" and ", predicates);
        WorkVisibilityPolicy.Scope workScope = workVisibility.scope(principal, params);
        String visibility = " and " + (teamOnly ? workScope.summary("x") : workScope.expectation("x", !mineOnly));
        return new ExpectationQuery("""
                select x.id, x.business_date, x.period_key, x.available_at, x.due_at, x.status,
                       x.row_version, x.waiver_allowed,
                       x.position_assignment_id, e.id as employee_id, e.name as employee_name,
                       p.name as position_name, p.job_family as position_job_family,
                       x.target_org_unit_id, o.name as target_org_unit_name,
                       i.id as work_package_item_id, i.item_code, i.name as item_name, i.item_type,
                       i.period_type,
                """ + cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy.effectiveModeSql("i", "x") + """
                       as review_mode, i.required as item_required,
                       latest_record.status as latest_record_status,
                       first_record.pending_review_count, first_record.submitted_record_count,
                       first_record.valid_submitted_at,
                       department_context.id as department_org_unit_id,
                       i.form_version_id, i.submission_policy, i.reminder_policy, i.report_policy,
                       i.applicability_policy, i.execution_policy,
                       hotel_context.id as hotel_org_unit_id, hotel_context.name as hotel_name,
                       department_context.name as department_name,
                        coalesce(h.guest_room_floor_count, 1) as guest_room_floor_count,
                        coalesce(h.operational_feature_flags, '{}'::jsonb) as operational_feature_flags,
                        coalesce(h.evidence_instance_configuration,
                            '{"facilityInstances":{},"assetInstances":{}}'::jsonb) as evidence_instance_configuration,
                       delegation.delegate_assignment_id, delegated_employee.name as delegated_employee_name,
                       delegation.owner_resting,
                       d.id as work_package_id, d.code as work_package_code, d.name as work_package_name,
                       v.id as work_package_version_id, v.version_no,
                       latest_record.id as record_id, latest_record.attempt_no as latest_attempt_no,
                       latest_record.submitted_at as latest_submitted_at,
                       case when latest_record.submitted_at is not null
                            then latest_record.position_assignment_id end as actual_executor_assignment_id,
                       case when latest_record.submitted_at is not null
                            then latest_record.employee_id end as actual_executor_employee_id,
                       case when latest_record.submitted_at is not null
                            then latest_record.employee_name end as actual_executor_employee_name,
                       case when latest_record.submitted_at is not null
                            then latest_record.position_name end as actual_executor_position_name,
                       first_record.submitted_at as first_submitted_at
                from work_expectation x
                join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                join work_package_version v on v.tenant_id = i.tenant_id and v.id = i.work_package_version_id
                join work_package_definition d on d.tenant_id = v.tenant_id and d.id = v.work_package_definition_id
                join employee_position_assignment a on a.tenant_id = x.tenant_id and a.id = x.position_assignment_id
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = x.tenant_id and o.id = x.target_org_unit_id
                left join lateral (
                    select hotel.id, hotel.name
                    from org_unit_closure hotel_link
                    join org_unit hotel
                      on hotel.tenant_id = hotel_link.tenant_id
                     and hotel.id = hotel_link.ancestor_id
                     and hotel.unit_type = 'HOTEL'
                    where hotel_link.tenant_id = x.tenant_id
                      and hotel_link.descendant_id = x.target_org_unit_id
                    order by hotel_link.depth
                    limit 1
                ) hotel_context on true
                left join lateral (
                    select department.id, department.name
                    from org_unit_closure department_link
                    join org_unit department
                      on department.tenant_id = department_link.tenant_id
                     and department.id = department_link.ancestor_id
                     and department.unit_type = 'DEPARTMENT'
                    where department_link.tenant_id = x.tenant_id
                      and department_link.descendant_id = x.target_org_unit_id
                    order by department_link.depth
                    limit 1
                ) department_context on true
                left join hotel_profile h
                  on h.tenant_id = x.tenant_id
                 and h.org_unit_id = coalesce(hotel_context.id, x.target_org_unit_id)
                left join work_expectation_delegation delegation
                  on delegation.tenant_id = x.tenant_id
                 and delegation.work_expectation_id = x.id and delegation.status = 'ACTIVE'
                left join employee_position_assignment delegated_assignment
                  on delegated_assignment.tenant_id = delegation.tenant_id
                 and delegated_assignment.id = delegation.delegate_assignment_id
                left join employee delegated_employee
                  on delegated_employee.tenant_id = delegated_assignment.tenant_id
                 and delegated_employee.id = delegated_assignment.employee_id
                left join lateral (
                    select w.id, w.status, w.attempt_no, w.submitted_at, w.position_assignment_id,
                           actual_employee.id as employee_id,
                           actual_employee.name as employee_name,
                           actual_position.name as position_name
                    from work_record w
                    join employee_position_assignment actual_assignment
                      on actual_assignment.tenant_id = w.tenant_id
                     and actual_assignment.id = w.position_assignment_id
                    join employee actual_employee
                      on actual_employee.tenant_id = actual_assignment.tenant_id
                     and actual_employee.id = actual_assignment.employee_id
                    join position_definition actual_position
                      on actual_position.tenant_id = actual_assignment.tenant_id
                     and actual_position.id = actual_assignment.position_id
                    where w.tenant_id = x.tenant_id
                      and w.work_expectation_id = x.id
                    order by w.attempt_no desc, w.created_at desc
                    limit 1
                ) latest_record on true
                left join lateral (
                    select min(w.submitted_at) as submitted_at,
                           min(w.submitted_at) filter (where w.status in ('SUBMITTED','APPROVED')) as valid_submitted_at,
                           count(*) filter (where w.status = 'SUBMITTED') as pending_review_count,
                           count(*) as submitted_record_count
                    from work_record w
                    where w.tenant_id = x.tenant_id
                      and w.work_expectation_id = x.id
                      and w.submitted_at is not null
                ) first_record on true
                where x.tenant_id = :tenantId
                """ + visibility + filters, params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> expectationDetail(UUID expectationId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission(
                "work-package.read", "work-record.read", "work-record.review", "work-record.team-read");
        MapSqlParameterSource params = base(principal).addValue("expectationId", expectationId);
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                select x.*, i.item_code, i.name as item_name, i.item_type, i.period_type,
                       i.form_version_id,
                       i.work_window_start, i.work_window_end, i.due_local_time,
                       i.grace_minutes,
                """ + cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy.effectiveModeSql("i", "x") + """
                       as review_mode, i.submission_policy,
                       i.reminder_policy, i.report_policy, i.applicability_policy,
                        i.execution_policy, coalesce(h.guest_room_floor_count, 1) as guest_room_floor_count,
                        coalesce(h.operational_feature_flags, '{}'::jsonb) as operational_feature_flags,
                        coalesce(h.evidence_instance_configuration,
                            '{"facilityInstances":{},"assetInstances":{}}'::jsonb) as evidence_instance_configuration,
                       delegation.delegate_assignment_id, delegated_employee.name as delegated_employee_name,
                       delegation.owner_resting,
                       d.id as work_package_id, d.code as work_package_code, d.name as work_package_name,
                       v.id as work_package_version_id, v.version_no,
                       e.name as employee_name, p.name as position_name, o.name as target_org_unit_name,
                       hotel_context.id as hotel_org_unit_id, hotel_context.name as hotel_name,
                       fd.code as form_code, fd.name as form_name, fv.json_schema as form_schema
                from work_expectation x
                join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                join work_package_version v on v.tenant_id = i.tenant_id and v.id = i.work_package_version_id
                join work_package_definition d on d.tenant_id = v.tenant_id and d.id = v.work_package_definition_id
                join form_version fv on fv.tenant_id = i.tenant_id and fv.id = i.form_version_id
                join form_definition fd on fd.tenant_id = fv.tenant_id and fd.id = fv.form_id
                join employee_position_assignment a on a.tenant_id = x.tenant_id and a.id = x.position_assignment_id
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = x.tenant_id and o.id = x.target_org_unit_id
                left join lateral (
                    select hotel.id, hotel.name
                    from org_unit_closure hotel_link
                    join org_unit hotel
                      on hotel.tenant_id = hotel_link.tenant_id
                     and hotel.id = hotel_link.ancestor_id
                     and hotel.unit_type = 'HOTEL'
                    where hotel_link.tenant_id = x.tenant_id
                      and hotel_link.descendant_id = x.target_org_unit_id
                    order by hotel_link.depth
                    limit 1
                ) hotel_context on true
                left join hotel_profile h
                  on h.tenant_id = x.tenant_id
                 and h.org_unit_id = coalesce(hotel_context.id, x.target_org_unit_id)
                left join work_expectation_delegation delegation
                  on delegation.tenant_id = x.tenant_id
                 and delegation.work_expectation_id = x.id and delegation.status = 'ACTIVE'
                left join employee_position_assignment delegated_assignment
                  on delegated_assignment.tenant_id = delegation.tenant_id
                 and delegated_assignment.id = delegation.delegate_assignment_id
                left join employee delegated_employee
                  on delegated_employee.tenant_id = delegated_assignment.tenant_id
                 and delegated_employee.id = delegated_assignment.employee_id
                where x.tenant_id = :tenantId and x.id = :expectationId
                """, params));
        workVisibility.requireExpectation(principal, expectationId);
        result.put("form_schema", cn.sifangguan.hotelaios.workdata.WorkEntryPolicy.schema(
                readObjectPolicy(java.util.Objects.toString(result.get("form_schema"), "{}"), "工作表单"), (String) result.get("item_code")));
        ObjectNode entryPolicy = (ObjectNode) readObjectPolicy(result.get("submission_policy").toString(), "提交规则");
        entryPolicy.put("completionStatementRequired", false);
        result.put("submission_policy", entryPolicy);
        result.put("standards", jdbc.queryForList("""
                select s.standard_version_id, s.usage_type, s.weight, sd.code as standard_code,
                       sv.version_no as standard_version_no, sv.title
                from work_package_item_standard s
                join standard_version sv on sv.tenant_id = s.tenant_id and sv.id = s.standard_version_id
                join standard_definition sd on sd.tenant_id = sv.tenant_id and sd.id = sv.standard_id
                where s.tenant_id = :tenantId and s.work_package_item_id = :itemId
                order by s.usage_type, sd.code
                """, base(principal).addValue("itemId", result.get("work_package_item_id"))));
        result.put("records", jdbc.queryForList("""
                select record.id, record.status, record.series_entry_no, record.repeatable_entry,
                       record.attempt_no, record.payload,
                       record.position_assignment_id as actual_executor_assignment_id,
                       employee.id as actual_executor_employee_id,
                       employee.name as actual_executor_employee_name,
                       position.name as actual_executor_position_name,
                       record.submitted_at, record.reviewed_at, record.review_reason, record.created_at
                from work_record record
                join employee_position_assignment assignment
                  on assignment.tenant_id = record.tenant_id
                 and assignment.id = record.position_assignment_id
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                where record.tenant_id = :tenantId and record.work_expectation_id = :expectationId
                order by record.series_entry_no desc, record.attempt_no desc
                """, params));
        return result;
    }

    @Transactional
    public Map<String, Object> waiveExpectation(UUID expectationId, WorkPackageModels.ExpectationAction request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        ExpectationAccess expectation = expectationAccess(principal, expectationId);
        accessPolicy.requireOrgScope(expectation.targetOrgUnitId());
        if (!expectation.waiverAllowed()) {
            throw new IllegalArgumentException("该工作条目不允许豁免");
        }
        Integer self = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                where a.tenant_id = :tenantId and a.id = :assignmentId and e.account_id = :actorId
                """, base(principal)
                .addValue("assignmentId", expectation.assignmentId())
                .addValue("actorId", principal.actorId()), Integer.class);
        if (self != null && self > 0) {
            throw new AccessDeniedException("岗位本人不能批准自己的工作豁免");
        }
        int changed = jdbc.update("""
                update work_expectation
                set status = 'WAIVED', waiver_reason = :reason, waived_by_account_id = :actorId,
                    waived_at = now(), row_version = row_version + 1
                where tenant_id = :tenantId and id = :expectationId
                  and status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS')
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("expectationId", expectationId)
                .addValue("reason", request.reason().trim())
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new IllegalArgumentException("工作期望状态已变化，请刷新后重试");
        }
        auditWriter.record("WORK_EXPECTATION_WAIVED", "WORK_EXPECTATION", expectationId,
                "{\"reason\":\"" + jsonEscape(request.reason().trim()) + "\"}");
        auditWriter.emit("WORK_EXPECTATION", expectationId, "WorkExpectationWaived",
                "{\"workExpectationId\":\"" + expectationId + "\",\"orgUnitId\":\""
                        + expectation.targetOrgUnitId() + "\"}");
        return response("id", expectationId, "status", "WAIVED", "rowVersion", request.expectedVersion() + 1);
    }

    @Transactional
    public Map<String, Object> cancelExpectation(UUID expectationId, WorkPackageModels.ExpectationAction request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.allocate");
        ExpectationAccess expectation = expectationAccess(principal, expectationId);
        accessPolicy.requireOrgScope(expectation.targetOrgUnitId());
        int changed = jdbc.update("""
                update work_expectation
                set status = 'CANCELLED', cancellation_reason = :reason, cancelled_by_account_id = :actorId,
                    cancelled_at = now(), row_version = row_version + 1
                where tenant_id = :tenantId and id = :expectationId
                  and status in ('PLANNED', 'AVAILABLE') and row_version = :expectedVersion
                """, base(principal)
                .addValue("expectationId", expectationId)
                .addValue("reason", request.reason().trim())
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new IllegalArgumentException("工作期望已开始或状态已变化，不能取消");
        }
        auditWriter.record("WORK_EXPECTATION_CANCELLED", "WORK_EXPECTATION", expectationId,
                "{\"reason\":\"" + jsonEscape(request.reason().trim()) + "\"}");
        auditWriter.emit("WORK_EXPECTATION", expectationId, "WorkExpectationCancelled",
                "{\"workExpectationId\":\"" + expectationId + "\",\"orgUnitId\":\""
                        + expectation.targetOrgUnitId() + "\"}");
        return response("id", expectationId, "status", "CANCELLED", "rowVersion", request.expectedVersion() + 1);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> delegationCandidates(UUID expectationId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-package.read");
        ExpectationAccess expectation = expectationAccess(principal, expectationId);
        DelegationActor delegationActor = requireDelegationOwner(principal, expectation);
        return jdbc.queryForList("""
                select assignment.id as assignment_id, employee.id as employee_id,
                       employee.name as employee_name, position.name as position_name
                from employee_position_assignment assignment
                join employee
                  on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                join user_account account
                  on account.tenant_id = employee.tenant_id and account.id = employee.account_id
                where assignment.tenant_id = :tenantId
                  and exists (
                    select 1
                    from org_unit_closure candidate_scope
                    where candidate_scope.tenant_id = assignment.tenant_id
                      and candidate_scope.ancestor_id = :orgUnitId
                      and candidate_scope.descendant_id = assignment.org_unit_id
                  )
                  and assignment.id <> :actingAssignmentId
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and account.status = 'ACTIVE'
                order by employee.name, position.name, assignment.id
                """, base(principal).addValue("orgUnitId", expectation.targetOrgUnitId())
                .addValue("actingAssignmentId", delegationActor.assignmentId())
                .addValue("businessDate", expectation.businessDate()));
    }

    @Transactional
    public Map<String, Object> delegateExpectation(
            UUID expectationId,
            WorkPackageModels.DelegateExpectation request
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        ExpectationAccess expectation = expectationAccess(principal, expectationId);
        DelegationActor delegationActor = requireDelegationOwner(principal, expectation);
        if (!expectation.delegationAllowed()) {
            throw new IllegalArgumentException("该工作事项不允许转交");
        }
        if (!Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS", "MISSED").contains(expectation.status())) {
            throw new IllegalArgumentException("该工作事项已提交或已结束，不能转交");
        }
        if (expectation.rowVersion() != request.expectedVersion()) {
            throw new IllegalArgumentException("工作事项版本已变化，请刷新后重试");
        }
        if (request.delegateAssignmentId().equals(delegationActor.assignmentId())) {
            throw new IllegalArgumentException("不能将工作转交给当前任职本人");
        }
        Integer candidate = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment assignment
                join employee
                  on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                join user_account account
                  on account.tenant_id = employee.tenant_id and account.id = employee.account_id
                where assignment.tenant_id = :tenantId and assignment.id = :delegateAssignmentId
                  and exists (
                    select 1
                    from org_unit_closure delegate_scope
                    where delegate_scope.tenant_id = assignment.tenant_id
                      and delegate_scope.ancestor_id = :orgUnitId
                      and delegate_scope.descendant_id = assignment.org_unit_id
                  )
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and account.status = 'ACTIVE'
                """, base(principal).addValue("delegateAssignmentId", request.delegateAssignmentId())
                .addValue("orgUnitId", expectation.targetOrgUnitId())
                .addValue("businessDate", expectation.businessDate()), Integer.class);
        if (candidate == null || candidate != 1) {
            throw new IllegalArgumentException("受托人必须是同店在职且任职有效的员工");
        }
        UUID delegateAccountId = jdbc.queryForObject("""
                select employee.account_id
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                where assignment.tenant_id = :tenantId and assignment.id = :delegateAssignmentId
                """, base(principal).addValue("delegateAssignmentId", request.delegateAssignmentId()), UUID.class);
        boolean transferRemainingDay = Boolean.TRUE.equals(request.ownerResting())
                && expectation.oneClickRestTransfer();
        List<DelegationTarget> targets = delegationTargetsForUpdate(
                principal, expectation, delegationActor, transferRemainingDay);
        DelegationTarget lockedCurrent = targets.stream()
                .filter(target -> target.id().equals(expectationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工作事项已提交或状态已变化，不能转交"));
        if (lockedCurrent.rowVersion() != request.expectedVersion()) {
            throw new IllegalArgumentException("工作事项版本已变化，请刷新后重试");
        }

        List<UUID> transferredIds = new ArrayList<>();
        List<UUID> delegationIds = new ArrayList<>();
        Map<UUID, UUID> createdDelegationsByExpectation = new LinkedHashMap<>();
        for (DelegationTarget target : targets) {
            if (request.delegateAssignmentId().equals(target.activeDelegateAssignmentId())
                    && Boolean.TRUE.equals(request.ownerResting()) == target.activeOwnerResting()) {
                continue;
            }
            jdbc.update("""
                    update work_expectation_delegation
                    set status = 'REVOKED', revoked_by_account_id = :actorId,
                        revoked_at = now(), updated_at = now()
                    where tenant_id = :tenantId and work_expectation_id = :expectationId
                      and status = 'ACTIVE'
                    """, base(principal).addValue("expectationId", target.id())
                    .addValue("actorId", principal.actorId()));
            UUID delegationId = UUID.randomUUID();
            jdbc.update("""
                    insert into work_expectation_delegation
                        (id, tenant_id, work_expectation_id, delegate_assignment_id,
                         delegated_by_account_id, owner_resting, reason)
                    values (:id, :tenantId, :expectationId, :delegateAssignmentId,
                            :actorId, :ownerResting, :reason)
                    """, base(principal).addValue("id", delegationId)
                    .addValue("expectationId", target.id())
                    .addValue("delegateAssignmentId", request.delegateAssignmentId())
                    .addValue("actorId", principal.actorId())
                    .addValue("ownerResting", Boolean.TRUE.equals(request.ownerResting()))
                    .addValue("reason", trimToNull(request.reason())));
            int updated = jdbc.update("""
                    update work_expectation set row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and id = :expectationId
                      and row_version = :expectedVersion
                    """, base(principal).addValue("expectationId", target.id())
                    .addValue("expectedVersion", target.rowVersion()));
            if (updated != 1) {
                throw new IllegalArgumentException("工作事项版本已变化，请刷新后重试");
            }
            transferredIds.add(target.id());
            delegationIds.add(delegationId);
            createdDelegationsByExpectation.put(target.id(), delegationId);
            if (delegateAccountId != null) {
                UUID notificationId = UUID.randomUUID();
                jdbc.update("""
                        insert into notification
                            (id, tenant_id, recipient_account_id, recipient_assignment_id,
                             notification_type, title, content, source_type, source_id, idempotency_key)
                        values (:id, :tenantId, :accountId, :assignmentId,
                                'WORK_EXPECTATION_DELEGATED', '工作事项已转交给你',
                                '请进入事项按模板完成并上传证据。', 'WORK_EXPECTATION', :expectationId,
                                :idempotencyKey)
                        on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                        """, base(principal).addValue("id", notificationId)
                        .addValue("accountId", delegateAccountId)
                        .addValue("assignmentId", request.delegateAssignmentId())
                        .addValue("expectationId", target.id())
                        .addValue("idempotencyKey", "work-expectation:delegated:" + target.id() + ":" + delegationId));
            }
        }
        ObjectNode audit = JsonNodeFactory.instance.objectNode();
        audit.put("delegateAssignmentId", request.delegateAssignmentId().toString());
        audit.put("ownerResting", Boolean.TRUE.equals(request.ownerResting()));
        audit.put("oneClickRestTransfer", transferRemainingDay);
        audit.put("transferredCount", transferredIds.size());
        audit.putPOJO("transferredIds", transferredIds);
        audit.putPOJO("delegationIds", delegationIds);
        auditWriter.record("WORK_EXPECTATION_DELEGATED", "WORK_EXPECTATION", expectationId,
                audit.toString());
        UUID currentDelegationId = createdDelegationsByExpectation.getOrDefault(
                expectationId, lockedCurrent.activeDelegationId());
        long currentRowVersion = transferredIds.contains(expectationId)
                ? lockedCurrent.rowVersion() + 1 : lockedCurrent.rowVersion();
        return response("id", expectationId, "delegationId", currentDelegationId,
                "delegateAssignmentId", request.delegateAssignmentId(),
                "ownerResting", Boolean.TRUE.equals(request.ownerResting()),
                "transferredCount", transferredIds.size(), "transferredIds", transferredIds,
                "rowVersion", currentRowVersion);
    }

    private List<DelegationTarget> delegationTargetsForUpdate(
            TenantPrincipal principal,
            ExpectationAccess current,
            DelegationActor actor,
            boolean transferRemainingDay
    ) {
        MapSqlParameterSource params = base(principal)
                .addValue("expectationId", current.id())
                .addValue("orgUnitId", current.targetOrgUnitId())
                .addValue("businessDate", current.businessDate())
                .addValue("actingAssignmentId", actor.assignmentId())
                .addValue("actingPositionCode", actor.positionCode())
                .addValue("currentAvailableAt", current.availableAt())
                .addValue("currentSortOrder", current.sortOrder());
        String scope = transferRemainingDay ? """
                  and x.target_org_unit_id = :orgUnitId
                  and x.business_date = :businessDate
                  and coalesce((i.execution_policy ->> 'oneClickRestTransfer')::boolean, false)
                  and (
                    x.position_assignment_id = :actingAssignmentId
                    or (
                      coalesce((i.execution_policy ->> 'sharedPerOrg')::boolean, false)
                      and jsonb_exists(coalesce(i.execution_policy -> 'allowedPositionCodes', '[]'::jsonb),
                                       :actingPositionCode)
                    )
                  )
                  and (
                    x.id = :expectationId
                    or x.available_at > :currentAvailableAt
                    or (x.available_at = :currentAvailableAt and i.sort_order >= :currentSortOrder)
                  )
                """ : " and x.id = :expectationId ";
        return jdbc.query("""
                select x.id, x.row_version,
                       delegation.id as active_delegation_id,
                       delegation.delegate_assignment_id as active_delegate_assignment_id,
                       coalesce(delegation.owner_resting, false) as active_owner_resting
                from work_expectation x
                join work_package_item i
                  on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                left join work_expectation_delegation delegation
                  on delegation.tenant_id = x.tenant_id
                 and delegation.work_expectation_id = x.id
                 and delegation.status = 'ACTIVE'
                where x.tenant_id = :tenantId
                  and x.status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS', 'MISSED')
                """ + scope + """
                  and not exists (
                    select 1 from work_record submitted
                    where submitted.tenant_id = x.tenant_id
                      and submitted.work_expectation_id = x.id
                      and submitted.status in ('SUBMITTED', 'APPROVED')
                  )
                order by x.available_at, i.sort_order, x.due_at, x.id
                for update of x
                """, params, (rs, rowNum) -> new DelegationTarget(
                        rs.getObject("id", UUID.class),
                        rs.getLong("row_version"),
                        rs.getObject("active_delegation_id", UUID.class),
                        rs.getObject("active_delegate_assignment_id", UUID.class),
                        rs.getBoolean("active_owner_resting")));
    }

    @Transactional
    public Map<String, Object> revokeDelegation(UUID expectationId, long expectedVersion) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        ExpectationAccess expectation = expectationAccess(principal, expectationId);
        requireDelegationOwner(principal, expectation);
        if (expectation.rowVersion() != expectedVersion) {
            throw new IllegalArgumentException("工作事项版本已变化，请刷新后重试");
        }
        int changed = jdbc.update("""
                update work_expectation_delegation
                set status = 'REVOKED', revoked_by_account_id = :actorId,
                    revoked_at = now(), updated_at = now()
                where tenant_id = :tenantId and work_expectation_id = :expectationId
                  and status = 'ACTIVE'
                """, base(principal).addValue("expectationId", expectationId)
                .addValue("actorId", principal.actorId()));
        if (changed != 1) throw new IllegalArgumentException("该事项当前没有有效转交");
        jdbc.update("""
                update work_expectation set row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and id = :expectationId
                """, base(principal).addValue("expectationId", expectationId));
        auditWriter.record("WORK_EXPECTATION_DELEGATION_REVOKED", "WORK_EXPECTATION", expectationId,
                "{\"expectedVersion\":" + expectedVersion + "}");
        return response("id", expectationId, "status", "REVOKED", "rowVersion", expectedVersion + 1);
    }

    private Map<String, Object> generateExpectationsInternal(
            TenantPrincipal principal,
            WorkPackageModels.GenerateExpectations request
    ) {
        String periodType = request.periodType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SHIFT", "DAY", "WEEK", "MONTH", "QUARTER").contains(periodType)) {
            throw new IllegalArgumentException("期望生成只支持班次、日、周、月或季度周期");
        }
        requireActiveAssignment(principal, request.positionAssignmentId(), request.businessDate());
        DutyWindow dutyWindow = null;
        if (request.dutyPeriodId() != null) {
            dutyWindow = jdbc.queryForObject("""
                    select planned_start_at, planned_end_at, period_type, position_assignment_id,
                           target_org_unit_id, business_date, status
                    from work_duty_period
                    where tenant_id = :tenantId and id = :dutyPeriodId
                    """, base(principal).addValue("dutyPeriodId", request.dutyPeriodId()),
                    (rs, rowNum) -> new DutyWindow(
                            rs.getObject("planned_start_at", OffsetDateTime.class),
                            rs.getObject("planned_end_at", OffsetDateTime.class),
                            rs.getString("period_type"),
                            rs.getObject("position_assignment_id", UUID.class),
                            rs.getObject("target_org_unit_id", UUID.class),
                            rs.getObject("business_date", LocalDate.class),
                            rs.getString("status")));
            if (!periodType.equals(dutyWindow.periodType())
                    || !request.positionAssignmentId().equals(dutyWindow.assignmentId())
                    || !request.targetOrgUnitId().equals(dutyWindow.targetOrgUnitId())
                    || !request.businessDate().equals(dutyWindow.businessDate())
                    || "CANCELLED".equals(dutyWindow.status())) {
                throw new IllegalArgumentException("班次周期与期望生成上下文不匹配");
            }
        } else if ("SHIFT".equals(periodType)) {
            throw new IllegalArgumentException("SHIFT期望必须关联实际班次周期");
        }

        List<GenerationCandidate> candidates = jdbc.query("""
                select i.id as item_id, i.item_code, i.period_type, i.timezone_mode, i.fixed_timezone,
                       i.work_window_start, i.due_local_time, i.waiver_allowed,
                       i.execution_policy::text as execution_policy,
                       i.applicability_policy::text as applicability_policy,
                       to_jsonb(h)::text as hotel_profile,
                       a.id as allocation_id, greatest(a.valid_from, v.effective_from::date) as eligible_from
                from work_package_allocation a
                join work_package_version v on v.tenant_id = a.tenant_id and v.id = a.work_package_version_id
                join work_package_item i on i.tenant_id = v.tenant_id and i.work_package_version_id = v.id
                left join lateral (
                    select hotel.id
                    from org_unit_closure link
                    join org_unit hotel
                      on hotel.tenant_id = link.tenant_id
                     and hotel.id = link.ancestor_id and hotel.unit_type = 'HOTEL'
                    where link.tenant_id = a.tenant_id
                      and link.descendant_id = a.target_org_unit_id
                    order by link.depth
                    limit 1
                ) hotel_context on true
                left join hotel_profile h
                  on h.tenant_id = a.tenant_id
                 and h.org_unit_id = coalesce(hotel_context.id, a.target_org_unit_id)
                where a.tenant_id = :tenantId and a.position_assignment_id = :assignmentId
                  and a.target_org_unit_id = :targetOrgUnitId and a.status = 'ACTIVE'
                  and a.valid_from <= :businessDate and (a.valid_to is null or a.valid_to >= :businessDate)
                  and v.lifecycle_status = 'PUBLISHED'
                  and v.effective_from::date <= :businessDate
                  and (v.effective_to is null or v.effective_to::date >= :businessDate)
                  and i.period_type = :periodType
                  and i.item_code not in ('HKA_GUEST_SERVICE_EVENT','HKA_LOST_PROPERTY')
                  and coalesce((i.execution_policy ->> 'enabled')::boolean, true)
                  and (cardinality(i.weekdays) = 0
                       or extract(isodow from cast(:businessDate as date))::smallint = any(i.weekdays))
                  and (
                    i.holiday_policy <> 'SKIP'
                    or jsonb_exists(coalesce(i.applicability_policy -> 'workdayOverrides', '[]'::jsonb), cast(:businessDate as text))
                    or not jsonb_exists(coalesce(i.applicability_policy -> 'holidayDates', '[]'::jsonb), cast(:businessDate as text))
                  )
                order by i.sort_order, i.item_code
                """, base(principal)
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId())
                .addValue("businessDate", request.businessDate())
                .addValue("periodType", periodType),
                (rs, rowNum) -> new GenerationCandidate(
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getObject("allocation_id", UUID.class),
                        rs.getString("timezone_mode"),
                        rs.getString("fixed_timezone"),
                        rs.getObject("work_window_start", LocalTime.class),
                        rs.getObject("due_local_time", LocalTime.class),
                        rs.getBoolean("waiver_allowed"),
                        rs.getString("execution_policy"),
                        rs.getString("applicability_policy"),
                        rs.getString("hotel_profile"), rs.getObject("eligible_from", LocalDate.class)));

        String tenantTimezone = jdbc.queryForObject(
                "select timezone from tenant where id = :tenantId",
                base(principal), String.class);
        int created = 0;
        int sharedReused = 0;
        List<UUID> createdIds = new ArrayList<>();
        List<UUID> sharedExistingIds = new ArrayList<>();
        List<Map<String, Object>> exclusions = new ArrayList<>();
        for (GenerationCandidate candidate : candidates) {
            JsonNode executionPolicy = readObjectPolicy(candidate.executionPolicy(), "执行规则");
            if (executionPolicy.hasNonNull("breakfastStages")
                    && !executionPolicy.path("breakfastStages").path("scheduleConfirmed").asBoolean(false)) {
                exclusions.add(response("itemId", candidate.itemId(), "reason", "BREAKFAST_HOURS_UNCONFIRMED"));
                continue;
            }
            ApplicabilityPolicyEvaluator.Result applicability = ApplicabilityPolicyEvaluator.evaluate(
                    readObjectPolicy(candidate.applicabilityPolicy(), "适用规则"),
                    readNullableObject(candidate.hotelProfile(), "酒店档案"));
            if (!applicability.applicable()) {
                exclusions.add(response("itemId", candidate.itemId(), "reason", applicability.reason()));
                continue;
            }
            ZoneId zone = zone(candidate, tenantTimezone);
            OffsetDateTime availableAt;
            OffsetDateTime dueAt;
            String periodKey;
            LocalDate periodStart = request.businessDate();
            LocalDate periodEnd = request.businessDate();
            DutyWindow itemDuty = dutyWindow;
            UUID itemDutyId = request.dutyPeriodId();
            if (itemDuty == null && executionPolicy.hasNonNull("shiftTiming")) {
                var shifts = jdbc.query("""
                        select id, planned_start_at, planned_end_at from work_duty_period
                        where tenant_id=:tenantId and position_assignment_id=:assignmentId
                          and target_org_unit_id=:targetOrgUnitId and business_date=:businessDate
                          and period_type='SHIFT' and status<>'CANCELLED'
                        order by planned_start_at limit 2
                        """, base(principal).addValue("assignmentId", request.positionAssignmentId())
                        .addValue("targetOrgUnitId", request.targetOrgUnitId())
                        .addValue("businessDate", request.businessDate()),
                        (rs, row) -> new Object[]{rs.getObject("id", UUID.class),
                                rs.getObject("planned_start_at", OffsetDateTime.class),
                                rs.getObject("planned_end_at", OffsetDateTime.class)});
                if (shifts.size() != 1) {
                    exclusions.add(response("itemId", candidate.itemId(), "reason", "ACTUAL_SHIFT_UNRESOLVED"));
                    continue;
                }
                Object[] shift = shifts.getFirst();
                itemDutyId = (UUID) shift[0];
                itemDuty = new DutyWindow((OffsetDateTime) shift[1], (OffsetDateTime) shift[2],
                        "SHIFT", request.positionAssignmentId(), request.targetOrgUnitId(), request.businessDate(), "PLANNED");
            }
            if (itemDuty != null) {
                ShiftTimingResolver.Window window = ShiftTimingResolver.resolve(
                        itemDuty.start(), itemDuty.end(), candidate.dueLocalTime(), zone,
                        executionPolicy);
                availableAt = window.availableAt();
                dueAt = window.dueAt();
                periodKey = dutyWindow == null ? "DAY:" + request.businessDate() : "SHIFT:" + request.dutyPeriodId();
            } else if (CycleSchedule.configured(periodType, executionPolicy)) {
                CycleSchedule.Window window = CycleSchedule.resolve(periodType, request.businessDate(),
                        candidate.dueLocalTime(), zone, executionPolicy);
                // Do not invent overdue work from before a template/assignment was first enabled.
                if (window.dueAt().isBefore(candidate.eligibleFrom().atStartOfDay(zone).toOffsetDateTime())) continue;
                periodStart = window.start();
                periodEnd = window.end();
                availableAt = window.availableAt().isBefore(candidate.eligibleFrom().atStartOfDay(zone).toOffsetDateTime())
                        ? candidate.eligibleFrom().atStartOfDay(zone).toOffsetDateTime() : window.availableAt();
                dueAt = window.dueAt();
                periodKey = window.key();
            } else {
                if (Set.of("MONTH", "QUARTER").contains(periodType))
                    throw new IllegalArgumentException("月清、季清必须配置周期截止规则");
                DayTimingResolver.Window window = DayTimingResolver.resolve(request.businessDate(),
                        candidate.windowStart(), candidate.dueLocalTime(), zone, executionPolicy);
                availableAt = window.availableAt();
                dueAt = window.dueAt();
                periodKey = "DAY".equals(periodType)
                        ? "DAY:" + request.businessDate()
                        : weekKey(request.businessDate());
            }
            String sharedKey = sharedKey(executionPolicy);
            if (sharedKey == null) {
                var generationParams = base(principal).addValue("assignmentId", request.positionAssignmentId())
                        .addValue("targetOrgUnitId", request.targetOrgUnitId()).addValue("periodKey", periodKey)
                        .addValue("itemId", candidate.itemId());
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 71))",
                        generationParams.addValue("key", principal.tenantId() + ":" + request.positionAssignmentId()
                                + ":" + request.targetOrgUnitId() + ":" + periodKey), rs -> null);
                Boolean existing = jdbc.queryForObject("""
                        select exists (
                          select 1 from work_expectation x
                          join work_package_item old_item on old_item.tenant_id=x.tenant_id and old_item.id=x.work_package_item_id
                          join work_package_version old_version on old_version.tenant_id=old_item.tenant_id and old_version.id=old_item.work_package_version_id
                          join work_package_item new_item on new_item.tenant_id=x.tenant_id and new_item.id=:itemId
                          join work_package_version new_version on new_version.tenant_id=new_item.tenant_id and new_version.id=new_item.work_package_version_id
                          where x.tenant_id=:tenantId and x.position_assignment_id=:assignmentId
                            and x.target_org_unit_id=:targetOrgUnitId and x.period_key=:periodKey and x.status<>'CANCELLED'
                            and old_item.item_code=new_item.item_code
                            and old_version.work_package_definition_id=new_version.work_package_definition_id)
                        """, generationParams, Boolean.class);
                if (Boolean.TRUE.equals(existing)) continue;
            }
            if (sharedKey != null) {
                String lockKey = principal.tenantId() + ":" + request.targetOrgUnitId() + ":"
                        + request.businessDate() + ":" + periodKey + ":" + sharedKey;
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(:lockKey, 56))",
                        new MapSqlParameterSource("lockKey", lockKey), resultSet -> null);
                List<UUID> existingShared = jdbc.queryForList("""
                        select expectation.id
                        from work_expectation expectation
                        join work_package_item existing_item
                          on existing_item.tenant_id = expectation.tenant_id
                         and existing_item.id = expectation.work_package_item_id
                        where expectation.tenant_id = :tenantId
                          and expectation.target_org_unit_id = :targetOrgUnitId
                          and expectation.business_date = :businessDate
                          and expectation.period_key = :periodKey
                          and expectation.status <> 'CANCELLED'
                          and existing_item.execution_policy ->> 'sharedKey' = :sharedKey
                        order by expectation.created_at
                        limit 1
                        """, base(principal)
                        .addValue("targetOrgUnitId", request.targetOrgUnitId())
                        .addValue("businessDate", request.businessDate())
                        .addValue("periodKey", periodKey)
                        .addValue("sharedKey", sharedKey), UUID.class);
                if (!existingShared.isEmpty()) {
                    sharedReused++;
                    sharedExistingIds.add(existingShared.getFirst());
                    continue;
                }
            }
            String initialStatus = OffsetDateTime.now().isBefore(availableAt) ? "PLANNED" : "AVAILABLE";
            UUID expectationId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    insert into work_expectation
                        (id, tenant_id, work_package_item_id, work_package_allocation_id,
                         position_assignment_id, duty_period_id, target_org_unit_id, business_date,
                         period_key, available_at, due_at, status, waiver_allowed, period_start, period_end)
                    values
                        (:id, :tenantId, :itemId, :allocationId, :assignmentId, :dutyPeriodId,
                         :targetOrgUnitId, :businessDate, :periodKey, :availableAt, :dueAt, :status,
                         :waiverAllowed, :periodStart, :periodEnd)
                    on conflict (tenant_id, work_package_item_id, position_assignment_id,
                                 target_org_unit_id, period_key) do nothing
                    """, base(principal)
                    .addValue("id", expectationId)
                    .addValue("itemId", candidate.itemId())
                    .addValue("allocationId", candidate.allocationId())
                    .addValue("assignmentId", request.positionAssignmentId())
                    .addValue("dutyPeriodId", itemDutyId)
                    .addValue("targetOrgUnitId", request.targetOrgUnitId())
                    .addValue("businessDate", request.businessDate())
                    .addValue("periodKey", periodKey)
                    .addValue("periodStart", periodStart).addValue("periodEnd", periodEnd)
                    .addValue("availableAt", availableAt)
                    .addValue("dueAt", dueAt)
                    .addValue("status", initialStatus)
                    .addValue("waiverAllowed", candidate.waiverAllowed()));
            if (inserted == 1) {
                created++;
                createdIds.add(expectationId);
                auditWriter.emit("WORK_EXPECTATION", expectationId, "WorkExpectationCreated",
                        "{\"workExpectationId\":\"" + expectationId + "\",\"positionAssignmentId\":\""
                                + request.positionAssignmentId() + "\",\"orgUnitId\":\""
                                + request.targetOrgUnitId() + "\"}");
            }
        }
        int applicableCount = candidates.size() - exclusions.size();
        return response("evaluatedCount", candidates.size(), "candidateCount", applicableCount,
                "excludedCount", exclusions.size(), "exclusions", exclusions,
                "createdCount", created, "existingCount", applicableCount - created,
                "sharedReusedCount", sharedReused, "sharedExistingIds", sharedExistingIds,
                "createdIds", createdIds);
    }

    private void insertScope(TenantPrincipal principal, UUID versionId, WorkPackageModels.Scope scope) {
        String scopeType = scope.scopeType().trim().toUpperCase(Locale.ROOT);
        UUID brandId = null;
        UUID orgUnitId = null;
        UUID positionId = null;
        switch (scopeType) {
            case "TENANT" -> {
                if (!principal.hasTenantScope()) {
                    throw new AccessDeniedException("仅集团级管理员可以配置租户范围");
                }
            }
            case "BRAND" -> {
                if (scope.brandId() == null) {
                    throw new IllegalArgumentException("BRAND范围必须提供brandId");
                }
                requireOwned("brand", principal, scope.brandId());
                brandId = scope.brandId();
            }
            case "ORG_UNIT", "ORG_TREE" -> {
                if (scope.orgUnitId() == null) {
                    throw new IllegalArgumentException(scopeType + "范围必须提供orgUnitId");
                }
                requireOwned("org_unit", principal, scope.orgUnitId());
                accessPolicy.requireOrgScope(scope.orgUnitId());
                orgUnitId = scope.orgUnitId();
            }
            case "POSITION" -> {
                if (scope.positionId() == null) {
                    throw new IllegalArgumentException("POSITION范围必须提供positionId");
                }
                requireOwned("position_definition", principal, scope.positionId());
                positionId = scope.positionId();
            }
            default -> throw new IllegalArgumentException("不支持的工作包范围类型: " + scopeType);
        }
        jdbc.update("""
                insert into work_package_scope
                    (id, tenant_id, work_package_version_id, scope_type, brand_id, org_unit_id, position_id)
                values
                    (:id, :tenantId, :versionId, :scopeType, :brandId, :orgUnitId, :positionId)
                """, base(principal)
                .addValue("id", UUID.randomUUID())
                .addValue("versionId", versionId)
                .addValue("scopeType", scopeType)
                .addValue("brandId", brandId)
                .addValue("orgUnitId", orgUnitId)
                .addValue("positionId", positionId));
    }

    private void insertItem(
            TenantPrincipal principal,
            UUID versionId,
            WorkPackageModels.Item item,
            String itemCode
    ) {
        requireOwned("form_version", principal, item.formVersionId());
        String periodType = item.periodType().trim().toUpperCase(Locale.ROOT);
        if (!PERIOD_TYPES.contains(periodType)) {
            throw new IllegalArgumentException("不支持的工作周期: " + periodType);
        }
        if (Set.of("MONTH", "QUARTER").contains(periodType) && !CycleSchedule.configured(periodType, item.executionPolicy()))
            throw new IllegalArgumentException("月清、季清必须配置周期截止规则");
        if (item.executionPolicy()!=null && CycleSchedule.configured(periodType,item.executionPolicy()))
            CycleSchedule.resolve(periodType,LocalDate.of(2026,1,1),LocalTime.NOON,ZoneId.of("Asia/Shanghai"),item.executionPolicy());
        String weekdays = toPostgresSmallIntArray(item.weekdays());
        UUID itemId = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_item
                    (id, tenant_id, work_package_version_id, item_code, name, description, item_type,
                     form_version_id, sort_order, required, period_type, timezone_mode, fixed_timezone,
                     work_window_start, work_window_end, due_local_time, grace_minutes, weekdays,
                     day_of_month, holiday_policy, waiver_allowed, target_granularity, review_mode,
                     submission_policy, reminder_policy, report_policy, applicability_policy, execution_policy)
                values
                    (:id, :tenantId, :versionId, :itemCode, :name, :description, :itemType,
                     :formVersionId, :sortOrder, :required, :periodType, :timezoneMode, :fixedTimezone,
                     :workWindowStart, :workWindowEnd, :dueLocalTime, :graceMinutes,
                     cast(:weekdays as smallint[]), :dayOfMonth, :holidayPolicy, :waiverAllowed,
                     :targetGranularity, :reviewMode, cast(:submissionPolicy as jsonb),
                     cast(:reminderPolicy as jsonb), cast(:reportPolicy as jsonb),
                     cast(:applicabilityPolicy as jsonb), cast(:executionPolicy as jsonb))
                """, base(principal)
                .addValue("id", itemId)
                .addValue("versionId", versionId)
                .addValue("itemCode", itemCode)
                .addValue("name", item.name().trim())
                .addValue("description", trimToNull(item.description()))
                .addValue("itemType", item.itemType().trim().toUpperCase(Locale.ROOT))
                .addValue("formVersionId", item.formVersionId())
                .addValue("sortOrder", item.sortOrder() == null ? 0 : item.sortOrder())
                .addValue("required", item.required() == null || item.required())
                .addValue("periodType", periodType)
                .addValue("timezoneMode", defaultUpper(item.timezoneMode(), "HOTEL"))
                .addValue("fixedTimezone", trimToNull(item.fixedTimezone()))
                .addValue("workWindowStart", item.workWindowStart())
                .addValue("workWindowEnd", item.workWindowEnd())
                .addValue("dueLocalTime", item.dueLocalTime())
                .addValue("graceMinutes", item.graceMinutes() == null ? 0 : item.graceMinutes())
                .addValue("weekdays", weekdays)
                .addValue("dayOfMonth", item.dayOfMonth())
                .addValue("holidayPolicy", defaultUpper(item.holidayPolicy(), "INCLUDE"))
                .addValue("waiverAllowed", item.waiverAllowed() != null && item.waiverAllowed())
                .addValue("targetGranularity", defaultUpper(item.targetGranularity(), "ASSIGNMENT_ORG"))
                .addValue("reviewMode", defaultUpper(item.reviewMode(), "MANUAL"))
                .addValue("submissionPolicy", normalizeSubmissionPolicy(item.submissionPolicy()))
                .addValue("reminderPolicy", normalizeObjectPolicy(item.reminderPolicy(), "工作提醒策略"))
                .addValue("reportPolicy", normalizeObjectPolicy(item.reportPolicy(), "日报汇总策略"))
                .addValue("applicabilityPolicy", normalizeApplicabilityPolicy(item.applicabilityPolicy()))
                .addValue("executionPolicy", normalizeExecutionPolicy(item.executionPolicy())));

        for (WorkPackageModels.StandardLink standard : nullSafe(item.standards())) {
            requireOwned("standard_version", principal, standard.standardVersionId());
            jdbc.update("""
                    insert into work_package_item_standard
                        (id, tenant_id, work_package_item_id, standard_version_id, usage_type, weight)
                    values
                        (:id, :tenantId, :itemId, :standardVersionId, :usageType, :weight)
                    """, base(principal)
                    .addValue("id", UUID.randomUUID())
                    .addValue("itemId", itemId)
                    .addValue("standardVersionId", standard.standardVersionId())
                    .addValue("usageType", standard.usageType().trim().toUpperCase(Locale.ROOT))
                    .addValue("weight", standard.weight() == null ? BigDecimal.ONE : standard.weight()));
        }
        for (WorkPackageModels.Responsibility responsibility : item.responsibilities()) {
            if (responsibility.positionId() != null) {
                requireOwned("position_definition", principal, responsibility.positionId());
            }
            jdbc.update("""
                    insert into work_package_item_responsibility
                        (id, tenant_id, work_package_item_id, participant_type, resolver_type,
                         position_id, scope_strategy, escalation_level)
                    values
                        (:id, :tenantId, :itemId, :participantType, :resolverType,
                         :positionId, :scopeStrategy, :escalationLevel)
                    """, base(principal)
                    .addValue("id", UUID.randomUUID())
                    .addValue("itemId", itemId)
                    .addValue("participantType", responsibility.participantType().trim().toUpperCase(Locale.ROOT))
                    .addValue("resolverType", responsibility.resolverType().trim().toUpperCase(Locale.ROOT))
                    .addValue("positionId", responsibility.positionId())
                    .addValue("scopeStrategy", defaultUpper(responsibility.scopeStrategy(), "TARGET_ORG"))
                    .addValue("escalationLevel", responsibility.escalationLevel() == null ? 0 : responsibility.escalationLevel()));
        }
    }

    private List<String> validationIssues(TenantPrincipal principal, UUID versionId) {
        MapSqlParameterSource params = base(principal).addValue("versionId", versionId);
        List<String> issues = new ArrayList<>();
        Integer scopeCount = jdbc.queryForObject("""
                select count(*) from work_package_scope
                where tenant_id = :tenantId and work_package_version_id = :versionId
                """, params, Integer.class);
        if (scopeCount == null || scopeCount == 0) {
            issues.add("至少配置一个适用范围");
        }
        Integer itemCount = jdbc.queryForObject("""
                select count(*) from work_package_item
                where tenant_id = :tenantId and work_package_version_id = :versionId
                """, params, Integer.class);
        if (itemCount == null || itemCount == 0) {
            issues.add("至少配置一个工作条目");
        }
        List<String> badForms = jdbc.queryForList("""
                select i.item_code
                from work_package_item i
                left join form_version f on f.tenant_id = i.tenant_id and f.id = i.form_version_id
                where i.tenant_id = :tenantId and i.work_package_version_id = :versionId
                  and (f.id is null or f.lifecycle_status <> 'PUBLISHED')
                order by i.item_code
                """, params, String.class);
        if (!badForms.isEmpty()) {
            issues.add("条目引用的表单版本未发布: " + String.join(",", badForms));
        }
        List<String> badStandards = jdbc.queryForList("""
                select distinct i.item_code
                from work_package_item i
                join work_package_item_standard s on s.tenant_id = i.tenant_id and s.work_package_item_id = i.id
                left join standard_version v on v.tenant_id = s.tenant_id and v.id = s.standard_version_id
                where i.tenant_id = :tenantId and i.work_package_version_id = :versionId
                  and (v.id is null or v.lifecycle_status <> 'PUBLISHED')
                order by i.item_code
                """, params, String.class);
        if (!badStandards.isEmpty()) {
            issues.add("条目引用的标准版本未发布: " + String.join(",", badStandards));
        }
        List<String> missingExecutor = jdbc.queryForList("""
                select i.item_code
                from work_package_item i
                where i.tenant_id = :tenantId and i.work_package_version_id = :versionId
                  and not exists (
                      select 1 from work_package_item_responsibility r
                      where r.tenant_id = i.tenant_id and r.work_package_item_id = i.id
                        and r.participant_type = 'EXECUTOR'
                  )
                order by i.item_code
                """, params, String.class);
        if (!missingExecutor.isEmpty()) {
            issues.add("条目缺少执行责任: " + String.join(",", missingExecutor));
        }
        List<String> missingAcceptor = jdbc.queryForList("""
                select i.item_code
                from work_package_item i
                where i.tenant_id = :tenantId and i.work_package_version_id = :versionId
                  and i.review_mode <> 'NONE'
                  and not exists (
                      select 1 from work_package_item_responsibility r
                      where r.tenant_id = i.tenant_id and r.work_package_item_id = i.id
                        and (r.participant_type = 'ACCEPTOR' or (
                          i.execution_policy->>'workflowRevision'='THREE_ROLE_V88' and r.participant_type='REVIEWER'))
                  )
                order by i.item_code
                """, params, String.class);
        if (!missingAcceptor.isEmpty()) {
            issues.add("需要复核的条目缺少验收责任: " + String.join(",", missingAcceptor));
        }
        return issues;
    }

    private Map<String, Object> versionConfiguration(TenantPrincipal principal, UUID versionId) {
        MapSqlParameterSource params = base(principal).addValue("versionId", versionId);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("scopes", jdbc.queryForList("""
                select id, scope_type, brand_id, org_unit_id, position_id
                from work_package_scope
                where tenant_id = :tenantId and work_package_version_id = :versionId
                order by scope_type, id
                """, params));
        List<Map<String, Object>> items = jdbc.queryForList("""
                select * from work_package_item
                where tenant_id = :tenantId and work_package_version_id = :versionId
                order by sort_order, item_code
                """, params);
        for (Map<String, Object> item : items) {
            normalizeSqlArray(item, "weekdays");
            normalizeJsonObject(item, "submission_policy");
            normalizeJsonObject(item, "reminder_policy");
            normalizeJsonObject(item, "report_policy");
            normalizeJsonObject(item, "applicability_policy");
            normalizeJsonObject(item, "execution_policy");
            UUID itemId = (UUID) item.get("id");
            item.put("standards", jdbc.queryForList("""
                    select standard_version_id, usage_type, weight
                    from work_package_item_standard
                    where tenant_id = :tenantId and work_package_item_id = :itemId
                    order by usage_type, standard_version_id
                    """, base(principal).addValue("itemId", itemId)));
            item.put("responsibilities", jdbc.queryForList("""
                    select participant_type, resolver_type, position_id, scope_strategy, escalation_level
                    from work_package_item_responsibility
                    where tenant_id = :tenantId and work_package_item_id = :itemId
                    order by participant_type, escalation_level
                    """, base(principal).addValue("itemId", itemId)));
        }
        configuration.put("items", items);
        return configuration;
    }

    private void normalizeJsonObject(Map<String, Object> row, String column) {
        Object raw = row.get(column);
        if (raw == null || raw instanceof JsonNode) {
            return;
        }
        try {
            JsonNode normalized = objectMapper.readTree(raw.toString());
            // Older API responses exposed the PostgreSQL driver's {type,value,null}
            // wrapper. A browser save could persist that wrapper back into JSONB.
            // Unwrap it defensively and retain any newer fields written beside it.
            while (normalized.isObject()
                    && "jsonb".equals(normalized.path("type").asText())
                    && normalized.path("value").isTextual()) {
                JsonNode inner = objectMapper.readTree(normalized.path("value").asText());
                ObjectNode overlay = ((ObjectNode) normalized).deepCopy();
                overlay.remove(List.of("type", "value", "null"));
                if (inner.isObject()) {
                    ((ObjectNode) inner).setAll(overlay);
                    normalized = inner;
                } else {
                    normalized = overlay;
                }
            }
            row.put(column, normalized);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取工作包JSON字段: " + column, exception);
        }
    }

    private static void normalizeSqlArray(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (!(value instanceof java.sql.Array sqlArray)) {
            return;
        }
        try {
            Object array = sqlArray.getArray();
            row.put(column, array instanceof Object[] values
                    ? java.util.Arrays.asList(values)
                    : List.of());
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("无法读取数据库数组字段: " + column, exception);
        } finally {
            try {
                sqlArray.free();
            } catch (java.sql.SQLException ignored) {
                // The value has already been copied to a detached Java list.
            }
        }
    }

    private String normalizeSubmissionPolicy(JsonNode configured) {
        ObjectNode policy = JsonNodeFactory.instance.objectNode();
        policy.put("completionStatementRequired", false);
        policy.put("exceptionStatementRequired", false);
        policy.put("nextActionRequired", false);
        policy.put("attachmentRequired", false);
        policy.put("maxAttachments", 10);
        policy.put("maxFileSizeBytes", 20 * 1024 * 1024);
        policy.putArray("allowedExtensions").add("jpg").add("jpeg").add("png")
                .add("pdf").add("docx").add("xlsx");
        if (configured != null && !configured.isNull()) {
            if (!configured.isObject()) {
                throw new IllegalArgumentException("工作提交策略必须是JSON对象");
            }
            policy.setAll((ObjectNode) configured);
        }
        policy.put("completionStatementRequired", false);
        int maxAttachments = policy.path("maxAttachments").asInt(10);
        if (maxAttachments < 0 || maxAttachments > 200) {
            throw new IllegalArgumentException("单条工作记录附件数量必须在0到200之间");
        }
        long maxBytes = policy.path("maxFileSizeBytes").asLong(20L * 1024 * 1024);
        if (maxBytes < 1 || maxBytes > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("单个附件大小上限不能超过20MB");
        }
        return policy.toString();
    }

    private String normalizeObjectPolicy(JsonNode configured, String label) {
        if (configured == null || configured.isNull()) {
            return "{}";
        }
        if (!configured.isObject()) {
            throw new IllegalArgumentException(label + "必须是JSON对象");
        }
        return configured.toString();
    }

    private String normalizeExecutionPolicy(JsonNode configured) {
        String normalized = normalizeObjectPolicy(configured, "执行规则");
        JsonNode policy = readObjectPolicy(normalized, "执行规则");
        if (policy.has("requiresExceptionReview") && !policy.path("requiresExceptionReview").isBoolean())
            throw new IllegalArgumentException("异常必审开关必须是布尔值");
        if (policy.has("spotCheckRate") && (!policy.path("spotCheckRate").isIntegralNumber()
                || policy.path("spotCheckRate").asInt() < 1 || policy.path("spotCheckRate").asInt() > 100))
            throw new IllegalArgumentException("抽查比例须为1至100的整数");
        ShiftTimingResolver.validatePolicy(policy);
        DayTimingResolver.validatePolicy(policy);
        sharedKey(policy);
        return normalized;
    }

    private static String sharedKey(JsonNode executionPolicy) {
        if (!executionPolicy.path("sharedPerOrg").asBoolean(false)) return null;
        String sharedKey = executionPolicy.path("sharedKey").asText("").trim().toUpperCase(Locale.ROOT);
        if (sharedKey.isBlank()) return null;
        if (!sharedKey.matches("[A-Z][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("executionPolicy.sharedKey格式无效");
        }
        return sharedKey;
    }

    private String normalizeApplicabilityPolicy(JsonNode configured) {
        String normalized = normalizeObjectPolicy(configured, "适用规则");
        ApplicabilityPolicyEvaluator.validatePolicy(readObjectPolicy(normalized, "适用规则"));
        return normalized;
    }

    private JsonNode readObjectPolicy(String json, String label) {
        try {
            JsonNode policy = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!policy.isObject()) {
                throw new IllegalArgumentException(label + "必须是JSON对象");
            }
            return policy;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取" + label, exception);
        }
    }

    private JsonNode readNullableObject(String json, String label) {
        if (json == null || json.isBlank()) return null;
        return readObjectPolicy(json, label);
    }

    private String contentSnapshot(TenantPrincipal principal, UUID versionId) {
        return jdbc.queryForObject("""
                select jsonb_build_object(
                    'version', to_jsonb(v) - ARRAY['created_at', 'updated_at', 'published_at', 'published_by'],
                    'scopes', coalesce((
                        select jsonb_agg(to_jsonb(s) - 'created_at' order by s.scope_type, s.id)
                        from work_package_scope s
                        where s.tenant_id = v.tenant_id and s.work_package_version_id = v.id
                    ), '[]'::jsonb),
                    'items', coalesce((
                        select jsonb_agg(
                            jsonb_build_object(
                                'item', to_jsonb(i) - ARRAY['created_at', 'updated_at'],
                                'standards', coalesce((
                                    select jsonb_agg(to_jsonb(st) - 'created_at' order by st.usage_type, st.id)
                                    from work_package_item_standard st
                                    where st.tenant_id = i.tenant_id and st.work_package_item_id = i.id
                                ), '[]'::jsonb),
                                'responsibilities', coalesce((
                                    select jsonb_agg(to_jsonb(r) - 'created_at' order by r.participant_type, r.escalation_level)
                                    from work_package_item_responsibility r
                                    where r.tenant_id = i.tenant_id and r.work_package_item_id = i.id
                                ), '[]'::jsonb)
                            ) order by i.sort_order, i.item_code
                        )
                        from work_package_item i
                        where i.tenant_id = v.tenant_id and i.work_package_version_id = v.id
                    ), '[]'::jsonb)
                )::text
                from work_package_version v
                where v.tenant_id = :tenantId and v.id = :versionId
                """, base(principal).addValue("versionId", versionId), String.class);
    }

    private void requireVisible(TenantPrincipal principal, UUID workPackageId) {
        if (principal.hasTenantScope()) {
            requireOwnedDefinition(principal, workPackageId);
            return;
        }
        if (principal.orgScopes().isEmpty()) {
            throw new AccessDeniedException("工作包不在当前数据范围内");
        }
        Integer count = jdbc.queryForObject("""
                select count(*)
                from work_package_definition d
                where d.tenant_id = :tenantId and d.id = :workPackageId
                  and exists (
                      select 1 from work_package_version v
                      join work_package_allocation a
                        on a.tenant_id = v.tenant_id and a.work_package_version_id = v.id
                      join org_unit_closure c
                        on c.tenant_id = a.tenant_id and c.descendant_id = a.target_org_unit_id
                      where v.tenant_id = d.tenant_id and v.work_package_definition_id = d.id
                        and a.status = 'ACTIVE' and c.ancestor_id in (:scopeIds)
                  )
                """, base(principal)
                .addValue("workPackageId", workPackageId)
                .addValue("scopeIds", principal.orgScopes()), Integer.class);
        if (count == null || count == 0) {
            throw new AccessDeniedException("工作包不在当前数据范围内");
        }
    }

    private void requireOwnedDefinition(TenantPrincipal principal, UUID workPackageId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from work_package_definition
                where tenant_id = :tenantId and id = :workPackageId
                """, base(principal).addValue("workPackageId", workPackageId), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("工作包不存在或不属于当前租户");
        }
    }

    private void requireVersion(TenantPrincipal principal,UUID workPackageId,UUID versionId) {
        Integer count=jdbc.queryForObject("""
                select count(*) from work_package_version where tenant_id=:tenantId and id=:versionId
                  and work_package_definition_id=:workPackageId
                """,base(principal).addValue("versionId",versionId).addValue("workPackageId",workPackageId),Integer.class);
        if(count==null||count!=1)throw new IllegalArgumentException("工作包版本不存在或不属于该工作包");
    }

    private void requireDraftVersion(TenantPrincipal principal, UUID workPackageId, UUID versionId) {
        requireManageableDefinition(principal, workPackageId);
        Integer count = jdbc.queryForObject("""
                select count(*) from work_package_version
                where tenant_id = :tenantId and id = :versionId
                  and work_package_definition_id = :workPackageId and lifecycle_status = 'DRAFT'
                """, base(principal)
                .addValue("workPackageId", workPackageId)
                .addValue("versionId", versionId), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("草稿工作包版本不存在或不属于当前租户");
        }
    }

    private void requireManageableDefinition(TenantPrincipal principal, UUID workPackageId) {
        Map<String, Object> definition;
        try {
            definition = jdbc.queryForMap("""
                    select owner_org_unit_id
                    from work_package_definition
                    where tenant_id = :tenantId and id = :workPackageId
                    """, base(principal).addValue("workPackageId", workPackageId));
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("工作包不存在或不属于当前租户");
        }
        if (principal.hasTenantScope()) {
            return;
        }
        UUID ownerOrgUnitId = (UUID) definition.get("owner_org_unit_id");
        if (ownerOrgUnitId == null) {
            throw new AccessDeniedException("集团统一工作包只能由集团级管理员维护");
        }
        accessPolicy.requireOrgScope(ownerOrgUnitId);
    }

    private void requireActiveAssignment(TenantPrincipal principal, UUID assignmentId, LocalDate businessDate) {
        Integer count = jdbc.queryForObject("""
                select count(*) from employee_position_assignment
                where tenant_id = :tenantId and id = :assignmentId and status = 'ACTIVE'
                  and valid_from <= :businessDate and (valid_to is null or valid_to >= :businessDate)
                """, base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("businessDate", businessDate), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("指定任职在业务日期无效");
        }
    }

    private ExpectationAccess expectationAccess(TenantPrincipal principal, UUID expectationId) {
        return jdbc.queryForObject("""
                select x.id, x.target_org_unit_id, x.position_assignment_id, x.business_date,
                       x.available_at, x.status, x.row_version, x.waiver_allowed, i.sort_order,
                       coalesce((i.execution_policy ->> 'delegationAllowed')::boolean, false) as delegation_allowed,
                       coalesce((i.execution_policy ->> 'oneClickRestTransfer')::boolean, false) as one_click_rest_transfer
                from work_expectation x
                join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                where x.tenant_id = :tenantId and x.id = :expectationId
                """, base(principal).addValue("expectationId", expectationId),
                (rs, rowNum) -> new ExpectationAccess(
                        rs.getObject("id", UUID.class),
                        rs.getObject("target_org_unit_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("available_at", OffsetDateTime.class),
                        rs.getInt("sort_order"),
                        rs.getString("status"),
                        rs.getLong("row_version"),
                        rs.getBoolean("waiver_allowed"),
                        rs.getBoolean("delegation_allowed"),
                        rs.getBoolean("one_click_rest_transfer")));
    }

    private DelegationActor requireDelegationOwner(
            TenantPrincipal principal,
            ExpectationAccess expectation
    ) {
        UUID actingAssignmentId = principal.businessActorAssignmentId();
        if (actingAssignmentId == null || !principal.assignmentIds().contains(actingAssignmentId)) {
            throw new AccessDeniedException("请选择当前有效业务任职后再转交工作");
        }
        List<DelegationActor> authorized = jdbc.query("""
                select acting.id, position.code as position_code
                from employee_position_assignment acting
                join position_definition position
                  on position.tenant_id = acting.tenant_id and position.id = acting.position_id
                join work_expectation expectation
                  on expectation.tenant_id = acting.tenant_id and expectation.id = :expectationId
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id
                 and item.id = expectation.work_package_item_id
                where acting.tenant_id = :tenantId and acting.id = :actingAssignmentId
                  and exists (
                    select 1
                    from org_unit_closure acting_scope
                    where acting_scope.tenant_id = expectation.tenant_id
                      and acting_scope.ancestor_id = expectation.target_org_unit_id
                      and acting_scope.descendant_id = acting.org_unit_id
                  )
                  and acting.status = 'ACTIVE'
                  and acting.valid_from <= expectation.business_date
                  and (acting.valid_to is null or acting.valid_to >= expectation.business_date)
                  and (
                    expectation.position_assignment_id = acting.id
                    or (
                      coalesce((item.execution_policy ->> 'sharedPerOrg')::boolean, false)
                      and jsonb_exists(
                        coalesce(item.execution_policy -> 'allowedPositionCodes', '[]'::jsonb),
                        position.code
                      )
                    )
                  )
                """, base(principal)
                .addValue("expectationId", expectation.id())
                .addValue("actingAssignmentId", actingAssignmentId),
                (rs, rowNum) -> new DelegationActor(
                        rs.getObject("id", UUID.class), rs.getString("position_code")));
        if (authorized.size() != 1) {
            throw new AccessDeniedException("仅事项所属任职或共享事项的可执行任职可以转交或撤销转交");
        }
        return authorized.getFirst();
    }

    private String orgVisibility(TenantPrincipal principal, MapSqlParameterSource params, String orgExpression) {
        if (principal.hasTenantScope()) {
            return "";
        }
        if (principal.orgScopes().isEmpty()) {
            return " and 1 = 0 ";
        }
        params.addValue("visibleOrgScopes", principal.orgScopes());
        return " and exists (select 1 from org_unit_closure visible_scope "
                + "where visible_scope.tenant_id = :tenantId and visible_scope.descendant_id = " + orgExpression
                + " and visible_scope.ancestor_id in (:visibleOrgScopes)) ";
    }

    private void requireOwned(String table, TenantPrincipal principal, UUID id) {
        if (!Set.of("position_definition", "org_unit", "brand", "form_version", "standard_version").contains(table)) {
            throw new IllegalArgumentException("不允许的实体类型");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("资源不存在或不属于当前租户");
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

    private static ZoneId zone(GenerationCandidate candidate, String tenantTimezone) {
        String zoneName = "FIXED".equals(candidate.timezoneMode())
                ? candidate.fixedTimezone() : tenantTimezone;
        try {
            return ZoneId.of(zoneName);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("无效的工作包时区: " + zoneName);
        }
    }

    private static String weekKey(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        return "WEEK:" + date.get(fields.weekBasedYear()) + "-W" + String.format("%02d", date.get(fields.weekOfWeekBasedYear()));
    }

    private static String toPostgresSmallIntArray(List<Integer> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return "{}";
        }
        for (Integer day : weekdays) {
            if (day == null || day < 1 || day > 7) {
                throw new IllegalArgumentException("weekdays只能包含1到7");
            }
        }
        return "{" + String.join(",", weekdays.stream().map(String::valueOf).toList()) + "}";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算工作包内容摘要", exception);
        }
    }

    private static String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultUpper(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, Object> response(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record WorkbenchMetricRow(
            LocalDate businessDate,
            OffsetDateTime dueAt,
            String status,
            OffsetDateTime firstSubmittedAt,
            UUID hotelOrgUnitId,
            String hotelName,
            UUID departmentOrgUnitId,
            String departmentName,
            UUID assignmentId,
            UUID employeeId,
            String employeeName,
            String positionName,
            UUID targetOrgUnitId,
            String targetOrgName
    ) {
    }

    private static final class WorkbenchMetric {
        private static final Set<String> COMPLETED_STATUSES = Set.of("SUBMITTED", "SATISFIED");
        private static final Set<String> OVERDUE_STATUSES = Set.of("MISSED", "FAILED");
        private int expected;
        private int completed;
        private int onTimeCompleted;
        private int lateSubmitted;
        private int pending;
        private int overdue;

        private void add(WorkbenchMetricRow row, OffsetDateTime cutoff) {
            expected++;
            boolean hasSubmission = row.firstSubmittedAt() != null;
            boolean isCompleted = hasSubmission || COMPLETED_STATUSES.contains(row.status());
            if (isCompleted) {
                completed++;
                if (hasSubmission && !row.firstSubmittedAt().isAfter(row.dueAt())) {
                    onTimeCompleted++;
                } else if (hasSubmission) {
                    lateSubmitted++;
                }
                return;
            }
            if (OVERDUE_STATUSES.contains(row.status()) || !row.dueAt().isAfter(cutoff)) {
                overdue++;
            } else {
                pending++;
            }
        }

        private Map<String, Object> toResponse() {
            int completionRate = expected == 0 ? 0 : (int) Math.round(onTimeCompleted * 100.0 / expected);
            return response(
                    "expected", expected,
                    "completed", completed,
                    "onTimeCompleted", onTimeCompleted,
                    "lateSubmitted", lateSubmitted,
                    "pending", pending,
                    "overdue", overdue,
                    "completionRate", completionRate);
        }
    }

    private static final class HotelWorkbenchMetric {
        private final UUID id;
        private final String name;
        private final WorkbenchMetric today = new WorkbenchMetric();
        private final WorkbenchMetric monthToDate = new WorkbenchMetric();
        private final Map<String, DepartmentWorkbenchMetric> departments = new LinkedHashMap<>();

        private HotelWorkbenchMetric(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        private String name() {
            return name;
        }

        private void add(WorkbenchMetricRow row, OffsetDateTime cutoff, boolean isToday) {
            monthToDate.add(row, cutoff);
            if (isToday) today.add(row, cutoff);
            String departmentId = row.departmentOrgUnitId() == null
                    ? id + ":hotel-management"
                    : row.departmentOrgUnitId().toString();
            String departmentName = row.departmentName() == null || row.departmentName().isBlank()
                    ? row.hotelOrgUnitId() == null ? "集团及总部工作" : "门店管理"
                    : row.departmentName();
            DepartmentWorkbenchMetric department = departments.computeIfAbsent(departmentId,
                    ignored -> new DepartmentWorkbenchMetric(departmentId, departmentName));
            department.add(row, cutoff, isToday);
        }

        private Map<String, Object> toResponse() {
            return response(
                    "id", id,
                    "name", name,
                    "today", today.toResponse(),
                    "monthToDate", monthToDate.toResponse(),
                    "departments", departments.values().stream()
                            .sorted(Comparator.comparing(DepartmentWorkbenchMetric::name))
                            .map(DepartmentWorkbenchMetric::toResponse)
                            .toList());
        }
    }

    private static final class DepartmentWorkbenchMetric {
        private final String id;
        private final String name;
        private final WorkbenchMetric today = new WorkbenchMetric();
        private final WorkbenchMetric monthToDate = new WorkbenchMetric();
        private final Map<UUID, EmployeeWorkbenchMetric> employees = new LinkedHashMap<>();

        private DepartmentWorkbenchMetric(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private String name() {
            return name;
        }

        private void add(WorkbenchMetricRow row, OffsetDateTime cutoff, boolean isToday) {
            monthToDate.add(row, cutoff);
            if (isToday) today.add(row, cutoff);
            EmployeeWorkbenchMetric employee = employees.computeIfAbsent(row.assignmentId(),
                    ignored -> new EmployeeWorkbenchMetric(
                            row.assignmentId(), row.employeeId(), row.employeeName(), row.positionName()));
            employee.add(row, cutoff, isToday);
        }

        private Map<String, Object> toResponse() {
            return response(
                    "id", id,
                    "name", name,
                    "today", today.toResponse(),
                    "monthToDate", monthToDate.toResponse(),
                    "employees", employees.values().stream()
                            .sorted(Comparator.comparing(EmployeeWorkbenchMetric::name)
                                    .thenComparing(EmployeeWorkbenchMetric::positionName))
                            .map(EmployeeWorkbenchMetric::toResponse)
                            .toList());
        }
    }

    private static final class EmployeeWorkbenchMetric {
        private final UUID assignmentId;
        private final UUID employeeId;
        private final String name;
        private final String positionName;
        private final WorkbenchMetric today = new WorkbenchMetric();
        private final WorkbenchMetric monthToDate = new WorkbenchMetric();

        private EmployeeWorkbenchMetric(UUID assignmentId, UUID employeeId, String name, String positionName) {
            this.assignmentId = assignmentId;
            this.employeeId = employeeId;
            this.name = name;
            this.positionName = positionName;
        }

        private String name() {
            return name == null ? "" : name;
        }

        private String positionName() {
            return positionName == null ? "" : positionName;
        }

        private void add(WorkbenchMetricRow row, OffsetDateTime cutoff, boolean isToday) {
            monthToDate.add(row, cutoff);
            if (isToday) today.add(row, cutoff);
        }

        private Map<String, Object> toResponse() {
            return response(
                    "id", assignmentId,
                    "assignmentId", assignmentId,
                    "employeeId", employeeId,
                    "name", name,
                    "positionName", positionName,
                    "today", today.toResponse(),
                    "monthToDate", monthToDate.toResponse());
        }
    }

    private record GenerationCandidate(
            UUID itemId,
            String itemCode,
            UUID allocationId,
            String timezoneMode,
            String fixedTimezone,
            LocalTime windowStart,
            LocalTime dueLocalTime,
            boolean waiverAllowed,
            String executionPolicy,
            String applicabilityPolicy,
            String hotelProfile,
            LocalDate eligibleFrom
    ) {
    }

    private record AllocationTarget(UUID assignmentId, UUID targetOrgUnitId, String periodType) {
    }

    private record DutyWindow(
            OffsetDateTime start,
            OffsetDateTime end,
            String periodType,
            UUID assignmentId,
            UUID targetOrgUnitId,
            LocalDate businessDate,
            String status
    ) {
    }

    private record ExpectationAccess(
            UUID id,
            UUID targetOrgUnitId,
            UUID assignmentId,
            LocalDate businessDate,
            OffsetDateTime availableAt,
            int sortOrder,
            String status,
            long rowVersion,
            boolean waiverAllowed,
            boolean delegationAllowed,
            boolean oneClickRestTransfer
    ) {
    }

    private record DelegationActor(UUID assignmentId, String positionCode) {
    }

    private record DelegationTarget(
            UUID id,
            long rowVersion,
            UUID activeDelegationId,
            UUID activeDelegateAssignmentId,
            boolean activeOwnerResting
    ) {
    }
}
