package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
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
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class FinanceCloseV2IntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static NamedParameterJdbcTemplate named;static TransactionTemplate tx;static FinanceCloseControlService service;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");static final LocalDate MONTH=LocalDate.of(2026,10,1);
 UUID hotel,company,book,owner,director,other;
 @BeforeAll static void start()throws Exception{pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();jdbc=new JdbcTemplate(source);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");Flyway.configure().dataSource(source).locations("classpath:db/migration").target("99").load().migrate();named=new NamedParameterJdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));service=new FinanceCloseControlService(named,new TenantDatabaseContext(jdbc,true),new AccessPolicy(),new AuditWriter(named,e->{}));}
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){hotel=UUID.randomUUID();company=UUID.randomUUID();book=UUID.randomUUID();owner=user();director=user();other=user();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'全来源关账测试','HOTEL')",hotel,T,hotel.toString());jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'月结测试法人',?)",company,T,company.toString().replace("-",""));jdbc.update("insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,payable_account,workflow_version,enabled) values(?,?,?,?,?,?,?,?,\'AP\',2,true)",T,hotel,company,other,owner,director,user(),user());jdbc.update("insert into fa_book(id,tenant_id,hotel_id,company_id,name,start_month) values(?,?,?,?, '月结真实账套',?)",book,T,hotel,company,MONTH);as(owner,"*");}

 @Test void emptyOrSingleHandwrittenSourceCannotPretendToCoverAllDomains(){
  assertThat(passed("V2_SOURCE_REGISTER")).isFalse();
  assertThatThrownBy(()->call(()->service.register(new FinanceCloseModels.Register(UUID.randomUUID(),book,"2026-10",List.of(new FinanceCloseModels.Entry("BANK","NOT_APPLICABLE",0,BigDecimal.ZERO,"银行原始清单","本期无银行流水")),"不能只写一个完成行")))).hasMessageContaining("全部");
  assertThat(jdbc.queryForObject("select count(*) from fcl_register where book_id=?",Integer.class,book)).isZero();
 }
 @Test void independentlyConfirmedAllSourceRegisterIsImmutableAndChangedRealSourceInvalidatesIt(){
  UUID register=register();as(owner,"*");assertThatThrownBy(()->call(()->service.confirmRegister(register,new FinanceCloseModels.Confirm(0,true,"不能自审")))).hasMessageContaining("独立");as(other,"finance.review");assertThatThrownBy(()->call(()->service.confirmRegister(register,new FinanceCloseModels.Confirm(0,true,"非配置人")))).hasMessageContaining("独立");as(director,"finance.review");call(()->service.confirmRegister(register,new FinanceCloseModels.Confirm(0,true,"已逐项核对实际无业务与原始依据")));as(owner,"*");assertThat(passed("V2_SOURCE_REGISTER")).isTrue();
  assertThatThrownBy(()->call(()->{jdbc.update("update fcl_register set note='覆盖旧依据' where id=?",register);return null;})).hasMessageContaining("immutable");
  revenue("REVIEW");assertThat(passed("V2_SOURCE_REGISTER")).isFalse();assertThat(passed("V2_REVENUE_REVIEW")).isFalse();
 }
 @Test void actualRevenueCannotBeMarkedNotApplicableOrDeclaredWithWrongControlTotal(){
  revenue("REVIEW");var entries=entries();int index=0;for(int n=0;n<entries.size();n++)if(entries.get(n).sourceCode().equals("REVENUE_PMS"))index=n;
  entries.set(index,new FinanceCloseModels.Entry("REVENUE_PMS","NOT_APPLICABLE",0,BigDecimal.ZERO,"原始PMS","明明存在不能跳过"));
  assertThatThrownBy(()->call(()->service.register(new FinanceCloseModels.Register(UUID.randomUUID(),book,"2026-10",entries,"不能伪造不适用")))).hasMessageContaining("已有真实");
  entries.set(index,new FinanceCloseModels.Entry("REVENUE_PMS","APPLICABLE",1,new BigDecimal("99"),"PMS原始明细控制100","不能用99代替"));assertThatThrownBy(()->call(()->service.register(new FinanceCloseModels.Register(UUID.randomUUID(),book,"2026-10",entries,"不得把控制数改平")))).hasMessageContaining("总额");
 }
 @Test void bankExplanationIsIndependentAndAnyCrossDomainAllocationChangeInvalidatesSnapshot(){
  UUID bank=bank(),explanation=UUID.randomUUID();call(()->service.explain(new FinanceCloseModels.Explanation(explanation,book,bank,"2026-10","真实未达账项：已查原始流水，责任人与预计清算日见银行说明")));assertThat(passed("BANK_RECONCILIATION")).isFalse();as(director,"finance.review");call(()->service.confirmExplanation(explanation,new FinanceCloseModels.Confirm(0,true,"独立核实该笔未达项")));as(owner,"*");assertThat(passed("BANK_RECONCILIATION")).isTrue();
  UUID entry=revenue("CONFIRMED"),movement=UUID.randomUUID();jdbc.update("insert into fb_revenue_movement(id,tenant_id,entry_id,kind,amount,occurred_on,bank_reference,note,created_by) values(?,?,?,'RECEIPT',40,?,'真实部分到账','原始收入实收',?)",movement,T,entry,MONTH,owner);jdbc.update("insert into fb_bank_match(id,tenant_id,transaction_id,source_type,source_id,amount,request_id,note,actor_id) values(?,?,?,'REVENUE',?,40,?,'真实部分匹配',?)",UUID.randomUUID(),T,bank,movement,UUID.randomUUID(),owner);assertThat(passed("BANK_RECONCILIATION")).isFalse();
  assertThat(jdbc.queryForObject("select status from fcl_bank_explanation where id=?",String.class,explanation)).isEqualTo("CONFIRMED");
 }
 @Test void revenueCashMustMatchButRedemptionAloneIsNotCash(){
  UUID entry=revenue("CONFIRMED"),related=revenue("CONFIRMED");jdbc.update("insert into fb_revenue_movement(id,tenant_id,entry_id,related_entry_id,kind,amount,occurred_on,bank_reference,note,created_by) values(?,?,?,?,'REDEEM',10,?,'真实预收核销','不是银行收款',?)",UUID.randomUUID(),T,entry,related,MONTH,owner);assertThat(passed("V2_CASH_BUSINESS_MATCH")).isTrue();
  jdbc.update("insert into fb_revenue_movement(id,tenant_id,entry_id,kind,amount,occurred_on,bank_reference,note,created_by) values(?,?,?,'RECEIPT',10,?,'真实实收待匹配','是银行收款',?)",UUID.randomUUID(),T,entry,MONTH,owner);assertThat(passed("V2_CASH_BUSINESS_MATCH")).isFalse();
 }
 @Test void explicitSourceCountsUseRealTenantRlsAndDenyCrossStoreOrSubmitOnlyReaders(){
  UUID register=register();as(other,"finance.submit");assertThatThrownBy(()->call(()->service.context(book,"2026-10"))).isInstanceOf(AccessDeniedException.class);TenantContext.set(new TenantPrincipal(T,owner,"TEST",Set.of("TEST"),Set.of("finance.accounting.close"),Set.of(UUID.randomUUID()),Set.of(),false,UUID.randomUUID()));assertThatThrownBy(()->call(()->service.context(book,"2026-10"))).isInstanceOf(AccessDeniedException.class);as(owner,"*");call(()->{jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("select count(*) from fcl_register where id=?",Integer.class,register)).isZero();return null;});
 }
 @Test void sourceRegisterRejectsClosedMonthAndChangedIdempotencyPayload(){UUID request=register();as(owner,"*");var original=entries();assertThatThrownBy(()->call(()->service.register(new FinanceCloseModels.Register(request,book,"2026-10",original,"同编号不同说明")))).hasMessageContaining("不同原始内容");jdbc.update("insert into fa_period(tenant_id,book_id,month,status) values(?,?,?,'CLOSED')",T,book,MONTH);assertThatThrownBy(()->call(()->service.register(new FinanceCloseModels.Register(UUID.randomUUID(),book,"2026-10",original,"锁期不可新增")))).hasMessageContaining("已关账");}

 @Test void constructionAssetIsNotForcedToDepreciateAndRealCapitalizationStartsItsActualMonth(){
  UUID physical=UUID.randomUUID(),document=UUID.randomUUID(),card=UUID.randomUUID();jdbc.update("insert into store_fixed_asset(id,tenant_id,hotel_org_unit_id,asset_code,name,category) values(?,?,?,?, '原实物在建项目','工程')",physical,T,hotel,physical.toString());jdbc.update("insert into fo_document(id,tenant_id,hotel_id,company_id,applicant_id,type,title,business_date,amount,details,lines,status) values(?,?,?,?,?,'ASSET','原在建实际资本来源',?,120,'{}','[]','APPROVED')",document,T,hotel,company,owner,MONTH);
  for(String code:List.of("ASSET","CIP","DEP","EXP"))jdbc.update("insert into fa_account(tenant_id,book_id,code,name,category,normal_side,report_line) values(?,?,?,?,?,?,?)",T,book,code,code,code.equals("EXP")?"EXPENSE":"ASSET",code.equals("DEP")?"CREDIT":"DEBIT",code);
  jdbc.update("insert into fs_asset_card(id,tenant_id,hotel_id,company_id,physical_asset_id,source_document_id,book_id,asset_code,status,original_value,residual_value,useful_months,in_service_date,asset_account,cip_account,depreciation_account,expense_account,created_by) values(?,?,?,?,?,?,?,?,'CIP',120,0,12,?,'ASSET','CIP','DEP','EXP',?)",card,T,hotel,company,physical,document,book,card.toString(),MONTH,owner);assertThat(passed("V2_ASSET_DEPRECIATION")).as("仍在建时不能被迫生成业务明确禁止的折旧").isTrue();
  var db=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var audit=new AuditWriter(named,e->{});var accounting=new FinanceAccountingService(named,db,access,audit,mapper);var stock=new cn.sifangguan.hotelaios.storeassets.StoreResourceService(named,db,access,new EffectiveIdentityService(named,db),audit,mapper,new cn.sifangguan.hotelaios.storeassets.StoreItemUsageScopes(named));var supply=new FinanceSupplyService(named,db,access,mapper,audit,accounting,new cn.sifangguan.hotelaios.storeassets.StoreFinanceBridge(named,stock,access),new FinanceOperationsService(named,db,access,mapper,audit,accounting));LocalDate capitalization=MONTH.plusDays(14);UUID capital=UUID.randomUUID();as(other,"*");call(()->supply.assetEvent(card,new FinanceSupplyModels.AssetEvent(capital,0,"CAPITALIZE",capitalization,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,null,"实际转固而非登记时自动投用")));assertThat(jdbc.queryForObject("select in_service_date from fs_asset_card where id=?",LocalDate.class,card)).isEqualTo(capitalization);assertThat(passed("V2_ASSET_DEPRECIATION")).as("真实转固当月起必须完成实际折旧和独立过账").isFalse();
  as(owner,"*");call(()->accounting.review(capital,0));call(()->accounting.post(capital,1));UUID depreciation=UUID.randomUUID();as(other,"*");call(()->supply.assetEvent(card,new FinanceSupplyModels.AssetEvent(depreciation,1,"DEPRECIATE",capitalization,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,null,"转固后的实际当月折旧")));assertThat(passed("V2_ASSET_DEPRECIATION")).isFalse();as(owner,"*");call(()->accounting.review(depreciation,0));call(()->accounting.post(depreciation,1));assertThat(passed("V2_ASSET_DEPRECIATION")).isTrue();
 }
 private UUID register(){as(owner,"*");UUID id=UUID.randomUUID();call(()->service.register(new FinanceCloseModels.Register(id,book,"2026-10",entries(),"逐项按实际原始来源核对并提供依据")));return id;}
 private List<FinanceCloseModels.Entry> entries(){as(owner,"*");var stats=call(()->service.context(book,"2026-10"));var result=new ArrayList<FinanceCloseModels.Entry>();for(var row:FinanceV2Support.rows(stats.get("sources"))){long count=((Number)row.get("count")).longValue();result.add(new FinanceCloseModels.Entry(row.get("sourceCode").toString(),count>0?"APPLICABLE":"NOT_APPLICABLE",count,new BigDecimal(row.get("amount").toString()),"已核对原始系统/导出批次与门店经营记录","逐项实际核对，本期"+(count>0?"存在业务":"没有发生此类业务")));}return result;}
 private boolean passed(String code){as(owner,"*");return call(()->{var bk=jdbc.queryForMap("select * from fa_book where id=?",book);var db=new TenantDatabaseContext(jdbc,true);db.apply(T);return FinanceCloseV2.extraChecks(named,TenantContext.require(),bk,MONTH).stream().filter(c->code.equals(c.get("code"))).map(c->Boolean.TRUE.equals(c.get("passed"))).findFirst().orElseThrow();});}
 private UUID revenue(String status){UUID batch=UUID.randomUUID(),entry=UUID.randomUUID();int revision=jdbc.queryForObject("select coalesce(max(revision),0)+1 from fb_revenue_batch where tenant_id=? and hotel_id=?",Integer.class,T,hotel);jdbc.update("insert into fb_revenue_batch(id,tenant_id,hotel_id,company_id,source,channel,business_date,night_audit_date,revision,expected_count,control_total,source_reference,status,source_hash,created_by,reviewer_id) values(?,?,?,?,'PMS','真实PMS',?,?,?,1,100,'原始夜审汇总',?,?,?,?)",batch,T,hotel,company,MONTH,MONTH,revision,status,"a".repeat(64),owner,director);jdbc.update("insert into fb_revenue_entry(id,tenant_id,batch_id,hotel_id,company_id,external_id,kind,customer,channel,due_date,amount,net_amount,tax_amount,commission,fee,active) values(?,?,?,?,?,?,'INCOME','真实客户','真实PMS',?,100,100,0,0,0,true)",entry,T,batch,hotel,company,entry.toString(),MONTH);return entry;}
 private UUID bank(){UUID account=UUID.randomUUID(),transaction=UUID.randomUUID();jdbc.update("insert into fo_bank_account(id,tenant_id,hotel_id,company_id,name,account_number,bank_name,opening_date,opening_balance,created_by) values(?,?,?,?, '真实银行账户',?,'银行',?,0,?)",account,T,hotel,company,account.toString(),MONTH,owner);jdbc.update("insert into fo_bank_transaction(id,tenant_id,bank_account_id,batch_id,external_id,occurred_on,direction,amount,source_hash,created_by) values(?,?,?,?,?,?,'IN',100,?,?)",transaction,T,account,UUID.randomUUID(),transaction.toString(),MONTH,"b".repeat(64),owner);return transaction;}
 private UUID user(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'月结真实角色')",id,T,id.toString());return id;}
 private void as(UUID actor,String...permissions){TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 private static <X>X call(Supplier<X> work){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");new TenantDatabaseContext(jdbc,true).apply(TenantContext.require().tenantId());return work.get();});}
}
