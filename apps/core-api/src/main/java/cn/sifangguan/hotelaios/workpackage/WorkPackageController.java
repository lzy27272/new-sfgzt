package cn.sifangguan.hotelaios.workpackage;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-packages")
public class WorkPackageController {
    private final WorkPackageService service;

    public WorkPackageController(WorkPackageService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.definitions();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody WorkPackageModels.CreateDefinition request) {
        return service.createDefinition(request);
    }

    @GetMapping("/{workPackageId}")
    public Map<String, Object> detail(@PathVariable UUID workPackageId) {
        return service.detail(workPackageId);
    }

    @GetMapping("/{workPackageId}/versions/{versionId}")
    public Map<String, Object> version(@PathVariable UUID workPackageId,@PathVariable UUID versionId) {
        return service.version(workPackageId,versionId);
    }

    @PostMapping("/{workPackageId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createVersion(
            @PathVariable UUID workPackageId,
            @Valid @RequestBody WorkPackageModels.CreateVersion request
    ) {
        return service.createVersion(workPackageId, request);
    }

    @PutMapping("/{workPackageId}/versions/{versionId}")
    public Map<String, Object> updateVersion(
            @PathVariable UUID workPackageId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WorkPackageModels.UpdateVersion request
    ) {
        return service.updateVersion(workPackageId, versionId, request);
    }

    @PostMapping("/{workPackageId}/versions/{versionId}/validate")
    public Map<String, Object> validateVersion(
            @PathVariable UUID workPackageId,
            @PathVariable UUID versionId
    ) {
        return service.validateVersion(workPackageId, versionId);
    }

    @PostMapping("/{workPackageId}/versions/{versionId}/publish")
    public Map<String, Object> publishVersion(
            @PathVariable UUID workPackageId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WorkPackageModels.PublishVersion request
    ) {
        return service.publishVersion(workPackageId, versionId, request);
    }

    @PostMapping("/{workPackageId}/versions/{versionId}/retire")
    public Map<String, Object> retireVersion(
            @PathVariable UUID workPackageId,
            @PathVariable UUID versionId,
            @RequestBody(required = false) WorkPackageModels.RetireVersion request
    ) {
        return service.retireVersion(workPackageId, versionId, request);
    }

    @GetMapping("/{workPackageId}/allocations")
    public List<Map<String, Object>> allocations(@PathVariable UUID workPackageId) {
        return service.allocations(workPackageId);
    }

    @PostMapping("/{workPackageId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> allocate(
            @PathVariable UUID workPackageId,
            @Valid @RequestBody WorkPackageModels.CreateAllocation request
    ) {
        return service.createAllocation(workPackageId, request);
    }

    @PostMapping("/{workPackageId}/allocations/{allocationId}/replace")
    public Map<String, Object> replaceAllocation(
            @PathVariable UUID workPackageId, @PathVariable UUID allocationId,
            @Valid @RequestBody WorkPackageModels.CreateAllocation request
    ) {
        return service.replaceAllocation(workPackageId, allocationId, request);
    }
}
