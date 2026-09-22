package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.dailyreports.DailyReportRoutineProjectionService;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreManagerDailyClosedLoopIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String GENERAL_MANAGER = "19000000-0000-0000-0000-000000000002";
    private static final String GENERAL_MANAGER_EMPLOYEE = "19100000-0000-0000-0000-000000000001";
    private static final String GENERAL_MANAGER_ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    private static final String ASSISTANT_GENERAL_MANAGER = "19000000-0000-0000-0000-000000000008";
    private static final String ASSISTANT_GENERAL_MANAGER_EMPLOYEE = "19100000-0000-0000-0000-000000000007";
    private static final String ASSISTANT_GENERAL_MANAGER_ASSIGNMENT = "19200000-0000-0000-0000-000000000008";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String OTHER_HOTEL = "12000000-0000-0000-0000-000000000004";
    private static final String FRONT_OFFICE_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String FRONT_OFFICE_SUPERVISOR = "19000000-0000-0000-0000-000000000005";
    private static final String FRONT_OFFICE_SUPERVISOR_EMPLOYEE = "19100000-0000-0000-0000-000000000004";
    private static final String FRONT_OFFICE_SUPERVISOR_ASSIGNMENT = "19200000-0000-0000-0000-000000000004";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_DESK_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String PACKAGE_VERSION = "47030000-0000-0000-0000-000000000001";
    private static final String MORNING_ITEM = "47050000-0000-0000-0000-000000000002";
    private static final String MORNING_FORM = "47020000-0000-0000-0000-000000000002";
    private static final String ROOM_ITEM = "47050000-0000-0000-0000-000000000005";
    private static final String ROOM_FORM = "47020000-0000-0000-0000-000000000005";
    private static final String REPORT_TEMPLATE = "47100000-0000-0000-0000-000000000001";
    private static final String DAILY_STANDARD = "41300000-0000-0000-0000-000000000001";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);
    private static final Path ATTACHMENT_ROOT = createAttachmentRoot();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkExpectationSlaService slaService;

    @Autowired
    private DailyReportRoutineProjectionService routineProjectionService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
        registry.add("app.attachments.root", ATTACHMENT_ROOT::toString);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void newlyCreatedHotelStoreManagerReceivesReportingLineAndDailyWorkPackage() {
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String loginName = "store-manager-provisioning-" + accountId;

        jdbc.update("""
                insert into user_account
                    (id, tenant_id, login_name, display_name, status)
                values (?, ?::uuid, ?, '新店长自动配置测试', 'ACTIVE')
                """, accountId, TENANT, loginName);
        jdbc.update("""
                insert into employee
                    (id, tenant_id, account_id, employee_no, name, employment_status, hired_on)
                values (?, ?::uuid, ?, ?, '新店长自动配置测试', 'ACTIVE', current_date)
                """, employeeId, TENANT, accountId, "AUTO-GM-" + employeeId);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (?, ?::uuid, ?, ?::uuid, '14000000-0000-0000-0000-000000000004'::uuid,
                        true, 'PERMANENT', current_date, 'ACTIVE')
                """, assignmentId, TENANT, employeeId, HOTEL);

        assertThat(jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment store_manager
                join employee_position_assignment manager
                  on manager.tenant_id = store_manager.tenant_id
                 and manager.id = store_manager.manager_assignment_id
                join position_definition manager_position
                  on manager_position.tenant_id = manager.tenant_id
                 and manager_position.id = manager.position_id
                where store_manager.tenant_id = ?::uuid
                  and store_manager.id = ?
                  and manager_position.code = 'GROUP_GENERAL_MANAGER'
                """, Integer.class, TENANT, assignmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from work_package_allocation allocation
                join work_package_version version on version.tenant_id=allocation.tenant_id
                  and version.id=allocation.work_package_version_id
                join work_package_definition definition on definition.tenant_id=version.tenant_id
                  and definition.id=version.work_package_definition_id
                where allocation.tenant_id = ?::uuid
                  and definition.code='WP-PILOT-GM-DAILY'
                  and version.lifecycle_status='PUBLISHED'
                  and version.version_no=(select max(current_version.version_no) from work_package_version current_version
                    where current_version.tenant_id=version.tenant_id
                      and current_version.work_package_definition_id=definition.id
                      and current_version.lifecycle_status='PUBLISHED'
                      and current_version.effective_from::date<=current_date
                      and (current_version.effective_to is null or current_version.effective_to::date>=current_date))
                  and allocation.position_assignment_id = ?
                  and allocation.target_org_unit_id = ?::uuid
                  and allocation.allocation_source = 'SYSTEM'
                  and allocation.status = 'ACTIVE'
                """, Integer.class, TENANT, assignmentId, HOTEL)).isEqualTo(1);

        slaService.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        assertThat(jdbc.queryForObject("""
                select count(*)
                from work_expectation
                where tenant_id = ?::uuid
                  and position_assignment_id = ?
                  and business_date = current_date
                  and work_package_item_id in (select id from work_package_item where tenant_id = work_expectation.tenant_id and period_type='DAY')
                """, Integer.class, TENANT, assignmentId)).isEqualTo(7);
    }

    @Test
    void storeManagerRoutineClosesFromScheduleThroughEvidenceReviewAndDailyReport() throws Exception {
        assertPublishedTemplateContract();

        LocalDate businessDate = LocalDate.now();
        slaService.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        List<String> expectationIds = jdbc.queryForList("""
                select expectation.id::text from work_expectation expectation
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id
                 and item.id = expectation.work_package_item_id
                where expectation.tenant_id = ?::uuid
                  and item.work_package_version_id = ?::uuid
                  and expectation.position_assignment_id = ?::uuid
                  and expectation.business_date = ?
                order by expectation.due_at, expectation.id
                """, String.class, TENANT, PACKAGE_VERSION, GENERAL_MANAGER_ASSIGNMENT, businessDate);
        assertThat(expectationIds).hasSize(7);
        int elapsedReminderMoments = jdbc.queryForObject("""
                select count(*)
                from work_expectation expectation
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id
                 and item.id = expectation.work_package_item_id
                join tenant on tenant.id = expectation.tenant_id
                cross join lateral jsonb_array_elements(item.reminder_policy -> 'moments') moment
                where expectation.tenant_id = ?::uuid
                  and expectation.id = any(?::uuid[])
                  and (moment ->> 'localTime')::time
                      <= (timezone(tenant.timezone, now()))::time
                  and (
                      (coalesce(moment ->> 'kind', 'REMINDER') in ('REMINDER', 'PROGRESS')
                          and expectation.due_at > now())
                      or
                      (coalesce(moment ->> 'kind', 'REMINDER') in ('OVERDUE', 'ESCALATION')
                          and expectation.due_at <= now())
                  )
                """, Integer.class, TENANT, "{" + String.join(",", expectationIds) + "}");
        if (elapsedReminderMoments == 0) {
            assertThat(reminderCount(expectationIds, null)).isZero();
        } else {
            assertThat(reminderCount(expectationIds, null)).isPositive();
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from work_expectation_reminder
                where tenant_id = ?::uuid and work_expectation_id = any(?::uuid[])
                  and reminder_stage ~ '^[A-Z][A-Z0-9_]{0,23}$'
                """, Integer.class, TENANT, "{" + String.join(",", expectationIds) + "}"))
                .isEqualTo(reminderCount(expectationIds, null));

        String morningExpectation = jdbc.queryForObject("""
                select id::text from work_expectation
                where tenant_id = ?::uuid and work_package_item_id = ?::uuid
                  and position_assignment_id = ?::uuid and business_date = ?
                """, String.class, TENANT, MORNING_ITEM, GENERAL_MANAGER_ASSIGNMENT, businessDate);
        assertThat(morningExpectation).isNotBlank();

        JsonNode myWork = response(identity(get("/api/v1/my/work-expectations"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 200);
        JsonNode morningResource = findById(myWork, morningExpectation);
        JsonNode reminderPolicy = jsonColumn(morningResource.path("reminder_policy"));
        JsonNode submissionPolicy = jsonColumn(morningResource.path("submission_policy"));
        assertThat(reminderPolicy.path("moments").path(0).path("code").asText()).isEqualTo("R0930");
        assertThat(submissionPolicy.path("evidenceRequirements").path(0)
                .path("checkpointCode").asText()).isEqualTo("full_body");

        JsonNode assistantWork = response(identity(get("/api/v1/my/work-expectations"),
                ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT), 200);
        assertThat(findById(assistantWork, morningExpectation).path("id").asText())
                .isEqualTo(morningExpectation);
        JsonNode assistantDraft = response(identity(post("/api/v1/work-data/records"),
                        ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "orgUnitId":"%s",
                          "employeeId":"%s",
                          "positionAssignmentId":"%s",
                          "formVersionId":"%s",
                          "businessDate":"%s",
                          "payload":{"expectedAttendance":12,"actualAttendance":12,
                            "appearancePassed":true,"meetingTopic":"共享事项测试",
                            "meetingNotes":"副店长可按店长模板形成草稿"},
                          "completionStatement":"副店长共享执行测试",
                          "workPackageVersionId":"%s",
                          "workPackageItemId":"%s",
                          "workExpectationId":"%s",
                          "recordKind":"INSPECTION",
                          "targetOrgUnitId":"%s",
                          "saveAsDraft":true
                        }
                        """.formatted(HOTEL, ASSISTANT_GENERAL_MANAGER_EMPLOYEE,
                        ASSISTANT_GENERAL_MANAGER_ASSIGNMENT, MORNING_FORM, businessDate,
                        PACKAGE_VERSION, MORNING_ITEM, morningExpectation, HOTEL)), 201);

        JsonNode draft = response(identity(post("/api/v1/work-data/records"),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "orgUnitId":"%s",
                          "employeeId":"%s",
                          "positionAssignmentId":"%s",
                          "formVersionId":"%s",
                          "businessDate":"%s",
                          "payload":{"expectedAttendance":12,"actualAttendance":12,
                            "appearancePassed":true,"meetingTopic":"今日服务标准",
                            "meetingNotes":"确认当班分工；大堂空调故障待维修"},
                          "completionStatement":"仪容仪表检查及晨会已完成",
                          "exceptionStatement":"大堂空调故障，现场温度无法下降",
                          "nextAction":"联系工程部于今日20:00前完成检修",
                          "workPackageVersionId":"%s",
                          "workPackageItemId":"%s",
                          "workExpectationId":"%s",
                          "recordKind":"INSPECTION",
                          "targetOrgUnitId":"%s",
                          "saveAsDraft":true
                        }
                        """.formatted(HOTEL, GENERAL_MANAGER_EMPLOYEE, GENERAL_MANAGER_ASSIGNMENT,
                        MORNING_FORM, businessDate, PACKAGE_VERSION, MORNING_ITEM, morningExpectation, HOTEL)), 201);
        String recordId = draft.path("id").asText();

        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/submit", recordId),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("全体员工全身照")));

        mockMvc.perform(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(new MockMultipartFile("file", "forged.png", "image/png", cameraPhoto()))
                        .param("captureSource", "SYSTEM"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("不支持的证据采集方式")));

        JsonNode portrait = response(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(new MockMultipartFile("file", "portrait.png", "image/png", photo(600, 800, "png")))
                        .param("captureSource", "FILE_PICKER"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 201);
        byte[] portraitContent = mockMvc.perform(identity(get(
                        "/api/v1/work-data/attachments/{attachmentId}/content", portrait.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        BufferedImage portraitImage = ImageIO.read(new ByteArrayInputStream(portraitContent));
        assertThat(portraitImage.getWidth()).isEqualTo(600);
        assertThat(portraitImage.getHeight()).isEqualTo(800);
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'displayOrientation'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, portrait.path("id").asText())).isEqualTo("PORTRAIT");
        mockMvc.perform(identity(delete(
                        "/api/v1/work-data/records/{recordId}/attachments/{attachmentId}",
                        recordId, portrait.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isNoContent());

        JsonNode square = response(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(new MockMultipartFile("file", "square.png", "image/png", photo(600, 600, "png")))
                        .param("captureSource", "CAMERA"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 201);
        byte[] squareContent = mockMvc.perform(identity(get(
                        "/api/v1/work-data/attachments/{attachmentId}/content", square.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        BufferedImage squareImage = ImageIO.read(new ByteArrayInputStream(squareContent));
        assertThat(squareImage.getWidth()).isEqualTo(600);
        assertThat(squareImage.getHeight()).isEqualTo(600);
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'displayOrientation'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, square.path("id").asText())).isEqualTo("SQUARE");
        mockMvc.perform(identity(delete(
                        "/api/v1/work-data/records/{recordId}/attachments/{attachmentId}",
                        recordId, square.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isNoContent());

        JsonNode exifNormalized = response(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(new MockMultipartFile("file", "mobile.jpg", "image/jpeg", exifRotatedPhoto()))
                        .param("captureSource", "FILE_PICKER"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 201);
        byte[] normalizedMobilePhoto = mockMvc.perform(identity(get(
                        "/api/v1/work-data/attachments/{attachmentId}/content", exifNormalized.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        BufferedImage normalizedMobileImage = ImageIO.read(new ByteArrayInputStream(normalizedMobilePhoto));
        assertThat(normalizedMobileImage.getWidth()).isEqualTo(800);
        assertThat(normalizedMobileImage.getHeight()).isEqualTo(600);
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'exifOrientation'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, exifNormalized.path("id").asText())).isEqualTo("6");
        mockMvc.perform(identity(delete(
                        "/api/v1/work-data/records/{recordId}/attachments/{attachmentId}",
                        recordId, exifNormalized.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isNoContent());

        OffsetDateTime capturedAt = OffsetDateTime.now().minusSeconds(2).withNano(0);
        MockMultipartFile photo = new MockMultipartFile(
                "file", "appearance.png", "image/png", cameraPhoto());
        JsonNode uploaded = response(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(photo)
                        .param("captureSource", "CAMERA")
                        .param("checkpointCode", "full_body")
                        .param("capturedAtClient", capturedAt.toString()),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 201);
        assertThat(uploaded.path("captureSource").asText()).isEqualTo("CAMERA");
        assertThat(uploaded.path("checkpointCode").asText()).isEqualTo("full_body");
        assertThat(uploaded.path("sha256").asText()).isNotEqualTo(uploaded.path("sourceSha256").asText());
        assertThat(uploaded.path("aiProcessing").path("status").asText())
                .isEqualTo("MANUAL_FALLBACK_REQUIRED");
        mockMvc.perform(identity(post(
                        "/api/v1/work-data/records/{recordId}/attachments/{attachmentId}/ai-review",
                        recordId, uploaded.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json")
                        .content("""
                                {"decision":"CONFIRM","summary":"不能把人工降级伪装成AI结果"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("""
                select evidence_metadata #>> '{aiProcessing,status}'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, uploaded.path("id").asText()))
                .isEqualTo("MANUAL_FALLBACK_REQUIRED");
        mockMvc.perform(identity(post(
                        "/api/v1/work-data/records/{recordId}/attachments/{attachmentId}/ai-review",
                        recordId, uploaded.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json")
                        .content("""
                                {"decision":"MANUAL_FALLBACK","summary":"员工仪容仪表检查已完成",
                                 "correctionReason":"AI服务未配置，人工确认"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProcessing.status").value("MANUAL_FALLBACK_CONFIRMED"));
        assertThat(jdbc.queryForObject("""
                select evidence_metadata #>> '{aiProcessing,status}'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, uploaded.path("id").asText()))
                .isEqualTo("MANUAL_FALLBACK_CONFIRMED");
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'trustedTimestampSource'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, uploaded.path("id").asText())).isEqualTo("SERVER_RECEIVED_AT");
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'orientationPolicy'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, uploaded.path("id").asText())).isEqualTo("ADAPTIVE");
        assertThat(jdbc.queryForObject("""
                select evidence_metadata ->> 'displayOrientation'
                from attachment where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, uploaded.path("id").asText())).isEqualTo("LANDSCAPE");

        byte[] watermarked = mockMvc.perform(identity(get(
                        "/api/v1/work-data/attachments/{attachmentId}/content", uploaded.path("id").asText()),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        BufferedImage watermarkedImage = ImageIO.read(new ByteArrayInputStream(watermarked));
        assertThat(watermarkedImage).isNotNull();
        assertThat(watermarkedImage.getWidth()).isEqualTo(800);
        assertThat(watermarkedImage.getHeight()).isEqualTo(600);

        for (String checkpoint : List.of("upper_body", "meeting_minutes")) {
            mockMvc.perform(identity(multipart(
                            "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                            .file(new MockMultipartFile("file", checkpoint + ".png", "image/png", cameraPhoto()))
                            .param("captureSource", "CAMERA")
                            .param("checkpointCode", checkpoint)
                            .param("capturedAtClient", OffsetDateTime.now().minusSeconds(1).toString()),
                    GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT))
                    .andExpect(status().isCreated());
        }

        UUID reportId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        createDraftReport(reportId, revisionId, businessDate);

        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/submit", recordId),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertThat(jdbc.queryForObject("""
                select count(*)
                from notification notification
                join employee_position_assignment executor
                  on executor.tenant_id = notification.tenant_id
                 and executor.id = ?::uuid
                 and executor.manager_assignment_id = notification.recipient_assignment_id
                where notification.tenant_id = ?::uuid
                  and notification.notification_type = 'WORK_RECORD_REVIEW_PENDING'
                  and notification.source_type = 'WORK_RECORD'
                  and notification.source_id = ?::uuid
                  and notification.idempotency_key = ?
                """, Integer.class, GENERAL_MANAGER_ASSIGNMENT, TENANT, recordId,
                "work-record:review-pending:" + recordId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select result_status from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, String.class, TENANT, revisionId, recordId)).isEqualTo("PENDING");
        int pendingAfterSubmit = jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and result_status = 'PENDING'
                """, Integer.class, TENANT, revisionId);
        assertThat(pendingAfterSubmit).isPositive();
        assertThat(jdbc.queryForObject("""
                select narrative from daily_report_revision
                where tenant_id = ?::uuid and id = ?
                """, String.class, TENANT, revisionId))
                .contains("已审核完成 0/7 项", "待主管审核 " + pendingAfterSubmit + " 项");
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_request
                where tenant_id = ?::uuid and provider_code = 'INTERNAL_RULE_ENGINE'
                  and input_snapshot ->> 'reportId' = ?
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(1);
        Map<String, Object> contentAwareRule = jdbc.queryForMap("""
                select recommendation.fact_summary::text as facts,
                       recommendation.analysis,
                       recommendation.recommendation
                from ai_recommendation recommendation
                join ai_request request
                  on request.tenant_id = recommendation.tenant_id
                 and request.id = recommendation.ai_request_id
                where recommendation.tenant_id = ?::uuid
                  and recommendation.status = 'AVAILABLE'
                  and request.provider_code = 'INTERNAL_RULE_ENGINE'
                  and request.input_snapshot ->> 'reportId' = ?
                """, TENANT, reportId.toString());
        assertThat(String.valueOf(contentAwareRule.get("facts")))
                .contains("FACT_ONLY_INTERNAL_RULES", "issueFacts", recordId,
                        "大堂空调故障，现场温度无法下降",
                        "formData.meetingNotes", "declaredNextActions");
        assertThat(String.valueOf(contentAwareRule.get("analysis")))
                .contains("明确问题事实", "大堂空调故障");
        assertThat(String.valueOf(contentAwareRule.get("recommendation")))
                .contains("联系工程部于今日20:00前完成检修", "责任人", "完成时限");
        Long pendingRowVersion = jdbc.queryForObject("""
                select row_version from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, Long.class, TENANT, revisionId, recordId);
        projectAsCeo(recordId);
        assertThat(jdbc.queryForObject("""
                select row_version from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, Long.class, TENANT, revisionId, recordId)).isEqualTo(pendingRowVersion);
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_request
                where tenant_id = ?::uuid and provider_code = 'INTERNAL_RULE_ENGINE'
                  and input_snapshot ->> 'reportId' = ?
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(1);
        JsonNode teamWork = response(identity(get(
                        "/api/v1/team/work-expectations?targetOrgUnitId=" + HOTEL), CEO, null), 200);
        JsonNode submittedExpectation = findById(teamWork, morningExpectation);
        assertThat(teamWork.path(0).path("id").asText()).isEqualTo(morningExpectation);
        assertThat(submittedExpectation.path("period_type").asText()).isEqualTo("DAY");
        assertThat(submittedExpectation.path("hotel_org_unit_id").asText()).isEqualTo(HOTEL);
        assertThat(submittedExpectation.path("hotel_name").asText()).isNotBlank();
        assertThat(submittedExpectation.path("actual_executor_assignment_id").asText())
                .isEqualTo(GENERAL_MANAGER_ASSIGNMENT);
        JsonNode workbenchSummary = response(identity(get(
                "/api/v1/team/workbench-summary?businessDate=" + businessDate), CEO, null), 200);
        assertThat(workbenchSummary.path("asOfDate").asText()).isEqualTo(businessDate.toString());
        assertThat(workbenchSummary.path("today").path("expected").asInt()).isPositive();
        assertThat(workbenchSummary.path("today").path("completed").asInt()).isPositive();
        assertThat(workbenchSummary.path("today").path("completionRate").asInt()).isBetween(0, 100);
        assertThat(workbenchSummary.path("monthToDate").path("expected").asInt())
                .isGreaterThanOrEqualTo(workbenchSummary.path("today").path("expected").asInt());
        assertThat(workbenchSummary.path("hotels").findValuesAsText("id")).contains(HOTEL);
        JsonNode hotelWorkbench = findById(workbenchSummary.path("hotels"), HOTEL);
        assertThat(hotelWorkbench.path("departments").isArray()).isTrue();
        assertThat(hotelWorkbench.path("departments").size()).isPositive();
        assertThat(hotelWorkbench.path("departments").findValuesAsText("employeeId"))
                .contains(GENERAL_MANAGER_EMPLOYEE);
        assertThat(hotelWorkbench.path("departments").path(0).path("today")
                .path("completionRate").asInt()).isBetween(0, 100);
        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/review", recordId),
                        ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json")
                        .content("{\"outcome\":\"APPROVED\",\"reason\":\"越级尝试\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("直属主管")));
        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/review", recordId), CEO, null)
                        .contentType("application/json")
                        .content("{\"outcome\":\"APPROVED\",\"reason\":\"证据完整\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/submit",
                        assistantDraft.path("id").asText()), ASSISTANT_GENERAL_MANAGER,
                        ASSISTANT_GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("其他人员提交")));

        assertThat(jdbc.queryForObject("select status from work_expectation where id = ?::uuid",
                String.class, morningExpectation)).isEqualTo("SATISFIED");
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, Integer.class, TENANT, revisionId, recordId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select value ->> 'factLabel' from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, String.class, TENANT, revisionId, recordId)).isEqualTo("仪容仪表与晨会");
        assertThat(jdbc.queryForObject("""
                select value #>> '{actualExecutor,assignmentId}' from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, String.class, TENANT, revisionId, recordId))
                .isEqualTo(GENERAL_MANAGER_ASSIGNMENT);
        assertThat(jdbc.queryForObject("""
                select result_status from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and system_prefilled = true
                  and source_summary ->> 'workRecordId' = ?
                """, String.class, TENANT, revisionId, recordId)).isEqualTo("EXCEPTION");
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_source_reference
                where tenant_id = ?::uuid and revision_id = ? and source_type = 'WORK_RECORD'
                  and source_id = ?::uuid
                """, Integer.class, TENANT, revisionId, recordId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from daily_report_evidence evidence
                join daily_report_item_result result
                  on result.tenant_id = evidence.tenant_id
                 and result.revision_id = evidence.revision_id
                 and result.id = evidence.item_result_id
                where evidence.tenant_id = ?::uuid and evidence.revision_id = ?
                  and result.source_summary ->> 'workRecordId' = ?
                """, Integer.class, TENANT, revisionId, recordId)).isEqualTo(3);
        int completedAfterApproval = jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ?
                  and result_status in ('COMPLETED', 'EXCEPTION')
                """, Integer.class, TENANT, revisionId);
        int pendingAfterApproval = jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and result_status = 'PENDING'
                """, Integer.class, TENANT, revisionId);
        int evidenceAfterApproval = jdbc.queryForObject("""
                select count(*) from daily_report_evidence
                where tenant_id = ?::uuid and revision_id = ?
                """, Integer.class, TENANT, revisionId);
        assertThat(jdbc.queryForObject("""
                select narrative from daily_report_revision
                where tenant_id = ?::uuid and id = ?
                """, String.class, TENANT, revisionId))
                .contains("已审核完成 " + completedAfterApproval + "/7 项",
                        "待主管审核 " + pendingAfterApproval + " 项",
                        "关联证据 " + evidenceAfterApproval + " 份");
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_request request
                join ai_recommendation recommendation
                  on recommendation.tenant_id = request.tenant_id
                 and recommendation.ai_request_id = request.id
                where request.tenant_id = ?::uuid
                  and request.provider_code = 'INTERNAL_RULE_ENGINE'
                  and request.input_snapshot ->> 'reportId' = ?
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_recommendation recommendation
                join ai_request request
                  on request.tenant_id = recommendation.tenant_id
                 and request.id = recommendation.ai_request_id
                where recommendation.tenant_id = ?::uuid
                  and request.input_snapshot ->> 'reportId' = ?
                  and recommendation.status = 'AVAILABLE'
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_recommendation recommendation
                join ai_request request
                  on request.tenant_id = recommendation.tenant_id
                 and request.id = recommendation.ai_request_id
                where recommendation.tenant_id = ?::uuid
                  and request.input_snapshot ->> 'reportId' = ?
                  and recommendation.status = 'INVALIDATED'
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(1);

        Map<String, Object> rejectedTarget = jdbc.queryForMap("""
                select expectation.id as expectation_id, item.id as item_id,
                       item.form_version_id
                from work_expectation expectation
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id
                 and item.id = expectation.work_package_item_id
                where expectation.tenant_id = ?::uuid
                  and expectation.position_assignment_id = ?::uuid
                  and expectation.business_date = ?
                  and item.work_package_version_id = ?::uuid
                  and item.id <> ?::uuid
                  and item.form_version_id is not null
                  and not exists (
                      select 1 from work_record existing
                      where existing.tenant_id = expectation.tenant_id
                        and existing.work_expectation_id = expectation.id
                        and existing.status in ('SUBMITTED', 'APPROVED')
                  )
                order by item.sort_order
                limit 1
                """, TENANT, GENERAL_MANAGER_ASSIGNMENT, businessDate, PACKAGE_VERSION, MORNING_ITEM);
        UUID rejectedRecordId = UUID.randomUUID();
        jdbc.update("""
                insert into work_record
                    (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                     form_version_id, business_date, status, payload, submitted_at,
                     work_package_version_id, work_package_item_id, work_expectation_id,
                     record_kind, target_org_unit_id, occurred_at, submitted_by_account_id,
                     attempt_no, content_hash, completion_statement)
                values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUBMITTED',
                        '{"test":"pending rejection"}'::jsonb, now(), ?::uuid, ?, ?,
                        'INSPECTION', ?::uuid, now(), ?::uuid, 1, repeat('b', 64),
                        '待主管审核后确认')
                """, rejectedRecordId, TENANT, HOTEL, GENERAL_MANAGER_EMPLOYEE,
                GENERAL_MANAGER_ASSIGNMENT, rejectedTarget.get("form_version_id"), businessDate,
                PACKAGE_VERSION, rejectedTarget.get("item_id"), rejectedTarget.get("expectation_id"),
                HOTEL, GENERAL_MANAGER);
        projectAsCeo(rejectedRecordId.toString());
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and result_status = 'PENDING'
                """, Integer.class, TENANT, revisionId)).isEqualTo(pendingAfterApproval + 1);

        jdbc.update("""
                update work_record
                set status = 'REJECTED', reviewed_by_account_id = ?::uuid,
                    reviewed_at = now(), review_reason = '测试退回清理', row_version = row_version + 1
                where tenant_id = ?::uuid and id = ?
                """, CEO, TENANT, rejectedRecordId);
        projectAsCeo(rejectedRecordId.toString());
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ? and result_status = 'PENDING'
                """, Integer.class, TENANT, revisionId)).isEqualTo(pendingAfterApproval);
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_source_reference
                where tenant_id = ?::uuid and revision_id = ? and source_id = ?
                """, Integer.class, TENANT, revisionId, rejectedRecordId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_request
                where tenant_id = ?::uuid and provider_code = 'INTERNAL_RULE_ENGINE'
                  and input_snapshot ->> 'reportId' = ?
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(4);
        projectAsCeo(rejectedRecordId.toString());
        assertThat(jdbc.queryForObject("""
                select count(*) from ai_request
                where tenant_id = ?::uuid and provider_code = 'INTERNAL_RULE_ENGINE'
                  and input_snapshot ->> 'reportId' = ?
                """, Integer.class, TENANT, reportId.toString())).isEqualTo(4);

        UUID evaluationRecordId = UUID.randomUUID();
        jdbc.update("""
                insert into work_record
                    (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                     form_version_id, business_date, status, payload, submitted_at,
                     work_package_version_id, work_package_item_id, record_kind,
                     target_org_unit_id, occurred_at, submitted_by_account_id,
                     attempt_no, content_hash)
                values
                    (?, ?::uuid, '12000000-0000-0000-0000-000000000005'::uuid,
                     ?::uuid, ?::uuid, ?::uuid, current_date, 'SUBMITTED',
                     '{"summary":"店长日清已完成"}'::jsonb, now(), ?::uuid, ?::uuid,
                     'INSPECTION', ?::uuid, now(), ?::uuid, 99, 'evaluation-target-org-test')
                """, evaluationRecordId, TENANT, GENERAL_MANAGER_EMPLOYEE,
                GENERAL_MANAGER_ASSIGNMENT, MORNING_FORM, PACKAGE_VERSION, MORNING_ITEM, HOTEL,
                GENERAL_MANAGER);
        mockMvc.perform(identity(post("/api/v1/standard-evaluations"), CEO, null)
                        .header("Idempotency-Key", "store-manager-evaluation-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {
                                  "subjectType":"WORK_RECORD",
                                  "subjectId":"%s",
                                  "orgUnitId":"%s",
                                  "positionAssignmentId":"%s",
                                  "standardVersionId":"%s",
                                  "inputSnapshot":{"summary":"店长日清已完成"}
                                }
                                """.formatted(evaluationRecordId, HOTEL, GENERAL_MANAGER_ASSIGNMENT, DAILY_STANDARD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject_type").value("WORK_RECORD"))
                .andExpect(jsonPath("$.subject_id").value(evaluationRecordId.toString()));

        mockMvc.perform(identity(post("/api/v1/tasks"), CEO, null)
                        .header("Idempotency-Key", "store-manager-corrective-task-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {
                                  "orgUnitId":"%s",
                                  "assigneeAssignmentId":"%s",
                                  "workRecordId":"%s",
                                  "title":"整改：店长日清检查",
                                  "description":"补充结果说明和现场证据",
                                  "priority":"NORMAL",
                                  "dueAt":"%s",
                                  "sourceSnapshot":{"source":"TEAM_WORK_REVIEW"},
                                  "dispatchNow":true
                                }
                                """.formatted(HOTEL, GENERAL_MANAGER_ASSIGNMENT, evaluationRecordId,
                                OffsetDateTime.now().plusDays(1).withNano(0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lifecycle_status").value("PENDING_ACK"))
                .andExpect(jsonPath("$.participants[1].participant_type").value("REVIEWER"))
                .andExpect(jsonPath("$.participants[1].position_assignment_id").isNotEmpty());
    }

    @Test
    void sharedWorkProjectsOnceIntoTheActualExecutorsDailyReportAcrossPackageVariants() {
        LocalDate businessDate = LocalDate.now().plusYears(3).plusDays(17);
        Map<String, Object> sharedItems = jdbc.queryForMap("""
                select manager_item.id as manager_item_id,
                       manager_item.form_version_id as manager_form_version_id,
                       manager_item.work_package_version_id as manager_package_version_id,
                       manager_relation.template_version_id as manager_report_template_id,
                       assistant_item.id as assistant_item_id,
                       assistant_item.work_package_version_id as assistant_package_version_id,
                       assistant_relation.template_version_id as assistant_report_template_id
                from work_package_item manager_item
                join daily_report_template_item manager_template_item
                  on manager_template_item.tenant_id = manager_item.tenant_id
                 and manager_template_item.work_package_item_id = manager_item.id
                join daily_report_template_section manager_relation
                  on manager_relation.tenant_id = manager_template_item.tenant_id
                 and manager_relation.section_version_id = manager_template_item.section_version_id
                join work_package_item assistant_item
                  on assistant_item.tenant_id = manager_item.tenant_id
                 and assistant_item.execution_policy ->> 'sharedKey'
                        = manager_item.execution_policy ->> 'sharedKey'
                 and assistant_item.item_code in (
                        'AGM_WS_APPEARANCE_MEETING', 'AGM_NS_APPEARANCE_MEETING')
                join daily_report_template_item assistant_template_item
                  on assistant_template_item.tenant_id = assistant_item.tenant_id
                 and assistant_template_item.work_package_item_id = assistant_item.id
                join daily_report_template_section assistant_relation
                  on assistant_relation.tenant_id = assistant_template_item.tenant_id
                 and assistant_relation.section_version_id = assistant_template_item.section_version_id
                where manager_item.tenant_id = ?::uuid
                  and manager_item.item_code = 'GM_APPEARANCE_MEETING_V56'
                order by assistant_item.item_code
                limit 1
                """, TENANT);
        UUID managerItemId = (UUID) sharedItems.get("manager_item_id");
        UUID managerPackageVersionId = (UUID) sharedItems.get("manager_package_version_id");
        UUID assistantPackageVersionId = (UUID) sharedItems.get("assistant_package_version_id");
        UUID allocationId = jdbc.queryForObject("""
                select allocation.id
                from work_package_allocation allocation
                where allocation.tenant_id = ?::uuid
                  and allocation.work_package_version_id = ?
                  and allocation.position_assignment_id = ?::uuid
                order by allocation.valid_from desc, allocation.id
                limit 1
                """, UUID.class, TENANT, managerPackageVersionId, GENERAL_MANAGER_ASSIGNMENT);

        UUID managerReportId = UUID.randomUUID();
        UUID managerRevisionId = UUID.randomUUID();
        createDraftReportForAssignment(
                managerReportId, managerRevisionId, businessDate,
                UUID.fromString(GENERAL_MANAGER_EMPLOYEE), UUID.fromString(GENERAL_MANAGER_ASSIGNMENT),
                (UUID) sharedItems.get("manager_report_template_id"), managerPackageVersionId,
                UUID.fromString(GENERAL_MANAGER));
        UUID assistantReportId = UUID.randomUUID();
        UUID assistantRevisionId = UUID.randomUUID();
        createDraftReportForAssignment(
                assistantReportId, assistantRevisionId, businessDate,
                UUID.fromString(ASSISTANT_GENERAL_MANAGER_EMPLOYEE),
                UUID.fromString(ASSISTANT_GENERAL_MANAGER_ASSIGNMENT),
                (UUID) sharedItems.get("assistant_report_template_id"), assistantPackageVersionId,
                UUID.fromString(ASSISTANT_GENERAL_MANAGER));

        UUID expectationId = UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation
                    (id, tenant_id, work_package_item_id, work_package_allocation_id,
                     position_assignment_id, target_org_unit_id, business_date, period_key,
                     available_at, due_at, status, waiver_allowed)
                values (?, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?, now(), now() + interval '1 hour',
                        'SUBMITTED', false)
                """, expectationId, TENANT, managerItemId, allocationId,
                GENERAL_MANAGER_ASSIGNMENT, HOTEL, businessDate,
                "shared-report-projection-" + UUID.randomUUID());
        UUID recordId = UUID.randomUUID();
        jdbc.update("""
                insert into work_record
                    (id, tenant_id, org_unit_id, employee_id, position_assignment_id,
                     form_version_id, business_date, status, payload, submitted_at,
                     work_package_version_id, work_package_item_id, work_expectation_id,
                     record_kind, target_org_unit_id, occurred_at, submitted_by_account_id,
                     attempt_no, content_hash, completion_statement)
                values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUBMITTED',
                        '{"summary":"店助实际完成共享晨会工作"}'::jsonb, now(), ?, ?, ?,
                        'INSPECTION', ?::uuid, now(), ?::uuid, 1, repeat('c', 64),
                        '共享晨会工作已完成')
                """, recordId, TENANT, HOTEL, ASSISTANT_GENERAL_MANAGER_EMPLOYEE,
                ASSISTANT_GENERAL_MANAGER_ASSIGNMENT, sharedItems.get("manager_form_version_id"),
                businessDate, managerPackageVersionId, managerItemId, expectationId, HOTEL,
                ASSISTANT_GENERAL_MANAGER);

        projectAsCeo(recordId.toString());
        projectAsCeo(recordId.toString());

        assertThat(jdbc.queryForObject("""
                select count(*)
                from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ?
                  and source_summary ->> 'workRecordId' = ?
                """, Integer.class, TENANT, assistantRevisionId, recordId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select value #>> '{actualExecutor,assignmentId}'
                from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ?
                  and source_summary ->> 'workRecordId' = ?
                """, String.class, TENANT, assistantRevisionId, recordId.toString()))
                .isEqualTo(ASSISTANT_GENERAL_MANAGER_ASSIGNMENT);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from daily_report_item_result
                where tenant_id = ?::uuid and revision_id = ?
                  and source_summary ->> 'workRecordId' = ?
                """, Integer.class, TENANT, managerRevisionId, recordId.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                select narrative from daily_report_revision
                where tenant_id = ?::uuid and id = ?
                """, String.class, TENANT, assistantRevisionId))
                .contains("已审核完成 0/1 项", "待主管审核 1 项");
    }

    @Test
    void restingOwnerTransfersCurrentAndRemainingEligibleWorkExactlyOnce() throws Exception {
        LocalDate businessDate = LocalDate.now().plusYears(6).plusDays(9);
        UUID allocationId = jdbc.queryForObject("""
                select allocation.id from work_package_allocation allocation
                where allocation.tenant_id = ?::uuid
                  and allocation.position_assignment_id = ?::uuid
                  and exists (
                    select 1 from work_package_item item
                    where item.tenant_id = allocation.tenant_id
                      and item.work_package_version_id = allocation.work_package_version_id
                      and coalesce((item.execution_policy ->> 'oneClickRestTransfer')::boolean, false)
                  )
                order by allocation.valid_from desc limit 1
                """, UUID.class, TENANT, GENERAL_MANAGER_ASSIGNMENT);
        List<UUID> itemIds = jdbc.queryForList("""
                select item.id from work_package_item item
                join work_package_allocation allocation
                  on allocation.tenant_id = item.tenant_id
                 and allocation.work_package_version_id = item.work_package_version_id
                where item.tenant_id = ?::uuid and allocation.id = ?
                  and coalesce((item.execution_policy ->> 'oneClickRestTransfer')::boolean, false)
                order by sort_order, id limit 4
                """, UUID.class, TENANT, allocationId);
        assertThat(itemIds).hasSize(4);

        UUID delegateAssignmentId = jdbc.queryForObject("""
                select assignment.id
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join user_account account on account.tenant_id = employee.tenant_id
                                         and account.id = employee.account_id
                where assignment.tenant_id = ?::uuid and assignment.org_unit_id = ?::uuid
                  and assignment.id <> ?::uuid and assignment.id <> ?::uuid
                  and assignment.status = 'ACTIVE'
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and account.status = 'ACTIVE'
                order by assignment.id limit 1
                """, UUID.class, TENANT, HOTEL, GENERAL_MANAGER_ASSIGNMENT,
                ASSISTANT_GENERAL_MANAGER_ASSIGNMENT);

        List<UUID> expectationIds = new java.util.ArrayList<>();
        for (int index = 0; index < itemIds.size(); index++) {
            UUID expectationId = UUID.randomUUID();
            expectationIds.add(expectationId);
            jdbc.update("""
                    insert into work_expectation
                        (id, tenant_id, work_package_item_id, work_package_allocation_id,
                         position_assignment_id, target_org_unit_id, business_date, period_key,
                         available_at, due_at, status, waiver_allowed)
                    values (?, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?,
                            now() + (? * interval '1 hour'), now() + ((? + 8) * interval '1 hour'),
                            ?, false)
                    """, expectationId, TENANT, itemIds.get(index), allocationId,
                    GENERAL_MANAGER_ASSIGNMENT, HOTEL, businessDate,
                    "rest-transfer-" + UUID.randomUUID(), index - 2, index - 2,
                    index == 2 ? "IN_PROGRESS" : "AVAILABLE");
        }

        UUID currentId = expectationIds.get(1);
        JsonNode first = response(identity(post(
                        "/api/v1/work-expectations/{expectationId}/delegate", currentId)
                        .contentType("application/json")
                        .content("""
                                {"delegateAssignmentId":"%s","ownerResting":true,
                                 "reason":"本人今日休息","expectedVersion":0}
                                """.formatted(delegateAssignmentId)),
                ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT), 200);
        assertThat(first.path("transferredCount").asInt()).isEqualTo(3);
        List<String> transferredIds = new java.util.ArrayList<>();
        first.path("transferredIds").forEach(node -> transferredIds.add(node.asText()));
        assertThat(transferredIds).containsExactlyInAnyOrderElementsOf(
                expectationIds.subList(1, 4).stream().map(UUID::toString).toList());
        assertThat(jdbc.queryForObject("""
                select count(*) from work_expectation_delegation
                where tenant_id = ?::uuid and status = 'ACTIVE'
                  and work_expectation_id = any(?::uuid[])
                  and delegate_assignment_id = ?
                  and owner_resting
                """, Integer.class, TENANT,
                "{" + String.join(",", expectationIds.stream().map(UUID::toString).toList()) + "}",
                delegateAssignmentId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from notification
                where tenant_id = ?::uuid and notification_type = 'WORK_EXPECTATION_DELEGATED'
                  and source_id = any(?::uuid[])
                """, Integer.class, TENANT,
                "{" + String.join(",", expectationIds.stream().map(UUID::toString).toList()) + "}"))
                .isEqualTo(3);

        JsonNode retry = response(identity(post(
                        "/api/v1/work-expectations/{expectationId}/delegate", currentId)
                        .contentType("application/json")
                        .content("""
                                {"delegateAssignmentId":"%s","ownerResting":true,
                                 "reason":"重复请求","expectedVersion":1}
                                """.formatted(delegateAssignmentId)),
                ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT), 200);
        assertThat(retry.path("transferredCount").asInt()).isZero();
        response(identity(post("/api/v1/work-expectations/{expectationId}/delegate", currentId)
                        .contentType("application/json")
                        .content("""
                                {"delegateAssignmentId":"%s","ownerResting":true,"expectedVersion":0}
                                """.formatted(delegateAssignmentId)),
                ASSISTANT_GENERAL_MANAGER, ASSISTANT_GENERAL_MANAGER_ASSIGNMENT), 400);
    }

    @Test
    void departmentAssignmentCanExecuteViewAndReceiveHotelWorkButCannotCrossHotels() throws Exception {
        LocalDate businessDate = LocalDate.now().plusYears(8).plusDays(17);
        Map<String, Object> source = jdbc.queryForMap("""
                select item.id as item_id, item.form_version_id, allocation.id as allocation_id
                from work_package_item item
                join work_package_allocation allocation
                  on allocation.tenant_id = item.tenant_id
                 and allocation.work_package_version_id = item.work_package_version_id
                where item.tenant_id = ?::uuid
                  and item.item_code = 'GM_FRONT_DESK_COACHING_V56'
                  and allocation.position_assignment_id = ?::uuid
                  and allocation.target_org_unit_id = ?::uuid
                order by allocation.valid_from desc limit 1
                """, TENANT, GENERAL_MANAGER_ASSIGNMENT, HOTEL);
        UUID itemId = (UUID) source.get("item_id");
        UUID formVersionId = (UUID) source.get("form_version_id");
        UUID allocationId = (UUID) source.get("allocation_id");

        UUID sharedExpectationId = insertHotelExpectation(
                itemId, allocationId, businessDate, OffsetDateTime.now().minusMinutes(5));
        UUID transferExpectationId = insertHotelExpectation(
                itemId, allocationId, businessDate, OffsetDateTime.now());
        UUID laterExpectationId = insertHotelExpectation(
                itemId, allocationId, businessDate, OffsetDateTime.now().plusMinutes(5));

        UUID otherAccountId = UUID.randomUUID();
        UUID otherEmployeeId = UUID.randomUUID();
        UUID otherAssignmentId = UUID.randomUUID();
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name, status)
                values (?, ?::uuid, ?, '跨店候选人', 'ACTIVE')
                """, otherAccountId, TENANT, "cross-store-" + otherAccountId);
        jdbc.update("""
                insert into employee
                    (id, tenant_id, account_id, employee_no, name, employment_status, hired_on)
                values (?, ?::uuid, ?, ?, '跨店候选人', 'ACTIVE', current_date)
                """, otherEmployeeId, TENANT, otherAccountId, "CROSS-" + otherEmployeeId);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (?, ?::uuid, ?, ?::uuid, '14000000-0000-0000-0000-000000000003'::uuid,
                        true, 'PERMANENT', current_date, 'ACTIVE')
                """, otherAssignmentId, TENANT, otherEmployeeId, OTHER_HOTEL);

        JsonNode sharedWork = response(identity(get("/api/v1/my/work-expectations")
                        .param("businessDate", businessDate.toString()),
                FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT), 200);
        assertThat(findById(sharedWork, sharedExpectationId.toString()).path("id").asText())
                .isEqualTo(sharedExpectationId.toString());

        JsonNode submitted = response(identity(post("/api/v1/work-data/records"),
                        FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "orgUnitId":"%s",
                          "employeeId":"%s",
                          "positionAssignmentId":"%s",
                          "formVersionId":"%s",
                          "businessDate":"%s",
                          "payload":{"workSummary":"已完成前台带教和三跟进检查","issueFound":false},
                          "completionStatement":"前台带教与三跟进已完成",
                          "workExpectationId":"%s",
                          "targetOrgUnitId":"%s"
                        }
                        """.formatted(FRONT_OFFICE_DEPARTMENT, FRONT_OFFICE_SUPERVISOR_EMPLOYEE,
                        FRONT_OFFICE_SUPERVISOR_ASSIGNMENT, formVersionId, businessDate,
                        sharedExpectationId, HOTEL)), 201);
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");

        JsonNode candidates = response(identity(get(
                        "/api/v1/work-expectations/{expectationId}/delegation-candidates", transferExpectationId),
                FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT), 200);
        assertThat(candidates.findValuesAsText("assignment_id"))
                .contains(FRONT_DESK_ASSIGNMENT)
                .doesNotContain(otherAssignmentId.toString());

        mockMvc.perform(identity(post("/api/v1/work-expectations/{expectationId}/delegate", transferExpectationId),
                        FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT)
                        .contentType("application/json")
                        .content("""
                                {"delegateAssignmentId":"%s","ownerResting":true,
                                 "reason":"跨门店必须拒绝","expectedVersion":0}
                                """.formatted(otherAssignmentId)))
                .andExpect(status().isBadRequest());

        JsonNode transfer = response(identity(post(
                        "/api/v1/work-expectations/{expectationId}/delegate", transferExpectationId),
                        FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {"delegateAssignmentId":"%s","ownerResting":true,
                         "reason":"店助休息一键转交","expectedVersion":0}
                        """.formatted(FRONT_DESK_ASSIGNMENT)), 200);
        assertThat(transfer.path("transferredCount").asInt()).isEqualTo(2);
        List<String> transferredIds = new java.util.ArrayList<>();
        transfer.path("transferredIds").forEach(node -> transferredIds.add(node.asText()));
        assertThat(transferredIds)
                .containsExactlyInAnyOrder(transferExpectationId.toString(), laterExpectationId.toString());

        JsonNode delegatedWork = response(identity(get("/api/v1/my/work-expectations")
                        .param("businessDate", businessDate.toString()),
                FRONT_DESK, FRONT_DESK_ASSIGNMENT), 200);
        assertThat(findById(delegatedWork, transferExpectationId.toString()).path("id").asText())
                .isEqualTo(transferExpectationId.toString());

        JsonNode report = response(identity(post("/api/v1/daily-reports"),
                        FRONT_OFFICE_SUPERVISOR, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("""
                        {"orgUnitId":"%s","positionAssignmentId":"%s","businessDate":"%s"}
                        """.formatted(HOTEL, FRONT_OFFICE_SUPERVISOR_ASSIGNMENT, businessDate)), 201);
        assertThat(report.path("id").asText()).isNotBlank();
    }

    private UUID insertHotelExpectation(
            UUID itemId,
            UUID allocationId,
            LocalDate businessDate,
            OffsetDateTime availableAt
    ) {
        UUID expectationId = UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation
                    (id, tenant_id, work_package_item_id, work_package_allocation_id,
                     position_assignment_id, target_org_unit_id, business_date, period_key,
                     available_at, due_at, status, waiver_allowed)
                values (?, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, 'AVAILABLE', false)
                """, expectationId, TENANT, itemId, allocationId, GENERAL_MANAGER_ASSIGNMENT,
                HOTEL, businessDate, "department-scope-" + expectationId, availableAt,
                availableAt.plusHours(8));
        return expectationId;
    }

    @Test
    void roomInspectionRequiresFiveRoomsTwoDirtyRoomsAndEvidenceForEveryRoom() throws Exception {
        LocalDate businessDate = LocalDate.now();
        slaService.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        String expectationId = jdbc.queryForObject("""
                select id::text from work_expectation
                where tenant_id = ?::uuid and work_package_item_id = ?::uuid
                  and position_assignment_id = ?::uuid and business_date = ?
                """, String.class, TENANT, ROOM_ITEM, GENERAL_MANAGER_ASSIGNMENT, businessDate);

        JsonNode resource = findById(response(identity(get("/api/v1/my/work-expectations"),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 200), expectationId);
        JsonNode submissionPolicy = jsonColumn(resource.path("submission_policy"));
        assertThat(submissionPolicy.path("attachmentCountUnlimited").asBoolean()).isTrue();
        assertThat(submissionPolicy.path("maxAttachments").asInt()).isEqualTo(200);
        assertThat(submissionPolicy.path("evidenceRequirements").path(0)
                .path("requiredInstances").asInt()).isEqualTo(5);
        assertThat(submissionPolicy.path("evidenceRequirements").path(1)
                .path("requiredInstances").asInt()).isEqualTo(2);

        JsonNode draft = response(identity(post("/api/v1/work-data/records"),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "orgUnitId":"%s",
                          "employeeId":"%s",
                          "positionAssignmentId":"%s",
                          "formVersionId":"%s",
                          "businessDate":"%s",
                          "payload":{
                            "roomInspectionRoomNumbers":["1201","1202","1203","1204","1205"],
                            "dirtyRoomNumbers":["1301","1302"],
                            "issueFound":false
                          },
                          "completionStatement":"已完成5间查房和2间走脏房检查",
                          "workPackageVersionId":"%s",
                          "workPackageItemId":"%s",
                          "workExpectationId":"%s",
                          "recordKind":"INSPECTION",
                          "targetOrgUnitId":"%s",
                          "saveAsDraft":true
                        }
                        """.formatted(HOTEL, GENERAL_MANAGER_EMPLOYEE, GENERAL_MANAGER_ASSIGNMENT,
                        ROOM_FORM, businessDate, PACKAGE_VERSION, ROOM_ITEM, expectationId, HOTEL)), 201);
        String recordId = draft.path("id").asText();

        mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/submit", recordId),
                        GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("1201")));

        for (String roomNumber : List.of("1201", "1202", "1203", "1204", "1205")) {
            uploadRoomPhoto(recordId, "room_inspection", roomNumber);
        }
        for (String roomNumber : List.of("1301", "1302")) {
            uploadRoomPhoto(recordId, "dirty_room", roomNumber);
        }
        JsonNode extraPhoto = uploadRoomPhoto(recordId, "room_inspection", "1201");
        assertThat(extraPhoto.path("evidenceInstanceKey").asText()).isEqualTo("1201");
        assertThat(jdbc.queryForObject("""
                select count(*) from attachment
                where tenant_id = ?::uuid and work_record_id = ?::uuid
                  and evidence_metadata ->> 'evidenceInstanceKey' = '1201'
                """, Integer.class, TENANT, recordId)).isEqualTo(2);

        UUID managerEmployeeId = jdbc.queryForObject("""
                select manager.employee_id
                from employee_position_assignment executor
                join employee_position_assignment manager
                  on manager.tenant_id = executor.tenant_id
                 and manager.id = executor.manager_assignment_id
                where executor.tenant_id = ?::uuid and executor.id = ?::uuid
                """, UUID.class, TENANT, GENERAL_MANAGER_ASSIGNMENT);
        UUID managerAccountId = jdbc.queryForObject("""
                select account_id from employee
                where tenant_id = ?::uuid and id = ?
                """, UUID.class, TENANT, managerEmployeeId);
        try {
            jdbc.update("""
                    update employee set account_id = null
                    where tenant_id = ?::uuid and id = ?
                    """, TENANT, managerEmployeeId);
            mockMvc.perform(identity(post("/api/v1/work-data/records/{recordId}/actions/submit", recordId),
                            GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT)
                            .contentType("application/json").content("{\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"));
        } finally {
            jdbc.update("""
                    update employee set account_id = ?
                    where tenant_id = ?::uuid and id = ?
                    """, managerAccountId, TENANT, managerEmployeeId);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = ?::uuid and resource_type = 'WORK_RECORD'
                  and resource_id = ?::uuid
                  and action = 'WORK_RECORD_REVIEW_NOTIFICATION_SKIPPED'
                  and after_data ->> 'reason' = 'DIRECT_MANAGER_ASSIGNMENT_UNRESOLVED'
                """, Integer.class, TENANT, recordId)).isEqualTo(1);
    }

    private JsonNode uploadRoomPhoto(String recordId, String checkpointCode, String roomNumber)
            throws Exception {
        return response(identity(multipart(
                        "/api/v1/work-data/records/{recordId}/attachments/upload", recordId)
                        .file(new MockMultipartFile("file", roomNumber + ".png", "image/png", cameraPhoto()))
                        .param("captureSource", "CAMERA")
                        .param("checkpointCode", checkpointCode)
                        .param("evidenceInstanceKey", roomNumber)
                        .param("capturedAtClient", OffsetDateTime.now().minusSeconds(1).toString()),
                GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT), 201);
    }

    private void assertPublishedTemplateContract() {
        assertThat(jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment store_manager
                join employee_position_assignment group_manager
                  on group_manager.tenant_id = store_manager.tenant_id
                 and group_manager.id = store_manager.manager_assignment_id
                join employee group_manager_employee
                  on group_manager_employee.tenant_id = group_manager.tenant_id
                 and group_manager_employee.id = group_manager.employee_id
                join position_definition group_manager_position
                  on group_manager_position.tenant_id = group_manager.tenant_id
                 and group_manager_position.id = group_manager.position_id
                where store_manager.tenant_id = ?::uuid
                  and store_manager.id = ?::uuid
                  and group_manager_employee.account_id = ?::uuid
                  and group_manager_position.code = 'GROUP_GENERAL_MANAGER'
                """, Integer.class, TENANT, GENERAL_MANAGER_ASSIGNMENT, CEO)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item
                where tenant_id = ?::uuid and work_package_version_id = ?::uuid
                  and report_policy ->> 'dailyReport' = 'true'
                """, Integer.class, TENANT, PACKAGE_VERSION)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item
                where tenant_id = ?::uuid and work_package_version_id = ?::uuid
                  and reminder_policy <> '{}'::jsonb
                """, Integer.class, TENANT, PACKAGE_VERSION)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                select jsonb_array_length(submission_policy -> 'evidenceRequirements')
                from work_package_item where tenant_id = ?::uuid
                  and item_code = 'GM_PUBLIC_AREA_INSPECTION_AM'
                  and work_package_version_id = ?::uuid
                """, Integer.class, TENANT, PACKAGE_VERSION)).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_template_item item
                join daily_report_template_section relation
                  on relation.tenant_id = item.tenant_id
                 and relation.section_version_id = item.section_version_id
                where item.tenant_id = ?::uuid and relation.template_version_id = ?::uuid
                """, Integer.class, TENANT, REPORT_TEMPLATE)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from position_function_profile profile
                join position_definition position
                  on position.tenant_id = profile.tenant_id and position.id = profile.position_id
                join position_function_profile_version version
                  on version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                join position_function_profile_permission grant_row
                  on grant_row.tenant_id = version.tenant_id and grant_row.profile_version_id = version.id
                join permission permission_item on permission_item.id = grant_row.permission_id
                where profile.tenant_id = ?::uuid and position.code = 'GENERAL_MANAGER'
                  and version.lifecycle_status = 'PUBLISHED'
                  and permission_item.code = 'ui.module.my-work'
                """, Integer.class, TENANT)).isPositive();
    }

    private int reminderCount(List<String> expectationIds, String stage) {
        String stagePredicate = stage == null ? "" : " and reminder_stage = '" + stage + "'";
        return jdbc.queryForObject("""
                select count(*) from work_expectation_reminder
                where tenant_id = ?::uuid and work_expectation_id = any(?::uuid[])
                """ + stagePredicate, Integer.class, TENANT,
                "{" + String.join(",", expectationIds) + "}");
    }

    private void projectAsCeo(String recordId) {
        TenantContext.set(new TenantPrincipal(
                UUID.fromString(TENANT), UUID.fromString(CEO), "CEO",
                Set.of(UUID.fromString(HOTEL)), UUID.randomUUID()));
        try {
            routineProjectionService.projectWorkRecord(UUID.fromString(recordId));
        } finally {
            TenantContext.clear();
        }
    }

    private void createDraftReport(UUID reportId, UUID revisionId, LocalDate businessDate) {
        createDraftReportForAssignment(
                reportId, revisionId, businessDate,
                UUID.fromString(GENERAL_MANAGER_EMPLOYEE), UUID.fromString(GENERAL_MANAGER_ASSIGNMENT),
                UUID.fromString(REPORT_TEMPLATE), UUID.fromString(PACKAGE_VERSION),
                UUID.fromString(GENERAL_MANAGER));
    }

    private void createDraftReportForAssignment(
            UUID reportId,
            UUID revisionId,
            LocalDate businessDate,
            UUID employeeId,
            UUID assignmentId,
            UUID templateVersionId,
            UUID packageVersionId,
            UUID createdByAccountId
    ) {
        jdbc.update("""
                insert into daily_report
                    (id, tenant_id, hotel_org_unit_id, org_unit_id, employee_id,
                     position_assignment_id, business_date, timezone, cutoff_local_time,
                     report_deadline_at, template_version_id, work_package_version_id,
                     report_status, review_status, current_revision_no, trace_id,
                     created_by_account_id)
                values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'Asia/Shanghai',
                        time '06:00', now() + interval '12 hours', ?::uuid, ?::uuid,
                        'DRAFT', 'NOT_REQUIRED', 1, ?, ?::uuid)
                """, reportId, TENANT, HOTEL, HOTEL, employeeId,
                assignmentId, businessDate, templateVersionId, packageVersionId,
                UUID.randomUUID(), createdByAccountId);
        jdbc.update("""
                insert into daily_report_revision
                    (id, tenant_id, report_id, revision_no, revision_type,
                     revision_status, payload_snapshot, created_by_account_id)
                values (?, ?::uuid, ?, 1, 'ORIGINAL', 'DRAFT', '{}'::jsonb, ?::uuid)
                """, revisionId, TENANT, reportId, createdByAccountId);
        jdbc.update("""
                update daily_report set current_revision_id = ?
                where tenant_id = ?::uuid and id = ?
                """, revisionId, TENANT, reportId);
    }

    private MockHttpServletRequestBuilder identity(
            MockHttpServletRequestBuilder request,
            String accountId,
            String assignmentId
    ) {
        request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", accountId);
        if (assignmentId != null) request.header("X-Assignment-Id", assignmentId);
        return request;
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode item : array) {
            if (id.equals(item.path("id").asText())) return item;
        }
        throw new AssertionError("response does not contain id " + id);
    }

    private JsonNode jsonColumn(JsonNode node) throws Exception {
        if (node.isTextual()) return objectMapper.readTree(node.asText());
        if (node.isObject() && node.path("value").isTextual()) {
            return objectMapper.readTree(node.path("value").asText());
        }
        return node;
    }

    private static byte[] cameraPhoto() {
        return photo(800, 600, "png");
    }

    private static byte[] exifRotatedPhoto() {
        try {
            byte[] jpeg = photo(600, 800, "jpeg");
            byte[] exif = {
                    (byte) 0xff, (byte) 0xe1, 0x00, 0x22,
                    'E', 'x', 'i', 'f', 0x00, 0x00,
                    'I', 'I', 0x2a, 0x00,
                    0x08, 0x00, 0x00, 0x00,
                    0x01, 0x00,
                    0x12, 0x01, 0x03, 0x00,
                    0x01, 0x00, 0x00, 0x00,
                    0x06, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
            };
            ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + exif.length);
            output.write(jpeg, 0, 2);
            output.write(exif);
            output.write(jpeg, 2, jpeg.length - 2);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] photo(int width, int height, String format) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(new Color(235, 242, 247));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(28, 79, 110));
            graphics.fillRect(width / 10, height / 10, width * 8 / 10, height * 6 / 10);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path createAttachmentRoot() {
        try {
            return Files.createTempDirectory("store-manager-closed-loop-");
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
