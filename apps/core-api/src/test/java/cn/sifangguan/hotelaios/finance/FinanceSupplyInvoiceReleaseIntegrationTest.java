package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
import cn.sifangguan.hotelaios.storeassets.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static cn.sifangguan.hotelaios.finance.FinanceSupplyModels.*;

class FinanceSupplyInvoiceReleaseIntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static TransactionTemplate tx;static FinanceSupplyService supply;static FinanceSupplyInvoiceControlService releases;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");static final LocalDate DAY=LocalDate.of(2026,10,24);
 UUID hotel,company,owner,finance,manager,cashier,receipt,line;
 @BeforeAll static void start()throws Exception{pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();jdbc=new JdbcTemplate(source);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");Flyway.configure().dataSource(source).locations("classpath:db/migration").target("102").load().migrate();var named=new NamedParameterJdbcTemplate(source);var database=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();var mapper=new ObjectMapper().findAndRegisterModules();var audit=new AuditWriter(named,e->{});tx=new TransactionTemplate(new DataSourceTransactionManager(source));var accounting=new FinanceAccountingService(named,database,access,audit,mapper);var stock=new StoreResourceService(named,database,access,new EffectiveIdentityService(named,database),audit,mapper,new StoreItemUsageScopes(named));supply=new FinanceSupplyService(named,database,access,mapper,audit,accounting,new StoreFinanceBridge(named,stock,access),new FinanceOperationsService(named,database,access,mapper,audit,accounting));releases=new FinanceSupplyInvoiceControlService(named,database,access,audit);}
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){hotel=UUID.randomUUID();company=UUID.randomUUID();owner=user();finance=user();manager=user();cashier=user();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'错票独立回退测试','HOTEL')",hotel,T,hotel.toString());jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'错票测试法人',?)",company,T,company.toString().replace("-",""));jdbc.update("insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,workflow_version,payable_account,enabled) values(?,?,?,?,?,?,?,?,2,'AP',true)",T,hotel,company,manager,finance,user(),user(),cashier);var batch=batch();receipt=batch.get("receipt");line=batch.get("line");as(owner,"*");}

 @Test void independentReleasePreservesEveryOriginalUseAndReturnsNeverRecognizedDraftToUnmatched(){
  UUID invoice=invoice(),rec=match(receipt,line,invoice,0),request=release(rec,0);as(owner,"finance.review");assertThatThrownBy(()->call(()->releases.review(request,new FinanceSupplyInvoiceControlService.Review(0,true,"不能自审")))).hasMessageContaining("独立");
  approve(request);assertThat(jdbc.queryForObject("select status from fs_recognition where id=?",String.class,rec)).isEqualTo("UNMATCHED");assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where recognition_id=? and released_at is not null",Integer.class,rec)).isOne();assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where recognition_id=?",Integer.class,rec)).isOne();assertThat(jdbc.queryForObject("select count(*) from fa_source_event where source_id=?",Integer.class,rec)).isZero();
  var next=batch();match(next.get("receipt"),next.get("line"),invoice,0);assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where invoice_id=?",Integer.class,invoice)).isEqualTo(2);assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where invoice_id=? and released_at is null",Integer.class,invoice)).isOne();
 }
 @Test void alreadyConfirmedEconomicCostCannotBeReleasedToManufactureASecondReimbursement(){
  UUID rec=match(receipt,line,invoice(),0);as(finance,"finance.review");call(()->supply.confirm(rec,new Confirm(UUID.randomUUID(),0,"独立确认真实成本")));as(owner,"finance.submit");assertThatThrownBy(()->call(()->releases.request(new FinanceSupplyInvoiceControlService.Request(UUID.randomUUID(),rec,1,"不能解占重报")))).hasMessageContaining("已确认采购");assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where recognition_id=? and released_at is null",Integer.class,rec)).isOne();
 }
 @Test void estimatedReceiptMayReleaseOnlyUnconfirmedWrongInvoiceWithoutUndoingEstimateOrPhysicalAcceptance(){
  UUID rec=UUID.randomUUID();as(owner,"finance.submit");call(()->supply.match(new Match(rec,receipt,0,true,List.of(),List.of(),DAY,"原服务验收真实暂估")));as(finance,"finance.review");call(()->supply.confirm(rec,new Confirm(UUID.randomUUID(),0,"原暂估确认")));UUID invoice=invoice();match(receipt,line,invoice,1);UUID release=release(rec,2);approve(release);
  assertThat(jdbc.queryForObject("select status from fs_recognition where id=?",String.class,rec)).isEqualTo("ESTIMATED");assertThat(jdbc.queryForObject("select count(*) from fa_source_event where source_id=? and event_key like 'ESTIMATE%'",Integer.class,rec)).isOne();assertThat(jdbc.queryForObject("select count(*) from procurement_receipt where id=? and status='APPROVED'",Integer.class,receipt)).isOne();assertThat(jdbc.queryForObject("select invoice_amount from fs_recognition where id=?",BigDecimal.class,rec)).isNull();
 }
 @Test void changedMatchingVersionMakesOldReleaseApprovalFailAtomically(){
  UUID rec=match(receipt,line,invoice(),0),release=release(rec,0);match(receipt,line,invoice(),0);as(finance,"finance.review");assertThatThrownBy(()->call(()->releases.review(release,new FinanceSupplyInvoiceControlService.Review(0,true,"原审批不能释放新增的另一票")))).hasMessageContaining("版本");assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where recognition_id=? and released_at is null",Integer.class,rec)).isEqualTo(2);assertThat(jdbc.queryForObject("select status from fs_invoice_release where id=?",String.class,release)).isEqualTo("REVIEW");
 }
 @Test void rejectedReleaseKeepsGlobalInvoiceOccupancyAndCannotEditFrozenOriginalFields(){
  UUID invoice=invoice(),rec=match(receipt,line,invoice,0),release=release(rec,0);as(finance,"finance.review");call(()->releases.review(release,new FinanceSupplyInvoiceControlService.Review(0,false,"依据不充分，仍锁原票")));var next=batch();assertThatThrownBy(()->match(next.get("receipt"),next.get("line"),invoice,0)).hasMessageContaining("相同票号");as(owner,"*");assertThatThrownBy(()->call(()->{jdbc.update("update fs_invoice_use set invoice_key='改票号' where invoice_id=?",invoice);return null;})).hasMessageContaining("immutable");
 }
 @Test void releaseWithReturnOrPayableDependencyIsRejectedInsteadOfDroppingFinancialHistory(){
  UUID rec=match(receipt,line,invoice(),0);jdbc.update("insert into fs_return(id,tenant_id,recognition_id,hotel_id,line_id,quantity,kind,applicant_id,reviewer_id,status,note,accounting_date) values(?,?,?,?,?,1,'RETURN',?,?,'PENDING','已有主管待复核退换',?)",UUID.randomUUID(),T,rec,hotel,line,owner,manager,DAY);as(owner,"finance.submit");assertThatThrownBy(()->call(()->releases.request(new FinanceSupplyInvoiceControlService.Request(UUID.randomUUID(),rec,0,"有依赖不可仅解票")))).hasMessageContaining("后续依赖");assertThat(jdbc.queryForObject("select count(*) from fs_invoice_release where recognition_id=?",Integer.class,rec)).isZero();
 }
 @Test void afterApprovedReleaseConcurrentReusesOfSameOriginalStillCommitOnlyOneNewOccupancy(){
  UUID invoice=invoice(),rec=match(receipt,line,invoice,0),release=release(rec,0);approve(release);var one=batch();var two=batch();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->tryMatch(one,invoice));var b=pool.submit(()->tryMatch(two,invoice));assertThat((a.get(20,TimeUnit.SECONDS)?1:0)+(b.get(20,TimeUnit.SECONDS)?1:0)).isOne();}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where invoice_id=? and released_at is null",Integer.class,invoice)).isOne();assertThat(jdbc.queryForObject("select count(*) from fs_invoice_use where invoice_id=? and released_at is not null",Integer.class,invoice)).isOne();
 }
 private boolean tryMatch(Map<String,UUID> batch,UUID invoice){try{match(batch.get("receipt"),batch.get("line"),invoice,0);return true;}catch(org.springframework.web.server.ResponseStatusException expected){return false;}}
 private UUID release(UUID rec,int version){as(owner,"finance.submit");UUID release=UUID.randomUUID();call(()->releases.request(new FinanceSupplyInvoiceControlService.Request(release,rec,version,"已核实转票确认前选错原票，申请独立释放全部本次原票占用")));return release;}
 private void approve(UUID request){as(finance,"finance.review");call(()->releases.review(request,new FinanceSupplyInvoiceControlService.Review(0,true,"独立核实没有原票正式成本或后续应付/退换依赖")));}
 private UUID match(UUID receipt,UUID line,UUID invoice,int version){as(owner,"finance.submit");UUID request=UUID.randomUUID();call(()->supply.match(new Match(request,receipt,version,false,List.of(invoice),List.of(new InvoiceLine(line,BigDecimal.ONE,new BigDecimal("100"))),DAY,"实际原件匹配")));return jdbc.queryForObject("select id from fs_recognition where receipt_id=?",UUID.class,receipt);}
 private Map<String,UUID> batch(){UUID order=UUID.randomUUID(),line=UUID.randomUUID(),receipt=UUID.randomUUID();jdbc.update("insert into procurement_order(id,tenant_id,hotel_id,source,status,applicant_id,title,supplier) values(?,?,?,'REQUEST','COMPLETED',?,'真实服务验收','供应商A')",order,T,hotel,owner);jdbc.update("insert into procurement_line(id,tenant_id,hotel_id,order_id,item_id,line_kind,item_name,sku,base_unit,input_unit,factor,input_quantity,quantity,unit_price,approved_quantity) values(?,?,?,?,null,'SERVICE','独立维修服务','','项','项',1,1,1,100,1)",line,T,hotel,order);jdbc.update("insert into procurement_receipt(id,tenant_id,order_id,receiver_id,status,reviewer_id,reviewed_at) values(?,?,?,?,'APPROVED',?,now())",receipt,T,order,owner,manager);jdbc.update("insert into procurement_receipt_line(tenant_id,order_id,receipt_id,line_id,arrived,accepted,rejected,accepted_value) values(?,?,?,?,1,1,0,100)",T,order,receipt,line);return Map.of("receipt",receipt,"line",line);}
 private UUID invoice(){UUID id=UUID.randomUUID(),assignment=jdbc.queryForObject("select id from employee_position_assignment where tenant_id=? limit 1",UUID.class,T);String number=String.format("%020d",new java.math.BigInteger(id.toString().replace("-",""),16).mod(java.math.BigInteger.TEN.pow(20)));jdbc.update("insert into finance_invoice_intake(id,tenant_id,owner_id,assignment_id,hotel_id,invoice_number,amount,buyer_tax_number,confirmed,original) values(?,?,?,?,?,?,100,?,true,cast(? as jsonb))",id,T,owner,assignment,hotel,number,company.toString().replace("-",""),"{\"sha256\":\""+FinanceService.hash(id.toString())+"\",\"scanStatus\":\"CLEAN\"}");return id;}
 private UUID user(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'错票测试独立人员')",id,T,id.toString());return id;}
 private void as(UUID actor,String...permissions){TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 private static <X>X call(Supplier<X> work){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");new TenantDatabaseContext(jdbc,true).apply(TenantContext.require().tenantId());return work.get();});}
}
