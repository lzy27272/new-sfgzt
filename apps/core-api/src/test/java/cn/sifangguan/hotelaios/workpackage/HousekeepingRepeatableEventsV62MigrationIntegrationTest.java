package cn.sifangguan.hotelaios.workpackage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class HousekeepingRepeatableEventsV62MigrationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String MIGRATION_OWNER = "housekeeping_v62_migration_owner";

    @Test
    void publishesRepeatableHousekeepingEventsWithFreshNobypassRlsMigrationConnection() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource adminDataSource = postgres.getPostgresDatabase();
            JdbcTemplate jdbc = new JdbcTemplate(adminDataSource);
            jdbc.execute("CREATE ROLE " + MIGRATION_OWNER
                    + " LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOCREATEDB "
                    + "NOCREATEROLE NOINHERIT NOBYPASSRLS");
            jdbc.execute("ALTER DATABASE postgres OWNER TO " + MIGRATION_OWNER);
            jdbc.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_OWNER);
            DataSource migrationDataSource = postgres.getDatabase(MIGRATION_OWNER, "postgres");

            migrate(migrationDataSource, "61");
            assertThat(new JdbcTemplate(migrationDataSource).queryForObject(
                    "select count(*) from work_package_definition", Integer.class)).isZero();
            migrate(migrationDataSource, "62");

            assertThat(jdbc.queryForObject(
                    "select rolbypassrls from pg_roles where rolname = ?",
                    Boolean.class, MIGRATION_OWNER)).isFalse();

            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from work_package_item item
                    join work_package_version version
                      on version.tenant_id = item.tenant_id
                     and version.id = item.work_package_version_id
                    join work_package_definition definition
                      on definition.tenant_id = version.tenant_id
                     and definition.id = version.work_package_definition_id
                    where item.tenant_id = ?::uuid
                      and definition.code = 'WP-PILOT-HK-ATTENDANT-DAILY'
                      and version.lifecycle_status = 'PUBLISHED'
                      and item.item_code in ('HKA_GUEST_SERVICE_EVENT', 'HKA_COMPLAINT_EVENT')
                      and item.item_type = 'EVENT_RECORD'
                      and (item.execution_policy ->> 'repeatableDailyRecords')::boolean
                      and (item.report_policy ->> 'aggregateAllRecords')::boolean
                    """, Integer.class, TENANT)).isEqualTo(2);

            assertThat(jdbc.queryForObject("""
                    select json_schema -> 'required'
                    from form_version version
                    join form_definition definition
                      on definition.tenant_id = version.tenant_id
                     and definition.id = version.form_id
                    where version.tenant_id = ?::uuid
                      and definition.code = 'PILOT-V62-HKA-GUEST-SERVICE-EVENT'
                      and version.lifecycle_status = 'PUBLISHED'
                    """, String.class, TENANT)).contains("roomNumber", "serviceContent");

            assertThat(jdbc.queryForObject("""
                    select json_schema
                    from form_version version
                    join form_definition definition
                      on definition.tenant_id = version.tenant_id
                     and definition.id = version.form_id
                    where version.tenant_id = ?::uuid
                      and definition.code = 'PILOT-V62-HKA-COMPLAINT-EVENT'
                      and version.lifecycle_status = 'PUBLISHED'
                    """, String.class, TENANT))
                    .contains("roomNumber", "房号", "complaintContent")
                    .doesNotContain("location", "房号/发生区域");

            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from daily_report_template_item item
                    join daily_report_section_version section
                      on section.tenant_id = item.tenant_id
                     and section.id = item.section_version_id
                    join daily_report_section_definition definition
                      on definition.tenant_id = section.tenant_id
                     and definition.id = section.section_definition_id
                    where item.tenant_id = ?::uuid
                      and definition.code = 'DRS-PILOT-HK-ATTENDANT-DAILY-V2'
                      and item.work_package_item_id in (
                          md5('V62:item:HKA_GUEST_SERVICE_EVENT')::uuid,
                          md5('V62:item:HKA_COMPLAINT_EVENT')::uuid
                      )
                      and item.source_policy ->> 'mode' = 'AUTO_ALL_RECORDS'
                    """, Integer.class, TENANT)).isEqualTo(2);

            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_indexes
                    where schemaname = 'public'
                      and indexname in (
                          'ux_work_record_expectation_series_attempt',
                          'uq_work_record_repeatable_series_active_submission'
                      )
                    """, Integer.class)).isEqualTo(2);
        }
    }

    private static void migrate(DataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
