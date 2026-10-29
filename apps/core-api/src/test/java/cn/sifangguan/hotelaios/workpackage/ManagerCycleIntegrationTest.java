package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManagerCycleIntegrationTest {
    static final String T="10000000-0000-0000-0000-000000000001", H="12000000-0000-0000-0000-000000000003";
    static final String ADMIN=PlatformAdminTestFixture.ACCOUNT_ID, GM="19000000-0000-0000-0000-000000000002", GMA="19200000-0000-0000-0000-000000000001";
    static final String STAFF="19000000-0000-0000-0000-000000000003", EA="19200000-0000-0000-0000-000000000002", E="19100000-0000-0000-0000-000000000002";
    static final EmbeddedPostgres PG=start();
    final String vp=UUID.randomUUID().toString(), vpAssignment=UUID.randomUUID().toString();
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()-> { try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new RuntimeException(e);} });
        r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres"); r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true); r.add("app.database.rls-enabled",()->true);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @Test void assessmentHistoryPermissionsOverrideBroadDefaultGrants() throws Exception {
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','employee_weekly_assessment',?)",
                Boolean.class, privilege)).as(privilege).isTrue();
        }
        for (String privilege : List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','employee_weekly_assessment',?)",
                Boolean.class, privilege)).as(privilege).isFalse();
        }
        try (var connection = PG.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
            statement.execute("set role hotel_ai_os_app");
            assertThatThrownBy(() -> statement.execute("delete from employee_weekly_assessment"))
                .isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.execute("truncate employee_weekly_assessment"))
                .isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }
    @Test void calendarGenerationAssessmentSubmissionReturnAndHistoricalScore() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001");
        String vpEmployee=UUID.randomUUID().toString();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?::uuid,?::uuid,?,'周期复核副总')",vp,T,"cycle-"+vp);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?::uuid,?::uuid,?::uuid,?,'周期复核副总',current_date)",vpEmployee,T,vp,"cycle-"+vp);
        jdbc.update("""
          insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from)
          select ?::uuid,?::uuid,?::uuid,?::uuid,id,true,current_date from position_definition where tenant_id=?::uuid and code='GROUP_VICE_PRESIDENT'
          """,vpAssignment,T,vpEmployee,H,T);
        jdbc.update("""
          update position_function_profile_version v set authorization_scope_type='ASSIGNED_HOTELS'
          from position_function_profile p join position_definition d on d.tenant_id=p.tenant_id and d.id=p.position_id
          where v.tenant_id=?::uuid and v.profile_id=p.id and v.tenant_id=p.tenant_id and d.code='GROUP_VICE_PRESIDENT' and v.lifecycle_status='PUBLISHED'
          """,T);
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?::uuid,?::uuid,?::uuid)",T,vpAssignment,H);
        String version=jdbc.queryForObject("select v.id::text from work_package_version v join work_package_definition d on d.id=v.work_package_definition_id where d.code='WP-GM-CYCLES' and d.tenant_id=?::uuid",String.class,T);
        assertThat(jdbc.queryForObject("select count(*) from work_package_item where work_package_version_id=?::uuid",Integer.class,version)).isEqualTo(23);
        call(post("/api/v1/work-packages/"+jdbc.queryForObject("select work_package_definition_id::text from work_package_version where id=?::uuid",String.class,version)+"/allocations"),ADMIN,
            Map.of("workPackageVersionId",version,"positionAssignmentId",GMA,"targetOrgUnitId",H,"validFrom",LocalDate.now().toString()),201);
        for(String period:List.of("WEEK","MONTH","QUARTER")) {
            var body=Map.of("positionAssignmentId",GMA,"targetOrgUnitId",H,"businessDate",LocalDate.now().toString(),"periodType",period);
            call(post("/api/v1/work-expectations/actions/generate"),ADMIN,body,201);
            assertThat(call(post("/api/v1/work-expectations/actions/generate"),ADMIN,body,201).path("createdCount").asInt()).isZero();
        }
        JsonNode board=call(get("/api/v1/manager-cycles?period=WEEK"),GM,null,200);
        assertThat(board.path("items").isArray()).isTrue(); assertThat(board.path("items").size()).isPositive();
        assertThat(call(get("/api/v1/manager-cycles?period=WEEK"),STAFF,null,200).path("items")).isEmpty();
        String x=jdbc.queryForObject("select x.id::text from work_expectation x join work_package_item i on i.id=x.work_package_item_id where i.item_code='GM_W_EMPLOYEE' and x.position_assignment_id=?::uuid",String.class,GMA);
        String endpoint="/api/v1/manager-cycles/"+x+"/assessment";
        JsonNode detail=call(get(endpoint),GM,null,200);
        assertThat(detail.path("employees").toString()).contains(E);
        call(get(endpoint),STAFF,null,403);
        call(post(endpoint+"/submit"),GM,null,400);
        call(put(endpoint+"/"+E),GM,Map.of("score",101),400);
        call(put(endpoint+"/"+E),GM,Map.of("score",88,"focus","提升服务主动性"),200);
        call(put(endpoint+"/"+E),GM,Map.of("score",77),400);
        call(put(endpoint+"/"+E),GM,Map.of("score",90,"expectedVersion",0),200);
        call(put(endpoint+"/"+E),GM,Map.of("score",91,"expectedVersion",0),400);
        call(get(endpoint+"/"+E+"/reference"),GM,null,200);
        JsonNode submitted=call(post(endpoint+"/submit"),GM,null,200);
        String r=submitted.path("id").asText(); assertThat(r).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_account_id=?::uuid and source_id=?::uuid and notification_type='WORK_RECORD_REVIEW_PENDING'",Integer.class,vp,r)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload->'employeeAssessments'->0->>'score' from work_record where id=?::uuid",String.class,r)).startsWith("90");
        call(put(endpoint+"/"+E),GM,Map.of("score",91,"expectedVersion",1),400);
        call(post("/api/v1/work-data/records/"+r+"/actions/review"),GM,Map.of("outcome","APPROVED","expectedVersion",0),403);
        assertThat(call(get(endpoint),vp,null,200).path("canEdit").asBoolean()).isFalse();
        call(put(endpoint+"/"+E),vp,Map.of("score",99,"expectedVersion",1),403);
        call(post("/api/v1/work-data/records/"+r+"/actions/review"),vp,Map.of("outcome","REJECTED","reason","补充辅导计划","expectedVersion",0),200);
        call(put(endpoint+"/"+E),GM,Map.of("score",92,"focus","已增加辅导计划","expectedVersion",1),200);
        JsonNode second=call(post(endpoint+"/submit"),GM,null,200);
        jdbc.update("delete from employee_assignment_hotel_scope where tenant_id=?::uuid and assignment_id=?::uuid",T,vpAssignment);
        call(post("/api/v1/work-data/records/"+second.path("id").asText()+"/actions/review"),vp,Map.of("outcome","APPROVED","expectedVersion",0),403);
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?::uuid,?::uuid,?::uuid)",T,vpAssignment,H);
        call(post("/api/v1/work-data/records/"+second.path("id").asText()+"/actions/review"),vp,Map.of("outcome","APPROVED","expectedVersion",0),200);
        assertThat(jdbc.queryForObject("select payload->'employeeAssessments'->0->>'score' from work_record where id=?::uuid",String.class,r)).startsWith("90");
        assertThat(call(get(endpoint),GM,null,200).path("canEdit").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from employee_weekly_assessment where work_expectation_id=?::uuid",Integer.class,x)).isEqualTo(1);
    }

    @Test @Transactional void auditedRoutineExemptionsCompleteWeeklyAndMonthlyPanelsWithoutChangingSubmissions() throws Exception {
        CycleActor actor=cycleActor();
        for(String code:List.of("GM_W_HYGIENE","GM_W_MEETING","GM_W_TRAINING","GM_M_CHANNEL")) {
            String expectation=cycleExpectation(actor,code);
            cycleRecord(expectation,"SUBMITTED",null,null);
            activateCycle(expectation,LocalDate.now().plusDays(1),true);
        }
        String manual=cycleExpectation(actor,"GM_W_EMPLOYEE");
        cycleRecord(manual,"SUBMITTED",null,null);
        JsonNode week=cycleBoard(actor,"WEEK"), month=cycleBoard(actor,"MONTH");
        for(String code:List.of("GM_W_HYGIENE","GM_W_MEETING","GM_W_TRAINING")) {
            JsonNode item=cycleItem(week,code);
            assertThat(item.path("board_status").asText()).as(code).isEqualTo("COMPLETED");
            assertThat(item.path("review_mode").asText()).isEqualTo("NONE");
            assertThat(item.path("record_status").asText()).isEqualTo("SUBMITTED");
        }
        assertThat(cycleItem(week,"GM_W_EMPLOYEE").path("board_status").asText()).isEqualTo("REVIEW");
        assertThat(week.path("counts").path("COMPLETED").asInt()).isEqualTo(3);
        assertThat(week.path("counts").path("REVIEW").asInt()).isEqualTo(1);
        assertThat(cycleItem(month,"GM_M_CHANNEL").path("board_status").asText()).isEqualTo("COMPLETED");
        assertThat(month.path("counts").path("COMPLETED").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from work_record where position_assignment_id=?::uuid and status='SUBMITTED'",
                Integer.class,actor.assignment())).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item i join work_review_policy_activation a
                  on a.tenant_id=i.tenant_id and a.work_package_item_id=i.id
                where a.reason=? and i.review_mode='MANUAL'
                """,Integer.class,actor.assignment())).isEqualTo(4);
    }

    @Test @Transactional void exceptionsAndRepairsRemainManualInCyclePanelsAndFailuresReturnToCorrection() throws Exception {
        CycleActor actor=cycleActor();
        String exception=cycleExpectation(actor,"GM_W_HYGIENE");
        String overdue=cycleExpectation(actor,"GM_W_MEETING");
        String pending=cycleExpectation(actor,"GM_W_TRAINING");
        for(String expectation:List.of(exception,overdue,pending))
            activateCycle(expectation,LocalDate.now().minusDays(1),false);
        cycleRecord(exception,"SUBMITTED","发现问题，需跟进",null);
        jdbc.update("update work_expectation set due_at=now()-interval '1 hour' where id=?::uuid",overdue);
        String rejected=cycleRecord(overdue,"REJECTED","退回整改",null);
        jdbc.update("update work_expectation set due_at=now()+interval '1 hour' where id=?::uuid",pending);
        cycleRecord(pending,"REJECTED","退回整改",null);
        JsonNode first=cycleBoard(actor,"WEEK");
        assertThat(cycleItem(first,"GM_W_HYGIENE").path("board_status").asText()).isEqualTo("REVIEW");
        assertThat(cycleItem(first,"GM_W_MEETING").path("board_status").asText()).isEqualTo("OVERDUE");
        assertThat(cycleItem(first,"GM_W_TRAINING").path("board_status").asText()).isEqualTo("PENDING");
        assertThat(first.path("counts").path("REVIEW").asInt()).isEqualTo(1);
        assertThat(first.path("counts").path("COMPLETED").asInt()).isZero();
        cycleRecord(overdue,"SUBMITTED",null,rejected);
        JsonNode repaired=cycleBoard(actor,"WEEK");
        assertThat(cycleItem(repaired,"GM_W_MEETING").path("board_status").asText()).isEqualTo("REVIEW");
        assertThat(cycleItem(repaired,"GM_W_MEETING").path("review_mode").asText()).isEqualTo("MANUAL");
        assertThat(repaired.path("counts").path("REVIEW").asInt()).isEqualTo(2);
        assertThat(repaired.path("counts").path("COMPLETED").asInt()).isZero();
    }

    @Test @Transactional void earlierHumanApprovalDoesNotBecomeExemptSpotCheckWorkOnActivationDay() throws Exception {
        CycleActor actor=cycleActor();
        String expectation=cycleExpectation(actor,"GM_W_MEETING");
        String record=cycleRecord(expectation,"APPROVED",null,null);
        jdbc.update("update work_record set reviewed_by_account_id=?::uuid,reviewed_at=clock_timestamp(),review_reason='已人工审核' where id=?::uuid",ADMIN,record);
        activateCycle(expectation,LocalDate.now(),false);
        JsonNode board=cycleBoard(actor,"WEEK");
        JsonNode item=cycleItem(board,"GM_W_MEETING");
        assertThat(item.path("board_status").asText()).isEqualTo("COMPLETED");
        assertThat(item.path("review_mode").asText()).isEqualTo("MANUAL");
        JsonNode eligibility=call(get("/api/v1/work-data/records/"+record+"/review-eligibility")
                .header("X-Assignment-Id",actor.assignment()),actor.account(),null,200);
        assertThat(eligibility.path("spotCheck").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select reviewed_by_account_id::text||':'||status||':'||review_reason from work_record where id=?::uuid",String.class,record))
                .isEqualTo(ADMIN+":APPROVED:已人工审核");
        assertThat(jdbc.queryForObject("select count(*) from work_review_policy_correction where work_record_id=?::uuid",Integer.class,record)).isZero();
    }

    private record CycleActor(String account,String assignment) { }
    private CycleActor cycleActor() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001");
        String account=UUID.randomUUID().toString(),employee=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?::uuid,?::uuid,?,'免审周期店总')",account,T,"cycle-exempt-"+account);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?::uuid,?::uuid,?::uuid,?,'免审周期店总',current_date)",employee,T,account,"cycle-exempt-"+account);
        jdbc.update("""
                insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from)
                select ?::uuid,?::uuid,?::uuid,?::uuid,id,true,current_date from position_definition where tenant_id=?::uuid and code='GENERAL_MANAGER'
                """,assignment,T,employee,H,T);
        String version=jdbc.queryForObject("select v.id::text from work_package_version v join work_package_definition d on d.id=v.work_package_definition_id where d.code='WP-GM-CYCLES' and d.tenant_id=?::uuid",String.class,T);
        String definition=jdbc.queryForObject("select work_package_definition_id::text from work_package_version where id=?::uuid",String.class,version);
        call(post("/api/v1/work-packages/"+definition+"/allocations"),ADMIN,
                Map.of("workPackageVersionId",version,"positionAssignmentId",assignment,"targetOrgUnitId",H,"validFrom",LocalDate.now().toString()),201);
        String allocation=jdbc.queryForObject("select id::text from work_package_allocation where position_assignment_id=?::uuid and work_package_version_id=?::uuid",String.class,assignment,version);
        // Model existing cycle work, independently of the generator's fresh-publication cutoff.
        // For example, a fresh test seed published after the 28th must not generate that month's overdue work.
        for(Map<String,Object> item:jdbc.queryForList("""
                select id,period_type,due_local_time::text,execution_policy::text from work_package_item
                where work_package_version_id=?::uuid
                  and item_code in ('GM_W_HYGIENE','GM_W_MEETING','GM_W_TRAINING','GM_M_CHANNEL','GM_W_EMPLOYEE')
                """,version)) {
            LocalDate day=LocalDate.now();
            CycleSchedule.Window window=CycleSchedule.resolve(item.get("period_type").toString(),day,
                    LocalTime.parse(item.get("due_local_time").toString()),ZoneId.of("Asia/Shanghai"),
                    mapper.readTree(item.get("execution_policy").toString()));
            jdbc.update("""
                    insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                      position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status,period_start,period_end)
                    values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?,?,?,?,'AVAILABLE',?,?)
                    """,UUID.randomUUID().toString(),T,item.get("id"),allocation,assignment,H,day,window.key(),
                    window.availableAt(),window.dueAt(),window.start(),window.end());
        }
        return new CycleActor(account,assignment);
    }
    private String cycleExpectation(CycleActor actor,String code) {
        return jdbc.queryForObject("select x.id::text from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id where x.position_assignment_id=?::uuid and i.item_code=?",String.class,actor.assignment(),code);
    }
    private String cycleRecord(String expectation,String status,String exception,String previous) {
        String record=UUID.randomUUID().toString();
        String expectationStatus=switch(status) { case "REJECTED" -> "FAILED"; case "APPROVED" -> "SATISFIED"; default -> "SUBMITTED"; };
        jdbc.update("update work_expectation set status=? where id=?::uuid",expectationStatus,expectation);
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
                  business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,work_expectation_id,
                  record_kind,target_org_unit_id,occurred_at,submitted_by_account_id,attempt_no,content_hash,exception_statement,supersedes_work_record_id)
                select ?::uuid,x.tenant_id,x.target_org_unit_id,a.employee_id,a.id,i.form_version_id,x.business_date,?,'{}'::jsonb,
                  clock_timestamp(),i.work_package_version_id,i.id,x.id,'SCHEDULED',x.target_org_unit_id,clock_timestamp(),e.account_id,
                  (select coalesce(max(r.attempt_no),0)+1 from work_record r where r.tenant_id=x.tenant_id and r.work_expectation_id=x.id),
                  repeat('c',64),cast(? as text),?::uuid
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where x.id=?::uuid
                """,record,status,exception,previous,expectation);
        return record;
    }
    private void activateCycle(String expectation,LocalDate from,boolean historical) {
        String activation=UUID.randomUUID().toString();
        jdbc.update("""
                insert into work_review_policy_activation(id,tenant_id,target_org_unit_id,work_package_item_id,new_work_from,authorization_reference,reason,created_at)
                select ?::uuid,tenant_id,target_org_unit_id,work_package_item_id,?,'USER_CONFIRMED_ROUTINE_34',position_assignment_id::text,clock_timestamp()
                from work_expectation where id=?::uuid
                """,activation,from,expectation);
        if(historical) jdbc.update("""
                insert into work_review_policy_correction(tenant_id,activation_id,work_expectation_id,work_record_id,before_record,before_expectation)
                select x.tenant_id,?::uuid,x.id,r.id,to_jsonb(r),to_jsonb(x) from work_expectation x
                join work_record r on r.tenant_id=x.tenant_id and r.work_expectation_id=x.id where x.id=?::uuid
                """,activation,expectation);
    }
    private JsonNode cycleBoard(CycleActor actor,String period) throws Exception {
        return call(get("/api/v1/manager-cycles?period="+period).header("X-Assignment-Id",actor.assignment()),actor.account(),null,200);
    }
    private JsonNode cycleItem(JsonNode board,String code) {
        for(JsonNode item:board.path("items")) if(code.equals(item.path("item_code").asText())) return item;
        throw new AssertionError("Missing cycle item: "+code);
    }
    private JsonNode call(MockHttpServletRequestBuilder request,String actor,Object body,int status) throws Exception {
        request.header("X-Tenant-Id",T).header("X-Actor-Id",actor);
        if(actor.equals(GM)) request.header("X-Assignment-Id",GMA);
        if(actor.equals(STAFF)) request.header("X-Assignment-Id",EA);
        if(actor.equals(vp)) request.header("X-Assignment-Id",vpAssignment);
        if(body!=null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    static EmbeddedPostgres start(){
        try {
            var pg = EmbeddedPostgres.builder().start();
            try (var connection = pg.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
                statement.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
                statement.execute("alter default privileges in schema public grant all on tables to hotel_ai_os_app");
            }
            return pg;
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
