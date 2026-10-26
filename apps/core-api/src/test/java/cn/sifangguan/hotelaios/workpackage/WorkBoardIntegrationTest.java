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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        record(expectation,day,recordStatus,submittedHours,MANAGER);
    }
    void record(UUID expectation, LocalDate day, String recordStatus, int submittedHours, String actor) {
        record(expectation,day,recordStatus,submittedHours,actor,"{}",null,null);
    }
    void record(UUID expectation, LocalDate day, String recordStatus, int submittedHours, String actor,
                String payload,String exception,OffsetDateTime submittedAt) {
        record(expectation,day,recordStatus,submittedHours,actor,payload,exception,submittedAt,null,null,null);
    }
    void record(UUID expectation, LocalDate day, String recordStatus, int submittedHours, String actor,
                String payload,String exception,OffsetDateTime submittedAt,UUID originalReviewer,OffsetDateTime originallyReviewedAt,String originalReason) {
        jdbc.update("""
                insert into work_record (id,tenant_id,org_unit_id,employee_id,position_assignment_id,
                 form_version_id,business_date,status,payload,submitted_at,work_package_version_id,
                 work_package_item_id,work_expectation_id,record_kind,target_org_unit_id,occurred_at,
                 submitted_by_account_id,attempt_no,content_hash,completion_statement,exception_statement,
                 reviewed_by_account_id,reviewed_at,review_reason)
                select ?,x.tenant_id,x.target_org_unit_id,a.employee_id,a.id,i.form_version_id,
                  ?,?,cast(? as jsonb),coalesce(cast(? as timestamptz),now() + (? * interval '1 hour')),i.work_package_version_id,
                  i.id,x.id,'INSPECTION',x.target_org_unit_id,now(),?::uuid,
                  (select coalesce(max(previous.attempt_no),0)+1 from work_record previous where previous.work_expectation_id=x.id),
                  repeat('b',64),'看板测试',cast(? as text),cast(? as uuid),cast(? as timestamptz),cast(? as text)
                from work_expectation x join work_package_item i on i.id=x.work_package_item_id
                join employee_position_assignment a on a.id=x.position_assignment_id where x.id=?
                """, UUID.randomUUID(), day, recordStatus, payload, submittedAt, submittedHours, actor, exception,
                originalReviewer,originallyReviewedAt,originalReason,expectation);
    }

    UUID variantExpectation(LocalDate day, String state, String period, boolean repeatable) {
        return variantExpectation(day,state,period,repeatable,"MANUAL",null);
    }
    UUID variantExpectation(LocalDate day, String state, String period, boolean repeatable, String reviewMode, String code) {
        return variantExpectation(day,state,period,repeatable,reviewMode,code,code==null?null:"{}");
    }
    UUID variantExpectation(LocalDate day, String state, String period, boolean repeatable, String reviewMode, String code, String submissionPolicy) {
        return variantExpectation(day,state,period,repeatable,reviewMode,code,submissionPolicy,"{}");
    }
    UUID variantExpectation(LocalDate day, String state, String period, boolean repeatable, String reviewMode, String code, String submissionPolicy, String executionExtras) {
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
                    'review_mode',cast(? as text),'item_code',coalesce(cast(? as text),i.item_code),
                    'submission_policy',coalesce(cast(? as jsonb),i.submission_policy),
                    'period_type',cast(? as text),'required',not cast(? as boolean),'execution_policy',
                    (i.execution_policy - 'allowedPositionCodes') || jsonb_build_object('repeatableDailyRecords',cast(? as boolean),
                      'registrationMode',case when cast(? as boolean) then 'WHEN_OCCURRED' else 'SCHEDULED' end) || cast(? as jsonb)))).*
                from work_package_item i where i.id=?::uuid
                """, item.toString(), version.toString(), reviewMode, code, submissionPolicy, period, repeatable, repeatable, repeatable, executionExtras, ITEM);
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

    @Test void exemptFailureWithdrawsOriginalDayOnceAndResubmissionNeedsManualApproval() throws Exception {
        LocalDate day=LocalDate.of(2025,8,11);
        UUID work=variantExpectation(day,"AVAILABLE","DAY",false,"NONE","BOARD_EXEMPT_RECHECK");
        String actor="19000000-0000-0000-0000-000000000003",assignment="19200000-0000-0000-0000-000000000002";
        jdbc.update("update work_expectation set position_assignment_id=?::uuid where id=?",assignment,work);
        Map<String,Object> ctx=jdbc.queryForMap("select i.form_version_id,a.employee_id,a.org_unit_id from work_expectation x join work_package_item i on i.id=x.work_package_item_id join employee_position_assignment a on a.id=x.position_assignment_id where x.id=?",work);
        Map<String,Object> body=Map.of("orgUnitId",ctx.get("org_unit_id"),"employeeId",ctx.get("employee_id"),
                "positionAssignmentId",assignment,"formVersionId",ctx.get("form_version_id"),"businessDate",day,
                "payload",Map.of("expectedAttendance",1,"actualAttendance",1,"appearancePassed",true,"meetingNotes","当班例行检查"),
                "completionStatement","完成本班工作","workExpectationId",work,"targetOrgUnitId",HOTEL);
        JsonNode submitted=workCommand("/api/v1/work-data/records",actor,assignment,body,201);
        UUID record=UUID.fromString(submitted.path("id").asText());
        JsonNode initial=getBoard("?from="+day+"&to="+day);
        assertThat(initial.path("period").path("expected").asInt()).isOne();
        assertThat(initial.path("period").path("completed").asInt()).isOne();
        assertThat(initial.path("period").path("review").asInt()).isZero();
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).contains(work.toString());
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='WORK_RECORD' and source_id=? and notification_type='WORK_RECORD_REVIEW_PENDING'",Integer.class,record)).isZero();
        JsonNode eligibility=json.readTree(mvc.perform(get("/api/v1/work-data/records/"+record+"/review-eligibility")
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",MANAGER).header("X-Assignment-Id",ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(eligibility.path("canReview").asBoolean()).isTrue();
        assertThat(eligibility.path("spotCheck").asBoolean()).isTrue();
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","REJECTED","reason","现场抽查不合格","expectedVersion",0),200);
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("completed").asInt()).isZero();
        assertThat(getBoard("?queue=REPAIR&size=200").path("items").findValuesAsText("id")).contains(work.toString());
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).doesNotContain(work.toString());
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","REJECTED","reason","现场抽查不合格","expectedVersion",0),400);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id=? and action='WORK_RECORD_SPOT_CHECK_FAILED'",Integer.class,record)).isOne();
        JsonNode corrected=workCommand("/api/v1/work-data/records",actor,assignment,body,201);
        JsonNode pending=getBoard("?from="+day+"&to="+day);
        assertThat(pending.path("period").path("expected").asInt()).isOne();
        assertThat(pending.path("period").path("completed").asInt()).isZero();
        assertThat(pending.path("period").path("review").asInt()).isOne();
        assertThat(getBoard("?queue=REVIEW&size=200").path("items").findValuesAsText("id")).contains(work.toString());
        assertThat(getBoard("?queue=REPAIR&size=200").path("items").findValuesAsText("id")).doesNotContain(work.toString());
        workCommand("/api/v1/work-data/records/"+corrected.path("id").asText()+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","APPROVED","reason","人工复验合格","expectedVersion",0),200);
        JsonNode restored=getBoard("?from="+day+"&to="+day);
        assertThat(restored.path("period").path("completed").asInt()).isOne();
        assertThat(restored.path("period").path("review").asInt()).isZero();
        JsonNode historical=json.readTree(mvc.perform(get("/api/v1/work-data/records/"+record+"/review-eligibility")
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",MANAGER).header("X-Assignment-Id",ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historical.path("canReview").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select status from work_record where id=?",String.class,record)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select business_date from work_record where id=?::uuid",LocalDate.class,corrected.path("id").asText())).isEqualTo(day);
        assertThat(getBoard("?from="+day.plusDays(1)+"&to="+day.plusDays(1)).path("period").path("completed").asInt()).isZero();
    }

    @Test void criticalNoneConfigurationRemainsManualAndOwnExemptWorkCannotBeSpotChecked() throws Exception {
        LocalDate day=LocalDate.of(2025,8,12);
        UUID critical=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","HKA_DAILY_REPORT");
        record(critical,day,"SUBMITTED",-1);
        JsonNode board=getBoard("?from="+day+"&to="+day);
        assertThat(board.path("period").path("completed").asInt()).isZero();
        assertThat(board.path("period").path("review").asInt()).isOne();
        UUID inventory=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_CUSTOM_USAGE","{\"inventoryUsage\":true}");
        record(inventory,day,"SUBMITTED",-1);
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("review").asInt()).isEqualTo(2);
        UUID exempt=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_OWN_EXEMPT");
        record(exempt,day,"SUBMITTED",-1);
        UUID record=jdbc.queryForObject("select id from work_record where work_expectation_id=?",UUID.class,exempt);
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","REJECTED","reason","不能审核本人","expectedVersion",0),403);
        assertThat(jdbc.queryForObject("select status from work_record where id=?",String.class,record)).isEqualTo("SUBMITTED");
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("completed").asInt()).isOne();
    }

    @Test void previouslyApprovedExemptRecordCanBeFailedByAuthorizedManagerWithoutNewCompletion() throws Exception {
        LocalDate day=LocalDate.of(2025,8,13);
        UUID work=variantExpectation(day,"SATISFIED","DAY",false,"NONE","BOARD_APPROVED_EXEMPT");
        jdbc.update("update work_expectation set position_assignment_id='19200000-0000-0000-0000-000000000002'::uuid where id=?",work);
        record(work,day,"APPROVED",-1,"19000000-0000-0000-0000-000000000003","{}",null,
                OffsetDateTime.parse("2025-08-13T09:00:00+08:00"),UUID.fromString(MANAGER),OffsetDateTime.parse("2025-08-13T10:00:00+08:00"),"原人工验收通过");
        UUID record=jdbc.queryForObject("select id from work_record where work_expectation_id=?",UUID.class,work);
        JsonNode originalReview=json.readTree(jdbc.queryForObject("select jsonb_build_object('status',status,'reviewedBy',reviewed_by_account_id,'reviewedAt',reviewed_at,'reason',review_reason)::text from work_record where id=?",String.class,record));
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("completed").asInt()).isOne();
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","REJECTED","reason","复查发现质量问题","expectedVersion",0),200);
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("completed").asInt()).isZero();
        assertThat(jdbc.queryForObject("select status from work_expectation where id=?",String.class,work)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?",Integer.class,work)).isOne();
        assertThat(json.readTree(jdbc.queryForObject("select before_review::text from work_record_spot_check where work_record_id=?",String.class,record))).isEqualTo(originalReview);
        assertThat(jdbc.queryForObject("select before_row_version from work_record_spot_check where work_record_id=?",Long.class,record)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update work_record_spot_check set reason='改写审计' where work_record_id=?",record))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from work_record_spot_check where work_record_id=?",record))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid='work_record_spot_check'::regclass",Boolean.class)).isTrue();
        try(var connection=POSTGRES.getPostgresDatabase().getConnection();var statement=connection.createStatement()) {
            String role="spot_audit_reader_"+UUID.randomUUID().toString().replace("-","");
            statement.execute("create role "+role+" nologin nosuperuser nobypassrls");
            statement.execute("grant select,insert on work_record_spot_check to "+role);
            connection.setAutoCommit(false);
            statement.execute("set local role "+role);
            statement.execute("select set_config('app.tenant_id','"+TENANT+"',true)");
            try(var result=statement.executeQuery("select count(*) from work_record_spot_check")) {result.next();assertThat(result.getInt(1)).isPositive();}
            statement.execute("select set_config('app.tenant_id','"+UUID.randomUUID()+"',true)");
            try(var result=statement.executeQuery("select count(*) from work_record_spot_check")) {result.next();assertThat(result.getInt(1)).isZero();}
            try(var insert=connection.prepareStatement("insert into work_record_spot_check(tenant_id,work_record_id,reviewer_account_id,reviewer_assignment_id,outcome,reason,before_row_version,before_review) values(?::uuid,?::uuid,?::uuid,?::uuid,'REJECTED','跨租户尝试',0,'{}'::jsonb)")) {
                insert.setString(1,TENANT);insert.setString(2,UUID.randomUUID().toString());insert.setString(3,MANAGER);insert.setString(4,ASSIGNMENT);
                org.assertj.core.api.Assertions.assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class)
                        .extracting(error -> ((java.sql.SQLException)error).getSQLState()).isEqualTo("42501");
            }
            connection.rollback();
        }
    }

    @Test void reviewedFactsAndCriticalStatusesRemainDatabaseProtected() {
        LocalDate day=LocalDate.of(2025,8,18);
        UUID work=variantExpectation(day,"SATISFIED","DAY",false,"NONE","BOARD_IMMUTABLE_EXEMPT");
        record(work,day,"APPROVED",-1);
        UUID record=jdbc.queryForObject("select id from work_record where work_expectation_id=?",UUID.class,work);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update work_record set payload='{\"rewritten\":true}'::jsonb where id=?",record))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update work_record set status='SUBMITTED' where id=?",record))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID critical=variantExpectation(day,"SATISFIED","DAY",false,"NONE","HKA_DAILY_REPORT");
        record(critical,day,"APPROVED",-1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update work_record set status='REJECTED',review_reason='不能越过数量审核保护' where work_expectation_id=?",critical))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select status from work_record where work_expectation_id=?",String.class,critical)).isEqualTo("APPROVED");
    }

    @Test void passingSpotCheckKeepsOriginalApprovalAndRateButStillAllowsLaterFailure() throws Exception {
        LocalDate day=LocalDate.of(2025,8,19);
        UUID work=variantExpectation(day,"SATISFIED","DAY",false,"NONE","BOARD_SPOT_PASS_AUDIT");
        jdbc.update("update work_expectation set position_assignment_id='19200000-0000-0000-0000-000000000002'::uuid where id=?",work);
        record(work,day,"APPROVED",-1,"19000000-0000-0000-0000-000000000003","{}",null,
                OffsetDateTime.parse("2025-08-19T09:00:00+08:00"),UUID.fromString(MANAGER),OffsetDateTime.parse("2025-08-19T10:00:00+08:00"),"原人工验收意见");
        UUID record=jdbc.queryForObject("select id from work_record where work_expectation_id=?",UUID.class,work);
        String beforeRecord=jdbc.queryForObject("select to_jsonb(r)::text from work_record r where id=?",String.class,record);
        String beforeExpectation=jdbc.queryForObject("select to_jsonb(x)::text from work_expectation x where id=?",String.class,work);
        JsonNode beforeMetric=getBoard("?from="+day+"&to="+day).path("period");
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).contains(work.toString());
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","APPROVED","reason","现场抽查合格，保留原审批","expectedVersion",0),200);
        assertThat(jdbc.queryForObject("select to_jsonb(r)::text from work_record r where id=?",String.class,record)).isEqualTo(beforeRecord);
        assertThat(jdbc.queryForObject("select to_jsonb(x)::text from work_expectation x where id=?",String.class,work)).isEqualTo(beforeExpectation);
        assertThat(getBoard("?from="+day+"&to="+day).path("period")).isEqualTo(beforeMetric);
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).doesNotContain(work.toString());
        assertThat(jdbc.queryForObject("select count(*) from work_record_spot_check where work_record_id=? and outcome='APPROVED'",Integer.class,record)).isOne();
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","APPROVED","reason","现场抽查合格，保留原审批","expectedVersion",0),200);
        assertThat(jdbc.queryForObject("select count(*) from work_record_spot_check where work_record_id=? and outcome='APPROVED'",Integer.class,record)).isOne();
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",MANAGER,ASSIGNMENT,
                Map.of("outcome","REJECTED","reason","后续抽查发现遗漏，重新整改","expectedVersion",0),200);
        assertThat(getBoard("?from="+day+"&to="+day).path("period").path("completed").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from work_record_spot_check where work_record_id=?",Integer.class,record)).isEqualTo(2);
        assertThat(json.readTree(jdbc.queryForObject("select before_review::text from work_record_spot_check where work_record_id=? and outcome='REJECTED'",String.class,record)).path("reason").asText()).isEqualTo("原人工验收意见");
    }

    @Test void spotCheckAndRepairFilterTheWholeAuthorizedSetBeforePagination() throws Exception {
        LocalDate day=LocalDate.of(2025,8,14);
        UUID work=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_SPOT_FULL_SCOPE");
        UUID scopedAssignment=isolatedAssignment();
        jdbc.update("update work_expectation set position_assignment_id=? where id=?",scopedAssignment,work);
        for(int index=0;index<65;index++) {
            UUID expected=index==0?work:cloneExpectation(work,day,"SUBMITTED");
            record(expected,day,"SUBMITTED",-1);
        }
        UUID failed=cloneExpectation(work,day,"FAILED");record(failed,day,"REJECTED",-1);
        UUID future=cloneExpectation(work,LocalDate.now().plusDays(2),"SUBMITTED");record(future,LocalDate.now().plusDays(2),"SUBMITTED",-1);
        String scope="&orgUnitId="+HOTEL+"&assignmentId="+scopedAssignment;
        JsonNode first=getBoard("?queue=SPOT_CHECK&size=10"+scope);
        assertThat(first.path("items").size()).isEqualTo(10);
        assertThat(first.path("filtered").path("item_count").asInt()).isEqualTo(65);
        assertThat(first.path("spotCheckQueue").path("item_count").asInt()).isEqualTo(65);
        JsonNode last=getBoard("?queue=SPOT_CHECK&size=10&page=6"+scope);
        assertThat(last.path("items").size()).isEqualTo(5);
        assertThat(last.path("filtered").path("item_count").asInt()).isEqualTo(65);
        assertThat(getBoard("?queue=SPOT_CHECK&size=200&status=REVIEW"+scope).path("items").size()).isZero();
        JsonNode repair=getBoard("?queue=REPAIR&size=10"+scope);
        assertThat(repair.path("items").findValuesAsText("id")).containsExactly(failed.toString());
        assertThat(repair.path("repairQueue").path("item_count").asInt()).isOne();
        assertThat(first.path("items").findValuesAsText("id")).doesNotContain(failed.toString(),future.toString());
    }

    @Test void configuredSpotSampleIsStableAcrossRefreshAndAllPages() throws Exception {
        LocalDate day=LocalDate.of(2025,8,15);
        UUID work=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_SPOT_SAMPLE","{}","{\"spotCheckRate\":25}");
        UUID scopedAssignment=isolatedAssignment();
        jdbc.update("update work_expectation set position_assignment_id=? where id=?",scopedAssignment,work);
        for(int index=0;index<30;index++) {
            UUID expected=index==0?work:cloneExpectation(work,day,"SUBMITTED");
            record(expected,day,"SUBMITTED",-1);
        }
        List<String> sampled=jdbc.queryForList("""
            select x.id::text from work_expectation x join work_record r on r.work_expectation_id=x.id
            where x.position_assignment_id=? and mod(('x'||substr(md5(r.id::text),1,8))::bit(32)::bigint,100)<25
            """,String.class,scopedAssignment);
        String scope="&assignmentId="+scopedAssignment;
        JsonNode all=getBoard("?queue=SPOT_CHECK&size=200"+scope);
        assertThat(new HashSet<>(all.path("items").findValuesAsText("id"))).isEqualTo(new HashSet<>(sampled));
        assertThat(all.path("filtered").path("item_count").asInt()).isEqualTo(sampled.size());
        assertThat(getBoard("?queue=SPOT_CHECK&size=200"+scope).path("items")).isEqualTo(all.path("items"));
        var paginated=new HashSet<String>();
        for(int page=0;page<(sampled.size()+2)/3;page++)paginated.addAll(getBoard("?queue=SPOT_CHECK&size=3&page="+page+scope).path("items").findValuesAsText("id"));
        assertThat(paginated).isEqualTo(new HashSet<>(sampled));
    }

    @Test void historicalDailyRepairsAndApprovedExemptSamplesRemainDiscoverableBeyondSevenDays() throws Exception {
        LocalDate day=LocalDate.of(2024,3,18);
        UUID completed=variantExpectation(day,"SATISFIED","DAY",false,"NONE","BOARD_HISTORICAL_QUEUES");
        UUID assignment=isolatedAssignment();
        jdbc.update("update work_expectation set position_assignment_id=? where id=?",assignment,completed);
        record(completed,day,"APPROVED",-1,MANAGER,"{}",null,OffsetDateTime.parse("2024-03-18T10:00:00+08:00"));
        UUID failed=cloneExpectation(completed,day,"FAILED");
        record(failed,day,"REJECTED",-1,MANAGER,"{}",null,OffsetDateTime.parse("2024-03-18T10:00:00+08:00"));
        String scope="&assignmentId="+assignment;
        assertThat(getBoard("?queue=SPOT_CHECK&size=200"+scope).path("items").findValuesAsText("id")).containsExactly(completed.toString());
        assertThat(getBoard("?queue=REPAIR&size=200"+scope).path("items").findValuesAsText("id")).containsExactly(failed.toString());
        assertThat(getBoard("?queue=REVIEW&size=200"+scope).path("items").size()).isZero();
    }

    @Test void explicitExceptionRoutesForManualReviewButDeclaredNormalDoesNot() throws Exception {
        LocalDate day=LocalDate.of(2025,8,16);
        UUID issue=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_EXCEPTION_ONLY","{}","{\"requiresExceptionReview\":true}");
        record(issue,day,"SUBMITTED",-1,MANAGER,"{}","客房门锁故障，已通知维修",null);
        UUID normal=cloneExpectation(issue,day,"SUBMITTED");
        record(normal,day,"SUBMITTED",-1,MANAGER,"{\"issueSummary\":\"正常\"}","无异常",null);
        JsonNode board=getBoard("?from="+day+"&to="+day);
        assertThat(board.path("period").path("completed").asInt()).isOne();
        assertThat(board.path("period").path("review").asInt()).isOne();
        assertThat(board.path("items").get(0).path("id").asText()).isEqualTo(issue.toString());
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).doesNotContain(issue.toString());
        UUID flagged=cloneExpectation(issue,day,"SUBMITTED");
        record(flagged,day,"SUBMITTED",-1,MANAGER,"{\"issueFound\":true,\"issueSummary\":\"\"}",null,null);
        JsonNode flaggedBoard=getBoard("?from="+day+"&to="+day);
        assertThat(flaggedBoard.path("period").path("completed").asInt()).isOne();
        assertThat(flaggedBoard.path("period").path("review").asInt()).isEqualTo(2);
        assertThat(getBoard("?queue=SPOT_CHECK&size=200").path("items").findValuesAsText("id")).doesNotContain(flagged.toString());
    }

    @Test void unrelatedReviewerIsRejectedForBothSpotCheckAndManualRecheck() throws Exception {
        LocalDate day=LocalDate.of(2025,8,17);
        UUID work=variantExpectation(day,"SUBMITTED","DAY",false,"NONE","BOARD_WRONG_REVIEWER");
        record(work,day,"SUBMITTED",-1);
        UUID record=jdbc.queryForObject("select id from work_record where work_expectation_id=?",UUID.class,work);
        UUID account=UUID.randomUUID(),employee=UUID.randomUUID(),assignment=UUID.randomUUID();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?::uuid,?,'未分管该门店的副总')",account,TENANT,"board-reviewer-"+account);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?,?::uuid,?,?,'未分管副总',current_date)",employee,TENANT,account,"BOARD-"+account);
        jdbc.update("""
            insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from)
            select ?,?::uuid,?,?::uuid,id,true,current_date from position_definition where tenant_id=?::uuid and code='GROUP_VICE_PRESIDENT'
            """,assignment,TENANT,employee,HOTEL,TENANT);
        // Being a VP or having a review permission is not a substitute for a configured reporting relation/portfolio.
        workCommand("/api/v1/work-data/records/"+record+"/actions/review",account.toString(),assignment.toString(),
                Map.of("outcome","REJECTED","reason","非分管人员尝试抽查","expectedVersion",0),403);
        jdbc.update("update work_record set status='REJECTED' where id=?",record);
        jdbc.update("update work_expectation set status='SUBMITTED' where id=?",work);
        record(work,day,"SUBMITTED",-1);
        UUID correction=jdbc.queryForObject("select id from work_record where work_expectation_id=? and status='SUBMITTED'",UUID.class,work);
        workCommand("/api/v1/work-data/records/"+correction+"/actions/review",account.toString(),assignment.toString(),
                Map.of("outcome","APPROVED","reason","非分管人员尝试复验","expectedVersion",0),403);
        assertThat(jdbc.queryForObject("select status from work_record where id=?",String.class,correction)).isEqualTo("SUBMITTED");
    }

    UUID isolatedAssignment() {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from)
            select ?,tenant_id,employee_id,org_unit_id,position_id,false,valid_from from employee_position_assignment where id=?::uuid
            """,id,ASSIGNMENT);
        return id;
    }
    UUID cloneExpectation(UUID source,LocalDate day,String state) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into work_expectation select (jsonb_populate_record(null::work_expectation,to_jsonb(x)||
              jsonb_build_object('id',cast(? as text),'business_date',cast(? as text),'period_key',cast(? as text),'status',cast(? as text)))).*
            from work_expectation x where id=?
            """,id.toString(),day.toString(),"board-copy-"+id,state,source);
        return id;
    }

    JsonNode workCommand(String path,String actor,String assignment,Object body,int expectedStatus) throws Exception {
        var response=mvc.perform(post(path).header("X-Tenant-Id",TENANT).header("X-Actor-Id",actor).header("X-Assignment-Id",assignment)
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(response.getContentAsString());
    }

}
