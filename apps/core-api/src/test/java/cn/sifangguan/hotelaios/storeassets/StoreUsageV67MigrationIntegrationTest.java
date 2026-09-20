package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class StoreUsageV67MigrationIntegrationTest {
    @Test void upgradeWithoutBypassRlsPreservesAllExistingStockAndCostFacts() throws Exception {
        try (var postgres=EmbeddedPostgres.builder().start()) {
            var source=postgres.getPostgresDatabase(); var jdbc=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("66").load().migrate();
            jdbc.update("""
                    insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost)
                    values('67000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',
                    '12000000-0000-0000-0000-000000000003','PRE-V67','升级前物品','CONSUMABLE','支',2)
                    """);
            jdbc.update("""
                    insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value)
                    values('10000000-0000-0000-0000-000000000001','12000000-0000-0000-0000-000000000003',
                    '67000000-0000-0000-0000-000000000001',100,200)
                    """);
            String before=jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id='67000000-0000-0000-0000-000000000001'",String.class);
            int movementCount=jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class);
            int costCount=jdbc.queryForObject("select count(*) from store_cost_entry",Integer.class);
            jdbc.execute("create role correction_migrator login password 'test-only' nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to correction_migrator");
            jdbc.execute("grant all on all tables in schema public to correction_migrator");
            jdbc.execute("grant all on all sequences in schema public to correction_migrator");
            for(String table:java.util.List.of("store_inventory_movement","store_resource_item","store_cost_entry","work_record"))
                jdbc.execute("alter table "+table+" owner to correction_migrator");
            jdbc.execute("alter function capture_store_movement_cost(store_inventory_movement,boolean) owner to correction_migrator");
            String url; try(var connection=source.getConnection()) { url=connection.getMetaData().getURL(); }
            Flyway.configure().dataSource(url,"correction_migrator","test-only").locations("classpath:db/migration").target("67").load().migrate();
            assertThat(jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id='67000000-0000-0000-0000-000000000001'",String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement",Integer.class)).isEqualTo(movementCount);
            assertThat(jdbc.queryForObject("select count(*) from store_cost_entry",Integer.class)).isEqualTo(costCount);
            for(String table:java.util.List.of("store_work_usage_correction","store_work_usage_correction_line","store_work_usage_report_link")) {
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            }
        }
    }
}
