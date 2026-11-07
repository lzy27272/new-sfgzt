package cn.sifangguan.hotelaios.operations;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class OperationsPlanTimingTest {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    @Test void weeklyUsesSundayMidnightAndMondayWorkingTimeEscalation() {
        var start=OperationsPlanTiming.start("WEEKLY",LocalDate.of(2026,11,7));
        assertThat(start).isEqualTo(LocalDate.of(2026,11,2));
        assertThat(OperationsPlanTiming.end("WEEKLY",start)).isEqualTo(LocalDate.of(2026,11,8));
        assertThat(OperationsPlanTiming.due("WEEKLY",start,zone)).isEqualTo(OffsetDateTime.parse("2026-11-09T00:00+08:00"));
        assertThat(OperationsPlanTiming.moments("WEEKLY",start,zone)).containsExactly(
                OffsetDateTime.parse("2026-11-08T18:00+08:00"),OffsetDateTime.parse("2026-11-08T22:00+08:00"),OffsetDateTime.parse("2026-11-09T09:00+08:00"));
    }
    @Test void monthlyIncludesTheEntireFifthDayAndHandlesYearBoundary() {
        var start=LocalDate.of(2026,12,1);
        assertThat(OperationsPlanTiming.due("MONTHLY",start,zone)).isEqualTo(OffsetDateTime.parse("2026-12-06T00:00+08:00"));
        assertThat(OperationsPlanTiming.end("MONTHLY",start)).isEqualTo(LocalDate.of(2026,12,31));
        assertThat(OperationsPlanTiming.moments("MONTHLY",start,zone)).containsExactly(
                OffsetDateTime.parse("2026-12-05T09:00+08:00"),OffsetDateTime.parse("2026-12-05T22:00+08:00"),OffsetDateTime.parse("2026-12-06T09:00+08:00"));
    }
    @Test void unsupportedPeriodsFailClosed(){assertThatThrownBy(()->OperationsPlanTiming.type("DAY")).isInstanceOf(IllegalArgumentException.class);}
}
