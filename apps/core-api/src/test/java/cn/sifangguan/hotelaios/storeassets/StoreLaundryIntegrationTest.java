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
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StoreLaundryIntegrationTest {
    private static final String T="10000000-0000-0000-0000-000000000001", H="12000000-0000-0000-0000-000000000003";
    private static final String OTHER="12000000-0000-0000-0000-000000000004", ADMIN=PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String GM="19000000-0000-0000-0000-000000000002", GMA="19200000-0000-0000-0000-000000000001";
    private static final String EMP="19000000-0000-0000-0000-000000000003", EA="19200000-0000-0000-0000-000000000002";
    private static final String PATH="/api/v1/store-resources/hotels/"+H+"/laundry";
    private static final EmbeddedPostgres PG=start(); private static final String URL=url();
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL); r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres"); r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true); r.add("app.database.rls-enabled",()->true);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void setup() { PlatformAdminTestFixture.ensure(jdbc,T,"19000000-0000-0000-0000-000000000001"); }

    @Test void partialReceiptsFreezeRatesAndOnlyQualifiedReturnsGenerateFees() throws Exception {
        String item=item("LINEN",100); rate(item,"1.25",-1,200);
        JsonNode order=dispatch(item,20,201); String id=order.path("id").asText(), line=lineId(order);
        balance(item,"100","1000"); held(item,"20");
        rate(item,"2",0,200);
        order=receive(id,0,List.of(receiptLine(line,8,2,0,"污渍返洗")),false,201);
        assertThat(order.path("fee").decimalValue()).isEqualByComparingTo("10");
        assertThat(order.path("pending_quantity").decimalValue()).isEqualByComparingTo("12");
        assertThat(order.path("status").asText()).isEqualTo("PARTIAL");
        order=receive(id,1,List.of(receiptLine(line,12,0,0,"")),false,201);
        assertThat(order.path("fee").decimalValue()).isEqualByComparingTo("25");
        assertThat(order.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(order.path("receipts")).hasSize(2); balance(item,"100","1000"); held(item,"0");
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where item_id=?::uuid",Integer.class,item)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update store_laundry_line set unit_price=99 where order_id=?::uuid",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from store_laundry_receipt where order_id=?::uuid",id)).isInstanceOf(RuntimeException.class);
    }

    @Test void writeOffNeedsConfirmationAndReversalRestoresOriginalCostNotCurrentRate() throws Exception {
        String item=item("LINEN",10); rate(item,"1.5",-1,200);
        JsonNode order=dispatch(item,8,201); String id=order.path("id").asText(), line=lineId(order);
        receive(id,0,List.of(receiptLine(line,6,0,2,"丢失两条")),false,400); balance(item,"10","100");
        receive(id,0,List.of(receiptLine(line,6,0,2,"")),true,400);
        order=receive(id,0,List.of(receiptLine(line,6,0,2,"丢失两条")),true,201);
        assertThat(order.path("fee").decimalValue()).isEqualByComparingTo("9"); balance(item,"8","80");
        String receipt=order.path("receipts").get(0).path("id").asText();
        movement(item,"RECEIPT",2,50,201); balance(item,"10","180");
        order=call(post(PATH+"/orders/"+id+"/receipts/"+receipt+"/reverse"),ADMIN,Map.of("reason","登记数量有误","expectedVersion",1),200);
        assertThat(order.path("fee").decimalValue()).isZero(); balance(item,"12","200"); held(item,"8");
        assertThat(order.path("receipts").get(0).path("status").asText()).isEqualTo("REVERSED");
        call(post(PATH+"/orders/"+id+"/receipts/"+receipt+"/reverse"),ADMIN,Map.of("reason","登记数量有误","expectedVersion",1),200);
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where source_id=?::uuid",Integer.class,receipt)).isEqualTo(2);
        call(post(PATH+"/orders/"+id+"/cancel"),ADMIN,Map.of("reason","取消错误送洗单","expectedVersion",2),200); held(item,"0");
    }

    @Test void reservationPreventsDuplicateDispatchAndManualOverdraw() throws Exception {
        String item=item("LINEN",10); rate(item,"1",-1,200);
        JsonNode order=dispatch(item,8,201); dispatch(item,3,409); held(item,"8");
        movement(item,"DAMAGE",3,10,409); balance(item,"10","100");
        movement(item,"DAMAGE",2,10,201); balance(item,"8","80");
        call(post(PATH+"/orders/"+order.path("id").asText()+"/cancel"),ADMIN,Map.of("reason","尚未实际交付","expectedVersion",0),200);
        held(item,"0"); dispatch(item,8,201);
    }

    @Test void repeatedAndConcurrentCommandsNeverDoubleCharge() throws Exception {
        String item=item("LINEN",10); rate(item,"0.35",-1,200);
        String key=UUID.randomUUID().toString(); var body=dispatchBody(item,10,0); JsonNode order;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call(post(PATH+"/orders").header("Idempotency-Key",key),ADMIN,body,201));
            var b=pool.submit(()->call(post(PATH+"/orders").header("Idempotency-Key",key),ADMIN,body,201));
            order=a.get(); assertThat(b.get().path("id").asText()).isEqualTo(order.path("id").asText());
        }
        call(post(PATH+"/orders").header("Idempotency-Key",key),ADMIN,dispatchBody(item,9,0),409);
        String id=order.path("id").asText(), receiptKey=UUID.randomUUID().toString();
        var input=receiveBody(0,List.of(receiptLine(lineId(order),10,0,0,"")),false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call(post(PATH+"/orders/"+id+"/receipts").header("Idempotency-Key",receiptKey),ADMIN,input,201));
            var b=pool.submit(()->call(post(PATH+"/orders/"+id+"/receipts").header("Idempotency-Key",receiptKey),ADMIN,input,201));
            assertThat(a.get().path("fee").decimalValue()).isEqualByComparingTo("3.5"); assertThat(b.get().path("receipts")).hasSize(1);
        }
        receive(id,0,List.of(receiptLine(lineId(order),1,0,0,"")),false,409);
        receive(id,1,List.of(receiptLine(lineId(order),1,0,0,"")),false,409);
    }

    @Test void competingReceiptsWithDifferentKeysCannotOverReceive() throws Exception {
        String item=item("LINEN",10); rate(item,"1",-1,200); JsonNode order=dispatch(item,10,201);
        String path=PATH+"/orders/"+order.path("id").asText()+"/receipts";
        var body=receiveBody(0,List.of(receiptLine(lineId(order),7,0,0,"")),false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->raw(post(path).header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body).getStatus());
            var b=pool.submit(()->raw(post(path).header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body).getStatus());
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);
        }
        held(item,"3");
    }

    @Test void reverseAfterRedispatchCannotCreateImpossibleCustody() throws Exception {
        String item=item("LINEN",10); rate(item,"1",-1,200); JsonNode order=dispatch(item,10,201);
        String id=order.path("id").asText(); order=receive(id,0,List.of(receiptLine(lineId(order),10,0,0,"")),false,201);
        dispatch(item,10,201);
        call(post(PATH+"/orders/"+id+"/receipts/"+order.path("receipts").get(0).path("id").asText()+"/reverse"),ADMIN,Map.of("reason","误填","expectedVersion",1),409);
        JsonNode unchanged=call(get(PATH+"/orders/"+id),ADMIN,null,200);
        assertThat(unchanged.path("fee").decimalValue()).isEqualByComparingTo("10"); held(item,"10");
    }

    @Test void mixedBadLineRollsBackEntireReceiptAndPermissionsFailClosed() throws Exception {
        String item=item("LINEN",10); rate(item,"1",-1,200); JsonNode order=dispatch(item,10,201); String id=order.path("id").asText();
        receive(id,0,List.of(receiptLine(lineId(order),8,0,0,""),receiptLine(UUID.randomUUID().toString(),2,0,0,"")),false,400);
        assertThat(call(get(PATH+"/orders/"+id),ADMIN,null,200).path("receipts")).isEmpty(); held(item,"10");
        call(get(PATH+"/orders"),EMP,null,403);
        call(put(PATH+"/rates/"+item),GM,Map.of("unitPrice",3,"expectedVersion",0,"expectedItemVersion",0),403);
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),GM,dispatchBody(item,1,0),403);
        call(get("/api/v1/store-resources/hotels/"+OTHER+"/laundry/orders/"+id),ADMIN,null,404);
        call(get("/api/v1/store-resources/hotels/"+OTHER+"/laundry/catalog"),GM,null,403);
    }

    @Test void validatesPrecisionDatesUnitsStaleVersionsAndCancellation() throws Exception {
        String item=item("LINEN",10); dispatch(item,1,409); rate(item,"1",-1,200); rate(item,"2",-1,409);
        var body=new LinkedHashMap<>(dispatchBody(item,1,0)); body.put("dispatchedOn",LocalDate.now().plusDays(2).toString());
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body,400);
        body.put("dispatchedOn",LocalDate.now().toString()); body.put("lines",List.of(Map.of("itemId",item,"quantity","0.00001","expectedRateVersion",0,"expectedItemVersion",0)));
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body,400);
        body.put("lines",List.of(Map.of("itemId",item,"quantity",1,"expectedRateVersion",9,"expectedItemVersion",0)));
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body,409);
        assertThatThrownBy(()->jdbc.update("update store_resource_item set unit='箱' where id=?::uuid",item)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("update store_resource_item set category='CONSUMABLE' where id=?::uuid",item)).isInstanceOf(RuntimeException.class);
        String nonLinen=item("CONSUMABLE",10); rate(nonLinen,"1",-1,409);
        JsonNode order=dispatch(item,1,201); String id=order.path("id").asText();
        receive(id,0,List.of(receiptLine(lineId(order),1,0,0,"")),false,201);
        call(post(PATH+"/orders/"+id+"/cancel"),ADMIN,Map.of("reason","不能直接取消有回收单据","expectedVersion",1),409);
        assertThat(call(get(PATH+"/orders").param("status","COMPLETED"),ADMIN,null,200).path("items").toString()).contains(id);
    }

    @Test void malformedLinesAreRejectedAndInactiveLinenCanStillBeReturned() throws Exception {
        String item=item("LINEN",10); rate(item,"0",-1,200);
        var body=new LinkedHashMap<>(dispatchBody(item,1,0)); body.put("lines",Collections.singletonList(null));
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body,400);
        body.put("lines",List.of(Map.of("itemId",item,"quantity",1,"expectedRateVersion",0,"expectedItemVersion",0),Map.of("itemId",item,"quantity",1,"expectedRateVersion",0,"expectedItemVersion",0)));
        call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,body,400);
        JsonNode order=dispatch(item,1,201); String id=order.path("id").asText();
        call(post(PATH+"/orders/"+id+"/receipts").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,
                receiveBody(0,Collections.singletonList(null),false),400);
        call(post("/api/v1/store-resources/hotels/"+H+"/items/"+item+"/actions/disable"),ADMIN,Map.of("expectedVersion",0),200);
        dispatch(item,1,409);
        order=receive(id,0,List.of(receiptLine(lineId(order),1,0,0,"")),false,201);
        assertThat(order.path("status").asText()).isEqualTo("COMPLETED"); assertThat(order.path("fee").decimalValue()).isZero();
        balance(item,"10","100");
    }

    private String item(String category,int quantity) throws Exception {
        String id=call(post("/api/v1/store-resources/hotels/"+H+"/items"),ADMIN,Map.of("sku",UUID.randomUUID().toString(),"name","洗涤测试布草","category",category,"unit","条","unitCost",10,"minimumQuantity",0),201).path("id").asText();
        movement(id,"RECEIPT",quantity,10,201); return id;
    }
    private void rate(String item,String price,int version,int status) throws Exception { call(put(PATH+"/rates/"+item),ADMIN,Map.of("unitPrice",price,"expectedVersion",version,"expectedItemVersion",0),status); }
    private void movement(String id,String type,int quantity,int price,int status) throws Exception {
        call(post("/api/v1/store-resources/hotels/"+H+"/movements").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,Map.of("itemId",id,"movementType",type,"quantity",quantity,"unitCost",price,"reason","测试库存"),status);
    }
    private Map<String,Object> dispatchBody(String item,int quantity,int rateVersion) { return Map.of("supplier","测试洗涤厂","dispatchedOn",LocalDate.now().toString(),"lines",List.of(Map.of("itemId",item,"quantity",quantity,"expectedRateVersion",rateVersion,"expectedItemVersion",0))); }
    private JsonNode dispatch(String item,int quantity,int status) throws Exception { return call(post(PATH+"/orders").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,dispatchBody(item,quantity,0),status); }
    private String lineId(JsonNode order) { return order.path("lines").get(0).path("id").asText(); }
    private Map<String,Object> receiptLine(String line,int accepted,int rewash,int lost,String reason) { return Map.of("lineId",line,"acceptedQuantity",accepted,"rewashQuantity",rewash,"writtenOffQuantity",lost,"differenceReason",reason); }
    private Map<String,Object> receiveBody(int version,List<Map<String,Object>> lines,boolean confirm) { return Map.of("receivedOn",LocalDate.now().toString(),"expectedVersion",version,"confirmWriteOff",confirm,"lines",lines); }
    private JsonNode receive(String order,int version,List<Map<String,Object>> lines,boolean confirm,int status) throws Exception { return call(post(PATH+"/orders/"+order+"/receipts").header("Idempotency-Key",UUID.randomUUID().toString()),ADMIN,receiveBody(version,lines,confirm),status); }
    private void held(String item,String quantity) throws Exception {
        JsonNode catalog=call(get(PATH+"/catalog"),ADMIN,null,200);
        for(JsonNode row:catalog) if(row.path("item_id").asText().equals(item)) { assertThat(row.path("pending_quantity").decimalValue()).isEqualByComparingTo(quantity); return; }
        fail("item missing from catalogue");
    }
    private void balance(String item,String quantity,String value) { var row=jdbc.queryForMap("select quantity,inventory_value from store_inventory_balance where item_id=?::uuid",item); assertThat((BigDecimal)row.get("quantity")).isEqualByComparingTo(quantity); assertThat((BigDecimal)row.get("inventory_value")).isEqualByComparingTo(value); }
    private org.springframework.mock.web.MockHttpServletResponse raw(MockHttpServletRequestBuilder request,String actor,Object body) throws Exception {
        request.header("X-Tenant-Id",T).header("X-Actor-Id",actor);
        if(actor.equals(GM))request.header("X-Assignment-Id",GMA); if(actor.equals(EMP))request.header("X-Assignment-Id",EA);
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }
    private JsonNode call(MockHttpServletRequestBuilder request,String actor,Object body,int status) throws Exception { var response=raw(request,actor,body); assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return mapper.readTree(response.getContentAsString()); }
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
