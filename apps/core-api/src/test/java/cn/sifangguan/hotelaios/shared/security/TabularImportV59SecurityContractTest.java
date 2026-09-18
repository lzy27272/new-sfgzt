package cn.sifangguan.hotelaios.shared.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TabularImportV59SecurityContractTest {

    private static final String MIGRATION =
            "/db/migration/V59__tabular_import_foundation_and_store_initialization.sql";

    @Test
    void importJobsAndResultLinksRemainTenantIsolatedAndTraceable() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V59 migration must be packaged with the core API");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE tabular_import_job")
                .contains("CREATE TABLE tabular_import_result_link")
                .contains("ALTER TABLE tabular_import_job FORCE ROW LEVEL SECURITY")
                .contains("ALTER TABLE tabular_import_result_link FORCE ROW LEVEL SECURITY")
                .contains("CREATE POLICY tenant_isolation ON tabular_import_job")
                .contains("CREATE POLICY tenant_isolation ON tabular_import_result_link")
                .contains("ux_tabular_import_job_file")
                .contains("trg_tabular_import_result_link_immutable")
                .contains("BEFORE UPDATE OR DELETE ON tabular_import_result_link")
                .doesNotContain("DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void openingImportHasExplicitLedgerSemanticsAndSeparatePermissions() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V59 migration must be packaged with the core API");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("movement_type IN ('OPENING', 'RECEIPT', 'CONSUMPTION', 'RETURN', 'DAMAGE', 'ADJUSTMENT')")
                .contains("store_inventory_movement_direction_check")
                .contains("movement_type IN ('OPENING', 'RECEIPT', 'RETURN') AND quantity_delta > 0")
                .contains("'store-resource.import-preview'")
                .contains("'store-resource.import-apply'");
    }
}
