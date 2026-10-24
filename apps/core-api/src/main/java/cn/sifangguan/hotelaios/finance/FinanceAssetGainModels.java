package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
public final class FinanceAssetGainModels {
 private FinanceAssetGainModels(){}
 public record Draft(@NotNull UUID requestId,@NotNull UUID bookId,@NotNull UUID physicalAssetId,
  @NotNull UUID departmentId,@NotBlank String financialCategoryCode,@NotNull LocalDate occurredOn,@NotNull LocalDate accountingOn,
  @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal value,
  @NotBlank String assetAccount,@NotBlank String creditAccount,
  @NotBlank @Size(max=2000) String valuationBasis,@NotBlank @Size(max=2000) String policyReference){}
 public record Command(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotBlank @Size(max=2000) String note){}
 public record Review(@NotNull UUID requestId,@Min(0) int expectedVersion,boolean approved,@NotBlank @Size(max=2000) String note){}
 public record Card(@NotNull UUID requestId,@NotBlank @Size(max=80) String assetCode,
  @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal residualValue,@Min(1) @Max(1200) int usefulMonths,
  @NotNull LocalDate inServiceDate,@NotBlank String cipAccount,@NotBlank String depreciationAccount,@NotBlank String expenseAccount){}
}
