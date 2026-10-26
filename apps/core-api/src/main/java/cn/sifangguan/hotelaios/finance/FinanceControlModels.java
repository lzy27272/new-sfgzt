package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class FinanceControlModels {
 private FinanceControlModels(){}
 public record Version(@Min(0) int expectedVersion,@NotBlank @Size(max=2000) String note){}
 public record Policy(@NotNull UUID requestId,@NotNull UUID hotelId,
  @Pattern(regexp="ENTITY|TRAVEL|WORKFLOW|CASH_PLAN|RETENTION|CATEGORY|BUDGET") @NotNull String kind,
  @NotBlank @Size(max=80) String code,@NotBlank @Size(max=160) String title,@NotNull LocalDate effectiveFrom,LocalDate effectiveTo,
  @NotNull @Size(min=1,max=30) Map<String,Object> spec){}
 public record ContractNode(@NotBlank @Size(max=80) String code,@NotNull LocalDate date,
  @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,boolean releaseRetainage){}
 public record Contract(@NotNull UUID requestId,@Min(0) int sourceVersion,
  @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
  @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal retainage,
  @NotNull @Size(min=1,max=120) List<@Valid ContractNode> nodes,@NotBlank @Size(max=2000) String reason){}
 public record Cash(@NotNull UUID requestId,@NotNull @Pattern(regexp="CLAIM|OPERATION") String sourceKind,
  @NotNull UUID sourceId,@Min(0) int sourceVersion,@NotNull @Pattern(regexp="PAYMENT|RECEIPT|REFUND|REPAYMENT") String action,
  @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,@NotNull LocalDate occurredOn,
  @NotNull UUID bankAccountId,UUID beneficiaryId,@Size(max=80) String contractNode,
  @NotBlank @Size(max=160) String reference,@NotBlank @Size(max=2000) String note){}
 public record Batch(@NotNull UUID requestId,@NotBlank @Size(max=160) String reference,
  @NotNull @Size(min=1,max=100) List<@NotNull UUID> instructionIds){}
 public record Travel(@Min(0) int expectedVersion,@NotBlank @Size(max=160) String destination,
  @NotNull LocalDate tripStart,@NotNull LocalDate tripEnd,@NotNull @Size(min=1,max=100) List<@NotNull UUID> travelerIds,
  @Min(0) @Max(3660) int hotelNights,@NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal lodgingAmount,
  @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal transportAmount,
  @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal mealAmount,
  @NotBlank @Size(max=2000) String purpose,UUID policyId,@NotNull @Size(max=2000) String exceptionNote){}
 public record ArchiveNote(@NotNull UUID requestId,@NotNull @Pattern(regexp="CLAIM|OPERATION|VOUCHER") String sourceKind,
  @NotNull UUID sourceId,UUID previousId,@NotBlank @Size(max=4000) String note){}
 public record ImportCommit(@Min(0) int expectedVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String fileHash){}
 public record ClaimMatch(@NotNull UUID requestId,@NotNull UUID instructionId,@NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,@NotBlank @Size(max=2000) String note){}
 public record LoanClear(@NotNull UUID requestId,@NotNull UUID claimId,@Min(0) int loanVersion,@Min(0) int claimVersion,
  @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,@NotNull LocalDate occurredOn,@NotBlank @Size(max=2000) String note){}
 public record AccountProposal(@NotNull UUID requestId,@NotNull @Valid FinanceAccountingModels.Account account,@NotBlank @Size(max=2000) String reason){}
 public record RuleProposal(@NotNull UUID requestId,@NotNull @Valid FinanceAccountingModels.Rule rule,@NotBlank @Size(max=2000) String reason){}
}
