package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
public final class FinanceDailyInsightModels {
 private FinanceDailyInsightModels(){}
 public record Freeze(@NotNull UUID requestId,@NotNull LocalDate date,UUID hotelId,@NotBlank String expectedReportVersion){}
 public record Question(@NotNull UUID requestId,@NotNull LocalDate date,UUID hotelId,@NotNull UUID snapshotId,@NotBlank String expectedReportVersion,@NotBlank @Size(max=2000)String question,boolean allowAggregateTransfer){}
}
