package cn.sifangguan.hotelaios.workdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class BreakfastSubmissionPolicyTest {
    @Test void finalSubmitCannotSkipTheConfiguredClosingStage() throws Exception {
        var policy = new ObjectMapper().readTree("{\"breakfastStages\":{\"scheduleConfirmed\":true,\"closing\":{\"start\":\"10:00\"}}}");
        LocalDate day=LocalDate.of(2026,10,26); ZoneId zone=ZoneId.of("Asia/Shanghai");
        assertThatThrownBy(() -> BreakfastSubmissionPolicy.validate(policy,day,zone,OffsetDateTime.parse("2026-10-26T09:59:59+08:00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("先保存");
        assertThatCode(() -> BreakfastSubmissionPolicy.validate(policy,day,zone,OffsetDateTime.parse("2026-10-26T10:00:00+08:00"))).doesNotThrowAnyException();
    }
    @Test void realConfiguredHoursAndUnconfirmedHoursAreNotReplacedWithExampleTimes() throws Exception {
        var mapper=new ObjectMapper(); LocalDate day=LocalDate.of(2026,10,26); ZoneId zone=ZoneId.of("Asia/Shanghai");
        var later=mapper.readTree("{\"breakfastStages\":{\"scheduleConfirmed\":true,\"closing\":{\"start\":\"11:00\"}}}");
        assertThatThrownBy(() -> BreakfastSubmissionPolicy.validate(later,day,zone,OffsetDateTime.parse("2026-10-26T10:30:00+08:00"))).isInstanceOf(IllegalArgumentException.class);
        var pending=mapper.readTree("{\"breakfastStages\":{\"scheduleConfirmed\":false,\"closing\":{\"start\":\"10:00\"}}}");
        assertThatThrownBy(() -> BreakfastSubmissionPolicy.validate(pending,day,zone,OffsetDateTime.parse("2026-10-26T11:30:00+08:00"))).hasMessageContaining("尚未确认");
        assertThatCode(() -> BreakfastSubmissionPolicy.validate(mapper.createObjectNode(),day,zone,OffsetDateTime.now())).doesNotThrowAnyException();
    }
}
