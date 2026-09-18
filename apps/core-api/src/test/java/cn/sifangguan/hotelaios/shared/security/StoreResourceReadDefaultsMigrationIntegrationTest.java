package cn.sifangguan.hotelaios.shared.security;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoreResourceReadDefaultsMigrationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String ACTOR = "19000000-0000-0000-0000-000000000001";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String MIGRATION = "/db/migration/V61__governed_store_resource_read_defaults.sql";

    @Test
    void publishesOnlyReadGrantsAsNewVersionsWithRlsOwnerAndPreservesScopesAndHistory() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrate(dataSource, "60");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            List<UUID> oldVersions = jdbc.queryForList("""
                    select version.id from position_function_profile_version version
                    join position_function_profile profile on profile.id = version.profile_id
                    join app_role role_item on role_item.id = profile.default_role_id
                    where role_item.code in ('GENERAL_MANAGER', 'CEO', 'GROUP_VICE_PRESIDENT')
                    """, UUID.class);
            String grantsBefore = historicalPermissions(jdbc, oldVersions);
            String scopeBefore = scopeSnapshot(jdbc);
            String adminsBefore = adminPermissions(jdbc);
            jdbc.execute("CREATE ROLE store_read_migration_owner LOGIN NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO store_read_migration_owner");
            jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO store_read_migration_owner");
            jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO store_read_migration_owner");
            jdbc.execute("ALTER TABLE tenant OWNER TO store_read_migration_owner");
            DataSource owner = postgres.getDatabase("store_read_migration_owner", "postgres");
            assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from tenant", Integer.class)).isZero();
            migrate(owner, "61");

            for (String role : List.of("GENERAL_MANAGER", "CEO", "GROUP_VICE_PRESIDENT")) {
                assertThat(storePermissions(jdbc, role, "PUBLISHED"))
                        .containsExactly("store-resource.read", "ui.module.store-resources");
                assertThat(storePermissions(jdbc, role, "DRAFT"))
                        .containsExactly("store-resource.read", "ui.module.store-resources");
                assertThat(jdbc.queryForList("""
                        select permission.code from role_permission item
                        join app_role role_item on role_item.id = item.role_id
                        join permission on permission.id = item.permission_id
                        where role_item.code = ? and (permission.code like 'store-resource.%'
                            or permission.code = 'ui.module.store-resources') order by permission.code
                        """, String.class, role)).containsExactly("store-resource.read", "ui.module.store-resources");
            }
            for (String role : List.of("FRONT_DESK", "HOUSEKEEPING_SUPERVISOR", "FRONT_OFFICE_SUPERVISOR", "ASSISTANT_GENERAL_MANAGER")) {
                assertThat(storePermissions(jdbc, role, "PUBLISHED")).isEmpty();
            }
            assertThat(historicalPermissions(jdbc, oldVersions)).isEqualTo(grantsBefore);
            assertThat(scopeSnapshot(jdbc)).isEqualTo(scopeBefore);
            assertThat(adminPermissions(jdbc)).isEqualTo(adminsBefore);
            assertThat(jdbc.queryForObject("""
                    select count(*) from audit_log where action = 'SYSTEM_STORE_RESOURCE_READ_DEFAULT_PUBLISHED'
                    """, Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select relforcerowsecurity from pg_class where relname = 'tenant'", Boolean.class)).isTrue();
            assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from position_function_profile", Integer.class)).isZero();

            int versions = jdbc.queryForObject("select count(*) from position_function_profile_version", Integer.class);
            try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute(migrationSql());
                connection.commit();
            }
            assertThat(jdbc.queryForObject("select count(*) from position_function_profile_version", Integer.class)).isEqualTo(versions);
        }
    }

    @Test
    void humanDraftPublishedChangesAndHotelOptOutRemainUntouched() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrate(dataSource, "60");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            // A saved human draft and a modified publication are never auto-granted.
            jdbc.update("""
                    update position_function_profile_version version set row_version = version.row_version + 1
                    from position_function_profile profile join app_role role_item on role_item.id = profile.default_role_id
                    where version.profile_id = profile.id and
                      ((role_item.code = 'GENERAL_MANAGER' and version.lifecycle_status = 'DRAFT')
                       or (role_item.code = 'GROUP_VICE_PRESIDENT' and version.lifecycle_status = 'PUBLISHED'))
                    """);
            String customizedBefore = customizedVersions(jdbc);
            UUID hotelProfile = UUID.randomUUID();
            UUID hotelVersion = UUID.randomUUID();
            jdbc.update("""
                    insert into position_function_profile (id, tenant_id, position_id, scope_type, hotel_org_unit_id)
                    select ?, ?::uuid, position.id, 'HOTEL', ?::uuid from position_definition position
                    where position.tenant_id = ?::uuid and position.code = 'GROUP_GENERAL_MANAGER'
                    """, hotelProfile, TENANT, HOTEL, TENANT);
            jdbc.update("""
                    insert into position_function_profile_version
                      (id, tenant_id, profile_id, version_no, lifecycle_status, based_on_group_version_id,
                       authorization_scope_type, published_by, published_at)
                    select ?, ?::uuid, ?, 1, 'PUBLISHED', version.id, 'SELF', ?::uuid, now()
                    from position_function_profile profile
                    join position_definition position on position.id = profile.position_id
                    join position_function_profile_version version on version.profile_id = profile.id
                    where position.code = 'GROUP_GENERAL_MANAGER' and profile.scope_type = 'GROUP'
                      and version.lifecycle_status = 'PUBLISHED'
                    """, hotelVersion, TENANT, hotelProfile, ACTOR);
            String hotelBefore = jdbc.queryForObject("select row_to_json(version)::text from position_function_profile_version version where id = ?",
                    String.class, hotelVersion);
            migrate(dataSource, "61");
            assertThat(customizedVersions(jdbc)).isEqualTo(customizedBefore);
            assertThat(storePermissions(jdbc, "GENERAL_MANAGER", "PUBLISHED")).isEmpty();
            assertThat(storePermissions(jdbc, "CEO", "PUBLISHED")).isEmpty();
            assertThat(storePermissions(jdbc, "GROUP_VICE_PRESIDENT", "PUBLISHED")).isEmpty();
            assertThat(jdbc.queryForObject("select row_to_json(version)::text from position_function_profile_version version where id = ?",
                    String.class, hotelVersion)).isEqualTo(hotelBefore);
            assertThat(jdbc.queryForObject("select count(*) from position_function_profile_permission where profile_version_id = ?",
                    Integer.class, hotelVersion)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from role_permission item join app_role role_item on role_item.id = item.role_id
                    join permission on permission.id = item.permission_id
                    where role_item.code in ('GENERAL_MANAGER', 'CEO', 'GROUP_VICE_PRESIDENT')
                      and permission.code = 'store-resource.read'
                    """, Integer.class)).isZero();
        }
    }

    private static void migrate(DataSource dataSource, String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).cleanDisabled(true).load().migrate();
    }

    private List<String> storePermissions(JdbcTemplate jdbc, String role, String lifecycle) {
        return jdbc.queryForList("""
                select permission.code from position_function_profile_permission item
                join permission on permission.id = item.permission_id
                join position_function_profile_version version on version.id = item.profile_version_id
                join position_function_profile profile on profile.id = version.profile_id
                join app_role role_item on role_item.id = profile.default_role_id
                where role_item.code = ? and version.lifecycle_status = ?
                  and (permission.code like 'store-resource.%' or permission.code = 'ui.module.store-resources')
                order by permission.code
                """, String.class, role, lifecycle);
    }

    private String historicalPermissions(JdbcTemplate jdbc, List<UUID> versions) {
        return versions.stream().sorted().map(id -> id + ":" + jdbc.queryForList("""
                select permission_id::text from position_function_profile_permission
                where profile_version_id = ? order by permission_id
                """, String.class, id)).toList().toString();
    }

    private String scopeSnapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select id::text, scope_type, scope_org_unit_id::text from role_assignment order by id
                """).toString() + jdbc.queryForList("""
                select id::text, org_unit_id::text from employee_position_assignment order by id
                """).toString();
    }

    private String adminPermissions(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select role_item.code, permission.code from role_permission item
                join app_role role_item on role_item.id = item.role_id
                join permission on permission.id = item.permission_id
                where role_item.code in ('PLATFORM_ADMIN', 'GROUP_ADMIN') order by role_item.code, permission.code
                """).toString();
    }

    private String customizedVersions(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select row_to_json(version)::text from position_function_profile_version version
                join position_function_profile profile on profile.id = version.profile_id
                join app_role role_item on role_item.id = profile.default_role_id
                where role_item.code in ('GENERAL_MANAGER', 'GROUP_VICE_PRESIDENT') order by version.id
                """, String.class).toString();
    }

    private String migrationSql() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(MIGRATION))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
