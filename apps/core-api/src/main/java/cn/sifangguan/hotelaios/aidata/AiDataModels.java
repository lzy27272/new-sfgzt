package cn.sifangguan.hotelaios.aidata;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AiDataModels {
    private AiDataModels() {}
    public record Query(@NotBlank String dataset, @NotNull LocalDate from, @NotNull LocalDate to,
                        UUID orgUnitId, UUID bindingId, @Min(0) @Max(100000) int offset) {}
    public record Connection(@NotBlank @Size(max=80) String name, @Min(1) @Max(30) int days,
                             boolean allowReportWrite) {}
    public record Report(@NotNull UUID requestId, @NotBlank @Size(max=160) String title,
                         @NotBlank @Size(max=60000) String body, @NotNull LocalDate from, @NotNull LocalDate to,
                         @NotEmpty @Size(max=8) List<UUID> snapshotIds,
                         @NotBlank @Size(max=120) String authoringTool) {}
}
