package cn.sifangguan.hotelaios.tasks;

import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.notifications.StoreManagerSupervisorResolver;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.TenantSystemAccountResolver;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TaskService {
    private enum CreationSource {
        MANUAL,
        RULE_ENGINE,
        TASK_CANDIDATE
    }

    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "DISPATCH", Map.of("PROPOSED", "PENDING_ACK"),
            "ACKNOWLEDGE", Map.of("PENDING_ACK", "IN_PROGRESS"),
            "START", Map.of("REWORK", "IN_PROGRESS"),
            "SUBMIT_RESULT", Map.of("IN_PROGRESS", "RESULT_SUBMITTED"),
            "APPROVE", Map.of("RESULT_SUBMITTED", "COMPLETED", "AWAITING_REVIEW", "COMPLETED"),
            "REWORK", Map.of("RESULT_SUBMITTED", "REWORK", "AWAITING_REVIEW", "REWORK"),
            "REJECT", Map.of("RESULT_SUBMITTED", "REWORK", "AWAITING_REVIEW", "REWORK")
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final NotificationService notificationService;
    private final TenantSystemAccountResolver systemAccountResolver;
    private final AttachmentService attachmentService;
    private final TaskTargetPolicy taskTargetPolicy;
    private final StoreManagerSupervisorResolver supervisorResolver;
    private final long defaultEscalationDelayHours;

    public TaskService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            NotificationService notificationService,
            TenantSystemAccountResolver systemAccountResolver,
            AttachmentService attachmentService,
            TaskTargetPolicy taskTargetPolicy,
            StoreManagerSupervisorResolver supervisorResolver,
            @Value("${app.tasks.default-escalation-delay-hours:48}") long defaultEscalationDelayHours
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.notificationService = notificationService;
        this.systemAccountResolver = systemAccountResolver;
        this.attachmentService = attachmentService;
        this.taskTargetPolicy = taskTargetPolicy;
        this.supervisorResolver = supervisorResolver;
        this.defaultEscalationDelayHours = Math.max(0, defaultEscalationDelayHours);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String view, String status, UUID orgUnitId) {
        accessPolicy.requirePermission("task.read");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        String normalizedView = normalizeView(view);
        if ("TEAM".equals(normalizedView)
                && !principal.hasTenantScope()
                && !principal.hasPermission("task.review")
                && !principal.hasPermission("task.dispatch")
                && !principal.hasPermission("task.create")) {
            throw new AccessDeniedException("当前角色无权查看团队任务");
        }
        MapSqlParameterSource params = visibleParams(principal)
                .addValue("view", normalizedView)
                .addValue("status", normalize(status))
                .addValue("orgUnitId", orgUnitId);
        return jdbc.queryForList("""
                select t.id, t.task_no, t.title, t.description, t.lifecycle_status, t.sla_status, t.priority,
                       t.creation_source,
                       t.org_unit_id, o.name as org_unit_name, t.standard_version_id, t.result_snapshot,
                       t.due_at, t.row_version, t.created_at,
                       a.position_assignment_id as assignee_assignment_id,
                       a.employee_snapshot ->> 'name' as assignee_name,
                       r.position_assignment_id as reviewer_assignment_id,
                       r.employee_snapshot ->> 'name' as reviewer_name
                from management_task t
                join org_unit o on o.tenant_id = t.tenant_id and o.id = t.org_unit_id
                left join task_participant a on a.tenant_id = t.tenant_id and a.task_id = t.id
                    and a.participant_type = 'ASSIGNEE' and a.valid_to is null
                left join task_participant r on r.tenant_id = t.tenant_id and r.task_id = t.id
                    and r.participant_type = 'REVIEWER' and r.valid_to is null
                where t.tenant_id = :tenantId
                  and (cast(:status as varchar) is null or t.lifecycle_status = :status)
                  and (cast(:orgUnitId as uuid) is null or exists (
                    select 1 from org_unit_closure requested_scope
                    where requested_scope.tenant_id = t.tenant_id
                      and requested_scope.ancestor_id = :orgUnitId
                      and requested_scope.descendant_id = t.org_unit_id
                  ))
                  and (
                    (:view = 'MINE' and exists (
                        select 1 from task_participant p
                        join employee_position_assignment pa on pa.tenant_id = p.tenant_id and pa.id = p.position_assignment_id
                        join employee e on e.tenant_id = pa.tenant_id and e.id = pa.employee_id
                        where p.tenant_id = t.tenant_id and p.task_id = t.id and e.account_id = :actorId
                          and p.valid_to is null
                    ))
                    or (:view = 'REVIEW' and exists (
                        select 1 from task_participant p
                        join employee_position_assignment pa on pa.tenant_id = p.tenant_id and pa.id = p.position_assignment_id
                        join employee e on e.tenant_id = pa.tenant_id and e.id = pa.employee_id
                        where p.tenant_id = t.tenant_id and p.task_id = t.id and e.account_id = :actorId
                          and p.participant_type = 'REVIEWER' and p.valid_to is null
                    ))
                    or (:view = 'TEAM' and (
                        :tenantReadScope = true
                        or t.org_unit_id in (:readOrgUnits)
                    ))
                    or (:view = 'ALL' and (
                        :tenantReadScope = true
                        or (:canReadOrg = true and t.org_unit_id in (:readOrgUnits))
                        or t.created_by = :actorId
                        or exists (
                            select 1 from task_participant p
                            join employee_position_assignment pa on pa.tenant_id = p.tenant_id and pa.id = p.position_assignment_id
                            join employee e on e.tenant_id = pa.tenant_id and e.id = pa.employee_id
                            where p.tenant_id = t.tenant_id and p.task_id = t.id and e.account_id = :actorId
                              and p.valid_to is null
                        )
                    ))
                  )
                order by t.created_at desc
                limit 500
                """, params);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> targets() {
        accessPolicy.requirePermission("task.create");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        return taskTargetPolicy.listTargets(principal);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID taskId) {
        accessPolicy.requirePermission("task.read");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        return detailResult(principal, taskId);
    }

    private Map<String, Object> detailResult(TenantPrincipal principal, UUID taskId) {
        requireVisible(principal, taskId);
        Map<String, Object> result = new LinkedHashMap<>(taskRow(principal, taskId, false));
        result.put("participants", jdbc.queryForList("""
                select id, participant_type, position_assignment_id, employee_snapshot,
                       position_snapshot, org_snapshot, valid_from, valid_to
                from task_participant where tenant_id = :tenantId and task_id = :taskId
                order by participant_type, created_at
                """, base(principal).addValue("taskId", taskId)));
        result.put("evidence", jdbc.queryForList("""
                select id, submitted_by_assignment_id, evidence_type, object_key, original_name,
                       media_type, size_bytes, sha256, structured_result, scan_status, created_at
                from task_evidence where tenant_id = :tenantId and task_id = :taskId
                order by created_at
                """, base(principal).addValue("taskId", taskId)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(UUID taskId) {
        accessPolicy.requirePermission("task.read");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        return jdbc.queryForList("""
                select id, from_status, to_status, command, actor_account_id, actor_assignment_id,
                       standard_evaluation_id, task_version, payload, occurred_at
                from task_transition where tenant_id = :tenantId and task_id = :taskId
                order by occurred_at, id
                """, base(principal).addValue("taskId", taskId));
    }

    @Transactional
    public Map<String, Object> create(TaskModels.CreateTask request, String idempotencyKey) {
        accessPolicy.requirePermission("task.create");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        UUID reviewerAssignmentId = taskTargetPolicy.resolveReviewer(
                principal,
                request.orgUnitId(),
                request.assigneeAssignmentId(),
                request.reviewerAssignmentId(),
                request.creatorAssignmentId()
        );
        TaskModels.RuleTaskSpec spec = new TaskModels.RuleTaskSpec(
                null, null, request.orgUnitId(), request.assigneeAssignmentId(), reviewerAssignmentId,
                request.standardVersionId(), request.workRecordId(), request.title(), request.description(),
                request.priority(), request.dueAt(), request.sourceSnapshot());
        UUID id = createTask(spec, idempotencyKey, CreationSource.MANUAL);
        if (request.creatorAssignmentId() != null) {
            requireActorAssignment(principal, request.creatorAssignmentId());
            jdbc.update("""
                    update management_task
                    set created_by_assignment_id = coalesce(created_by_assignment_id, :creatorAssignmentId)
                    where tenant_id = :tenantId and id = :taskId and created_by = :actorId
                    """, base(principal)
                    .addValue("taskId", id)
                    .addValue("actorId", principal.actorId())
                    .addValue("creatorAssignmentId", request.creatorAssignmentId()));
        }
        if (Boolean.TRUE.equals(request.dispatchNow())) {
            accessPolicy.requirePermission("task.dispatch");
            Map<String, Object> row = taskRow(principal, id, true);
            if ("PROPOSED".equals(row.get("lifecycle_status"))) {
                TaskModels.Command dispatch = new TaskModels.Command(
                        ((Number) row.get("row_version")).longValue(),
                        request.creatorAssignmentId(),
                        JsonNodeFactory.instance.objectNode().put("source", "CREATE_AND_DISPATCH")
                );
                transition(id, "DISPATCH", idempotencyKey + ":dispatch", dispatch, false, null);
            }
        }
        return detailResult(principal, id);
    }

    @Transactional
    public UUID createFromRule(TaskModels.RuleTaskSpec spec, String idempotencyKey) {
        prepare();
        UUID taskId = createTask(spec, idempotencyKey, CreationSource.RULE_ENGINE);
        Map<String, Object> row = taskRow(accessPolicy.principal(), taskId, true);
        if ("PROPOSED".equals(row.get("lifecycle_status"))) {
            TaskModels.Command dispatch = new TaskModels.Command(((Number) row.get("row_version")).longValue(), null,
                    JsonNodeFactory.instance.objectNode().put("source", "RULE_ENGINE"));
            transition(taskId, "DISPATCH", idempotencyKey + ":dispatch", dispatch, true, null);
        }
        return taskId;
    }

    @Transactional
    public UUID createFromCandidate(TaskModels.CandidateTaskSpec candidate, String idempotencyKey) {
        prepare();
        ObjectNode sourceSnapshot = JsonNodeFactory.instance.objectNode();
        if (candidate.sourceSnapshot() != null && candidate.sourceSnapshot().isObject()) {
            sourceSnapshot.setAll((ObjectNode) candidate.sourceSnapshot().deepCopy());
        } else if (candidate.sourceSnapshot() != null) {
            sourceSnapshot.set("candidateSource", candidate.sourceSnapshot().deepCopy());
        }
        sourceSnapshot.put("taskCandidateId", candidate.candidateId().toString());
        TaskModels.RuleTaskSpec spec = new TaskModels.RuleTaskSpec(
                null,
                null,
                candidate.orgUnitId(),
                candidate.assigneeAssignmentId(),
                candidate.reviewerAssignmentId(),
                candidate.standardVersionId(),
                null,
                candidate.title(),
                candidate.description(),
                candidate.priority(),
                candidate.dueAt(),
                sourceSnapshot
        );
        UUID taskId = createTask(spec, idempotencyKey, CreationSource.TASK_CANDIDATE);
        Map<String, Object> row = taskRow(accessPolicy.principal(), taskId, true);
        if ("PROPOSED".equals(row.get("lifecycle_status"))) {
            TaskModels.Command dispatch = new TaskModels.Command(
                    ((Number) row.get("row_version")).longValue(),
                    null,
                    JsonNodeFactory.instance.objectNode()
                            .put("source", CreationSource.TASK_CANDIDATE.name())
                            .put("taskCandidateId", candidate.candidateId().toString())
            );
            transition(taskId, "DISPATCH", idempotencyKey + ":dispatch", dispatch, true, null);
        }
        return taskId;
    }

    /** Read-only service contract for closed-loop modules; controllers still use detail(). */
    @Transactional(readOnly = true)
    public TaskModels.TaskReference integrationReference(UUID taskId) {
        TenantPrincipal principal = prepare();
        Map<String, Object> row = taskRow(principal, taskId, false);
        return new TaskModels.TaskReference(
                (UUID) row.get("id"),
                String.valueOf(row.get("task_no")),
                (UUID) row.get("org_unit_id"),
                String.valueOf(row.get("title")),
                String.valueOf(row.get("lifecycle_status")),
                ((Number) row.get("row_version")).longValue()
        );
    }

    @Transactional
    public Map<String, Object> command(UUID taskId, String command, String idempotencyKey, TaskModels.Command request) {
        String normalizedCommand = command.toUpperCase(Locale.ROOT).replace('-', '_');
        switch (normalizedCommand) {
            case "DISPATCH" -> accessPolicy.requirePermission("task.dispatch");
            case "ACKNOWLEDGE", "START", "SUBMIT_RESULT" -> accessPolicy.requirePermission("task.act");
            case "APPROVE", "REWORK", "REJECT" -> accessPolicy.requirePermission("task.review");
            case "CANCEL" -> accessPolicy.requirePermission("task.cancel");
            default -> throw new IllegalArgumentException("不支持的任务命令: " + normalizedCommand);
        }
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        return transition(taskId, normalizedCommand, idempotencyKey, request, false, null);
    }

    @Transactional
    public Map<String, Object> addEvidence(UUID taskId, TaskModels.AddEvidence request) {
        accessPolicy.requirePermission("task.act");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        requireActorAssignment(principal, request.submittedByAssignmentId());
        requireParticipant(principal, taskId, request.submittedByAssignmentId(), Set.of("ASSIGNEE", "REVIEWER"));
        Map<String, Object> task = taskRow(principal, taskId, true);
        if (!Set.of("IN_PROGRESS", "REWORK", "RESULT_SUBMITTED").contains(task.get("lifecycle_status"))) {
            throw new IllegalArgumentException("当前任务状态不允许追加执行证据");
        }
        if (request.structuredResult() != null
                && "DISPATCH_REFERENCE".equals(request.structuredResult().path("purpose").asText())) {
            throw new AccessDeniedException("执行人不能将执行证据标记为下达参考资料");
        }
        requireTaskEvidenceCapacity(principal, taskId);
        UUID id = UUID.randomUUID();
        JsonNode structured = request.structuredResult() == null ? JsonNodeFactory.instance.objectNode() : request.structuredResult();
        jdbc.update("""
                insert into task_evidence
                    (id, tenant_id, task_id, submitted_by_assignment_id, evidence_type, object_key,
                     original_name, media_type, size_bytes, sha256, structured_result)
                values
                    (:id, :tenantId, :taskId, :assignmentId, :type, :objectKey,
                     :originalName, :mediaType, :sizeBytes, :sha256, cast(:structured as jsonb))
                """, base(principal)
                .addValue("id", id)
                .addValue("taskId", taskId)
                .addValue("assignmentId", request.submittedByAssignmentId())
                .addValue("type", request.evidenceType().toUpperCase(Locale.ROOT))
                .addValue("objectKey", request.objectKey())
                .addValue("originalName", request.originalName())
                .addValue("mediaType", request.mediaType())
                .addValue("sizeBytes", request.sizeBytes())
                .addValue("sha256", request.sha256())
                .addValue("structured", structured.toString()));
        auditWriter.record("TASK_EVIDENCE_ADDED", "TASK_EVIDENCE", id,
                "{\"taskId\":\"" + taskId + "\"}");
        return Map.of("id", id, "taskId", taskId, "evidenceType", request.evidenceType().toUpperCase(Locale.ROOT));
    }

    @Transactional
    public Map<String, Object> uploadEvidence(UUID taskId, UUID submittedByAssignmentId, MultipartFile file) {
        accessPolicy.requirePermission("task.act");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        requireActorAssignment(principal, submittedByAssignmentId);
        requireParticipant(principal, taskId, submittedByAssignmentId, Set.of("ASSIGNEE"));
        Map<String, Object> task = taskRow(principal, taskId, true);
        if (!Set.of("IN_PROGRESS", "REWORK").contains(task.get("lifecycle_status"))) {
            throw new IllegalArgumentException("仅执行中或返工中的任务可以上传证据");
        }
        requireTaskEvidenceCapacity(principal, taskId);
        UUID evidenceId = UUID.randomUUID();
        String original = file == null || file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = principal.tenantId() + "/tasks/" + taskId + "/" + evidenceId + "-" + safeName;
        AttachmentService.StoredObject stored = attachmentService.storeObject(objectKey, file);
        try {
            String evidenceType = stored.mediaType().startsWith("image/") ? "IMAGE" : "FILE";
            jdbc.update("""
                    insert into task_evidence
                        (id, tenant_id, task_id, submitted_by_assignment_id, evidence_type, object_key,
                         original_name, media_type, size_bytes, sha256, scan_status)
                    values (:id, :tenantId, :taskId, :assignmentId, :type, :objectKey,
                            :originalName, :mediaType, :sizeBytes, :sha256, :scanStatus)
                    """, base(principal).addValue("id", evidenceId).addValue("taskId", taskId)
                    .addValue("assignmentId", submittedByAssignmentId).addValue("type", evidenceType)
                    .addValue("objectKey", stored.objectKey()).addValue("originalName", stored.originalName())
                    .addValue("mediaType", stored.mediaType()).addValue("sizeBytes", stored.sizeBytes())
                    .addValue("sha256", stored.sha256()).addValue("scanStatus", stored.scanStatus()));
        } catch (RuntimeException exception) {
            attachmentService.discardUncommittedObject(stored);
            throw exception;
        }
        auditWriter.record("TASK_EVIDENCE_UPLOADED", "TASK_EVIDENCE", evidenceId,
                "{\"taskId\":\"" + taskId + "\",\"sha256\":\"" + stored.sha256() + "\"}");
        return Map.of("id", evidenceId, "taskId", taskId, "originalName", stored.originalName(),
                "mediaType", stored.mediaType(), "sizeBytes", stored.sizeBytes(), "scanStatus", stored.scanStatus());
    }

    @Transactional
    public Map<String, Object> uploadDispatchAttachment(
            UUID taskId, UUID submittedByAssignmentId, String note, MultipartFile file
    ) {
        accessPolicy.requirePermission("task.dispatch");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        if (submittedByAssignmentId != null) {
            requireActorAssignment(principal, submittedByAssignmentId);
        } else if (!principal.hasTenantScope()) {
            throw new AccessDeniedException("下达任务的附件必须指定当前有效任职");
        }
        Map<String, Object> task = taskRow(principal, taskId, true);
        if (!"PROPOSED".equals(task.get("lifecycle_status"))) {
            throw new IllegalArgumentException("只有待派发任务可以补充下达参考图片");
        }
        if (!principal.actorId().equals(task.get("created_by"))) {
            throw new AccessDeniedException("只有任务创建人可以上传下达参考图片");
        }
        UUID creatorAssignmentId = (UUID) task.get("created_by_assignment_id");
        if (creatorAssignmentId != null && !creatorAssignmentId.equals(submittedByAssignmentId)) {
            throw new AccessDeniedException("当前任职与任务创建任职不一致");
        }
        requireDispatchReferenceCapacity(principal, taskId);

        UUID evidenceId = UUID.randomUUID();
        String original = file == null || file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = principal.tenantId() + "/tasks/" + taskId + "/dispatch/" + evidenceId + "-" + safeName;
        AttachmentService.StoredObject stored = attachmentService.storeObject(objectKey, file);
        ObjectNode structured = JsonNodeFactory.instance.objectNode().put("purpose", "DISPATCH_REFERENCE");
        String normalizedNote = note == null ? "" : note.trim();
        if (!normalizedNote.isEmpty()) {
            structured.put("note", normalizedNote.substring(0, Math.min(normalizedNote.length(), 500)));
        }
        try {
            String evidenceType = stored.mediaType().startsWith("image/") ? "IMAGE" : "FILE";
            jdbc.update("""
                    insert into task_evidence
                        (id, tenant_id, task_id, submitted_by_assignment_id, evidence_type, object_key,
                         original_name, media_type, size_bytes, sha256, structured_result, scan_status)
                    values (:id, :tenantId, :taskId, :assignmentId, :type, :objectKey,
                            :originalName, :mediaType, :sizeBytes, :sha256, cast(:structured as jsonb), :scanStatus)
                    """, base(principal).addValue("id", evidenceId).addValue("taskId", taskId)
                    .addValue("assignmentId", submittedByAssignmentId).addValue("type", evidenceType)
                    .addValue("objectKey", stored.objectKey()).addValue("originalName", stored.originalName())
                    .addValue("mediaType", stored.mediaType()).addValue("sizeBytes", stored.sizeBytes())
                    .addValue("sha256", stored.sha256()).addValue("structured", structured.toString())
                    .addValue("scanStatus", stored.scanStatus()));
        } catch (RuntimeException exception) {
            attachmentService.discardUncommittedObject(stored);
            throw exception;
        }
        auditWriter.record("TASK_DISPATCH_ATTACHMENT_UPLOADED", "TASK_EVIDENCE", evidenceId,
                "{\"taskId\":\"" + taskId + "\",\"sha256\":\"" + stored.sha256() + "\"}");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", evidenceId);
        result.put("taskId", taskId);
        result.put("originalName", stored.originalName());
        result.put("mediaType", stored.mediaType());
        result.put("sizeBytes", stored.sizeBytes());
        result.put("scanStatus", stored.scanStatus());
        result.put("structuredResult", structured);
        return result;
    }

    @Transactional(readOnly = true)
    public AttachmentService.Download evidenceContent(UUID taskId, UUID evidenceId) {
        accessPolicy.requirePermission("task.read");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        Map<String, Object> evidence = jdbc.queryForMap("""
                select object_key, original_name, media_type, size_bytes, scan_status
                from task_evidence
                where tenant_id = :tenantId and task_id = :taskId and id = :evidenceId
                  and object_key is not null
                """, base(principal).addValue("taskId", taskId).addValue("evidenceId", evidenceId));
        return attachmentService.openStoredObject(String.valueOf(evidence.get("object_key")),
                String.valueOf(evidence.get("original_name")), String.valueOf(evidence.get("media_type")),
                ((Number) evidence.get("size_bytes")).longValue(), String.valueOf(evidence.get("scan_status")));
    }

    @Transactional
    public void deleteEvidence(UUID taskId, UUID evidenceId, UUID actorAssignmentId) {
        accessPolicy.requirePermission("task.act");
        TenantPrincipal principal = prepare();
        rejectChairmanBusinessActor(principal);
        requireVisible(principal, taskId);
        requireActorAssignment(principal, actorAssignmentId);
        requireParticipant(principal, taskId, actorAssignmentId, Set.of("ASSIGNEE"));
        Map<String, Object> task = taskRow(principal, taskId, true);
        if (!Set.of("IN_PROGRESS", "REWORK").contains(task.get("lifecycle_status"))) {
            throw new IllegalArgumentException("当前任务状态不允许删除执行证据");
        }
        String objectKey = jdbc.queryForObject("""
                select object_key from task_evidence
                where tenant_id = :tenantId and task_id = :taskId and id = :evidenceId
                  and submitted_by_assignment_id = :assignmentId and object_key is not null
                """, base(principal).addValue("taskId", taskId).addValue("evidenceId", evidenceId)
                .addValue("assignmentId", actorAssignmentId), String.class);
        int deleted = jdbc.update("""
                delete from task_evidence
                where tenant_id = :tenantId and task_id = :taskId and id = :evidenceId
                  and submitted_by_assignment_id = :assignmentId
                """, base(principal).addValue("taskId", taskId).addValue("evidenceId", evidenceId)
                .addValue("assignmentId", actorAssignmentId));
        if (deleted != 1) throw new IllegalArgumentException("任务证据不存在或不属于当前任职");
        attachmentService.removeStoredObject(objectKey);
        auditWriter.record("TASK_EVIDENCE_DELETED", "TASK_EVIDENCE", evidenceId,
                "{\"taskId\":\"" + taskId + "\"}");
    }

    @Transactional
    public Map<String, Object> completeEvaluation(UUID taskId, UUID evaluationId, String idempotencyKey) {
        TenantPrincipal principal = prepare();
        Map<String, Object> task = taskRow(principal, taskId, true);
        TaskModels.Command request = new TaskModels.Command(((Number) task.get("row_version")).longValue(), null,
                JsonNodeFactory.instance.objectNode().put("evaluationId", evaluationId.toString()));
        return transition(taskId, "EVALUATION_COMPLETED", idempotencyKey, request, true, evaluationId);
    }

    @Transactional
    public Map<String, Object> processSla() {
        accessPolicy.requirePermission("task.dispatch");
        TenantPrincipal principal = prepare();
        return processSla(principal, 500);
    }

    @Transactional
    public Map<String, Object> processSlaAsSystem(UUID tenantId, int batchLimit, UUID correlationId) {
        if (batchLimit < 1 || batchLimit > 500) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 500");
        }
        UUID actorId = systemAccountResolver.resolveOrCreate(tenantId);
        TenantPrincipal previous = TenantContext.current().orElse(null);
        TenantPrincipal systemPrincipal = new TenantPrincipal(
                tenantId,
                actorId,
                "SYSTEM_AUTOMATION",
                Set.of("SYSTEM_AUTOMATION"),
                Set.of("task.dispatch", "notification.read"),
                Set.of(),
                Set.of(),
                true,
                correlationId
        );
        TenantContext.set(systemPrincipal);
        try {
            databaseContext.apply(tenantId);
            return processSla(systemPrincipal, batchLimit);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private Map<String, Object> processSla(TenantPrincipal principal, int batchLimit) {
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                with candidates as (
                    select id from management_task
                    where tenant_id = :tenantId and due_at < now() and sla_status <> 'OVERDUE'
                      and lifecycle_status not in ('COMPLETED', 'CANCELLED')
                    order by due_at, id
                    for update skip locked
                    limit :batchLimit
                )
                update management_task t
                set sla_status = 'OVERDUE', row_version = t.row_version + 1
                from candidates c
                where t.tenant_id = :tenantId and t.id = c.id
                returning t.id, t.title, t.lifecycle_status, t.row_version, t.org_unit_id
                """, base(principal).addValue("batchLimit", batchLimit));
        int notifications = 0;
        var supervisors = overdue.isEmpty() ? null : supervisorResolver.load(principal, "task.read");
        for (Map<String, Object> row : overdue) {
            UUID taskId = (UUID) row.get("id");
            jdbc.update("""
                    insert into task_transition
                        (tenant_id, task_id, from_status, to_status, command, actor_account_id,
                         task_version, idempotency_key, payload)
                    values
                        (:tenantId, :taskId, :status, :status, 'MARK_OVERDUE', :actorId,
                         :taskVersion, :key, '{"slaStatus":"OVERDUE"}'::jsonb)
                    on conflict (tenant_id, task_id, idempotency_key) do nothing
                    """, base(principal)
                    .addValue("taskId", taskId)
                    .addValue("status", row.get("lifecycle_status"))
                    .addValue("actorId", principal.actorId())
                    .addValue("taskVersion", row.get("row_version"))
                    .addValue("key", "sla-overdue:" + taskId));
            UUID reviewer = participantAssignment(principal, taskId, "REVIEWER");
            if (reviewer != null) {
                notificationService.createForAssignment(reviewer, "TASK_OVERDUE", "任务已逾期",
                        String.valueOf(row.get("title")), "TASK", taskId, "task-overdue:" + taskId);
                notifications++;
            }
            UUID executor = participantAssignment(principal, taskId, "ASSIGNEE");
            if (executor != null) {
                for (var supervisor : supervisors.forWork(executor, (UUID) row.get("org_unit_id"))) {
                    notificationService.createForAssignment(supervisor.assignmentId(), "TASK_OVERDUE", "店长任务已逾期",
                            String.valueOf(row.get("title")), "TASK", taskId, "task-overdue:" + taskId);
                    notifications++;
                }
            }
        }
        int cancelledEscalations = jdbc.update("""
                update task_escalation e
                set status = 'CANCELLED', executed_at = now()
                from management_task t
                where e.tenant_id = :tenantId and t.tenant_id = e.tenant_id and t.id = e.task_id
                  and e.status = 'SCHEDULED' and t.lifecycle_status in ('COMPLETED', 'CANCELLED')
                """, base(principal));
        List<Map<String, Object>> dueEscalations = jdbc.queryForList("""
                select e.id as escalation_id, e.task_id, e.escalation_level,
                       e.resolved_assignment_id, t.title, t.lifecycle_status, t.row_version
                from task_escalation e
                join management_task t on t.tenant_id = e.tenant_id and t.id = e.task_id
                where e.tenant_id = :tenantId and e.status = 'SCHEDULED' and e.scheduled_at <= now()
                  and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                order by e.scheduled_at, e.id
                limit :batchLimit
                for update of e skip locked
                """, base(principal).addValue("batchLimit", batchLimit));
        int escalations = 0;
        for (Map<String, Object> row : dueEscalations) {
            UUID escalationId = (UUID) row.get("escalation_id");
            UUID taskId = (UUID) row.get("task_id");
            int level = ((Number) row.get("escalation_level")).intValue();
            int claimed = jdbc.update("""
                    update task_escalation
                    set status = 'EXECUTED', executed_at = now(), last_error = null
                    where tenant_id = :tenantId and id = :escalationId and status = 'SCHEDULED'
                    """, base(principal).addValue("escalationId", escalationId));
            if (claimed != 1) {
                continue;
            }
            jdbc.update("""
                    insert into task_transition
                        (tenant_id, task_id, from_status, to_status, command, actor_account_id,
                         task_version, idempotency_key, payload)
                    values
                        (:tenantId, :taskId, :status, :status, 'ESCALATE', :actorId,
                         :taskVersion, :key, cast(:payload as jsonb))
                    on conflict (tenant_id, task_id, idempotency_key) do nothing
                    """, base(principal)
                    .addValue("taskId", taskId)
                    .addValue("status", row.get("lifecycle_status"))
                    .addValue("actorId", principal.actorId())
                    .addValue("taskVersion", row.get("row_version"))
                    .addValue("key", "task-escalate:" + taskId + ":" + level)
                    .addValue("payload", "{\"escalationLevel\":" + level + ",\"escalationId\":\"" + escalationId + "\"}"));
            UUID resolvedAssignmentId = (UUID) row.get("resolved_assignment_id");
            if (resolvedAssignmentId != null) {
                notificationService.createForAssignment(resolvedAssignmentId, "TASK_ESCALATED", "任务逾期升级",
                        String.valueOf(row.get("title")), "TASK", taskId,
                        "task-escalated:" + taskId + ":" + level);
                notifications++;
            }
            auditWriter.record("TASK_ESCALATED", "TASK", taskId,
                    "{\"escalationLevel\":" + level + ",\"escalationId\":\"" + escalationId + "\"}");
            escalations++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overdueTasks", overdue.size());
        result.put("escalations", escalations);
        result.put("cancelledEscalations", cancelledEscalations);
        result.put("notifications", notifications);
        return result;
    }

    private UUID createTask(TaskModels.RuleTaskSpec spec, String idempotencyKey, CreationSource source) {
        TenantPrincipal principal = accessPolicy.principal();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key不能为空");
        }
        List<UUID> idempotentTask = jdbc.queryForList("""
                select id from management_task where tenant_id = :tenantId and idempotency_key = :key
                """, base(principal).addValue("key", idempotencyKey), UUID.class);
        if (!idempotentTask.isEmpty()) {
            return idempotentTask.getFirst();
        }
        if (spec.assigneeAssignmentId() == null || spec.reviewerAssignmentId() == null) {
            throw new IllegalArgumentException("任务责任人与验收人必须解析为精确任职");
        }
        if (spec.assigneeAssignmentId().equals(spec.reviewerAssignmentId())) {
            throw new IllegalArgumentException("任务责任人与验收人不得为同一任职");
        }
        if (source != CreationSource.MANUAL || principal.hasTenantScope()) {
            requireAssignment(principal, spec.assigneeAssignmentId());
            requireAssignment(principal, spec.reviewerAssignmentId());
        }
        requireOwnedOptional(principal, "standard_version", spec.standardVersionId());
        requireOwnedOptional(principal, "work_record", spec.workRecordId());
        if (spec.sourceActionId() != null) {
            List<UUID> existing = jdbc.queryForList("""
                    select id from management_task where tenant_id = :tenantId and source_action_id = :sourceActionId
                    """, base(principal).addValue("sourceActionId", spec.sourceActionId()), UUID.class);
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
        }
        UUID taskId = UUID.randomUUID();
        String taskNo = "T-" + OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + taskId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        JsonNode snapshot = spec.sourceSnapshot() == null ? JsonNodeFactory.instance.objectNode() : spec.sourceSnapshot();
        jdbc.update("""
                insert into management_task
                    (id, tenant_id, task_no, idempotency_key, source_event_id, source_action_id, standard_version_id,
                     work_record_id, org_unit_id, title, description, priority, due_at,
                     source_snapshot, responsibility_snapshot, created_by)
                values
                    (:id, :tenantId, :taskNo, :idempotencyKey, :sourceEventId, :sourceActionId, :standardVersionId,
                     :workRecordId, :orgUnitId, :title, :description, :priority, :dueAt,
                     cast(:sourceSnapshot as jsonb), cast(:responsibility as jsonb), :actorId)
                """, base(principal)
                .addValue("id", taskId)
                .addValue("taskNo", taskNo)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("sourceEventId", spec.sourceEventId())
                .addValue("sourceActionId", spec.sourceActionId())
                .addValue("standardVersionId", spec.standardVersionId())
                .addValue("workRecordId", spec.workRecordId())
                .addValue("orgUnitId", spec.orgUnitId())
                .addValue("title", spec.title())
                .addValue("description", spec.description())
                .addValue("priority", normalizePriority(spec.priority()))
                .addValue("dueAt", spec.dueAt())
                .addValue("sourceSnapshot", snapshot.toString())
                .addValue("responsibility", "{\"assigneeAssignmentId\":\"" + spec.assigneeAssignmentId()
                        + "\",\"reviewerAssignmentId\":\"" + spec.reviewerAssignmentId() + "\"}")
                .addValue("actorId", principal.actorId()));
        insertParticipant(principal, taskId, "ASSIGNEE", spec.assigneeAssignmentId());
        insertParticipant(principal, taskId, "REVIEWER", spec.reviewerAssignmentId());
        jdbc.update("""
                insert into task_transition
                    (tenant_id, task_id, from_status, to_status, command, actor_account_id,
                     task_version, idempotency_key, payload)
                values (:tenantId, :taskId, null, 'PROPOSED', 'CREATE', :actorId, 0, :key,
                        cast(:payload as jsonb))
                """, base(principal)
                .addValue("taskId", taskId)
                .addValue("actorId", principal.actorId())
                .addValue("key", idempotencyKey + ":create")
                .addValue("payload", "{\"source\":\"" + source.name() + "\"}"));
        if (spec.dueAt() != null) {
            jdbc.update("""
                    insert into task_escalation
                        (tenant_id, task_id, escalation_level, scheduled_at, resolver_snapshot, resolved_assignment_id)
                    values (:tenantId, :taskId, 1, :scheduledAt,
                            '{"resolver":"REVIEWER"}'::jsonb, :reviewerAssignmentId)
                    """, base(principal)
                    .addValue("taskId", taskId)
                    .addValue("scheduledAt", spec.dueAt().plusHours(defaultEscalationDelayHours))
                    .addValue("reviewerAssignmentId", spec.reviewerAssignmentId()));
        }
        auditWriter.record("TASK_CREATED", "TASK", taskId,
                "{\"taskNo\":\"" + taskNo + "\",\"source\":\"" + source.name() + "\"}");
        auditWriter.emit("TASK", taskId, "TaskCreated", "{\"taskId\":\"" + taskId + "\"}");
        return taskId;
    }

    private Map<String, Object> transition(
            UUID taskId,
            String command,
            String idempotencyKey,
            TaskModels.Command request,
            boolean internal,
            UUID evaluationId
    ) {
        TenantPrincipal principal = accessPolicy.principal();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key不能为空");
        }
        Integer duplicate = jdbc.queryForObject("""
                select count(*) from task_transition
                where tenant_id = :tenantId and task_id = :taskId and idempotency_key = :key
                """, base(principal).addValue("taskId", taskId).addValue("key", idempotencyKey), Integer.class);
        if (duplicate != null && duplicate > 0) {
            return taskRow(principal, taskId, false);
        }
        Map<String, Object> task = taskRow(principal, taskId, true);
        String from = String.valueOf(task.get("lifecycle_status"));
        boolean manualNoStandardReview = validateReviewPath(task, command, from);
        String to = targetState(command, from);
        if (!internal) {
            authorizeCommand(principal, taskId, command, request.actorAssignmentId());
        }
        JsonNode payload = request.payload() == null ? JsonNodeFactory.instance.objectNode() : request.payload();
        if (manualNoStandardReview) {
            ObjectNode auditedPayload = JsonNodeFactory.instance.objectNode();
            if (payload.isObject()) {
                auditedPayload.setAll((ObjectNode) payload);
            } else {
                auditedPayload.set("requestPayload", payload);
            }
            auditedPayload.put("reviewMode", "MANUAL_NO_STANDARD");
            payload = auditedPayload;
        }
        // Keep the existing task_transition command constraint backward compatible.
        // REJECT is the explicit API/audit action; its lifecycle transition is the
        // established REWORK event and the original action remains in the payload.
        String persistedCommand = command;
        if ("REJECT".equals(command)) {
            persistedCommand = "REWORK";
            ObjectNode auditedPayload = payload.isObject()
                    ? ((ObjectNode) payload).deepCopy()
                    : JsonNodeFactory.instance.objectNode().set("requestPayload", payload);
            auditedPayload.put("requestedCommand", "REJECT");
            payload = auditedPayload;
        }
        if ("SUBMIT_RESULT".equals(command)) {
            validateTaskResultPolicy(principal, taskId, payload);
        }
        int updated = jdbc.update("""
                update management_task
                set lifecycle_status = :toStatus,
                    completed_at = case when :toStatus = 'COMPLETED' then now() else completed_at end,
                    result_snapshot = case when :command = 'SUBMIT_RESULT' then cast(:payload as jsonb) else result_snapshot end,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :taskId and row_version = :expectedVersion
                  and lifecycle_status = :fromStatus
                """, base(principal)
                .addValue("taskId", taskId)
                .addValue("fromStatus", from)
                .addValue("toStatus", to)
                .addValue("command", command)
                .addValue("payload", payload.toString())
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务版本或状态已变化，请刷新后重试");
        }
        long newVersion = request.expectedVersion() + 1;
        jdbc.update("""
                insert into task_transition
                    (tenant_id, task_id, from_status, to_status, command, actor_account_id,
                     actor_assignment_id, standard_evaluation_id, task_version, idempotency_key, payload)
                values
                    (:tenantId, :taskId, :fromStatus, :toStatus, :command, :actorId,
                     :actorAssignmentId, :evaluationId, :taskVersion, :key, cast(:payload as jsonb))
                """, base(principal)
                .addValue("taskId", taskId)
                .addValue("fromStatus", from)
                .addValue("toStatus", to)
                .addValue("command", persistedCommand)
                .addValue("actorId", principal.actorId())
                .addValue("actorAssignmentId", request.actorAssignmentId())
                .addValue("evaluationId", evaluationId)
                .addValue("taskVersion", newVersion)
                .addValue("key", idempotencyKey)
                .addValue("payload", payload.toString()));
        auditWriter.record("TASK_" + command, "TASK", taskId,
                "{\"from\":\"" + from + "\",\"to\":\"" + to + "\""
                        + (manualNoStandardReview ? ",\"reviewMode\":\"MANUAL_NO_STANDARD\"" : "") + "}");
        auditWriter.emit("TASK", taskId, "TaskTransitioned",
                "{\"taskId\":\"" + taskId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
        if ("DISPATCH".equals(command)) {
            UUID assignee = participantAssignment(principal, taskId, "ASSIGNEE");
            notificationService.createForAssignment(assignee, "TASK_ASSIGNED", "收到新任务",
                    String.valueOf(task.get("title")), "TASK", taskId, "task-assigned:" + taskId);
        } else if ("SUBMIT_RESULT".equals(command)) {
            UUID reviewer = participantAssignment(principal, taskId, "REVIEWER");
            notificationService.createForAssignment(reviewer, "TASK_RESULT_SUBMITTED", "任务结果待评价",
                    String.valueOf(task.get("title")), "TASK", taskId, "task-result:" + taskId + ":" + newVersion);
        } else if ("REWORK".equals(command) || "REJECT".equals(command)) {
            UUID assignee = participantAssignment(principal, taskId, "ASSIGNEE");
            notificationService.createForAssignment(assignee, "TASK_REWORK", "任务被要求返工",
                    String.valueOf(task.get("title")), "TASK", taskId, "task-rework:" + taskId + ":" + newVersion);
        }
        return taskRow(principal, taskId, false);
    }

    private void requireTaskEvidenceCapacity(TenantPrincipal principal, UUID taskId) {
        Map<String, Object> policy = jdbc.queryForMap("""
                select case
                         when jsonb_typeof(source_snapshot #> '{taskPolicy,maxAttachments}') = 'number'
                         then least(10, greatest(0, ((source_snapshot #>> '{taskPolicy,maxAttachments}')::numeric)))::int
                         else 10
                       end as max_attachments,
                       (select count(*) from task_evidence evidence
                        where evidence.tenant_id = task.tenant_id and evidence.task_id = task.id
                          and evidence.scan_status <> 'REJECTED'
                          and coalesce(evidence.structured_result ->> 'purpose', 'EXECUTION') <> 'DISPATCH_REFERENCE') as evidence_count
                from management_task task
                where task.tenant_id = :tenantId and task.id = :taskId
                """, base(principal).addValue("taskId", taskId));
        int maxAttachments = ((Number) policy.get("max_attachments")).intValue();
        int evidenceCount = ((Number) policy.get("evidence_count")).intValue();
        if (evidenceCount >= maxAttachments) {
            throw new IllegalArgumentException("任务证据数量已达到模板上限" + maxAttachments + "个");
        }
    }

    private void requireDispatchReferenceCapacity(TenantPrincipal principal, UUID taskId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from task_evidence
                where tenant_id = :tenantId and task_id = :taskId
                  and scan_status <> 'REJECTED'
                  and structured_result ->> 'purpose' = 'DISPATCH_REFERENCE'
                """, base(principal).addValue("taskId", taskId), Integer.class);
        if (count != null && count >= 10) {
            throw new IllegalArgumentException("下达参考图片最多上传10个");
        }
    }

    private void validateTaskResultPolicy(TenantPrincipal principal, UUID taskId, JsonNode payload) {
        Map<String, Object> policy = jdbc.queryForMap("""
                select case
                         when jsonb_typeof(source_snapshot #> '{taskPolicy,narrativeRequired}') = 'boolean'
                         then (source_snapshot #>> '{taskPolicy,narrativeRequired}')::boolean
                         else false
                       end as narrative_required,
                       case
                         when jsonb_typeof(source_snapshot #> '{taskPolicy,attachmentRequired}') = 'boolean'
                         then (source_snapshot #>> '{taskPolicy,attachmentRequired}')::boolean
                         else false
                       end as attachment_required,
                       case
                         when jsonb_typeof(source_snapshot #> '{taskPolicy,maxAttachments}') = 'number'
                         then least(10, greatest(0, ((source_snapshot #>> '{taskPolicy,maxAttachments}')::numeric)))::int
                         else 10
                       end as max_attachments,
                       (select count(*) from task_evidence evidence
                        where evidence.tenant_id = task.tenant_id and evidence.task_id = task.id
                          and evidence.scan_status <> 'REJECTED'
                          and coalesce(evidence.structured_result ->> 'purpose', 'EXECUTION') <> 'DISPATCH_REFERENCE') as evidence_count
                from management_task task
                where task.tenant_id = :tenantId and task.id = :taskId
                """, base(principal).addValue("taskId", taskId));
        boolean narrativeRequired = Boolean.TRUE.equals(policy.get("narrative_required"));
        boolean attachmentRequired = Boolean.TRUE.equals(policy.get("attachment_required"));
        int maxAttachments = ((Number) policy.get("max_attachments")).intValue();
        int evidenceCount = ((Number) policy.get("evidence_count")).intValue();
        String summary = payload.path("result").path("summary").asText("").trim();
        if (narrativeRequired && summary.isBlank()) {
            throw new IllegalArgumentException("该任务模板要求填写执行结果说明");
        }
        if (attachmentRequired && evidenceCount == 0) {
            throw new IllegalArgumentException("该任务模板要求至少上传一个执行证据");
        }
        if (evidenceCount > maxAttachments) {
            throw new IllegalArgumentException("任务证据数量超过模板上限" + maxAttachments + "个");
        }
    }

    private boolean validateReviewPath(Map<String, Object> task, String command, String from) {
        if (!"RESULT_SUBMITTED".equals(from)
                || !Set.of("APPROVE", "REWORK", "REJECT").contains(command)) {
            return false;
        }
        if (task.get("standard_version_id") != null) {
            throw new IllegalArgumentException("已绑定标准的任务必须先完成标准评价，再进行验收或驳回");
        }
        return true;
    }

    private String targetState(String command, String from) {
        if ("CANCEL".equals(command) && !Set.of("COMPLETED", "CANCELLED").contains(from)) {
            return "CANCELLED";
        }
        if ("EVALUATION_COMPLETED".equals(command) && "RESULT_SUBMITTED".equals(from)) {
            return "AWAITING_REVIEW";
        }
        Map<String, String> allowed = TRANSITIONS.get(command);
        if (allowed == null || !allowed.containsKey(from)) {
            throw new IllegalArgumentException("非法任务状态迁移: " + from + " --" + command + "--> ?");
        }
        return allowed.get(from);
    }

    private void authorizeCommand(TenantPrincipal principal, UUID taskId, String command, UUID actorAssignmentId) {
        if (Set.of("ACKNOWLEDGE", "START", "SUBMIT_RESULT").contains(command)) {
            requireActorAssignment(principal, actorAssignmentId);
            requireParticipant(principal, taskId, actorAssignmentId, Set.of("ASSIGNEE"));
            return;
        }
        if (Set.of("APPROVE", "REWORK", "REJECT").contains(command)) {
            requireActorAssignment(principal, actorAssignmentId);
            requireParticipant(principal, taskId, actorAssignmentId, Set.of("REVIEWER"));
            UUID assignee = participantAssignment(principal, taskId, "ASSIGNEE");
            if (actorAssignmentId.equals(assignee)) {
                throw new AccessDeniedException("责任人不得验收自己的任务");
            }
            return;
        }
        if (Set.of("DISPATCH", "CANCEL").contains(command)) {
            if (principal.hasTenantScope()) {
                return;
            }
            requireActorAssignment(principal, actorAssignmentId);
            Integer authorized = jdbc.queryForObject("""
                    select count(*)
                    from management_task task
                    where task.tenant_id = :tenantId and task.id = :taskId
                      and (
                        task.created_by = :actorId
                        or exists (
                          select 1 from task_participant participant
                          where participant.tenant_id = task.tenant_id
                            and participant.task_id = task.id
                            and participant.position_assignment_id = :assignmentId
                            and participant.participant_type = 'REVIEWER'
                            and participant.valid_to is null
                        )
                      )
                    """, base(principal).addValue("taskId", taskId)
                    .addValue("actorId", principal.actorId())
                    .addValue("assignmentId", actorAssignmentId), Integer.class);
            if (authorized == null || authorized != 1) {
                throw new AccessDeniedException("只有任务创建人或验收负责人可以派发、取消任务");
            }
            return;
        }
        throw new IllegalArgumentException("不支持的任务命令: " + command);
    }

    private void insertParticipant(TenantPrincipal principal, UUID taskId, String type, UUID assignmentId) {
        int inserted = jdbc.update("""
                insert into task_participant
                    (tenant_id, task_id, participant_type, position_assignment_id,
                     employee_snapshot, position_snapshot, org_snapshot)
                select :tenantId, :taskId, :type, a.id,
                       jsonb_build_object('id', e.id, 'name', e.name, 'employeeNo', e.employee_no),
                       jsonb_build_object('id', p.id, 'code', p.code, 'name', p.name),
                       jsonb_build_object('id', o.id, 'code', o.code, 'name', o.name, 'unitType', o.unit_type)
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                where a.tenant_id = :tenantId and a.id = :assignmentId
                """, base(principal)
                .addValue("taskId", taskId)
                .addValue("type", type)
                .addValue("assignmentId", assignmentId));
        if (inserted != 1) {
            throw new IllegalArgumentException("任务参与人的任职不存在");
        }
    }

    private void requireVisible(TenantPrincipal principal, UUID taskId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from management_task t
                where t.tenant_id = :tenantId and t.id = :taskId
                  and (
                    :tenantReadScope = true
                    or (:canReadOrg = true and t.org_unit_id in (:readOrgUnits))
                    or t.created_by = :actorId
                    or exists (
                        select 1 from task_participant p
                        join employee_position_assignment pa on pa.tenant_id = p.tenant_id and pa.id = p.position_assignment_id
                        join employee e on e.tenant_id = pa.tenant_id and e.id = pa.employee_id
                        where p.tenant_id = t.tenant_id and p.task_id = t.id and e.account_id = :actorId
                    )
                  )
                """, visibleParams(principal).addValue("taskId", taskId), Integer.class);
        if (count == null || count == 0) {
            throw new AccessDeniedException("任务不存在或不在当前授权范围");
        }
    }

    private void requireActorAssignment(TenantPrincipal principal, UUID assignmentId) {
        if (assignmentId == null) {
            throw new AccessDeniedException("操作必须指定当前有效任职");
        }
        Integer count = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                where a.tenant_id = :tenantId and a.id = :assignmentId and e.account_id = :actorId
                  and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                """, base(principal).addValue("assignmentId", assignmentId).addValue("actorId", principal.actorId()), Integer.class);
        if (count == null || count == 0) {
            throw new AccessDeniedException("任职不属于当前账号或已失效");
        }
    }

    private void requireParticipant(TenantPrincipal principal, UUID taskId, UUID assignmentId, Set<String> types) {
        Integer count = jdbc.queryForObject("""
                select count(*) from task_participant
                where tenant_id = :tenantId and task_id = :taskId and position_assignment_id = :assignmentId
                  and participant_type in (:types) and valid_to is null
                """, base(principal).addValue("taskId", taskId).addValue("assignmentId", assignmentId).addValue("types", types), Integer.class);
        if (count == null || count == 0) {
            throw new AccessDeniedException("当前任职不是该任务所需的参与角色");
        }
    }

    private void requireAssignment(TenantPrincipal principal, UUID assignmentId) {
        List<UUID> assignmentOrgs = jdbc.queryForList("""
                select org_unit_id from employee_position_assignment
                where tenant_id = :tenantId and id = :assignmentId and status = 'ACTIVE'
                  and valid_from <= current_date and (valid_to is null or valid_to >= current_date)
                """, base(principal).addValue("assignmentId", assignmentId), UUID.class);
        if (assignmentOrgs.isEmpty()) {
            throw new IllegalArgumentException("任务责任任职不存在或已失效: " + assignmentId);
        }
        accessPolicy.requireOrgScope(assignmentOrgs.getFirst());
    }

    private void requireOwnedOptional(TenantPrincipal principal, String table, UUID id) {
        if (id == null) {
            return;
        }
        if (!Set.of("standard_version", "work_record").contains(table)) {
            throw new IllegalArgumentException("不允许校验的资源类型");
        }
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("任务引用资源不存在或不属于当前租户");
        }
    }

    private UUID participantAssignment(TenantPrincipal principal, UUID taskId, String type) {
        List<UUID> ids = jdbc.queryForList("""
                select position_assignment_id from task_participant
                where tenant_id = :tenantId and task_id = :taskId and participant_type = :type and valid_to is null
                order by created_at limit 1
                """, base(principal).addValue("taskId", taskId).addValue("type", type), UUID.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Map<String, Object> taskRow(TenantPrincipal principal, UUID taskId, boolean forUpdate) {
        return jdbc.queryForMap("""
                select t.id, t.task_no, t.source_event_id, t.source_action_id, t.standard_version_id, t.work_record_id,
                       t.org_unit_id, o.name as org_unit_name, t.title, t.description,
                       t.lifecycle_status, t.sla_status, t.priority, t.due_at,
                       t.completed_at, t.creation_source, t.created_by_assignment_id,
                       t.source_snapshot, t.responsibility_snapshot, t.result_snapshot,
                       t.row_version, t.created_by, t.created_at, t.updated_at
                from management_task t
                join org_unit o on o.tenant_id = t.tenant_id and o.id = t.org_unit_id
                where t.tenant_id = :tenantId and t.id = :taskId
                """ + (forUpdate ? " for update of t" : ""), base(principal).addValue("taskId", taskId));
    }

    private MapSqlParameterSource visibleParams(TenantPrincipal principal) {
        TaskTargetPolicy.ReadScope readScope = taskTargetPolicy.readScope(principal);
        return base(principal)
                .addValue("tenantReadScope", readScope.tenantScope())
                .addValue("canReadOrg", principal.hasPermission("task.review")
                        || principal.hasPermission("task.dispatch")
                        || principal.hasPermission("task.create"))
                .addValue("readOrgUnits", readScope.orgUnitIds().isEmpty()
                        ? List.of(new UUID(0, 0)) : readScope.orgUnitIds())
                .addValue("actorId", principal.actorId());
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private void rejectChairmanBusinessActor(TenantPrincipal principal) {
        UUID assignmentId = principal.businessActorAssignmentId();
        if (assignmentId == null) return;
        Integer chairman = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment assignment
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                 and position.id = assignment.position_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and position.code = 'GROUP_CHAIRMAN'
                """, base(principal).addValue("assignmentId", assignmentId), Integer.class);
        if (chairman != null && chairman > 0) {
            throw new AccessDeniedException("集团董事长只能使用专用高管交办任务接口");
        }
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeView(String value) {
        String view = value == null || value.isBlank() ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "MINE", "TEAM", "REVIEW").contains(view)) {
            throw new IllegalArgumentException("不支持的任务视图: " + value);
        }
        return view;
    }

    private String normalizePriority(String value) {
        String priority = value == null || value.isBlank() ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)) {
            throw new IllegalArgumentException("不支持的任务优先级: " + priority);
        }
        return priority;
    }
}
