package cn.sifangguan.hotelaios.workdata;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class WorkDataModels {
    private WorkDataModels() {
    }

    public record CreateForm(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String formType,
            UUID positionId
    ) {
    }

    public record CreateFormVersion(@NotNull JsonNode jsonSchema, JsonNode uiSchema) {
    }

    public record SubmitWorkRecord(
            @NotNull UUID orgUnitId,
            @NotNull UUID employeeId,
            @NotNull UUID positionAssignmentId,
            @NotNull UUID formVersionId,
            @NotNull LocalDate businessDate,
            @NotNull JsonNode payload,
            String completionStatement,
            String exceptionStatement,
            String nextAction,
            UUID workPackageVersionId,
            UUID workPackageItemId,
            UUID workExpectationId,
            String recordKind,
            UUID targetOrgUnitId,
            OffsetDateTime occurredAt,
            UUID supersedesWorkRecordId,
            Boolean saveAsDraft
    ) {
    }

    public record UpdateDraft(
            @NotNull JsonNode payload,
            String completionStatement,
            String exceptionStatement,
            String nextAction,
            OffsetDateTime occurredAt,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record SubmitDraft(@PositiveOrZero long expectedVersion) {
    }

    public record ReviewRecord(
            @NotBlank String outcome,
            String reason,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record AddAttachment(
            @NotBlank String originalName,
            @NotBlank String mediaType,
            @PositiveOrZero long sizeBytes,
            String sha256
    ) {
    }

    public record ReviewAttachmentAi(
            @NotBlank String decision,
            @NotBlank String summary,
            String correctionReason
    ) {
    }

    public record AddSupplement(
            @NotNull UUID submittedByAssignmentId,
            @NotBlank String content
    ) {
    }
}
