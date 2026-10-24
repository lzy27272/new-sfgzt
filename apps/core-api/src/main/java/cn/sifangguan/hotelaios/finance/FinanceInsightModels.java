package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public final class FinanceInsightModels {
 private FinanceInsightModels(){}
 public record Metric(@NotNull UUID requestId,@NotNull UUID hotelId,@Pattern(regexp="\\d{4}-\\d{2}") String month,@NotBlank String kind,@Size(max=120) String dimension,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=4) BigDecimal value,@NotBlank @Size(max=500) String sourceReference,int expectedVersion){}
 public record Generate(@NotNull UUID requestId,@NotBlank String month,UUID hotelId,@NotBlank String expectedReportVersion){}
 public record Edit(@Min(0) int expectedVersion,@NotBlank @Size(max=200) String title,@NotNull Map<String,Object> content){}
 public record Version(@Min(0) int expectedVersion){}
 public record Question(@NotNull UUID requestId,@NotBlank String month,UUID hotelId,@NotBlank String expectedReportVersion,@NotBlank String kind,@NotBlank @Size(max=2000) String question,@AssertTrue boolean allowAggregateTransfer,UUID snapshotId){public Question(UUID requestId,String month,UUID hotelId,String expectedReportVersion,String kind,String question,boolean allowAggregateTransfer){this(requestId,month,hotelId,expectedReportVersion,kind,question,allowAggregateTransfer,null);}}
 public record Recommendation(@NotNull UUID requestId,@Min(-1) int expectedVersion,@Min(0) int recommendationIndex,@NotBlank String status,@NotNull UUID ownerId,LocalDate dueDate,@NotBlank @Size(max=2000) String note){}
}
