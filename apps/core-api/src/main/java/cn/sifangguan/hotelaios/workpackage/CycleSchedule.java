package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/** Attribution and deadline are deliberately independent (e.g. September closes on October 5). */
public final class CycleSchedule {
    private CycleSchedule() { }
    public static boolean configured(String type, JsonNode policy) {
        return Set.of("WEEK", "MONTH", "QUARTER").contains(type) && policy != null && policy.path("cycleSchedule").isObject();
    }
    public static LocalDate start(String type, LocalDate date) {
        return switch (type) {
            case "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> date.withDayOfMonth(1);
            case "QUARTER" -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
            default -> throw new IllegalArgumentException("不支持的周期");
        };
    }
    public static LocalDate end(String type, LocalDate start) {
        return switch (type) {
            case "WEEK" -> start.plusWeeks(1).minusDays(1);
            case "MONTH" -> start.plusMonths(1).minusDays(1);
            case "QUARTER" -> start.plusMonths(3).minusDays(1);
            default -> throw new IllegalArgumentException("不支持的周期");
        };
    }
    public static Window resolve(String type, LocalDate anchor, LocalTime time, ZoneId zone, JsonNode policy) {
        JsonNode schedule = policy.path("cycleSchedule");
        LocalDate start = start(type, anchor);
        LocalDate end = end(type, start);
        LocalDate deadline;
        if ("WEEK".equals(type)) {
            int days = integer(schedule, "dueDayOffset", 0, 13);
            deadline = start.plusDays(days);
        } else {
            int month = integer(schedule, "dueMonthOffset", 0, "MONTH".equals(type) ? 1 : 3);
            int day = integer(schedule, "dueDay", 0, 31);
            LocalDate target = start.plusMonths(month);
            deadline = day == 0 ? target.with(TemporalAdjusters.lastDayOfMonth())
                    : target.withDayOfMonth(Math.min(day, target.lengthOfMonth()));
        }
        return new Window(start, end, type + ":" + start,
                start.atStartOfDay(zone).toOffsetDateTime(),
                deadline.atTime(time == null ? LocalTime.of(23, 59) : time).atZone(zone).toOffsetDateTime());
    }
    private static int integer(JsonNode node, String key, int min, int max) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new IllegalArgumentException("周期截止规则无效：" + key);
        return value.intValue();
    }
    public record Window(LocalDate start, LocalDate end, String key, OffsetDateTime availableAt, OffsetDateTime dueAt) { }
}
