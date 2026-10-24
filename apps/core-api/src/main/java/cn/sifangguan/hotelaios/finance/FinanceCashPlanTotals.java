package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Actual cash events, not instructions. Corrections belong to their own accounting month. */
public final class FinanceCashPlanTotals {
 private final NamedParameterJdbcTemplate jdbc;
 public FinanceCashPlanTotals(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 public BigDecimal total(TenantPrincipal p,UUID hotel,YearMonth month,String direction){
  if(!p.hasTenantScope()&&!p.orgScopes().contains(hotel))throw new AccessDeniedException("资金计划组织不在当前授权范围");
  if(!Set.of("IN","OUT").contains(direction)||month==null)throw bad("资金计划月份或方向无效");
  var parts=new ArrayList<String>();boolean clear=table("fc_claim_loan_clear");
  String excludeOffset=clear?" and not exists(select 1 from fc_claim_loan_clear lc where lc.tenant_id=pay.tenant_id and lc.payment_id=pay.id)":"";
  parts.add("select a.occurred_on as event_on,case when a.action in ('RECEIPT','REPAYMENT') or a.action='REFUND' and d.type<>'REVENUE' then 'IN' else 'OUT' end as direction,a.amount as amount from fo_action a join fo_document d on d.tenant_id=a.tenant_id and d.id=a.document_id where a.tenant_id=:tenant and d.hotel_id=:hotel and a.action in ('PAYMENT','RECEIPT','REFUND','REPAYMENT') and a.occurred_on>=:start and a.occurred_on<:end");
  parts.add("select pay.paid_on,'OUT',pay.amount from finance_payment pay join finance_claim c on c.tenant_id=pay.tenant_id and c.id=pay.claim_id where pay.tenant_id=:tenant and c.hotel_id=:hotel and pay.paid_on>=:start and pay.paid_on<:end"+excludeOffset);
  if(table("fb_revenue_movement")){
   parts.add("select m.occurred_on,case when m.kind='RECEIPT' then 'IN' else 'OUT' end,m.amount from fb_revenue_movement m join fb_revenue_entry e on e.tenant_id=m.tenant_id and e.id=m.entry_id where m.tenant_id=:tenant and e.hotel_id=:hotel and m.kind in ('RECEIPT','REFUND') and m.occurred_on>=:start and m.occurred_on<:end");
   parts.add("select pay.occurred_on,'OUT',pay.amount from fb_payroll_payment pay join fb_payroll_line l on l.tenant_id=pay.tenant_id and l.id=pay.line_id join fb_payroll_batch b on b.tenant_id=l.tenant_id and b.id=l.batch_id where pay.tenant_id=:tenant and b.hotel_id=:hotel and pay.occurred_on>=:start and pay.occurred_on<:end");
   parts.add("select c.occurred_on,case when c.kind='RECEIPT' then 'IN' else 'OUT' end,c.amount from fb_opening_clearing c where c.tenant_id=:tenant and c.hotel_id=:hotel and c.occurred_on>=:start and c.occurred_on<:end");
   parts.add("select c.occurred_on,case when c.kind='RECEIPT' then 'IN' else 'OUT' end,c.amount from fb_intercompany_settlement c where c.tenant_id=:tenant and c.hotel_id=:hotel and c.occurred_on>=:start and c.occurred_on<:end");
   // REDEEM is non-cash, and a tax/payment receipt is an archive, not another bank payment.
   parts.add("select c.accounting_on,case when c.original_kind='RECEIPT' then 'IN' else 'OUT' end,-c.amount from fb_cash_correction c where c.tenant_id=:tenant and c.hotel_id=:hotel and c.original_kind in ('RECEIPT','PAYMENT','REFUND') and c.accounting_on>=:start and c.accounting_on<:end");
  }
  if(table("ft_tax_payment"))parts.add("select p.occurred_on,'OUT',p.amount from ft_tax_payment p where p.tenant_id=:tenant and p.hotel_id=:hotel and p.occurred_on>=:start and p.occurred_on<:end");
  if(table("fs_settlement"))parts.add("select s.business_date,'IN',s.amount from fs_settlement s join fs_return r on r.tenant_id=s.tenant_id and r.id=s.return_id where s.tenant_id=:tenant and r.hotel_id=:hotel and s.kind='REFUND' and s.bank_transaction_id is not null and s.business_date>=:start and s.business_date<:end");
  if(table("fx_cash_correction")){
   parts.add("select x.accounting_on,case when a.action in ('RECEIPT','REPAYMENT') or a.action='REFUND' and d.type<>'REVENUE' then 'IN' else 'OUT' end,-x.amount from fx_cash_correction x join fo_action a on a.tenant_id=x.tenant_id and a.id=x.source_id join fo_document d on d.tenant_id=a.tenant_id and d.id=a.document_id where x.tenant_id=:tenant and x.hotel_id=:hotel and x.source_kind='OPERATION' and x.status='CONFIRMED' and a.action in ('PAYMENT','RECEIPT','REFUND','REPAYMENT') and x.accounting_on>=:start and x.accounting_on<:end");
   parts.add("select x.accounting_on,'OUT',-x.amount from fx_cash_correction x join finance_payment pay on pay.tenant_id=x.tenant_id and pay.id=x.source_id where x.tenant_id=:tenant and x.hotel_id=:hotel and x.source_kind='CLAIM' and x.status='CONFIRMED' and x.accounting_on>=:start and x.accounting_on<:end"+excludeOffset);
  }
  return jdbc.queryForObject("select coalesce(sum(amount),0) from ("+String.join(" union all ",parts)+") cash_events where direction=:direction",base(p).addValue("hotel",hotel).addValue("start",month.atDay(1)).addValue("end",month.plusMonths(1).atDay(1)).addValue("direction",direction),BigDecimal.class);
 }
 private boolean table(String name){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(:name) is not null",java.util.Map.of("name",name),Boolean.class));}
}
