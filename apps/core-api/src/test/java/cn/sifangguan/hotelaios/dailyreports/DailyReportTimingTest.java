package cn.sifangguan.hotelaios.dailyreports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;

class DailyReportTimingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nightReportFollowsEightAmHandoverNotAnInventedTwoAm() throws Exception {
        var timing = mapper.readTree("{\"mode\":\"SHIFT_END\",\"openOffsetMinutes\":-30,\"dueOffsetMinutes\":15}");
        var window = DailyReportTiming.shift(timing, OffsetDateTime.parse("2026-10-27T08:00:00+08:00"));
        assertThat(window.openAt()).isEqualTo("2026-10-27T07:30:00+08:00");
        assertThat(window.dueAt()).isEqualTo("2026-10-27T08:15:00+08:00");
    }

    @Test
    void housekeepingOpensAt1530AndAssistantAt1900() throws Exception {
        var hk = DailyReportTiming.fixed(mapper.readTree("""
                {"mode":"FIXED_LOCAL","openLocalTime":"15:30","dueLocalTime":"18:00"}
                """), LocalDate.of(2026,10,26), LocalTime.of(6,0), "Asia/Shanghai");
        assertThat(hk.openAt()).isEqualTo("2026-10-26T15:30:00+08:00");
        var agm = DailyReportTiming.fixed(mapper.readTree("""
                {"mode":"FIXED_LOCAL","openLocalTime":"19:00","dueLocalTime":"19:30"}
                """), LocalDate.of(2026,10,26), LocalTime.of(6,0), "Asia/Shanghai");
        assertThat(agm.dueAt()).isEqualTo("2026-10-26T19:30:00+08:00");
    }

    @Test
    void invalidOffsetsAndUnknownModeAreRejected() throws Exception {
        assertThatThrownBy(() -> DailyReportTiming.validate(mapper.readTree("""
                {"mode":"SHIFT_END","openOffsetMinutes":-30,"dueOffsetMinutes":4294967296}
                """))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DailyReportTiming.validate(mapper.readTree("{\"mode\":\"DEFAULT_02\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nightFinalSubmissionWaitsForHandoverRatherThanDraftOpening() throws Exception {
        var snapshot = mapper.readTree("""
                {"resolvedTemplate":{"deliveryTiming":{"mode":"SHIFT_END","openOffsetMinutes":-30,"dueOffsetMinutes":15}},
                 "deliveryPolicy":{"openAt":"2026-10-27T07:30:00+08:00","dueAt":"2026-10-27T08:15:00+08:00"}}
                """);
        assertThatThrownBy(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-27T07:59:00+08:00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("交接完成");
        assertThatCode(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-27T08:00:00+08:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void positiveOpeningOffsetCannotPermitFinalSubmissionBeforeItsOpening() throws Exception {
        var snapshot = mapper.readTree("""
                {"resolvedTemplate":{"deliveryTiming":{"mode":"SHIFT_END","openOffsetMinutes":10,"dueOffsetMinutes":15}},
                 "deliveryPolicy":{"openAt":"2026-10-27T08:10:00+08:00"}}
                """);
        assertThatThrownBy(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-27T08:05:00+08:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-27T08:10:00+08:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void fixedOpeningAndLegacySnapshotsKeepTheirExistingSubmissionBoundaries() throws Exception {
        var snapshot = mapper.readTree("""
                {"resolvedTemplate":{"deliveryTiming":{"mode":"FIXED_LOCAL","openLocalTime":"15:30","dueLocalTime":"18:00"}},
                 "deliveryPolicy":{"openAt":"2026-10-26T15:30:00+08:00"}}
                """);
        assertThatThrownBy(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-26T15:29:59+08:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> DailyReportTiming.validateFinalSubmission(snapshot, OffsetDateTime.parse("2026-10-26T15:30:00+08:00")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DailyReportTiming.validateFinalSubmission(mapper.createObjectNode(), OffsetDateTime.parse("2026-10-26T15:29:00+08:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void missingOrAmbiguousShiftsDoNotInventDeadlinesAndFrozenReportsSurviveShiftChanges() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table daily_report_template_version(tenant_id uuid,id uuid,configuration varchar)");
        jdbc.execute("create table work_duty_period(tenant_id uuid,position_assignment_id uuid,target_org_unit_id uuid,"
                +"business_date date,period_type varchar,status varchar,planned_start_at timestamp with time zone,planned_end_at timestamp with time zone)");
        jdbc.execute("create table daily_report(tenant_id uuid,position_assignment_id uuid,hotel_org_unit_id uuid,"
                +"business_date date,template_version_id uuid,current_revision_id uuid,report_deadline_at timestamp with time zone,created_at timestamp with time zone)");
        jdbc.execute("create table daily_report_revision(tenant_id uuid,id uuid,payload_snapshot varchar)");
        UUID tenant=UUID.randomUUID(),version=UUID.randomUUID(),assignment=UUID.randomUUID(),hotel=UUID.randomUUID(),revision=UUID.randomUUID();
        jdbc.update("insert into daily_report_template_version values(?,?,?)",tenant,version,
                "{\"deliveryTiming\":{\"mode\":\"SHIFT_END\",\"openOffsetMinutes\":-30,\"dueOffsetMinutes\":15}}");
        var named = new NamedParameterJdbcTemplate(dataSource);
        var date = LocalDate.of(2026,10,26);
        assertThatThrownBy(()->DailyReportTiming.resolve(named,mapper,tenant,version,assignment,hotel,date,LocalTime.of(6,0),"Asia/Shanghai"))
                .isInstanceOf(DailyReportTiming.UnresolvedShiftException.class);
        jdbc.update("insert into work_duty_period values(?,?,?,?,?,'PLANNED',?,?)",tenant,assignment,hotel,date,"SHIFT",
                OffsetDateTime.parse("2026-10-26T17:00:00+08:00"),OffsetDateTime.parse("2026-10-27T08:00:00+08:00"));
        assertThat(DailyReportTiming.resolve(named,mapper,tenant,version,assignment,hotel,date,LocalTime.of(6,0),"Asia/Shanghai").dueAt())
                .isEqualTo("2026-10-27T08:15:00+08:00");
        jdbc.execute("insert into work_duty_period select * from work_duty_period");
        assertThatThrownBy(()->DailyReportTiming.resolve(named,mapper,tenant,version,assignment,hotel,date,LocalTime.of(6,0),"Asia/Shanghai"))
                .isInstanceOf(DailyReportTiming.UnresolvedShiftException.class);
        jdbc.update("insert into daily_report_revision values(?,?,?)",tenant,revision,
                "{\"resolvedTemplate\":{\"deliveryTiming\":{\"mode\":\"SHIFT_END\",\"openOffsetMinutes\":-30,\"dueOffsetMinutes\":15}},\"deliveryPolicy\":{\"openAt\":\"2026-10-27T07:30:00+08:00\",\"dueAt\":\"2026-10-27T08:15:00+08:00\"}}");
        jdbc.update("insert into daily_report values(?,?,?,?,?,?,?,?)",tenant,assignment,hotel,date,version,revision,
                OffsetDateTime.parse("2026-10-27T08:15:00+08:00"),OffsetDateTime.parse("2026-10-27T07:40:00+08:00"));
        jdbc.execute("delete from work_duty_period");
        var frozen=DailyReportTiming.resolve(named,mapper,tenant,version,assignment,hotel,date,LocalTime.of(6,0),"Asia/Shanghai");
        assertThat(frozen.openAt()).isEqualTo("2026-10-27T07:30:00+08:00");
        assertThat(frozen.dueAt()).isEqualTo("2026-10-27T08:15:00+08:00");
        jdbc.update("insert into work_duty_period values(?,?,?,?,?,'PLANNED',?,?)",tenant,assignment,hotel,date,"SHIFT",
                OffsetDateTime.parse("2026-10-26T17:00:00+08:00"),OffsetDateTime.parse("2026-10-27T10:00:00+08:00"));
        var frozenSnapshot = mapper.readTree(jdbc.queryForObject("select payload_snapshot from daily_report_revision where id=?",String.class,revision));
        assertThatThrownBy(() -> DailyReportTiming.validateFinalSubmission(frozenSnapshot, OffsetDateTime.parse("2026-10-27T07:59:00+08:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> DailyReportTiming.validateFinalSubmission(frozenSnapshot, OffsetDateTime.parse("2026-10-27T08:00:00+08:00")))
                .doesNotThrowAnyException();
    }
}
