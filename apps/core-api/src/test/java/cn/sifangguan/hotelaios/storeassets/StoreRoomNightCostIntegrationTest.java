package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StoreRoomNightCostIntegrationTest {
    private static final String T="10000000-0000-0000-0000-000000000001", ADMIN=PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String GM="19000000-0000-0000-0000-000000000002", GMA="19200000-0000-0000-0000-000000000001";
    private static final EmbeddedPostgres PG=start(); private static final String URL=url();
    private static final Path DIR=directory();
    private static final YearMonth LAST=YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    String hotel,profile,path;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);
        r.add("app.store-cost.pms-source.tenant-id",()->T);r.add("app.store-cost.pms-source.allowed-tenant-code",()->"test-source");
        r.add("app.store-cost.pms-source.hotel-directory-path",()->DIR.resolve("directory.json").toString());
        r.add("app.store-cost.pms-source.summary-path",()->DIR.resolve("summary.json").toString());
    }
    @AfterAll static void close() throws Exception {
        PG.close(); Files.deleteIfExists(DIR.resolve("directory.json")); Files.deleteIfExists(DIR.resolve("summary.json")); Files.deleteIfExists(DIR);
    }
    @BeforeEach void setup() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001");
        hotel=UUID.randomUUID().toString(); profile=UUID.randomUUID().toString(); path="/api/v1/store-resources/hotels/"+hotel;
        jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?::uuid,?::uuid,?,'间夜成本测试','HOTEL')",hotel,T,hotel);
        jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?::uuid,?::uuid,?::uuid,0)",T,hotel,hotel);
        jdbc.update("insert into hotel_profile(id,tenant_id,org_unit_id,property_code) values(?::uuid,?::uuid,?::uuid,?)",profile,T,hotel,hotel);
        mapper.writeValue(DIR.resolve("directory.json").toFile(),List.of(Map.of("tenantCode","test-source","hotelId",profile,"hotelCode",hotel,"pmsProviderCode","TEST")));
        data(month(LAST,200),month(LAST.minusMonths(1),100),month(LAST.minusYears(1),50));
    }
    @Test void calculatesExactCostsAndComparableRatiosWithoutIncludingPurchasesDamageOrAdjustment() throws Exception {
        String consumable=item("CONSUMABLE","2");
        movement(consumable,"CONSUMPTION",50,LAST.atDay(10));
        movement(consumable,"CONSUMPTION",15,LAST.minusMonths(1).atDay(10));
        movement(consumable,"CONSUMPTION",25,LAST.minusYears(1).atDay(10));
        movement(consumable,"DAMAGE",10,LAST.atDay(11));movement(consumable,"ADJUSTMENT",-10,LAST.atDay(11));
        String cleaning=item("CLEANING_SUPPLY","2");movement(cleaning,"CONSUMPTION",30,LAST.atDay(10));
        laundry();
        JsonNode result=analysis("MONTH",LAST.toString());
        assertThat(metric(result,"CONSUMABLE").path("amount").asText()).isEqualTo("100.0000");
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("0.5000");
        assertThat(metric(result,"CLEANING_SUPPLY").path("perRoomUse").asText()).isEqualTo("0.3000");
        assertThat(metric(result,"LAUNDRY").path("perRoomUse").asText()).isEqualTo("0.0600");
        assertThat(result.path("comparisons").get(0).path("previous").path("percent").asText()).isEqualTo("66.67");
        assertThat(result.path("comparisons").get(0).path("yearAgo").path("percent").asText()).isEqualTo("-50.00");
        assertThat(result.path("current").path("source").path("expectedDays").asInt()).isEqualTo(LAST.lengthOfMonth());
    }
    @Test void closedAmountsStayImmutableWhileLatestPmsRevisionIsClearlySeparate() throws Exception {
        String item=item("CONSUMABLE","2");movement(item,"CONSUMPTION",50,LAST.atDay(10));
        JsonNode preview=call(get(path+"/costs").param("month",LAST.toString()),ADMIN,null,200);
        JsonNode closed=call(post(path+"/costs/close"),ADMIN,Map.of("month",LAST.toString(),"previewToken",preview.path("previewToken").asText(),"note","核对","acknowledgeWarnings",true),200);
        JsonNode first=analysis("MONTH",LAST.toString());
        data(month(LAST,400));
        JsonNode corrected=analysis("MONTH",LAST.toString());
        assertThat(metric(corrected,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("0.2500");
        assertThat(corrected.path("current").path("costMonthClosed").asBoolean()).isTrue();
        assertThat(corrected.path("current").path("source").path("revision")).isNotEqualTo(first.path("current").path("source").path("revision"));
        movement(item,"CONSUMPTION",10,LAST.atDay(11));
        assertThat(metric(analysis("MONTH",LAST.toString()),"CONSUMABLE").path("amount").asText()).isEqualTo("100.0000");
        assertThat(call(get(path+"/costs").param("month",LAST.toString()),ADMIN,null,200)).isEqualTo(closed);
    }
    @Test void separatelyAssignedSourceAndCoreIdsUseOnlyTheExplicitlyBoundHotel() throws Exception {
        String sourceId=UUID.randomUUID().toString();
        mapper.writeValue(DIR.resolve("directory.json").toFile(),List.of(Map.of(
                "tenantCode","test-source","hotelId",sourceId,"hotelCode",hotel,"pmsProviderCode","TEST",
                "coreTenantId",T,"coreHotelProfileId",profile,"mappingEvidenceReference","test:approved-mapping")));
        ObjectNode original=month(LAST,200).put("sourceHotelId",sourceId);
        data(original,month(LAST,999));
        String item=item("CONSUMABLE","2");movement(item,"CONSUMPTION",50,LAST.atDay(10));
        JsonNode result=analysis("MONTH",LAST.toString());
        assertThat(result.path("current").path("source").path("roomUses").asText()).isEqualTo("200");
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("0.5000");
        assertThat(jdbc.queryForObject("select id::text from hotel_profile where tenant_id=?::uuid and org_unit_id=?::uuid",
                String.class,T,hotel)).isEqualTo(profile);
        assertThat(mapper.readTree(DIR.resolve("summary.json").toFile()).path("records").get(0)
                .path("sourceHotelId").asText()).isEqualTo(sourceId);
    }
    @Test void missingZeroAndUnpricedCostsNeverBecomeFakeZeroCostIndicators() throws Exception {
        assertThat(metric(analysis("MONTH",LAST.toString()),"CONSUMABLE").path("state").asText()).isEqualTo("NO_COST_RECORDS");
        String item=item("CONSUMABLE","0");movement(item,"CONSUMPTION",10,LAST.atDay(10));
        JsonNode zeroPrice=metric(analysis("MONTH",LAST.toString()),"CONSUMABLE");
        assertThat(zeroPrice.path("state").asText()).isEqualTo("ZERO_AMOUNT_REVIEW");assertThat(zeroPrice.hasNonNull("perRoomUse")).isFalse();
        data(month(LAST,0));
        JsonNode zero=analysis("MONTH",LAST.toString());
        assertThat(zero.path("current").path("source").path("roomUses").asText()).isEqualTo("0");
        assertThat(metric(zero,"CONSUMABLE").hasNonNull("perRoomUse")).isFalse();
        data();
        assertThat(analysis("MONTH",LAST.toString()).path("current").path("source").path("state").asText()).isEqualTo("NO_DATA");
    }
    @Test void handlesDayComparisonsAndRefusesIncompatibleRoomNightDefinitions() throws Exception {
        LocalDate day=LAST.atDay(10);String item=item("CONSUMABLE","1");
        movement(item,"CONSUMPTION",20,day);movement(item,"CONSUMPTION",10,day.minusDays(1));movement(item,"CONSUMPTION",20,day.minusYears(1));
        ObjectNode old=day(day.minusYears(1),10);((ObjectNode)old.path("roomUseDefinition")).put("code","ALL_USED_ROOM_NIGHTS_V1");
        data(PmsRoomNightSourceTest.totalOnly(day(day,10)),day(day.minusDays(1),10),old);
        JsonNode result=analysis("DAY",day.toString());
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("2.0000");
        assertThat(result.path("comparisons").get(0).path("previous").path("percent").asText()).isEqualTo("100.00");
        assertThat(result.path("comparisons").get(0).path("yearAgo").path("state").asText()).isEqualTo("NOT_COMPARABLE");
    }
    @Test void pendingUsageBlocksConsumableRatiosAndMonthToDateNeverBorrowsFullMonth() throws Exception {
        String item=item("CONSUMABLE","1");movement(item,"CONSUMPTION",20,LAST.atDay(10));
        String record=UUID.randomUUID().toString();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,target_org_unit_id,employee_id,position_assignment_id,form_version_id,business_date,status,occurred_at,content_hash,submitted_at)
                select ?::uuid,?::uuid,?::uuid,?::uuid,'19100000-0000-0000-0000-000000000002','19200000-0000-0000-0000-000000000002',id,?::date,'SUBMITTED',now(),repeat('0',64),now()
                from form_version where tenant_id=?::uuid limit 1
                """,record,T,hotel,hotel,LAST.atDay(10).toString(),T);
        jdbc.update("insert into store_work_usage(tenant_id,hotel_org_unit_id,work_record_id,reviewer_assignment_id_snapshot,no_usage) values(?::uuid,?::uuid,?::uuid,?::uuid,true)",T,hotel,record,GMA);
        assertThat(metric(analysis("MONTH",LAST.toString()),"CONSUMABLE").path("state").asText()).isEqualTo("PENDING_USAGE");
        JsonNode current=analysis("MONTH",LAST.plusMonths(1).toString());
        assertThat(LocalDate.parse(current.path("current").path("to").asText())).isEqualTo(LocalDate.parse(current.path("latestClosedDay").asText()));
        assertThat(current.path("current").path("source").path("state").asText()).isIn("NO_DATA","PERIOD_OPEN");
    }
    @Test void enforcesHotelPermissionDateValidationAndMappingBeforeReadingAnotherStore() throws Exception {
        call(get(path+"/costs/per-room-use").param("period",LAST.toString()),GM,null,403);
        call(get(path+"/costs/per-room-night").param("period",LAST.toString()),GM,null,403);
        call(get(path+"/costs/per-room-use").param("periodType","WEEK"),ADMIN,null,400);
        call(get(path+"/costs/per-room-use").param("period","2026-99"),ADMIN,null,400);
        call(get(path+"/costs/per-room-use").param("period",LAST.plusYears(10).toString()),ADMIN,null,400);
        call(get("/api/v1/store-resources/hotels/"+UUID.randomUUID()+"/costs/per-room-use"),ADMIN,null,404);
        mapper.writeValue(DIR.resolve("directory.json").toFile(),List.of(Map.of("tenantCode","other-source","hotelId",profile,"hotelCode",hotel)));
        assertThat(analysis("MONTH",LAST.toString()).path("current").path("source").path("state").asText()).isEqualTo("NOT_MAPPED");
        Files.writeString(DIR.resolve("summary.json"),"broken");
        // Mapping is checked before even opening a source owned by another tenant.
        assertThat(analysis("MONTH",LAST.toString()).path("current").path("source").path("state").asText()).isEqualTo("NOT_MAPPED");
    }
    @Test void allUsageTypesContributeToTheCostDenominatorAndOldScopeIsNotComparable() throws Exception {
        String item=item("CONSUMABLE","1");movement(item,"CONSUMPTION",88,LAST.atDay(10));
        movement(item,"CONSUMPTION",40,LAST.minusMonths(1).atDay(10));
        ObjectNode current=month(LAST,200),old=month(LAST.minusMonths(1),100);
        ((ObjectNode)current.path("metrics")).put("totalRoomUses",220)
                .put("complimentaryRoomUses",10).put("houseUseRoomUses",5)
                .put("paidHourlyRoomUses",5);
        ((ObjectNode)old.path("roomUseDefinition")).put("code","OVERNIGHT_SOLD");
        data(current,old);
        JsonNode result=analysis("MONTH",LAST.toString());
        assertThat(result.path("roomUseBasis").asText()).isEqualTo("ALL_ROOM_USES_V1");
        assertThat(result.path("current").path("source").path("roomUses").asText()).isEqualTo("220");
        assertThat(result.path("current").path("source").path("breakdown").path("paidHourlyRoomUses").asText()).isEqualTo("5");
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("0.4000");
        assertThat(result.path("comparisons").get(0).path("previous").path("state").asText()).isEqualTo("NOT_COMPARABLE");
        assertThat(result.path("comparisons").get(0).path("previous").hasNonNull("percent")).isFalse();
    }
    @Test void confirmedTotalOnlyDividesByTwoUsesAndLegacyAliasReturnsANewVersionedContract() throws Exception {
        LocalDate date=LAST.atDay(10); String item=item("CONSUMABLE","1");
        movement(item,"CONSUMPTION",20,date);
        data(PmsRoomNightSourceTest.totalOnly(day(date,2)));
        JsonNode result=analysis("DAY",date.toString());
        assertThat(result.path("roomUseBasis").asText()).isEqualTo("ALL_ROOM_USES_V1");
        assertThat(result.path("current").path("source").path("roomUses").asText()).isEqualTo("2");
        assertThat(result.path("current").path("source").hasNonNull("breakdown")).isFalse();
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("10.0000");
        assertThat(result.has("roomNightBasis")).isFalse();
        assertThat(metric(result,"CONSUMABLE").has("perRoomNight")).isFalse();
        JsonNode alias=call(get(path+"/costs/per-room-night").param("periodType","DAY").param("period",date.toString()),ADMIN,null,200);
        assertThat(alias.path("roomUseBasis")).isEqualTo(result.path("roomUseBasis"));
        assertThat(alias.path("current")).isEqualTo(result.path("current"));
    }
    @Test void monthlyCostUsesSumOfUsesNotTheAverageOfDailyRates() throws Exception {
        String item=item("CONSUMABLE","1");
        movement(item,"CONSUMPTION",20,LAST.atDay(1));movement(item,"CONSUMPTION",40,LAST.atDay(2));
        List<ObjectNode> rows=new ArrayList<>();
        for(int d=1;d<=LAST.lengthOfMonth();d++) rows.add(PmsRoomNightSourceTest.totalOnly(day(LAST.atDay(d),d==1?2:d==2?8:0)));
        data(rows.toArray(ObjectNode[]::new));
        JsonNode result=analysis("MONTH",LAST.toString());
        assertThat(result.path("current").path("source").path("roomUses").asText()).isEqualTo("10");
        // 60 / (2 + 8) = 6; not (20/2 + 40/8) / 2 = 7.5.
        assertThat(metric(result,"CONSUMABLE").path("perRoomUse").asText()).isEqualTo("6.0000");
    }

    @Test void operatingCardsAndCustomPeriodUseRealDatesTotalsAndSumOfRoomUses() throws Exception {
        LocalDate yesterday=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        String goods=item("CONSUMABLE","2"), cleaning=item("CLEANING_SUPPLY","1");
        movement(goods,"CONSUMPTION",3,yesterday); movement(cleaning,"CONSUMPTION",4,yesterday);
        movement(goods,"CONSUMPTION",5,LAST.atDay(10));
        data(PmsRoomNightSourceTest.totalOnly(day(yesterday,2)),PmsRoomNightSourceTest.totalOnly(day(LAST.atDay(10),5)),PmsRoomNightSourceTest.totalOnly(day(LAST.atDay(11),0)));
        JsonNode result=call(get(path+"/costs/operating-summary").param("periodType","CUSTOM").param("from",LAST.atDay(10).toString()).param("to",LAST.atDay(11).toString()),ADMIN,null,200);
        assertThat(result.path("dateBasis").asText()).isEqualTo("BUSINESS_DATE_LIVE");
        assertThat(result.path("yesterdayDate").asText()).isEqualTo(yesterday.toString());
        assertThat(result.path("yesterday").path("amount").asText()).isEqualTo("10.0000");
        // Before the hotel's cutoff yesterday may not be closed; never fabricate its room-use denominator.
        if (!LocalDate.parse(result.path("latestClosedDay").asText()).isBefore(yesterday))
            assertThat(result.path("yesterday").path("perRoomUse").asText()).isEqualTo("5.0000");
        assertThat(result.path("selected").path("amount").asText()).isEqualTo("10.0000");
        assertThat(result.path("selected").path("perRoomUse").asText()).isEqualTo("2.0000");
        assertThat(result.path("selected").path("detail").path("source").path("roomUses").asText()).isEqualTo("5");
        JsonNode day=call(get(path+"/costs/operating-summary").param("periodType","DAY").param("period",LAST.atDay(10).toString()),ADMIN,null,200);
        assertThat(day.path("selected").path("amount").asText()).isEqualTo("10.0000");
    }

    @Test void operatingBusinessDateReflectsAdjustmentsWhileFinancialMonthRemainsFrozen() throws Exception {
        String goods=item("CONSUMABLE","2");movement(goods,"CONSUMPTION",5,LAST.atDay(10));
        JsonNode preview=call(get(path+"/costs").param("month",LAST.toString()),ADMIN,null,200);
        JsonNode closed=call(post(path+"/costs/close"),ADMIN,Map.of("month",LAST.toString(),"previewToken",preview.path("previewToken").asText(),"note","锁定历史月结","acknowledgeWarnings",true),200);
        movement(goods,"CONSUMPTION",2,LAST.atDay(10));
        JsonNode live=call(get(path+"/costs/operating-summary").param("periodType","MONTH").param("period",LAST.toString()),ADMIN,null,200);
        assertThat(live.path("selected").path("amount").asText()).isEqualTo("14.0000");
        assertThat(live.path("selected").path("perRoomUse").asText()).isEqualTo("0.0700");
        assertThat(call(get(path+"/costs").param("month",LAST.toString()),ADMIN,null,200)).isEqualTo(closed);
    }

    @Test void operatingCostDoesNotInventZerosAndRejectsForeignStoresAndInvalidRanges() throws Exception {
        JsonNode result=call(get(path+"/costs/operating-summary"),ADMIN,null,200);
        assertThat(result.path("yesterday").hasNonNull("amount")).isFalse();
        assertThat(result.path("yesterday").hasNonNull("perRoomUse")).isFalse();
        assertThat(result.path("yesterday").path("state").asText()).isEqualTo("NO_COST_RECORDS");
        call(get(path+"/costs/operating-summary"),GM,null,403);
        for (String[] dates:List.of(new String[]{"2026-09-31","2026-09-31"},new String[]{"2025-05-02","2025-05-01"},new String[]{"2020-01-01","2026-01-01"},new String[]{"2099-01-01","2099-01-02"}))
            call(get(path+"/costs/operating-summary").param("periodType","CUSTOM").param("from",dates[0]).param("to",dates[1]),ADMIN,null,400);
        call(get(path+"/costs/operating-summary").param("periodType","CUSTOM"),ADMIN,null,400);
    }
    private JsonNode metric(JsonNode analysis,String kind) {
        for(JsonNode m:analysis.path("current").path("costs")) if(kind.equals(m.path("kind").asText()))return m;
        throw new AssertionError(kind);
    }
    private JsonNode analysis(String type,String period) throws Exception { return call(get(path+"/costs/per-room-use").param("periodType",type).param("period",period),ADMIN,null,200); }
    private ObjectNode month(YearMonth month,int nights) { return fixture(month.atDay(1),month.atEndOfMonth(),nights); }
    private ObjectNode day(LocalDate day,int nights) { return fixture(day,day,nights); }
    private ObjectNode fixture(LocalDate from,LocalDate to,int nights) {
        return PmsRoomNightSourceTest.fixture(mapper,profile,hotel,from,to,nights,Instant.now().toString());
    }
    private void data(ObjectNode... records) throws Exception { mapper.writeValue(DIR.resolve("summary.json").toFile(),Map.of("records",List.of(records))); }
    private String item(String category,String price) throws Exception {
        String id=call(post(path+"/items"),ADMIN,Map.of("sku",UUID.randomUUID().toString(),"name","单房成本测试物品","category",category,"unit","件","unitCost",price,"minimumQuantity",0),201).path("id").asText();
        call(post(path+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",id,"movementType","RECEIPT","quantity",1000,"unitCost",price,"reason","库存准备","occurredAt",LAST.minusYears(2).atDay(1)+"T00:00:00+08:00"),201);return id;
    }
    private void movement(String item,String type,int quantity,LocalDate date) throws Exception {
        call(post(path+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",item,"movementType",type,"quantity",quantity,"reason","核对成本","occurredAt",date+"T00:00:00+08:00"),201);
    }
    private void laundry() throws Exception {
        String item=item("LINEN","10");
        call(put(path+"/laundry/rates/"+item),ADMIN,Map.of("unitPrice","1.2","expectedVersion",-1,"expectedItemVersion",0),200);
        JsonNode order=call(post(path+"/laundry/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("supplier","测试洗涤","dispatchedOn",LAST.atDay(9).toString(),"lines",List.of(Map.of("itemId",item,"quantity",10,"expectedRateVersion",0,"expectedItemVersion",0))),201);
        call(post(path+"/laundry/orders/"+order.path("id").asText()+"/receipts").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("receivedOn",LAST.atDay(10).toString(),"expectedVersion",0,"confirmWriteOff",false,"lines",List.of(Map.of("lineId",order.path("lines").get(0).path("id").asText(),"acceptedQuantity",10,"rewashQuantity",0,"writtenOffQuantity",0))),201);
    }
    private JsonNode call(MockHttpServletRequestBuilder request,String actor,Object body,int status) throws Exception {
        request.header("X-Tenant-Id",T).header("X-Actor-Id",actor);if(actor.equals(GM))request.header("X-Assignment-Id",GMA);
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Path directory(){try{return Files.createTempDirectory("pms-cost-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
