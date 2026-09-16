package cn.sifangguan.hotelaios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExecutiveTaskIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String GROUP = "12000000-0000-0000-0000-000000000001";
    private static final UUID CHAIRMAN = UUID.fromString("39100000-0000-0000-0000-000000000001");
    private static final UUID GENERAL_MANAGER = UUID.fromString("39100000-0000-0000-0000-000000000002");
    private static final UUID VICE_PRESIDENT = UUID.fromString("39100000-0000-0000-0000-000000000003");
    private static final UUID HR = UUID.fromString("39100000-0000-0000-0000-000000000004");
    private static final UUID OTHER_CHAIRMAN = UUID.fromString("39100000-0000-0000-0000-000000000005");
    private static final UUID CHAIRMAN_ASSIGNMENT = UUID.fromString("39200000-0000-0000-0000-000000000001");
    private static final UUID GENERAL_MANAGER_ASSIGNMENT = UUID.fromString("39200000-0000-0000-0000-000000000002");
    private static final UUID VICE_PRESIDENT_ASSIGNMENT = UUID.fromString("39200000-0000-0000-0000-000000000003");
    private static final UUID HR_ASSIGNMENT = UUID.fromString("39200000-0000-0000-0000-000000000004");
    private static final UUID OTHER_CHAIRMAN_ASSIGNMENT = UUID.fromString("39200000-0000-0000-0000-000000000005");

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.group-management.executive-tasks-enabled", () -> true);
        registry.add("app.group-management.tenant-ids", () -> TENANT);
    }

    @BeforeEach
    void seedActors() {
        tenantContext();
        insertActor(CHAIRMAN, CHAIRMAN_ASSIGNMENT, "B-CHAIR", "董事长甲", "GROUP_CHAIRMAN", true);
        insertActor(GENERAL_MANAGER, GENERAL_MANAGER_ASSIGNMENT, "B-GM", "集团总经理", "GROUP_GENERAL_MANAGER", true);
        insertActor(VICE_PRESIDENT, VICE_PRESIDENT_ASSIGNMENT, "B-VP", "集团副总经理", "GROUP_VICE_PRESIDENT", true);
        insertActor(HR, HR_ASSIGNMENT, "B-HR", "行政人事", "HR_ADMINISTRATION", true);
        insertActor(OTHER_CHAIRMAN, OTHER_CHAIRMAN_ASSIGNMENT, "B-CHAIR-2", "董事长乙", "GROUP_CHAIRMAN", true);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void exposesOnlyGeneralManagerAndVicePresidentTargets() throws Exception {
        JsonNode targets = json(mockMvc.perform(get("/api/v1/executive-tasks/targets")
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT)))
                .andExpect(status().isOk()).andReturn());
        assertThat(targets.findValuesAsText("assignmentId"))
                .contains(GENERAL_MANAGER_ASSIGNMENT.toString(), VICE_PRESIDENT_ASSIGNMENT.toString())
                .doesNotContain(HR_ASSIGNMENT.toString(), OTHER_CHAIRMAN_ASSIGNMENT.toString());
    }

    @Test
    void chairmanIdentityExposesOnlyTheEnabledDedicatedDispatchContract() throws Exception {
        JsonNode me = json(mockMvc.perform(get("/api/v1/iam/me")
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.groupManagement.executiveTasksEnabled").value(true))
                .andReturn());

        JsonNode assignment = null;
        for (JsonNode candidate : me.path("positionAssignments")) {
            if (CHAIRMAN_ASSIGNMENT.toString().equals(candidate.path("id").asText())) {
                assignment = candidate;
                break;
            }
        }
        assertThat(assignment).isNotNull();
        List<String> permissionCodes = new ArrayList<>();
        assignment.path("permissionCodes").forEach(permission -> permissionCodes.add(permission.asText()));
        assertThat(permissionCodes)
                .contains("executive-task.read", "executive-task.assign")
                .doesNotContain("task.create", "task.dispatch", "template.read");
    }

    @Test
    void createsDispatchesAndReplaysOneAuthoritativeDirectiveAtomically() throws Exception {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> body = createBody(commandId, GENERAL_MANAGER_ASSIGNMENT, "董事长重点交办");
        JsonNode first = json(postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, commandId.toString(), body, 201));
        JsonNode replay = json(postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, commandId.toString(), body, 201));
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(first.path("lifecycleStatus").asText()).isEqualTo("PENDING_ACK");
        assertThat(first.path("creationSource").asText()).isEqualTo("CHAIRMAN_DIRECTIVE");
        assertThat(first.path("description").asText()).isEqualTo("请完成集团级重点事项");
        assertThat(first.path("evidenceCount").asInt()).isZero();
        assertThat(first.has("evidence")).isFalse();

        tenantContext();
        assertThat(count("management_task", first.path("id").asText())).isOne();
        assertThat(count("executive_task_directive", first.path("id").asText())).isOne();
        assertThat(count("task_participant", first.path("id").asText())).isEqualTo(2);
        assertThat(count("task_transition", first.path("id").asText())).isEqualTo(2);
        assertThat(count("task_reminder", first.path("id").asText())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = ?::uuid and resource_id = ?::uuid
                  and action = 'EXECUTIVE_TASK_ASSIGNED'
                """, Integer.class, TENANT, first.path("id").asText())).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_event
                where tenant_id = ?::uuid and aggregate_id = ?::uuid
                  and event_type = 'EXECUTIVETASKASSIGNED'
                """, Integer.class, TENANT, first.path("id").asText())).isOne();

        JsonNode list = json(mockMvc.perform(get("/api/v1/executive-tasks")
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT)))
                .andExpect(status().isOk()).andReturn());
        JsonNode summary = findById(list, first.path("id").asText());
        assertThat(summary.path("title").asText()).isEqualTo("董事长重点交办");
        assertThat(summary.has("description")).isFalse();
        assertThat(summary.has("evidenceCount")).isFalse();
        assertThat(summary.has("resultSummary")).isFalse();
    }

    @Test
    void rejectsForbiddenTargetSecurityFieldsAndIdempotencyPayloadCollision() throws Exception {
        UUID forbiddenTargetCommand = UUID.randomUUID();
        postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, forbiddenTargetCommand.toString(),
                createBody(forbiddenTargetCommand, HR_ASSIGNMENT, "越权目标"), 403)
                .getResponse();

        UUID forgedCommand = UUID.randomUUID();
        Map<String, Object> forged = new LinkedHashMap<>(createBody(
                forgedCommand, GENERAL_MANAGER_ASSIGNMENT, "伪造来源"));
        forged.put("creationSource", "MANUAL");
        forged.put("reviewerAssignmentId", HR_ASSIGNMENT);
        postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, forgedCommand.toString(), forged, 400);

        UUID commandId = UUID.randomUUID();
        postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, commandId.toString(),
                createBody(commandId, GENERAL_MANAGER_ASSIGNMENT, "幂等原请求"), 201);
        MvcResult collision = postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, commandId.toString(),
                createBody(commandId, VICE_PRESIDENT_ASSIGNMENT, "幂等异载荷"), 409);
        assertThat(json(collision).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void onlyOriginatingChairmanCanApproveOrReworkThroughDedicatedActions() throws Exception {
        UUID approveCommand = UUID.randomUUID();
        JsonNode created = json(postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, approveCommand.toString(),
                createBody(approveCommand, GENERAL_MANAGER_ASSIGNMENT, "待验收交办"), 201));
        String taskId = created.path("id").asText();
        moveToSubmitted(taskId);

        mockMvc.perform(post("/api/v1/executive-tasks/{taskId}/actions/approve", taskId)
                        .headers(headers(OTHER_CHAIRMAN, OTHER_CHAIRMAN_ASSIGNMENT))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXECUTIVE_TASK_ACTION_FORBIDDEN"));

        String actionKey = UUID.randomUUID().toString();
        MvcResult approved = mockMvc.perform(post("/api/v1/executive-tasks/{taskId}/actions/approve", taskId)
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT))
                        .header("Idempotency-Key", actionKey)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2,\"comment\":\"同意验收\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(approved).path("lifecycleStatus").asText()).isEqualTo("COMPLETED");
        mockMvc.perform(post("/api/v1/executive-tasks/{taskId}/actions/approve", taskId)
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT))
                        .header("Idempotency-Key", actionKey)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2,\"comment\":\"同意验收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("COMPLETED"));

        UUID reworkCommand = UUID.randomUUID();
        JsonNode reworkTask = json(postCreate(CHAIRMAN, CHAIRMAN_ASSIGNMENT, reworkCommand.toString(),
                createBody(reworkCommand, VICE_PRESIDENT_ASSIGNMENT, "待退回交办"), 201));
        moveToSubmitted(reworkTask.path("id").asText());
        mockMvc.perform(post("/api/v1/executive-tasks/{taskId}/actions/rework", reworkTask.path("id").asText())
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2,\"reason\":\"结果需补充\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("REWORK"));
    }

    @Test
    void chairmanCannotUseGenericTaskOrEvidenceEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/tasks/targets")
                        .headers(headers(CHAIRMAN, CHAIRMAN_ASSIGNMENT)))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> createBody(UUID commandId, UUID target, String title) {
        OffsetDateTime now = OffsetDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.SECONDS);
        return Map.of(
                "clientCommandId", commandId,
                "targetAssignmentId", target,
                "title", title,
                "description", "请完成集团级重点事项",
                "priority", "HIGH",
                "dueAt", now.plusDays(2),
                "reminderTimes", List.of(now.plusHours(4), now.plusDays(1))
        );
    }

    private MvcResult postCreate(UUID actor, UUID assignment, String key, Map<String, Object> body, int statusCode)
            throws Exception {
        return mockMvc.perform(post("/api/v1/executive-tasks")
                        .headers(headers(actor, assignment))
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(statusCode)).andReturn();
    }

    private org.springframework.http.HttpHeaders headers(UUID actor, UUID assignment) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Tenant-Id", TENANT);
        headers.add("X-Actor-Id", actor.toString());
        headers.add("X-Assignment-Id", assignment.toString());
        return headers;
    }

    private void moveToSubmitted(String taskId) {
        tenantContext();
        jdbc.update("""
                update management_task set lifecycle_status = 'RESULT_SUBMITTED', row_version = 2,
                    result_snapshot = '{"result":{"summary":"已完成"}}'::jsonb
                where tenant_id = ?::uuid and id = ?::uuid
                """, TENANT, taskId);
    }

    private int count(String table, String taskId) {
        String taskColumn = "management_task".equals(table) ? "id" : "task_id";
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?::uuid and "
                        + taskColumn + " = ?::uuid",
                Integer.class, TENANT, taskId);
    }

    private void insertActor(UUID accountId, UUID assignmentId, String employeeNo, String name,
                             String positionCode, boolean primary) {
        UUID employeeId = UUID.nameUUIDFromBytes(("employee:" + accountId).getBytes());
        UUID positionId = jdbc.queryForObject("""
                select id from position_definition
                where tenant_id = ?::uuid and code = ?
                """, UUID.class, TENANT, positionCode);
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name)
                values (?, ?::uuid, ?, ?) on conflict (tenant_id, id) do nothing
                """, accountId, TENANT, employeeNo.toLowerCase(), name);
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, hired_on)
                values (?, ?::uuid, ?, ?, ?, current_date) on conflict (tenant_id, id) do nothing
                """, employeeId, TENANT, accountId, employeeNo, name);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                values (?, ?::uuid, ?, ?::uuid, ?, ?, current_date)
                on conflict (tenant_id, id) do nothing
                """, assignmentId, TENANT, employeeId, GROUP, positionId, primary);
    }

    private void tenantContext() {
        jdbc.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, TENANT);
    }

    private JsonNode findById(JsonNode list, String id) {
        for (JsonNode item : list) if (id.equals(item.path("id").asText())) return item;
        return objectMapper.createObjectNode();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) { return connection.getMetaData().getURL(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
