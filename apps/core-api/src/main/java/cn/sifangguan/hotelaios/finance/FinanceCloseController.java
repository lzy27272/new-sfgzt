package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/close")
public class FinanceCloseController {
 private final FinanceCloseControlService service;
 public FinanceCloseController(FinanceCloseControlService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(@RequestParam UUID bookId,@RequestParam String month){return service.context(bookId,month);}
 @PostMapping("/registers") public Map<String,Object> register(@Valid @RequestBody FinanceCloseModels.Register in){return service.register(in);}
 @PostMapping("/registers/{id}/review") public Map<String,Object> reviewRegister(@PathVariable UUID id,@Valid @RequestBody FinanceCloseModels.Confirm in){return service.confirmRegister(id,in);}
 @PostMapping("/bank-explanations") public Map<String,Object> explain(@Valid @RequestBody FinanceCloseModels.Explanation in){return service.explain(in);}
 @PostMapping("/bank-explanations/{id}/review") public Map<String,Object> reviewExplanation(@PathVariable UUID id,@Valid @RequestBody FinanceCloseModels.Confirm in){return service.confirmExplanation(id,in);}
}
