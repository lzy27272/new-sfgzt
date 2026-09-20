package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.dailyreporttemplates.DailyReportTemplateSnapshotReader;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DailyReportSummaryFactsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DailyReportRoutineProjectionService service = new DailyReportRoutineProjectionService(
            mock(NamedParameterJdbcTemplate.class), mock(TenantDatabaseContext.class), mapper,
            mock(DailyReportTemplateSnapshotReader.class), mock(AuditWriter.class));

    @Test void deduplicatesReportReferencesAndSeparatesPendingApprovedAndReversedQuantities() throws Exception {
        var result = service.summarizeWorkResults(List.of(
                row("usage-work", "ledger-1", "APPROVED", "2.75"),
                row("daily-report-work", "ledger-1", "APPROVED", "2.75"),
                row("other-work", "ledger-2", "PENDING", "0.35"),
                row("reversed-work", "ledger-3", "REVERSED", "100")));
        assertThat(result.path("workItems").size()).isEqualTo(4);
        assertThat(result.path("usageRecordCount").asInt()).isEqualTo(3);
        assertThat(result.path("excludedUsageRecordCount").asInt()).isEqualTo(1);
        assertThat(result.path("usageItems").size()).isEqualTo(1);
        assertThat(result.path("usageItems").get(0).path("approvedQuantity").asText()).isEqualTo("2.75");
        assertThat(result.path("usageItems").get(0).path("pendingQuantity").asText()).isEqualTo("0.35");
    }

    @Test void zeroRegisteredUsageAndNoSubmissionAreDifferentFacts() throws Exception {
        var empty = service.summarizeWorkResults(List.of());
        assertThat(empty.path("usageRecordCount").asInt()).isZero();
        var row = row("work", "ledger", "APPROVED", "0");
        var value = mapper.readTree((String) row.get("value"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("inventoryUsage")).putArray("lines");
        var declared = service.summarizeWorkResults(List.of(Map.of("label", "已登记无耗用", "value", value.toString(), "source", "{}")));
        assertThat(declared.path("usageItems").isEmpty()).isTrue();
        assertThat(declared.path("usageRecordCount").asInt()).isEqualTo(1);
    }

    private Map<String, Object> row(String record, String ledger, String status, String quantity) throws Exception {
        return Map.of("label", "耗用工作", "source", "{\"evidenceCount\":1}", "value", mapper.writeValueAsString(Map.of(
                "workRecordId", record, "status", "APPROVED", "completion", "已核对数量", "formData", Map.of(),
                "inventoryUsage", Map.of("usageRecordId", ledger, "status", status, "lines", List.of(Map.of(
                        "itemId", "cleaner", "itemName", "清洁液", "category", "CLEANING_SUPPLY", "unit", "毫升", "quantity", quantity))))));
    }
}
