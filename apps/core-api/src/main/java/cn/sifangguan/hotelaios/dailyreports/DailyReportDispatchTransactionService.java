package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.dailyreporttemplates.DailyReportTemplateService;
import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.notifications.StoreManagerSupervisorResolver;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.BusinessEvent;
import cn.sifangguan.hotelaios.shared.events.BusinessEventPublisher;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy;
import cn.sifangguan.hotelaios.shared.time.BusinessDayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DailyReportDispatchTransactionService {
    private static final String PRODUCER = "daily-report-dispatch-service";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final BusinessDayService businessDayService;
    private final DailyReportTemplateService templateService;
    private final NotificationService notificationService;
    private final BusinessEventPublisher eventPublisher;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final DailyReportRoutineProjectionService routineProjectionService;
    private final StoreManagerSupervisorResolver supervisors;

    public DailyReportDispatchTransactionService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            BusinessDayService businessDayService,
            DailyReportTemplateService templateService,
            NotificationService notificationService,
            BusinessEventPublisher eventPublisher,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            DailyReportRoutineProjectionService routineProjectionService,
            StoreManagerSupervisorResolver supervisors
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.businessDayService = businessDayService;
        this.templateService = templateService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.routineProjectionService = routineProjectionService;
        this.supervisors = supervisors;
    }

    @Transactional(readOnly = true)
    public List<DispatchCandidate> findCandidates(
            int batchLimit,
            UUID afterAssignmentId,
            UUID afterPolicyId
    ) {
        TenantPrincipal principal = prepare();
        return jdbc.query("""
                select policy.id as policy_id, assignment.id as position_assignment_id,
                       assignment.org_unit_id, policy.backfill_days
                from daily_report_delivery_policy policy
                join daily_report_template_assignment template_assignment
                  on template_assignment.tenant_id = policy.tenant_id
                 and template_assignment.id = policy.template_assignment_id
                join daily_report_template_version template_version
                  on template_version.tenant_id = template_assignment.tenant_id
                 and template_version.id = template_assignment.template_version_id
                join daily_report_template_definition definition
                  on definition.tenant_id = template_version.tenant_id
                 and definition.id = template_version.template_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = definition.tenant_id
                 and assignment.position_id = definition.position_id
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where policy.tenant_id = :tenantId and policy.enabled = true
                  and template_assignment.assignment_kind = 'BASE'
                  and template_assignment.status = 'ACTIVE'
                  and template_version.lifecycle_status = 'PUBLISHED'
                  and definition.status = 'ACTIVE' and definition.template_origin = 'HQ'
                  and assignment.status = 'ACTIVE'
                  and employee.employment_status = 'ACTIVE' and employee.account_id is not null
                  and (
                    template_assignment.scope_type = 'TENANT'
                    or (template_assignment.scope_type = 'POSITION'
                        and template_assignment.position_id = assignment.position_id)
                    or (template_assignment.scope_type = 'ORG_UNIT'
                        and template_assignment.org_unit_id = assignment.org_unit_id)
                    or (template_assignment.scope_type = 'ORG_TREE' and exists (
                      select 1 from org_unit_closure scope
                      where scope.tenant_id = assignment.tenant_id
                        and scope.ancestor_id = template_assignment.org_unit_id
                        and scope.descendant_id = assignment.org_unit_id
                    ))
                  )
                  and exists (
                    select 1
                    from org_unit_closure hotel_scope
                    join org_unit hotel
                      on hotel.tenant_id = hotel_scope.tenant_id
                     and hotel.id = hotel_scope.ancestor_id
                    where hotel_scope.tenant_id = assignment.tenant_id
                      and hotel_scope.descendant_id = assignment.org_unit_id
                      and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                  )
                order by case
                    when cast(:afterAssignmentId as uuid) is null then 0
                    when (assignment.id, policy.id)
                         > (cast(:afterAssignmentId as uuid), cast(:afterPolicyId as uuid)) then 0
                    else 1
                end,
                assignment.id, policy.id
                limit :batchLimit
                """, base(principal)
                .addValue("batchLimit", batchLimit)
                .addValue("afterAssignmentId", afterAssignmentId)
                .addValue("afterPolicyId", afterPolicyId),
                (rs, rowNum) -> new DispatchCandidate(
                        rs.getObject("policy_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getInt("backfill_days")));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispatchOutcome materializeAndDispatch(
            DispatchCandidate candidate,
            LocalDate businessDate,
            Instant now
    ) {
        TenantPrincipal principal = prepare();
        BusinessDayService.BusinessDayContext day =
                businessDayService.resolve(candidate.orgUnitId(), businessDate, now);
        OffsetDateTime templateEffectiveAt = day.businessDate().atTime(day.cutoffLocalTime())
                .atZone(ZoneId.of(day.timezone())).toOffsetDateTime();
        List<PolicyAssignment> rows = jdbc.query("""
                select policy.id as policy_id, policy.template_assignment_id,
                       policy.open_local_time, policy.due_local_time, policy.grace_minutes,
                       policy.pre_due_reminder_minutes, policy.overdue_reminder_minutes,
                       template_assignment.template_version_id,
                       template_version.work_package_version_id,
                       assignment.id as position_assignment_id,
                       assignment.org_unit_id, assignment.employee_id
                from daily_report_delivery_policy policy
                join daily_report_template_assignment template_assignment
                  on template_assignment.tenant_id = policy.tenant_id
                 and template_assignment.id = policy.template_assignment_id
                join daily_report_template_version template_version
                  on template_version.tenant_id = template_assignment.tenant_id
                 and template_version.id = template_assignment.template_version_id
                join daily_report_template_definition definition
                  on definition.tenant_id = template_version.tenant_id
                 and definition.id = template_version.template_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = definition.tenant_id
                 and assignment.id = :positionAssignmentId
                 and assignment.position_id = definition.position_id
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where policy.tenant_id = :tenantId and policy.id = :policyId
                  and policy.enabled = true
                  and template_assignment.assignment_kind = 'BASE'
                  and template_assignment.status = 'ACTIVE'
                  and template_assignment.valid_from <= :businessDate
                  and (template_assignment.valid_to is null
                       or template_assignment.valid_to >= :businessDate)
                  and template_version.lifecycle_status = 'PUBLISHED'
                  and template_version.effective_from <= :templateEffectiveAt
                  and (template_version.effective_to is null
                       or template_version.effective_to > :templateEffectiveAt)
                  and definition.status = 'ACTIVE' and definition.template_origin = 'HQ'
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= :businessDate
                  and (assignment.valid_to is null or assignment.valid_to >= :businessDate)
                  and employee.employment_status = 'ACTIVE' and employee.account_id is not null
                  and (
                    template_assignment.scope_type = 'TENANT'
                    or (template_assignment.scope_type = 'POSITION'
                        and template_assignment.position_id = assignment.position_id)
                    or (template_assignment.scope_type = 'ORG_UNIT'
                        and template_assignment.org_unit_id = assignment.org_unit_id)
                    or (template_assignment.scope_type = 'ORG_TREE' and exists (
                      select 1 from org_unit_closure scope
                      where scope.tenant_id = assignment.tenant_id
                        and scope.ancestor_id = template_assignment.org_unit_id
                        and scope.descendant_id = assignment.org_unit_id
                    ))
                  )
                """, base(principal)
                .addValue("policyId", candidate.policyId())
                .addValue("positionAssignmentId", candidate.positionAssignmentId())
                .addValue("businessDate", day.businessDate())
                .addValue("templateEffectiveAt", templateEffectiveAt),
                (rs, rowNum) -> new PolicyAssignment(
                        rs.getObject("policy_id", UUID.class),
                        rs.getObject("template_assignment_id", UUID.class),
                        rs.getObject("template_version_id", UUID.class),
                        rs.getObject("work_package_version_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("open_local_time", LocalTime.class),
                        rs.getObject("due_local_time", LocalTime.class),
                        rs.getInt("grace_minutes"),
                        integerList(rs.getArray("pre_due_reminder_minutes")),
                        integerList(rs.getArray("overdue_reminder_minutes"))));
        if (rows.size() != 1) {
            return DispatchOutcome.skipped();
        }
        PolicyAssignment policy = rows.getFirst();
        OffsetDateTime openAt = scheduledAt(
                day.businessDate(), policy.openLocalTime(), day.cutoffLocalTime(), day.timezone());
        OffsetDateTime dueAt = scheduledAt(
                day.businessDate(), policy.dueLocalTime(), day.cutoffLocalTime(), day.timezone());
        DailyReportTiming.Window configuredWindow;
        try {
            configuredWindow = DailyReportTiming.resolve(jdbc, objectMapper, principal.tenantId(),
                    policy.templateVersionId(), policy.positionAssignmentId(), day.hotelOrgUnitId(),
                    day.businessDate(), day.cutoffLocalTime(), day.timezone());
        } catch (DailyReportTiming.UnresolvedShiftException exception) {
            // No concrete shift means no invented deadline, report or overdue notification.
            return DispatchOutcome.skipped();
        }
        if (configuredWindow != null) {
            openAt = configuredWindow.openAt();
            dueAt = configuredWindow.dueAt();
        }
        if (!openAt.isBefore(dueAt)) {
            throw new IllegalStateException(
                    "invalid daily-report delivery window: openAt must be before dueAt"
                            + " (policyId=" + policy.policyId()
                            + ", businessDate=" + day.businessDate()
                            + ", cutoff=" + day.cutoffLocalTime()
                            + ", openAt=" + openAt
                            + ", dueAt=" + dueAt + ")");
        }
        if (now.isBefore(openAt.toInstant())) {
            return DispatchOutcome.skipped();
        }

        Map<String, Object> resolution = templateService.resolve(
                policy.orgUnitId(), policy.positionAssignmentId(), day.businessDate());
        UUID resolvedVersionId = (UUID) resolution.get("selectedTemplateVersionId");
        if (!policy.templateVersionId().equals(resolvedVersionId)) {
            return DispatchOutcome.skipped();
        }
        OffsetDateTime deadlineAt = configuredWindow == null ? dueAt.plusMinutes(policy.graceMinutes()) : dueAt;
        UUID reportId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                insert into daily_report
                    (id, tenant_id, hotel_org_unit_id, org_unit_id, employee_id,
                     position_assignment_id, business_date, timezone, cutoff_local_time,
                     report_deadline_at, template_version_id, work_package_version_id,
                     report_status, review_status, current_revision_no, trace_id,
                     created_by_account_id)
                values
                    (:id, :tenantId, :hotelOrgUnitId, :orgUnitId, :employeeId,
                     :positionAssignmentId, :businessDate, :timezone, :cutoffLocalTime,
                     :deadlineAt, :templateVersionId, :workPackageVersionId,
                     'DRAFT', 'NOT_REQUIRED', 1, :traceId, :actorId)
                on conflict (tenant_id, hotel_org_unit_id, position_assignment_id, business_date)
                    do nothing
                returning id
                """, base(principal)
                .addValue("id", reportId)
                .addValue("hotelOrgUnitId", day.hotelOrgUnitId())
                .addValue("orgUnitId", policy.orgUnitId())
                .addValue("employeeId", policy.employeeId())
                .addValue("positionAssignmentId", policy.positionAssignmentId())
                .addValue("businessDate", day.businessDate())
                .addValue("timezone", day.timezone())
                .addValue("cutoffLocalTime", day.cutoffLocalTime())
                .addValue("deadlineAt", deadlineAt)
                .addValue("templateVersionId", resolvedVersionId)
                .addValue("workPackageVersionId", resolution.get("workPackageVersionId"))
                .addValue("traceId", principal.correlationId())
                .addValue("actorId", principal.actorId()),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        boolean created = !inserted.isEmpty();
        if (created) {
            reportId = inserted.getFirst();
            createOriginalRevision(principal, reportId, resolution, policy, openAt, dueAt, deadlineAt);
            auditWriter.record(
                    "DAILY_REPORT_AUTO_CREATED",
                    "DAILY_REPORT",
                    reportId,
                    "{\"positionAssignmentId\":\"" + policy.positionAssignmentId()
                            + "\",\"businessDate\":\"" + day.businessDate() + "\"}");
        } else {
            reportId = jdbc.queryForObject("""
                    select id from daily_report
                    where tenant_id = :tenantId and hotel_org_unit_id = :hotelOrgUnitId
                      and position_assignment_id = :positionAssignmentId
                      and business_date = :businessDate
                    """, base(principal)
                    .addValue("hotelOrgUnitId", day.hotelOrgUnitId())
                    .addValue("positionAssignmentId", policy.positionAssignmentId())
                    .addValue("businessDate", day.businessDate()), UUID.class);
        }
        Map<String, Object> report = jdbc.queryForMap("""
                select report.report_status, report.report_deadline_at,
                       report.template_version_id, report.current_revision_id,
                       revision.payload_snapshot::text as payload_snapshot
                from daily_report report
                left join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = report.current_revision_id
                where report.tenant_id = :tenantId and report.id = :reportId
                """, base(principal).addValue("reportId", reportId));
        if (!resolvedVersionId.equals(report.get("template_version_id"))) {
            return DispatchOutcome.skipped();
        }
        if (!"DRAFT".equals(report.get("report_status"))) {
            return new DispatchOutcome(true, created, false, 0, 0);
        }
        UUID currentRevisionId = (UUID) report.get("current_revision_id");
        if (currentRevisionId == null) {
            return DispatchOutcome.skipped();
        }
        // Reconcile every still-editable report, including reports created by an earlier
        // dispatch pass. This makes late work submissions and review outcomes self-healing.
        routineProjectionService.projectReport(reportId, currentRevisionId);
        DispatchSchedule schedule = frozenSchedule(
                report.get("payload_snapshot"),
                policy,
                dueAt,
                deadlineAt);
        if (schedule == null) {
            return DispatchOutcome.skipped();
        }
        dueAt = schedule.dueAt();
        deadlineAt = schedule.deadlineAt();

        if (!now.isBefore(dueAt.toInstant())) {
            notifyManagerOfPendingReviews(principal, reportId, policy, day.businessDate());
        }

        boolean housekeeping = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from daily_report d join work_package_item i
                  on i.tenant_id=d.tenant_id and i.work_package_version_id=d.work_package_version_id
                  where d.tenant_id=:tenantId and d.id=:reportId and i.item_code='HKA_DAILY_REPORT')
                """, base(principal).addValue("reportId", reportId), Boolean.class));
        notificationService.createForAssignment(
                policy.positionAssignmentId(),
                "DAILY_REPORT_READY",
                housekeeping ? "今日日报与遗留物待填报" : "今日日报待填报",
                "请按当前岗位工作包完成 " + day.businessDate() + " 营业日日报。"
                        + (housekeeping ? "服务案例和遗留物一并填写；无遗留物也请确认。提交后通知指定接收人审核。" : ""),
                "DAILY_REPORT",
                reportId,
                "daily-report:ready:" + reportId);
        int opened = publishStage(
                "DAILY_REPORT_OPENED", "OPENED", reportId, policy, day, dueAt, deadlineAt, null);

        int dueSoonEvents = 0;
        if (now.isBefore(deadlineAt.toInstant())) {
            for (Integer offset : schedule.preDueReminderMinutes()) {
                if (!now.isBefore(dueAt.minusMinutes(offset).toInstant())) {
                    dueSoonEvents += publishStage(
                            "DAILY_REPORT_DUE_SOON",
                            "DUE_SOON",
                            reportId,
                            policy,
                            day,
                            dueAt,
                            deadlineAt,
                            offset);
                }
            }
        }
        int overdueEvents = 0;
        for (Integer offset : schedule.overdueReminderMinutes()) {
            if (!now.isBefore(deadlineAt.plusMinutes(offset).toInstant())) {
                overdueEvents += publishStage(
                        "DAILY_REPORT_OVERDUE",
                        "OVERDUE",
                        reportId,
                        policy,
                        day,
                        dueAt,
                        deadlineAt,
                        offset);
            }
        }
        return new DispatchOutcome(true, created, opened == 1, dueSoonEvents, overdueEvents);
    }

    private void notifyManagerOfPendingReviews(
            TenantPrincipal principal, UUID reportId, PolicyAssignment policy, LocalDate businessDate
    ) {
        Integer pending = jdbc.queryForObject("""
                select count(*) from work_record record
                left join work_package_item review_item
                  on review_item.tenant_id=record.tenant_id and review_item.id=record.work_package_item_id
                left join work_expectation review_expectation
                  on review_expectation.tenant_id=record.tenant_id and review_expectation.id=record.work_expectation_id
                where record.tenant_id=:tenantId and record.position_assignment_id=:assignment
                  and record.business_date=:day and record.status='SUBMITTED'
                  and coalesce(
                """ + WorkReviewPolicy.effectiveModeSql("review_item", "review_expectation") + """
                  ,'MANUAL')<>'NONE'
                  and exists (select 1 from work_package_item_responsibility responsibility
                    where responsibility.tenant_id=record.tenant_id and responsibility.work_package_item_id=record.work_package_item_id
                      and responsibility.participant_type in ('REVIEWER','ACCEPTOR') and responsibility.resolver_type='DIRECT_MANAGER_ASSIGNMENT')
                  and not exists (select 1 from store_work_usage usage
                    where usage.tenant_id=record.tenant_id and usage.work_record_id=record.id)
                """, base(principal).addValue("assignment",policy.positionAssignmentId()).addValue("day",businessDate), Integer.class);
        if (pending == null || pending == 0) return;
        var recipients = supervisors.ordinaryReviewers(principal, "work-record.review", policy.positionAssignmentId(), policy.orgUnitId());
        if (recipients.isEmpty()) recordPendingReviewReminderSkippedOnce(principal,reportId,"AUTHORIZED_REVIEWER_UNRESOLVED");
        for (var recipient : recipients) {
            jdbc.update("""
                    insert into notification(tenant_id,recipient_account_id,recipient_assignment_id,notification_type,
                      title,content,source_type,source_id,idempotency_key)
                    values(:tenantId,:account,:assignment,'DAILY_REPORT_REVIEW_PENDING','当日工作待复核',
                      :content,'DAILY_REPORT',:report,:key)
                    on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing
                    """,base(principal).addValue("account",recipient.accountId()).addValue("assignment",recipient.assignmentId())
                    .addValue("content",businessDate+" 共有 "+pending+" 项普通工作待复核，请查看日报来源记录。")
                    .addValue("report",reportId).addValue("key","daily-report:review-pending:"+reportId+":"+pending));
        }
    }

    private void recordPendingReviewReminderSkippedOnce(
            TenantPrincipal principal,
            UUID reportId,
            String reason
    ) {
        Integer existing = jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = :tenantId and resource_type = 'DAILY_REPORT'
                  and resource_id = :reportId
                  and action = 'DAILY_REPORT_REVIEW_NOTIFICATION_SKIPPED'
                """, base(principal).addValue("reportId", reportId), Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        auditWriter.record(
                "DAILY_REPORT_REVIEW_NOTIFICATION_SKIPPED",
                "DAILY_REPORT",
                reportId,
                "{\"reason\":\"" + reason + "\"}");
    }

    private DispatchSchedule frozenSchedule(
            Object payloadSnapshot,
            PolicyAssignment policy,
            OffsetDateTime calculatedDueAt,
            OffsetDateTime calculatedDeadlineAt
    ) {
        if (payloadSnapshot == null) {
            return new DispatchSchedule(
                    calculatedDueAt,
                    calculatedDeadlineAt,
                    policy.preDueReminderMinutes(),
                    policy.overdueReminderMinutes());
        }
        try {
            JsonNode root = objectMapper.readTree(String.valueOf(payloadSnapshot));
            JsonNode frozen = root.path("deliveryPolicy");
            if (!frozen.isObject()) {
                return new DispatchSchedule(
                        calculatedDueAt,
                        calculatedDeadlineAt,
                        policy.preDueReminderMinutes(),
                        policy.overdueReminderMinutes());
            }
            String frozenPolicyId = frozen.path("policyId").asText();
            if (!frozenPolicyId.isBlank() && !policy.policyId().toString().equals(frozenPolicyId)) {
                return null;
            }
            OffsetDateTime dueAt = OffsetDateTime.parse(frozen.path("dueAt").asText());
            OffsetDateTime deadlineAt = OffsetDateTime.parse(frozen.path("deadlineAt").asText());
            return new DispatchSchedule(
                    dueAt,
                    deadlineAt,
                    integerList(frozen.path("preDueReminderMinutes")),
                    integerList(frozen.path("overdueReminderMinutes")));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("invalid frozen daily-report delivery policy snapshot", exception);
        }
    }

    private UUID createOriginalRevision(
            TenantPrincipal principal,
            UUID reportId,
            Map<String, Object> resolution,
            PolicyAssignment policy,
            OffsetDateTime openAt,
            OffsetDateTime dueAt,
            OffsetDateTime deadlineAt
    ) {
        UUID revisionId = UUID.randomUUID();
        ObjectNode snapshot = objectMapper.createObjectNode();
        JsonNode resolvedTemplate = objectMapper.valueToTree(resolution.get("resolvedConfiguration"));
        snapshot.set("resolvedTemplate", resolvedTemplate.deepCopy());
        snapshot.set("templateResolution", objectMapper.valueToTree(resolution));
        Map<String, Object> policySnapshot = new LinkedHashMap<>();
        policySnapshot.put("policyId", policy.policyId());
        policySnapshot.put("templateAssignmentId", policy.templateAssignmentId());
        policySnapshot.put("openLocalTime", policy.openLocalTime());
        policySnapshot.put("dueLocalTime", policy.dueLocalTime());
        policySnapshot.put("graceMinutes", policy.graceMinutes());
        policySnapshot.put("preDueReminderMinutes", policy.preDueReminderMinutes());
        policySnapshot.put("overdueReminderMinutes", policy.overdueReminderMinutes());
        policySnapshot.put("dueAt", dueAt);
        policySnapshot.put("openAt", openAt);
        policySnapshot.put("deadlineAt", deadlineAt);
        snapshot.set("deliveryPolicy", objectMapper.valueToTree(policySnapshot));
        snapshot.putArray("items");
        jdbc.update("""
                insert into daily_report_revision
                    (id, tenant_id, report_id, revision_no, revision_type, revision_status,
                     payload_snapshot, created_by_account_id)
                values
                    (:id, :tenantId, :reportId, 1, 'ORIGINAL', 'DRAFT',
                     cast(:snapshot as jsonb), :actorId)
                """, base(principal)
                .addValue("id", revisionId)
                .addValue("reportId", reportId)
                .addValue("snapshot", snapshot.toString())
                .addValue("actorId", principal.actorId()));
        jdbc.update("""
                update daily_report set current_revision_id = :revisionId
                where tenant_id = :tenantId and id = :reportId
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId));
        return revisionId;
    }

    private int publishStage(
            String eventType,
            String stage,
            UUID reportId,
            PolicyAssignment policy,
            BusinessDayService.BusinessDayContext day,
            OffsetDateTime dueAt,
            OffsetDateTime deadlineAt,
            Integer offsetMinutes
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reportId", reportId.toString());
        payload.put("deliveryPolicyId", policy.policyId().toString());
        payload.put("templateVersionId", policy.templateVersionId().toString());
        payload.put("positionAssignmentId", policy.positionAssignmentId().toString());
        payload.put("orgUnitId", policy.orgUnitId().toString());
        payload.put("hotelOrgUnitId", day.hotelOrgUnitId().toString());
        payload.put("businessDate", day.businessDate().toString());
        payload.put("stage", stage);
        payload.put("dueAt", dueAt.toString());
        payload.put("deadlineAt", deadlineAt.toString());
        if (offsetMinutes != null) {
            payload.put("offsetMinutes", offsetMinutes);
        }
        String key = switch (stage) {
            case "OPENED" -> "daily-report:opened:" + reportId;
            case "DUE_SOON" -> "daily-report:due-soon:" + reportId + ":" + offsetMinutes;
            case "OVERDUE" -> "daily-report:overdue:" + reportId + ":" + offsetMinutes;
            default -> throw new IllegalArgumentException("unsupported daily report stage: " + stage);
        };
        return eventPublisher.publishIfAbsent(new BusinessEvent(
                "DAILY_REPORT",
                reportId,
                eventType,
                1,
                PRODUCER,
                policy.orgUnitId(),
                day.hotelOrgUnitId(),
                policy.positionAssignmentId(),
                policy.positionAssignmentId(),
                day.businessDate(),
                null,
                null,
                key,
                "INTERNAL",
                payload)).created() ? 1 : 0;
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private static OffsetDateTime scheduledAt(
            LocalDate businessDate,
            LocalTime localTime,
            LocalTime cutoff,
            String timezone
    ) {
        LocalDate calendarDate = localTime.isBefore(cutoff)
                ? businessDate.plusDays(1) : businessDate;
        return calendarDate.atTime(localTime).atZone(ZoneId.of(timezone)).toOffsetDateTime();
    }

    private static List<Integer> integerList(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object raw = sqlArray.getArray();
            if (raw instanceof Integer[] values) {
                return List.copyOf(Arrays.asList(values));
            }
            if (raw instanceof Object[] values) {
                return Arrays.stream(values).map(value -> ((Number) value).intValue()).toList();
            }
            return List.of();
        } finally {
            sqlArray.free();
        }
    }

    private static List<Integer> integerList(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.intValue()));
        return List.copyOf(result);
    }

    private static MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    public record DispatchCandidate(
            UUID policyId,
            UUID positionAssignmentId,
            UUID orgUnitId,
            int backfillDays
    ) {
    }

    private record PolicyAssignment(
            UUID policyId,
            UUID templateAssignmentId,
            UUID templateVersionId,
            UUID workPackageVersionId,
            UUID positionAssignmentId,
            UUID orgUnitId,
            UUID employeeId,
            LocalTime openLocalTime,
            LocalTime dueLocalTime,
            int graceMinutes,
            List<Integer> preDueReminderMinutes,
            List<Integer> overdueReminderMinutes
    ) {
    }

    private record DispatchSchedule(
            OffsetDateTime dueAt,
            OffsetDateTime deadlineAt,
            List<Integer> preDueReminderMinutes,
            List<Integer> overdueReminderMinutes
    ) {
    }

    public record DispatchOutcome(
            boolean processed,
            boolean created,
            boolean openedEventCreated,
            int dueSoonEventsCreated,
            int overdueEventsCreated
    ) {
        static DispatchOutcome skipped() {
            return new DispatchOutcome(false, false, false, 0, 0);
        }
    }
}
