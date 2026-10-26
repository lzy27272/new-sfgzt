package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/insights/actions")
public class FinanceInsightActionController {
 private final FinanceInsightActionService service;public FinanceInsightActionController(FinanceInsightActionService service){this.service=service;}
 @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="0")int page){return service.list(page);}
 @GetMapping("/assignees") public Map<String,Object> assignees(@RequestParam UUID hotelId){return service.assignees(hotelId);}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @GetMapping("/{id}/sources") public Map<String,Object> sources(@PathVariable UUID id,@RequestParam String sourceType,@RequestParam(defaultValue="0")int page){return service.candidates(id,sourceType,page);}
 @PostMapping public Map<String,Object> assign(@Valid @RequestBody FinanceInsightActionModels.Assign in){return service.assign(in);}
 @PostMapping("/{id}/start") public Map<String,Object> start(@PathVariable UUID id,@Valid @RequestBody FinanceInsightActionModels.Move in){return service.start(id,in);}
 @PostMapping("/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id,@Valid @RequestBody FinanceInsightActionModels.Submit in){return service.submit(id,in);}
 @PostMapping("/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceInsightActionModels.Review in){return service.review(id,in);}
 @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody FinanceInsightActionModels.Move in){return service.cancel(id,in);}
 @PostMapping("/{id}/proof-preview") public Map<String,Object> preview(@PathVariable UUID id,@Valid @RequestBody FinanceInsightActionModels.Proof in){return service.preview(id,in.sourceType(),in.sourceId());}
}
