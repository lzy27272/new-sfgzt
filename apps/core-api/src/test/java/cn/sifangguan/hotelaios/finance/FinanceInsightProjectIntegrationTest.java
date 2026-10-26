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
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Actual V107 project references and immutable real source rows, not client-provided report facts. */
class FinanceInsightProjectIntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static NamedParameterJdbcTemplate named;static TransactionTemplate tx;static FinanceInsightService insights;
 static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");UUID hotel,company,book,actor,reviewer;
 @BeforeAll static void start()throws Exception{pg=EmbeddedPostgres.builder().start();var ds=pg.getPostgresDatabase();jdbc=new JdbcTemplate(ds);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("107").load().migrate();named=new NamedParameterJdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);var db=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();var accounting=new FinanceAccountingService(named,db,access,new AuditWriter(named,e->{}),JSON);insights=new FinanceInsightService(named,db,access,new AuditWriter(named,e->{}),accounting,new FinanceAnalysisGateway("","","",false),manager);}
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed(){hotel=UUID.randomUUID();company=UUID.randomUUID();book=UUID.randomUUID();actor=account();reviewer=account();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'项目预算验收门店','HOTEL')",hotel,T,hotel.toString());jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'项目预算法人',?)",company,T,company.toString().replace("-",""));jdbc.update("insert into fa_book(id,tenant_id,hotel_id,company_id,name,start_month) values(?,?,?,?,'项目来源验收账套','2026-10-01')",book,T,hotel,company);TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of("finance.read","finance.configure"),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 @Test void sameCategoryAndDepartmentNeverMergeDifferentRealProjectsOrDifferentFrozenProjectRevisions(){
  UUID first=contract("合同项目一"),second=contract("合同项目二");var firstSnapshot=project(first);flow("CONTRACT",first,firstSnapshot,"10.00");flow("CONTRACT",second,project(second),"20.00");
  jdbc.update("update fo_document set title='合同项目一受控新版本',version=version+1 where id=?",first);flow("CONTRACT",first,project(first),"3.00");flow("NONE",null,Map.of(),"5.00");
  var data=call(()->insights.dashboard("2026-10",hotel));var rows=rows(data.get("budgetBusinessSources"));assertThat(rows).hasSize(4);assertThat(rows.stream().map(r->amount(r.get("actual"))).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("38.00");
  var sameProject=rows.stream().filter(r->("CONTRACT:"+first).equals(r.get("projectKey"))).toList();assertThat(sameProject).hasSize(2);assertThat(sameProject.stream().map(r->r.get("projectSourceVersion")).distinct().count()).isEqualTo(2);assertThat(sameProject).allSatisfy(r->assertThat(r).containsEntry("projectState","FROZEN_REAL_PROJECT_REFERENCE"));assertThat(rows.stream().filter(r->r.get("projectKey").equals("NONE"))).singleElement().satisfies(r->assertThat(r).containsEntry("actual","5.00").containsEntry("projectState","UNASSIGNED_NOT_REVIEWED"));
  var dataset=FinanceInsightService.questionDataset(data,"BUDGET");assertThat(rows(dataset.get("facts")).stream().filter(f->f.get("id").toString().startsWith("BUDGET_SOURCE:"))).hasSize(16).allSatisfy(f->assertThat(f).containsKeys("projectKey","projectState","projectSourceVersion"));assertThat(json(dataset)).doesNotContain("合同项目一受控新版本","bank_account");
 }
 @Test void frozenProjectSourcesAndXlsxAndQuestionsStayTheSameVersionWhenNewActualCostArrives()throws Exception{
  UUID contract=contract("已核合同");flow("CONTRACT",contract,project(contract),"10.00");var first=call(()->insights.dashboard("2026-10",hotel));var frozen=call(()->insights.freeze(new FinanceInsightModels.Generate(UUID.randomUUID(),"2026-10",hotel,first.get("reportVersion").toString())));UUID snapshot=id(frozen.get("snapshotId"));flow("CONTRACT",contract,project(contract),"2.00");var latest=call(()->insights.dashboard("2026-10",hotel));assertThat(latest.get("reportVersion")).isNotEqualTo(first.get("reportVersion"));var old=call(()->insights.snapshot(snapshot));assertThat(rows(old.get("budgetBusinessSources"))).singleElement().satisfies(r->assertThat(amount(r.get("actual"))).isEqualByComparingTo("10.00"));
  assertThat(FinanceInsightService.questionDataset(old,"BUDGET").get("reportVersion")).isEqualTo(first.get("reportVersion"));var exported=call(()->insights.export(null,null,snapshot,first.get("reportVersion").toString(),"xlsx"));try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exported.content()))){var sheet=workbook.getSheet("真实预算业务来源");var headers=new ArrayList<String>();for(var cell:sheet.getRow(0))headers.add(cell.getStringCellValue());assertThat(headers).contains("projectKey","projectSourceVersion","projectSnapshot","actual");int key=headers.indexOf("projectKey"),amount=headers.indexOf("actual");assertThat(sheet.getRow(1).getCell(key).getStringCellValue()).isEqualTo("CONTRACT:"+contract);assertThat(sheet.getRow(1).getCell(amount).getNumericCellValue()).isEqualTo(10.00);assertThat(sheet.getLastRowNum()).isOne();}
 }
 @Test void explicitNoProjectIsDistinctFromHistoricalEmptyMappingAndCannotExpandTheReadersScope(){
  var explicit=new FinanceProjectDimensions(named,new AccessPolicy());var noProject=call(()->explicit.freeze(TenantContext.require(),hotel,company,new FinanceProjectDimensions.ProjectRef("NONE",null)));flow("NONE",null,noProject,"7.00");flow("NONE",null,Map.of(),"8.00");var data=call(()->insights.dashboard("2026-10",hotel));assertThat(rows(data.get("budgetBusinessSources"))).extracting(r->r.get("projectState")).containsExactlyInAnyOrder("EXPLICIT_NO_PROJECT","UNASSIGNED_NOT_REVIEWED");TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of("finance.read"),Set.of(UUID.randomUUID()),Set.of(),false,UUID.randomUUID()));assertThatThrownBy(()->call(()->insights.dashboard("2026-10",hotel))).isInstanceOf(AccessDeniedException.class);assertThat(rows(call(()->insights.dashboard("2026-10",null)).get("budgetBusinessSources"))).isEmpty();
 }
 @Test void retiredSpecificProjectPolicyKeepsItsHistoricalIssueButNoLongerBlocksCurrentCoverage(){
  UUID contract=contract("历史未归项目控制"),policy=UUID.randomUUID();var spec=new LinkedHashMap<String,Object>();spec.put("month","2026-10");spec.put("categoryCode","COST-REAL");spec.put("budgetKind","EXPENSE");spec.put("amount","100.00");spec.put("control","BLOCK");spec.put("projectKind","CONTRACT");spec.put("projectId",contract);spec.put("projectKey","CONTRACT:"+contract);spec.put("projectSnapshot",project(contract));
  jdbc.update("insert into fc_policy(id,tenant_id,hotel_id,kind,code,title,effective_from,spec,spec_hash,status,created_by,published_by) values(?,?,?,'BUDGET',?,'项目预算当前阻断','2026-10-01',?::jsonb,?,'PUBLISHED',?,?)",policy,T,hotel,policy.toString(),json(spec),FinanceService.hash(json(spec)),actor,reviewer);
  jdbc.update("insert into fbu_issue(tenant_id,source_kind,source_id,hotel_id,message,status) values(?,'PROJECT_HISTORY',?,?,'同月存在未确证的历史项目，需独立调整总预算','OPEN')",T,policy,hotel);assertThat(call(()->insights.dashboard("2026-10",hotel))).containsEntry("budgetSourceCoverage","INCOMPLETE_OPEN_SOURCE_CLASSIFICATION_ISSUES");
  jdbc.update("update fc_policy set status='RETIRED',version=version+1 where id=?",policy);var after=call(()->insights.dashboard("2026-10",hotel));assertThat(after).containsEntry("budgetSourceCoverage","REGISTERED_SOURCES_WITHOUT_OPEN_ISSUES");assertThat(rows(after.get("budgetSourceIssues"))).isEmpty();assertThat(jdbc.queryForObject("select status from fbu_issue where tenant_id=? and source_id=?",String.class,T,policy)).isEqualTo("OPEN");
 }
 private UUID contract(String title){UUID id=UUID.randomUUID();jdbc.update("insert into fo_document(id,tenant_id,hotel_id,company_id,applicant_id,type,title,business_date,amount,details,lines,status,approval_route,cashier_account_id) values(?,?,?,?,?,'CONTRACT',?,'2026-10-01',100,'{}','[]','APPROVED','[]',?)",id,T,hotel,company,actor,title,reviewer);return id;}
 private Map<String,Object> project(UUID id){return call(()->new FinanceProjectDimensions(named,new AccessPolicy()).freeze(TenantContext.require(),hotel,company,new FinanceProjectDimensions.ProjectRef("CONTRACT",id)));}
 private void flow(String kind,UUID project,Map<String,Object> snapshot,String amount){UUID id=UUID.randomUUID();jdbc.update("insert into fbu_flow(id,tenant_id,hotel_id,company_id,source_kind,source_id,line_id,document_id,month,category_code,basis_kind,actual,source_version,source_hash,snapshot,project_kind,project_id,project_key,project_snapshot) values(?,?,?,?,'INVENTORY',?,?,?,'2026-10-01','COST-REAL','EXPENSE',?::numeric,0,?,'{}',?,?,?,?::jsonb)",id,T,hotel,company,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),amount,FinanceService.hash(id.toString()),kind,project,kind.equals("NONE")?"NONE":kind+":"+project,json(snapshot));}
 private UUID account(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'项目验收账号')",id,T,id.toString());return id;}
 private static <R> R call(Supplier<R> action){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,T.toString());return action.get();});}
}
