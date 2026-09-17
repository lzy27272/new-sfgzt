package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a SHIFT item's availability and deadline against the concrete duty period.
 * Relative offsets deliberately use the duty period timestamps rather than the business
 * date, so an overnight shift remains one continuous execution window.
 */
final class ShiftTimingResolver {
    private static final Set<String> ANCHORS = Set.of("SHIFT_START", "SHIFT_MIDPOINT", "SHIFT_END");
    private static final int MAX_ABSOLUTE_OFFSET_MINUTES = 2 * 24 * 60;

    private ShiftTimingResolver() {
    }

    static Window resolve(
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            LocalTime fixedDueLocalTime,
            ZoneId itemZone,
            JsonNode executionPolicy
    ) {
        if (shiftStart == null || shiftEnd == null || !shiftEnd.isAfter(shiftStart)) {
            throw new IllegalArgumentException("班次开始和结束时间无效");
        }
        JsonNode timing = executionPolicy == null ? null : executionPolicy.get("shiftTiming");
        if (timing == null || timing.isNull()) {
            OffsetDateTime dueAt = fixedDueLocalTime == null
                    ? shiftEnd
                    : resolveFixedLocalTime(shiftStart, fixedDueLocalTime, itemZone);
            if (!dueAt.isAfter(shiftStart)) {
                throw new IllegalArgumentException("班次工作截止时间必须晚于可开始时间");
            }
            return new Window(shiftStart, dueAt, "LEGACY_FIXED_OR_SHIFT_END");
        }
        if (!timing.isObject()) {
            throw new IllegalArgumentException("executionPolicy.shiftTiming必须是JSON对象");
        }

        if (!hasCanonicalFields(timing) && hasLegacyRelativeFields(timing)) {
            return resolveLegacyRelativeWindow(shiftStart, shiftEnd, timing);
        }

        String availableAnchor = anchor(timing, "availableAnchor", "SHIFT_START");
        String dueAnchor = anchor(timing, "dueAnchor", "SHIFT_END");
        int availableOffset = offset(timing, "availableOffsetMinutes");
        int dueOffset = offset(timing, "dueOffsetMinutes");
        OffsetDateTime availableAt = anchorTime(availableAnchor, shiftStart, shiftEnd)
                .plusMinutes(availableOffset);
        OffsetDateTime dueAt = anchorTime(dueAnchor, shiftStart, shiftEnd)
                .plusMinutes(dueOffset);
        if (!dueAt.isAfter(availableAt)) {
            throw new IllegalArgumentException("班次相对时间规则无效：截止时间必须晚于可开始时间");
        }
        return new Window(availableAt, dueAt, "SHIFT_RELATIVE");
    }

    static void validatePolicy(JsonNode executionPolicy) {
        if (executionPolicy == null || executionPolicy.isNull()) return;
        JsonNode timing = executionPolicy.get("shiftTiming");
        if (timing == null || timing.isNull()) return;
        if (!timing.isObject()) {
            throw new IllegalArgumentException("executionPolicy.shiftTiming必须是JSON对象");
        }
        String availableAnchor = anchor(timing, "availableAnchor", "SHIFT_START");
        String dueAnchor = anchor(timing, "dueAnchor", "SHIFT_END");
        int availableOffset = offset(timing, "availableOffsetMinutes");
        int dueOffset = offset(timing, "dueOffsetMinutes");
        if (availableAnchor.equals(dueAnchor) && dueOffset <= availableOffset) {
            throw new IllegalArgumentException("班次相对时间规则无效：同一基准下截止偏移必须晚于可开始偏移");
        }
        for (String field : Set.of("startOffsetMinutes", "endOffsetMinutes",
                "startBeforeShiftEndMinutes", "endBeforeShiftEndMinutes",
                "endAfterShiftEndMinutes", "aroundShiftMidpointMinutes")) {
            offset(timing, field);
        }
    }

