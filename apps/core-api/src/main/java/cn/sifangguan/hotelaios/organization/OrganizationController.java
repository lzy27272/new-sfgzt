package cn.sifangguan.hotelaios.organization;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/org")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping("/units")
    public List<Map<String, Object>> orgUnits(@RequestParam(required = false) String type) {
        return service.listOrgUnits(type);
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrgUnit(@Valid @RequestBody OrganizationModels.CreateOrgUnit request) {
        return service.createOrgUnit(request);
    }

    @PutMapping("/units/{orgUnitId}")
    public Map<String, Object> updateOrgUnit(
            @PathVariable UUID orgUnitId,
            @Valid @RequestBody OrganizationModels.UpdateOrgUnit request
    ) {
        return service.updateOrgUnit(orgUnitId, request);
    }

    @DeleteMapping("/units/{orgUnitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrgUnit(@PathVariable UUID orgUnitId) {
        service.deleteOrgUnit(orgUnitId);
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam(defaultValue = "false") boolean includeHistory) {
        return service.listEmployees(includeHistory);
    }

    @GetMapping("/position-options")
    public List<Map<String, Object>> positionOptions() {
        return service.listPositions();
    }

    @GetMapping("/employees/deleted")
    public List<Map<String, Object>> deletedEmployees() {
        return service.listDeletedEmployees();
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createEmployee(@Valid @RequestBody OrganizationModels.CreateEmployee request) {
        return service.createEmployee(request);
    }

    @PutMapping("/employees/{employeeId}")
    public Map<String, Object> updateEmployee(
            @PathVariable UUID employeeId,
            @Valid @RequestBody OrganizationModels.UpdateEmployee request
    ) {
        return service.updateEmployee(employeeId, request);
    }

    @DeleteMapping("/employees/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmployee(@PathVariable UUID employeeId) {
        service.deleteEmployee(employeeId);
    }

    @PostMapping("/employees/actions/delete-inactive")
    public Map<String, Object> deleteAllInactiveEmployees() {
        return service.deleteAllInactiveEmployees();
    }

    @PostMapping("/employees/{employeeId}/restore")
    public Map<String, Object> restoreEmployee(@PathVariable UUID employeeId) {
        return service.restoreEmployee(employeeId);
    }

    @DeleteMapping("/employees/{employeeId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentlyDeleteEmployee(@PathVariable UUID employeeId) {
        service.permanentlyDeleteEmployee(employeeId);
    }

    @PostMapping("/employees/{employeeId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignPosition(
            @PathVariable UUID employeeId,
            @Valid @RequestBody OrganizationModels.CreatePositionAssignment request
    ) {
        return service.assignPosition(employeeId, request);
    }

    @PutMapping("/assignments/{assignmentId}/hotel-scope")
    public Map<String, Object> updateAssignmentHotelScope(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody OrganizationModels.UpdateAssignmentHotelScope request
    ) {
        return service.updateAssignmentHotelScope(assignmentId, request);
    }

    @PutMapping("/assignments/{assignmentId}")
    public Map<String, Object> updateAssignment(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody OrganizationModels.UpdatePositionAssignment request
    ) {
        return service.updateAssignment(assignmentId, request);
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endAssignment(@PathVariable UUID assignmentId) {
        service.endAssignment(assignmentId);
    }
}
