package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;
/** Tax confirmation is a downstream use even when the latest deduction happens to be zero. */
public final class FinanceInputTaxGuard {
 private FinanceInputTaxGuard(){}
 public static void assertReleasable(NamedParameterJdbcTemplate jdbc,TenantPrincipal p,UUID invoice){if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('ft_input_basis') is not null",Map.of(),Boolean.class)))return;if(jdbc.queryForObject("select count(*) from ft_input_basis where tenant_id=:tenant and invoice_id=:invoice and source_kind in ('PROCUREMENT','PROCUREMENT_RED') and status='CONFIRMED'",base(p).addValue("invoice",invoice),Integer.class)>0)throw conflict("原票已有独立确认的税务用途事实，不能只释放占用后重复使用；须按原来源办理税额转出及实际红字等受控处理");}
 public static void assertIntakeReusable(NamedParameterJdbcTemplate jdbc,TenantPrincipal p,UUID invoice){if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('ft_input_basis') is not null",Map.of(),Boolean.class)))return;if(jdbc.queryForObject("select count(*) from ft_input_basis where tenant_id=:tenant and invoice_id=:invoice and source_kind='PROCUREMENT_RED' and status in ('REVIEW','CONFIRMED')",base(p).addValue("invoice",invoice),Integer.class)>0)throw conflict("该真实红字票已关联原采购退货的税务确认，不能再作为另一笔报销或采购蓝票；原事实必须保留");}
}
