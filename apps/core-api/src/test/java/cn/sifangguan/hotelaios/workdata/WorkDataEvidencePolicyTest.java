package cn.sifangguan.hotelaios.workdata;

import cn.sifangguan.hotelaios.dailyreports.DailyReportRoutineProjectionService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkDataEvidencePolicyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void explicitZeroMinimumRemainsOptionalWhileMatchedConditionDefaultsToOne() throws Exception {
        JsonNode optional = json("""
                {"checkpointCode":"issue","minimum":0}
                """);
        JsonNode conditionalWithoutMinimum = json("""
                {"checkpointCode":"issue","requiredWhen":{"field":"issueFound","equals":true}}
                """);

        assertThat(WorkDataService.baseEvidenceMinimum(optional, false)).isZero();
        assertThat(WorkDataService.baseEvidenceMinimum(optional, true)).isZero();
        assertThat(WorkDataService.baseEvidenceMinimum(conditionalWithoutMinimum, true)).isEqualTo(1);
    }

    @Test
    void dynamicPerInstanceUsesPayloadArrayWithoutRequiredInstances() throws Exception {
        JsonNode dynamic = json("""
                {"minimumPerInstance":1,"instanceField":"roomNumbers"}
                """);
        JsonNode fixed = json("""
                {"minimumPerInstance":1,"instanceField":"roomNumbers","requiredInstances":2}
                """);
        JsonNode payload = json("""
                {"roomNumbers":["1201","1202","1203"]}
                """);

        assertThat(WorkDataService.evidenceInstanceKeys(payload, dynamic, "逐房照片", "房号"))
                .containsExactly("1201", "1202", "1203");
        assertThatThrownBy(() -> WorkDataService.evidenceInstanceKeys(payload, fixed, "逐房照片", "房号"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须填写2个房号");
    }

    @Test
    void conditionalFacilityUsesExistingHotelOperationalFlagsAndMissingMeansNotConfigured() throws Exception {
        JsonNode configured = json("""
                {"trendyPlayArea":true,"gym":false}
                """);

        assertThat(WorkDataService.configuredFacilityEnabled(configured, "TRENDY_PLAY_AREA")).isTrue();
        assertThat(WorkDataService.configuredFacilityEnabled(configured, "GYM")).isFalse();
        assertThat(WorkDataService.configuredFacilityEnabled(configured, "TERRACE")).isFalse();
        assertThatThrownBy(() -> WorkDataService.configuredFacilityEnabled(configured, "UNKNOWN_ROOM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知conditionalFacility");
    }

    @Test
    void configuredFacilityAndAssetRulesUseAuthoritativeHotelConfiguration() throws Exception {
        JsonNode hotelConfiguration = json("""
                {
                  "facilityInstances":{"public_area":["LOBBY","GYM"]},
                  "assetInstances":{"equipment_temperature":["AIR_UNIT_1","AIR_UNIT_2"]}
                }
                """);
        JsonNode requirement = json("{} ");

        assertThat(WorkDataService.configuredInstanceKeys(
                hotelConfiguration, requirement, "public_area", "facility"))
                .containsExactly("LOBBY", "GYM");
        assertThat(WorkDataService.configuredInstanceKeys(
                hotelConfiguration, requirement, "equipment_temperature", "asset"))
                .containsExactly("AIR_UNIT_1", "AIR_UNIT_2");
        assertThat(WorkDataService.configuredInstanceKeys(
                json("""
                        {"assetInstances":{"equipment_temperature":["1号空气能机组（东侧）"]}}
                        """), requirement, "equipment_temperature", "asset"))
                .containsExactly("1号空气能机组（东侧）");
        assertThatThrownBy(() -> WorkDataService.configuredInstanceKeys(
                json("{}"), requirement, "equipment_temperature", "asset"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请管理员配置门店证据清单");

        JsonNode noFacilities = json("""
                {"facilityInstances":{"public_area":[]}}
                """);
        assertThatThrownBy(() -> WorkDataService.configuredInstanceKeys(
                noFacilities, requirement, "public_area", "facility"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");

        JsonNode employeePayloadShape = json("""
                {"facilityInstances":{"public_area":["EMPLOYEE_SUPPLIED"]}}
                """);
        assertThatThrownBy(() -> WorkDataService.configuredInstanceKeys(
                employeePayloadShape, requirement, "equipment_temperature", "asset"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请管理员配置门店证据清单");
    }

    @Test
    void submissionCannotUseEmployeePayloadToBypassEmptyHotelConfiguration() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForMap(contains("select submission_policy::text"), any(MapSqlParameterSource.class)))
                .thenReturn(Map.of(
                        "submission_policy", """
                                {"attachmentRequired":false,"evidenceRequirements":[{
                                  "checkpointCode":"public_area",
                                  "minimumPerConfiguredFacility":1,
                                  "configuredFacilityInstanceField":"publicAreaFacilityIds"
                                }]}
                                """,
                        "applicability_policy", "{}",
                        "execution_policy", "{}"));
        when(jdbc.queryForList(contains("evidence_instance_configuration"),
                any(MapSqlParameterSource.class))).thenReturn(List.of(Map.of(
                        "evidence_configuration", """
                                {"facilityInstances":{"publicAreaFacilityIds":[]},
                                 "assetInstances":{}}
                                """)));
        WorkDataService service = new WorkDataService(
                jdbc,
                mock(TenantDatabaseContext.class),
                mock(AccessPolicy.class),
                mock(WorkVisibilityPolicy.class),
                mock(AuditWriter.class),
                mock(FormPayloadValidator.class),
                objectMapper,
                mock(DailyReportRoutineProjectionService.class),
                mock(cn.sifangguan.hotelaios.storeassets.StoreWorkUsageService.class),
                mock(cn.sifangguan.hotelaios.workpackage.WeeklyAssessmentService.class),
                mock(cn.sifangguan.hotelaios.notifications.StoreManagerSupervisorResolver.class));

        assertThatThrownBy(() -> invokeValidation(service, json("""
                {"publicAreaFacilityIds":[],
                 "facilityInstances":{"public_area":["EMPLOYEE_SUPPLIED"]}}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请管理员配置门店证据清单")
                .hasMessageContaining("不能为空");
    }

    @Test
    void roomCountFormulaRequiresStructuredInputsAndEnoughDistinctRooms() throws Exception {
        JsonNode policy = json("""
                {"requiredRoomCountFormula":"max(ceil(completedRooms*0.20),onDutyHousekeepers*5)"}
                """);
        JsonNode valid = json("""
                {"completedRooms":21,"onDutyHousekeepers":1,
                 "roomNumbers":["1201","1202","1203","1204","1205"]}
                """);
        JsonNode insufficient = json("""
                {"completedRooms":31,"onDutyHousekeepers":1,
                 "roomNumbers":["1201","1202","1203","1204","1205","1206"]}
                """);

        assertThat(WorkDataService.validateRequiredRoomCountFormula(policy, valid)).isEqualTo(5);
        assertThatThrownBy(() -> WorkDataService.validateRequiredRoomCountFormula(policy, insufficient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要检查7间");
        assertThatThrownBy(() -> WorkDataService.validateRequiredRoomCountFormula(policy,
                json("{\"roomNumbers\":[]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completedRooms");
    }

    @Test
    void nonApplicableItemRequiresExplicitConditionButSkipsEvidenceGate() throws Exception {
        JsonNode lostPropertyPolicy = json("""
                {"conditionField":"lostPropertyOccurred","equals":true,
                 "excludeFromCompletionWhenNotApplicable":true}
                """);
        JsonNode prearrivalPolicy = json("""
                {"condition":"HAS_SPECIAL_ARRIVAL_REQUIREMENT",
                 "excludeFromCompletionWhenNotApplicable":true}
                """);

        assertThat(WorkDataService.submissionApplicable(
                lostPropertyPolicy, json("{\"lostPropertyOccurred\":false}"))).isFalse();
        assertThat(WorkDataService.submissionApplicable(
                lostPropertyPolicy, json("{\"lostPropertyOccurred\":true}"))).isTrue();
        assertThat(WorkDataService.submissionApplicable(
                prearrivalPolicy, json("{\"specialArrivalRequirement\":false}"))).isFalse();
        assertThatThrownBy(() -> WorkDataService.submissionApplicable(
                lostPropertyPolicy, json("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须明确填写适用条件字段lostPropertyOccurred");
    }

    @Test
    void itemApplicabilitySkipsGlobalAttachmentRequiredAndFormulaComesFromExecutionPolicy() throws Exception {
        JsonNode notApplicablePayload = json("{\"lostPropertyOccurred\":false}");
        WorkDataService nonApplicableService = serviceWithPolicies(
                "{\"attachmentRequired\":true,\"evidenceRequirements\":[{\"minimum\":1}]}",
                "{\"conditionField\":\"lostPropertyOccurred\",\"equals\":true,"
                        + "\"excludeFromCompletionWhenNotApplicable\":true}",
                "{}");

        assertThatCode(() -> invokeValidation(nonApplicableService, notApplicablePayload))
                .doesNotThrowAnyException();

        WorkDataService formulaService = serviceWithPolicies(
                "{\"attachmentRequired\":false,\"evidenceRequirements\":[]}",
                "{}",
                "{\"requiredRoomCountFormula\":"
                        + "\"max(ceil(completedRooms*0.20),onDutyHousekeepers*5)\"}");
        assertThatThrownBy(() -> invokeValidation(formulaService, json("{\"roomNumbers\":[]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completedRooms");
    }

    @Test
    void allowedKindsAreMappedToStoredMimeTypes() throws Exception {
        JsonNode images = json("{\"allowedKinds\":[\"IMAGE\"]}");
        JsonNode documents = json("{\"allowedKinds\":[\"PDF\",\"WORD\",\"EXCEL\"]}");

        assertThat(WorkDataService.mediaTypeAllowed("image/jpeg", images)).isTrue();
        assertThat(WorkDataService.mediaTypeAllowed("application/pdf", images)).isFalse();
        assertThat(WorkDataService.mediaTypeAllowed("application/pdf", documents)).isTrue();
        assertThat(WorkDataService.mediaTypeAllowed(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", documents)).isTrue();
        assertThat(WorkDataService.mediaTypeAllowed("image/png", documents)).isFalse();
    }

    @Test
    void serviceAlbumEvidenceSatisfiesUnrestrictedPhotoPolicyButNotExplicitCameraPolicy() throws Exception {
        String requirement="\"checkpointCode\":\"guest_service_photo\",\"label\":\"服务照片\",\"minimum\":1,\"allowedKinds\":[\"IMAGE\"]";
        String policy="{\"attachmentRequired\":true,\"evidenceRequirements\":[{"+requirement+"}]}";
        WorkDataService service=serviceWithPolicies(policy,"{}","{}");
        var jdbc=(NamedParameterJdbcTemplate)ReflectionTestUtils.getField(service,"jdbc");
        when(jdbc.queryForObject(contains("select count(*) from attachment"),any(MapSqlParameterSource.class),eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(contains("from attachment"),any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            var parameters=invocation.getArgument(1,MapSqlParameterSource.class);
            assertThat(parameters.getValue("checkpointCode")).isEqualTo("guest_service_photo");
            // Stored photo is FILE_PICKER without a client capture timestamp. SQL must not require CAMERA.
            return "CAMERA".equals(parameters.getValue("captureSource")) ? List.of() : List.of(Map.of("media_type","image/jpeg","evidence_metadata","{}"));
        });
        TenantPrincipal principal=new TenantPrincipal(UUID.randomUUID(),UUID.randomUUID(),"TEST",Set.of(),UUID.randomUUID());
        UUID item=UUID.randomUUID(),record=UUID.randomUUID(),hotel=UUID.randomUUID();
        JsonNode payload=json("{\"roomNumber\":\"1208\",\"serviceContent\":\"补送浴巾\"}");
        assertThatCode(()->ReflectionTestUtils.invokeMethod(service,"validateSubmissionPolicy",principal,item,record,payload,hotel,null,null,null)).doesNotThrowAnyException();
        when(jdbc.queryForMap(contains("select submission_policy::text"),any(MapSqlParameterSource.class)))
            .thenReturn(Map.of("submission_policy","{\"attachmentRequired\":true,\"evidenceRequirements\":[{"+requirement+",\"captureSource\":\"CAMERA\"}]}","applicability_policy","{}","execution_policy","{}"));
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"validateSubmissionPolicy",principal,item,record,payload,hotel,null,null,null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("服务照片至少需要1份");
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private WorkDataService serviceWithPolicies(
            String submissionPolicy,
            String applicabilityPolicy,
            String executionPolicy
    ) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForMap(contains("select submission_policy::text"), any(MapSqlParameterSource.class)))
                .thenReturn(Map.of(
                        "submission_policy", submissionPolicy,
                        "applicability_policy", applicabilityPolicy,
                        "execution_policy", executionPolicy));
        return new WorkDataService(
                jdbc,
                mock(TenantDatabaseContext.class),
                mock(AccessPolicy.class),
                mock(WorkVisibilityPolicy.class),
                mock(AuditWriter.class),
                mock(FormPayloadValidator.class),
                objectMapper,
                mock(DailyReportRoutineProjectionService.class),
                mock(cn.sifangguan.hotelaios.storeassets.StoreWorkUsageService.class),
                mock(cn.sifangguan.hotelaios.workpackage.WeeklyAssessmentService.class),
                mock(cn.sifangguan.hotelaios.notifications.StoreManagerSupervisorResolver.class));
    }

    private void invokeValidation(WorkDataService service, JsonNode payload) {
        UUID tenantId = UUID.randomUUID();
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, UUID.randomUUID(), "TEST", Set.of(), UUID.randomUUID());
        ReflectionTestUtils.invokeMethod(
                service,
                "validateSubmissionPolicy",
                principal,
                UUID.randomUUID(),
                null,
                payload,
                UUID.randomUUID(),
                "已明确记录适用情况",
                null,
                null);
    }
}
