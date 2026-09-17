package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShiftTimingResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void relativeRulesUseConcreteOvernightShiftAnchors() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-17T22:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-18T07:00:00+08:00");

        ShiftTimingResolver.Window result = ShiftTimingResolver.resolve(start, end, null,
                ZoneId.of("Asia/Shanghai"), objectMapper.readTree("""
                        {"shiftTiming":{
                          "availableAnchor":"SHIFT_START","availableOffsetMinutes":30,
                          "dueAnchor":"SHIFT_END","dueOffsetMinutes":-15
                        }}
                        """));

        assertThat(result.availableAt()).isEqualTo("2026-09-17T22:30:00+08:00");
        assertThat(result.dueAt()).isEqualTo("2026-09-18T06:45:00+08:00");
        assertThat(result.source()).isEqualTo("SHIFT_RELATIVE");
    }

    @Test
    void legacyFixedLocalDeadlineRollsIntoNextCalendarDay() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-17T22:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-18T07:00:00+08:00");

        ShiftTimingResolver.Window result = ShiftTimingResolver.resolve(start, end,
                LocalTime.of(6, 0), ZoneId.of("Asia/Shanghai"), objectMapper.readTree("{}"));

        assertThat(result.availableAt()).isEqualTo(start);
        assertThat(result.dueAt()).isEqualTo("2026-09-18T06:00:00+08:00");
    }

    @Test
    void invalidRelativeWindowIsRejectedBeforeExpectationCreation() throws Exception {
        assertThatThrownBy(() -> ShiftTimingResolver.resolve(
                OffsetDateTime.parse("2026-09-17T08:00:00+08:00"),
                OffsetDateTime.parse("2026-09-17T16:00:00+08:00"), null,
                ZoneId.of("Asia/Shanghai"), objectMapper.readTree("""
                        {"shiftTiming":{"availableAnchor":"SHIFT_END",
                          "dueAnchor":"SHIFT_START"}}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("截止时间");
    }

    @Test
    void sameAnchorInvalidOrderIsRejectedWhenPolicyIsPublished() throws Exception {
        assertThatThrownBy(() -> ShiftTimingResolver.validatePolicy(objectMapper.readTree("""
                {"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":30,
                  "dueAnchor":"SHIFT_START","dueOffsetMinutes":30}}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("截止偏移必须晚于可开始偏移");
    }

    @Test
    void seededLegacyRelativeKeysRemainDeterministicDuringMigration() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-17T22:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-18T07:00:00+08:00");

        ShiftTimingResolver.Window result = ShiftTimingResolver.resolve(start, end, null,
                ZoneId.of("Asia/Shanghai"), objectMapper.readTree("""
                        {"shiftTiming":{"startBeforeShiftEndMinutes":90,
                          "endBeforeShiftEndMinutes":45}}
                        """));

        assertThat(result.availableAt()).isEqualTo("2026-09-18T05:30:00+08:00");
        assertThat(result.dueAt()).isEqualTo("2026-09-18T06:15:00+08:00");
    }

    @Test
    void canonicalMidpointAnchorPreservesLegacyMidpointMeaning() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-17T22:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-18T06:00:00+08:00");
        ShiftTimingResolver.Window result = ShiftTimingResolver.resolve(start, end, null,
                ZoneId.of("Asia/Shanghai"), objectMapper.readTree("""
                        {"shiftTiming":{"availableAnchor":"SHIFT_MIDPOINT","availableOffsetMinutes":-30,
                          "dueAnchor":"SHIFT_MIDPOINT","dueOffsetMinutes":30}}
                        """));
        assertThat(result.availableAt()).isEqualTo("2026-09-18T01:30:00+08:00");
        assertThat(result.dueAt()).isEqualTo("2026-09-18T02:30:00+08:00");
    }
}
