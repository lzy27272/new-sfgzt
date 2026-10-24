package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public final class FinanceSupplyModels {
 private FinanceSupplyModels(){}
 public record InvoiceLine(@NotNull UUID lineId,@NotNull @DecimalMin("0.0001") @Digits(integer=14,fraction=4) BigDecimal quantity,@NotNull @DecimalMin("0") @Digits(integer=14,fraction=4) BigDecimal unitPrice){}
 public record Match(@NotNull UUID requestId,@NotNull UUID receiptId,@Min(0) int expectedVersion,boolean estimate,@NotNull @Size(max=100) List<UUID> invoiceIds,@NotNull @Size(max=200) List<@Valid InvoiceLine> lines,@NotNull LocalDate accountingDate,@NotBlank @Size(max=2000) String note){}
 public record Confirm(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotBlank @Size(max=2000) String note){}
 public record Return(@NotNull UUID requestId,@NotNull UUID recognitionId,@NotNull UUID lineId,@NotNull @DecimalMin("0.0001") @Digits(integer=14,fraction=4) BigDecimal quantity,@Pattern(regexp="RETURN|EXCHANGE") String kind,@NotNull LocalDate accountingDate,@NotBlank @Size(max=2000) String note){}
 public record ReturnReview(@NotNull UUID requestId,@Min(0) int expectedVersion,boolean approved,@NotBlank @Size(max=2000) String note){}
 public record Settlement(@NotNull UUID requestId,@Pattern(regexp="REFUND|CREDIT|REPLACEMENT") String kind,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal amount,UUID bankTransactionId,UUID targetRecognitionId,UUID replacementReceiptId,@NotBlank @Size(max=2000) String note){}
 public record Sync(@NotNull UUID hotelId,@NotNull LocalDate throughDate,@Min(1) @Max(500) int limit){}
 public record Payable(@NotNull UUID requestId,@NotNull UUID partyId,@NotNull LocalDate dueDate,@NotBlank @Size(max=2000) String note){}
 public record PhysicalTransfer(@NotNull UUID requestId,@NotNull UUID physicalAssetId,@NotNull UUID targetHotelId,@Min(0) long expectedVersion,@NotBlank @Size(max=2000) String note){}
 public record OpeningStock(@NotNull UUID requestId,@NotNull UUID subledgerId,@Min(0) long expectedVersion,@NotBlank @Size(max=2000) String note){}
 public record Asset(@NotNull UUID requestId,UUID sourceDocumentId,@NotNull UUID physicalAssetId,@NotNull UUID bookId,@NotBlank @Size(max=80) String assetCode,boolean construction,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal residualValue,@Min(0) @Max(1200) int usefulMonths,LocalDate inServiceDate,@NotBlank String assetAccount,@NotBlank String cipAccount,@NotBlank String depreciationAccount,@NotBlank String expenseAccount,UUID openingSubledgerId,LocalDate depreciationStart,UUID procurementRecognitionId,UUID procurementLineId,@DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal procurementAllocatedAmount){
  public Asset(UUID requestId,UUID sourceDocumentId,UUID physicalAssetId,UUID bookId,String assetCode,boolean construction,BigDecimal residualValue,int usefulMonths,LocalDate inServiceDate,String assetAccount,String cipAccount,String depreciationAccount,String expenseAccount){this(requestId,sourceDocumentId,physicalAssetId,bookId,assetCode,construction,residualValue,usefulMonths,inServiceDate,assetAccount,cipAccount,depreciationAccount,expenseAccount,null,null,null,null,null);}
  public Asset(UUID requestId,UUID sourceDocumentId,UUID physicalAssetId,UUID bookId,String assetCode,boolean construction,BigDecimal residualValue,int usefulMonths,LocalDate inServiceDate,String assetAccount,String cipAccount,String depreciationAccount,String expenseAccount,UUID openingSubledgerId,LocalDate depreciationStart){this(requestId,sourceDocumentId,physicalAssetId,bookId,assetCode,construction,residualValue,usefulMonths,inServiceDate,assetAccount,cipAccount,depreciationAccount,expenseAccount,openingSubledgerId,depreciationStart,null,null,null);}
 }
 public record AssetEvent(@NotNull UUID requestId,@Min(0) int expectedVersion,@Pattern(regexp="CAPITALIZE|DEPRECIATE|TRANSFER|LOSS|DISPOSE") String action,@NotNull LocalDate occurredOn,UUID targetHotelId,UUID targetBookId,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal proceeds,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal disposalCost,String receivableAccount,String gainAccount,String lossAccount,String payableAccount,@NotBlank @Size(max=2000) String note){}
}
