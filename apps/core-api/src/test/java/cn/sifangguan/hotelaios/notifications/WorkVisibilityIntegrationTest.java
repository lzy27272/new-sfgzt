package cn.sifangguan.hotelaios.notifications;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import cn.sifangguan.hotelaios.workdata.WorkDataService;
import cn.sifangguan.hotelaios.dailyreports.DailyReportService;
import cn.sifangguan.hotelaios.workpackage.WorkPackageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkVisibilityIntegrationTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GROUP = UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000004");
    private static final UUID MANAGER = UUID.fromString("19200000-0000-0000-0000-000000000001");
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID SHARED_ITEM = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID FORM_VERSION = UUID.randomUUID();
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired JdbcTemplate jdbc;
    @Autowired EffectiveIdentityService identities;
    @Autowired WorkPackageService work;
    @Autowired cn.sifangguan.hotelaios.workpackage.WorkBoardService boards;
    @Autowired WorkDataService records;
    @Autowired AttachmentService attachments;
    @Autowired DailyReportService reports;
    @Autowired ObjectMapper mapper;
    @Autowired cn.sifangguan.hotelaios.dashboard.DashboardService dashboards;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

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


    @BeforeEach void fixtures() { createWorkPackage(); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void assistantCannotReadManagerWorkPayloadAttachmentsOrStatistics() {
        UUID account = UUID.randomUUID();
        UUID assistant = actor(account, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID managerWork = expectation(MANAGER, HOTEL);
        UUID managerRecord = draftRecord(MANAGER, HOTEL, managerWork);
        UUID attachment = UUID.randomUUID();
        jdbc.update("""
                insert into attachment (id,tenant_id,work_record_id,object_key,original_name,media_type,size_bytes,sha256)
                values (?,?,?,'isolated-test/no-file','manager.jpg','image/jpeg',1,repeat('a',64))
                """, attachment, TENANT, managerRecord);
        UUID ownWork = expectation(assistant, HOTEL);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), assistant));
        assertThat(work.teamExpectations(null, null, null, null, 0, 200))
                .extracting(row -> row.get("id")).contains(ownWork).doesNotContain(managerWork);
        assertThat(work.expectations(null, null, null, null, null, null, false, 0, 200))
                .extracting(row -> row.get("id")).doesNotContain(managerWork);
        assertThatThrownBy(() -> work.expectationDetail(managerWork)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> records.record(managerRecord)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> attachments.list(managerRecord)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> attachments.download(attachment)).isInstanceOf(AccessDeniedException.class);
        assertThat(records.records()).extracting(row -> row.get("id")).doesNotContain(managerRecord);
        JsonNode summary = mapper.valueToTree(work.teamWorkbenchSummary(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
        assertThat(summary.findValuesAsText("assignmentId")).doesNotContain(MANAGER.toString());
        JsonNode dashboard = mapper.valueToTree(dashboards.hotelDashboard(HOTEL));
        assertThat(dashboard.path("risks").findValuesAsText("source_id")).doesNotContain(managerWork.toString());
        // These fixtures use the calendar date, which differs from the default business day before 04:00.
        LocalDate fixtureDay = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        JsonNode board = mapper.valueToTree(boards.board(false, HOTEL, null, null,
                fixtureDay, fixtureDay, null, null, false, 0, 200));
        assertThat(board.path("items").findValuesAsText("id")).contains(ownWork.toString())
                .doesNotContain(managerWork.toString());
        assertThat(board.path("groups").findValuesAsText("group_id")).doesNotContain(MANAGER.toString());
        JsonNode filtered = mapper.valueToTree(boards.board(false, HOTEL, null, MANAGER,
                fixtureDay, fixtureDay, null, null, false, 0, 200));
        assertThat(filtered.path("items")).isEmpty();
        assertThat(filtered.path("filtered").path("item_count").asInt()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER, true",
        "ASSISTANT_GENERAL_MANAGER, GENERAL_MANAGER, false",
        "ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER, false",
        "ASSISTANT_GENERAL_MANAGER, FRONT_OFFICE_SUPERVISOR, true",
        "ASSISTANT_GENERAL_MANAGER, HOUSEKEEPING_SUPERVISOR, true",
        "FRONT_OFFICE_SUPERVISOR, FRONT_DESK, true",
        "FRONT_OFFICE_SUPERVISOR, HOUSEKEEPING_SUPERVISOR, false",
        "FRONT_OFFICE_SUPERVISOR, GENERAL_MANAGER, false",
        "HOUSEKEEPING_SUPERVISOR, HOUSEKEEPING_ATTENDANT, true",
        "HOUSEKEEPING_SUPERVISOR, ASSISTANT_GENERAL_MANAGER, false",
        "HOUSEKEEPING_ATTENDANT, GENERAL_MANAGER, false",
        "FRONT_DESK, FRONT_OFFICE_SUPERVISOR, false",
        "SERVICE_STEWARD, GENERAL_MANAGER, false",
        "NIGHT_SERVICE_ATTENDANT, FRONT_OFFICE_SUPERVISOR, false",
        "BREAKFAST_AREA_LEAD, GENERAL_MANAGER, false",
        "OTA_OPERATION_MANAGER, OTA_OPERATION_ASSISTANT, true",
        "OTA_OPERATION_ASSISTANT, OTA_OPERATION_MANAGER, false",
        "HR_ADMINISTRATION_SUPERVISOR, HR_ADMINISTRATION, true",
        "HR_ADMINISTRATION, GROUP_GENERAL_MANAGER, false",
        "GROUP_GENERAL_MANAGER, GROUP_VICE_PRESIDENT, true",
        "GROUP_VICE_PRESIDENT, GROUP_GENERAL_MANAGER, false",
        "GROUP_CHAIRMAN, GROUP_GENERAL_MANAGER, true"
    })
    void roleMatrixDoesNotTurnHotelScopeIntoSuperiorOrPeerAccess(String viewerCode, String targetCode, boolean visible) {
        UUID account = UUID.randomUUID();
        UUID viewer = actor(account, HOTEL, viewerCode, true);
        jdbc.update("insert into employee_assignment_hotel_scope (tenant_id, assignment_id, hotel_org_unit_id) values (?, ?, ?) on conflict do nothing",
                TENANT, viewer, HOTEL);
        UUID target = actor(UUID.randomUUID(), HOTEL, targetCode, true);
        UUID targetWork = expectation(target, HOTEL);
        UUID targetRecord = draftRecord(target, HOTEL, targetWork);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), viewer));
        if (visible) {
            assertThat(work.expectationDetail(targetWork).get("id")).isEqualTo(targetWork);
            assertThat(records.record(targetRecord).get("id")).isEqualTo(targetRecord);
            assertThat(attachments.list(targetRecord)).isEmpty();
        } else {
            assertThatThrownBy(() -> work.expectationDetail(targetWork)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> records.record(targetRecord)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> attachments.list(targetRecord)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void personalWorkIncludesExplicitSharedWorkButNeverAnotherSelectedAssignment() {
        UUID account = UUID.randomUUID();
        UUID assistant = actor(account, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID other = actor(account, OTHER_HOTEL, "ASSISTANT_GENERAL_MANAGER", false);
        UUID shared = expectation(MANAGER, HOTEL, SHARED_ITEM);
        UUID otherWork = expectation(other, OTHER_HOTEL);
        UUID record = draftRecord(MANAGER, HOTEL, shared);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), assistant));
        assertThat(work.expectations(null, null, null, null, null, null, true, 0, 200))
                .extracting(row -> row.get("id")).contains(shared).doesNotContain(otherWork);
        assertThat(work.expectationDetail(shared).get("id")).isEqualTo(shared);
        assertThat(records.record(record).get("id")).isEqualTo(record);
        assertThat(attachments.list(record)).isEmpty();
        assertThatThrownBy(() -> work.expectationDetail(otherWork)).isInstanceOf(AccessDeniedException.class);
        JsonNode summary = mapper.valueToTree(work.teamWorkbenchSummary(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
        assertThat(summary.findValuesAsText("assignmentId")).doesNotContain(MANAGER.toString());
        LocalDate fixtureDay = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        JsonNode ownBoard = mapper.valueToTree(boards.board(true, null, null, null,
                fixtureDay, fixtureDay, null, null, false, 0, 200));
        assertThat(ownBoard.path("items").findValuesAsText("id")).contains(shared.toString())
                .doesNotContain(otherWork.toString());
        JsonNode teamBoard = mapper.valueToTree(boards.board(false, HOTEL, null, null,
                fixtureDay, fixtureDay, null, null, false, 0, 200));
        assertThat(teamBoard.path("groups").findValuesAsText("group_id")).doesNotContain(MANAGER.toString());
    }

    @Test
    void explicitReportingLinksAllowReadOnlySupervisionIncludingHistoricalWork() {
        UUID account = UUID.randomUUID();
        UUID leader = actor(account, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID direct = actor(UUID.randomUUID(), HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID indirect = actor(UUID.randomUUID(), HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID outside = actor(UUID.randomUUID(), OTHER_HOTEL, "HOUSEKEEPING_ATTENDANT", true);
        UUID directWork = expectation(direct, HOTEL), indirectWork = expectation(indirect, HOTEL);
        UUID outsideWork = expectation(outside, OTHER_HOTEL);
        jdbc.update("update employee_position_assignment set manager_assignment_id=? where id=?", leader, direct);
        jdbc.update("""
                insert into position_assignment_reporting_relation
                  (tenant_id,subordinate_assignment_id,leader_assignment_id,valid_from)
                values (?,?,?,current_date-1)
                """, TENANT, indirect, leader);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), leader));
        assertThat(work.expectationDetail(directWork).get("id")).isEqualTo(directWork);
        assertThat(work.expectationDetail(indirectWork).get("id")).isEqualTo(indirectWork);
        assertThatThrownBy(() -> work.expectationDetail(outsideWork)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("update employee_position_assignment set status='INACTIVE',valid_to=current_date where id=?", direct);
        assertThat(work.expectationDetail(directWork).get("id")).isEqualTo(directWork);
        jdbc.update("update position_assignment_reporting_relation set valid_to=current_date-1 where subordinate_assignment_id=?", indirect);
        assertThatThrownBy(() -> work.expectationDetail(indirectWork)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void roleDefaultsCannotOverrideAnotherLeaderOrRevokedReportingLinks() {
        UUID accountA = UUID.randomUUID(), accountB = UUID.randomUUID();
        UUID leaderA = actor(accountA, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID leaderB = actor(accountB, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID employee = actor(UUID.randomUUID(), HOTEL, "FRONT_DESK", true);
        UUID employeeWork = expectation(employee, HOTEL);
        jdbc.update("update employee_position_assignment set manager_assignment_id=? where id=?", leaderB, employee);
        TenantContext.set(identities.resolve(TENANT, accountB, UUID.randomUUID(), leaderB));
        assertThat(work.expectationDetail(employeeWork).get("id")).isEqualTo(employeeWork);
        TenantContext.set(identities.resolve(TENANT, accountA, UUID.randomUUID(), leaderA));
        assertThatThrownBy(() -> work.expectationDetail(employeeWork)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("""
                insert into position_assignment_reporting_relation
                  (tenant_id,subordinate_assignment_id,leader_assignment_id,valid_from)
                values (?,?,?,current_date-1)
                """, TENANT, employee, leaderA);
        assertThat(work.expectationDetail(employeeWork).get("id")).isEqualTo(employeeWork);
        jdbc.update("update position_assignment_reporting_relation set valid_to=current_date-1 where subordinate_assignment_id=?", employee);
        assertThatThrownBy(() -> work.expectationDetail(employeeWork)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void projectedWorkInDailyReportsHasTheSameOwnerAndSubordinateBoundary() {
        UUID account = UUID.randomUUID();
        UUID assistant = actor(account, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID managerRecord = draftRecord(MANAGER, HOTEL, expectation(MANAGER, HOTEL));
        UUID managerReport = projectedReport(MANAGER, managerRecord);
        UUID assistantRecord = draftRecord(assistant, HOTEL, expectation(assistant, HOTEL));
        UUID assistantReport = projectedReport(assistant, assistantRecord);
        UUID managerAccount = jdbc.queryForObject("""
                select e.account_id from employee_position_assignment a
                join employee e on e.id=a.employee_id and e.tenant_id=a.tenant_id where a.id=?
                """, UUID.class, MANAGER);
        TenantContext.set(identities.resolve(TENANT, managerAccount, UUID.randomUUID(), MANAGER));
        assertThat(mapper.valueToTree(reports.detail(managerReport)).path("itemResults").get(0).path("value").has("formData")).isTrue();
        assertThat(reports.detail(assistantReport).get("id")).isEqualTo(assistantReport);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), assistant));
        assertThat(reports.detail(assistantReport).get("id")).isEqualTo(assistantReport);
        assertThatThrownBy(() -> records.record(managerRecord)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reports.detail(managerReport)).isInstanceOf(AccessDeniedException.class);
        assertThat(reports.teamReports(HOTEL, null, null)).extracting(row -> row.get("id"))
                .contains(assistantReport).doesNotContain(managerReport);
    }

    private UUID projectedReport(UUID assignment, UUID record) {
        UUID report = UUID.randomUUID(), revision = UUID.randomUUID();
        var template = jdbc.queryForMap("""
                select v.id, item.id as item_id from daily_report_template_version v
                join daily_report_template_section section on section.tenant_id=v.tenant_id and section.template_version_id=v.id
                join daily_report_template_item item on item.tenant_id=section.tenant_id and item.section_version_id=section.section_version_id
                where v.tenant_id=? and v.lifecycle_status='PUBLISHED' limit 1
                """, TENANT);
        jdbc.update("""
                insert into daily_report(id,tenant_id,hotel_org_unit_id,org_unit_id,employee_id,position_assignment_id,
                  business_date,timezone,cutoff_local_time,template_version_id,work_package_version_id,trace_id,created_by_account_id,current_revision_id)
                select ?,a.tenant_id,?,?,e.id,a.id,current_date,'Asia/Shanghai','00:00',?,?,?,?,?
                from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                where a.id=?
                """, report, HOTEL, HOTEL, template.get("id"), VERSION, UUID.randomUUID(),
                jdbc.queryForObject("select e.account_id from employee e join employee_position_assignment a on a.employee_id=e.id where a.id=?", UUID.class, assignment),
                revision, assignment);
        jdbc.update("""
                insert into daily_report_revision(id,tenant_id,report_id,revision_no,created_by_account_id,payload_snapshot)
                select ?,tenant_id,id,1,created_by_account_id,
                  '{"resolvedTemplate":{"sections":[{"items":[{"itemCode":"TEST"}]}]}}'::jsonb
                from daily_report where id=?
                """, revision, report);
        // Same stored representation produced by DailyReportRoutineProjectionService.
        jdbc.update("""
                insert into daily_report_item_result(tenant_id,revision_id,template_item_id,value,system_prefilled)
                select tenant_id,?,?,jsonb_build_object('formData',payload),true from work_record where id=?
                """, revision, template.get("item_id"), record);
        return report;
    }

    @Test
    void delegationGrantsOnlyThatWorkAndItsEvidenceNotOtherWorkByTheOwner() {
        UUID account = UUID.randomUUID();
        UUID assistant = actor(account, HOTEL, "ASSISTANT_GENERAL_MANAGER", true);
        UUID delegated = expectation(MANAGER, HOTEL), privateWork = expectation(MANAGER, HOTEL);
        UUID record = draftRecord(MANAGER, HOTEL, delegated);
        jdbc.update("""
                insert into work_expectation_delegation
                  (tenant_id,work_expectation_id,delegate_assignment_id,reason,delegated_by_account_id)
                values (?,?,?, '测试转交',?)
                """, TENANT, delegated, assistant, account);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), assistant));
        assertThat(work.expectations(null,null,null,null,null,null,true,0,200))
                .extracting(row -> row.get("id")).contains(delegated).doesNotContain(privateWork);
        assertThat(records.record(record).get("id")).isEqualTo(record);
        assertThat(attachments.list(record)).isEmpty();
        assertThatThrownBy(() -> work.expectationDetail(privateWork)).isInstanceOf(AccessDeniedException.class);
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

    @ParameterizedTest
    @CsvSource({"GENERAL_MANAGER,ASSISTANT_GENERAL_MANAGER", "GENERAL_MANAGER,HOUSEKEEPING_ATTENDANT", "GROUP_VICE_PRESIDENT,GENERAL_MANAGER"})
    void managersReviewHistoricalOrdinaryWorkWithoutLegacyReportingLink(String reviewerRole, String employeeRole) {
        UUID account = UUID.randomUUID(), employeeAccount = UUID.randomUUID();
        UUID reviewer = actor(account, reviewerRole.equals("GROUP_VICE_PRESIDENT") ? GROUP : HOTEL, reviewerRole, true);
        UUID employee = actor(employeeAccount, HOTEL, employeeRole, true);
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)", TENANT, reviewer, HOTEL);
        UUID expected = expectation(employee, HOTEL);
        UUID record = draftRecord(employee, HOTEL, expected);
        jdbc.update("update work_record set status='SUBMITTED',submitted_by_account_id=?,submitted_at=now(),business_date=current_date-3 where id=?", employeeAccount, record);
        jdbc.update("update work_expectation set status='SUBMITTED' where id=?", expected);
        var identity = identities.resolve(TENANT, account, UUID.randomUUID(), reviewer);
        TenantContext.set(identity);
        assertThat(identity.hasPermission("work-record.review")).isTrue();
        assertThat(records.record(record).get("id")).isEqualTo(record);
        assertThat(records.reviewEligibility(record)).containsEntry("canReview", true);
        assertThat(records.review(record, new cn.sifangguan.hotelaios.workdata.WorkDataModels.ReviewRecord("APPROVED", "已核对证据", 0)))
                .containsEntry("status", "APPROVED");
        assertThat(records.reviewEligibility(record)).containsEntry("canReview", false);
        assertThatThrownBy(() -> records.review(record, new cn.sifangguan.hotelaios.workdata.WorkDataModels.ReviewRecord("APPROVED", "重复", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id=? and action='WORK_RECORD_APPROVED'", Integer.class, record)).isEqualTo(1);
    }

    @Test
    void ordinaryReviewPolicyRejectsPeerSelfWrongSelectedAssignmentAndRevokedPortfolio() {
        UUID account = UUID.randomUUID();
        UUID vp = actor(account, GROUP, "GROUP_VICE_PRESIDENT", true);
        UUID ownOther = actor(account, OTHER_HOTEL, "FRONT_DESK", false);
        UUID manager = actor(UUID.randomUUID(), HOTEL, "GENERAL_MANAGER", true);
        var policy = new cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)", TENANT, vp, HOTEL);
        var identity = identities.resolve(TENANT, account, UUID.randomUUID(), vp);
        assertThat(policy.canManage(identity, manager, HOTEL)).isTrue();
        assertThat(policy.canManage(identity, vp, HOTEL)).isFalse();
        assertThat(policy.canManage(identity, ownOther, OTHER_HOTEL)).isFalse();
        assertThat(policy.canManage(identities.resolve(TENANT, account, UUID.randomUUID(), ownOther), manager, HOTEL)).isFalse();
        UUID peer = actor(UUID.randomUUID(), HOTEL, "GROUP_VICE_PRESIDENT", true);
        assertThat(policy.canManage(identity, peer, HOTEL)).isFalse();
        jdbc.update("delete from employee_assignment_hotel_scope where assignment_id=?", vp);
        assertThat(policy.canManage(identity, manager, HOTEL)).isFalse();
        jdbc.update("update employee_position_assignment set status='INACTIVE',valid_to=current_date where id=?", vp);
        assertThat(policy.canManage(identity, manager, HOTEL)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"GENERAL_MANAGER,ASSISTANT_GENERAL_MANAGER", "GENERAL_MANAGER,HOUSEKEEPING_ATTENDANT", "GROUP_VICE_PRESIDENT,GENERAL_MANAGER"})
    void ordinaryDailyReportReviewUsesTheSameManagedHotelBoundary(String reviewerRole, String employeeRole) {
        UUID account = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID reviewer = actor(account, reviewerRole.equals("GROUP_VICE_PRESIDENT") ? GROUP : HOTEL, reviewerRole, true);
        UUID employee = actor(owner, HOTEL, employeeRole, true);
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)", TENANT, reviewer, HOTEL);
        UUID report = projectedReport(employee, draftRecord(employee, HOTEL, expectation(employee, HOTEL)));
        jdbc.update("update daily_report set report_status='SUBMITTED',review_status='PENDING',submitted_at=now() where id=?", report);
        jdbc.update("update daily_report_revision set revision_status='SUBMITTED',content_hash=repeat('a',64),submitted_at=now(),submitted_by_account_id=? where report_id=?", owner, report);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), reviewer));
        assertThat(mapper.valueToTree(reports.detail(report)).path("reviewEligibility").path("canReview").asBoolean()).isTrue();
        assertThat(reports.review(report, new cn.sifangguan.hotelaios.dailyreports.DailyReportModels.Review("APPROVED", "已核对日报", reviewer, 0), "review-" + report))
                .containsEntry("reviewStatus", "APPROVED");
        assertThat(mapper.valueToTree(reports.detail(report)).path("reviewEligibility").path("canReview").asBoolean()).isFalse();
    }

    @Test
    void workReviewWriteEndpointRejectsUnassignedHotelsAndRevokedPermission() {
        UUID account = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID reviewer = actor(account, GROUP, "GROUP_VICE_PRESIDENT", true);
        UUID employee = actor(owner, HOTEL, "GENERAL_MANAGER", true);
        UUID record = draftRecord(employee, HOTEL, expectation(employee, HOTEL));
        jdbc.update("update work_record set status='SUBMITTED',submitted_by_account_id=?,submitted_at=now() where id=?", owner, record);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), reviewer));
        var review = new cn.sifangguan.hotelaios.workdata.WorkDataModels.ReviewRecord("APPROVED", "越权检查", 0);
        assertThatThrownBy(() -> records.review(record, review)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)", TENANT, reviewer, HOTEL);
        jdbc.update("""
                delete from position_function_profile_permission grant_row using position_function_profile_version version,
                  position_function_profile profile,position_definition position,permission permission
                where grant_row.profile_version_id=version.id and version.profile_id=profile.id and profile.position_id=position.id
                  and grant_row.permission_id=permission.id and permission.code='work-record.review'
                  and position.tenant_id=? and position.code='GROUP_VICE_PRESIDENT' and version.lifecycle_status='PUBLISHED'
                """, TENANT);
        TenantContext.set(identities.resolve(TENANT, account, UUID.randomUUID(), reviewer));
        assertThat(records.reviewEligibility(record)).containsEntry("canReview", false);
        assertThatThrownBy(() -> records.review(record, review)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select status from work_record where id=?", String.class, record)).isEqualTo("SUBMITTED");
    }

    @Test
    void fallbackManagerReceivesReviewNotificationAndWecomReportRemindersDiscardStaleRevisions() {
        UUID owner = UUID.randomUUID();
        UUID employee = actor(owner, HOTEL, "HOUSEKEEPING_ATTENDANT", true);
        UUID record = draftRecord(employee, HOTEL, expectation(employee, HOTEL));
        jdbc.update("update work_record set status='SUBMITTED',submitted_by_account_id=?,submitted_at=now() where id=?", owner, record);
        TenantContext.set(identities.resolve(TENANT, owner, UUID.randomUUID(), employee));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(records, "notifySubmittedForReview", TenantContext.require(), record);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(records, "notifySubmittedForReview", TenantContext.require(), record);
        assertThat(jdbc.queryForObject("select count(*) from notification where source_id=? and recipient_assignment_id=? and notification_type='WORK_RECORD_REVIEW_PENDING'", Integer.class, record, MANAGER)).isOne();
        UUID managerAccount = jdbc.queryForObject("select e.account_id from employee e join employee_position_assignment a on a.employee_id=e.id where a.id=?", UUID.class, MANAGER);
        UUID report = projectedReport(employee, record);
        jdbc.update("update daily_report set report_status='SUBMITTED',review_status='PENDING',submitted_at=now() where id=?", report);
        jdbc.update("update daily_report_revision set revision_status='SUBMITTED',content_hash=repeat('a',64),submitted_at=now(),submitted_by_account_id=? where report_id=?", owner, report);
        UUID revision = jdbc.queryForObject("select current_revision_id from daily_report where id=?", UUID.class, report);
        UUID currentNotice = UUID.randomUUID(), staleNotice = UUID.randomUUID();
        for (UUID notice : List.of(currentNotice, staleNotice)) jdbc.update("""
                insert into notification(id,tenant_id,recipient_account_id,recipient_assignment_id,notification_type,title,content,source_type,source_id,idempotency_key)
                values(?,?,?,?,'DAILY_REPORT_REVIEW_PENDING','本地审核测试','本地审核测试','DAILY_REPORT',?,?)
                """, notice,TENANT,managerAccount,MANAGER,report,"report-review:"+report+":"+(notice.equals(currentNotice)?revision:UUID.randomUUID()));
        jdbc.update("""
                insert into wecom_user_binding(tenant_id,corp_id,wecom_user_id,account_id,preferred_assignment_id,user_id_fingerprint)
                values(?,'qa-review-corp','qa-member',?,?,repeat('b',64))
                """,TENANT,managerAccount,MANAGER);
        var properties = org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComProperties.class);
        org.mockito.Mockito.when(properties.tenantId()).thenReturn(TENANT);
        org.mockito.Mockito.when(properties.corpId()).thenReturn("qa-review-corp");
        var worker = new cn.sifangguan.hotelaios.integrations.wecom.WeComDeliveryWorker(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                new cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext(jdbc,true),
                new org.springframework.transaction.support.TransactionTemplate(transactions),
                org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComApiClient.class),properties,200);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker,"enqueueTaskNotifications");
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery where notification_id=?",Integer.class,currentNotice)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery where notification_id=?",Integer.class,staleNotice)).isZero();
        jdbc.update("update daily_report set review_status='APPROVED' where id=?", report);
        List<?> claimed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker,"claim");
        assertThat(claimed).extracting("sourceId").doesNotContain(report);
    }

    private UUID expectation(UUID assignment, UUID org) {
        return expectation(assignment, org, ITEM);
    }

    private UUID expectation(UUID assignment, UUID org, UUID itemId) {
        UUID expectation = UUID.randomUUID();
        UUID allocation = jdbc.queryForObject("""
                insert into work_package_allocation
                  (id, tenant_id, work_package_version_id, position_assignment_id, target_org_unit_id, valid_from)
                values (?, ?, ?, ?, ?, current_date)
                on conflict (tenant_id, work_package_version_id, position_assignment_id, target_org_unit_id, valid_from)
                do update set status = work_package_allocation.status
                returning id
                """, UUID.class, UUID.randomUUID(), TENANT, VERSION, assignment, org);
        jdbc.update("""
                insert into work_expectation
                  (id, tenant_id, work_package_item_id, work_package_allocation_id, position_assignment_id,
                   target_org_unit_id, business_date, period_key, available_at, due_at, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, now() - interval '2 hours', now() - interval '1 hour', 'MISSED')
                """, expectation, TENANT, itemId, allocation, assignment, org,
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
                insert into work_package_item
                  (id, tenant_id, work_package_version_id, item_code, name, item_type, period_type,
                   timezone_mode, due_local_time, form_version_id, execution_policy)
                values (?, ?, ?, 'SHARED_TEST', '共享工作', 'INSPECTION', 'DAY', 'TENANT', '23:59', ?,
                  '{"allowedPositionCodes":["ASSISTANT_GENERAL_MANAGER"],"sharedKey":"TEST-SHARED"}'::jsonb)
                """, SHARED_ITEM, TENANT, VERSION, FORM_VERSION);
        jdbc.update("insert into work_package_item_responsibility(tenant_id,work_package_item_id,participant_type,resolver_type) values(?,?,'REVIEWER','DIRECT_MANAGER_ASSIGNMENT')", TENANT, ITEM);
        jdbc.update("""
                update work_package_version set lifecycle_status = 'PUBLISHED', content_hash = repeat('a',64),
                  effective_from = now() - interval '1 day', published_at = now()
                where tenant_id = ? and id = ?
                """, TENANT, VERSION);
    }

    private UUID draftRecord(UUID assignment, UUID target, UUID expectation) {
        UUID record = UUID.randomUUID();
        jdbc.update("""
                insert into work_record (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                  form_version_id, business_date, target_org_unit_id, work_package_version_id,
                  work_package_item_id, work_expectation_id, record_kind, content_hash, occurred_at)
                select ?, tenant_id, org_unit_id, employee_id, id, ?, current_date, ?, ?, ?, ?, 'SCHEDULED', repeat('a',64), now()
                from employee_position_assignment where tenant_id = ? and id = ?
                """, record, FORM_VERSION, target, VERSION,
                jdbc.queryForObject("select work_package_item_id from work_expectation where id=?", UUID.class, expectation),
                expectation, TENANT, assignment);
        return record;
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
