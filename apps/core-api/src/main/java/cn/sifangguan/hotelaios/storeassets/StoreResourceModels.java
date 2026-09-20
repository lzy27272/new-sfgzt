package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class StoreResourceModels {
    private StoreResourceModels() {
    }

    public record CreateItemRequest(
            @NotBlank @Size(max = 80) String sku,
            @NotBlank @Size(max = 160) String name,
            @NotBlank String category,
            @NotBlank @Size(max = 32) String unit,
            @NotNull @DecimalMin("0") BigDecimal unitCost,
            @NotNull @DecimalMin("0") BigDecimal minimumQuantity,
            @Size(max = 240) String specification,
            @Size(max = 32) String purchaseUnit,
            BigDecimal purchaseFactor,
            @Size(max = 32) String issueUnit,
            BigDecimal issueFactor
    ) {
    }

    public record UpdateItemRequest(
            @NotBlank @Size(max = 80) String sku,
            @NotBlank @Size(max = 160) String name,
            @NotBlank String category,
            @NotBlank @Size(max = 32) String unit,
            @NotNull @DecimalMin("0") BigDecimal unitCost,
            @NotNull @DecimalMin("0") BigDecimal minimumQuantity,
            long expectedVersion,
            @Size(max = 240) String specification,
            @Size(max = 32) String purchaseUnit,
            BigDecimal purchaseFactor,
            @Size(max = 32) String issueUnit,
            BigDecimal issueFactor
    ) {
    }

    public record VersionCommand(long expectedVersion, String reason) {
    }

    public record CreateMovementRequest(
            @NotNull UUID itemId,
            @NotBlank String movementType,
            @NotNull BigDecimal quantity,
            BigDecimal unitCost,
            @NotBlank @Size(max = 1000) String reason,
            OffsetDateTime occurredAt,
            @Size(max = 32) String quantityUnit,
            Long expectedItemVersion
    ) {
    }

    public record CreateAssetRequest(
            @NotBlank @Size(max = 80) String assetCode,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 80) String category,
            @Size(max = 240) String specification,
            @Size(max = 160) String serialNumber,
            @NotBlank @Size(max = 32) String unit,
            @NotNull @DecimalMin("0") BigDecimal originalValue,
            LocalDate acquiredOn,
            @Size(max = 160) String location,
            UUID responsibleAssignmentId,
            String status
    ) {
    }

    public record UpdateAssetRequest(
            @NotBlank @Size(max = 80) String assetCode,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 80) String category,
            @Size(max = 240) String specification,
            @Size(max = 160) String serialNumber,
            @NotBlank @Size(max = 32) String unit,
            @NotNull @DecimalMin("0") BigDecimal originalValue,
            LocalDate acquiredOn,
            @Size(max = 160) String location,
            UUID responsibleAssignmentId,
            @NotBlank String status,
            long expectedVersion
    ) {
    }

    public record CreateHandoverRequest(
            @NotNull UUID transferorAssignmentId,
            @NotNull UUID recipientAssignmentId,
            @NotNull UUID approverAssignmentId,
            @Size(max = 1000) String note
    ) {
    }

    public record DisputeDecisionRequest(
            boolean hasDispute,
            @Size(max = 2000) String reason,
            long expectedVersion
    ) {
    }

    public record PhysicalCountLineRequest(
            @NotNull UUID lineId,
            @DecimalMin("0") BigDecimal physicalQuantity,
            String physicalState,
            @Size(max = 2000) String differenceReason
    ) {
    }

    public record PhysicalCountRequest(
            @NotEmpty List<@Valid PhysicalCountLineRequest> lines,
            @Size(max = 2000) String note,
            long expectedVersion
    ) {
    }

    public record ApprovalRequest(
            boolean approved,
            @Size(max = 2000) String reason,
            long expectedVersion
    ) {
    }
}
