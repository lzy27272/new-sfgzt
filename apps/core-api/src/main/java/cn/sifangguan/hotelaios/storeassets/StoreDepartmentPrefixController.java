package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/store-resources/hotels/{hotelId}/department-prefix")
public class StoreDepartmentPrefixController {
    private final StoreDepartmentPrefixService service;
    public StoreDepartmentPrefixController(StoreDepartmentPrefixService service) { this.service=service; }
    @GetMapping public Map<String,Object> preview(@PathVariable UUID hotelId) { return service.preview(hotelId); }
    @PutMapping public Map<String,Object> configure(@PathVariable UUID hotelId,@Valid @RequestBody StoreDepartmentPrefixService.RulesInput input) { return service.saveRules(hotelId,input); }
    @PostMapping("/apply") public Object apply(@PathVariable UUID hotelId,@Valid @RequestBody StoreDepartmentPrefixService.ApplyInput input,@RequestHeader("Idempotency-Key") String key) { return service.apply(hotelId,input,key); }
}
