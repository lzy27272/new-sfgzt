package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class FinanceAccountingModels {
 private FinanceAccountingModels() {}
 public record Book(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull UUID companyId,@NotBlank @Size(max=160) String name,@NotNull LocalDate startMonth) {}
 public record Account(@NotNull UUID bookId,@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,32}") String code,@NotBlank @Size(max=120) String name,
   @NotNull @Pattern(regexp="ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE") String category,@NotNull @Pattern(regexp="DEBIT|CREDIT") String normalSide,
   @NotBlank @Size(max=80) String reportLine,boolean cashAccount,boolean enabled,@Min(-1) int expectedVersion,List<@Pattern(regexp="DEPARTMENT|EMPLOYEE|PARTY|PROJECT") String> requiredDimensions) {
  public Account(UUID bookId,String code,String name,String category,String normalSide,String reportLine,boolean cashAccount,boolean enabled,int expectedVersion){this(bookId,code,name,category,normalSide,reportLine,cashAccount,enabled,expectedVersion,List.of());}
  public Account{requiredDimensions=requiredDimensions==null?List.of():List.copyOf(requiredDimensions);if(new HashSet<>(requiredDimensions).size()!=requiredDimensions.size())throw new IllegalArgumentException("辅助维度不能重复");}
 }
 public record Line(@NotBlank @Size(max=32) String accountCode,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal debit,
   @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal credit,@NotNull @Size(max=500) String summary,
   @Pattern(regexp="OPERATING|INVESTING|FINANCING|TRANSFER") String cashFlowCategory,Map<String,Object> auxiliary) {
  public Line(String accountCode,BigDecimal debit,BigDecimal credit,String summary,String cashFlowCategory){this(accountCode,debit,credit,summary,cashFlowCategory,Map.of());}
  public Line{auxiliary=auxiliary==null?Map.of():Map.copyOf(auxiliary);}
 }
 public record Voucher(@NotNull UUID requestId,@NotNull UUID bookId,@NotNull LocalDate businessDate,@NotNull LocalDate accountingDate,
   @NotBlank @Size(max=500) String summary,@NotNull @Size(min=2,max=500) List<@NotNull @Valid Line> lines) {}
 public record Version(@Min(0) int expectedVersion) {}
 public record Reverse(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotNull LocalDate accountingDate,@NotBlank @Size(max=500) String reason) {}
 public record Period(@NotNull UUID bookId,@NotNull @Pattern(regexp="\\d{4}-\\d{2}") String month,@Min(0) int expectedVersion,@NotBlank @Size(max=2000) String reason) {}
 public record Rule(@NotNull UUID bookId,@NotBlank @Size(max=60) String sourceType,@NotBlank @Size(max=120) String eventKey,
   @NotBlank @Size(max=32) String debitAccountCode,@NotBlank @Size(max=32) String creditAccountCode,
   @Pattern(regexp="OPERATING|INVESTING|FINANCING|TRANSFER") String cashFlowCategory,boolean enabled,@Min(-1) int expectedVersion) {}
 public record SourceCheck(@NotNull UUID bookId,@NotNull @Pattern(regexp="\\d{4}-\\d{2}") String month,@NotBlank @Size(max=60) String sourceType,
   boolean complete,@Min(0) Integer expectedCount,@Min(0) Integer actualCount,@NotBlank @Size(max=2000) String note,@Min(-1) int expectedVersion) {}
 public record Opening(@NotBlank @Size(max=32) String accountCode,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal debit,
   @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal credit) {}
 public record Import(@NotNull UUID requestId,@NotNull UUID bookId,@NotNull @Pattern(regexp="PREVIEW|COMMIT") String mode,
   @NotBlank @Size(max=160) String sourceReference,@NotNull @Size(min=1,max=2000) List<@NotNull @Valid Opening> balances,
   @NotNull Map<@Size(max=80) String,@Size(max=2000) String> reconciliation) {}
 public record YearClose(@NotNull UUID requestId,@NotNull UUID bookId,@Min(2000) @Max(9998) int year,
   @NotBlank @Size(max=32) String retainedEarningsAccountCode,@NotBlank @Size(max=500) String summary) {}
 public record RecurringPlan(@NotNull UUID requestId,@NotNull UUID bookId,
   @NotNull @Pattern(regexp="DEPRECIATION|AMORTIZATION|ALLOCATION|ACCRUAL") String kind,
   @NotBlank @Size(max=60) String sourceType,@NotNull UUID sourceId,@NotBlank @Size(max=240) String sourceReference,
   @NotNull @Pattern(regexp="\\d{4}-\\d{2}") String startMonth,@NotNull @Pattern(regexp="\\d{4}-\\d{2}") String endMonth,
   @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal monthlyAmount,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal totalAmount,
   @NotBlank @Size(max=32) String debitAccountCode,@NotBlank @Size(max=32) String creditAccountCode,@NotBlank @Size(max=500) String summary) {}
 public record RecurringRun(@NotNull UUID requestId,@NotNull @Pattern(regexp="\\d{4}-\\d{2}") String month,@Min(0) int expectedVersion) {}
 public record RecurringActive(boolean active,@Min(0) int expectedVersion,@NotBlank @Size(max=500) String reason) {}
}
