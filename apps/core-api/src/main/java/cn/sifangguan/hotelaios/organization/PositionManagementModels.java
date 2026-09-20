package cn.sifangguan.hotelaios.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PositionManagementModels {
    private PositionManagementModels() {
    }

    public record CreatePositionRequest(
            @NotBlank @Size(max = 120) String name,
            boolean appliesToAllHotels,
            List<@NotNull UUID> applicableHotelIds,
            UUID copyFromPositionId,
            List<@NotBlank @Size(max = 120) String> permissionCodes,
            @NotBlank String authorizationScopeType,
            boolean wecomSelfSelectable
    ) {
    }

    public record UpdatePositionRequest(
            @NotBlank @Size(max = 120) String name,
            boolean appliesToAllHotels,
            List<@NotNull UUID> applicableHotelIds,
            long expectedVersion
    ) {
    }

    public record ImpactPreviewRequest(
            @NotBlank String operation,
            List<@NotBlank @Size(max = 120) String> permissionCodes,
            Boolean restoreAssignments,
            Boolean appliesToAllHotels,
            List<@NotNull UUID> applicableHotelIds
    ) {
    }

    public record RestorePositionRequest(
            long expectedVersion,
            boolean restoreAssignments
    ) {
    }

    public record DraftProfileRequest(
            long expectedProfileVersion,
            List<@NotBlank @Size(max = 120) String> permissionCodes,
            @NotBlank String authorizationScopeType,
            boolean wecomSelfSelectable
    ) {
    }

    public record PublishProfileRequest(
            long expectedProfileVersion,
            long expectedPositionVersion
    ) {
    }

    public record ApplicableHotel(UUID id, String code, String name) {
    }

    public record ProfileSummary(
            UUID profileId,
            Integer draftVersion,
            Long draftRowVersion,
            Integer publishedVersion,
            String status,
            boolean draftDirty,
            List<String> permissionCodes,
            String authorizationScopeType,
            boolean wecomSelfSelectable
    ) {
    }

    public record PositionSummary(
            UUID id,
            String name,
            String status,
            long rowVersion,
            boolean appliesToAllHotels,
            List<ApplicableHotel> applicableHotels,
            long activeAssignmentCount,
            long affectedEmployeeCount,
            OffsetDateTime deletedAt,
            String deletedBy,
            ProfileSummary profile
    ) {
    }

    public record PermissionOption(
            String permissionCode,
            String label,
            String category,
            boolean delegable,
            String restrictionReason
    ) {
    }

    public record ProfileContext(
            String roleCode,
            List<String> authorizationScopeTypes,
            List<PermissionOption> retainedPermissions,
            String selfSelectionBlockedReason,
            boolean workPlansEnabled,
            boolean executiveTasksEnabled
    ) {
    }

    public record ImpactPreview(
            UUID positionId,
            String operation,
            long activeAssignmentCount,
            long affectedEmployeeCount,
            long applicableHotelCount,
            List<String> addedPermissionCodes,
            List<String> removedPermissionCodes,
            List<String> blockedPermissionCodes,
            List<ApplicableHotel> removedHotels,
            long affectedActiveBindingCount,
            long affectedRoleGrantCount,
            long rowVersion
    ) {
    }

    public record RestoreResult(
            UUID positionId,
            long rowVersion,
            int restoredAssignmentCount,
            int skippedAssignmentCount
    ) {
    }

    public record ProfileVersionSummary(
            UUID id,
            int version,
            String status,
            long rowVersion,
            UUID basedOnGroupVersionId,
            OffsetDateTime publishedAt,
            String publishedBy,
            List<String> permissionCodes,
            String authorizationScopeType,
            boolean wecomSelfSelectable
    ) {
    }
}
