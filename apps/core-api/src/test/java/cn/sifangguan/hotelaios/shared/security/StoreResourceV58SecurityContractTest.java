package cn.sifangguan.hotelaios.shared.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StoreResourceV58SecurityContractTest {

    private static final String MIGRATION =
            "/db/migration/V58__store_inventory_assets_and_handover.sql";

    @Test
    void migrationKeepsTenantIsolationAndImmutableBusinessFacts() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V58 migration must be packaged with the core API");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> tenantTables = List.of(
                "store_resource_item",
                "store_inventory_balance",
                "store_inventory_movement",
                "store_fixed_asset",
                "store_resource_handover",
                "store_resource_handover_snapshot_line",
                "store_resource_handover_action"
        );
        tenantTables.forEach(table -> {
            assertThat(sql).contains("CREATE TABLE " + table);
            assertThat(sql).contains("'" + table + "'");
        });

        assertThat(sql)
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("CREATE POLICY tenant_isolation")
                .contains("current_setting(''app.tenant_id'', true)")
                .doesNotContain("DISABLE ROW LEVEL SECURITY");

        assertThat(sql)
                .contains("trg_store_inventory_movement_immutable")
                .contains("trg_store_resource_handover_action_immutable")
                .contains("BEFORE UPDATE OR DELETE ON store_inventory_movement")
                .contains("BEFORE UPDATE OR DELETE ON store_resource_handover_action")
                .contains("trg_store_resource_snapshot_book_immutable")
                .contains("handover book snapshot is immutable");
    }

    @Test
    void migrationKeepsIdempotencyAndThreePartyHandoverGuards() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V58 migration must be packaged with the core API");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("UNIQUE (tenant_id, idempotency_key)")
                .contains("request_hash CHAR(64) NOT NULL")
                .contains("transferor_assignment_id <> recipient_assignment_id")
                .contains("transferor_assignment_id <> approver_assignment_id")
                .contains("recipient_assignment_id <> approver_assignment_id")
                .contains("trg_store_resource_handover_distinct_people")
                .contains("count(DISTINCT employee_id)")
                .contains("CREATE UNIQUE INDEX ux_store_resource_handover_one_open")
                .contains("CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))");

        for (String permission : List.of(
                "store-resource.read",
                "store-resource.item-manage",
                "store-resource.movement-create",
                "store-resource.asset-manage",
                "store-resource.handover-create",
                "store-resource.handover-confirm",
                "store-resource.handover-approve")) {
            assertThat(sql).contains("'" + permission + "'");
        }
    }
}
