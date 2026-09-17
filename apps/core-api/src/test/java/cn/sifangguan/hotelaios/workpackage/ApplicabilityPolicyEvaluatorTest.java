package cn.sifangguan.hotelaios.workpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicabilityPolicyEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingRequiredFacilityIsExcludedBeforeItCanEnterCompletionDenominator() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("""
                        {"requiredHotelProfileFlags":["breakfast_service_enabled"]}
                        """),
                objectMapper.readTree("""
                        {"breakfast_service_enabled":false}
                        """));

        assertThat(result.applicable()).isFalse();
        assertThat(result.reason()).contains("breakfast_service_enabled");
    }

    @Test
    void enabledFacilityRemainsApplicable() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("{\"requiresBreakfastService\":true}"),
                objectMapper.readTree("{\"breakfast_service_enabled\":true}"));

        assertThat(result.applicable()).isTrue();
    }

    @Test
    void unavailableNamedFacilityIsFailClosedAndExcluded() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("{\"requiresFacility\":\"AFTERNOON_TEA\"}"),
                objectMapper.readTree("{\"facility_codes\":[\"KITCHEN\"]}"));

        assertThat(result.applicable()).isFalse();
        assertThat(result.reason()).contains("AFTERNOON_TEA");
    }

    @Test
    void namedFacilityUsesHotelOperationalFeatureConfiguration() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("{\"requiresFacility\":\"AFTERNOON_TEA\"}"),
                objectMapper.readTree("""
                        {"operational_feature_flags":{"afternoonTea":true}}
                        """));

        assertThat(result.applicable()).isTrue();
    }

    @Test
    void requiredHotelProfileFlagCanComeFromOperationalFeatureFlags() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("{\"requiredHotelProfileFlags\":[\"kitchen\"]}"),
                objectMapper.readTree("{\"operational_feature_flags\":{\"kitchen\":true}}"));

        assertThat(result.applicable()).isTrue();
    }

    @Test
    void assistantTemplateModeMustMatchTheCurrentHotelConfiguration() throws Exception {
        var result = ApplicabilityPolicyEvaluator.evaluate(
                objectMapper.readTree("{\"assistantWorkTemplateMode\":\"WITHOUT_FRONT_OFFICE_SUPERVISOR\"}"),
                objectMapper.readTree("{\"assistant_work_template_mode\":\"WITH_FRONT_OFFICE_SUPERVISOR\"}"));

        assertThat(result.applicable()).isFalse();
        assertThat(result.reason()).contains("ASSISTANT_WORK_TEMPLATE_MODE_MISMATCH");
    }
}
