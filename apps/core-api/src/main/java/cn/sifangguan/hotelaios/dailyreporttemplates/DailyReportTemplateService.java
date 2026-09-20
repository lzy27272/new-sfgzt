package cn.sifangguan.hotelaios.dailyreporttemplates;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.BusinessEvent;
import cn.sifangguan.hotelaios.shared.events.BusinessEventPublisher;
import cn.sifangguan.hotelaios.shared.idempotency.CommandIdempotencyService;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.time.BusinessDayService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DailyReportTemplateService {
    private static final String PRODUCER = "daily-report-template-service";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final CommandIdempotencyService idempotency;
    private final AuditWriter auditWriter;
    private final BusinessEventPublisher eventPublisher;
    private final BusinessDayService businessDayService;
    private final ObjectMapper objectMapper;
    private final DailyReportTemplateSnapshotReader snapshotReader;

    public DailyReportTemplateService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            CommandIdempotencyService idempotency,
            AuditWriter auditWriter,
            BusinessEventPublisher eventPublisher,
            BusinessDayService businessDayService,
            ObjectMapper objectMapper,
            DailyReportTemplateSnapshotReader snapshotReader
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.idempotency = idempotency;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.businessDayService = businessDayService;
        this.objectMapper = objectMapper;
        this.snapshotReader = snapshotReader;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, UUID orgUnitId, UUID positionId) {
        accessPolicy.requirePermission("daily-report-template.read");
        TenantPrincipal principal = prepare();
        if (orgUnitId != null) accessPolicy.requireOrgScope(orgUnitId);
        return jdbc.queryForList("""
                select definition.id, definition.code, definition.name, definition.description,
                       definition.template_origin as "templateOrigin",
                       definition.owner_org_unit_id as "ownerOrgUnitId",
                       definition.position_id as "positionId",
                       definition.base_template_definition_id as "baseTemplateDefinitionId",
                       definition.status, definition.row_version as "rowVersion",
                       latest.id as "latestVersionId", latest.version_no as "latestVersionNo",
                       latest.lifecycle_status as "latestVersionStatus"
                from daily_report_template_definition definition
                left join lateral (
                    select version.id, version.version_no, version.lifecycle_status
                    from daily_report_template_version version
                    where version.tenant_id = definition.tenant_id and version.template_id = definition.id
                    order by version.version_no desc limit 1
                ) latest on true
                where definition.tenant_id = :tenantId
                  and (cast(:status as varchar) is null or definition.status = :status)
                  and (cast(:positionId as uuid) is null or definition.position_id = :positionId)
                  and (cast(:orgUnitId as uuid) is null or definition.owner_org_unit_id is null
                       or definition.owner_org_unit_id = :orgUnitId)
                  and (definition.template_origin = 'HQ' or :tenantScope = true
                       or definition.owner_org_unit_id in (:orgScopes))
                order by definition.template_origin, definition.code
                """, visible(principal)
                .addValue("status", normalize(status))
                .addValue("orgUnitId", orgUnitId)
                .addValue("positionId", positionId));
    }

    @Transactional
    public Map<String, Object> create(DailyReportTemplateModels.CreateTemplate request, String idempotencyKey) {
        String origin = normalizeOrigin(request.templateOrigin());
        if ("HQ".equals(origin)) {
            accessPolicy.requirePermission("daily-report-template.manage");
        } else {
            accessPolicy.requirePermission("daily-report-template.store-supplement");
        }
        TenantPrincipal principal = prepare();
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_CREATE", idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        requireOwned(principal, "position_definition", request.positionId());
        UUID ownerOrgUnitId = request.ownerOrgUnitId();
        if (ownerOrgUnitId == null && "HQ".equals(origin)) {
            ownerOrgUnitId = jdbc.queryForObject("""
                    select id from org_unit
                    where tenant_id = :tenantId and parent_id is null and status = 'ACTIVE'
                    order by case unit_type when 'GROUP' then 0 else 1 end, code
                    limit 1
                    """, base(principal), UUID.class);
        }
        if (ownerOrgUnitId != null) accessPolicy.requireOrgScope(ownerOrgUnitId);
        if ("STORE".equals(origin)) {
            if (ownerOrgUnitId == null || request.baseTemplateDefinitionId() == null) {
                throw new IllegalArgumentException("门店补充模板必须指定所属组织和总部基础模板");
            }
            requireBaseTemplate(principal, request.baseTemplateDefinitionId(), request.positionId());
        } else if (request.baseTemplateDefinitionId() != null) {
            throw new IllegalArgumentException("总部模板不能再引用基础模板");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into daily_report_template_definition
                    (id, tenant_id, code, name, description, template_origin, owner_org_unit_id,
                     position_id, base_template_definition_id, created_by)
                values
                    (:id, :tenantId, :code, :name, :description, :origin, :ownerOrgUnitId,
                     :positionId, :baseTemplateDefinitionId, :actorId)
                """, base(principal)
                .addValue("id", id)
                .addValue("code", request.code().trim().toUpperCase(Locale.ROOT))
                .addValue("name", request.name().trim())
                .addValue("description", trim(request.description()))
                .addValue("origin", origin)
                .addValue("ownerOrgUnitId", ownerOrgUnitId)
                .addValue("positionId", request.positionId())
                .addValue("baseTemplateDefinitionId", request.baseTemplateDefinitionId())
                .addValue("actorId", principal.actorId()));
        Map<String, Object> response = templateRow(principal, id);
        auditWriter.record("DAILY_REPORT_TEMPLATE_CREATED", "DAILY_REPORT_TEMPLATE", id, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE", id, 201, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID templateId) {
        accessPolicy.requirePermission("daily-report-template.read");
        TenantPrincipal principal = prepare();
        Map<String, Object> result = new LinkedHashMap<>(requireVisible(principal, templateId));
        result.put("versions", jdbc.queryForList("""
                select id, version_no as "versionNo", lifecycle_status as "lifecycleStatus",
                       work_package_version_id as "workPackageVersionId", configuration,
                       content_hash as "contentHash", effective_from as "effectiveFrom",
                       effective_to as "effectiveTo", review_requested_at as "reviewRequestedAt",
                       reviewed_at as "reviewedAt", review_comment as "reviewComment",
                       published_at as "publishedAt", row_version as "rowVersion", created_at as "createdAt"
                from daily_report_template_version
                where tenant_id = :tenantId and template_id = :templateId
                order by version_no desc
                """, base(principal).addValue("templateId", templateId)).stream()
                .map(this::jsonColumns).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> createVersion(
            UUID templateId,
            DailyReportTemplateModels.CreateVersion request,
            String idempotencyKey
    ) {
        accessPolicy.requireAnyPermission(
                "daily-report-template.manage", "daily-report-template.store-supplement");
        TenantPrincipal principal = prepare();
        requireManageable(principal, templateId, true);
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_VERSION_CREATE:" + templateId, idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        requirePublishedWorkPackage(principal, request.workPackageVersionId(), templateId);
        Integer versionNo = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) + 1 from daily_report_template_version
                where tenant_id = :tenantId and template_id = :templateId
                """, base(principal).addValue("templateId", templateId), Integer.class);
        UUID versionId = UUID.randomUUID();
        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.put("title", request.title().trim());
        if (trim(request.description()) != null) configuration.put("description", trim(request.description()));
        configuration.putArray("sections");
        jdbc.update("""
                insert into daily_report_template_version
                    (id, tenant_id, template_id, version_no, lifecycle_status,
                     work_package_version_id, configuration, created_by)
                values
                    (:id, :tenantId, :templateId, :versionNo, 'DRAFT',
                     :workPackageVersionId, cast(:configuration as jsonb), :actorId)
                """, base(principal)
                .addValue("id", versionId)
                .addValue("templateId", templateId)
                .addValue("versionNo", versionNo)
                .addValue("workPackageVersionId", request.workPackageVersionId())
                .addValue("configuration", configuration.toString())
                .addValue("actorId", principal.actorId()));
        Map<String, Object> response = versionRow(principal, templateId, versionId);
        auditWriter.record("DAILY_REPORT_TEMPLATE_VERSION_CREATED", "DAILY_REPORT_TEMPLATE_VERSION",
                versionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE_VERSION", versionId, 201, response);
        return response;
    }

    @Transactional
    public Map<String, Object> updateVersion(
            UUID templateId,
            UUID versionId,
            DailyReportTemplateModels.UpdateVersion request,
            String idempotencyKey
    ) {
        accessPolicy.requireAnyPermission(
                "daily-report-template.manage", "daily-report-template.store-supplement");
        TenantPrincipal principal = prepare();
        Map<String, Object> template = requireManageable(principal, templateId, true);
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_VERSION_UPDATE:" + versionId, idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        Map<String, Object> before = versionForUpdate(principal, templateId, versionId);
        if (!"DRAFT".equals(before.get("lifecycle_status"))
                || ((Number) before.get("row_version")).longValue() != request.expectedVersion()) {
            conflict("模板草稿版本已变化或不再可编辑");
        }
        ObjectNode configuration = persistDraftSections(principal, template, versionId, request);
        String contentHash = hash(configuration.toString());
        int changed = jdbc.update("""
                update daily_report_template_version
                set configuration = cast(:configuration as jsonb), content_hash = :contentHash,
                    row_version = row_version + 1
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                  and lifecycle_status = 'DRAFT' and row_version = :expectedVersion
                """, base(principal)
                .addValue("templateId", templateId)
                .addValue("versionId", versionId)
                .addValue("configuration", configuration.toString())
                .addValue("contentHash", contentHash)
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) conflict("模板草稿版本已变化或不再可编辑");
        Map<String, Object> response = versionRow(principal, templateId, versionId);
        auditWriter.record("DAILY_REPORT_TEMPLATE_VERSION_UPDATED", "DAILY_REPORT_TEMPLATE_VERSION",
                versionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE_VERSION", versionId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> submitReview(
            UUID templateId,
            UUID versionId,
            DailyReportTemplateModels.VersionAction request,
            String idempotencyKey
    ) {
        accessPolicy.requireAnyPermission(
                "daily-report-template.manage", "daily-report-template.store-supplement");
        TenantPrincipal principal = prepare();
        requireManageable(principal, templateId, true);
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_SUBMIT_REVIEW:" + versionId, idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        int changed = jdbc.update("""
                update daily_report_template_version
                set lifecycle_status = 'IN_REVIEW', review_requested_by = :actorId,
                    review_requested_at = now(), review_comment = :comment, row_version = row_version + 1
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                  and lifecycle_status = 'DRAFT' and content_hash is not null
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("templateId", templateId)
                .addValue("versionId", versionId)
                .addValue("actorId", principal.actorId())
                .addValue("comment", trim(request.comment()))
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) conflict("模板版本已变化、尚未完成内容校验或不再是草稿");
        int submittedSections = jdbc.update("""
                update daily_report_section_version section_version
                set lifecycle_status = 'IN_REVIEW', review_requested_by = :actorId,
                    review_requested_at = now(), review_comment = :comment,
                    row_version = row_version + 1
                where section_version.tenant_id = :tenantId
                  and section_version.lifecycle_status = 'DRAFT'
                  and section_version.content_hash is not null
                  and exists (
                    select 1 from daily_report_template_section relation
                    where relation.tenant_id = section_version.tenant_id
                      and relation.template_version_id = :versionId
                      and relation.section_version_id = section_version.id
                  )
                """, base(principal).addValue("versionId", versionId)
                .addValue("actorId", principal.actorId()).addValue("comment", trim(request.comment())));
        Integer sectionCount = jdbc.queryForObject("""
                select count(*) from daily_report_template_section
                where tenant_id = :tenantId and template_version_id = :versionId
                """, base(principal).addValue("versionId", versionId), Integer.class);
        if (sectionCount == null || sectionCount == 0 || submittedSections != sectionCount) {
            conflict("模板至少需要一个内容完整的板块才能送审");
        }
        Map<String, Object> response = versionRow(principal, templateId, versionId);
        auditWriter.record("DAILY_REPORT_TEMPLATE_REVIEW_REQUESTED", "DAILY_REPORT_TEMPLATE_VERSION",
                versionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE_VERSION", versionId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> publish(
            UUID templateId,
            UUID versionId,
            DailyReportTemplateModels.VersionAction request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report-template.review");
        accessPolicy.requirePermission("daily-report-template.publish");
        TenantPrincipal principal = prepare();
        Map<String, Object> template = requireManageable(principal, templateId, false);
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_PUBLISH:" + versionId, idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        Map<String, Object> before = versionForUpdate(principal, templateId, versionId);
        if (principal.actorId().equals(before.get("created_by"))
                || principal.actorId().equals(before.get("review_requested_by"))
                || hasEditedTemplateVersion(principal, versionId)) {
            throw new AccessDeniedException("模板编辑或送审人员不能审核发布同一版本");
        }
        OffsetDateTime effectiveFrom = request.effectiveFrom() == null ? OffsetDateTime.now() : request.effectiveFrom();
        jdbc.update("""
                update daily_report_template_version
                set lifecycle_status = 'RETIRED', effective_to = :effectiveFrom,
                    row_version = row_version + 1
                where tenant_id = :tenantId and template_id = :templateId and id <> :versionId
                  and lifecycle_status = 'PUBLISHED'
                """, base(principal).addValue("templateId", templateId)
                .addValue("versionId", versionId).addValue("effectiveFrom", effectiveFrom));
        jdbc.update("""
                update daily_report_section_version section_version
                set lifecycle_status = 'RETIRED', effective_to = :effectiveFrom,
                    row_version = row_version + 1
                where section_version.tenant_id = :tenantId
                  and section_version.lifecycle_status = 'PUBLISHED'
                  and exists (
                    select 1 from daily_report_template_section relation
                    join daily_report_template_version template_version
                      on template_version.tenant_id = relation.tenant_id
                     and template_version.id = relation.template_version_id
                    where relation.tenant_id = section_version.tenant_id
                      and relation.section_version_id = section_version.id
                      and template_version.template_id = :templateId
                      and template_version.id <> :versionId
                      and template_version.lifecycle_status = 'RETIRED'
                  )
                """, base(principal).addValue("templateId", templateId)
                .addValue("versionId", versionId).addValue("effectiveFrom", effectiveFrom));
        int changed = jdbc.update("""
                update daily_report_template_version
                set lifecycle_status = 'PUBLISHED', effective_from = :effectiveFrom,
                    effective_to = :effectiveTo, reviewed_by = :actorId, reviewed_at = now(),
                    review_comment = :comment, published_by = :actorId, published_at = now(),
                    row_version = row_version + 1
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                  and lifecycle_status = 'IN_REVIEW' and row_version = :expectedVersion
                """, base(principal)
                .addValue("templateId", templateId).addValue("versionId", versionId)
                .addValue("effectiveFrom", effectiveFrom).addValue("effectiveTo", request.effectiveTo())
                .addValue("actorId", principal.actorId()).addValue("comment", trim(request.comment()))
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) conflict("模板版本已变化或尚未进入审核状态");
        int publishedSections = jdbc.update("""
                update daily_report_section_version section_version
                set lifecycle_status = 'PUBLISHED', effective_from = :effectiveFrom,
                    effective_to = :effectiveTo, reviewed_by = :actorId, reviewed_at = now(),
                    review_comment = :comment, published_by = :actorId, published_at = now(),
                    row_version = row_version + 1
                where section_version.tenant_id = :tenantId
                  and section_version.lifecycle_status = 'IN_REVIEW'
                  and exists (
                    select 1 from daily_report_template_section relation
                    where relation.tenant_id = section_version.tenant_id
                      and relation.template_version_id = :versionId
                      and relation.section_version_id = section_version.id
                  )
                """, base(principal).addValue("versionId", versionId)
                .addValue("effectiveFrom", effectiveFrom).addValue("effectiveTo", request.effectiveTo())
                .addValue("actorId", principal.actorId()).addValue("comment", trim(request.comment())));
        Integer requiredSections = jdbc.queryForObject("""
                select count(*) from daily_report_template_section
                where tenant_id = :tenantId and template_version_id = :versionId
                """, base(principal).addValue("versionId", versionId), Integer.class);
        if (requiredSections == null || requiredSections == 0 || publishedSections != requiredSections) {
            conflict("模板必须包含已送审的有效板块才能发布");
        }
        createAssignment(principal, template, versionId, effectiveFrom, request.effectiveTo());
        Map<String, Object> response = versionRow(principal, templateId, versionId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("templateId", templateId.toString());
        payload.put("templateVersionId", versionId.toString());
        payload.put("versionNo", ((Number) response.get("versionNo")).intValue());
        payload.put("effectiveFrom", effectiveFrom.toString());
        UUID traceId = UUID.randomUUID();
        eventPublisher.publish(new BusinessEvent(
                "DAILY_REPORT_TEMPLATE", templateId, "DAILY_REPORT_TEMPLATE_PUBLISHED", 1,
                PRODUCER, (UUID) requireVisible(principal, templateId).get("ownerOrgUnitId"), null,
                null, null, null, traceId, null, idempotencyKey, "INTERNAL", payload));
        auditWriter.record("DAILY_REPORT_TEMPLATE_PUBLISHED", "DAILY_REPORT_TEMPLATE_VERSION",
                versionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE_VERSION", versionId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> retire(
            UUID templateId,
            UUID versionId,
            DailyReportTemplateModels.VersionAction request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report-template.publish");
        TenantPrincipal principal = prepare();
        requireManageable(principal, templateId, false);
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_TEMPLATE_RETIRE:" + versionId, idempotencyKey, request, UUID.randomUUID());
        if (reservation.replayed()) return replay(reservation);
        OffsetDateTime effectiveTo = request.effectiveTo() == null ? OffsetDateTime.now() : request.effectiveTo();
        int changed = jdbc.update("""
                update daily_report_template_version
                set lifecycle_status = 'RETIRED', effective_to = :effectiveTo, row_version = row_version + 1
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                  and lifecycle_status = 'PUBLISHED' and row_version = :expectedVersion
                """, base(principal).addValue("templateId", templateId).addValue("versionId", versionId)
                .addValue("effectiveTo", effectiveTo).addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) conflict("模板版本已变化或不是已发布状态");
        jdbc.update("""
                update daily_report_template_assignment
                set status = 'REVOKED',
                    valid_to = case when :effectiveDate < valid_from then valid_from else :effectiveDate end,
                    row_version = row_version + 1
                where tenant_id = :tenantId and template_version_id = :versionId and status = 'ACTIVE'
                """, base(principal).addValue("versionId", versionId)
                .addValue("effectiveDate", effectiveTo.toLocalDate()));
        jdbc.update("""
                update daily_report_section_version section_version
                set lifecycle_status = 'RETIRED', effective_to = :effectiveTo,
                    row_version = row_version + 1
                where section_version.tenant_id = :tenantId
                  and section_version.lifecycle_status = 'PUBLISHED'
                  and exists (
                    select 1 from daily_report_template_section relation
                    where relation.tenant_id = section_version.tenant_id
                      and relation.template_version_id = :versionId
                      and relation.section_version_id = section_version.id
                  )
                """, base(principal).addValue("versionId", versionId)
                .addValue("effectiveTo", effectiveTo));
        Map<String, Object> response = versionRow(principal, templateId, versionId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("templateId", templateId.toString());
        payload.put("templateVersionId", versionId.toString());
        payload.put("effectiveTo", effectiveTo.toString());
        eventPublisher.publish(new BusinessEvent(
                "DAILY_REPORT_TEMPLATE", templateId, "DAILY_REPORT_TEMPLATE_RETIRED", 1,
                PRODUCER, (UUID) requireVisible(principal, templateId).get("ownerOrgUnitId"), null,
                null, null, null, UUID.randomUUID(), null, idempotencyKey, "INTERNAL", payload));
        auditWriter.record("DAILY_REPORT_TEMPLATE_RETIRED", "DAILY_REPORT_TEMPLATE_VERSION",
                versionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_TEMPLATE_VERSION", versionId, 200, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolve(UUID orgUnitId, UUID positionAssignmentId, LocalDate businessDate) {
        accessPolicy.requirePermission("daily-report-template.read");
        TenantPrincipal principal = prepare();
        BusinessDayService.BusinessDayContext day = businessDayService.resolve(orgUnitId, businessDate);
        UUID positionId = jdbc.queryForObject("""
                select assignment.position_id
                from employee_position_assignment assignment
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and status = 'ACTIVE' and valid_from <= :businessDate
                  and (valid_to is null or valid_to >= :businessDate)
                  and exists (
                    select 1
                    from org_unit_closure report_scope
                    where report_scope.tenant_id = assignment.tenant_id
                      and report_scope.ancestor_id = :orgUnitId
                      and report_scope.descendant_id = assignment.org_unit_id
                  )
                """, base(principal).addValue("assignmentId", positionAssignmentId)
                .addValue("orgUnitId", orgUnitId).addValue("businessDate", day.businessDate()), UUID.class);
        OffsetDateTime effectiveAt = day.businessDate().atTime(day.cutoffLocalTime())
                .atZone(ZoneId.of(day.timezone())).toOffsetDateTime();
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select definition.id as "templateId", definition.code, definition.name,
                       definition.template_origin as "templateOrigin",
                       definition.base_template_definition_id as "baseTemplateDefinitionId",
                       version.id as "templateVersionId", version.version_no as "versionNo",
                       version.work_package_version_id as "workPackageVersionId", version.configuration,
                       assignment.assignment_kind as "assignmentKind", assignment.priority
                from daily_report_template_definition definition
                join daily_report_template_version version
                  on version.tenant_id = definition.tenant_id and version.template_id = definition.id
                join daily_report_template_assignment assignment
                  on assignment.tenant_id = version.tenant_id
                 and assignment.template_version_id = version.id
                where definition.tenant_id = :tenantId and definition.status = 'ACTIVE'
                  and definition.position_id = :positionId
                  and version.lifecycle_status = 'PUBLISHED'
                  and version.effective_from <= :effectiveAt
                  and (version.effective_to is null or version.effective_to > :effectiveAt)
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and (
                    assignment.scope_type = 'TENANT'
                    or (assignment.scope_type = 'POSITION' and assignment.position_id = :positionId)
                    or (assignment.scope_type = 'ORG_UNIT' and assignment.org_unit_id = :orgUnitId)
                    or (assignment.scope_type = 'ORG_TREE' and exists (
                      select 1 from org_unit_closure scope
                      where scope.tenant_id = assignment.tenant_id
                        and scope.ancestor_id = assignment.org_unit_id
                        and scope.descendant_id = :orgUnitId
                    ))
                  )
                order by case definition.template_origin when 'HQ' then 0 else 1 end,
                         assignment.priority, version.version_no desc
                """, base(principal).addValue("positionId", positionId)
                .addValue("effectiveAt", effectiveAt).addValue("orgUnitId", orgUnitId)
                .addValue("businessDate", day.businessDate())).stream()
                .map(this::jsonColumns).toList();
        Map<String, Object> baseTemplate = candidates.stream()
                .filter(item -> "HQ".equals(item.get("templateOrigin"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前岗位没有生效的总部日报模板"));
        UUID baseTemplateId = (UUID) baseTemplate.get("templateId");
        List<Map<String, Object>> supplements = candidates.stream()
                .filter(item -> "STORE".equals(item.get("templateOrigin")))
                .filter(item -> baseTemplateId.equals(item.get("baseTemplateDefinitionId")))
                .toList();
        ArrayNode resolvedSections = objectMapper.createArrayNode();
        appendSections(resolvedSections, snapshotReader.complete((UUID) baseTemplate.get("templateVersionId"),
                (JsonNode) baseTemplate.get("configuration")));
        for (Map<String, Object> supplement : supplements) {
            appendSections(resolvedSections, snapshotReader.complete((UUID) supplement.get("templateVersionId"),
                    (JsonNode) supplement.get("configuration")));
        }
        ObjectNode resolvedConfiguration = objectMapper.createObjectNode();
        resolvedConfiguration.put("title", String.valueOf(baseTemplate.get("name")));
        resolvedConfiguration.set("sections", resolvedSections);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hotelOrgUnitId", day.hotelOrgUnitId());
        response.put("orgUnitId", orgUnitId);
        response.put("positionAssignmentId", positionAssignmentId);
        response.put("positionId", positionId);
        response.put("businessDate", day.businessDate());
        response.put("timezone", day.timezone());
        response.put("cutoffLocalTime", day.cutoffLocalTime());
        response.put("selectedTemplateVersionId", baseTemplate.get("templateVersionId"));
        response.put("workPackageVersionId", baseTemplate.get("workPackageVersionId"));
        response.put("resolvedConfiguration", resolvedConfiguration);
        response.put("baseTemplate", baseTemplate);
        response.put("supplementTemplates", supplements);
        return response;
    }

    private void appendSections(ArrayNode target, Object configurationValue) {
        JsonNode configuration = configurationValue instanceof JsonNode node
                ? node : parseJson(String.valueOf(configurationValue));
        JsonNode sections = configuration.path("sections");
        if (!sections.isArray()) return;
        sections.forEach(section -> target.add(section.deepCopy()));
    }

    private ObjectNode persistDraftSections(
            TenantPrincipal principal,
            Map<String, Object> template,
            UUID templateVersionId,
            DailyReportTemplateModels.UpdateVersion request
    ) {
        UUID workPackageVersionId = jdbc.queryForObject("""
                select work_package_version_id from daily_report_template_version
                where tenant_id = :tenantId and id = :versionId and lifecycle_status = 'DRAFT'
                """, base(principal).addValue("versionId", templateVersionId), UUID.class);
        Map<UUID, UUID> existingVersions = new HashMap<>();
        jdbc.query("""
                select definition.id as definition_id, section_version.id as section_version_id
                from daily_report_template_section relation
                join daily_report_section_version section_version
                  on section_version.tenant_id = relation.tenant_id
                 and section_version.id = relation.section_version_id
                join daily_report_section_definition definition
                  on definition.tenant_id = section_version.tenant_id
                 and definition.id = section_version.section_definition_id
                where relation.tenant_id = :tenantId and relation.template_version_id = :versionId
                """, base(principal).addValue("versionId", templateVersionId), resultSet -> {
            existingVersions.put(
                    resultSet.getObject("definition_id", UUID.class),
                    resultSet.getObject("section_version_id", UUID.class));
        });

        ObjectNode config = objectMapper.createObjectNode();
        config.put("title", request.title().trim());
        if (trim(request.description()) != null) config.put("description", trim(request.description()));
        ArrayNode sections = config.putArray("sections");
        Set<UUID> retainedVersions = new HashSet<>();
        Set<String> requestSectionCodes = new HashSet<>();
        String templateOrigin = String.valueOf(template.get("templateOrigin"));
        String templateCode = String.valueOf(template.get("code"));
        UUID ownerOrgUnitId = (UUID) template.get("ownerOrgUnitId");
        UUID positionId = (UUID) template.get("positionId");
        for (DailyReportTemplateModels.Section section : request.sections()) {
            String sectionCode = normalizeRequired(section.sectionCode(), "sectionCode");
            if (!requestSectionCodes.add(sectionCode)) {
                throw new IllegalArgumentException("同一模板版本不能包含重复sectionCode：" + sectionCode);
            }
            String sectionOrigin = normalizeDefault(section.sectionOrigin(), templateOrigin);
            if (!templateOrigin.equals(sectionOrigin)) {
                throw new IllegalArgumentException("门店补充板块与总部标准板块必须分别维护，不能跨来源修改");
            }
            JsonNode condition = objectNodeOrEmpty(section.applicabilityCondition(), "applicabilityCondition");
            String sectionRole = normalizeSectionRole(section.sectionRole(), templateOrigin, condition);
            String storageCode = sectionStorageCode(templateCode, sectionCode);
            UUID sectionDefinitionId = resolveSectionDefinition(
                    principal, storageCode, section, sectionOrigin, ownerOrgUnitId, positionId);
            UUID sectionVersionId = existingVersions.get(sectionDefinitionId);
            ObjectNode sectionConfiguration = objectMapper.createObjectNode();
            sectionConfiguration.put("sectionCode", sectionCode);
            sectionConfiguration.put("title", section.title().trim());
            if (trim(section.description()) != null) {
                sectionConfiguration.put("description", trim(section.description()));
            }
            sectionConfiguration.put("sectionOrigin", sectionOrigin);
            if (sectionVersionId == null) {
                sectionVersionId = createSectionVersion(
                        principal, sectionDefinitionId, sectionConfiguration, condition);
                jdbc.update("""
                        insert into daily_report_template_section
                            (tenant_id, template_version_id, section_version_id,
                             section_role, required, sort_order)
                        values
                            (:tenantId, :templateVersionId, :sectionVersionId,
                             :sectionRole, :required, :sortOrder)
                        """, base(principal)
                        .addValue("templateVersionId", templateVersionId)
                        .addValue("sectionVersionId", sectionVersionId)
                        .addValue("sectionRole", sectionRole)
                        .addValue("required", !Boolean.FALSE.equals(section.required()))
                        .addValue("sortOrder", defaultOrder(section.sortOrder())));
            } else {
                int changed = jdbc.update("""
                        update daily_report_section_version
                        set condition_expression = cast(:condition as jsonb),
                            configuration = cast(:configuration as jsonb),
                            row_version = row_version + 1
                        where tenant_id = :tenantId and id = :sectionVersionId
                          and lifecycle_status = 'DRAFT'
                        """, base(principal)
                        .addValue("sectionVersionId", sectionVersionId)
                        .addValue("condition", condition.toString())
                        .addValue("configuration", sectionConfiguration.toString()));
                if (changed != 1) conflict("日报板块版本已送审或已发布，不能继续修改");
                jdbc.update("""
                        update daily_report_template_section
                        set section_role = :sectionRole, required = :required, sort_order = :sortOrder
                        where tenant_id = :tenantId and template_version_id = :templateVersionId
                          and section_version_id = :sectionVersionId
                        """, base(principal)
                        .addValue("templateVersionId", templateVersionId)
                        .addValue("sectionVersionId", sectionVersionId)
                        .addValue("sectionRole", sectionRole)
                        .addValue("required", !Boolean.FALSE.equals(section.required()))
                        .addValue("sortOrder", defaultOrder(section.sortOrder())));
            }
            retainedVersions.add(sectionVersionId);

            ObjectNode sectionNode = sections.addObject();
            sectionNode.put("id", sectionDefinitionId.toString());
            sectionNode.put("sectionVersionId", sectionVersionId.toString());
            sectionNode.put("sectionCode", sectionCode);
            sectionNode.put("title", section.title().trim());
            if (trim(section.description()) != null) sectionNode.put("description", trim(section.description()));
            sectionNode.put("sectionOrigin", sectionOrigin);
            sectionNode.set("applicabilityCondition", condition.deepCopy());
            sectionNode.put("sectionRole", sectionRole);
            sectionNode.put("required", !Boolean.FALSE.equals(section.required()));
            sectionNode.put("sortOrder", defaultOrder(section.sortOrder()));
            ArrayNode items = sectionNode.putArray("items");
            Map<String, UUID> existingItemIds = new HashMap<>();
            jdbc.query("""
                    select item_code, id from daily_report_template_item
                    where tenant_id = :tenantId and section_version_id = :sectionVersionId
                    """, base(principal).addValue("sectionVersionId", sectionVersionId), resultSet -> {
                existingItemIds.put(resultSet.getString("item_code"), resultSet.getObject("id", UUID.class));
            });
            jdbc.update("""
                    delete from daily_report_template_item
                    where tenant_id = :tenantId and section_version_id = :sectionVersionId
                    """, base(principal).addValue("sectionVersionId", sectionVersionId));
            Set<String> requestItemCodes = new HashSet<>();
            for (DailyReportTemplateModels.Item item : section.items()) {
                String itemCode = normalizeRequired(item.itemCode(), "itemCode");
                if (!requestItemCodes.add(itemCode)) {
                    throw new IllegalArgumentException("同一板块不能包含重复itemCode：" + itemCode);
                }
                String inputType = normalizeInputType(item.valueType());
                if (item.workPackageItemId() != null) {
                    requireWorkPackageItem(principal, workPackageVersionId, item.workPackageItemId());
                }
                JsonNode evidencePolicy = objectNodeOrEmpty(item.evidencePolicy(), "evidencePolicy");
                JsonNode validationRules = objectNodeOrEmpty(item.validationRules(), "validationRules");
                JsonNode optionValues = arrayNodeOrEmpty(item.optionValues(), "optionValues");
                ObjectNode sourcePolicy = objectMapper.createObjectNode();
                sourcePolicy.put("sourceType", normalizeDefault(item.dataSourceType(), "MANUAL"));
                if (item.dataSourceConfig() != null) {
                    sourcePolicy.set("configuration", item.dataSourceConfig().deepCopy());
                }
                UUID itemId = existingItemIds.getOrDefault(itemCode, UUID.randomUUID());
                jdbc.update("""
                        insert into daily_report_template_item
                            (id, tenant_id, section_version_id, item_code, label, help_text,
                             input_type, required, work_package_item_id, standard_version_id,
                             metric_id, evidence_policy, source_policy, validation_rules,
                             option_values, sort_order)
                        values
                            (:id, :tenantId, :sectionVersionId, :itemCode, :label, :helpText,
                             :inputType, :required, :workPackageItemId, :standardVersionId,
                             :metricId, cast(:evidencePolicy as jsonb), cast(:sourcePolicy as jsonb),
                             cast(:validationRules as jsonb), cast(:optionValues as jsonb), :sortOrder)
                        """, base(principal)
                        .addValue("id", itemId)
                        .addValue("sectionVersionId", sectionVersionId)
                        .addValue("itemCode", itemCode)
                        .addValue("label", item.label().trim())
                        .addValue("helpText", trim(item.description()))
                        .addValue("inputType", inputType)
                        .addValue("required", !Boolean.FALSE.equals(item.required()))
                        .addValue("workPackageItemId", item.workPackageItemId())
                        .addValue("standardVersionId", item.standardVersionId())
                        .addValue("metricId", item.metricId())
                        .addValue("evidencePolicy", evidencePolicy.toString())
                        .addValue("sourcePolicy", sourcePolicy.toString())
                        .addValue("validationRules", validationRules.toString())
                        .addValue("optionValues", optionValues.toString())
                        .addValue("sortOrder", defaultOrder(item.sortOrder())));
                ObjectNode itemNode = items.addObject();
                itemNode.put("id", itemId.toString());
                itemNode.put("itemCode", itemCode);
                itemNode.put("label", item.label().trim());
                if (trim(item.description()) != null) itemNode.put("description", trim(item.description()));
                itemNode.put("valueType", inputType);
                itemNode.put("required", !Boolean.FALSE.equals(item.required()));
                if (item.workPackageItemId() != null) {
                    itemNode.put("workPackageItemId", item.workPackageItemId().toString());
                }
                if (item.standardVersionId() != null) {
                    itemNode.put("standardVersionId", item.standardVersionId().toString());
                }
                if (item.metricId() != null) itemNode.put("metricId", item.metricId().toString());
                itemNode.put("dataSourceType", sourcePolicy.get("sourceType").asText());
                if (item.dataSourceConfig() != null) itemNode.set("dataSourceConfig", item.dataSourceConfig());
                itemNode.set("evidencePolicy", evidencePolicy.deepCopy());
                itemNode.set("validationRules", validationRules.deepCopy());
                itemNode.set("optionValues", optionValues.deepCopy());
                itemNode.put("sortOrder", defaultOrder(item.sortOrder()));
            }
            jdbc.update("""
                    update daily_report_section_version
                    set content_hash = :contentHash, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :sectionVersionId
                      and lifecycle_status = 'DRAFT'
                    """, base(principal).addValue("sectionVersionId", sectionVersionId)
                    .addValue("contentHash", hash(sectionNode.toString())));
        }
        for (UUID staleVersionId : existingVersions.values()) {
            if (retainedVersions.contains(staleVersionId)) continue;
            jdbc.update("""
                    delete from daily_report_template_section
                    where tenant_id = :tenantId and template_version_id = :templateVersionId
                      and section_version_id = :sectionVersionId
                    """, base(principal).addValue("templateVersionId", templateVersionId)
                    .addValue("sectionVersionId", staleVersionId));
            jdbc.update("""
                    delete from daily_report_template_item
                    where tenant_id = :tenantId and section_version_id = :sectionVersionId
                    """, base(principal).addValue("sectionVersionId", staleVersionId));
            jdbc.update("""
                    delete from daily_report_section_version
                    where tenant_id = :tenantId and id = :sectionVersionId and lifecycle_status = 'DRAFT'
                    """, base(principal).addValue("sectionVersionId", staleVersionId));
        }
        return config;
    }

    private UUID resolveSectionDefinition(
            TenantPrincipal principal,
            String storageCode,
            DailyReportTemplateModels.Section section,
            String sectionOrigin,
            UUID ownerOrgUnitId,
            UUID positionId
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, section_origin, owner_org_unit_id, position_id
                from daily_report_section_definition
                where tenant_id = :tenantId and code = :code
                """, base(principal).addValue("code", storageCode));
        if (rows.isEmpty()) {
            UUID definitionId = UUID.randomUUID();
            jdbc.update("""
                    insert into daily_report_section_definition
                        (id, tenant_id, code, name, description, section_origin,
                         owner_org_unit_id, position_id, created_by)
                    values
                        (:id, :tenantId, :code, :name, :description, :origin,
                         :ownerOrgUnitId, :positionId, :actorId)
                    """, base(principal)
                    .addValue("id", definitionId).addValue("code", storageCode)
                    .addValue("name", section.title().trim()).addValue("description", trim(section.description()))
                    .addValue("origin", sectionOrigin).addValue("ownerOrgUnitId", ownerOrgUnitId)
                    .addValue("positionId", positionId).addValue("actorId", principal.actorId()));
            return definitionId;
        }
        Map<String, Object> row = rows.getFirst();
        if (!sectionOrigin.equals(row.get("section_origin"))
                || !ownerOrgUnitId.equals(row.get("owner_org_unit_id"))
                || !positionId.equals(row.get("position_id"))) {
            throw new IllegalArgumentException("日报板块编码已被其他来源、组织或岗位占用：" + storageCode);
        }
        UUID definitionId = (UUID) row.get("id");
        jdbc.update("""
                update daily_report_section_definition
                set name = :name, description = :description, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and status = 'ACTIVE'
                """, base(principal).addValue("id", definitionId)
                .addValue("name", section.title().trim()).addValue("description", trim(section.description())));
        return definitionId;
    }

    private UUID createSectionVersion(
            TenantPrincipal principal,
            UUID sectionDefinitionId,
            ObjectNode configuration,
            JsonNode condition
    ) {
        Integer versionNo = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) + 1 from daily_report_section_version
                where tenant_id = :tenantId and section_definition_id = :definitionId
                """, base(principal).addValue("definitionId", sectionDefinitionId), Integer.class);
        UUID sectionVersionId = UUID.randomUUID();
        jdbc.update("""
                insert into daily_report_section_version
                    (id, tenant_id, section_definition_id, version_no, lifecycle_status,
                     condition_expression, configuration, created_by)
                values
                    (:id, :tenantId, :definitionId, :versionNo, 'DRAFT',
                     cast(:condition as jsonb), cast(:configuration as jsonb), :actorId)
                """, base(principal)
                .addValue("id", sectionVersionId).addValue("definitionId", sectionDefinitionId)
                .addValue("versionNo", versionNo).addValue("condition", condition.toString())
                .addValue("configuration", configuration.toString()).addValue("actorId", principal.actorId()));
        return sectionVersionId;
    }

    private void requireWorkPackageItem(
            TenantPrincipal principal,
            UUID workPackageVersionId,
            UUID workPackageItemId
    ) {
        Integer count = jdbc.queryForObject("""
                select count(*) from work_package_item
                where tenant_id = :tenantId and work_package_version_id = :versionId and id = :itemId
                """, base(principal).addValue("versionId", workPackageVersionId)
                .addValue("itemId", workPackageItemId), Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("日报字段引用的工作包事项不属于当前模板绑定版本");
        }
    }

    private void createAssignment(
            TenantPrincipal principal,
            Map<String, Object> template,
            UUID versionId,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
        boolean headquarters = "HQ".equals(template.get("templateOrigin"));
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                insert into daily_report_template_assignment
                    (id, tenant_id, template_version_id, assignment_kind, scope_type,
                     org_unit_id, position_id, priority, valid_from, valid_to, assigned_by)
                values
                    (:id, :tenantId, :versionId, :assignmentKind, :scopeType,
                     :orgUnitId, :positionId, :priority, :validFrom, :validTo, :actorId)
                """, base(principal).addValue("id", assignmentId).addValue("versionId", versionId)
                .addValue("assignmentKind", headquarters ? "BASE" : "SUPPLEMENT")
                .addValue("scopeType", headquarters ? "POSITION" : "ORG_TREE")
                .addValue("orgUnitId", headquarters ? null : template.get("ownerOrgUnitId"))
                .addValue("positionId", headquarters ? template.get("positionId") : null)
                .addValue("priority", headquarters ? 100 : 200)
                .addValue("validFrom", effectiveFrom.toLocalDate())
                .addValue("validTo", effectiveTo == null ? null : effectiveTo.toLocalDate())
                .addValue("actorId", principal.actorId()));
        if (headquarters) {
            Map<String, Object> schedule = jdbc.queryForMap("""
                    select coalesce(min(item.due_local_time), cast('23:00' as time)) as due_local_time,
                           coalesce(min(item.grace_minutes), 30) as grace_minutes
                    from daily_report_template_version version
                    left join work_package_item item
                      on item.tenant_id = version.tenant_id
                     and item.work_package_version_id = version.work_package_version_id
                    where version.tenant_id = :tenantId and version.id = :versionId
                    """, base(principal).addValue("versionId", versionId));
            Object dueLocalTimeValue = schedule.get("due_local_time");
            LocalTime dueLocalTime = dueLocalTimeValue instanceof LocalTime value
                    ? value
                    : ((java.sql.Time) dueLocalTimeValue).toLocalTime();
            int graceMinutes = ((Number) schedule.get("grace_minutes")).intValue();
            jdbc.update("""
                    insert into daily_report_delivery_policy
                        (tenant_id, template_assignment_id, enabled, open_local_time,
                         due_local_time, grace_minutes, pre_due_reminder_minutes,
                         overdue_reminder_minutes, backfill_days, created_by, updated_by)
                    values
                        (:tenantId, :assignmentId, true, :openLocalTime,
                         :dueLocalTime, :graceMinutes, cast('{30}' as integer[]),
                         cast('{0,30}' as integer[]), 1, :actorId, :actorId)
                    on conflict (tenant_id, template_assignment_id) do nothing
                    """, base(principal)
                    .addValue("assignmentId", assignmentId)
                    .addValue("openLocalTime", dueLocalTime.minusHours(1))
                    .addValue("dueLocalTime", dueLocalTime)
                    .addValue("graceMinutes", graceMinutes)
                    .addValue("actorId", principal.actorId()));
        }
    }

    private void requirePublishedWorkPackage(TenantPrincipal principal, UUID versionId, UUID templateId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from work_package_version version
                join work_package_definition definition
                  on definition.tenant_id = version.tenant_id
                 and definition.id = version.work_package_definition_id
                join daily_report_template_definition template
                  on template.tenant_id = definition.tenant_id and template.id = :templateId
                where version.tenant_id = :tenantId and version.id = :versionId
                  and version.lifecycle_status = 'PUBLISHED'
                  and definition.position_id = template.position_id
                """, base(principal).addValue("versionId", versionId).addValue("templateId", templateId),
                Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("日报模板必须绑定同岗位的已发布工作包版本");
        }
    }

    private void requireBaseTemplate(TenantPrincipal principal, UUID baseId, UUID positionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_template_definition
                where tenant_id = :tenantId and id = :baseId and template_origin = 'HQ'
                  and position_id = :positionId and status = 'ACTIVE'
                """, base(principal).addValue("baseId", baseId).addValue("positionId", positionId), Integer.class);
        if (count == null || count != 1) throw new IllegalArgumentException("总部基础日报模板不存在或岗位不一致");
    }

    private Map<String, Object> requireManageable(
            TenantPrincipal principal,
            UUID templateId,
            boolean requireEditPermission
    ) {
        Map<String, Object> template = requireVisible(principal, templateId);
        boolean headquarters = "HQ".equals(template.get("templateOrigin"));
        if (headquarters && !principal.hasTenantScope()) {
            throw new AccessDeniedException("门店不能修改总部日报模板");
        }
        if (requireEditPermission && headquarters
                && !principal.hasPermission("daily-report-template.manage")
                && !principal.hasPermission("*")) {
            throw new AccessDeniedException("总部模板只能由具备模板管理权限的人员编辑");
        }
        if (requireEditPermission && !headquarters
                && !principal.hasPermission("daily-report-template.manage")
                && !principal.hasPermission("daily-report-template.store-supplement")
                && !principal.hasPermission("*")) {
            throw new AccessDeniedException("缺少门店补充模板维护权限");
        }
        UUID ownerOrg = (UUID) template.get("ownerOrgUnitId");
        if (ownerOrg != null) accessPolicy.requireOrgScope(ownerOrg);
        return template;
    }

    private Map<String, Object> templateRow(TenantPrincipal principal, UUID templateId) {
        return jsonColumns(jdbc.queryForMap("""
                select id, code, name, description, template_origin as "templateOrigin",
                       owner_org_unit_id as "ownerOrgUnitId", position_id as "positionId",
                       base_template_definition_id as "baseTemplateDefinitionId", status,
                       row_version as "rowVersion", created_at as "createdAt", updated_at as "updatedAt"
                from daily_report_template_definition
                where tenant_id = :tenantId and id = :templateId
                """, base(principal).addValue("templateId", templateId)));
    }

    private Map<String, Object> requireVisible(TenantPrincipal principal, UUID templateId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, description, template_origin as "templateOrigin",
                       owner_org_unit_id as "ownerOrgUnitId", position_id as "positionId",
                       base_template_definition_id as "baseTemplateDefinitionId", status,
                       row_version as "rowVersion", created_at as "createdAt", updated_at as "updatedAt"
                from daily_report_template_definition
                where tenant_id = :tenantId and id = :templateId
                  and (template_origin = 'HQ' or :tenantScope = true or owner_org_unit_id in (:orgScopes))
                """, visible(principal).addValue("templateId", templateId));
        if (rows.isEmpty()) throw new AccessDeniedException("日报模板不存在或不在当前授权范围");
        return jsonColumns(rows.getFirst());
    }

    private Map<String, Object> versionRow(TenantPrincipal principal, UUID templateId, UUID versionId) {
        return jsonColumns(jdbc.queryForMap("""
                select id, template_id as "templateId", version_no as "versionNo",
                       lifecycle_status as "lifecycleStatus",
                       work_package_version_id as "workPackageVersionId", configuration,
                       content_hash as "contentHash", effective_from as "effectiveFrom",
                       effective_to as "effectiveTo", review_requested_at as "reviewRequestedAt",
                       reviewed_at as "reviewedAt", review_comment as "reviewComment",
                       published_at as "publishedAt", row_version as "rowVersion", created_at as "createdAt"
                from daily_report_template_version
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                """, base(principal).addValue("templateId", templateId).addValue("versionId", versionId)));
    }

    private Map<String, Object> versionForUpdate(TenantPrincipal principal, UUID templateId, UUID versionId) {
        return jdbc.queryForMap("""
                select id, lifecycle_status, created_by, review_requested_by, row_version
                from daily_report_template_version
                where tenant_id = :tenantId and template_id = :templateId and id = :versionId
                for update
                """, base(principal).addValue("templateId", templateId).addValue("versionId", versionId));
    }

    private boolean hasEditedTemplateVersion(TenantPrincipal principal, UUID versionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = :tenantId and actor_id = :actorId
                  and resource_type = 'DAILY_REPORT_TEMPLATE_VERSION'
                  and resource_id = :versionId
                  and action in (
                    'DAILY_REPORT_TEMPLATE_VERSION_CREATED',
                    'DAILY_REPORT_TEMPLATE_VERSION_UPDATED',
                    'DAILY_REPORT_TEMPLATE_REVIEW_REQUESTED'
                  )
                """, base(principal).addValue("actorId", principal.actorId())
                .addValue("versionId", versionId), Integer.class);
        return count != null && count > 0;
    }

    private void requireOwned(TenantPrincipal principal, String table, UUID id) {
        if (!Set.of("position_definition").contains(table)) throw new IllegalArgumentException("不允许校验的资源类型");
        Integer count = jdbc.queryForObject("select count(*) from " + table
                        + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count != 1) throw new IllegalArgumentException("引用资源不存在或不属于当前租户");
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private MapSqlParameterSource visible(TenantPrincipal principal) {
        Collection<UUID> scopes = principal.orgScopes().isEmpty()
                ? List.of(new UUID(0, 0)) : principal.orgScopes();
        return base(principal).addValue("tenantScope", principal.hasTenantScope()).addValue("orgScopes", scopes);
    }

    private Map<String, Object> replay(CommandIdempotencyService.Reservation reservation) {
        return objectMapper.convertValue(reservation.responseSnapshot(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private Map<String, Object> jsonColumns(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        Object configuration = result.get("configuration");
        if (configuration != null && !(configuration instanceof JsonNode)) {
            result.put("configuration", parseJson(String.valueOf(configuration)));
        }
        return result;
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("日报模板配置不是有效JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法生成审计快照", exception);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(field + "不能为空");
        return normalized;
    }

    private static String normalizeOrigin(String value) {
        String origin = normalizeDefault(value, "HQ");
        if (!Set.of("HQ", "STORE").contains(origin)) throw new IllegalArgumentException("templateOrigin必须为HQ或STORE");
        return origin;
    }

    private static String normalizeSectionRole(String value, String templateOrigin, JsonNode condition) {
        String fallback = "STORE".equals(templateOrigin)
                ? "SUPPLEMENT" : condition.isEmpty() ? "BASE" : "CONDITIONAL";
        String role = normalizeDefault(value, fallback);
        if (!Set.of("BASE", "CONDITIONAL", "SUPPLEMENT").contains(role)) {
            throw new IllegalArgumentException("sectionRole必须为BASE、CONDITIONAL或SUPPLEMENT");
        }
        if ("STORE".equals(templateOrigin) != "SUPPLEMENT".equals(role)) {
            throw new IllegalArgumentException("门店板块必须是SUPPLEMENT，总部板块不能伪装为门店补充项");
        }
        return role;
    }

    private static String normalizeInputType(String value) {
        String normalized = normalizeRequired(value, "valueType");
        return switch (normalized) {
            case "STRING", "SHORT_TEXT" -> "TEXT";
            case "TEXT", "LONG_TEXT", "NUMBER", "BOOLEAN", "SINGLE_SELECT", "MULTI_SELECT",
                    "DATE", "TIME", "DATETIME", "METRIC_REFERENCE", "WORK_RECORD_REFERENCE" -> normalized;
            default -> throw new IllegalArgumentException("不支持的日报字段类型：" + normalized);
        };
    }

    private static int defaultOrder(Integer value) {
        return value == null ? 0 : value;
    }

    private JsonNode objectNodeOrEmpty(JsonNode value, String field) {
        if (value == null || value.isNull()) return objectMapper.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException(field + "必须是JSON对象");
        return value;
    }

    private JsonNode arrayNodeOrEmpty(JsonNode value, String field) {
        if (value == null || value.isNull()) return objectMapper.createArrayNode();
        if (!value.isArray()) throw new IllegalArgumentException(field + "必须是JSON数组");
        return value;
    }

    private static String sectionStorageCode(String templateCode, String sectionCode) {
        String candidate = normalizeRequired(templateCode, "templateCode") + "." + sectionCode;
        if (candidate.length() <= 80) return candidate;
        return candidate.substring(0, 63) + "." + hash(candidate).substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private static String normalizeDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
