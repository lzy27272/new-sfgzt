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
    @Autowired org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    @Autowired cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext database;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    UUID owner,other,manager,finance,director,leader,cashier,hotel,assignment,category;String invoiceNumber;
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");r.add("app.security.development-header-auth-enabled",()->true);r.add("app.database.rls-enabled",()->true);r.add("app.finance.attachments-root",FILES::toString);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @AfterEach void clear() {TenantContext.clear();}
    @BeforeEach void setup() {
        owner=account();other=account();manager=account();finance=account();director=account();leader=account();cashier=account();hotel=UUID.randomUUID();assignment=UUID.randomUUID();category=UUID.randomUUID();
        invoiceNumber="%020d".formatted(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'财务测试门店','HOTEL')",hotel,T,hotel.toString());
        jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",T,hotel,hotel);
        UUID employee=UUID.randomUUID();jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name) values(?,?,?,?,'报销测试员工')",employee,T,owner,employee.toString());
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) select ?,?,?,?,id,current_date from position_definition where tenant_id=? limit 1",assignment,T,employee,hotel,T);
        as(owner,true,"finance.configure");service.configure(hotel,config(-1,manager,finance,leader));
        service.category(hotel,category,new FinanceModels.Category("客房费用","EXPENSE","客房,补贴","管理费用",true,-1));
    }
    @Test void completeFourStageChainWarnsDifferenceAndSeparatesPayment() {
        UUID id=draft(true);as(owner,false,"finance.submit");var pending=service.submit(id,1);assertThat(pending.get("status")).isEqualTo("MANAGER_REVIEW");
        assertThat(service.submit(id,1).get("version")).isEqualTo(2);
        as(leader,false,"finance.review");assertThatThrownBy(()->service.review(id,review(2,"APPROVE",false))).isInstanceOf(ResponseStatusException.class);
        as(manager,false,"finance.review");var command=review(2,"APPROVE",false);assertThat(service.review(id,command).get("status")).isEqualTo("FINANCE_REVIEW");service.review(id,command);
        as(finance,false,"finance.review");assertThatThrownBy(()->service.review(id,review(3,"APPROVE",false))).hasMessageContaining("核实差额");
        assertThat(service.review(id,review(3,"APPROVE",true)).get("status")).isEqualTo("DIRECTOR_REVIEW");
        as(leader,false,"finance.review");assertThatThrownBy(()->service.review(id,review(4,"APPROVE",false))).isInstanceOf(ResponseStatusException.class);
        as(director,false,"finance.review");assertThat(service.review(id,review(4,"APPROVE",false)).get("status")).isEqualTo("LEADER_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from finance_expense_entry where claim_id=?",Integer.class,id)).isZero();
        as(leader,false,"finance.review");assertThat(service.review(id,review(5,"APPROVE",false)).get("status")).isEqualTo("APPROVED");
        as(finance,false,"finance.read","finance.pay");var totals=(Map<?,?>)service.ledger(YearMonth.from(today).toString(),hotel).get("totals");assertThat(totals.get("total")).isEqualTo("90.00");assertThat(totals.get("paid")).isEqualTo("0");
        var payment=new FinanceModels.Payment(UUID.randomUUID(),6,new BigDecimal("90"),"测试流水",today);
        assertThatThrownBy(()->service.payment(id,payment)).isInstanceOf(ResponseStatusException.class);
        as(cashier,false,"finance.pay");service.payment(id,payment);service.payment(id,payment);
        assertThat(jdbc.queryForObject("select count(*) from finance_payment where claim_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(service.voucher(id)).containsEntry("status","DRAFT").containsKey("evidence");
        assertThat(service.voucher(id).get("amount")).isEqualTo("90.00");
        assertThatThrownBy(()->jdbc.update("update finance_expense_entry set amount=1 where claim_id=?",id)).isInstanceOf(RuntimeException.class);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.save(id,input(7,List.of(),true))).hasMessageContaining("不能覆盖");
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
    @Test void returningReleasesReservationAndRestartRequiresManagerReview() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"RETURN",false));
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,id)).isZero();
        as(owner,false,"finance.submit");assertThat(service.submit(id,3).get("status")).isEqualTo("MANAGER_REVIEW");
        service.withdraw(id,4);assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,id)).isZero();
    }
    @Test void forbidsSelfReviewMissingScopeAndOtherEmployeesAndStaleEdits() {
        UUID id=draft(false);as(other,false,"finance.submit");assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        as(finance,false,"finance.read");TenantContext.set(principal(finance,T,Set.of("finance.read"),Set.of(),false));assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.save(id,input(0,List.of(),false))).hasMessageContaining("其他页面更新");
        as(owner,true,"finance.configure");service.configure(hotel,config(0,manager,finance,owner));
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("本人");
        as(owner,true,"finance.configure");assertThatThrownBy(()->service.configure(hotel,config(1,manager,manager,leader))).hasMessageContaining("不同账号");
        TenantContext.set(principal(owner,UUID.randomUUID(),Set.of("finance.read"),Set.of(hotel),true));assertThatThrownBy(()->service.detail(id)).hasMessageContaining("不存在");
    }
    @Test void snapshotsCompanyAndDoesNotDoubleCountInventory() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"APPROVE",false));
        as(owner,true,"finance.configure");service.category(hotel,category,new FinanceModels.Category("采购入库","INVENTORY","采购","库存商品",true,0));
        as(finance,false,"finance.review");service.review(id,review(3,"APPROVE",true));
        as(director,false,"finance.review");service.review(id,review(4,"APPROVE",false));
        as(leader,false,"finance.review");service.review(id,review(5,"APPROVE",false));
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
        as(finance,false,"finance.pay");assertThatThrownBy(()->service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),1,new BigDecimal("90"),"test",today))).hasMessageContaining("全部审批");
        as(owner,false,"finance.submit");UUID paymentId=jdbc.queryForObject("select id from finance_attachment where claim_id=? and kind='PAYMENT'",UUID.class,id);
        service.removeAttachment(id,paymentId,1);assertThatThrownBy(()->service.submit(id,2)).hasMessageContaining("补齐发票");
        // A saved draft must NOT disable a live config. Explicitly simulate a paused legacy config here.
        jdbc.update("update finance_store_config set enabled=false where tenant_id=? and hotel_id=?",T,hotel);
        as(owner,false,"finance.submit");UUID disabledDraft=UUID.randomUUID();
        assertThat(service.create(new FinanceModels.Create(disabledDraft,assignment))).containsEntry("status","DRAFT");
        assertThatThrownBy(()->service.submit(disabledDraft,0)).hasMessageContaining("报销尚未启用");
    }
    @Test void unconfiguredDraftCanBeSavedAndLaterSubmittedWithoutChangingItsIdentity() {
        jdbc.update("delete from finance_store_config where tenant_id=? and hotel_id=?",T,hotel);
        as(owner,false,"finance.submit");UUID id=UUID.randomUUID();
        assertThat(service.create(new FinanceModels.Create(id,assignment))).containsEntry("company_id",null);
        service.save(id,input(0,List.of(),false));
        assertThat(service.detail(id).get("configurationIssues")).asList().isNotEmpty();
        assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("尚未配置");
        as(owner,true,"finance.configure");service.configure(hotel,config(-1,manager,finance,leader));
        as(owner,false,"finance.submit");
        assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("核对所有发票");
        assertThat(service.detail(id)).containsEntry("id",id).containsEntry("status","DRAFT");
    }
    @Test void multiLinesValidateMoneyAndFreezeLineFactsWithoutDoubleCounting() {
        UUID id=draft(false);UUID second=UUID.randomUUID();
        as(owner,true,"finance.configure");service.category(hotel,second,new FinanceModels.Category("办公室用品","EXPENSE","办公","管理费用",true,-1));
        as(owner,false,"finance.submit");
        var detail=service.detail(id);@SuppressWarnings("unchecked")var invoices=(List<Map<String,Object>>)detail.get("invoices");
        var invoice=invoices.getFirst();
        UUID invoiceId=UUID.fromString(invoice.get("id").toString());
        var commands=List.of(new FinanceModels.Invoice(invoiceId,UUID.fromString(invoice.get("attachment_id").toString()),"",invoiceNumber,new BigDecimal("90"),"91520100123456789X",true));
        var items=List.of(new FinanceModels.Line(UUID.randomUUID(),category,"房务用品",new BigDecimal("2"),new BigDecimal("30"),new BigDecimal("60"),null,today.withDayOfMonth(1),List.of(invoiceId)),
            new FinanceModels.Line(UUID.randomUUID(),second,"办公用品",BigDecimal.ONE,new BigDecimal("30"),new BigDecimal("30"),null,today.withDayOfMonth(1),List.of(invoiceId)));
        as(owner,false,"finance.submit");
        assertThatThrownBy(()->service.save(id,new FinanceModels.Draft(1,"多行费用",today,category,new BigDecimal("90"),new BigDecimal("90"),new BigDecimal("91"),"",commands,items))).hasMessageContaining("明细合计");
        assertThat(service.save(id,new FinanceModels.Draft(1,"多行费用",today,category,new BigDecimal("90"),new BigDecimal("90"),new BigDecimal("90"),"",commands,items)).get("items")).asList().hasSize(2);
        service.submit(id,2);as(manager,false,"finance.review");service.review(id,review(3,"APPROVE",false));as(finance,false,"finance.review");
        assertThatThrownBy(()->service.review(id,new FinanceModels.Review(UUID.randomUUID(),4,"APPROVE","不应覆盖多行",second,new BigDecimal("90"),true))).hasMessageContaining("多行报销不能");
        assertThatThrownBy(()->service.review(id,new FinanceModels.Review(UUID.randomUUID(),4,"APPROVE","不应改总额",category,new BigDecimal("89"),true))).hasMessageContaining("多行报销不能");
        service.review(id,review(4,"APPROVE",true));as(director,false,"finance.review");service.review(id,review(5,"APPROVE",false));as(leader,false,"finance.review");service.review(id,review(6,"APPROVE",false));
        assertThat(jdbc.queryForObject("select count(*) from finance_expense_entry where claim_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select sum(amount) from finance_expense_line_entry where claim_id=?",BigDecimal.class,id)).isEqualByComparingTo("90");
        as(finance,false,"finance.read");assertThat(((Map<?,?>)service.ledger(YearMonth.from(today).toString(),hotel).get("totals")).get("total")).isEqualTo("90.00");
    }
    @Test void workbenchTodosKeepCrossDayAndOnlyCurrentReviewerCounts() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        jdbc.update("update finance_claim set submitted_at=now()-interval '3 days' where id=?",id);
        as(leader,false,"finance.review");assertThat(service.todos(0).get("total")).isEqualTo(0L);
        as(manager,false,"finance.review");assertThat(service.todos(0).get("total")).isEqualTo(1L);
        assertThat(service.todos(1).get("total")).isEqualTo(1L);assertThat((List<?>)service.todos(1).get("items")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from notification where source_type='FINANCE' and source_id=? and recipient_account_id=?",Integer.class,id,manager)).isEqualTo(1);
        service.review(id,review(2,"RETURN",false));assertThat(service.todos(0).get("total")).isEqualTo(0L);
        as(owner,false,"finance.submit");assertThat(service.todos(0).get("total")).isEqualTo(1L);
        as(other,false,"finance.submit");assertThat(service.todos(0).get("total")).isEqualTo(0L);
    }
    @Test void independentInvoiceIntakePreservesOriginalAndAssociatesWithoutCreatingExpenseOrLosingOwnerScope() throws Exception {
        as(owner,false,"finance.submit");UUID intake=UUID.randomUUID();service.createIntake(new FinanceModels.Create(intake,assignment));
        assertThat(jdbc.queryForObject("select count(*) from finance_claim where id=?",Integer.class,intake)).isZero();
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,24,BufferedImage.TYPE_INT_RGB),"png",bytes);byte[] original=bytes.toByteArray();
        evidence.uploadIntake(intake,new MockMultipartFile("file","original.png","image/png",original));
        service.saveIntake(intake,new FinanceModels.IntakeDraft(1,"",invoiceNumber,new BigDecimal("90"),"91520100123456789X",true));
        assertThat(evidence.downloadIntake(intake).resource().getContentAsByteArray()).isEqualTo(original);
        UUID claim=UUID.randomUUID();service.create(new FinanceModels.Create(claim,assignment));
        var linked=service.associateIntake(intake,new FinanceModels.Associate(claim,0));
        assertThat(service.associateIntake(intake,new FinanceModels.Associate(claim,0)).get("version")).isEqualTo(linked.get("version"));
        @SuppressWarnings("unchecked")var files=(List<Map<String,Object>>)linked.get("attachments");UUID file=UUID.fromString(files.getFirst().get("id").toString());
        assertThat(evidence.download(claim,file).resource().getContentAsByteArray()).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from finance_expense_entry where claim_id=?",Integer.class,claim)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,claim)).isZero();
        as(other,false,"finance.submit");assertThatThrownBy(()->evidence.downloadIntake(intake)).isInstanceOf(ResponseStatusException.class);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.saveIntake(intake,new FinanceModels.IntakeDraft(3,"","",null,"",false))).hasMessageContaining("未关联");
    }
    @Test void partialPaymentsRemainDueAndCashierCanArchiveOriginalAfterFullPayment() throws Exception {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);as(manager,false,"finance.review");service.review(id,review(2,"APPROVE",false));as(finance,false,"finance.review");service.review(id,review(3,"APPROVE",true));as(director,false,"finance.review");service.review(id,review(4,"APPROVE",false));as(leader,false,"finance.review");service.review(id,review(5,"APPROVE",false));
        as(cashier,false,"finance.pay");var first=new FinanceModels.Payment(UUID.randomUUID(),6,new BigDecimal("40"),"首次付款",today);service.payment(id,first);service.payment(id,first);
        assertThat(service.detail(id)).containsEntry("unpaidAmount","50.00");
        assertThat(((Map<?,?>)service.todos(0).get("counts")).get("receipt")).isEqualTo(1L);
        assertThat(service.todos(0,"payment").get("total")).isEqualTo(1L);
        assertThat(service.todos(0,"receipt").get("total")).isEqualTo(1L);
        assertThat(service.todos(0,"review").get("total")).isEqualTo(0L);
        assertThatThrownBy(()->service.todos(0,"unknown")).hasMessageContaining("待办类别");
        assertThatThrownBy(()->service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),7,new BigDecimal("51"),"超付",today))).hasMessageContaining("未付余额");
        service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),7,new BigDecimal("50"),"尾款",today));
        assertThat(service.detail(id)).containsEntry("canPay",false).containsEntry("canArchiveReceipts",true);
        assertThat(service.todos(0,"payment").get("total")).isEqualTo(0L);
        assertThat(service.todos(0,"receipt").get("total")).isEqualTo(1L);
        UUID attachment=UUID.randomUUID();var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,24,BufferedImage.TYPE_INT_RGB),"png",bytes);
        evidence.upload(id,attachment,"PAYMENT",new MockMultipartFile("file","bank.png","image/png",bytes.toByteArray()));
        UUID payment=jdbc.queryForObject("select id from finance_payment where request_id=?",UUID.class,first.requestId());
        service.linkPaymentReceipt(id,payment,new FinanceModels.Receipt(attachment));
        assertThat(jdbc.queryForObject("select count(*) from finance_payment_receipt where payment_id=?",Integer.class,payment)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("delete from finance_payment_receipt where payment_id=?",payment)).isInstanceOf(RuntimeException.class);
        try(var connection=PG.getPostgresDatabase().getConnection();var statement=connection.createStatement()) {
            statement.execute("set role hotel_ai_os_app");
            statement.execute("select set_config('app.tenant_id','"+T+"',false)");
            assertThatThrownBy(()->statement.executeUpdate("update finance_payment_receipt set created_at=now() where payment_id='"+payment+"'"))
                .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->statement.executeUpdate("delete from finance_payment_receipt where payment_id='"+payment+"'"))
                .isInstanceOf(java.sql.SQLException.class);
        }
    }
    @Test void cloudDraftRetainsIncompleteLinesAndInvoiceCandidatesWithoutInventingFacts() {
        UUID id=draft(false);as(owner,false,"finance.submit");
        UUID attachment=jdbc.queryForObject("select attachment_id from finance_invoice where claim_id=?",UUID.class,id);
        var candidates=List.of(new FinanceModels.Invoice(UUID.randomUUID(),attachment,"","0012",null,"",false));
        var items=List.of(new FinanceModels.Line(UUID.randomUUID(),null,"尚未写完",null,null,null,null,null,List.of()));
        var saved=service.save(id,new FinanceModels.Draft(1,"保存未完成内容",today,null,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"",candidates,items));
        assertThat((List<?>)saved.get("items")).hasSize(1);assertThat((List<?>)saved.get("invoices")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from finance_claim_line where claim_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice where claim_id=?",Integer.class,id)).isZero();
        assertThatThrownBy(()->service.submit(id,2)).hasMessageContaining("补齐费用明细");
    }
    @Test void allocatedPaymentsConserveCentsAcrossThreeExpenseLines() {
        UUID id=draft(false);as(owner,false,"finance.submit");
        var detail=service.detail(id);
        @SuppressWarnings("unchecked") var invoices=(List<Map<String,Object>>)detail.get("invoices");
        var invoice=invoices.getFirst();UUID invoiceId=UUID.fromString(invoice.get("id").toString());
        var invoiceCommands=List.of(new FinanceModels.Invoice(invoiceId,UUID.fromString(invoice.get("attachment_id").toString()),"",invoiceNumber,new BigDecimal("90"),"91520100123456789X",true));
        var lines=new ArrayList<FinanceModels.Line>();
        for(int i=0;i<3;i++) lines.add(new FinanceModels.Line(UUID.randomUUID(),category,"分摊明细"+i,BigDecimal.ONE,new BigDecimal("30"),new BigDecimal("30"),null,null,List.of(invoiceId)));
        service.save(id,new FinanceModels.Draft(1,"累计分摊守恒",today,category,new BigDecimal("90"),new BigDecimal("90"),new BigDecimal("90"),"",invoiceCommands,lines));
        service.submit(id,2);as(manager,false,"finance.review");service.review(id,review(3,"APPROVE",false));
        as(finance,false,"finance.review");service.review(id,review(4,"APPROVE",true));
        as(director,false,"finance.review");service.review(id,review(5,"APPROVE",false));
        as(leader,false,"finance.review");service.review(id,review(6,"APPROVE",false));
        as(cashier,false,"finance.pay");service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),7,BigDecimal.ONE,"实际只付一元",today));
        as(finance,false,"finance.read");var ledger=service.ledger(YearMonth.from(today).toString(),hotel);
        var totals=(Map<?,?>)ledger.get("totals");assertThat(new BigDecimal(totals.get("paid").toString())).isEqualByComparingTo("1.00");
        @SuppressWarnings("unchecked") var entries=(List<Map<String,Object>>)ledger.get("entries");
        assertThat(entries).hasSize(3);
        assertThat(entries.stream().map(row->new BigDecimal(row.get("allocated_paid").toString())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1.00");
        assertThat(entries.stream().map(row->row.get("entry_id")).distinct().count()).isEqualTo(3);
    }
    @Test void mixedTreatmentLedgerExportUsesSameLineFactsAndPreservesAllocationCents() {
        UUID id=draft(false),asset=UUID.randomUUID();as(owner,true,"finance.configure");
        service.category(hotel,asset,new FinanceModels.Category("固定资产","ASSET","设备","固定资产",true,-1));
        as(owner,false,"finance.submit");var detail=service.detail(id);
        @SuppressWarnings("unchecked")var invoices=(List<Map<String,Object>>)detail.get("invoices");var invoice=invoices.getFirst();UUID invoiceId=UUID.fromString(invoice.get("id").toString());
        var invoiceCommands=List.of(new FinanceModels.Invoice(invoiceId,UUID.fromString(invoice.get("attachment_id").toString()),"",invoiceNumber,new BigDecimal("90"),"91520100123456789X",true));
        var lines=List.of(new FinanceModels.Line(UUID.randomUUID(),category,"当期费用",BigDecimal.ONE,new BigDecimal("60"),new BigDecimal("60"),null,null,List.of(invoiceId)),new FinanceModels.Line(UUID.randomUUID(),asset,"购入设备",BigDecimal.ONE,new BigDecimal("30"),new BigDecimal("30"),null,null,List.of(invoiceId)));
        service.save(id,new FinanceModels.Draft(1,"=SUM(1)",today,category,new BigDecimal("90"),new BigDecimal("90"),new BigDecimal("90"),"",invoiceCommands,lines));service.submit(id,2);
        as(manager,false,"finance.review");service.review(id,review(3,"APPROVE",false));as(finance,false,"finance.review");service.review(id,review(4,"APPROVE",true));as(director,false,"finance.review");service.review(id,review(5,"APPROVE",false));as(leader,false,"finance.review");service.review(id,review(6,"APPROVE",false));
        as(cashier,false,"finance.pay");service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),7,BigDecimal.ONE,"一元真实分次付款",today));
        as(finance,false,"finance.read");String month=YearMonth.from(today).toString();var ledger=service.ledger(month,hotel);
        @SuppressWarnings("unchecked")var entries=(List<Map<String,Object>>)ledger.get("entries");
        assertThat(((Map<?,?>)ledger.get("totals")).get("total")).isEqualTo("60.00");
        var csv=service.export(month,hotel);assertThat(csv).contains("\"明细事实ID\"").contains("\"已付分摊\"").contains("\"'=SUM(1)\"");
        var exported=csv.lines().skip(1).toList();assertThat(exported).hasSize(2);
        for(var entry:entries){String line=exported.stream().filter(row->row.startsWith("\""+id+"\",\""+entry.get("entry_id")+"\",")).findFirst().orElseThrow();
            assertThat(line).contains(",\""+entry.get("treatment")+"\",\""+entry.get("amount")+"\",\""+entry.get("allocated_paid")+"\",\""+entry.get("allocated_unpaid")+"\",");}
        assertThat(entries.stream().map(row->new BigDecimal(row.get("allocated_paid").toString())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1.00");
        assertThat(exported.stream().filter(line->line.contains("\"ASSET\",\"30.00\"")).count()).isEqualTo(1);
        assertThat(exported.stream().filter(line->line.contains("\"EXPENSE\",\"60.00\"")).count()).isEqualTo(1);
    }
    @Test void financeWeComQueueExecutesRealSqlAndSendsOnlyStillCurrentNodeWithMockTransport() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        String corp="finance-test-"+UUID.randomUUID();String user="test-"+UUID.randomUUID();
        jdbc.update("insert into wecom_user_binding(tenant_id,corp_id,wecom_user_id,user_id_fingerprint,account_id) values(?,?,?,?,?)",T,corp,user,FinanceService.hash(corp+":"+user),manager);
        var props=org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComProperties.class);
        org.mockito.Mockito.when(props.tenantId()).thenReturn(T);org.mockito.Mockito.when(props.corpId()).thenReturn(corp);
        org.mockito.Mockito.when(props.oauthCallbackUrl()).thenReturn(java.net.URI.create("https://localhost/api/v1/integrations/wecom/oauth/callback"));
        var api=org.mockito.Mockito.mock(cn.sifangguan.hotelaios.integrations.wecom.WeComApiClient.class);
        org.mockito.Mockito.when(api.sendApplicationTaskLink(org.mockito.ArgumentMatchers.eq(user),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.net.URI.class))).thenReturn("isolated-test-message");
        var worker=new cn.sifangguan.hotelaios.integrations.wecom.WeComDeliveryWorker(namedJdbc,database,new org.springframework.transaction.support.TransactionTemplate(transactionManager),api,props,50);
        worker.run();
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery d join notification n on n.tenant_id=d.tenant_id and n.id=d.notification_id where n.source_id=? and d.status='SENT'",Integer.class,id)).isEqualTo(1);
        UUID second=draft(false);as(owner,false,"finance.submit");
        // Withdraw releases the first reservation before submitting the next independent message.
        service.withdraw(id,2);service.submit(second,1);as(manager,false,"finance.review");service.review(second,review(2,"APPROVE",false));
        worker.run();
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery d join notification n on n.tenant_id=d.tenant_id and n.id=d.notification_id where n.source_id=?",Integer.class,second)).isZero();
        org.mockito.Mockito.verify(api,org.mockito.Mockito.times(1)).sendApplicationTaskLink(org.mockito.ArgumentMatchers.eq(user),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.net.URI.class));
    }
    @Test void initialSelfReviewSkipsOnlyOnceAndKeepsEvidenceAudit() {
        UUID id=draft(false);as(owner,true,"finance.configure");service.configure(hotel,config(0,owner,finance,leader));
        as(owner,false,"finance.submit","finance.review");var submitted=service.submit(id,1);
        assertThat(submitted).containsEntry("status","FINANCE_REVIEW").containsEntry("canReview",false);
        service.submit(id,1);
        assertThat(jdbc.queryForObject("select count(*) from finance_review where claim_id=? and decision='SKIP_SELF'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select review_snapshot::text from finance_review where claim_id=?",String.class,id)).contains(invoiceNumber,"approval_route");
        assertThatThrownBy(()->service.review(id,review(2,"APPROVE",true))).isInstanceOf(ResponseStatusException.class);
        as(finance,false,"finance.review");assertThat((List<?>)service.list("review",0).get("items")).hasSize(1);
        as(manager,false,"finance.review");assertThat((List<?>)service.list("review",0).get("items")).isEmpty();
        as(leader,false,"finance.review");assertThat((List<?>)service.list("review",0).get("items")).isEmpty();
        assertThatThrownBy(()->jdbc.update("update finance_review set decision='APPROVE' where claim_id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void partialDraftDoesNotDisableLiveConfigOrChangeSubmittedChain() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(owner,true,"finance.configure");service.configure(hotel,partialConfig(0,false));
        assertThat(jdbc.queryForObject("select enabled from finance_store_config where hotel_id=?",Boolean.class,hotel)).isTrue();
        assertThat(jdbc.queryForObject("select manager_account_id from finance_store_config where hotel_id=?",UUID.class,hotel)).isEqualTo(manager);
        assertThat(jdbc.queryForObject("select draft_configuration->>'leaderName' from finance_store_config where hotel_id=?",String.class,hotel)).isEqualTo("刘照阳");
        assertThatThrownBy(()->service.configure(hotel,partialConfig(1,true))).hasMessageContaining("暂不可启用");
        UUID newManager=account();service.configure(hotel,config(1,newManager,finance,leader));
        as(newManager,false,"finance.review");assertThatThrownBy(()->service.detail(id)).isInstanceOf(ResponseStatusException.class);
        as(manager,false,"finance.review");assertThat(service.review(id,review(2,"APPROVE",false)).get("status")).isEqualTo("FINANCE_REVIEW");
        assertThat(jdbc.queryForObject("select manager_account_id from finance_claim where id=?",UUID.class,id)).isEqualTo(manager);
        assertThat(jdbc.queryForObject("select draft_configuration from finance_store_config where hotel_id=?",Object.class,hotel)).isNull();
    }
    @Test void bothHeadquartersCanShareOneCompanyDraftWithoutMatchingCode015() {
        UUID group=organization("四方馆酒店集团","SFG-FIN-"+UUID.randomUUID(),"GROUP");
        UUID headquarters=organization("集团总部",UUID.randomUUID().toString(),"HOTEL");
        UUID unrelated=organization("贵州宴",UUID.randomUUID().toString(),"HOTEL");
        as(owner,true,"finance.configure");
        service.configureBatch(new FinanceModels.ConfigureBatch(List.of(new FinanceModels.ConfigTarget(group,-1),new FinanceModels.ConfigTarget(headquarters,-1)),partialConfig(-1,false)));
        assertThat(jdbc.queryForObject("select count(distinct company_id) from finance_store_config where hotel_id in (?,?)",Integer.class,group,headquarters)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from finance_store_config where hotel_id in (?,?) and not enabled and leader_account_id is null",Integer.class,group,headquarters)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from finance_store_config where hotel_id=?",Integer.class,unrelated)).isZero();
        assertThatThrownBy(()->service.configure(group,partialConfig(0,true))).hasMessageContaining("暂不可启用");
    }
    @Test void multiOrganizationSaveRollsBackOnStaleVersionOrMissingScope() {
        UUID second=organization("第二测试公司",UUID.randomUUID().toString(),"GROUP");as(owner,true,"finance.configure");
        assertThatThrownBy(()->service.configureBatch(new FinanceModels.ConfigureBatch(List.of(new FinanceModels.ConfigTarget(hotel,-1),new FinanceModels.ConfigTarget(second,-1)),partialConfig(-1,false)))).hasMessageContaining("全部未生效");
        assertThat(jdbc.queryForObject("select count(*) from finance_store_config where hotel_id=?",Integer.class,second)).isZero();
        as(owner,false,"finance.configure");
        assertThatThrownBy(()->service.configureBatch(new FinanceModels.ConfigureBatch(List.of(new FinanceModels.ConfigTarget(hotel,0),new FinanceModels.ConfigTarget(second,-1)),partialConfig(-1,false)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select version from finance_store_config where hotel_id=?",Integer.class,hotel)).isZero();
    }
    @Test void cannotSkipUnconfiguredInactiveOrLaterSelfReviewer() {
        UUID id=draft(false);as(owner,true,"finance.configure");
        assertThatThrownBy(()->service.configure(hotel,new FinanceModels.StoreConfig("测试酒店有限责任公司","91520100123456789X",owner,null,director,leader,cashier,"刘照阳","",true,true,0))).hasMessageContaining("暂不可启用");
        service.configure(hotel,config(0,owner,finance,leader));
        jdbc.update("update user_account set status='DISABLED' where id=?",finance);
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("已停用");
        assertThat(jdbc.queryForObject("select count(*) from finance_review where claim_id=?",Integer.class,id)).isZero();
        jdbc.update("update user_account set status='ACTIVE' where id=?",finance);
        as(owner,true,"finance.configure");service.configure(hotel,config(1,manager,owner,leader));
        as(owner,false,"finance.submit");assertThatThrownBy(()->service.submit(id,1)).hasMessageContaining("独立审核人");
    }
    @Test void applicantCanWithdrawBeforeDirectorReviewWithoutLeavingInvoiceReserved() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        as(manager,false,"finance.review");service.review(id,review(2,"APPROVE",false));
        as(finance,false,"finance.review");service.review(id,review(3,"APPROVE",true));
        as(owner,false,"finance.submit");assertThat(service.withdraw(id,4).get("status")).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select count(*) from finance_invoice_reservation where claim_id=?",Integer.class,id)).isZero();
        as(director,false,"finance.review");assertThatThrownBy(()->service.review(id,review(5,"APPROVE",false))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void legacyInFlightClaimFinishesItsOriginalThreeStageChain() {
        UUID id=draft(false);as(owner,false,"finance.submit");service.submit(id,1);
        // This shape is the pre-V74 stored claim, unchanged by the additive migration.
        jdbc.update("update finance_claim set workflow_version=1,director_account_id=null,cashier_account_id=null,approval_route='[]'::jsonb where id=?",id);
        as(manager,false,"finance.review");service.review(id,review(2,"APPROVE",false));
        as(finance,false,"finance.review");assertThat(service.review(id,review(3,"APPROVE",true)).get("status")).isEqualTo("LEADER_REVIEW");
        as(leader,false,"finance.review");assertThat(service.review(id,review(4,"APPROVE",false)).get("status")).isEqualTo("APPROVED");
        as(finance,false,"finance.pay");service.payment(id,new FinanceModels.Payment(UUID.randomUUID(),5,new BigDecimal("90"),"历史流程付款",today));
    }
    private UUID organization(String name,String code,String type) {UUID id=UUID.randomUUID();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,?,?)",id,T,code,name,type);return id;}
    private FinanceModels.StoreConfig partialConfig(int version,boolean enabled) {return new FinanceModels.StoreConfig("测试酒店有限责任公司","91520100123456789X",null,null,null,null,null,"刘照阳","其他应付款",true,enabled,version);}
    private String submitAttempt(UUID id) {as(owner,false,"finance.submit");try {service.submit(id,1);return "OK";}catch(ResponseStatusException e){if(e.getStatusCode().value()!=409)throw e;return "CONFLICT";}finally{TenantContext.clear();}}
    private FinanceModels.StoreConfig config(int version,UUID manager,UUID finance,UUID leader) {return new FinanceModels.StoreConfig("测试酒店有限责任公司","91520100123456789X",manager,finance,director,leader,cashier,"刘照阳","其他应付款",true,true,version);}
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
