package cn.sifangguan.hotelaios.workdata;

import cn.sifangguan.hotelaios.dailyreports.DailyReportRoutineProjectionService;
import cn.sifangguan.hotelaios.storeassets.StoreWorkUsageService;
import cn.sifangguan.hotelaios.workpackage.WeeklyAssessmentService;
import cn.sifangguan.hotelaios.notifications.StoreManagerSupervisorResolver;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class WorkDataService {
    private static final Set<String> RECORD_KINDS = Set.of(
            "LEGACY", "SCHEDULED", "EVENT", "INSPECTION", "METRIC_REVIEW", "REVIEW_APPROVAL"
    );
    private static final Map<String, String> FACILITY_FLAG_KEYS = Map.ofEntries(
            Map.entry("BREAKFAST_AREA", "breakfastArea"),
            Map.entry("KITCHEN", "kitchen"),
            Map.entry("AFTERNOON_TEA", "afternoonTea"),
            Map.entry("BAR", "bar"),
            Map.entry("PROJECTION", "projection"),
            Map.entry("GYM", "gym"),
            Map.entry("TERRACE", "terrace"),
            Map.entry("TRENDY_PLAY_AREA", "trendyPlayArea")
    );
    private static final Set<String> ALLOWED_EVIDENCE_KINDS = Set.of(
            "IMAGE", "PDF", "WORD", "EXCEL", "DOCUMENT"
    );
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final WorkVisibilityPolicy workVisibility;
    private final AuditWriter auditWriter;
    private final FormPayloadValidator payloadValidator;
    private final ObjectMapper objectMapper;
    private final DailyReportRoutineProjectionService routineProjectionService;
    private final StoreWorkUsageService usageService;
    private final WeeklyAssessmentService weeklyAssessments;
    private final StoreManagerSupervisorResolver supervisors;

    public WorkDataService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            WorkVisibilityPolicy workVisibility,
            AuditWriter auditWriter,
            FormPayloadValidator payloadValidator,
            ObjectMapper objectMapper,
            DailyReportRoutineProjectionService routineProjectionService,
            StoreWorkUsageService usageService,
            WeeklyAssessmentService weeklyAssessments,
            StoreManagerSupervisorResolver supervisors
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.workVisibility = workVisibility;
        this.auditWriter = auditWriter;
        this.payloadValidator = payloadValidator;
        this.objectMapper = objectMapper;
        this.routineProjectionService = routineProjectionService;
        this.usageService = usageService;
        this.weeklyAssessments = weeklyAssessments;
        this.supervisors = supervisors;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forms() {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.read");
        return jdbc.queryForList("""
                select f.id, f.code, f.name, f.form_type, f.position_id, p.name as position_name,
                       v.id as latest_version_id, v.version_no, v.lifecycle_status
                from form_definition f
                left join position_definition p on p.tenant_id = f.tenant_id and p.id = f.position_id
                left join lateral (
                    select fv.id, fv.version_no, fv.lifecycle_status
                    from form_version fv
                    where fv.tenant_id = f.tenant_id and fv.form_id = f.id
                    order by fv.version_no desc limit 1
                ) v on true
                where f.tenant_id = :tenantId
                order by f.form_type, f.name
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createForm(WorkDataModels.CreateForm request) {
        accessPolicy.requireConfigurationAdmin();
        TenantPrincipal principal = prepare();
        if (request.positionId() != null) {
            requireOwned("position_definition", principal, request.positionId());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into form_definition (id, tenant_id, code, name, form_type, position_id)
                values (:id, :tenantId, :code, :name, :formType, :positionId)
                """, base(principal)
                .addValue("id", id)
                .addValue("code", request.code().trim().toUpperCase(Locale.ROOT))
                .addValue("name", request.name().trim())
                .addValue("formType", request.formType().toUpperCase(Locale.ROOT))
                .addValue("positionId", request.positionId()));
        auditWriter.record("FORM_CREATED", "FORM", id, "{\"code\":\"" + jsonEscape(request.code()) + "\"}");
        return response("id", id, "code", request.code(), "name", request.name());
    }

    @Transactional
    public Map<String, Object> createFormVersion(UUID formId, WorkDataModels.CreateFormVersion request) {
        accessPolicy.requireConfigurationAdmin();
        TenantPrincipal principal = prepare();
        requireOwned("form_definition", principal, formId);
        Integer versionNo = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) + 1 from form_version
                where tenant_id = :tenantId and form_id = :formId
                """, base(principal).addValue("formId", formId), Integer.class);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into form_version
                    (id, tenant_id, form_id, version_no, json_schema, ui_schema)
                values
                    (:id, :tenantId, :formId, :versionNo, cast(:jsonSchema as jsonb), cast(:uiSchema as jsonb))
                """, base(principal)
                .addValue("id", id)
                .addValue("formId", formId)
                .addValue("versionNo", versionNo)
                .addValue("jsonSchema", request.jsonSchema().toString())
                .addValue("uiSchema", (request.uiSchema() == null ? JsonNodeFactory.instance.objectNode() : request.uiSchema()).toString()));
        return response("id", id, "formId", formId, "versionNo", versionNo, "status", "DRAFT");
    }

    @Transactional
    public Map<String, Object> publishFormVersion(UUID formId, UUID versionId) {
        accessPolicy.requireConfigurationAdmin();
        TenantPrincipal principal = prepare();
        int count = jdbc.update("""
                update form_version set lifecycle_status = 'PUBLISHED', published_at = now()
                where tenant_id = :tenantId and form_id = :formId and id = :versionId
                  and lifecycle_status = 'DRAFT'
                """, base(principal).addValue("formId", formId).addValue("versionId", versionId));
        if (count != 1) {
            throw new IllegalArgumentException("只有当前租户的草稿表单版本可以发布");
        }
        jdbc.update("""
                update form_version set lifecycle_status = 'RETIRED'
                where tenant_id = :tenantId and form_id = :formId and id <> :versionId
                  and lifecycle_status = 'PUBLISHED'
                """, base(principal).addValue("formId", formId).addValue("versionId", versionId));
        auditWriter.emit("FORM", formId, "FormPublished",
                "{\"formId\":\"" + formId + "\",\"versionId\":\"" + versionId + "\"}");
        return response("formId", formId, "versionId", versionId, "status", "PUBLISHED");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> records() {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.read");
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty() && principal.assignmentIds().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = base(principal);
        String visibility = " and " + workVisibility.scope(principal, params).record("w");
        return jdbc.queryForList("""
                select w.id, w.business_date, w.status, w.record_kind, w.payload, w.occurred_at,
                       w.submitted_at, w.series_entry_no, w.repeatable_entry,
                       w.attempt_no, w.row_version, w.content_hash,
                       w.work_package_version_id, w.work_package_item_id, w.work_expectation_id,
                       w.org_unit_id, owner_org.name as org_unit_name,
                       w.target_org_unit_id, target_org.name as target_org_unit_name,
                       e.name as employee_name, p.name as position_name, f.name as form_name,
                       i.item_code as work_package_item_code, i.name as work_package_item_name
                from work_record w
                join org_unit owner_org on owner_org.tenant_id = w.tenant_id and owner_org.id = w.org_unit_id
                join org_unit target_org on target_org.tenant_id = w.tenant_id and target_org.id = w.target_org_unit_id
                join employee e on e.tenant_id = w.tenant_id and e.id = w.employee_id
                join employee_position_assignment a on a.tenant_id = w.tenant_id and a.id = w.position_assignment_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join form_version fv on fv.tenant_id = w.tenant_id and fv.id = w.form_version_id
                join form_definition f on f.tenant_id = fv.tenant_id and f.id = fv.form_id
                left join work_package_item i on i.tenant_id = w.tenant_id and i.id = w.work_package_item_id
                where w.tenant_id = :tenantId
                """ + visibility + " order by w.business_date desc, w.created_at desc limit 500", params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> record(UUID recordId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission("work-record.read", "work-record.review", "work-record.team-read");
        MapSqlParameterSource params = base(principal).addValue("recordId", recordId);
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                select w.*, owner_org.name as org_unit_name, target_org.name as target_org_unit_name,
                       e.name as employee_name, p.name as position_name, f.name as form_name,
                       i.item_code as work_package_item_code, i.name as work_package_item_name
                from work_record w
                join org_unit owner_org on owner_org.tenant_id = w.tenant_id and owner_org.id = w.org_unit_id
                join org_unit target_org on target_org.tenant_id = w.tenant_id and target_org.id = w.target_org_unit_id
                join employee e on e.tenant_id = w.tenant_id and e.id = w.employee_id
                join employee_position_assignment a on a.tenant_id = w.tenant_id and a.id = w.position_assignment_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join form_version fv on fv.tenant_id = w.tenant_id and fv.id = w.form_version_id
                join form_definition f on f.tenant_id = fv.tenant_id and f.id = fv.form_id
                left join work_package_item i on i.tenant_id = w.tenant_id and i.id = w.work_package_item_id
                where w.tenant_id = :tenantId and w.id = :recordId
                """, params));
        workVisibility.requireRecord(principal, recordId);
        result.put("attachments", jdbc.queryForList("""
                select id, object_key, original_name, media_type, size_bytes, sha256, scan_status,
                       capture_source, checkpoint_code, captured_at_client, received_at,
                       source_sha256, evidence_metadata,
                       evidence_metadata ->> 'displayOrientation' as display_orientation,
                       nullif(evidence_metadata ->> 'normalizedWidth', '')::integer as normalized_width,
                       nullif(evidence_metadata ->> 'normalizedHeight', '')::integer as normalized_height,
                       evidence_metadata ->> 'evidenceInstanceKey' as evidence_instance_key,
                       created_at
                from attachment where tenant_id = :tenantId and work_record_id = :recordId
                order by created_at
                """, params));
        result.put("supplements", jdbc.queryForList("""
                select supplement.id, supplement.submitted_by_assignment_id,
                       employee.name as submitted_by_name, supplement.content, supplement.created_at
                from work_record_supplement supplement
                join employee_position_assignment assignment
                  on assignment.tenant_id = supplement.tenant_id
                 and assignment.id = supplement.submitted_by_assignment_id
                join employee on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where supplement.tenant_id = :tenantId and supplement.work_record_id = :recordId
                order by supplement.created_at, supplement.id
                """, params));
        result.put("inventoryUsage", usageService.detail(recordId));
        return result;
    }

    @Transactional
    public Map<String, Object> submit(WorkDataModels.SubmitWorkRecord request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        UUID targetOrgUnitId = request.targetOrgUnitId() == null ? request.orgUnitId() : request.targetOrgUnitId();
        requireTargetOrgScopeOrAssignmentMembership(principal, targetOrgUnitId,
                request.positionAssignmentId(), request.businessDate());
        authorizeAssignmentSubmission(principal, request.positionAssignmentId(), request.orgUnitId());
        validateAssignmentAndForm(principal, request);
        usageService.lockDay(targetOrgUnitId, request.employeeId(), request.businessDate());
        WorkContext workContext = resolveWorkContext(principal, request, targetOrgUnitId);
        if (WorkEntryPolicy.mergedIntoDailyReport(workContext.itemCode()) && request.supersedesWorkRecordId() == null) {
            throw new IllegalArgumentException("服务案例与遗留物已合并到客房日报，请在日报中填写并一同提交；历史草稿可在原记录继续完成");
        }
        boolean saveAsDraft = Boolean.TRUE.equals(request.saveAsDraft());
        if (saveAsDraft) {
            validateWorkPayload(principal, request.formVersionId(), workContext.itemId(), request.payload(), true);
        } else {
            validateWorkPayload(principal, request.formVersionId(), workContext.itemId(), request.payload(), false);
        }
        RecordSequence sequence = nextRecordSequence(principal, request, workContext);
        int attemptNo = sequence.attemptNo();
        String recordKind = resolveRecordKind(request.recordKind(), workContext.itemType());
        String status = saveAsDraft ? "DRAFT" : "SUBMITTED";
        boolean applicable = true;
        if ("SUBMITTED".equals(status)) {
            if (!workContext.repeatableDailyRecords()) {
                requireFirstSharedSubmission(principal, workContext.expectationId(), null);
            }
            validateConditionalFields(workContext.itemCode(), request.payload());
            weeklyAssessments.validateSubmission(workContext.itemCode(), workContext.expectationId(), request.payload());
            applicable = validateSubmissionPolicy(principal, workContext.itemId(), null,
                    request.payload(), targetOrgUnitId,
                    request.completionStatement(), request.exceptionStatement(), request.nextAction());
        }
        OffsetDateTime occurredAt = request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
        String contentHash = sha256(request.payload().toString());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into work_record
                    (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                     form_version_id, business_date, status, payload, submitted_at,
                     work_package_version_id, work_package_item_id, work_expectation_id,
                     record_kind, target_org_unit_id, occurred_at, submitted_by_account_id,
                     supersedes_work_record_id, series_entry_no, repeatable_entry, attempt_no, content_hash,
                     completion_statement, exception_statement, next_action)
                values
                    (:id, :tenantId, :orgUnitId, :employeeId, :assignmentId,
                     :formVersionId, :businessDate, :status, cast(:payload as jsonb), :submittedAt,
                     :workPackageVersionId, :workPackageItemId, :workExpectationId,
                     :recordKind, :targetOrgUnitId, :occurredAt, :actorId,
                     :supersedesWorkRecordId, :seriesEntryNo, :repeatableEntry, :attemptNo, :contentHash,
                     :completionStatement, :exceptionStatement, :nextAction)
                """, base(principal)
                .addValue("id", id)
                .addValue("orgUnitId", request.orgUnitId())
                .addValue("employeeId", request.employeeId())
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("formVersionId", request.formVersionId())
                .addValue("businessDate", request.businessDate())
                .addValue("status", status)
                .addValue("payload", request.payload().toString())
                .addValue("submittedAt", "SUBMITTED".equals(status) ? OffsetDateTime.now() : null)
                .addValue("workPackageVersionId", workContext.versionId())
                .addValue("workPackageItemId", workContext.itemId())
                .addValue("workExpectationId", workContext.expectationId())
                .addValue("recordKind", recordKind)
                .addValue("targetOrgUnitId", targetOrgUnitId)
                .addValue("occurredAt", occurredAt)
                .addValue("actorId", principal.actorId())
                .addValue("supersedesWorkRecordId", request.supersedesWorkRecordId())
                .addValue("seriesEntryNo", sequence.seriesEntryNo())
                .addValue("repeatableEntry", workContext.repeatableDailyRecords())
                .addValue("attemptNo", attemptNo)
                .addValue("contentHash", contentHash)
                .addValue("completionStatement", trimToNull(request.completionStatement()))
                .addValue("exceptionStatement", trimToNull(request.exceptionStatement()))
                .addValue("nextAction", trimToNull(request.nextAction())));
        updateExpectationAfterRecord(principal, workContext.expectationId(), status, applicable,
                workContext.repeatableDailyRecords());
        auditWriter.record("DRAFT".equals(status) ? "WORK_RECORD_DRAFT_CREATED" : "WORK_RECORD_SUBMITTED",
                "WORK_RECORD", id, request.payload().toString());
        if ("SUBMITTED".equals(status)) {
            usageService.registerSubmitted(id);
            emitSubmitted(principal, id);
            routineProjectionService.projectWorkRecord(id);
            if (applicable && !usageService.hasUsage(id)) {
                notifySubmittedForReview(principal, id);
            }
        }
        return response("id", id, "status", status, "businessDate", request.businessDate(),
                "recordKind", recordKind, "seriesEntryNo", sequence.seriesEntryNo(),
                "attemptNo", attemptNo, "rowVersion", 0L,
                "applicabilityStatus", applicable ? "APPLICABLE" : "NOT_APPLICABLE");
    }

    @Transactional
    public Map<String, Object> updateDraft(UUID recordId, WorkDataModels.UpdateDraft request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordAccess record = recordAccess(principal, recordId);
        authorizeRecordMutation(principal, record);
        if (!"DRAFT".equals(record.status())) {
            throw new IllegalArgumentException("只有草稿工作记录可以更新");
        }
        validateWorkPayload(principal, record.formVersionId(), record.workPackageItemId(), request.payload(), true);
        OffsetDateTime occurredAt = request.occurredAt() == null ? record.occurredAt() : request.occurredAt();
        int changed = jdbc.update("""
                update work_record
                set payload = cast(:payload as jsonb), occurred_at = :occurredAt,
                    content_hash = :contentHash, completion_statement = :completionStatement,
                    exception_statement = :exceptionStatement, next_action = :nextAction,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :recordId and status = 'DRAFT'
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("recordId", recordId)
                .addValue("payload", request.payload().toString())
                .addValue("occurredAt", occurredAt)
                .addValue("contentHash", sha256(request.payload().toString()))
                .addValue("completionStatement", trimToNull(request.completionStatement()))
                .addValue("exceptionStatement", trimToNull(request.exceptionStatement()))
                .addValue("nextAction", trimToNull(request.nextAction()))
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new IllegalArgumentException("工作记录版本已变化，请刷新后重试");
        }
        auditWriter.record("WORK_RECORD_DRAFT_UPDATED", "WORK_RECORD", recordId, request.payload().toString());
        return response("id", recordId, "status", "DRAFT", "rowVersion", request.expectedVersion() + 1);
    }

    @Transactional
    public Map<String, Object> submitDraft(UUID recordId, WorkDataModels.SubmitDraft request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordAccess record = recordAccess(principal, recordId);
        authorizeRecordMutation(principal, record);
        if (!"DRAFT".equals(record.status())) {
            throw new IllegalArgumentException("只有草稿工作记录可以提交");
        }
        validateWorkPayload(principal, record.formVersionId(), record.workPackageItemId(), readJson(record.payloadJson()), false);
        usageService.lockAssignmentDay(record.targetOrgUnitId(), record.assignmentId(), record.businessDate());
        if (!record.repeatableEntry()) {
            requireFirstSharedSubmission(principal, record.expectationId(), recordId);
        }
        boolean applicable = validateSubmissionPolicyForRecord(principal, recordId);
        int changed = jdbc.update("""
                update work_record
                set status = 'SUBMITTED', submitted_at = now(), submitted_by_account_id = :actorId,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :recordId and status = 'DRAFT'
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("recordId", recordId)
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new IllegalArgumentException("工作记录版本已变化，请刷新后重试");
        }
        updateExpectationAfterRecord(principal, record.expectationId(), "SUBMITTED", applicable,
                record.repeatableEntry());
        auditWriter.record("WORK_RECORD_SUBMITTED", "WORK_RECORD", recordId, record.payloadJson());
        usageService.registerSubmitted(recordId);
        emitSubmitted(principal, recordId);
        routineProjectionService.projectWorkRecord(recordId);
        if (applicable && !usageService.hasUsage(recordId)) {
            notifySubmittedForReview(principal, recordId);
        }
        return response("id", recordId, "status", "SUBMITTED", "rowVersion", request.expectedVersion() + 1,
                "applicabilityStatus", applicable ? "APPLICABLE" : "NOT_APPLICABLE");
    }

    @Transactional
    public Map<String, Object> review(UUID recordId, WorkDataModels.ReviewRecord request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission("work-record.review", "manager-cycle.review");
        RecordAccess record = recordAccessForUpdate(principal, recordId);
        if (!isManagerCycle(principal, record.workPackageItemId())) accessPolicy.requirePermission("work-record.review");
        accessPolicy.requireOrgScope(record.targetOrgUnitId());
        if (usageService.isReviewRetry(recordId, request.outcome().trim().toUpperCase(Locale.ROOT), request.expectedVersion())) {
            return response("id", recordId, "status", record.status(), "rowVersion", request.expectedVersion() + 1);
        }
        if (!"SUBMITTED".equals(record.status())) {
            throw new IllegalArgumentException("只有已提交工作记录可以复核");
        }
        if (principal.actorId().equals(record.submittedByAccountId())) {
            throw new AccessDeniedException("提交人不能复核自己的工作记录");
        }
        if (usageService.hasUsage(recordId)) usageService.requireReviewer(recordId);
        else requireConfiguredReviewer(principal, record);
        String outcome = request.outcome().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(outcome)) {
            throw new IllegalArgumentException("复核结果只能是APPROVED或REJECTED");
        }
        if ("REJECTED".equals(outcome) && (request.reason() == null || request.reason().isBlank())) {
            throw new IllegalArgumentException("退回工作记录必须填写原因");
        }
        int changed = jdbc.update("""
                update work_record
                set status = :outcome, reviewed_by_account_id = :actorId, reviewed_at = now(),
                    review_reason = :reason, row_version = row_version + 1
                where tenant_id = :tenantId and id = :recordId and status = 'SUBMITTED'
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("recordId", recordId)
                .addValue("outcome", outcome)
                .addValue("actorId", principal.actorId())
                .addValue("reason", trimToNull(request.reason()))
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new IllegalArgumentException("工作记录状态或版本已变化，请刷新后重试");
        }
        if (record.expectationId() != null && record.repeatableEntry()) {
            refreshRepeatableExpectationStatus(principal, record.expectationId());
        } else if (record.expectationId() != null) {
            jdbc.update("""
                    update work_expectation
                    set status = :expectationStatus, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :expectationId
                      and status in ('SUBMITTED', 'SATISFIED', 'FAILED')
                    """, base(principal)
                    .addValue("expectationId", record.expectationId())
                    .addValue("expectationStatus", "APPROVED".equals(outcome) ? "SATISFIED" : "FAILED"));
        }
        usageService.onReview(recordId, outcome);
        auditWriter.record("WORK_RECORD_" + outcome, "WORK_RECORD", recordId,
                "{\"reason\":\"" + jsonEscape(request.reason() == null ? "" : request.reason().trim()) + "\"}");
        auditWriter.emit("WORK_RECORD", recordId,
                "APPROVED".equals(outcome) ? "WorkRecordApproved" : "WorkRecordRejected",
                "{\"workRecordId\":\"" + recordId + "\",\"orgUnitId\":\""
                        + record.targetOrgUnitId() + "\",\"positionAssignmentId\":\""
                        + record.assignmentId() + "\"}");
        routineProjectionService.projectWorkRecord(recordId);
        return response("id", recordId, "status", outcome, "rowVersion", request.expectedVersion() + 1);
    }

    @Transactional
    public Map<String, Object> addAttachment(UUID workRecordId, WorkDataModels.AddAttachment request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordAccess record = recordAccessForUpdate(principal, workRecordId);
        authorizeRecordMutation(principal, record);
        if (Set.of("APPROVED", "REJECTED").contains(record.status())) {
            throw new IllegalArgumentException("已完成复核的工作记录不能追加附件");
        }
        UUID attachmentId = UUID.randomUUID();
        String safeName = request.originalName().replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = principal.tenantId() + "/work-records/" + workRecordId + "/" + attachmentId + "-" + safeName;
        jdbc.update("""
                insert into attachment
                    (id, tenant_id, work_record_id, object_key, original_name, media_type, size_bytes, sha256)
                values
                    (:id, :tenantId, :workRecordId, :objectKey, :originalName, :mediaType, :sizeBytes, :sha256)
                """, base(principal)
                .addValue("id", attachmentId)
                .addValue("workRecordId", workRecordId)
                .addValue("objectKey", objectKey)
                .addValue("originalName", request.originalName())
                .addValue("mediaType", request.mediaType())
                .addValue("sizeBytes", request.sizeBytes())
                .addValue("sha256", request.sha256()));
        auditWriter.record("WORK_RECORD_ATTACHMENT_ADDED", "ATTACHMENT", attachmentId,
                "{\"workRecordId\":\"" + workRecordId + "\"}");
        return response("id", attachmentId, "objectKey", objectKey, "scanStatus", "PENDING");
    }

    @Transactional
    public Map<String, Object> addSupplement(UUID workRecordId, WorkDataModels.AddSupplement request) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordAccess record = recordAccess(principal, workRecordId);
        authorizeRecordMutation(principal, record);
        if (!record.assignmentId().equals(request.submittedByAssignmentId())) {
            throw new AccessDeniedException("补充说明必须绑定原工作记录任职");
        }
        if (!"SUBMITTED".equals(record.status())) {
            throw new IllegalArgumentException("只有待复核工作记录可以追加说明");
        }
        UUID supplementId = UUID.randomUUID();
        String content = request.content().trim();
        jdbc.update("""
                insert into work_record_supplement
                    (id, tenant_id, work_record_id, submitted_by_assignment_id, content)
                values (:id, :tenantId, :recordId, :assignmentId, :content)
                """, base(principal).addValue("id", supplementId).addValue("recordId", workRecordId)
                .addValue("assignmentId", request.submittedByAssignmentId()).addValue("content", content));
        auditWriter.record("WORK_RECORD_SUPPLEMENT_ADDED", "WORK_RECORD_SUPPLEMENT", supplementId,
                "{\"workRecordId\":\"" + workRecordId + "\"}");
        return response("id", supplementId, "workRecordId", workRecordId,
                "submittedByAssignmentId", request.submittedByAssignmentId(), "content", content);
    }

    private void validateAssignmentAndForm(TenantPrincipal principal, WorkDataModels.SubmitWorkRecord request) {
        Integer validAssignment = jdbc.queryForObject("""
                select count(*) from employee_position_assignment a
                join form_version fv on fv.tenant_id = a.tenant_id and fv.id = :formVersionId
                join form_definition f on f.tenant_id = fv.tenant_id and f.id = fv.form_id
                where a.tenant_id = :tenantId and a.id = :assignmentId and a.employee_id = :employeeId
                  and a.org_unit_id = :orgUnitId and a.status = 'ACTIVE'
                  and a.valid_from <= :businessDate and (a.valid_to is null or a.valid_to >= :businessDate)
                  and exists (
                    select 1
                    from org_unit_closure target_scope
                    where target_scope.tenant_id = a.tenant_id
                      and target_scope.ancestor_id = :targetOrgUnitId
                      and target_scope.descendant_id = a.org_unit_id
                  )
                  and fv.lifecycle_status = 'PUBLISHED'
                  and (f.position_id is null or f.position_id = a.position_id or :linkedExpectation = true)
                """, base(principal)
                .addValue("formVersionId", request.formVersionId())
                .addValue("assignmentId", request.positionAssignmentId())
                .addValue("employeeId", request.employeeId())
                .addValue("orgUnitId", request.orgUnitId())
                .addValue("targetOrgUnitId", request.targetOrgUnitId() == null
                        ? request.orgUnitId() : request.targetOrgUnitId())
                .addValue("linkedExpectation", request.workExpectationId() != null)
                .addValue("businessDate", request.businessDate()), Integer.class);
        if (validAssignment == null || validAssignment != 1) {
            throw new IllegalArgumentException("任职、岗位、组织与已发布表单不匹配");
        }
    }

    private void requireTargetOrgScopeOrAssignmentMembership(
            TenantPrincipal principal,
            UUID targetOrgUnitId,
            UUID assignmentId,
            java.time.LocalDate businessDate
    ) {
        if (principal.hasTenantScope() || principal.orgScopes().contains(targetOrgUnitId)) {
            return;
        }
        Boolean belongsToTarget = jdbc.queryForObject("""
                select exists (
                  select 1
                  from employee_position_assignment assignment
                  join org_unit_closure target_scope
                    on target_scope.tenant_id = assignment.tenant_id
                   and target_scope.ancestor_id = :targetOrgUnitId
                   and target_scope.descendant_id = assignment.org_unit_id
                  where assignment.tenant_id = :tenantId
                    and assignment.id = :assignmentId
                    and assignment.status = 'ACTIVE'
                    and assignment.valid_from <= :businessDate
                    and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                )
                """, base(principal)
                .addValue("targetOrgUnitId", targetOrgUnitId)
                .addValue("assignmentId", assignmentId)
                .addValue("businessDate", businessDate), Boolean.class);
        if (!Boolean.TRUE.equals(belongsToTarget)) {
            throw new AccessDeniedException("目标组织不在当前任职所属门店范围内");
        }
    }

    private boolean validateSubmissionPolicyForRecord(TenantPrincipal principal, UUID recordId) {
        Map<String, Object> record = jdbc.queryForMap("""
                select record.work_package_item_id, record.target_org_unit_id, record.completion_statement,
                       record.exception_statement, record.next_action, record.payload::text,
                       item.item_code, record.work_expectation_id
                from work_record record
                left join work_package_item item
                  on item.tenant_id = record.tenant_id and item.id = record.work_package_item_id
                where record.tenant_id = :tenantId and record.id = :recordId
                """, base(principal).addValue("recordId", recordId));
        validateConditionalFields((String) record.get("item_code"), readJson((String) record.get("payload")));
        weeklyAssessments.validateSubmission((String) record.get("item_code"), (UUID) record.get("work_expectation_id"),
                readJson((String) record.get("payload")));
        return validateSubmissionPolicy(principal, (UUID) record.get("work_package_item_id"), recordId,
                readJson((String) record.get("payload")), (UUID) record.get("target_org_unit_id"),
                (String) record.get("completion_statement"), (String) record.get("exception_statement"),
                (String) record.get("next_action"));
    }

    private boolean validateSubmissionPolicy(
            TenantPrincipal principal,
            UUID workPackageItemId,
            UUID workRecordId,
            JsonNode payload,
            UUID targetOrgUnitId,
            String completionStatement,
            String exceptionStatement,
            String nextAction
    ) {
        if (workPackageItemId == null) {
            return true;
        }
        Map<String, Object> policyRow = jdbc.queryForMap("""
                select submission_policy::text as submission_policy,
                       applicability_policy::text as applicability_policy,
                       execution_policy::text as execution_policy, item_code
                from work_package_item
                where tenant_id = :tenantId and id = :itemId
                """, base(principal).addValue("itemId", workPackageItemId));
        JsonNode submissionPolicy = readJson(String.valueOf(policyRow.get("submission_policy")));
        JsonNode applicabilityPolicy = readJson(String.valueOf(policyRow.get("applicability_policy")));
        JsonNode executionPolicy = readJson(String.valueOf(policyRow.get("execution_policy")));
        if ("HKA_LOST_PROPERTY".equals(policyRow.get("item_code"))) {
            UUID recipient;
            try { recipient = UUID.fromString(payload.path("handoverRecipientAssignmentId").asText()); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("请选择有效的遗留物接收人"); }
            Boolean validRecipient = jdbc.queryForObject("""
                    select exists(select 1 from employee_position_assignment a
                      join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                      join user_account account on account.tenant_id=e.tenant_id and account.id=e.account_id
                      join org_unit_closure scope on scope.tenant_id=a.tenant_id and scope.descendant_id=a.org_unit_id
                      where a.tenant_id=:tenantId and a.id=:recipient and scope.ancestor_id=:targetOrgUnitId
                        and a.status='ACTIVE' and a.valid_from<=current_date
                        and (a.valid_to is null or a.valid_to>=current_date)
                        and e.employment_status='ACTIVE' and e.deleted_at is null and account.status='ACTIVE'
                        and account.id<>:actorId)
                    """, base(principal).addValue("recipient", recipient).addValue("targetOrgUnitId", targetOrgUnitId)
                    .addValue("actorId", principal.actorId()), Boolean.class);
            if (!Boolean.TRUE.equals(validRecipient)) throw new IllegalArgumentException("遗留物接收人必须是本店有效员工，且不能选择本人");
        }
        // Generic work-result prose is no longer a submission requirement. Business-specific
        // facts and evidence are still validated by the item form and evidence policy.
        if (submissionPolicy.path("exceptionStatementRequired").asBoolean(false)
                && (exceptionStatement == null || exceptionStatement.isBlank())) {
            throw new IllegalArgumentException("提交工作记录必须填写异常与协同事项");
        }
        if (submissionPolicy.path("nextActionRequired").asBoolean(false)
                && (nextAction == null || nextAction.isBlank())) {
            throw new IllegalArgumentException("提交工作记录必须填写下一步行动");
        }
        int maxAttachments = Math.max(0, Math.min(200, submissionPolicy.path("maxAttachments").asInt(10)));
        int count = 0;
        if (workRecordId != null) {
            Integer stored = jdbc.queryForObject("""
                    select count(*) from attachment
                    where tenant_id = :tenantId and work_record_id = :recordId
                      and scan_status <> 'REJECTED'
                    """, base(principal).addValue("recordId", workRecordId), Integer.class);
            count = stored == null ? 0 : stored;
        }
        if (count > maxAttachments) {
            throw new IllegalArgumentException("工作记录附件数量超过模板上限" + maxAttachments + "个");
        }
        if (!submissionApplicable(applicabilityPolicy, payload)) {
            return false;
        }
        validateRequiredRoomCountFormula(executionPolicy, payload);
        JsonNode operationalFeatureFlags = null;
        JsonNode evidenceInstanceConfiguration = null;
        for (JsonNode requirement : submissionPolicy.path("evidenceRequirements")) {
            JsonNode requiredWhen = requirement.path("requiredWhen");
            boolean requiredWhenMatched = false;
            if (requiredWhen.isObject()) {
                String field = requiredWhen.path("field").asText("");
                JsonNode expected = requiredWhen.get("equals");
                if (field.isBlank() || expected == null) {
                    throw evidenceConfigurationError("requiredWhen必须同时配置field和equals");
                }
                JsonNode actual = WorkEntryPolicy.policyValue(payload, field);
                if (actual == null || !actual.equals(expected)) {
                    continue;
                }
                requiredWhenMatched = true;
            } else if (!requiredWhen.isMissingNode() && !requiredWhen.isNull()) {
                throw evidenceConfigurationError("requiredWhen必须是对象");
            }

            String checkpointCode = requirement.path("checkpointCode").asText("").trim();
            String captureSource = requirement.path("captureSource").asText("").trim();
            String conditionalFacility = requirement.path("conditionalFacility").asText("").trim();
            if (!conditionalFacility.isBlank()) {
                if (operationalFeatureFlags == null) {
                    operationalFeatureFlags = loadOperationalFeatureFlags(principal, targetOrgUnitId);
                }
                if (!configuredFacilityEnabled(operationalFeatureFlags, conditionalFacility)) {
                    continue;
                }
            }

            if (requirement.has("requiredInstances")
                    || (requirement.has("minimumPerInstance")
                    && !requirement.path("instanceField").asText("").isBlank())) {
                validateInstanceEvidence(principal, workRecordId, payload, requirement,
                        checkpointCode, captureSource);
                continue;
            }

            int minimum = baseEvidenceMinimum(requirement, requiredWhenMatched);
            if (requirement.has("minimumPerHotelFloor") && targetOrgUnitId != null) {
                int minimumPerFloor = strictNonNegativeInt(requirement, "minimumPerHotelFloor");
                Integer floors = jdbc.queryForObject("""
                        select coalesce(profile.guest_room_floor_count, 1)
                        from org_unit_closure scope
                        join hotel_profile profile
                          on profile.tenant_id = scope.tenant_id
                         and profile.org_unit_id = scope.ancestor_id
                        where scope.tenant_id = :tenantId
                          and scope.descendant_id = :orgUnitId
                        order by scope.depth
                        limit 1
                        """, base(principal).addValue("orgUnitId", targetOrgUnitId), Integer.class);
                minimum = Math.max(minimum,
                        safeEvidenceProduct(Math.max(1, floors == null ? 1 : floors), minimumPerFloor));
            }

            if (requirement.has("minimumPerConfiguredFacility")) {
                if (evidenceInstanceConfiguration == null) {
                    evidenceInstanceConfiguration = loadHotelEvidenceInstanceConfiguration(
                            principal, targetOrgUnitId);
                }
                List<String> configuredFacilities = configuredInstanceKeys(
                        evidenceInstanceConfiguration, requirement, checkpointCode, "facility");
                int perFacility = strictPositiveInt(requirement, "minimumPerConfiguredFacility");
                validateConfiguredInstanceEvidence(principal, workRecordId, requirement,
                        checkpointCode, captureSource, configuredFacilities, perFacility, "设施");
                minimum = Math.max(minimum,
                        safeEvidenceProduct(configuredFacilities.size(), perFacility));
            }

            if (requirement.has("minimumPerConfiguredAsset")) {
                if (evidenceInstanceConfiguration == null) {
                    evidenceInstanceConfiguration = loadHotelEvidenceInstanceConfiguration(
                            principal, targetOrgUnitId);
                }
                List<String> configuredAssets = configuredInstanceKeys(
                        evidenceInstanceConfiguration, requirement, checkpointCode, "asset");
                int perAsset = strictPositiveInt(requirement, "minimumPerConfiguredAsset");
                validateConfiguredInstanceEvidence(principal, workRecordId, requirement,
                        checkpointCode, captureSource, configuredAssets, perAsset, "设备");
                minimum = Math.max(minimum, safeEvidenceProduct(configuredAssets.size(), perAsset));
            }

            if (minimum == 0) {
                continue;
            }
            if (workRecordId == null) {
                throw new IllegalArgumentException("模板要求先保存草稿并上传现场证据后再提交");
            }
            int matching = matchingEvidence(principal, workRecordId, requirement,
                    checkpointCode, captureSource, null);
            if (matching < minimum) {
                String label = requirement.path("label").asText(checkpointCode.isBlank() ? "现场证据" : checkpointCode);
                throw new IllegalArgumentException(label + "至少需要" + minimum + "份符合要求的证据");
            }
        }
        if (submissionPolicy.path("attachmentRequired").asBoolean(false) && count == 0) {
            throw new IllegalArgumentException("该岗位工作模板要求至少上传一个附件证据");
        }
        return true;
    }

    private int matchingEvidence(
            TenantPrincipal principal,
            UUID workRecordId,
            JsonNode requirement,
            String checkpointCode,
            String captureSource,
            String instanceKey
    ) {
        validateMediaRequirement(requirement);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                    select media_type, evidence_metadata::text as evidence_metadata
                    from attachment
                    where tenant_id = :tenantId and work_record_id = :recordId
                      and scan_status <> 'REJECTED'
                      and (:checkpointCode = '' or checkpoint_code = :checkpointCode)
                      and (:captureSource = '' or capture_source = :captureSource
                           or (:captureSource = 'CAMERA_OR_FILE' and capture_source in ('CAMERA', 'FILE_PICKER')))
                    """, base(principal).addValue("recordId", workRecordId)
                    .addValue("checkpointCode", checkpointCode)
                    .addValue("captureSource", captureSource));
        int matching = 0;
        for (Map<String, Object> row : rows) {
            JsonNode metadata = readJson(String.valueOf(row.get("evidence_metadata")));
            String storedInstanceKey = normalizeEvidenceInstanceKey(
                    metadata.path("evidenceInstanceKey").asText(null));
            if (instanceKey != null && !instanceKey.equals(storedInstanceKey)) {
                continue;
            }
            boolean reference = metadata.path("reference").asBoolean(false)
                    || metadata.hasNonNull("referenceAttachmentId");
            if (reference && !requirement.path("allowReference").asBoolean(false)) {
                continue;
            }
            if (mediaTypeAllowed(String.valueOf(row.get("media_type")), requirement)) {
                matching++;
            }
        }
        return matching;
    }

    private void validateConfiguredInstanceEvidence(
            TenantPrincipal principal,
            UUID workRecordId,
            JsonNode requirement,
            String checkpointCode,
            String captureSource,
            List<String> configuredInstanceKeys,
            int minimumPerInstance,
            String instanceLabel
    ) {
        if (workRecordId == null) {
            throw new IllegalArgumentException("模板要求先保存草稿并上传现场证据后再提交");
        }
        String label = requirement.path("label").asText(checkpointCode.isBlank() ? "现场证据" : checkpointCode);
        for (String instanceKey : configuredInstanceKeys) {
            if (matchingEvidence(principal, workRecordId, requirement,
                    checkpointCode, captureSource, instanceKey) < minimumPerInstance) {
                throw new IllegalArgumentException(label + instanceLabel + instanceKey
                        + "至少需要" + minimumPerInstance + "份对应证据");
            }
        }
    }

    private void validateInstanceEvidence(
            TenantPrincipal principal,
            UUID workRecordId,
            JsonNode payload,
            JsonNode requirement,
            String checkpointCode,
            String captureSource
    ) {
        String instanceLabel = requirement.path("instanceLabel").asText("房号").trim();
        String label = requirement.path("label").asText(checkpointCode.isBlank() ? "逐项证据" : checkpointCode);
        List<String> instanceKeys = evidenceInstanceKeys(payload, requirement, label, instanceLabel);
        if (instanceKeys.isEmpty()) return;
        int minimumPerInstance = requirement.has("minimumPerInstance")
                ? strictNonNegativeInt(requirement, "minimumPerInstance") : 1;
        if (minimumPerInstance == 0) {
            return;
        }
        if (workRecordId == null) {
            throw new IllegalArgumentException("模板要求先保存草稿并上传现场证据后再提交");
        }
        for (String instanceKey : instanceKeys) {
            if (matchingEvidence(principal, workRecordId, requirement,
                    checkpointCode, captureSource, instanceKey) < minimumPerInstance) {
                throw new IllegalArgumentException(label + instanceLabel + instanceKey
                        + "至少需要" + minimumPerInstance + "张对应照片");
            }
        }
    }

    static List<String> evidenceInstanceKeys(
            JsonNode payload,
            JsonNode requirement,
            String label,
            String instanceLabel
    ) {
        boolean fixedInstanceCount = requirement.has("requiredInstances");
        int requiredInstances = fixedInstanceCount
                ? strictPositiveInt(requirement, "requiredInstances") : 0;
        String instanceField = requirement.path("instanceField").asText("").trim();
        if (requiredInstances > 100 || instanceField.isBlank()) {
            throw evidenceConfigurationError("逐项证据必须配置有效的instanceField，requiredInstances最多100");
        }
        JsonNode values = payload == null ? null : payload.get(instanceField);
        if (fixedInstanceCount && (values == null || !values.isArray() || values.size() != requiredInstances)) {
            throw new IllegalArgumentException(label + "必须填写" + requiredInstances + "个" + instanceLabel);
        }
        if (!fixedInstanceCount && (values == null || values.isNull())) {
            return List.of();
        }
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(label + instanceLabel + "必须是数组");
        }
        if (values.size() > 100) {
            throw new IllegalArgumentException(label + instanceLabel + "最多填写100个");
        }
        List<String> instanceKeys = new ArrayList<>(values.size());
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String key = normalizeEvidenceInstanceKey(value == null || !value.isTextual() ? null : value.asText());
            if (key == null) {
                throw new IllegalArgumentException(label + instanceLabel + "不能为空或格式不正确");
            }
            if (!distinct.add(key)) {
                throw new IllegalArgumentException(label + instanceLabel + "不能重复：" + key);
            }
            instanceKeys.add(key);
        }
        return List.copyOf(instanceKeys);
    }

    private JsonNode loadOperationalFeatureFlags(TenantPrincipal principal, UUID targetOrgUnitId) {
        if (targetOrgUnitId == null) {
            throw evidenceConfigurationError("按设施或设备取证时必须存在目标门店");
        }
        List<Map<String, Object>> profiles = jdbc.queryForList("""
                select profile.operational_feature_flags::text as feature_flags
                from org_unit_closure scope
                join hotel_profile profile
                  on profile.tenant_id = scope.tenant_id
                 and profile.org_unit_id = scope.ancestor_id
                where scope.tenant_id = :tenantId
                  and scope.descendant_id = :orgUnitId
                order by scope.depth
                limit 1
                """, base(principal).addValue("orgUnitId", targetOrgUnitId));
        if (profiles.size() != 1) {
            throw evidenceConfigurationError("目标门店缺少唯一的hotel_profile配置");
        }
        JsonNode flags = readJson(String.valueOf(profiles.get(0).get("feature_flags")));
        if (!flags.isObject()) {
            throw evidenceConfigurationError("operational_feature_flags必须是对象");
        }
        return flags;
    }

    private JsonNode loadHotelEvidenceInstanceConfiguration(
            TenantPrincipal principal,
            UUID targetOrgUnitId
    ) {
        if (targetOrgUnitId == null) {
            throw evidenceConfigurationError("按设施或设备取证时必须存在目标门店");
        }
        List<Map<String, Object>> profiles = jdbc.queryForList("""
                select profile.evidence_instance_configuration::text as evidence_configuration
                from org_unit_closure scope
                join hotel_profile profile
                  on profile.tenant_id = scope.tenant_id
                 and profile.org_unit_id = scope.ancestor_id
                where scope.tenant_id = :tenantId
                  and scope.descendant_id = :orgUnitId
                order by scope.depth
                limit 1
                """, base(principal).addValue("orgUnitId", targetOrgUnitId));
        if (profiles.size() != 1) {
            throw evidenceConfigurationError("目标门店缺少唯一的hotel_profile配置");
        }
        JsonNode configuration = readJson(String.valueOf(
                profiles.get(0).get("evidence_configuration")));
        if (!configuration.isObject()) {
            throw evidenceConfigurationError("门店证据清单必须是对象");
        }
        return configuration;
    }

    static int baseEvidenceMinimum(JsonNode requirement, boolean requiredWhenMatched) {
        if (requirement.has("minimum")) {
            return strictNonNegativeInt(requirement, "minimum");
        }
        if (requiredWhenMatched) {
            return 1;
        }
        if (requirement.has("minimumPerHotelFloor")
                || requirement.has("minimumPerConfiguredFacility")
                || requirement.has("minimumPerConfiguredAsset")
                || requirement.has("minimumPerInstance")) {
            return 0;
        }
        return 1;
    }

    static boolean configuredFacilityEnabled(JsonNode featureFlags, String facilityCode) {
        String normalized = facilityCode == null ? "" : facilityCode.trim().toUpperCase(Locale.ROOT);
        String key = FACILITY_FLAG_KEYS.get(normalized);
        if (key == null) {
            throw evidenceConfigurationError("未知conditionalFacility：" + normalized);
        }
        JsonNode configured = featureFlags == null ? null : featureFlags.get(key);
        if (configured == null || configured.isNull()) {
            return false;
        }
        if (!configured.isBoolean()) {
            throw evidenceConfigurationError("门店设施标识" + key + "必须是布尔值");
        }
        return configured.asBoolean();
    }

    static List<String> configuredInstanceKeys(
            JsonNode evidenceConfiguration,
            JsonNode requirement,
            String checkpointCode,
            String instanceType
    ) {
        if (!Set.of("facility", "asset").contains(instanceType)) {
            throw evidenceConfigurationError("未知配置实例类型：" + instanceType);
        }
        String capitalized = Character.toUpperCase(instanceType.charAt(0)) + instanceType.substring(1);
        String configuredField = requirement.path("configured" + capitalized + "InstanceField")
                .asText("").trim();
        if (configuredField.isBlank()) {
            configuredField = checkpointCode;
        }
        if (configuredField == null || configuredField.isBlank()) {
            throw evidenceConfigurationError("minimumPerConfigured" + capitalized
                    + "必须配置门店证据清单字段");
        }
        String containerName = instanceType + "Instances";
        JsonNode container = evidenceConfiguration == null ? null
                : evidenceConfiguration.get(containerName);
        if (container == null || !container.isObject()) {
            throw evidenceConfigurationError("请管理员配置门店证据清单：缺少" + containerName);
        }
        JsonNode values = container.get(configuredField);
        if (values == null || values.isNull()) {
            throw evidenceConfigurationError("请管理员配置门店证据清单：缺少"
                    + configuredField);
        }
        if (!values.isArray()) {
            throw evidenceConfigurationError("门店证据清单" + configuredField + "必须是数组");
        }
        if (values.isEmpty()) {
            throw evidenceConfigurationError("请管理员配置门店证据清单："
                    + configuredField + "不能为空");
        }
        if (values.size() > 100) {
            throw new IllegalArgumentException(instanceType + "实例列表最多100项");
        }
        List<String> keys = new ArrayList<>(values.size());
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String key = normalizeEvidenceInstanceKey(
                    value == null || !value.isTextual() ? null : value.asText());
            if (key == null) {
                throw new IllegalArgumentException(instanceType + "实例编码不能为空或格式不正确");
            }
            if (!distinct.add(key)) {
                throw new IllegalArgumentException(instanceType + "实例编码不能重复：" + key);
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    static boolean submissionApplicable(JsonNode applicabilityPolicy, JsonNode payload) {
        if (applicabilityPolicy == null || applicabilityPolicy.isNull()
                || applicabilityPolicy.isMissingNode() || applicabilityPolicy.isEmpty()) {
            return true;
        }
        if (!applicabilityPolicy.isObject()) {
            throw evidenceConfigurationError("applicability_policy必须是对象");
        }
        boolean excludesWhenNotApplicable = applicabilityPolicy
                .path("excludeFromCompletionWhenNotApplicable").asBoolean(false);
        String conditionField = applicabilityPolicy.path("conditionField").asText("").trim();
        String namedCondition = applicabilityPolicy.path("condition").asText("").trim();
        if (!conditionField.isBlank() && !namedCondition.isBlank()) {
            throw evidenceConfigurationError("conditionField与condition不能同时配置");
        }
        if (!conditionField.isBlank()) {
            JsonNode expected = applicabilityPolicy.get("equals");
            if (expected == null) {
                throw evidenceConfigurationError("conditionField必须配置equals");
            }
            JsonNode actual = payload == null ? null : payload.get(conditionField);
            if (actual == null || actual.isNull()) {
                throw new IllegalArgumentException("必须明确填写适用条件字段" + conditionField);
            }
            boolean matches = actual.equals(expected);
            if (!matches && !excludesWhenNotApplicable) {
                throw new IllegalArgumentException("当前记录不满足工作事项适用条件" + conditionField);
            }
            return matches;
        }
        if (!namedCondition.isBlank()) {
            String payloadField = switch (namedCondition) {
                case "HAS_SPECIAL_ARRIVAL_REQUIREMENT" -> "specialArrivalRequirement";
                default -> throw evidenceConfigurationError("不支持的condition：" + namedCondition);
            };
            JsonNode actual = payload == null ? null : payload.get(payloadField);
            if (actual == null || !actual.isBoolean()) {
                throw new IllegalArgumentException("必须明确填写布尔适用条件字段" + payloadField);
            }
            boolean matches = actual.asBoolean();
            if (!matches && !excludesWhenNotApplicable) {
                throw new IllegalArgumentException("当前记录不满足工作事项适用条件" + namedCondition);
            }
            return matches;
        }
        if (excludesWhenNotApplicable) {
            throw evidenceConfigurationError(
                    "excludeFromCompletionWhenNotApplicable必须同时配置conditionField或condition");
        }
        return true;
    }

    static int validateRequiredRoomCountFormula(JsonNode policy, JsonNode payload) {
        String formula = policy.path("requiredRoomCountFormula").asText("").replaceAll("\\s+", "");
        if (formula.isBlank()) {
            return 0;
        }
        if (!"max(ceil(completedRooms*0.20),onDutyHousekeepers*5)".equals(formula)) {
            throw evidenceConfigurationError("不支持的requiredRoomCountFormula：" + formula);
        }
        int completedRooms = strictPayloadNonNegativeInt(payload, "completedRooms");
        int onDutyHousekeepers = strictPayloadNonNegativeInt(payload, "onDutyHousekeepers");
        long byCompletion = (completedRooms + 4L) / 5L;
        long byStaffing = onDutyHousekeepers * 5L;
        long calculated = Math.max(byCompletion, byStaffing);
        if (calculated > 100) {
            throw new IllegalArgumentException("按完成房数和当班客房员计算的查房数量超过100间，请核对数据");
        }
        JsonNode roomNumbers = payload == null ? null : payload.get("roomNumbers");
        if (roomNumbers == null || !roomNumbers.isArray()) {
            throw new IllegalArgumentException("查房任务必须填写roomNumbers房号数组");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode value : roomNumbers) {
            String normalized = normalizeEvidenceInstanceKey(
                    value == null || !value.isTextual() ? null : value.asText());
            if (normalized == null) {
                throw new IllegalArgumentException("查房房号不能为空或格式不正确");
            }
            if (!distinct.add(normalized)) {
                throw new IllegalArgumentException("查房房号不能重复：" + normalized);
            }
        }
        if (distinct.size() < calculated) {
            throw new IllegalArgumentException("按完成房数和当班客房员计算，本次至少需要检查"
                    + calculated + "间，当前仅填写" + distinct.size() + "间");
        }
        return (int) calculated;
    }

    static boolean mediaTypeAllowed(String rawMediaType, JsonNode requirement) {
        String mediaType = rawMediaType == null
                ? "" : rawMediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        JsonNode mediaTypes = requirement.get("mediaTypes");
        if (mediaTypes != null && mediaTypes.size() > 0) {
            boolean exactMatch = false;
            for (JsonNode configured : mediaTypes) {
                if (mediaType.equals(configured.asText("").trim().toLowerCase(Locale.ROOT))) {
                    exactMatch = true;
                    break;
                }
            }
            if (!exactMatch) {
                return false;
            }
        }
        JsonNode allowedKinds = requirement.get("allowedKinds");
        if (allowedKinds == null || allowedKinds.isEmpty()) {
            return true;
        }
        for (JsonNode configured : allowedKinds) {
            String kind = configured.asText("").trim().toUpperCase(Locale.ROOT);
            if (switch (kind) {
                case "IMAGE" -> mediaType.startsWith("image/");
                case "PDF" -> "application/pdf".equals(mediaType);
                case "WORD" -> Set.of(
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ).contains(mediaType);
                case "EXCEL" -> Set.of(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv"
                ).contains(mediaType);
                case "DOCUMENT" -> "application/pdf".equals(mediaType)
                        || "application/msword".equals(mediaType)
                        || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mediaType)
                        || "application/vnd.ms-excel".equals(mediaType)
                        || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mediaType)
                        || "text/csv".equals(mediaType)
                        || "text/plain".equals(mediaType);
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private static void validateMediaRequirement(JsonNode requirement) {
        JsonNode mediaTypes = requirement.get("mediaTypes");
        if (mediaTypes != null) {
            if (!mediaTypes.isArray()) {
                throw evidenceConfigurationError("mediaTypes必须是数组");
            }
            for (JsonNode configured : mediaTypes) {
                if (!configured.isTextual() || configured.asText().isBlank()) {
                    throw evidenceConfigurationError("mediaTypes只能包含非空MIME类型");
                }
            }
        }
        JsonNode allowedKinds = requirement.get("allowedKinds");
        if (allowedKinds != null) {
            if (!allowedKinds.isArray()) {
                throw evidenceConfigurationError("allowedKinds必须是数组");
            }
            for (JsonNode configured : allowedKinds) {
                String kind = configured.asText("").trim().toUpperCase(Locale.ROOT);
                if (!configured.isTextual() || !ALLOWED_EVIDENCE_KINDS.contains(kind)) {
                    throw evidenceConfigurationError("未知allowedKinds：" + configured.asText());
                }
            }
        }
        JsonNode allowReference = requirement.get("allowReference");
        if (allowReference != null && !allowReference.isBoolean()) {
            throw evidenceConfigurationError("allowReference必须是布尔值");
        }
    }

    private static int strictPayloadNonNegativeInt(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException("查房任务必须填写非负整数" + field);
        }
        return value.intValue();
    }

    private static int strictNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw evidenceConfigurationError(field + "必须是非负整数");
        }
        return value.intValue();
    }

    private static int strictPositiveInt(JsonNode node, String field) {
        int value = strictNonNegativeInt(node, field);
        if (value == 0) {
            throw evidenceConfigurationError(field + "必须大于0");
        }
        return value;
    }

    private static int safeEvidenceProduct(int units, int perUnit) {
        long product = (long) units * perUnit;
        if (product > 200) {
            throw evidenceConfigurationError("按门店配置计算的证据数量超过200份上限");
        }
        return (int) product;
    }

    private static IllegalArgumentException evidenceConfigurationError(String detail) {
        return new IllegalArgumentException("证据模板配置错误：" + detail);
    }

    private static String normalizeEvidenceInstanceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.length() <= 120
                && normalized.codePoints().noneMatch(Character::isISOControl)
                ? normalized : null;
    }

    private void validateConditionalFields(String itemCode, JsonNode payload) {
        if ("GM_M_WATER".equals(itemCode)
                && ("不正常".equals(payload.path("equipmentNormal").asText()) || "不足".equals(payload.path("chemicalSufficient").asText()))
                && payload.path("handlingPlan").asText("").isBlank())
            throw new IllegalArgumentException("设备不正常或药剂不足时，请填写处理安排");
        if (itemCode == null || !Set.of("GM_PUBLIC_AREA_INSPECTION", "GM_PUBLIC_AREA_INSPECTION_AM",
                "GM_PUBLIC_AREA_INSPECTION_PM", "GM_ROOM_INSPECTION")
                .contains(itemCode) || !WorkEntryPolicy.policyValue(payload,"issueFound").asBoolean(false)) {
            return;
        }
        if (payload.path("issueSummary").asText("").isBlank()
                || payload.path("correctiveOwner").asText("").isBlank()
                || payload.path("correctiveDeadline").asText("").isBlank()) {
            throw new IllegalArgumentException("巡检发现问题时必须填写问题说明、整改责任人和整改时限");
        }
    }

    private WorkContext resolveWorkContext(
            TenantPrincipal principal,
            WorkDataModels.SubmitWorkRecord request,
            UUID targetOrgUnitId
    ) {
        if (request.workExpectationId() != null) {
            WorkContext context = jdbc.queryForObject("""
                    select v.id as version_id, i.id as item_id, x.id as expectation_id,
                           i.item_type, i.item_code, i.form_version_id, x.position_assignment_id,
                           x.target_org_unit_id, x.business_date, x.status,
                           coalesce((i.execution_policy ->> 'repeatableDailyRecords')::boolean, false)
                               as repeatable_daily_records
                    from work_expectation x
                    join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                    join work_package_version v on v.tenant_id = i.tenant_id and v.id = i.work_package_version_id
                    where x.tenant_id = :tenantId and x.id = :expectationId
                    for update of x
                    """, base(principal).addValue("expectationId", request.workExpectationId()),
                    (rs, rowNum) -> new WorkContext(
                            rs.getObject("version_id", UUID.class),
                            rs.getObject("item_id", UUID.class),
                            rs.getObject("expectation_id", UUID.class),
                            rs.getString("item_type"),
                            rs.getString("item_code"),
                            rs.getObject("form_version_id", UUID.class),
                            rs.getObject("position_assignment_id", UUID.class),
                            rs.getObject("target_org_unit_id", UUID.class),
                            rs.getObject("business_date", java.time.LocalDate.class),
                            rs.getString("status"),
                            rs.getBoolean("repeatable_daily_records")));
            Set<String> executableStatuses = context.repeatableDailyRecords()
                    ? Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS", "SUBMITTED", "SATISFIED", "FAILED", "MISSED")
                    : Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS", "FAILED", "MISSED");
            if (!canExecuteExpectation(principal, context.expectationId(), request.positionAssignmentId())
                    || !targetOrgUnitId.equals(context.targetOrgUnitId())
                    || !request.businessDate().equals(context.businessDate())
                    || !request.formVersionId().equals(context.formVersionId())
                    || !executableStatuses.contains(context.expectationStatus())) {
                throw new IllegalArgumentException("工作期望与任职、门店、周期或表单不匹配");
            }
            if (request.workPackageVersionId() != null && !request.workPackageVersionId().equals(context.versionId())) {
                throw new IllegalArgumentException("工作包版本与工作期望不匹配");
            }
            if (request.workPackageItemId() != null && !request.workPackageItemId().equals(context.itemId())) {
                throw new IllegalArgumentException("工作包条目与工作期望不匹配");
            }
            return context;
        }
        if ((request.workPackageVersionId() == null) != (request.workPackageItemId() == null)) {
            throw new IllegalArgumentException("workPackageVersionId与workPackageItemId必须同时提供");
        }
        if (request.workPackageVersionId() == null) {
            return WorkContext.legacy();
        }
        return jdbc.queryForObject("""
                select v.id as version_id, i.id as item_id, i.item_type, i.item_code, i.form_version_id,
                       coalesce((i.execution_policy ->> 'repeatableDailyRecords')::boolean, false)
                           as repeatable_daily_records
                from work_package_version v
                join work_package_item i on i.tenant_id = v.tenant_id and i.work_package_version_id = v.id
                where v.tenant_id = :tenantId and v.id = :versionId and i.id = :itemId
                  and v.lifecycle_status = 'PUBLISHED' and i.form_version_id = :formVersionId
                """, base(principal)
                .addValue("versionId", request.workPackageVersionId())
                .addValue("itemId", request.workPackageItemId())
                .addValue("formVersionId", request.formVersionId()),
                (rs, rowNum) -> new WorkContext(
                        rs.getObject("version_id", UUID.class),
                        rs.getObject("item_id", UUID.class), null,
                        rs.getString("item_type"), rs.getString("item_code"),
                        rs.getObject("form_version_id", UUID.class),
                        null, null, null, null, rs.getBoolean("repeatable_daily_records")));
    }

    private boolean canExecuteExpectation(
            TenantPrincipal principal,
            UUID expectationId,
            UUID requestedAssignmentId
    ) {
        Integer allowed = jdbc.queryForObject("""
                select count(*)
                from work_expectation x
                join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                join employee_position_assignment candidate
                  on candidate.tenant_id = x.tenant_id and candidate.id = :assignmentId
                 and candidate.status = 'ACTIVE'
                 and candidate.valid_from <= x.business_date
                 and (candidate.valid_to is null or candidate.valid_to >= x.business_date)
                join position_definition position
                  on position.tenant_id = candidate.tenant_id and position.id = candidate.position_id
                left join work_expectation_delegation delegation
                  on delegation.tenant_id = x.tenant_id
                 and delegation.work_expectation_id = x.id and delegation.status = 'ACTIVE'
                where x.tenant_id = :tenantId and x.id = :expectationId
                  and exists (
                    select 1
                    from org_unit_closure execution_scope
                    where execution_scope.tenant_id = x.tenant_id
                      and execution_scope.ancestor_id = x.target_org_unit_id
                      and execution_scope.descendant_id = candidate.org_unit_id
                  )
                  and (
                    (delegation.id is not null and delegation.delegate_assignment_id = candidate.id)
                    or (delegation.id is null and (
                      x.position_assignment_id = candidate.id
                      or jsonb_exists(coalesce(i.execution_policy -> 'allowedPositionCodes', '[]'::jsonb), position.code)
                    ))
                  )
                """, base(principal).addValue("expectationId", expectationId)
                .addValue("assignmentId", requestedAssignmentId), Integer.class);
        return allowed != null && allowed == 1;
    }

    private void requireFirstSharedSubmission(
            TenantPrincipal principal,
            UUID expectationId,
            UUID currentRecordId
    ) {
        if (expectationId == null) return;
        String excludeCurrentRecord = currentRecordId == null ? "" : " and id <> :recordId";
        MapSqlParameterSource params = base(principal).addValue("expectationId", expectationId);
        if (currentRecordId != null) {
            params.addValue("recordId", currentRecordId);
        }
        Integer existing = jdbc.queryForObject("""
                select count(*) from work_record
                where tenant_id = :tenantId and work_expectation_id = :expectationId
                  and status in ('SUBMITTED', 'APPROVED')
                """ + excludeCurrentRecord, params, Integer.class);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("该共享事项已由其他人员提交，请刷新待办");
        }
    }

    private RecordSequence nextRecordSequence(
            TenantPrincipal principal,
            WorkDataModels.SubmitWorkRecord request,
            WorkContext context
    ) {
        if (request.supersedesWorkRecordId() != null) {
            RecordSequence previous = jdbc.queryForObject("""
                    select series_entry_no, attempt_no from work_record
                    where tenant_id = :tenantId and id = :previousId
                      and position_assignment_id = :assignmentId and form_version_id = :formVersionId
                      and business_date = :businessDate and status in ('SUBMITTED', 'APPROVED', 'REJECTED')
                      and work_expectation_id is not distinct from :expectationId
                    """, base(principal)
                    .addValue("previousId", request.supersedesWorkRecordId())
                    .addValue("assignmentId", request.positionAssignmentId())
                    .addValue("formVersionId", request.formVersionId())
                    .addValue("businessDate", request.businessDate())
                    .addValue("expectationId", context.expectationId()),
                    (rs, rowNum) -> new RecordSequence(
                            rs.getInt("series_entry_no"), rs.getInt("attempt_no")));
            if (previous == null) {
                throw new IllegalArgumentException("被纠正的历史工作记录不匹配");
            }
            return new RecordSequence(previous.seriesEntryNo(), previous.attemptNo() + 1);
        }
        if (context.expectationId() != null) {
            if (context.repeatableDailyRecords()) {
                Integer nextSeries = jdbc.queryForObject("""
                        select coalesce(max(series_entry_no), 0) + 1 from work_record
                        where tenant_id = :tenantId and work_expectation_id = :expectationId
                        """, base(principal).addValue("expectationId", context.expectationId()), Integer.class);
                return new RecordSequence(nextSeries == null ? 1 : nextSeries, 1);
            }
            Integer next = jdbc.queryForObject("""
                    select coalesce(max(attempt_no), 0) + 1 from work_record
                    where tenant_id = :tenantId and work_expectation_id = :expectationId
                    """, base(principal).addValue("expectationId", context.expectationId()), Integer.class);
            return new RecordSequence(1, next == null ? 1 : next);
        }
        return new RecordSequence(1, 1);
    }

    private void validateWorkPayload(TenantPrincipal principal, UUID formVersionId, UUID itemId, JsonNode payload, boolean draft) {
        Map<String, Object> row = jdbc.queryForMap("""
                select f.json_schema::text as schema, i.item_code
                from form_version f
                left join work_package_item i on i.tenant_id=f.tenant_id and i.id=:itemId
                where f.tenant_id=:tenantId and f.id=:formId and f.lifecycle_status='PUBLISHED'
                """, base(principal).addValue("formId", formVersionId).addValue("itemId", itemId));
        JsonNode original = readJson((String) row.get("schema"));
        JsonNode effective = WorkEntryPolicy.schema(original, (String) row.get("item_code"));
        JsonNode validated = WorkEntryPolicy.validationPayload(payload, effective, original, (String) row.get("item_code"));
        if (draft) payloadValidator.requireValidDraft(effective, validated);
        else payloadValidator.requireValid(effective, validated);
    }

    private void authorizeAssignmentSubmission(TenantPrincipal principal, UUID assignmentId, UUID assignmentOrgUnitId) {
        if (principal.assignmentIds().contains(assignmentId)) {
            return;
        }
        accessPolicy.requirePermission("work-record.submit-for-other");
        accessPolicy.requireOrgScope(assignmentOrgUnitId);
    }

    private void authorizeRecordMutation(TenantPrincipal principal, RecordAccess record) {
        accessPolicy.requireOrgScopeOrOwnWork(record.targetOrgUnitId(), record.assignmentId());
        if (!principal.assignmentIds().contains(record.assignmentId())) {
            accessPolicy.requirePermission("work-record.submit-for-other");
        }
    }

    private void updateExpectationAfterRecord(
            TenantPrincipal principal,
            UUID expectationId,
            String recordStatus,
            boolean applicable,
            boolean repeatableEntry
    ) {
        if (expectationId == null) {
            return;
        }
        if ("SUBMITTED".equals(recordStatus) && !applicable) {
            jdbc.update("""
                    update work_expectation
                    set status = 'WAIVED', waiver_reason = 'NOT_APPLICABLE_RECORDED',
                        waived_by_account_id = :actorId, waived_at = now(),
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :expectationId
                      and status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS', 'FAILED', 'MISSED')
                    """, base(principal)
                    .addValue("expectationId", expectationId)
                    .addValue("actorId", principal.actorId()));
            auditWriter.record("WORK_EXPECTATION_NOT_APPLICABLE_RECORDED", "WORK_EXPECTATION", expectationId,
                    "{\"reason\":\"NOT_APPLICABLE_RECORDED\"}");
            return;
        }
        if (repeatableEntry) {
            refreshRepeatableExpectationStatus(principal, expectationId);
            return;
        }
        String expectationStatus = "DRAFT".equals(recordStatus) ? "IN_PROGRESS" : "SUBMITTED";
        jdbc.update("""
                update work_expectation
                set status = :status, row_version = row_version + 1
                where tenant_id = :tenantId and id = :expectationId
                  and status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS', 'FAILED', 'MISSED')
                """, base(principal)
                .addValue("expectationId", expectationId)
                .addValue("status", expectationStatus));
    }

    private void refreshRepeatableExpectationStatus(TenantPrincipal principal, UUID expectationId) {
        jdbc.update("""
                with latest_series as (
                    select distinct on (record.series_entry_no)
                           record.series_entry_no, record.status
                    from work_record record
                    where record.tenant_id = :tenantId
                      and record.work_expectation_id = :expectationId
                      and record.repeatable_entry
                    order by record.series_entry_no, record.attempt_no desc,
                             record.created_at desc, record.id desc
                ), aggregate_status as (
                    select case
                               when bool_or(status = 'REJECTED') then 'FAILED'
                               when bool_or(status = 'SUBMITTED') then 'SUBMITTED'
                               when bool_or(status = 'DRAFT') then 'IN_PROGRESS'
                               when bool_or(status = 'APPROVED') then 'SATISFIED'
                               else null
                           end as status
                    from latest_series
                )
                update work_expectation expectation
                set status = aggregate_status.status,
                    row_version = expectation.row_version + 1,
                    updated_at = now()
                from aggregate_status
                where expectation.tenant_id = :tenantId
                  and expectation.id = :expectationId
                  and aggregate_status.status is not null
                  and expectation.status not in ('CANCELLED', 'WAIVED')
                  and expectation.status is distinct from aggregate_status.status
                """, base(principal).addValue("expectationId", expectationId));
    }

    private void emitSubmitted(TenantPrincipal principal, UUID recordId) {
        Map<String, Object> source = jdbc.queryForMap("""
                select w.id, w.target_org_unit_id, w.position_assignment_id, w.form_version_id,
                       w.work_package_version_id, w.work_package_item_id, w.work_expectation_id,
                       w.record_kind, w.business_date, w.occurred_at, w.payload::text as business_payload,
                       f.code as form_code, f.form_type,
                       (select wis.standard_version_id
                        from work_package_item_standard wis
                        where wis.tenant_id = w.tenant_id
                          and wis.work_package_item_id = w.work_package_item_id
                          and wis.usage_type = 'ACCEPTANCE'
                        order by wis.created_at, wis.id limit 1) as standard_version_id
                from work_record w
                join form_version fv on fv.tenant_id = w.tenant_id and fv.id = w.form_version_id
                join form_definition f on f.tenant_id = fv.tenant_id and f.id = fv.form_id
                where w.tenant_id = :tenantId and w.id = :recordId
                """, base(principal).addValue("recordId", recordId));
        ObjectNode event = objectMapper.createObjectNode();
        event.put("workRecordId", recordId.toString());
        putUuid(event, "orgUnitId", source.get("target_org_unit_id"));
        putUuid(event, "positionAssignmentId", source.get("position_assignment_id"));
        putUuid(event, "formVersionId", source.get("form_version_id"));
        putUuid(event, "workPackageVersionId", source.get("work_package_version_id"));
        putUuid(event, "workPackageItemId", source.get("work_package_item_id"));
        putUuid(event, "workExpectationId", source.get("work_expectation_id"));
        putUuid(event, "standardVersionId", source.get("standard_version_id"));
        event.put("formCode", String.valueOf(source.get("form_code")));
        event.put("formType", String.valueOf(source.get("form_type")));
        event.put("recordKind", String.valueOf(source.get("record_kind")));
        event.put("businessDate", String.valueOf(source.get("business_date")));
        event.put("occurredAt", String.valueOf(source.get("occurred_at")));
        event.set("businessPayload", readJson(String.valueOf(source.get("business_payload"))));
        auditWriter.emit("WORK_RECORD", recordId, "WorkRecordSubmitted", event.toString());
        String formCode = event.path("formCode").asText().toUpperCase(Locale.ROOT);
        String formType = event.path("formType").asText().toUpperCase(Locale.ROOT);
        if ("EVENT".equals(event.path("recordKind").asText())
                && (formCode.contains("COMPLAINT") || formType.contains("COMPLAINT"))) {
            auditWriter.emit("WORK_RECORD", recordId, "ComplaintReported", event.toString());
        }
    }

    private static void putUuid(ObjectNode target, String field, Object value) {
        if (value instanceof UUID uuid) {
            target.put(field, uuid.toString());
        }
    }

    private RecordAccess recordAccess(TenantPrincipal principal, UUID recordId) {
        return recordAccess(principal, recordId, false);
    }

    private RecordAccess recordAccessForUpdate(TenantPrincipal principal, UUID recordId) {
        return recordAccess(principal, recordId, true);
    }

    private RecordAccess recordAccess(TenantPrincipal principal, UUID recordId, boolean forUpdate) {
        return jdbc.queryForObject("""
                select status, target_org_unit_id, position_assignment_id, form_version_id,
                       work_package_item_id, work_expectation_id, submitted_by_account_id,
                       business_date, occurred_at, payload::text, repeatable_entry, series_entry_no
                from work_record where tenant_id = :tenantId and id = :recordId
                """ + (forUpdate ? " for update" : ""), base(principal).addValue("recordId", recordId),
                (rs, rowNum) -> new RecordAccess(
                        rs.getString("status"),
                        rs.getObject("target_org_unit_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("form_version_id", UUID.class),
                        rs.getObject("work_package_item_id", UUID.class),
                        rs.getObject("work_expectation_id", UUID.class),
                        rs.getObject("submitted_by_account_id", UUID.class),
                        rs.getObject("business_date", java.time.LocalDate.class),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("payload"),
                        rs.getBoolean("repeatable_entry"),
                        rs.getInt("series_entry_no")));
    }

    private void requireConfiguredReviewer(TenantPrincipal principal, RecordAccess record) {
        if (record.workPackageItemId() == null) {
            return;
        }
        if (isManagerCycle(principal, record.workPackageItemId())) {
            boolean allowed = principal.hasRole("PLATFORM_ADMIN") || supervisors.load(principal, "manager-cycle.review")
                    .forWork(record.assignmentId(), record.targetOrgUnitId()).stream()
                    .anyMatch(s -> s.accountId().equals(principal.actorId()) && (principal.businessActorAssignmentId()==null
                            || principal.businessActorAssignmentId().equals(s.assignmentId())));
            if (!allowed) throw new AccessDeniedException("店长周期工作须由该门店的分管副总复核");
            return;
        }
        Integer directManagerRuleCount = jdbc.queryForObject("""
                select count(*)
                from work_package_item_responsibility
                where tenant_id = :tenantId
                  and work_package_item_id = :itemId
                  and participant_type in ('REVIEWER', 'ACCEPTOR')
                  and resolver_type = 'DIRECT_MANAGER_ASSIGNMENT'
                """, base(principal).addValue("itemId", record.workPackageItemId()), Integer.class);
        if (directManagerRuleCount == null || directManagerRuleCount == 0) {
            return;
        }

        UUID directManagerAssignmentId = jdbc.query("""
                select manager.id
                from employee_position_assignment executor
                join employee_position_assignment manager
                  on manager.tenant_id = executor.tenant_id
                 and manager.id = executor.manager_assignment_id
                where executor.tenant_id = :tenantId
                  and executor.id = :assignmentId
                  and manager.status = 'ACTIVE'
                  and manager.valid_from <= :businessDate
                  and (manager.valid_to is null or manager.valid_to >= :businessDate)
                """, base(principal)
                .addValue("assignmentId", record.assignmentId())
                .addValue("businessDate", record.businessDate()),
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null);
        if (directManagerAssignmentId == null) {
            throw new AccessDeniedException("当前事项未配置有效的直属主管任职，暂不能复核");
        }
        if (!principal.assignmentIds().contains(directManagerAssignmentId)) {
            throw new AccessDeniedException("仅该事项执行人的直属主管可以复核");
        }
    }

    private void notifySubmittedForReview(TenantPrincipal principal, UUID recordId) {
        var record = recordAccessForUpdate(principal, recordId);
        if (record.workPackageItemId()!=null && isManagerCycle(principal, record.workPackageItemId())) {
            for (var supervisor : supervisors.load(principal, "manager-cycle.review").forWork(record.assignmentId(), record.targetOrgUnitId())) {
                jdbc.update("""
                    insert into notification(tenant_id,recipient_account_id,recipient_assignment_id,notification_type,
                      title,content,source_type,source_id,idempotency_key)
                    values(:tenantId,:account,:assignment,'WORK_RECORD_REVIEW_PENDING','店长周期工作待复核',
                      '店长已提交周期工作，请查看内容与证据并完成复核。照片按留存策略到期清理，请及时查看。',
                      'WORK_RECORD',:record,:key)
                    on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing
                    """, base(principal).addValue("account",supervisor.accountId()).addValue("assignment",supervisor.assignmentId())
                    .addValue("record",recordId).addValue("key","cycle-review:"+recordId));
            }
            return;
        }
        SupervisorNotificationContext context = jdbc.queryForObject("""
                select record.business_date, coalesce(item.name, '工作记录') as item_name,
                       exists (
                         select 1
                         from work_package_item_responsibility responsibility
                         where responsibility.tenant_id = record.tenant_id
                           and responsibility.work_package_item_id = record.work_package_item_id
                           and responsibility.participant_type in ('REVIEWER', 'ACCEPTOR')
                           and responsibility.resolver_type = 'DIRECT_MANAGER_ASSIGNMENT'
                       ) as direct_manager_review_configured,
                       (
                         select manager.id
                         from employee_position_assignment executor
                         join employee_position_assignment manager
                           on manager.tenant_id = executor.tenant_id
                          and manager.id = executor.manager_assignment_id
                         join employee manager_employee
                           on manager_employee.tenant_id = manager.tenant_id
                          and manager_employee.id = manager.employee_id
                         where executor.tenant_id = record.tenant_id
                           and executor.id = record.position_assignment_id
                           and manager.status = 'ACTIVE'
                           and manager.valid_from <= record.business_date
                           and (manager.valid_to is null or manager.valid_to >= record.business_date)
                           and manager_employee.employment_status = 'ACTIVE'
                           and manager_employee.account_id is not null
                         limit 1
                       ) as manager_assignment_id
                from work_record record
                left join work_package_item item
                  on item.tenant_id = record.tenant_id and item.id = record.work_package_item_id
                where record.tenant_id = :tenantId and record.id = :recordId
                  and record.status = 'SUBMITTED'
                """, base(principal).addValue("recordId", recordId),
                (rs, rowNum) -> new SupervisorNotificationContext(
                        rs.getObject("business_date", java.time.LocalDate.class),
                        rs.getString("item_name"),
                        rs.getBoolean("direct_manager_review_configured"),
                        rs.getObject("manager_assignment_id", UUID.class)));
        if (context == null || !context.directManagerReviewConfigured()) {
            return;
        }
        if (context.managerAssignmentId() == null) {
            recordMissingSupervisorAudit(recordId, "DIRECT_MANAGER_ASSIGNMENT_UNRESOLVED");
            return;
        }
        UUID notificationId = UUID.randomUUID();
        String idempotencyKey = "work-record:review-pending:" + recordId;
        int inserted = jdbc.update("""
                insert into notification
                    (id, tenant_id, recipient_account_id, recipient_assignment_id,
                     notification_type, title, content, source_type, source_id, idempotency_key)
                select :id, :tenantId, manager_employee.account_id, manager.id,
                       'WORK_RECORD_REVIEW_PENDING', '工作记录待复核', :content,
                       'WORK_RECORD', :recordId, :idempotencyKey
                from employee_position_assignment manager
                join employee manager_employee
                  on manager_employee.tenant_id = manager.tenant_id
                 and manager_employee.id = manager.employee_id
                where manager.tenant_id = :tenantId and manager.id = :managerAssignmentId
                  and manager.status = 'ACTIVE'
                  and manager.valid_from <= :businessDate
                  and (manager.valid_to is null or manager.valid_to >= :businessDate)
                  and manager_employee.employment_status = 'ACTIVE'
                  and manager_employee.account_id is not null
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, base(principal)
                .addValue("id", notificationId)
                .addValue("recordId", recordId)
                .addValue("managerAssignmentId", context.managerAssignmentId())
                .addValue("businessDate", context.businessDate())
                .addValue("content", context.businessDate() + " 的“" + context.itemName()
                        + "”已提交，请及时审核。")
                .addValue("idempotencyKey", idempotencyKey));
        if (inserted == 1) {
            auditWriter.record(
                    "NOTIFICATION_CREATED",
                    "NOTIFICATION",
                    notificationId,
                    "{\"type\":\"WORK_RECORD_REVIEW_PENDING\"}");
        } else if (!reviewNotificationExists(principal, context.managerAssignmentId(), idempotencyKey)) {
            // Reporting lines can change between resolution and insert. An insert-select makes
            // that race a no-op instead of an exception that could roll back the submission.
            recordMissingSupervisorAudit(recordId, "DIRECT_MANAGER_RECIPIENT_UNAVAILABLE");
        }
    }

    private boolean reviewNotificationExists(
            TenantPrincipal principal,
            UUID managerAssignmentId,
            String idempotencyKey
    ) {
        Integer count = jdbc.queryForObject("""
                select count(*) from notification
                where tenant_id = :tenantId and recipient_assignment_id = :managerAssignmentId
                  and idempotency_key = :idempotencyKey
                """, base(principal)
                .addValue("managerAssignmentId", managerAssignmentId)
                .addValue("idempotencyKey", idempotencyKey), Integer.class);
        return count != null && count > 0;
    }

    private void recordMissingSupervisorAudit(UUID recordId, String reason) {
        auditWriter.record(
                "WORK_RECORD_REVIEW_NOTIFICATION_SKIPPED",
                "WORK_RECORD",
                recordId,
                "{\"reason\":\"" + reason + "\"}");
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private boolean isManagerCycle(TenantPrincipal principal, UUID itemId) {
        if (itemId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("select coalesce((execution_policy->>'managerCycle')::boolean,false) from work_package_item where tenant_id=:tenantId and id=:id",
                base(principal).addValue("id",itemId),Boolean.class));
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private void requireOwned(String table, TenantPrincipal principal, UUID id) {
        if (!Set.of("position_definition", "form_definition").contains(table)) {
            throw new IllegalArgumentException("不允许的实体类型");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("资源不存在或不属于当前租户");
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("表单Schema或记录内容不是有效JSON", exception);
        }
    }

    private static String resolveRecordKind(String requested, String itemType) {
        String kind;
        if (requested != null && !requested.isBlank()) {
            kind = requested.trim().toUpperCase(Locale.ROOT);
        } else if (itemType == null) {
            kind = "LEGACY";
        } else {
            kind = switch (itemType) {
                case "EVENT_RECORD" -> "EVENT";
                case "INSPECTION" -> "INSPECTION";
                case "METRIC_REVIEW" -> "METRIC_REVIEW";
                case "REVIEW_APPROVAL" -> "REVIEW_APPROVAL";
                default -> "SCHEDULED";
            };
        }
        if (!RECORD_KINDS.contains(kind)) {
            throw new IllegalArgumentException("不支持的工作记录类型: " + kind);
        }
        return kind;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算工作记录内容摘要", exception);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, Object> response(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record WorkContext(
            UUID versionId,
            UUID itemId,
            UUID expectationId,
            String itemType,
            String itemCode,
            UUID formVersionId,
            UUID assignmentId,
            UUID targetOrgUnitId,
            java.time.LocalDate businessDate,
            String expectationStatus,
            boolean repeatableDailyRecords
    ) {
        private static WorkContext legacy() {
            return new WorkContext(null, null, null, null, null, null, null, null, null, null, false);
        }
    }

    private record RecordSequence(int seriesEntryNo, int attemptNo) {
    }

    private record RecordAccess(
            String status,
            UUID targetOrgUnitId,
            UUID assignmentId,
            UUID formVersionId,
            UUID workPackageItemId,
            UUID expectationId,
            UUID submittedByAccountId,
            java.time.LocalDate businessDate,
            OffsetDateTime occurredAt,
            String payloadJson,
            boolean repeatableEntry,
            int seriesEntryNo
    ) {
    }

    private record SupervisorNotificationContext(
            java.time.LocalDate businessDate,
            String itemName,
            boolean directManagerReviewConfigured,
            UUID managerAssignmentId
    ) {
    }
}
