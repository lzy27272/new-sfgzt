package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkExpectationSlaIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String PLATFORM_ADMIN = PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String FRONT_ACCOUNT = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String FRONT_POSITION = "14000000-0000-0000-0000-000000000001";
    private static final String FRONT_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String FRONT_FORM_VERSION = "19700000-0000-0000-0000-000000000001";

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
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
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
    void overdueProcessingIsTenantControlledAuditedIdempotentAndConcurrencySafe() throws Exception {
        LocalDate today = LocalDate.now();
        List<String> expectationIds = createDailyPackageAndExpectations(today);

        String available = expectationIds.get(0);
        String planned = expectationIds.get(1);
        String inProgress = expectationIds.get(2);
        String submitted = expectationIds.get(3);
        String reservedForConcurrency = expectationIds.get(4);

        jdbc.update("update work_expectation set status = 'PLANNED' where id = ?::uuid", planned);
        jdbc.update("update work_expectation set status = 'IN_PROGRESS' where id = ?::uuid", inProgress);
        jdbc.update("update work_expectation set status = 'SUBMITTED' where id = ?::uuid", submitted);
        jdbc.update("update work_expectation set due_at = now() + interval '1 day' where id = ?::uuid",
                reservedForConcurrency);

        mockMvc.perform(identity(post("/api/v1/work-expectations/sla/process"), FRONT_ACCOUNT))
                .andExpect(status().isForbidden());
        mockMvc.perform(identity(post("/api/v1/work-expectations/sla/process?limit=0"), PLATFORM_ADMIN))
                .andExpect(status().isBadRequest());

        JsonNode first = response(identity(post("/api/v1/work-expectations/sla/process?limit=100"), PLATFORM_ADMIN), 200);
        assertThat(first.path("processedCount").asInt()).isEqualTo(3);
        assertThat(first.path("batchLimit").asInt()).isEqualTo(100);
        assertThat(jsonStrings(first.path("expectationIds")))
                .containsExactlyInAnyOrder(available, planned, inProgress);

        assertStatus(available, "MISSED");
        assertStatus(planned, "MISSED");
        assertStatus(inProgress, "MISSED");
        assertStatus(submitted, "SUBMITTED");
        assertStatus(reservedForConcurrency, "AVAILABLE");
        assertMissedEvidence(available);
        assertMissedEvidence(planned);
        assertMissedEvidence(inProgress);

        mockMvc.perform(identity(post("/api/v1/work-expectations/sla/process?limit=100"), PLATFORM_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(0));
        assertMissedEventCount(available, 1);
        assertMissedEventCount(planned, 1);
        assertMissedEventCount(inProgress, 1);

        jdbc.update("update work_expectation set due_at = now() - interval '1 minute' where id = ?::uuid",
                reservedForConcurrency);
        List<JsonNode> concurrentResults = processConcurrently();
        assertThat(concurrentResults.stream().mapToInt(result -> result.path("processedCount").asInt()).sum())
                .isEqualTo(1);
        assertStatus(reservedForConcurrency, "MISSED");
        assertMissedEvidence(reservedForConcurrency);
        assertMissedEventCount(reservedForConcurrency, 1);
    }

    private List<String> createDailyPackageAndExpectations(LocalDate today) throws Exception {
        OffsetDateTime effectiveFrom = OffsetDateTime.now().minusDays(40).withNano(0);
        JsonNode definition = response(identity(post("/api/v1/work-packages"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "code":"WP-SLA-INTEGRATION",
                          "name":"SLA integration package",
                          "positionId":"%s",
                          "ownerOrgUnitId":"%s"
                        }
                        """.formatted(FRONT_POSITION, HANGZHOU_HOTEL)), 201);
        String packageId = definition.path("id").asText();

        JsonNode version = response(identity(post("/api/v1/work-packages/{id}/versions", packageId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"SLA integration package V1\"}"), 201);
        String versionId = version.path("id").asText();

        mockMvc.perform(identity(put("/api/v1/work-packages/{id}/versions/{versionId}", packageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"SLA integration package V1",
                                  "scopes":[{"scopeType":"ORG_TREE","orgUnitId":"%s"}],
                                  "items":[{
                                    "itemCode":"DAILY_SLA_CHECK",
                                    "name":"Daily SLA check",
                                    "itemType":"SCHEDULED_RECORD",
                                    "formVersionId":"%s",
                                    "periodType":"DAY",
                                    "timezoneMode":"TENANT",
                                    "dueLocalTime":"00:01:00",
                                    "waiverAllowed":true,
                                    "targetGranularity":"TARGET_ORG",
                                    "reviewMode":"NONE",
                                    "standards":[],
                                    "responsibilities":[
                                      {"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}
                                    ]
                                  }]
                                }
                                """.formatted(HANGZHOU_HOTEL, FRONT_FORM_VERSION)))
                .andExpect(status().isOk());

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/versions/{versionId}/publish",
                packageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + effectiveFrom + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/allocations", packageId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workPackageVersionId":"%s",
                                  "positionAssignmentId":"%s",
                                  "targetOrgUnitId":"%s",
                                  "validFrom":"%s"
                                }
                                """.formatted(versionId, FRONT_ASSIGNMENT, FRONT_DEPARTMENT,
                                today.minusDays(30))))
                .andExpect(status().isCreated());

        List<String> expectationIds = new ArrayList<>();
        for (int offset = 10; offset < 15; offset++) {
            LocalDate businessDate = today.minusDays(offset);
            JsonNode generation = response(identity(post("/api/v1/work-expectations/actions/generate"), PLATFORM_ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "positionAssignmentId":"%s",
                              "targetOrgUnitId":"%s",
                              "businessDate":"%s",
                              "periodType":"DAY"
                            }
                            """.formatted(FRONT_ASSIGNMENT, FRONT_DEPARTMENT, businessDate)), 201);
            assertThat(generation.path("createdCount").asInt()).isEqualTo(1);
            expectationIds.add(generation.path("createdIds").get(0).asText());
        }
        return expectationIds;
    }

    private List<JsonNode> processConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(this::processSlaUnchecked, executor);
            CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(this::processSlaUnchecked, executor);
            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
            return List.of(first.join(), second.join());
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode processSlaUnchecked() {
        try {
            return response(identity(post("/api/v1/work-expectations/sla/process?limit=100"), PLATFORM_ADMIN), 200);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertStatus(String expectationId, String expectedStatus) {
        String actual = jdbc.queryForObject(
                "select status from work_expectation where id = ?::uuid", String.class, expectationId);
        assertThat(actual).isEqualTo(expectedStatus);
    }

    private void assertMissedEvidence(String expectationId) throws Exception {
        Integer auditCount = jdbc.queryForObject("""
                select count(*) from audit_log
                where resource_id = ?::uuid and action = 'WORK_EXPECTATION_MISSED'
                """, Integer.class, expectationId);
        assertThat(auditCount).isEqualTo(1);
        assertMissedEventCount(expectationId, 1);

        String payload = jdbc.queryForObject("""
                select payload::text from outbox_event
                where aggregate_id = ?::uuid and event_type = 'WORKEXPECTATIONMISSED'
                """, String.class, expectationId);
        JsonNode facts = objectMapper.readTree(payload);
        assertThat(facts.path("workExpectationId").asText()).isEqualTo(expectationId);
        assertThat(facts.path("workPackageItemId").asText()).isNotBlank();
        assertThat(facts.path("positionAssignmentId").asText()).isEqualTo(FRONT_ASSIGNMENT);
        assertThat(facts.path("orgUnitId").asText()).isEqualTo(FRONT_DEPARTMENT);
        assertThat(facts.path("businessDate").asText()).isNotBlank();
        assertThat(facts.path("dueAt").asText()).isNotBlank();
        assertThat(facts.path("status").asText()).isEqualTo("MISSED");

        Integer projectionCount = jdbc.queryForObject("""
                select count(*) from management_event e
                join outbox_event o on o.id = e.source_event_id
                where o.aggregate_id = ?::uuid and o.event_type = 'WORKEXPECTATIONMISSED'
                """, Integer.class, expectationId);
        assertThat(projectionCount).isEqualTo(1);
    }

    private void assertMissedEventCount(String expectationId, int expected) {
        Integer count = jdbc.queryForObject("""
                select count(*) from outbox_event
                where aggregate_id = ?::uuid and event_type = 'WORKEXPECTATIONMISSED'
                """, Integer.class, expectationId);
        assertThat(count).isEqualTo(expected);
    }

    private List<String> jsonStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request, String accountId) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", accountId);
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
