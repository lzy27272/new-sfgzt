package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/accrual")
public class FinanceAccrualController {
 private final FinanceAccrualControlService service;
 public FinanceAccrualController(FinanceAccrualControlService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/policies") public Map<String,Object> policies(@RequestParam UUID bookId){return service.policies(bookId);}
 @PostMapping("/policies") public Map<String,Object> policy(@Valid @RequestBody FinanceAccrualModels.Policy in){return service.createPolicy(in);}
 @PostMapping("/policies/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id,@Valid @RequestBody FinanceAccrualModels.Confirm in){return service.publishPolicy(id,in);}
 @GetMapping("/plans") public Map<String,Object> plans(@RequestParam UUID bookId){return service.plans(bookId);}
 @PostMapping("/plans") public Map<String,Object> create(@Valid @RequestBody FinanceAccrualModels.Plan in){return service.createPlan(in);}
 @GetMapping("/plans/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @PostMapping("/plans/{id}/revisions") public Map<String,Object> revise(@PathVariable UUID id,@Valid @RequestBody FinanceAccrualModels.Revision in){return service.revise(id,in);}
 @PostMapping("/revisions/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceAccrualModels.Confirm in){return service.reviewRevision(id,in);}
 @PostMapping("/plans/{id}/run") public Map<String,Object> run(@PathVariable UUID id,@Valid @RequestBody FinanceAccrualModels.Run in){return service.run(id,in);}
 @PostMapping("/plans/{id}/finalize-termination") public Map<String,Object> finalizeTermination(@PathVariable UUID id){return service.finalizeTermination(id);}
 @GetMapping("/calendar") public Map<String,Object> calendar(@RequestParam UUID bookId){return service.calendar(bookId);}
}
