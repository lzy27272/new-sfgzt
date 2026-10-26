package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public final class FinanceTaxModels {
 private FinanceTaxModels(){}
 public record Basis(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull @Pattern(regexp="CLAIM|PROCUREMENT|PROCUREMENT_RED") String sourceKind,@NotNull UUID invoiceId,UUID sourceParentId,UUID redOfBasisId,@NotNull @DecimalMin("0") BigDecimal netAmount,@NotNull @DecimalMin("0") BigDecimal taxAmount,@NotNull @DecimalMin("0") BigDecimal eligibleTax,@NotBlank @Size(max=500) String purpose,@NotNull UUID reviewerId,@NotBlank @Size(max=2000) String note){}
 public record Review(@Min(0) int expectedVersion,boolean approved,@NotBlank @Size(max=2000) String note){}
 public record Decision(@NotNull UUID requestId,@NotNull UUID basisId,@NotBlank String month,@NotNull UUID policyId,@NotNull @DecimalMin("0") BigDecimal claimedTax,@NotNull @DecimalMin("0") BigDecimal transferOut,@NotBlank @Size(max=500) String purpose,@NotBlank @Size(max=2000) String note){}
 public record Accrual(@NotNull UUID requestId,@NotNull UUID returnId,@NotBlank @Size(min=64,max=64) String sourceHash,@NotBlank @Size(max=100) String taxKind,@NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull LocalDate accountingOn,@NotNull UUID reviewerId,@NotBlank @Size(max=2000) String note){}
 public record Payment(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotNull UUID taxReceiptId,@NotNull UUID bankTransactionId,@NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull LocalDate accountingOn,@NotBlank @Size(max=2000) String note){}
}
