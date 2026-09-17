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

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Sprint2AuthorizationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE_SUPERVISOR = "19000000-0000-0000-0000-000000000005";

    private static final String FRONT_DESK_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String HOUSEKEEPING_ASSIGNMENT = "19200000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE_ASSIGNMENT = "19200000-0000-0000-0000-000000000004";

    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String FRONT_DESK_POSITION = "14000000-0000-0000-0000-000000000001";
    private static final String FRONT_OFFICE_SUPERVISOR_POSITION = "14000000-0000-0000-0000-000000000003";
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
    void taskVisibilityRequiresParticipationOrAuthorizedManagerScope() throws Exception {
        UUID assigneeAssignment = createUnprivilegedAssignment(FRONT_DESK_POSITION, "AUTH-TASK-ASSIGNEE");
        UUID reviewerAssignment = createUnprivilegedAssignment(FRONT_OFFICE_SUPERVISOR_POSITION, "AUTH-TASK-REVIEWER");

        MvcResult created = postJson("/api/v1/tasks", CEO, "auth-visibility-create-" + UUID.randomUUID(), """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Authorization visibility task",
                  "priority":"NORMAL"
                }
                """.formatted(FRONT_OFFICE_DEPARTMENT, assigneeAssignment, reviewerAssignment, STANDARD_VERSION));
        String taskId = json(created).path("id").asText();

        String filteredTaskList = "/api/v1/tasks?status=PROPOSED&orgUnitId=" + FRONT_OFFICE_DEPARTMENT;
        MvcResult frontDeskList = getJson(filteredTaskList, FRONT_DESK);
        assertThat(containsId(json(frontDeskList), taskId)).isFalse();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isForbidden());

        MvcResult supervisorList = getJson(filteredTaskList, FRONT_OFFICE_SUPERVISOR);
        assertThat(containsId(json(supervisorList), taskId)).isTrue();
        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId));
    }

    @Test
    void frontDeskCannotReadTeamWorkExpectations() throws Exception {
        mockMvc.perform(get("/api/v1/team/work-expectations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK))
                .andExpect(status().isForbidden());
    }

    @Test
    void workExpectationPaginationIsStableAndRejectsUnsafeRanges() throws Exception {
        JsonNode firstPage = json(getJson("/api/v1/team/work-expectations?page=0&size=1", CEO));
        JsonNode secondPage = json(getJson("/api/v1/team/work-expectations?page=1&size=1", CEO));
        assertThat(firstPage.isArray()).isTrue();
        assertThat(secondPage.isArray()).isTrue();
        assertThat(firstPage).hasSize(1);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage.get(0).path("id").asText())
                .isNotEqualTo(secondPage.get(0).path("id").asText());

        mockMvc.perform(get("/api/v1/team/work-expectations?page=0&size=201")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/team/work-expectations")
                        .param("fromBusinessDate", "2026-09-15")
                        .param("toBusinessDate", "2026-09-01")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optionalListFiltersCanBeOmitted() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/standard-evaluations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/org/units")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/management-events")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO))
                .andExpect(status().isOk());
    }

    @Test
    void taskEvaluationMustUseTheStandardVersionBoundToTheTask() throws Exception {
        UUID differentPublishedStandard = createSecondPublishedStandard();
        String taskId = json(postJson("/api/v1/tasks", CEO, "auth-evaluation-create-" + UUID.randomUUID(), """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"Task standard binding",
                  "priority":"HIGH"
                }
                """.formatted(FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT,
                FRONT_OFFICE_ASSIGNMENT, STANDARD_VERSION))).path("id").asText();

        postJson("/api/v1/tasks/" + taskId + "/actions/dispatch", CEO,
                "auth-evaluation-dispatch-" + UUID.randomUUID(), """
                        {"expectedVersion":0,"payload":{"reason":"authorization test"}}
                        """);
        postJson("/api/v1/tasks/" + taskId + "/actions/acknowledge", FRONT_DESK,
                "auth-evaluation-ack-" + UUID.randomUUID(), """
                        {"expectedVersion":1,"actorAssignmentId":"%s","payload":{}}
                        """.formatted(FRONT_DESK_ASSIGNMENT));
        postJson("/api/v1/tasks/" + taskId + "/actions/submit-result", FRONT_DESK,
                "auth-evaluation-submit-" + UUID.randomUUID(), """
                        {
                          "expectedVersion":2,
                          "actorAssignmentId":"%s",
                          "payload":{"summary":"completed"}
                        }
                        """.formatted(FRONT_DESK_ASSIGNMENT));

        String evaluationBody = """
                {
                  "subjectType":"TASK",
                  "subjectId":"%s",
                  "orgUnitId":"%s",
                  "positionAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "inputSnapshot":{
                    "greeting":true,
                    "identity":true,
                    "explain":true,
                    "key":true,
                    "farewell":true
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/standard-evaluations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR)
                        .header("Idempotency-Key", "auth-evaluation-wrong-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(evaluationBody.formatted(taskId, FRONT_OFFICE_DEPARTMENT,
                                FRONT_DESK_ASSIGNMENT, differentPublishedStandard)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/standard-evaluations")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR)
                        .header("Idempotency-Key", "auth-evaluation-correct-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(evaluationBody.formatted(taskId, FRONT_OFFICE_DEPARTMENT,
                                FRONT_DESK_ASSIGNMENT, STANDARD_VERSION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject_type").value("TASK"))
                .andExpect(jsonPath("$.subject_id").value(taskId))
                .andExpect(jsonPath("$.standard_version_id").value(STANDARD_VERSION));
    }

    @Test
    void taskOrganizationCannotExcludeTheAssigneeDepartment() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_OFFICE_SUPERVISOR)
                        .header("Idempotency-Key", "auth-outside-scope-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {
                                  "orgUnitId":"%s",
                                  "assigneeAssignmentId":"%s",
                                  "reviewerAssignmentId":"%s",
                                  "standardVersionId":"%s",
                                  "title":"Outside authorized assignment",
                                  "priority":"NORMAL"
                                }
                                """.formatted(FRONT_OFFICE_DEPARTMENT, HOUSEKEEPING_ASSIGNMENT,
                                FRONT_OFFICE_ASSIGNMENT, STANDARD_VERSION)))
                .andExpect(status().isForbidden());
    }

    private UUID createUnprivilegedAssignment(String positionId, String employeeNoPrefix) {
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                insert into employee (id, tenant_id, employee_no, name, hired_on)
                values (?, ?::uuid, ?, 'Authorization Test Employee', current_date)
                """, employeeId, TENANT, employeeNoPrefix + "-" + employeeId.toString().substring(0, 8));
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, is_primary, valid_from)
                values (?, ?::uuid, ?, ?::uuid, ?::uuid, true, current_date)
                """, assignmentId, TENANT, employeeId, FRONT_OFFICE_DEPARTMENT, positionId);
        return assignmentId;
    }

    private UUID createSecondPublishedStandard() {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String code = "STD-AUTH-OTHER-" + definitionId.toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                insert into standard_definition
                    (id, tenant_id, category_id, code, name, owner_org_unit_id)
                values
                    (?, ?::uuid, '15000000-0000-0000-0000-000000000001'::uuid,
                     ?, 'Different published standard', ?::uuid)
                """, definitionId, TENANT, code, HANGZHOU_HOTEL);
        jdbc.update("""
                insert into standard_version
                    (id, tenant_id, standard_id, version_no, lifecycle_status, title,
                     items, evidence_requirements, scoring_rules, effective_from, published_at)
                values
                    (?, ?::uuid, ?, 1, 'PUBLISHED', 'Different published standard V1',
                     '[{"code":"other","required":true}]'::jsonb,
                     '[]'::jsonb, '{"passScore":80,"fullScore":100}'::jsonb, now(), now())
                """, versionId, TENANT, definitionId);
        return versionId;
    }

    private MvcResult getJson(String path, String actorId) throws Exception {
        return mockMvc.perform(get(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult postJson(String path, String actorId, String idempotencyKey, String body) throws Exception {
        var builder = post(path)
                .header("X-Tenant-Id", TENANT)
                .header("X-Actor-Id", actorId)
                .contentType("application/json")
                .content(body);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder)
                .andExpect(status().is2xxSuccessful())
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
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
