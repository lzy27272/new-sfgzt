package cn.sifangguan.hotelaios.dailyreporttemplates;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DailyReportTemplateModels {
    private DailyReportTemplateModels() {
    }

    public record CreateTemplate(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @NotNull UUID positionId,
            UUID ownerOrgUnitId,
            UUID baseTemplateDefinitionId,
            String templateOrigin
    ) {
    }

    public record CreateVersion(
            @NotBlank String title,
            String description,
            @NotNull UUID workPackageVersionId
    ) {
    }

    public record Item(
            UUID id,
            @NotBlank String itemCode,
            @NotBlank String label,
            String description,
            @NotBlank String valueType,
            Boolean required,
            UUID workPackageItemId,
            UUID standardVersionId,
            UUID metricId,
            String dataSourceType,
            JsonNode dataSourceConfig,
            JsonNode evidencePolicy,
            JsonNode validationRules,
            JsonNode optionValues,
            Integer sortOrder
    ) {
    }

    public record Section(
            UUID id,
            @NotBlank String sectionCode,
            @NotBlank String title,
            String description,
            String sectionOrigin,
            JsonNode applicabilityCondition,
            String sectionRole,
            Boolean required,
            Integer sortOrder,
            @NotEmpty List<@Valid Item> items
    ) {
    }

    public record UpdateVersion(
            @NotBlank String title,
            String description,
            @NotEmpty List<@Valid Section> sections,
            @PositiveOrZero long expectedVersion,
            JsonNode deliveryTiming
    ) {
    }

    public record VersionAction(
            @PositiveOrZero long expectedVersion,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String comment
    ) {
    }

    public record ResolveQuery(
            @NotNull UUID orgUnitId,
            @NotNull UUID positionAssignmentId,
            LocalDate businessDate
    ) {
    }
}
