package cn.sifangguan.hotelaios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Transactional;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.workpackage.WorkPackageService;
import cn.sifangguan.hotelaios.workpackage.WorkExpectationSlaService;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationalDailyAssignmentIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE = "12000000-0000-0000-0000-000000000005";

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


    @Autowired EffectiveIdentityService identities;
    @Autowired WorkPackageService workPackages;
    @Autowired WorkExpectationSlaService sla;

    @BeforeEach void publishedPackagesAreEffectiveTodayInIsolatedFixture() {
        // Publish test-only copies effective today: release versions are deliberately
        // immutable and effective tomorrow when Flyway creates a fresh database.
        var versions = jdbc.queryForList("""
                select v.id from work_package_version v
                join work_package_definition d on d.tenant_id=v.tenant_id and d.id=v.work_package_definition_id
                where d.code like 'WP-PILOT-%-DAILY' and v.lifecycle_status='PUBLISHED'
                """, UUID.class);
        for (UUID original : versions) {
            UUID copy = UUID.randomUUID();
            jdbc.update("""
                    insert into work_package_version
                    select (jsonb_populate_record(null::work_package_version, to_jsonb(v) ||
                      jsonb_build_object('id',?::uuid,'version_no',v.version_no+1000,'lifecycle_status','DRAFT',
                        'published_at',null,'effective_from',null,'effective_to',null))).*
                    from work_package_version v where v.id=?
                    """, copy, original);
            jdbc.update("""
                    insert into work_package_item
                    select (jsonb_populate_record(null::work_package_item, to_jsonb(i) ||
                      jsonb_build_object('id',gen_random_uuid(),'work_package_version_id',?::uuid))).*
                    from work_package_item i where i.work_package_version_id=?
                    """, copy, original);
            jdbc.update("""
                    update work_package_version set lifecycle_status='PUBLISHED',
                      effective_from=current_date-1, published_at=now() where id=?
                    """, copy);
        }
        jdbc.update("""
                update hotel_profile set breakfast_service_enabled = true,
                  operational_feature_flags = operational_feature_flags || '{"breakfastArea":true}'::jsonb
                where tenant_id = ? and org_unit_id = ?
                """, UUID.fromString(TENANT), UUID.fromString(HOTEL));
    }

    @ParameterizedTest
    @CsvSource({
        "GENERAL_MANAGER, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-GM-DAILY",
        "ASSISTANT_GENERAL_MANAGER, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-AGM-DAILY",
        "ASSISTANT_GENERAL_MANAGER, WITHOUT_FRONT_OFFICE_SUPERVISOR, WP-PILOT-AGM-DAILY",
        "FRONT_OFFICE_SUPERVISOR, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-FO-SUPERVISOR-DAILY",
        "FRONT_OFFICE_SUPERVISOR, WITHOUT_FRONT_OFFICE_SUPERVISOR, WP-PILOT-FO-SUPERVISOR-DAILY",
        "SERVICE_STEWARD, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-SERVICE-STEWARD-DAILY",
        "FRONT_DESK, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-FRONT-DAILY",
        "HOUSEKEEPING_SUPERVISOR, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-HK-SUPERVISOR-DAILY",
        "HOUSEKEEPING_ATTENDANT, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-HK-ATTENDANT-DAILY",
        "NIGHT_SERVICE_ATTENDANT, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-NIGHT-ATTENDANT-DAILY",
        "BREAKFAST_AREA_LEAD, WITH_FRONT_OFFICE_SUPERVISOR, WP-PILOT-BREAKFAST-LEAD-DAILY"
    })
    void everyOperationalRoleReceivesCurrentWorkWhenItsDayOrShiftStarts(String positionCode, String mode, String packageCode) throws Exception {
        jdbc.update("update hotel_profile set assistant_work_template_mode = ? where org_unit_id = ?",
                mode, UUID.fromString(HOTEL));
        UUID assignment = activate(positionCode, LocalDate.now());
        assertThat(jdbc.queryForObject("""
                select count(*) from work_package_allocation a
                join work_package_version v on v.tenant_id = a.tenant_id and v.id = a.work_package_version_id
                join work_package_definition d on d.tenant_id = v.tenant_id and d.id = v.work_package_definition_id
                where a.position_assignment_id = ? and a.status = 'ACTIVE'
                  and a.valid_from <= current_date and (a.valid_to is null or a.valid_to >= current_date)
                  and d.code = ?
                """, Integer.class, assignment, packageCode)).isEqualTo(1);
        if ("FRONT_DESK".equals(positionCode)) {
            // Front-desk work is shift-relative. An assignment must not fabricate a shift.
            assertThat(jdbc.queryForObject("select count(*) from work_expectation where position_assignment_id = ?",
                    Integer.class, assignment)).isZero();
            PlatformAdminTestFixture.ensure(jdbc, TENANT, CEO);
            postJson("/api/v1/work-duty-periods", PlatformAdminTestFixture.ACCOUNT_ID, """
                    {"positionAssignmentId":"%s","targetOrgUnitId":"%s","businessDate":"%s",
                     "periodType":"SHIFT","shiftCode":"TEST-DAY",
                     "plannedStartAt":"%sT08:00:00+08:00","plannedEndAt":"%sT16:00:00+08:00"}
                    """.formatted(assignment, HOTEL, LocalDate.now(), LocalDate.now(), LocalDate.now()));
        }
        UUID account = jdbc.queryForObject("""
                select e.account_id from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id where a.id = ?
                """, UUID.class, assignment);
        TenantContext.set(identities.resolve(UUID.fromString(TENANT), account, UUID.randomUUID(), assignment));
        try {
            assertThat(workPackages.expectations(null, null, null, LocalDate.now(), null, null, true, 0, 200))
                    .as("current-day owned or explicitly shared work for " + positionCode).isNotEmpty();
        } finally { TenantContext.clear(); }
        if ("HOUSEKEEPING_ATTENDANT".equals(positionCode)) {
            assertThat(jdbc.queryForObject("""
                    select (x.available_at at time zone 'Asia/Shanghai')::time=time '15:30'
                      and (x.due_at at time zone 'Asia/Shanghai')::time=time '18:00'
                    from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                    where x.position_assignment_id=? and x.business_date=current_date and i.item_code='HKA_DAILY_REPORT'
                    """,Boolean.class,assignment)).isTrue();
        }
        int count = jdbc.queryForObject("select count(*) from work_expectation where business_date = current_date", Integer.class);
        // Reopening / repeating automation must not create a second copy of today's work.
        sla.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        int afterFirstPass = jdbc.queryForObject("select count(*) from work_expectation where business_date = current_date", Integer.class);
        sla.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from work_expectation where business_date = current_date", Integer.class))
                .isEqualTo(afterFirstPass).isGreaterThanOrEqualTo(count);
    }

    @Test
    void addingAnotherAssistantKeepsTheirOwnAndSharedWorkWithoutExposingManagersOrPeers() throws Exception {
        UUID manager = activate("GENERAL_MANAGER", LocalDate.now());
        UUID firstAssistant = activate("ASSISTANT_GENERAL_MANAGER", LocalDate.now());
        UUID newAssistant = activate("ASSISTANT_GENERAL_MANAGER", LocalDate.now());
        UUID account = jdbc.queryForObject("""
                select e.account_id from employee_position_assignment a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where a.id=?
                """, UUID.class, newAssistant);
        var managerPrivateWork = jdbc.queryForList("""
                select x.id from work_expectation x
                join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                where x.position_assignment_id=? and not jsonb_exists(
                  coalesce(i.execution_policy->'allowedPositionCodes','[]'::jsonb), 'ASSISTANT_GENERAL_MANAGER')
                """, UUID.class, manager);
        assertThat(managerPrivateWork).isNotEmpty();
        TenantContext.set(identities.resolve(UUID.fromString(TENANT), account, UUID.randomUUID(), newAssistant));
        try {
            var personal = workPackages.expectations(null, null, null, LocalDate.now(), null, null, true, 0, 200);
            assertThat(personal).isNotEmpty();
            // Shared-per-hotel items may already belong to the first GM/AGM.
            // They remain executable by the newly assigned assistant without duplicating work.
            personal.forEach(row -> assertThat(workPackages.expectationDetail((UUID) row.get("id"))).isNotEmpty());
            assertThat(personal).extracting(row -> row.get("id")).doesNotContainAnyElementsOf(managerPrivateWork);
            assertThat(workPackages.teamExpectations(null, LocalDate.now(), null, null, 0, 200))
                    .extracting(row -> row.get("position_assignment_id")).doesNotContain(manager, firstAssistant);
        } finally { TenantContext.clear(); }
    }

    @Test
    void futureAssignmentDoesNotReceiveWorkBeforeItsEffectiveDate() throws Exception {
        UUID assignment = activate("HOUSEKEEPING_ATTENDANT", LocalDate.now().plusDays(3));
        assertThat(jdbc.queryForObject("select count(*) from work_expectation where position_assignment_id = ?",
                Integer.class, assignment)).isZero();
    }

    @Test
    void automationRepairsInitialTomorrowOnlyGapButDoesNotReopenRevokedWork() throws Exception {
        UUID position = jdbc.queryForObject("select id from position_definition where tenant_id = ? and code = 'HOUSEKEEPING_ATTENDANT'",
                UUID.class, UUID.fromString(TENANT));
        UUID account = UUID.randomUUID(), employee = UUID.randomUUID(), assignment = UUID.randomUUID();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values (?,?,?,?)",
                account, UUID.fromString(TENANT), "gap-" + account, "Allocation gap");
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values (?,?,?,?,?,current_date)",
                employee, UUID.fromString(TENANT), account, "gap-" + employee, "Allocation gap");
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) values (?,?,?,?,?,current_date)",
                assignment, UUID.fromString(TENANT), employee, UUID.fromString(HOTEL), position);
        // This is the original failure shape: a valid employee whose first allocation starts tomorrow.
        jdbc.update("""
                insert into work_package_allocation(tenant_id,work_package_version_id,position_assignment_id,
                  target_org_unit_id,allocation_source,valid_from)
                select v.tenant_id,v.id,?,?,'SYSTEM',current_date+1 from work_package_version v
                join work_package_definition d on d.tenant_id=v.tenant_id and d.id=v.work_package_definition_id
                where d.code='WP-PILOT-HK-ATTENDANT-DAILY' and v.lifecycle_status='PUBLISHED'
                """, assignment, UUID.fromString(HOTEL));
        sla.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from work_expectation where position_assignment_id=? and business_date=current_date",
                Integer.class, assignment)).isPositive();
        jdbc.update("update work_package_allocation set status='REVOKED' where position_assignment_id=?", assignment);
        sla.processTenantAsSystem(UUID.fromString(TENANT), 100, UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where position_assignment_id=? and status='ACTIVE'",
                Integer.class, assignment)).isZero();
    }

    @Test
    void existingSupervisorProvisioningGapStartsTomorrowWithoutHistoricalMisses() {
        jdbc.update("update hotel_profile set assistant_work_template_mode='WITHOUT_FRONT_OFFICE_SUPERVISOR' where org_unit_id=?",UUID.fromString(HOTEL));
        UUID account=UUID.randomUUID(), employee=UUID.randomUUID(), assignment=UUID.randomUUID();
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values (?,?,?,'前厅主管补齐验证')",account,UUID.fromString(TENANT),"fo-gap-"+account);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values (?,?,?,?,'前厅主管补齐验证',current_date-2)",employee,UUID.fromString(TENANT),account,"fo-gap-"+employee);
        jdbc.update("""
                insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from)
                select ?,?,?,?,id,current_date-2 from position_definition where tenant_id=? and code='FRONT_OFFICE_SUPERVISOR'
                """,assignment,UUID.fromString(TENANT),employee,UUID.fromString(FRONT_OFFICE),UUID.fromString(TENANT));
        // Recreate the missing provisioning shape; revocation of real employee work is not being tested here.
        sla.processTenantAsSystem(UUID.fromString(TENANT),100,UUID.randomUUID());
        sla.processTenantAsSystem(UUID.fromString(TENANT),100,UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where position_assignment_id=? and valid_from=current_date+1 and status='ACTIVE'",Integer.class,assignment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from work_expectation where position_assignment_id=? and business_date<=current_date",Integer.class,assignment)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from daily_report_template_assignment a
                join daily_report_template_version v on v.tenant_id=a.tenant_id and v.id=a.template_version_id
                join daily_report_template_definition d on d.tenant_id=v.tenant_id and d.id=v.template_id
                join daily_report_delivery_policy policy on policy.tenant_id=a.tenant_id and policy.template_assignment_id=a.id
                where a.org_unit_id=? and a.status='ACTIVE' and a.valid_from<=current_date+1
                  and (a.valid_to is null or a.valid_to>=current_date+1)
                  and d.code='DRT-PILOT-FO-SUPERVISOR-DAILY' and policy.enabled
                """,Integer.class,UUID.fromString(HOTEL))).isEqualTo(1);
    }

    private UUID activate(String positionCode, LocalDate validFrom) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID position = jdbc.queryForObject("select id from position_definition where tenant_id = ? and code = ?",
                UUID.class, UUID.fromString(TENANT), positionCode);
        UUID employee = UUID.fromString(json(postJson("/api/v1/org/employees", CEO, """
                {"employeeNo":"INIT-%s","name":"岗位当天生成测试","hiredOn":"%s",
                 "loginName":"init.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix, validFrom, suffix))).path("id").asText());
        return UUID.fromString(json(postJson("/api/v1/org/employees/" + employee + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,"assignmentType":"PERMANENT","validFrom":"%s"}
                """.formatted(FRONT_OFFICE, position, validFrom))).path("id").asText());
    }

    private MvcResult postJson(String path, String actorId, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
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
