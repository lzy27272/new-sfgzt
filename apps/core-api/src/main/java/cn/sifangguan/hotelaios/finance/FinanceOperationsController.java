package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceOperationsModels.*;

@RestController
@RequestMapping("/api/v1/finance/operations")
public class FinanceOperationsController {
    private final FinanceOperationsService service;
    private final FinanceOperationsEvidenceService evidence;
    public FinanceOperationsController(FinanceOperationsService service,FinanceOperationsEvidenceService evidence) {this.service=service;this.evidence=evidence;}
    @GetMapping("/context") public Map<String,Object> context(){return service.context();}
    @GetMapping("/documents") public Map<String,Object> list(@RequestParam(required=false)String type,@RequestParam(required=false)UUID hotelId,@RequestParam(defaultValue="0")int page){return service.documents(type,hotelId,page);}
    @GetMapping("/documents/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
    @PostMapping("/documents/{id}/attachments/{attachmentId}") public Map<String,Object> upload(@PathVariable UUID id,@PathVariable UUID attachmentId,@RequestParam String kind,@RequestParam(required=false)UUID settlementId,@RequestParam MultipartFile file){return evidence.upload(id,attachmentId,kind,settlementId,file);}
    @GetMapping("/documents/{id}/attachments/{attachmentId}") public ResponseEntity<Resource> download(@PathVariable UUID id,@PathVariable UUID attachmentId){var file=evidence.download(id,attachmentId);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.resource());}
    @PostMapping("/documents") public Map<String,Object> create(@Valid @RequestBody Document input){return service.save(null,input);}
    @PutMapping("/documents/{id}") public Map<String,Object> save(@PathVariable UUID id,@Valid @RequestBody Document input){return service.save(id,input);}
    @PostMapping("/documents/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id,@Valid @RequestBody Version input){return service.submit(id,input);}
    @PostMapping("/documents/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Review input){return service.review(id,input);}
    @PostMapping("/documents/{id}/settle") public Map<String,Object> settle(@PathVariable UUID id,@Valid @RequestBody Settlement input){return service.settle(id,input);}
    @PostMapping("/documents/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody Version input){return service.cancel(id,input);}
    @GetMapping("/parties") public Map<String,Object> parties(){return service.parties();}
    @PostMapping("/parties") public Map<String,Object> party(@Valid @RequestBody Party input){return service.saveParty(input);}
    @GetMapping("/bank-accounts") public Map<String,Object> bankAccounts(){return service.bankAccounts();}
    @PostMapping("/bank-accounts") public Map<String,Object> bankAccount(@Valid @RequestBody BankAccount input){return service.saveBankAccount(input);}
    @GetMapping("/bank-transactions") public Map<String,Object> bankTransactions(@RequestParam(required=false)UUID bankAccountId,@RequestParam(defaultValue="0")int page){return service.bankTransactions(bankAccountId,page);}
    @PostMapping("/bank-transactions") public Map<String,Object> bankImport(@Valid @RequestBody BankImport input){return service.importBankTransactions(input);}
    @PostMapping("/bank-transactions/{id}/match") public Map<String,Object> match(@PathVariable UUID id,@Valid @RequestBody Match input){return service.match(id,input);}
    @PostMapping("/bank-transactions/{id}/explanation") public Map<String,Object> explain(@PathVariable UUID id,@Valid @RequestBody BankExplanation input){return service.explainBankTransaction(id,input);}
    @GetMapping("/budgets") public Map<String,Object> budgets(){return service.budgets();}
    @PostMapping("/budgets") public Map<String,Object> budget(@Valid @RequestBody Budget input){return service.saveBudget(input);}
    @GetMapping("/calendar") public Map<String,Object> calendar(){return service.calendar();}
    @GetMapping("/reports") public Map<String,Object> reports(@RequestParam(required=false)UUID hotelId,@RequestParam String month){return service.reports(hotelId,month);}
}
