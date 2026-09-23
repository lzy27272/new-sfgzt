package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.imports.XlsxWorkbook;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProcurementFlowIntegrationTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001",HOTEL="12000000-0000-0000-0000-000000000003",OTHER="12000000-0000-0000-0000-000000000004";
    static final String ADMIN="19000000-0000-0000-0000-000000000001",MANAGER="19000000-0000-0000-0000-000000000002",BUYER="19000000-0000-0000-0000-000000000005",RECEIVER="19000000-0000-0000-0000-000000000008";
    static final EmbeddedPostgres PG=start();static final Path FILES=temp();
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired ProcurementService service;@Autowired XlsxWorkbook workbook;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->{try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new IllegalStateException(e);}});
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);r.add("app.attachments.root",FILES::toString);
    }
    @BeforeEach void setup()throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,TENANT,ADMIN);
        for(String actor:List.of(ADMIN,MANAGER,BUYER,RECEIVER))jdbc.update("""
            insert into role_assignment(id,tenant_id,account_id,role_id,scope_type,valid_from,granted_by)
            select gen_random_uuid(),?::uuid,?::uuid,id,'TENANT',now(),?::uuid from app_role r where r.tenant_id=?::uuid and r.code='PLATFORM_ADMIN'
            and not exists(select 1 from role_assignment a where a.tenant_id=r.tenant_id and a.account_id=?::uuid and a.role_id=r.id)
            """,TENANT,actor,ADMIN,TENANT,actor);
        var row=api(get("/api/v1/procurement/hotels/"+HOTEL),ADMIN,200);
        api(put("/api/v1/procurement/hotels/"+HOTEL+"/config"),ADMIN,Map.of("expectedVersion",row.path("config").has("version")?row.path("config").path("version").asLong():-1,
            "enabled",true,"managerId",MANAGER,"buyerId",BUYER,"reviewerId",BUYER,"financeId",ADMIN,"financeDirectorId",ADMIN,"deputyId",MANAGER,"generalId",ADMIN,"receiverIds",List.of(RECEIVER)),200);
    }
    @AfterAll static void close()throws Exception{PG.close();}
    @AfterEach void clearIdentity(){TenantContext.clear();}
    @Test void nonAdminScopeAndRequestOnlyOwnershipAreEnforced()throws Exception {
        var item=item(HOTEL,"1");var batch=receive(ordered(item,3,"2"),"3","3","0");
        UUID id=UUID.fromString(batch.order().path("id").asText());
        as(TENANT,BUYER,HOTEL,"procurement.read");
        assertThat(service.context().get("canConfigure")).isEqualTo(false);
        // No stock-management permission or wildcard is needed by a configured reviewer.
        var reviewed=service.review(id,UUID.fromString(batch.id()),new ProcurementModels.Review(UUID.randomUUID(),receiptVersion(batch.order(),batch.id()),true,"已核对"));
        assertThat(reviewed.get("status")).isEqualTo("COMPLETED");
        as(TENANT,MANAGER,HOTEL,"procurement.request");
        assertThat(service.detail(id).get("id")).isEqualTo(id);
        as(TENANT,RECEIVER,HOTEL,"procurement.request");
        assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThat(service.list(UUID.fromString(HOTEL),"ALL",0)).noneMatch(row->id.equals(row.get("id")));
        as(TENANT,BUYER,OTHER,"procurement.read");
        assertThatThrownBy(()->service.detail(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(null,"ALL",0)).noneMatch(row->id.equals(row.get("id")));
        as(UUID.randomUUID().toString(),BUYER,HOTEL,"procurement.read");
        assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        as(TENANT,BUYER,HOTEL);
        assertThatThrownBy(service::context).isInstanceOf(AccessDeniedException.class);
    }
    @Test void databaseApplicationRoleCannotReadOtherTenantOrRewriteAudit()throws Exception {
        var order=ordered(item(HOTEL,"1"),1,"1");String id=order.path("id").asText();
        try(var c=PG.getPostgresDatabase().getConnection();var statement=c.createStatement()){
            c.setAutoCommit(false);statement.execute("set local role hotel_ai_os_app");
            statement.execute("set local app.tenant_id='"+TENANT+"'");
            try(var rows=statement.executeQuery("select count(*) from procurement_order where id='"+id+"'")){rows.next();assertThat(rows.getInt(1)).isOne();}
            statement.execute("set local app.tenant_id='"+UUID.randomUUID()+"'");
            try(var rows=statement.executeQuery("select count(*) from procurement_order where id='"+id+"'")){rows.next();assertThat(rows.getInt(1)).isZero();}
            statement.execute("set local app.tenant_id='"+TENANT+"'");
            assertThatThrownBy(()->statement.executeUpdate("delete from procurement_event where order_id='"+id+"'"))
                .isInstanceOf(java.sql.SQLException.class);
            c.rollback();
        }
    }
    @Test void onlyOwnerCanVoidNeverSubmittedDraftAndKeepEvidenceWithoutStockChange()throws Exception {
        var item=item(HOTEL,"1");var batch=receive(ordered(item,10,"2"),"10","10","0");String id=batch.order().path("id").asText(),draft=UUID.randomUUID().toString();
        api(put("/api/v1/procurement/orders/"+id+"/receipts/"+draft),RECEIVER,receiptBody(batch.order(),draft,"1","1","0"),200);
        var attachment=photo(id,draft);
        var partial=api(post("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/review"),BUYER,reviewBody(batch.order(),batch.id(),true),200);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL");assertThat(balance(item)).isEqualByComparingTo("10");
        var command=Map.of("commandId",UUID.randomUUID(),"expectedVersion",receiptVersion(partial,draft),"note","误建重复草稿");
        String path="/api/v1/procurement/orders/"+id+"/receipts/"+draft+"/void";
        api(post(path),MANAGER,command,403);
        var done=api(post(path),RECEIVER,command,200);api(post(path),RECEIVER,command,200);
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(item)).isEqualByComparingTo("10");
        assertThat(done.path("receipts").toString()).contains("VOIDED","误建重复草稿");
        assertThat(done.path("attachments").toString()).contains(attachment.path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from procurement_event where order_id=?::uuid and event_type='RECEIPT_DRAFT_VOIDED'",Integer.class,id)).isOne();
        api(post("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/void"),RECEIVER,Map.of("commandId",UUID.randomUUID(),"expectedVersion",receiptVersion(done,batch.id()),"note","不允许撤销已入库记录"),409);
    }
    @Test void receiptWaitsForReviewAndRetriesCannotDuplicateStock()throws Exception {
        var item=item(HOTEL,"100");var order=ordered(item,2,"50");String id=order.path("id").asText();
        var receipt=receive(order,"70","70","0");order=receipt.order();
        assertThat(balance(item)).isEqualByComparingTo("0");assertThat(order.path("status").asText()).isEqualTo("ORDERED");
        var command=reviewBody(order,receipt.id(),true);String path="/api/v1/procurement/orders/"+id+"/receipts/"+receipt.id()+"/review";
        api(post(path),MANAGER,command,403);
        var result=api(post(path),BUYER,command,200);var again=api(post(path),BUYER,command,200);
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");assertThat(again.path("lines").get(0).path("approved_quantity").decimalValue()).isEqualByComparingTo("70");
        assertThat(balance(item)).isEqualByComparingTo("70");
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where source_type='PROCUREMENT' and source_id=?::uuid",Integer.class,receipt.id())).isOne();
        var movement=result.path("receipts").get(0).path("lines").get(0);
        assertThat(movement.path("quantity_before").decimalValue()).isEqualByComparingTo("0");assertThat(movement.path("quantity_after").decimalValue()).isEqualByComparingTo("70");assertThat(movement.path("value_delta").decimalValue()).isEqualByComparingTo("35");
        var last=receive(result,"130","130","0");var complete=api(post("/api/v1/procurement/orders/"+id+"/receipts/"+last.id()+"/review"),BUYER,reviewBody(last.order(),last.id(),true),200);
        assertThat(complete.path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(item)).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("select inventory_value from store_inventory_balance where item_id=?::uuid",BigDecimal.class,item.path("id").asText())).isEqualByComparingTo("100");
    }
    @Test void returnedReceiptRetainsClockAndThirtyDayAlertIsDeduplicated()throws Exception {
        var item=item(HOTEL,"1");var order=ordered(item,100,"1");var batch=receive(order,"60","60","0");String id=order.path("id").asText();
        jdbc.update("update procurement_order set first_difference_at=now()-interval '29 days' where id=?::uuid",id);
        service.automate(UUID.fromString(TENANT));assertThat(alertCount(id)).isZero();
        jdbc.update("update procurement_order set first_difference_at=now()-interval '31 days' where id=?::uuid",id);
        String clock=jdbc.queryForObject("select first_difference_at::text from procurement_order where id=?::uuid",String.class,id);
        var returned=api(post("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/review"),BUYER,reviewBody(batch.order(),batch.id(),false),200);
        assertThat(balance(item)).isEqualByComparingTo("0");
        service.automate(UUID.fromString(TENANT));service.automate(UUID.fromString(TENANT));assertThat(alertCount(id)).isEqualTo(4);
        var saved=api(put("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()),RECEIVER,receiptBody(returned,batch.id(),"60","60","0"),200);
        var resubmitted=api(post("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/submit"),RECEIVER,cmd(receiptVersion(saved,batch.id())),200);
        assertThat(jdbc.queryForObject("select first_difference_at::text from procurement_order where id=?::uuid",String.class,id)).isEqualTo(clock);
        api(post("/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/review"),BUYER,reviewBody(resubmitted,batch.id(),true),200);
        assertThat(balance(item)).isEqualByComparingTo("60");assertThat(alertCount(id)).isEqualTo(4);
        var detail=api(get("/api/v1/procurement/orders/"+id),ADMIN,200);assertThat(detail.path("notifications").toString()).contains("UNBOUND");
    }
    @Test void lowStockGeneratesOneDraftAndManagerCanAddDemandBeforeSubmission()throws Exception {
        var low=item(HOTEL,"100");var extra=item(HOTEL,"1");
        api(put("/api/v1/procurement/hotels/"+HOTEL+"/rules/"+low.path("id").asText()),ADMIN,Map.of("expectedVersion",-1,"enabled",true,"targetQuantity",200),200);
        service.automate(UUID.fromString(TENANT));service.automate(UUID.fromString(TENANT));
        String id=jdbc.queryForObject("select order_id::text from procurement_line where item_id=?::uuid",String.class,low.path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from procurement_line where item_id=?::uuid",Integer.class,low.path("id").asText())).isOne();
        var order=api(get("/api/v1/procurement/orders/"+id),MANAGER,200);
        assertThat(order.path("source").asText()).isEqualTo("LOW_STOCK");
        var body=Map.of("commandId",UUID.randomUUID(),"expectedVersion",order.path("version").asLong(),"title","补货与额外需求","lines",List.of(Map.of("itemId",low.path("id").asText(),"quantity",2,"unit","箱"),Map.of("itemId",extra.path("id").asText(),"quantity",5,"unit","支")));
        api(put("/api/v1/procurement/orders/"+id),BUYER,body,403);
        var saved=api(put("/api/v1/procurement/orders/"+id),MANAGER,body,200);
        var sent=api(post("/api/v1/procurement/orders/"+id+"/submit"),MANAGER,cmd(saved.path("version").asLong()),200);
        assertThat(sent.path("lines")).hasSize(2);assertThat(sent.path("status").asText()).isEqualTo("SUBMITTED");
        var todos=api(get("/api/v1/procurement/todos"),BUYER,200);assertThat(todos.toString()).contains(id,"待采购办理");
    }
    @Test void mismatchedUnitsCrossStoreAndMissingPhotoFailWithoutPosting()throws Exception {
        var foreign=item(OTHER,"1");api(post("/api/v1/procurement/hotels/"+HOTEL+"/orders"),MANAGER,request(foreign,1),400);
        var item=item(HOTEL,"1");var order=ordered(item,10,"2");String id=order.path("id").asText(),receipt=UUID.randomUUID().toString();
        var bad=receiptBody(order,receipt,"5","3","1");api(put("/api/v1/procurement/orders/"+id+"/receipts/"+receipt),RECEIVER,bad,400);
        var saved=api(put("/api/v1/procurement/orders/"+id+"/receipts/"+receipt),RECEIVER,receiptBody(order,receipt,"11","11","0"),200);
        api(post("/api/v1/procurement/orders/"+id+"/receipts/"+receipt+"/submit"),RECEIVER,cmd(receiptVersion(saved,receipt)),400);
        photo(id,receipt);api(post("/api/v1/procurement/orders/"+id+"/receipts/"+receipt+"/submit"),RECEIVER,cmd(receiptVersion(saved,receipt)),400);
        assertThat(balance(item)).isEqualByComparingTo("0");
    }
    @Test void concurrentReviewPostsOneMovementAndRecordsActualStockAtApproval()throws Exception {
        var item=item(HOTEL,"1");var batch=receive(ordered(item,10,"2"),"10","10","0");String id=batch.order().path("id").asText();
        api(post("/api/v1/store-resources/hotels/"+HOTEL+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",item.path("id").asText(),"movementType","RECEIPT","quantity",5,"unitCost",2,"reason","复核前另一批入库"),201);
        var body=reviewBody(batch.order(),batch.id(),true);String path="/api/v1/procurement/orders/"+id+"/receipts/"+batch.id()+"/review";
        try(var pool=Executors.newFixedThreadPool(2)){var tasks=List.<Callable<JsonNode>>of(()->api(post(path),BUYER,body,200),()->api(post(path),BUYER,body,200));for(var f:pool.invokeAll(tasks))assertThat(f.get(15,TimeUnit.SECONDS).path("status").asText()).isEqualTo("COMPLETED");}
        assertThat(balance(item)).isEqualByComparingTo("15");
        var detail=api(get("/api/v1/procurement/orders/"+id),ADMIN,200);assertThat(detail.path("receipts").get(0).path("lines").get(0).path("quantity_before").decimalValue()).isEqualByComparingTo("5");
    }
    @Test void splitCostRoundingEqualsFullPurchaseValue()throws Exception {
        var item=item(HOTEL,"3");var order=ordered(item,1,"1");
        for(int i=0;i<3;i++){var batch=receive(order,"1","1","0");order=api(post("/api/v1/procurement/orders/"+order.path("id").asText()+"/receipts/"+batch.id()+"/review"),BUYER,reviewBody(batch.order(),batch.id(),true),200);}
        assertThat(jdbc.queryForObject("select inventory_value from store_inventory_balance where item_id=?::uuid",BigDecimal.class,item.path("id").asText())).isEqualByComparingTo("1");
    }
    @Test void spreadsheetPreviewIsScopedAndDoesNotCreateOrders()throws Exception {
        var item=item(HOTEL,"100");var sheets=new LinkedHashMap<String,List<List<String>>>();sheets.put("采购明细",List.of(List.of("物品编码","数量","单位"),List.of(item.path("sku").asText(),"2","箱")));
        var file=new MockMultipartFile("file","采购.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook.write(sheets));
        var result=api(multipart("/api/v1/procurement/hotels/"+HOTEL+"/imports/preview").file(file),MANAGER,200);
        assertThat(result.path("valid").asBoolean()).isTrue();assertThat(result.path("rows").get(0).path("baseQuantity").decimalValue()).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("select count(*) from procurement_line where item_id=?::uuid",Integer.class,item.path("id").asText())).isZero();
    }
    @Test void receiptEvidenceCanBeViewedButOtherActorsCannotUploadIt()throws Exception {
        var item=item(HOTEL,"1");var order=ordered(item,1,"1");String id=order.path("id").asText(),r=UUID.randomUUID().toString();
        api(put("/api/v1/procurement/orders/"+id+"/receipts/"+r),RECEIVER,receiptBody(order,r,"1","1","0"),200);
        var attachment=photo(id,r);mvc.perform(headers(get("/api/v1/procurement/orders/"+id+"/attachments/"+attachment.path("id").asText()),BUYER)).andExpect(status().isOk()).andExpect(result->assertThat(result.getResponse().getContentType()).isEqualTo("image/png"));
        api(multipart("/api/v1/procurement/orders/"+id+"/attachments").file(image()).param("receipt",r),MANAGER,403);
    }
    private JsonNode item(String hotel,String factor)throws Exception{return api(post("/api/v1/store-resources/hotels/"+hotel+"/items"),ADMIN,Map.of("sku","PUR-"+UUID.randomUUID(),"name","采购测试牙刷","category","CONSUMABLE","unit","支","unitCost",0,"minimumQuantity",10,"purchaseUnit","箱","purchaseFactor",factor),201);}
    private Map<String,Object> request(JsonNode item,int count){return Map.of("commandId",UUID.randomUUID(),"expectedVersion",-1,"title","门店采购测试","lines",List.of(Map.of("itemId",item.path("id").asText(),"quantity",count,"unit","箱")));}
    private JsonNode ordered(JsonNode item,int count,String price)throws Exception {
        var draft=api(post("/api/v1/procurement/hotels/"+HOTEL+"/orders"),MANAGER,request(item,count),201);String id=draft.path("id").asText();
        var submitted=api(post("/api/v1/procurement/orders/"+id+"/submit"),MANAGER,cmd(draft.path("version").asLong()),200);
        return api(post("/api/v1/procurement/orders/"+id+"/purchase"),BUYER,Map.of("commandId",UUID.randomUUID(),"expectedVersion",submitted.path("version").asLong(),"supplier","测试供应商","expectedOn","2026-09-25","buyerId",BUYER,"prices",List.of(Map.of("lineId",submitted.path("lines").get(0).path("id").asText(),"unitPrice",price))),200);
    }
    private record Batch(JsonNode order,String id){}
    private Batch receive(JsonNode order,String arrived,String accepted,String rejected)throws Exception{String id=order.path("id").asText(),r=UUID.randomUUID().toString();var saved=api(put("/api/v1/procurement/orders/"+id+"/receipts/"+r),RECEIVER,receiptBody(order,r,arrived,accepted,rejected),200);photo(id,r);return new Batch(api(post("/api/v1/procurement/orders/"+id+"/receipts/"+r+"/submit"),RECEIVER,cmd(receiptVersion(saved,r)),200),r);}
    private Map<String,Object> receiptBody(JsonNode order,String receipt,String arrived,String accepted,String rejected){return Map.of("commandId",UUID.randomUUID(),"expectedVersion",receiptVersion(order,receipt),"note","实际收货数量核对记录","lines",List.of(Map.of("lineId",order.path("lines").get(0).path("id").asText(),"arrived",arrived,"accepted",accepted,"rejected",rejected)));}
    private long receiptVersion(JsonNode order,String receipt){for(var r:order.path("receipts"))if(r.path("id").asText().equals(receipt))return r.path("version").asLong();return -1;}
    private Map<String,Object> cmd(long version){return Map.of("commandId",UUID.randomUUID(),"expectedVersion",version);}
    private Map<String,Object> reviewBody(JsonNode order,String r,boolean approved){return Map.of("commandId",UUID.randomUUID(),"expectedVersion",receiptVersion(order,r),"approved",approved,"note",approved?"数量已核对":"请补充核对");}
    private BigDecimal balance(JsonNode item){return jdbc.queryForObject("select quantity from store_inventory_balance where item_id=?::uuid",BigDecimal.class,item.path("id").asText());}
    private int alertCount(String id){return jdbc.queryForObject("select count(*) from notification where source_id=?::uuid and notification_type='PROCUREMENT_OVERDUE_30_DAYS'",Integer.class,id);}
    private JsonNode photo(String id,String receipt)throws Exception{return api(multipart("/api/v1/procurement/orders/"+id+"/attachments").file(image()).param("receipt",receipt),RECEIVER,201);}
    private MockMultipartFile image()throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB),"png",output);return new MockMultipartFile("file","收货.png","image/png",output.toByteArray());}
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder req,String actor){return req.header("X-Tenant-Id",TENANT).header("X-Actor-Id",actor);}
    private JsonNode api(MockHttpServletRequestBuilder req,String actor,Object body,int code)throws Exception{return api(req.contentType("application/json").content(json.writeValueAsString(body)),actor,code);}
    private JsonNode api(MockHttpServletRequestBuilder req,String actor,int code)throws Exception{var response=mvc.perform(headers(req,actor)).andExpect(status().is(code)).andReturn().getResponse();return json.readTree(response.getContentAsByteArray());}
    private void as(String tenant,String actor,String hotel,String... permissions){TenantContext.set(new TenantPrincipal(UUID.fromString(tenant),UUID.fromString(actor),"PROCUREMENT_TEST",Set.of("PROCUREMENT_TEST"),Set.of(permissions),Set.of(UUID.fromString(hotel)),Set.of(),false,UUID.randomUUID()));}
    private static EmbeddedPostgres start(){try{var pg=EmbeddedPostgres.builder().start();try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");}return pg;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Path temp(){try{return Files.createTempDirectory("procurement-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
