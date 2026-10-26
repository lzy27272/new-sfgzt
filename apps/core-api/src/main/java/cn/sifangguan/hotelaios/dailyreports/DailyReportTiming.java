package cn.sifangguan.hotelaios.dailyreports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** New template versions opt in; legacy reports and their frozen deadlines are unchanged. */
public final class DailyReportTiming {
    private DailyReportTiming() { }

    static Window resolve(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, UUID tenantId,
                          UUID versionId, UUID assignmentId, UUID hotelId, LocalDate date,
                          LocalTime cutoff, String timezone) {
        String raw = jdbc.queryForObject("""
                select configuration::text from daily_report_template_version
                where tenant_id=:tenantId and id=:versionId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("versionId", versionId), String.class);
        JsonNode timing;
        try {
            timing = mapper.readTree(raw).get("deliveryTiming");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("日报时间配置无效", exception);
        }
        if (timing == null || timing.isNull()) return null;
        validate(timing);
        // A later shift edit must not move an already-materialized report or its reminders.
        var existing = jdbc.query("""
                select r.report_deadline_at, r.created_at, v.payload_snapshot::text as payload_snapshot
                from daily_report r join daily_report_revision v
                  on v.tenant_id=r.tenant_id and v.id=r.current_revision_id
                where r.tenant_id=:tenantId and r.position_assignment_id=:assignmentId
                  and r.hotel_org_unit_id=:hotelId and r.business_date=:date
                  and r.template_version_id=:versionId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("assignmentId", assignmentId)
                .addValue("hotelId", hotelId).addValue("date", date).addValue("versionId", versionId),
                (rs, row) -> {
                    try {
                        JsonNode frozen = mapper.readTree(rs.getString("payload_snapshot")).path("deliveryPolicy");
                        return new Window(frozen.hasNonNull("openAt") ? OffsetDateTime.parse(frozen.path("openAt").asText())
                                : rs.getObject("created_at", OffsetDateTime.class),
                                frozen.hasNonNull("dueAt") ? OffsetDateTime.parse(frozen.path("dueAt").asText())
                                        : rs.getObject("report_deadline_at", OffsetDateTime.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                        throw new IllegalArgumentException("已保存的日报时间快照无效", exception);
                    }
                });
        if (!existing.isEmpty()) return existing.getFirst();
        if ("FIXED_LOCAL".equals(timing.path("mode").asText())) {
            return fixed(timing, date, cutoff, timezone);
        }
        List<OffsetDateTime> shifts = jdbc.query("""
                select planned_end_at from work_duty_period
                where tenant_id=:tenantId and position_assignment_id=:assignmentId
                  and target_org_unit_id=:hotelId and business_date=:date
                  and period_type='SHIFT' and status<>'CANCELLED'
                order by planned_start_at limit 2
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("assignmentId", assignmentId)
                .addValue("hotelId", hotelId).addValue("date", date),
                (rs, row) -> rs.getObject("planned_end_at", OffsetDateTime.class));
        if (shifts.size() != 1) throw new UnresolvedShiftException();
        return shift(timing, shifts.getFirst());
    }

    public static void validate(JsonNode timing) {
        if (!timing.isObject()) throw new IllegalArgumentException("日报deliveryTiming必须是对象");
        String mode = timing.path("mode").asText();
        if ("SHIFT_END".equals(mode)) {
            int open = offset(timing, "openOffsetMinutes");
            int due = offset(timing, "dueOffsetMinutes");
            if (due <= open) throw new IllegalArgumentException("日报截止必须晚于开放时间");
        } else if ("FIXED_LOCAL".equals(mode)) {
            LocalTime.parse(timing.path("openLocalTime").asText());
            LocalTime.parse(timing.path("dueLocalTime").asText());
        } else throw new IllegalArgumentException("日报时间模式只支持FIXED_LOCAL或SHIFT_END");
    }

    static Window shift(JsonNode timing, OffsetDateTime end) {
        validate(timing);
        return new Window(end.plusMinutes(offset(timing, "openOffsetMinutes")),
                end.plusMinutes(offset(timing, "dueOffsetMinutes")));
    }

    static Window fixed(JsonNode timing, LocalDate date, LocalTime cutoff, String timezone) {
        validate(timing);
        OffsetDateTime open = scheduled(date, LocalTime.parse(timing.path("openLocalTime").asText()), cutoff, timezone);
        OffsetDateTime due = scheduled(date, LocalTime.parse(timing.path("dueLocalTime").asText()), cutoff, timezone);
        if (!due.isAfter(open)) throw new IllegalArgumentException("日报截止必须晚于开放时间");
        return new Window(open, due);
    }

    static void validateFinalSubmission(JsonNode snapshot, OffsetDateTime now) {
        JsonNode frozenOpen = snapshot.path("deliveryPolicy").path("openAt");
        if (!frozenOpen.isTextual()) return;
        OffsetDateTime finalNotBefore = OffsetDateTime.parse(frozenOpen.asText());
        JsonNode timing = snapshot.path("resolvedTemplate").path("deliveryTiming");
        boolean shiftRelative = "SHIFT_END".equals(timing.path("mode").asText());
        if (shiftRelative) {
            // Use the report's frozen window, never a subsequently edited duty period.
            OffsetDateTime shiftEnd = finalNotBefore.minusMinutes(offset(timing, "openOffsetMinutes"));
            if (shiftEnd.isAfter(finalNotBefore)) finalNotBefore = shiftEnd;
        }
        if (now.isBefore(finalNotBefore)) {
            throw new IllegalArgumentException(shiftRelative
                    ? "请在本班最后检查与交接完成后再提交日报；当前可继续保存草稿（最早提交时间：" + finalNotBefore + "）"
                    : "尚未到日报最终提交时间，可继续保存当班记录和遗留物草稿");
        }
    }

    private static int offset(JsonNode timing, String field) {
        JsonNode value = timing.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || Math.abs(value.longValue()) > 2880) {
            throw new IllegalArgumentException("日报" + field + "须为48小时内的整数分钟");
        }
        return value.intValue();
    }

    private static OffsetDateTime scheduled(LocalDate date, LocalTime time, LocalTime cutoff, String zone) {
        return (time.isBefore(cutoff) ? date.plusDays(1) : date).atTime(time)
                .atZone(ZoneId.of(zone)).toOffsetDateTime();
    }

    record Window(OffsetDateTime openAt, OffsetDateTime dueAt) { }

    static final class UnresolvedShiftException extends IllegalArgumentException {
        UnresolvedShiftException() {
            super("本营业日尚未配置唯一实际班次，请管理员核对排班后再填写日报；系统不会默认02:00下班");
        }
    }
}
