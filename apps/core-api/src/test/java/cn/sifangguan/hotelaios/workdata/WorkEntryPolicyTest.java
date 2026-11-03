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
        assertThat(daily.path("properties").size()).isEqualTo(5);
        assertThat(daily.path("properties").has("checkoutRooms")).isTrue();
        var lost = WorkEntryPolicy.schema(shared,"HKA_LOST_PROPERTY");
        assertThat(lost.path("required").size()).isEqualTo(6);
        assertThat(lost.path("properties").has("lostPropertyOccurred")).isFalse();
        assertThat(lost.path("properties").path("roomNumber").path("minLength").asInt()).isEqualTo(1);
    }

    @Test void plannedHygieneIsOptionalAndOnlyAddedToHousekeepingDailyReport() throws Exception {
        var original = json.readTree("""
          {"type":"object","additionalProperties":false,"required":[],"properties":{}}
          """);
        var schema = WorkEntryPolicy.schema(original,"HKA_DAILY_REPORT");
        assertThat(schema.path("required")).isEmpty();
        assertThat(schema.path("properties").path("plannedHygieneContent").path("title").asText())
                .isEqualTo("计划卫生内容（选填）");
        assertThat(schema.path("properties").path("plannedHygieneContent").path("maxLength").asInt()).isEqualTo(2000);
        assertThat(schema.path("properties").path("plannedHygieneRoomNumbers").path("maxLength").asInt()).isEqualTo(1000);
        var payload = json.readTree("""
          {"plannedHygieneContent":"清洗空调滤网","plannedHygieneRoomNumbers":"0101、A102"}
          """);
        assertThat(WorkEntryPolicy.validationPayload(payload,schema,original,"HKA_DAILY_REPORT")).isEqualTo(payload);
        assertThat(original.path("properties")).isEmpty();
        assertThat(WorkEntryPolicy.schema(original,"OTHER_ROLE_DAILY").path("properties")).isEmpty();
        assertThat(WorkEntryPolicy.schema(original,"HKA_GUEST_SERVICE_EVENT").path("properties")).isEmpty();
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

    @Test void clarifiesExceptionInputWithoutRewritingPublishedSchemaOrWeakeningItsRequirements() throws Exception {
        var original = json.readTree("""
          {"type":"object","additionalProperties":false,"required":["issueSummary","customerCount"],
           "properties":{"issueSummary":{"type":"string","title":"问题及处理","maxLength":1000},
                         "customerCount":{"type":"integer","minimum":0}}}
          """);
        var snapshot = original.deepCopy();
        var effective = WorkEntryPolicy.schema(original,"AGM_NS_REVIEW_TRAINING");

        assertThat(effective.path("properties").path("issueSummary").path("title").asText())
                .isEqualTo("异常及处理");
        assertThat(effective.path("properties").path("issueSummary").path("description").asText())
                .contains("实际问题", "工作结果说明", "好评率");
        assertThat(effective.path("properties").path("issueSummary").path("maxLength").asInt()).isEqualTo(1000);
        assertThat(effective.path("required")).isEqualTo(original.path("required"));
        assertThat(effective.path("additionalProperties").asBoolean()).isFalse();
        assertThat(original).isEqualTo(snapshot);
    }

    @Test void legacyIssueDetailsRemainEvidenceAndOrdinaryResultsDoNotImplyAnException() throws Exception {
        var original = json.readTree("""
          {"type":"object","additionalProperties":false,"required":[],"properties":{
           "workSummary":{"type":"string","title":"工作结果"},
           "issueSummary":{"type":"string","title":"问题及处理"},
           "customerCount":{"type":"integer"}}}
          """);
        var oldDraft = json.readTree("""
          {"workSummary":"旧草稿正常统计","issueSummary":"订单51，好评20，好评率38%","customerCount":51}
          """);
        var snapshot = oldDraft.deepCopy();
        var effective = WorkEntryPolicy.schema(original,"AGM_NS_REVIEW_TRAINING");
        var validated = WorkEntryPolicy.validationPayload(oldDraft,effective,original,"AGM_NS_REVIEW_TRAINING");

        assertThat(validated.path("issueSummary").asText()).isEqualTo("订单51，好评20，好评率38%");
        assertThat(validated.path("customerCount").asInt()).isEqualTo(51);
        assertThat(WorkEntryPolicy.policyValue(validated,"issueFound").asBoolean()).isTrue();
        assertThat(oldDraft).isEqualTo(snapshot);
        assertThat(WorkEntryPolicy.policyValue(json.readTree("""
          {"workSummary":"订单51，好评20，好评率38%","customerCount":51}
          """),"issueFound").asBoolean()).isFalse();
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
