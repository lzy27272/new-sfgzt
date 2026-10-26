package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class FinanceAuxiliaryIntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static TransactionTemplate tx;static FinanceAccountingService service;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");static final LocalDate DAY=LocalDate.of(2026,10,24);
 UUID hotel,other,dept,company,book,maker,reviewer;
 @BeforeAll static void start() throws Exception {pg=EmbeddedPostgres.builder().start();var ds=pg.getPostgresDatabase();jdbc=new JdbcTemplate(ds);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("111").load().migrate();var named=new NamedParameterJdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));service=new FinanceAccountingService(named,new TenantDatabaseContext(jdbc,true),new AccessPolicy(),new AuditWriter(named,e->{}),new ObjectMapper().findAndRegisterModules());}
 @AfterAll static void stop() throws Exception {if(pg!=null)pg.close();}@AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){maker=user();reviewer=user();hotel=org("HOTEL",null);other=org("HOTEL",null);dept=org("DEPARTMENT",hotel);company=UUID.randomUUID();book=UUID.randomUUID();jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'实际辅助核算主体',?)",company,T,company.toString().replace("-",""));jdbc.update("insert into fa_book(id,tenant_id,hotel_id,company_id,name,start_month) values(?,?,?,?,'实际维度账套','2026-10-01')",book,T,hotel,company);jdbc.update("insert into fa_period(tenant_id,book_id,month) values(?,?,'2026-10-01')",T,book);for(String code:List.of("6602","2202"))jdbc.update("insert into fa_account(tenant_id,book_id,code,name,category,normal_side,report_line,required_dimensions) values(?,?,?,? ,?,?,'真实报表行',?::jsonb)",T,book,code,code,code.equals("6602")?"EXPENSE":"LIABILITY",code.equals("6602")?"DEBIT":"CREDIT",code.equals("6602")?"[\"DEPARTMENT\"]":"[]");as(maker,"finance.accounting.read","finance.accounting.write","finance.review");}
 @Test void actualManualDepartmentIsFrozenQueryableAndPreservedByIndependentReversal(){
  var input=voucher(Map.of("departmentId",dept.toString()));UUID id=input.requestId();call(()->service.createVoucher(input));assertThat(call(()->service.createVoucher(input))).containsEntry("id",id);
  as(reviewer,"finance.accounting.review","finance.accounting.post","finance.accounting.read");call(()->service.review(id,0));call(()->service.post(id,1));var result=call(()->service.auxiliaryLedger(book,"2026-10",dept,null,null,null));assertThat(result).containsEntry("debit","10.01");assertThat(FinanceV2Support.rows(result.get("items"))).hasSize(2);
  String original=jdbc.queryForObject("select auxiliary#>>'{department,name}' from fa_voucher_line where voucher_id=? and line_no=1",String.class,id);jdbc.update("update org_unit set name='真实改名不重写历史' where id=?",dept);assertThat(jdbc.queryForObject("select auxiliary#>>'{department,name}' from fa_voucher_line where voucher_id=? and line_no=1",String.class,id)).isEqualTo(original);
  UUID reverse=UUID.randomUUID();call(()->service.reverse(id,new FinanceAccountingModels.Reverse(reverse,2,DAY,"真实核对后的独立调整")));assertThat(jdbc.queryForObject("select auxiliary from fa_voucher_line where voucher_id=? and line_no=1",String.class,reverse)).isEqualTo(jdbc.queryForObject("select auxiliary from fa_voucher_line where voucher_id=? and line_no=1",String.class,id));
  assertThatThrownBy(()->jdbc.update("update fa_voucher_line set auxiliary='{}' where voucher_id=?",id)).isInstanceOf(RuntimeException.class);
 }
 @Test void foreignDepartmentFakeNamesAndMissingRequiredDimensionsCannotPass(){
  assertThatThrownBy(()->call(()->service.createVoucher(voucher(Map.of("departmentId",other.toString()))))).hasMessageContaining("部门");
  assertThatThrownBy(()->call(()->service.createVoucher(voucher(Map.of("department",Map.of("id",dept,"name","伪造")))))).hasMessageContaining("伪造");
  assertThatThrownBy(()->call(()->service.createVoucher(voucher(Map.of())))).hasMessageContaining("真实辅助维度");
  as(maker,"finance.accounting.read");assertThat(call(()->service.auxiliaryContext(book))).containsKeys("departments","employees","parties","projects");
  TenantContext.set(new TenantPrincipal(T,maker,"TEST",Set.of("TEST"),Set.of("finance.accounting.read"),Set.of(other),Set.of(),false,UUID.randomUUID()));assertThatThrownBy(()->call(()->service.auxiliaryLedger(book,"2026-10",null,null,null,null))).isInstanceOf(RuntimeException.class);
 }
 @Test void realOperationAllocationsFlowToBalancedSourceLinesWithoutNameGuessing(){
  UUID source=UUID.randomUUID();var lines=List.of(Map.of("quantity","1","unitPrice","6.01","departmentId",dept.toString()),Map.of("quantity","1","unitPrice","4.00","departmentId",hotel.toString()));jdbc.update("insert into fo_document(id,tenant_id,hotel_id,company_id,applicant_id,type,title,business_date,amount,details,lines,status,approval_route) values(?,?,?,?,?,'PAYABLE','真实两部门费用','2026-10-24',10.01,'{}',?::jsonb,'APPROVED','[]')",source,T,hotel,company,maker,FinanceV2Support.json(lines));
  jdbc.update("insert into fa_posting_rule(tenant_id,book_id,source_type,event_key,debit_account_id,credit_account_id,confirmed_by) select ?,?,'FO_PAYABLE','RECOGNITION',d.id,c.id,? from fa_account d join fa_account c on c.book_id=d.book_id where d.book_id=? and d.code='6602' and c.code='2202'",T,book,reviewer,book);
  var event=call(()->service.recordSourceEvent(hotel,"FO_PAYABLE",source,"RECOGNITION",DAY,DAY,new BigDecimal("10.01"),"真实来源",Map.of("documentId",source.toString(),"companyId",company.toString())));assertThat(event).containsEntry("status","DRAFT");UUID id=(UUID)event.get("voucherId");assertThat(jdbc.queryForObject("select count(*) from fa_voucher_line where voucher_id=?",Integer.class,id)).isEqualTo(4);assertThat(jdbc.queryForObject("select sum(debit-credit) from fa_voucher_line where voucher_id=?",BigDecimal.class,id)).isEqualByComparingTo("0");
  assertThatThrownBy(()->jdbc.update("update fa_source_event set auxiliary_snapshot='{}' where id=?",event.get("id"))).hasMessageContaining("immutable");
 }
 FinanceAccountingModels.Voucher voucher(Map<String,Object> aux){return new FinanceAccountingModels.Voucher(UUID.randomUUID(),book,DAY,DAY,"真实辅助核算",List.of(new FinanceAccountingModels.Line("6602",new BigDecimal("10.01"),BigDecimal.ZERO,"真实费用",null,aux),new FinanceAccountingModels.Line("2202",BigDecimal.ZERO,new BigDecimal("10.01"),"真实应付",null,aux)));}
 UUID org(String kind,UUID parent){UUID id=UUID.randomUUID();jdbc.update("insert into org_unit(id,tenant_id,parent_id,code,name,unit_type) values(?,?,?,?,?,?)",id,T,parent,id.toString(),"真实"+kind,kind);jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",T,id,id);if(parent!=null)jdbc.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) select tenant_id,ancestor_id,?,depth+1 from org_unit_closure where tenant_id=? and descendant_id=?",id,T,parent);return id;}
 UUID user(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'实际会计账号')",id,T,id.toString());return id;}
 void as(UUID actor,String...permissions){TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 <V> V call(Supplier<V> action){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");return action.get();});}
}
