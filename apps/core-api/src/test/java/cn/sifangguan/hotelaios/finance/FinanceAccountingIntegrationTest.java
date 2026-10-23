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
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.assertj.core.api.Assertions.*;

class FinanceAccountingIntegrationTest {
 static EmbeddedPostgres pg;static JdbcTemplate jdbc;static FinanceAccountingService service;static TransactionTemplate transaction;
 static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
 static final LocalDate DAY=LocalDate.of(2026,10,24),START=LocalDate.of(2026,10,1);
 UUID hotel,book,maker,reviewer,company;
 @BeforeAll static void start() throws Exception {
  pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();jdbc=new JdbcTemplate(source);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
  Flyway.configure().dataSource(source).locations("classpath:db/migration").target("89").load().migrate();
  var named=new NamedParameterJdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));service=new FinanceAccountingService(named,new TenantDatabaseContext(jdbc,true),new AccessPolicy(),new AuditWriter(named,e->{}),new ObjectMapper().findAndRegisterModules());
 }
 @AfterAll static void stop() throws Exception {if(pg!=null)pg.close();}
 @AfterEach void clear(){TenantContext.clear();}
 @BeforeEach void seed() {
  hotel=UUID.randomUUID();book=UUID.randomUUID();company=UUID.randomUUID();maker=account();reviewer=account();
  jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'核算测试','HOTEL')",hotel,TENANT,hotel.toString());
  jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'真实核对主体',?)",company,TENANT,company.toString().replace("-", ""));
  jdbc.update("insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,workflow_version,enabled) values(?,?,?,?,?,?,?,?,2,true)",TENANT,hotel,company,maker,reviewer,account(),account(),reviewer);
  as(maker,"*");call(()->service.createBook(new FinanceAccountingModels.Book(book,hotel,company,"测试账套",START)));
  account("1002","银行存款","ASSET","DEBIT",true);account("2202","应付账款","LIABILITY","CREDIT",false);account("4001","实收资本","EQUITY","CREDIT",false);account("4104","未分配利润","EQUITY","CREDIT",false);account("6001","营业收入","REVENUE","CREDIT",false);account("6602","管理费用","EXPENSE","DEBIT",false);
 }
 @Test void independentReviewPostingRealStatementsAndImmutableFacts() {
  importZero();var command=voucher("1002","6001","120.25","OPERATING");UUID id=command.requestId();call(()->service.createVoucher(command));assertThat(call(()->service.createVoucher(command))).containsEntry("id",id);
  assertThatThrownBy(()->call(()->service.review(id,0))).hasMessageContaining("不能审核");as(reviewer,"finance.accounting.review");call(()->service.review(id,0));as(maker,"finance.accounting.post");call(()->service.post(id,1));
  var report=call(()->service.reports(book,"2026-10"));assertThat(section(report,"incomeStatement")).containsEntry("revenue","120.25").containsEntry("netProfit","120.25");assertThat(section(report,"balanceSheet")).containsEntry("assets","120.25").containsEntry("unclosedProfit","120.25").containsEntry("difference","0.00");assertThat(section(report,"cashFlowStatement")).containsEntry("endingCash","120.25").containsEntry("difference","0.00");assertThat(report).containsEntry("status","INCOMPLETE");
  assertThatThrownBy(()->jdbc.update("update fa_voucher set summary='覆盖' where id=?",id)).hasMessageContaining("immutable");assertThatThrownBy(()->jdbc.update("update fa_voucher_line set debit=1 where voucher_id=?",id)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->call(()->service.post(id,1))).hasMessageContaining("版本");
 }
 @Test void imbalancedVoucherAndConflictingIdempotencyAreRejectedAtomically() {
  var invalid=new FinanceAccountingModels.Voucher(UUID.randomUUID(),book,DAY,DAY,"不平",List.of(line("6602","10","0",null),line("2202","0","9",null)));
  assertThatThrownBy(()->call(()->service.createVoucher(invalid))).hasMessageContaining("借贷不平衡");assertThat(jdbc.queryForObject("select count(*) from fa_voucher where id=?",Integer.class,invalid.requestId())).isZero();
  var valid=voucher("6602","2202","10",null);call(()->service.createVoucher(valid));var other=new FinanceAccountingModels.Voucher(valid.requestId(),book,DAY,DAY,"不同内容",valid.lines());assertThatThrownBy(()->call(()->service.createVoucher(other))).hasMessageContaining("不同内容");
 }
 @Test void sourceCaptureMissingMappingRetryAndConcurrentReplaysNeverDuplicateVoucher() throws Exception {
  UUID source=UUID.randomUUID();operationSource(source,"PAYABLE","12.50",Map.of("treatment","EXPENSE"));var first=call(()->event(source,"12.50"));assertThat(first).containsEntry("status","PENDING_MAPPING");UUID eventId=(UUID)first.get("id");
  call(()->service.saveRule(new FinanceAccountingModels.Rule(book,"FO_PAYABLE","APPROVED","6602","2202",null,true,-1)));
  var retried=call(()->service.retrySource(eventId));assertThat(retried).containsEntry("status","DRAFT");UUID voucher=(UUID)retried.get("voucherId");
  try(var pool=Executors.newFixedThreadPool(2)){var jobs=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{as(maker,"*");try{return call(()->event(source,"12.50"));}finally{TenantContext.clear();}}));for(var job:jobs)assertThat(job.get(15,TimeUnit.SECONDS)).containsEntry("voucherId",voucher);}
  assertThat(jdbc.queryForObject("select count(*) from fa_voucher where book_id=?",Integer.class,book)).isOne();assertThatThrownBy(()->call(()->event(source,"13.50"))).hasMessageContaining("内容已变更");
  as(reviewer,"*");call(()->service.review(voucher,0));call(()->service.post(voucher,1));as(maker,"*");assertThat(call(()->event(source,"12.50"))).containsEntry("status","POSTED");
 }
 @Test void openingPreviewImportReconciliationAndAtomicFailures() {
  var bad=new FinanceAccountingModels.Import(UUID.randomUUID(),book,"PREVIEW","金蝶真实文件",List.of(new FinanceAccountingModels.Opening("1002",new BigDecimal("100.12"),BigDecimal.ZERO),new FinanceAccountingModels.Opening("4001",BigDecimal.ZERO,new BigDecimal("99.12"))),Map.of("reference","已核对"));
  assertThat(call(()->service.imports(bad))).containsEntry("committed",false);assertThat(jdbc.queryForObject("select count(*) from fa_import_batch where book_id=?",Integer.class,book)).isZero();
  var commit=new FinanceAccountingModels.Import(bad.requestId(),book,"COMMIT",bad.sourceReference(),bad.balances(),bad.reconciliation());assertThatThrownBy(()->call(()->service.imports(commit))).hasMessageContaining("整批未导入");
  var valid=new FinanceAccountingModels.Import(UUID.randomUUID(),book,"COMMIT","金蝶确认文件",List.of(new FinanceAccountingModels.Opening("1002",new BigDecimal("100.12"),BigDecimal.ZERO),new FinanceAccountingModels.Opening("4001",BigDecimal.ZERO,new BigDecimal("100.12"))),Map.of("reference","一致"));call(()->service.imports(valid));assertThat(call(()->service.imports(valid))).containsEntry("replayed",true);assertThat(section(call(()->service.reports(book,"2026-10")),"balanceSheet")).containsEntry("difference","0.00");
  assertThatThrownBy(()->call(()->service.imports(new FinanceAccountingModels.Import(UUID.randomUUID(),book,"COMMIT",valid.sourceReference(),valid.balances(),valid.reconciliation())))).hasMessageContaining("不同期初");
 }
 @Test void closeChecksRealOutstandingPayableLocksPeriodAndReversalInNextPeriodKeepsSource() {
  importZero();UUID id=posted(voucher("6602","2202","50",null));as(maker,"*");
  assertThat(call(()->service.periodCheck(book,"2026-10"))).containsEntry("canClose",false);assertThatThrownBy(()->call(()->service.close(period("2026-10",0),false))).hasMessageContaining("检查未通过");
  call(()->service.saveSourceCheck(new FinanceAccountingModels.SourceCheck(book,"2026-10","ALL_APPLICABLE_SOURCES_AND_TASKS",true,1,1,"已核对全部来源、计提折旧摊销及差异；应付50仍未付",-1)));
  assertThat(call(()->service.periodCheck(book,"2026-10"))).containsEntry("canClose",true);call(()->service.close(period("2026-10",0),false));assertThat(call(()->service.reports(book,"2026-10"))).containsEntry("status","FINAL");
  assertThatThrownBy(()->call(()->service.createVoucher(voucher("6602","2202","1",null)))).hasMessageContaining("已关账");
  UUID reverseId=UUID.randomUUID();var reverse=new FinanceAccountingModels.Reverse(reverseId,2,LocalDate.of(2026,11,1),"次期红冲");var result=call(()->service.reverse(id,reverse));assertThat(result).containsEntry("status","DRAFT").containsEntry("reversesId",id);assertThat(call(()->service.reverse(id,reverse))).containsEntry("id",reverseId);
  as(reviewer,"*");call(()->service.review(reverseId,0));call(()->service.post(reverseId,1));as(maker,"*");assertThat(section(call(()->service.reports(book,"2026-11")),"balanceSheet")).containsEntry("liabilities","0.00");assertThat(section(call(()->service.reports(book,"2026-10")),"incomeStatement")).containsEntry("expense","50.00");
 }
 @Test void permissionsOrgScopeTenantRlsAndAppRoleGrantsAreEnforced() {
  as(maker,"finance.submit");assertThatThrownBy(()->call(()->service.reports(book,"2026-10"))).isInstanceOf(AccessDeniedException.class);
  as(maker,"finance.read");assertThatThrownBy(()->call(()->service.createVoucher(voucher("6602","2202","1",null)))).isInstanceOf(AccessDeniedException.class);
  TenantContext.set(new TenantPrincipal(TENANT,maker,"TEST",Set.of("TEST"),Set.of("*"),Set.of(),Set.of(),false,UUID.randomUUID()));assertThatThrownBy(()->call(()->service.reports(book,"2026-10"))).isInstanceOf(AccessDeniedException.class);
  Integer hidden=transaction.execute(s->{jdbc.execute("set local role hotel_ai_os_app");jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());return jdbc.queryForObject("select count(*) from fa_book",Integer.class);});assertThat(hidden).isZero();
  assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','fa_book','SELECT,INSERT,UPDATE')",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','fa_voucher_line','UPDATE')",Boolean.class)).isFalse();
 }
 @Test void yearCloseTransfersExactProfitAndDoesNotEraseDecemberIncome() {
  importZero();var december=LocalDate.of(2026,12,20);posted(new FinanceAccountingModels.Voucher(UUID.randomUUID(),book,december,december,"十二月收入",List.of(line("1002","80.25","0","OPERATING"),line("6001","0","80.25",null))));as(maker,"*");
  UUID closeId=UUID.randomUUID();call(()->service.yearClose(new FinanceAccountingModels.YearClose(closeId,book,2026,"4104","年度损益结转")));as(reviewer,"*");call(()->service.review(closeId,0));call(()->service.post(closeId,1));as(maker,"*");
  var report=call(()->service.reports(book,"2026-12"));assertThat(section(report,"incomeStatement")).containsEntry("revenue","80.25");assertThat(section(report,"balanceSheet")).containsEntry("equity","80.25").containsEntry("unclosedProfit","0.00").containsEntry("difference","0.00");
  var january=call(()->service.reports(book,"2027-01"));assertThat(section(january,"balanceSheet")).containsEntry("equity","80.25");assertThat(section(january,"incomeStatement")).containsEntry("revenue","0.00");
  assertThatThrownBy(()->call(()->service.reverse(closeId,new FinanceAccountingModels.Reverse(UUID.randomUUID(),2,LocalDate.of(2027,1,1),"不能将结转回冲当收入")))).hasMessageContaining("年结凭证不能使用通用红冲");assertThat(section(call(()->service.reports(book,"2026-12")),"incomeStatement")).containsEntry("revenue","80.25");
 }
 @Test void recurringPlansBlockMissingTasksAllocateExactRemainderAndNeverPostThemselves() {
  importZero();UUID source=UUID.randomUUID();operationSource(source,"PREPAYMENT","25.01",Map.of("benefitStart","2026-10-01","benefitEnd","2026-12-31","recognitionMethod","MANUAL"));operationAction(source,"PAYMENT","25.01",START);UUID plan=UUID.randomUUID();call(()->service.saveRecurringPlan(new FinanceAccountingModels.RecurringPlan(plan,book,"AMORTIZATION","FO_PREPAYMENT",source,"真实确认的三个月费用计提表","2026-10","2026-12",new BigDecimal("10.00"),new BigDecimal("25.01"),"6602","2202","每月核对计提")));
  var check=call(()->service.periodCheck(book,"2026-10"));assertThat(check.toString()).contains("RECURRING_TASKS").contains("false");var october=new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-10",0);var first=call(()->service.runRecurring(plan,october));UUID firstId=(UUID)first.get("id");assertThat(first).containsEntry("status","DRAFT").containsEntry("debitTotal","10.00");assertThat(call(()->service.runRecurring(plan,october))).containsEntry("id",firstId);
  assertThatThrownBy(()->call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-12",0)))).hasMessageContaining("按月顺序");
  call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-11",0)));var last=call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-12",0)));assertThat(last).containsEntry("debitTotal","5.01");assertThat(jdbc.queryForObject("select sum(amount) from fa_recurring_run where plan_id=?",BigDecimal.class,plan)).isEqualByComparingTo("25.01");
  assertThat(jdbc.queryForObject("select count(*) from fa_voucher where book_id=? and status='POSTED'",Integer.class,book)).isZero();
 }
 @Test void trueExcelAndChineseMultipagePdfShareScreenVersionAndPreserveTextAndMoney() throws Exception {
  importZero();var input=voucher("1002","6001","120.25","OPERATING");posted(new FinanceAccountingModels.Voucher(input.requestId(),book,DAY,DAY,"=0012345678901234567890",input.lines()));as(maker,"*");var report=call(()->service.reports(book,"2026-10"));String version=report.get("reportVersion").toString();
  var excel=call(()->service.exportReport(book,"2026-10","xlsx","ALL",version));assertThat(excel.reportVersion()).isEqualTo(version);assertThat(excel.name()).endsWith(".xlsx");
  try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(excel.content()))) {
   var sheet=workbook.getSheet("明细账");int summaryColumn=-1,debitColumn=-1;for(var cell:sheet.getRow(0)){if(cell.getStringCellValue().equals("voucherSummary"))summaryColumn=cell.getColumnIndex();if(cell.getStringCellValue().equals("debit"))debitColumn=cell.getColumnIndex();}
   assertThat(sheet.getRow(1).getCell(summaryColumn).getCellType()).isEqualTo(CellType.STRING);assertThat(sheet.getRow(1).getCell(summaryColumn).getStringCellValue()).isEqualTo("=0012345678901234567890");assertThat(sheet.getRow(1).getCell(debitColumn).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(new BigDecimal(((org.apache.poi.xssf.usermodel.XSSFCell)sheet.getRow(1).getCell(debitColumn)).getCTCell().getV())).isEqualByComparingTo("120.25");boolean versionFound=false;for(var row:workbook.getSheet("报表口径与版本"))if(row.getCell(0).getStringCellValue().equals("reportVersion")){assertThat(row.getCell(1).getStringCellValue()).isEqualTo(version);versionFound=true;}assertThat(versionFound).isTrue();
  }
  var pdf=call(()->service.exportReport(book,"2026-10","pdf","ALL",version));try(var document=Loader.loadPDF(pdf.content())){assertThat(document.getNumberOfPages()).isGreaterThan(1);var text=new PDFTextStripper().getText(document);assertThat(text).contains("财务账簿与财务报表","120.25",version,"数据完整性检查");}
  assertThatThrownBy(()->call(()->service.exportReport(book,"2026-10","xlsx","NOT_A_REPORT",version))).hasMessageContaining("未知报表");assertThatThrownBy(()->call(()->service.exportReport(book,"2026-10","csv","ALL",version))).hasMessageContaining("仅支持");assertThatThrownBy(()->call(()->service.exportReport(book,"2026-10","xlsx","ALL","stale-version"))).hasMessageContaining("已刷新");
 }
 @Test void payrollDetailsAndFrozenSnapshotsPreserveFullDataWhenCloserHasNoPayrollPermission() {
  importZero();UUID source=UUID.randomUUID();operationSource(source,"PAYROLL","1234.56",Map.of("payrollMonth","2026-10"));call(()->service.saveRule(new FinanceAccountingModels.Rule(book,"FO_PAYROLL","RECOGNITION","6602","2202",null,true,-1)));var event=call(()->service.recordSourceEvent(hotel,"FO_PAYROLL",UUID.randomUUID(),"RECOGNITION",DAY,DAY,new BigDecimal("1234.56"),"张某个人工资机密",Map.of("documentId",source.toString(),"companyId",company.toString())));UUID voucher=(UUID)event.get("voucherId");as(reviewer,"*");call(()->service.review(voucher,0));call(()->service.post(voucher,1));as(maker,"finance.accounting.close");call(()->service.saveSourceCheck(new FinanceAccountingModels.SourceCheck(book,"2026-10","ALL",true,1,1,"已核对所有来源和任务",-1)));call(()->service.close(period("2026-10",0),false));
  assertThat(jdbc.queryForObject("select close_snapshot::text from fa_period where book_id=? and month=?",String.class,book,START)).contains("张某个人工资机密");
  as(maker,"finance.accounting.read");assertThat(call(()->service.vouchers(book,"2026-10"))).containsEntry("total",0);assertThat(call(()->service.sourceEvents(book,hotel,"2026-10"))).containsEntry("total",0);assertThatThrownBy(()->call(()->service.voucher(voucher))).isInstanceOf(AccessDeniedException.class);var report=call(()->service.reports(book,"2026-10"));assertThat(section(report,"incomeStatement")).containsEntry("expense","1234.56");assertThat(report.get("detailLedger")).isEqualTo(List.of());assertThat(report.toString()).doesNotContain("张某个人工资机密");assertThat(call(()->service.reportSnapshot(book,"2026-10")).toString()).doesNotContain("张某个人工资机密");
  as(maker,"finance.accounting.read","finance.payroll");assertThat(call(()->service.voucher(voucher))).containsEntry("summary","张某个人工资机密");assertThat(call(()->service.reportSnapshot(book,"2026-10")).toString()).contains("张某个人工资机密");
 }
 @Test void recurringSourcesRejectFictionWrongEntityUnapprovedAndOverlappingBusinessAmounts() {
  assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(UUID.randomUUID(),"FO_PREPAYMENT","AMORTIZATION","10.00","25.00")))).hasMessageContaining("来源单据不存在");
  assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(UUID.randomUUID(),"MANUAL_ACCRUAL","ACCRUAL","10.00","25.00")))).hasMessageContaining("任意手工UUID");
  UUID source=UUID.randomUUID();operationSource(source,"PREPAYMENT","25.00",Map.of("benefitStart","2026-10-01","benefitEnd","2026-12-31","recognitionMethod","MANUAL"));
  assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(source,"FO_PREPAYMENT","AMORTIZATION","10.00","25.00")))).hasMessageContaining("没有实际");operationAction(source,"PAYMENT","25.00",START);operationAction(source,"AMORTIZE","5.00",START);
  assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(source,"FO_PREPAYMENT","AMORTIZATION","10.00","25.00")))).hasMessageContaining("累计超过");
  jdbc.update("update fo_document set status='DRAFT',review_index=0 where id=?",source);assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(source,"FO_PREPAYMENT","AMORTIZATION","10.00","20.00")))).hasMessageContaining("完成四段");jdbc.update("update fo_document set status='APPROVED',review_index=4,company_id=null where id=?",source);assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(source,"FO_PREPAYMENT","AMORTIZATION","10.00","20.00")))).hasMessageContaining("冻结法人");
  UUID asset=UUID.randomUUID();operationSource(asset,"ASSET","100.00",Map.of("inServiceDate","2026-10-01","usefulMonths",3,"residualAmount","10.00","method","STRAIGHT_LINE"));assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(asset,"FO_ASSET","DEPRECIATION","10.00","30.00")))).hasMessageContaining("直线计提政策");operationAction(asset,"DEPRECIATE","30.00",START);assertThatThrownBy(()->call(()->service.saveRecurringPlan(plan(asset,"FO_ASSET","DEPRECIATION","30.00","90.00")))).hasMessageContaining("累计超过");
 }
 @Test void crossEntityBooksAndSourcesNeverPostToAnUnrelatedLegalEntity() {
  UUID otherCompany=UUID.randomUUID();jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'另一法人',?)",otherCompany,TENANT,otherCompany.toString().replace("-",""));assertThatThrownBy(()->call(()->service.createBook(new FinanceAccountingModels.Book(UUID.randomUUID(),hotel,otherCompany,"错主体账套",START)))).hasMessageContaining("已发布启用");
  call(()->service.saveRule(new FinanceAccountingModels.Rule(book,"FO_PAYABLE","APPROVED","6602","2202",null,true,-1)));UUID wrong=UUID.randomUUID();operationSource(wrong,"PAYABLE","12.50",Map.of("treatment","EXPENSE"));jdbc.update("update fo_document set company_id=? where id=?",otherCompany,wrong);
  var mismatch=call(()->service.recordSourceEvent(hotel,"FO_PAYABLE",UUID.randomUUID(),"APPROVED",DAY,DAY,new BigDecimal("12.50"),"跨主体来源",Map.of("documentId",wrong.toString(),"companyId",otherCompany.toString())));assertThat(mismatch).containsEntry("status","PENDING_ENTITY_MISMATCH").containsEntry("companyId",otherCompany);assertThat(mismatch.get("voucherId")).isNull();
  UUID old=UUID.randomUUID();operationSource(old,"PAYABLE","12.50",Map.of("treatment","EXPENSE"));assertThat(call(()->service.recordSourceEvent(hotel,"FO_PAYABLE",UUID.randomUUID(),"APPROVED",DAY,DAY,new BigDecimal("12.50"),"旧事件有真实冻结主体",Map.of("documentId",old.toString())))).containsEntry("status","DRAFT");
  assertThat(call(()->service.recordSourceEvent(hotel,"FO_PAYABLE",UUID.randomUUID(),"APPROVED",DAY,DAY,new BigDecimal("12.50"),"声明主体与真实源单冲突",Map.of("documentId",old.toString(),"companyId",otherCompany.toString())))).containsEntry("status","PENDING_ENTITY_MISMATCH");
  UUID unresolved=UUID.randomUUID();operationSource(unresolved,"PAYABLE","12.50",Map.of("treatment","EXPENSE"));jdbc.update("update fo_document set company_id=null where id=?",unresolved);assertThat(call(()->service.recordSourceEvent(hotel,"FO_PAYABLE",UUID.randomUUID(),"APPROVED",DAY,DAY,new BigDecimal("12.50"),"旧事件缺主体",Map.of("documentId",unresolved.toString())))).containsEntry("status","PENDING_ENTITY_MISMATCH");
 }
 @Test void twelveMonthRoundingTransfersOriginalPurchaseBudgetExactlyOnceAndRejectsThirteenthMonth() {
  account("1601","固定资产","ASSET","DEBIT",false);UUID purchase=UUID.randomUUID(),card=UUID.randomUUID(),budget=UUID.randomUUID(),plan=UUID.randomUUID();operationSource(purchase,"PAYABLE","1200.01",Map.of("treatment","ASSET"));
  jdbc.update("insert into fo_budget(id,tenant_id,hotel_id,month,category_code,amount,control,created_by) values(?,?,?,?,'CAPITAL',1200.01,'BLOCK',?)",budget,TENANT,hotel,START,maker);jdbc.update("update fo_document set budget_id=? where id=?",budget,purchase);jdbc.update("insert into fo_budget_usage(tenant_id,budget_id,document_id,committed) values(?,?,?,1200.01)",TENANT,budget,purchase);
  call(()->service.saveRule(new FinanceAccountingModels.Rule(book,"FO_PAYABLE_ASSET","RECOGNITION","1601","2202",null,true,-1)));var recognition=call(()->service.recordSourceEvent(hotel,"FO_PAYABLE_ASSET",UUID.randomUUID(),"RECOGNITION",DAY,DAY,new BigDecimal("1200.01"),"真实资产购买",Map.of("documentId",purchase.toString(),"companyId",company.toString())));UUID recognitionVoucher=(UUID)recognition.get("voucherId");as(reviewer,"*");call(()->service.review(recognitionVoucher,0));call(()->service.post(recognitionVoucher,1));as(maker,"*");
  operationSource(card,"ASSET","1200.01",Map.of("inServiceDate","2026-10-01","usefulMonths",12,"residualAmount","0.00","method","STRAIGHT_LINE","recognizedSourceDocumentId",purchase.toString()));jdbc.update("update fo_document set recognized_source_document_id=? where id=?",purchase,card);
  call(()->service.saveRecurringPlan(new FinanceAccountingModels.RecurringPlan(plan,book,"DEPRECIATION","FO_ASSET",card,"已核对实物与原购买","2026-10","2027-09",new BigDecimal("100.00"),new BigDecimal("1200.01"),"6602","1601","按批准政策计提")));
  for(int i=0;i<12;i++){String month=java.time.YearMonth.from(START).plusMonths(i).toString();var input=new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),month,0);var result=call(()->service.runRecurring(plan,input));assertThat(result).containsEntry("debitTotal",i==11?"100.01":"100.00");assertThat(call(()->service.runRecurring(plan,input))).containsEntry("id",result.get("id"));}
  assertThat(jdbc.queryForObject("select actual from fo_budget_usage where document_id=?",BigDecimal.class,purchase)).isEqualByComparingTo("1200.01");assertThat(jdbc.queryForObject("select committed from fo_budget_usage where document_id=?",BigDecimal.class,purchase)).isEqualByComparingTo("0.00");assertThat(jdbc.queryForObject("select count(*) from fo_budget_usage where document_id=?",Integer.class,card)).isZero();assertThat(jdbc.queryForObject("select count(*) from fa_recurring_run where plan_id=?",Integer.class,plan)).isEqualTo(12);
  assertThatThrownBy(()->call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2027-10",0)))).hasMessageContaining("不在计划范围");
 }
 @Test void partiallyPaidPrepaymentCannotFreezeAnUnstablePlanAndStablePolicyWorksAfterFunding() {
  UUID source=UUID.randomUUID();operationSource(source,"PREPAYMENT","1200.00",Map.of("benefitStart","2026-10-01","benefitEnd","2027-09-30","recognitionMethod","STRAIGHT_LINE"));operationAction(source,"PAYMENT","600.00",START);UUID plan=UUID.randomUUID();var input=new FinanceAccountingModels.RecurringPlan(plan,book,"AMORTIZATION","FO_PREPAYMENT",source,"真实分次付款待摊","2026-10","2027-09",new BigDecimal("100.00"),new BigDecimal("1200.00"),"6602","2202","政策固定按批准总额");
  assertThatThrownBy(()->call(()->service.saveRecurringPlan(input))).hasMessageContaining("尚未足额净实付");assertThat(jdbc.queryForObject("select count(*) from fa_recurring_plan where source_id=?",Integer.class,source)).isZero();operationAction(source,"PAYMENT","600.00",START.plusDays(3));call(()->service.saveRecurringPlan(input));assertThat(call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-10",0)))).containsEntry("debitTotal","100.00");assertThat(call(()->service.runRecurring(plan,new FinanceAccountingModels.RecurringRun(UUID.randomUUID(),"2026-11",0)))).containsEntry("debitTotal","100.00");
 }
 @Test void cashFlowClassificationIsMandatoryForCreateReviewPostAndMissingSourceRulesRemainPending() {
  var badInput=voucher("1002","6001","100.00",null);assertThatThrownBy(()->call(()->service.createVoucher(badInput))).hasMessageContaining("必须明确");assertThat(jdbc.queryForObject("select count(*) from fa_voucher where id=?",Integer.class,badInput.requestId())).isZero();
  assertThatThrownBy(()->call(()->service.saveRule(new FinanceAccountingModels.Rule(book,"FO_PAYABLE","APPROVED","1002","2202",null,true,-1)))).hasMessageContaining("必须确认现金流");
  jdbc.update("insert into fa_posting_rule(tenant_id,book_id,source_type,event_key,debit_account_id,credit_account_id,confirmed_by) select ?,?,'FO_PAYABLE','APPROVED',d.id,c.id,? from fa_account d join fa_account c on c.tenant_id=d.tenant_id and c.book_id=d.book_id where d.book_id=? and d.code='1002' and c.code='2202'",TENANT,book,maker,book);UUID source=UUID.randomUUID();operationSource(source,"PAYABLE","12.50",Map.of("treatment","EXPENSE"));assertThat(call(()->event(source,"12.50"))).containsEntry("status","PENDING_MAPPING");
  UUID legacy=UUID.randomUUID();jdbc.update("insert into fa_period(tenant_id,book_id,month) values(?,?,?) on conflict do nothing",TENANT,book,START);jdbc.update("insert into fa_voucher(id,tenant_id,book_id,month,business_date,accounting_date,summary,kind,created_by,request_hash) values(?,?,?,?,?,?,'旧未分类分录','MANUAL',?,?)",legacy,TENANT,book,START,DAY,DAY,maker,FinanceService.hash(legacy.toString()));
  jdbc.update("insert into fa_voucher_line(tenant_id,book_id,voucher_id,line_no,account_id,debit,credit) select ?,?,?,1,id,100,0 from fa_account where book_id=? and code='1002'",TENANT,book,legacy,book);jdbc.update("insert into fa_voucher_line(tenant_id,book_id,voucher_id,line_no,account_id,debit,credit) select ?,?,?,2,id,0,100 from fa_account where book_id=? and code='6001'",TENANT,book,legacy,book);
  as(reviewer,"*");assertThatThrownBy(()->call(()->service.review(legacy,0))).hasMessageContaining("现金流分类缺失");jdbc.update("update fa_voucher set status='REVIEWED',reviewed_by=?,reviewed_at=now(),version=1 where id=?",reviewer,legacy);assertThatThrownBy(()->call(()->service.post(legacy,1))).hasMessageContaining("现金流分类缺失");assertThat(jdbc.queryForObject("select status from fa_voucher where id=?",String.class,legacy)).isEqualTo("REVIEWED");
 }
 @Test void bankMonthCloseCheckRecomputesUnmatchedItemsAndInvalidatesAnExplanationAfterNewMatches() {
  UUID transaction=bankRow(hotel,"100.00",DAY);assertThat(checkPassed("BANK_RECONCILIATION")).isFalse();String hash=jdbc.queryForObject("select source_hash from fo_bank_transaction where id=?",String.class,transaction);jdbc.update("insert into fo_bank_explanation(tenant_id,transaction_id,reason,transaction_hash_snapshot,matched_amount_snapshot,match_count_snapshot,actor_id) values(?,?,'已核对银行在途原因',?,0,0,?)",TENANT,transaction,hash,maker);assertThat(checkPassed("BANK_RECONCILIATION")).isTrue();
  UUID source=UUID.randomUUID();operationSource(source,"PAYABLE","100.00",Map.of("treatment","EXPENSE"));bankMatch(transaction,source,"40.00");assertThat(checkPassed("BANK_RECONCILIATION")).isFalse();jdbc.update("update fo_bank_explanation set matched_amount_snapshot=40,match_count_snapshot=1,version=1 where transaction_id=?",transaction);assertThat(checkPassed("BANK_RECONCILIATION")).isTrue();bankMatch(transaction,source,"60.00");assertThat(checkPassed("BANK_RECONCILIATION")).isTrue();
  bankRow(hotel,"7.00",DAY.plusMonths(1));UUID elsewhere=UUID.randomUUID();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'其它门店','HOTEL')",elsewhere,TENANT,elsewhere.toString());bankRow(elsewhere,"9.00",DAY);assertThat(checkPassed("BANK_RECONCILIATION")).isTrue();
 }
 @SuppressWarnings("unchecked") private boolean checkPassed(String code){return ((List<Map<String,Object>>)call(()->service.periodCheck(book,"2026-10")).get("checks")).stream().filter(c->code.equals(c.get("code"))).map(c->Boolean.TRUE.equals(c.get("passed"))).findFirst().orElseThrow();}
 private UUID bankRow(UUID bankHotel,String amount,LocalDate day){UUID bank=UUID.randomUUID(),row=UUID.randomUUID();jdbc.update("insert into fo_bank_account(id,tenant_id,hotel_id,name,account_number,bank_name,opening_date,opening_balance,created_by) values(?,?,?,'真实核对账户',?,'测试银行',?,0,?)",bank,TENANT,bankHotel,bank.toString(),START,maker);jdbc.update("insert into fo_bank_transaction(id,tenant_id,bank_account_id,batch_id,external_id,occurred_on,direction,amount,source_hash,created_by) values(?,?,?,?,?,?,'OUT',?,?,?)",row,TENANT,bank,UUID.randomUUID(),row.toString(),day,new BigDecimal(amount),FinanceService.hash(row.toString()),maker);return row;}
 private void bankMatch(UUID transaction,UUID source,String amount){UUID action=UUID.randomUUID();UUID bank=jdbc.queryForObject("select bank_account_id from fo_bank_transaction where id=?",UUID.class,transaction);jdbc.update("insert into fo_action(id,tenant_id,document_id,actor_id,action,amount,occurred_on,bank_account_id,request_id) values(?,?,?,?,'PAYMENT',?,?,?,?)",action,TENANT,source,maker,new BigDecimal(amount),DAY,bank,UUID.randomUUID());jdbc.update("insert into fo_bank_match(tenant_id,transaction_id,action_id,amount,request_id,note,actor_id) values(?,?,?,?,?,'真实付款核对',?)",TENANT,transaction,action,new BigDecimal(amount),UUID.randomUUID(),maker);}
 private FinanceAccountingModels.RecurringPlan plan(UUID source,String type,String kind,String monthly,String total){return new FinanceAccountingModels.RecurringPlan(UUID.randomUUID(),book,kind,type,source,"已确认真实来源","2026-10","2026-12",new BigDecimal(monthly),new BigDecimal(total),"6602","2202","周期确认");}
 private void operationSource(UUID id,String type,String amount,Map<String,Object> details){try {jdbc.update("insert into fo_document(id,tenant_id,hotel_id,company_id,applicant_id,type,title,business_date,amount,details,lines,status,review_index) values(?,?,?,?,?,?,'核算来源测试',?,?,cast(? as jsonb),'[]'::jsonb,'APPROVED',4)",id,TENANT,hotel,company,maker,type,START,new BigDecimal(amount),new ObjectMapper().writeValueAsString(details));}catch(Exception e){throw new IllegalStateException(e);}}
 private void operationAction(UUID source,String action,String amount,LocalDate day){jdbc.update("insert into fo_action(tenant_id,document_id,actor_id,action,amount,occurred_on,request_id) values(?,?,?,?,?,?,?)",TENANT,source,maker,action,new BigDecimal(amount),day,UUID.randomUUID());}
 private Map<String,Object> event(UUID source,String amount){return service.recordSourceEvent(hotel,"FO_PAYABLE",source,"APPROVED",DAY,DAY,new BigDecimal(amount),"应付确认",Map.of("sourceReference","真实单据","documentId",source.toString(),"companyId",company.toString()));}
 private FinanceAccountingModels.Period period(String month,int version){return new FinanceAccountingModels.Period(book,month,version,"财务核对确认");}
 private void importZero(){call(()->service.imports(new FinanceAccountingModels.Import(UUID.randomUUID(),book,"COMMIT","新账套零期初确认",List.of(new FinanceAccountingModels.Opening("1002",BigDecimal.ZERO,BigDecimal.ZERO)),Map.of("reference","核对后零期初"))));}
 private UUID posted(FinanceAccountingModels.Voucher input){as(maker,"*");call(()->service.createVoucher(input));as(reviewer,"*");call(()->service.review(input.requestId(),0));call(()->service.post(input.requestId(),1));return input.requestId();}
 private FinanceAccountingModels.Voucher voucher(String debit,String credit,String amount,String flow){return new FinanceAccountingModels.Voucher(UUID.randomUUID(),book,DAY,DAY,"真实事项",List.of(line(debit,amount,"0",flow),line(credit,"0",amount,null)));}
 private FinanceAccountingModels.Line line(String code,String debit,String credit,String flow){return new FinanceAccountingModels.Line(code,new BigDecimal(debit),new BigDecimal(credit),"核对分录",flow);}
 private UUID account(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'核算测试人员')",id,TENANT,id.toString());return id;}
 private void account(String code,String name,String category,String side,boolean cash){call(()->service.saveAccount(new FinanceAccountingModels.Account(book,code,name,category,side,name,cash,true,-1)));}
 private void as(UUID actor,String... permissions){TenantContext.set(new TenantPrincipal(TENANT,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
 private static <T> T call(Supplier<T> action){return transaction.execute(s->{jdbc.execute("set local role hotel_ai_os_app");return action.get();});}
 @SuppressWarnings("unchecked") private static Map<String,Object> section(Map<String,Object> report,String key){return (Map<String,Object>)report.get(key);}
}