    private static Window resolveLegacyRelativeWindow(
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            JsonNode timing
    ) {
        OffsetDateTime availableAt;
        OffsetDateTime dueAt;
        if (timing.has("startOffsetMinutes")) {
            availableAt = shiftStart.plusMinutes(offset(timing, "startOffsetMinutes"));
        } else if (timing.has("startBeforeShiftEndMinutes")) {
            availableAt = shiftEnd.minusMinutes(offset(timing, "startBeforeShiftEndMinutes"));
        } else if (timing.has("aroundShiftMidpointMinutes")) {
            availableAt = midpoint(shiftStart, shiftEnd)
                    .minusMinutes(offset(timing, "aroundShiftMidpointMinutes"));
        } else {
            availableAt = shiftStart;
        }
        if (timing.has("endOffsetMinutes")) {
            dueAt = shiftStart.plusMinutes(offset(timing, "endOffsetMinutes"));
        } else if (timing.has("endBeforeShiftEndMinutes")) {
            dueAt = shiftEnd.minusMinutes(offset(timing, "endBeforeShiftEndMinutes"));
        } else if (timing.has("endAfterShiftEndMinutes")) {
            dueAt = shiftEnd.plusMinutes(offset(timing, "endAfterShiftEndMinutes"));
        } else if (timing.has("aroundShiftMidpointMinutes")) {
            dueAt = midpoint(shiftStart, shiftEnd)
                    .plusMinutes(offset(timing, "aroundShiftMidpointMinutes"));
        } else {
            dueAt = shiftEnd;
        }
        if (!dueAt.isAfter(availableAt)) {
            throw new IllegalArgumentException("班次相对时间规则无效：截止时间必须晚于可开始时间");
        }
        return new Window(availableAt, dueAt, "SHIFT_RELATIVE_LEGACY_COMPATIBLE");
    }

    private static OffsetDateTime midpoint(OffsetDateTime start, OffsetDateTime end) {
        return start.plusSeconds(Duration.between(start, end).toSeconds() / 2);
    }

    private static boolean hasCanonicalFields(JsonNode timing) {
        return timing.has("availableAnchor") || timing.has("availableOffsetMinutes")
                || timing.has("dueAnchor") || timing.has("dueOffsetMinutes");
    }

    private static boolean hasLegacyRelativeFields(JsonNode timing) {
        return timing.has("startOffsetMinutes") || timing.has("endOffsetMinutes")
                || timing.has("startBeforeShiftEndMinutes")
                || timing.has("endBeforeShiftEndMinutes")
                || timing.has("endAfterShiftEndMinutes")
                || timing.has("aroundShiftMidpointMinutes");
    }

    private static OffsetDateTime resolveFixedLocalTime(
            OffsetDateTime shiftStart,
            LocalTime fixedDueLocalTime,
            ZoneId itemZone
    ) {
        LocalDate localStartDate = shiftStart.atZoneSameInstant(itemZone).toLocalDate();
        OffsetDateTime candidate = localStartDate.atTime(fixedDueLocalTime)
                .atZone(itemZone).toOffsetDateTime();
        while (candidate.isBefore(shiftStart)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static String anchor(JsonNode timing, String field, String fallback) {
        JsonNode value = timing.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return fallback;
        if (!value.isTextual()) {
            throw new IllegalArgumentException("shiftTiming." + field + "必须是字符串");
        }
        String normalized = value.asText().trim().toUpperCase(Locale.ROOT);
        if (!ANCHORS.contains(normalized)) {
            throw new IllegalArgumentException("shiftTiming." + field
                    + "只支持SHIFT_START、SHIFT_MIDPOINT或SHIFT_END");
        }
        return normalized;
    }

    private static int offset(JsonNode timing, String field) {
        JsonNode value = timing.get(field);
        if (value == null || value.isNull()) return 0;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("shiftTiming." + field + "必须是整数分钟");
        }
        int offset = value.intValue();
        if (Math.abs((long) offset) > MAX_ABSOLUTE_OFFSET_MINUTES) {
            throw new IllegalArgumentException("shiftTiming." + field + "不能超过48小时");
        }
        return offset;
    }

    private static OffsetDateTime anchorTime(
            String anchor,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd
    ) {
        return switch (anchor) {
            case "SHIFT_END" -> shiftEnd;
            case "SHIFT_MIDPOINT" -> midpoint(shiftStart, shiftEnd);
            default -> shiftStart;
        };
    }

    record Window(OffsetDateTime availableAt, OffsetDateTime dueAt, String source) {
    }
}
