package cn.sifangguan.hotelaios;

import cn.sifangguan.hotelaios.dailyreporttemplates.DailyReportTemplateSnapshotReader;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DailyReportFlowIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String GENERAL_MANAGER = "19000000-0000-0000-0000-000000000002";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_SUPERVISOR = "19000000-0000-0000-0000-000000000005";
    private static final String ASSISTANT_GENERAL_MANAGER = "19000000-0000-0000-0000-000000000008";
    private static final String GROUP_ORG = "12000000-0000-0000-0000-000000000001";
    private static final String FRONT_ORG = "12000000-0000-0000-0000-000000000005";
    private static final String FRONT_POSITION = "14000000-0000-0000-0000-000000000001";
    private static final String FRONT_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String GM_ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    private static final String WORK_PACKAGE_VERSION = "42100000-0000-0000-0000-000000000001";
    private static final String WORK_PACKAGE_ITEM = "42300000-0000-0000-0000-000000000001";
    private static final String WORK_RECORD = "19800000-0000-0000-0000-000000000001";
    private static final String OUT_OF_SCOPE_WORK_RECORD = "19800000-0000-0000-0000-000000000002";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DailyReportTemplateSnapshotReader snapshotReader;

    @Test
    void seededHousekeepingTemplateHydratesExactVersionWithoutChangingPublishedConfiguration() throws Exception {
        UUID versionId=jdbc.queryForObject("select md5('V62:drtv:HKA')::uuid",UUID.class);
        String frozen=jdbc.queryForObject("select configuration::text from daily_report_template_version where tenant_id=?::uuid and id=?",String.class,TENANT,versionId);
        TenantContext.set(new TenantPrincipal(UUID.fromString(TENANT),UUID.fromString(CEO),"CEO",Set.of(),UUID.randomUUID()));
        try {
            var result=snapshotReader.complete(versionId,objectMapper.readTree(frozen));
            assertThat(result.path("sections").size()).isEqualTo(1);
            assertThat(result.path("sections").get(0).path("items").size()).isEqualTo(11);
            assertThat(result.toString()).contains("hka_usage_return_fact","manualOtherWork","AI_SUMMARY");
            assertThat(jdbc.queryForObject("select configuration::text from daily_report_template_version where tenant_id=?::uuid and id=?",String.class,TENANT,versionId)).isEqualTo(frozen);
            var alreadyFrozen=objectMapper.readTree("{\"title\":\"旧日报\",\"sections\":[{\"title\":\"已冻结内容\",\"items\":[]}]}");
            assertThat(snapshotReader.complete(versionId,alreadyFrozen)).isEqualTo(alreadyFrozen);
            TenantContext.set(new TenantPrincipal(UUID.randomUUID(),UUID.fromString(CEO),"CEO",Set.of(),UUID.randomUUID()));
            assertThat(snapshotReader.complete(versionId,objectMapper.createObjectNode()).path("sections").isEmpty()).isTrue();
        } finally { TenantContext.clear(); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
    }

    @BeforeEach
    void allowIndependentTemplateReviewer() {
        grantCeoRole(GENERAL_MANAGER);
        grantCeoRole(ASSISTANT_GENERAL_MANAGER);
    }

    private void grantCeoRole(String accountId) {
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_type, valid_from, granted_by)
                select ?::uuid, ?::uuid, ?::uuid, role.id, 'TENANT', now() - interval '1 day', ?::uuid
                from app_role role
                where role.tenant_id = ?::uuid and role.code = 'CEO'
                  and not exists (
                    select 1 from role_assignment existing
                    where existing.tenant_id = role.tenant_id and existing.account_id = ?::uuid
                      and existing.role_id = role.id and existing.valid_to is null
                  )
                """, UUID.randomUUID(), TENANT, accountId, CEO, TENANT, accountId);
    }

    @Test
    void publishesTemplateThenSubmitsCorrectsAndReviewsDailyReport() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        JsonNode template = json(postJson("/api/v1/daily-report-templates", CEO,
                "template-create-" + suffix, """
                        {
                          "code":"DR-FRONT-%s",
                          "name":"前台岗位日报集成测试",
                          "description":"按岗位工作包驱动",
                          "positionId":"%s",
                          "ownerOrgUnitId":"%s",
                          "templateOrigin":"HQ"
                        }
                        """.formatted(suffix, FRONT_POSITION, GROUP_ORG), 201));
        String templateId = template.path("id").asText();

        JsonNode version = json(postJson("/api/v1/daily-report-templates/" + templateId + "/versions",
                CEO, "template-version-" + suffix, """
                        {
                          "title":"前台岗位日报 V1",
                          "description":"集成测试版本",
                          "workPackageVersionId":"%s"
                        }
                        """.formatted(WORK_PACKAGE_VERSION), 201));
        String versionId = version.path("id").asText();

        String updateBody = """
                        {
                          "title":"前台岗位日报 V1",
                          "description":"日报字段来自岗位工作包",
                          "expectedVersion":0,
                          "sections":[{
                            "sectionCode":"SHIFT_RESULT",
                            "title":"当班结果",
                            "sectionOrigin":"HQ",
                            "sectionRole":"BASE",
                            "required":true,
                            "sortOrder":1,
                            "applicabilityCondition":{},
                            "items":[{
                              "itemCode":"CHECKINS",
                              "label":"今日入住数",
                              "description":"填写当班入住数量",
                              "valueType":"NUMBER",
                              "required":true,
                              "workPackageItemId":"%s",
                              "dataSourceType":"MANUAL",
                              "dataSourceConfig":{},
                              "evidencePolicy":{"required":true},
                              "validationRules":{"minimum":0},
                              "optionValues":[],
                              "sortOrder":1
                            }]
                          }]
                        }
                        """.formatted(WORK_PACKAGE_ITEM);
        putJson("/api/v1/daily-report-templates/" + templateId + "/versions/" + versionId,
                FRONT_SUPERVISOR, "template-store-forbidden-" + suffix, updateBody, 403);
        JsonNode updated = json(putJson(
                "/api/v1/daily-report-templates/" + templateId + "/versions/" + versionId,
                GENERAL_MANAGER, "template-update-" + suffix, updateBody, 200));
        String templateItemId = updated.path("configuration").path("sections").get(0)
                .path("items").get(0).path("id").asText();
        String sectionVersionId = updated.path("configuration").path("sections").get(0)
                .path("sectionVersionId").asText();
        assertDraftChildInsertUpdateDelete(versionId, sectionVersionId, templateItemId);

        postJson("/api/v1/daily-report-templates/" + templateId + "/versions/" + versionId
                        + "/actions/submit-review",
                CEO, "template-submit-review-" + suffix,
                "{\"expectedVersion\":1,\"comment\":\"请独立审核\"}", 200);
        String publishBody = """
                        {
                          "expectedVersion":2,
                          "effectiveFrom":"2025-01-01T00:00:00+08:00",
                          "comment":"审核通过"
                        }
                        """;
        postJson("/api/v1/daily-report-templates/" + templateId + "/versions/" + versionId
                        + "/actions/publish",
                GENERAL_MANAGER, "template-editor-self-review-" + suffix, publishBody, 403);
        postJson("/api/v1/daily-report-templates/" + templateId + "/versions/" + versionId
                        + "/actions/publish",
                ASSISTANT_GENERAL_MANAGER, "template-publish-" + suffix, publishBody, 200);

        String businessDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String createKey = "report-create-" + suffix;
        String createBody = """
                {
                  "orgUnitId":"%s",
                  "positionAssignmentId":"%s",
                  "businessDate":"%s"
                }
                """.formatted(FRONT_ORG, FRONT_ASSIGNMENT, businessDate);
        JsonNode report = json(postJson("/api/v1/daily-reports", FRONT_DESK, createKey, createBody, 201));
        String reportId = report.path("id").asText();
        String revisionId = report.path("currentRevisionId").asText();
        JsonNode replay = json(postJson("/api/v1/daily-reports", FRONT_DESK, createKey, createBody, 201));
        assertThat(replay.path("id").asText()).isEqualTo(reportId);

        JsonNode draft = json(putJson("/api/v1/daily-reports/" + reportId + "/draft", FRONT_DESK,
                "report-save-" + suffix, """
                        {
                          "revisionId":"%s",
                          "expectedVersion":0,
                          "narrative":"当班经营平稳",
                          "items":[{
                            "templateItemId":"%s",
                            "value":46,
                            "confirmed":true,
                            "exception":false,
                            "comment":"已与PMS核对"
                          }]
                        }
                        """.formatted(revisionId, templateItemId), 200));
        String itemResultId = draft.path("itemResults").get(0).path("id").asText();

        postJson("/api/v1/daily-reports/" + reportId + "/revisions/" + revisionId + "/sources",
                FRONT_DESK, "report-cross-org-source-" + suffix, """
                        {
                          "itemResultId":"%s",
                          "sourceType":"WORK_RECORD",
                          "sourceId":"%s",
                          "sourceVersion":"1",
                          "sourceSnapshot":{"probe":"cross-org"},
                          "expectedVersion":1
                        }
                        """.formatted(itemResultId, OUT_OF_SCOPE_WORK_RECORD), 403);
        postJson("/api/v1/daily-reports/" + reportId + "/revisions/" + revisionId + "/sources",
                FRONT_DESK, "report-source-" + suffix, """
                        {
                          "itemResultId":"%s",
                          "sourceType":"WORK_RECORD",
                          "sourceId":"%s",
                          "sourceVersion":"1",
                          "sourceSnapshot":{"checkins":46},
                          "expectedVersion":1
                        }
                        """.formatted(itemResultId, WORK_RECORD), 201);
        postJson("/api/v1/daily-reports/" + reportId + "/revisions/" + revisionId + "/evidence",
                FRONT_DESK, "report-cross-tenant-evidence-" + suffix, """
                        {
                          "itemResultId":"%s",
                          "evidenceType":"IMAGE",
                          "objectKey":"other-tenant/daily-reports/%s/revisions/%s/probe.jpg",
                          "originalName":"probe.jpg",
                          "mediaType":"image/jpeg",
                          "sizeBytes":1,
                          "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                          "sensitivity":"INTERNAL",
                          "metadata":{},
                          "expectedVersion":2
                        }
                        """.formatted(itemResultId, reportId, revisionId), 403);
        JsonNode evidence = json(postJson(
                "/api/v1/daily-reports/" + reportId + "/revisions/" + revisionId + "/evidence",
                FRONT_DESK, "report-evidence-" + suffix, """
                        {
                          "itemResultId":"%s",
                          "evidenceType":"IMAGE",
                          "objectKey":"%s/daily-reports/%s/revisions/%s/checkins.jpg",
                          "originalName":"checkins.jpg",
                          "mediaType":"image/jpeg",
                          "sizeBytes":1024,
                          "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "sensitivity":"SENSITIVE",
                          "metadata":{"width":1280,"height":720},
                          "expectedVersion":2
                        }
                        """.formatted(itemResultId, TENANT, reportId, revisionId), 201));
        assertThat(evidence.path("metadataRestricted").asBoolean()).isTrue();
        assertThat(evidence.path("objectKey").isMissingNode()).isTrue();
        JsonNode submitted = json(postJson("/api/v1/daily-reports/" + reportId + "/actions/submit",
                FRONT_DESK, "report-submit-" + suffix,
                "{\"revisionId\":\"%s\",\"expectedVersion\":3}".formatted(revisionId), 200));
        assertThat(submitted.path("reportStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("reviewStatus").asText()).isEqualTo("NOT_REQUIRED");
        assertSubmittedFactsImmutable(revisionId, itemResultId);

        JsonNode correction = json(postJson("/api/v1/daily-reports/" + reportId + "/corrections",
                FRONT_DESK, "report-correction-" + suffix,
                "{\"reason\":\"PMS夜审后入住数修正\",\"expectedVersion\":4}", 201));
        String correctionId = correction.path("currentRevisionId").asText();
        JsonNode correctionDraft = json(putJson("/api/v1/daily-reports/" + reportId + "/draft",
                FRONT_DESK, "report-correction-save-" + suffix, """
                        {
                          "revisionId":"%s",
                          "expectedVersion":5,
                          "narrative":"夜审后修正",
                          "items":[{
                            "templateItemId":"%s",
                            "value":47,
                            "confirmed":true,
                            "exception":false,
                            "comment":"夜审最终数"
                          }]
                        }
                        """.formatted(correctionId, templateItemId), 200));
        assertThat(correctionDraft.path("rowVersion").asLong()).isEqualTo(6);
        JsonNode correctionSubmitted = json(postJson(
                "/api/v1/daily-reports/" + reportId + "/actions/submit",
                FRONT_DESK, "report-correction-submit-" + suffix,
                "{\"revisionId\":\"%s\",\"expectedVersion\":6}".formatted(correctionId), 200));
        assertThat(correctionSubmitted.path("reviewStatus").asText()).isEqualTo("PENDING");

        JsonNode reviewed = json(postJson("/api/v1/daily-reports/" + reportId + "/reviews",
                GENERAL_MANAGER, "report-review-" + suffix, """
                        {
                          "outcome":"APPROVED",
                          "comment":"修订依据充分",
                          "reviewerAssignmentId":"%s",
                          "expectedVersion":7
                        }
                        """.formatted(GM_ASSIGNMENT), 200));
        assertThat(reviewed.path("reviewStatus").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_event
                where tenant_id = ?::uuid and aggregate_id = ?::uuid
                  and event_type in ('DAILY_REPORT_SUBMITTED',
                                     'DAILY_REPORT_CORRECTION_SUBMITTED', 'DAILY_REPORT_REVIEWED')
                """, Integer.class, TENANT, reportId)).isEqualTo(3);
    }

    private void assertSubmittedFactsImmutable(String revisionId, String itemResultId) {
        assertThatThrownBy(() -> jdbc.update("""
                update daily_report_revision set narrative = 'tampered'
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, revisionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update daily_report_item_result set value = '999'::jsonb
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, itemResultId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update daily_report_source_reference set source_snapshot = '{"tampered":true}'::jsonb
                where tenant_id = ?::uuid and revision_id = ?::uuid
                """, TENANT, revisionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update daily_report_evidence set structured_snapshot = '{"tampered":true}'::jsonb
                where tenant_id = ?::uuid and revision_id = ?::uuid
                """, TENANT, revisionId)).isInstanceOf(DataAccessException.class);
    }

    private void assertDraftChildInsertUpdateDelete(
            String templateVersionId,
            String sectionVersionId,
            String templateItemId
    ) {
        String relationId = jdbc.queryForObject("""
                select id::text from daily_report_template_section
                where tenant_id = ?::uuid and template_version_id = ?::uuid
                  and section_version_id = ?::uuid
                """, String.class, TENANT, templateVersionId, sectionVersionId);
        assertThat(jdbc.update("""
                update daily_report_template_section set sort_order = 9
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, relationId)).isEqualTo(1);
        assertThat(jdbc.update("""
                delete from daily_report_template_section
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, relationId)).isEqualTo(1);
        assertThat(jdbc.update("""
                insert into daily_report_template_section
                    (id, tenant_id, template_version_id, section_version_id,
                     section_role, required, sort_order)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'BASE', true, 1)
                """, relationId, TENANT, templateVersionId, sectionVersionId)).isEqualTo(1);

        assertThat(jdbc.update("""
                update daily_report_template_item set label = '触发器更新探针'
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, templateItemId)).isEqualTo(1);
        assertThat(jdbc.update("""
                delete from daily_report_template_item
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, templateItemId)).isEqualTo(1);
        assertThat(jdbc.update("""
                insert into daily_report_template_item
                    (id, tenant_id, section_version_id, item_code, label, help_text,
                     input_type, required, work_package_item_id, evidence_policy,
                     source_policy, validation_rules, option_values, sort_order)
                values
                    (?::uuid, ?::uuid, ?::uuid, 'CHECKINS', '今日入住数', '填写当班入住数量',
                     'NUMBER', true, ?::uuid, '{"required":true}'::jsonb,
                     '{"sourceType":"MANUAL"}'::jsonb, '{"minimum":0}'::jsonb,
                     '[]'::jsonb, 1)
                """, templateItemId, TENANT, sectionVersionId, WORK_PACKAGE_ITEM)).isEqualTo(1);
    }

    private MvcResult postJson(
            String path,
            String actorId,
            String idempotencyKey,
            String body,
            int expectedStatus
    ) throws Exception {
        return mockMvc.perform(post(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult putJson(
            String path,
            String actorId,
            String idempotencyKey,
            String body,
            int expectedStatus
    ) throws Exception {
        return mockMvc.perform(put(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
