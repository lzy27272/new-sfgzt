package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
public final class FinanceAccrualModels {
 private FinanceAccrualModels(){}
 public record Policy(@NotNull UUID requestId,@NotNull UUID bookId,@NotBlank @Size(max=160) String name,@Pattern(regexp="PREPAID|LEASE_ACCRUAL") String kind,@NotBlank @Size(max=2000) String policyReference){}
 public record Allocation(@NotNull UUID departmentId,@NotNull @DecimalMin("0.000001") @Digits(integer=4,fraction=6) BigDecimal weight,@Size(max=80) String financialCategoryCode,@Pattern(regexp="NONE|CONTRACT|CIP") String projectKind,UUID projectId){public Allocation(UUID departmentId,BigDecimal weight){this(departmentId,weight,null,null,null);}public Allocation(UUID departmentId,BigDecimal weight,String financialCategoryCode){this(departmentId,weight,financialCategoryCode,null,null);}}
 public record Plan(@NotNull UUID requestId,@NotNull UUID bookId,@NotNull UUID policyId,UUID sourceDocumentId,UUID openingSubledgerId,@NotBlank @Size(max=160) String title,@Pattern(regexp="\\d{4}-\\d{2}") String startMonth,@Pattern(regexp="\\d{4}-\\d{2}") String endMonth,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal totalBasis,@NotBlank String debitAccount,@NotBlank String creditAccount,@NotNull @Size(min=1,max=100) List<@Valid Allocation> allocations,@NotBlank @Size(max=2000) String note){}
 public record Revision(@NotNull UUID requestId,@Min(0) int expectedVersion,@Pattern(regexp="REVISE|TERMINATE") String kind,@Pattern(regexp="\\d{4}-\\d{2}") String effectiveMonth,@Pattern(regexp="\\d{4}-\\d{2}") String endMonth,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal remainingBasis,@NotBlank String debitAccount,@NotBlank String creditAccount,@NotNull @Size(min=1,max=100) List<@Valid Allocation> allocations,@NotBlank @Size(max=2000) String note){}
 public record Confirm(@NotNull UUID requestId,@Min(0) int expectedVersion,boolean approved,@NotBlank @Size(max=2000) String note){}
 public record Run(@NotNull UUID requestId,@Pattern(regexp="\\d{4}-\\d{2}") String month,@Min(0) int expectedVersion){}
}
