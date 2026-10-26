package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/invoice-automation")
public class FinanceInvoiceAutomationController {
 private final FinanceInvoiceAutomationService service;public FinanceInvoiceAutomationController(FinanceInvoiceAutomationService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/intakes/{id}/jobs") public Map<String,Object> jobs(@PathVariable UUID id){return service.jobs(id);}
 @PostMapping("/intakes/{id}/jobs") public Map<String,Object> start(@PathVariable UUID id,@Valid @RequestBody FinanceInvoiceAutomationModels.Start in){return service.start(id,in);}
 @PostMapping("/intakes/{id}/jobs/{job}/refresh") public Map<String,Object> refresh(@PathVariable UUID id,@PathVariable UUID job){return service.refresh(id,job);}
 @PostMapping("/intakes/{id}/jobs/{job}/adopt") public Map<String,Object> adopt(@PathVariable UUID id,@PathVariable UUID job,@Valid @RequestBody FinanceInvoiceAutomationModels.Adopt in){return service.adopt(id,job,in);}
}
