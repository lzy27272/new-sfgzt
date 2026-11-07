package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class StoreUsageReviewGroupV120MigrationIntegrationTest {
    @Test void upgradeKeepsTheOriginalReviewerAndBusinessRecordsWhileEnforcingTenantIsolation() throws Exception {
        try (var postgres=EmbeddedPostgres.builder().start()) {
            var source=postgres.getPostgresDatabase();
            var jdbc=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("119").load().migrate();
            jdbc.update("""
                    insert into store_usage_review_config
                        (tenant_id,hotel_org_unit_id,reviewer_assignment_id,updated_by)
                    values ('10000000-0000-0000-0000-000000000001',
                        '12000000-0000-0000-0000-000000000003',
                        '19200000-0000-0000-0000-000000000001',
                        '19000000-0000-0000-0000-000000000002')
                    """);
            String before=jdbc.queryForObject("select to_jsonb(c)::text from store_usage_review_config c",String.class);
            int workRecords=jdbc.queryForObject("select count(*) from work_record",Integer.class);
            int reports=jdbc.queryForObject("select count(*) from daily_report",Integer.class);
            int movements=jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class);
            String profileSnapshotSql="""
                    select coalesce(json_agg(profile_row order by profile_id),'[]'::json)::text from (
                      select f.id as profile_id,v.authorization_scope_type,v.wecom_self_selectable,
                        array_agg(permission.code order by permission.code) as original_permissions
                      from position_function_profile f
                      join position_definition p on p.tenant_id=f.tenant_id and p.id=f.position_id
                      join position_function_profile_version v on v.tenant_id=f.tenant_id and v.profile_id=f.id and v.lifecycle_status='PUBLISHED'
                      join position_function_profile_permission granted on granted.tenant_id=v.tenant_id and granted.profile_version_id=v.id
                      join permission on permission.id=granted.permission_id and permission.code<>'store-resource.usage-review'
                      where f.scope_type='GROUP' and p.code in ('GENERAL_MANAGER','ASSISTANT_GENERAL_MANAGER')
                      group by f.id,v.authorization_scope_type,v.wecom_self_selectable
                    ) profile_row
                    """;
            String profileBefore=jdbc.queryForObject(profileSnapshotSql,String.class);
            String draftsSql="select coalesce(json_agg(to_jsonb(v) order by id),'[]'::json)::text from position_function_profile_version v where lifecycle_status='DRAFT'";
            String draftsBefore=jdbc.queryForObject(draftsSql,String.class);
            jdbc.execute("create role usage_group_migrator login password 'isolated-test-only' nosuperuser nobypassrls");
            jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
            jdbc.execute("grant usage on schema public to hotel_ai_os_app");
            jdbc.execute("grant usage,create on schema public to usage_group_migrator");
            jdbc.execute("grant all on all tables in schema public to usage_group_migrator");
            jdbc.execute("grant all on all sequences in schema public to usage_group_migrator");
            jdbc.execute("alter table tenant owner to usage_group_migrator");
            String url;
            try(var connection=source.getConnection()) { url=connection.getMetaData().getURL(); }
            Flyway.configure().dataSource(url,"usage_group_migrator","isolated-test-only")
                    .locations("classpath:db/migration").target("120").load().migrate();
            assertThat(jdbc.queryForObject("select to_jsonb(c)::text from store_usage_review_config c",String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from store_usage_review_config_member",Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select reviewer_assignment_id::text from store_usage_review_config_member",String.class))
                    .isEqualTo("19200000-0000-0000-0000-000000000001");
            assertThat(jdbc.queryForObject("select count(*) from work_record",Integer.class)).isEqualTo(workRecords);
            assertThat(jdbc.queryForObject("select count(*) from daily_report",Integer.class)).isEqualTo(reports);
            assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class)).isEqualTo(movements);
            assertThat(jdbc.queryForObject(profileSnapshotSql,String.class)).isEqualTo(profileBefore);
            assertThat(jdbc.queryForObject(draftsSql,String.class)).isEqualTo(draftsBefore);
            assertThat(jdbc.queryForObject("select count(*) from permission where code='store-resource.usage-review' and delegable_to_position",Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SYSTEM_USAGE_REVIEW_PROFILE_PUBLISHED'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid='store_usage_review_config_member'::regclass",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select relforcerowsecurity from pg_class where oid='tenant'::regclass",Boolean.class)).isTrue();
            try(var connection=source.getConnection()) {
                connection.setAutoCommit(false);
                var restricted=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                restricted.execute("set local role hotel_ai_os_app");
                restricted.execute("set local app.tenant_id='10000000-0000-0000-0000-000000000001'");
                assertThat(restricted.queryForObject("select count(*) from store_usage_review_config_member",Integer.class)).isOne();
                restricted.execute("set local app.tenant_id='10000000-0000-0000-0000-000000000099'");
                assertThat(restricted.queryForObject("select count(*) from store_usage_review_config_member",Integer.class)).isZero();
                connection.rollback();
            }
        }
    }
}
