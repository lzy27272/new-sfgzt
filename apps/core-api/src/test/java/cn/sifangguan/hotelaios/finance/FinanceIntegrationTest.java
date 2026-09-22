package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.context.*;
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
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FinanceIntegrationTest {
    static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final EmbeddedPostgres PG=start();static final String URL=url();static final Path FILES=temp();
    @Autowired FinanceService service;@Autowired FinanceEvidenceService evidence;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    UUID owner,other,manager,finance,leader,hotel,assignment,category;String invoiceNumber;
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);r.add("app.finance.attachments-root",FILES::toString);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @AfterEach void clear() {TenantContext.clear();}
    @BeforeEach void setup() {
        owner=account();other=account();manager=account();finance=account();leader=account();hotel=UUID.randomUUID();assignment=UUID.randomUUID();category=UUID.randomUUID();
        invoiceNumber="%020d".formatted(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'财务测试门店','HOTEL')",hotel,T,hotel.toString());
        jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",T,hotel,hotel);
        UUID employee=UUID.randomUUID();jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name) values(?,?,?,?,'报销测试员工')",employee,T,owner,employee.toString());
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) select ?,?,?,?,id,current_date from position_definition where tenant_id=? limit 1",assignment,T,employee,hotel,T);
        as(owner,true,"finance.configure");service.configure(hotel,config(-1,manager,finance,leader));
        service.category(hotel,category,new FinanceModels.Category("客房费用","EXPENSE","客房,补贴","管理费用",true,-1));
    }
    @Test void completeThreeStageChainWarnsDifferenceAndSeparatesPayment() {
        UUID id=draft(true);as(owner,false,"finance.submit");var pending=service.submit(id,1);assertThat(pending.get("status")).isEqualTo("MANAGER_REVIEW");
        assertThat(service.submit(id,1).get("version")).isEqualTo(2);
        as(leader,false,"finance.review");assertThatThrownBy(()->service.review(id,review(2,"APPROVE",false))).isInstanceOf(ResponseStatusException.class);
        as(manager,false,"finance.review");var command=review(2,"APPROVE",false);assertThat(service.review(id,command).get("status")).isEqualTo("FINANCE_REVIEW");service.review(id,command);
        as(finance,false,"finance.review");assertThatThrownBy(()->service.review(id,review(3,"APPROVE",false))).hasMessageContaining("核实差额");
        assertThat(service.review(id,review(3,"APPROVE",true)).get("status")).isEqualTo("LEADER_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from finance_expense_entry where claim_id=?",Integer.class,id)).isZero();
        as(leader,false,"finance.review");assertThat(service.review(id,review(4,"APPROVE",false)).get("status")).isEqualTo("APPROVED");
        as(finance,false,"finance.read","finance.pay");var totals=(Map<?,?>)service.ledger(YearMonth.from(today).toString(),hotel).get("totals");assertThat(totals.get("total")).isEqualTo("90.00");assertThat(totals.get("paid")).isEqualTo("0");
        var payment=new FinanceModels.Payment(UUID.randomUUID(),5,new BigDecimal("90"),"测试流水",today);service.payment(id,payment);service.payment(id,payment);
        assertThat(jdbc.queryForObject("select count(*) from finance_payment where claim_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(service.voucher(id)).containsEntry("status","DRAFT").containsKey("evidence");
        assertThat(service.voucher(id).get("amount")).isEqualTo("90.00");
        assertThatThrownBy(()->jdbc.update("update finance_expense_entry set amount=1 where claim_id=?",id)).isInstanceOf(RuntimeException.class);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.save(id,input(6,List.of(),true))).hasMessageContaining("不能覆盖");
    }
    @Test void duplicateInvoiceIsBlockedIncludingConcurrentAndHistoricalSubmissions() throws Exception {
        UUID first=draft(false),second=draft(false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->submitAttempt(first));var b=pool.submit(()->submitAttempt(second));
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where invoice_key=?",Integer.class,"DIGITAL:"+invoiceNumber)).isEqualTo(1);
        invoiceNumber="%020d".formatted(Math.abs(UUID.randomUUID().getLeastSignificantBits()));UUID historical=draft(false);
        as(owner,true,"finance.configure");service.historical(new FinanceModels.Historical(List.of(new FinanceModels.HistoricalInvoice("",invoiceNumber,"金蝶历史测试"))));
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.submit(historical,1)).hasMessageContaining("历史已报销");
    }
    @Test void returningPreservesReservationAndRestartRequiresManagerReview() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"RETURN",false));
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,id)).isEqualTo(1);
        as(owner,false,"finance.submit");assertThat(service.submit(id,3).get("status")).isEqualTo("MANAGER_REVIEW");
        service.withdraw(id,4);assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,id)).isZero();
    }
    @Test void forbidsSelfReviewMissingScopeAndOtherEmployeesAndStaleEdits() {
        UUID id=draft(false);as(other,false,"finance.submit");assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        as(finance,false,"finance.read");TenantContext.set(principal(finance,T,Set.of("finance.read"),Set.of(),false));assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.save(id,input(0,List.of(),false))).hasMessageContaining("其他页面更新");
        as(owner,true,"finance.configure");service.configure(hotel,config(0,owner,finance,leader));
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("本人");
        as(owner,true,"finance.configure");assertThatThrownBy(()->service.configure(hotel,config(1,manager,manager,leader))).hasMessageContaining("不同账号");
        TenantContext.set(principal(owner,UUID.randomUUID(),Set.of("finance.read"),Set.of(hotel),true));assertThatThrownBy(()->service.detail(id)).hasMessageContaining("不存在");
    }
    @Test void snapshotsCompanyAndDoesNotDoubleCountInventory() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"APPROVE",false));
        as(owner,true,"finance.configure");service.category(hotel,category,new FinanceModels.Category("采购入库","INVENTORY","采购","库存商品",true,0));
        as(finance,false,"finance.review");service.review(id,review(3,"APPROVE",true));
        as(leader,false,"finance.review");service.review(id,review(4,"APPROVE",false));
        jdbc.update("update org_unit set name='改名' where id=?",hotel);jdbc.update("update employee set name='新姓名' where account_id=?",owner);
        as(finance,false,"finance.read");assertThat(service.detail(id).get("hotel_name")).isEqualTo("财务测试门店");
        var ledger=service.ledger(YearMonth.from(today).toString(),hotel);assertThat(((Map<?,?>)ledger.get("totals")).get("total")).isEqualTo("0");assertThat((List<?>)ledger.get("otherTreatments")).hasSize(1);
    }
    @Test void uploadPreservesOriginalBytesRetriesAndRejectsInvalidFiles() throws Exception {
        as(owner,false,"finance.submit");UUID id=UUID.randomUUID();service.create(new FinanceModels.Create(id,assignment));
        var bytes=new ByteArrayOutputStream();var img=new BufferedImage(32,48,BufferedImage.TYPE_INT_RGB);ImageIO.write(img,"png",bytes);var original=bytes.toByteArray();UUID attachment=UUID.randomUUID();
        var file=new MockMultipartFile("file","receipt.png","image/png",original);
        evidence.upload(id,attachment,"INVOICE",file);evidence.upload(id,attachment,"INVOICE",file);
        assertThat(evidence.download(id,attachment).resource().getContentAsByteArray()).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from finance_attachment where claim_id=?",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->evidence.upload(id,UUID.randomUUID(),"PAYMENT",new MockMultipartFile("file","fake.png","image/png","bad".getBytes()))).hasMessageContaining("真实的");
        as(other,false,"finance.submit");assertThatThrownBy(()->evidence.download(id,attachment)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void controllerValidatesRequiredReviewFieldsAndConfigurationIsNotFinancialRead() throws Exception {
        PlatformAdminTestFixture.ensure(jdbc,T.toString(),owner.toString());
        var response=mvc.perform(post("/api/v1/finance/claims/"+UUID.randomUUID()+"/review").header("X-Tenant-Id",T).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID).contentType("application/json").content("{}")).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(400);
        UUID id=draft(false);as(other,true,"finance.configure");assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        assertThat((List<?>)service.configuration().get("stores")).isNotEmpty();
    }
    @Test void runtimeDatabaseRoleCannotReadOtherTenantsOrMutateFacts() throws Exception {
        UUID id=draft(false);
        try(var connection=PG.getPostgresDatabase().getConnection();var statement=connection.createStatement()) {
            statement.execute("set role hotel_ai_os_app");
            statement.execute("select set_config('app.tenant_id','"+T+"',false)");
            try(var r=statement.executeQuery("select count(*) from finance_claim where id='"+id+"'")) {r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            statement.execute("select set_config('app.tenant_id','"+UUID.randomUUID()+"',false)");
            try(var r=statement.executeQuery("select count(*) from finance_claim")) {r.next();assertThat(r.getInt(1)).isZero();}
            assertThatThrownBy(()->statement.executeUpdate("delete from finance_review")).isInstanceOf(java.sql.SQLException.class);
        }
    }
    @Test void returnedReplacementRetainsReadableOriginalsAndFrozenReview() throws Exception {
        UUID id=draft(false),originalId=UUID.randomUUID();
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(24,36,BufferedImage.TYPE_INT_RGB),"png",bytes);var original=bytes.toByteArray();
        var file=new MockMultipartFile("file","original.png","image/png",original);
        evidence.upload(id,originalId,"PAYMENT",file);service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"RETURN",false));
        String snapshot=jdbc.queryForObject("select review_snapshot::text from finance_review where claim_id=?",String.class,id);
        assertThat(snapshot).contains(originalId.toString(),FinanceService.hash(original),invoiceNumber);
        as(owner,false,"finance.submit");service.removeAttachment(id,originalId,3);
        assertThat(((List<?>)service.detail(id).get("attachments"))).hasSize(3);
        assertThat(evidence.download(id,originalId).resource().getContentAsByteArray()).isEqualTo(original);
        assertThatThrownBy(()->evidence.upload(id,originalId,"PAYMENT",file)).hasMessageContaining("已从草稿移除");
        evidence.upload(id,UUID.randomUUID(),"PAYMENT",file);
        service.save(id,input(4,List.of(),false));
        as(manager,false,"finance.review");assertThat(evidence.download(id,originalId).resource().getContentAsByteArray()).isEqualTo(original);
        assertThat(jdbc.queryForObject("select review_snapshot::text from finance_review where claim_id=?",String.class,id)).isEqualTo(snapshot);
        assertThatThrownBy(()->jdbc.update("delete from finance_attachment where id=?",originalId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("update finance_attachment set original_name='changed' where id=?",originalId)).isInstanceOf(RuntimeException.class);
        as(other,false,"finance.submit");assertThatThrownBy(()->evidence.download(id,originalId)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void missingProofAndDisabledConfigurationBlockSubmissionAndPaymentCannotPrecedeApproval() {
        UUID id=draft(false);
        as(finance,false,"finance.pay");assertThatThrownBy(()->service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),1,new BigDecimal("90"),"test",today))).hasMessageContaining("三段审批");
        as(owner,false,"finance.submit");UUID paymentId=jdbc.queryForObject("select id from finance_attachment where claim_id=? and kind='PAYMENT'",UUID.class,id);
        service.removeAttachment(id,paymentId,1);assertThatThrownBy(()->service.submit(id,2)).hasMessageContaining("补齐发票");
        as(owner,true,"finance.configure");service.configure(hotel,new FinanceModels.StoreConfig("测试酒店有限责任公司","91520100123456789X",manager,finance,leader,"其他应付款",false,0));
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.create(new FinanceModels.Create(UUID.randomUUID(),assignment))).hasMessageContaining("报销尚未启用");
    }
    private String submitAttempt(UUID id) {as(owner,false,"finance.submit");try {service.submit(id,1);return "OK";}catch(ResponseStatusException e){if(e.getStatusCode().value()!=409)throw e;return "CONFLICT";}finally{TenantContext.clear();}}
    private FinanceModels.StoreConfig config(int version,UUID manager,UUID finance,UUID leader) {return new FinanceModels.StoreConfig("测试酒店有限责任公司","91520100123456789X",manager,finance,leader,"其他应付款",true,version);}
    private UUID draft(boolean mismatch) {
        as(owner,false,"finance.submit");UUID id=UUID.randomUUID();service.create(new FinanceModels.Create(id,assignment));UUID attachment=UUID.randomUUID();
        for(String kind:List.of("INVOICE","TRANSACTION","PAYMENT")) service.registerAttachment(id,kind.equals("INVOICE")?attachment:UUID.randomUUID(),kind,"test.pdf","application/pdf",20,FinanceService.hash(UUID.randomUUID().toString()),T+"/"+id+"/"+UUID.randomUUID()+".original","BYPASSED_DEV",Map.of("source","MANUAL_REQUIRED"));
        service.save(id,input(0,List.of(new FinanceModels.Invoice(UUID.randomUUID(),attachment,"",invoiceNumber,new BigDecimal(mismatch?"100":"90"),"91520100123456789X",true)),mismatch));return id;
    }
    private FinanceModels.Draft input(int version,List<FinanceModels.Invoice> invoices,boolean mismatch) {return new FinanceModels.Draft(version,"客房用品",today,category,new BigDecimal(mismatch?"100":"90"),new BigDecimal("90"),new BigDecimal("90"),mismatch?"平台补贴10元":"",invoices);}
    private FinanceModels.Review review(int version,String decision,boolean ack) {return new FinanceModels.Review(UUID.randomUUID(),version,decision,"已核对原件及用途",category,new BigDecimal("90"),ack);}
    private UUID account() {UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name,status) values(?,?,?,'财务测试账号','ACTIVE')",id,T,id.toString());return id;}
    private void as(UUID actor,boolean all,String... permissions) {TenantContext.set(principal(actor,T,Set.of(permissions),Set.of(hotel),all));}
    private TenantPrincipal principal(UUID actor,UUID tenant,Set<String> permissions,Set<UUID> scope,boolean all) {return new TenantPrincipal(tenant,actor,"FINANCE_TEST",Set.of("FINANCE_TEST"),permissions,scope,Set.of(assignment),assignment,all,UUID.randomUUID());}
    private static EmbeddedPostgres start(){try{var pg=EmbeddedPostgres.builder().start();try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");}return pg;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Path temp(){try{return Files.createTempDirectory("finance-integration-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
