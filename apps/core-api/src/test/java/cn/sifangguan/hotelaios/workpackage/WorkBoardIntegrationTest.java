package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkBoardIntegrationTest {
    static final String TENANT = "10000000-0000-0000-0000-000000000001";
    static final String CEO = "19000000-0000-0000-0000-000000000001";
    static final String MANAGER = "19000000-0000-0000-0000-000000000002";
    static final String ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    static final String EMPLOYEE = "19100000-0000-0000-0000-000000000001";
    static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    static final String OTHER_HOTEL = "12000000-0000-0000-0000-000000000004";
    static final String ITEM = "47050000-0000-0000-0000-000000000002";
    static final EmbeddedPostgres POSTGRES = postgres();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkExpectationSlaService sla;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "postgres");
        r.add("app.security.development-header-auth-enabled", () -> true);
        r.add("app.work-expectation.sla.scheduler-enabled", () -> false);
        r.add("app.database.rls-enabled", () -> true);
    }
    @AfterAll static void close() throws Exception { POSTGRES.close(); }
    static EmbeddedPostgres postgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    JsonNode getBoard(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/team/work-board" + query)
                .header("X-Tenant-Id", TENANT).header("X-Actor-Id", CEO))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    UUID expectation(LocalDate day, String state, int dueHours) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation (id, tenant_id, work_package_item_id, work_package_allocation_id,
                  position_assignment_id, target_org_unit_id, business_date, period_key, available_at, due_at, status)
                select ?, ?::uuid, ?::uuid, allocation.id, ?::uuid, ?::uuid, ?, ?,
                  now() - interval '2 days', now() + (? * interval '1 hour'), ?
                from work_package_allocation allocation
                where allocation.tenant_id = ?::uuid and allocation.position_assignment_id = ?::uuid
                  and allocation.work_package_version_id = '47030000-0000-0000-0000-000000000001'::uuid
                limit 1
                """, id, TENANT, ITEM, ASSIGNMENT, HOTEL, day, "board-" + id, dueHours, state, TENANT, ASSIGNMENT);
        return id;
    }
    void record(UUID expectation, LocalDate day, String recordStatus, int submittedHours) {
        jdbc.update("""
                insert into work_record (id,tenant_id,org_unit_id,employee_id,position_assignment_id,
                 form_version_id,business_date,status,payload,submitted_at,work_package_version_id,
                 work_package_item_id,work_expectation_id,record_kind,target_org_unit_id,occurred_at,
                 submitted_by_account_id,attempt_no,content_hash,completion_statement)
                select ?,x.tenant_id,x.target_org_unit_id,a.employee_id,a.id,i.form_version_id,
                  ?,?,'{}'::jsonb,now() + (? * interval '1 hour'),i.work_package_version_id,
                  i.id,x.id,'INSPECTION',x.target_org_unit_id,now(),?::uuid,1,repeat('b',64),'看板测试'
                from work_expectation x join work_package_item i on i.id=x.work_package_item_id
                join employee_position_assignment a on a.id=x.position_assignment_id where x.id=?
                """, UUID.randomUUID(), day, recordStatus, submittedHours, MANAGER, expectation);
    }

    UUID variantExpectation(LocalDate day, String state, String period, boolean repeatable) {
        UUID version = UUID.randomUUID(), item = UUID.randomUUID(), allocation = UUID.randomUUID();
        // Fixtures follow draft -> publish; never bypass immutable published-version guards.
        jdbc.update("""
                insert into work_package_version
                select (jsonb_populate_record(null::work_package_version, to_jsonb(v) ||
                  jsonb_build_object('id',cast(? as text),'version_no',
                    (select max(version_no)+1 from work_package_version where work_package_definition_id=v.work_package_definition_id),
                    'lifecycle_status','DRAFT','published_at',null,'published_by',null))).*
                from work_package_version v where v.id='47030000-0000-0000-0000-000000000001'::uuid
                """, version.toString());
        jdbc.update("""
                insert into work_package_item
                select (jsonb_populate_record(null::work_package_item, to_jsonb(i) ||
                  jsonb_build_object('id',cast(? as text),'work_package_version_id',cast(? as text),
                    'period_type',cast(? as text),'required',not cast(? as boolean),'execution_policy',
                    (i.execution_policy - 'allowedPositionCodes') || jsonb_build_object('repeatableDailyRecords',cast(? as boolean),
                      'registrationMode',case when cast(? as boolean) then 'WHEN_OCCURRED' else 'SCHEDULED' end)))).*
                from work_package_item i where i.id=?::uuid
                """, item.toString(), version.toString(), period, repeatable, repeatable, repeatable, ITEM);
        jdbc.update("update work_package_version set lifecycle_status='PUBLISHED',published_at=now() where id=?", version);
        jdbc.update("""
                insert into work_package_allocation
                select (jsonb_populate_record(null::work_package_allocation,to_jsonb(a) ||
                  jsonb_build_object('id',cast(? as text),'work_package_version_id',cast(? as text)))).*
                from work_package_allocation a where a.position_assignment_id=?::uuid
                  and a.work_package_version_id='47030000-0000-0000-0000-000000000001'::uuid limit 1
                """, allocation.toString(), version.toString(), ASSIGNMENT);
        UUID id = expectation(day, state, -2);
        jdbc.update("update work_expectation set work_package_item_id=?,work_package_allocation_id=? where id=?", item, allocation, id);
        return id;
    }

    @Test void fullScopeSummaryIsNotTruncatedByPaginationAndReviewIsNotCompletion() throws Exception {
        LocalDate day = LocalDate.of(2025, 1, 17);
        for (int i = 0; i < 51; i++) expectation(day, "AVAILABLE", 2);
        expectation(day, "MISSED", -2);
        UUID review = expectation(day, "SUBMITTED", -2);
        record(review, day, "SUBMITTED", -1);
        UUID complete = expectation(day, "SATISFIED", -2);
        record(complete, day, "APPROVED", -3);
        JsonNode result = getBoard("?from=" + day + "&to=" + day + "&orgUnitId=" + HOTEL + "&size=10");
        JsonNode m = result.path("period");
        assertThat(result.path("items").size()).isEqualTo(10);
        assertThat(m.path("expected").asInt()).isEqualTo(54);
        assertThat(m.path("pending").asInt()).isEqualTo(51);
        assertThat(m.path("overdue").asInt()).isEqualTo(1);
        assertThat(m.path("review").asInt()).isEqualTo(1);
        assertThat(m.path("completed").asInt()).isEqualTo(1);
        assertThat(m.path("submitted").asInt()).isEqualTo(2);
        assertThat(m.path("on_time_completed").asInt()).isEqualTo(1);
        assertThat(m.path("late").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(review.toString());
        assertThat(result.path("recentSubmissions").size()).isBetween(2,5);
        assertThat(result.path("recentSubmissions").get(0).path("id").asText()).isEqualTo(review.toString());
        JsonNode lastPage = getBoard("?from=" + day + "&to=" + day + "&orgUnitId=" + HOTEL + "&size=10&page=5");
        assertThat(lastPage.path("items").size()).isEqualTo(4);
        assertThat(lastPage.path("filtered").path("item_count").asInt()).isEqualTo(54);
        assertThat(getBoard("?from=" + day + "&to=" + day + "&lateOnly=true")
                .path("items").get(0).path("board_status").asText()).isEqualTo("REVIEW");
    }

    @Test void oldDailyMissesStayHistoricalButLateReviewsRemainDiscoverable() throws Exception {
        LocalDate old = LocalDate.of(2024, 12, 14);
        UUID missed = expectation(old, "MISSED", -2);
        UUID submitted = expectation(old, "SUBMITTED", -2);
        record(submitted, old, "SUBMITTED", -1);
        UUID approved = expectation(old, "SATISFIED", -2);
        record(approved, old, "APPROVED", -1);
        JsonNode today = getBoard("");
        assertThat(today.path("items").findValuesAsText("id")).doesNotContain(missed.toString(), submitted.toString());
        assertThat(today.path("recentSubmissions").findValuesAsText("id")).contains(submitted.toString(), approved.toString());
        JsonNode queue = getBoard("?queue=REVIEW");
        assertThat(queue.path("items").findValuesAsText("id")).contains(submitted.toString()).doesNotContain(missed.toString());
        assertThat(getBoard("?from=" + old + "&to=" + old).path("items").findValuesAsText("id"))
                .contains(missed.toString(), submitted.toString());
    }

    @Test void emptyRangeHasNoFakeHundredPercentAndInvalidRangesAreRejected() throws Exception {
        JsonNode empty = getBoard("?from=2000-01-01&to=2000-01-01");
        assertThat(empty.path("period").path("expected").asInt()).isZero();
        assertThat(empty.path("period").path("completed_rate").isNull()
                || empty.path("period").path("completed_rate").isMissingNode()).isTrue();
        for (String query : new String[]{"?from=2026-09-20&to=2026-09-01", "?from=2024-01-01&to=2026-01-01", "?size=201", "?status=HACK"}) {
            mvc.perform(get("/api/v1/team/work-board" + query).header("X-Tenant-Id", TENANT).header("X-Actor-Id", CEO))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void organizationScopeCannotBeExpandedByHotelFilter() throws Exception {
        mvc.perform(get("/api/v1/team/work-board?orgUnitId=" + OTHER_HOTEL)
                        .header("X-Tenant-Id", TENANT).header("X-Actor-Id", MANAGER).header("X-Assignment-Id", ASSIGNMENT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/my/work-board")
                        .header("X-Tenant-Id", TENANT).header("X-Actor-Id", MANAGER).header("X-Assignment-Id", ASSIGNMENT))
                .andExpect(status().isOk());
    }

    @Test void continuingWorkBeforeThisMonthRemainsInItsOwnQueue() throws Exception {
        UUID oldWork = variantExpectation(LocalDate.of(2023, 11, 20), "MISSED", "WEEK", false);
        JsonNode result = getBoard("?queue=CARRYOVER&size=200");
        assertThat(result.path("items").findValuesAsText("id")).contains(oldWork.toString());
        JsonNode current = getBoard("");
        assertThat(result.path("period")).isEqualTo(current.path("period"));
        assertThat(current.path("items").findValuesAsText("id")).doesNotContain(oldWork.toString());
        assertThat(result.path("carryover").path("expected").asInt()).isPositive();
    }

    @Test void repeatableRecordsNeverInflateRequiredRateAndOldPendingReviewsAreKept() throws Exception {
        LocalDate day = LocalDate.of(2023, 10, 20);
        UUID optional = variantExpectation(day, "SATISFIED", "DAY", true);
        record(optional, day, "SUBMITTED", -1);
        JsonNode result = getBoard("?from=" + day + "&to=" + day);
        assertThat(result.path("period").path("expected").asInt()).isZero();
        assertThat(result.path("period").path("optional_records").asInt()).isEqualTo(1);
        assertThat(result.path("period").path("optional_entries").asInt()).isEqualTo(1);
        assertThat(result.path("period").path("pending_review_records").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("board_status").asText()).isEqualTo("REVIEW");
        assertThat(getBoard("?queue=REVIEW&size=200").path("items").findValuesAsText("id")).contains(optional.toString());
    }

    @Test void waitingForReviewDoesNotReduceOnTimeSubmissionRate() throws Exception {
        LocalDate day = LocalDate.of(2025, 2, 10);
        UUID id = expectation(day, "SUBMITTED", 2);
        record(id, day, "SUBMITTED", -1);
        JsonNode m = getBoard("?from=" + day + "&to=" + day).path("period");
        assertThat(m.path("review").asInt()).isEqualTo(1);
        assertThat(m.path("completed").asInt()).isZero();
        assertThat(m.path("on_time_submitted_rate").asInt()).isEqualTo(100);
        assertThat(m.path("on_time_completed_rate").asInt()).isZero();
    }

    @Test void unrecordedOccurrenceIsNeverMissedOverdueOrInTheRateDenominator() throws Exception {
        LocalDate day=LocalDate.of(2024,2,6);
        UUID optional=variantExpectation(day,"AVAILABLE","DAY",true);
        sla.processTenantAsSystem(UUID.fromString(TENANT),100,UUID.randomUUID());
        assertThat(jdbc.queryForObject("select status from work_expectation where id=?",String.class,optional)).isEqualTo("AVAILABLE");
        JsonNode board=getBoard("?from="+day+"&to="+day);
        assertThat(board.path("period").path("expected").asInt()).isZero();
        assertThat(board.path("period").path("overdue").asInt()).isZero();
        assertThat(board.path("period").path("completed_rate").isMissingNode()).isTrue(); // NON_NULL API serialization.
        assertThat(board.path("items").get(0).path("board_status").asText()).isEqualTo("PENDING");
    }

    @Test void personalBoardUsesSelectedAssignmentInsteadOfAllJobsOnTheAccount() throws Exception {
        LocalDate day = LocalDate.now();
        UUID ownWork = variantExpectation(day, "AVAILABLE", "DAY", false);
        UUID otherWork = variantExpectation(day, "AVAILABLE", "DAY", false);
        UUID otherAssignment = UUID.randomUUID();
        jdbc.update("""
                insert into employee_position_assignment (id,tenant_id,employee_id,org_unit_id,position_id,
                  manager_assignment_id,is_primary,assignment_type,valid_from,status)
                select ?,tenant_id,employee_id,org_unit_id,position_id,manager_assignment_id,false,'TEMPORARY',valid_from,'ACTIVE'
                from employee_position_assignment where id=?::uuid
                """, otherAssignment, ASSIGNMENT);
        jdbc.update("update work_expectation set position_assignment_id=? where id=?", otherAssignment, otherWork);
        JsonNode result = json.readTree(mvc.perform(get("/api/v1/my/work-board?from=" + day + "&to=" + day)
                .header("X-Tenant-Id", TENANT).header("X-Actor-Id", MANAGER).header("X-Assignment-Id", ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("items").findValuesAsText("id")).contains(ownWork.toString()).doesNotContain(otherWork.toString());
    }

}
