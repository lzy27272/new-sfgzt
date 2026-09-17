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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkPackageFlowIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String PLATFORM_ADMIN = PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String FRONT_ACCOUNT = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_EMPLOYEE = "19100000-0000-0000-0000-000000000002";
    private static final String FRONT_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String FRONT_POSITION = "14000000-0000-0000-0000-000000000001";
    private static final String SUPERVISOR_POSITION = "14000000-0000-0000-0000-000000000003";
    private static final String FRONT_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String FRONT_FORM_VERSION = "19700000-0000-0000-0000-000000000001";
    private static final String FRONT_STANDARD_VERSION = "17000000-0000-0000-0000-000000000001";

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

    @BeforeEach
    void ensurePlatformAdministrator() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, CEO);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void publishedPackageGeneratesExpectationAndAcceptsSchemaValidatedRecord() throws Exception {
        LocalDate date = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now().withNano(0);

        JsonNode rule = response(identity(post("/api/v1/rules"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"WP-REAL-SUBMIT-RULE","name":"Work record submitted rule",
                         "eventType":"WorkRecordSubmitted"}
                        """), 201);
        String ruleId = rule.path("id").asText();
        JsonNode ruleVersion = response(identity(post("/api/v1/rules/{id}/versions", ruleId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "conditionAst":{"op":"EXISTS","fact":"workRecordId"},
                          "actions":[{
                            "key":"notify-executor","type":"CREATE_NOTIFICATION",
                            "recipientResolver":"CURRENT_ASSIGNMENT",
                            "title":"Work record submitted"
                          }],
                          "priority":10,
                          "cooldownMinutes":0,
                          "scopes":[{"scopeType":"TENANT"}]
                        }
                        """), 201);
        String ruleVersionId = ruleVersion.path("id").asText();
        response(identity(post("/api/v1/rules/{id}/versions/{versionId}/publish", ruleId, ruleVersionId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"effectiveFrom\":\"" + now.minusMinutes(1) + "\"}"), 200);

        JsonNode definition = response(identity(post("/api/v1/work-packages"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "code":"WP-FRONT-SHIFT-TEST",
                          "name":"前台班次测试工作包",
                          "positionId":"%s",
                          "ownerOrgUnitId":"%s"
                        }
                        """.formatted(FRONT_POSITION, HANGZHOU_HOTEL)), 201);
        String workPackageId = definition.path("id").asText();

        JsonNode version = response(identity(post("/api/v1/work-packages/{id}/versions", workPackageId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"前台班次测试工作包 V1\"}"), 201);
        String versionId = version.path("id").asText();

        mockMvc.perform(identity(put("/api/v1/work-packages/{id}/versions/{versionId}", workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"前台班次测试工作包 V1",
                                  "scopes":[{"scopeType":"ORG_TREE","orgUnitId":"%s"}],
                                  "items":[{
                                    "itemCode":"SHIFT_HANDOVER",
                                    "name":"班次交接记录",
                                    "itemType":"SCHEDULED_RECORD",
                                    "formVersionId":"%s",
                                    "periodType":"SHIFT",
                                    "timezoneMode":"TENANT",
                                    "dueLocalTime":"23:00:00",
                                    "waiverAllowed":true,
                                    "targetGranularity":"TARGET_ORG",
                                    "reviewMode":"MANUAL",
                                    "standards":[{"standardVersionId":"%s","usageType":"EXECUTION","weight":1}],
                                    "responsibilities":[
                                      {"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"},
                                      {"participantType":"ACCEPTOR","resolverType":"POSITION_IN_SAME_ORG",
                                       "positionId":"%s","scopeStrategy":"TARGET_ORG"}
                                    ]
                                  }]
                                }
                                """.formatted(HANGZHOU_HOTEL, FRONT_FORM_VERSION,
                                FRONT_STANDARD_VERSION, SUPERVISOR_POSITION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1));

        mockMvc.perform(identity(get("/api/v1/work-packages/{id}", workPackageId), PLATFORM_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.items[0].weekdays").isArray())
                .andExpect(jsonPath("$.latestVersion.items[0].weekdays").isEmpty());

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/versions/{versionId}/validate",
                        workPackageId, versionId), PLATFORM_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/versions/{versionId}/publish",
                        workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + now.minusMinutes(1) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/allocations", workPackageId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workPackageVersionId":"%s",
                                  "positionAssignmentId":"%s",
                                  "targetOrgUnitId":"%s",
                                  "validFrom":"%s"
                                }
                                """.formatted(versionId, FRONT_ASSIGNMENT, FRONT_DEPARTMENT, date)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        JsonNode duty = response(identity(post("/api/v1/work-duty-periods"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "positionAssignmentId":"%s",
                          "targetOrgUnitId":"%s",
                          "businessDate":"%s",
                          "periodType":"SHIFT",
                          "shiftCode":"TEST-%s",
                          "plannedStartAt":"%s",
                          "plannedEndAt":"%s"
                        }
                        """.formatted(FRONT_ASSIGNMENT, FRONT_DEPARTMENT, date, date,
                        now.minusHours(1), now.plusHours(8))), 201);
        String expectationId = duty.path("expectations").path("createdIds").get(0).asText();

        JsonNode assignedExpectations = response(
                identity(get("/api/v1/my/work-expectations"), FRONT_ACCOUNT), 200);
        assertThat(assignedExpectations.findValuesAsText("id")).contains(expectationId);

        JsonNode draft = response(identity(post("/api/v1/work-data/records"), FRONT_ACCOUNT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(date, expectationId, "{\"checkins\":10}", true)), 201);
        String recordId = draft.path("id").asText();

        mockMvc.perform(identity(post("/api/v1/work-data/records/{id}/actions/submit", recordId), FRONT_ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(identity(put("/api/v1/work-data/records/{id}", recordId), FRONT_ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"checkins\":10,\"complaints\":0},"
                                + "\"completionStatement\":\"班次工作已完成\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1));

        mockMvc.perform(identity(post("/api/v1/work-data/records/{id}/actions/submit", recordId), FRONT_ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(identity(get("/api/v1/work-data/records/{id}", recordId), FRONT_ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_expectation_id").value(expectationId))
                .andExpect(jsonPath("$.record_kind").value("SCHEDULED"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        String managementEventId = jdbc.queryForObject("""
                select id::text from management_event
                where event_type = 'WORKRECORDSUBMITTED'
                  and payload_snapshot ->> 'workRecordId' = ?
                """, String.class, recordId);
        assertThat(jdbc.queryForObject("""
                select processing_status from management_event where id = ?::uuid
                """, String.class, managementEventId)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("""
                select o.event_type from outbox_event o
                join management_event e on e.source_event_id = o.id
                where e.id = ?::uuid
                """, String.class, managementEventId)).isEqualTo("WORKRECORDSUBMITTED");
        assertThat(jdbc.queryForObject("""
                select count(*) from rule_action_execution a
                join rule_version v on v.id = a.rule_version_id
                where a.management_event_id = ?::uuid and v.rule_id = ?::uuid and a.status = 'SUCCEEDED'
                """, Integer.class, managementEventId, ruleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from notification n
                join rule_action_execution a on a.target_id = n.id
                join rule_version v on v.id = a.rule_version_id
                where a.management_event_id = ?::uuid and v.rule_id = ?::uuid
                """, Integer.class, managementEventId, ruleId)).isEqualTo(1);
    }

    @Test
    void configuredItemEnablementWeekdayAndHolidayRulesControlGeneration() throws Exception {
        LocalDate businessDate = LocalDate.now().plusYears(2);
        int otherWeekday = businessDate.getDayOfWeek().getValue() % 7 + 1;
        OffsetDateTime effectiveFrom = OffsetDateTime.now().minusMinutes(1).withNano(0);
        JsonNode definition = response(identity(post("/api/v1/work-packages"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "code":"WP-ALL-POSITION-CONFIG-TEST",
                          "name":"All position configuration test",
                          "positionId":"%s",
                          "ownerOrgUnitId":"%s"
                        }
                        """.formatted(FRONT_POSITION, HANGZHOU_HOTEL)), 201);
        String workPackageId = definition.path("id").asText();
        JsonNode version = response(identity(post("/api/v1/work-packages/{id}/versions", workPackageId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"All position configuration test V1\"}"), 201);
        String versionId = version.path("id").asText();

        mockMvc.perform(identity(put("/api/v1/work-packages/{id}/versions/{versionId}", workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"All position configuration test V1",
                                  "scopes":[{"scopeType":"ORG_TREE","orgUnitId":"%s"}],
                                  "items":[
                                    {
                                      "itemCode":"CONFIG_ACTIVE_TEST","name":"Active configured work",
                                       "itemType":"INSPECTION","formVersionId":"%s","periodType":"DAY",
                                       "timezoneMode":"TENANT","dueLocalTime":"18:00:00","reviewMode":"NONE",
                                       "weekdays":[%d],
                                       "executionPolicy":{"enabled":true},
                                      "reminderPolicy":{"moments":[{"code":"R0900","kind":"REMINDER","localTime":"09:00","recipient":"EXECUTORS"}]},
                                      "submissionPolicy":{"attachmentRequired":true,"maxAttachments":20,"allowedExtensions":["jpg","pdf"]},
                                      "reportPolicy":{"dailyReport":true,"factLabel":"Active configured work","includeEvidence":true},
                                      "standards":[],"responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                                    },
                                    {
                                      "itemCode":"CONFIG_DISABLED_TEST","name":"Disabled configured work",
                                      "itemType":"INSPECTION","formVersionId":"%s","periodType":"DAY",
                                      "timezoneMode":"TENANT","dueLocalTime":"19:00:00","reviewMode":"NONE",
                                       "executionPolicy":{"enabled":false},
                                       "standards":[],"responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                                    },
                                    {
                                      "itemCode":"CONFIG_WEEKDAY_TEST","name":"Wrong weekday work",
                                      "itemType":"INSPECTION","formVersionId":"%s","periodType":"DAY",
                                      "timezoneMode":"TENANT","dueLocalTime":"19:30:00","reviewMode":"NONE",
                                      "weekdays":[%d],"executionPolicy":{"enabled":true},
                                      "standards":[],"responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                                    },
                                    {
                                      "itemCode":"CONFIG_HOLIDAY_TEST","name":"Skipped holiday work",
                                      "itemType":"INSPECTION","formVersionId":"%s","periodType":"DAY",
                                      "timezoneMode":"TENANT","dueLocalTime":"20:00:00","reviewMode":"NONE",
                                      "weekdays":[],"holidayPolicy":"SKIP",
                                      "applicabilityPolicy":{"holidayDates":["%s"],"workdayOverrides":[]},
                                      "executionPolicy":{"enabled":true},
                                      "standards":[],"responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                                    }
                                  ]
                                }
                                """.formatted(HANGZHOU_HOTEL, FRONT_FORM_VERSION,
                                businessDate.getDayOfWeek().getValue(), FRONT_FORM_VERSION,
                                FRONT_FORM_VERSION, otherWeekday, FRONT_FORM_VERSION, businessDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(4));

        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/versions/{versionId}/publish",
                        workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + effectiveFrom + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(identity(post("/api/v1/work-packages/{id}/allocations", workPackageId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workPackageVersionId":"%s","positionAssignmentId":"%s",
                                  "targetOrgUnitId":"%s","validFrom":"%s"
                                }
                                """.formatted(versionId, FRONT_ASSIGNMENT, FRONT_DEPARTMENT, businessDate.minusDays(1))))
                .andExpect(status().isCreated());
        mockMvc.perform(identity(post("/api/v1/work-expectations/actions/generate"), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "positionAssignmentId":"%s","targetOrgUnitId":"%s",
                                  "businessDate":"%s","periodType":"DAY","dutyPeriodId":null
                                }
                                """.formatted(FRONT_ASSIGNMENT, FRONT_DEPARTMENT, businessDate)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                select count(*) from work_expectation expectation
                join work_package_item item on item.tenant_id = expectation.tenant_id
                                           and item.id = expectation.work_package_item_id
                where expectation.business_date = ? and item.item_code = 'CONFIG_ACTIVE_TEST'
                """, Integer.class, businessDate)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_expectation expectation
                join work_package_item item on item.tenant_id = expectation.tenant_id
                                           and item.id = expectation.work_package_item_id
                where expectation.business_date = ? and item.item_code = 'CONFIG_DISABLED_TEST'
                """, Integer.class, businessDate)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from work_expectation expectation
                join work_package_item item on item.tenant_id = expectation.tenant_id
                                           and item.id = expectation.work_package_item_id
                where expectation.business_date = ?
                  and item.item_code in ('CONFIG_WEEKDAY_TEST', 'CONFIG_HOLIDAY_TEST')
                """, Integer.class, businessDate)).isZero();
    }

    @Test
    void draftVersionCanReplaceExistingItemsAndTheirGuardedRelations() throws Exception {
        JsonNode definition = response(identity(post("/api/v1/work-packages"), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "code":"WP-DRAFT-REPLACE-TEST","name":"Draft replacement test",
                          "positionId":"%s","ownerOrgUnitId":"%s"
                        }
                        """.formatted(FRONT_POSITION, HANGZHOU_HOTEL)), 201);
        String workPackageId = definition.path("id").asText();
        JsonNode version = response(identity(post("/api/v1/work-packages/{id}/versions", workPackageId), PLATFORM_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Draft replacement test V1\"}"), 201);
        String versionId = version.path("id").asText();

        String firstPayload = """
                {
                  "title":"Draft replacement test V1",
                  "scopes":[{"scopeType":"ORG_TREE","orgUnitId":"%s"}],"items":[{
                    "itemCode":"FIRST_ITEM","name":"First item","itemType":"INSPECTION",
                    "formVersionId":"%s","periodType":"DAY","timezoneMode":"TENANT",
                    "dueLocalTime":"18:00:00","reviewMode":"MANUAL",
                    "standards":[{"standardVersionId":"%s","usageType":"EXECUTION","weight":1}],
                    "responsibilities":[
                      {"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"},
                      {"participantType":"ACCEPTOR","resolverType":"POSITION_IN_SAME_ORG",
                       "positionId":"%s","scopeStrategy":"TARGET_ORG"}
                    ]
                  }]
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_FORM_VERSION, FRONT_STANDARD_VERSION, SUPERVISOR_POSITION);
        mockMvc.perform(identity(put("/api/v1/work-packages/{id}/versions/{versionId}", workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1));
        mockMvc.perform(identity(get("/api/v1/work-packages/{id}", workPackageId), PLATFORM_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.items[0].submission_policy.maxAttachments").value(10))
                .andExpect(jsonPath("$.latestVersion.items[0].report_policy").isMap())
                .andExpect(jsonPath("$.latestVersion.items[0].report_policy.type").doesNotExist());

        String replacementPayload = """
                {
                  "title":"Draft replacement test V1 edited",
                  "scopes":[{"scopeType":"ORG_TREE","orgUnitId":"%s"}],"items":[
                    {
                      "itemCode":"FIRST_ITEM_EDITED","name":"First item edited","itemType":"INSPECTION",
                      "formVersionId":"%s","periodType":"DAY","timezoneMode":"TENANT",
                      "dueLocalTime":"17:00:00","reviewMode":"NONE","standards":[],
                      "responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                    },
                    {
                      "itemCode":"SECOND_ITEM","name":"Second item","itemType":"SCHEDULED_RECORD",
                      "formVersionId":"%s","periodType":"DAY","timezoneMode":"TENANT",
                      "dueLocalTime":"19:00:00","reviewMode":"NONE","standards":[],
                      "responsibilities":[{"participantType":"EXECUTOR","resolverType":"CURRENT_ASSIGNMENT"}]
                    }
                  ]
                }
                """.formatted(HANGZHOU_HOTEL, FRONT_FORM_VERSION, FRONT_FORM_VERSION);
        mockMvc.perform(identity(put("/api/v1/work-packages/{id}/versions/{versionId}", workPackageId, versionId), PLATFORM_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(replacementPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));

        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item
                where tenant_id = ?::uuid and work_package_version_id = ?::uuid
                """, Integer.class, TENANT, versionId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_item item
                where item.tenant_id = ?::uuid and item.work_package_version_id = ?::uuid
                  and item.item_code = 'FIRST_ITEM'
                """, Integer.class, TENANT, versionId)).isZero();
    }

    private String recordBody(LocalDate date, String expectationId, String payload, boolean draft) {
        return """
                {
                  "orgUnitId":"%s",
                  "employeeId":"%s",
                  "positionAssignmentId":"%s",
                  "formVersionId":"%s",
                  "businessDate":"%s",
                  "payload":%s,
                  "workExpectationId":"%s",
                  "targetOrgUnitId":"%s",
                  "saveAsDraft":%s
                }
                """.formatted(FRONT_DEPARTMENT, FRONT_EMPLOYEE, FRONT_ASSIGNMENT, FRONT_FORM_VERSION,
                date, payload, expectationId, FRONT_DEPARTMENT, draft);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String content = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content);
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
