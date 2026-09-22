package cn.sifangguan.hotelaios.workpackage;

import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/manager-cycles")
public class ManagerCycleController {
    private final ManagerCycleService cycles;
    private final WeeklyAssessmentService assessments;
    public ManagerCycleController(ManagerCycleService cycles, WeeklyAssessmentService assessments) {
        this.cycles=cycles; this.assessments=assessments;
    }
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="WEEK") String period, @RequestParam(required=false) LocalDate date) {
        return cycles.list(period,date);
    }
    @GetMapping("/{id}/assessment") public Map<String,Object> detail(@PathVariable UUID id) { return assessments.detail(id); }
    @GetMapping("/{id}/assessment/{employeeId}/reference") public Map<String,Object> reference(@PathVariable UUID id,@PathVariable UUID employeeId) {
        return assessments.reference(id,employeeId);
    }
    @PutMapping("/{id}/assessment/{employeeId}") public Map<String,Object> save(@PathVariable UUID id,@PathVariable UUID employeeId,@RequestBody @Valid AssessmentRequest request) {
        return assessments.save(id,employeeId,request.score(),request.focus(),request.expectedVersion());
    }
    @PostMapping("/{id}/assessment/submit") public Map<String,Object> submit(@PathVariable UUID id) { return cycles.submitAssessment(id); }
    public record AssessmentRequest(@NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score,
                                    @Size(max=2000) String focus,@PositiveOrZero Long expectedVersion) { }
}
