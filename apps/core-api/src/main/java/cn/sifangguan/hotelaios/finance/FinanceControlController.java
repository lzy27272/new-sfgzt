package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.io.IOException;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceControlModels.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;
@RestController @RequestMapping("/api/v1/finance/control")
public class FinanceControlController {
 private final FinanceControlService service;private final FinanceBankControlService bank;
 public FinanceControlController(FinanceControlService service,FinanceBankControlService bank){this.service=service;this.bank=bank;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/policies") public Map<String,Object> policies(@RequestParam(required=false)UUID hotelId){return service.policies(hotelId);}
 @PostMapping("/policies") public Map<String,Object> policy(@Valid @RequestBody Policy in){return service.savePolicy(in);}
 @PostMapping("/policies/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id,@Valid @RequestBody Version in){return service.publishPolicy(id,in,false);}
 @PostMapping("/policies/{id}/retire") public Map<String,Object> retire(@PathVariable UUID id,@Valid @RequestBody Version in){return service.publishPolicy(id,in,true);}
 @GetMapping("/contracts/{id}") public Map<String,Object> contract(@PathVariable UUID id){return service.contracts(id);}
 @PostMapping("/contracts/{id}/revisions") public Map<String,Object> contract(@PathVariable UUID id,@Valid @RequestBody Contract in){return service.contract(id,in);}
 @PostMapping("/contract-revisions/{id}/review") public Map<String,Object> contractReview(@PathVariable UUID id,@Valid @RequestBody Version in){return service.reviewContract(id,in);}
 @GetMapping("/cash") public Map<String,Object> queue(@RequestParam(required=false)String status){return service.cashQueue(status);}
 @GetMapping("/cash-sources") public Map<String,Object> sources(){return service.cashSources();}
 @GetMapping("/cash/{id}") public Map<String,Object> cash(@PathVariable UUID id){return service.cash(id);}
 @PostMapping("/cash") public Map<String,Object> cash(@Valid @RequestBody Cash in){return service.createCash(in);}
 @PostMapping("/cash/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Version in){return service.reviewCash(id,in,true);}
 @PostMapping("/cash/{id}/reject") public Map<String,Object> reject(@PathVariable UUID id,@Valid @RequestBody Version in){return service.reviewCash(id,in,false);}
 @PostMapping("/cash/{id}/void") public Map<String,Object> voidCash(@PathVariable UUID id,@Valid @RequestBody Version in){return service.voidCash(id,in);}
 @PostMapping("/cash-batches") public Map<String,Object> execute(@Valid @RequestBody Batch in){return service.execute(in);}
 @GetMapping("/travel/{id}") public Map<String,Object> travel(@PathVariable UUID id){return service.travel(id);}
 @PutMapping("/travel/{id}") public Map<String,Object> travel(@PathVariable UUID id,@Valid @RequestBody Travel in){return service.saveTravel(id,in);}
 @GetMapping("/archive/{kind}/{id}") public Map<String,Object> archive(@PathVariable String kind,@PathVariable UUID id){return service.archive(kind,id);}
 @PostMapping("/archive/notes") public Map<String,Object> note(@Valid @RequestBody ArchiveNote in){return service.archiveNote(in);}
 @PostMapping("/loans/{id}/clear-claims") public Map<String,Object> clear(@PathVariable UUID id,@Valid @RequestBody LoanClear in){return service.clearLoan(id,in);}
 @GetMapping("/loans/{id}/clear-candidates") public Map<String,Object> candidates(@PathVariable UUID id){return service.loanClearCandidates(id);}
 @GetMapping("/loans/exit-check") public Map<String,Object> exit(@RequestParam UUID hotelId,@RequestParam UUID accountId){return service.loanExitCheck(hotelId,accountId);}
 @PostMapping("/bank-imports") public Map<String,Object> preview(@RequestParam UUID requestId,@RequestParam UUID bankAccountId,@RequestParam String mapping,@RequestParam MultipartFile file)throws IOException{if(file.getSize()>20*1024*1024)throw bad("银行流水文件超过20MB");var fields=new LinkedHashMap<String,String>();try{map(mapping).forEach((k,v)->fields.put(k,(String)v));}catch(Exception e){throw bad("请选择每个目标字段对应的源表头");}return bank.preview(requestId,bankAccountId,file.getOriginalFilename(),file.getBytes(),fields);}
 @PostMapping("/bank-imports/columns") public Map<String,Object> columns(@RequestParam MultipartFile file)throws IOException{if(file.getSize()>20*1024*1024)throw bad("文件超过20MB");return bank.columns(file.getOriginalFilename(),file.getBytes());}
 @GetMapping("/bank-imports/{id}") public Map<String,Object> preview(@PathVariable UUID id){return bank.detail(id);}
 @PostMapping("/bank-imports/{id}/commit") public Map<String,Object> commit(@PathVariable UUID id,@Valid @RequestBody ImportCommit in){return bank.commit(id,in);}
 @GetMapping("/bank-statements") public Map<String,Object> statement(@RequestParam UUID bankAccountId,@RequestParam LocalDate through){return bank.statement(bankAccountId,through);}
 @PostMapping("/bank-transactions/{id}/claim-match") public Map<String,Object> match(@PathVariable UUID id,@Valid @RequestBody ClaimMatch in){return bank.matchClaim(id,in);}
}
