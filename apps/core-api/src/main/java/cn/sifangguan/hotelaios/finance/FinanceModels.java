package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FinanceModels {
    private FinanceModels() { }
    public record StoreConfig(@NotBlank @Size(max=160) String companyName,
            @NotBlank @Pattern(regexp="[A-Za-z0-9]{15,20}") String taxNumber,
            UUID managerAccountId, UUID financeAccountId, UUID directorAccountId, UUID leaderAccountId, UUID cashierAccountId,
            @Size(max=120) String leaderName, @NotNull @Size(max=120) String payableAccount,
            boolean skipSelfReview, boolean enabled, @Min(-1) int expectedVersion) { }
    public record ConfigTarget(@NotNull UUID hotelId, @Min(-1) int expectedVersion) { }
    public record ConfigureBatch(@NotNull @Size(min=1,max=100) List<@NotNull @Valid ConfigTarget> targets,
            @NotNull @Valid StoreConfig configuration) { }
    public record Category(@NotBlank @Size(max=80) String name,
            @Pattern(regexp="EXPENSE|INVENTORY|ASSET|PREPAID|ALREADY_RECORDED") @NotNull String treatment,
            @NotNull @Size(max=500) String keywords, @NotNull @Size(max=120) String debitAccount,
            boolean enabled, @Min(-1) int expectedVersion,@Size(max=80) String budgetCode) {
        public Category(String name,String treatment,String keywords,String debitAccount,boolean enabled,int expectedVersion){this(name,treatment,keywords,debitAccount,enabled,expectedVersion,null);}
    }
    public record Create(@NotNull UUID requestId, @NotNull UUID assignmentId) { }
    public record Invoice(@NotNull UUID id, @NotNull UUID attachmentId,
            @NotNull @Size(max=20) String invoiceCode, @NotNull @Size(max=24) String invoiceNumber,
            @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
            @NotNull @Size(max=32) String buyerTaxNumber, boolean confirmed) { }
    public record Draft(@Min(0) int expectedVersion, @NotNull @Size(max=160) String title,
            @NotNull LocalDate businessDate, UUID categoryId,
            @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal transactionAmount,
            @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal paymentAmount,
            @NotNull @DecimalMin("0") @Digits(integer=14,fraction=2) BigDecimal requestedAmount,
            @NotNull @Size(max=2000) String differenceNote,
            @NotNull @Size(max=30) List<@Valid Invoice> invoices,
            @Size(max=100) List<@NotNull @Valid Line> items) {
        public Draft(int expectedVersion,String title,LocalDate businessDate,UUID categoryId,BigDecimal transactionAmount,
                BigDecimal paymentAmount,BigDecimal requestedAmount,String differenceNote,List<Invoice> invoices) {
            this(expectedVersion,title,businessDate,categoryId,transactionAmount,paymentAmount,requestedAmount,differenceNote,invoices,null);
        }
    }
    public record Line(@NotNull UUID id,UUID categoryId,@NotNull @Size(max=500) String description,
            @DecimalMin("0.0001") @Digits(integer=12,fraction=4) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer=14,fraction=4) BigDecimal unitPrice,
            @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
            UUID departmentId,LocalDate allocationMonth,@NotNull @Size(max=30) List<@NotNull UUID> invoiceIds,
            @Pattern(regexp="NONE|CONTRACT|CIP") String projectKind,UUID projectId) {
        public Line(UUID id,UUID categoryId,String description,BigDecimal quantity,BigDecimal unitPrice,BigDecimal amount,UUID departmentId,LocalDate allocationMonth,List<UUID> invoiceIds){this(id,categoryId,description,quantity,unitPrice,amount,departmentId,allocationMonth,invoiceIds,null,null);}
    }
    public record Verification(@NotBlank @Size(max=2000) String note) { }
    public record Receipt(@NotNull UUID attachmentId) { }
    public record IntakeDraft(@Min(0) int expectedVersion,@NotNull @Size(max=20) String invoiceCode,
            @NotNull @Size(max=24) String invoiceNumber,@DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
            @NotNull @Size(max=32) String buyerTaxNumber,boolean confirmed) { }
    public record Associate(@NotNull UUID claimId,@Min(0) int expectedVersion) { }
    public record Version(@Min(0) int expectedVersion) { }
    public record Review(@NotNull UUID requestId, @Min(0) int expectedVersion,
            @NotNull @Pattern(regexp="APPROVE|RETURN|REJECT") String decision,
            @NotBlank @Size(max=2000) String note, UUID categoryId,
            @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal approvedAmount,
            boolean acknowledgeDifference) { }
    public record Payment(@NotNull UUID requestId, @Min(0) int expectedVersion,
            @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal amount,
            @NotBlank @Size(max=160) String reference, @NotNull LocalDate paidOn) { }
    public record Historical(@NotNull @Size(min=1,max=500) List<@Valid HistoricalInvoice> invoices) { }
    public record HistoricalInvoice(@NotNull @Size(max=20) String invoiceCode,
            @NotBlank @Size(max=24) String invoiceNumber, @NotBlank @Size(max=160) String reference) { }
}
