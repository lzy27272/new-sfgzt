package cn.sifangguan.hotelaios.operations;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

public final class OperationsPlanTiming {
    private OperationsPlanTiming() { }
    public static String type(String type) {
        if (!List.of("WEEKLY","MONTHLY").contains(type)) throw new IllegalArgumentException("计划周期必须为WEEKLY或MONTHLY");
        return type;
    }
    public static LocalDate start(String type, LocalDate date) {
        return "WEEKLY".equals(type(type)) ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date.withDayOfMonth(1);
    }
    public static LocalDate end(String type, LocalDate start) { return "WEEKLY".equals(type(type)) ? start.plusDays(6) : start.with(TemporalAdjusters.lastDayOfMonth()); }
    public static OffsetDateTime due(String type, LocalDate start, ZoneId zone) {
        return ("WEEKLY".equals(type(type)) ? start.plusDays(7) : start.withDayOfMonth(6)).atStartOfDay(zone).toOffsetDateTime();
    }
    public static List<OffsetDateTime> moments(String type, LocalDate start, ZoneId zone) {
        LocalDate finalDay = "WEEKLY".equals(type(type)) ? start.plusDays(6) : start.withDayOfMonth(5);
        return List.of(finalDay.atTime("WEEKLY".equals(type) ? 18 : 9,0).atZone(zone).toOffsetDateTime(),
                finalDay.atTime(22,0).atZone(zone).toOffsetDateTime(), finalDay.plusDays(1).atTime(9,0).atZone(zone).toOffsetDateTime());
    }
}
