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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StoreCostIntegrationTest {
    private static final String T="10000000-0000-0000-0000-000000000001", ADMIN=PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String GM="19000000-0000-0000-0000-000000000002", GMA="19200000-0000-0000-0000-000000000001";
    private static final EmbeddedPostgres PG=start();private static final String URL=url();
    private static final YearMonth LAST=YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    String hotel,path;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);
    }
    @AfterAll static void closePg() throws Exception { PG.close(); }
    @BeforeEach void setup() {
        PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001");
        hotel=UUID.randomUUID().toString();path="/api/v1/store-resources/hotels/"+hotel;
        jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?::uuid,?::uuid,?,'成本测试门店','HOTEL')",hotel,T,hotel);
        jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?::uuid,?::uuid,?::uuid,0)",T,hotel,hotel);
    }

    @Test void separatesConsumptionLossAndAdjustmentsFromPurchasesAndFreezesLabels() throws Exception {
        String item=item("CONSUMABLE","牙刷",100,"1.25");
        movement(item,"CONSUMPTION",8,"1.25",LAST.atDay(10),201);
        movement(item,"DAMAGE",2,"1.25",LAST.atDay(11),201);
        movement(item,"ADJUSTMENT",-1,"1.25",LAST.atDay(12),201);
        movement(item,"RETURN",1,"1.25",LAST.atDay(12),201); // Ordinary inventory return is not an expense reversal.
        jdbc.update("update store_resource_item set name='改名后的耗品',category='CLEANING_SUPPLY',unit_cost=99 where id=?::uuid",item);
        JsonNode preview=preview(LAST);
        money(preview,"usageTotal","10");money(preview,"lossTotal","2.5");money(preview,"costTotal","12.5");money(preview,"adjustmentTotal","1.25");
        assertThat(preview.path("entryCount").asInt()).isEqualTo(3);
        assertThat(preview.path("items").toString()).contains("牙刷","CONSUMABLE").doesNotContain("改名后的耗品","CLEANING_SUPPLY");
        JsonNode page=call(get(path+"/costs/entries").param("month",LAST.toString()).param("kind","LOSS"),ADMIN,null,200);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("amount").asText()).isEqualTo("2.5000");
        call(get(path+"/costs/entries").param("month",LAST.toString()).param("day",LAST.minusMonths(1).atDay(1).toString()),ADMIN,null,400);
    }

    @Test void closesOnceAndRoutesLateBackdatedChargesIntoCurrentPeriod() throws Exception {
        String item=item("CLEANING_SUPPLY","清洁液",100,"0.35");
        movement(item,"CONSUMPTION",3,"0.35",LAST.atDay(10),201);
        JsonNode preview=preview(LAST);var command=command(preview,true);
        JsonNode closed=call(post(path+"/costs/close"),ADMIN,command,200);
        assertThat(closed.path("closed").asBoolean()).isTrue();money(closed,"costTotal","1.05");
        assertThat(call(post(path+"/costs/close"),ADMIN,command,200).path("previewToken")).isEqualTo(closed.path("previewToken"));
        movement(item,"CONSUMPTION",2,"0.35",LAST.atDay(12),201);
        assertThat(preview(LAST)).isEqualTo(closed);
        JsonNode current=preview(LAST.plusMonths(1));money(current,"costTotal","0.70");
        assertThat(current.path("priorPeriodCount").asInt()).isEqualTo(1);
        assertThat(call(get(path+"/costs/closed-months"),ADMIN,null,200).path("items")).hasSize(1);
        assertThatThrownBy(()->jdbc.update("update store_cost_month_close set note='覆盖' where hotel_org_unit_id=?::uuid",hotel)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from store_cost_entry where hotel_org_unit_id=?::uuid",hotel)).isInstanceOf(RuntimeException.class);
    }

    @Test void refusesStalePreviewMissingConfirmationCurrentMonthAndUnauthorizedClose() throws Exception {
        String item=item("CONSUMABLE","牙膏",100,"0");JsonNode stale=preview(LAST);
        movement(item,"CONSUMPTION",2,"0",LAST.atDay(9),201);
        call(post(path+"/costs/close"),ADMIN,command(stale,true),409);
        JsonNode fresh=preview(LAST);assertThat(fresh.path("zeroAmountCount").asInt()).isEqualTo(1);
        call(post(path+"/costs/close"),ADMIN,command(fresh,false),400);
        call(post(path+"/costs/close"),ADMIN,command(preview(LAST.plusMonths(1)),true),400);
        call(post(path+"/costs/close"),GM,command(fresh,true),403);
        call(get(path+"/costs").param("month",LAST.toString()),GM,null,403);
        call(get(path+"/costs").param("month","2026-99"),ADMIN,null,400);
        call(get(path+"/costs/entries").param("month",LAST.toString()).param("page","-1"),ADMIN,null,400);
        call(get(path+"/costs/entries").param("month",LAST.toString()).param("kind","SQL"),ADMIN,null,400);
        movement(item,"DAMAGE",1,"0",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1),400);
    }

    @Test void laundryReversalKeepsClosedMonthAndReversesBothFeeAndOriginalLossToday() throws Exception {
        String item=item("LINEN","浴巾",10,"10");
        call(put(path+"/laundry/rates/"+item),ADMIN,Map.of("unitPrice","1.25","expectedVersion",-1,"expectedItemVersion",0),200);
        JsonNode order=call(post(path+"/laundry/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,
                Map.of("supplier","洗涤厂","dispatchedOn",LAST.atDay(10).toString(),"lines",List.of(Map.of("itemId",item,"quantity",8,"expectedRateVersion",0,"expectedItemVersion",0))),201);
        String oid=order.path("id").asText(), line=order.path("lines").get(0).path("id").asText();
        order=call(post(path+"/laundry/orders/"+oid+"/receipts").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,
                Map.of("receivedOn",LAST.atDay(11).toString(),"expectedVersion",0,"confirmWriteOff",true,"lines",List.of(Map.of("lineId",line,"acceptedQuantity",6,"rewashQuantity",0,"writtenOffQuantity",2,"differenceReason","破损"))),201);
        String receipt=order.path("receipts").get(0).path("id").asText();
        JsonNode closed=call(post(path+"/costs/close"),ADMIN,command(preview(LAST),true),200);
        money(closed,"usageTotal","7.5");money(closed,"lossTotal","20");
        call(post(path+"/laundry/orders/"+oid+"/receipts/"+receipt+"/reverse"),ADMIN,Map.of("reason","误录重核","expectedVersion",1),200);
        assertThat(preview(LAST)).isEqualTo(closed);
        JsonNode current=preview(LAST.plusMonths(1));money(current,"costTotal","-27.5");assertThat(current.path("entryCount").asInt()).isEqualTo(2);
        call(post(path+"/laundry/orders/"+oid+"/receipts/"+receipt+"/reverse"),ADMIN,Map.of("reason","误录重核","expectedVersion",1),200);
        assertThat(preview(LAST.plusMonths(1)).path("entryCount").asInt()).isEqualTo(2);
    }

    @Test void simultaneousCloseRequestsCreateOneSnapshot() throws Exception {
        var body=command(preview(LAST),true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call(post(path+"/costs/close"),ADMIN,body,200));
            var b=pool.submit(()->call(post(path+"/costs/close"),ADMIN,body,200));
            assertThat(a.get(15,TimeUnit.SECONDS).path("closedAt")).isEqualTo(b.get(15,TimeUnit.SECONDS).path("closedAt"));
        }
        assertThat(jdbc.queryForObject("select count(*) from store_cost_month_close where hotel_org_unit_id=?::uuid",Integer.class,hotel)).isEqualTo(1);
    }

    @Test void pendingUsageBlocksCloseAndZeroUsageReviewCanReleaseIt() throws Exception {
        String record=UUID.randomUUID().toString();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,target_org_unit_id,employee_id,position_assignment_id,form_version_id,business_date,status,occurred_at,content_hash,submitted_at)
                select ?::uuid,?::uuid,?::uuid,?::uuid,'19100000-0000-0000-0000-000000000002','19200000-0000-0000-0000-000000000002',id,?::date,'SUBMITTED',now(),repeat('0',64),now()
                from form_version where tenant_id=?::uuid limit 1
                """,record,T,hotel,hotel,LAST.atDay(10).toString(),T);
        jdbc.update("insert into store_work_usage(tenant_id,hotel_org_unit_id,work_record_id,reviewer_assignment_id_snapshot,no_usage) values(?::uuid,?::uuid,?::uuid,?::uuid,true)",T,hotel,record,GMA);
        JsonNode preview=preview(LAST);assertThat(preview.path("pendingUsageCount").asInt()).isEqualTo(1);
        call(post(path+"/costs/close"),ADMIN,command(preview,true),409);
        jdbc.update("update store_work_usage set status='APPROVED',reviewed_by=?::uuid,reviewed_at=now() where work_record_id=?::uuid",ADMIN,record);
        call(post(path+"/costs/close"),ADMIN,command(preview,true),409);
        call(post(path+"/costs/close"),ADMIN,command(preview(LAST),true),200);
    }

    @Test void postingWaitingOnCloseSeesCommittedCloseAndMovesToCurrentMonth() throws Exception {
        String item=item("CONSUMABLE","关账并发",100,"2");JsonNode preview=preview(LAST);
        try(var connection=PG.getPostgresDatabase().getConnection();var pool=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var stmt=connection.prepareStatement("insert into store_cost_month_close(tenant_id,hotel_org_unit_id,month_start,preview_token,snapshot,note,closed_by) values(?::uuid,?::uuid,?::date,?,?::jsonb,'并发关闭测试',?::uuid)")) {
                stmt.setString(1,T);stmt.setString(2,hotel);stmt.setString(3,LAST.atDay(1).toString());stmt.setString(4,preview.path("previewToken").asText());stmt.setString(5,preview.toString());stmt.setString(6,ADMIN);stmt.executeUpdate();
            }
            var writer=pool.submit(()->{movement(item,"CONSUMPTION",1,"2",LAST.atDay(12),201);return true;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
            while(System.nanoTime()<deadline && !writer.isDone()) {
                waiting=jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and not granted)",Boolean.class);
                if(waiting)break;Thread.sleep(20);
            }
            assertThat(waiting).isTrue();connection.commit();writer.get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select posting_date from store_cost_entry where hotel_org_unit_id=?::uuid",LocalDate.class,hotel)).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        money(preview(LAST.plusMonths(1)),"costTotal","2");
    }

    @Test void closeWaitsForInFlightPostAndRejectsAnOutdatedPreview() throws Exception {
        String item=item("CONSUMABLE","并发测试",100,"2");var body=command(preview(LAST),true);
        try(var connection=PG.getPostgresDatabase().getConnection();var pool=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            // A real uncommitted posting holds the same shared advisory lock as every stock/laundry writer.
            try(var stmt=connection.prepareStatement("insert into store_inventory_movement(tenant_id,hotel_org_unit_id,item_id,movement_type,quantity_delta,quantity_before,quantity_after,unit_cost_snapshot,value_delta,reason,idempotency_key,request_hash,actor_account_id,occurred_at) values(?::uuid,?::uuid,?::uuid,'CONSUMPTION',-1,100,99,2,-2,'并发入账',?,'"+"0".repeat(64)+"',?::uuid,?::timestamptz)")) {
                stmt.setString(1,T);stmt.setString(2,hotel);stmt.setString(3,item);stmt.setString(4,UUID.randomUUID().toString());stmt.setString(5,ADMIN);stmt.setString(6,LAST.atDay(12)+"T12:00:00+08:00");stmt.executeUpdate();
            }
            var close=pool.submit(()->call(post(path+"/costs/close"),ADMIN,body,409));
            // Await the actual database lock waiter, with a bounded poll, not a timing-based sleep assertion.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
            while(System.nanoTime()<deadline && !close.isDone()) {
                waiting=jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and not granted)",Boolean.class);
                if(waiting) break;
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();connection.commit();close.get(15,TimeUnit.SECONDS);
        }
        money(preview(LAST),"costTotal","2");
    }

    private JsonNode preview(YearMonth month) throws Exception { return call(get(path+"/costs").param("month",month.toString()),ADMIN,null,200); }
    private Map<String,Object> command(JsonNode preview,boolean confirm) { return Map.of("month",preview.path("month").asText(),"previewToken",preview.path("previewToken").asText(),"note","逐项核对，无未解释差异","acknowledgeWarnings",confirm); }
    private void money(JsonNode value,String field,String expected) { assertThat(new BigDecimal(value.path(field).asText())).isEqualByComparingTo(expected); }
    private String item(String category,String name,int quantity,String price) throws Exception {
        String id=call(post(path+"/items"),ADMIN,Map.of("sku",UUID.randomUUID().toString(),"name",name,"category",category,"unit","件","unitCost",price,"minimumQuantity",0),201).path("id").asText();
        movement(id,"RECEIPT",quantity,price,LAST.atDay(1),201);return id;
    }
    private void movement(String id,String type,int quantity,String price,LocalDate day,int status) throws Exception {
        call(post(path+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",id,"movementType",type,"quantity",quantity,"unitCost",price,"reason","测试记账","occurredAt",day+"T00:00:00+08:00"),status);
    }
    private JsonNode call(MockHttpServletRequestBuilder request,String actor,Object body,int status) throws Exception {
        request.header("X-Tenant-Id",T).header("X-Actor-Id",actor);if(actor.equals(GM))request.header("X-Assignment-Id",GMA);
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
