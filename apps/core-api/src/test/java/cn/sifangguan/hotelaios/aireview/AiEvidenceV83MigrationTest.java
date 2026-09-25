package cn.sifangguan.hotelaios.aireview;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AiEvidenceV83MigrationTest {
    @Test void additiveUpgradeResetsInheritedGrantsAndPreservesBusinessHistory() throws Exception {
        try (var pg = EmbeddedPostgres.builder().start()) {
            var source = pg.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("82").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var protectedTables = List.of("work_record", "work_expectation", "employee_position_assignment", "daily_report", "audit_log");
            var before = protectedTables.stream().map(table -> fingerprint(jdbc, table)).toList();
            jdbc.execute("create role ai_migration_owner nologin nosuperuser nobypassrls");
            jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
            jdbc.execute("create role hotel_ai_os_readonly nologin nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to ai_migration_owner");
            jdbc.execute("grant all on all tables in schema public to ai_migration_owner");
            jdbc.execute("grant usage,select on all sequences in schema public to ai_migration_owner");
            jdbc.execute("alter default privileges for role ai_migration_owner in schema public grant all on tables to hotel_ai_os_app,hotel_ai_os_readonly");
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("83")
                    .initSql("set role ai_migration_owner").load().migrate();
            assertThat(protectedTables.stream().map(table -> fingerprint(jdbc, table)).toList()).isEqualTo(before);
            for (String table : List.of("ai_evidence_rule", "ai_evidence_sample", "ai_evidence_photo", "ai_evidence_check", "ai_evidence_event")) {
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass", Boolean.class, table)).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
                assertThat(privilege(jdbc, "hotel_ai_os_app", table, "SELECT")).isTrue();
                assertThat(privilege(jdbc, "hotel_ai_os_app", table, "INSERT")).isTrue();
                assertThat(privilege(jdbc, "hotel_ai_os_app", table, "UPDATE")).isEqualTo(List.of("ai_evidence_rule", "ai_evidence_check").contains(table));
                assertThat(privilege(jdbc, "hotel_ai_os_app", table, "DELETE")).isEqualTo(table.equals("ai_evidence_sample"));
                assertThat(privilege(jdbc, "hotel_ai_os_readonly", table, "SELECT")).isTrue();
                for (String action : List.of("TRUNCATE", "REFERENCES", "TRIGGER"))
                    assertThat(privilege(jdbc, "hotel_ai_os_app", table, action)).isFalse();
                for (String action : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
                    assertThat(privilege(jdbc, "hotel_ai_os_readonly", table, action)).isFalse();
            }
        }
    }
    private boolean privilege(JdbcTemplate jdbc, String role, String table, String action) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select has_table_privilege(?,?,?)", Boolean.class, role, table, action));
    }
    private String fingerprint(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select md5(coalesce(string_agg(row_json,',' order by row_json),'')) from (select to_jsonb(t)::text row_json from " + table + " t) s", String.class);
    }
}
