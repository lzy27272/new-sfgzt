package cn.sifangguan.hotelaios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Pilot7TaskDeliveryIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String GENERAL_MANAGER = "19000000-0000-0000-0000-000000000002";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String HOUSEKEEPING_SUPERVISOR = "19000000-0000-0000-0000-000000000004";
    private static final String FRONT_OFFICE_SUPERVISOR = "19000000-0000-0000-0000-000000000005";
    private static final String OTA_ASSISTANT = "19000000-0000-0000-0000-000000000006";
    private static final String REGIONAL_MANAGER = "19000000-0000-0000-0000-000000000007";
    private static final String ASSISTANT_GENERAL_MANAGER = "19000000-0000-0000-0000-000000000008";

    private static final String GENERAL_MANAGER_ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    private static final String FRONT_DESK_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String HOUSEKEEPING_ASSIGNMENT = "19200000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE_ASSIGNMENT = "19200000-0000-0000-0000-000000000004";
    private static final String OTA_ASSISTANT_ASSIGNMENT = "19200000-0000-0000-0000-000000000006";

    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String SHANGHAI_HOTEL = "12000000-0000-0000-0000-000000000004";
    private static final String FRONT_OFFICE_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String GENERAL_MANAGER_POSITION = "14000000-0000-0000-0000-000000000004";
    private static final String OTA_ASSISTANT_POSITION = "14000000-0000-0000-0000-000000000005";
    private static final String STANDARD_VERSION = "17000000-0000-0000-0000-000000000001";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void ceoWithoutPositionCanCreateAndDispatchWithAutomaticReviewer() throws Exception {
        String taskId = json(postTask(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"CEO atomic dispatch",
                  "priority":"HIGH",
                  "dispatchNow":true
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_DESK_ASSIGNMENT, STANDARD_VERSION)))
                .path("id").asText();

        JsonNode detail = json(getJson("/api/v1/tasks/" + taskId, CEO));
        assertThat(detail.path("lifecycle_status").asText()).isEqualTo("PENDING_ACK");
        assertThat(participant(detail, "ASSIGNEE").path("position_assignment_id").asText())
                .isEqualTo(FRONT_DESK_ASSIGNMENT);
        assertThat(participant(detail, "REVIEWER").path("position_assignment_id").asText())
                .isEqualTo(FRONT_OFFICE_ASSIGNMENT);

        assertThat(containsId(json(getJson("/api/v1/tasks?view=mine", FRONT_DESK)), taskId)).isTrue();
        assertThat(hasNotification(json(getJson("/api/v1/notifications", FRONT_DESK)), taskId, "TASK_ASSIGNED"))
                .isTrue();
    }

    @Test
    void taskEvidenceAcceptsPortraitPhotoAndKeepsItsNormalizedOrientation() throws Exception {
        String taskId = json(postTask(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "title":"Portrait evidence",
                  "dispatchNow":true
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_DESK_ASSIGNMENT, FRONT_OFFICE_ASSIGNMENT)))
                .path("id").asText();
        postCommandExpecting(taskId, "acknowledge", FRONT_DESK, FRONT_DESK_ASSIGNMENT, 1, 200);

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/tasks/{taskId}/evidence/upload", taskId)
                        .file(new MockMultipartFile("file", "portrait.png", "image/png", photo(600, 800)))
                        .param("submittedByAssignmentId", FRONT_DESK_ASSIGNMENT)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isCreated())
                .andReturn();
        String evidenceId = json(uploaded).path("id").asText();

        byte[] content = mockMvc.perform(get("/api/v1/tasks/{taskId}/evidence/{evidenceId}/content", taskId, evidenceId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        assertThat(image.getWidth()).isEqualTo(600);
        assertThat(image.getHeight()).isEqualTo(800);
    }

    @Test
    void creatorCanAttachOptionalDispatchPhotoWithoutSatisfyingAssigneeEvidencePolicy() throws Exception {
        JsonNode created = json(postTask(FRONT_OFFICE_SUPERVISOR, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "creatorAssignmentId":"%s",
                  "title":"Dispatch photo reference",
                  "dispatchNow":false,
                  "sourceSnapshot":{"taskPolicy":{"attachmentRequired":true,"maxAttachments":10}}
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_DESK_ASSIGNMENT,
                FRONT_OFFICE_ASSIGNMENT, FRONT_OFFICE_ASSIGNMENT)));
        String taskId = created.path("id").asText();
        assertThat(created.path("lifecycle_status").asText()).isEqualTo("PROPOSED");
        assertThat(created.path("created_by_assignment_id").asText()).isEqualTo(FRONT_OFFICE_ASSIGNMENT);

        MvcResult uploaded = mockMvc.perform(multipart(
                                "/api/v1/tasks/{taskId}/dispatch-attachments/upload", taskId)
                        .file(new MockMultipartFile("file", "reference.png", "image/png", photo(800, 600)))
                        .param("submittedByAssignmentId", FRONT_OFFICE_ASSIGNMENT)
                        .param("note", "请按图中标注处处理")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(json(uploaded).path("structuredResult").path("purpose").asText())
                .isEqualTo("DISPATCH_REFERENCE");

        JsonNode detail = json(getJson("/api/v1/tasks/" + taskId, FRONT_OFFICE_SUPERVISOR));
        JsonNode persistedStructured = detail.path("evidence").get(0).path("structured_result");
        if (persistedStructured.has("value")) {
            persistedStructured = objectMapper.readTree(persistedStructured.path("value").asText());
        }
        assertThat(persistedStructured.path("note").asText())
                .isEqualTo("请按图中标注处处理");

        postCommandExpecting(taskId, "dispatch", FRONT_OFFICE_SUPERVISOR,
                FRONT_OFFICE_ASSIGNMENT, 0, 200);
        postCommandExpecting(taskId, "acknowledge", FRONT_DESK,
                FRONT_DESK_ASSIGNMENT, 1, 200);
        postCommandExpecting(taskId, "submit-result", FRONT_DESK,
                FRONT_DESK_ASSIGNMENT, 2, 400);
    }

    @Test
    void hotelSupervisorCanTargetSameHotelAcrossDepartmentsButNotAnotherHotel() throws Exception {
        JsonNode targets = json(getJson("/api/v1/tasks/targets", FRONT_OFFICE_SUPERVISOR));
        assertThat(containsAssignment(targets, HOUSEKEEPING_ASSIGNMENT)).isTrue();

        MvcResult created = postTask(FRONT_OFFICE_SUPERVISOR, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Same hotel cross department",
                  "creatorAssignmentId":"%s",
                  "dispatchNow":true
                }
                """.formatted(HANGZHOU_HOTEL, HOUSEKEEPING_ASSIGNMENT,
                STANDARD_VERSION, FRONT_OFFICE_ASSIGNMENT));
        String taskId = json(created).path("id").asText();
        assertThat(json(created).path("lifecycle_status").asText()).isEqualTo("PENDING_ACK");
        assertThat(containsId(json(getJson("/api/v1/tasks?view=mine", HOUSEKEEPING_SUPERVISOR)), taskId)).isTrue();

        UUID outsideAssignment = createAssignmentAt(
                SHANGHAI_HOTEL, GENERAL_MANAGER_POSITION, "P7-OUTSIDE-MANAGER");
        assertThat(containsAssignment(json(getJson("/api/v1/tasks/targets", FRONT_OFFICE_SUPERVISOR)),
                outsideAssignment.toString())).isFalse();
        postTaskExpecting(FRONT_OFFICE_SUPERVISOR, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Cross hotel forbidden",
                  "creatorAssignmentId":"%s"
                }
                """.formatted(SHANGHAI_HOTEL, outsideAssignment,
                STANDARD_VERSION, FRONT_OFFICE_ASSIGNMENT), 403);
    }

    @Test
    void otaAssistantCannotListTargetsOrDispatchManagementTasks() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/targets")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", OTA_ASSISTANT))
                .andExpect(status().isForbidden());

        postTaskExpecting(OTA_ASSISTANT, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"OTA assistant management task",
                  "creatorAssignmentId":"%s",
                  "dispatchNow":true
                }
                """.formatted(HANGZHOU_HOTEL, GENERAL_MANAGER_ASSIGNMENT,
                STANDARD_VERSION, OTA_ASSISTANT_ASSIGNMENT), 403);
    }

    @Test
    void frontDeskCannotListTargetsOrCreateTasks() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/targets")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isForbidden());

        postTaskExpecting(FRONT_DESK, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "title":"Front desk forbidden"
                }
                """.formatted(HANGZHOU_HOTEL, HOUSEKEEPING_ASSIGNMENT,
                FRONT_OFFICE_ASSIGNMENT), 403);
    }

    @Test
    void taskOrganizationMustContainAssigneeHotelAssignment() throws Exception {
        postTaskExpecting(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Organization mismatch"
                }
                """.formatted(SHANGHAI_HOTEL, FRONT_DESK_ASSIGNMENT, STANDARD_VERSION), 403);
    }

    @Test
    void mineTeamAndReviewViewsHaveDistinctSemantics() throws Exception {
        String taskId = json(postTask(FRONT_OFFICE_SUPERVISOR, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"View semantics",
                  "creatorAssignmentId":"%s",
                  "dispatchNow":true
                }
                """.formatted(HANGZHOU_HOTEL, HOUSEKEEPING_ASSIGNMENT,
                STANDARD_VERSION, FRONT_OFFICE_ASSIGNMENT))).path("id").asText();

        assertThat(containsId(json(getJson("/api/v1/tasks?view=mine", HOUSEKEEPING_SUPERVISOR)), taskId)).isTrue();
        assertThat(containsId(json(getJson("/api/v1/tasks?view=review", HOUSEKEEPING_SUPERVISOR)), taskId)).isFalse();
        assertThat(containsId(json(getJson("/api/v1/tasks?view=mine", FRONT_OFFICE_SUPERVISOR)), taskId)).isTrue();
        assertThat(containsId(json(getJson("/api/v1/tasks?view=review", FRONT_OFFICE_SUPERVISOR)), taskId)).isTrue();
        // Cross-department delivery does not expand the supervisor's TEAM read grant.
        // The creator/reviewer still sees the task through MINE and REVIEW above.
        assertThat(containsId(json(getJson("/api/v1/tasks?view=team", FRONT_OFFICE_SUPERVISOR)), taskId)).isFalse();
        assertThat(containsId(json(getJson("/api/v1/tasks?view=team", CEO)), taskId)).isTrue();
        assertThat(containsId(json(getJson("/api/v1/tasks?view=mine", FRONT_DESK)), taskId)).isFalse();

        mockMvc.perform(get("/api/v1/tasks?view=team")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgUnitListFilterUsesOrganizationSubtree() throws Exception {
        String taskId = json(postTask(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Organization subtree filter"
                }
                """.formatted(FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT,
                STANDARD_VERSION))).path("id").asText();

        assertThat(containsId(json(getJson(
                "/api/v1/tasks?view=team&orgUnitId=" + HANGZHOU_HOTEL, CEO)), taskId)).isTrue();
        assertThat(containsId(json(getJson(
                "/api/v1/tasks?view=team&orgUnitId=" + SHANGHAI_HOTEL, CEO)), taskId)).isFalse();
    }

    @Test
    void departmentSupervisorCannotReadHotelOrRegionalDashboards() throws Exception {
        mockMvc.perform(get("/api/v1/dashboards/hotels/{hotelId}", HANGZHOU_HOTEL)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/dashboards/operations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isForbidden());
    }

    @Test
    void hotelDiscoveryUsesHotelPermissionWhileRegionalAggregationRemainsSeparate() throws Exception {
        JsonNode generalManagerHotels = json(getJson("/api/v1/dashboards/hotels", GENERAL_MANAGER));
        assertThat(generalManagerHotels.path("hotels").size()).isEqualTo(1);
        assertThat(generalManagerHotels.path("hotels").get(0).path("id").asText())
                .isEqualTo(HANGZHOU_HOTEL);
        mockMvc.perform(get("/api/v1/dashboards/operations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", GENERAL_MANAGER))
                .andExpect(status().isForbidden());

        JsonNode assistantHotels = json(getJson(
                "/api/v1/dashboards/hotels", ASSISTANT_GENERAL_MANAGER));
        assertThat(assistantHotels.path("hotels").size()).isEqualTo(1);
        assertThat(assistantHotels.path("hotels").get(0).path("id").asText())
                .isEqualTo(HANGZHOU_HOTEL);
        mockMvc.perform(get("/api/v1/dashboards/operations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", ASSISTANT_GENERAL_MANAGER))
                .andExpect(status().isForbidden());

        JsonNode regionalHotels = json(getJson("/api/v1/dashboards/hotels", REGIONAL_MANAGER));
        assertThat(regionalHotels.path("hotels").size()).isEqualTo(2);
        assertThat(regionalHotels.path("hotels").findValuesAsText("id"))
                .containsExactlyInAnyOrder(HANGZHOU_HOTEL, SHANGHAI_HOTEL);
        JsonNode operations = json(getJson("/api/v1/dashboards/operations", REGIONAL_MANAGER));
        assertThat(operations.path("hotels").size()).isEqualTo(2);
    }

    @Test
    void departmentSupervisorCannotReadAnotherHotelDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/dashboards/hotels/{hotelId}", SHANGHAI_HOTEL)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isForbidden());
    }

    @Test
    void otaDeliveryScopeDoesNotBroadenTeamReadScopeOrFollowUnrelatedSecondPosition() throws Exception {
        ScopedActor scopedOta = createScopedActor(
                "OTA_OPERATION_ASSISTANT", HANGZHOU_HOTEL, OTA_ASSISTANT_POSITION);
        addPosition(scopedOta.employeeId(), SHANGHAI_HOTEL, GENERAL_MANAGER_POSITION);
        UUID shanghaiAssignee = createAssignmentAt(
                SHANGHAI_HOTEL, GENERAL_MANAGER_POSITION, "P7-READ-SH-ASSIGNEE");
        UUID shanghaiReviewer = createAssignmentAt(
                SHANGHAI_HOTEL, GENERAL_MANAGER_POSITION, "P7-READ-SH-REVIEWER");

        mockMvc.perform(get("/api/v1/tasks/targets")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", scopedOta.accountId()))
                .andExpect(status().isForbidden());

        String unrelatedTaskId = json(postTask(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Outside OTA read grant"
                }
                """.formatted(SHANGHAI_HOTEL, shanghaiAssignee, shanghaiReviewer, STANDARD_VERSION)))
                .path("id").asText();

        mockMvc.perform(get("/api/v1/tasks?view=team")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", scopedOta.accountId()))
                .andExpect(status().isForbidden());
        assertThat(containsId(json(getJson(
                "/api/v1/tasks?view=all", scopedOta.accountId().toString())), unrelatedTaskId)).isFalse();
        mockMvc.perform(get("/api/v1/tasks/{taskId}", unrelatedTaskId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", scopedOta.accountId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noStandardTaskSupportsAuditedManualApproveAndRejectByReviewerOnly() throws Exception {
        String approvedTask = createSubmittedTask("Manual no-standard approval", false);

        postCommandExpecting(approvedTask, "approve", FRONT_OFFICE_SUPERVISOR,
                FRONT_OFFICE_ASSIGNMENT, 3, 200);
        JsonNode approved = json(getJson("/api/v1/tasks/" + approvedTask, FRONT_OFFICE_SUPERVISOR));
        assertThat(approved.path("lifecycle_status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = ?::uuid and resource_id = ?::uuid
                  and action = 'TASK_APPROVE'
                  and after_data ->> 'reviewMode' = 'MANUAL_NO_STANDARD'
                """, Integer.class, TENANT, approvedTask)).isEqualTo(1);

        String rejectedTask = createSubmittedTask("Manual no-standard rejection", false);
        postCommandExpecting(rejectedTask, "reject", FRONT_OFFICE_SUPERVISOR,
                FRONT_OFFICE_ASSIGNMENT, 3, 200);
        JsonNode rejected = json(getJson("/api/v1/tasks/" + rejectedTask, FRONT_OFFICE_SUPERVISOR));
        assertThat(rejected.path("lifecycle_status").asText()).isEqualTo("REWORK");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = ?::uuid and resource_id = ?::uuid
                  and action = 'TASK_REJECT'
                  and after_data ->> 'reviewMode' = 'MANUAL_NO_STANDARD'
                """, Integer.class, TENANT, rejectedTask)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from task_transition
                where tenant_id = ?::uuid and task_id = ?::uuid
                  and command = 'REWORK'
                  and payload ->> 'requestedCommand' = 'REJECT'
                  and payload ->> 'reviewMode' = 'MANUAL_NO_STANDARD'
                """, Integer.class, TENANT, rejectedTask)).isEqualTo(1);
        assertThat(hasNotification(json(getJson("/api/v1/notifications", FRONT_DESK)),
                rejectedTask, "TASK_REWORK")).isTrue();

        String protectedTask = createSubmittedTask("Reviewer-only manual acceptance", false);
        postCommandExpecting(protectedTask, "approve", GENERAL_MANAGER,
                GENERAL_MANAGER_ASSIGNMENT, 3, 403);
    }

    @Test
    void standardBoundTaskCannotBypassEvaluationWithDirectApproveOrReject() throws Exception {
        String taskId = createSubmittedTask("Standard-bound review gate", true);

        postCommandExpecting(taskId, "approve", FRONT_OFFICE_SUPERVISOR,
                FRONT_OFFICE_ASSIGNMENT, 3, 400);
        postCommandExpecting(taskId, "reject", FRONT_OFFICE_SUPERVISOR,
                FRONT_OFFICE_ASSIGNMENT, 3, 400);
        assertThat(json(getJson("/api/v1/tasks/" + taskId, FRONT_OFFICE_SUPERVISOR))
                .path("lifecycle_status").asText()).isEqualTo("RESULT_SUBMITTED");
    }

    private MvcResult postTask(String actorId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .header("Idempotency-Key", "pilot7-task-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static byte[] photo(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private String createSubmittedTask(String title, boolean withStandard) throws Exception {
        String standard = withStandard ? ",\"standardVersionId\":\"" + STANDARD_VERSION + "\"" : "";
        String taskId = json(postTask(CEO, """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "title":"%s",
                  "dispatchNow":true%s
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_DESK_ASSIGNMENT,
                FRONT_OFFICE_ASSIGNMENT, title, standard))).path("id").asText();
        postCommandExpecting(taskId, "acknowledge", FRONT_DESK, FRONT_DESK_ASSIGNMENT, 1, 200);
        postCommandExpecting(taskId, "submit-result", FRONT_DESK, FRONT_DESK_ASSIGNMENT, 2, 200);
        return taskId;
    }

    private void postCommandExpecting(
            String taskId,
            String command,
            String actorId,
            String actorAssignmentId,
            long expectedVersion,
            int expectedStatus
    ) throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{taskId}/actions/{command}", taskId, command)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .header("Idempotency-Key", "pilot7-command-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {
                                  "expectedVersion":%d,
                                  "actorAssignmentId":"%s",
                                  "payload":{"remark":"Pilot 7 authorization test","result":{"summary":"done"}}
                                }
                                """.formatted(expectedVersion, actorAssignmentId)))
                .andExpect(status().is(expectedStatus));
    }

    private void postTaskExpecting(String actorId, String body, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .header("Idempotency-Key", "pilot7-task-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private MvcResult getJson(String path, String actorId) throws Exception {
        return mockMvc.perform(get(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId))
                .andExpect(status().isOk())
                .andReturn();
    }

    private UUID createAssignmentAt(String orgUnitId, String positionId, String employeeNoPrefix) {
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                insert into employee (id, tenant_id, employee_no, name, hired_on)
                values (?, ?::uuid, ?, 'Pilot 7 Target', current_date)
                """, employeeId, TENANT, employeeNoPrefix + "-" + employeeId.toString().substring(0, 8));
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                values (?, ?::uuid, ?, ?::uuid, ?::uuid, true, current_date)
                """, assignmentId, TENANT, employeeId, orgUnitId, positionId);
        return assignmentId;
    }

    private ScopedActor createScopedActor(String roleCode, String scopeOrgUnitId, String positionId) {
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("""
                select id from app_role where tenant_id = ?::uuid and code = ?
                """, UUID.class, TENANT, roleCode);
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name)
                values (?, ?::uuid, ?, 'Scoped Pilot 7 Actor')
                """, accountId, TENANT, "p7.scoped." + accountId.toString().substring(0, 8));
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, hired_on)
                values (?, ?::uuid, ?, ?, 'Scoped Pilot 7 Actor', current_date)
                """, employeeId, TENANT, accountId, "P7-SCOPED-" + employeeId.toString().substring(0, 8));
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                values (?, ?::uuid, ?, ?::uuid, ?::uuid, true, current_date)
                """, assignmentId, TENANT, employeeId, scopeOrgUnitId, positionId);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id, scope_type, valid_from)
                values (?, ?::uuid, ?, ?, ?::uuid, 'ORG_TREE', now())
                """, UUID.randomUUID(), TENANT, accountId, roleId, scopeOrgUnitId);
        return new ScopedActor(accountId, employeeId, assignmentId);
    }

    private UUID addPosition(UUID employeeId, String orgUnitId, String positionId) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                values (?, ?::uuid, ?, ?::uuid, ?::uuid, false, current_date)
                """, assignmentId, TENANT, employeeId, orgUnitId, positionId);
        return assignmentId;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode participant(JsonNode detail, String type) {
        for (JsonNode row : detail.path("participants")) {
            if (type.equals(row.path("participant_type").asText())) {
                return row;
            }
        }
        return objectMapper.createObjectNode();
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) return true;
        }
        return false;
    }

    private boolean containsAssignment(JsonNode rows, String assignmentId) {
        for (JsonNode row : rows) {
            if (assignmentId.equals(row.path("assignment_id").asText())) return true;
        }
        return false;
    }

    private boolean hasNotification(JsonNode rows, String sourceId, String type) {
        for (JsonNode row : rows) {
            if (sourceId.equals(row.path("source_id").asText())
                    && type.equals(row.path("notification_type").asText())) return true;
        }
        return false;
    }

    private record ScopedActor(UUID accountId, UUID employeeId, UUID assignmentId) {
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
