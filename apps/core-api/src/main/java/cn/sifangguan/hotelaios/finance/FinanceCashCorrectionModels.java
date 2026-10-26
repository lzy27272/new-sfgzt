package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class FinanceCashCorrectionModels {
 private FinanceCashCorrectionModels(){}
 public record MatchReverse(@NotNull UUID matchId,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount){}
 public record Request(@NotNull UUID requestId,@NotNull @Pattern(regexp="CLAIM|OPERATION") String sourceKind,
  @NotNull UUID sourceId,@Min(0) int expectedSourceVersion,@NotNull UUID reviewerId,
  @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,@NotNull LocalDate accountingDate,
  @NotBlank @Size(max=4000) String reason,@NotNull @Size(max=200) List<@Valid MatchReverse> matchingReversals){}
 public record Review(@Min(0) int expectedVersion,boolean approve,@NotBlank @Size(max=4000) String note){}
}
