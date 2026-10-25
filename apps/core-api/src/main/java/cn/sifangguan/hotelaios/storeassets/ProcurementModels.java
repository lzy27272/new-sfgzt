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
            @NotNull @Size(max=50) List<@NotNull UUID> receiverIds,Boolean requireFinancialDimensions) {
        public Configuration(long expectedVersion,boolean enabled,UUID managerId,UUID buyerId,UUID reviewerId,UUID financeId,UUID financeDirectorId,UUID deputyId,UUID generalId,List<UUID> receiverIds){this(expectedVersion,enabled,managerId,buyerId,reviewerId,financeId,financeDirectorId,deputyId,generalId,receiverIds,null);}
    }
    public record Replenishment(long expectedVersion, boolean enabled,
            @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal targetQuantity) { }
    public record Line(UUID itemId, @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                       @NotBlank @Size(max=32) String unit,@Pattern(regexp="INVENTORY|SERVICE|ASSET|PROJECT") String lineKind,
                       @Size(max=160) String description,@Size(max=240) String specification,
                       @Size(max=80) String financialCategoryCode,UUID departmentId,@DecimalMin("0") BigDecimal budgetUnitPrice,@Pattern(regexp="NONE|CONTRACT|CIP") String projectKind,UUID projectId) {
        public Line(UUID itemId,BigDecimal quantity,String unit){this(itemId,quantity,unit,"INVENTORY",null,null,null,null,null,null,null);}
        public Line(UUID itemId,BigDecimal quantity,String unit,String lineKind,String description,String specification){this(itemId,quantity,unit,lineKind,description,specification,null,null,null,null,null);}
        public Line(UUID itemId,BigDecimal quantity,String unit,String lineKind,String description,String specification,String financialCategoryCode,UUID departmentId){this(itemId,quantity,unit,lineKind,description,specification,financialCategoryCode,departmentId,null,null,null);}
        public Line(UUID itemId,BigDecimal quantity,String unit,String lineKind,String description,String specification,String financialCategoryCode,UUID departmentId,BigDecimal budgetUnitPrice){this(itemId,quantity,unit,lineKind,description,specification,financialCategoryCode,departmentId,budgetUnitPrice,null,null);}
    }
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
