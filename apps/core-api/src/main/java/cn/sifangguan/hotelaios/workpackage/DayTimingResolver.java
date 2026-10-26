package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Explicit offsets disambiguate 00:00–02:00 of an overnight business date. */
final class DayTimingResolver {
    private DayTimingResolver() { }

    static Window resolve(LocalDate date, LocalTime start, LocalTime due, ZoneId zone, JsonNode policy) {
        validatePolicy(policy);
        if (policy != null && policy.hasNonNull("breakfastStages")) {
            JsonNode stages = policy.path("breakfastStages");
            start = LocalTime.parse(stages.path("opening").path("start").asText());
            due = LocalTime.parse(stages.path("closing").path("end").asText());
        }
        int offset = policy == null ? 0 : policy.path("calendarDayOffset").asInt(0);
        OffsetDateTime availableAt = date.plusDays(offset).atTime(start == null ? LocalTime.MIN : start)
                .atZone(zone).toOffsetDateTime();
        OffsetDateTime dueAt = date.plusDays(offset).atTime(due == null ? LocalTime.of(23, 59, 59) : due)
                .atZone(zone).toOffsetDateTime();
        if (dueAt.isBefore(availableAt)) dueAt = dueAt.plusDays(1);
        return new Window(availableAt, dueAt);
    }

    static void validatePolicy(JsonNode policy) {
        if (policy == null) return;
        if (policy.hasNonNull("breakfastStages")) {
            JsonNode stages = policy.path("breakfastStages");
            if (!stages.isObject() || !stages.path("scheduleConfirmed").isBoolean())
                throw new IllegalArgumentException("早餐巡查须明确营业时间是否已确认");
            LocalTime previousEnd = null;
            for (String phase : java.util.List.of("opening", "peak", "closing")) {
                LocalTime start = LocalTime.parse(stages.path(phase).path("start").asText());
                LocalTime end = LocalTime.parse(stages.path(phase).path("end").asText());
                if (!end.isAfter(start) || (previousEnd != null && start.isBefore(previousEnd)))
                    throw new IllegalArgumentException("早餐巡查阶段时间须顺序排列且不重叠");
                previousEnd = end;
            }
        }
        if (!policy.has("calendarDayOffset")) return;
        JsonNode value = policy.get("calendarDayOffset");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 1) {
            throw new IllegalArgumentException("calendarDayOffset只能为0或1");
        }
    }

    record Window(OffsetDateTime availableAt, OffsetDateTime dueAt) { }
}
