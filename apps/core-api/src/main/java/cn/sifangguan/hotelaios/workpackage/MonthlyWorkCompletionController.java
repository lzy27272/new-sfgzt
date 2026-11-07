package cn.sifangguan.hotelaios.workpackage;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/completion")
public class MonthlyWorkCompletionController {
    private final MonthlyWorkCompletionService service;
    public MonthlyWorkCompletionController(MonthlyWorkCompletionService service) { this.service=service; }
    @GetMapping
    public Map<String,Object> report(@RequestParam(required=false) String month,@RequestParam(required=false) UUID hotelId,
            @RequestParam(required=false) UUID assignmentId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return service.report(month,hotelId,assignmentId,page,size);
    }
    @GetMapping("/history")
    public Map<String,Object> history(@RequestParam(required=false) String month,@RequestParam(required=false) UUID hotelId,
            @RequestParam(required=false) UUID assignmentId) { return service.history(month,hotelId,assignmentId); }
}
