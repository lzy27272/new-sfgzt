package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

class DayTimingResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test
    void explicitSecondEquipmentRoundUsesNextDateNotBusinessDateMorning() throws Exception {
        var window = DayTimingResolver.resolve(LocalDate.of(2026, 10, 26), LocalTime.MIDNIGHT,
                LocalTime.of(2, 0), zone, mapper.readTree("{\"calendarDayOffset\":1}"));
        assertThat(window.availableAt()).isEqualTo("2026-10-27T00:00:00+08:00");
        assertThat(window.dueAt()).isEqualTo("2026-10-27T02:00:00+08:00");
    }

    @Test
    void legacyCrossMidnightPatrolAndUnconfiguredDayItemsStayCompatible() throws Exception {
        var window = DayTimingResolver.resolve(LocalDate.of(2026, 10, 26), LocalTime.of(21, 0),
                LocalTime.of(1, 0), zone, mapper.readTree("{}"));
        assertThat(window.availableAt()).isEqualTo("2026-10-26T21:00:00+08:00");
        assertThat(window.dueAt()).isEqualTo("2026-10-27T01:00:00+08:00");
    }

    @Test
    void confirmedBreakfastHoursOverrideExampleHours() throws Exception {
        var policy = mapper.readTree("""
                {"breakfastStages":{"scheduleConfirmed":true,
                  "opening":{"start":"06:30","end":"06:50"},
                  "peak":{"start":"07:30","end":"07:50"},
                  "closing":{"start":"09:30","end":"10:00"}}}
                """);
        var window = DayTimingResolver.resolve(LocalDate.of(2026, 10, 26), LocalTime.of(7, 0),
                LocalTime.of(10, 30), zone, policy);
        assertThat(window.availableAt()).isEqualTo("2026-10-26T06:30:00+08:00");
        assertThat(window.dueAt()).isEqualTo("2026-10-26T10:00:00+08:00");
    }

    @Test
    void invalidDayOffsetCannotSilentlyTruncate() throws Exception {
        assertThatThrownBy(() -> DayTimingResolver.validatePolicy(mapper.readTree("{\"calendarDayOffset\":4294967296}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
