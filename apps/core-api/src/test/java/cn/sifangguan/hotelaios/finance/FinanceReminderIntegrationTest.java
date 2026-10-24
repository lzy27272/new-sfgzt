package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** App-role PG and real effective ordinary assignment identities; no wildcard principals or mocked queue pages. */
class FinanceReminderIntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static TransactionTemplate tx;static FinanceReminderService reminders;static FinanceService finance;static EffectiveIdentityService identities;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");UUID hotel,other,company,creator;User reviewer,worker,foreign;
 static FinanceDeliveryEligibilityService deliveryEligibility;
 record User(UUID account,UUID assignment,UUID role){}
 @BeforeAll static void start()throws Exception{pg=EmbeddedPostgres.builder().start();var ds=pg.getPostgresDatabase();jdbc=new JdbcTemplate(ds);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("108").load().migrate();var named=new NamedParameterJdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);var db=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();var audit=new AuditWriter(named,e->{});var accounting=new FinanceAccountingService(named,db,access,audit,FinanceV2Support.JSON);finance=new FinanceService(named,db,access,FinanceV2Support.JSON,audit,accounting);reminders=new FinanceReminderService(named,db,finance);identities=new EffectiveIdentityService(named,db);deliveryEligibility=new FinanceDeliveryEligibilityService(named,db,identities,finance);}
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}@AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){hotel=hotel();other=hotel();company=UUID.randomUUID();creator=account();jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'提醒验收公司',?)",company,T,company.toString().replace("-",""));reviewer=user(hotel,"finance.review");worker=user(hotel,"finance.submit");foreign=user(other,"finance.review");}
 @Test void allCurrentNodesAcrossPagesAndPastDaysAreRemindedOncePerDayWithoutBroadeningScope(){var expected=new HashSet<UUID>();for(int n=0;n<61;n++)expected.add(revenue(hotel,reviewer.account()));UUID foreignSource=revenue(other,reviewer.account()),otherReviewer=revenue(hotel,foreign.account());var identity=as(reviewer);assertThat(identity.permissions()).containsExactly("finance.review").doesNotContain("*");assertThat(identity.hasTenantScope()).isFalse();assertThat(identity.orgScopes()).contains(hotel).doesNotContain(other);assertThat(remind(reviewer)).isEqualTo(61);assertThat(remind(reviewer)).isZero();assertThat(new HashSet<>(jdbc.queryForList("select source_id from notification where tenant_id=? and recipient_account_id=? and source_type='FINANCE_REVENUE'",UUID.class,T,reviewer.account()))).isEqualTo(expected).doesNotContain(foreignSource,otherReviewer);assertThat(jdbc.queryForObject("select count(*) from notification where recipient_account_id=? and title like '%已逾期%'",Integer.class,reviewer.account())).isEqualTo(61);}
 @Test void payrollRequiresAnActualIndependentGrantAndHandledNodesStopProducingNewVersionReminders(){UUID ordinary=revenue(hotel,reviewer.account()),salary=payroll(hotel,reviewer.account());assertThat(remind(reviewer)).isOne();assertThat(jdbc.queryForObject("select count(*) from notification where recipient_account_id=? and source_id=?",Integer.class,reviewer.account(),salary)).isZero();grant(reviewer,"finance.payroll");assertThat(as(reviewer).permissions()).contains("finance.review","finance.payroll").doesNotContain("*");assertThat(remind(reviewer)).isOne();jdbc.update("update fb_revenue_batch set status='CANCELLED',version=version+1 where id=?",ordinary);jdbc.update("update fb_payroll_batch set status='CANCELLED',version=version+1 where id=?",salary);assertThat(remind(reviewer)).isZero();assertThat(FinanceV2Support.rows(call(()->{as(reviewer);return finance.todos(0);}).get("items"))).noneSatisfy(row->assertThat(row.get("id")).isIn(ordinary,salary));}
 @Test void ownerWorkAndIndependentVerificationMoveBetweenRealPeopleWithoutSelfReviewOrRepeatedReminders(){UUID task=action();assertThat(remind(worker)).isOne();assertThat(remind(reviewer)).isZero();assertThat(jdbc.queryForObject("select source_type from notification where recipient_account_id=? and source_id=?",String.class,worker.account(),task)).isEqualTo("FINANCE_INSIGHT_ACTION");jdbc.update("update fi_action_task set status='SUBMITTED',version=1,submission_version=1,submitted_by=? where id=?",worker.account(),task);assertThat(remind(worker)).isZero();assertThat(remind(reviewer)).isOne();assertThat(remind(reviewer)).isZero();jdbc.update("update fi_action_task set status='REWORK',version=2 where id=?",task);assertThat(remind(worker)).isOne();assertThat(remind(reviewer)).isZero();assertThat(remind(foreign)).isZero();jdbc.update("delete from role_permission where tenant_id=? and role_id=?",T,worker.role());assertThat(as(worker).permissions()).doesNotContain("finance.submit","*");assertThat(remind(worker)).isZero();}
 @Test void revokedActiveAssignmentIsNotReplacedByAFabricatedAdministrativeIdentity(){revenue(hotel,reviewer.account());assertThat(remind(reviewer)).isOne();jdbc.update("update employee_position_assignment set status='INACTIVE',is_primary=false where id=?",reviewer.assignment());assertThat(call(()->identities.inspectNotificationRecipient(T,reviewer.account(),reviewer.assignment()))).isEmpty();assertThat(call(()->reminders.candidates(T,0))).noneSatisfy(row->assertThat(row.get("assignment_id")).isEqualTo(reviewer.assignment()));}
 @Test void sendTimeEligibilityUsesActualCurrentIdentityNotTheEnqueueSnapshot(){
  UUID ordinary=revenue(hotel,reviewer.account()),salary=payroll(hotel,reviewer.account()),outside=revenue(other,reviewer.account());
  assertThat(deliverable(reviewer,"FINANCE_REVENUE",ordinary)).isTrue();
  assertThat(deliverable(reviewer,"FINANCE_REVENUE",outside)).isFalse();
  assertThat(deliverable(reviewer,"FINANCE_PAYROLL",salary)).isFalse();
  grant(reviewer,"finance.payroll");assertThat(deliverable(reviewer,"FINANCE_PAYROLL",salary)).isTrue();
  jdbc.update("delete from role_permission where tenant_id=? and role_id=? and permission_id=(select id from permission where code='finance.payroll')",T,reviewer.role());
  assertThat(deliverable(reviewer,"FINANCE_PAYROLL",salary)).isFalse();
  jdbc.update("update fb_revenue_batch set status='CANCELLED',version=version+1 where id=?",ordinary);
  assertThat(deliverable(reviewer,"FINANCE_REVENUE",ordinary)).isFalse();
  UUID pending=revenue(hotel,reviewer.account());jdbc.update("update employee_position_assignment set status='INACTIVE',is_primary=false where id=?",reviewer.assignment());
  assertThat(deliverable(reviewer,"FINANCE_REVENUE",pending)).isFalse();
  assertThat(call(()->deliveryEligibility.eligible(T,reviewer.account(),null,"FINANCE_REVENUE",pending))).isFalse();
 }
 @Test void finalEligibilityScansBeyondFirstPageAndRestoresCallingContext(){
  for(int n=0;n<61;n++)revenue(hotel,reviewer.account());
  var prior=as(worker);UUID later=call(()->{as(reviewer);return (UUID)FinanceV2Support.rows(finance.todos(1).get("items")).getFirst().get("id");});
  TenantContext.set(prior);assertThat(call(()->deliveryEligibility.eligible(T,reviewer.account(),reviewer.assignment(),"FINANCE_REVENUE",later))).isTrue();
  assertThat(TenantContext.require()).isSameAs(prior);
  jdbc.update("delete from role_permission where tenant_id=? and role_id=?",T,reviewer.role());
  assertThat(deliverable(reviewer,"FINANCE_REVENUE",later)).isFalse();
 }
 @Test void narrowAccountingOrArchiveReadCanOpenEmptyWorkbenchWithoutGainingReview(){
  revenue(hotel,reviewer.account());
  for(String permission:List.of("finance.archive.read","finance.accounting.write","finance.accounting.close")){
   User narrow=user(hotel,permission);var p=as(narrow);assertThat(p.permissions()).containsExactly(permission).doesNotContain("*");
   var result=call(()->finance.todos(0));assertThat(((Number)result.get("total")).longValue()).isZero();assertThat(FinanceV2Support.rows(result.get("items"))).isEmpty();
  }
 }
 private boolean deliverable(User user,String source,UUID id){return call(()->deliveryEligibility.eligible(T,user.account(),user.assignment(),source,id));}
 private int remind(User user){return call(()->{as(user);return reminders.remindCurrent();});}
 private TenantPrincipal as(User user){return call(()->{var p=identities.resolve(T,user.account(),UUID.randomUUID(),user.assignment());TenantContext.set(p);return p;});}
 private void grant(User user,String permission){jdbc.update("insert into role_permission(tenant_id,role_id,permission_id) select ?,?,id from permission where code=? on conflict do nothing",T,user.role(),permission);}
 private User user(UUID organization,String...permissions){UUID account=account(),employee=UUID.randomUUID(),position=UUID.randomUUID(),role=UUID.randomUUID(),assignment=UUID.randomUUID();jdbc.update("insert into app_role(id,tenant_id,code,name,role_type) values(?,?,?,'普通权限验收岗位','CUSTOM')",role,T,role.toString());jdbc.update("insert into position_definition(id,tenant_id,code,name,job_family) values(?,?,?,'普通权限验收岗位','FINANCE')",position,T,position.toString());jdbc.update("insert into position_function_profile(id,tenant_id,position_id,scope_type,default_role_id) values(?,?,?,'GROUP',?)",UUID.randomUUID(),T,position,role);jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name) values(?,?,?,?,'普通提醒验收员工')",employee,T,account,employee.toString());jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,is_primary,valid_from) values(?,?,?,?,?,true,current_date-30)",assignment,T,employee,organization,position);var user=new User(account,assignment,role);for(String permission:permissions)grant(user,permission);return user;}
 private UUID hotel(){UUID id=UUID.randomUUID();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'提醒范围门店','HOTEL')",id,T,id.toString());jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",T,id,id);return id;}
 private UUID account(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'真实提醒验收账号')",id,T,id.toString());return id;}
 private UUID revenue(UUID organization,UUID reviewer){UUID id=UUID.randomUUID();jdbc.update("insert into fb_revenue_batch(id,tenant_id,hotel_id,company_id,source,channel,business_date,night_audit_date,revision,expected_count,control_total,source_reference,source_hash,created_by,reviewer_id) values(?,?,?,?,'PMS',?,'2026-09-01','2026-09-02',1,1,10,'跨日真实来源',?,?,?)",id,T,organization,company,id.toString(),"a".repeat(64),creator,reviewer);return id;}
 private UUID payroll(UUID organization,UUID reviewer){UUID id=UUID.randomUUID();jdbc.update("insert into fb_payroll_batch(id,tenant_id,hotel_id,company_id,month,source_reference,gross_amount,deduction_amount,net_amount,employer_amount,created_by,reviewer_id,cashier_id) values(?,?,?,?,'2026-09-01','真实工资待独立核实',10,0,10,0,?,?,?)",id,T,organization,company,creator,reviewer,foreign.account());return id;}
 private UUID action(){UUID snapshot=UUID.randomUUID(),report=UUID.randomUUID(),task=UUID.randomUUID();jdbc.update("insert into fi_snapshot(id,tenant_id,month,hotel_ids,report_version,facts,created_by) values(?,?,'2026-09-01',?::uuid[],?,'{}',?)",snapshot,T,"{"+hotel+"}",snapshot.toString().replace("-","").repeat(2),creator);jdbc.update("insert into fi_report(id,tenant_id,snapshot_id,title,status,created_by,last_edited_by,model,model_request_hash,published_by,published_at) values(?,?,?,'待跟进已发布报告','PUBLISHED',?,?,'fixture',?,?,now())",report,T,snapshot,creator,creator,"a".repeat(64),reviewer.account());jdbc.update("insert into fi_report_revision(tenant_id,report_id,version,content,edited_by) values(?,?,0,'{}',?)",T,report,creator);jdbc.update("insert into fi_action_task(id,tenant_id,report_id,report_revision,recommendation_index,hotel_id,company_id,owner_id,reviewer_id,title,recommendation_text,citations,source_report_version,due_date,created_by,request_hash) values(?,?,?,0,0,?,?,?,?,'实际责任人跟进事项','明确采纳建议','[]',?,'2026-09-01',?,?)",task,T,report,hotel,company,worker.account(),reviewer.account(),"a".repeat(64),creator,"b".repeat(64));return task;}
 private static <R> R call(Supplier<R> action){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");return action.get();});}
}
