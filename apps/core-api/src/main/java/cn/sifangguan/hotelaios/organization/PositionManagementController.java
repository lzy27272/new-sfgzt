package cn.sifangguan.hotelaios.organization;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/org/positions")
public class PositionManagementController {
    private final PositionManagementService service;

    public PositionManagementController(PositionManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<PositionManagementModels.PositionSummary> list() {
        return service.list(false);
    }

    @GetMapping("/deleted")
    public List<PositionManagementModels.PositionSummary> deleted() {
        return service.list(true);
    }

    @GetMapping("/function-options")
    public List<PositionManagementModels.PermissionOption> functionOptions(
            @RequestParam(required = false) UUID positionId
    ) {
        return service.functionOptions(positionId);
    }

    @GetMapping("/profile-context")
    public PositionManagementModels.ProfileContext profileContext(
            @RequestParam(required = false) UUID positionId
    ) {
        return service.profileContext(positionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PositionManagementModels.PositionSummary create(
            @Valid @RequestBody PositionManagementModels.CreatePositionRequest request
    ) {
        return service.create(request);
    }

    @PutMapping("/{positionId}")
    public PositionManagementModels.PositionSummary update(
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionManagementModels.UpdatePositionRequest request
    ) {
        return service.update(positionId, request);
    }

    @PostMapping("/{positionId}/impact-preview")
    public PositionManagementModels.ImpactPreview preview(
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionManagementModels.ImpactPreviewRequest request
    ) {
        return service.preview(positionId, request);
    }

    @DeleteMapping("/{positionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID positionId,
            @RequestParam long expectedVersion
    ) {
        service.delete(positionId, expectedVersion);
    }

    @PostMapping("/{positionId}/restore")
    public PositionManagementModels.RestoreResult restore(
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionManagementModels.RestorePositionRequest request
    ) {
        return service.restore(positionId, request);
    }

    @DeleteMapping("/{positionId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentlyDelete(
            @PathVariable UUID positionId,
            @RequestParam long expectedVersion
    ) {
        service.permanentlyDelete(positionId, expectedVersion);
    }

    @PutMapping("/{positionId}/profile/draft")
    public PositionManagementModels.ProfileSummary saveGroupDraft(
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionManagementModels.DraftProfileRequest request
    ) {
        return service.saveDraft(positionId, null, request);
    }

    @PostMapping("/{positionId}/profile/publish")
    public PositionManagementModels.ProfileSummary publishGroup(
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionManagementModels.PublishProfileRequest request
    ) {
        return service.publish(positionId, null, request);
    }

    @PutMapping("/{positionId}/profile/hotels/{hotelId}/draft")
    public PositionManagementModels.ProfileSummary saveHotelDraft(
            @PathVariable UUID positionId,
            @PathVariable UUID hotelId,
            @Valid @RequestBody PositionManagementModels.DraftProfileRequest request
    ) {
        return service.saveDraft(positionId, hotelId, request);
    }

    @PostMapping("/{positionId}/profile/hotels/{hotelId}/publish")
    public PositionManagementModels.ProfileSummary publishHotel(
            @PathVariable UUID positionId,
            @PathVariable UUID hotelId,
            @Valid @RequestBody PositionManagementModels.PublishProfileRequest request
    ) {
        return service.publish(positionId, hotelId, request);
    }

    @GetMapping("/{positionId}/profile/versions")
    public List<PositionManagementModels.ProfileVersionSummary> versions(
            @PathVariable UUID positionId,
            @RequestParam(required = false) UUID hotelId
    ) {
        return service.versions(positionId, hotelId);
    }
}
