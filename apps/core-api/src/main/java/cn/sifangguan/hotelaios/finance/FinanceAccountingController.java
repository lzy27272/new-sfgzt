package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance/accounting")
public class FinanceAccountingController {
 private final FinanceAccountingService service;
 public FinanceAccountingController(FinanceAccountingService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/books") public Map<String,Object> books(){return service.books();}
 @PostMapping("/books") public Map<String,Object> book(@Valid @RequestBody FinanceAccountingModels.Book in){return service.createBook(in);}
 @GetMapping("/accounts") public Map<String,Object> accounts(@RequestParam UUID bookId){return service.accounts(bookId);}
 @GetMapping("/auxiliary/context") public Map<String,Object> auxiliary(@RequestParam UUID bookId){return service.auxiliaryContext(bookId);}
 @GetMapping("/auxiliary/ledger") public Map<String,Object> auxiliary(@RequestParam UUID bookId,@RequestParam String month,@RequestParam(required=false)UUID departmentId,@RequestParam(required=false)UUID employeeAccountId,@RequestParam(required=false)UUID partyId,@RequestParam(required=false)String projectKey){return service.auxiliaryLedger(bookId,month,departmentId,employeeAccountId,partyId,projectKey);}
 @PostMapping("/accounts") public Map<String,Object> account(@Valid @RequestBody FinanceAccountingModels.Account in){return service.saveAccount(in);}
 @GetMapping("/vouchers") public Map<String,Object> vouchers(@RequestParam UUID bookId,@RequestParam String month){return service.vouchers(bookId,month);}
 @GetMapping("/vouchers/{id}") public Map<String,Object> voucher(@PathVariable UUID id){return service.voucher(id);}
 @PostMapping("/vouchers") public Map<String,Object> voucher(@Valid @RequestBody FinanceAccountingModels.Voucher in){return service.createVoucher(in);}
 @PostMapping("/vouchers/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.Version in){return service.review(id,in.expectedVersion());}
 @PostMapping("/vouchers/{id}/post") public Map<String,Object> post(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.Version in){return service.post(id,in.expectedVersion());}
 @PostMapping("/vouchers/{id}/reverse") public Map<String,Object> reverse(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.Reverse in){return service.reverse(id,in);}
 @PostMapping("/vouchers/{id}/reverse-year-close") public Map<String,Object> reverseYear(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.Reverse in){return service.reverseYearClose(id,in);}
 @GetMapping("/rules") public Map<String,Object> rules(@RequestParam UUID bookId){return service.rules(bookId);}
 @PostMapping("/rules") public Map<String,Object> rule(@Valid @RequestBody FinanceAccountingModels.Rule in){return service.saveRule(in);}
 @GetMapping("/source-events") public Map<String,Object> events(@RequestParam(required=false) UUID bookId,@RequestParam(required=false) UUID hotelId,@RequestParam String month){return service.sourceEvents(bookId,hotelId,month);}
 @PostMapping("/source-events/{id}/retry") public Map<String,Object> retry(@PathVariable UUID id){return service.retrySource(id);}
 @GetMapping("/recurring-plans") public Map<String,Object> recurring(@RequestParam UUID bookId){return service.recurringPlans(bookId);}
 @PostMapping("/recurring-plans") public Map<String,Object> recurring(@Valid @RequestBody FinanceAccountingModels.RecurringPlan in){return service.saveRecurringPlan(in);}
 @PostMapping("/recurring-plans/{id}/active") public Map<String,Object> recurringActive(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.RecurringActive in){return service.recurringActive(id,in);}
 @PostMapping("/recurring-plans/{id}/run") public Map<String,Object> run(@PathVariable UUID id,@Valid @RequestBody FinanceAccountingModels.RecurringRun in){return service.runRecurring(id,in);}
 @GetMapping("/source-checks") public Map<String,Object> checks(@RequestParam UUID bookId,@RequestParam String month){return service.sourceChecks(bookId,month);}
 @PostMapping("/source-checks") public Map<String,Object> check(@Valid @RequestBody FinanceAccountingModels.SourceCheck in){return service.saveSourceCheck(in);}
 @GetMapping("/reports") public Map<String,Object> reports(@RequestParam UUID bookId,@RequestParam String month){return service.reports(bookId,month);}
 @GetMapping("/reports/export") public ResponseEntity<byte[]> export(@RequestParam UUID bookId,@RequestParam String month,@RequestParam String format,@RequestParam(defaultValue="ALL") String reportType,@RequestParam(required=false) String expectedReportVersion){var file=service.exportReport(bookId,month,format,reportType,expectedReportVersion);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header("X-Report-Version",file.reportVersion()).body(file.content());}
 @GetMapping("/reports/snapshot") public Map<String,Object> snapshot(@RequestParam UUID bookId,@RequestParam String month){return service.reportSnapshot(bookId,month);}
 @GetMapping("/reports/snapshot/export") public ResponseEntity<byte[]> snapshotExport(@RequestParam UUID bookId,@RequestParam String month,@RequestParam String format,@RequestParam(defaultValue="ALL")String reportType,@RequestParam String expectedReportVersion){var file=service.exportSnapshot(bookId,month,format,reportType,expectedReportVersion);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header("X-Report-Version",file.reportVersion()).body(file.content());}
 @GetMapping("/periods/check") public Map<String,Object> period(@RequestParam UUID bookId,@RequestParam String month){return service.periodCheck(bookId,month);}
 @PostMapping("/periods/close") public Map<String,Object> close(@Valid @RequestBody FinanceAccountingModels.Period in){return service.close(in,false);}
 @PostMapping("/periods/reopen") public Map<String,Object> reopen(@Valid @RequestBody FinanceAccountingModels.Period in){return service.close(in,true);}
 @PostMapping("/periods/year-close") public Map<String,Object> year(@Valid @RequestBody FinanceAccountingModels.YearClose in){return service.yearClose(in);}
 @PostMapping("/imports") public Map<String,Object> imports(@Valid @RequestBody FinanceAccountingModels.Import in){return service.imports(in);}
}
