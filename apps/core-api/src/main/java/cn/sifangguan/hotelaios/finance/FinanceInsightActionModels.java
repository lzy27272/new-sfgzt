package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
public final class FinanceInsightActionModels {
 private FinanceInsightActionModels(){}
 public record Assign(@NotNull UUID requestId,@NotNull UUID reportId,@Min(0) int reportRevision,@Min(0) int recommendationIndex,@NotNull UUID hotelId,@NotNull UUID ownerId,@NotNull UUID reviewerId,@NotBlank @Size(max=240) String title,@NotNull LocalDate dueDate,@NotBlank @Size(max=4000) String note){}
 public record Proof(@NotBlank String sourceType,@NotNull UUID sourceId,@NotBlank String expectedSourceVersion){}
 public record Submit(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotBlank @Size(max=4000) String note,@NotEmpty @Size(max=20) List<@Valid Proof> evidence){}
 public record Move(@NotNull UUID requestId,@Min(0) int expectedVersion,@NotBlank @Size(max=4000) String note){}
 public record Review(@NotNull UUID requestId,@Min(0) int expectedVersion,boolean approve,@NotBlank @Size(max=4000) String note){}
}
