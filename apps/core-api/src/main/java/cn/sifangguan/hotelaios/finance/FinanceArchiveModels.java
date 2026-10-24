package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.util.UUID;
public final class FinanceArchiveModels {
 private FinanceArchiveModels(){}
 public record Freeze(@NotNull UUID requestId,@NotBlank String sourceType,@NotNull UUID sourceId,@NotBlank @Size(max=500) String expectedSourceVersion,@NotBlank @Size(max=240) String title,boolean restricted){}
 public record Note(@NotNull UUID requestId,@NotBlank @Size(max=4000) String body){}
 public record Request(@NotNull UUID requestId,@NotBlank @Size(max=2000) String reason,@Min(1) @Max(72) int hours){}
 public record Decision(@Min(0) int expectedVersion,boolean approve,@NotBlank @Size(max=2000) String note){}
 public record Retention(@NotNull UUID requestId,@NotNull UUID companyId,@Min(1) @Max(50) int years,boolean legalHold,@NotBlank @Size(max=2000) String reason){}
}
