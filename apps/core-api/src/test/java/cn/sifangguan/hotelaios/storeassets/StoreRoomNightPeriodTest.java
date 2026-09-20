package cn.sifangguan.hotelaios.storeassets;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class StoreRoomNightPeriodTest {
    @Test void cutoffAndGraceNeverExposeUnclosedBusinessDays() {
        assertThat(closed("2026-09-18T03:59:00+08:00", 0)).isEqualTo("2026-09-16");
        assertThat(closed("2026-09-18T04:00:00+08:00", 0)).isEqualTo("2026-09-17");
        assertThat(closed("2026-09-18T04:10:00+08:00", 30)).isEqualTo("2026-09-16");
        assertThat(closed("2026-09-18T04:30:00+08:00", 30)).isEqualTo("2026-09-17");
        assertThat(closed("2026-09-18T12:00:00+08:00", 2160)).isEqualTo("2026-09-15");
    }
    @Test void wholeMonthUsesWholeComparisonMonthButMonthToDateUsesEquivalentDay() {
        var full = new StoreRoomNightCostService.Range(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"), true);
        assertThat(StoreRoomNightCostService.comparisonRange(full, "MONTH", false).to()).isEqualTo("2026-01-31");
        var partial = new StoreRoomNightCostService.Range(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-30"), false);
        assertThat(StoreRoomNightCostService.comparisonRange(partial, "MONTH", false).to()).isEqualTo("2026-02-28");
    }
    @Test void leapDayYearComparisonAndEmptyCurrentMonthRemainBounded() {
        var leap = new StoreRoomNightCostService.Range(LocalDate.parse("2024-02-29"), LocalDate.parse("2024-02-29"), true);
        assertThat(StoreRoomNightCostService.comparisonRange(leap, "DAY", true).from()).isEqualTo("2023-02-28");
        var empty = new StoreRoomNightCostService.Range(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-08-31"), false);
        var previous = StoreRoomNightCostService.comparisonRange(empty, "MONTH", false);
        assertThat(previous.to()).isBefore(previous.from());
    }
    private LocalDate closed(String now, int grace) {
        return StoreRoomNightCostService.latestClosedDay(ZonedDateTime.parse(now), LocalTime.of(4, 0), grace);
    }
}
