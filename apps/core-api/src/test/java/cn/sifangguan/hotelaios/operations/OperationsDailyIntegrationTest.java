package cn.sifangguan.hotelaios.operations;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.workpackage.WorkExpectationSlaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsDailyIntegrationTest {
    private static final String TENANT="10000000-0000-0000-0000-000000000001";
    private static final String CEO="19000000-0000-0000-0000-000000000001";
    private static final String HQ="12000000-0000-0000-0000-000000000001";
    private static final String H1="12000000-0000-0000-0000-000000000003";
    private static final String H2="12000000-0000-0000-0000-000000000004";
    private static final EmbeddedPostgres PG=startPostgres();
    private static final Path ATTACHMENT_ROOT=attachmentRoot();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EffectiveIdentityService identities;
    @Autowired WorkExpectationSlaService sla;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.user",()->"postgres");registry.add("spring.flyway.password",()->"postgres");
        registry.add("app.security.development-header-auth-enabled",()->true);
        registry.add("app.database.rls-enabled",()->true);
        registry.add("app.attachments.root",ATTACHMENT_ROOT::toString);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void isolatedPublishedCopies() {
        PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);
        // Production versions start tomorrow. Test-only immutable copies exercise today's materializer.
        for(UUID original:jdbc.queryForList("""
                select v.id from work_package_version v join work_package_definition d on d.id=v.work_package_definition_id
                where d.code like 'WP-OPERATIONS-%-DAILY' and v.lifecycle_status='PUBLISHED'
                """,UUID.class)) {
            UUID copy=UUID.randomUUID();
            jdbc.update("""
                    insert into work_package_version
                    select (jsonb_populate_record(null::work_package_version,to_jsonb(v)||
                      jsonb_build_object('id',?::uuid,'version_no',v.version_no+1000,'lifecycle_status','DRAFT',
                        'published_at',null,'effective_from',null,'effective_to',null))).*
                    from work_package_version v where v.id=?
                    """,copy,original);
            jdbc.update("""
                    insert into work_package_item select (jsonb_populate_record(null::work_package_item,to_jsonb(i)||
                      jsonb_build_object('id',gen_random_uuid(),'work_package_version_id',?::uuid))).*
                    from work_package_item i where i.work_package_version_id=?
                    """,copy,original);
            jdbc.update("insert into work_package_scope(tenant_id,work_package_version_id,scope_type) values(?::uuid,?,'TENANT')",TENANT,copy);
            jdbc.update("update work_package_version set lifecycle_status='PUBLISHED',published_at=now(),effective_from=current_date-1 where id=?",copy);
        }
    }

    @Test void headquartersAssignmentsReceiveSevenIndependentItemsPerExplicitHotelAndRole() throws Exception {
        Fixture manager=activate("OPERATIONS_MANAGER",List.of(H1,H2));
        Fixture director=activate("OPERATIONS_DIRECTOR",List.of(H1,H2));
        assertThat(count(manager.assignment)).isEqualTo(14);
        assertThat(count(director.assignment)).isEqualTo(14);
        assertThat(jdbc.queryForObject("select org_unit_id::text from employee_position_assignment where id=?",String.class,manager.assignment)).isEqualTo(HQ);
        JsonNode context=request(get("/api/v1/operations/context"),manager.account,manager.assignment,null,200);
        assertThat(context.path("hotels").size()).isEqualTo(2);
        assertThat(context.path("positionCode").asText()).isEqualTo("OPERATIONS_MANAGER");
        assertThat(context.path("canReviewPlan").asBoolean()).isFalse();
        JsonNode daily=request(get("/api/v1/operations/daily").param("date",today().toString()),manager.account,manager.assignment,null,200);
        assertThat(daily.path("items").size()).isEqualTo(14);
        assertThat(daily.path("items").findValuesAsText("assignmentId")).containsOnly(manager.assignment.toString());
        assertThat(daily.path("items").findValuesAsText("boardStatus")).doesNotContain("COMPLETED");
    }

    @Test void releasePreservesExactReminderDeadlinesAndClassifiedEvidence() {
        var rows=jdbc.queryForList("""
                select i.item_code,i.review_mode,i.grace_minutes,i.due_local_time::text as due,
                       i.reminder_policy->>'dueSoonMinutes' as reminder,
                       i.reminder_policy->>'escalationMinutes' as escalation,
                       jsonb_array_length(i.submission_policy->'evidenceRequirements') as evidence,
                       i.execution_policy->>'sharedKey' as shared
                from work_package_item i join work_package_version v on v.id=i.work_package_version_id
                where v.id=md5('V121:version:OPERATIONS_MANAGER')::uuid order by i.sort_order
                """);
        assertThat(rows).hasSize(7);
        assertThat(rows.stream().map(row->row.get("due"))).containsExactly("09:50:00","10:40:00","11:30:00","11:30:00","12:10:00","15:40:00","18:00:00");
        assertThat(rows.stream().map(row->row.get("evidence"))).containsExactly(4,6,4,4,7,4,4);
        for(var row:rows) {assertThat(row.get("review_mode")).isEqualTo("MANUAL");assertThat(row.get("grace_minutes")).isEqualTo(0);assertThat(row.get("escalation")).isEqualTo("20");assertThat(row.get("shared")).isNull();}
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item i, jsonb_array_elements(i.submission_policy->'evidenceRequirements') e
                where i.work_package_version_id=md5('V121:version:OPERATIONS_MANAGER')::uuid
                  and e->>'captureSource'<>'FILE_PICKER'
                """,Integer.class)).isZero();
    }

    @Test void headquartersManagerUploadsFourPortraitScreenshotsAndSubmitsForHumanReview() throws Exception {
        Fixture manager=activate("OPERATIONS_MANAGER",List.of(H1));
        var context=jdbc.queryForMap("""
                select x.id,x.work_package_item_id,i.work_package_version_id,i.form_version_id,a.employee_id
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id
                where x.position_assignment_id=? and x.target_org_unit_id=?::uuid
                  and x.business_date=current_date and i.item_code='OPS_ROUND_1'
                """,manager.assignment,H1);
        UUID expectation=(UUID)context.get("id");
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("orgUnitId",HQ);body.put("employeeId",context.get("employee_id"));
        body.put("positionAssignmentId",manager.assignment);body.put("formVersionId",context.get("form_version_id"));
        body.put("businessDate",today());body.put("targetOrgUnitId",H1);body.put("workExpectationId",expectation);
        body.put("workPackageVersionId",context.get("work_package_version_id"));
        body.put("workPackageItemId",context.get("work_package_item_id"));body.put("recordKind","INSPECTION");
        body.put("saveAsDraft",true);body.put("completionStatement","已检查本店及两家竞对预订、流量和外网价格，数据正常");
        body.put("payload",Map.of("workSummary","两主力平台预订、流量、本店价格与两家竞对价格均已核对",
                "issueFound",false,"dataScope","本店两主力平台及两家竞对，当天第1轮","dataUnavailable",false));
        var outside=new LinkedHashMap<>(body);outside.put("targetOrgUnitId",H2);
        request(post("/api/v1/work-data/records"),manager.account,manager.assignment,mapper.writeValueAsString(outside),403);
        var unlinked=new LinkedHashMap<>(body);unlinked.remove("workExpectationId");
        request(post("/api/v1/work-data/records"),manager.account,manager.assignment,mapper.writeValueAsString(unlinked),400);
        var wrongForm=new LinkedHashMap<>(body);wrongForm.put("formVersionId",UUID.randomUUID());
        request(post("/api/v1/work-data/records"),manager.account,manager.assignment,mapper.writeValueAsString(wrongForm),400);
        // Today's already generated H1 duties remain executable after tomorrow's portfolio moves to H2.
        request(put("/api/v1/org/assignments/"+manager.assignment+"/hotel-scope"),PlatformAdminTestFixture.ACCOUNT_ID,null,
                mapper.writeValueAsString(Map.of("responsibleHotelIds",List.of(H2))),200);
        JsonNode draft=request(post("/api/v1/work-data/records"),manager.account,manager.assignment,
                mapper.writeValueAsString(body),201);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        UUID record=UUID.fromString(draft.path("id").asText());
        int imageIndex=0;
        for(String checkpoint:List.of("round_bookings","round_traffic","round_own_prices","round_competitor_prices")) {
            BufferedImage screenshot=new BufferedImage(240,400,BufferedImage.TYPE_INT_RGB);
            screenshot.setRGB(0,0,++imageIndex);
            var bytes=new ByteArrayOutputStream();ImageIO.write(screenshot,"png",bytes);
            JsonNode uploaded=request(multipart("/api/v1/work-data/records/{id}/attachments/upload",record)
                            .file(new MockMultipartFile("file",checkpoint+".png","image/png",bytes.toByteArray()))
                            .param("captureSource","FILE_PICKER").param("checkpointCode",checkpoint),
                    manager.account,manager.assignment,null,201);
            assertThat(uploaded.path("captureSource").asText()).isEqualTo("FILE_PICKER");
            assertThat(uploaded.path("checkpointCode").asText()).isEqualTo(checkpoint);
            byte[] downloaded=mvc.perform(get("/api/v1/work-data/attachments/{id}/content",uploaded.path("id").asText())
                            .header("X-Tenant-Id",TENANT).header("X-Actor-Id",manager.account)
                            .header("X-Business-Assignment-Id",manager.assignment))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            BufferedImage preserved=ImageIO.read(new ByteArrayInputStream(downloaded));
            assertThat(preserved.getWidth()).isEqualTo(240);assertThat(preserved.getHeight()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("select count(distinct checkpoint_code) from attachment where work_record_id=? and capture_source='FILE_PICKER'",Integer.class,record)).isEqualTo(4);
        JsonNode submitted=request(post("/api/v1/work-data/records/{id}/actions/submit",record),manager.account,manager.assignment,
                mapper.writeValueAsString(Map.of("expectedVersion",draft.path("rowVersion").asLong())),200);
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select status from work_record where id=?",String.class,record)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select status from work_expectation where id=?",String.class,expectation)).isEqualTo("SUBMITTED");
        JsonNode daily=request(get("/api/v1/operations/daily").param("date",today().toString()),manager.account,manager.assignment,null,200);
        List<String> statuses=new ArrayList<>();
        for(JsonNode item:daily.path("items"))if(item.path("expectationId").asText().equals(expectation.toString()))statuses.add(item.path("boardStatus").asText());
        assertThat(statuses).containsExactly("REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=? and status='APPROVED'",Integer.class,expectation)).isZero();
    }

    @Test void scopeChangesApplyTomorrowWithoutDeletingTodaysResponsibilities() throws Exception {
        Fixture manager=activate("OPERATIONS_MANAGER",List.of(H1,H2));
        request(put("/api/v1/org/assignments/"+manager.assignment+"/hotel-scope"),PlatformAdminTestFixture.ACCOUNT_ID,null,
                mapper.writeValueAsString(Map.of("responsibleHotelIds",List.of(H1))),200);
        assertThat(count(manager.assignment)).isEqualTo(14);
        JsonNode retained=request(get("/api/v1/operations/daily").param("date",today().toString()),manager.account,manager.assignment,null,200);
        assertThat(retained.path("items").size()).isEqualTo(14);
        assertThat(jdbc.queryForList("""
                select target_org_unit_id::text from work_package_allocation where position_assignment_id=? and status='ACTIVE'
                  and valid_from<=current_date+1 and (valid_to is null or valid_to>=current_date+1)
                """,String.class,manager.assignment)).containsExactly(H1);
        UUID position=jdbc.queryForObject("select position_id from employee_position_assignment where id=?",UUID.class,manager.assignment);
        request(put("/api/v1/org/assignments/"+manager.assignment),PlatformAdminTestFixture.ACCOUNT_ID,null,
                mapper.writeValueAsString(Map.of("orgUnitId",HQ,"positionId",position,"primary",true,
                        "assignmentType","PERMANENT","validFrom",today().toString(),"responsibleHotelIds",List.of(H2))),200);
        assertThat(jdbc.queryForList("""
                select target_org_unit_id::text from work_package_allocation where position_assignment_id=? and status='ACTIVE'
                  and valid_from<=current_date+1 and (valid_to is null or valid_to>=current_date+1)
                """,String.class,manager.assignment)).containsExactly(H2);
        assertThat(count(manager.assignment)).isEqualTo(14);
        request(put("/api/v1/org/assignments/"+manager.assignment+"/hotel-scope"),PlatformAdminTestFixture.ACCOUNT_ID,null,
                "{\"responsibleHotelIds\":[]}",400);
    }

    @Test void schedulerRetriesAreIdempotentAndDoNotReactivateRevokedPortfolios() throws Exception {
        Fixture manager=activate("OPERATIONS_MANAGER",List.of(H1,H2));
        jdbc.update("update work_package_allocation set status='REVOKED' where position_assignment_id=? and target_org_unit_id=?::uuid",manager.assignment,H1);
        sla.processTenantAsSystem(UUID.fromString(TENANT),100,UUID.randomUUID());
        sla.processTenantAsSystem(UUID.fromString(TENANT),100,UUID.randomUUID());
        assertThat(count(manager.assignment)).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where position_assignment_id=? and target_org_unit_id=?::uuid and status='ACTIVE'",Integer.class,manager.assignment,H1)).isZero();
    }

    @Test void oldOtaAssistantHasNoInventedOperationsDailyTemplate() throws Exception {
        Fixture assistant=activate("OTA_OPERATION_ASSISTANT",List.of());
        JsonNode context=request(get("/api/v1/operations/context"),assistant.account,assistant.assignment,null,200);
        assertThat(context.path("canSubmitPlan").asBoolean()).isFalse();
        assertThat(context.path("dailyTemplateStatus").asText()).isEqualTo("PENDING_CONFIGURATION");
        JsonNode daily=request(get("/api/v1/operations/daily"),assistant.account,assistant.assignment,null,200);
        assertThat(daily.path("items").size()).isZero();
    }

    @Test void unusedOtaProfileUpgradesToExplicitPortfolioAndKeepsPublishedHistory() throws Exception {
        UUID position=jdbc.queryForObject("select id from position_definition where code='OTA_OPERATION_ASSISTANT' and tenant_id=?::uuid",UUID.class,TENANT);
        jdbc.update("update employee_position_assignment set status='ENDED' where position_id=? and status='ACTIVE'",position);
        UUID original=otaPublished(position);
        var permissions=jdbc.queryForList("select permission_id from position_function_profile_permission where profile_version_id=? order by permission_id",UUID.class,original);
        rerunOtaUpgrade();
        UUID published=otaPublished(position);
        assertThat(published).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("select authorization_scope_type from position_function_profile_version where id=?",String.class,published)).isEqualTo("ASSIGNED_HOTELS");
        assertThat(jdbc.queryForObject("select lifecycle_status from position_function_profile_version where id=?",String.class,original)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForList("select permission_id from position_function_profile_permission where profile_version_id=? order by permission_id",UUID.class,original)).isEqualTo(permissions);
        assertThat(jdbc.queryForList("select permission_id from position_function_profile_permission where profile_version_id=? order by permission_id",UUID.class,published)).containsAll(permissions);
        assertThat(jdbc.queryForObject("select copied_from_version_id from position_function_profile_version where id=?",UUID.class,published)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SYSTEM_OPERATIONS_PROFILE_PUBLISHED' and after_data->>'publishedVersionId'=?",Integer.class,published.toString())).isEqualTo(1);
    }

    @Test void futureActiveOtaAppointmentsBlockAutomaticScopeChanges() throws Exception {
        UUID position=jdbc.queryForObject("select id from position_definition where code='OTA_OPERATION_ASSISTANT' and tenant_id=?::uuid",UUID.class,TENANT);
        jdbc.update("update employee_position_assignment set status='ENDED' where position_id=? and status='ACTIVE'",position);
        UUID future=UUID.randomUUID();
        jdbc.update("""
                insert into employee_position_assignment
                select (jsonb_populate_record(null::employee_position_assignment,to_jsonb(a)||jsonb_build_object(
                  'id',?::uuid,'position_id',?::uuid,'valid_from',current_date+14,'valid_to',null,'status','ACTIVE','is_primary',false))).*
                from employee_position_assignment a where a.tenant_id=?::uuid limit 1
                """,future,position,TENANT);
        UUID original=otaPublished(position);
        String originalScope=jdbc.queryForObject("select authorization_scope_type from position_function_profile_version where id=?",String.class,original);
        rerunOtaUpgrade();
        assertThat(otaPublished(position)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select authorization_scope_type from position_function_profile_version where id=?",String.class,original)).isEqualTo(originalScope);
    }

    private UUID otaPublished(UUID position) {
        return jdbc.queryForObject("""
                select v.id from position_function_profile f join position_function_profile_version v on v.profile_id=f.id
                where f.position_id=? and f.scope_type='GROUP' and v.lifecycle_status='PUBLISHED'
                """,UUID.class,position);
    }
    private void rerunOtaUpgrade() throws Exception {
        try(var resource=getClass().getResourceAsStream("/db/migration/V121__operations_daily_portfolios.sql")) {
            String script=new String(Objects.requireNonNull(resource).readAllBytes(),StandardCharsets.UTF_8);
            int start=script.indexOf("-- BEGIN V121_OTA_PORTFOLIO_UPGRADE");
            int end=script.indexOf("-- END V121_OTA_PORTFOLIO_UPGRADE");
            jdbc.execute(script.substring(start,end));
        }
    }

    private Fixture activate(String code,List<String> hotels) throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        JsonNode employee=request(post("/api/v1/org/employees"),CEO,null,mapper.writeValueAsString(Map.of(
                "employeeNo","OPS-"+suffix,"name","运营隔离测试","employeeType","REGULAR","hiredOn",today().toString(),
                "loginName","ops."+suffix,"temporaryPassword","TestOnly!2026")),201);
        UUID position=jdbc.queryForObject("select id from position_definition where tenant_id=?::uuid and code=?",UUID.class,TENANT,code);
        JsonNode assignment=request(post("/api/v1/org/employees/"+employee.path("id").asText()+"/assignments"),CEO,null,
                mapper.writeValueAsString(Map.of("orgUnitId",HQ,"positionId",position,"primary",true,"assignmentType","PERMANENT",
                        "validFrom",today().toString(),"responsibleHotelIds",hotels)),201);
        UUID id=UUID.fromString(assignment.path("id").asText());
        UUID account=jdbc.queryForObject("select account_id from employee where id=?::uuid",UUID.class,employee.path("id").asText());
        return new Fixture(id,account.toString());
    }
    private int count(UUID assignment) {return jdbc.queryForObject("select count(*) from work_expectation where position_assignment_id=? and business_date=current_date",Integer.class,assignment);}
    private JsonNode request(MockHttpServletRequestBuilder request,String account,UUID assignment,String body,int code) throws Exception {
        request.header("X-Tenant-Id",TENANT).header("X-Actor-Id",account);
        if(assignment!=null)request.header("X-Business-Assignment-Id",assignment);
        if(body!=null)request.contentType("application/json").content(body);
        var result=mvc.perform(request).andExpect(status().is(code)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    private record Fixture(UUID assignment,String account){}
    private static Path attachmentRoot(){try{return Files.createTempDirectory("operations-daily-evidence-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
