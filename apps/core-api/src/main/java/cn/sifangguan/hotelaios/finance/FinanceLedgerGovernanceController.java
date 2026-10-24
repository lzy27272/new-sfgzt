package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceControlModels.*;
@RestController @RequestMapping("/api/v1/finance/control/ledger-changes")
public class FinanceLedgerGovernanceController {
 private final FinanceLedgerGovernance service;public FinanceLedgerGovernanceController(FinanceLedgerGovernance service){this.service=service;}
 @GetMapping public Map<String,Object> list(@RequestParam UUID bookId){return service.list(bookId);}
 @PostMapping("/accounts") public Map<String,Object> account(@Valid @RequestBody AccountProposal in){return service.account(in.requestId(),in.account(),in.reason());}
 @PostMapping("/rules") public Map<String,Object> rule(@Valid @RequestBody RuleProposal in){return service.rule(in.requestId(),in.rule(),in.reason());}
 @PostMapping("/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id,@Valid @RequestBody Version in){return service.publish(id,in,false);}
 @PostMapping("/{id}/reject") public Map<String,Object> reject(@PathVariable UUID id,@Valid @RequestBody Version in){return service.publish(id,in,true);}
}
