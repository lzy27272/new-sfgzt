package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WorkExpectationSlaServiceTest {
    @Test
    void afterOpenReminderRunsOneMinuteAfterActualAvailability() throws Exception {
        var policy = new ObjectMapper().readTree("""
                {"afterOpenMinutes":1,"dueSoonMinutes":15,
                 "overdueMinutes":5,"escalationMinutes":20}
                """);
        OffsetDateTime availableAt = OffsetDateTime.parse("2026-09-17T22:00:00+08:00");

        assertThat(WorkExpectationSlaService.relativeReminderTime(
                policy, "afterOpenMinutes", availableAt, false))
                .isEqualTo("2026-09-17T22:01:00+08:00");
    }
}
