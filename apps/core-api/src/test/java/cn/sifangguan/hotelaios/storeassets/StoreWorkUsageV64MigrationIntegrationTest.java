package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class StoreWorkUsageV64MigrationIntegrationTest {
    @Test void optInMigrationWorksWithoutBypassRlsAndDoesNotBackfillOrDeduct() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var source = postgres.getPostgresDatabase();
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("63").load().migrate();
            jdbc.update("""
                    insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost)
                    values('64000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',
                        '12000000-0000-0000-0000-000000000003','BEFORE-USAGE','升级前物品','CONSUMABLE','支',2)
                    """);
            jdbc.update("""
                    insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value)
                    values('10000000-0000-0000-0000-000000000001','12000000-0000-0000-0000-000000000003',
                        '64000000-0000-0000-0000-000000000001',100,200)
                    """);
            String before = jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id='64000000-0000-0000-0000-000000000001'",String.class);
            int records = jdbc.queryForObject("select count(*) from work_record",Integer.class);
            int movements = jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class);
            jdbc.execute("create role usage_migration_owner login password 'local-only' nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to usage_migration_owner");
            jdbc.execute("grant all on all tables in schema public to usage_migration_owner");
            jdbc.execute("grant all on all sequences in schema public to usage_migration_owner");
            // DDL owner permissions, not superuser/BYPASSRLS, as in the production migrator.
            jdbc.execute("alter table store_inventory_movement owner to usage_migration_owner");
            jdbc.execute("alter table store_resource_item owner to usage_migration_owner");
            String url;
            try (var connection=source.getConnection()) { url=connection.getMetaData().getURL(); }
            Flyway.configure().dataSource(url,"usage_migration_owner","local-only")
                    .locations("classpath:db/migration").target("64").load().migrate();
            assertThat(jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id='64000000-0000-0000-0000-000000000001'",String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from work_record",Integer.class)).isEqualTo(records);
            assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class)).isEqualTo(movements);
            for (String table : java.util.List.of("store_usage_review_config","store_work_usage","store_work_usage_line")) {
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            }
        }
    }
}
