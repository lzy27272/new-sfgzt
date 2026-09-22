package cn.sifangguan.hotelaios.workdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkEntryPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void removesOnlyGenericQuestionsAndRetainsSpecialistFacts() throws Exception {
        var source = json.readTree("""
          {"required":["issueFound","workSummary","checkoutRooms","aiExtractionConfirmed"],
           "properties":{"issueFound":{"title":"是否有异常","type":"boolean"},
           "workSummary":{"title":"工作结果","type":"string"},
           "checkoutRooms":{"type":"integer"},"aiExtractionConfirmed":{"type":"boolean"}}}
          """);
        var effective = WorkEntryPolicy.schema(source, "OTHER_ROLE_WEEKLY");
        assertThat(effective.path("properties").has("issueFound")).isFalse();
        assertThat(effective.path("properties").has("workSummary")).isFalse();
        assertThat(effective.path("properties").has("checkoutRooms")).isTrue();
        assertThat(effective.path("properties").has("aiExtractionConfirmed")).isTrue();
        assertThat(effective.path("required").size()).isEqualTo(2);
        assertThat(source.path("properties").has("issueFound")).isTrue();
    }

    @Test void housekeepingItemsDoNotReuseDailyReportOrLostPropertyFields() throws Exception {
        var shared = json.readTree("""
          {"required":["workSummary","issueFound"],"properties":{
           "workSummary":{"type":"string"},"issueFound":{"type":"boolean"},
           "checkoutRooms":{"type":"integer"},"stayoverRooms":{"type":"integer"},
           "inventoryQuantities":{"type":"object"},"lostPropertyOccurred":{"type":"boolean"},
           "handoverRecipientAssignmentId":{"type":"string"}}}
          """);
        assertThat(WorkEntryPolicy.schema(shared,"HKA_ITEM_ISSUE").path("properties").size()).isEqualTo(1);
        assertThat(WorkEntryPolicy.schema(shared,"HKA_CART_PREPARATION").path("properties").size()).isEqualTo(1);
        var daily = WorkEntryPolicy.schema(shared,"HKA_DAILY_REPORT");
        assertThat(daily.path("properties").size()).isEqualTo(3);
        assertThat(daily.path("properties").has("checkoutRooms")).isTrue();
        var lost = WorkEntryPolicy.schema(shared,"HKA_LOST_PROPERTY");
        assertThat(lost.path("required").size()).isEqualTo(6);
        assertThat(lost.path("properties").has("lostPropertyOccurred")).isFalse();
        assertThat(lost.path("properties").path("roomNumber").path("minLength").asInt()).isEqualTo(1);
    }

    @Test void staleDraftsValidateWithoutRewritingTheirEvidenceFacts() throws Exception {
        var original = json.readTree("""
          {"properties":{"issueFound":{"type":"boolean"},"serviceContent":{"type":"string"}}}
          """);
        var payload = json.readTree("{\"issueFound\":false,\"serviceContent\":\"送水\"}");
        var effective = WorkEntryPolicy.validationPayload(payload,WorkEntryPolicy.schema(original,"HKA_GUEST_SERVICE_EVENT"),original,"HKA_GUEST_SERVICE_EVENT");
        assertThat(effective.has("issueFound")).isFalse();
        assertThat(effective.path("serviceContent").asText()).isEqualTo("送水");
        assertThat(payload.has("issueFound")).isTrue();
    }

    @Test void migratedDraftWithNewSchemaStillIgnoresObsoleteSharedQuestions() throws Exception {
        var slim = json.readTree("""
          {"additionalProperties":false,"properties":{"notes":{"type":"string"}}}
          """);
        var payload = json.readTree("""
          {"workSummary":"旧草稿","issueFound":false,"checkoutRooms":2,"notes":"已整理","unknownField":"retain validation"}
          """);
        var effective = WorkEntryPolicy.validationPayload(payload,slim,slim,"HKA_CART_PREPARATION");
        assertThat(effective.has("checkoutRooms")).isFalse();
        assertThat(effective.has("workSummary")).isFalse();
        assertThat(effective.has("unknownField")).isTrue();
        assertThat(effective.path("notes").asText()).isEqualTo("已整理");
    }

    @Test void conditionalEvidenceUsesIssueDetailsWithoutRestoringTheRemovedQuestion() throws Exception {
        assertThat(WorkEntryPolicy.policyValue(json.readTree("{\"issueSummary\":\"门锁损坏\"}"),"issueFound").asBoolean()).isTrue();
        assertThat(WorkEntryPolicy.policyValue(json.readTree("{\"issueSummary\":\"无异常\",\"issueFound\":true}"),"issueFound").asBoolean()).isFalse();
        assertThat(WorkEntryPolicy.policyValue(json.readTree("{}"),"issueFound").asBoolean()).isFalse();
        assertThat(WorkEntryPolicy.policyValue(json.readTree("{\"specialArrivalRequirement\":true}"),"specialArrivalRequirement").asBoolean()).isTrue();
    }

    @Test void onlyTheThreeEventsAreOptionalIncludingTheirReportReferences() {
        for (String code : WorkEntryPolicy.OCCURRENCE_ITEMS) {
            assertThat(WorkEntryPolicy.occursOnDemand(code)).isTrue();
            assertThat(WorkEntryPolicy.occursOnDemand(code.toLowerCase()+"_fact")).isTrue();
        }
        assertThat(WorkEntryPolicy.occursOnDemand("HKA_DAILY_REPORT")).isFalse();
        assertThat(WorkEntryPolicy.occursOnDemand(null)).isFalse();
    }
}
