package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ThreeRolePublicationIntegrationTest {
    private static final String TENANT="10000000-0000-0000-0000-000000000001";
    private static final String CEO="19000000-0000-0000-0000-000000000001";
    private static final EmbeddedPostgres POSTGRES=start();
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->POSTGRES.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.user",()->"postgres");
        registry.add("spring.flyway.password",()->"postgres");
        registry.add("app.security.development-header-auth-enabled",()->true);
        registry.add("app.database.rls-enabled",()->true);
    }

    @Test
    void stagedReportSectionsHydrateReadOnlyThenCanBeSavedAndSubmittedForReview() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);
        Map<String,Object> candidate=jdbc.queryForMap("""
                select v.id,v.template_id,v.configuration::text from daily_report_template_version v
                join daily_report_template_definition d on d.id=v.template_id
                join position_definition p on p.id=d.position_id
                where v.configuration->>'workflowRevision'='THREE_ROLE_V88' and p.code='HOUSEKEEPING_ATTENDANT'
                  and d.code='DRT-PILOT-HK-ATTENDANT-DAILY-V2'
                """);
        UUID template=(UUID)candidate.get("template_id"),version=(UUID)candidate.get("id");
        String frozen=String.valueOf(candidate.get("configuration"));
        var detail=mvc.perform(identity(get("/api/v1/daily-report-templates/{id}",template)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var root=mapper.readTree(detail);
        assertThat(root.path("publicationTimeZone").asText()).isEqualTo("Asia/Shanghai");
        var hydrated=root.path("versions").get(0).path("configuration");
        assertThat(hydrated.path("sections").size()).isGreaterThan(0);
        assertThat(hydrated.path("sections").get(0).path("items").size()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select configuration::text from daily_report_template_version where id=?",String.class,version)).isEqualTo(frozen);
        ObjectNode body=(ObjectNode)hydrated.deepCopy();
        body.put("expectedVersion",0);
        mvc.perform(identity(put("/api/v1/daily-report-templates/{t}/versions/{v}",template,version))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isOk());
        mvc.perform(identity(post("/api/v1/daily-report-templates/{t}/versions/{v}/actions/submit-review",template,version))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select lifecycle_status from daily_report_template_version where id=?",String.class,version)).isEqualTo("IN_REVIEW");
    }

    @Test
    void reportPublishRequiresItsBoundWorkPackageToBePublishedAndEffective() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);
        Map<String,Object> candidate=jdbc.queryForMap("""
                select distinct v.id,v.work_package_definition_id from work_package_version v
                join work_package_item i on i.work_package_version_id=v.id
                where i.item_code='AGM_WS_APPEARANCE_MEETING' and i.execution_policy->>'workflowRevision'='THREE_ROLE_V88'
                """);
        UUID work=(UUID)candidate.get("id"),definition=(UUID)candidate.get("work_package_definition_id");
        Map<String,Object> report=jdbc.queryForMap("""
                select id,template_id from daily_report_template_version
                where work_package_version_id=? and configuration->>'workflowRevision'='THREE_ROLE_V88'
                """,work);
        UUID version=(UUID)report.get("id"),template=(UUID)report.get("template_id");
        jdbc.update("update daily_report_template_version set lifecycle_status='IN_REVIEW',content_hash=repeat('c',64) where id=?",version);
        jdbc.update("""
                update daily_report_section_version set lifecycle_status='IN_REVIEW',content_hash=repeat('d',64)
                where id in(select section_version_id from daily_report_template_section where template_version_id=?)
                """,version);
        String future=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
        String workEnd=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
        String body="{\"expectedVersion\":0,\"effectiveFrom\":\""+future+"\"}";
        mvc.perform(identity(post("/api/v1/daily-report-templates/{t}/versions/{v}/actions/publish",template,version))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail",containsString("请先发布该日报绑定的工作包")));
        mvc.perform(identity(post("/api/v1/work-packages/{d}/versions/{v}/publish",definition,work))
                .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\""+future+"\",\"effectiveTo\":\""+workEnd+"\"}"))
                .andExpect(status().isOk());
        mvc.perform(identity(post("/api/v1/daily-report-templates/{t}/versions/{v}/actions/publish",template,version))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"effectiveFrom\":\""+workEnd+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail",containsString("请先发布该日报绑定的工作包")));
        mvc.perform(identity(post("/api/v1/daily-report-templates/{t}/versions/{v}/actions/publish",template,version))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void futurePublishKeepsOldDatesAndOtherAssistantVariantAvailable() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);
        Map<String,Object> candidate=jdbc.queryForMap("""
                select distinct v.id,v.work_package_definition_id from work_package_version v
                join work_package_item i on i.work_package_version_id=v.id
                where i.item_code='AGM_NS_APPEARANCE_MEETING' and i.execution_policy->>'workflowRevision'='THREE_ROLE_V88'
                """);
        UUID version=(UUID)candidate.get("id"),definition=(UUID)candidate.get("work_package_definition_id");
        String otherBefore=jdbc.queryForObject("""
                select string_agg(v.id::text||':'||v.lifecycle_status||':'||coalesce(v.effective_to::text,''),'|' order by v.id)
                from work_package_version v where v.work_package_definition_id=? and v.lifecycle_status='PUBLISHED'
                and exists(select 1 from work_package_item i where i.work_package_version_id=v.id
                  and i.applicability_policy->>'assistantWorkTemplateMode'='WITH_FRONT_OFFICE_SUPERVISOR')
                """,String.class,definition);
        var zone=ZoneId.of("Asia/Shanghai");
        var today=LocalDate.now(zone);
        String future=today.plusDays(2).atStartOfDay(zone).toOffsetDateTime().toString();
        mvc.perform(identity(post("/api/v1/work-packages/{d}/versions/{v}/publish",definition,version))
                .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\""+today.atStartOfDay(zone).toOffsetDateTime()+"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(identity(post("/api/v1/work-packages/{d}/versions/{v}/publish",definition,version))
                .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\""+future+"\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select string_agg(v.id::text||':'||v.lifecycle_status||':'||coalesce(v.effective_to::text,''),'|' order by v.id)
                from work_package_version v where v.work_package_definition_id=? and v.lifecycle_status='PUBLISHED'
                and exists(select 1 from work_package_item i where i.work_package_version_id=v.id
                  and i.applicability_policy->>'assistantWorkTemplateMode'='WITH_FRONT_OFFICE_SUPERVISOR')
                """,String.class,definition)).isEqualTo(otherBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_version v where v.work_package_definition_id=? and v.id<>?
                  and v.lifecycle_status='PUBLISHED' and v.effective_from::date<=? and v.effective_to::date>=?
                  and exists(select 1 from work_package_item i where i.work_package_version_id=v.id
                    and i.applicability_policy->>'assistantWorkTemplateMode'='WITHOUT_FRONT_OFFICE_SUPERVISOR')
                """,Integer.class,definition,version,today.plusDays(1),today.plusDays(1))).isGreaterThan(0);

        Map<String,Object> report=jdbc.queryForMap("""
                select id,template_id from daily_report_template_version
                where work_package_version_id=? and configuration->>'workflowRevision'='THREE_ROLE_V88'
                """,version);
        UUID reportVersion=(UUID)report.get("id"),template=(UUID)report.get("template_id");
        var oldReportIds=jdbc.queryForList("select id from daily_report_template_version where template_id=? and lifecycle_status='PUBLISHED'",UUID.class,template);
        jdbc.update("update daily_report_template_version set lifecycle_status='IN_REVIEW',content_hash=repeat('a',64) where id=?",reportVersion);
        jdbc.update("""
                update daily_report_section_version set lifecycle_status='IN_REVIEW',content_hash=repeat('b',64)
                where id in(select section_version_id from daily_report_template_section where template_version_id=?)
                """,reportVersion);
        mvc.perform(identity(post("/api/v1/daily-report-templates/{d}/versions/{v}/actions/publish",template,reportVersion))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",UUID.randomUUID().toString())
                .content("{\"expectedVersion\":0,\"effectiveFrom\":\""+future+"\"}"))
                .andExpect(status().isOk());
        for(UUID old:oldReportIds) {
            assertThat(jdbc.queryForObject("select lifecycle_status from daily_report_template_version where id=?",String.class,old)).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject("select effective_to::date from daily_report_template_version where id=?",LocalDate.class,old))
                    .isEqualTo(today.plusDays(2));
            assertThat(jdbc.queryForObject("""
                    select count(*) from daily_report_template_version v where v.id=? and lifecycle_status='PUBLISHED'
                      and effective_from<=?::timestamptz and effective_to>?::timestamptz
                    """,Integer.class,old,today.plusDays(1).atTime(6,0).atZone(zone).toOffsetDateTime(),
                    today.plusDays(1).atTime(6,0).atZone(zone).toOffsetDateTime())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,version)).isZero();
    }

    private MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request) {
        return request.header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID);
    }
    private static EmbeddedPostgres start() { try { return EmbeddedPostgres.builder().start(); } catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @AfterAll static void stop() throws Exception { POSTGRES.close(); }
}
