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
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static cn.sifangguan.hotelaios.finance.FinanceOperationsModels.*;
import static org.assertj.core.api.Assertions.*;

class FinanceOperationsIntegrationTest {
    static EmbeddedPostgres pg;static JdbcTemplate jdbc;static FinanceOperationsService service;static FinanceService claims;static TransactionTemplate tx;
    static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");static final LocalDate DAY=LocalDate.of(2026,10,24);
    UUID hotel,owner,manager,finance,director,leader,cashier,bank;
    @BeforeAll static void start() throws Exception {
        pg=EmbeddedPostgres.builder().start();var source=pg.getPostgresDatabase();jdbc=new JdbcTemplate(source);jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("90").load().migrate();var named=new NamedParameterJdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var database=new TenantDatabaseContext(jdbc,true);var access=new AccessPolicy();var mapper=new ObjectMapper().findAndRegisterModules();var audit=new AuditWriter(named,e->{});
        var accounting=new FinanceAccountingService(named,database,access,audit,mapper);service=new FinanceOperationsService(named,database,access,mapper,audit,accounting);claims=new FinanceService(named,database,access,mapper,audit,accounting);
    }
    @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
    @AfterEach void clear(){TenantContext.clear();}
    @BeforeEach void seed(){
        hotel=UUID.randomUUID();owner=account();manager=account();finance=account();director=account();leader=account();cashier=account();bank=UUID.randomUUID();
        jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'业务测试门店','HOTEL')",hotel,T,hotel.toString());
        UUID company=UUID.randomUUID();jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'测试财务公司',?)",company,T,company.toString().replace("-",""));
        jdbc.update("insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,workflow_version,payable_account,enabled) values(?,?,?,?,?,?,?,?,2,'应付款',true)",T,hotel,company,manager,finance,director,leader,cashier);
        as(owner,"*");call(()->service.saveBankAccount(new BankAccount(bank,hotel,"结算账户","001234567890","银行",DAY.withDayOfMonth(1),new BigDecimal("10000"))));
    }
    @Test void approvalPartialPaymentReplayAndNoSelfOrEarlySettlement(){
        UUID id=draft("PAYABLE",payable(),"86.00",null);as(cashier,"finance.pay");assertThatThrownBy(()->call(()->service.settle(id,payment(0,"40.00")))).hasMessageContaining("无权查看");
        as(owner,"finance.submit","finance.review");assertThatThrownBy(()->call(()->service.review(id,new Review(UUID.randomUUID(),0,"APPROVE","自审")))).isInstanceOf(RuntimeException.class);
        approve(id);as(cashier,"finance.pay");var first=payment(5,"40.00");var paid=call(()->service.settle(id,first));assertThat(paid).containsEntry("paidAmount","40.00");call(()->service.settle(id,first));
        assertThat(jdbc.queryForObject("select count(*) from fo_action where document_id=? and action='PAYMENT'",Integer.class,id)).isOne();assertThatThrownBy(()->call(()->service.settle(id,payment(6,"47.00")))).hasMessageContaining("超过批准金额");
        call(()->service.settle(id,payment(6,"46.00")));assertThat(jdbc.queryForObject("select count(*) from fa_source_event where metadata->>'documentId'=?",Integer.class,id.toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from fa_source_event where hotel_id=? and voucher_id is not null",Integer.class,hotel)).isZero();
        assertThatThrownBy(()->jdbc.update("update fo_action set amount=1 where document_id=?",id)).hasMessageContaining("append-only");
    }
    @Test void budgetInflightActualAndReturnReleaseAreAtomic(){
        UUID budget=UUID.randomUUID();as(owner,"*");call(()->service.saveBudget(new Budget(budget,hotel,"2026-10","SUPPLIES",new BigDecimal("100"),"BLOCK",0)));
        UUID id=draft("PAYABLE",payable(),"86.00",budget);as(owner,"finance.submit");call(()->service.submit(id,new Version(UUID.randomUUID(),0)));assertUsage(id,"0.00","86.00","0.00");
        UUID next=draft("PAYABLE",payable(),"20.00",budget);assertThatThrownBy(()->call(()->service.submit(next,new Version(UUID.randomUUID(),0)))).hasMessageContaining("预算超额");assertThat(jdbc.queryForObject("select status from fo_document where id=?",String.class,next)).isEqualTo("DRAFT");
        as(manager,"finance.review");call(()->service.review(id,new Review(UUID.randomUUID(),1,"RETURN","资料补充")));assertUsage(id,"0.00","0.00","0.00");
        as(owner,"finance.submit");call(()->service.submit(next,new Version(UUID.randomUUID(),0)));assertUsage(next,"0.00","20.00","0.00");
        int version=1;for(UUID reviewer:List.of(manager,finance,director,leader)){as(reviewer,"finance.review");int expected=version++;call(()->service.review(next,new Review(UUID.randomUUID(),expected,"APPROVE","核实通过")));}assertUsage(next,"0.00","0.00","20.00");
    }
    @Test void bankImportIsIdempotentAndPartialAllocationsCannotOvermatch(){
        UUID id=draft("PAYABLE",payable(),"86.00",null);approve(id);as(cashier,"finance.pay");var paid=call(()->service.settle(id,payment(5,"40.00")));UUID settlement=(UUID)((Map<?,?>)((List<?>)paid.get("settlements")).getFirst()).get("id");
        var input=new BankImport(bank,UUID.randomUUID(),List.of(new BankRow("000001",DAY,"OUT",new BigDecimal("40.00"),"供应商","真实流水")));
        assertThat(call(()->service.importBankTransactions(input))).containsEntry("imported",1);assertThat(call(()->service.importBankTransactions(input))).containsEntry("existing",1);
        UUID transaction=jdbc.queryForObject("select id from fo_bank_transaction where bank_account_id=?",UUID.class,bank);
        var match=new Match(UUID.randomUUID(),List.of(new Allocation(settlement,new BigDecimal("30.00"))),"人工确认");call(()->service.match(transaction,match));call(()->service.match(transaction,match));
        assertThatThrownBy(()->call(()->service.match(transaction,new Match(UUID.randomUUID(),List.of(new Allocation(settlement,new BigDecimal("11.00"))),"超额")))).hasMessageContaining("超过银行流水");
        call(()->service.match(transaction,new Match(UUID.randomUUID(),List.of(new Allocation(settlement,new BigDecimal("10.00"))),"余款")));
        assertThat(jdbc.queryForObject("select sum(amount) from fo_bank_match where transaction_id=?",BigDecimal.class,transaction)).isEqualByComparingTo("40.00");
        var conflict=new BankImport(bank,input.batchId(),List.of(new BankRow("000001",DAY,"OUT",new BigDecimal("41.00"),"供应商","真实流水")));assertThatThrownBy(()->call(()->service.importBankTransactions(conflict))).hasMessageContaining("内容不同");
    }
    @Test void loanRepaymentWriteoffAndOverClearAreBounded(){
        UUID id=draft("LOAN",Map.of("borrowerAccountId",owner.toString(),"purpose","备用金","repayBy","2026-11-01"),"100.00",null);approve(id);as(cashier,"finance.pay");call(()->service.settle(id,payment(5,"100.00")));
        var repay=new Settlement(UUID.randomUUID(),6,"REPAYMENT",new BigDecimal("60.00"),DAY,bank,null,"归还流水","",null,null);call(()->service.settle(id,repay));
        assertThatThrownBy(()->call(()->service.settle(id,new Settlement(UUID.randomUUID(),7,"REPAYMENT",new BigDecimal("41.00"),DAY,bank,null,"超额归还","",null,null)))).hasMessageContaining("不能超过借款");
    }
    @Test void depreciationAndAmortizationAreRealMonthlyUniqueEvents(){
        UUID asset=draft("ASSET",Map.of("assetCode","ASSET-001","inServiceDate","2026-10-01","usefulMonths",12,"residualAmount","0.00","method","STRAIGHT_LINE"),"1200.00",null);approve(asset);as(finance,"finance.read","finance.review");
        call(()->service.settle(asset,new Settlement(UUID.randomUUID(),5,"DEPRECIATE",new BigDecimal("100.00"),DAY,null,null,"2026-10折旧","",null,null)));
        assertThatThrownBy(()->call(()->service.settle(asset,new Settlement(UUID.randomUUID(),6,"DEPRECIATE",new BigDecimal("100.00"),DAY,null,null,"重复折旧","",null,null)))).hasMessageContaining("已经计提");
        UUID prepaid=draft("PREPAYMENT",Map.of("benefitStart","2026-10-01","benefitEnd","2026-12-31","contractReference","租赁合同","recognitionMethod","STRAIGHT_LINE"),"300.00",null);approve(prepaid);as(finance,"finance.read","finance.review");
        assertThatThrownBy(()->call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),5,"AMORTIZE",new BigDecimal("100.00"),DAY,null,null,"未实付不能摊销","",null,null)))).hasMessageContaining("真实已付或已核算");
        as(cashier,"finance.pay");call(()->service.settle(prepaid,payment(5,"300.00")));as(finance,"finance.read","finance.review");
        assertThatThrownBy(()->call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),6,"AMORTIZE",new BigDecimal("99.00"),DAY,null,null,"计提","",null,null)))).hasMessageContaining("应为 100.00");
        call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),6,"AMORTIZE",new BigDecimal("100.00"),DAY,null,null,"计提","",null,null)));
    }
    @Test void loanWriteoffAndCashPaymentShareOneExpenseSettlementBalanceInBothOrders(){
        UUID loan=draft("LOAN",Map.of("borrowerAccountId",owner.toString(),"purpose","员工垫付","repayBy","2026-11-01"),"100.00",null);approve(loan);as(cashier,"finance.pay");call(()->service.settle(loan,payment(5,"100.00")));
        UUID expense=draft("PAYABLE",payable(),"100.00",null);approve(expense);as(cashier,"finance.pay");call(()->service.settle(expense,payment(5,"100.00")));as(finance,"finance.read","finance.review");
        assertThatThrownBy(()->call(()->service.settle(loan,new Settlement(UUID.randomUUID(),6,"WRITEOFF",new BigDecimal("100.00"),DAY,null,null,"不得重复核销","",null,expense)))).hasMessageContaining("剩余清偿余额");
        UUID loan2=draft("LOAN",Map.of("borrowerAccountId",owner.toString(),"purpose","先冲借款","repayBy","2026-11-01"),"100.00",null);approve(loan2);as(cashier,"finance.pay");call(()->service.settle(loan2,payment(5,"100.00")));
        UUID expense2=draft("PAYABLE",payable(),"100.00",null);approve(expense2);as(finance,"finance.read","finance.review");call(()->service.settle(loan2,new Settlement(UUID.randomUUID(),6,"WRITEOFF",new BigDecimal("60.00"),DAY,null,null,"借款冲抵","",null,expense2)));
        var cleared=call(()->service.detail(expense2));assertThat(cleared).containsEntry("outstandingAmount","40.00").containsEntry("writeoffReceivedTotal","60.00");
        as(cashier,"finance.pay");assertThatThrownBy(()->call(()->service.settle(expense2,payment(6,"100.00")))).hasMessageContaining("剩余清偿余额");
        call(()->service.settle(expense2,payment(6,"40.00")));assertThat(call(()->service.detail(expense2))).containsEntry("outstandingAmount","0.00");
    }
    @Test void recognizedSourceCannotFundMultipleCardsOrDuplicateCashEvenWithConcurrentCreates() throws Exception {
        var purchase=new LinkedHashMap<String,Object>(payable());purchase.put("treatment","ASSET");UUID source=draft("PAYABLE",purchase,"1200.00",null);approve(source);
        var details=new LinkedHashMap<String,Object>(Map.of("assetCode","CARD-001","inServiceDate","2026-10-01","usefulMonths",12,"residualAmount","0.00","method","STRAIGHT_LINE"));details.put("recognizedSourceDocumentId",source.toString());
        var first=document("ASSET",details,"1200.00",null,null);var second=document("ASSET",details,"1200.00",null,null);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=new ArrayList<Future<Boolean>>();for(var command:List.of(first,second))results.add(pool.submit(()->{as(owner,"*");barrier.await(10,TimeUnit.SECONDS);try{call(()->service.save(null,command));return true;}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}finally{TenantContext.clear();}}));
            int created=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))created++;assertThat(created).isEqualTo(1);
        }
        UUID card=jdbc.queryForObject("select id from fo_document where tenant_id=? and recognized_source_document_id=?",UUID.class,T,source);approve(card);as(cashier,"finance.pay");
        assertThatThrownBy(()->call(()->service.settle(card,payment(5,"1200.00")))).hasMessageContaining("原应付单据");
        as(finance,"finance.read","finance.review");assertThatThrownBy(()->call(()->service.settle(card,new Settlement(UUID.randomUUID(),5,"DEPRECIATE",new BigDecimal("100.00"),DAY,null,null,"未过账来源不能折旧","",null,null)))).hasMessageContaining("尚未在本期完成核算过账");
        assertThat(jdbc.queryForObject("select count(*) from fo_action where tenant_id=? and document_id=? and action='DEPRECIATE'",Integer.class,T,card)).isZero();
    }
    @Test void counterpartConfirmationGatesIntercompanyCashActionsAndCompanyIsFrozen(){
        UUID other=UUID.randomUUID();jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'对方门店','HOTEL')",other,T,other.toString());
        UUID id=draft("INTERCOMPANY",Map.of("counterpartyHotelId",other.toString(),"basisReference","真实双方往来","counterpartyReviewerId",finance.toString()),"100.00",null);approve(id);as(cashier,"finance.pay");
        assertThat((List<?>)call(()->service.detail(id)).get("allowedActions")).isEmpty();assertThatThrownBy(()->call(()->service.settle(id,payment(5,"100.00")))).hasMessageContaining("尚未完成对方确认");
        assertThat(jdbc.queryForObject("select company_id from fo_document where id=?",UUID.class,id)).isEqualTo(jdbc.queryForObject("select company_id from finance_store_config where hotel_id=?",UUID.class,hotel));
    }
    @Test void incompleteDisposalCannotFreezeAnAssetOrPretendToProduceAccounting(){
        UUID asset=draft("ASSET",Map.of("assetCode","ASSET-DISPOSAL","inServiceDate","2026-10-01","usefulMonths",12,"residualAmount","0.00","method","STRAIGHT_LINE"),"1200.00",null);approve(asset);as(finance,"finance.read","finance.review");
        assertThat(((List<?>)call(()->service.detail(asset)).get("allowedActions")).contains("DISPOSE")).isFalse();
        assertThatThrownBy(()->call(()->service.settle(asset,new Settlement(UUID.randomUUID(),5,"DISPOSE",BigDecimal.ZERO,DAY,null,null,"不能假处置","",null,null)))).hasMessageContaining("净值、累计折旧与处置损益");
        assertThat(jdbc.queryForObject("select count(*) from fo_action where tenant_id=? and document_id=? and action='DISPOSE'",Integer.class,T,asset)).isZero();
        assertThat(call(()->service.detail(asset))).containsEntry("version",5);
    }
    @Test void manualStraightLineUsesFinalMonthRemainderAndRejectsThirteenthMonth(){
        UUID asset=draft("ASSET",Map.of("assetCode","TAIL-CENT","inServiceDate","2026-10-01","usefulMonths",12,"residualAmount","0.00","method","STRAIGHT_LINE"),"1200.01",null);approve(asset);as(finance,"finance.read","finance.review");
        int version=5;for(int month=0;month<12;month++){int current=version++;LocalDate day=DAY.withDayOfMonth(1).plusMonths(month);BigDecimal amount=new BigDecimal(month==11?"100.01":"100.00");call(()->service.settle(asset,new Settlement(UUID.randomUUID(),current,"DEPRECIATE",amount,day,null,null,"真实月份折旧","",null,null)));}
        assertThat(jdbc.queryForObject("select sum(amount) from fo_action where document_id=? and action='DEPRECIATE'",BigDecimal.class,asset)).isEqualByComparingTo("1200.01");
        assertThatThrownBy(()->call(()->service.settle(asset,new Settlement(UUID.randomUUID(),17,"DEPRECIATE",new BigDecimal("0.01"),DAY.plusYears(1),null,null,"第13月不能补差","",null,null)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        UUID prepaid=draft("PREPAYMENT",Map.of("benefitStart","2026-10-01","benefitEnd","2026-12-31","contractReference","尾差租约","recognitionMethod","STRAIGHT_LINE"),"300.01",null);approve(prepaid);as(cashier,"finance.pay");call(()->service.settle(prepaid,payment(5,"100.00")));as(finance,"finance.read","finance.review");
        call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),6,"AMORTIZE",new BigDecimal("100.00"),DAY,null,null,"部分实付不重写批准月额","",null,null)));
        as(cashier,"finance.pay");call(()->service.settle(prepaid,payment(7,"200.01")));as(finance,"finance.read","finance.review");
        call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),8,"AMORTIZE",new BigDecimal("100.00"),LocalDate.of(2026,11,24),null,null,"追加实付后月额不变","",null,null)));
        call(()->service.settle(prepaid,new Settlement(UUID.randomUUID(),9,"AMORTIZE",new BigDecimal("100.01"),LocalDate.of(2026,12,24),null,null,"末月补足尾差","",null,null)));
        assertThat(jdbc.queryForObject("select sum(amount) from fo_action where document_id=? and action='AMORTIZE'",BigDecimal.class,prepaid)).isEqualByComparingTo("300.01");
    }
    @Test void unmatchedBankExplanationIsScopedVersionedAndInvalidatedByFurtherMatching(){
        UUID id=draft("PAYABLE",payable(),"40.00",null);approve(id);as(cashier,"finance.pay");var paid=call(()->service.settle(id,payment(5,"40.00")));UUID settlement=(UUID)((Map<?,?>)((List<?>)paid.get("settlements")).getFirst()).get("id");
        call(()->service.importBankTransactions(new BankImport(bank,UUID.randomUUID(),List.of(new BankRow("EXPLAIN-001",DAY,"OUT",new BigDecimal("100.00"),"真实对方","渠道合并出款")))));
        UUID bankTx=jdbc.queryForObject("select id from fo_bank_transaction where bank_account_id=?",UUID.class,bank);
        call(()->service.match(bankTx,new Match(UUID.randomUUID(),List.of(new Allocation(settlement,new BigDecimal("20.00"))),"已核对部分")));
        assertThatThrownBy(()->call(()->service.explainBankTransaction(bankTx,new BankExplanation(UUID.randomUUID(),-1,"只有付款权不能解释")))).isInstanceOf(AccessDeniedException.class);
        as(finance,"finance.review","finance.read");assertThatThrownBy(()->call(()->service.explainBankTransaction(bankTx,new BankExplanation(UUID.randomUUID(),-1," ")))).hasMessageContaining("非空");
        var command=new BankExplanation(UUID.randomUUID(),-1,"余80元为渠道跨期差异，已留待后续实际核对，未伪造匹配");call(()->service.explainBankTransaction(bankTx,command));call(()->service.explainBankTransaction(bankTx,command));
        @SuppressWarnings("unchecked")var explained=(Map<String,Object>)((List<?>)call(()->service.bankTransactions(bank,0)).get("items")).getFirst();assertThat(explained).containsEntry("explanationStatus","VALID").containsEntry("matchedAmount","20.00").containsEntry("explanationVersion",0);
        as(cashier,"finance.pay");call(()->service.match(bankTx,new Match(UUID.randomUUID(),List.of(new Allocation(settlement,new BigDecimal("20.00"))),"再匹配20元")));
        as(finance,"finance.review","finance.read");@SuppressWarnings("unchecked")var stale=(Map<String,Object>)((List<?>)call(()->service.bankTransactions(bank,0)).get("items")).getFirst();assertThat(stale).containsEntry("explanationStatus","STALE").containsEntry("matchedAmount","40.00");
        assertThatThrownBy(()->call(()->service.explainBankTransaction(bankTx,new BankExplanation(UUID.randomUUID(),-1,"过期版本不可覆盖")))).hasMessageContaining("版本");
        call(()->service.explainBankTransaction(bankTx,new BankExplanation(UUID.randomUUID(),0,"按当前40元匹配重新复核剩余60元")));
        assertThat(jdbc.queryForObject("select version from fo_bank_explanation where transaction_id=?",Integer.class,bankTx)).isEqualTo(1);
        TenantContext.set(new TenantPrincipal(T,finance,"TEST",Set.of("TEST"),Set.of("finance.review"),Set.of(UUID.randomUUID()),Set.of(),false,UUID.randomUUID()));
        assertThatThrownBy(()->call(()->service.explainBankTransaction(bankTx,new BankExplanation(UUID.randomUUID(),1,"不得越门店解释")))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unifiedOperationTodosRespectCurrentNodePayrollTaxAndOriginalReceiptCounts() throws Exception {
        UUID payable=draft("PAYABLE",payable(),"30.00",null),payroll=draft("PAYROLL",Map.of("payrollMonth","2026-10","confirmedByAccountId",finance.toString(),"employeeAccountIds",List.of(owner.toString())),"50.00",null);
        var taxCommand=new Document(UUID.randomUUID(),0,hotel,"TAX_TASK","真实税务任务",DAY,DAY,null,"MANUAL",null,"确认政策",null,Map.of("taxPeriod","2026-10","taxType","真实税种","dueDate",DAY.toString(),"policyReference","财务确认规则"),List.of(new Line("实际确认额",BigDecimal.ONE,BigDecimal.TEN,null,null)));as(owner,"*");call(()->service.save(null,taxCommand));
        for(UUID id:List.of(payable,payroll,taxCommand.requestId())){as(owner,"*");call(()->service.submit(id,new Version(UUID.randomUUID(),0)));}
        as(manager,"finance.review");assertThat(((Map<?,?>)call(()->claims.todos(0)).get("counts")).get("review")).isEqualTo(1L);
        as(manager,"finance.review","finance.payroll");assertThat(((Map<?,?>)call(()->claims.todos(0)).get("counts")).get("review")).isEqualTo(2L);
        as(manager,"finance.review","finance.payroll","finance.tax");assertThat(((Map<?,?>)call(()->claims.todos(0)).get("counts")).get("review")).isEqualTo(3L);
        as(leader,"finance.review","finance.payroll","finance.tax");assertThat(call(()->claims.todos(0)).get("total")).isEqualTo(0L);
        int version=1;for(UUID actor:List.of(manager,finance,director,leader)){as(actor,"finance.review");int expected=version++;call(()->service.review(payable,new Review(UUID.randomUUID(),expected,"APPROVE","仅当前节点")));}
        as(cashier,"finance.pay");var paid=call(()->service.settle(payable,payment(5,"10.00")));UUID settlement=(UUID)((Map<?,?>)((List<?>)paid.get("settlements")).getFirst()).get("id");
        var counts=(Map<?,?>)call(()->claims.todos(0)).get("counts");assertThat(counts.get("payment")).isEqualTo(1L);assertThat(counts.get("receipt")).isEqualTo(1L);assertThat(call(()->claims.todos(1)).get("total")).isEqualTo(2L);assertThat((List<?>)call(()->claims.todos(1)).get("items")).isEmpty();
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(24,16,BufferedImage.TYPE_INT_RGB),"png",output);var evidence=new FinanceOperationsEvidenceService(Files.createTempDirectory("finance-todo-receipt-").toString(),service,new AccessPolicy(),new AttachmentMalwareScanner("","",1,true,false));
        call(()->evidence.upload(payable,UUID.randomUUID(),"PAYMENT",settlement,new MockMultipartFile("file","真实回单.png","image/png",output.toByteArray())));
        assertThat(((Map<?,?>)call(()->claims.todos(0)).get("counts")).get("receipt")).isEqualTo(0L);assertThat(call(()->claims.todos(0)).get("total")).isEqualTo(1L);
    }
    @Test void payrollScopeTenantAndSourceUniquenessAreIndependent(){
        UUID id=draft("PAYROLL",Map.of("payrollMonth","2026-10","confirmedByAccountId",finance.toString(),"employeeAccountIds",List.of(owner.toString())),"200.00",null);as(manager,"finance.read","finance.review");assertThatThrownBy(()->call(()->service.detail(id))).hasMessageContaining("无权");assertThat(((Number)call(()->service.documents(null,null,0)).get("total")).longValue()).isZero();
        as(manager,"finance.read","finance.payroll");assertThat(call(()->service.detail(id))).containsEntry("id",id);
        TenantContext.set(new TenantPrincipal(UUID.randomUUID(),manager,"TEST",Set.of("TEST"),Set.of("*"),Set.of(hotel),Set.of(),true,UUID.randomUUID()));assertThatThrownBy(()->call(()->service.detail(id))).hasMessageContaining("不存在");
        as(owner,"*");Map<String,Object> details=Map.of("externalSystem","PMS","externalReference","NIGHT001","nightAuditDate","2026-10-24","revenueKind","DEPOSIT");UUID source=UUID.randomUUID();var doc=document("REVENUE",details,"200.00",null,source);call(()->service.save(null,doc));
        assertThatThrownBy(()->call(()->service.save(null,document("REVENUE",details,"200.00",null,source)))).isInstanceOf(RuntimeException.class);
    }
    @Test void totalIsNotFirstPageAndCalendarIncludesEarlierDates(){
        for(int i=0;i<53;i++)draft("PAYABLE",payable(),"1.00",null);as(owner,"finance.submit");var response=call(()->service.documents("PAYABLE",hotel,0));assertThat(((List<?>)response.get("items"))).hasSize(50);assertThat(((Number)response.get("total")).longValue()).isEqualTo(53);assertThat((List<?>)call(()->service.calendar()).get("items")).hasSize(53);
    }
    @Test void originalBytesReceiptSupplementAndLargeDecimalPrecisionArePreserved() throws Exception {
        UUID id=draft("PAYABLE",payable(),"99999999999999.99",null);as(owner,"finance.submit");var detail=call(()->service.detail(id));assertThat(detail).containsEntry("amount","99999999999999.99");assertThat(((Map<?,?>)((List<?>)detail.get("lines")).getFirst()).get("unitPrice")).isEqualTo("99999999999999.99");
        var evidence=new FinanceOperationsEvidenceService(Files.createTempDirectory("finance-operation-evidence-").toString(),service,new AccessPolicy(),new AttachmentMalwareScanner("","",1,true,false));
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,24,BufferedImage.TYPE_INT_RGB),"png",output);byte[] original=output.toByteArray();UUID attachment=UUID.randomUUID();var file=new MockMultipartFile("file","原始发票.png","image/png",original);
        call(()->evidence.upload(id,attachment,"INVOICE",null,file));assertThat(call(()->{try{return evidence.download(id,attachment).resource().getContentAsByteArray();}catch(Exception e){throw new RuntimeException(e);}})).isEqualTo(original);
        assertThatThrownBy(()->jdbc.update("delete from fo_attachment where id=?",attachment)).hasMessageContaining("append-only");
        // Fresh approved document: the cashier may supplement a receipt, not replace submitted applicant evidence.
        UUID paid=draft("PAYABLE",payable(),"10.00",null);approve(paid);as(cashier,"finance.pay");var settled=call(()->service.settle(paid,payment(5,"10.00")));UUID settlement=(UUID)((Map<?,?>)((List<?>)settled.get("settlements")).getFirst()).get("id");
        call(()->evidence.upload(paid,UUID.randomUUID(),"PAYMENT",settlement,file));assertThat(jdbc.queryForObject("select count(*) from fo_attachment where document_id=? and action_id=?",Integer.class,paid,settlement)).isOne();
        assertThatThrownBy(()->call(()->evidence.upload(paid,UUID.randomUUID(),"INVOICE",null,file))).hasMessageContaining("缺少权限");
    }
    @Test void taxTaskRequiresIndependentPermissionAcrossEveryReadPath(){
        var command=new Document(UUID.randomUUID(),0,hotel,"TAX_TASK","真实税务归档任务",DAY,DAY,null,"MANUAL",null,"政策依据",null,Map.of("taxPeriod","2026-10","taxType","人工确认税种","dueDate",DAY.toString(),"policyReference","财务审核后的实际依据"),List.of(new Line("经确认税额",BigDecimal.ONE,new BigDecimal("100.25"),null,null)));
        as(owner,"finance.submit");assertThatThrownBy(()->call(()->service.save(null,command))).isInstanceOf(AccessDeniedException.class);
        as(owner,"*");call(()->service.save(null,command));as(manager,"finance.read","finance.review");
        assertThatThrownBy(()->call(()->service.detail(command.requestId()))).hasMessageContaining("无权查看");assertThat(((List<?>)call(()->service.context()).get("types")).contains("TAX_TASK")).isFalse();assertThat(((Number)call(()->service.documents(null,null,0)).get("total")).longValue()).isZero();assertThat((List<?>)call(()->service.calendar()).get("items")).isEmpty();assertThat((List<?>)call(()->service.reports(hotel,"2026-10")).get("totals")).isEmpty();
        as(manager,"finance.read","finance.tax");assertThat(call(()->service.detail(command.requestId()))).containsEntry("type","TAX_TASK");
    }
    private Document document(String type,Map<String,Object> details,String amount,UUID budget,UUID source){return new Document(UUID.randomUUID(),0,hotel,type,"真实业务单据",DAY,DAY.minusDays(1),null,"MANUAL",source,"人工核对来源",budget,details,List.of(new Line("业务明细",BigDecimal.ONE,new BigDecimal(amount),"SUPPLIES",null)));}
    private UUID draft(String type,Map<String,Object> details,String amount,UUID budget){as(owner,"*");var command=document(type,details,amount,budget,null);call(()->service.save(null,command));return command.requestId();}
    private void approve(UUID id){as(owner,"finance.submit","finance.payroll");call(()->service.submit(id,new Version(UUID.randomUUID(),0)));int version=1;for(UUID user:List.of(manager,finance,director,leader)){as(user,"finance.review","finance.payroll");int expected=version++;call(()->service.review(id,new Review(UUID.randomUUID(),expected,"APPROVE","核对真实资料")));}}
    private Map<String,Object> payable(){return Map.of("acceptanceReference","验收确认","invoiceReference","发票归档","orderReference","订单归档","treatment","EXPENSE");}
    private Settlement payment(int version,String amount){return new Settlement(UUID.randomUUID(),version,"PAYMENT",new BigDecimal(amount),DAY,bank,null,"真实转账登记","",null,null);}
    private void assertUsage(UUID id,String committed,String inflight,String actual){var row=jdbc.queryForMap("select * from fo_budget_usage where document_id=?",id);assertThat((BigDecimal)row.get("committed")).isEqualByComparingTo(committed);assertThat((BigDecimal)row.get("inflight")).isEqualByComparingTo(inflight);assertThat((BigDecimal)row.get("actual")).isEqualByComparingTo(actual);}
    private UUID account(){UUID id=UUID.randomUUID();jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,'业务域测试人员')",id,T,id.toString());return id;}
    private void as(UUID actor,String... permissions){TenantContext.set(new TenantPrincipal(T,actor,"TEST",Set.of("TEST"),Set.of(permissions),Set.of(hotel),Set.of(),false,UUID.randomUUID()));}
    private static <T>T call(Supplier<T> command){return tx.execute(s->{jdbc.execute("set local role hotel_ai_os_app");return command.get();});}
}
