package cn.sifangguan.hotelaios.aidata;

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
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AiDataIntegrationTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001";
    static final String CEO="19000000-0000-0000-0000-000000000001";
    static final String ADMIN=PlatformAdminTestFixture.ACCOUNT_ID;
    static final EmbeddedPostgres PG=start();
    static final String URL=url();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);
        r.add("app.data-retention.cleanup-worker-enabled",()->false);
    }
    @BeforeEach void fixture(){PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);}
    @AfterAll static void stop() throws Exception {PG.close();}

    @Test void nativeDatasetsSaveImmutableScopedEvidenceAndSupportComparisonPeriods() throws Exception {
        for(String dataset:List.of("WORK_RECORDS","DAILY_REPORTS","TASKS","KPI_FACTS")) {
            JsonNode value=read(mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(query(dataset)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshotId").isString()).andReturn());
            assertThat(value.path("coverage").path("expectedTotalVerified").asBoolean()).isFalse();
            assertThat(value.path("rows").toString()).doesNotContain("source_snapshot","employee_snapshot","PAYROLL_SENSITIVE");
        }
        JsonNode snapshot=read(mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(query("WORK_RECORDS"))).andExpect(status().isOk()).andReturn());
        var report=new LinkedHashMap<String,Object>(Map.of("requestId",UUID.randomUUID(),"title","集成验证报告","body","仅基于实际记录，待人工核验。",
            "from","2026-09-01","to","2026-09-07","snapshotIds",List.of(snapshot.path("snapshotId").asText()),"authoringTool","integration-test"));
        String body=json.writeValueAsString(report);
        JsonNode saved=read(mvc.perform(identity(post("/api/v1/ai-data/reports")).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source_type").value("PLATFORM")).andReturn());
        String id=saved.path("id").asText();
        mvc.perform(identity(post("/api/v1/ai-data/reports")).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        report.put("body","changed");
        mvc.perform(identity(post("/api/v1/ai-data/reports")).contentType("application/json").content(json.writeValueAsString(report))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/ai-data/reports/"+id).header("X-Tenant-Id",TENANT).header("X-Actor-Id",CEO)).andExpect(status().isNotFound());
        assertThatThrownBy(()->jdbc.update("update ai_analysis_report set body='modified' where id=?::uuid",id)).hasMessageContaining("append-only");
        assertThat(saved.path("evidence").get(0).path("data_hash").asText()).hasSize(64);
        // A legitimate period-over-period report keeps both query periods intact.
        JsonNode previous=read(mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json")
            .content(query("WORK_RECORDS").replace("2026-09-01","2026-08-25").replace("2026-09-07","2026-08-31"))).andExpect(status().isOk()).andReturn());
        report.put("requestId",UUID.randomUUID());report.put("snapshotIds",List.of(snapshot.path("snapshotId").asText(),previous.path("snapshotId").asText()));
        mvc.perform(identity(post("/api/v1/ai-data/reports")).contentType("application/json").content(json.writeValueAsString(report))).andExpect(status().isOk()).andExpect(jsonPath("$.evidence.length()").value(2));
        jdbc.update("update ai_data_snapshot set expires_at=now()-interval '1 second' where id=?::uuid",snapshot.path("snapshotId").asText());
        report.put("requestId",UUID.randomUUID());
        mvc.perform(identity(post("/api/v1/ai-data/reports")).contentType("application/json").content(json.writeValueAsString(report))).andExpect(status().isForbidden());
    }

    @Test void mcpRequiresIndependentUnexpiredTokenAndRechecksIam() throws Exception {
        String rpc="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        mvc.perform(post("/api/v1/ai-data/mcp").contentType("application/json").content(rpc)).andExpect(status().isUnauthorized());
        mvc.perform(identity(post("/api/v1/ai-data/mcp")).contentType("application/json").content(rpc)).andExpect(status().isUnauthorized());
        JsonNode created=connection(false);String token=created.path("token").asText();String id=created.path("id").asText();
        assertThat(jdbc.queryForObject("select token_hash from ai_data_connection where id=?::uuid",String.class,id)).isEqualTo(AiDataSupport.hash(token)).doesNotContain(token);
        mvc.perform(rpc(token,rpc)).andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(4));
        mvc.perform(rpc(token,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
        mvc.perform(rpc(token,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());
        mvc.perform(rpc(token,rpc).header("Origin","https://attacker.invalid")).andExpect(status().isForbidden());
        mvc.perform(rpc(token,rpc).header("MCP-Protocol-Version","bad")).andExpect(status().isBadRequest());
        mvc.perform(rpc(token,"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"save_report\",\"arguments\":{}}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true));
        mvc.perform(get("/api/v1/ai-data/mcp").header("Authorization","Bearer "+token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(rpc(token,"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"data_catalog\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false));
        mvc.perform(identity(post("/api/v1/ai-data/connections/"+id+"/revoke"))).andExpect(status().isNoContent());
        mvc.perform(rpc(token,rpc)).andExpect(status().isUnauthorized());
        JsonNode expiring=connection(true);
        mvc.perform(rpc(expiring.path("token").asText(),rpc)).andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(5));
        jdbc.update("update ai_data_connection set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where id=?::uuid",expiring.path("id").asText());
        mvc.perform(rpc(expiring.path("token").asText(),rpc)).andExpect(status().isUnauthorized());
        JsonNode live=connection(false);
        try {
            jdbc.update("update user_account set status='DISABLED' where id=?::uuid",ADMIN);
            assertThat(mvc.perform(rpc(live.path("token").asText(),rpc)).andReturn().getResponse().getStatus()).isIn(401,403);
        } finally {jdbc.update("update user_account set status='ACTIVE' where id=?::uuid",ADMIN);}
    }

    @Test void otaMappingIsExplicitAndFunctionDoesNotCrossSourceTenants() throws Exception {
        jdbc.execute("create schema if not exists ota_analytics");
        jdbc.execute("""
            create table if not exists ota_analytics.daily_operating_fact (
              tenant_id text,hotel_id text,business_date date,observed_at timestamptz,finalized_at timestamptz,
              revision int,source_system text,completeness text,room_revenue numeric,sold_room_nights numeric,
              effective_sellable_room_nights numeric,available_rooms numeric,occupancy_rate numeric,adr numeric,revpar numeric,content_hash text)
            """);
        String source=UUID.randomUUID().toString(),hotel=UUID.randomUUID().toString();
        for(String tenant:List.of(source,"unrelated-tenant")) jdbc.update("""
            insert into ota_analytics.daily_operating_fact(tenant_id,hotel_id,business_date,revision,source_system,completeness,room_revenue)
            values(?,?,'2026-09-01',1,'PMS','COMPLETE',?)
            """,tenant,hotel,tenant.equals(source)?100:99999);
        UUID org=jdbc.queryForObject("select id from org_unit where tenant_id=?::uuid and unit_type='HOTEL' limit 1",UUID.class,TENANT);
        UUID binding=UUID.randomUUID();
        jdbc.update("insert into ai_data_source_binding(id,tenant_id,org_unit_id,source_tenant_id,source_hotel_id,source_name) values(?,?::uuid,?,?,?,'explicit test binding')",binding,TENANT,org,source,hotel);
        String body=json.writeValueAsString(Map.of("dataset","OTA_DAILY","from","2026-09-01","to","2026-09-07","bindingId",binding));
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.rows[0].room_revenue").value(100))
            .andExpect(jsonPath("$.coverage.state").value("PARTIAL"));
        jdbc.execute("create table if not exists ota_analytics.hourly_operating_fact as select 1::bigint as id,d.* from ota_analytics.daily_operating_fact d where false");
        jdbc.update("insert into ota_analytics.hourly_operating_fact select 1,d.* from ota_analytics.daily_operating_fact d where tenant_id in (?,'unrelated-tenant')",source);
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(body.replace("OTA_DAILY","OTA_HOURLY")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.rows[0].room_revenue").value(100));
        jdbc.execute("""
            create table if not exists ota_analytics.period_rollup (
              tenant_id text,hotel_id text,period_type text,period_start date,period_end date,
              available_day_count int,expected_day_count int,room_revenue numeric,sold_room_nights numeric,
              effective_sellable_room_nights numeric,occupancy_rate numeric,adr numeric,revpar numeric,completeness text,refreshed_at timestamptz)
            """);
        for(String tenant:List.of(source,"unrelated-tenant")) jdbc.update("""
            insert into ota_analytics.period_rollup(tenant_id,hotel_id,period_type,period_start,period_end,room_revenue,completeness)
            values(?,?,'MONTH','2026-09-01','2026-09-30',?,'PARTIAL')
            """,tenant,hotel,tenant.equals(source)?100:99999);
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json")
            .content(body.replace("OTA_DAILY","OTA_PERIODS").replace("2026-09-07","2026-09-30")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.rows[0].period_type").value("MONTH"))
            .andExpect(jsonPath("$.rows[0].room_revenue").value(100));
        assertThatThrownBy(()->jdbc.update("update ai_data_source_binding set source_tenant_id='unrelated-tenant' where id=?",binding)).hasMessageContaining("new binding");
        jdbc.update("update ai_data_source_binding set enabled=false where id=?",binding);
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(body)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select has_function_privilege('public','ai_read_ota_daily(uuid,date,date,integer,integer)','EXECUTE')",Boolean.class)).isFalse();
    }

    @Test void rlsAndInputBoundsRemainFailClosed() throws Exception {
        jdbc.execute("create role ai_rls_probe nologin");
        jdbc.execute("grant select on ai_data_connection,ai_data_source_binding,ai_data_snapshot,ai_analysis_report to ai_rls_probe");
        try(var connection=PG.getPostgresDatabase().getConnection();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set local role ai_rls_probe");
            statement.execute("select set_config('app.tenant_id','00000000-0000-0000-0000-000000000999',true)");
            for(String table:List.of("ai_data_connection","ai_data_source_binding","ai_data_snapshot","ai_analysis_report"))
                try(var rows=statement.executeQuery("select count(*) from "+table)) {rows.next();assertThat(rows.getInt(1)).isZero();}
            connection.rollback();
        }
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(query("raw_sql"))).andExpect(status().isBadRequest());
        mvc.perform(identity(post("/api/v1/ai-data/query")).contentType("application/json").content(query("WORK_RECORDS").replace("2026-09-01","2010-01-01"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ai-data/query").header("X-Tenant-Id",TENANT).header("X-Actor-Id",ADMIN).contentType("application/json").content(query("WORK_RECORDS"))).andExpect(status().is4xxClientError());
    }
    private JsonNode connection(boolean write) throws Exception {
        return read(mvc.perform(identity(post("/api/v1/ai-data/connections")).contentType("application/json")
            .content("{\"name\":\"test\",\"days\":1,\"allowReportWrite\":"+write+"}")).andExpect(status().isOk()).andReturn());
    }
    private String query(String dataset) {return "{\"dataset\":\""+dataset+"\",\"from\":\"2026-09-01\",\"to\":\"2026-09-07\"}";}
    private JsonNode read(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString());}
    private MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder r) {return r.header("X-Tenant-Id",TENANT).header("X-Actor-Id",ADMIN).header("X-SFG-AI-Action","1");}
    private MockHttpServletRequestBuilder rpc(String token,String body) {return post("/api/v1/ai-data/mcp").header("Authorization","Bearer "+token).contentType("application/json").content(body);}
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
