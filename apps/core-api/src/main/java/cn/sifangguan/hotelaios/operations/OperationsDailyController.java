package cn.sifangguan.hotelaios.operations;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsDailyController {
    private final OperationsDailyService service;
    public OperationsDailyController(OperationsDailyService service) { this.service=service; }
    @GetMapping("/context") public Map<String,Object> context() { return service.context(); }
    @GetMapping("/daily") public Map<String,Object> daily(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.daily(date);
    }
}
