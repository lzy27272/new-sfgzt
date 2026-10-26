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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrganizationMaintenanceIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String FRONT_OFFICE = "12000000-0000-0000-0000-000000000005";
    private static final String FRONT_OFFICE_SUPERVISOR_POSITION = "14000000-0000-0000-0000-000000000003";

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
    void unusedInactiveMasterDataCanBeDeletedAfterEditing() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID orgId = UUID.fromString(json(postJson("/api/v1/org/units", CEO, """
                {"parentId":"%s","code":"TMP-ORG-%s","name":"临时测试部门","unitType":"DEPARTMENT","sortOrder":99}
                """.formatted(HOTEL, suffix))).path("id").asText());
        UUID positionId = UUID.fromString(json(postJson("/api/v1/org/positions", CEO, """
                {"name":"临时测试岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF","wecomSelfSelectable":false}
                """)).path("id").asText());
        UUID employeeId = UUID.fromString(json(postJson("/api/v1/org/employees", CEO, """
                {"employeeType":"REGULAR","employeeNo":"TMP-EMP-%s","name":"临时测试员工","mobile":"13900000000","hiredOn":"2026-07-19"}
                """.formatted(suffix))).path("id").asText());

        putJson("/api/v1/org/units/" + orgId, CEO, """
                {"code":"TMP-ORG-%s","name":"临时测试部门已修改","sortOrder":98,"status":"INACTIVE"}
                """.formatted(suffix), 200);
        putJson("/api/v1/org/positions/" + positionId, CEO, """
                {"name":"临时测试岗位已修改","appliesToAllHotels":true,
                 "applicableHotelIds":[],"expectedVersion":0}
                """, 200);
        putJson("/api/v1/org/employees/" + employeeId, CEO, """
                {"employeeType":"REGULAR","employeeNo":"TMP-EMP-%s","name":"临时测试员工已修改","mobile":"13900000001","hiredOn":"2026-07-19","employmentStatus":"INACTIVE"}
                """.formatted(suffix), 200);

        deleteJson("/api/v1/org/units/" + orgId, CEO, 204);
        deleteJson("/api/v1/org/positions/" + positionId + "?expectedVersion=1", CEO, 204);
        deleteJson("/api/v1/org/employees/" + employeeId, CEO, 204);

        assertThat(count("org_unit", orgId)).isOne();
        assertThat(jdbc.queryForObject(
                "select status from org_unit where id = ?", String.class, orgId
        )).isEqualTo("DELETED");
        assertThat(count("position_definition", positionId)).isOne();
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from position_definition where id = ?", Boolean.class, positionId
        )).isTrue();
        assertThat(count("employee", employeeId)).isOne();
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from employee where id = ?", Boolean.class, employeeId
        )).isTrue();
    }

    @Test
    void deactivationPreservesHistoryAndDisablesAccessPaths() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID orgId = UUID.fromString(json(postJson("/api/v1/org/units", CEO, """
                {"parentId":"%s","code":"HIS-ORG-%s","name":"历史测试部门","unitType":"DEPARTMENT","sortOrder":99}
                """.formatted(HOTEL, suffix))).path("id").asText());
        UUID positionId = UUID.fromString(json(postJson("/api/v1/org/positions", CEO, """
                {"name":"历史测试岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF","wecomSelfSelectable":false}
                """)).path("id").asText());
        postJson("/api/v1/org/positions/" + positionId + "/profile/publish", CEO, """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """);
        JsonNode employee = json(postJson("/api/v1/org/employees", CEO, """
                {"employeeType":"REGULAR","employeeNo":"HIS-EMP-%s","name":"历史测试员工","mobile":"13900000002","hiredOn":"2026-07-19","loginName":"history.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix, suffix.toLowerCase())));
        UUID employeeId = UUID.fromString(employee.path("id").asText());
        UUID accountId = UUID.fromString(employee.path("accountId").asText());
        UUID assignmentId = UUID.fromString(json(postJson("/api/v1/org/employees/" + employeeId + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,"assignmentType":"PERMANENT","validFrom":"2026-07-19"}
                """.formatted(orgId, positionId))).path("id").asText());
        // SYSTEM role grants are produced only through published positions.
        // Use the published ORG_UNIT-scoped supervisor position so org
        // deactivation still verifies closure of a SYSTEM role grant.
        JsonNode standardAssignment = json(postJson("/api/v1/org/employees/" + employeeId + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":false,"assignmentType":"PERMANENT","validFrom":"2026-07-19"}
                """.formatted(orgId, FRONT_OFFICE_SUPERVISOR_POSITION)));
        UUID standardRoleAssignmentId = UUID.fromString(standardAssignment.path("roleAssignmentId").asText());

        putJson("/api/v1/org/units/" + orgId, CEO, """
                {"code":"HIS-ORG-%s","name":"历史测试部门","sortOrder":99,"status":"INACTIVE"}
                """.formatted(suffix), 200);

        assertThat(jdbc.queryForObject("select status from employee_position_assignment where id = ?", String.class, assignmentId))
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("select valid_to is not null from role_assignment where id = ?", Boolean.class, standardRoleAssignmentId))
                .isTrue();
        deleteJson("/api/v1/org/units/" + orgId, CEO, 204);
        assertThat(jdbc.queryForObject(
                "select status from org_unit where id = ?", String.class, orgId
        )).isEqualTo("DELETED");

        deleteJson("/api/v1/org/positions/" + positionId + "?expectedVersion=1", CEO, 204);

        putJson("/api/v1/org/employees/" + employeeId, CEO, """
                {"employeeType":"REGULAR","employeeNo":"HIS-EMP-%s","name":"历史测试员工","mobile":"13900000002","hiredOn":"2026-07-19","employmentStatus":"INACTIVE","loginName":"history.%s"}
                """.formatted(suffix, suffix.toLowerCase()), 200);
        assertThat(jdbc.queryForObject("select status from user_account where id = ?", String.class, accountId))
                .isEqualTo("INACTIVE");
        deleteJson("/api/v1/org/employees/" + employeeId, CEO, 204);
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from employee where id = ?", Boolean.class, employeeId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from employee_position_assignment where employee_id = ?", Integer.class, employeeId
        )).isEqualTo(2);

        postJson("/api/v1/org/employees/" + employeeId + "/restore", CEO, "{}");
        assertThat(jdbc.queryForObject(
                "select deleted_at is null from employee where id = ?", Boolean.class, employeeId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select employment_status from employee where id = ?", String.class, employeeId
        )).isEqualTo("INACTIVE");

        deleteJson("/api/v1/org/employees/" + employeeId, CEO, 204);
        deleteJson("/api/v1/org/employees/" + employeeId + "/permanent", CEO, 204);
        assertThat(jdbc.queryForObject(
                "select permanently_deleted_at is not null from employee where id = ?", Boolean.class, employeeId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select name from employee where id = ?", String.class, employeeId
        )).isEqualTo("已永久删除员工");
        assertThat(jdbc.queryForObject(
                "select login_name like 'purged-%' from user_account where id = ?", Boolean.class, accountId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select password_hash is null from user_account where id = ?", Boolean.class, accountId
        )).isTrue();
    }

    @Test
    void administratorCanMoveAllInactiveEmployeesToRecycleBin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID employeeId = UUID.fromString(json(postJson("/api/v1/org/employees", CEO, """
                {"employeeType":"REGULAR","employeeNo":"BULK-EMP-%s","name":"批量删除测试员工","hiredOn":"2026-07-19"}
                """.formatted(suffix))).path("id").asText());
        putJson("/api/v1/org/employees/" + employeeId, CEO, """
                {"employeeType":"REGULAR","employeeNo":"BULK-EMP-%s","name":"批量删除测试员工","hiredOn":"2026-07-19","employmentStatus":"INACTIVE"}
                """.formatted(suffix), 200);

        JsonNode response = json(postJson("/api/v1/org/employees/actions/delete-inactive", CEO, "{}"));

        assertThat(response.path("deletedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from employee where id = ?", Boolean.class, employeeId
        )).isTrue();
    }

    @Test
    void currentAssignmentCanBeUpdatedReplacedAndEndedWithoutDeletingHistory() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID firstPositionId = UUID.fromString(json(postJson("/api/v1/org/positions", CEO, """
                {"name":"任职变更原岗位%s","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF","wecomSelfSelectable":false}
                """.formatted(suffix))).path("id").asText());
        UUID secondPositionId = UUID.fromString(json(postJson("/api/v1/org/positions", CEO, """
                {"name":"任职变更新岗位%s","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF","wecomSelfSelectable":false}
                """.formatted(suffix))).path("id").asText());
        postJson("/api/v1/org/positions/" + firstPositionId + "/profile/publish", CEO,
                "{\"expectedProfileVersion\":0,\"expectedPositionVersion\":0}");
        postJson("/api/v1/org/positions/" + secondPositionId + "/profile/publish", CEO,
                "{\"expectedProfileVersion\":0,\"expectedPositionVersion\":0}");
        JsonNode employee = json(postJson("/api/v1/org/employees", CEO, """
                {"employeeType":"REGULAR","employeeNo":"ASSIGN-EDIT-%s","name":"任职编辑测试员工","hiredOn":"%s",
                 "loginName":"assignment.edit.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix, LocalDate.now(), suffix.toLowerCase())));
        UUID employeeId = UUID.fromString(employee.path("id").asText());
        UUID primaryAssignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + employeeId + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,
                 "assignmentType":"PERMANENT","validFrom":"%s"}
                """.formatted(HOTEL, firstPositionId, LocalDate.now()))).path("id").asText());
        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + employeeId + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"%s"}
                """.formatted(HOTEL, secondPositionId, LocalDate.now()))).path("id").asText());

        JsonNode updated = json(putJson("/api/v1/org/assignments/" + assignmentId, CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,
                 "assignmentType":"TEMPORARY","validFrom":"%s","validTo":"%s",
                 "responsibleHotelIds":[]}
                """.formatted(HOTEL, secondPositionId, LocalDate.now(), LocalDate.now().plusDays(30)), 200));
        assertThat(updated.path("changeMode").asText()).isEqualTo("UPDATED");
        assertThat(jdbc.queryForObject(
                "select assignment_type from employee_position_assignment where id = ?",
                String.class, assignmentId)).isEqualTo("TEMPORARY");

        JsonNode replaced = json(putJson("/api/v1/org/assignments/" + assignmentId, CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"%s","validTo":null,
                 "responsibleHotelIds":[]}
                """.formatted(FRONT_OFFICE, secondPositionId, LocalDate.now()), 200));
        UUID replacementId = UUID.fromString(replaced.path("replacementAssignmentId").asText());
        assertThat(replaced.path("changeMode").asText()).isEqualTo("REPLACED");
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?",
                String.class, assignmentId)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?",
                String.class, replacementId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "select count(*) from employee_position_assignment where employee_id = ?",
                Integer.class, employeeId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from role_assignment
                where source_assignment_id = ? and (valid_to is null or valid_to > now())
                """, Integer.class, assignmentId)).isZero();

        deleteJson("/api/v1/org/assignments/" + primaryAssignmentId, CEO, 204);
        assertThat(jdbc.queryForObject(
                "select is_primary from employee_position_assignment where id = ?",
                Boolean.class, replacementId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?",
                String.class, primaryAssignmentId)).isEqualTo("INACTIVE");

        deleteJson("/api/v1/org/assignments/" + replacementId, CEO, 204);
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?",
                String.class, replacementId)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from role_assignment
                where source_assignment_id = ? and (valid_to is null or valid_to > now())
                """, Integer.class, replacementId)).isZero();
    }

    @Test
    void staffWithoutOrganizationManagementPermissionCannotModifyMasterData() throws Exception {
        putJson("/api/v1/org/units/" + HOTEL, FRONT_DESK, """
                {"code":"HZ-CENTER","name":"不应修改","sortOrder":1,"status":"ACTIVE","propertyCode":"HZ001"}
                """, 403);
    }

    @Test
    void changingAssistantTemplateModeReconcilesNextDayWorkAndReportAssignments() throws Exception {
        Map<String, Object> hotel = jdbc.queryForMap("""
                select org.code, org.name, org.sort_order, profile.property_code, profile.city,
                       profile.room_count, profile.opening_date, profile.breakfast_service_enabled,
                       profile.guest_room_floor_count, profile.assistant_work_template_mode,
                       coalesce((profile.operational_feature_flags ->> 'kitchen')::boolean,false) kitchen,
                       coalesce((profile.operational_feature_flags ->> 'afternoonTea')::boolean,false) afternoon_tea,
                       coalesce((profile.operational_feature_flags ->> 'bar')::boolean,false) bar,
                       coalesce((profile.operational_feature_flags ->> 'projection')::boolean,false) projection,
                       coalesce((profile.operational_feature_flags ->> 'gym')::boolean,false) gym,
                       coalesce((profile.operational_feature_flags ->> 'terrace')::boolean,false) terrace,
                       coalesce((profile.operational_feature_flags ->> 'trendyPlayArea')::boolean,false) trendy_play_area
                from org_unit org
                join hotel_profile profile on profile.tenant_id=org.tenant_id and profile.org_unit_id=org.id
                where org.id=?
                """, UUID.fromString(HOTEL));
        String currentMode = String.valueOf(hotel.get("assistant_work_template_mode"));
        String targetMode = "WITH_FRONT_OFFICE_SUPERVISOR".equals(currentMode)
                ? "WITHOUT_FRONT_OFFICE_SUPERVISOR" : "WITH_FRONT_OFFICE_SUPERVISOR";
        var body = objectMapper.createObjectNode();
        body.put("code", String.valueOf(hotel.get("code")));
        body.put("name", String.valueOf(hotel.get("name")));
        body.put("sortOrder", ((Number) hotel.get("sort_order")).intValue());
        body.put("status", "ACTIVE");
        body.put("propertyCode", String.valueOf(hotel.get("property_code")));
        if (hotel.get("city") != null) body.put("city", String.valueOf(hotel.get("city")));
        if (hotel.get("room_count") != null) body.put("roomCount", ((Number) hotel.get("room_count")).intValue());
        if (hotel.get("opening_date") != null) body.put("openingDate", hotel.get("opening_date").toString());
        body.put("breakfastServiceEnabled", Boolean.TRUE.equals(hotel.get("breakfast_service_enabled")));
        body.put("guestRoomFloorCount", ((Number) hotel.get("guest_room_floor_count")).intValue());
        body.put("assistantWorkTemplateMode", targetMode);
        body.put("kitchenEnabled", Boolean.TRUE.equals(hotel.get("kitchen")));
        body.put("afternoonTeaEnabled", Boolean.TRUE.equals(hotel.get("afternoon_tea")));
        body.put("barEnabled", Boolean.TRUE.equals(hotel.get("bar")));
        body.put("projectionEnabled", Boolean.TRUE.equals(hotel.get("projection")));
        body.put("gymEnabled", Boolean.TRUE.equals(hotel.get("gym")));
        body.put("terraceEnabled", Boolean.TRUE.equals(hotel.get("terrace")));
        body.put("trendyPlayAreaEnabled", Boolean.TRUE.equals(hotel.get("trendy_play_area")));

        putJson("/api/v1/org/units/" + HOTEL, CEO, body.toString(), 200);

        assertThat(jdbc.queryForObject(
                "select assistant_work_template_mode from hotel_profile where org_unit_id=?",
                String.class, UUID.fromString(HOTEL))).isEqualTo(targetMode);
        assertThat(jdbc.queryForObject("""
                select count(distinct allocation.id)
                from work_package_allocation allocation
                join work_package_version version on version.tenant_id=allocation.tenant_id
                    and version.id=allocation.work_package_version_id
                join work_package_item item on item.tenant_id=version.tenant_id
                    and item.work_package_version_id=version.id
                join employee_position_assignment employee_assignment
                    on employee_assignment.tenant_id=allocation.tenant_id
                    and employee_assignment.id=allocation.position_assignment_id
                join position_definition position on position.tenant_id=employee_assignment.tenant_id
                    and position.id=employee_assignment.position_id
                where allocation.status='ACTIVE' and allocation.valid_from=current_date+1
                  and allocation.target_org_unit_id=?
                  and position.code='ASSISTANT_GENERAL_MANAGER'
                  and item.applicability_policy ->> 'assistantWorkTemplateMode'=?
                  and exists (select 1 from org_unit_closure scope
                    where scope.tenant_id=allocation.tenant_id and scope.ancestor_id=?
                      and scope.descendant_id=employee_assignment.org_unit_id)
                """, Integer.class, UUID.fromString(HOTEL), targetMode,
                UUID.fromString(HOTEL))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from daily_report_template_assignment template_assignment
                join daily_report_template_version version on version.tenant_id=template_assignment.tenant_id
                    and version.id=template_assignment.template_version_id
                join daily_report_template_definition definition on definition.tenant_id=version.tenant_id
                    and definition.id=version.template_id
                where template_assignment.status='ACTIVE'
                  and template_assignment.scope_type='ORG_TREE'
                  and template_assignment.org_unit_id=? and template_assignment.valid_from=current_date+1
                  and definition.code like 'DRT-PILOT-AGM-%'
                  and definition.code like case when ?='WITH_FRONT_OFFICE_SUPERVISOR'
                      then '%WITH-SUPERVISOR' else '%WITHOUT-SUPERVISOR' end
                """, Integer.class, UUID.fromString(HOTEL), targetMode)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from daily_report_template_assignment template_assignment
                join daily_report_template_version version on version.tenant_id=template_assignment.tenant_id
                    and version.id=template_assignment.template_version_id
                join daily_report_template_definition definition on definition.tenant_id=version.tenant_id
                    and definition.id=version.template_id
                where template_assignment.status='ACTIVE'
                  and template_assignment.scope_type='POSITION'
                  and template_assignment.valid_from=current_date+1
                  and definition.code='DRT-PILOT-GM-DAILY'
                """, Integer.class)).isEqualTo(1);
        int activeFrontOfficeReports = jdbc.queryForObject("""
                select count(*)
                from daily_report_template_assignment template_assignment
                join daily_report_template_version version on version.tenant_id=template_assignment.tenant_id
                    and version.id=template_assignment.template_version_id
                join daily_report_template_definition definition on definition.tenant_id=version.tenant_id
                    and definition.id=version.template_id
                where template_assignment.status='ACTIVE' and template_assignment.org_unit_id=?
                  and template_assignment.valid_from=current_date+1
                  and definition.code='DRT-PILOT-FO-SUPERVISOR-DAILY'
                """, Integer.class, UUID.fromString(HOTEL));
        // An explicitly assigned supervisor keeps their own report even when the
        // assistant template is configured to cover front-office duties too.
        assertThat(activeFrontOfficeReports).isEqualTo(1);
    }

    @Test
    void operationalAssignmentPublishesProfileAndMaintainsOneHotelAllocationAcrossTransfer() throws Exception {
        assertThat(jdbc.queryForObject("""
                select count(distinct position.code)
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id=position.tenant_id and profile.position_id=position.id
                 and profile.scope_type='GROUP'
                join position_function_profile_version published
                  on published.tenant_id=profile.tenant_id and published.profile_id=profile.id
                 and published.lifecycle_status='PUBLISHED'
                where position.tenant_id=?
                  and position.code in ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
                  and exists (select 1 from position_function_profile_permission grant_item
                    where grant_item.tenant_id=published.tenant_id
                      and grant_item.profile_version_id=published.id)
                """, Integer.class, UUID.fromString(TENANT))).isEqualTo(3);

        UUID serviceStewardPosition = jdbc.queryForObject("""
                select id from position_definition where tenant_id=? and code='SERVICE_STEWARD'
                """, UUID.class, UUID.fromString(TENANT));
        UUID nightAttendantPosition = jdbc.queryForObject("""
                select id from position_definition where tenant_id=? and code='NIGHT_SERVICE_ATTENDANT'
                """, UUID.class, UUID.fromString(TENANT));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        UUID employeeId = UUID.fromString(json(postJson("/api/v1/org/employees", CEO, """
                {"employeeType":"REGULAR","employeeNo":"OP-AUTO-%s","name":"日清自动分配测试员工","hiredOn":"%s",
                 "loginName":"op.auto.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix.toUpperCase(), LocalDate.now(), suffix))).path("id").asText());

        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + employeeId + "/assignments", CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,
                 "assignmentType":"PERMANENT","validFrom":"%s"}
                """.formatted(FRONT_OFFICE, serviceStewardPosition, LocalDate.now())))
                .path("id").asText());
        assertOneActiveOperationalAllocation(assignmentId, "WP-PILOT-SERVICE-STEWARD-DAILY");

        putJson("/api/v1/org/assignments/" + assignmentId, CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,
                 "assignmentType":"PERMANENT","validFrom":"%s","validTo":null,
                 "responsibleHotelIds":[]}
                """.formatted(FRONT_OFFICE, serviceStewardPosition, LocalDate.now()), 200);
        assertOneActiveOperationalAllocation(assignmentId, "WP-PILOT-SERVICE-STEWARD-DAILY");

        JsonNode replacement = json(putJson("/api/v1/org/assignments/" + assignmentId, CEO, """
                {"orgUnitId":"%s","positionId":"%s","primary":true,
                 "assignmentType":"PERMANENT","validFrom":"%s","validTo":null,
                 "responsibleHotelIds":[]}
                """.formatted(FRONT_OFFICE, nightAttendantPosition, LocalDate.now()), 200));
        UUID replacementId = UUID.fromString(replacement.path("replacementAssignmentId").asText());
        assertThat(activeOperationalAllocationCount(assignmentId)).isZero();
        assertOneActiveOperationalAllocation(replacementId, "WP-PILOT-NIGHT-ATTENDANT-DAILY");

        deleteJson("/api/v1/org/assignments/" + replacementId, CEO, 204);
        assertThat(activeOperationalAllocationCount(replacementId)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void actualEmploymentExitBlocksOutstandingLoanWithoutDisclosingMoneyButAssignmentChangeIsAllowed() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID employee=UUID.fromString(json(postJson("/api/v1/org/employees",CEO,"""
                {"employeeNo":"LOAN-EXIT-%s","name":"借款离职守卫员工","employeeType":"REGULAR",
                 "loginName":"loan.exit.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix,suffix))).path("id").asText());
        UUID account=jdbc.queryForObject("select account_id from employee where id=?",UUID.class,employee);
        UUID assignment=UUID.fromString(json(postJson("/api/v1/org/employees/"+employee+"/assignments",CEO,"""
                {"orgUnitId":"%s","positionId":"%s","primary":true,"assignmentType":"PERMANENT","validFrom":"%s"}
                """.formatted(FRONT_OFFICE,FRONT_OFFICE_SUPERVISOR_POSITION,LocalDate.now()))).path("id").asText());
        UUID loan=actualLoanForEmployee(account);
        deleteJson("/api/v1/org/assignments/"+assignment,CEO,204);
        assertThat(jdbc.queryForObject("select employment_status from employee where id=?",String.class,employee)).isEqualTo("ACTIVE");
        String inactive="""
                {"employeeNo":"LOAN-EXIT-%s","name":"借款离职守卫员工","employeeType":"REGULAR",
                 "employmentStatus":"INACTIVE","loginName":"loan.exit.%s"}
                """.formatted(suffix,suffix);
        var blocked=putJson("/api/v1/org/employees/"+employee,CEO,inactive,409);
        // Organization currently uses Servlet sendError for ResponseStatusException, not a JSON body.
        assertThat(blocked.getResponse().getErrorMessage()).isEqualTo("存在未清借款，请联系财务核对");
        assertThat(blocked.getResolvedException()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(((org.springframework.web.server.ResponseStatusException)blocked.getResolvedException()).getReason()).isEqualTo("存在未清借款，请联系财务核对");
        assertThat(blocked.getResponse().getContentAsString()).doesNotContain("50.00",loan.toString(),account.toString());
        assertThat(jdbc.queryForObject("select employment_status from employee where id=?",String.class,employee)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from user_account where id=?",String.class,account)).isEqualTo("ACTIVE");
        actualLoanRepayment(loan);
        putJson("/api/v1/org/employees/"+employee,CEO,inactive,200);
        deleteJson("/api/v1/org/employees/"+employee,CEO,204);
        assertThat(jdbc.queryForObject("select count(*) from fo_action where document_id=?",Integer.class,loan)).isEqualTo(2);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void historicalInactiveAndRecycleBinEmployeesCannotBypassLoanCheckOnDeletion() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID employee=UUID.fromString(json(postJson("/api/v1/org/employees",CEO,"""
                {"employeeNo":"OLD-LOAN-%s","name":"历史借款员工","employeeType":"REGULAR",
                 "loginName":"old.loan.%s","temporaryPassword":"TempPass!2026"}
                """.formatted(suffix,suffix))).path("id").asText());
        UUID account=jdbc.queryForObject("select account_id from employee where id=?",UUID.class,employee),loan=actualLoanForEmployee(account);
        // Simulate historical inactive data, not an HR settlement or bypass of the new endpoint.
        jdbc.update("update employee set employment_status='INACTIVE' where id=?",employee);
        deleteJson("/api/v1/org/employees/"+employee,CEO,409);
        jdbc.update("update employee set deleted_at=now(),deleted_by=? where id=?",UUID.fromString(CEO),employee);
        deleteJson("/api/v1/org/employees/"+employee+"/permanent",CEO,409);
        assertThat(jdbc.queryForObject("select permanently_deleted_at is null from employee where id=?",Boolean.class,employee)).isTrue();
        actualLoanRepayment(loan);
        deleteJson("/api/v1/org/employees/"+employee+"/permanent",CEO,204);
    }

    private UUID actualLoanForEmployee(UUID account) {
        UUID company=UUID.randomUUID(),loan=UUID.randomUUID();
        jdbc.update("insert into finance_company(id,tenant_id,name,tax_number) values(?,?,'借款实际主体',?)",company,UUID.fromString(TENANT),company.toString().replace("-",""));
        jdbc.update("insert into fo_document(id,tenant_id,hotel_id,applicant_id,company_id,type,title,business_date,amount,details,lines,status) values(?,?,?,?,?,'LOAN','真实已发放员工借款',current_date,50,cast(? as jsonb),'[]','APPROVED')",loan,UUID.fromString(TENANT),UUID.fromString(HOTEL),account,company,"{\"borrowerAccountId\":\""+account+"\"}");
        jdbc.update("insert into fo_action(tenant_id,document_id,actor_id,action,amount,occurred_on,reference,request_id) values(?,?,?,'PAYMENT',50,current_date,'既有财务真实发放原记录',?)",UUID.fromString(TENANT),loan,UUID.fromString(CEO),UUID.randomUUID());
        return loan;
    }

    private void actualLoanRepayment(UUID loan) {
        // An existing financial repayment fact is distinct from the tested HR action.
        jdbc.update("insert into fo_action(tenant_id,document_id,actor_id,action,amount,occurred_on,reference,request_id) values(?,?,?,'REPAYMENT',50,current_date,'财务另行确认的真实还款',?)",UUID.fromString(TENANT),loan,UUID.fromString(CEO),UUID.randomUUID());
    }

    private void assertOneActiveOperationalAllocation(UUID assignmentId, String definitionCode) {
        assertThat(jdbc.queryForObject("""
                select count(*)
                from work_package_allocation allocation
                join work_package_version version
                  on version.tenant_id=allocation.tenant_id
                 and version.id=allocation.work_package_version_id
                join work_package_definition definition
                  on definition.tenant_id=version.tenant_id
                 and definition.id=version.work_package_definition_id
                where allocation.position_assignment_id=?
                  and allocation.target_org_unit_id=?
                  and allocation.status='ACTIVE'
                  and allocation.valid_from=current_date+1
                  and definition.code=?
                """, Integer.class, assignmentId, UUID.fromString(HOTEL), definitionCode)).isEqualTo(1);
    }

    private int activeOperationalAllocationCount(UUID assignmentId) {
        return jdbc.queryForObject("""
                select count(*) from work_package_allocation
                where position_assignment_id=? and status='ACTIVE'
                  and (valid_to is null or valid_to>=current_date)
                """, Integer.class, assignmentId);
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

    private MvcResult putJson(String path, String actorId, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(put(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void deleteJson(String path, String actorId, int expectedStatus) throws Exception {
        mockMvc.perform(delete(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", actorId))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int count(String table, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
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
