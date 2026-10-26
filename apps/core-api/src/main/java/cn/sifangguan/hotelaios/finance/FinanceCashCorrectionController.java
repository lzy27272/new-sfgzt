package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceCashCorrectionModels.*;

@RestController @RequestMapping("/api/v1/finance/cash-corrections")
public class FinanceCashCorrectionController {
 private final FinanceCashCorrectionService service;
 public FinanceCashCorrectionController(FinanceCashCorrectionService service){this.service=service;}
 @GetMapping public Map<String,Object> list(){return service.list();}
 @GetMapping("/facts") public Map<String,Object> facts(){return service.facts();}
 @PostMapping public Map<String,Object> request(@Valid @RequestBody Request in){return service.request(in);}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @PostMapping("/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Review in){return service.review(id,in);}
}
