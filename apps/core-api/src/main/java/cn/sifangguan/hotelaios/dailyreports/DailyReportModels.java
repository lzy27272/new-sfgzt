package cn.sifangguan.hotelaios.dailyreports;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DailyReportModels {
    private DailyReportModels() {
    }

    public record CreateReport(
            @NotNull UUID orgUnitId,
            @NotNull UUID positionAssignmentId,
            LocalDate businessDate,
            UUID templateVersionId
    ) {
    }

    public record ItemValue(
            @NotNull UUID templateItemId,
            JsonNode value,
            Boolean confirmed,
            Boolean exception,
            String comment
    ) {
    }

    public record SaveDraft(
            @NotNull UUID revisionId,
            @NotNull List<@Valid ItemValue> items,
            String narrative,
            @PositiveOrZero long expectedVersion,
            JsonNode inventoryQuantities,
            JsonNode roomCounts,
            @Valid PlannedHygiene plannedHygiene,
            JsonNode housekeepingActivities
    ) {
        public SaveDraft(UUID revisionId, List<ItemValue> items, String narrative, long expectedVersion) {
            this(revisionId,items,narrative,expectedVersion,null,null,null,null);
        }
        public SaveDraft(UUID revisionId,List<ItemValue> items,String narrative,long expectedVersion,
                         JsonNode inventoryQuantities,JsonNode roomCounts,PlannedHygiene plannedHygiene) {
            this(revisionId,items,narrative,expectedVersion,inventoryQuantities,roomCounts,plannedHygiene,null);
        }
    }

    public record PlannedHygiene(
            @Size(max = 2000, message = "计划卫生内容不能超过2000字") String content,
            @Size(max = 1000, message = "计划卫生房间号不能超过1000字") String roomNumbers
    ) { }

    public record SubmitReport(
            @NotNull UUID revisionId,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record CreateCorrection(
            @NotBlank String reason,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record Review(
            @NotBlank String outcome,
            String comment,
            @NotNull UUID reviewerAssignmentId,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record AddSource(
            UUID itemResultId,
            @NotBlank String sourceType,
            UUID sourceId,
            String sourceExternalKey,
            String sourceVersion,
            JsonNode sourceSnapshot,
            OffsetDateTime sourceOccurredAt,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record AddEvidence(
            UUID itemResultId,
            @NotBlank String evidenceType,
            String objectKey,
            String originalName,
            String mediaType,
            @PositiveOrZero long sizeBytes,
            String sha256,
            String sensitivity,
            JsonNode metadata,
            @PositiveOrZero long expectedVersion
    ) {
    }
}
