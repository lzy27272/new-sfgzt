package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
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
import static cn.sifangguan.hotelaios.finance.FinanceAccrualModels.*;

/** Real PostgreSQL/app-role checks; generated schedules never pretend to be cash or posted GL. */
class FinanceAccrualIntegrationTest {
 static EmbeddedPostgres pg;
 static JdbcTemplate jdbc;
 static TransactionTemplate tx;
 static FinanceAccountingService accounting;
 static FinanceLedgerGovernance governance;static FinanceControlService control;
 static FinanceAccrualControlService service;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");
 static final LocalDate START=LocalDate.of(2026,8,1);
 UUID hotel,company,owner,director,reviewer,book,budgetCategory;

 @BeforeAll static void start()throws Exception {
  pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();jdbc=new JdbcTemplate(source);
  jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
  Flyway.configure().dataSource(source).locations("classpath:db/migration").target("111").load().migrate();
  var named=new NamedParameterJdbcTemplate(source);var database=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();
  var audit=new AuditWriter(named,e->{});var mapper=new ObjectMapper().findAndRegisterModules();
  tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  accounting=new FinanceAccountingService(named,database,access,audit,mapper);
  governance=new FinanceLedgerGovernance(named,database,access,accounting);control=new FinanceControlService(named,database,access,null,null,null);
  service=new FinanceAccrualControlService(named,database,access,audit,accounting,false);
 }
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){
  hotel=UUID.randomUUID();company=UUID.randomUUID();owner=user();director=user();reviewer=user();book=UUID.randomUUID();
  jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'受控摊销门店','HOTEL')",hotel,T,hotel.toString());
  jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'摊销独立法人',?)",company,T,company.toString().replace("-",""));
  jdbc.update("insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,workflow_version,payable_account,enabled) values(?,?,?,?,?,?,?,?,2,'AP',true)",T,hotel,company,reviewer,owner,director,user(),user());
  as(owner,"*");call(()->accounting.createBook(new FinanceAccountingModels.Book(book,hotel,company,"租赁待摊账套",START)));
  for(String code:List.of("EXP","PREPAID","AP")){
   UUID proposal=UUID.randomUUID();as(owner,"*");call(()->governance.account(proposal,new FinanceAccountingModels.Account(book,code,code,code.equals("EXP")?"EXPENSE":code.equals("AP")?"LIABILITY":"ASSET",code.equals("AP")?"CREDIT":"DEBIT",code,false,true,-1),"真实科目草稿"));
   as(reviewer,"*");call(()->governance.publish(proposal,new FinanceControlModels.Version(0,"另一财务独立核对发布"),false));
  }
  UUID category=UUID.randomUUID();as(owner,"finance.configure");call(()->control.savePolicy(new FinanceControlModels.Policy(category,hotel,"CATEGORY","actual-period-costs","真实待摊期间费用",START,null,Map.of("items",List.of(Map.of("code","PERIOD_COST","name","本期真实受益费用","treatment","EXPENSE"))))));as(director,"finance.configure");call(()->control.publishPolicy(category,new FinanceControlModels.Version(0,"独立确认费用类别"),false));
  budgetCategory=jdbc.queryForObject("select id from finance_category where tenant_id=? and hotel_id=? and budget_code='PERIOD_COST' and enabled",UUID.class,T,hotel);
  as(owner,"*");
 }

 @Test void monthlySchedulePreservesCentsCreatesOnlyDraftAndRetainsDepartmentEvidence(){
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01");
  UUID aug=run(plan,"2026-08");assertThat(jdbc.queryForObject("select status from fa_voucher where id=?",String.class,aug)).isEqualTo("DRAFT");
  assertThat(jdbc.queryForObject("select status from fa_source_event where voucher_id=?",String.class,aug)).isEqualTo("DRAFT");
  assertThatThrownBy(()->run(plan,"2026-09")).hasMessageContaining("前月凭证");
  post(aug);UUID sep=run(plan,"2026-09");post(sep);UUID oct=run(plan,"2026-10");
  assertThat(jdbc.queryForList("select amount from far_run where plan_id=? order by month",BigDecimal.class,plan)).containsExactly(new BigDecimal("33.33"),new BigDecimal("33.33"),new BigDecimal("33.35"));
  assertThat(jdbc.queryForObject("select sum(amount) from far_run where plan_id=?",BigDecimal.class,plan)).isEqualByComparingTo("100.01");
  assertThat(jdbc.queryForObject("select count(*) from fa_voucher_line where voucher_id=?",Integer.class,oct)).isEqualTo(2);
  assertThat(jdbc.queryForObject("select allocation_snapshot->0->>'departmentId' from far_run where id=?",String.class,oct)).isEqualTo(hotel.toString());
  assertThatThrownBy(()->call(()->{jdbc.update("update far_run set amount=1 where id=?",aug);return null;})).hasMessageContaining("append-only");
 }
 @Test void partialActualPaymentCannotBePretendedAsAvailablePrepaidBalance(){
  UUID source=source("PREPAYMENT","100.01","20"),plan=plan(source,null,"PREPAID","100.01");
  assertThatThrownBy(()->run(plan,"2026-08")).hasMessageContaining("净实付");
  assertThat(jdbc.queryForObject("select count(*) from far_run where plan_id=?",Integer.class,plan)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from fa_source_event where source_type='FAR_ACCRUAL' and metadata->>'planId'=?",Integer.class,plan.toString())).isZero();
 }
 @Test void leaseAccrualUsesActualApprovedContractAndDoesNotInventPayment(){
  UUID source=source("CONTRACT","100.01","0"),plan=plan(source,null,"LEASE_ACCRUAL","100.01");run(plan,"2026-08");
  assertThat(jdbc.queryForObject("select count(*) from fo_action where document_id=?",Integer.class,source)).isZero();
  assertThat(jdbc.queryForObject("select sum(amount) from far_run where plan_id=?",BigDecimal.class,plan)).isEqualByComparingTo("33.33");
 }
 @Test void policyAndRevisionRequireIndependentActuallyConfiguredDirector(){
  UUID policy=createPolicy("PREPAID");as(owner,"*");assertThatThrownBy(()->call(()->service.publishPolicy(policy,new Confirm(UUID.randomUUID(),0,true,"本人不能代配置总监")))).hasMessageContaining("独立");
  as(reviewer,"finance.review");assertThatThrownBy(()->call(()->service.publishPolicy(policy,new Confirm(UUID.randomUUID(),0,true,"非指派人")))).hasMessageContaining("独立");
  as(director,"finance.review");call(()->service.publishPolicy(policy,new Confirm(UUID.randomUUID(),0,true,"总监独立确认")));
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=UUID.randomUUID();as(owner,"*");call(()->service.createPlan(planInput(plan,source,null,policy,"100.01")));
  as(reviewer,"finance.review");assertThatThrownBy(()->call(()->service.reviewRevision(plan,new Confirm(UUID.randomUUID(),0,true,"不能代审")))).hasMessageContaining("授权范围");
  as(director,"finance.review");call(()->service.reviewRevision(plan,new Confirm(UUID.randomUUID(),0,true,"真实总监确认")));
 }
 @Test void reviewedAdjustmentNeverRewritesFrozenMonthsOrUnpostedDependencies(){
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01"),aug=run(plan,"2026-08");
  as(owner,"*");assertThatThrownBy(()->call(()->service.revise(plan,revision(plan,"REVISE","2026-09","66.68")))).hasMessageContaining("依赖");post(aug);
  as(owner,"*");assertThatThrownBy(()->call(()->service.revise(plan,revision(plan,"REVISE","2026-08","66.68")))).hasMessageContaining("冻结");
  UUID adjustment=UUID.randomUUID();call(()->service.revise(plan,new Revision(adjustment,version(plan),"REVISE","2026-09","2026-10",new BigDecimal("66.68"),"EXP","PREPAID",allocations(),"自9月按新受益依据调整")));
  as(director,"finance.review");call(()->service.reviewRevision(adjustment,new Confirm(UUID.randomUUID(),0,true,"独立确认未来调整")));
  UUID sep=run(plan,"2026-09");post(sep);run(plan,"2026-10");
  assertThat(jdbc.queryForObject("select amount from far_run where id=?",BigDecimal.class,aug)).isEqualByComparingTo("33.33");
  assertThat(jdbc.queryForObject("select count(*) from far_revision where plan_id=? and status='APPROVED'",Integer.class,plan)).isEqualTo(2);
 }
 @Test void terminationRequiresPriorGlAndShowsRefundDueWithoutInventingRefundCash(){
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01"),aug=run(plan,"2026-08");post(aug);
  UUID termination=UUID.randomUUID();as(owner,"*");call(()->service.revise(plan,new Revision(termination,version(plan),"TERMINATE","2026-09","2026-09",BigDecimal.ZERO,"EXP","PREPAID",allocations(),"真实提前终止，退款另走资金审核")));
  as(director,"finance.review");call(()->service.reviewRevision(termination,new Confirm(UUID.randomUUID(),0,true,"独立确认提前终止")));
  assertThat(jdbc.queryForObject("select status from far_plan where id=?",String.class,plan)).isEqualTo("TERMINATED");
  assertThat(call(()->service.detail(plan)).get("pendingRefundNotCash")).isEqualTo("66.68");
  assertThat(jdbc.queryForObject("select count(*) from fo_action where document_id=? and action='REFUND'",Integer.class,source)).isZero();
  assertThatThrownBy(()->run(plan,"2026-09")).hasMessageContaining("终止");
 }
 @Test void approvedOpeningPrepaidRestoresRemainingScheduleNotAcquisitionOrCash(){
  UUID batch=UUID.randomUUID(),opening=UUID.randomUUID();
  jdbc.update("insert into fm_batch(id,tenant_id,book_id,kind,original_name,source_reference,file_sha256,mapping,status,created_by) values(?,?,?,'PREPAID','真实金蝶期初.csv','核对期初',?,'{}','ACTIVE',?)",batch,T,book,"a".repeat(64),owner);
  jdbc.update("insert into fm_subledger(id,tenant_id,book_id,batch_id,kind,source_key,account_code,name,direction,business_date,original_amount,remaining_amount,accumulated_amount,remaining_months,benefit_end,status) values(?,?,?,?,'PREPAID',?,'PREPAID','已核对剩余受益期','DEBIT',?,120,100.01,19.99,3,'2026-10-31','ACTIVE')",opening,T,book,batch,opening.toString(),START);
  UUID plan=plan(null,opening,"PREPAID","100.01");run(plan,"2026-08");
  assertThat(jdbc.queryForObject("select opening_subledger_id from far_plan where id=?",UUID.class,plan)).isEqualTo(opening);
  assertThat(jdbc.queryForObject("select count(*) from fo_action where tenant_id=? and actor_id=?",Integer.class,T,owner)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from fa_source_event where source_type='FAR_ACCRUAL' and metadata->>'planId'=?",Integer.class,plan.toString())).isOne();
  assertThat(jdbc.queryForObject("select remaining_amount from fm_subledger where id=?",BigDecimal.class,opening)).isEqualByComparingTo("100.01");
 }
 @Test void sameMonthConcurrentReplayCommitsOneImmutableRun(){
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01"),request=UUID.randomUUID();int version=version(plan);
  var in=new Run(request,"2026-08",version);var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{as(owner,"*");return call(()->service.run(plan,in));});var b=pool.submit(()->{as(owner,"*");return call(()->service.run(plan,in));});a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
  assertThat(jdbc.queryForObject("select count(*) from far_run where plan_id=?",Integer.class,plan)).isOne();
  as(owner,"*");assertThatThrownBy(()->call(()->service.run(plan,new Run(request,"2026-09",version)))).hasMessageContaining("不同内容");
 }
 @Test void sourceDriftLockPeriodAndTenantOrSameStoreUnassignedReviewerAreDenied(){
  UUID source=source("PREPAYMENT","100.01","100.01"),policy=createPolicy("PREPAID");publish(policy);UUID pending=UUID.randomUUID();as(owner,"*");call(()->service.createPlan(planInput(pending,source,null,policy,"100.01")));
  jdbc.update("update fo_document set amount=101 where id=?",source);as(director,"finance.review");assertThatThrownBy(()->call(()->service.reviewRevision(pending,new Confirm(UUID.randomUUID(),0,true,"须先核对变化")))).hasMessageContaining("来源已发生变化");
  as(reviewer,"finance.review");assertThat((List<?>)call(()->service.plans(book)).get("items")).isEmpty();assertThatThrownBy(()->call(()->service.detail(pending))).hasMessageContaining("授权范围");
  TenantContext.set(new TenantPrincipal(T,director,"TEST",Set.of("TEST"),Set.of("finance.review"),Set.of(UUID.randomUUID()),Set.of(),false,UUID.randomUUID()));assertThatThrownBy(()->call(()->service.detail(pending))).isInstanceOf(AccessDeniedException.class);
  as(owner,"*");call(()->{jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("select count(*) from far_plan where id=?",Integer.class,pending)).isZero();return null;});
 }
 @Test void lockedMonthCannotGenerateAndSchedulerStateDoesNotClaimEnabled(){
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01");
  jdbc.update("insert into fa_period(tenant_id,book_id,month,status) values(?,?,?,'CLOSED') on conflict(tenant_id,book_id,month) do update set status='CLOSED'",T,book,START);
  assertThatThrownBy(()->run(plan,"2026-08")).hasMessageContaining("已关账");
  as(owner,"*");assertThat(call(()->service.context()).get("schedulerEnabled")).isEqualTo(false);
  call(()->{service.scheduleIssue(T,plan,START,"原凭证待复核");service.scheduleIssue(T,plan,START,"仍待实际复核");return null;});
  assertThat(jdbc.queryForObject("select attempts from far_schedule_issue where plan_id=?",Integer.class,plan)).isEqualTo(2);
  assertThat(call(()->service.nextScheduledMonth(T,plan,LocalDate.of(2026,10,24)))).isEqualTo("2026-08");
 }
 @Test void departmentAllocationPreservesExactTailAndNeverUsesBinaryFloatingMoney(){
  var list=FinanceAccrualControlService.allocate(List.of(new Allocation(UUID.randomUUID(),BigDecimal.ONE),new Allocation(UUID.randomUUID(),BigDecimal.ONE),new Allocation(UUID.randomUUID(),BigDecimal.ONE)),new BigDecimal("100.01"));
  assertThat(list.stream().map(r->new BigDecimal(r.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("100.01");
  assertThat(list.stream().map(r->r.get("amount").toString()).toList()).containsExactly("33.34","33.33","33.34");
 }


 @Test void realMonthlyAccrualEntersPublishedExpenseBudgetWithoutRepeatingPurchaseOrCashBudget(){
  UUID budget=UUID.randomUUID();as(owner,"finance.configure");call(()->control.savePolicy(new FinanceControlModels.Policy(budget,hotel,"BUDGET","period-expense","8月本期费用预算",START,START.plusMonths(1).minusDays(1),Map.of("month","2026-08","categoryCode","PERIOD_COST","categoryId",budgetCategory,"budgetKind","EXPENSE","amount","30.00","control","WARN"))));as(director,"finance.configure");call(()->control.publishPolicy(budget,new FinanceControlModels.Version(0,"独立确认预算"),false));
  UUID source=source("PREPAYMENT","100.01","100.01"),plan=plan(source,null,"PREPAID","100.01"),aug=run(plan,"2026-08");
  assertThat(jdbc.queryForObject("select sum(actual) from fbu_flow where source_kind='ACCRUAL' and source_id=?",BigDecimal.class,aug)).isEqualByComparingTo("33.33");
  assertThat(jdbc.queryForObject("select category_code from fbu_flow where source_id=?",String.class,aug)).isEqualTo("PERIOD_COST");
  assertThat(jdbc.queryForObject("select count(*) from fc_budget_usage where source_id=?",Integer.class,source)).as("prepayment purchase is not expense actual again").isZero();
  assertThat(jdbc.queryForObject("select count(*) from fc_event where source_id=? and event='BUDGET_ACTUAL_WARNING'",Integer.class,aug)).isOne();
 }
 private UUID createPolicy(String kind){UUID policy=UUID.randomUUID();as(owner,"*");call(()->service.createPolicy(new Policy(policy,book,"独立确认实际政策",kind,"经核实的受益期/合同条款及直线法")));return policy;}
 @Test void contractCommitmentTransfersOnlyRealMonthlyExpenseAndReplayNeverReleasesTwice(){
  UUID budget=UUID.randomUUID();as(owner,"finance.configure");call(()->control.savePolicy(new FinanceControlModels.Policy(budget,hotel,"BUDGET","lease-expense","原合同费用承诺",START,START.plusMonths(1).minusDays(1),Map.of("month","2026-08","categoryCode","PERIOD_COST","categoryId",budgetCategory,"budgetKind","EXPENSE","amount","100.01","control","WARN"))));as(director,"finance.configure");call(()->control.publishPolicy(budget,new FinanceControlModels.Version(0,"独立确认原费用承诺"),false));
  UUID source=source("CONTRACT","100.01","0"),plan=plan(source,null,"LEASE_ACCRUAL","100.01");jdbc.update("insert into fc_budget_usage(tenant_id,policy_id,source_kind,source_id,amount,state,snapshot) values(?,?,'OPERATION',?,100.01,'COMMITTED','{\"originalApproval\":true}')",T,budget,source);
  UUID aug=run(plan,"2026-08");assertThat(jdbc.queryForObject("select amount from fc_budget_usage where source_id=?",BigDecimal.class,source)).isEqualByComparingTo("66.68");
  var named=new NamedParameterJdbcTemplate(pg.getPostgresDatabase());as(owner,"*");call(()->{new FinanceBudgetBridge(named).accrual(TenantContext.require(),aug);return null;});assertThat(jdbc.queryForObject("select amount from fc_budget_usage where source_id=?",BigDecimal.class,source)).isEqualByComparingTo("66.68");assertThat(jdbc.queryForObject("select count(*) from fc_event where source_id=? and event='BUDGET_ACCRUAL_TRANSFER'",Integer.class,aug)).isOne();
  post(aug);UUID sep=run(plan,"2026-09");assertThat(jdbc.queryForObject("select amount from fc_budget_usage where source_id=?",BigDecimal.class,source)).isEqualByComparingTo("33.35");assertThat(jdbc.queryForObject("select month from fbu_flow where source_id=?",LocalDate.class,sep)).isEqualTo(START.plusMonths(1));assertThat(jdbc.queryForObject("select spec->>'month' from fc_policy where id=?",String.class,budget)).isEqualTo("2026-08");
 }
 @Test void oneDepartmentMayAllocateRealMonthlyCostAcrossTwoExplicitProjectsWithoutLossOrDuplicateFlow(){
  UUID a=source("CONTRACT","10000.00","0"),b=source("CONTRACT","10000.00","0"),original=source("PREPAYMENT","100.01","100.01"),policy=createPolicy("PREPAID");publish(policy);UUID plan=UUID.randomUUID();as(owner,"*");call(()->service.createPlan(new Plan(plan,book,policy,original,null,"同实际部门两个真实项目受益","2026-08","2026-10",new BigDecimal("100.01"),"EXP","PREPAID",List.of(new Allocation(hotel,BigDecimal.ONE,"PERIOD_COST","CONTRACT",a),new Allocation(hotel,BigDecimal.ONE,"PERIOD_COST","CONTRACT",b)),"原已批准合同锚点与真实受益分配，不从费用科目猜项目")));as(director,"finance.review","finance.read");call(()->service.reviewRevision(plan,new Confirm(UUID.randomUUID(),0,true,"独立核对两个真实项目")));UUID run=run(plan,"2026-08");
  assertThat(jdbc.queryForObject("select count(*) from fbu_flow where source_kind='ACCRUAL' and source_id=?",Integer.class,run)).isEqualTo(2);assertThat(jdbc.queryForObject("select sum(actual) from fbu_flow where source_id=?",BigDecimal.class,run)).isEqualByComparingTo("33.33");assertThat(jdbc.queryForList("select project_key from fbu_flow where source_id=? order by project_key",String.class,run)).containsExactlyInAnyOrder("CONTRACT:"+a,"CONTRACT:"+b);assertThat(jdbc.queryForObject("select count(*) from fa_voucher where id=?",Integer.class,run)).isOne();
  assertThat(jdbc.queryForObject("select count(*) from fa_voucher_line where voucher_id=?",Integer.class,run)).isEqualTo(4);assertThat(jdbc.queryForObject("select sum(debit) from fa_voucher_line where voucher_id=?",BigDecimal.class,run)).isEqualByComparingTo("33.33");assertThat(jdbc.queryForObject("select sum(credit) from fa_voucher_line where voucher_id=?",BigDecimal.class,run)).isEqualByComparingTo("33.33");assertThat(jdbc.queryForList("select distinct auxiliary#>>'{project,key}' from fa_voucher_line where voucher_id=?",String.class,run)).containsExactlyInAnyOrder("CONTRACT:"+a,"CONTRACT:"+b);assertThat(jdbc.queryForObject("select count(*) from fa_voucher_line where voucher_id=? and auxiliary#>>'{department,id}'=?",Integer.class,run,hotel.toString())).isEqualTo(4);assertThat(jdbc.queryForObject("select jsonb_array_length(auxiliary_snapshot->'allocations') from fa_source_event where voucher_id=?",Integer.class,run)).isEqualTo(2);
 }
 private void publish(UUID policy){as(director,"finance.review");call(()->service.publishPolicy(policy,new Confirm(UUID.randomUUID(),0,true,"配置总监独立发布")));}
 private UUID plan(UUID source,UUID opening,String kind,String basis){UUID policy=createPolicy(kind);publish(policy);UUID plan=UUID.randomUUID();as(owner,"*");call(()->service.createPlan(planInput(plan,source,opening,policy,basis)));as(director,"finance.review");call(()->service.reviewRevision(plan,new Confirm(UUID.randomUUID(),0,true,"独立确认月度计划")));return plan;}
 private Plan planInput(UUID id,UUID source,UUID opening,UUID policy,String basis){new TenantDatabaseContext(jdbc,true).apply(TenantContext.require().tenantId());String credit=jdbc.queryForObject("select kind from far_policy where id=?",String.class,policy).equals("LEASE_ACCRUAL")?"AP":"PREPAID";return new Plan(id,book,policy,source,opening,"真实受益期", "2026-08","2026-10",new BigDecimal(basis),"EXP",credit,allocations(),"不重复采购或付款");}
 private Revision revision(UUID plan,String kind,String effective,String basis){return new Revision(UUID.randomUUID(),version(plan),kind,effective,"2026-10",new BigDecimal(basis),"EXP","PREPAID",allocations(),"受控变更");}
 private List<Allocation> allocations(){return List.of(new Allocation(hotel,BigDecimal.ONE,"PERIOD_COST"));}
 private UUID run(UUID plan,String month){as(owner,"*");UUID run=UUID.randomUUID();call(()->service.run(plan,new Run(run,month,version(plan))));return run;}
 private void post(UUID voucher){as(reviewer,"*");call(()->accounting.review(voucher,0));call(()->accounting.post(voucher,1));}
 private int version(UUID plan){return jdbc.queryForObject("select version from far_plan where id=?",Integer.class,plan);}
 private UUID source(String type,String amount,String paid){UUID source=UUID.randomUUID();String details=type.equals("CONTRACT")?"{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-10-31\"}":"{\"benefitStart\":\"2026-08-01\",\"benefitEnd\":\"2026-10-31\",\"recognitionMethod\":\"STRAIGHT_LINE\"}";
  jdbc.update("insert into fo_document(id,tenant_id,hotel_id,company_id,applicant_id,type,title,business_date,amount,details,lines,status) values(?,?,?,?,?,?, '真实批准原单',?,?,cast(? as jsonb),'[]','APPROVED')",source,T,hotel,company,owner,type,START,new BigDecimal(amount),details);
  if(new BigDecimal(paid).signum()>0)jdbc.update("insert into fo_action(tenant_id,document_id,actor_id,action,amount,occurred_on,request_id,reference) values(?,?,?,'PAYMENT',?,?,?,'已确认实际付款')",T,source,owner,new BigDecimal(paid),START,UUID.randomUUID());return source;
 }
 private UUID user(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'摊销测试账号')",id,T,id.toString());return id;}
 private void as(UUID actor,String...permissions){TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 private static <X>X call(Supplier<X> work){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");new TenantDatabaseContext(jdbc,true).apply(TenantContext.require().tenantId());return work.get();});}
}
