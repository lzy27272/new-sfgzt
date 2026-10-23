package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class FinanceOperationsModels {
    private FinanceOperationsModels() { }
    public record Line(@NotBlank @Size(max=240) String description,
            @NotNull @DecimalMin("0.000001") @Digits(integer=10,fraction=6) BigDecimal quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer=14,fraction=4) BigDecimal unitPrice,
            @Size(max=80) String categoryCode, UUID departmentId) { }
    public record Document(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotNull UUID hotelId,
            @NotNull @Pattern(regexp="CONTRACT|PAYABLE|LOAN|PREPAYMENT|ASSET|PAYROLL|REVENUE|INTERCOMPANY|TAX_TASK") String type,
            @NotBlank @Size(max=160) String title,@NotNull LocalDate businessDate,LocalDate dueDate,UUID partyId,
            @Size(max=80) String sourceType,UUID sourceId,@Size(max=240) String sourceReference,UUID budgetId,
            @NotNull @Size(max=40) Map<String,Object> details,
            @NotNull @Size(min=1,max=500) List<@NotNull @Valid Line> lines) { }
    public record Version(@NotNull UUID requestId,@Min(0) int expectedVersion) { }
    public record Review(@NotNull UUID requestId,@Min(0) int expectedVersion,
            @NotNull @Pattern(regexp="APPROVE|RETURN|REJECT") String decision,@NotBlank @Size(max=2000) String note) { }
    public record Settlement(@NotNull UUID requestId,@Min(0) int expectedVersion,
            @NotNull @Pattern(regexp="PAYMENT|RECEIPT|REFUND|REPAYMENT|WRITEOFF|DEPRECIATE|AMORTIZE|DISPOSE|COUNTERSIGN|ISSUE_INVOICE|RED_INVOICE|TAX_FILE") String action,
            @NotNull @DecimalMin("0.00") @Digits(integer=14,fraction=2) BigDecimal amount,@NotNull LocalDate occurredOn,
            UUID bankAccountId,UUID bankTransactionId,@NotBlank @Size(max=240) String reference,
            @Size(max=2000) String note,@Size(max=240) String receiptReference,UUID relatedDocumentId) { }
    public record Party(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank @Size(max=160) String name,
            @NotNull @Pattern(regexp="SUPPLIER|CUSTOMER|EMPLOYEE") String kind,@Size(max=32) String taxNumber,
            @Size(max=160) String bankName,@Size(max=80) String bankAccount,@Min(0) int expectedVersion) { }
    public record BankAccount(@NotNull UUID requestId,@NotNull UUID hotelId,@NotBlank @Size(max=160) String name,
            @NotBlank @Size(max=80) String accountNumber,@NotBlank @Size(max=160) String bankName,
            @NotNull LocalDate openingDate,@NotNull @Digits(integer=14,fraction=2) BigDecimal openingBalance) { }
    public record BankRow(@NotBlank @Size(max=160) String externalId,@NotNull LocalDate occurredOn,
            @NotNull @Pattern(regexp="IN|OUT") String direction,@NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
            @NotNull @Size(max=160) String counterparty,@NotNull @Size(max=240) String reference) { }
    public record BankImport(@NotNull UUID bankAccountId,@NotNull UUID batchId,@NotNull @Size(min=1,max=1000) List<@NotNull @Valid BankRow> rows) { }
    public record Allocation(@NotNull UUID settlementId,@NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount) { }
    public record Match(@NotNull UUID requestId,@NotNull @Size(min=1,max=200) List<@NotNull @Valid Allocation> allocations,
            @NotNull @Size(max=2000) String note) { }
    public record BankExplanation(@NotNull UUID requestId,@Min(-1) int expectedVersion,@NotBlank @Size(max=2000) String reason) { }
    public record Budget(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull @Pattern(regexp="\\d{4}-\\d{2}") String month,
            @NotBlank @Size(max=80) String categoryCode,@NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal amount,
            @NotNull @Pattern(regexp="WARN|BLOCK") String control,@Min(0) int expectedVersion) { }
}
