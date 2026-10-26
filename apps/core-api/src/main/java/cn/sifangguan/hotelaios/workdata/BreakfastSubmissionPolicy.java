package cn.sifangguan.hotelaios.workdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

final class BreakfastSubmissionPolicy {
    private BreakfastSubmissionPolicy() { }

    static void validate(JsonNode executionPolicy, LocalDate businessDate, ZoneId zone, OffsetDateTime now) {
        JsonNode stages = executionPolicy.path("breakfastStages");
        if (stages.isMissingNode() || stages.isNull()) return;
        if (!stages.path("scheduleConfirmed").asBoolean(false))
            throw new IllegalArgumentException("早餐实际营业时段尚未确认，请管理员配置后再提交");
        LocalTime closingStart;
        try { closingStart = LocalTime.parse(stages.path("closing").path("start").asText()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("早餐收尾阶段开始时间配置无效，请联系管理员"); }
        if (now.isBefore(businessDate.atTime(closingStart).atZone(zone).toOffsetDateTime()))
            throw new IllegalArgumentException("尚未到早餐收尾阶段，可先保存各阶段草稿，完成收尾后再最终提交");
    }
}
