package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class FinanceLocalOcrIntegrationTest {
    static EmbeddedPostgres pg;static JdbcTemplate admin,app;static NamedParameterJdbcTemplate named;static TenantDatabaseContext database;static TransactionTemplate tx;
    static FinanceService finance;static FinanceEvidenceService evidence;static HttpServer worker;static Path files;static String mode;static final AtomicInteger posts=new AtomicInteger();
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    UUID tenant,owner,other,manager,reviewer,director,leader,cashier,hotel,assignment,category;
    static final String INVOICE="00000000000000123456",TAX="91520100123456789X";
    @BeforeAll static void start()throws Exception{
        pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();admin=new JdbcTemplate(source);admin.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("116").load().migrate();
        var restricted=new DelegatingDataSource(source){@Override public Connection getConnection()throws SQLException{var connection=super.getConnection();try(var statement=connection.createStatement()){statement.execute("set role hotel_ai_os_app");}return connection;}};
        app=new JdbcTemplate(restricted);named=new NamedParameterJdbcTemplate(restricted);database=new TenantDatabaseContext(app,true);var access=new AccessPolicy();var audit=new AuditWriter(named,event->{});
        var accounting=new FinanceAccountingService(named,database,access,audit,JSON);var target=new FinanceService(named,database,access,JSON,audit,accounting);var transactions=new DataSourceTransactionManager(restricted);tx=new TransactionTemplate(transactions);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));finance=(FinanceService)proxy.getProxy();
        worker=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);worker.createContext("/recognize",exchange->{posts.incrementAndGet();var payload=JSON.readTree(exchange.getRequestBody().readAllBytes());var body=Map.of("provider","RapidOCR","sourceSha256",payload.path("sourceSha256").asText(),"blocks",List.of(Map.of("text","发票号码："+INVOICE,"confidence",0.99),Map.of("text","价税合计(小写)90.00","confidence",0.99),Map.of("text","购买方税号："+TAX,"confidence",0.99),Map.of("text","交易金额90.00","confidence",0.99),Map.of("text","实付金额90.00","confidence",0.99)),"qrCodes",List.of(),"warnings",List.of(),"imageHash",Map.of("algorithm","phash-dhash-v1","phash","1234567890abcdef","dhash","1234567890abcdef"));byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(mode.equals("DOWN")?503:200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();});worker.start();
        files=Files.createTempDirectory("finance-local-ocr-originals-");String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));var scanner=new AttachmentMalwareScanner(java,"-cp|"+classpath+"|"+FinanceLocalOcrScannerFixture.class.getName()+"|{file}",10,false,false);
        evidence=new FinanceEvidenceService(files.toString(),finance,access,scanner,new FinanceLocalOcrClient("http://127.0.0.1:"+worker.getAddress().getPort()+"/recognize","isolated-token",3000,JSON));
    }
    @AfterAll static void stop()throws Exception{if(worker!=null)worker.stop(0);if(pg!=null)pg.close();}
    @BeforeEach void seed(){mode="OK";posts.set(0);seedTenant();}
    @AfterEach void clear(){TenantContext.clear();}
    private void seedTenant(){
        tenant=UUID.randomUUID();admin.update("insert into tenant(id,code,name) values(?,?,?)",tenant,"OCR-"+tenant,"隔离OCR测试租户");owner=account();other=account();manager=account();reviewer=account();director=account();leader=account();cashier=account();hotel=UUID.randomUUID();assignment=UUID.randomUUID();category=UUID.randomUUID();
        admin.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'OCR真实门店','HOTEL')",hotel,tenant,hotel.toString());admin.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",tenant,hotel,hotel);
        UUID employee=UUID.randomUUID(),position=UUID.randomUUID();admin.update("insert into employee(id,tenant_id,account_id,employee_no,name) values(?,?,?,?, '真实OCR经办人')",employee,tenant,owner,employee.toString());admin.update("insert into position_definition(id,tenant_id,code,name,job_family) values(?,?,?,'OCR测试岗位','TEST')",position,tenant,position.toString());admin.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) values(?,?,?,?,?,'2020-01-01')",assignment,tenant,employee,hotel,position);
        as(owner,"finance.configure");call(()->finance.configure(hotel,new FinanceModels.StoreConfig("真实测试酒店有限公司",TAX,manager,reviewer,director,leader,cashier,"独立CEO","其他应付款",true,true,-1)));call(()->finance.category(hotel,category,new FinanceModels.Category("测试客房费用","EXPENSE","","管理费用",true,-1)));as(owner,"finance.submit");
    }
    @Test void unavailableUploadRetainsExactOriginalAndRetryAppendsWithoutChangingOriginalMetadata()throws Exception{
        UUID claim=claim(),attachment=UUID.randomUUID();byte[] original=image(10);mode="DOWN";var uploaded=upload(claim,attachment,"INVOICE",original);
        assertThat(extraction(uploaded)).containsEntry("status","UNAVAILABLE");assertThat(posts.get()).isOne();
        String key=admin.queryForObject("select object_key from finance_attachment where id=?",String.class,attachment),initial=admin.queryForObject("select extraction::text from finance_attachment where id=?",String.class,attachment);
        assertThat(Files.readAllBytes(files.resolve(key))).isEqualTo(original);int count=originalCount();mode="OK";
        var recognized=evidence.recognizeAttachment(claim,attachment);assertThat(extraction(recognized)).containsEntry("status","CANDIDATES").containsEntry("invoiceNumber",INVOICE).containsEntry("confirmationRequired",true);
        assertThat(originalCount()).isEqualTo(count);assertThat(admin.queryForObject("select extraction::text from finance_attachment where id=?",String.class,attachment)).isEqualTo(initial);
        assertThat(admin.queryForObject("select count(*) from finance_local_ocr_result where attachment_id=?",Integer.class,attachment)).isEqualTo(2);assertThat(admin.queryForObject("select version from finance_claim where id=?",Integer.class,claim)).isZero();
        assertThatThrownBy(()->admin.update("update finance_attachment set extraction='{}' where id=?",attachment)).hasMessageContaining("immutable");
    }
    @Test void differentImagesWithSameConfirmedInvoiceNumberStillHitExistingDatabaseHardDuplicate()throws Exception{
        UUID first=completeClaim(20),second=completeClaim(30);assertThat(call(()->finance.submit(first,1))).containsEntry("status","MANAGER_REVIEW");
        assertThatThrownBy(()->call(()->finance.submit(second,1))).hasMessageContaining("已被其他报销");
        assertThat(admin.queryForObject("select count(distinct sha256) from finance_attachment where claim_id in (?,?) and kind='INVOICE'",Integer.class,first,second)).isEqualTo(2);
        assertThat(admin.queryForObject("select count(*) from finance_invoice_reservation where tenant_id=? and invoice_key=?",Integer.class,tenant,"DIGITAL:"+INVOICE)).isOne();
    }
    @Test void intakeRetryPreservesManualValuesOriginalAndBusinessVersionWhileAppendingLatestOverlay()throws Exception{
        UUID intake=UUID.randomUUID();call(()->finance.createIntake(new FinanceModels.Create(intake,assignment)));byte[] bytes=image(40);mode="DOWN";evidence.uploadIntake(intake,new MockMultipartFile("file","真实发票.jpg","image/jpeg",bytes));
        call(()->finance.saveIntake(intake,new FinanceModels.IntakeDraft(1,"","00000000000000987654",new BigDecimal("123.45"),TAX,true)));
        String original=admin.queryForObject("select original::text from finance_invoice_intake where id=?",String.class,intake);mode="OK";var result=evidence.recognizeIntake(intake);
        assertThat(result).containsEntry("invoiceNumber","00000000000000987654").containsEntry("amount","123.45").containsEntry("confirmed",true).containsEntry("version",2);assertThat(extraction(result)).containsEntry("invoiceNumber",INVOICE);
        assertThat(admin.queryForObject("select original::text from finance_invoice_intake where id=?",String.class,intake)).isEqualTo(original);assertThat(admin.queryForObject("select count(*) from finance_local_ocr_result where intake_id=?",Integer.class,intake)).isEqualTo(2);
        assertThat(admin.queryForObject("select version from finance_invoice_intake where id=?",Integer.class,intake)).isEqualTo(2);
    }
    @Test void actualPngUsesOnlyPaymentFieldsAndScannerFailureCannotBeSwallowedAsOcrFailure()throws Exception{
        UUID claim=claim(),attachment=UUID.randomUUID();var image=new BufferedImage(30,20,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        var uploaded=evidence.upload(claim,attachment,"PAYMENT",new MockMultipartFile("file","真实付款.png","image/png",bytes.toByteArray()));assertThat(uploaded).containsEntry("media_type","image/png");assertThat(extraction(uploaded)).containsEntry("paymentAmount","90.00").doesNotContainKeys("amount","transactionAmount","invoiceNumber");
        int before=posts.get(),count=originalCount();var unavailableScan=new FinanceEvidenceService(files.toString(),finance,new AccessPolicy(),new AttachmentMalwareScanner("","",1,false,false),new FinanceLocalOcrClient("http://127.0.0.1:"+worker.getAddress().getPort()+"/recognize","isolated-token",3000,JSON));
        assertThatThrownBy(()->unavailableScan.upload(claim,UUID.randomUUID(),"INVOICE",new MockMultipartFile("file","发票.jpg","image/jpeg",image(45)))).hasMessageContaining("安全扫描");assertThat(posts.get()).isEqualTo(before);assertThat(originalCount()).isEqualTo(count);assertThat(admin.queryForObject("select count(*) from finance_attachment where claim_id=?",Integer.class,claim)).isOne();
    }
    @Test void imageSimilarityIsTenantScopedWarningOnlyAndDoesNotExposeOtherEmployeesOrDocuments()throws Exception{
        UUID first=claim(),attachment=UUID.randomUUID(),previousOwner=owner;upload(first,attachment,"INVOICE",image(50));seedTenant();
        var unrelated=upload(claim(),UUID.randomUUID(),"INVOICE",image(60));assertThat(extraction(unrelated)).doesNotContainKey("similarityWarning");
        var similar=upload(claim(),UUID.randomUUID(),"INVOICE",image(70));assertThat(extraction(similar)).containsKey("similarityWarning");assertThat(extraction(similar).get("similarityWarning").toString()).doesNotContain(first.toString(),attachment.toString(),previousOwner.toString());
    }
    @Test void retryRejectsOtherOwnerSubmittedClaimRemovedOriginalAndTamperedFileBeforeCallingWorker()throws Exception{
        UUID claim=claim(),attachment=UUID.randomUUID();upload(claim,attachment,"INVOICE",image(80));int before=posts.get();
        as(other,"finance.submit");assertThatThrownBy(()->evidence.recognizeAttachment(claim,attachment)).isInstanceOf(RuntimeException.class);assertThat(posts.get()).isEqualTo(before);
        as(owner,"finance.submit");String key=admin.queryForObject("select object_key from finance_attachment where id=?",String.class,attachment);Files.write(files.resolve(key),new byte[]{1,2,3});assertThatThrownBy(()->evidence.recognizeAttachment(claim,attachment)).hasMessageContaining("完整性");assertThat(posts.get()).isEqualTo(before);
        UUID pending=completeClaim(90);call(()->finance.submit(pending,1));UUID original=admin.queryForObject("select id from finance_attachment where claim_id=? and kind='INVOICE'",UUID.class,pending);before=posts.get();assertThatThrownBy(()->evidence.recognizeAttachment(pending,original)).hasMessageContaining("不能覆盖");assertThat(posts.get()).isEqualTo(before);
    }
    @Test void applicationRoleCannotReadOtherTenantResultsOrRewriteObservationsAndWrongShaCannotBeInserted()throws Exception{
        UUID claim=claim(),attachment=UUID.randomUUID();upload(claim,attachment,"INVOICE",image(100));
        assertThat(call(()->app.queryForObject("select count(*) from finance_local_ocr_result",Integer.class))).isOne();
        assertThatThrownBy(()->call(()->{app.update("delete from finance_local_ocr_result where attachment_id=?",attachment);return true;})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->call(()->{app.update("insert into finance_local_ocr_result(id,tenant_id,attachment_id,source_sha256,kind,extraction,created_by) values(?,?,?,?,'INVOICE',?::jsonb,?)",UUID.randomUUID(),tenant,attachment,"0".repeat(64),FinanceV2Support.json(Map.of("sourceSha256","0".repeat(64),"confirmationRequired",true)),owner);return true;})).hasMessageContaining("exact scanned original");
        assertThat(call(()->{app.execute("set local app.tenant_id='ffffffff-ffff-4fff-8fff-ffffffffffff'");return app.queryForObject("select count(*) from finance_local_ocr_result",Integer.class);})).isZero();
    }
    private UUID completeClaim(int color)throws Exception{UUID claim=claim(),invoice=UUID.randomUUID();upload(claim,invoice,"INVOICE",image(color));upload(claim,UUID.randomUUID(),"TRANSACTION",image(color+1));upload(claim,UUID.randomUUID(),"PAYMENT",image(color+2));call(()->finance.save(claim,new FinanceModels.Draft(0,"真实客房用品",LocalDate.now(ZoneId.of("Asia/Shanghai")),category,new BigDecimal("90"),new BigDecimal("90"),new BigDecimal("90"),"",List.of(new FinanceModels.Invoice(UUID.randomUUID(),invoice,"",INVOICE,new BigDecimal("90"),TAX,true)))));return claim;}
    private UUID claim(){UUID id=UUID.randomUUID();call(()->finance.create(new FinanceModels.Create(id,assignment)));return id;}
    private Map<String,Object> upload(UUID claim,UUID attachment,String kind,byte[] bytes){return evidence.upload(claim,attachment,kind,new MockMultipartFile("file","真实凭证.jpg","image/jpeg",bytes));}
    @SuppressWarnings("unchecked") private Map<String,Object> extraction(Map<String,Object> value){return (Map<String,Object>)value.get("extraction");}
    private UUID account(){UUID id=UUID.randomUUID();admin.update("insert into user_account(id,tenant_id,login_name,display_name,status) values(?,?,?,'测试真实账号','ACTIVE')",id,tenant,id.toString());return id;}
    private void as(UUID actor,String... permissions){TenantContext.set(new TenantPrincipal(tenant,actor,"OCR_TEST",Set.of("OCR_TEST"),Set.of(permissions),Set.of(hotel),Set.of(assignment),assignment,true,UUID.randomUUID()));}
    private <T>T call(Supplier<T> work){return tx.execute(status->{database.apply(TenantContext.require().tenantId());return work.get();});}
    private int originalCount()throws Exception{try(var stream=Files.walk(files)){return Math.toIntExact(stream.filter(Files::isRegularFile).count());}}
    private byte[] image(int color)throws Exception{var image=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);for(int y=0;y<40;y++)for(int x=0;x<80;x++)image.setRGB(x,y,color*0x010101);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"jpg",bytes);return bytes.toByteArray();}
}
