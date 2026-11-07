package cn.sifangguan.hotelaios.operations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OperationsPlanModels {
    private OperationsPlanModels() { }
    public record Item(UUID actionId, UUID hotelId, UUID assigneeAssignmentId, String title,
            String description, OffsetDateTime dueAt, String acceptanceCriteria,
            UUID linkedTaskId, UUID linkedExpectationId) { }
    public record Draft(String summary, String objective, String risk,
            Map<String,String> hotelSummaries, List<Item> items, UUID expectedRevisionId, Long expectedDraftVersion) { }
    public record Review(String decision, String comment, UUID expectedRevisionId) { }
}
