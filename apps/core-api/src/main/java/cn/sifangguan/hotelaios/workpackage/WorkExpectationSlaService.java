package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.TenantSystemAccountResolver;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkExpectationSlaService {
    static final int MAX_BATCH_SIZE = 500;
    private static final String PROCESS_PERMISSION = "work-package.manage";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final TenantSystemAccountResolver systemAccountResolver;
    private final ObjectMapper objectMapper;
    private final WorkPackageService workPackageService;

    public WorkExpectationSlaService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            TenantSystemAccountResolver systemAccountResolver,
            ObjectMapper objectMapper,
            WorkPackageService workPackageService
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.systemAccountResolver = systemAccountResolver;
        this.objectMapper = objectMapper;
        this.workPackageService = workPackageService;
    }

    @Transactional
    public WorkPackageModels.SlaProcessResult processCurrentTenant(int batchLimit) {
        int validatedLimit = validateBatchLimit(batchLimit);
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        accessPolicy.requirePermission(PROCESS_PERMISSION);
        return processOverdue(principal, validatedLimit, false);
    }

    @Transactional
    public WorkPackageModels.SlaProcessResult processTenantAsSystem(
            UUID tenantId,
            int batchLimit,
            UUID correlationId
    ) {
        int validatedLimit = validateBatchLimit(batchLimit);
        UUID actorId = systemAccountResolver.resolveOrCreate(tenantId);
        TenantPrincipal previous = TenantContext.current().orElse(null);
        TenantPrincipal systemPrincipal = new TenantPrincipal(
                tenantId,
                actorId,
                "SYSTEM_AUTOMATION",
                Set.of("SYSTEM_AUTOMATION"),
                Set.of(PROCESS_PERMISSION),
                Set.of(),
                Set.of(),
                true,
                correlationId
        );
        TenantContext.set(systemPrincipal);
        try {
            databaseContext.apply(tenantId);
            return processOverdue(systemPrincipal, validatedLimit, true);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private WorkPackageModels.SlaProcessResult processOverdue(
            TenantPrincipal principal,
            int batchLimit,
            boolean materializeDailyWork
    ) {
        OffsetDateTime processingTime = OffsetDateTime.now(ZoneOffset.UTC);
        String timezone = jdbc.queryForObject(
                "select timezone from tenant where id = :tenantId",
                new MapSqlParameterSource("tenantId", principal.tenantId()), String.class);
        LocalDate businessDate = processingTime.atZoneSameInstant(ZoneId.of(timezone)).toLocalDate();
        if (materializeDailyWork) {
            workPackageService.generateDailyExpectationsForAutomation(businessDate);
        }
        activateAvailable(principal, processingTime);
        enqueueDueReminders(principal, processingTime);

        List<MissedExpectation> missed = jdbc.query("""
                with candidates as (
                    select x.id
                    from work_expectation x
                    join work_package_item i
                      on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                    where x.tenant_id = :tenantId
                      and x.status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS')
                      and x.due_at + make_interval(mins => i.grace_minutes) < now()
                    order by x.due_at, x.id
                    for update skip locked
                    limit :batchLimit
                )
                update work_expectation x
                set status = 'MISSED',
                    row_version = x.row_version + 1,
                    updated_at = now()
                from candidates c
                where x.tenant_id = :tenantId and x.id = c.id
                returning x.id, x.work_package_item_id, x.position_assignment_id,
                          x.target_org_unit_id, x.business_date, x.due_at
                """, new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("batchLimit", batchLimit),
                (rs, rowNum) -> new MissedExpectation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("work_package_item_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("target_org_unit_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("due_at", OffsetDateTime.class)
                ));
        missed.sort(Comparator.comparing(MissedExpectation::dueAt).thenComparing(MissedExpectation::id));

        for (MissedExpectation expectation : missed) {
            String payload = payload(expectation);
            auditWriter.record("WORK_EXPECTATION_MISSED", "WORK_EXPECTATION", expectation.id(), payload);
            auditWriter.emit("WORK_EXPECTATION", expectation.id(), "WorkExpectationMissed", payload);
        }

        return new WorkPackageModels.SlaProcessResult(
                missed.size(),
                batchLimit,
                missed.stream().map(MissedExpectation::id).toList(),
                processingTime
        );
    }

    private void activateAvailable(TenantPrincipal principal, OffsetDateTime processingTime) {
        List<UUID> activated = jdbc.queryForList("""
                update work_expectation
                set status = 'AVAILABLE', row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and status = 'PLANNED' and available_at <= :processingTime
                returning id
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("processingTime", processingTime), UUID.class);
        for (UUID expectationId : activated) {
            auditWriter.emit("WORK_EXPECTATION", expectationId, "WorkExpectationAvailable",
                    "{\"workExpectationId\":\"" + expectationId + "\"}");
        }
    }

    private void enqueueDueReminders(TenantPrincipal principal, OffsetDateTime processingTime) {
        List<ReminderSource> sources = jdbc.query("""
                select x.id, x.status, x.business_date, x.available_at, x.due_at, x.position_assignment_id,
                       x.target_org_unit_id, i.name as item_name, i.reminder_policy::text,
                       i.timezone_mode, i.fixed_timezone, tenant.timezone as tenant_timezone,
                       org.name as org_name, employee.account_id as executor_account_id,
                       manager.id as manager_assignment_id,
                       manager_employee.account_id as manager_account_id
                from work_expectation x
                join work_package_item i
                  on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = x.tenant_id and assignment.id = x.position_assignment_id
                join employee
                  on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                join org_unit org
                  on org.tenant_id = x.tenant_id and org.id = x.target_org_unit_id
                join tenant on tenant.id = x.tenant_id
                left join employee_position_assignment manager
                  on manager.tenant_id = assignment.tenant_id
                 and manager.id = assignment.manager_assignment_id
                 and manager.status = 'ACTIVE'
                 and manager.valid_from <= x.business_date
                 and (manager.valid_to is null or manager.valid_to >= x.business_date)
                left join employee manager_employee
                  on manager_employee.tenant_id = manager.tenant_id
                 and manager_employee.id = manager.employee_id
                where x.tenant_id = :tenantId
                  and x.status in ('PLANNED', 'AVAILABLE', 'IN_PROGRESS', 'MISSED')
                  and i.reminder_policy <> '{}'::jsonb
                  and employee.account_id is not null
                order by x.available_at, x.id
                """, new MapSqlParameterSource("tenantId", principal.tenantId()),
                (rs, rowNum) -> new ReminderSource(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("available_at", OffsetDateTime.class),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("target_org_unit_id", UUID.class),
                        rs.getString("item_name"), rs.getString("org_name"),
                        rs.getString("reminder_policy"), rs.getString("timezone_mode"),
                        rs.getString("fixed_timezone"), rs.getString("tenant_timezone"),
                        rs.getObject("executor_account_id", UUID.class),
                        rs.getObject("manager_assignment_id", UUID.class),
                        rs.getObject("manager_account_id", UUID.class)));
        for (ReminderSource source : sources) {
            JsonNode policy = readPolicy(source.reminderPolicy());
            List<ReminderMoment> moments = new ArrayList<>();
            if (policy.path("moments").isArray()) {
                addConfiguredMoments(principal, source, policy, moments);
            } else {
                addMoment(moments, policy, "preOpenMinutes", "PRE_OPEN",
                        source.availableAt(), true, source.executorAccountId(), source.assignmentId());
                addMoment(moments, policy, "afterOpenMinutes", "AFTER_OPEN",
                        source.availableAt(), false, source.executorAccountId(), source.assignmentId());
                addMoment(moments, policy, "dueSoonMinutes", "DUE_SOON",
                        source.dueAt(), true, source.executorAccountId(), source.assignmentId());
                addMoment(moments, policy, "overdueMinutes", "OVERDUE",
                        source.dueAt(), false, source.executorAccountId(), source.assignmentId());
                addMoment(moments, policy, "escalationMinutes", "ESCALATION",
                        source.dueAt(), false, source.managerAccountId(), source.managerAssignmentId());
            }
            for (ReminderMoment moment : moments) {
                if (!processingTime.isBefore(moment.scheduledAt())
                        && stageApplies(source, moment.kind(), processingTime)) {
                    enqueueReminder(principal, source, moment);
                }
            }
        }
    }

    private void addConfiguredMoments(
            TenantPrincipal principal,
            ReminderSource source,
            JsonNode policy,
            List<ReminderMoment> moments
    ) {
        for (JsonNode configured : policy.path("moments")) {
            String code = configured.path("code").asText("").trim().toUpperCase();
            String kind = configured.path("kind").asText("REMINDER").trim().toUpperCase();
            String localTime = configured.path("localTime").asText("").trim();
            String recipient = configured.path("recipient").asText("EXECUTORS").trim().toUpperCase();
            if (!code.matches("[A-Z][A-Z0-9_]{0,23}") || localTime.isBlank()) {
                throw new IllegalArgumentException("提醒时点编码或时间无效");
            }
            OffsetDateTime scheduledAt = source.businessDate().atTime(LocalTime.parse(localTime))
                    .atZone(reminderZone(source)).toOffsetDateTime();
            List<ReminderRecipient> recipients = "DIRECT_MANAGER".equals(recipient)
                    ? source.managerAccountId() == null ? List.of()
                    : List.of(new ReminderRecipient(source.managerAccountId(), source.managerAssignmentId()))
                    : executorRecipients(principal, source);
            for (ReminderRecipient target : recipients) {
                moments.add(new ReminderMoment(code, kind, scheduledAt, target.accountId(), target.assignmentId()));
            }
        }
    }

    private List<ReminderRecipient> executorRecipients(TenantPrincipal principal, ReminderSource source) {
        List<ReminderRecipient> delegated = jdbc.query("""
                select employee.account_id, assignment.id as assignment_id
                from work_expectation_delegation delegation
                join employee_position_assignment assignment
                  on assignment.tenant_id = delegation.tenant_id
                 and assignment.id = delegation.delegate_assignment_id
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                where delegation.tenant_id = :tenantId
                  and delegation.work_expectation_id = :expectationId
                  and delegation.status = 'ACTIVE' and employee.account_id is not null
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("expectationId", source.id()),
                (rs, rowNum) -> new ReminderRecipient(
                        rs.getObject("account_id", UUID.class), rs.getObject("assignment_id", UUID.class)));
        if (!delegated.isEmpty()) return delegated;
        List<ReminderRecipient> shared = jdbc.query("""
                select distinct employee.account_id, assignment.id as assignment_id
                from work_expectation expectation
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id and item.id = expectation.work_package_item_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = expectation.tenant_id
                 and assignment.status = 'ACTIVE'
                 and assignment.valid_from <= expectation.business_date
                 and (assignment.valid_to is null or assignment.valid_to >= expectation.business_date)
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                where expectation.tenant_id = :tenantId and expectation.id = :expectationId
                  and exists (
                    select 1
                    from org_unit_closure executor_scope
                    where executor_scope.tenant_id = expectation.tenant_id
                      and executor_scope.ancestor_id = expectation.target_org_unit_id
                      and executor_scope.descendant_id = assignment.org_unit_id
                  )
                  and employee.account_id is not null
                  and (assignment.id = expectation.position_assignment_id
                       or jsonb_exists(coalesce(item.execution_policy -> 'allowedPositionCodes', '[]'::jsonb), position.code))
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("expectationId", source.id()),
                (rs, rowNum) -> new ReminderRecipient(
                        rs.getObject("account_id", UUID.class), rs.getObject("assignment_id", UUID.class)));
        return shared.isEmpty() && source.executorAccountId() != null
                ? List.of(new ReminderRecipient(source.executorAccountId(), source.assignmentId())) : shared;
    }

    private void addMoment(
            List<ReminderMoment> moments,
            JsonNode policy,
            String property,
            String stage,
            OffsetDateTime anchor,
            boolean before,
            UUID accountId,
            UUID assignmentId
    ) {
        if (accountId == null) {
            return;
        }
        OffsetDateTime scheduledAt = relativeReminderTime(policy, property, anchor, before);
        if (scheduledAt == null) return;
        moments.add(new ReminderMoment(stage, stage,
                scheduledAt,
                accountId, assignmentId));
    }

    static OffsetDateTime relativeReminderTime(
            JsonNode policy,
            String property,
            OffsetDateTime anchor,
            boolean before
    ) {
        if (!policy.has(property) || !policy.path(property).canConvertToInt()) return null;
        int minutes = policy.path(property).asInt();
        if (minutes < 0 || minutes > 7 * 24 * 60) {
            throw new IllegalArgumentException("提醒分钟数超出允许范围: " + property);
        }
        return before ? anchor.minusMinutes(minutes) : anchor.plusMinutes(minutes);
    }

    private static ZoneId reminderZone(ReminderSource source) {
        String zoneName = "FIXED".equals(source.timezoneMode())
                ? source.fixedTimezone() : source.tenantTimezone();
        return ZoneId.of(zoneName);
    }

    private boolean stageApplies(ReminderSource source, String stage, OffsetDateTime processingTime) {
        if ("PRE_OPEN".equals(stage)) {
            return "PLANNED".equals(source.status())
                    && processingTime.isBefore(source.availableAt());
        }
        if ("DUE_SOON".equals(stage)) {
            return Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS").contains(source.status())
                    && processingTime.isBefore(source.dueAt());
        }
        if ("AFTER_OPEN".equals(stage)) {
            return Set.of("AVAILABLE", "IN_PROGRESS").contains(source.status())
                    && processingTime.isBefore(source.dueAt());
        }
        if (Set.of("REMINDER", "PROGRESS").contains(stage)) {
            return Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS").contains(source.status())
                    && processingTime.isBefore(source.dueAt());
        }
        return Set.of("PLANNED", "AVAILABLE", "IN_PROGRESS", "MISSED").contains(source.status())
                && !processingTime.isBefore(source.dueAt());
    }

    private void enqueueReminder(TenantPrincipal principal, ReminderSource source, ReminderMoment moment) {
        UUID reminderId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into work_expectation_reminder
                    (id, tenant_id, work_expectation_id, reminder_stage, scheduled_at,
                     recipient_account_id, recipient_assignment_id, status)
                values (:id, :tenantId, :expectationId, :stage, :scheduledAt,
                        :accountId, :assignmentId, 'PENDING')
                on conflict (tenant_id, work_expectation_id, reminder_stage, recipient_account_id)
                do nothing
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("id", reminderId).addValue("expectationId", source.id())
                .addValue("stage", moment.stage()).addValue("scheduledAt", moment.scheduledAt())
                .addValue("accountId", moment.accountId()).addValue("assignmentId", moment.assignmentId()));
        if (inserted != 1) {
            return;
        }
        UUID notificationId = UUID.randomUUID();
        String title = reminderTitle(moment.kind(), source.itemName());
        String content = reminderContent(moment.kind(), source);
        UUID storedNotificationId = jdbc.queryForObject("""
                insert into notification
                    (id, tenant_id, recipient_account_id, recipient_assignment_id,
                     notification_type, title, content, source_type, source_id, idempotency_key)
                values (:id, :tenantId, :accountId, :assignmentId,
                        'WORK_EXPECTATION_REMINDER', :title, :content,
                        'WORK_EXPECTATION', :expectationId, :idempotencyKey)
                on conflict (tenant_id, recipient_account_id, idempotency_key)
                do update set title = excluded.title
                returning id
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("id", notificationId).addValue("accountId", moment.accountId())
                .addValue("assignmentId", moment.assignmentId()).addValue("title", title)
                .addValue("content", content).addValue("expectationId", source.id())
                .addValue("idempotencyKey", "work-expectation:" + source.id() + ":" + moment.stage()),
                UUID.class);
        jdbc.update("""
                update work_expectation_reminder
                set status = 'SENT', notification_id = :notificationId, sent_at = now()
                where tenant_id = :tenantId and id = :id and status = 'PENDING'
                """, new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("id", reminderId).addValue("notificationId", storedNotificationId));
        auditWriter.record("WORK_EXPECTATION_REMINDER_SENT", "WORK_EXPECTATION", source.id(),
                "{\"stage\":\"" + moment.stage() + "\",\"notificationId\":\""
                        + storedNotificationId + "\"}");
    }

    private String reminderTitle(String stage, String itemName) {
        return switch (stage) {
            case "PRE_OPEN" -> "即将开始：" + itemName;
            case "DUE_SOON" -> "即将截止：" + itemName;
            case "OVERDUE" -> "工作已逾期：" + itemName;
            case "ESCALATION" -> "下属工作逾期：" + itemName;
            case "PROGRESS" -> "工作进度督办：" + itemName;
            case "REMINDER" -> "工作提醒：" + itemName;
            default -> itemName;
        };
    }

    private String reminderContent(String stage, ReminderSource source) {
        String due = source.dueAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        String prefix = Set.of("ESCALATION", "PROGRESS").contains(stage)
                ? "请督办下属完成" : "请按模板完成";
        return prefix + source.itemName() + "，门店：" + source.orgName()
                + "，截止：" + due + "。点击消息进入事项并上传证据。";
    }

    private JsonNode readPolicy(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("工作提醒策略不是有效JSON", exception);
        }
    }

    private String payload(MissedExpectation expectation) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workExpectationId", expectation.id().toString());
        payload.put("workPackageItemId", expectation.itemId().toString());
        payload.put("positionAssignmentId", expectation.assignmentId().toString());
        payload.put("orgUnitId", expectation.orgUnitId().toString());
        payload.put("businessDate", expectation.businessDate().toString());
        payload.put("dueAt", expectation.dueAt().toString());
        payload.put("status", "MISSED");
        return payload.toString();
    }

    private int validateBatchLimit(int batchLimit) {
        if (batchLimit < 1 || batchLimit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        return batchLimit;
    }

    private record MissedExpectation(
            UUID id,
            UUID itemId,
            UUID assignmentId,
            UUID orgUnitId,
            LocalDate businessDate,
            OffsetDateTime dueAt
    ) {
    }

    private record ReminderSource(
            UUID id,
            String status,
            LocalDate businessDate,
            OffsetDateTime availableAt,
            OffsetDateTime dueAt,
            UUID assignmentId,
            UUID targetOrgUnitId,
            String itemName,
            String orgName,
            String reminderPolicy,
            String timezoneMode,
            String fixedTimezone,
            String tenantTimezone,
            UUID executorAccountId,
            UUID managerAssignmentId,
            UUID managerAccountId
    ) {
    }

    private record ReminderMoment(
            String stage,
            String kind,
            OffsetDateTime scheduledAt,
            UUID accountId,
            UUID assignmentId
    ) {
    }

    private record ReminderRecipient(UUID accountId, UUID assignmentId) {
    }
}
