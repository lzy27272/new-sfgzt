package cn.sifangguan.hotelaios.dailyreports;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DailyReportDispatchIntegrationTest {
    private static final UUID TENANT =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate BUSINESS_DATE = LocalDate.now(BUSINESS_ZONE);
    private static final Instant OPEN_SCAN = atBusinessTime(22, 0);
    private static final Instant DUE_SOON_SCAN = atBusinessTime(22, 30);
    private static final Instant DEADLINE_SCAN = atBusinessTime(23, 30);
    private static final UUID GENERAL_MANAGER_ASSIGNMENT =
            UUID.fromString("19200000-0000-0000-0000-000000000001");
    private static final UUID FRONT_DESK_ASSIGNMENT =
            UUID.fromString("19200000-0000-0000-0000-000000000002");
    private static final UUID GENERAL_MANAGER_TEMPLATE_VERSION =
            UUID.fromString("43100000-0000-0000-0000-000000000004");

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private DailyReportDispatchService service;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.automation.worker.enabled", () -> false);
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
    }

    @Test
    void materializesFiveHotelRolesOnceAndUsesFrozenPolicyForEligibleDraftReports() {
        // This test isolates the current-business-day lifecycle. Production keeps
        // the seeded one-day backfill policy, which is covered by the dispatcher itself.
        jdbc.update("""
                update daily_report_delivery_policy
                set backfill_days = 0
                where tenant_id = ?
                """, TENANT);
        DailyReportDispatchService.ProcessResult first = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), OPEN_SCAN);

        assertThat(first.createdReports()).isEqualTo(6);
        assertThat(first.openedEvents()).isEqualTo(6);
        assertThat(first.dueSoonEvents()).isZero();
        assertThat(first.overdueEvents()).isZero();
        assertThat(count("""
                select count(*) from daily_report
                where tenant_id = ? and business_date = ?
                """, TENANT, BUSINESS_DATE)).isEqualTo(6);
        assertHotelRoleCoverage();
        assertThat(count("""
                select count(*)
                from daily_report report
                join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = report.current_revision_id
                where report.tenant_id = ? and report.business_date = ?
                  and report.report_status = 'DRAFT'
                  and revision.revision_type = 'ORIGINAL'
                  and revision.revision_status = 'DRAFT'
                  and jsonb_exists(revision.payload_snapshot, 'deliveryPolicy')
                """, TENANT, BUSINESS_DATE)).isEqualTo(6);
        assertThat(count("""
                select count(*)
                from daily_report report
                join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = report.current_revision_id
                where report.tenant_id = ? and report.business_date = ?
                  and revision.payload_snapshot -> 'deliveryPolicy'
                        -> 'preDueReminderMinutes' = '[30]'::jsonb
                  and revision.payload_snapshot -> 'deliveryPolicy'
                        -> 'overdueReminderMinutes' = '[0, 30]'::jsonb
                """, TENANT, BUSINESS_DATE)).isEqualTo(5);
        assertThat(count("""
                select count(*)
                from daily_report report
                join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = report.current_revision_id
                where report.tenant_id = ? and report.business_date = ?
                  and revision.payload_snapshot -> 'deliveryPolicy'
                        -> 'preDueReminderMinutes' = '[180, 60]'::jsonb
                  and revision.payload_snapshot -> 'deliveryPolicy'
                        -> 'overdueReminderMinutes' = '[60, 65]'::jsonb
                """, TENANT, BUSINESS_DATE)).isEqualTo(1);
        assertThat(count("""
                select count(*) from notification notification
                join daily_report report
                  on report.tenant_id = notification.tenant_id
                 and report.id = notification.source_id
                where notification.tenant_id = ?
                  and notification.notification_type = 'DAILY_REPORT_READY'
                  and notification.source_type = 'DAILY_REPORT'
                  and report.business_date = ?
                """, TENANT, BUSINESS_DATE)).isEqualTo(6);
        assertThat(count("""
                select count(*) from outbox_event
                where tenant_id = ? and business_date = ?
                  and event_type = 'DAILY_REPORT_OPENED'
                """, TENANT, BUSINESS_DATE)).isEqualTo(6);

        DailyReportDispatchService.ProcessResult replay = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), OPEN_SCAN);
        assertThat(replay.createdReports()).isZero();
        assertThat(replay.openedEvents()).isZero();
        assertThat(replay.dueSoonEvents()).isZero();
        assertThat(replay.overdueEvents()).isZero();
        assertThat(count("""
                select count(*) from daily_report
                where tenant_id = ? and business_date = ?
                """, TENANT, BUSINESS_DATE)).isEqualTo(6);

        UUID submittedReportId = reportIdExcludingAssignments(
                GENERAL_MANAGER_ASSIGNMENT, FRONT_DESK_ASSIGNMENT);
        UUID mismatchedReportId = reportIdForAssignment(FRONT_DESK_ASSIGNMENT);
        assertThat(jdbc.update("""
                update daily_report
                set report_status = 'SUBMITTED', submitted_at = now(),
                    row_version = row_version + 1
                where tenant_id = ? and id = ?
                """, TENANT, submittedReportId)).isEqualTo(1);
        assertThat(jdbc.update("""
                update daily_report
                set template_version_id = ?, row_version = row_version + 1
                where tenant_id = ? and id = ?
                """, GENERAL_MANAGER_TEMPLATE_VERSION, TENANT, mismatchedReportId)).isEqualTo(1);
        jdbc.update("""
                update daily_report_delivery_policy
                set pre_due_reminder_minutes = array[5]::integer[],
                    overdue_reminder_minutes = array[10]::integer[],
                    updated_by = created_by,
                    row_version = row_version + 1
                where tenant_id = ?
                """, TENANT);
        PendingReviewFixture pendingReview = createPendingReviewFixture(
                submittedReportId, mismatchedReportId);

        DailyReportDispatchService.ProcessResult dueSoon = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), DUE_SOON_SCAN);
        assertThat(dueSoon.createdReports()).isZero();
        assertThat(dueSoon.dueSoonEvents()).isEqualTo(3);
        assertThat(count("""
                select count(*) from outbox_event
                where tenant_id = ? and business_date = ?
                  and event_type = 'DAILY_REPORT_DUE_SOON'
                """, TENANT, BUSINESS_DATE)).isEqualTo(3);
        assertThat(count("""
                select count(*)
                from notification notification
                join daily_report report
                  on report.tenant_id = notification.tenant_id
                 and report.id = notification.source_id
                where notification.tenant_id = ?
                  and report.business_date = ?
                  and notification.source_type = 'DAILY_REPORT'
                  and notification.notification_type = 'DAILY_REPORT_DUE_SOON'
                """, TENANT, BUSINESS_DATE)).isEqualTo(3);
        assertThat(count("""
                select count(*)
                from daily_report_item_result result
                join daily_report report
                  on report.tenant_id = result.tenant_id
                 and report.current_revision_id = result.revision_id
                where result.tenant_id = ? and report.id = ?
                  and result.result_status = 'PENDING'
                """, TENANT, pendingReview.reportId())).isEqualTo(1);
        assertThat(count("""
                select count(*)
                from notification notification
                join daily_report report
                  on report.tenant_id = notification.tenant_id
                 and report.id = notification.source_id
                join employee_position_assignment executor
                  on executor.tenant_id = report.tenant_id
                 and executor.id = report.position_assignment_id
                 and executor.manager_assignment_id = notification.recipient_assignment_id
                where notification.tenant_id = ? and report.id = ?
                  and notification.notification_type = 'DAILY_REPORT_REVIEW_PENDING'
                  and notification.source_type = 'DAILY_REPORT'
                  and notification.idempotency_key = ?
                """, TENANT, pendingReview.reportId(),
                "daily-report:review-pending:" + pendingReview.reportId() + ":1")).isEqualTo(1);
        assertNoReminderEvents(submittedReportId);
        assertNoReminderEvents(mismatchedReportId);

        PendingReviewFixture secondPendingReview = createPendingReviewFixture(
                submittedReportId, mismatchedReportId);
        assertThat(secondPendingReview.reportId()).isEqualTo(pendingReview.reportId());
        DailyReportDispatchService.ProcessResult dueSoonReplay = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), DUE_SOON_SCAN);
        assertThat(dueSoonReplay.dueSoonEvents()).isZero();
        assertThat(count("""
                select count(*) from notification
                where tenant_id = ? and source_id = ?
                  and notification_type = 'DAILY_REPORT_REVIEW_PENDING'
                """, TENANT, pendingReview.reportId())).isEqualTo(2);
        assertThat(count("""
                select count(*) from notification
                where tenant_id = ? and source_id = ?
                  and notification_type = 'DAILY_REPORT_REVIEW_PENDING'
                  and idempotency_key = ?
                """, TENANT, pendingReview.reportId(),
                "daily-report:review-pending:" + pendingReview.reportId() + ":2")).isEqualTo(1);

        DailyReportDispatchService.ProcessResult unchangedPendingReplay = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), DUE_SOON_SCAN);
        assertThat(unchangedPendingReplay.dueSoonEvents()).isZero();
        assertThat(count("""
                select count(*) from notification
                where tenant_id = ? and source_id = ?
                  and notification_type = 'DAILY_REPORT_REVIEW_PENDING'
                """, TENANT, pendingReview.reportId())).isEqualTo(2);

        DailyReportDispatchService.ProcessResult overdue = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), DEADLINE_SCAN);
        assertThat(overdue.createdReports()).isZero();
        assertThat(overdue.overdueEvents()).isPositive();
        assertThat(count("""
                select count(*) from outbox_event
                where tenant_id = ? and business_date = ?
                  and event_type = 'DAILY_REPORT_OVERDUE'
                """, TENANT, BUSINESS_DATE)).isEqualTo(overdue.overdueEvents());
        assertThat(count("""
                select count(*)
                from notification notification
                join daily_report report
                  on report.tenant_id = notification.tenant_id
                 and report.id = notification.source_id
                where notification.tenant_id = ?
                  and report.business_date = ?
                  and notification.source_type = 'DAILY_REPORT'
                  and notification.notification_type = 'DAILY_REPORT_OVERDUE'
                """, TENANT, BUSINESS_DATE)).isEqualTo(overdue.overdueEvents());
        assertNoReminderEvents(submittedReportId);
        assertNoReminderEvents(mismatchedReportId);

        DailyReportDispatchService.ProcessResult frozenReplay = service.processTenantAsSystem(
                TENANT, 100, UUID.randomUUID(), atBusinessTime(23, 40));
        assertThat(frozenReplay.dueSoonEvents()).isZero();
        assertThat(frozenReplay.overdueEvents()).isZero();
        assertThat(count("""
                select count(*) from outbox_event
                where tenant_id = ? and idempotency_key like 'daily-report:overdue:%:10'
                """, TENANT)).isZero();
    }

    private void assertHotelRoleCoverage() {
        assertThat(count("""
                select count(distinct position.code)
                from daily_report report
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id
                 and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                where report.tenant_id = ? and report.business_date = ?
                """, TENANT, BUSINESS_DATE)).isEqualTo(5);
        assertPositionReportCount("FRONT_DESK", 2);
        assertPositionReportCount("FRONT_OFFICE_SUPERVISOR", 1);
        assertPositionReportCount("HOUSEKEEPING_SUPERVISOR", 1);
        assertPositionReportCount("ASSISTANT_GENERAL_MANAGER", 1);
        assertPositionReportCount("GENERAL_MANAGER", 1);
        assertThat(count("""
                select count(*)
                from daily_report report
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id
                 and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                where report.tenant_id = ? and report.business_date = ?
                  and position.code in ('OTA_OPERATION_ASSISTANT', 'OTA_OPERATION_MANAGER')
                """, TENANT, BUSINESS_DATE)).isZero();
        assertThat(count("""
                select count(*)
                from daily_report report
                join employee
                  on employee.tenant_id = report.tenant_id
                 and employee.id = report.employee_id
                join user_account account
                  on account.tenant_id = employee.tenant_id
                 and account.id = employee.account_id
                where report.tenant_id = ? and report.business_date = ?
                  and account.login_name = 'ceo.demo'
                """, TENANT, BUSINESS_DATE)).isZero();
    }

    private void assertPositionReportCount(String positionCode, int expected) {
        assertThat(count("""
                select count(*)
                from daily_report report
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id
                 and assignment.id = report.position_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                where report.tenant_id = ? and report.business_date = ?
                  and position.code = ?
                """, TENANT, BUSINESS_DATE, positionCode)).isEqualTo(expected);
    }

    private UUID reportIdForAssignment(UUID assignmentId) {
        return jdbc.queryForObject("""
                select id from daily_report
                where tenant_id = ? and business_date = ?
                  and position_assignment_id = ?
                """, UUID.class, TENANT, BUSINESS_DATE, assignmentId);
    }

    private UUID reportIdExcludingAssignments(UUID... assignmentIds) {
        return jdbc.queryForObject("""
                select id from daily_report
                where tenant_id = ? and business_date = ?
                  and position_assignment_id <> all(?::uuid[])
                order by id
                limit 1
                """, UUID.class, TENANT, BUSINESS_DATE,
                "{" + String.join(",", java.util.Arrays.stream(assignmentIds)
                        .map(UUID::toString).toList()) + "}");
    }

    private PendingReviewFixture createPendingReviewFixture(UUID... excludedReportIds) {
        Map<String, Object> source = jdbc.queryForMap("""
                select report.id as report_id, report.position_assignment_id,
                       report.employee_id, assignment.org_unit_id,
                       package_item.id as work_package_item_id,
                       package_item.work_package_version_id,
                       package_item.form_version_id,
                       allocation.id as allocation_id,
                       allocation.target_org_unit_id,
                       employee.account_id
                from daily_report report
                join employee_position_assignment assignment
                  on assignment.tenant_id = report.tenant_id
                 and assignment.id = report.position_assignment_id
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                join daily_report_template_section relation
                  on relation.tenant_id = report.tenant_id
                 and relation.template_version_id = report.template_version_id
                join daily_report_template_item template_item
                  on template_item.tenant_id = relation.tenant_id
                 and template_item.section_version_id = relation.section_version_id
                join work_package_item package_item
                  on package_item.tenant_id = template_item.tenant_id
                 and package_item.id = template_item.work_package_item_id
                 and package_item.form_version_id is not null
                join work_package_allocation allocation
                  on allocation.tenant_id = package_item.tenant_id
                 and allocation.work_package_version_id = package_item.work_package_version_id
                 and allocation.position_assignment_id = report.position_assignment_id
                 and allocation.status = 'ACTIVE'
                join employee_position_assignment manager
                  on manager.tenant_id = assignment.tenant_id
                 and manager.id = assignment.manager_assignment_id
                 and manager.status = 'ACTIVE'
                join employee manager_employee
                  on manager_employee.tenant_id = manager.tenant_id
                 and manager_employee.id = manager.employee_id
                 and manager_employee.account_id is not null
                where report.tenant_id = ? and report.business_date = ?
                  and report.report_status = 'DRAFT'
                  and report.id <> all(?::uuid[])
                  and not exists (
                    select 1
                    from work_record existing
                    where existing.tenant_id = report.tenant_id
                      and existing.position_assignment_id = report.position_assignment_id
                      and existing.business_date = report.business_date
                      and existing.work_package_item_id = package_item.id
                      and existing.status in ('SUBMITTED', 'APPROVED')
                  )
                  and exists (
                    select 1 from work_package_item_responsibility responsibility
                    where responsibility.tenant_id = package_item.tenant_id
                      and responsibility.work_package_item_id = package_item.id
                      and responsibility.participant_type in ('REVIEWER', 'ACCEPTOR')
                      and responsibility.resolver_type = 'DIRECT_MANAGER_ASSIGNMENT'
                  )
                order by report.id, template_item.sort_order
                limit 1
                """, TENANT, BUSINESS_DATE,
                "{" + String.join(",", java.util.Arrays.stream(excludedReportIds)
                        .map(UUID::toString).toList()) + "}");
        UUID expectationId = UUID.randomUUID();
        UUID workRecordId = UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation
                    (id, tenant_id, work_package_item_id, work_package_allocation_id,
                     position_assignment_id, target_org_unit_id, business_date, period_key,
                     available_at, due_at, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, now() - interval '1 hour', now(), 'SUBMITTED')
                """, expectationId, TENANT, source.get("work_package_item_id"),
                source.get("allocation_id"), source.get("position_assignment_id"),
                source.get("target_org_unit_id"), BUSINESS_DATE,
                "dispatch-reconcile-" + expectationId);
        jdbc.update("""
                insert into work_record
                    (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                     form_version_id, business_date, status, payload, submitted_at,
                     work_package_version_id, work_package_item_id, work_expectation_id,
                     record_kind, target_org_unit_id, occurred_at, submitted_by_account_id,
                     attempt_no, content_hash, completion_statement)
                values (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', '{}'::jsonb, now(),
                        ?, ?, ?, 'SCHEDULED', ?, now(), ?, 1, repeat('a', 64), '已完成当日工作')
                """, workRecordId, TENANT, source.get("org_unit_id"), source.get("employee_id"),
                source.get("position_assignment_id"), source.get("form_version_id"), BUSINESS_DATE,
                source.get("work_package_version_id"), source.get("work_package_item_id"),
                expectationId, source.get("target_org_unit_id"), source.get("account_id"));
        return new PendingReviewFixture((UUID) source.get("report_id"), workRecordId);
    }

    private void assertNoReminderEvents(UUID reportId) {
        assertThat(count("""
                select count(*) from outbox_event
                where tenant_id = ? and aggregate_id = ?
                  and event_type in ('DAILY_REPORT_DUE_SOON', 'DAILY_REPORT_OVERDUE')
                """, TENANT, reportId)).isZero();
    }

    private static Instant atBusinessTime(int hour, int minute) {
        return BUSINESS_DATE.atTime(LocalTime.of(hour, minute)).atZone(BUSINESS_ZONE).toInstant();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record PendingReviewFixture(UUID reportId, UUID workRecordId) {
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
