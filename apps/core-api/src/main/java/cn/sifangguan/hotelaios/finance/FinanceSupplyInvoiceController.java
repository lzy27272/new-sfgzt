package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/supply/invoice-releases")
public class FinanceSupplyInvoiceController {
 private final FinanceSupplyInvoiceControlService service;
 public FinanceSupplyInvoiceController(FinanceSupplyInvoiceControlService service){this.service=service;}
 @GetMapping public Map<String,Object> list(@RequestParam UUID recognitionId){return service.list(recognitionId);}
 @PostMapping public Map<String,Object> request(@Valid @RequestBody FinanceSupplyInvoiceControlService.Request in){return service.request(in);}
 @PostMapping("/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyInvoiceControlService.Review in){return service.review(id,in);}
}
