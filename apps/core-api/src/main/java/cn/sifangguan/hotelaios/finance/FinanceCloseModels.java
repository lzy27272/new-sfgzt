package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
public final class FinanceCloseModels {
 private FinanceCloseModels(){}
 public record Entry(@NotBlank String sourceCode,@Pattern(regexp="APPLICABLE|NOT_APPLICABLE") String applicability,@Min(0) long expectedCount,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=4) BigDecimal controlAmount,@NotBlank @Size(max=1000) String reference,@NotBlank @Size(max=1000) String reason){}
 public record Register(@NotNull UUID requestId,@NotNull UUID bookId,@Pattern(regexp="\\d{4}-\\d{2}") String month,@NotNull @Size(min=1,max=30) List<@Valid Entry> entries,@NotBlank @Size(max=2000) String note){}
 public record Confirm(@Min(0) int expectedVersion,boolean approved,@NotBlank @Size(max=2000) String note){}
 public record Explanation(@NotNull UUID requestId,@NotNull UUID bookId,@NotNull UUID transactionId,@Pattern(regexp="\\d{4}-\\d{2}") String month,@NotBlank @Size(max=2000) String note){}
}
