package cn.sifangguan.hotelaios.academy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AcademyV79MigrationIntegrationTest {
    @Test
    void upgradesAnExistingTenantWithoutClassifyingEmployeesOrChangingBusinessHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("78").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var tables = jdbc.queryForList("""
                    select tablename from pg_tables where schemaname='public'
                    and tablename <> 'flyway_schema_history' order by tablename
                    """, String.class);
            Map<String, String> before = new LinkedHashMap<>();
            for (String table : tables) before.put(table, fingerprint(jdbc, table, false));
            assertThat(jdbc.queryForObject("select count(*) from employee", Integer.class)).isPositive();

            // The deployed migration owner cannot bypass tenant row policies.
            jdbc.execute("create role academy_migration_owner nologin nosuperuser nobypassrls");
            jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to academy_migration_owner");
            jdbc.execute("grant all on all tables in schema public to academy_migration_owner");
            jdbc.execute("grant usage,select on all sequences in schema public to academy_migration_owner");
            jdbc.execute("alter table employee owner to academy_migration_owner");
            jdbc.execute("alter table tenant owner to academy_migration_owner");
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("79")
                    .initSql("set role academy_migration_owner").load().migrate();

            for (String table : tables) {
                assertThat(fingerprint(jdbc, table, true)).as("existing rows in %s", table).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForObject("select count(*) from employee where employee_type is not null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from academy_plan", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from academy_dispatch_queue", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname='public' and c.relkind='r' and c.relname like 'academy_%'
                    and c.relrowsecurity and c.relforcerowsecurity
                    and has_table_privilege('hotel_ai_os_app',c.oid,'SELECT,INSERT,UPDATE,DELETE')
                    """, Integer.class)).isEqualTo(12);
            assertThat(jdbc.queryForObject("select relforcerowsecurity from pg_class where oid='tenant'::regclass", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from permission where code in ('ui.module.academy','academy.learn','academy.manage','academy.team-read')", Integer.class)).isEqualTo(4);
        }
    }

    private static String fingerprint(JdbcTemplate jdbc, String table, boolean upgraded) {
        String row = upgraded && table.equals("employee")
                ? "to_jsonb(t)-'employee_type'-'employee_type_updated_at'-'employee_type_updated_by'" : "to_jsonb(t)";
        String filter = "";
        if (upgraded && table.equals("permission")) filter = " where code not in ('ui.module.academy','academy.learn','academy.manage','academy.team-read')";
        if (upgraded && (table.equals("role_permission") || table.equals("position_function_profile_permission")))
            filter = " where permission_id not in (select id from permission where code in ('ui.module.academy','academy.learn','academy.manage','academy.team-read'))";
        return jdbc.queryForObject("select md5(coalesce(jsonb_agg(" + row + " order by (" + row + ")::text)::text,'[]')) from \""
                + table.replace("\"", "\"\"") + "\" t" + filter, String.class);
    }
}
