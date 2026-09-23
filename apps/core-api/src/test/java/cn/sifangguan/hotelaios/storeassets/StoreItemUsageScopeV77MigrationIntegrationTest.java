package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class StoreItemUsageScopeV77MigrationIntegrationTest {
    @Test void upgradeOnlyAddsLegacyMetadataAndPreservesAllExistingBusinessFacts() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            var source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("76").load().migrate();
            var jdbc = new JdbcTemplate(source);
            String tenant = "10000000-0000-0000-0000-000000000001", hotel = "12000000-0000-0000-0000-000000000003";
            String position = "14000000-0000-0000-0000-000000000001", actor = "19000000-0000-0000-0000-000000000001";
            String item = UUID.randomUUID().toString(), movement = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost,employee_usage_enabled)
                    values(?::uuid,?::uuid,?::uuid,'LEGACY-SCOPE','旧物品','CONSUMABLE','支',2,false)
                    """, item, tenant, hotel);
            jdbc.update("insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value) values(?::uuid,?::uuid,?::uuid,10,20)", tenant,hotel,item);
            jdbc.update("""
                    insert into store_inventory_movement(id,tenant_id,hotel_org_unit_id,item_id,movement_type,quantity_delta,
                    quantity_before,quantity_after,unit_cost_snapshot,value_delta,reason,idempotency_key,request_hash,actor_account_id)
                    values(?::uuid,?::uuid,?::uuid,?::uuid,'RECEIPT',10,0,10,2,20,'原有流水','legacy-scope',repeat('0',64),?::uuid)
                    """,movement,tenant,hotel,item,actor);
            jdbc.update("insert into store_usage_quick_list(tenant_id,hotel_org_unit_id,position_id,updated_by) values(?::uuid,?::uuid,?::uuid,?::uuid)",tenant,hotel,position,actor);
            jdbc.update("insert into store_usage_quick_list_item(tenant_id,hotel_org_unit_id,position_id,item_id,display_order) values(?::uuid,?::uuid,?::uuid,?::uuid,0)",tenant,hotel,position,item);
            Map<String,String> before = new HashMap<>();
            for (String table : List.of("store_resource_item","store_inventory_balance","store_inventory_movement","store_usage_quick_list","store_usage_quick_list_item"))
                before.put(table,jdbc.queryForObject("select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from " + table + " t",String.class));
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("77").load().migrate();
            for (String table : before.keySet()) {
                String projection = table.equals("store_resource_item") ? "to_jsonb(t)-'usage_scope_mode'" : "to_jsonb(t)";
                assertThat(jdbc.queryForObject("select jsonb_agg(" + projection + " order by (" + projection + ")::text)::text from " + table + " t",String.class)).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForObject("select usage_scope_mode from store_resource_item where id=?::uuid",String.class,item)).isEqualTo("LEGACY");
            for (String table : List.of("store_item_usage_department","store_item_usage_position")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table,Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            }
        }
    }
}
