package cn.sifangguan.hotelaios.operations;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/operations/plans")
public class OperationsPlanController {
    private final OperationsPlanService service;
    public OperationsPlanController(OperationsPlanService service){this.service=service;}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="WEEKLY") String type,@RequestParam(required=false) LocalDate periodStart,@RequestParam(defaultValue="OWN") String scope){return service.list(type,periodStart,scope);}
    @GetMapping("/targets") public List<Map<String,Object>> targets(@RequestParam UUID hotelId){return service.targets(hotelId);}
    @GetMapping("/prefill") public Map<String,Object> prefill(@RequestParam String type,@RequestParam LocalDate periodStart){return service.prefill(type,periodStart);}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
    @PutMapping("/{type}/{periodStart}/draft") public Map<String,Object> draft(@PathVariable String type,@PathVariable LocalDate periodStart,@RequestBody OperationsPlanModels.Draft draft){return service.save(type,periodStart,draft);}
    @PostMapping("/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id){return service.submit(id);}
    @GetMapping("/{id}/approval-preview") public Map<String,Object> preview(@PathVariable UUID id){return service.preview(id);}
    @PostMapping("/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@RequestBody OperationsPlanModels.Review review){return service.review(id,review);}
}
