package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyWorkCompletionIntegrationTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001";
    static final String ACTOR="19000000-0000-0000-0000-000000000002";
    static final String ASSIGNMENT="19200000-0000-0000-0000-000000000001";
    static final String HOTEL="12000000-0000-0000-0000-000000000003";
    static final String OTHER_HOTEL="12000000-0000-0000-0000-000000000004";
    static final String ITEM="47050000-0000-0000-0000-000000000002";
    static final EmbeddedPostgres POSTGRES=postgres();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MonthlyWorkCompletionService completion;
    @Autowired WorkPackageService work;
    static EmbeddedPostgres postgres() {
        try {
            var pg=EmbeddedPostgres.builder().start();
            try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()) {
                s.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
            }
            return pg;
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->POSTGRES.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);
        r.add("app.work-expectation.sla.scheduler-enabled",()->false);
        r.add("app.operations.plans.scheduler-enabled",()->false);
        r.add("app.database.rls-enabled",()->true);
    }
    @AfterAll static void close() throws Exception { POSTGRES.close(); }
    JsonNode report(String params) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/operations/completion"+params)
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",ACTOR).header("X-Assignment-Id",ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    UUID expectation(LocalDate day,String state) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                  position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status)
                select ?,?::uuid,?::uuid,a.id,?::uuid,?::uuid,?,?,?::timestamptz,?::timestamptz,?
                from work_package_allocation a where a.tenant_id=?::uuid and a.position_assignment_id=?::uuid
                  and a.work_package_version_id='47030000-0000-0000-0000-000000000001'::uuid limit 1
                """,id,TENANT,ITEM,ASSIGNMENT,HOTEL,day,"monthly-"+id,day+"T08:00:00+08:00",day+"T18:00:00+08:00",state,TENANT,ASSIGNMENT);
        return id;
    }
    UUID record(UUID expectation,LocalDate day,String state,OffsetDateTime submitted) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
                  business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,
                  work_expectation_id,record_kind,target_org_unit_id,occurred_at,submitted_by_account_id,attempt_no,
                  content_hash,completion_statement)
                select ?,x.tenant_id,x.target_org_unit_id,a.employee_id,a.id,i.form_version_id,?,?,'{}',?,
                  i.work_package_version_id,i.id,x.id,'INSPECTION',x.target_org_unit_id,?,?::uuid,
                  (select coalesce(max(attempt_no),0)+1 from work_record where work_expectation_id=x.id),repeat('d',64),'完成检查'
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id where x.id=?
                """,id,day,state,submitted,submitted,ACTOR,expectation);
        return id;
    }
    UUID task(OffsetDateTime due,String source,String state,OffsetDateTime submitted) {
        return new TransactionTemplate(transactions).execute(transaction -> taskInTransaction(due,source,state,submitted));
    }
    UUID taskInTransaction(OffsetDateTime due,String source,String state,OffsetDateTime submitted) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into management_task(id,tenant_id,task_no,idempotency_key,org_unit_id,title,lifecycle_status,
                  creation_source,due_at,source_snapshot,responsibility_snapshot,created_by,created_by_assignment_id)
                values(?,?::uuid,?,?,?::uuid,'月度测试任务',?,?,?,'{}','{}',?::uuid,?::uuid)
                """,id,TENANT,"MONTH-"+id,"monthly-test-"+id,HOTEL,state,source,due,ACTOR,ASSIGNMENT);
        jdbc.update("""
                insert into task_participant(tenant_id,task_id,participant_type,position_assignment_id,
                  employee_snapshot,position_snapshot,org_snapshot)
                values(?::uuid,?,'ASSIGNEE',?::uuid,'{}','{}','{}')
                """,TENANT,id,ASSIGNMENT);
        if(submitted!=null) jdbc.update("""
                insert into task_transition(tenant_id,task_id,from_status,to_status,command,task_version,idempotency_key,payload,occurred_at)
                values(?::uuid,?,'IN_PROGRESS','RESULT_SUBMITTED','SUBMIT_RESULT',1,?,'{}',?)
                """,TENANT,id,"submission-"+id,submitted);
        if (source.equals("WORK_PLAN")) planProvenance(id,due);
        return id;
    }

    void planProvenance(UUID task,OffsetDateTime due) {
        UUID plan=UUID.randomUUID(),revision=UUID.randomUUID(),item=UUID.randomUUID(),review=UUID.randomUUID(),decision=UUID.randomUUID();
        YearMonth month=YearMonth.from(due);
        jdbc.update("""
                insert into management_work_plan(id,tenant_id,owner_assignment_id,plan_type,period_start,period_end)
                values(?,?::uuid,?::uuid,'MONTHLY',?,?)
                """,plan,TENANT,ASSIGNMENT,month.atDay(1),month.atEndOfMonth());
        jdbc.update("""
                insert into management_work_plan_revision(id,tenant_id,work_plan_id,revision_no,created_by_assignment_id)
                values(?,?::uuid,?,1,?::uuid)
                """,revision,TENANT,plan,ASSIGNMENT);
        jdbc.update("""
                insert into management_work_plan_item(id,tenant_id,revision_id,item_no,title,target_assignment_id,due_at)
                values(?,?::uuid,?,1,'月度测试行动',?::uuid,?)
                """,item,TENANT,revision,ASSIGNMENT,due);
        jdbc.update("""
                insert into management_work_plan_review(id,tenant_id,revision_id,reviewer_assignment_id,action)
                values(?,?::uuid,?,?::uuid,'APPROVE')
                """,review,TENANT,revision,ASSIGNMENT);
        jdbc.update("""
                insert into management_work_plan_item_decision(id,tenant_id,review_id,item_id,content_decision,due_decision,reminder_decision)
                values(?,?::uuid,?,?,'ACCEPT','ACCEPT','ACCEPT')
                """,decision,TENANT,review,item);
        jdbc.update("insert into management_work_plan_task_link(tenant_id,item_decision_id,task_id) values(?::uuid,?,?)",TENANT,decision,task);
    }

    @Test void combinesRealWorkAndTasksAndArchivesLateCorrectionsWithoutChangingOriginalFacts() throws Exception {
        LocalDate day=LocalDate.of(2025,12,10);
        UUID accepted=expectation(day,"SATISFIED");
        record(accepted,day,"APPROVED",day.atTime(16,0).atOffset(ZoneOffset.ofHours(8)));
        UUID pending=expectation(day.plusDays(1),"SUBMITTED");
        record(pending,day.plusDays(1),"SUBMITTED",day.plusDays(1).atTime(17,0).atOffset(ZoneOffset.ofHours(8)));
        UUID failed=expectation(day.plusDays(2),"FAILED");
        UUID original=record(failed,day.plusDays(2),"REJECTED",day.plusDays(2).atTime(16,0).atOffset(ZoneOffset.ofHours(8)));
        String before=jdbc.queryForObject("select to_jsonb(r)::text from work_record r where id=?",String.class,original);
        task(day.plusDays(3).atTime(18,0).atOffset(ZoneOffset.ofHours(8)),"WORK_PLAN","COMPLETED",day.plusDays(4).atTime(9,0).atOffset(ZoneOffset.ofHours(8)));
        task(day.plusDays(5).atTime(18,0).atOffset(ZoneOffset.ofHours(8)),"MANUAL","RESULT_SUBMITTED",day.plusDays(5).atTime(17,0).atOffset(ZoneOffset.ofHours(8)));
        task(day.plusDays(5).atTime(18,0).atOffset(ZoneOffset.ofHours(8)),"MANUAL","CANCELLED",null);
        task(day.plusDays(5).atTime(18,0).atOffset(ZoneOffset.ofHours(8)),"MANUAL","PROPOSED",null);
        var first=report("?month=2025-12");
        assertThat(first.path("denominator").asInt()).isEqualTo(5);
        assertThat(first.path("qualifiedCompleted").asInt()).isEqualTo(2);
        assertThat(first.path("onTimeCompleted").asInt()).isEqualTo(1);
        assertThat(first.path("pendingReview").asInt()).isEqualTo(2);
        assertThat(first.path("lateQualified").asInt()).isEqualTo(1);
        assertThat(first.path("snapshot").path("revisionNo").asInt()).isOne();
        var paged=report("?month=2025-12&page=1&size=1");
        assertThat(paged.path("denominator").asInt()).isEqualTo(5);
        assertThat(paged.path("items").size()).isOne();
        assertThat(paged.path("snapshot").path("id").asText()).isEqualTo(first.path("snapshot").path("id").asText());
        record(failed,day.plusDays(2),"APPROVED",OffsetDateTime.parse("2026-01-03T12:00:00+08:00"));
        jdbc.update("update work_expectation set status='SATISFIED' where id=?",failed);
        var fixed=report("?month=2025-12");
        assertThat(fixed.path("qualifiedCompleted").asInt()).isEqualTo(3);
        assertThat(fixed.path("onTimeCompleted").asInt()).isOne();
        assertThat(fixed.path("snapshot").path("revisionNo").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select to_jsonb(r)::text from work_record r where id=?",String.class,original)).isEqualTo(before);
        var history=json.readTree(mvc.perform(get("/api/v1/operations/completion/history?month=2025-12")
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",ACTOR).header("X-Assignment-Id",ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("items");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("qualifiedCompleted").asInt()).isEqualTo(3);
        assertThat(history.get(1).path("qualifiedCompleted").asInt()).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("delete from operations_monthly_work_snapshot where id=?::uuid",first.path("snapshot").path("id").asText()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        try(var c=POSTGRES.getPostgresDatabase().getConnection();var s=c.createStatement()) {
            s.execute("set role hotel_ai_os_app");
            s.execute("set app.tenant_id='10000000-0000-0000-0000-000000000099'");
            try(var rs=s.executeQuery("select count(*) from operations_monthly_work_snapshot")) { rs.next();assertThat(rs.getInt(1)).isZero(); }
        }
    }
    @Test void aFutureMonthHasNoPercentageAndNoArtificialZeroCompletion() throws Exception {
        OffsetDateTime future=OffsetDateTime.now().plusYears(2).withDayOfMonth(10);
        task(future,"WORK_PLAN","PENDING_ACK",null);
        var result=report("?month="+YearMonth.from(future));
        assertThat(result.path("denominator").asInt()).isZero();
        assertThat(result.path("futurePlanned").asInt()).isOne();
        assertThat(result.path("completionRate").isNull() || result.path("completionRate").isMissingNode()).isTrue();
        assertThat(result.path("onTimeRate").isNull() || result.path("onTimeRate").isMissingNode()).isTrue();
    }
    @Test void rejectsInvalidMonthsAnonymousReadsAndUnrelatedAssignmentFilters() throws Exception {
        mvc.perform(get("/api/v1/operations/completion?month=2025-99").header("X-Tenant-Id",TENANT)
                .header("X-Actor-Id",ACTOR).header("X-Assignment-Id",ASSIGNMENT)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/operations/completion?month=2025-12")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/operations/completion?month=2025-12&assignmentId="+UUID.randomUUID())
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",ACTOR).header("X-Assignment-Id",ASSIGNMENT)).andExpect(status().isForbidden());
    }

    @Test void narrowedViewerCannotReadEarlierFullPortfolioSnapshotHistory() {
        var viewer=subject("OPERATIONS_DIRECTOR");
        var worker=subject("OPERATIONS_MANAGER");
        reportsTo(worker,viewer);
        LocalDate day=LocalDate.of(2025,10,10);
        expectationFor(worker,UUID.fromString(HOTEL),day);
        expectationFor(worker,UUID.fromString(OTHER_HOTEL),day);
        var full=principal(viewer,Set.of(UUID.fromString(HOTEL),UUID.fromString(OTHER_HOTEL)),true);
        var narrowed=principal(viewer,Set.of(UUID.fromString(HOTEL)),true);
        var fullReport=as(full,()->completion.report("2025-10",null,worker.assignment(),0,50));
        assertThat(((Number)fullReport.get("denominator")).intValue()).isEqualTo(2);
        assertThat(history(as(full,()->completion.history("2025-10",null,worker.assignment())))).hasSize(1);
        assertThat(history(as(narrowed,()->completion.history("2025-10",null,worker.assignment())))).isEmpty();
        var partialReport=as(narrowed,()->completion.report("2025-10",null,worker.assignment(),0,50));
        assertThat(((Number)partialReport.get("denominator")).intValue()).isEqualTo(1);
        assertThat(snapshotId(partialReport)).isNotEqualTo(snapshotId(fullReport));
        var partialHistory=history(as(narrowed,()->completion.history("2025-10",null,worker.assignment())));
        assertThat(partialHistory).hasSize(1);
        assertThat(((Number)partialHistory.getFirst().get("denominator")).intValue()).isEqualTo(1);
        var fullHistory=history(as(full,()->completion.history("2025-10",null,worker.assignment())));
        assertThat(fullHistory).hasSize(1);
        assertThat(((Number)fullHistory.getFirst().get("denominator")).intValue()).isEqualTo(2);
    }

    @Test void undatedTasksHonorBothHotelSelectionAndTheViewersAuthorizedOrganizations() {
        var viewer=subject("OPERATIONS_DIRECTOR");
        var worker=subject("OPERATIONS_MANAGER");
        reportsTo(worker,viewer);
        taskFor(worker,UUID.fromString(HOTEL));
        taskFor(worker,UUID.fromString(OTHER_HOTEL));
        var full=principal(viewer,Set.of(UUID.fromString(HOTEL),UUID.fromString(OTHER_HOTEL)),true);
        var narrowed=principal(viewer,Set.of(UUID.fromString(HOTEL)),true);
        var all=as(full,()->completion.report("2025-09",null,worker.assignment(),0,50));
        var selected=as(full,()->completion.report("2025-09",UUID.fromString(HOTEL),worker.assignment(),0,50));
        var limited=as(narrowed,()->completion.report("2025-09",null,worker.assignment(),0,50));
        assertThat(((Number)all.get("undatedTasks")).intValue()).isEqualTo(2);
        assertThat(((Number)selected.get("undatedTasks")).intValue()).isEqualTo(1);
        assertThat(((Number)limited.get("undatedTasks")).intValue()).isEqualTo(1);
        assertThatThrownBy(()->as(narrowed,()->completion.report("2025-09",UUID.fromString(OTHER_HOTEL),worker.assignment(),0,50)))
                .isInstanceOf(cn.sifangguan.hotelaios.shared.security.AccessDeniedException.class);
    }

    @Test void incomingAndOutgoingDelegationsHaveTheSameResponsibilityAttributionForEmployeeAndSupervisor() {
        var viewer=subject("OPERATIONS_DIRECTOR");
        var worker=subject("OPERATIONS_MANAGER");
        var other=subject("OPERATIONS_MANAGER");
        reportsTo(worker,viewer);reportsTo(other,viewer);
        LocalDate day=LocalDate.of(2025,8,10);
        UUID incoming=expectationFor(other,UUID.fromString(HOTEL),day);
        UUID outgoing=expectationFor(worker,UUID.fromString(HOTEL),day.plusDays(1));
        delegate(incoming,worker,viewer);delegate(outgoing,other,viewer);
        var own=principal(worker,Set.of(UUID.fromString(HOTEL)),false);
        var supervisor=principal(viewer,Set.of(UUID.fromString(HOTEL)),true);
        var employeeReport=as(own,()->completion.report("2025-08",null,null,0,50));
        var supervisorReport=as(supervisor,()->completion.report("2025-08",null,worker.assignment(),0,50));
        assertThat(((Number)employeeReport.get("denominator")).intValue()).isEqualTo(1);
        assertThat(((Number)supervisorReport.get("denominator")).intValue()).isEqualTo(1);
        assertThat(factIds(employeeReport)).containsExactly(incoming);
        assertThat(factIds(supervisorReport)).containsExactly(incoming);
        assertThat(factIds(employeeReport)).doesNotContain(outgoing);
        var otherReport=as(principal(other,Set.of(UUID.fromString(HOTEL)),false),()->completion.report("2025-08",null,null,0,50));
        assertThat(factIds(otherReport)).containsExactly(outgoing);

        recordAs(incoming,day,"APPROVED",day.atTime(9,45).atOffset(ZoneOffset.ofHours(8)),worker);
        jdbc.update("update work_expectation set status='SATISFIED' where id=?",incoming);
        jdbc.update("""
                update work_expectation_delegation set status='REVOKED',revoked_by_account_id=?,revoked_at=now()
                where work_expectation_id=? and status='ACTIVE'
                """,viewer.account(),incoming);
        var submitted=as(own,()->completion.report("2025-08",null,null,0,50));
        assertThat(factIds(submitted)).containsExactly(incoming);
        assertThat(((Number)submitted.get("qualifiedCompleted")).intValue()).isOne();
        assertThat(((Number)submitted.get("onTimeCompleted")).intValue()).isOne();
        assertThat(factIds(as(supervisor,()->completion.report("2025-08",null,worker.assignment(),0,50))))
                .containsExactly(incoming);
        assertThat(factIds(as(principal(other,Set.of(UUID.fromString(HOTEL)),false),
                ()->completion.report("2025-08",null,null,0,50)))).containsExactly(outgoing);

        recordAs(incoming,day,"DRAFT",null,worker);
        var withDraft=as(own,()->completion.report("2025-08",null,null,0,50));
        assertThat(((Number)withDraft.get("qualifiedCompleted")).intValue()).isOne();
        assertThat(((Number)withDraft.get("onTimeCompleted")).intValue()).isOne();
        assertThat(snapshotId(withDraft)).isEqualTo(snapshotId(submitted));
    }

    @Test void permissionToAssistWithSharedWorkDoesNotCreateAnotherEmployeesMonthlyObligation() {
        var owner=subject("GENERAL_MANAGER",UUID.fromString(HOTEL));
        var assistant=subject("ASSISTANT_GENERAL_MANAGER",UUID.fromString(HOTEL));
        LocalDate day=LocalDate.of(2025,7,10);
        UUID allocation=UUID.randomUUID(),expectation=UUID.randomUUID();
        jdbc.update("""
                insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,allocation_source,valid_from)
                values(?,?::uuid,'47030000-0000-0000-0000-000000000001'::uuid,?,?,'MANUAL',date '2024-01-01')
                """,allocation,TENANT,owner.assignment(),UUID.fromString(HOTEL));
        jdbc.update("""
                insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                  position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status)
                values(?,?::uuid,?::uuid,?,?,?,?,?,?::timestamptz,?::timestamptz,'AVAILABLE')
                """,expectation,TENANT,ITEM,allocation,owner.assignment(),UUID.fromString(HOTEL),day,"shared-month-"+expectation,
                day+"T08:30:00+08:00",day+"T10:30:00+08:00");
        var assistantPrincipal=principal(assistant,Set.of(UUID.fromString(HOTEL)),false);
        assertThat(as(assistantPrincipal,()->work.expectationDetail(expectation)).get("id")).isEqualTo(expectation);
        recordAs(expectation,day,"DRAFT",null,assistant);
        var assistantReport=as(assistantPrincipal,()->completion.report("2025-07",null,null,0,50));
        var ownerReport=as(principal(owner,Set.of(UUID.fromString(HOTEL)),false),()->completion.report("2025-07",null,null,0,50));
        assertThat(((Number)assistantReport.get("denominator")).intValue()).isZero();
        assertThat(factIds(assistantReport)).isEmpty();
        assertThat(factIds(ownerReport)).containsExactly(expectation);
    }

    private Subject subject(String positionCode) {
        return subject(positionCode,UUID.fromString("12000000-0000-0000-0000-000000000001"));
    }
    private Subject subject(String positionCode,UUID organization) {
        UUID account=UUID.randomUUID(),employee=UUID.randomUUID(),assignment=UUID.randomUUID();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?::uuid,?,'月统计隔离人员')",account,TENANT,"month-scope-"+account);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?,?::uuid,?,?,'月统计隔离人员',date '2024-01-01')",employee,TENANT,account,"MONTH-"+employee);
        jdbc.update("""
                insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from)
                select ?,?::uuid,?,?,p.id,date '2024-01-01'
                from position_definition p where p.tenant_id=?::uuid and p.code=?
                """,assignment,TENANT,employee,organization,TENANT,positionCode);
        return new Subject(account,assignment,positionCode);
    }
    private void reportsTo(Subject worker,Subject supervisor) {
        jdbc.update("""
                insert into position_assignment_reporting_relation(tenant_id,subordinate_assignment_id,leader_assignment_id,valid_from)
                values(?::uuid,?,?,date '2024-01-01')
                """,TENANT,worker.assignment(),supervisor.assignment());
    }
    private UUID expectationFor(Subject worker,UUID hotel,LocalDate day) {
        UUID allocation=UUID.randomUUID(),expectation=UUID.randomUUID();
        jdbc.update("""
                insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,allocation_source,valid_from)
                values(?,?::uuid,md5('V121:version:OPERATIONS_MANAGER')::uuid,?,?,'MANUAL',date '2024-01-01')
                """,allocation,TENANT,worker.assignment(),hotel);
        jdbc.update("""
                insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                  position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status)
                values(?,?::uuid,md5('V121:item:OPERATIONS_MANAGER:OPS_ROUND_1')::uuid,?,?,?,?,?,?::timestamptz,?::timestamptz,'AVAILABLE')
                """,expectation,TENANT,allocation,worker.assignment(),hotel,day,"scope-"+expectation,day+"T09:30:00+08:00",day+"T09:50:00+08:00");
        return expectation;
    }
    private void delegate(UUID expectation,Subject worker,Subject supervisor) {
        jdbc.update("""
                insert into work_expectation_delegation(tenant_id,work_expectation_id,delegate_assignment_id,delegated_by_account_id,reason)
                values(?::uuid,?,?,?,'月统计委派归属测试')
                """,TENANT,expectation,worker.assignment(),supervisor.account());
    }
    private UUID recordAs(UUID expectation,LocalDate day,String state,OffsetDateTime submitted,Subject executor) {
        UUID record=UUID.randomUUID();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
                  business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,
                  work_expectation_id,record_kind,target_org_unit_id,occurred_at,submitted_by_account_id,attempt_no,
                  content_hash,completion_statement)
                select ?,x.tenant_id,x.target_org_unit_id,a.employee_id,a.id,i.form_version_id,?,?,'{}',?,
                  i.work_package_version_id,i.id,x.id,'INSPECTION',x.target_org_unit_id,?, ?,
                  (select coalesce(max(attempt_no),0)+1 from work_record where work_expectation_id=x.id),repeat('e',64),'完成检查'
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=? where x.id=?
                """,record,day,state,submitted,submitted==null?day.atTime(12,0).atOffset(ZoneOffset.ofHours(8)):submitted,
                executor.account(),executor.assignment(),expectation);
        return record;
    }
    private UUID taskFor(Subject worker,UUID hotel) {
        return new TransactionTemplate(transactions).execute(transaction->{
            UUID task=UUID.randomUUID();
            jdbc.update("""
                    insert into management_task(id,tenant_id,task_no,idempotency_key,org_unit_id,title,lifecycle_status,
                      creation_source,due_at,source_snapshot,responsibility_snapshot,created_by,created_by_assignment_id)
                    values(?,?::uuid,?,?,?,'无截止隔离任务','PENDING_ACK','MANUAL',null,'{}','{}',?,?)
                    """,task,TENANT,"UNDATED-"+task,"undated-"+task,hotel,worker.account(),worker.assignment());
            jdbc.update("""
                    insert into task_participant(tenant_id,task_id,participant_type,position_assignment_id,
                      employee_snapshot,position_snapshot,org_snapshot)
                    values(?::uuid,?,'ASSIGNEE',?,'{}','{}','{}')
                    """,TENANT,task,worker.assignment());
            return task;
        });
    }
    private TenantPrincipal principal(Subject person,Set<UUID> hotels,boolean team) {
        Set<String> permissions=new HashSet<>(Set.of("work-record.read","task.read"));
        if(team)permissions.add("work-record.team-read");
        return new TenantPrincipal(UUID.fromString(TENANT),person.account(),person.role(),Set.of(person.role()),permissions,
                hotels,Set.of(person.assignment()),person.assignment(),false,UUID.randomUUID());
    }
    private <T>T as(TenantPrincipal principal,Supplier<T> action) {
        var previous=TenantContext.current();
        return new TransactionTemplate(transactions).execute(transaction->{
            TenantContext.set(principal);
            try{return action.get();}finally{if(previous.isPresent())TenantContext.set(previous.get());else TenantContext.clear();}
        });
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> history(Map<String,Object> response) {return (List<Map<String,Object>>)response.get("items");}
    @SuppressWarnings("unchecked") private UUID snapshotId(Map<String,Object> response) {return (UUID)((Map<String,Object>)response.get("snapshot")).get("id");}
    @SuppressWarnings("unchecked") private List<UUID> factIds(Map<String,Object> response) {return ((List<Map<String,Object>>)response.get("items")).stream().map(item->(UUID)item.get("id")).toList();}
    private record Subject(UUID account,UUID assignment,String role){}
}
