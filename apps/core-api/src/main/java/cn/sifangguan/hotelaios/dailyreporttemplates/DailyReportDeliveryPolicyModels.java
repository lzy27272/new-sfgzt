package cn.sifangguan.hotelaios.dailyreporttemplates;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DailyReportDeliveryPolicyModels {
    private DailyReportDeliveryPolicyModels() {
    }

    public record UpdatePolicy(
            @NotNull Boolean enabled,
            @NotNull LocalTime openLocalTime,
            @NotNull LocalTime dueLocalTime,
            @NotNull @Min(0) @Max(1440) Integer graceMinutes,
            @NotNull @Size(max = 8)
            List<@NotNull @Min(1) @Max(10080) Integer> preDueReminderMinutes,
            @NotNull @Size(max = 8)
            List<@NotNull @Min(0) @Max(10080) Integer> overdueReminderMinutes,
            @NotNull @Min(0) @Max(7) Integer backfillDays,
            @PositiveOrZero long expectedVersion
    ) {
    }

    public record Policy(
            UUID id,
            UUID templateAssignmentId,
            UUID templateId,
            UUID templateVersionId,
            boolean enabled,
            LocalTime openLocalTime,
            LocalTime dueLocalTime,
            int graceMinutes,
            List<Integer> preDueReminderMinutes,
            List<Integer> overdueReminderMinutes,
            int backfillDays,
            long rowVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            com.fasterxml.jackson.databind.JsonNode deliveryTiming
    ) {
    }
}
