package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
import java.util.UUID;
public final class FinanceInvoiceAutomationModels {
 private FinanceInvoiceAutomationModels(){}
 public record Start(@NotNull UUID requestId,@NotBlank @Pattern(regexp="OCR|VERIFY") String operation,@Min(0) int expectedIntakeVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceSha256,@AssertTrue boolean allowExternalTransfer,@NotBlank @Size(max=1000) String consentNote){}
 public record Adopt(@Min(0) int expectedIntakeVersion,@AssertTrue boolean confirmCandidateAdoption){}
}
