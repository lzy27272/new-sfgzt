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
