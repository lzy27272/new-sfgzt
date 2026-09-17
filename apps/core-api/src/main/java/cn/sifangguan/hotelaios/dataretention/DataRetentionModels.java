package cn.sifangguan.hotelaios.dataretention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class DataRetentionModels {
    private DataRetentionModels() {
    }

    public record UpdatePolicy(
            @NotNull @Min(1) @Max(365) Integer imageRetentionDays,
            @NotNull @Min(1) @Max(3650) Integer documentRetentionDays,
            @NotNull @Min(1) @Max(120) Integer employeeMetricRetentionMonths,
            @NotNull @Min(1) @Max(120) Integer pmsRetentionMonths,
            @NotNull @Min(1) @Max(120) Integer otaRetentionMonths,
            @NotNull @Min(1) @Max(120) Integer kpiRetentionMonths,
            @NotNull @Min(1) @Max(3650) Integer rawPayloadRetentionDays,
            @NotNull Boolean imageCleanupEnabled,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record Policy(
            int imageRetentionDays,
            int documentRetentionDays,
            int employeeMetricRetentionMonths,
            int pmsRetentionMonths,
            int otaRetentionMonths,
            int kpiRetentionMonths,
            int rawPayloadRetentionDays,
            boolean imageCleanupEnabled,
            long version,
            OffsetDateTime updatedAt,
            UUID updatedBy
    ) {
    }

    public record CleanupPreview(
            long candidateCount,
            long totalSizeBytes,
            OffsetDateTime oldestRetentionUntil,
            OffsetDateTime newestRetentionUntil,
            OffsetDateTime generatedAt,
            boolean cleanupEnabled
    ) {
    }
}
