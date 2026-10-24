package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceBusinessModels.*;

@RestController @RequestMapping("/api/v1/finance/business")
public class FinanceBusinessController {
 private final FinanceBusinessService service;private final FinanceBusinessEvidenceService evidence;
 public FinanceBusinessController(FinanceBusinessService service,FinanceBusinessEvidenceService evidence){this.service=service;this.evidence=evidence;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/cash-facts") public Map<String,Object> cashFacts(){return service.cashFacts();}
 @GetMapping("/bank-matches") public Map<String,Object> bankMatches(){return service.bankMatches();}
 @GetMapping("/bank-match-suggestions") public Map<String,Object> bankSuggestions(){return service.bankMatchSuggestions();}
 @PostMapping("/bank-matches") public Map<String,Object> bankMatch(@Valid @RequestBody BankMatch in){return service.matchBank(in);}
 @GetMapping("/opening-items") public Map<String,Object> openingItems(){return service.openingItems();}
 @PostMapping("/opening-items/{id}/clear") public Map<String,Object> clearOpening(@PathVariable UUID id,@Valid @RequestBody OpeningClear in){return service.clearOpening(id,in);}
 @PostMapping("/cash-corrections") public Map<String,Object> cashCorrection(@Valid @RequestBody CashCorrection in){return service.correctCash(in);}
 @GetMapping("/cash-corrections") public Map<String,Object> cashCorrections(){return service.cashCorrections();}
 @GetMapping("/revenue/imports") public Map<String,Object> revenueImports(){return service.revenueImports();}
 @PostMapping("/revenue/imports/preview") public Map<String,Object> preview(@Valid @RequestBody RevenueImport in){return service.previewRevenue(in);}
 @PostMapping("/revenue/imports") public Map<String,Object> importRevenue(@Valid @RequestBody RevenueImport in){return service.importRevenue(in);}
 @PostMapping("/revenue/imports/file") public Map<String,Object> importRevenueFile(@RequestParam MultipartFile file,@RequestParam(defaultValue="false")boolean preview){return evidence.importRevenueFile(file,preview);}
 @GetMapping("/revenue/imports/{id}") public Map<String,Object> revenueDetail(@PathVariable UUID id){return service.revenueDetail(id);}
 @PostMapping("/revenue/imports/{id}/confirm") public Map<String,Object> confirmRevenue(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.confirmRevenue(id,in);}
 @PostMapping("/revenue/imports/{id}/cancel") public Map<String,Object> cancelRevenue(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.cancelDraft("REVENUE",id,in);}
 @GetMapping("/revenue/accounts") public Map<String,Object> revenueAccounts(){return service.revenueAccounts();}
 @GetMapping("/revenue/movements") public Map<String,Object> revenueMovements(){return service.revenueMovements();}
 @PostMapping("/revenue/accounts/{id}/movements") public Map<String,Object> moveRevenue(@PathVariable UUID id,@Valid @RequestBody RevenueMovement in){return service.moveRevenue(id,in);}
 @GetMapping("/payroll/batches") public Map<String,Object> payroll(){return service.payrollBatches();}
 @PostMapping("/payroll/batches") public Map<String,Object> createPayroll(@Valid @RequestBody PayrollBatch in){return service.createPayroll(in);}
 @PostMapping("/payroll/batches/file") public Map<String,Object> importPayrollFile(@RequestParam MultipartFile file){return evidence.importPayrollFile(file);}
 @GetMapping("/payroll/batches/{id}") public Map<String,Object> payrollDetail(@PathVariable UUID id){return service.payrollDetail(id);}
 @PostMapping("/payroll/batches/{id}/confirm") public Map<String,Object> confirmPayroll(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.confirmPayroll(id,in);}
 @PostMapping("/payroll/batches/{id}/cancel") public Map<String,Object> cancelPayroll(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.cancelDraft("PAYROLL",id,in);}
 @PostMapping("/payroll/batches/{id}/payments") public Map<String,Object> payPayroll(@PathVariable UUID id,@Valid @RequestBody PayrollPayment in){return service.payPayroll(id,in);}
 @GetMapping("/intercompany") public Map<String,Object> intercompany(){return service.intercompanyList();}
 @PostMapping("/intercompany") public Map<String,Object> createIntercompany(@Valid @RequestBody Intercompany in){return service.createIntercompany(in);}
 @GetMapping("/intercompany/{id}") public Map<String,Object> intercompanyDetail(@PathVariable UUID id){return service.intercompanyDetail(id);}
 @PostMapping("/intercompany/{id}/confirm") public Map<String,Object> confirmIntercompany(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.confirmIntercompany(id,in);}
 @PostMapping("/intercompany/{id}/cancel") public Map<String,Object> cancelIntercompany(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.cancelDraft("INTERCOMPANY",id,in);}
 @PostMapping("/intercompany/{id}/settlements") public Map<String,Object> settleIntercompany(@PathVariable UUID id,@Valid @RequestBody IntercompanySettlement in){return service.settleIntercompany(id,in);}
 @GetMapping("/consolidation/scopes") public Map<String,Object> scopes(){return service.consolidationScopes();}
 @PostMapping("/consolidation/scopes") public Map<String,Object> createScope(@Valid @RequestBody ConsolidationScope in){return service.createScope(in);}
 @PostMapping("/consolidation/scopes/{id}/publish") public Map<String,Object> publishScope(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.publishScope(id,in);}
 @PostMapping("/consolidation/worksheets") public Map<String,Object> worksheet(@Valid @RequestBody Worksheet in){return service.worksheet(in);}
 @GetMapping("/tax/output-requests") public Map<String,Object> outputs(){return service.outputRequests();}
 @PostMapping("/tax/output-requests") public Map<String,Object> createOutput(@Valid @RequestBody OutputRequest in){return service.createOutput(in);}
 @GetMapping("/tax/output-requests/{id}") public Map<String,Object> outputDetail(@PathVariable UUID id){return service.outputDetail(id);}
 @PostMapping("/tax/output-requests/{id}/register") public Map<String,Object> register(@PathVariable UUID id,@Valid @RequestBody InvoiceResult in){return service.registerInvoice(id,in);}
 @PostMapping("/tax/output-requests/{id}/cancel") public Map<String,Object> cancelOutput(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.cancelOutput(id,in);}
 @PostMapping("/tax/output-requests/{id}/execute") public Map<String,Object> execute(@PathVariable UUID id){return service.executeInvoice(id);}
 @GetMapping("/tax/input-decisions") public Map<String,Object> inputs(){return service.inputTaxes();}
 @PostMapping("/tax/input-decisions") public Map<String,Object> input(@Valid @RequestBody InputTax in){return service.recordInputTax(in);}
 @GetMapping("/tax/policies") public Map<String,Object> policies(){return service.taxPolicies();}
 @PostMapping("/tax/policies") public Map<String,Object> policy(@Valid @RequestBody TaxPolicy in){return service.createTaxPolicy(in);}
 @PostMapping("/tax/policies/{id}/publish") public Map<String,Object> publishPolicy(@PathVariable UUID id,@Valid @RequestBody Confirm in){return service.publishTaxPolicy(id,in);}
 @GetMapping("/tax/returns") public Map<String,Object> returns(){return service.taxReturns();}
 @PostMapping("/tax/returns") public Map<String,Object> createReturn(@Valid @RequestBody TaxReturn in){return service.createTaxReturn(in);}
 @GetMapping("/tax/returns/{id}") public Map<String,Object> returnDetail(@PathVariable UUID id){return service.taxReturnDetail(id);}
 @PostMapping("/tax/returns/{id}/receipts") public Map<String,Object> receipt(@PathVariable UUID id,@Valid @RequestBody TaxReceipt in){return service.recordTaxReceipt(id,in);}
 @PostMapping("/attachments/{domain}/{id}/{attachment}") public Map<String,Object> upload(@PathVariable String domain,@PathVariable UUID id,@PathVariable UUID attachment,@RequestParam MultipartFile file){return evidence.upload(domain,id,attachment,file);}
 @GetMapping("/attachments/{domain}/{id}/{attachment}") public ResponseEntity<Resource> download(@PathVariable String domain,@PathVariable UUID id,@PathVariable UUID attachment){var file=evidence.download(domain,id,attachment);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.resource());}
}
