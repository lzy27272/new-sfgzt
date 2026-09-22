package cn.sifangguan.hotelaios.workpackage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class CycleScheduleTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private CycleSchedule.Window window(String type,String date,String rule) throws Exception {
        return CycleSchedule.resolve(type,LocalDate.parse(date),LocalTime.NOON,ZoneId.of("Asia/Shanghai"),mapper.readTree("{\"cycleSchedule\":"+rule+"}"));
    }
    @Test void weekAcrossYearKeepsOriginalAttribution() throws Exception {
        var w=window("WEEK","2026-12-31","{\"dueDayOffset\":7}");
        assertThat(w.start()).isEqualTo("2026-12-28"); assertThat(w.end()).isEqualTo("2027-01-03");
        assertThat(w.dueAt().toLocalDate()).isEqualTo("2027-01-04");
        assertThat(w.key()).isEqualTo("WEEK:2026-12-28");
    }
    @Test void monthAttributionAndNextMonthSubmissionAreSeparate() throws Exception {
        var w=window("MONTH","2026-09-22","{\"dueMonthOffset\":1,\"dueDay\":5}");
        assertThat(w.start()).isEqualTo("2026-09-01"); assertThat(w.end()).isEqualTo("2026-09-30");
        assertThat(w.dueAt().toLocalDate()).isEqualTo("2026-10-05");
        assertThat(window("MONTH","2028-02-02","{\"dueMonthOffset\":0,\"dueDay\":31}").dueAt().toLocalDate()).isEqualTo("2028-02-29");
    }
    @Test void quarterAndMonthEndFollowCalendar() throws Exception {
        var w=window("QUARTER","2026-11-15","{\"dueMonthOffset\":3,\"dueDay\":10}");
        assertThat(w.start()).isEqualTo("2026-10-01"); assertThat(w.end()).isEqualTo("2026-12-31");
        assertThat(w.dueAt().toLocalDate()).isEqualTo("2027-01-10");
        assertThat(window("QUARTER","2026-09-22","{\"dueMonthOffset\":1,\"dueDay\":0}").dueAt().toLocalDate()).isEqualTo("2026-08-31");
    }
    @Test void invalidRulesFailClosedAndLegacyWeeklyIsUnchanged() throws Exception {
        assertThat(CycleSchedule.configured("WEEK",mapper.readTree("{}"))).isFalse();
        assertThat(CycleSchedule.configured("MONTH",null)).isFalse();
        assertThatThrownBy(()->window("MONTH","2026-09-22","{\"dueMonthOffset\":2,\"dueDay\":5}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->window("WEEK","2026-09-22","{\"dueDayOffset\":1.5}")).isInstanceOf(IllegalArgumentException.class);
    }
}
