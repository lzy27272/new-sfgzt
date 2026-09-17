package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Evaluates hotel capability flags before an expectation is created. */
final class ApplicabilityPolicyEvaluator {
    private static final Map<String, String> OPERATIONAL_FEATURE_KEYS = Map.of(
            "KITCHEN", "kitchen",
            "AFTERNOON_TEA", "afternoonTea",
            "BAR", "bar",
            "PROJECTION", "projection",
            "GYM", "gym",
            "TERRACE", "terrace",
            "TRENDY_PLAY_AREA", "trendyPlayArea",
            "BREAKFAST_AREA", "breakfastArea");
    private ApplicabilityPolicyEvaluator() {
    }

    static Result evaluate(JsonNode policy, JsonNode hotelProfile) {
        String requiredAssistantMode = policy == null
                ? ""
                : policy.path("assistantWorkTemplateMode").asText("").trim();
        if (!requiredAssistantMode.isBlank()) {
            String configuredMode = hotelProfile == null
                    ? ""
                    : hotelProfile.path("assistant_work_template_mode").asText("").trim();
            if (!requiredAssistantMode.equals(configuredMode)) {
                return new Result(false, "ASSISTANT_WORK_TEMPLATE_MODE_MISMATCH:" + requiredAssistantMode);
            }
        }
        List<String> required = new ArrayList<>();
        if (policy != null && policy.path("requiresBreakfastService").asBoolean(false)) {
            required.add("breakfast_service_enabled");
        }
        String requiredFacility = policy == null ? "" : policy.path("requiresFacility").asText("").trim();
        if (!requiredFacility.isBlank() && !hasFacility(hotelProfile, requiredFacility)) {
            return new Result(false, "HOTEL_FACILITY_FALSE_OR_MISSING:" + requiredFacility);
        }
        JsonNode configured = policy == null ? null : policy.get("requiredHotelProfileFlags");
        if (configured != null && !configured.isNull()) {
            if (!configured.isArray()) {
                throw new IllegalArgumentException("requiredHotelProfileFlags必须是字符串数组");
            }
            for (JsonNode flag : configured) {
                if (!flag.isTextual() || !flag.asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("酒店能力标识无效");
                }
                required.add(flag.asText());
            }
        }
        for (String flag : required) {
            if (!enabledHotelFlag(hotelProfile, flag)) {
                return new Result(false, "HOTEL_PROFILE_FLAG_FALSE_OR_MISSING:" + flag);
            }
        }
        return new Result(true, null);
    }

    private static boolean enabledHotelFlag(JsonNode hotelProfile, String flag) {
        if (hotelProfile == null) return false;
        if (hotelProfile.path(flag).asBoolean(false)) return true;
        return hotelProfile.path("operational_feature_flags").path(flag).asBoolean(false);
    }

    private static boolean hasFacility(JsonNode hotelProfile, String facilityCode) {
        if (hotelProfile == null) return false;
        String normalized = facilityCode.trim().toUpperCase();
        for (String arrayField : List.of("facilities", "facility_codes")) {
            JsonNode values = hotelProfile.path(arrayField);
            if (values.isArray()) {
                for (JsonNode value : values) {
                    if (normalized.equals(value.asText("").trim().toUpperCase())) return true;
                }
            }
        }
        JsonNode flags = hotelProfile.path("facility_flags");
        if (flags.path(normalized).asBoolean(false)
                || flags.path(normalized.toLowerCase()).asBoolean(false)) return true;
        String operationalKey = OPERATIONAL_FEATURE_KEYS.get(normalized);
        if (operationalKey != null
                && hotelProfile.path("operational_feature_flags").path(operationalKey).asBoolean(false)) {
            return true;
        }
        String booleanField = normalized.toLowerCase() + "_enabled";
        return hotelProfile.path(booleanField).asBoolean(false);
    }

    static void validatePolicy(JsonNode policy) {
        evaluate(policy, null);
    }

    record Result(boolean applicable, String reason) {
    }
}
