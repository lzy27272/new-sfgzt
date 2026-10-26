package cn.sifangguan.hotelaios.finance;
import jakarta.validation.constraints.*;
public final class FinanceMigrationModels {
 private FinanceMigrationModels(){}
 public record Command(@Min(0) int expectedVersion,@NotBlank String expectedFileHash,@NotBlank @Size(max=2000) String confirmationNote){}
}
