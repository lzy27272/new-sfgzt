package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoreWorkUsageIntegrationTest {
    private static final String T="10000000-0000-0000-0000-000000000001", H="12000000-0000-0000-0000-000000000003";
    private static final String OTHER_H="12000000-0000-0000-0000-000000000004", D="12000000-0000-0000-0000-000000000005";
    private static final String ADMIN=PlatformAdminTestFixture.ACCOUNT_ID, GM="19000000-0000-0000-0000-000000000002";
    private static final String GMA="19200000-0000-0000-0000-000000000001", AGM="19000000-0000-0000-0000-000000000008", AGMA="19200000-0000-0000-0000-000000000008";
    private static final String EMP="19000000-0000-0000-0000-000000000003", EMPLOYEE="19100000-0000-0000-0000-000000000002", EA="19200000-0000-0000-0000-000000000002";
    private static final EmbeddedPostgres PG=start();
    private static final String URL=url();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private String form, version, expectation;
    private String usageItemCode="HKA_USAGE_RETURN";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL); r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres"); r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true); r.add("app.database.rls-enabled",()->true);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void setup() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001");
        String suffix=UUID.randomUUID().toString();
        String f=call(post("/api/v1/work-data/forms"),ADMIN,Map.of("code","USAGE-"+suffix,"name","耗用测试","formType","DAILY_WORK"),201).path("id").asText();
        form=call(post("/api/v1/work-data/forms/"+f+"/versions"),ADMIN,Map.of("jsonSchema",Map.of("type","object","properties",Map.of("inventoryQuantities",Map.of("type","object")))),201).path("id").asText();
        call(post("/api/v1/work-data/forms/"+f+"/versions/"+form+"/publish"),ADMIN,null,200);
        String wp=call(post("/api/v1/work-packages"),ADMIN,Map.of("code","USAGE-"+suffix,"name","耗用测试","positionId","14000000-0000-0000-0000-000000000001","ownerOrgUnitId",H),201).path("id").asText();
        version=call(post("/api/v1/work-packages/"+wp+"/versions"),ADMIN,Map.of("title","耗用测试"),201).path("id").asText();
        Map<String,Object> item=new LinkedHashMap<>(Map.of("itemCode",usageItemCode,"name","耗用登记","itemType","METRIC_REVIEW","formVersionId",form,"periodType","SHIFT","timezoneMode","TENANT","dueLocalTime","23:00:00","targetGranularity","TARGET_ORG","reviewMode","MANUAL"));
        item.put("submissionPolicy",Map.of("completionStatementRequired",true,"attachmentRequired",false,"maxAttachments",10,"maxFileSizeBytes",20971520,"allowedExtensions",List.of("jpg")));
        item.put("standards",List.of(Map.of("standardVersionId","17000000-0000-0000-0000-000000000001","usageType","EXECUTION","weight",1)));
        item.put("responsibilities",List.of(Map.of("participantType","EXECUTOR","resolverType","CURRENT_ASSIGNMENT"),
                Map.of("participantType","ACCEPTOR","resolverType","DIRECT_MANAGER_ASSIGNMENT")));
        call(put("/api/v1/work-packages/"+wp+"/versions/"+version),ADMIN,Map.of("title","耗用测试","scopes",List.of(Map.of("scopeType","ORG_TREE","orgUnitId",H)),"items",List.of(item)),200);
        call(post("/api/v1/work-packages/"+wp+"/versions/"+version+"/publish"),ADMIN,Map.of("effectiveFrom",OffsetDateTime.now().minusMinutes(1).toString()),200);
        call(post("/api/v1/work-packages/"+wp+"/allocations"),ADMIN,Map.of("workPackageVersionId",version,"positionAssignmentId",EA,"targetOrgUnitId",D,"validFrom",LocalDate.now().toString()),201);
        JsonNode duty=call(post("/api/v1/work-duty-periods"),ADMIN,Map.of("positionAssignmentId",EA,"targetOrgUnitId",D,"businessDate",LocalDate.now().toString(),"periodType","SHIFT","shiftCode",suffix,"plannedStartAt",OffsetDateTime.now().minusHours(1).toString(),"plannedEndAt",OffsetDateTime.now().plusHours(2).toString()),201);
        expectation=jdbc.queryForObject("select x.id::text from work_expectation x join work_package_item i on i.id=x.work_package_item_id where i.work_package_version_id=?::uuid and x.duty_period_id=?::uuid",String.class,version,duty.path("id").asText());
        configure(GMA,200);
    }

    @Test void draftSubmissionApprovalLinenAndIdempotentRetry() throws Exception {
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID revision=createUsageDailyReport(version,itemId,LocalDate.now());
        String reportId=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        JsonNode emptyReport=call(get("/api/v1/daily-reports/"+reportId),EMP,null,200);
        assertThat(emptyReport.path("revisions").get(0).path("payloadSnapshot").path("resolvedTemplate").path("sections").size()).isEqualTo(1);
        assertThat(emptyReport.path("aiRecommendations").get(0).path("analysis").asText()).contains("暂未取得本营业日、本任职的已提交工作");
        JsonNode unchanged=call(get("/api/v1/daily-reports/"+reportId),EMP,null,200);
        assertThat(unchanged.path("rowVersion")).isEqualTo(emptyReport.path("rowVersion"));
        assertThat(unchanged.path("revisions")).isEqualTo(emptyReport.path("revisions"));
        String brush=item("CONSUMABLE","支","支",1,100,0.5), linen=item("LINEN","条","条",1,20,15);
        String record=submit(List.of(line(brush,"3","支",0),line(linen,"4","条",0)),true,201).path("id").asText();
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage where work_record_id=?::uuid",Integer.class,record)).isZero();
        call(post("/api/v1/work-data/records/"+record+"/actions/submit"),EMP,Map.of("expectedVersion",0),200);
        balance(brush,"100","50"); balance(linen,"20","300");
        assertThat(call(get("/api/v1/work-data/records/"+record),EMP,null,200).path("inventoryUsage").path("status").asText()).isEqualTo("PENDING");
        assertThat(reportUsage(revision)).contains("待审核，未扣库", "布草使用，不扣总库存").doesNotContain("inventoryQuantities", "schemaVersion");
        assertThat(summaryQuantity(revision,"CONSUMABLE","pendingQuantity")).isEqualTo("3");
        assertThat(summaryQuantity(revision,"CONSUMABLE","approvedQuantity")).isEqualTo("0");
        review(record,GM,"APPROVED",1,200); review(record,GM,"APPROVED",1,200);
        balance(brush,"97","48.5"); balance(linen,"20","300");
        assertThat(movements(record)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,record)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount from store_cost_entry where source_parent_id=?::uuid",BigDecimal.class,record)).isEqualByComparingTo("1.5");
        assertThat(jdbc.queryForObject("select status from work_record where id=?::uuid",String.class,record)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from notification where source_id=?::uuid and idempotency_key like 'usage-review:%'",Integer.class,record)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update store_work_usage_line set input_quantity=9 where work_record_id=?::uuid",record)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from store_work_usage where work_record_id=?::uuid",record)).isInstanceOf(RuntimeException.class);
        assertThat(reportUsage(revision)).contains("耗用已审核");
        assertThat(summaryQuantity(revision,"CONSUMABLE","approvedQuantity")).isEqualTo("3");
        assertThat(summaryQuantity(revision,"LINEN","approvedQuantity")).isEqualTo("4");
        JsonNode ready=call(get("/api/v1/daily-reports/"+reportId),EMP,null,200);
        assertThat(ready.path("aiRecommendations").get(0).path("analysis").asText()).contains("耗用审核", "已核对实际耗用");
        String templateItemId=ready.path("itemResults").get(0).path("templateItemId").asText();
        call(put("/api/v1/daily-reports/"+reportId+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",ready.path("rowVersion").asLong(),
                "items",List.of(Map.of("templateItemId",templateItemId,"value","冒充自动汇总","exception",false))),400);
        call(put("/api/v1/daily-reports/"+reportId+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",ready.path("rowVersion").asLong(),
                "items",List.of(Map.of("templateItemId",templateItemId,"value","","exception",false))),200);
        assertThat(reportUsage(revision)).contains("耗用已审核");
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/correct"),ADMIN,
                Map.of("quantities",Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(brush,"5","支",0),line(linen,"6","条",0))),
                        "expectedVersion",0,"reason","日报汇总更正验证"),200);
        assertThat(summaryQuantity(revision,"CONSUMABLE","approvedQuantity")).isEqualTo("5");
        assertThat(summaryQuantity(revision,"LINEN","approvedQuantity")).isEqualTo("6");
        balance(brush,"95","47.5");
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/reverse"),ADMIN,Map.of("reason","日报冲销验证"),200);
        assertThat(reportUsage(revision)).contains("已冲销，原库存及成本已退回", "日报冲销验证");
        balance(brush,"100","50");
        assertThat(jdbc.queryForObject("select sum(amount) from store_cost_entry where source_parent_id=?::uuid",BigDecimal.class,record)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,record)).isEqualTo(4);
        JsonNode reversed=call(get("/api/v1/daily-reports/"+reportId),EMP,null,200);
        JsonNode summary=reversed.path("revisions").get(0).path("payloadSnapshot").path("dailySummary");
        assertThat(summary.path("usageItems").isEmpty()).isTrue();
        assertThat(summary.path("excludedUsageRecordCount").asInt()).isEqualTo(1);
        call(post("/api/v1/daily-reports/"+reportId+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",reversed.path("rowVersion").asLong()),200);
        String frozen=jdbc.queryForObject("select payload_snapshot::text from daily_report_revision where id=?",String.class,revision);
        call(get("/api/v1/daily-reports/"+reportId),EMP,null,200);
        assertThat(jdbc.queryForObject("select payload_snapshot::text from daily_report_revision where id=?",String.class,revision)).isEqualTo(frozen);
    }

    @Test void insufficientStockRollsBackEntireReviewThenConcurrentApprovalPostsOnce() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2), b=item("CLEANING_SUPPLY","毫升","毫升",1,1,3);
        String record=submit(List.of(line(a,"2","支",0),line(b,"2","毫升",0)),false,201).path("id").asText();
        review(record,GM,"APPROVED",0,409);
        balance(a,"10","20"); balance(b,"1","3"); assertThat(movements(record)).isZero();
        assertThat(jdbc.queryForObject("select status from work_record where id=?::uuid",String.class,record)).isEqualTo("SUBMITTED");
        receipt(b,9,3);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->review(record,GM,"APPROVED",0,200));
            var two=pool.submit(()->review(record,GM,"APPROVED",0,200)); one.get(); two.get();
        }
        balance(a,"8","16"); balance(b,"8","24"); assertThat(movements(record)).isEqualTo(2);
    }

    @Test void supervisorCorrectionIsAtomicVersionedIdempotentAndReversible() throws Exception {
        String a=item("CONSUMABLE","支","支",1,100,2), linen=item("LINEN","条","条",1,20,10);
        String record=submit(List.of(line(a,"3","支",0),line(linen,"2","条",0)),false,201).path("id").asText();
        review(record,GM,"APPROVED",0,200); balance(a,"97","194");
        Map<String,Object> body=Map.of("quantities",Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(a,"5","支",0),line(linen,"4","条",0))),"expectedVersion",0,"reason","主管核实实际用量");
        String endpoint="/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/correct";
        call(post(endpoint),AGM,body,403); call(post(endpoint),EMP,body,403);
        JsonNode result=call(post(endpoint),GM,body,200); call(post(endpoint),GM,body,200);
        assertThat(result.path("correction_version").asInt()).isEqualTo(1);
        balance(a,"95","190"); balance(linen,"20","200");
        assertThat(jdbc.queryForObject("select sum(amount) from store_cost_entry where source_parent_id=?::uuid",BigDecimal.class,record)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,record)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select input_quantity from store_work_usage_line where work_record_id=?::uuid and item_id=?::uuid",BigDecimal.class,record,a)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("select payload::text from work_record where id=?::uuid",String.class,record)).contains("\"quantity\": \"3\"");
        Map<String,Object> stale=new LinkedHashMap<>(body); stale.put("reason","并发修改不能覆盖");
        call(post(endpoint),GM,stale,409);
        assertThatThrownBy(()->jdbc.update("update store_work_usage_correction set reason='覆盖历史' where work_record_id=?::uuid",record)).isInstanceOf(RuntimeException.class);
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/reverse"),ADMIN,Map.of("reason","撤回更正后耗用"),200);
        balance(a,"100","200");
        assertThat(jdbc.queryForObject("select sum(amount) from store_cost_entry where source_parent_id=?::uuid",BigDecimal.class,record)).isZero();
    }

    @Test void failedCorrectionRollsBackAndConcurrentIdenticalCorrectionOnlyPostsOnce() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2), b=item("CLEANING_SUPPLY","毫升","毫升",1,1,3);
        String record=submit(List.of(line(a,"3","支",0)),false,201).path("id").asText(); review(record,GM,"APPROVED",0,200);
        String endpoint="/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/correct";
        Map<String,Object> tooMany=Map.of("quantities",Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(a,"4","支",0),line(b,"2","毫升",0))),"expectedVersion",0,"reason","测试库存不足");
        call(post(endpoint),GM,tooMany,409); balance(a,"7","14"); balance(b,"1","3");
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage_correction where work_record_id=?::uuid",Integer.class,record)).isZero();
        Map<String,Object> none=Map.of("quantities",Map.of("schemaVersion",1,"noUsage",true,"lines",List.of()),"expectedVersion",0,"reason","实际无耗用");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->call(post(endpoint),GM,none,200)); var second=pool.submit(()->call(post(endpoint),GM,none,200)); first.get(); second.get();
        }
        balance(a,"10","20");
        assertThat(call(get("/api/v1/work-data/records/"+record),EMP,null,200).path("inventoryUsage").path("no_usage").asBoolean()).isTrue();
        Map<String,Object> again=Map.of("quantities",Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(a,"2","支",0))),"expectedVersion",1,"reason","再次核对数量");
        call(post(endpoint),GM,again,200); balance(a,"8","16");
        assertThat(jdbc.queryForObject("select sum(amount) from store_cost_entry where source_parent_id=?::uuid",BigDecimal.class,record)).isEqualByComparingTo("4");
    }

    @Test @Order(1) void housekeepingDailyReportUsesCatalogueAndCannotDoublePostTheUsageTask() throws Exception {
        usageItemCode="HKA_DAILY_REPORT"; setup();
        String a=item("CONSUMABLE","支","支",1,10,2);
        String first=submit(List.of(line(a,"3","支",0)),false,201).path("id").asText(); review(first,GM,"APPROVED",0,200);
        // Create an independent daily-report task through the same public template workflow.
        usageItemCode="HKA_USAGE_RETURN";
        setup();
        JsonNode catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D).param("businessDate",LocalDate.now().toString()).param("itemCode","HKA_USAGE_RETURN"),EMP,null,200);
        // Runs before the shared employee accumulates multiple test shifts on this business date.
        assertThat(catalog.path("reference_usage").path("work_record_id").asText()).isEqualTo(first);
        submit(List.of(line(a,"3","支",0)),false,409);
        String linked=call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",1,"referenceRecordId",first),false),201).path("id").asText();
        balance(a,"7","14"); assertThat(movements(first)).isEqualTo(1); assertThat(movements(linked)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage_report_link where work_record_id=?::uuid",Integer.class,linked)).isEqualTo(1);
        assertThat(call(get("/api/v1/work-data/records/"+linked),EMP,null,200).path("inventoryUsage").path("referenced").asBoolean()).isTrue();
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+first+"/reverse"),ADMIN,Map.of("reason","测试撤回日报耗用"),200);
    }

    @Test void rejectionNeverDeductsAndCorrectedAttemptKeepsHistory() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2);
        String first=submit(List.of(line(a,"8","支",0)),false,201).path("id").asText();
        review(first,GM,"REJECTED",0,200); review(first,GM,"REJECTED",0,200); balance(a,"10","20");
        Map<String,Object> body=recordBody(Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(a,"2","支",0))),false);
        body.put("supersedesWorkRecordId",first);
        String second=call(post("/api/v1/work-data/records"),EMP,body,201).path("id").asText();
        review(second,GM,"APPROVED",0,200); balance(a,"8","16"); assertThat(movements(first)).isZero();
        assertThat(jdbc.queryForObject("select status from store_work_usage where work_record_id=?::uuid",String.class,first)).isEqualTo("REJECTED");
        submit(List.of(line(a,"2","支",0)),false,400); // Same daily task cannot be posted twice with a new record id.
    }

    @Test void permissionsStoreIsolationAndCurrentConfiguredReviewer() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2);
        JsonNode catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D),EMP,null,200);
        assertThat(catalog.toString()).doesNotContain("inventory_value","unit_cost","current_quantity");
        call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",OTHER_H),EMP,null,403);
        call(put("/api/v1/store-resources/hotels/"+H+"/usage-review-config"),EMP,Map.of("reviewerAssignmentId",EA,"expectedVersion",0),403);
        configure(EA,400);
        String record=submit(List.of(line(a,"1","支",0)),false,201).path("id").asText();
        review(record,EMP,"APPROVED",0,403); review(record,AGM,"APPROVED",0,403);
        grantAssistantStockRead(); // Explicit governed grant, never an implicit production permission expansion.
        configure(AGMA,200); review(record,GM,"APPROVED",0,403); review(record,AGM,"APPROVED",0,200);
        balance(a,"9","18");
        assertThat(jdbc.queryForObject("select reviewer_assignment_id_snapshot::text from store_work_usage where work_record_id=?::uuid",String.class,record)).isEqualTo(GMA);
    }

    @Test void invalidQuantityDuplicateForeignItemAndStaleConfigAreRejected() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2);
        submit(List.of(line(a,"1","支",9)),false,409);
        submit(List.of(Map.of("itemId",a,"quantity","1","unit","支","expectedItemVersion",new java.math.BigInteger("18446744073709551616"))),false,409);
        submit(List.of(line(a,"-1","支",0)),false,400);
        submit(List.of(line(a,"0.00001","支",0)),false,400);
        submit(List.of(line(a,"1","未知",0)),false,400);
        submit(List.of(line(a,"1","支",0),line(a,"1","支",0)),false,400);
        submit(List.of(line(UUID.randomUUID().toString(),"1","支",0)),false,400);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isZero();
        String none=call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",1,"noUsage",true,"lines",List.of()),false),201).path("id").asText();
        review(none,GM,"APPROVED",0,200); assertThat(movements(none)).isZero();
    }

    @Test void snapshotSurvivesRatioEditsAndReversalReturnsOriginalCostExactly() throws Exception {
        String a=item("CLEANING_SUPPLY","毫升","升",1000,2000,0.02);
        String record=submit(List.of(line(a,"0.15","升",0)),false,201).path("id").asText();
        jdbc.update("update store_resource_item set issue_factor=500,row_version=row_version+1 where id=?::uuid",a);
        review(record,GM,"APPROVED",0,200); balance(a,"1850","37");
        receipt(a,1000,0.1); // Cost has changed since approval. Reversal must restore the original 3 yuan.
        call(post("/api/v1/store-resources/hotels/"+OTHER_H+"/work-usage/"+record+"/reverse"),ADMIN,Map.of("reason","录入有误"),404);
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/reverse"),EMP,Map.of("reason","录入有误"),403);
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/reverse"),ADMIN,Map.of("reason","录入有误"),200);
        call(post("/api/v1/store-resources/hotels/"+H+"/work-usage/"+record+"/reverse"),ADMIN,Map.of("reason","录入有误"),200);
        balance(a,"3000","140"); assertThat(movements(record)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from work_record where id=?::uuid",String.class,record)).isEqualTo("APPROVED");
        assertThat(call(get("/api/v1/work-data/records/"+record),EMP,null,200).path("inventoryUsage").path("status").asText()).isEqualTo("REVERSED");
    }

    @Test void pendingLinenLocksBaseUnitAndHasNoStockCost() throws Exception {
        String a=item("LINEN","条","条",1,0,20);
        String record=submit(List.of(line(a,"10","条",0)),false,201).path("id").asText();
        assertThatThrownBy(()->jdbc.update("update store_resource_item set unit='包' where id=?::uuid",a)).isInstanceOf(RuntimeException.class);
        review(record,GM,"APPROVED",0,200); balance(a,"0","0"); assertThat(movements(record)).isZero();
    }

    @Test void legacyFreeTextCannotSilentlyDeductAfterOptInAndDraftCanBeSaved() throws Exception {
        Map<String,Object> body=recordBody(Map.of("牙刷",2),false);
        call(post("/api/v1/work-data/records"),EMP,body,400);
        body.put("saveAsDraft",true); call(post("/api/v1/work-data/records"),EMP,body,201);
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage u join work_record r on r.id=u.work_record_id where r.work_expectation_id=?::uuid",Integer.class,expectation)).isZero();
    }

    @Test void disabledReviewerPositionIsReportedWithoutUnexpectedRollback() throws Exception {
        String actualPosition=jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,GMA);
        jdbc.update("update position_definition set status='INACTIVE' where id=?::uuid",actualPosition);
        try {
            assertThat(call(get("/api/v1/store-resources/hotels/"+H+"/usage-review-config"),ADMIN,null,200).path("reviewer_available").asBoolean()).isFalse();
            call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",1,"noUsage",true,"lines",List.of()),false),409);
            configure(GMA,400);
        } finally { jdbc.update("update position_definition set status='ACTIVE' where id=?::uuid",actualPosition); }
    }

    @Test void newTablesHaveForcedTenantIsolation() throws Exception {
        String a=item("CONSUMABLE","支","支",1,10,2);
        submit(List.of(line(a,"1","支",0)),false,201);
        for(String table:List.of("store_usage_review_config","store_work_usage","store_work_usage_line","store_work_usage_correction","store_work_usage_correction_line","store_work_usage_report_link")) {
            assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from pg_policies where tablename=? and qual like '%app.tenant_id%' and with_check like '%app.tenant_id%'",Integer.class,table)).isEqualTo(1);
        }
        try(var connection=PG.getPostgresDatabase().getConnection(); var statement=connection.createStatement()) {
            statement.execute("create role usage_rls_reader nologin nosuperuser nobypassrls");
            statement.execute("grant select on store_usage_review_config,store_work_usage,store_work_usage_line,store_work_usage_correction,store_work_usage_correction_line,store_work_usage_report_link,store_work_usage_current_line to usage_rls_reader");
            connection.setAutoCommit(false);
            statement.execute("set local role usage_rls_reader");
            for(String tenant:List.of(T,"10000000-0000-0000-0000-000000000099")) {
                statement.execute("select set_config('app.tenant_id','"+tenant+"',true)");
                for(String table:List.of("store_usage_review_config","store_work_usage","store_work_usage_line")) {
                    try(var result=statement.executeQuery("select count(*) from "+table)) {
                        result.next(); if(tenant.equals(T))assertThat(result.getInt(1)).isPositive(); else assertThat(result.getInt(1)).isZero();
                    }
                }
                for(String table:List.of("store_work_usage_correction","store_work_usage_correction_line","store_work_usage_report_link","store_work_usage_current_line")) {
                    try(var result=statement.executeQuery("select count(*) from "+table)) {
                        result.next(); if(tenant.equals(T)) assertThat(result.getInt(1)).isGreaterThanOrEqualTo(0); else assertThat(result.getInt(1)).isZero();
                    }
                }
            }
            connection.rollback();
        }
    }

    private String reportUsage(UUID revision) {
        return jdbc.queryForObject("select value::text from daily_report_item_result where revision_id=?",String.class,revision);
    }
    private String summaryQuantity(UUID revision,String category,String field) throws Exception {
        JsonNode summary=mapper.readTree(jdbc.queryForObject("select payload_snapshot->'dailySummary' from daily_report_revision where id=?",String.class,revision));
        for(JsonNode item:summary.path("usageItems")) if(category.equals(item.path("category").asText())) return item.path(field).asText();
        throw new AssertionError("Missing usage category: "+category);
    }
    private UUID createUsageDailyReport(
            String workPackageVersionId,
            String workPackageItemId,
            LocalDate businessDate
    ) {
        UUID templateId = UUID.randomUUID();
        UUID templateVersionId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID sectionVersionId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String suffix = templateId.toString().substring(0, 8);
        jdbc.update("""
                insert into daily_report_template_definition
                    (id, tenant_id, code, name, template_origin, owner_org_unit_id,
                     position_id, status, created_by)
                values (?, ?::uuid, ?, '耗用审核日报测试', 'HQ', ?::uuid,
                        ?::uuid, 'ACTIVE', ?::uuid)
                """, templateId, T, "DRT-REPEATABLE-" + suffix,
                H, "14000000-0000-0000-0000-000000000001", ADMIN);
        jdbc.update("""
                insert into daily_report_section_definition
                    (id, tenant_id, code, name, section_origin, owner_org_unit_id,
                     position_id, status, created_by)
                values (?, ?::uuid, ?, '耗用审核日报事实', 'HQ', ?::uuid,
                        ?::uuid, 'ACTIVE', ?::uuid)
                """, sectionId, T, "DRS-REPEATABLE-" + suffix,
                H, "14000000-0000-0000-0000-000000000001", ADMIN);
        jdbc.update("""
                insert into daily_report_template_version
                    (id, tenant_id, template_id, version_no, lifecycle_status,
                     work_package_version_id, configuration, created_by)
                values (?, ?::uuid, ?, 1, 'DRAFT', ?::uuid,
                        '{"repeatableEventAggregation":true}'::jsonb, ?::uuid)
                """, templateVersionId, T, templateId,
                workPackageVersionId, ADMIN);
        jdbc.update("""
                insert into daily_report_section_version
                    (id, tenant_id, section_definition_id, version_no, lifecycle_status,
                     condition_expression, configuration, created_by)
                values (?, ?::uuid, ?, 1, 'DRAFT', '{}'::jsonb, '{}'::jsonb, ?::uuid)
                """, sectionVersionId, T, sectionId, ADMIN);
        jdbc.update("""
                insert into daily_report_template_section
                    (id, tenant_id, template_version_id, section_version_id,
                     section_role, required, sort_order)
                values (?, ?::uuid, ?, ?, 'BASE', true, 1)
                """, UUID.randomUUID(), T, templateVersionId, sectionVersionId);
        jdbc.update("""
                insert into daily_report_template_item
                    (id, tenant_id, section_version_id, item_code, label, input_type,
                     required, work_package_item_id, evidence_policy, source_policy,
                     validation_rules, option_values, sort_order)
                values (?, ?::uuid, ?, 'usage_fact', '耗用审核',
                        'WORK_RECORD_REFERENCE', false, ?::uuid,
                        '{"inheritFromWorkRecord":true,"includeEvidenceLinks":true}'::jsonb,
                        '{"sourceType":"WORK_RECORD","mode":"AUTO"}'::jsonb,
                        '{}'::jsonb, '[]'::jsonb, 1)
                """, UUID.randomUUID(), T, sectionVersionId, workPackageItemId);
        jdbc.update("""
                insert into daily_report
                    (id, tenant_id, hotel_org_unit_id, org_unit_id, employee_id,
                     position_assignment_id, business_date, timezone, cutoff_local_time,
                     report_deadline_at, template_version_id, work_package_version_id,
                     report_status, review_status, current_revision_no, trace_id,
                     created_by_account_id)
                values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'Asia/Shanghai',
                        time '06:00', now() + interval '12 hours', ?, ?::uuid,
                        'DRAFT', 'NOT_REQUIRED', 1, ?, ?::uuid)
                """, reportId, T, H, D,
                EMPLOYEE, EA, businessDate, templateVersionId,
                workPackageVersionId, UUID.randomUUID(), EMP);
        jdbc.update("""
                insert into daily_report_revision
                    (id, tenant_id, report_id, revision_no, revision_type,
                     revision_status, payload_snapshot, created_by_account_id)
                values (?, ?::uuid, ?, 1, 'ORIGINAL', 'DRAFT', '{}'::jsonb, ?::uuid)
                """, revisionId, T, reportId, EMP);
        jdbc.update("""
                update daily_report set current_revision_id = ?
                where tenant_id = ?::uuid and id = ?
                """, revisionId, T, reportId);
        return revisionId;
    }

    @Test void dailyReportEditorSubmitsOneUsageRecordAndRollsBackInvalidQuantities() throws Exception {
        usageItemCode="HKA_DAILY_REPORT"; setup();
        LocalDate date=LocalDate.now().minusDays(2);
        jdbc.update("update work_package_allocation set valid_from=? where id=(select work_package_allocation_id from work_expectation where id=?::uuid)",date,expectation);
        jdbc.update("update work_expectation set business_date=? where id=?::uuid",date,expectation);
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID revision=createUsageDailyReport(version,itemId,date);
        String report=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        JsonNode detail=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        assertThat(detail.path("inventoryContext").path("catalogue").path("enabled").asBoolean()).isTrue();
        assertThat(detail.path("plannedHygieneSupported").asBoolean()).isTrue();
        Map<String,String> hygiene=Map.of("content","清洗空调滤网\n清洁床底","roomNumbers","0101、A102、1203");
        String brush=item("CONSUMABLE","支","支",1,100,0.5);
        Map<String,Object> quantities=Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(brush,"3","支",0)));
        JsonNode invalid=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",detail.path("rowVersion").asLong(),"items",List.of(),
                        "inventoryQuantities",Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(brush,"0","支",0))),
                        "roomCounts",Map.of("checkoutRooms",6,"stayoverRooms",4)),200);
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",invalid.path("rowVersion").asLong()),400);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isZero();
        assertThat(jdbc.queryForObject("select report_status from daily_report where id=?::uuid",String.class,report)).isEqualTo("DRAFT");
        JsonNode saved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",invalid.path("rowVersion").asLong(),"items",List.of(),
                        "inventoryQuantities",quantities,"roomCounts",Map.of("checkoutRooms",6,"stayoverRooms",4),
                        "plannedHygiene",hygiene),200);
        assertThat(saved.path("revisions").get(0).path("payloadSnapshot").path("inventoryQuantities")).isEqualTo(mapper.valueToTree(quantities));
        JsonNode reopened=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        assertThat(reopened.path("revisions").get(0).path("payloadSnapshot").path("plannedHygiene")).isEqualTo(mapper.valueToTree(hygiene));
        JsonNode submitted=call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        assertThat(submitted.path("reportStatus").asText()).isEqualTo("SUBMITTED");
        String record=jdbc.queryForObject("select id::text from work_record where work_expectation_id=?::uuid",String.class,expectation);
        JsonNode workPayload=mapper.readTree(jdbc.queryForObject("select payload::text from work_record where id=?::uuid",String.class,record));
        assertThat(workPayload.path("plannedHygieneContent").asText()).isEqualTo(hygiene.get("content"));
        assertThat(workPayload.path("plannedHygieneRoomNumbers").asText()).isEqualTo(hygiene.get("roomNumbers"));
        JsonNode managerView=call(get("/api/v1/daily-reports/"+report),GM,null,200);
        assertThat(managerView.path("revisions").get(0).path("payloadSnapshot").path("plannedHygiene")).isEqualTo(mapper.valueToTree(hygiene));
        call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",submitted.path("rowVersion").asLong(),"items",List.of(),
                        "plannedHygiene",Map.of("content","覆盖已提交内容","roomNumbers","9999")),409);
        assertThat(call(get("/api/v1/daily-reports/"+report),EMP,null,200).path("revisions").get(0).path("payloadSnapshot").path("plannedHygiene"))
                .isEqualTo(mapper.valueToTree(hygiene));
        balance(brush,"100","50");
        review(record,GM,"APPROVED",0,200); review(record,GM,"APPROVED",0,200);
        balance(brush,"97","48.5");
        assertThat(movements(record)).isEqualTo(1);
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",submitted.path("rowVersion").asLong()),409);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isEqualTo(1);
    }

    @Test void dailyReportReferencesExistingWorkAndRejectsUnrelatedUsage() throws Exception {
        usageItemCode="HKA_DAILY_REPORT"; setup();
        LocalDate date=LocalDate.now().minusDays(1);
        jdbc.update("update work_package_allocation set valid_from=? where id=(select work_package_allocation_id from work_expectation where id=?::uuid)",date,expectation);
        jdbc.update("update work_expectation set business_date=? where id=?::uuid",date,expectation);
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        String brush=item("CONSUMABLE","支","盒",12,100,0.5);
        var body=recordBody(Map.of("schemaVersion",1,"noUsage",false,"lines",List.of(line(brush,"2","盒",0))),false);
        body.put("businessDate",date.toString());
        String record=call(post("/api/v1/work-data/records"),EMP,body,201).path("id").asText();
        UUID revision=createUsageDailyReport(version,itemId,date);
        String report=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        JsonNode detail=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        assertThat(detail.path("inventoryContext").path("catalogue").path("reference_usage").path("work_record_id").asText()).isEqualTo(record);
        JsonNode invalid=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",detail.path("rowVersion").asLong(),"items",List.of(),
                        "inventoryQuantities",Map.of("schemaVersion",1,"referenceRecordId",UUID.randomUUID().toString())),200);
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",invalid.path("rowVersion").asLong()),400);
        JsonNode saved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",invalid.path("rowVersion").asLong(),"items",List.of(),
                        "inventoryQuantities",Map.of("schemaVersion",1,"referenceRecordId",record)),200);
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isEqualTo(1);
        balance(brush,"100","50");
        assertThat(movements(record)).isZero();
    }

    @Test void plannedHygieneCanBeClearedAndOldClientsDoNotEraseIt() throws Exception {
        usageItemCode="HKA_DAILY_REPORT"; setup();
        LocalDate date=LocalDate.now().minusDays(4);
        jdbc.update("update work_package_allocation set valid_from=? where id=(select work_package_allocation_id from work_expectation where id=?::uuid)",date,expectation);
        jdbc.update("update work_expectation set business_date=? where id=?::uuid",date,expectation);
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID revision=createUsageDailyReport(version,itemId,date);
        String report=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        JsonNode detail=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        Map<String,String> hygiene=Map.of("content","清洁床底","roomNumbers","001、002");
        for (String field:List.of("content","roomNumbers")) {
            call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                    Map.of("revisionId",revision,"expectedVersion",detail.path("rowVersion").asLong(),"items",List.of(),
                            "plannedHygiene",Map.of(field,"长".repeat(field.equals("content")?2001:1001))),400);
        }
        JsonNode saved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",detail.path("rowVersion").asLong(),"items",List.of(),"plannedHygiene",hygiene),200);
        JsonNode legacySaved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong(),"items",List.of()),200);
        assertThat(legacySaved.path("revisions").get(0).path("payloadSnapshot").path("plannedHygiene")).isEqualTo(mapper.valueToTree(hygiene));
        JsonNode cleared=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",legacySaved.path("rowVersion").asLong(),"items",List.of(),
                        "plannedHygiene",Map.of("content","  ","roomNumbers",""),
                        "inventoryQuantities",Map.of("schemaVersion",1,"noUsage",true,"lines",List.of()),
                        "roomCounts",Map.of("checkoutRooms",0,"stayoverRooms",0)),200);
        assertThat(cleared.path("revisions").get(0).path("payloadSnapshot").path("plannedHygiene"))
                .isEqualTo(mapper.valueToTree(Map.of("content","","roomNumbers","")));
        JsonNode submitted=call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",cleared.path("rowVersion").asLong()),200);
        assertThat(submitted.path("reportStatus").asText()).isEqualTo("SUBMITTED");
    }

    @Test void otherReportsCannotWriteHousekeepingPlannedHygiene() throws Exception {
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID revision=createUsageDailyReport(version,itemId,LocalDate.now().minusDays(5));
        String report=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        JsonNode detail=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        assertThat(detail.path("plannedHygieneSupported").asBoolean()).isFalse();
        call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",detail.path("rowVersion").asLong(),"items",List.of(),
                        "plannedHygiene",Map.of("content","无关岗位","roomNumbers","101")),400);
        assertThat(call(get("/api/v1/daily-reports/"+report),EMP,null,200).path("rowVersion")).isEqualTo(detail.path("rowVersion"));
    }

    @Test void issuePickerValidatesConfigurationWithoutPostingConsumption() throws Exception {
        usageItemCode="HKA_ITEM_ISSUE"; setup();
        String brush=item("CONSUMABLE","支","盒",12,100,0.5);
        JsonNode catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D).param("itemCode","HKA_ITEM_ISSUE"),EMP,null,200);
        assertThat(catalog.path("mode").asText()).isEqualTo("ISSUE");
        jdbc.update("update store_resource_item set employee_usage_enabled=false where id=?::uuid",brush);
        assertThat(call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D).param("itemCode","HKA_ITEM_ISSUE"),EMP,null,200).path("items").toString()).doesNotContain(brush);
        submit(List.of(line(brush,"2","盒",0)),false,400);
        jdbc.update("update store_resource_item set employee_usage_enabled=true where id=?::uuid",brush);
        submit(List.of(line(brush,"2","支",0)),false,400);
        String record=submit(List.of(line(brush,"2","盒",0)),false,201).path("id").asText();
        UUID previousManager=jdbc.queryForObject("select manager_assignment_id from employee_position_assignment where id=?::uuid",UUID.class,EA);
        jdbc.update("update employee_position_assignment set manager_assignment_id=?::uuid where id=?::uuid",GMA,EA);
        try { review(record,GM,"APPROVED",0,200); }
        finally { jdbc.update("update employee_position_assignment set manager_assignment_id=? where id=?::uuid",previousManager,EA); }
        balance(brush,"100","50");
        assertThat(movements(record)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,record)).isZero();
    }

    private void configure(String assignment,int status) throws Exception {
        long v=call(get("/api/v1/store-resources/hotels/"+H+"/usage-review-config"),ADMIN,null,200).path("row_version").asLong(-1);
        call(put("/api/v1/store-resources/hotels/"+H+"/usage-review-config"),ADMIN,Map.of("reviewerAssignmentId",assignment,"expectedVersion",v),status);
    }
    private void grantAssistantStockRead() throws Exception {
        String positionId="14000000-0000-0000-0000-000000000007";
        JsonNode position=null;
        for(JsonNode candidate:call(get("/api/v1/org/positions"),ADMIN,null,200)) {
            if(positionId.equals(candidate.path("id").asText()))position=candidate;
        }
        assertThat(position).isNotNull();
        JsonNode profile=position.path("profile");
        Set<String> codes=new LinkedHashSet<>(); profile.path("permissionCodes").forEach(code->codes.add(code.asText()));
        codes.addAll(List.of("work-record.review","store-resource.read","ui.module.store-resources"));
        JsonNode draft=call(put("/api/v1/org/positions/"+positionId+"/profile/draft"),ADMIN,
                Map.of("expectedProfileVersion",profile.path("draftRowVersion").asLong(),"permissionCodes",codes,
                        "authorizationScopeType",profile.path("authorizationScopeType").asText(),"wecomSelfSelectable",profile.path("wecomSelfSelectable").asBoolean()),200);
        call(post("/api/v1/org/positions/"+positionId+"/profile/publish"),ADMIN,
                Map.of("expectedProfileVersion",draft.path("draftRowVersion").asLong(),"expectedPositionVersion",position.path("rowVersion").asLong()),200);
    }
    private String item(String category,String base,String unit,int factor,int qty,double cost) throws Exception {
        String id=call(post("/api/v1/store-resources/hotels/"+H+"/items"),ADMIN,Map.of("sku",UUID.randomUUID().toString(),"name","耗用测试物品","category",category,"unit",base,"unitCost",cost,"minimumQuantity",0,"issueUnit",unit,"issueFactor",factor),201).path("id").asText();
        if(qty>0)receipt(id,qty,cost); return id;
    }
    private void receipt(String id,int qty,double cost) throws Exception {
        call(post("/api/v1/store-resources/hotels/"+H+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",id,"movementType","RECEIPT","quantity",qty,"unitCost",cost,"reason","测试入库"),201);
    }
    private Map<String,Object> line(String id,String quantity,String unit,int v) { return Map.of("itemId",id,"quantity",quantity,"unit",unit,"expectedItemVersion",v); }
    private JsonNode submit(List<Map<String,Object>> lines,boolean draft,int status) throws Exception {
        return call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",1,"noUsage",false,"lines",lines),draft),status);
    }
    private Map<String,Object> recordBody(Object input,boolean draft) {
        Map<String,Object> b=new LinkedHashMap<>(Map.of("orgUnitId",D,"employeeId",EMPLOYEE,"positionAssignmentId",EA,"formVersionId",form,"businessDate",LocalDate.now().toString(),"payload",Map.of("inventoryQuantities",input),"completionStatement","已核对实际耗用","saveAsDraft",draft));
        b.put("targetOrgUnitId",D); b.put("workExpectationId",expectation); return b;
    }
    private JsonNode review(String id,String actor,String outcome,int v,int status) throws Exception {
        return call(post("/api/v1/work-data/records/"+id+"/actions/review"),actor,Map.of("outcome",outcome,"reason","核对数量","expectedVersion",v),status);
    }
    private void balance(String id,String quantity,String amount) {
        var row=jdbc.queryForMap("select quantity,inventory_value from store_inventory_balance where item_id=?::uuid",id);
        assertThat((BigDecimal)row.get("quantity")).isEqualByComparingTo(quantity); assertThat((BigDecimal)row.get("inventory_value")).isEqualByComparingTo(amount);
    }
    private int movements(String record) { return jdbc.queryForObject("select count(*) from store_inventory_movement where source_id=?::uuid",Integer.class,record); }
    private JsonNode call(MockHttpServletRequestBuilder request,String actor,Object body,int status) throws Exception {
        request.header("X-Tenant-Id",T).header("X-Actor-Id",actor);
        if(actor.equals(EMP))request.header("X-Assignment-Id",EA);
        if(actor.equals(GM))request.header("X-Assignment-Id",GMA);
        if(actor.equals(AGM))request.header("X-Assignment-Id",AGMA);
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
