package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceBusinessExceptionModels.*;
@RestController @RequestMapping("/api/v1/finance/business-exceptions")
public class FinanceBusinessExceptionController {
 private final FinanceBusinessExceptionService service;
 public FinanceBusinessExceptionController(FinanceBusinessExceptionService service){this.service=service;}
 @GetMapping("/adjustments") public Map<String,Object> adjustments(){return service.adjustments();}
 @PostMapping("/adjustments") public Map<String,Object> adjustment(@Valid @RequestBody Adjustment in){return service.adjustment(in);}
 @PostMapping("/adjustments/{id}/review") public Map<String,Object> reviewAdjustment(@PathVariable UUID id,@Valid @RequestBody Review in){return service.reviewAdjustment(id,in);}
 @GetMapping("/recoveries") public Map<String,Object> recoveries(){return service.recoveries();}
 @PostMapping("/recoveries") public Map<String,Object> recovery(@Valid @RequestBody Recovery in){return service.recovery(in);}
 @PostMapping("/recoveries/{id}/review") public Map<String,Object> reviewRecovery(@PathVariable UUID id,@Valid @RequestBody Review in){return service.reviewRecovery(id,in);}
 @GetMapping("/tax-calendar") public Map<String,Object> calendar(){return service.calendar();}
 @PostMapping("/tax-calendar") public Map<String,Object> calendar(@Valid @RequestBody FinanceBusinessExceptionModels.Calendar in){return service.createCalendar(in);}
 @PostMapping("/tax-calendar/{id}/review") public Map<String,Object> reviewCalendar(@PathVariable UUID id,@Valid @RequestBody Review in){return service.reviewCalendar(id,in);}
 @PostMapping("/tax-calendar/{id}/link") public Map<String,Object> link(@PathVariable UUID id,@Valid @RequestBody Link in){return service.link(id,in);}
}
