package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** HR receives only a blocking flag, never a cross-role financial subledger or a settlement capability. */
@Service @Transactional
public class FinanceEmployeeExitGuard {
 private final NamedParameterJdbcTemplate jdbc;private final TenantDatabaseContext db;private final AccessPolicy access;
 public FinanceEmployeeExitGuard(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext db,AccessPolicy access){this.jdbc=jdbc;this.db=db;this.access=access;}
 public void assertCanEndEmployment(UUID employeeId){access.requirePermission("org.manage");var p=access.principal();db.apply(p.tenantId());if(!p.hasTenantScope())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"员工停用及删除须先通过原员工管理范围校验");var b=base(p).addValue("employee",employeeId);var employees=jdbc.queryForList("select id,account_id from employee where tenant_id=:tenant and id=:employee",b);if(employees.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"员工不存在或不在当前管理范围");Object account=employees.getFirst().get("account_id");if(account==null)return;b.addValue("account",account);
  // Funding already holds its source loan, then locks this employee. Keep that same order.
  if(table("fo_document"))jdbc.queryForList("select id from fo_document where tenant_id=:tenant and type='LOAN' and status='APPROVED' and details->>'borrowerAccountId'=cast(:account as text) order by id for update",b);
  borrowerLock(p,account);
  var current=jdbc.queryForList("select id,account_id from employee where tenant_id=:tenant and id=:employee for update",b);if(current.size()!=1||!Objects.equals(account,current.getFirst().get("account_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"员工身份已变化，请刷新后重新核对");if(!table("fo_document"))return;
  String amount=table("fx_cash_correction")?"fx_operation_action_amount(d.tenant_id,d.id,'PAYMENT',:asof)-fx_operation_action_amount(d.tenant_id,d.id,'REFUND',:asof)-fx_operation_action_amount(d.tenant_id,d.id,'REPAYMENT',:asof)-fx_operation_action_amount(d.tenant_id,d.id,'WRITEOFF',:asof)":"coalesce((select sum(case when a.action='PAYMENT' then a.amount when a.action in ('REFUND','REPAYMENT','WRITEOFF') then -a.amount else 0 end) from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id and a.occurred_on<=:asof),0)";
  Integer count=jdbc.queryForObject("select count(*) from fo_document d where d.tenant_id=:tenant and d.type='LOAN' and d.status='APPROVED' and d.details->>'borrowerAccountId'=cast(:account as text) and ("+amount+")>0",b,Integer.class);if(count>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"存在未清借款，请联系财务核对");
 }
 /** Called from controlled loan funding, after the real source document lock; never creates/clears debt. */
 public void assertActiveLoanBorrower(UUID loanDocumentId){access.requirePermission("finance.pay");var p=access.principal();db.apply(p.tenantId());var loans=jdbc.queryForList("select hotel_id,details->>'borrowerAccountId' borrower from fo_document where tenant_id=:tenant and id=:loan and type='LOAN' and status='APPROVED'",base(p).addValue("loan",loanDocumentId));if(loans.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"借款来源未批准或不存在");var loan=loans.getFirst();access.requireOrgScope(UUID.fromString(loan.get("hotel_id").toString()));if(loan.get("borrower")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"原借款未关联真实员工账号，请财务核对");borrowerLock(p,loan.get("borrower"));var employees=jdbc.queryForList("select e.id,e.employment_status,e.deleted_at,u.status account_status from employee e join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id where e.tenant_id=:tenant and e.account_id=:account for update of e,u",base(p).addValue("account",UUID.fromString(loan.get("borrower").toString())));if(employees.size()!=1||!"ACTIVE".equals(employees.getFirst().get("employment_status"))||employees.getFirst().get("deleted_at")!=null||!"ACTIVE".equals(employees.getFirst().get("account_status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"借款人已停用、离职或身份不可用，不能继续发放借款");}
 private void borrowerLock(TenantPrincipal p,Object account){jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,109))",Map.of("key",p.tenantId()+":loan-borrower:"+account));}
 private MapSqlParameterSource base(TenantPrincipal p){return new MapSqlParameterSource("tenant",p.tenantId()).addValue("asof",LocalDate.now(ZoneId.of("Asia/Shanghai")));}
 private boolean table(String name){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(:table) is not null",Map.of("table",name),Boolean.class));}
}
