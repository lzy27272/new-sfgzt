package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.workdata.WorkEntryPolicy;
import cn.sifangguan.hotelaios.dailyreporttemplates.DailyReportTemplateService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.BusinessEvent;
import cn.sifangguan.hotelaios.shared.events.BusinessEventPublisher;
import cn.sifangguan.hotelaios.shared.idempotency.CommandIdempotencyService;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import cn.sifangguan.hotelaios.shared.time.BusinessDayService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DailyReportService {
    private static final String PRODUCER = "daily-report-service";
    private static final Set<String> REPORT_STATUSES = Set.of("DRAFT", "SUBMITTED", "ARCHIVED");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "WORK_RECORD", "INSPECTION", "QUALITY_REPORT", "TASK", "METRIC", "MANUAL", "OTHER");
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "FILE", "IMAGE", "DOCUMENT", "QUALITY_REPORT", "LINK", "STRUCTURED");
    private static final Set<String> SENSITIVITY_LEVELS = Set.of(
            "PUBLIC", "INTERNAL", "SENSITIVE", "RESTRICTED");
    private static final Set<String> REVIEW_DECISIONS = Set.of(
            "APPROVED", "REJECTED", "SUPPLEMENT_REQUIRED", "ACKNOWLEDGED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final WorkVisibilityPolicy workVisibility;
    private final CommandIdempotencyService idempotency;
    private final AuditWriter auditWriter;
    private final BusinessEventPublisher eventPublisher;
    private final BusinessDayService businessDayService;
    private final DailyReportTemplateService templateService;
    private final ObjectMapper objectMapper;
    private final DailyReportRoutineProjectionService routineProjection;
    private final DailyReportWorkEntryService workEntry;
    private final DailyActivityService activities;

    public DailyReportService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            WorkVisibilityPolicy workVisibility,
            CommandIdempotencyService idempotency,
            AuditWriter auditWriter,
            BusinessEventPublisher eventPublisher,
            BusinessDayService businessDayService,
            DailyReportTemplateService templateService,
            ObjectMapper objectMapper,
            DailyReportRoutineProjectionService routineProjection,
            DailyReportWorkEntryService workEntry,
            DailyActivityService activities
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.workVisibility = workVisibility;
        this.idempotency = idempotency;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.businessDayService = businessDayService;
        this.templateService = templateService;
        this.objectMapper = objectMapper;
        this.routineProjection = routineProjection;
        this.workEntry = workEntry;
        this.activities = activities;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myReports(
            LocalDate businessDate,
            String status,
            UUID positionAssignmentId
    ) {
        accessPolicy.requirePermission("daily-report.read");
        TenantPrincipal principal = prepare();
        MapSqlParameterSource params = base(principal).addValue("actorId", principal.actorId())
                .addValue("businessDate", businessDate).addValue("status", normalizeStatus(status))
                .addValue("positionAssignmentId", positionAssignmentId);
        String visibility = workVisibility.reportScope(principal, params).personal("report");
        return jdbc.queryForList("""
                select report.id, report.hotel_org_unit_id as "hotelOrgUnitId",
                       report.org_unit_id as "orgUnitId", report.employee_id as "employeeId",
                       employee.name as "employeeName",
                       report.position_assignment_id as "positionAssignmentId",
                       position.name as "positionName", report.business_date as "businessDate",
                       report.report_status as "reportStatus", report.review_status as "reviewStatus",
                       report.report_deadline_at as "reportDeadlineAt",
                       template.name as "templateName",
                       template_version.version_no as "templateVersionNo",
                       report.current_revision_id as "currentRevisionId",
                       report.current_revision_no as "currentRevisionNo",
                       report.submitted_at as "submittedAt", report.row_version as "rowVersion",
                       report.created_at as "createdAt", report.updated_at as "updatedAt"
                from daily_report report
                join employee on employee.tenant_id = report.tenant_id and employee.id = report.employee_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                join daily_report_template_version template_version
                  on template_version.tenant_id = report.tenant_id
                 and template_version.id = report.template_version_id
                join daily_report_template_definition template
                  on template.tenant_id = template_version.tenant_id
                 and template.id = template_version.template_id
                where report.tenant_id = :tenantId and employee.account_id = :actorId
                  and %s
                  and (cast(:businessDate as date) is null or report.business_date = :businessDate)
                  and (cast(:status as varchar) is null or report.report_status = :status)
                  and (cast(:positionAssignmentId as uuid) is null
                       or report.position_assignment_id = :positionAssignmentId)
                order by report.business_date desc, report.created_at desc
                """.formatted(visibility), params);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teamReports(UUID orgUnitId, LocalDate businessDate, String status) {
        accessPolicy.requirePermission("daily-report.team-read");
        TenantPrincipal principal = prepare();
        accessPolicy.requireOrgScope(orgUnitId);
        MapSqlParameterSource params = base(principal).addValue("orgUnitId", orgUnitId)
                .addValue("businessDate", businessDate).addValue("status", normalizeStatus(status));
        String visibility = workVisibility.reportScope(principal, params).report("report");
        return jdbc.queryForList("""
                select report.id, report.hotel_org_unit_id as "hotelOrgUnitId",
                       report.org_unit_id as "orgUnitId", report.employee_id as "employeeId",
                       employee.name as "employeeName",
                       report.position_assignment_id as "positionAssignmentId",
                       position.name as "positionName", report.business_date as "businessDate",
                       report.report_status as "reportStatus", report.review_status as "reviewStatus",
                       report.report_deadline_at as "reportDeadlineAt",
                       template.name as "templateName",
                       template_version.version_no as "templateVersionNo",
                       report.current_revision_id as "currentRevisionId",
                       report.current_revision_no as "currentRevisionNo",
                       report.submitted_at as "submittedAt", report.row_version as "rowVersion",
                       report.created_at as "createdAt", report.updated_at as "updatedAt"
                from daily_report report
                join employee on employee.tenant_id = report.tenant_id and employee.id = report.employee_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                join daily_report_template_version template_version
                  on template_version.tenant_id = report.tenant_id
                 and template_version.id = report.template_version_id
                join daily_report_template_definition template
                  on template.tenant_id = template_version.tenant_id
                 and template.id = template_version.template_id
                where report.tenant_id = :tenantId
                  and %s
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = report.tenant_id and scope.ancestor_id = :orgUnitId
                      and scope.descendant_id = report.org_unit_id
                  )
                  and (cast(:businessDate as date) is null or report.business_date = :businessDate)
                  and (cast(:status as varchar) is null or report.report_status = :status)
                order by report.business_date desc, employee.name, position.name
                """.formatted(visibility), params);
    }

    @Transactional
    public Map<String, Object> create(DailyReportModels.CreateReport request, String idempotencyKey) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        accessPolicy.requireActiveAssignment(request.positionAssignmentId());
        BusinessDayService.BusinessDayContext day = businessDayService.resolve(
                request.orgUnitId(), request.businessDate());
        UUID employeeId = jdbc.queryForObject("""
                select employee.id
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and exists (
                    select 1
                    from org_unit_closure report_scope
                    where report_scope.tenant_id = assignment.tenant_id
                      and report_scope.ancestor_id = :orgUnitId
                      and report_scope.descendant_id = assignment.org_unit_id
                  )
                  and employee.account_id = :actorId and employee.employment_status = 'ACTIVE'
                """, base(principal).addValue("assignmentId", request.positionAssignmentId())
                .addValue("orgUnitId", request.orgUnitId()).addValue("businessDate", day.businessDate())
                .addValue("actorId", principal.actorId()), UUID.class);
        Map<String, Object> resolution = templateService.resolve(
                request.orgUnitId(), request.positionAssignmentId(), day.businessDate());
        UUID templateVersionId = (UUID) resolution.get("selectedTemplateVersionId");
        UUID workPackageVersionId = (UUID) resolution.get("workPackageVersionId");
        if (request.templateVersionId() != null && !request.templateVersionId().equals(templateVersionId)) {
            conflict("指定模板版本与该岗位营业日的生效模板不一致");
        }
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_CREATE:" + request.positionAssignmentId() + ":" + day.businessDate(),
                idempotencyKey, request, principal.correlationId());
        if (reservation.replayed()) return replay(reservation);
        Integer existing = jdbc.queryForObject("""
                select count(*) from daily_report
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelOrgUnitId
                  and position_assignment_id = :assignmentId and business_date = :businessDate
                """, base(principal).addValue("hotelOrgUnitId", day.hotelOrgUnitId())
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("businessDate", day.businessDate()), Integer.class);
        if (existing != null && existing > 0) conflict("该岗位本营业日已经创建日报");

        UUID reportId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime deadline = day.businessDate().plusDays(1).atTime(day.cutoffLocalTime())
                .atZone(ZoneId.of(day.timezone())).plusMinutes(day.closingGraceMinutes()).toOffsetDateTime();
        jdbc.update("""
                insert into daily_report
                    (id, tenant_id, hotel_org_unit_id, org_unit_id, employee_id,
                     position_assignment_id, business_date, timezone, cutoff_local_time,
                     report_deadline_at, template_version_id, work_package_version_id,
                     report_status, review_status, current_revision_no, trace_id,
                     created_by_account_id)
                values
                    (:id, :tenantId, :hotelOrgUnitId, :orgUnitId, :employeeId,
                     :assignmentId, :businessDate, :timezone, :cutoffLocalTime,
                     :deadline, :templateVersionId, :workPackageVersionId,
                     'DRAFT', 'NOT_REQUIRED', 1, :traceId, :actorId)
                """, base(principal).addValue("id", reportId)
                .addValue("hotelOrgUnitId", day.hotelOrgUnitId()).addValue("orgUnitId", request.orgUnitId())
                .addValue("employeeId", employeeId).addValue("assignmentId", request.positionAssignmentId())
                .addValue("businessDate", day.businessDate()).addValue("timezone", day.timezone())
                .addValue("cutoffLocalTime", day.cutoffLocalTime()).addValue("deadline", deadline)
                .addValue("templateVersionId", templateVersionId)
                .addValue("workPackageVersionId", workPackageVersionId)
                .addValue("traceId", principal.correlationId()).addValue("actorId", principal.actorId()));
        ObjectNode snapshot = objectMapper.createObjectNode();
        JsonNode resolvedTemplate = toJsonNode(resolution.get("resolvedConfiguration"));
        snapshot.set("resolvedTemplate", resolvedTemplate.deepCopy());
        snapshot.set("templateResolution", objectMapper.valueToTree(resolution));
        snapshot.putArray("items");
        jdbc.update("""
                insert into daily_report_revision
                    (id, tenant_id, report_id, revision_no, revision_type, revision_status,
                     payload_snapshot, created_by_account_id)
                values
                    (:id, :tenantId, :reportId, 1, 'ORIGINAL', 'DRAFT',
                     cast(:snapshot as jsonb), :actorId)
                """, base(principal).addValue("id", revisionId).addValue("reportId", reportId)
                .addValue("snapshot", snapshot.toString()).addValue("actorId", principal.actorId()));
        jdbc.update("""
                update daily_report set current_revision_id = :revisionId
                where tenant_id = :tenantId and id = :reportId
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId));
        Map<String, Object> response = detailInternal(principal, reportId);
        auditWriter.record("DAILY_REPORT_CREATED", "DAILY_REPORT", reportId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT", reportId, 201, response);
        return response;
    }

    @Transactional
    public Map<String, Object> detail(UUID reportId) {
        accessPolicy.requirePermission("daily-report.read");
        TenantPrincipal principal = prepare();
        requireVisible(principal, reportId);
        UUID revisionId = jdbc.queryForObject("select current_revision_id from daily_report where tenant_id = :tenantId and id = :reportId",
                base(principal).addValue("reportId", reportId), UUID.class);
        // Materialize only editable drafts. Submitted revisions remain immutable snapshots.
        routineProjection.projectReport(reportId, revisionId);
        return detailInternal(principal, reportId);
    }

    @Transactional
    public Map<String, Object> saveDraft(
            UUID reportId,
            DailyReportModels.SaveDraft request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        ReportLock report = lockReport(principal, reportId);
        requireOwner(principal, report);
        requireExpected(report, request.expectedVersion());
        if (!"DRAFT".equals(report.reportStatus()) || !request.revisionId().equals(report.currentRevisionId())) {
            conflict("只有当前日报草稿版本可以保存");
        }
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_DRAFT_SAVE:" + reportId, idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        ObjectNode snapshot = revisionSnapshotForUpdate(principal, reportId, request.revisionId());
        Map<UUID, ItemPolicy> allowedItems = itemPolicies(snapshot);
        Set<UUID> requestItems = new HashSet<>();
        ArrayNode itemSnapshot = objectMapper.createArrayNode();
        for (DailyReportModels.ItemValue item : request.items()) {
            if (!requestItems.add(item.templateItemId())) {
                throw new IllegalArgumentException("草稿中存在重复日报字段");
            }
            if (!allowedItems.containsKey(item.templateItemId())) {
                throw new IllegalArgumentException("日报字段不属于创建时冻结的模板");
            }
            if (allowedItems.get(item.templateItemId()).systemManaged()) {
                if ((item.value() != null && !item.value().isNull() && !item.value().asText().isBlank())
                        || Boolean.TRUE.equals(item.exception())) {
                    throw new IllegalArgumentException("自动汇总字段不能手工覆盖，请在原工作记录中修正");
                }
                continue;
            }
            boolean exception = Boolean.TRUE.equals(item.exception());
            String resultStatus = exception ? "EXCEPTION"
                    : item.value() == null || item.value().isNull() ? "PENDING" : "COMPLETED";
            ObjectNode itemCanonical = objectMapper.createObjectNode();
            itemCanonical.put("templateItemId", item.templateItemId().toString());
            if (item.value() != null) itemCanonical.set("value", item.value().deepCopy());
            itemCanonical.put("confirmed", Boolean.TRUE.equals(item.confirmed()));
            itemCanonical.put("exception", exception);
            if (trim(item.comment()) != null) itemCanonical.put("comment", trim(item.comment()));
            jdbc.update("""
                    insert into daily_report_item_result
                        (tenant_id, revision_id, template_item_id, result_status, value,
                         system_prefilled, employee_confirmed, exception_flag,
                         exception_statement, source_summary, content_hash)
                    values
                        (:tenantId, :revisionId, :templateItemId, :resultStatus,
                         cast(:value as jsonb), false, :confirmed, :exception,
                         :comment, '{}'::jsonb, :contentHash)
                    on conflict (tenant_id, revision_id, template_item_id) do update
                    set result_status = excluded.result_status, value = excluded.value,
                        employee_confirmed = excluded.employee_confirmed,
                        exception_flag = excluded.exception_flag,
                        exception_statement = excluded.exception_statement,
                        content_hash = excluded.content_hash, row_version = daily_report_item_result.row_version + 1
                    """, base(principal).addValue("revisionId", request.revisionId())
                    .addValue("templateItemId", item.templateItemId()).addValue("resultStatus", resultStatus)
                    .addValue("value", item.value() == null || item.value().isNull() ? null : item.value().toString())
                    .addValue("confirmed", Boolean.TRUE.equals(item.confirmed()))
                    .addValue("exception", exception).addValue("comment", trim(item.comment()))
                    .addValue("contentHash", hash(itemCanonical.toString())));
            itemSnapshot.add(itemCanonical);
        }
        snapshot.set("items", itemSnapshot);
        if (request.inventoryQuantities() != null) snapshot.set("inventoryQuantities", request.inventoryQuantities());
        if (request.roomCounts() != null) snapshot.set("roomCounts", request.roomCounts());
        if (request.housekeepingActivities() != null) {
            snapshot.set("housekeepingActivities", activities.normalize(reportId, request.housekeepingActivities(), false));
        }
        if (request.plannedHygiene() != null) {
            if (!workEntry.supportsPlannedHygiene(reportId)) {
                throw new IllegalArgumentException("计划卫生只适用于客房服务员日报");
            }
            var hygiene = request.plannedHygiene();
            snapshot.set("plannedHygiene", objectMapper.valueToTree(new DailyReportModels.PlannedHygiene(
                    hygiene.content() == null ? "" : hygiene.content().trim(),
                    hygiene.roomNumbers() == null ? "" : hygiene.roomNumbers().trim())));
        }
        int revisionChanged = jdbc.update("""
                update daily_report_revision
                set payload_snapshot = cast(:snapshot as jsonb), narrative = :narrative,
                    row_version = row_version + 1
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                  and revision_status = 'DRAFT'
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", request.revisionId())
                .addValue("snapshot", snapshot.toString()).addValue("narrative", trim(request.narrative())));
        if (revisionChanged != 1) conflict("日报修订已提交，不能继续保存");
        incrementReportVersion(principal, reportId, request.expectedVersion());
        Map<String, Object> response = detailInternal(principal, reportId);
        auditWriter.record("DAILY_REPORT_DRAFT_SAVED", "DAILY_REPORT", reportId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT", reportId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> submit(
            UUID reportId,
            DailyReportModels.SubmitReport request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        workEntry.lockDay(reportId);
        ReportLock report = lockReport(principal, reportId);
        requireOwner(principal, report);
        requireExpected(report, request.expectedVersion());
        if (!"DRAFT".equals(report.reportStatus()) || !request.revisionId().equals(report.currentRevisionId())) {
            conflict("只有当前日报草稿可以提交");
        }
        routineProjection.projectReport(reportId, request.revisionId());
        RevisionLock revision = lockRevision(principal, reportId, request.revisionId());
        if (!"DRAFT".equals(revision.status())) conflict("日报修订已经提交或审核");
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_SUBMIT:" + reportId + ":" + request.revisionId(),
                idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        ObjectNode snapshot = parseObject(revision.payloadSnapshot(), "payload_snapshot");
        ValidationOutcome validation = validateSubmission(principal, request.revisionId(), itemPolicies(snapshot));
        activities.submit(reportId, request.revisionId(), snapshot.get("housekeepingActivities"));
        workEntry.submit(reportId, snapshot);
        // Work submission can enrich this draft. Freeze the enriched facts, not the pre-submission copy.
        routineProjection.projectReport(reportId, request.revisionId());
        snapshot = revisionSnapshotForUpdate(principal, reportId, request.revisionId());
        long submissionVersion = lockReport(principal, reportId).rowVersion();
        String contentHash = reportContentHash(principal, request.revisionId(), snapshot);
        int revisionChanged = jdbc.update("""
                update daily_report_revision
                set revision_status = 'SUBMITTED', content_hash = :contentHash,
                    submitted_by_account_id = :actorId, submitted_by_assignment_id = :assignmentId,
                    submitted_at = now(), row_version = row_version + 1
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                  and revision_status = 'DRAFT'
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", request.revisionId())
                .addValue("contentHash", contentHash).addValue("actorId", principal.actorId())
                .addValue("assignmentId", report.positionAssignmentId()));
        if (revisionChanged != 1) conflict("日报修订已被其他请求提交");
        boolean reviewRequired = validation.hasExceptions() || "CORRECTION".equals(revision.type());
        int reportChanged = jdbc.update("""
                update daily_report
                set report_status = 'SUBMITTED', review_status = :reviewStatus,
                    submitted_at = now(), row_version = row_version + 1
                where tenant_id = :tenantId and id = :reportId and report_status = 'DRAFT'
                  and row_version = :expectedVersion and current_revision_id = :revisionId
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", request.revisionId())
                .addValue("reviewStatus", reviewRequired ? "PENDING" : "NOT_REQUIRED")
                .addValue("expectedVersion", submissionVersion));
        if (reportChanged != 1) conflict("日报已变化，提交未生效");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reportId", reportId.toString());
        payload.put("revisionId", request.revisionId().toString());
        payload.put("revisionNo", revision.revisionNo());
        payload.put("revisionType", revision.type());
        payload.put("reviewRequired", reviewRequired);
        payload.put("exceptionCount", validation.exceptionCount());
        eventPublisher.publish(new BusinessEvent(
                "DAILY_REPORT", reportId,
                "CORRECTION".equals(revision.type())
                        ? "DAILY_REPORT_CORRECTION_SUBMITTED" : "DAILY_REPORT_SUBMITTED",
                1, PRODUCER, report.orgUnitId(), report.hotelOrgUnitId(),
                report.positionAssignmentId(), report.positionAssignmentId(), report.businessDate(),
                report.traceId(), null, idempotencyKey, "INTERNAL", payload));
        Map<String, Object> response = detailInternal(principal, reportId);
        auditWriter.record("DAILY_REPORT_SUBMITTED", "DAILY_REPORT", reportId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT", reportId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> createCorrection(
            UUID reportId,
            DailyReportModels.CreateCorrection request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        ReportLock report = lockReport(principal, reportId);
        requireOwner(principal, report);
        requireExpected(report, request.expectedVersion());
        if (!Set.of("SUBMITTED", "ARCHIVED").contains(report.reportStatus())) {
            conflict("只有已提交日报可以发起修订");
        }
        RevisionLock current = lockRevision(principal, reportId, report.currentRevisionId());
        if ("DRAFT".equals(current.status())) conflict("当前已有待完成的修订草稿");
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_CORRECTION_CREATE:" + reportId,
                idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        UUID revisionId = UUID.randomUUID();
        int revisionNo = report.currentRevisionNo() + 1;
        ObjectNode snapshot = parseObject(current.payloadSnapshot(), "payload_snapshot").deepCopy();
        snapshot.put("correctionReason", request.reason().trim());
        jdbc.update("""
                insert into daily_report_revision
                    (id, tenant_id, report_id, revision_no, revision_type, revision_status,
                     supersedes_revision_id, payload_snapshot, narrative, created_by_account_id)
                values
                    (:id, :tenantId, :reportId, :revisionNo, 'CORRECTION', 'DRAFT',
                     :supersedesRevisionId, cast(:snapshot as jsonb), :reason, :actorId)
                """, base(principal).addValue("id", revisionId).addValue("reportId", reportId)
                .addValue("revisionNo", revisionNo).addValue("supersedesRevisionId", report.currentRevisionId())
                .addValue("snapshot", snapshot.toString()).addValue("reason", request.reason().trim())
                .addValue("actorId", principal.actorId()));
        copyRevisionFacts(principal, report.currentRevisionId(), revisionId);
        int changed = jdbc.update("""
                update daily_report
                set current_revision_id = :revisionId, current_revision_no = :revisionNo,
                    report_status = 'DRAFT', review_status = 'PENDING', row_version = row_version + 1
                where tenant_id = :tenantId and id = :reportId and row_version = :expectedVersion
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId)
                .addValue("revisionNo", revisionNo).addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) conflict("日报已变化，修订草稿未创建");
        Map<String, Object> response = detailInternal(principal, reportId);
        auditWriter.record("DAILY_REPORT_CORRECTION_CREATED", "DAILY_REPORT_REVISION", revisionId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_REVISION", revisionId, 201, response);
        return response;
    }

    @Transactional
    public Map<String, Object> review(
            UUID reportId,
            DailyReportModels.Review request,
            String idempotencyKey
    ) {
        TenantPrincipal principal = prepare();
        requireVisible(principal, reportId);
        ReportLock report = lockReport(principal, reportId);
        requireExpected(report, request.expectedVersion());
        RevisionLock revision = lockRevision(principal, reportId, report.currentRevisionId());
        if ("CORRECTION".equals(revision.type())) {
            accessPolicy.requirePermission("daily-report.revision-review");
        } else {
            accessPolicy.requirePermission("daily-report.review");
        }
        accessPolicy.requireOrgScope(report.orgUnitId());
        verifyReviewerAssignment(principal, request.reviewerAssignmentId(), report.orgUnitId());
        if (principal.actorId().equals(report.createdByAccountId())
                || principal.actorId().equals(revision.submittedByAccountId())) {
            throw new AccessDeniedException("日报提交人不能审核自己的日报");
        }
        if (!"SUBMITTED".equals(revision.status()) || !"SUBMITTED".equals(report.reportStatus())) {
            conflict("只有待审核的已提交日报可以审核");
        }
        String decision = normalizeAllowed(request.outcome(), REVIEW_DECISIONS, "outcome");
        if (Set.of("REJECTED", "SUPPLEMENT_REQUIRED").contains(decision)
                && trim(request.comment()) == null) {
            throw new IllegalArgumentException("驳回或要求补充时必须填写审核意见");
        }
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_REVIEW:" + reportId + ":" + revision.id(),
                idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        UUID reviewId = UUID.randomUUID();
        String reviewType = "CORRECTION".equals(revision.type()) ? "CORRECTION"
                : hasExceptions(principal, revision.id()) ? "EXCEPTION" : "SUPERVISOR";
        jdbc.update("""
                insert into daily_report_review
                    (id, tenant_id, report_id, revision_id, review_type, decision,
                     reviewer_account_id, reviewer_assignment_id, comment, trace_id)
                values
                    (:id, :tenantId, :reportId, :revisionId, :reviewType, :decision,
                     :actorId, :assignmentId, :comment, :traceId)
                """, base(principal).addValue("id", reviewId).addValue("reportId", reportId)
                .addValue("revisionId", revision.id()).addValue("reviewType", reviewType)
                .addValue("decision", decision).addValue("actorId", principal.actorId())
                .addValue("assignmentId", request.reviewerAssignmentId())
                .addValue("comment", trim(request.comment())).addValue("traceId", report.traceId()));
        boolean approved = Set.of("APPROVED", "ACKNOWLEDGED").contains(decision);
        int revisionChanged = jdbc.update("""
                update daily_report_revision
                set revision_status = :status, row_version = row_version + 1
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                  and revision_status = 'SUBMITTED'
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revision.id())
                .addValue("status", approved ? "APPROVED" : "REJECTED"));
        if (revisionChanged != 1) conflict("日报修订已被其他审核请求处理");
        int reportChanged = jdbc.update("""
                update daily_report
                set review_status = :reviewStatus, row_version = row_version + 1
                where tenant_id = :tenantId and id = :reportId and row_version = :expectedVersion
                """, base(principal).addValue("reportId", reportId)
                .addValue("reviewStatus", approved ? "APPROVED" : "REJECTED")
                .addValue("expectedVersion", request.expectedVersion()));
        if (reportChanged != 1) conflict("日报已变化，审核未生效");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reportId", reportId.toString());
        payload.put("revisionId", revision.id().toString());
        payload.put("reviewId", reviewId.toString());
        payload.put("reviewType", reviewType);
        payload.put("decision", decision);
        eventPublisher.publish(new BusinessEvent(
                "DAILY_REPORT", reportId, "DAILY_REPORT_REVIEWED", 1, PRODUCER,
                report.orgUnitId(), report.hotelOrgUnitId(), report.positionAssignmentId(),
                request.reviewerAssignmentId(), report.businessDate(), report.traceId(), null,
                idempotencyKey, "INTERNAL", payload));
        Map<String, Object> response = detailInternal(principal, reportId);
        auditWriter.record("DAILY_REPORT_REVIEWED", "DAILY_REPORT_REVIEW", reviewId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_REVIEW", reviewId, 200, response);
        return response;
    }

    @Transactional
    public Map<String, Object> addSource(
            UUID reportId,
            UUID revisionId,
            DailyReportModels.AddSource request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        ReportLock report = lockReport(principal, reportId);
        requireOwner(principal, report);
        requireExpected(report, request.expectedVersion());
        requireDraftRevision(principal, report, revisionId);
        validateItemResult(principal, revisionId, request.itemResultId());
        if ((request.sourceId() == null) == (trim(request.sourceExternalKey()) == null)) {
            throw new IllegalArgumentException("sourceId和sourceExternalKey必须且只能填写一个");
        }
        String sourceType = normalizeAllowed(request.sourceType(), SOURCE_TYPES, "sourceType");
        validateSourceBoundary(principal, report, sourceType, request.sourceId());
        JsonNode snapshot = objectNodeOrEmpty(request.sourceSnapshot(), "sourceSnapshot");
        ObjectNode fingerprint = objectMapper.createObjectNode();
        fingerprint.put("sourceType", sourceType);
        if (request.sourceId() != null) fingerprint.put("sourceId", request.sourceId().toString());
        if (trim(request.sourceExternalKey()) != null) {
            fingerprint.put("sourceExternalKey", trim(request.sourceExternalKey()));
        }
        if (trim(request.sourceVersion()) != null) fingerprint.put("sourceVersion", trim(request.sourceVersion()));
        fingerprint.set("sourceSnapshot", snapshot.deepCopy());
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_SOURCE_ADD:" + revisionId, idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        UUID sourceReferenceId = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into daily_report_source_reference
                        (id, tenant_id, revision_id, item_result_id, source_type, source_id,
                         source_external_key, source_version, source_status, source_snapshot,
                         content_hash, source_occurred_at, linked_by_account_id)
                    values
                        (:id, :tenantId, :revisionId, :itemResultId, :sourceType, :sourceId,
                         :sourceExternalKey, :sourceVersion, 'ACTIVE', cast(:snapshot as jsonb),
                         :contentHash, :sourceOccurredAt, :actorId)
                    """, base(principal).addValue("id", sourceReferenceId).addValue("revisionId", revisionId)
                    .addValue("itemResultId", request.itemResultId()).addValue("sourceType", sourceType)
                    .addValue("sourceId", request.sourceId())
                    .addValue("sourceExternalKey", trim(request.sourceExternalKey()))
                    .addValue("sourceVersion", trim(request.sourceVersion()))
                    .addValue("snapshot", snapshot.toString()).addValue("contentHash", hash(fingerprint.toString()))
                    .addValue("sourceOccurredAt", request.sourceOccurredAt())
                    .addValue("actorId", principal.actorId()));
        } catch (DataIntegrityViolationException exception) {
            conflict("相同来源已经关联到该日报修订");
        }
        incrementReportVersion(principal, reportId, request.expectedVersion());
        Map<String, Object> response = sourceRow(principal, sourceReferenceId);
        auditWriter.record("DAILY_REPORT_SOURCE_LINKED", "DAILY_REPORT_SOURCE_REFERENCE",
                sourceReferenceId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_SOURCE_REFERENCE", sourceReferenceId, 201, response);
        return response;
    }

    @Transactional
    public Map<String, Object> addEvidence(
            UUID reportId,
            UUID revisionId,
            DailyReportModels.AddEvidence request,
            String idempotencyKey
    ) {
        accessPolicy.requirePermission("daily-report.submit");
        TenantPrincipal principal = prepare();
        ReportLock report = lockReport(principal, reportId);
        requireOwner(principal, report);
        requireExpected(report, request.expectedVersion());
        requireDraftRevision(principal, report, revisionId);
        validateItemResult(principal, revisionId, request.itemResultId());
        String evidenceType = normalizeAllowed(request.evidenceType(), EVIDENCE_TYPES, "evidenceType");
        String sensitivity = normalizeAllowedDefault(
                request.sensitivity(), SENSITIVITY_LEVELS, "sensitivity", "INTERNAL");
        JsonNode metadata = objectNodeOrEmpty(request.metadata(), "metadata");
        String objectKey = trim(request.objectKey());
        if (objectKey == null && metadata.isEmpty()) {
            throw new IllegalArgumentException("证据必须包含objectKey或结构化metadata");
        }
        if (!"STRUCTURED".equals(evidenceType) && objectKey == null) {
            throw new IllegalArgumentException("文件、图片、文档、质检报告和链接证据必须提供objectKey");
        }
        validateEvidenceObjectKey(principal, report, revisionId, evidenceType, objectKey);
        String sha256 = trim(request.sha256());
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256必须是64位十六进制摘要");
        }
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "DAILY_REPORT_EVIDENCE_ADD:" + revisionId, idempotencyKey, request, report.traceId());
        if (reservation.replayed()) return replay(reservation);
        UUID evidenceId = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into daily_report_evidence
                        (id, tenant_id, revision_id, item_result_id, evidence_type, object_key,
                         original_name, media_type, size_bytes, sha256, structured_snapshot,
                         scan_status, sensitivity_level, uploaded_by_account_id,
                         uploaded_by_assignment_id)
                    values
                        (:id, :tenantId, :revisionId, :itemResultId, :evidenceType, :objectKey,
                         :originalName, :mediaType, :sizeBytes, :sha256, cast(:metadata as jsonb),
                         'PENDING', :sensitivity, :actorId, :assignmentId)
                    """, base(principal).addValue("id", evidenceId).addValue("revisionId", revisionId)
                    .addValue("itemResultId", request.itemResultId()).addValue("evidenceType", evidenceType)
                    .addValue("objectKey", objectKey).addValue("originalName", trim(request.originalName()))
                    .addValue("mediaType", trim(request.mediaType())).addValue("sizeBytes", request.sizeBytes())
                    .addValue("sha256", sha256 == null ? null : sha256.toLowerCase(Locale.ROOT))
                    .addValue("metadata", metadata.toString()).addValue("sensitivity", sensitivity)
                    .addValue("actorId", principal.actorId())
                    .addValue("assignmentId", report.positionAssignmentId()));
        } catch (DataIntegrityViolationException exception) {
            conflict("相同对象证据已经关联到该日报修订");
        }
        incrementReportVersion(principal, reportId, request.expectedVersion());
        boolean canReadSensitive = principal.hasPermission("evidence.sensitive.read")
                || principal.hasPermission("*");
        Map<String, Object> response = evidenceRow(principal, evidenceId, canReadSensitive);
        auditWriter.record("DAILY_REPORT_EVIDENCE_ADDED", "DAILY_REPORT_EVIDENCE", evidenceId, json(response));
        idempotency.succeed(reservation, "DAILY_REPORT_EVIDENCE", evidenceId, 201, response);
        return response;
    }

    private Map<String, Object> detailInternal(TenantPrincipal principal, UUID reportId) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                select report.id, report.hotel_org_unit_id as "hotelOrgUnitId",
                       report.org_unit_id as "orgUnitId", report.employee_id as "employeeId",
                       employee.name as "employeeName", report.position_assignment_id as "positionAssignmentId",
                       assignment.position_id as "positionId", position.name as "positionName",
                       report.business_date as "businessDate", report.timezone,
                       report.cutoff_local_time as "cutoffLocalTime",
                       report.report_deadline_at as "reportDeadlineAt",
                       report.template_version_id as "templateVersionId",
                       report.work_package_version_id as "workPackageVersionId",
                       report.report_status as "reportStatus", report.review_status as "reviewStatus",
                       report.current_revision_id as "currentRevisionId",
                       report.current_revision_no as "currentRevisionNo",
                       report.submitted_at as "submittedAt", report.trace_id as "traceId",
                       report.row_version as "rowVersion", report.created_at as "createdAt",
                       report.updated_at as "updatedAt"
                from daily_report report
                join employee on employee.tenant_id = report.tenant_id and employee.id = report.employee_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                where report.tenant_id = :tenantId and report.id = :reportId
                """, base(principal).addValue("reportId", reportId)));
        List<Map<String, Object>> revisions = jdbc.queryForList("""
                select id, revision_no as "revisionNo", revision_type as "revisionType",
                       revision_status as "revisionStatus", supersedes_revision_id as "supersedesRevisionId",
                       payload_snapshot::text as "payloadSnapshot", narrative,
                       content_hash as "contentHash", submitted_by_account_id as "submittedByAccountId",
                       submitted_by_assignment_id as "submittedByAssignmentId",
                       submitted_at as "submittedAt", row_version as "rowVersion",
                       created_at as "createdAt", updated_at as "updatedAt"
                from daily_report_revision
                where tenant_id = :tenantId and report_id = :reportId
                order by revision_no desc
                """, base(principal).addValue("reportId", reportId));
        revisions.forEach(revision -> revision.put(
                "payloadSnapshot", parseJson(String.valueOf(revision.get("payloadSnapshot")))));
        result.put("revisions", revisions);
        result.put("plannedHygieneSupported", workEntry.supportsPlannedHygiene(reportId));
        if ("DRAFT".equals(result.get("reportStatus"))
                && java.util.Objects.equals(result.get("positionAssignmentId"), principal.businessActorAssignmentId())
                && principal.hasPermission("work-record.submit")) {
            result.put("inventoryContext", workEntry.editableContext(reportId));
        }
        result.put("itemResults", jdbc.queryForList("""
                select item_result.id, item_result.revision_id as "revisionId",
                       revision.revision_no as "revisionNo",
                       item_result.template_item_id as "templateItemId",
                       template_item.item_code as "itemCode",
                       coalesce(nullif(package_item.report_policy ->> 'factLabel', ''), template_item.label) as label,
                       item_result.result_status as "resultStatus", item_result.value::text as value,
                       item_result.system_prefilled as "systemPrefilled",
                       item_result.employee_confirmed as "employeeConfirmed",
                       item_result.exception_flag as "exceptionFlag",
                       item_result.exception_statement as "exceptionStatement",
                       item_result.source_summary::text as "sourceSummary",
                       item_result.row_version as "rowVersion"
                from daily_report_item_result item_result
                join daily_report_revision revision
                  on revision.tenant_id = item_result.tenant_id and revision.id = item_result.revision_id
                join daily_report_template_item template_item
                  on template_item.tenant_id = item_result.tenant_id
                 and template_item.id = item_result.template_item_id
                left join work_package_item package_item
                  on package_item.tenant_id = template_item.tenant_id
                 and package_item.id = template_item.work_package_item_id
                where revision.tenant_id = :tenantId and revision.report_id = :reportId
                order by revision.revision_no desc, template_item.sort_order, template_item.item_code
                """, base(principal).addValue("reportId", reportId)).stream()
                .map(this::parseItemResult).toList());
        result.put("sources", jdbc.queryForList("""
                select source.id, source.revision_id as "revisionId",
                       source.item_result_id as "itemResultId", source.source_type as "sourceType",
                       source.source_id as "sourceId", source.source_external_key as "sourceExternalKey",
                       source.source_version as "sourceVersion", source.source_status as "sourceStatus",
                       source.source_snapshot::text as "sourceSnapshot",
                       source.source_occurred_at as "sourceOccurredAt", source.linked_at as "linkedAt"
                from daily_report_source_reference source
                join daily_report_revision revision
                  on revision.tenant_id = source.tenant_id and revision.id = source.revision_id
                where revision.tenant_id = :tenantId and revision.report_id = :reportId
                order by source.linked_at
                """, base(principal).addValue("reportId", reportId)).stream()
                .map(this::parseSource).toList());
        boolean sensitive = principal.hasPermission("evidence.sensitive.read") || principal.hasPermission("*");
        result.put("evidence", jdbc.queryForList("""
                select evidence.id, evidence.revision_id as "revisionId",
                       evidence.item_result_id as "itemResultId", evidence.evidence_type as "evidenceType",
                       case when evidence.sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else evidence.object_key end as "objectKey",
                       case when evidence.sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else evidence.original_name end as "originalName",
                       evidence.media_type as "mediaType", evidence.size_bytes as "sizeBytes",
                       case when evidence.sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else evidence.sha256 end as sha256,
                       case when evidence.sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then '{}'::jsonb else evidence.structured_snapshot end::text as metadata,
                       evidence.scan_status as "scanStatus",
                       evidence.sensitivity_level as sensitivity,
                       evidence.invalidated_at as "invalidatedAt", evidence.invalidation_reason as "invalidationReason",
                       evidence.created_at as "createdAt",
                       (evidence.sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive)
                            as "metadataRestricted"
                from daily_report_evidence evidence
                join daily_report_revision revision
                  on revision.tenant_id = evidence.tenant_id and revision.id = evidence.revision_id
                where revision.tenant_id = :tenantId and revision.report_id = :reportId
                order by evidence.created_at
                """, base(principal).addValue("reportId", reportId).addValue("sensitive", sensitive)).stream()
                .map(this::parseEvidence).toList());
        result.put("reviews", jdbc.queryForList("""
                select id, revision_id as "revisionId", review_type as "reviewType", decision,
                       reviewer_account_id as "reviewerAccountId",
                       reviewer_assignment_id as "reviewerAssignmentId", comment,
                       trace_id as "traceId", created_at as "createdAt"
                from daily_report_review
                where tenant_id = :tenantId and report_id = :reportId
                order by created_at
                """, base(principal).addValue("reportId", reportId)));
        List<Map<String, Object>> recommendations = jdbc.queryForList("""
                select recommendation.id, recommendation.fact_summary::text as facts,
                       recommendation.analysis, recommendation.recommendation,
                       recommendation.generated_at as "createdAt",
                       coalesce((
                           select jsonb_agg(label_row.label order by label_row.label)::text
                           from (
                               select distinct concat(source.source_type, ':',
                                      coalesce(source.source_id::text, source.source_external_key)) as label
                               from ai_recommendation_source source
                               where source.tenant_id = recommendation.tenant_id
                                 and source.recommendation_id = recommendation.id
                           ) label_row
                       ), '[]') as "sourceLabelsJson"
                from ai_recommendation recommendation
                join ai_request request
                  on request.tenant_id = recommendation.tenant_id
                 and request.id = recommendation.ai_request_id
                where recommendation.tenant_id = :tenantId
                  and recommendation.status = 'AVAILABLE'
                  and request.request_type = 'DAILY_REPORT_ANALYSIS'
                  and request.input_snapshot ->> 'reportId' = :reportIdText
                  and request.input_snapshot ->> 'revisionId' = :revisionIdText
                order by recommendation.generated_at desc, recommendation.id
                """, base(principal)
                .addValue("reportIdText", reportId.toString())
                .addValue("revisionIdText", String.valueOf(result.get("currentRevisionId"))));
        recommendations.forEach(recommendation -> recommendation.put(
                "sourceLabels", parseJson(String.valueOf(recommendation.remove("sourceLabelsJson")))));
        result.put("aiRecommendations", recommendations);
        return result;
    }

    private void requireVisible(TenantPrincipal principal, UUID reportId) {
        workVisibility.requireReport(principal, reportId);
    }

    private ReportLock lockReport(TenantPrincipal principal, UUID reportId) {
        return jdbc.queryForObject("""
                select report.id, report.hotel_org_unit_id, report.org_unit_id,
                       report.employee_id, employee.account_id as employee_account_id,
                       report.position_assignment_id, report.business_date,
                       report.report_status, report.review_status, report.current_revision_id,
                       report.current_revision_no, report.trace_id, report.created_by_account_id,
                       report.row_version
                from daily_report report
                join employee on employee.tenant_id = report.tenant_id and employee.id = report.employee_id
                where report.tenant_id = :tenantId and report.id = :reportId
                for update of report
                """, base(principal).addValue("reportId", reportId), (resultSet, rowNum) -> new ReportLock(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("hotel_org_unit_id", UUID.class),
                resultSet.getObject("org_unit_id", UUID.class),
                resultSet.getObject("employee_id", UUID.class),
                resultSet.getObject("employee_account_id", UUID.class),
                resultSet.getObject("position_assignment_id", UUID.class),
                resultSet.getObject("business_date", LocalDate.class),
                resultSet.getString("report_status"), resultSet.getString("review_status"),
                resultSet.getObject("current_revision_id", UUID.class), resultSet.getInt("current_revision_no"),
                resultSet.getObject("trace_id", UUID.class),
                resultSet.getObject("created_by_account_id", UUID.class), resultSet.getLong("row_version")));
    }

    private RevisionLock lockRevision(TenantPrincipal principal, UUID reportId, UUID revisionId) {
        return jdbc.queryForObject("""
                select id, revision_no, revision_type, revision_status, payload_snapshot::text,
                       submitted_by_account_id
                from daily_report_revision
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                for update
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId),
                (resultSet, rowNum) -> new RevisionLock(
                        resultSet.getObject("id", UUID.class), resultSet.getInt("revision_no"),
                        resultSet.getString("revision_type"), resultSet.getString("revision_status"),
                        resultSet.getString("payload_snapshot"),
                        resultSet.getObject("submitted_by_account_id", UUID.class)));
    }

    private ObjectNode revisionSnapshotForUpdate(TenantPrincipal principal, UUID reportId, UUID revisionId) {
        String value = jdbc.queryForObject("""
                select payload_snapshot::text from daily_report_revision
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                  and revision_status = 'DRAFT'
                for update
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId), String.class);
        return parseObject(value, "payload_snapshot");
    }

    private Map<UUID, ItemPolicy> itemPolicies(ObjectNode snapshot) {
        JsonNode sections = snapshot.path("resolvedTemplate").path("sections");
        if (!sections.isArray()) throw new IllegalStateException("日报缺少创建时冻结的模板板块");
        Map<UUID, ItemPolicy> result = new LinkedHashMap<>();
        sections.forEach(section -> section.path("items").forEach(item -> {
            UUID id;
            try {
                id = UUID.fromString(item.path("id").asText());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("日报冻结模板包含无效字段标识", exception);
            }
            boolean optionalEntry = WorkEntryPolicy.occursOnDemand(item.path("itemCode").asText());
            boolean removedQuestion = WorkEntryPolicy.genericQuestion(item.path("itemCode").asText(), item.path("label").asText());
            boolean evidenceRequired = !optionalEntry && !removedQuestion && item.path("evidencePolicy").path("required").asBoolean(false);
            boolean sourceRequired = !optionalEntry && !removedQuestion && item.path("dataSourceConfig").path("required").asBoolean(false);
            result.put(id, new ItemPolicy(
                    id, !optionalEntry && !removedQuestion && item.path("required").asBoolean(true), evidenceRequired, sourceRequired,
                    Set.of("SYSTEM", "WORK_RECORD", "AI_SUMMARY").contains(item.path("dataSourceType").asText())));
        }));
        if (result.isEmpty()) throw new IllegalStateException("日报冻结模板没有可填写字段");
        return result;
    }

    private ValidationOutcome validateSubmission(
            TenantPrincipal principal,
            UUID revisionId,
            Map<UUID, ItemPolicy> policies
    ) {
        Map<UUID, ResultValidation> results = new HashMap<>();
        jdbc.query("""
                select id, template_item_id, result_status, exception_flag, exception_statement
                from daily_report_item_result
                where tenant_id = :tenantId and revision_id = :revisionId
                """, base(principal).addValue("revisionId", revisionId), resultSet -> {
            results.put(resultSet.getObject("template_item_id", UUID.class), new ResultValidation(
                    resultSet.getObject("id", UUID.class), resultSet.getString("result_status"),
                    resultSet.getBoolean("exception_flag"), resultSet.getString("exception_statement")));
        });
        int exceptions = 0;
        for (ItemPolicy policy : policies.values()) {
            ResultValidation result = results.get(policy.id());
            if (policy.required() && (result == null || "PENDING".equals(result.status()))) {
                throw new IllegalArgumentException("必填日报字段尚未完成：" + policy.id());
            }
            if (result == null) continue;
            if (result.exception()) {
                exceptions++;
                if (trim(result.exceptionStatement()) == null) {
                    throw new IllegalArgumentException("异常日报字段必须填写异常说明：" + policy.id());
                }
            }
            if (policy.evidenceRequired() && countEvidence(principal, revisionId, result.id()) == 0) {
                throw new IllegalArgumentException("日报字段缺少模板要求的证据：" + policy.id());
            }
            if (policy.sourceRequired() && countSources(principal, revisionId, result.id()) == 0) {
                throw new IllegalArgumentException("日报字段缺少模板要求的数据来源：" + policy.id());
            }
        }
        return new ValidationOutcome(exceptions > 0, exceptions);
    }

    private int countEvidence(TenantPrincipal principal, UUID revisionId, UUID itemResultId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_evidence
                where tenant_id = :tenantId and revision_id = :revisionId and item_result_id = :itemResultId
                  and invalidated_at is null and scan_status <> 'REJECTED'
                """, base(principal).addValue("revisionId", revisionId)
                .addValue("itemResultId", itemResultId), Integer.class);
        return count == null ? 0 : count;
    }

    private int countSources(TenantPrincipal principal, UUID revisionId, UUID itemResultId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_source_reference
                where tenant_id = :tenantId and revision_id = :revisionId and item_result_id = :itemResultId
                  and source_status = 'ACTIVE'
                """, base(principal).addValue("revisionId", revisionId)
                .addValue("itemResultId", itemResultId), Integer.class);
        return count == null ? 0 : count;
    }

    private String reportContentHash(TenantPrincipal principal, UUID revisionId, ObjectNode snapshot) {
        String itemHashes = jdbc.queryForObject("""
                select coalesce(string_agg(coalesce(content_hash, ''), '' order by template_item_id), '')
                from daily_report_item_result where tenant_id = :tenantId and revision_id = :revisionId
                """, base(principal).addValue("revisionId", revisionId), String.class);
        String sourceHashes = jdbc.queryForObject("""
                select coalesce(string_agg(content_hash, '' order by id), '')
                from daily_report_source_reference
                where tenant_id = :tenantId and revision_id = :revisionId and source_status = 'ACTIVE'
                """, base(principal).addValue("revisionId", revisionId), String.class);
        String evidenceHashes = jdbc.queryForObject("""
                select coalesce(string_agg(coalesce(sha256, id::text), '' order by id), '')
                from daily_report_evidence
                where tenant_id = :tenantId and revision_id = :revisionId and invalidated_at is null
                """, base(principal).addValue("revisionId", revisionId), String.class);
        return hash(snapshot.toString() + itemHashes + sourceHashes + evidenceHashes);
    }

    private void copyRevisionFacts(TenantPrincipal principal, UUID oldRevisionId, UUID newRevisionId) {
        jdbc.update("""
                insert into daily_report_item_result
                    (tenant_id, revision_id, template_item_id, result_status, value,
                     system_prefilled, employee_confirmed, exception_flag, exception_statement,
                     source_summary, content_hash)
                select tenant_id, :newRevisionId, template_item_id, result_status, value,
                       system_prefilled, employee_confirmed, exception_flag, exception_statement,
                       source_summary, content_hash
                from daily_report_item_result
                where tenant_id = :tenantId and revision_id = :oldRevisionId
                """, base(principal).addValue("oldRevisionId", oldRevisionId)
                .addValue("newRevisionId", newRevisionId));
        jdbc.update("""
                insert into daily_report_source_reference
                    (tenant_id, revision_id, item_result_id, source_type, source_id,
                     source_external_key, source_version, source_status, source_snapshot,
                     content_hash, source_occurred_at, linked_by_account_id)
                select source.tenant_id, :newRevisionId, new_result.id, source.source_type,
                       source.source_id, source.source_external_key, source.source_version,
                       'ACTIVE', source.source_snapshot, source.content_hash,
                       source.source_occurred_at, :actorId
                from daily_report_source_reference source
                left join daily_report_item_result old_result
                  on old_result.tenant_id = source.tenant_id and old_result.id = source.item_result_id
                left join daily_report_item_result new_result
                  on new_result.tenant_id = source.tenant_id and new_result.revision_id = :newRevisionId
                 and new_result.template_item_id = old_result.template_item_id
                where source.tenant_id = :tenantId and source.revision_id = :oldRevisionId
                  and source.source_status = 'ACTIVE'
                """, base(principal).addValue("oldRevisionId", oldRevisionId)
                .addValue("newRevisionId", newRevisionId).addValue("actorId", principal.actorId()));
        jdbc.update("""
                insert into daily_report_evidence
                    (tenant_id, revision_id, item_result_id, evidence_type, object_key,
                     original_name, media_type, size_bytes, sha256, structured_snapshot,
                     scan_status, sensitivity_level, uploaded_by_account_id,
                     uploaded_by_assignment_id)
                select evidence.tenant_id, :newRevisionId, new_result.id, evidence.evidence_type,
                       evidence.object_key, evidence.original_name, evidence.media_type,
                       evidence.size_bytes, evidence.sha256, evidence.structured_snapshot,
                       evidence.scan_status, evidence.sensitivity_level, :actorId,
                       evidence.uploaded_by_assignment_id
                from daily_report_evidence evidence
                left join daily_report_item_result old_result
                  on old_result.tenant_id = evidence.tenant_id and old_result.id = evidence.item_result_id
                left join daily_report_item_result new_result
                  on new_result.tenant_id = evidence.tenant_id and new_result.revision_id = :newRevisionId
                 and new_result.template_item_id = old_result.template_item_id
                where evidence.tenant_id = :tenantId and evidence.revision_id = :oldRevisionId
                  and evidence.invalidated_at is null
                """, base(principal).addValue("oldRevisionId", oldRevisionId)
                .addValue("newRevisionId", newRevisionId).addValue("actorId", principal.actorId()));
    }

    private void validateSourceBoundary(
            TenantPrincipal principal,
            ReportLock report,
            String sourceType,
            UUID sourceId
    ) {
        boolean internalSource = Set.of(
                "WORK_RECORD", "INSPECTION", "QUALITY_REPORT", "TASK", "METRIC").contains(sourceType);
        if (sourceId == null) {
            if (internalSource) {
                throw new IllegalArgumentException(sourceType + "必须使用当前租户内的sourceId，外部来源请使用OTHER");
            }
            return;
        }
        if (!internalSource) {
            throw new IllegalArgumentException("MANUAL或OTHER来源必须使用sourceExternalKey");
        }
        String sql = switch (sourceType) {
            case "WORK_RECORD", "QUALITY_REPORT" -> """
                    select target_org_unit_id from work_record
                    where tenant_id = :tenantId and id = :sourceId
                    """;
            case "INSPECTION" -> """
                    select target_org_unit_id from work_record
                    where tenant_id = :tenantId and id = :sourceId and record_kind = 'INSPECTION'
                    """;
            case "TASK" -> """
                    select org_unit_id from management_task
                    where tenant_id = :tenantId and id = :sourceId
                    """;
            case "METRIC" -> """
                    select hotel_org_unit_id from metric_observation
                    where tenant_id = :tenantId and id = :sourceId
                    """;
            default -> throw new IllegalArgumentException("不支持的内部来源类型");
        };
        List<UUID> sourceOrganizations = jdbc.queryForList(
                sql, base(principal).addValue("sourceId", sourceId), UUID.class);
        if (sourceOrganizations.size() != 1) {
            throw new IllegalArgumentException("来源不存在、类型不匹配或不属于当前租户");
        }
        UUID sourceOrgUnitId = sourceOrganizations.getFirst();
        accessPolicy.requireOrgScope(sourceOrgUnitId);
        Integer sameHotel = jdbc.queryForObject("""
                select count(*) from org_unit_closure
                where tenant_id = :tenantId and ancestor_id = :hotelOrgUnitId
                  and descendant_id = :sourceOrgUnitId
                """, base(principal).addValue("hotelOrgUnitId", report.hotelOrgUnitId())
                .addValue("sourceOrgUnitId", sourceOrgUnitId), Integer.class);
        if (sameHotel == null || sameHotel != 1) {
            throw new AccessDeniedException("来源不属于日报所在门店");
        }
    }

    private void validateEvidenceObjectKey(
            TenantPrincipal principal,
            ReportLock report,
            UUID revisionId,
            String evidenceType,
            String objectKey
    ) {
        if (objectKey == null || Set.of("LINK", "STRUCTURED").contains(evidenceType)) return;
        if (objectKey.startsWith("/") || objectKey.contains("\\")) {
            throw new IllegalArgumentException("证据objectKey格式不安全");
        }
        for (String segment : objectKey.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("证据objectKey包含无效路径段");
            }
        }
        String tenantPrefix = principal.tenantId() + "/";
        if (!objectKey.startsWith(tenantPrefix)) {
            throw new AccessDeniedException("证据objectKey不属于当前租户");
        }
        List<UUID> attachmentOrganizations = jdbc.queryForList("""
                select record.target_org_unit_id
                from attachment attachment
                join work_record record
                  on record.tenant_id = attachment.tenant_id and record.id = attachment.work_record_id
                where attachment.tenant_id = :tenantId and attachment.object_key = :objectKey
                """, base(principal).addValue("objectKey", objectKey), UUID.class);
        if (!attachmentOrganizations.isEmpty()) {
            UUID sourceOrgUnitId = attachmentOrganizations.getFirst();
            accessPolicy.requireOrgScope(sourceOrgUnitId);
            Integer sameHotel = jdbc.queryForObject("""
                    select count(*) from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id = :hotelOrgUnitId
                      and descendant_id = :sourceOrgUnitId
                    """, base(principal).addValue("hotelOrgUnitId", report.hotelOrgUnitId())
                    .addValue("sourceOrgUnitId", sourceOrgUnitId), Integer.class);
            if (sameHotel == null || sameHotel != 1) {
                throw new AccessDeniedException("附件证据不属于日报所在门店");
            }
            return;
        }
        String reportPrefix = principal.tenantId() + "/daily-reports/" + report.id()
                + "/revisions/" + revisionId + "/";
        if (!objectKey.startsWith(reportPrefix) || objectKey.length() == reportPrefix.length()) {
            throw new AccessDeniedException("新证据objectKey不属于当前日报修订");
        }
    }

    private void verifyReviewerAssignment(TenantPrincipal principal, UUID assignmentId, UUID reportOrgUnitId) {
        accessPolicy.requireActiveAssignment(assignmentId);
        Integer count = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment assignment
                join org_unit_closure scope
                  on scope.tenant_id = assignment.tenant_id
                 and scope.ancestor_id = assignment.org_unit_id
                 and scope.descendant_id = :reportOrgUnitId
                join employee on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE' and employee.account_id = :actorId
                  and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                """, base(principal).addValue("assignmentId", assignmentId)
                .addValue("reportOrgUnitId", reportOrgUnitId).addValue("actorId", principal.actorId()),
                Integer.class);
        if (count == null || count != 1) {
            throw new AccessDeniedException("审核任职不在日报组织的管理范围内");
        }
    }

    private boolean hasExceptions(TenantPrincipal principal, UUID revisionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = :tenantId and revision_id = :revisionId and exception_flag
                """, base(principal).addValue("revisionId", revisionId), Integer.class);
        return count != null && count > 0;
    }

    private void requireDraftRevision(TenantPrincipal principal, ReportLock report, UUID revisionId) {
        if (!"DRAFT".equals(report.reportStatus()) || !revisionId.equals(report.currentRevisionId())) {
            conflict("来源和证据只能添加到当前日报草稿修订");
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_revision
                where tenant_id = :tenantId and report_id = :reportId and id = :revisionId
                  and revision_status = 'DRAFT'
                """, base(principal).addValue("reportId", report.id())
                .addValue("revisionId", revisionId), Integer.class);
        if (count == null || count != 1) conflict("日报修订已经提交，不能再添加来源或证据");
    }

    private void validateItemResult(TenantPrincipal principal, UUID revisionId, UUID itemResultId) {
        if (itemResultId == null) return;
        Integer count = jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = :tenantId and revision_id = :revisionId and id = :itemResultId
                """, base(principal).addValue("revisionId", revisionId)
                .addValue("itemResultId", itemResultId), Integer.class);
        if (count == null || count != 1) throw new IllegalArgumentException("itemResultId不属于当前日报修订");
    }

    private Map<String, Object> sourceRow(TenantPrincipal principal, UUID id) {
        return parseSource(jdbc.queryForMap("""
                select id, revision_id as "revisionId", item_result_id as "itemResultId",
                       source_type as "sourceType", source_id as "sourceId",
                       source_external_key as "sourceExternalKey", source_version as "sourceVersion",
                       source_status as "sourceStatus", source_snapshot::text as "sourceSnapshot",
                       source_occurred_at as "sourceOccurredAt", linked_at as "linkedAt"
                from daily_report_source_reference where tenant_id = :tenantId and id = :id
                """, base(principal).addValue("id", id)));
    }

    private Map<String, Object> evidenceRow(TenantPrincipal principal, UUID id, boolean sensitive) {
        return parseEvidence(jdbc.queryForMap("""
                select id, revision_id as "revisionId", item_result_id as "itemResultId",
                       evidence_type as "evidenceType",
                       case when sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else object_key end as "objectKey",
                       case when sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else original_name end as "originalName",
                       media_type as "mediaType", size_bytes as "sizeBytes",
                       case when sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then null else sha256 end as sha256,
                       case when sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive
                            then '{}'::jsonb else structured_snapshot end::text as metadata,
                       scan_status as "scanStatus", sensitivity_level as sensitivity,
                       created_at as "createdAt",
                       (sensitivity_level in ('SENSITIVE', 'RESTRICTED') and not :sensitive)
                            as "metadataRestricted"
                from daily_report_evidence where tenant_id = :tenantId and id = :id
                """, base(principal).addValue("id", id).addValue("sensitive", sensitive)));
    }

    private Map<String, Object> parseItemResult(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("value", parseNullableJson(result.get("value")));
        result.put("sourceSummary", parseNullableJson(result.get("sourceSummary")));
        return result;
    }

    private Map<String, Object> parseSource(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("sourceSnapshot", parseNullableJson(result.get("sourceSnapshot")));
        return result;
    }

    private Map<String, Object> parseEvidence(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("metadata", parseNullableJson(result.get("metadata")));
        return result;
    }

    private void requireOwner(TenantPrincipal principal, ReportLock report) {
        if (!principal.actorId().equals(report.employeeAccountId())
                || !principal.assignmentIds().contains(report.positionAssignmentId())) {
            throw new AccessDeniedException("只有日报所属员工可以维护和提交该日报");
        }
    }

    private static void requireExpected(ReportLock report, long expectedVersion) {
        if (report.rowVersion() != expectedVersion) conflict("日报已被其他请求修改，请刷新后重试");
    }

    private void incrementReportVersion(TenantPrincipal principal, UUID reportId, long expectedVersion) {
        int changed = jdbc.update("""
                update daily_report set row_version = row_version + 1
                where tenant_id = :tenantId and id = :reportId and row_version = :expectedVersion
                """, base(principal).addValue("reportId", reportId).addValue("expectedVersion", expectedVersion));
        if (changed != 1) conflict("日报已被其他请求修改，请刷新后重试");
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private Map<String, Object> replay(CommandIdempotencyService.Reservation reservation) {
        return objectMapper.convertValue(
                reservation.responseSnapshot(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private JsonNode toJsonNode(Object value) {
        if (value instanceof JsonNode node) return node;
        return objectMapper.valueToTree(value);
    }

    private JsonNode parseNullableJson(Object value) {
        if (value == null) return null;
        if (value instanceof JsonNode node) return node;
        return parseJson(String.valueOf(value));
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("数据库中的日报JSON不是有效格式", exception);
        }
    }

    private ObjectNode parseObject(String value, String field) {
        JsonNode node = parseJson(value);
        if (!node.isObject()) throw new IllegalStateException(field + "不是JSON对象");
        return (ObjectNode) node;
    }

    private JsonNode objectNodeOrEmpty(JsonNode value, String field) {
        if (value == null || value.isNull()) return objectMapper.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException(field + "必须是JSON对象");
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法生成日报审计快照", exception);
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

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        return normalizeAllowed(value, REPORT_STATUSES, "status");
    }

    private static String normalizeAllowed(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + "取值不受支持");
        return normalized;
    }

    private static String normalizeAllowedDefault(
            String value,
            Set<String> allowed,
            String field,
            String fallback
    ) {
        return value == null || value.isBlank() ? fallback : normalizeAllowed(value, allowed, field);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ReportLock(
            UUID id,
            UUID hotelOrgUnitId,
            UUID orgUnitId,
            UUID employeeId,
            UUID employeeAccountId,
            UUID positionAssignmentId,
            LocalDate businessDate,
            String reportStatus,
            String reviewStatus,
            UUID currentRevisionId,
            int currentRevisionNo,
            UUID traceId,
            UUID createdByAccountId,
            long rowVersion
    ) {
    }

    private record RevisionLock(
            UUID id,
            int revisionNo,
            String type,
            String status,
            String payloadSnapshot,
            UUID submittedByAccountId
    ) {
    }

    private record ItemPolicy(UUID id, boolean required, boolean evidenceRequired, boolean sourceRequired,
                              boolean systemManaged) {
    }

    private record ResultValidation(UUID id, String status, boolean exception, String exceptionStatement) {
    }

    private record ValidationOutcome(boolean hasExceptions, int exceptionCount) {
    }
}
