package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class FinanceBusinessModels {
 private FinanceBusinessModels() {}
 public record Confirm(@NotNull UUID requestId,int expectedVersion,@NotBlank @Size(max=2000) String note) {}
 public record RevenueRow(@NotBlank @Size(max=160) String externalId,@NotBlank String kind,@NotBlank @Size(max=160) String customer,@NotNull LocalDate dueDate,@NotNull BigDecimal amount,@NotNull BigDecimal netAmount,@NotNull BigDecimal taxAmount,@NotNull BigDecimal commission,@NotNull BigDecimal fee) {}
 public record RevenueImport(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank String source,@NotBlank @Size(max=100) String channel,@NotNull LocalDate businessDate,@NotNull LocalDate nightAuditDate,@Min(1) int revision,@Min(1) int expectedCount,@NotNull BigDecimal controlTotal,@NotBlank @Size(max=240) String sourceReference,@NotNull UUID reviewerId,boolean nightAuditClosed,@NotEmpty @Size(max=5000) List<@Valid RevenueRow> rows) {}
 public record RevenueMovement(@NotNull UUID requestId,@NotBlank String kind,@NotNull BigDecimal amount,@NotNull LocalDate occurredOn,@NotBlank @Size(max=240) String bankReference,@NotBlank @Size(max=2000) String note,UUID relatedEntryId) {
  public RevenueMovement(UUID requestId,String kind,BigDecimal amount,LocalDate occurredOn,String bankReference,String note){this(requestId,kind,amount,occurredOn,bankReference,note,null);}
 }
 public record PayrollLine(@NotNull UUID employeeId,@NotNull UUID departmentId,@NotEmpty Map<String,BigDecimal> earnings,@NotNull Map<String,BigDecimal> deductions,@NotNull Map<String,BigDecimal> employerCosts) {}
 public record PayrollBatch(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank String month,@NotBlank @Size(max=240) String sourceReference,UUID supplementsId,@NotNull UUID reviewerId,@NotNull UUID cashierId,@NotEmpty @Size(max=2000) List<@Valid PayrollLine> lines) {}
 public record PayrollPayment(@NotNull UUID requestId,@NotNull UUID lineId,@NotNull BigDecimal amount,@NotNull LocalDate occurredOn,@NotBlank @Size(max=240) String bankReference) {}
 public record Basis(@NotBlank String key,@NotNull BigDecimal weight,@NotBlank String reference) {}
 public record Intercompany(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull UUID counterpartyHotelId,@NotBlank String month,@NotBlank String kind,@NotNull BigDecimal amount,@NotBlank @Size(max=240) String basisReference,@NotEmpty @Size(max=1000) List<@Valid Basis> basis,@NotNull UUID reviewerId,@NotNull UUID counterpartyReviewerId) {}
 public record EliminationRule(@NotBlank String debitReportLine,@NotBlank String creditReportLine,@NotBlank String transactionKind) {}
 public record ConsolidationScope(@NotNull UUID requestId,@NotBlank @Size(max=160) String name,@Min(1) int version,@NotEmpty List<UUID> hotelIds,@NotEmpty List<@Valid EliminationRule> rules) {}
 public record Worksheet(@NotNull UUID requestId,@NotNull UUID scopeId,@NotBlank String month) {}
 public record InvoiceAllocation(@NotNull UUID entryId,@NotNull BigDecimal amount) {}
 public record OutputRequest(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank @Size(max=160) String buyerName,@NotBlank @Size(max=80) String buyerTaxNumber,@NotEmpty List<@Valid InvoiceAllocation> lines) {}
 public record InvoiceResult(@NotNull UUID requestId,int expectedVersion,@NotBlank @Size(max=40) String invoiceCode,@NotBlank @Size(max=80) String invoiceNumber,@NotNull BigDecimal netAmount,@NotNull BigDecimal taxAmount,@NotNull LocalDate issuedOn,UUID redOf,@NotNull UUID attachmentId,@NotBlank @Size(max=240) String externalReference) {}
 public record TaxField(@NotBlank String code,@NotBlank String label,@NotBlank String source,@NotNull BigDecimal factor) {}
 public record TaxPolicy(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank @Size(max=160) String name,@Min(1) int version,@NotNull LocalDate effectiveFrom,@NotNull LocalDate effectiveUntil,@NotBlank @Size(max=1000) String officialReference,@NotEmpty List<@Valid TaxField> fields) {}
 public record InputTax(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull UUID invoiceId,@NotBlank String month,@NotBlank String purpose,@NotNull BigDecimal eligibleTax,@NotNull BigDecimal claimedTax,@NotNull BigDecimal transferOut,@NotNull UUID policyId,@NotBlank @Size(max=2000) String note) {}
 public record TaxReturn(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank String month,@NotNull UUID policyId,BigDecimal declaredAmount,@Size(max=2000) String explanation) {}
 public record TaxReceipt(@NotNull UUID requestId,@NotBlank String kind,@NotBlank @Size(max=240) String externalReference,@NotNull BigDecimal amount,@NotNull UUID attachmentId,@NotNull LocalDate occurredOn) {}
 public record CashAllocation(@NotBlank String sourceType,@NotNull UUID sourceId,@NotNull BigDecimal amount) {}
 public record BankMatch(@NotNull UUID requestId,@NotNull UUID transactionId,@NotEmpty List<@Valid CashAllocation> allocations,@NotBlank @Size(max=2000) String note) {}
 public record OpeningClear(@NotNull UUID requestId,@NotNull BigDecimal amount,@NotNull LocalDate occurredOn,@NotBlank @Size(max=240) String bankReference) {}
 public record IntercompanySettlement(@NotNull UUID requestId,@NotBlank String side,@NotNull BigDecimal amount,@NotNull LocalDate occurredOn,@NotBlank @Size(max=240) String bankReference) {}
 public record MatchUndo(@NotNull UUID matchId,@NotNull BigDecimal amount) {}
 public record CashCorrection(@NotNull UUID requestId,@NotBlank String sourceType,@NotNull UUID sourceId,@NotNull BigDecimal amount,@NotNull LocalDate accountingDate,@NotBlank @Size(max=2000) String note,@NotNull List<@Valid MatchUndo> matchingReversals) {}
}
