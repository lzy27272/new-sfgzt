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
    private static final String CUSTODY_ACTOR="19000000-0000-0000-0000-000000000005", CUSTODY_ASSIGNMENT="19200000-0000-0000-0000-000000000005";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private String form, version, expectation;
    private String usageItemCode="HKA_USAGE_RETURN";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL); r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres"); r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true); r.add("app.database.rls-enabled",()->true);
        r.add("app.attachments.root",()->java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"custody-test-"+PG.getPort()).toString());
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

    @Test void oneLostEntryRoutesBeforeReportSubmissionAndFinalReportOnlyReferencesIt() throws Exception {
        var report=activityReport(10);String id=report.path("id").asText(),revision=report.path("currentRevisionId").asText();
        String photo=activityPhoto("/api/v1/daily-reports/"+id+"/activity-photos",EMP,EA);
        var entry=lostEntry(photo);
        var unfinished=new LinkedHashMap<>(entry);unfinished.put("id",UUID.randomUUID().toString());
        unfinished.put("itemName","");unfinished.put("quantity",null);unfinished.put("photoIds",List.of());
        var partial=Map.of("schemaVersion",1,"lostPropertyOccurred",true,"lostProperties",List.of(unfinished),
                "serviceCases",List.of(Map.of("id",UUID.randomUUID().toString(),"roomNumber","","serviceContent","","photoIds",List.of())));
        var saved=saveActivities(id,revision,report.path("rowVersion").asLong(),partial,false);
        String endpoint="/api/v1/daily-reports/"+id+"/lost-property-entries/"+entry.get("id")+"/submit";
        var request=Map.of("expectedVersion",saved.path("rowVersion").asLong(),"entry",entry);
        var sent=call(post(endpoint).header("Idempotency-Key","immediate-send"),EMP,request,200);
        assertThat(sent.path("reportStatus").asText()).isEqualTo("DRAFT");
        assertThat(sent.path("revisions").get(0).path("payloadSnapshot").path("housekeepingActivities").path("lostProperties").size()).isEqualTo(2);
        assertThat(sent.path("revisions").get(0).path("payloadSnapshot").path("housekeepingActivities").path("serviceCases").size()).isEqualTo(1);
        String caseId=jdbc.queryForObject("select id::text from lost_property_case where report_id=?::uuid",String.class,id);
        assertThat(jdbc.queryForObject("select state from lost_property_case where id=?::uuid",String.class,caseId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select remaining_quantity from lost_property_case where id=?::uuid",Integer.class,caseId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from work_record where payload->>'dailyReportId'=?",Integer.class,id)).isZero();
        call(post(endpoint).header("Idempotency-Key","immediate-send"),EMP,request,200);
        var changed=new LinkedHashMap<>(entry);changed.put("quantity",4);
        call(post(endpoint).header("Idempotency-Key","immediate-send"),EMP,Map.of("expectedVersion",saved.path("rowVersion").asLong(),"entry",changed),409);
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='LOST_PROPERTY' and source_id=?::uuid",Integer.class,caseId)).isOne();
        var states=call(get("/api/v1/daily-reports/"+id+"/lost-property-entries"),EMP,null,200);
        assertThat(states.get(0).path("entryId").asText()).isEqualTo(entry.get("id"));
        var received=custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","early-receive"),
                Map.of("expectedVersion",0,"outcome","RECEIVED","storageLocation","前台A2"),200);
        assertThat(received.path("remainingQuantity").asInt()).isEqualTo(3);
        // Neither a draft save nor a final report may rewrite or remove a routed custody entry.
        var current=call(get("/api/v1/daily-reports/"+id),EMP,null,200);
        call(put("/api/v1/daily-reports/"+id+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",current.path("rowVersion").asLong(),"items",List.of(),
                        "housekeepingActivities",Map.of("schemaVersion",1,"lostPropertyOccurred",false,"lostProperties",List.of(),"serviceCases",List.of())),400);
        call(put("/api/v1/daily-reports/"+id+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",current.path("rowVersion").asLong(),"items",List.of(),"housekeepingActivities",activities(changed,null)),400);
        saved=saveActivities(id,revision,current.path("rowVersion").asLong(),activities(entry,null),true);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        assertThat(jdbc.queryForObject("select count(*) from lost_property_case where report_id=?::uuid",Integer.class,id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='LOST_PROPERTY' and source_id=?::uuid and notification_type='LOST_PROPERTY_REVIEW'",Integer.class,caseId)).isOne();
        assertThat(jdbc.queryForObject("select state from lost_property_case where id=?::uuid",String.class,caseId)).isEqualTo("IN_CUSTODY");
        call(post(endpoint).header("Idempotency-Key","immediate-send"),EMP,request,200);
    }

    @Test void immediateLostEntryRejectsSelfRecipientStaleVersionAndOtherActorsWithoutSaving() throws Exception {
        var report=activityReport(11);String id=report.path("id").asText();
        String photo=activityPhoto("/api/v1/daily-reports/"+id+"/activity-photos",EMP,EA);
        var entry=lostEntry(photo);
        String endpoint="/api/v1/daily-reports/"+id+"/lost-property-entries/"+entry.get("id")+"/submit";
        long version=report.path("rowVersion").asLong();
        entry.put("recipientAssignmentId",EA);
        call(post(endpoint).header("Idempotency-Key","self"),EMP,Map.of("expectedVersion",version,"entry",entry),400);
        entry.put("recipientAssignmentId",CUSTODY_ASSIGNMENT);
        call(post(endpoint).header("Idempotency-Key","stale"),EMP,Map.of("expectedVersion",version+1,"entry",entry),409);
        call(post(endpoint).header("Idempotency-Key","other"),GM,Map.of("expectedVersion",version,"entry",entry),403);
        var wrongId=new LinkedHashMap<>(entry);wrongId.put("id",UUID.randomUUID().toString());
        call(post(endpoint).header("Idempotency-Key","wrong-id"),EMP,Map.of("expectedVersion",version,"entry",wrongId),400);
        assertThat(jdbc.queryForObject("select count(*) from lost_property_case where report_id=?::uuid",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select row_version from daily_report where id=?::uuid",Long.class,id)).isEqualTo(version);
    }

    @Test void dailyActivitiesCommitWithReportAndCustodyApprovalReleaseAreIsolatedAndIdempotent() throws Exception {
        var report=activityReport(6);String id=report.path("id").asText();
        String revision=report.path("revisions").get(0).path("id").asText();
        var options=call(get("/api/v1/daily-reports/"+id+"/activity-recipients"),EMP,null,200);
        assertThat(options.toString()).contains(CUSTODY_ASSIGNMENT).doesNotContain(EA);
        String photo=activityPhoto("/api/v1/daily-reports/"+id+"/activity-photos",EMP,EA);
        String servicePhoto=activityPhoto("/api/v1/daily-reports/"+id+"/activity-photos",EMP,EA);
        var entry=lostEntry(photo); var payload=activities(entry,servicePhoto);
        var saved=saveActivities(id,revision,report.path("rowVersion").asLong(),payload,false);
        assertThat(jdbc.queryForObject("select count(*) from lost_property_case where report_id=?::uuid",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='LOST_PROPERTY' and source_id in(select id from lost_property_case where report_id=?::uuid)",Integer.class,id)).isZero();
        assertThat(call(get("/api/v1/daily-reports/"+id),EMP,null,200).path("revisions").get(0).path("payloadSnapshot").path("housekeepingActivities").path("serviceCases").size()).isEqualTo(1);
        // Missing usage confirmation fails after activity creation: the outer transaction must undo both.
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),400);
        assertThat(jdbc.queryForObject("select count(*) from lost_property_case where report_id=?::uuid",Integer.class,id)).isZero();
        saved=saveActivities(id,revision,saved.path("rowVersion").asLong(),payload,true);
        var submitted=call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        assertThat(submitted.path("reportStatus").asText()).isEqualTo("SUBMITTED");
        String caseId=jdbc.queryForObject("select id::text from lost_property_case where report_id=?::uuid",String.class,id);
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='LOST_PROPERTY' and source_id=?::uuid",Integer.class,caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select remaining_quantity from lost_property_case where id=?::uuid",Integer.class,caseId)).isZero();
        call(get("/api/v1/lost-properties/"+caseId),AGM,null,403);
        // The recipient's primary post is a supervisor, but the message targets their front-desk post.
        assertThat(call(get("/api/v1/lost-properties/"+caseId+"/entry-assignment"),CUSTODY_ACTOR,null,200).path("assignmentId").asText()).isEqualTo(CUSTODY_ASSIGNMENT);
        call(get("/api/v1/lost-properties/"+caseId+"/entry-assignment"),AGM,null,403);
        var receive=Map.of("expectedVersion",0,"outcome","RECEIVED","storageLocation","前台保险柜A2","note","数量照片相符");
        call(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","wrong-recipient"),EMP,receive,403);
        custodyCall(get("/api/v1/lost-properties/"+caseId),null,200);
        mvc.perform(get("/api/v1/lost-properties/"+caseId+"/photos/"+photo).header("X-Tenant-Id",T).header("X-Actor-Id",CUSTODY_ACTOR).header("X-Assignment-Id",CUSTODY_ASSIGNMENT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(get("/api/v1/lost-properties/"+caseId+"/photos/"+servicePhoto).header("X-Tenant-Id",T).header("X-Actor-Id",CUSTODY_ACTOR).header("X-Assignment-Id",CUSTODY_ASSIGNMENT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        var approved=custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","receive"),receive,200);
        custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","receive"),receive,200);
        assertThat(approved.path("remainingQuantity").asInt()).isEqualTo(3);
        custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","stale"),receive,409);
        String handover=activityPhoto("/api/v1/lost-properties/"+caseId+"/photos",CUSTODY_ACTOR,CUSTODY_ASSIGNMENT);
        var release=new LinkedHashMap<String,Object>(Map.of("expectedVersion",1,"quantity",2,"recipientName","张女士","verification","房号及物品特征核对一致","photoIds",List.of(handover)));
        var partial=custodyCall(post("/api/v1/lost-properties/"+caseId+"/release").header("Idempotency-Key","release-first"),release,200);
        custodyCall(post("/api/v1/lost-properties/"+caseId+"/release").header("Idempotency-Key","release-first"),release,200);
        assertThat(partial.path("remainingQuantity").asInt()).isEqualTo(1);
        release.put("expectedVersion",2);
        custodyCall(post("/api/v1/lost-properties/"+caseId+"/release").header("Idempotency-Key","over-release"),release,400);
        release.put("quantity",1);
        assertThat(custodyCall(post("/api/v1/lost-properties/"+caseId+"/release").header("Idempotency-Key","release-final"),release,200).path("state").asText()).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject("select count(*) from lost_property_event where case_id=?::uuid",Integer.class,caseId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,caseId)).isZero();
        assertThatThrownBy(()->jdbc.update("update lost_property_event set kind='RETURNED' where case_id=?::uuid",caseId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from daily_activity_photo where id=?::uuid",photo)).isInstanceOf(RuntimeException.class);
    }

    @Test void dailyActivitiesValidateSelfOtherHotelAndReturnWithReason() throws Exception {
        var report=activityReport(7);String id=report.path("id").asText(),revision=report.path("revisions").get(0).path("id").asText();
        String photo=activityPhoto("/api/v1/daily-reports/"+id+"/activity-photos",EMP,EA);
        var entry=lostEntry(photo);entry.put("recipientAssignmentId",EA);
        var saved=saveActivities(id,revision,report.path("rowVersion").asLong(),activities(entry,null),true);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),400);
        UUID other=UUID.randomUUID();
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from) values(?,?::uuid,'19100000-0000-0000-0000-000000000004',?::uuid,'14000000-0000-0000-0000-000000000001',false,current_date)",other,T,OTHER_H);
        entry.put("recipientAssignmentId",other.toString());
        saved=saveActivities(id,revision,saved.path("rowVersion").asLong(),activities(entry,null),true);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),400);
        entry.put("recipientAssignmentId",CUSTODY_ASSIGNMENT);
        saved=saveActivities(id,revision,saved.path("rowVersion").asLong(),activities(entry,null),true);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        String caseId=jdbc.queryForObject("select id::text from lost_property_case where report_id=?::uuid",String.class,id);
        call(get("/api/v1/lost-properties/"+caseId).header("X-Assignment-Id",other.toString()),CUSTODY_ACTOR,null,403);
        var wecom=custodyWecomWorker();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(wecom,"enqueueTaskNotifications");
        assertThat(custodyDeliveryCount(caseId)).isEqualTo(1);
        custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","return-no-reason"),Map.of("expectedVersion",0,"outcome","RETURNED"),400);
        assertThat(custodyCall(post("/api/v1/lost-properties/"+caseId+"/review").header("Idempotency-Key","return"),Map.of("expectedVersion",0,"outcome","RETURNED","note","请补充抽屉位置"),200).path("state").asText()).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("select remaining_quantity from lost_property_case where id=?::uuid",Integer.class,caseId)).isZero();
        List<?> staleClaims=org.springframework.test.util.ReflectionTestUtils.invokeMethod(wecom,"claim");
        assertThat(staleClaims).extracting("sourceId").doesNotContain(UUID.fromString(caseId));
        var submitted=call(get("/api/v1/daily-reports/"+id),EMP,null,200);
        var correction=call(post("/api/v1/daily-reports/"+id+"/corrections").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("expectedVersion",submitted.path("rowVersion").asLong(),"reason","补充发现位置"),201);
        String correctedRevision=jdbc.queryForObject("select current_revision_id::text from daily_report where id=?::uuid",String.class,id);
        entry.put("foundLocation","床头柜左侧抽屉");
        // Editing already submitted quantities is still rejected; correcting only the lost item is allowed.
        saved=saveActivities(id,correctedRevision,correction.path("rowVersion").asLong(),activities(entry,null),false);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",correctedRevision,"expectedVersion",saved.path("rowVersion").asLong()),400);
        assertThat(jdbc.queryForObject("select state from lost_property_case where id=?::uuid",String.class,caseId)).isEqualTo("RETURNED");
        saved=saveActivities(id,correctedRevision,saved.path("rowVersion").asLong(),activities(entry,null),true);
        call(post("/api/v1/daily-reports/"+id+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",correctedRevision,"expectedVersion",saved.path("rowVersion").asLong()),200);
        assertThat(jdbc.queryForObject("select state from lost_property_case where id=?::uuid",String.class,caseId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from lost_property_case where report_id=?::uuid",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='LOST_PROPERTY' and source_id=?::uuid",Integer.class,caseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from lost_property_event where case_id=?::uuid",Integer.class,caseId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from work_record where payload->>'dailyReportId'=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage u join work_record w on w.id=u.work_record_id where w.payload->>'dailyReportId'=?",Integer.class,id)).isEqualTo(1);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(wecom,"enqueueTaskNotifications");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(wecom,"enqueueTaskNotifications");
        assertThat(custodyDeliveryCount(caseId)).isEqualTo(2);
        List<?> freshClaims=org.springframework.test.util.ReflectionTestUtils.invokeMethod(wecom,"claim");
        assertThat(freshClaims).filteredOn("sourceId",UUID.fromString(caseId)).hasSize(1);
    }

    private int custodyDeliveryCount(String caseId) {
        return jdbc.queryForObject("select count(*) from notification_delivery d join notification n on n.id=d.notification_id and n.tenant_id=d.tenant_id where n.source_type='LOST_PROPERTY' and n.source_id=?::uuid and d.channel='WECHAT'",Integer.class,caseId);
    }
    private cn.sifangguan.hotelaios.integrations.wecom.WeComDeliveryWorker custodyWecomWorker() {
        String corp="custody-test-"+UUID.randomUUID();
        var properties=org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComProperties.class);
        org.mockito.Mockito.when(properties.tenantId()).thenReturn(UUID.fromString(T));
        org.mockito.Mockito.when(properties.corpId()).thenReturn(corp);
        jdbc.update("insert into wecom_user_binding(tenant_id,corp_id,wecom_user_id,account_id,preferred_assignment_id,user_id_fingerprint) values(?::uuid,?,'local-custody-test',?::uuid,?::uuid,repeat('b',64))",T,corp,CUSTODY_ACTOR,CUSTODY_ASSIGNMENT);
        // Only enqueue/claim are invoked. The API mock can never send real messages.
        return new cn.sifangguan.hotelaios.integrations.wecom.WeComDeliveryWorker(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                new cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext(jdbc,true),
                new org.springframework.transaction.support.TransactionTemplate(transactions),
                org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComApiClient.class),properties,100);
    }

    private JsonNode activityReport(int daysAgo) throws Exception {
        usageItemCode="HKA_DAILY_REPORT";setup();LocalDate date=LocalDate.now().minusDays(daysAgo);
        jdbc.update("update work_package_allocation set valid_from=? where id=(select work_package_allocation_id from work_expectation where id=?::uuid)",date,expectation);
        jdbc.update("update work_expectation set business_date=? where id=?::uuid",date,expectation);
        String itemId=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID rev=createUsageDailyReport(version,itemId,date);
        String id=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,rev);
        return call(get("/api/v1/daily-reports/"+id),EMP,null,200);
    }
    private JsonNode saveActivities(String id,String revision,long version,Map<String,Object> activities,boolean validUsage) throws Exception {
        return call(put("/api/v1/daily-reports/"+id+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,
                Map.of("revisionId",revision,"expectedVersion",version,"items",List.of(),"roomCounts",Map.of("checkoutRooms",0,"stayoverRooms",0),
                       "inventoryQuantities",Map.of("schemaVersion",1,"noUsage",validUsage,"lines",List.of()),"housekeepingActivities",activities),200);
    }
    private Map<String,Object> lostEntry(String photo) {
        return new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"itemName","充电器","quantity",3,"roomNumber","1208","foundLocation","床头柜",
                "foundAt",OffsetDateTime.now().minusHours(1).toString(),"recipientAssignmentId",CUSTODY_ASSIGNMENT,"notes","黑色","photoIds",List.of(photo)));
    }
    private Map<String,Object> activities(Map<String,Object> entry,String servicePhoto) {
        return Map.of("schemaVersion",1,"lostPropertyOccurred",true,"lostProperties",List.of(entry),"serviceCases",servicePhoto==null?List.of():List.of(Map.of("id",UUID.randomUUID().toString(),"roomNumber","1210","serviceContent","协助客人搬运行李","photoIds",List.of(servicePhoto))));
    }
    private JsonNode custodyCall(MockHttpServletRequestBuilder request,Object body,int status) throws Exception {
        return call(request.header("X-Assignment-Id",CUSTODY_ASSIGNMENT),CUSTODY_ACTOR,body,status);
    }
    private String activityPhoto(String base,String actor,String assignment) throws Exception {
        String id=UUID.randomUUID().toString();
        var image=new java.awt.image.BufferedImage(8,4,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("file","scene.png","image/png",bytes.toByteArray());
        var request=multipart(base+"/"+id).file(file).with(r->{r.setMethod("PUT");return r;});
        if(!actor.equals(EMP))request.header("X-Assignment-Id",assignment);
        call(request,actor,null,200);
        return id;
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
    @Test void quickListsAreHotelPositionScopedOrderedVersionedAndPermissionChecked() throws Exception {
        String position=jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,EA);
        String endpoint="/api/v1/store-resources/hotels/"+H+"/usage-quick-lists/"+position;
        String brush=item("CONSUMABLE","支","支",1,10,2), linen=item("LINEN","条","条",1,10,2);
        var before=call(get(endpoint),ADMIN,null,200);
        var body=Map.of("itemIds",List.of(linen,brush),"expectedVersion",before.path("row_version").asLong());
        call(put(endpoint),EMP,body,403);
        var saved=call(put(endpoint),ADMIN,body,200);
        assertThat(saved.path("item_ids")).isEqualTo(mapper.valueToTree(List.of(linen,brush)));
        call(put(endpoint),ADMIN,body,409);
        var catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D),EMP,null,200);
        assertThat(catalog.path("quick_list").path("item_ids")).isEqualTo(mapper.valueToTree(List.of(linen,brush)));
        assertThat(catalog.toString()).doesNotContain("unit_cost","inventory_value","current_quantity");
        var other=call(get("/api/v1/store-resources/hotels/"+OTHER_H+"/usage-quick-lists/"+position),ADMIN,null,200);
        assertThat(other.path("configured").asBoolean()).isFalse();
        call(put("/api/v1/store-resources/hotels/"+OTHER_H+"/usage-quick-lists/"+position),ADMIN,Map.of("itemIds",List.of(brush),"expectedVersion",-1),400);
        call(get("/api/v1/store-resources/hotels/"+OTHER_H+"/usage-quick-lists/"+position),GM,null,403);
        call(put(endpoint),ADMIN,Map.of("itemIds",List.of(brush,brush),"expectedVersion",saved.path("row_version").asLong()),400);
        jdbc.update("update store_resource_item set employee_usage_enabled=false,row_version=row_version+1 where id=?::uuid",linen);
        call(put(endpoint),ADMIN,Map.of("itemIds",List.of(linen),"expectedVersion",saved.path("row_version").asLong()),400);
        call(put(endpoint),ADMIN,Map.of("itemIds",List.of(),"expectedVersion",saved.path("row_version").asLong()),200);
        var empty=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D),EMP,null,200);
        assertThat(empty.path("quick_list").path("configured").asBoolean()).isTrue();
        assertThat(empty.path("quick_list").path("item_ids").isEmpty()).isTrue();
        call(get("/api/v1/store-resources/hotels/"+H+"/usage-quick-list-positions"),ADMIN,null,200);
    }

    @Test void quickEntryRetainsZeroBlankAndPositiveSnapshotAndPostsOnlyAfterReview() throws Exception {
        usageItemCode="NSA_DAILY_HANDOVER"; setup();
        String brush=item("CONSUMABLE","支","支",1,10,2), linen=item("LINEN","条","条",1,10,2), soap=item("CLEANING_SUPPLY","毫升","毫升",1,10,2);
        var input=new LinkedHashMap<String,Object>(Map.of("schemaVersion",2,"remainingUnusedConfirmed",false,"lines",List.of(line(brush,"3","支",0),line(linen,"0","条",0),line(soap,"","毫升",0))));
        call(post("/api/v1/work-data/records"),EMP,recordBody(input,false),400);
        input.put("remainingUnusedConfirmed",true);
        String record=call(post("/api/v1/work-data/records"),EMP,recordBody(input,false),201).path("id").asText();
        balance(brush,"10","20");
        assertThat(mapper.readTree(jdbc.queryForObject("select payload::text from work_record where id=?::uuid",String.class,record)).path("inventoryQuantities")).isEqualTo(mapper.valueToTree(input));
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage_line where work_record_id=?::uuid",Integer.class,record)).isEqualTo(1);
        review(record,GM,"APPROVED",0,200); review(record,GM,"APPROVED",0,200);
        balance(brush,"7","14"); balance(linen,"10","20"); balance(soap,"10","20");
        assertThat(movements(record)).isEqualTo(1);
    }

    @Test void quickEntryChecksVersionsAndScopeEvenForZerosAndConfirmedBlanks() throws Exception {
        usageItemCode="NSA_DAILY_HANDOVER"; setup();
        String brush=item("CONSUMABLE","支","支",1,10,2);
        for (String quantity:List.of("0","")) {
            call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(brush,quantity,"支",99))),false),409);
            call(post("/api/v1/work-data/records"),EMP,recordBody(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(UUID.randomUUID().toString(),quantity,"支",0))),false),409);
        }
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isZero();
        var input=Map.of("schemaVersion",2,"remainingUnusedConfirmed",false,"lines",List.of(line(brush,"0","支",0)));
        String record=call(post("/api/v1/work-data/records"),EMP,recordBody(input,false),201).path("id").asText();
        review(record,GM,"APPROVED",0,200); balance(brush,"10","20"); assertThat(movements(record)).isZero();
    }

    @Test void dailyQuickEntryDraftPreservesBlankAndZeroUntilOneAtomicReportSubmission() throws Exception {
        usageItemCode="HKA_DAILY_REPORT"; setup();
        LocalDate date=LocalDate.now().minusDays(9);
        jdbc.update("update work_package_allocation set valid_from=? where id=(select work_package_allocation_id from work_expectation where id=?::uuid)",date,expectation);
        jdbc.update("update work_expectation set business_date=? where id=?::uuid",date,expectation);
        String workItem=jdbc.queryForObject("select work_package_item_id::text from work_expectation where id=?::uuid",String.class,expectation);
        UUID revision=createUsageDailyReport(version,workItem,date);
        String report=jdbc.queryForObject("select report_id::text from daily_report_revision where id=?",String.class,revision);
        String brush=item("CONSUMABLE","支","支",1,100,0.5), linen=item("LINEN","条","条",1,10,2), soap=item("CLEANING_SUPPLY","毫升","毫升",1,10,2);
        var quantities=new LinkedHashMap<String,Object>(Map.of("schemaVersion",2,"remainingUnusedConfirmed",false,"commonItemIds",List.of(brush,linen,soap),"lines",List.of(line(brush,"3","支",0),line(linen,"0","条",0),line(soap,"","毫升",0))));
        var initial=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        var draft=new LinkedHashMap<String,Object>(Map.of("revisionId",revision,"expectedVersion",initial.path("rowVersion").asLong(),"items",List.of(),"inventoryQuantities",quantities,"roomCounts",Map.of("checkoutRooms",19,"stayoverRooms",6)));
        var saved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,draft,200);
        var reopened=call(get("/api/v1/daily-reports/"+report),EMP,null,200);
        assertThat(reopened.path("revisions").get(0).path("payloadSnapshot").path("inventoryQuantities")).isEqualTo(mapper.valueToTree(quantities));
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong()),400);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isZero();
        quantities.put("remainingUnusedConfirmed",true); draft.put("expectedVersion",saved.path("rowVersion").asLong());
        saved=call(put("/api/v1/daily-reports/"+report+"/draft").header("Idempotency-Key",UUID.randomUUID().toString()),EMP,draft,200);
        var command=Map.of("revisionId",revision,"expectedVersion",saved.path("rowVersion").asLong());
        String key=UUID.randomUUID().toString();
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",key),EMP,command,200);
        // Report commands reject stale row versions before checking an idempotency key.
        call(post("/api/v1/daily-reports/"+report+"/actions/submit").header("Idempotency-Key",key),EMP,command,409);
        assertThat(jdbc.queryForObject("select count(*) from work_record where work_expectation_id=?::uuid",Integer.class,expectation)).isEqualTo(1);
        String record=jdbc.queryForObject("select id::text from work_record where work_expectation_id=?::uuid",String.class,expectation);
        balance(brush,"100","50"); assertThat(movements(record)).isZero();
        assertThat(mapper.readTree(jdbc.queryForObject("select payload_snapshot::text from daily_report_revision where id=?",String.class,revision)).path("inventoryQuantities")).isEqualTo(mapper.valueToTree(quantities));
        review(record,GM,"APPROVED",0,200); balance(brush,"97","48.5"); balance(linen,"10","20"); balance(soap,"10","20");
        assertThat(movements(record)).isEqualTo(1);
    }

    private String item(String category,String base,String unit,int factor,int qty,double cost) throws Exception {
        String id=call(post("/api/v1/store-resources/hotels/"+H+"/items"),ADMIN,Map.of("sku",UUID.randomUUID().toString(),"name","耗用测试物品","category",category,"unit",base,"unitCost",cost,"minimumQuantity",0,"issueUnit",unit,"issueFactor",factor,"usageScope",Map.of("mode","ALL_DEPARTMENTS","departmentIds",List.of(),"positionIds",List.of())),201).path("id").asText();
        if(qty>0)receipt(id,qty,cost); return id;
    }
    private void receipt(String id,int qty,double cost) throws Exception {
        call(post("/api/v1/store-resources/hotels/"+H+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",id,"movementType","RECEIPT","quantity",qty,"unitCost",cost,"reason","测试入库"),201);
    }

    @Test void employeeCatalogueEnforcesDepartmentAndPositionAndFiltersQuickList() throws Exception {
        String position=jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,EA);
        String otherPosition=jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,GMA);
        String shared=item("CONSUMABLE","支","支",1,0,1), department=item("CONSUMABLE","支","支",1,0,1), own=item("CONSUMABLE","支","支",1,0,1);
        String other=item("CONSUMABLE","支","支",1,0,1), legacy=item("CONSUMABLE","支","支",1,0,1), wrongDepartment=item("CONSUMABLE","支","支",1,0,1);
        jdbc.update("update store_resource_item set usage_scope_mode='DEPARTMENTS' where id in (?::uuid,?::uuid)",department,wrongDepartment);
        jdbc.update("insert into store_item_usage_department(tenant_id,hotel_org_unit_id,item_id,department_id) values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,department,D);
        jdbc.update("insert into store_item_usage_department(tenant_id,hotel_org_unit_id,item_id,department_id) values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,wrongDepartment,"12000000-0000-0000-0000-000000000006");
        for (String id:List.of(own,wrongDepartment)) jdbc.update("insert into store_item_usage_position(tenant_id,hotel_org_unit_id,item_id,position_id) values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,id,position);
        jdbc.update("insert into store_item_usage_position(tenant_id,hotel_org_unit_id,item_id,position_id) values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,other,otherPosition);
        jdbc.update("update store_resource_item set usage_scope_mode='LEGACY' where id=?::uuid",legacy);
        // Even a second active role belonging to the same employee must not widen the current role.
        UUID extra=UUID.randomUUID();
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from,status) values(?,?::uuid,?::uuid,?::uuid,?::uuid,current_date,'ACTIVE')",extra,T,EMPLOYEE,H,otherPosition);
        try {
            String endpoint="/api/v1/store-resources/hotels/"+H+"/usage-quick-lists/"+position;
            var before=call(get(endpoint),ADMIN,null,200);
            call(put(endpoint),ADMIN,Map.of("itemIds",List.of(shared,department,own,other,legacy,wrongDepartment),"expectedVersion",before.path("row_version").asLong()),200);
            for (String code:List.of("HKA_ITEM_ISSUE","HKA_DAILY_REPORT","NSA_DAILY_HANDOVER")) {
                var catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",H).param("itemCode",code),EMP,null,200);
                Set<String> ids=new HashSet<>(); catalog.path("items").forEach(item->ids.add(item.path("id").asText()));
                assertThat(ids).contains(shared,department,own).doesNotContain(legacy);
                if(code.equals("HKA_ITEM_ISSUE")) {
                    assertThat(ids).contains(other,wrongDepartment);
                    for(JsonNode item:catalog.path("items")) if(Set.of(other,wrongDepartment).contains(item.path("id").asText()))
                        assertThat(item.path("applicable_to_assignment").asBoolean()).isFalse();
                } else assertThat(ids).doesNotContain(other,wrongDepartment);
                assertThat(catalog.path("department_configured").asBoolean()).isTrue();
                assertThat(catalog.path("unconfigured_item_count").asInt()).isPositive();
                assertThat(catalog.toString()).doesNotContain("unit_cost","inventory_value","current_quantity");
                if (!code.equals("HKA_ITEM_ISSUE")) assertThat(catalog.path("quick_list").path("item_ids")).isEqualTo(mapper.valueToTree(List.of(shared,department,own)));
                for(JsonNode item:catalog.path("items")) {
                    if(item.path("id").asText().equals(own))assertThat(item.path("usage_scope_kind").asText()).isEqualTo("POSITION");
                    if(item.path("id").asText().equals(department))assertThat(item.path("usage_scope_kind").asText()).isEqualTo("DEPARTMENT");
                    if(item.path("id").asText().equals(shared))assertThat(item.path("usage_scope_kind").asText()).isEqualTo("SHARED");
                }
            }
            // Caller-supplied hotel/department and broad administrative permissions cannot impersonate an assignment.
            call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",H),ADMIN,null,403);
            jdbc.update("update employee_position_assignment set org_unit_id=?::uuid where id=?::uuid",H,EA);
            try {
                var catalog=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",D),EMP,null,200);
                assertThat(catalog.path("department_configured").asBoolean()).isFalse();
                assertThat(catalog.path("items").toString()).doesNotContain(department).contains(shared,own);
            } finally { jdbc.update("update employee_position_assignment set org_unit_id=?::uuid where id=?::uuid",D,EA); }
        } finally { jdbc.update("update employee_position_assignment set status='INACTIVE' where id=?",extra); }
    }

    @Test void forgedAndReconfiguredUsageLinesAreRejectedIncludingZerosWithoutPosting() throws Exception {
        usageItemCode="NSA_DAILY_HANDOVER"; setup();
        String allowed=item("CONSUMABLE","支","支",1,10,2), denied=item("CONSUMABLE","支","支",1,10,2);
        String record=submit(List.of(line(allowed,"3","支",0),line(denied,"2","支",0)),true,201).path("id").asText();
        jdbc.update("update store_resource_item set usage_scope_mode='LEGACY' where id=?::uuid",denied);
        call(post("/api/v1/work-data/records/"+record+"/actions/submit"),EMP,Map.of("expectedVersion",0),400);
        assertThat(jdbc.queryForObject("select status from work_record where id=?::uuid",String.class,record)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select payload::text from work_record where id=?::uuid",String.class,record)).contains(denied,"\"2\"");
        assertThat(jdbc.queryForObject("select count(*) from store_work_usage where work_record_id=?::uuid",Integer.class,record)).isZero();
        balance(allowed,"10","20"); balance(denied,"10","20");
        for (String quantity:List.of("0","")) {
            var input=Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(denied,quantity,"支",0)));
            call(post("/api/v1/work-data/records"),EMP,recordBody(input,false),400);
        }
    }

    @Test void upgrading83UnconfiguredItemsMakesThemSelectableWithoutChangingStock() throws Exception {
        usageItemCode="HKA_ITEM_ISSUE"; setup();
        List<String> ids=new ArrayList<>();
        for(int i=0;i<83;i++) {
            String id=UUID.randomUUID().toString(); ids.add(id);
            jdbc.update("insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,employee_usage_enabled,usage_scope_mode) values(?::uuid,?::uuid,?::uuid,?,'旧物品','CONSUMABLE','支',true,'LEGACY')",id,T,H,(i==0 ? "QT-" : i==1 ? "KF-" : "COMMON-")+id);
        }
        String disabled=ids.get(82);
        jdbc.update("update store_resource_item set employee_usage_enabled=false where id=?::uuid",disabled);
        String endpoint="/api/v1/store-resources/usage-catalog";
        var before=call(get(endpoint).param("orgUnitId",H).param("itemCode",usageItemCode),EMP,null,200);
        for(String id:ids) assertThat(before.path("items").toString()).doesNotContain(id);
        jdbc.execute(new org.springframework.core.io.ClassPathResource("db/migration/V86__store_item_department_defaults.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var after=call(get(endpoint).param("orgUnitId",H).param("itemCode",usageItemCode),EMP,null,200);
        assertThat(after.path("unconfigured_item_count").asInt()).isZero();
        assertThat(after.path("items").toString()).doesNotContain(disabled);
        for(String id:ids.subList(0,82)) assertThat(after.path("items").toString()).contains(id);
        for(JsonNode row:after.path("items")) {
            String id=row.path("id").asText();
            if(id.equals(ids.get(1))) assertThat(row.path("applicable_to_assignment").asBoolean()).isFalse();
            if(id.equals(ids.get(2))) {
                assertThat(row.path("usage_scope_mode").asText()).isEqualTo("ALL_DEPARTMENTS");
                assertThat(row.path("applicable_to_assignment").asBoolean()).isTrue();
            }
        }
        String record=submit(List.of(line(ids.get(1),"1","支",1),line(ids.get(2),"2","支",1)),false,201).path("id").asText();
        assertThat(movements(record)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,record)).isZero();
        call(get(endpoint).param("orgUnitId",OTHER_H).param("itemCode",usageItemCode),EMP,null,403);
    }

    @Test void crossDepartmentIssueCanBeSubmittedButDoesNotPostStockOrCost() throws Exception {
        usageItemCode="HKA_ITEM_ISSUE"; setup();
        String item=item("CONSUMABLE","支","支",1,10,2);
        String otherPosition=jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,GMA);
        jdbc.update("insert into store_item_usage_position(tenant_id,hotel_org_unit_id,item_id,position_id) values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,item,otherPosition);
        String issued=submit(List.of(line(item,"2","支",0)),false,201).path("id").asText();
        assertThat(movements(issued)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_cost_entry where source_parent_id=?::uuid",Integer.class,issued)).isZero();
        balance(item,"10","20");
        usageItemCode="NSA_DAILY_HANDOVER"; setup();
        String catalogue="/api/v1/store-resources/usage-catalog";
        var current=call(get(catalogue).param("orgUnitId",H).param("itemCode",usageItemCode).param("businessDate",LocalDate.now().toString()),EMP,null,200);
        assertThat(current.path("items").toString()).contains(item,"issued_today");
        assertThat(call(get(catalogue).param("orgUnitId",H).param("itemCode",usageItemCode).param("businessDate",LocalDate.now().minusDays(1).toString()),EMP,null,200).path("items").toString()).doesNotContain(item);
        var body=recordBody(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(item,"0.5","支",0))),false);
        String usage=call(post("/api/v1/work-data/records"),EMP,body,201).path("id").asText();
        balance(item,"10","20");
        review(usage,GM,"APPROVED",0,200); review(usage,GM,"APPROVED",0,200);
        balance(item,"9.5","19"); assertThat(movements(usage)).isOne();
    }

    @Test void draftsRejectedIssuesAndOtherAssignmentsDoNotAuthorizeDailyUse() throws Exception {
        usageItemCode="HKA_ITEM_ISSUE"; setup();
        String item=item("CONSUMABLE","支","支",1,10,2);
        jdbc.update("update store_resource_item set usage_scope_mode='DEPARTMENTS' where id=?::uuid",item);
        jdbc.update("insert into store_item_usage_department values(?::uuid,?::uuid,?::uuid,?::uuid)",T,H,item,"12000000-0000-0000-0000-000000000006");
        String issued=submit(List.of(line(item,"2","支",0)),true,201).path("id").asText();
        usageItemCode="NSA_DAILY_HANDOVER"; setup();
        submit(List.of(line(item,"1","支",0)),false,400);
        call(post("/api/v1/work-data/records/"+issued+"/actions/submit"),EMP,Map.of("expectedVersion",0),200);
        // A different assignment cannot inherit the employee's declaration.
        var other=call(get("/api/v1/store-resources/usage-catalog").param("orgUnitId",H)
                .param("businessDate",LocalDate.now().toString()).param("itemCode",usageItemCode),GM,null,200);
        assertThat(other.path("items").toString()).doesNotContain(item);
        UUID previousManager=jdbc.queryForObject("select manager_assignment_id from employee_position_assignment where id=?::uuid",UUID.class,EA);
        jdbc.update("update employee_position_assignment set manager_assignment_id=?::uuid where id=?::uuid",GMA,EA);
        try { review(issued,GM,"REJECTED",1,200); }
        finally { jdbc.update("update employee_position_assignment set manager_assignment_id=? where id=?::uuid",previousManager,EA); }
        submit(List.of(line(item,"1","支",0)),false,400);
        balance(item,"10","20");
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
