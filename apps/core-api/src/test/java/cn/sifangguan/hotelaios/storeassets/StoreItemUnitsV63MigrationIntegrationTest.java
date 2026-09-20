package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreItemUnitsV63MigrationIntegrationTest {
    @Test
    void upgradePreservesLegacyLedgerAndInitializesUnitDefaultsWithoutWeakeningRls() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            var source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("62").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String itemId = UUID.randomUUID().toString();
            String movementId = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into store_resource_item (id, tenant_id, hotel_org_unit_id, sku, name, category, unit, unit_cost)
                    values (?::uuid, '10000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000003',
                        'LEGACY-UNIT', '旧牙刷', 'CONSUMABLE', '支', 0.5)
                    """, itemId);
            jdbc.update("""
                    insert into store_inventory_balance (tenant_id, hotel_org_unit_id, item_id, quantity, inventory_value)
                    values ('10000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000003', ?::uuid, 200, 100)
                    """, itemId);
            jdbc.update("""
                    insert into store_inventory_movement (id, tenant_id, hotel_org_unit_id, item_id, movement_type,
                        quantity_delta, quantity_before, quantity_after, unit_cost_snapshot, value_delta, reason,
                        idempotency_key, request_hash, actor_account_id)
                    values (?::uuid, '10000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000003',
                        ?::uuid, 'RECEIPT', 200, 0, 200, 0.5, 100, '旧版流水', 'legacy-key', repeat('0', 64),
                        '19000000-0000-0000-0000-000000000001')
                    """, movementId, itemId);
            String original = jdbc.queryForObject("select to_jsonb(m)::text from store_inventory_movement m where id = ?::uuid", String.class, movementId);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("63").load().migrate();
            var item = jdbc.queryForMap("select purchase_unit, purchase_factor, issue_unit, issue_factor from store_resource_item where id = ?::uuid", itemId);
            assertThat(item.get("purchase_unit")).isEqualTo("支");
            assertThat(item.get("issue_unit")).isEqualTo("支");
            assertThat((BigDecimal) item.get("purchase_factor")).isEqualByComparingTo("1");
            assertThat((BigDecimal) item.get("issue_factor")).isEqualByComparingTo("1");
            assertThat(jdbc.queryForObject("""
                    select (to_jsonb(m) - 'base_unit_snapshot' - 'input_unit' - 'input_quantity'
                        - 'conversion_factor' - 'input_unit_cost')::text from store_inventory_movement m where id = ?::uuid
                    """, String.class, movementId)).isEqualTo(original);
            assertThat(jdbc.queryForObject("select base_unit_snapshot is null from store_inventory_movement where id = ?::uuid", Boolean.class, movementId)).isTrue();
            assertThat(jdbc.queryForObject("select inventory_value from store_inventory_balance where item_id = ?::uuid", BigDecimal.class, itemId))
                    .isEqualByComparingTo("100");
            assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid = 'store_resource_item'::regclass", Boolean.class)).isTrue();
            assertThatThrownBy(() -> jdbc.update("update store_resource_item set unit = '盒' where id = ?::uuid", itemId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update store_resource_item set purchase_factor = 0 where id = ?::uuid", itemId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("""
                    insert into store_resource_item (tenant_id, hotel_org_unit_id, sku, name, category, unit)
                    values ('10000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000003',
                        'OLD-CLIENT-AFTER-UPGRADE', '旧客户端新建', 'CONSUMABLE', '个')
                    """);
            assertThat(jdbc.queryForObject("select purchase_unit from store_resource_item where sku = 'OLD-CLIENT-AFTER-UPGRADE'", String.class)).isEqualTo("个");
        }
    }
}
