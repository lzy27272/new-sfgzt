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
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PositionManagementIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String FRONT_ACCOUNT = "19000000-0000-0000-0000-000000000003";
    private static final String FRONT_SUPERVISOR_ACCOUNT = "19000000-0000-0000-0000-000000000005";
    private static final String FRONT_SUPERVISOR_ASSIGNMENT = "19200000-0000-0000-0000-000000000004";
    private static final String FRONT_SECONDARY_ASSIGNMENT = "19200000-0000-0000-0000-000000000005";
    private static final String FRONT_EMPLOYEE = "19100000-0000-0000-0000-000000000002";
    private static final String FRONT_DEPARTMENT = "12000000-0000-0000-0000-000000000005";
    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String SHANGHAI_HOTEL = "12000000-0000-0000-0000-000000000004";

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
    void createAndPublishUsesExplicitRoleMappingAndNeverExposesLegacyClassification() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"夜审测试岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read","notification.read"],
                 "authorizationScopeType":"SELF","wecomSelfSelectable":true}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());
        assertThat(created.has("code")).isFalse();
        assertThat(created.has("jobFamily")).isFalse();
        assertThat(created.has("levelCode")).isFalse();
        JsonNode publishedVersion = created.path("profile").path("publishedVersion");
        assertThat(publishedVersion.isMissingNode() || publishedVersion.isNull()).isTrue();
        assertThat(created.path("profile").path("draftVersion").asInt()).isEqualTo(1);
        assertThat(created.path("profile").path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("profile").path("draftDirty").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("""
                select role.code = position.code
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id and profile.position_id = position.id
                 and profile.scope_type = 'GROUP'
                join app_role role
                  on role.tenant_id = profile.tenant_id and role.id = profile.default_role_id
                where position.id = ?
                """, Boolean.class, positionId)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*)
                from position_function_profile profile
                join role_permission grant_item
                  on grant_item.tenant_id = profile.tenant_id
                 and grant_item.role_id = profile.default_role_id
                where profile.position_id = ? and profile.scope_type = 'GROUP'
                """, Integer.class, positionId)).isZero();

        JsonNode draft = json(putJson("/api/v1/org/positions/" + positionId + "/profile/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["org.read","task.read"],
                 "authorizationScopeType":"ORG_UNIT","wecomSelfSelectable":false}
                """, 200));
        assertThat(draft.path("draftRowVersion").asLong()).isEqualTo(1);
        JsonNode published = json(postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":1,"expectedPositionVersion":0}
                """, 200));
        assertThat(published.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(published.path("draftDirty").asBoolean()).isFalse();
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("""
                select permission.code
                from position_function_profile profile
                join role_permission grant_item
                  on grant_item.tenant_id = profile.tenant_id
                 and grant_item.role_id = profile.default_role_id
                join permission on permission.id = grant_item.permission_id
                where profile.position_id = ? and profile.scope_type = 'GROUP'
                order by permission.code
                """, String.class, positionId)).containsExactly("org.read", "task.read");

        putJson("/api/v1/org/positions/" + positionId, """
                {"name":"并发覆盖不应成功","appliesToAllHotels":true,
                 "applicableHotelIds":[],"expectedVersion":0}
                """, 409);
    }

    @Test
    void savingPublishedOnlyProfileRecreatesEditableDraftInsteadOfReturning404() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"仅发布版本修复测试岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF",
                 "wecomSelfSelectable":false}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());
        JsonNode published = json(postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200));
        UUID profileId = UUID.fromString(published.path("profileId").asText());

        jdbc.update("""
                delete from position_function_profile_version
                where profile_id = ? and lifecycle_status = 'DRAFT'
                """, profileId);

        JsonNode saved = json(putJson("/api/v1/org/positions/" + positionId + "/profile/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["org.read","task.read"],
                 "authorizationScopeType":"ORG_UNIT","wecomSelfSelectable":false}
                """, 200));

        assertThat(saved.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(saved.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(saved.path("draftRowVersion").asLong()).isEqualTo(1);
        assertThat(saved.path("status").asText()).isEqualTo("PUBLISHED_WITH_DRAFT");
        assertThat(saved.path("permissionCodes")).hasSize(2);
        assertThat(saved.path("permissionCodes").toString()).contains("org.read", "task.read");
    }

    @Test
    void protectedPermissionAndHotelElevationFailClosed() throws Exception {
        JsonNode options = json(getJson("/api/v1/org/positions/function-options", 200).andReturn());
        assertThat(options.findValuesAsText("permissionCode"))
                .contains("org.read")
                .doesNotContain(
                        "iam.manage", "position-profile.manage", "wecom-binding.approve",
                        "investment.confirm"
                );
        assertThat(option(options, "executive-task.assign").path("delegable").asBoolean()).isFalse();
        assertThat(option(options, "executive-task.assign").path("restrictionReason").asText())
                .isEqualTo("仅集团董事长岗位可配置");
        assertThat(option(options, "dashboard.ceo").path("delegable").asBoolean()).isFalse();

        UUID vicePresidentPositionId = jdbc.queryForObject("""
                select id from position_definition
                where tenant_id = ?::uuid and code = 'GROUP_VICE_PRESIDENT'
                """, UUID.class, TENANT);
        JsonNode vicePresidentOptions = json(getJson(
                "/api/v1/org/positions/function-options?positionId=" + vicePresidentPositionId,
                200
        ).andReturn());
        assertThat(option(vicePresidentOptions, "executive-task.read").path("delegable").asBoolean())
                .isFalse();
        assertThat(option(vicePresidentOptions, "dashboard.ceo").path("delegable").asBoolean())
                .isFalse();

        UUID chairmanPositionId = jdbc.queryForObject("""
                select id from position_definition
                where tenant_id = ?::uuid and code = 'GROUP_CHAIRMAN'
                """, UUID.class, TENANT);
        JsonNode chairmanOptions = json(getJson(
                "/api/v1/org/positions/function-options?positionId=" + chairmanPositionId,
                200
        ).andReturn());
        assertThat(option(chairmanOptions, "executive-task.read").path("delegable").asBoolean())
                .isTrue();
        assertThat(option(chairmanOptions, "executive-task.assign").path("delegable").asBoolean())
                .isTrue();

        postJson("/api/v1/org/positions", """
                {"name":"越权岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["iam.manage"],"authorizationScopeType":"SELF",
                 "wecomSelfSelectable":true}
                """, 400);

        postJson("/api/v1/org/positions", """
                {"name":"越权集团任务岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["executive-task.assign"],"authorizationScopeType":"SELF",
                 "wecomSelfSelectable":false}
                """, 400);

        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"门店减权岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"ORG_UNIT",
                 "wecomSelfSelectable":false}
                """, 201));
        String id = created.path("id").asText();
        putJson("/api/v1/org/positions/" + id + "/profile/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["executive-task.read"],
                 "authorizationScopeType":"ORG_UNIT","wecomSelfSelectable":false}
                """, 400);
        JsonNode restrictedImpact = json(postJson(
                "/api/v1/org/positions/" + id + "/impact-preview", """
                {"operation":"PUBLISH_PROFILE","permissionCodes":["executive-task.read"]}
                """, 200));
        assertThat(restrictedImpact.path("blockedPermissionCodes").toString())
                .contains("executive-task.read");
        postJson("/api/v1/org/positions/" + id + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200);
        putJson("/api/v1/org/positions/" + id + "/profile/hotels/12000000-0000-0000-0000-000000000003/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["org.read","task.read"],
                 "authorizationScopeType":"TENANT","wecomSelfSelectable":true}
                """, 400);

        putJson("/api/v1/org/positions/" + id + "/profile/hotels/12000000-0000-0000-0000-000000000003/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["org.read"],
                 "authorizationScopeType":"SELF","wecomSelfSelectable":false}
                """, 200);
        UUID hotelDraftId = jdbc.queryForObject("""
                select version.id
                from position_function_profile profile
                join position_function_profile_version version
                  on version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                where profile.position_id = ? and profile.scope_type = 'HOTEL'
                  and profile.hotel_org_unit_id = '12000000-0000-0000-0000-000000000003'
                  and version.lifecycle_status = 'DRAFT'
                """, UUID.class, UUID.fromString(id));
        assertThatThrownBy(() -> jdbc.update("""
                update position_function_profile_version
                set authorization_scope_type = 'TENANT'
                where id = ?
                """, hotelDraftId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update position_function_profile_version
                set wecom_self_selectable = true
                where id = ?
                """, hotelDraftId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void profileContextExplainsSystemBoundariesWithoutChangingPublishedGrants() throws Exception {
        UUID hrPositionId = jdbc.queryForObject("""
                select id from position_definition
                where tenant_id = ?::uuid and code = 'HR_ADMINISTRATION_SUPERVISOR'
                """, UUID.class, TENANT);
        long before = jdbc.queryForObject("select count(*) from role_permission", Long.class);
        JsonNode context = json(getJson(
                "/api/v1/org/positions/profile-context?positionId=" + hrPositionId, 200).andReturn());
        assertThat(context.path("roleCode").asText()).isEqualTo("HR_ADMINISTRATION_SUPERVISOR");
        assertThat(context.path("authorizationScopeTypes").toString())
                .isEqualTo("[\"SELF\",\"ORG_UNIT\",\"ORG_TREE\",\"ASSIGNED_HOTELS\",\"TENANT\"]");
        assertThat(context.path("selfSelectionBlockedReason").asText()).contains("不能开放员工自选");
        assertThat(context.path("retainedPermissions").findValuesAsText("permissionCode"))
                .contains("wecom-binding.read").doesNotContain("task.read");
        context.path("retainedPermissions").forEach(option ->
                assertThat(option.path("delegable").asBoolean()).isFalse());
        assertThat(context.path("executiveTasksEnabled").asBoolean()).isFalse();
        assertThat(context.path("workPlansEnabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from role_permission", Long.class)).isEqualTo(before);

        JsonNode generic = json(getJson("/api/v1/org/positions/profile-context", 200).andReturn());
        assertThat(generic.path("roleCode").asText()).isEqualTo("CUSTOM");
        assertThat(generic.path("retainedPermissions")).isEmpty();
        assertThat(generic.hasNonNull("selfSelectionBlockedReason")).isFalse();
    }

    @Test
    void profileContextRequiresReadPermissionAndTenantVisiblePosition() throws Exception {
        getAs("/api/v1/org/positions/profile-context", FRONT_ACCOUNT, 403);
        getJson("/api/v1/org/positions/profile-context?positionId=" + UUID.randomUUID(), 404);
    }

    @Test
    void protectedSelfSelectionReturnsActionableValidationInsteadOfDatabaseError() throws Exception {
        UUID hrPositionId = jdbc.queryForObject("""
                select id from position_definition
                where tenant_id = ?::uuid and code = 'HR_ADMINISTRATION_SUPERVISOR'
                """, UUID.class, TENANT);
        String versionsPath = "/api/v1/org/positions/" + hrPositionId + "/profile/versions";
        JsonNode before = json(getJson(versionsPath, 200).andReturn());
        JsonNode error = json(putJson("/api/v1/org/positions/" + hrPositionId + "/profile/draft", """
                {"expectedProfileVersion":0,"permissionCodes":["ui.module.workbench"],
                 "authorizationScopeType":"ORG_UNIT","wecomSelfSelectable":true}
                """, 400));
        assertThat(error.path("detail").asText()).contains("不能开放员工自选");
        assertThat(json(getJson(versionsPath, 200).andReturn())).isEqualTo(before);
    }

    @Test
    void recycleRestoreAndPermanentDeletePreserveAssignmentHistory() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"回收站岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"SELF",
                 "wecomSelfSelectable":false}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());
        postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200);
        postJson("/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"TEMPORARY","validFrom":"2026-01-02","validTo":"2026-01-01"}
                """.formatted(FRONT_DEPARTMENT, positionId), 400);
        postJson("/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"TEMPORARY","validFrom":"2026-01-01","validTo":"2026-01-31"}
                """.formatted(FRONT_DEPARTMENT, positionId), 400);
        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"2026-09-01"}
                """.formatted(FRONT_DEPARTMENT, positionId), 201)).path("id").asText());

        deleteJson("/api/v1/org/positions/" + positionId + "?expectedVersion=1", 204);
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?", String.class, assignmentId
        )).isEqualTo("INACTIVE");
        JsonNode restored = json(postJson("/api/v1/org/positions/" + positionId + "/restore", """
                {"expectedVersion":2,"restoreAssignments":true}
                """, 200));
        assertThat(restored.path("restoredAssignmentCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?", String.class, assignmentId
        )).isEqualTo("ACTIVE");

        deleteJson("/api/v1/org/positions/" + positionId + "?expectedVersion=3", 204);
        deleteJson("/api/v1/org/positions/" + positionId + "/permanent?expectedVersion=4", 204);
        assertThat(jdbc.queryForObject(
                "select name from position_definition where id = ?", String.class, positionId
        )).isEqualTo("回收站岗位（已删除）");
        assertThat(jdbc.queryForObject(
                "select count(*) from employee_position_assignment where id = ?", Integer.class, assignmentId
        )).isOne();
        getJson("/api/v1/org/positions/deleted", 200)
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(positionId)).isEmpty());
    }

    @Test
    void iamMeReturnsAssignmentScopedPermissionsForFutureRoleSwitching() throws Exception {
        getAs("/api/v1/iam/me", FRONT_ACCOUNT, 200)
                .andExpect(jsonPath("$.positionAssignments[0].permissionCodes").isArray())
                .andExpect(jsonPath("$.positionAssignments[0].authorizationScopeType").value("SELF"))
                .andExpect(jsonPath("$.positionAssignments[0].wecomSelfSelectable").value(true));
    }

    @Test
    void ordinaryOrgReadersUseMinimalPositionOptionsWithoutGovernanceDetails() throws Exception {
        MvcResult result = getAs("/api/v1/org/position-options", FRONT_ACCOUNT, 200).andReturn();
        JsonNode options = json(result);
        assertThat(options.isArray()).isTrue();
        assertThat(options.size()).isPositive();
        assertThat(options.get(0).has("profile")).isFalse();
        assertThat(options.get(0).has("permissionCodes")).isFalse();
        assertThat(options.get(0).has("defaultRoleId")).isFalse();
    }

    @Test
    void narrowingApplicabilityPreviewsAndRevokesOnlyPositionSourcedAccess() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"范围收窄来源隔离岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"ORG_UNIT",
                 "wecomSelfSelectable":true}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());
        postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200);
        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"2026-09-01"}
                """.formatted(FRONT_DEPARTMENT, positionId), 201)).path("id").asText());
        UUID roleId = jdbc.queryForObject("""
                select default_role_id from position_function_profile
                where tenant_id = ?::uuid and position_id = ? and scope_type = 'GROUP'
                """, UUID.class, TENANT, positionId);
        UUID sourcedGrant = jdbc.queryForObject("""
                select id from role_assignment
                where tenant_id = ?::uuid and source_type = 'POSITION_ASSIGNMENT'
                  and source_assignment_id = ? and role_id = ?
                """, UUID.class, TENANT, assignmentId, roleId);
        UUID manualGrant = UUID.randomUUID();
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id, scope_type,
                     valid_from, granted_by)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, 'ORG_UNIT', now(), ?::uuid)
                """, manualGrant, TENANT, FRONT_ACCOUNT, roleId, FRONT_DEPARTMENT, CEO);
        UUID bindingId = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_user_binding
                    (id, tenant_id, corp_id, wecom_user_id, user_id_fingerprint, account_id,
                     preferred_assignment_id, status, assignment_snapshot_hash, updated_by)
                values (?, ?::uuid, 'test-corp', ?, repeat('a',64), ?::uuid,
                        ?, 'ACTIVE', repeat('b',64), ?::uuid)
                """, bindingId, TENANT, "scope-" + bindingId, FRONT_ACCOUNT, assignmentId, CEO);

        getAsWithAssignment("/api/v1/iam/me", FRONT_ACCOUNT, assignmentId.toString(), 200)
                .andExpect(jsonPath("$.permissions[?(@ == 'org.read')]").isNotEmpty());
        JsonNode deleteImpact = json(postJson(
                "/api/v1/org/positions/" + positionId + "/impact-preview",
                "{\"operation\":\"DELETE\"}", 200));
        assertThat(deleteImpact.path("affectedActiveBindingCount").asLong()).isEqualTo(1);
        assertThat(deleteImpact.path("affectedRoleGrantCount").asLong()).isEqualTo(1);
        JsonNode impact = json(postJson("/api/v1/org/positions/" + positionId + "/impact-preview", """
                {"operation":"UPDATE_APPLICABILITY","appliesToAllHotels":false,
                 "applicableHotelIds":["%s"]}
                """.formatted(SHANGHAI_HOTEL), 200));
        assertThat(impact.path("activeAssignmentCount").asLong()).isEqualTo(1);
        assertThat(impact.path("affectedEmployeeCount").asLong()).isEqualTo(1);
        assertThat(impact.path("affectedActiveBindingCount").asLong()).isEqualTo(1);
        assertThat(impact.path("affectedRoleGrantCount").asLong()).isEqualTo(1);
        assertThat(impact.path("removedHotels").findValuesAsText("id"))
                .contains(HANGZHOU_HOTEL).doesNotContain(SHANGHAI_HOTEL);

        putJson("/api/v1/org/positions/" + positionId, """
                {"name":"范围收窄来源隔离岗位","appliesToAllHotels":false,
                 "applicableHotelIds":["%s"],"expectedVersion":1}
                """.formatted(SHANGHAI_HOTEL), 200);
        assertThat(jdbc.queryForObject(
                "select status from employee_position_assignment where id = ?", String.class, assignmentId
        )).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject(
                "select status from wecom_user_binding where id = ?", String.class, bindingId
        )).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject(
                "select valid_to is not null from role_assignment where id = ?", Boolean.class, sourcedGrant
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select valid_to is null from role_assignment where id = ?", Boolean.class, manualGrant
        )).isTrue();
        getAsWithAssignment("/api/v1/iam/me", FRONT_ACCOUNT, assignmentId.toString(), 403);
    }

    @Test
    void assignedHotelScopePublishesAndIsolatesEachAssignmentToItsSelectedHotels() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"跨店运营范围测试岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"ASSIGNED_HOTELS",
                 "wecomSelfSelectable":false}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());

        JsonNode published = json(postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200));
        assertThat(published.path("authorizationScopeType").asText()).isEqualTo("ASSIGNED_HOTELS");

        postJson("/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"2026-09-01",
                 "responsibleHotelIds":[]}
                """.formatted(FRONT_DEPARTMENT, positionId), 400);

        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"PERMANENT","validFrom":"2026-09-01",
                 "responsibleHotelIds":["%s","%s"]}
                """.formatted(FRONT_DEPARTMENT, positionId, HANGZHOU_HOTEL, SHANGHAI_HOTEL), 201))
                .path("id").asText());
        assertThat(jdbc.queryForObject("""
                select scope_type = 'ASSIGNED_HOTELS' and scope_org_unit_id is null
                from role_assignment
                where tenant_id = ?::uuid and source_assignment_id = ?
                """, Boolean.class, TENANT, assignmentId)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from employee_assignment_hotel_scope
                where tenant_id = ?::uuid and assignment_id = ?
                """, Integer.class, TENANT, assignmentId)).isEqualTo(2);

        putJson("/api/v1/org/assignments/" + assignmentId + "/hotel-scope", """
                {"responsibleHotelIds":["%s"]}
                """.formatted(HANGZHOU_HOTEL), 200);
        MvcResult visibleUnits = getAsWithAssignment(
                "/api/v1/org/units", FRONT_ACCOUNT, assignmentId.toString(), 200).andReturn();
        assertThat(json(visibleUnits).findValuesAsText("id"))
                .contains(HANGZHOU_HOTEL, FRONT_DEPARTMENT)
                .doesNotContain(SHANGHAI_HOTEL);

        getJson("/api/v1/org/employees", 200)
                .andExpect(jsonPath("$[?(@.assignment_id == '%s')].responsible_hotel_names"
                        .formatted(assignmentId)).value(org.hamcrest.Matchers.hasItem("杭州中心店")));
    }

    @Test
    void assignmentCreatesSourceGrantWithTheSameEffectiveDateWindow() throws Exception {
        JsonNode created = json(postJson("/api/v1/org/positions", """
                {"name":"未来任职授权窗口岗位","appliesToAllHotels":true,"applicableHotelIds":[],
                 "permissionCodes":["org.read"],"authorizationScopeType":"ORG_UNIT",
                 "wecomSelfSelectable":false}
                """, 201));
        UUID positionId = UUID.fromString(created.path("id").asText());
        postJson("/api/v1/org/positions/" + positionId + "/profile/publish", """
                {"expectedProfileVersion":0,"expectedPositionVersion":0}
                """, 200);
        UUID assignmentId = UUID.fromString(json(postJson(
                "/api/v1/org/employees/" + FRONT_EMPLOYEE + "/assignments", """
                {"orgUnitId":"%s","positionId":"%s","primary":false,
                 "assignmentType":"TEMPORARY","validFrom":"2099-01-01","validTo":"2099-01-31"}
                """.formatted(FRONT_DEPARTMENT, positionId), 201)).path("id").asText());
        assertThat(jdbc.queryForObject("""
                select valid_from::date = date '2099-01-01'
                       and valid_to::date = date '2099-02-01'
                from role_assignment
                where tenant_id = ?::uuid and source_type = 'POSITION_ASSIGNMENT'
                  and source_assignment_id = ?
                """, Boolean.class, TENANT, assignmentId)).isTrue();
        getAsWithAssignment("/api/v1/iam/me", FRONT_ACCOUNT, assignmentId.toString(), 403);
    }

    @Test
    void requestAssignmentHeaderPreventsPermissionsFromAnotherActivePosition() throws Exception {
        getAsWithAssignment("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT,
                FRONT_SUPERVISOR_ASSIGNMENT, 200)
                .andExpect(jsonPath("$.primaryRole").value("FRONT_OFFICE_SUPERVISOR"))
                .andExpect(jsonPath("$.permissions[?(@ == 'task.dispatch')]").isNotEmpty());

        getAsWithAssignment("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT,
                FRONT_SECONDARY_ASSIGNMENT, 200)
                .andExpect(jsonPath("$.primaryRole").value("FRONT_DESK"))
                .andExpect(jsonPath("$.permissions[?(@ == 'task.dispatch')]").isEmpty())
                .andExpect(jsonPath("$.permissions[?(@ == 'work-record.submit')]").isNotEmpty());

        getAsWithAssignment("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT,
                "19200000-0000-0000-0000-000000000002", 403);
        getAsWithAssignment("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT,
                "not-a-uuid", 400)
                .andExpect(jsonPath("$.detail").value("X-Assignment-Id不是有效UUID"));
    }

    @Test
    void hrSupplementSurvivesRoleSwitchWithoutMergingTheOtherAssignment() throws Exception {
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_type, valid_from, granted_by)
                select ?, ?::uuid, ?::uuid, role.id, 'TENANT', now(), ?::uuid
                from app_role role
                where role.tenant_id = ?::uuid and role.code = 'HR_KPI_ADMIN'
                """, grantId, TENANT, FRONT_SUPERVISOR_ACCOUNT, CEO, TENANT);
        try {
            MvcResult result = getAsWithAssignment("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT,
                    FRONT_SECONDARY_ASSIGNMENT, 200)
                    .andExpect(jsonPath("$.roles[?(@ == 'HR_KPI_ADMIN')]").isNotEmpty())
                    .andExpect(jsonPath("$.permissions[?(@ == 'wecom-binding.approve')]").isNotEmpty())
                    .andExpect(jsonPath("$.permissions[?(@ == 'task.dispatch')]").isEmpty())
                    .andExpect(jsonPath("$.permissions[?(@ == '*')]").isEmpty())
                    .andReturn();
            JsonNode response = json(result);
            JsonNode primary = assignment(response, FRONT_SUPERVISOR_ASSIGNMENT);
            JsonNode secondary = assignment(response, FRONT_SECONDARY_ASSIGNMENT);
            assertThat(permissionCodes(primary))
                    .contains("wecom-binding.approve", "task.dispatch");
            assertThat(permissionCodes(secondary))
                    .contains("wecom-binding.approve", "work-record.submit")
                    .doesNotContain("task.dispatch");
        } finally {
            jdbc.update("delete from role_assignment where id = ?", grantId);
        }
    }

    @Test
    void bootstrapWithoutHeaderDeterministicallySelectsFirstAssignmentWhenNoPrimaryExists() throws Exception {
        jdbc.update("update employee_position_assignment set is_primary = false where id = ?::uuid",
                FRONT_SUPERVISOR_ASSIGNMENT);
        try {
            getAs("/api/v1/iam/me", FRONT_SUPERVISOR_ACCOUNT, 200)
                    .andExpect(jsonPath("$.primaryRole").value("FRONT_OFFICE_SUPERVISOR"))
                    .andExpect(jsonPath("$.permissions[?(@ == 'task.dispatch')]").isNotEmpty());
        } finally {
            jdbc.update("update employee_position_assignment set is_primary = true where id = ?::uuid",
                    FRONT_SUPERVISOR_ASSIGNMENT);
        }
    }

    private MvcResult postJson(String path, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post(path).header("X-Tenant-Id", TENANT).header("X-Actor-Id", CEO)
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult putJson(String path, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(put(path).header("X-Tenant-Id", TENANT).header("X-Actor-Id", CEO)
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private void deleteJson(String path, int expectedStatus) throws Exception {
        mockMvc.perform(delete(path).header("X-Tenant-Id", TENANT).header("X-Actor-Id", CEO))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions getJson(String path, int expectedStatus) throws Exception {
        return getAs(path, CEO, expectedStatus);
    }

    private org.springframework.test.web.servlet.ResultActions getAs(
            String path, String accountId, int expectedStatus
    ) throws Exception {
        return mockMvc.perform(get(path).header("X-Tenant-Id", TENANT).header("X-Actor-Id", accountId))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions getAsWithAssignment(
            String path, String accountId, String assignmentId, int expectedStatus
    ) throws Exception {
        return mockMvc.perform(get(path)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", accountId)
                        .header("X-Assignment-Id", assignmentId))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode assignment(JsonNode response, String assignmentId) {
        for (JsonNode assignment : response.path("positionAssignments")) {
            if (assignmentId.equals(assignment.path("id").asText())) return assignment;
        }
        throw new AssertionError("响应缺少任职 " + assignmentId);
    }

    private JsonNode option(JsonNode response, String permissionCode) {
        for (JsonNode option : response) {
            if (permissionCode.equals(option.path("permissionCode").asText())) return option;
        }
        throw new AssertionError("响应缺少权限选项 " + permissionCode);
    }

    private Set<String> permissionCodes(JsonNode assignment) {
        Set<String> permissions = new LinkedHashSet<>();
        assignment.path("permissionCodes").forEach(item -> permissions.add(item.asText()));
        return permissions;
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
