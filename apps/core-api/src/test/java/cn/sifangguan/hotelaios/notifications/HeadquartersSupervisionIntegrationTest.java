package cn.sifangguan.hotelaios.notifications;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import cn.sifangguan.hotelaios.workdata.WorkDataService;
import cn.sifangguan.hotelaios.workdata.WorkDataModels;
import cn.sifangguan.hotelaios.tasks.TaskService;
import cn.sifangguan.hotelaios.workpackage.WorkExpectationSlaService;
import cn.sifangguan.hotelaios.workpackage.WorkPackageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import cn.sifangguan.hotelaios.workpackage.WorkBoardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HeadquartersSupervisionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GROUP = UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000004");
    private static final UUID MANAGER = UUID.fromString("19200000-0000-0000-0000-000000000001");
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID FORM_VERSION = UUID.randomUUID();
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired JdbcTemplate jdbc;
    @Autowired EffectiveIdentityService identities;
    @Autowired StoreManagerSupervisorResolver resolver;
    @Autowired WorkExpectationSlaService workSla;
    @Autowired TaskService tasks;
    @Autowired WorkPackageService work;
    @Autowired WorkBoardService boards;
    @Autowired WorkDataService records;
    @Autowired AttachmentService attachments;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
                return connection.getMetaData().getURL();
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
    }

    @AfterAll static void close() throws Exception { POSTGRES.close(); }

    @Test
    void headquartersAffiliationHotelResponsibilityUpwardVisibilityAndOverdueSupervisionCoexist() {
        UUID headquarters = organization("总部", "DEPARTMENT");
        UUID testHotel = organization("测试门店", "HOTEL");
        UUID vp = UUID.randomUUID();
        UUID vpAssignment = actor(vp, headquarters, "GROUP_VICE_PRESIDENT", true);
        UUID secondVpAssignment = actor(vp, GROUP, "GROUP_VICE_PRESIDENT", false);
        UUID groupManager = UUID.randomUUID();
        UUID groupManagerAssignment = actor(groupManager, GROUP, "GROUP_GENERAL_MANAGER", true);
        UUID chairman = UUID.randomUUID();
        UUID chairmanAssignment = actor(chairman, GROUP, "GROUP_CHAIRMAN", true);
        // Local isolated fixture only: publish explicit responsible-hotel scope, not TENANT.
        jdbc.update("""
                update position_function_profile_version v set authorization_scope_type = 'ASSIGNED_HOTELS'
                from position_function_profile p join position_definition d
                  on d.tenant_id = p.tenant_id and d.id = p.position_id
                where v.tenant_id = ? and v.profile_id = p.id and v.tenant_id = p.tenant_id
                  and d.code = 'GROUP_VICE_PRESIDENT' and v.lifecycle_status = 'PUBLISHED'
                """, TENANT);
        for (UUID assignment : List.of(vpAssignment, secondVpAssignment)) {
            for (UUID hotel : List.of(HOTEL, OTHER_HOTEL)) {
                jdbc.update("""
                        insert into employee_assignment_hotel_scope (tenant_id, assignment_id, hotel_org_unit_id)
                        values (?, ?, ?)
                        """, TENANT, assignment, hotel);
            }
        }
        TenantPrincipal vpIdentity = identities.resolve(TENANT, vp, UUID.randomUUID(), vpAssignment);
        assertThat(vpIdentity.hasTenantScope()).isFalse();
        assertThat(vpIdentity.orgScopes()).contains(HOTEL, OTHER_HOTEL).doesNotContain(testHotel);
        assertThat(jdbc.queryForObject("select org_unit_id from employee_position_assignment where tenant_id = ? and id = ?",
                UUID.class, TENANT, vpAssignment)).isEqualTo(headquarters);
        TenantPrincipal managerIdentity = identities.resolve(TENANT, groupManager, UUID.randomUUID(), groupManagerAssignment);
        TenantPrincipal chairmanIdentity = identities.resolve(TENANT, chairman, UUID.randomUUID(), chairmanAssignment);
        assertThat(managerIdentity.hasTenantScope() || managerIdentity.orgScopes().contains(headquarters)).isTrue();
        assertThat(chairmanIdentity.hasTenantScope() || chairmanIdentity.orgScopes().contains(headquarters)).isTrue();

        var audience = audience(managerIdentity, "work-record.team-read");
        assertThat(audience.forWork(MANAGER, HOTEL)).extracting(StoreManagerSupervisorResolver.Supervisor::accountId)
                .containsExactly(vp); // Two vice-president assignments, one recipient account.
        assertThat(audience.forWork(MANAGER, OTHER_HOTEL)).hasSize(1);
        assertThat(audience.forWork(MANAGER, testHotel)).isEmpty();
        assertThat(audience.forWork(vpAssignment, HOTEL)).isEmpty(); // Not a store-manager-owned work item.

        // A deterministic, already-due configured manager escalation, independent of wall-clock hour.
        createWorkPackage();
        UUID formalWork = expectation(MANAGER, HOTEL);
        UUID testWork = expectation(MANAGER, testHotel);
        UUID headquartersWork = expectation(vpAssignment, headquarters);
        workSla.processTenantAsSystem(TENANT, 100, UUID.randomUUID());
        workSla.processTenantAsSystem(TENANT, 100, UUID.randomUUID());
        // Two configured stages, each exactly once despite repeated runs and duplicate assignments.
        assertThat(notificationCount(vp, formalWork, "WORK_EXPECTATION_SUPERVISION")).isEqualTo(2);
        assertThat(notificationCount(vp, formalWork, "WORK_EXPECTATION_REMINDER")).isZero();
        assertThat(notificationCount(vp, testWork, "WORK_EXPECTATION_SUPERVISION")).isZero();
        assertThat(notificationCount(vp, headquartersWork, "WORK_EXPECTATION_SUPERVISION")).isZero();

        UUID formalTask = task(MANAGER, HOTEL);
        UUID excludedTask = task(MANAGER, testHotel);
        tasks.processSlaAsSystem(TENANT, 100, UUID.randomUUID());
        tasks.processSlaAsSystem(TENANT, 100, UUID.randomUUID());
        assertThat(notificationCount(vp, formalTask, "TASK_OVERDUE")).isOne();
        assertThat(notificationCount(vp, excludedTask, "TASK_OVERDUE")).isZero();

        // Both upper leaders see headquarters staff alongside (not instead of) the hotels.
        for (TenantPrincipal leader : List.of(managerIdentity, chairmanIdentity)) {
            TenantContext.set(leader);
            try {
                JsonNode summary = mapper.valueToTree(work.teamWorkbenchSummary(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
                assertThat(summary.path("hotels").findValuesAsText("id")).contains(HOTEL.toString())
                        .doesNotContain(headquarters.toString());
                assertThat(summary.path("organizations").findValuesAsText("assignmentId")).contains(vpAssignment.toString());
                JsonNode board = mapper.valueToTree(boards.board(false, null, null, null,
                        null, null, "ALL", "PERIOD", false, 0, 200));
                assertThat(board.path("items").findValuesAsText("position_assignment_id")).contains(vpAssignment.toString());
                for (JsonNode group : board.path("groups")) {
                    if (group.path("dimension").asText().equals("hotel")) {
                        assertThat(group.path("group_id").asText()).isNotEqualTo(headquarters.toString());
                    }
                }
            } finally { TenantContext.clear(); }
        }
        TenantContext.set(vpIdentity);
        try {
            JsonNode summary = mapper.valueToTree(work.teamWorkbenchSummary(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
            assertThat(summary.path("hotels").findValuesAsText("id")).contains(HOTEL.toString()).doesNotContain(testHotel.toString());
            // Headquarters affiliation is NOT a blanket scope grant. The active
            // owner can read/complete their work without seeing their peers' work.
            UUID ownRecord = draftRecord(vpAssignment, headquarters, headquartersWork);
            UUID peerWork = expectation(groupManagerAssignment, headquarters);
            UUID peerRecord = draftRecord(groupManagerAssignment, headquarters, peerWork);
            UUID otherAssignmentWork = expectation(secondVpAssignment, headquarters);
            assertThat(vpIdentity.orgScopes()).doesNotContain(headquarters, GROUP);
            assertThat(work.expectationDetail(headquartersWork).get("id")).isEqualTo(headquartersWork);
            assertThat(records.record(ownRecord).get("id")).isEqualTo(ownRecord);
            assertThat(attachments.list(ownRecord)).isEmpty();
            assertThatThrownBy(() -> work.expectationDetail(peerWork)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> work.expectationDetail(otherAssignmentWork)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> records.record(peerRecord)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> attachments.list(peerRecord)).isInstanceOf(AccessDeniedException.class);
            var update = new WorkDataModels.UpdateDraft(mapper.createObjectNode(), "本人完成", null, null, null, 0);
            // Scope ownership cannot create operation permissions. The default VP
            // profile is read-only for these records; grant writing only in this
            // isolated fixture to also exercise explicitly configured personal work.
            assertThatThrownBy(() -> records.records()).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> records.updateDraft(ownRecord, update)).isInstanceOf(AccessDeniedException.class);
            jdbc.update("""
                    insert into position_function_profile_permission (tenant_id, profile_version_id, permission_id)
                    select v.tenant_id, v.id, permission.id
                    from position_function_profile_version v
                    join position_function_profile p on p.tenant_id = v.tenant_id and p.id = v.profile_id
                    join position_definition d on d.tenant_id = p.tenant_id and d.id = p.position_id
                    cross join permission
                    where v.tenant_id = ? and d.code = 'GROUP_VICE_PRESIDENT'
                      and v.lifecycle_status = 'PUBLISHED'
                      and permission.code in ('work-record.read', 'work-record.submit')
                    on conflict do nothing
                    """, TENANT);
            TenantContext.set(identities.resolve(TENANT, vp, UUID.randomUUID(), vpAssignment));
            assertThat(records.records()).extracting(row -> row.get("id")).contains(ownRecord).doesNotContain(peerRecord);
            JsonNode personalBoard = mapper.valueToTree(boards.board(true, null, null, null,
                    null, null, "ALL", "PERIOD", false, 0, 200));
            assertThat(personalBoard.path("items").findValuesAsText("id"))
                    .contains(headquartersWork.toString()).doesNotContain(peerWork.toString(), otherAssignmentWork.toString());
            assertThatThrownBy(() -> records.updateDraft(peerRecord, update)).isInstanceOf(AccessDeniedException.class);
            assertThat(records.updateDraft(ownRecord, update).get("status")).isEqualTo("DRAFT");
            assertThat(records.submitDraft(ownRecord, new WorkDataModels.SubmitDraft(1)).get("status")).isEqualTo("SUBMITTED");
        } finally { TenantContext.clear(); }
        assertThat(audience(managerIdentity, "not-granted.read").forWork(MANAGER, HOTEL)).isEmpty();
        jdbc.update("update user_account set status = 'DISABLED' where tenant_id = ? and id = ?", TENANT, vp);
        assertThat(audience(managerIdentity, "work-record.team-read").forWork(MANAGER, HOTEL)).isEmpty();
    }

    private StoreManagerSupervisorResolver.Audience audience(TenantPrincipal principal, String permission) {
        return new TransactionTemplate(transactions).execute(status -> resolver.load(principal, permission));
    }

    private UUID organization(String name, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into org_unit (id, tenant_id, parent_id, code, name, unit_type) values (?, ?, ?, ?, ?, ?)",
                id, TENANT, GROUP, "VP-" + id, name, type);
        jdbc.update("insert into org_unit_closure (tenant_id, ancestor_id, descendant_id, depth) values (?, ?, ?, 0), (?, ?, ?, 1)",
                TENANT, id, id, TENANT, GROUP, id);
        return id;
    }

    private UUID actor(UUID account, UUID org, String positionCode, boolean primary) {
        UUID employee = UUID.nameUUIDFromBytes(("supervision:" + account).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID assignment = UUID.randomUUID();
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name) values (?, ?, ?, ?)
                on conflict (tenant_id, id) do nothing
                """, account, TENANT, "vp-" + account, positionCode);
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, hired_on) values (?, ?, ?, ?, ?, current_date)
                on conflict (tenant_id, id) do nothing
                """, employee, TENANT, account, "VP-" + account, positionCode);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                select ?, ?, ?, ?, id, ?, current_date from position_definition where tenant_id = ? and code = ?
                """, assignment, TENANT, employee, org, primary, TENANT, positionCode);
        return assignment;
    }

    private UUID expectation(UUID assignment, UUID org) {
        UUID allocation = UUID.randomUUID();
        UUID expectation = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_allocation
                  (id, tenant_id, work_package_version_id, position_assignment_id, target_org_unit_id, valid_from)
                values (?, ?, ?, ?, ?, current_date)
                """, allocation, TENANT, VERSION, assignment, org);
        jdbc.update("""
                insert into work_expectation
                  (id, tenant_id, work_package_item_id, work_package_allocation_id, position_assignment_id,
                   target_org_unit_id, business_date, period_key, available_at, due_at, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, now() - interval '2 hours', now() - interval '1 hour', 'MISSED')
                """, expectation, TENANT, ITEM, allocation, assignment, org,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), "vp-" + expectation);
        return expectation;
    }

    private void createWorkPackage() {
        UUID form = UUID.randomUUID();
        jdbc.update("insert into form_definition (id, tenant_id, code, name, form_type) values (?, ?, 'VP_SELF', 'Personal work fixture', 'DAILY_WORK')", form, TENANT);
        jdbc.update("""
                insert into form_version (id, tenant_id, form_id, version_no, lifecycle_status, json_schema, published_at)
                values (?, ?, ?, 1, 'PUBLISHED', '{"type":"object","properties":{}}'::jsonb, now())
                """, FORM_VERSION, TENANT, form);
        UUID definition = UUID.randomUUID();
        jdbc.update("""
                insert into work_package_definition (id, tenant_id, code, name, position_id, owner_org_unit_id)
                select ?, ?, 'VP-SUPERVISION', 'Supervision fixture', id, ? from position_definition
                where tenant_id = ? and code = 'GENERAL_MANAGER'
                """, definition, TENANT, GROUP, TENANT);
        jdbc.update("""
                insert into work_package_version (id, tenant_id, work_package_definition_id, version_no, title)
                values (?, ?, ?, 1, 'Supervision fixture')
                """, VERSION, TENANT, definition);
        jdbc.update("""
                insert into work_package_item
                  (id, tenant_id, work_package_version_id, item_code, name, item_type, period_type,
                   timezone_mode, due_local_time, form_version_id, reminder_policy)
                values (?, ?, ?, 'VP_TEST', 'Supervision work', 'INSPECTION', 'DAY', 'TENANT', '00:00', ?,
                  '{"moments":[
                    {"code":"VP_TEST","kind":"ESCALATION","localTime":"00:00","recipient":"DIRECT_MANAGER"},
                    {"code":"VP_OVERDUE","kind":"OVERDUE","localTime":"00:00","recipient":"DIRECT_MANAGER"}
                  ]}'::jsonb)
                """, ITEM, TENANT, VERSION, FORM_VERSION);
        jdbc.update("""
                update work_package_version set lifecycle_status = 'PUBLISHED', content_hash = repeat('a',64),
                  effective_from = now() - interval '1 day', published_at = now()
                where tenant_id = ? and id = ?
                """, TENANT, VERSION);
    }

    private UUID task(UUID assignee, UUID org) {
        UUID task = UUID.randomUUID();
        jdbc.update("""
                insert into management_task
                  (id, tenant_id, task_no, idempotency_key, org_unit_id, title, due_at, lifecycle_status)
                values (?, ?, ?, ?, ?, 'Scoped store-manager task', now() - interval '1 hour', 'IN_PROGRESS')
                """, task, TENANT, "VP-" + task, "vp-" + task, org);
        jdbc.update("""
                insert into task_participant
                  (tenant_id, task_id, participant_type, position_assignment_id, employee_snapshot, position_snapshot, org_snapshot)
                values (?, ?, 'ASSIGNEE', ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
                """, TENANT, task, assignee);
        return task;
    }

    private UUID draftRecord(UUID assignment, UUID target, UUID expectation) {
        UUID record = UUID.randomUUID();
        jdbc.update("""
                insert into work_record (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                  form_version_id, business_date, target_org_unit_id, work_package_version_id,
                  work_package_item_id, work_expectation_id, record_kind, content_hash, occurred_at)
                select ?, tenant_id, org_unit_id, employee_id, id, ?, current_date, ?, ?, ?, ?, 'SCHEDULED', repeat('a',64), now()
                from employee_position_assignment where tenant_id = ? and id = ?
                """, record, FORM_VERSION, target, VERSION, ITEM, expectation, TENANT, assignment);
        return record;
    }

    private int notificationCount(UUID account, UUID source, String type) {
        return jdbc.queryForObject("select count(*) from notification where tenant_id = ? and recipient_account_id = ? and source_id = ? and notification_type = ?",
                Integer.class, TENANT, account, source, type);
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
