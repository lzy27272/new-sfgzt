package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProcurementModels {
    private ProcurementModels() { }
    public record Configuration(long expectedVersion, boolean enabled,
            @NotNull UUID managerId, @NotNull UUID buyerId, @NotNull UUID reviewerId,
            @NotNull UUID financeId, @NotNull UUID financeDirectorId, @NotNull UUID deputyId, @NotNull UUID generalId,
            @NotNull @Size(max=50) List<@NotNull UUID> receiverIds) { }
    public record Replenishment(long expectedVersion, boolean enabled,
            @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal targetQuantity) { }
    public record Line(@NotNull UUID itemId, @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                       @NotBlank @Size(max=32) String unit) { }
    public record Request(@NotNull UUID commandId, long expectedVersion,
                          @NotBlank @Size(max=160) String title, @Size(max=2000) String note,
                          LocalDate expectedOn, @NotEmpty @Size(max=200) List<@Valid Line> lines) { }
    public record Command(@NotNull UUID commandId, long expectedVersion, @Size(max=2000) String note) { }
    public record Price(@NotNull UUID lineId, @NotNull @DecimalMin("0") BigDecimal unitPrice) { }
    public record Purchase(@NotNull UUID commandId, long expectedVersion, @NotBlank @Size(max=200) String supplier,
                           @NotNull LocalDate expectedOn, @NotNull UUID buyerId,
                           @NotEmpty @Size(max=200) List<@Valid Price> prices) { }
    public record ReceiptLine(@NotNull UUID lineId, @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal arrived,
                              @NotNull @DecimalMin("0") BigDecimal accepted, @NotNull @DecimalMin("0") BigDecimal rejected) { }
    public record Receipt(@NotNull UUID commandId, long expectedVersion, @Size(max=2000) String note,
                          @NotEmpty @Size(max=200) List<@Valid ReceiptLine> lines) { }
    public record Review(@NotNull UUID commandId, long expectedVersion, boolean approved, @Size(max=2000) String note) { }
}
