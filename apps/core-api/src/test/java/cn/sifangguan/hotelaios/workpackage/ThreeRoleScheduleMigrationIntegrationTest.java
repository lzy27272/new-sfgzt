package cn.sifangguan.hotelaios.workpackage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ThreeRoleScheduleMigrationIntegrationTest {
    @Test
    void stagedDraftsPreserveEveryExistingContractAndBusinessRow() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("113").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            Map<String,String> frozen = new LinkedHashMap<>();
            for (String table : new String[]{"work_package_version","work_package_item","work_package_allocation",
                    "work_expectation","work_record","daily_report_template_version","daily_report","daily_report_revision",
                    "daily_report_delivery_policy"}) {
                frozen.put(table, snapshot(jdbc, table));
            }
            // Snapshot old IDs separately so additions can be excluded after migration.
            var oldIds = new LinkedHashMap<String,java.util.List<String>>();
            for (String table : frozen.keySet()) oldIds.put(table, jdbc.queryForList("select id::text from "+table, String.class));
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("114").load().migrate();
            for (var entry : oldIds.entrySet()) {
                String table=entry.getKey();
                String actual = entry.getValue().isEmpty() ? "" : jdbc.queryForObject(
                        "select coalesce(string_agg(to_jsonb(t)::text,'|' order by id),'') from "+table+" t where id in ("
                                +String.join(",",entry.getValue().stream().map(id->"'"+id+"'::uuid").toList())+")",String.class);
                assertThat(actual).as("unchanged old rows: "+table).isEqualTo(frozen.get(table));
            }
            assertThat(jdbc.queryForObject("""
                    select count(distinct work_package_version_id) from work_package_item
                    where execution_policy->>'workflowRevision'='THREE_ROLE_V88'
                    """,Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("""
                    select count(*) from work_package_version v where v.lifecycle_status<>'DRAFT'
                      and exists(select 1 from work_package_item i where i.work_package_version_id=v.id
                        and i.execution_policy->>'workflowRevision'='THREE_ROLE_V88')
                    """,Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from work_package_item i where execution_policy->>'workflowRevision'='THREE_ROLE_V88'
                      and item_code in ('AGM_NS_BREAKFAST_FEEDBACK','AGM_WS_BREAKFAST_FEEDBACK')
                    """,Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from work_package_item i join form_version f on f.id=i.form_version_id
                    where i.execution_policy->>'workflowRevision'='THREE_ROLE_V88'
                      and item_code like 'AGM_%_BREAKFAST_INSPECTION'
                      and f.json_schema->'required' ? 'breakfastOpeningNotes'
                      and f.json_schema->'required' ? 'breakfastPeakNotes'
                      and f.json_schema->'required' ? 'breakfastClosingNotes'
                    """,Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    select execution_policy->>'calendarDayOffset' from work_package_item
                    where execution_policy->>'workflowRevision'='THREE_ROLE_V88' and item_code='NSA_EQUIPMENT_SECOND'
                    """,String.class)).isEqualTo("1");
            assertThat(jdbc.queryForObject("""
                    select review_mode from work_package_item
                    where execution_policy->>'workflowRevision'='THREE_ROLE_V88' and item_code='HKA_CART_PREPARATION'
                    """,String.class)).isEqualTo("NONE");
            assertThat(jdbc.queryForObject("""
                    select count(*) from daily_report_template_version
                    where configuration->>'workflowRevision'='THREE_ROLE_V88'
                      and lifecycle_status='DRAFT' and effective_from is null
                    """,Integer.class)).isGreaterThanOrEqualTo(3);
            // Staged candidates do not relax content immutability of their predecessors.
            assertThatThrownBy(()->jdbc.update("""
                    update work_package_version set title=title||' changed'
                    where id=(select v.id from work_package_version v join work_package_definition d
                      on d.id=v.work_package_definition_id where d.code='WP-PILOT-HK-ATTENDANT-DAILY'
                        and v.lifecycle_status='PUBLISHED' order by v.version_no desc limit 1)
                    """)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("""
                    update daily_report_template_version set configuration=configuration||'{"changed":true}'::jsonb
                    where id=(select id from daily_report_template_version where lifecycle_status='PUBLISHED' limit 1)
                    """)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    private String snapshot(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select coalesce(string_agg(to_jsonb(t)::text,'|' order by id),'') from "+table+" t",String.class);
    }
}
