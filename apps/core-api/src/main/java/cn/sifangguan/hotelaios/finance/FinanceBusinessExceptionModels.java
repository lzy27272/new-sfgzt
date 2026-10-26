package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public final class FinanceBusinessExceptionModels {
 private FinanceBusinessExceptionModels(){}
 public record Adjustment(@NotNull UUID requestId,@NotNull UUID entryId,@Min(0) int expectedSourceVersion,
  @NotNull @Pattern(regexp="BAD_DEBT|CHANNEL_DIFFERENCE") String kind,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
  @NotNull LocalDate accountingDate,@NotNull UUID proofAttachmentId,@NotNull UUID reviewerId,@NotBlank @Size(max=4000) String reason){}
 public record Review(@Min(0) int expectedVersion,boolean approve,@NotBlank @Size(max=4000) String note){}
 public record Recovery(@NotNull UUID requestId,@NotNull UUID adjustmentId,@Min(0) int expectedSourceVersion,
  @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,@NotNull LocalDate accountingDate,
  @NotNull UUID proofAttachmentId,@NotNull UUID reviewerId,@NotBlank @Size(max=4000) String reason){}
 public record Calendar(@NotNull UUID requestId,@NotNull UUID hotelId,@NotNull UUID policyId,@NotBlank @Size(max=80) String taxKind,
  @NotBlank @Size(max=160) String title,@NotBlank String month,@NotNull LocalDate dueOn,@NotNull @Pattern(regexp="FILING|PAYMENT") String obligation,
  @NotBlank @Size(max=2000) String officialReference,@NotNull UUID reviewerId){}
 public record Link(@NotNull UUID requestId,@NotNull UUID returnId,@Min(0) int expectedVersion,@NotBlank @Size(max=4000) String note){}
}
