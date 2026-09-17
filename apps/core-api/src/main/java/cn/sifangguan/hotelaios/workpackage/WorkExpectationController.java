package cn.sifangguan.hotelaios.workpackage;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class WorkExpectationController {
    private final WorkPackageService service;
    private final WorkExpectationSlaService slaService;

    public WorkExpectationController(WorkPackageService service, WorkExpectationSlaService slaService) {
        this.service = service;
        this.slaService = slaService;
    }

    @GetMapping("/api/v1/work-expectations")
    public List<Map<String, Object>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "positionAssignmentId", required = false) UUID positionAssignmentId,
            @RequestParam(name = "targetOrgUnitId", required = false) UUID targetOrgUnitId,
            @RequestParam(name = "businessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(name = "fromBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromBusinessDate,
            @RequestParam(name = "toBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toBusinessDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size
    ) {
        return service.expectations(status, positionAssignmentId, targetOrgUnitId, businessDate,
                fromBusinessDate, toBusinessDate, false, page, size);
    }

    @GetMapping("/api/v1/my/work-expectations")
    public List<Map<String, Object>> mine(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "businessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(name = "fromBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromBusinessDate,
            @RequestParam(name = "toBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toBusinessDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size
    ) {
        return service.expectations(status, null, null, businessDate,
                fromBusinessDate, toBusinessDate, true, page, size);
    }

    @GetMapping("/api/v1/team/work-expectations")
    public List<Map<String, Object>> team(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "businessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(name = "fromBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromBusinessDate,
            @RequestParam(name = "toBusinessDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toBusinessDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size
    ) {
        return service.teamExpectations(status, businessDate, fromBusinessDate, toBusinessDate, page, size);
    }

    @GetMapping("/api/v1/team/workbench-summary")
    public Map<String, Object> workbenchSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate
    ) {
        return service.teamWorkbenchSummary(businessDate);
    }

    @GetMapping("/api/v1/work-expectations/{expectationId}")
    public Map<String, Object> detail(@PathVariable UUID expectationId) {
        return service.expectationDetail(expectationId);
    }

    @PostMapping("/api/v1/work-expectations/actions/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> generate(@Valid @RequestBody WorkPackageModels.GenerateExpectations request) {
        return service.generateExpectations(request);
    }

    @PostMapping("/api/v1/work-expectations/sla/process")
    public WorkPackageModels.SlaProcessResult processSla(
            @RequestParam(defaultValue = "100") int limit
    ) {
        return slaService.processCurrentTenant(limit);
    }

    @PostMapping("/api/v1/work-expectations/{expectationId}/waive")
    public Map<String, Object> waive(
            @PathVariable UUID expectationId,
            @Valid @RequestBody WorkPackageModels.ExpectationAction request
    ) {
        return service.waiveExpectation(expectationId, request);
    }

    @PostMapping("/api/v1/work-expectations/{expectationId}/cancel")
    public Map<String, Object> cancel(
            @PathVariable UUID expectationId,
            @Valid @RequestBody WorkPackageModels.ExpectationAction request
    ) {
        return service.cancelExpectation(expectationId, request);
    }

    @GetMapping("/api/v1/work-expectations/{expectationId}/delegation-candidates")
    public List<Map<String, Object>> delegationCandidates(@PathVariable UUID expectationId) {
        return service.delegationCandidates(expectationId);
    }

    @PostMapping("/api/v1/work-expectations/{expectationId}/delegate")
    public Map<String, Object> delegate(
            @PathVariable UUID expectationId,
            @Valid @RequestBody WorkPackageModels.DelegateExpectation request
    ) {
        return service.delegateExpectation(expectationId, request);
    }

    @DeleteMapping("/api/v1/work-expectations/{expectationId}/delegation")
    public Map<String, Object> revokeDelegation(
            @PathVariable UUID expectationId,
            @RequestParam long expectedVersion
    ) {
        return service.revokeDelegation(expectationId, expectedVersion);
    }
}
