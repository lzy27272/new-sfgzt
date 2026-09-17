package cn.sifangguan.hotelaios;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.OutboxAutomationService;
import cn.sifangguan.hotelaios.shared.events.OutboxCreatedEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Sprint2RuleTaskIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String PLATFORM_ADMIN = PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String FRONT_DESK_ACCOUNT = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE_SUPERVISOR = "19000000-0000-0000-0000-000000000005";
    private static final String FRONT_DESK_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String FRONT_OFFICE_ASSIGNMENT = "19200000-0000-0000-0000-000000000004";
    private static final String FRONT_OFFICE_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
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
    @Autowired
    private AuditWriter auditWriter;
    @Autowired
    private TenantDatabaseContext databaseContext;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxAutomationService automationService;

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

    @BeforeEach
    void ensurePlatformAdministrator() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, CEO);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void closesTaskWithExactAssignmentsEvaluationAndOptimisticVersion() throws Exception {
        MvcResult created = postJson("/api/v1/tasks", CEO, "task-e2e-create", """
                {
                  "orgUnitId":"%s",
                  "assigneeAssignmentId":"%s",
                  "reviewerAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "title":"前台客诉整改",
                  "priority":"HIGH"
                }
                """.formatted(FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT,
                FRONT_OFFICE_ASSIGNMENT, STANDARD_VERSION));
        JsonNode task = json(created);
        String taskId = task.path("id").asText();
        assertThat(task.path("lifecycle_status").asText()).isEqualTo("PROPOSED");

        postJson("/api/v1/tasks/" + taskId + "/actions/dispatch", CEO, "task-e2e-dispatch", """
                {"expectedVersion":0,"payload":{"reason":"主管派发"}}
                """);
        postJson("/api/v1/tasks/" + taskId + "/actions/acknowledge", FRONT_DESK_ACCOUNT, "task-e2e-ack", """
                {"expectedVersion":1,"actorAssignmentId":"%s","payload":{}}
                """.formatted(FRONT_DESK_ASSIGNMENT));

        MvcResult duplicateAck = postJson("/api/v1/tasks/" + taskId + "/actions/acknowledge",
                FRONT_DESK_ACCOUNT, "task-e2e-ack", """
                        {"expectedVersion":1,"actorAssignmentId":"%s","payload":{}}
                        """.formatted(FRONT_DESK_ASSIGNMENT));
        assertThat(json(duplicateAck).path("row_version").asLong()).isEqualTo(2);

        postJson("/api/v1/tasks/" + taskId + "/actions/submit-result", FRONT_DESK_ACCOUNT, "task-e2e-submit", """
                {
                  "expectedVersion":2,
                  "actorAssignmentId":"%s",
                  "payload":{"summary":"已按标准补齐并回访"}
                }
                """.formatted(FRONT_DESK_ASSIGNMENT));

        MvcResult evaluation = postJson("/api/v1/standard-evaluations", FRONT_OFFICE_SUPERVISOR, "evaluation-e2e", """
                {
                  "subjectType":"TASK",
                  "subjectId":"%s",
                  "orgUnitId":"%s",
                  "positionAssignmentId":"%s",
                  "standardVersionId":"%s",
                  "inputSnapshot":{
                    "greeting":true,"identity":true,"explain":true,"key":true,"farewell":true
                  }
                }
                """.formatted(taskId, FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT, STANDARD_VERSION));

        String evaluationId = json(evaluation).path("id").asText();
        String completedEventPayload = jdbc.queryForObject("""
                select payload::text
                  from outbox_event
                 where tenant_id = ?::uuid
                   and aggregate_id = ?::uuid
                   and event_type = 'STANDARDEVALUATIONCOMPLETED'
                 order by occurred_at desc
                 limit 1
                """, String.class, TENANT, evaluationId);
        JsonNode completedEvent = objectMapper.readTree(completedEventPayload);
        assertThat(completedEvent.path("subjectType").asText()).isEqualTo("TASK");
        assertThat(completedEvent.path("taskId").asText()).isEqualTo(taskId);
        assertThat(completedEvent.path("orgUnitId").asText()).isEqualTo(FRONT_OFFICE_DEPARTMENT);
        assertThat(completedEvent.path("positionAssignmentId").asText()).isEqualTo(FRONT_DESK_ASSIGNMENT);
        assertThat(completedEvent.path("standardVersionId").asText()).isEqualTo(STANDARD_VERSION);

        MvcResult approved = postJson("/api/v1/tasks/" + taskId + "/actions/approve",
                FRONT_OFFICE_SUPERVISOR, "task-e2e-approve", """
                        {
                          "expectedVersion":4,
                          "actorAssignmentId":"%s",
                          "payload":{"comment":"验收通过"}
                        }
                        """.formatted(FRONT_OFFICE_ASSIGNMENT));
        assertThat(json(approved).path("lifecycle_status").asText()).isEqualTo("COMPLETED");
        assertThat(json(approved).path("row_version").asLong()).isEqualTo(5);
    }

    @Test
    void simulatesAndConsumesRuleExactlyOnce() throws Exception {
        MvcResult createdRule = postJson("/api/v1/rules", PLATFORM_ADMIN, null, """
                {"code":"RULE-OTA-RISK","name":"OTA评分风险提醒","eventType":"OTA_SCORE_RISK"}
                """);
        String ruleId = json(createdRule).path("id").asText();

        MvcResult createdVersion = postJson("/api/v1/rules/" + ruleId + "/versions", PLATFORM_ADMIN, null, """
                {
                  "conditionAst":{"op":"LT","fact":"score","value":4.9},
                  "actions":[{
                    "key":"notify-owner","type":"CREATE_NOTIFICATION",
                    "recipientResolver":"CURRENT_ASSIGNMENT","title":"OTA评分风险"
                  }],
                  "priority":10,
                  "cooldownMinutes":0,
                  "scopes":[{"scopeType":"TENANT"}]
                }
                """);
        JsonNode version = json(createdVersion);
        String versionId = version.path("id").asText();

        postJson("/api/v1/rules/" + ruleId + "/versions/" + versionId + "/simulate", PLATFORM_ADMIN, null, """
                {"facts":{"score":4.8}}
                """).getResponse().getContentAsString();

        postJson("/api/v1/rules/" + ruleId + "/versions/" + versionId + "/publish", PLATFORM_ADMIN, null, """
                {"expectedVersion":0,"effectiveFrom":"%s"}
                """.formatted(OffsetDateTime.now().minusMinutes(1)));

        UUID sourceEventId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, ?::uuid, 'METRIC', ?, 'OTA_SCORE_RISK', '{"score":4.8}'::jsonb)
                """, sourceEventId, TENANT, UUID.randomUUID());
        jdbc.update("""
                insert into management_event
                    (id, tenant_id, source_event_id, event_type, org_unit_id,
                     position_assignment_id, occurred_at, payload_snapshot)
                values (?, ?::uuid, ?, 'OTA_SCORE_RISK', ?::uuid, ?::uuid, now(), '{"score":4.8}'::jsonb)
                """, eventId, TENANT, sourceEventId, FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT);

        MvcResult consumed = postJson("/api/v1/management-events/" + eventId + "/consume", PLATFORM_ADMIN, null, "{}");
        JsonNode result = json(consumed);
        assertThat(result.path("event").path("processing_status").asText()).isEqualTo("PROCESSED");
        assertThat(result.path("evaluations")).hasSize(1);
        assertThat(result.path("actions")).hasSize(1);
        assertThat(result.path("actions").get(0).path("status").asText()).isEqualTo("SUCCEEDED");

        MvcResult replay = postJson("/api/v1/management-events/" + eventId + "/consume", PLATFORM_ADMIN, null, "{}");
        assertThat(json(replay).path("actions")).hasSize(1);
    }

    @Test
    void camelCaseAuditOutboxEventMatchesCanonicalRuleType() throws Exception {
        String eventType = "WorkRecordSubmitted";
        String ruleId = createAndPublishNotificationRule("RULE-WORK-SUBMITTED-P0", eventType,
                "CURRENT_ASSIGNMENT", 0);

        UUID outboxEventId = emitAuditEvent(eventType, """
                {"workRecordId":"%s","orgUnitId":"%s","positionAssignmentId":"%s"}
                """.formatted(UUID.randomUUID(), FRONT_OFFICE_DEPARTMENT, FRONT_DESK_ASSIGNMENT));

        assertThat(jdbc.queryForObject(
                "select event_type from outbox_event where id = ?", String.class, outboxEventId))
                .isEqualTo("WORKRECORDSUBMITTED");
        UUID managementEventId = jdbc.queryForObject(
                "select id from management_event where source_event_id = ?", UUID.class, outboxEventId);
        assertThat(jdbc.queryForObject(
                "select processing_status from management_event where id = ?", String.class, managementEventId))
                .isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("""
                select count(*) from rule_action_execution a
                join rule_version v on v.id = a.rule_version_id
                where a.management_event_id = ? and v.rule_id = ?::uuid and a.status = 'SUCCEEDED'
                """, Integer.class, managementEventId, ruleId)).isEqualTo(1);
    }

    @Test
    void failedRuleActionKeepsEventFailedAndRecoveryRetriesExistingAction() throws Exception {
        String eventType = "RetryableActionEvent";
        createAndPublishNotificationRule("RULE-RETRY-ACTION-P0", eventType,
                "CURRENT_ASSIGNMENT", 30);

        UUID outboxEventId = emitAuditEvent(eventType,
                "{\"workRecordId\":\"" + UUID.randomUUID() + "\",\"orgUnitId\":\""
                        + FRONT_OFFICE_DEPARTMENT + "\"}");
        UUID managementEventId = jdbc.queryForObject(
                "select id from management_event where source_event_id = ?", UUID.class, outboxEventId);
        assertThat(jdbc.queryForObject(
                "select processing_status from management_event where id = ?", String.class, managementEventId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select status from rule_action_execution where management_event_id = ?", String.class, managementEventId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select locked_until > now() from management_event where id = ?", Boolean.class, managementEventId))
                .isTrue();

        jdbc.update("""
                update management_event
                set position_assignment_id = ?::uuid, locked_until = now()
                where id = ?
                """, FRONT_DESK_ASSIGNMENT, managementEventId);
        postJson("/api/v1/management-events/actions/project-outbox?limit=20", PLATFORM_ADMIN, null, "{}");

        assertThat(jdbc.queryForObject(
                "select processing_status from management_event where id = ?", String.class, managementEventId))
                .isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject(
                "select status from rule_action_execution where management_event_id = ?", String.class, managementEventId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select attempt_count from rule_action_execution where management_event_id = ?", Integer.class, managementEventId))
                .isEqualTo(2);
    }

    @Test
    void automationAccountIsResolvedInsideEachTenant() {
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000099");
        UUID outboxEventId = UUID.randomUUID();
        jdbc.update("""
                insert into tenant (id, code, name) values (?, 'P0-TENANT', 'P0 tenant')
                on conflict (id) do nothing
                """, tenantId);
        jdbc.update("""
                insert into outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, ?, 'TEST', ?, 'TenantAutomationProbe', '{}'::jsonb)
                """, outboxEventId, tenantId, UUID.randomUUID());

        OutboxAutomationService service = automationService;
        var result = service.process(new OutboxCreatedEvent(tenantId, outboxEventId, UUID.randomUUID()));

        assertThat(result.status()).isEqualTo("PUBLISHED");
        UUID tenantSystemAccount = jdbc.queryForObject("""
                select id from user_account where tenant_id = ? and login_name = 'system.automation'
                """, UUID.class, tenantId);
        assertThat(tenantSystemAccount)
                .isNotEqualTo(UUID.fromString("19000000-0000-0000-0000-000000000009"));
        assertThat(jdbc.queryForObject("""
                select processing_status from management_event
                where tenant_id = ? and source_event_id = ?
                """, String.class, tenantId, outboxEventId)).isEqualTo("PROCESSED");
    }

    private String createAndPublishNotificationRule(
            String code,
            String eventType,
            String recipientResolver,
            int cooldownMinutes
    ) throws Exception {
        MvcResult createdRule = postJson("/api/v1/rules", PLATFORM_ADMIN, null, """
                {"code":"%s","name":"P0 rule","eventType":"%s"}
                """.formatted(code, eventType));
        String ruleId = json(createdRule).path("id").asText();
        MvcResult createdVersion = postJson("/api/v1/rules/" + ruleId + "/versions", PLATFORM_ADMIN, null, """
                {
                  "conditionAst":{"op":"EXISTS","fact":"workRecordId"},
                  "actions":[{
                    "key":"notify-owner","type":"CREATE_NOTIFICATION",
                    "recipientResolver":"%s","title":"P0 event notification"
                  }],
                  "priority":10,
                  "cooldownMinutes":%d,
                  "scopes":[{"scopeType":"TENANT"}]
                }
                """.formatted(recipientResolver, cooldownMinutes));
        JsonNode version = json(createdVersion);
        String versionId = version.path("id").asText();
        postJson("/api/v1/rules/" + ruleId + "/versions/" + versionId + "/publish", PLATFORM_ADMIN, null, """
                {"expectedVersion":0,"effectiveFrom":"%s"}
                """.formatted(OffsetDateTime.now().minusMinutes(1)));
        return ruleId;
    }

    private UUID emitAuditEvent(String eventType, String payload) {
        TenantPrincipal principal = new TenantPrincipal(
                UUID.fromString(TENANT), UUID.fromString(CEO), "CEO",
                Set.of("CEO"), Set.of("rule.manage"), Set.of(), Set.of(), true, UUID.randomUUID());
        TenantContext.set(principal);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                databaseContext.apply(principal.tenantId());
                return auditWriter.emit("WORK_RECORD", UUID.randomUUID(), eventType, payload);
            });
        } finally {
            TenantContext.clear();
        }
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
        return mockMvc.perform(builder).andExpect(status().is2xxSuccessful()).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
