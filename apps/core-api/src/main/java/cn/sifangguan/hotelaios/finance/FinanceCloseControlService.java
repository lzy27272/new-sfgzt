package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

@Service @Transactional
public class FinanceCloseControlService {
 private final NamedParameterJdbcTemplate jdbc;private final TenantDatabaseContext database;private final AccessPolicy access;private final AuditWriter audit;
 public FinanceCloseControlService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,AuditWriter audit){this.jdbc=jdbc;this.database=database;this.access=access;this.audit=audit;}
 public Map<String,Object> context(UUID bookId,String period){
  var p=read();var bk=book(p,bookId);var month=month(period);var b=FinanceCloseV2.params(p,bk,month);
  if(!p.hasPermission("*")&&!p.hasPermission("finance.read")&&!p.hasPermission("finance.accounting.read")&&!p.hasPermission("finance.accounting.close"))return output(Map.of("sources",List.of(),"registers",jdbc.queryForList("select * from fcl_register where tenant_id=:tenant and book_id=:book and month=:month and reviewer_id=:actor order by created_at desc,id desc",b),"bankRows",List.of(),"explanations",jdbc.queryForList("select * from fcl_bank_explanation where tenant_id=:tenant and book_id=:book and as_of=:asof and reviewer_id=:actor order by created_at desc,id desc",b),"checks",List.of()));
  return output(Map.of("sources",new ArrayList<>(FinanceCloseV2.sourceStats(jdbc,p,bk,month).values()),"registers",jdbc.queryForList("select * from fcl_register where tenant_id=:tenant and book_id=:book and month=:month order by created_at desc,id desc",b),"bankRows",FinanceCloseV2.bankRows(jdbc,p,bk,month),"explanations",jdbc.queryForList("select * from fcl_bank_explanation where tenant_id=:tenant and book_id=:book and as_of=:asof order by created_at desc,id desc",b),"checks",FinanceCloseV2.extraChecks(jdbc,p,bk,month)));
 }
 public Map<String,Object> register(FinanceCloseModels.Register in){
  var p=write();var bk=book(p,in.bookId());var month=month(in.month());open(p,bk,month);lock(p,in.bookId(),month);String hash=FinanceService.hash(json(in));var b=base(p).addValue("id",in.requestId());
  var prior=jdbc.queryForList("select * from fcl_register where tenant_id=:tenant and id=:id",b);if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("request_hash")))throw conflict("来源登记请求编号已用于不同原始内容");return output(prior.getFirst());}
  var entries=rows(json(in.entries()));var errors=FinanceCloseV2.validateRegister(entries,FinanceCloseV2.sourceStats(jdbc,p,bk,month));if(!errors.isEmpty())throw conflict(String.join("；",errors));UUID reviewer=reviewer(p,bk);if(reviewer.equals(p.actorId()))throw conflict("来源完整性须独立总监确认，请由其他实际经办发起");
  jdbc.update("insert into fcl_register(id,tenant_id,book_id,month,entries,request_hash,created_by,reviewer_id,note) values(:id,:tenant,:book,:month,cast(:entries as jsonb),:hash,:actor,:reviewer,:note)",b.addValue("book",in.bookId()).addValue("month",month).addValue("entries",json(in.entries())).addValue("hash",hash).addValue("reviewer",reviewer).addValue("note",in.note()));audit.record("FINANCE_CLOSE_SOURCES_SUBMITTED","FCL_REGISTER",in.requestId(),json(in));return output(one("select * from fcl_register where tenant_id=:tenant and id=:id",b));
 }
 public Map<String,Object> confirmRegister(UUID id,FinanceCloseModels.Confirm in){
  var p=review();var b=base(p).addValue("id",id);var row=one("select * from fcl_register where tenant_id=:tenant and id=:id for update",b);var bk=book(p,id(row.get("book_id")));LocalDate month=date(row.get("month"));open(p,bk,month);version(row,in.expectedVersion());independent(p,row);
  if(in.approved()){var errors=FinanceCloseV2.validateRegister(rows(row.get("entries")),FinanceCloseV2.sourceStats(jdbc,p,bk,month));if(!errors.isEmpty())throw conflict("原始来源已变化："+String.join("；",errors));}
  jdbc.update("update fcl_register set status=:status,reviewed_by=:actor,reviewed_at=now(),version=version+1 where tenant_id=:tenant and id=:id",b.addValue("status",in.approved()?"CONFIRMED":"REJECTED"));audit.record("FINANCE_CLOSE_SOURCES_REVIEWED","FCL_REGISTER",id,json(in));return output(one("select * from fcl_register where tenant_id=:tenant and id=:id",b));
 }
 public Map<String,Object> explain(FinanceCloseModels.Explanation in){
  var p=write();var bk=book(p,in.bookId());LocalDate month=month(in.month()),asOf=month.plusMonths(1).minusDays(1);open(p,bk,month);lock(p,in.bookId(),month);var b=base(p).addValue("id",in.requestId());var existing=jdbc.queryForList("select * from fcl_bank_explanation where tenant_id=:tenant and id=:id",b);
  if(!existing.isEmpty()){var old=existing.getFirst();if(!in.bookId().equals(old.get("book_id"))||!in.transactionId().equals(old.get("transaction_id"))||!asOf.equals(date(old.get("as_of")))||!in.note().equals(old.get("note")))throw conflict("差异解释请求已用于其他流水或月份");return output(old);}
  var bank=one("select t.*,a.company_id,a.hotel_id from fo_bank_transaction t join fo_bank_account a on a.tenant_id=t.tenant_id and a.id=t.bank_account_id where t.tenant_id=:tenant and t.id=:bank",base(p).addValue("bank",in.transactionId()));if(!Objects.equals(bank.get("company_id"),bk.get("company_id"))||!Objects.equals(bank.get("hotel_id"),bk.get("hotel_id"))||date(bank.get("occurred_on")).isBefore(month)||date(bank.get("occurred_on")).isAfter(asOf))throw conflict("流水不属于当前冻结主体/组织/期间");
  var snapshot=FinanceCloseV2.bankSnapshot(jdbc,p,in.transactionId(),asOf);if(amount(snapshot.get("allocatedAmount")).compareTo(amount(bank.get("amount")))>=0)throw conflict("已全额匹配无需差异说明；超分配必须撤销错误匹配，不能靠说明放行");UUID reviewer=reviewer(p,bk);if(reviewer.equals(p.actorId()))throw conflict("差异解释必须由独立实际总监确认");
  jdbc.update("insert into fcl_bank_explanation(id,tenant_id,book_id,transaction_id,as_of,allocated_amount,allocation_hash,snapshot,note,created_by,reviewer_id) values(:id,:tenant,:book,:bank,:asof,:amount,:hash,cast(:snapshot as jsonb),:note,:actor,:reviewer)",b.addValue("book",in.bookId()).addValue("bank",in.transactionId()).addValue("asof",asOf).addValue("amount",amount(snapshot.get("allocatedAmount"))).addValue("hash",FinanceService.hash(json(snapshot))).addValue("snapshot",json(snapshot)).addValue("note",in.note()).addValue("reviewer",reviewer));audit.record("FINANCE_CLOSE_BANK_DIFFERENCE_SUBMITTED","FCL_BANK_EXPLANATION",in.requestId(),json(in));return output(one("select * from fcl_bank_explanation where tenant_id=:tenant and id=:id",b));
 }
 public Map<String,Object> confirmExplanation(UUID id,FinanceCloseModels.Confirm in){
  var p=review();var b=base(p).addValue("id",id);var row=one("select * from fcl_bank_explanation where tenant_id=:tenant and id=:id for update",b);var bk=book(p,id(row.get("book_id")));open(p,bk,date(row.get("as_of")).withDayOfMonth(1));version(row,in.expectedVersion());independent(p,row);
  if(in.approved()&&!FinanceService.hash(json(FinanceCloseV2.bankSnapshot(jdbc,p,id(row.get("transaction_id")),date(row.get("as_of"))))).equals(row.get("allocation_hash")))throw conflict("跨域净匹配来源已变化，原差异说明失效，须重提真实快照");
  jdbc.update("update fcl_bank_explanation set status=:status,reviewed_by=:actor,reviewed_at=now(),version=version+1 where tenant_id=:tenant and id=:id",b.addValue("status",in.approved()?"CONFIRMED":"REJECTED"));audit.record("FINANCE_CLOSE_BANK_DIFFERENCE_REVIEWED","FCL_BANK_EXPLANATION",id,json(in));return output(one("select * from fcl_bank_explanation where tenant_id=:tenant and id=:id",b));
 }
 private void independent(TenantPrincipal p,Map<String,Object> row){if(!"DRAFT".equals(row.get("status"))||!p.actorId().equals(row.get("reviewer_id"))||p.actorId().equals(row.get("created_by")))throw conflict("须由后台配置的独立总监审核当前草稿");}
 private UUID reviewer(TenantPrincipal p,Map<String,Object> book){return id(one("select c.director_account_id from finance_store_config c join user_account u on u.tenant_id=c.tenant_id and u.id=c.director_account_id where c.tenant_id=:tenant and c.hotel_id=:hotel and c.company_id=:company and c.enabled and c.workflow_version=2 and u.status='ACTIVE'",base(p).addValue("hotel",book.get("hotel_id")).addValue("company",book.get("company_id"))).get("director_account_id"));}
 private void open(TenantPrincipal p,Map<String,Object> bk,LocalDate month){if(month.isBefore(date(bk.get("start_month"))))throw conflict("月份早于账套切换，不回写历史");if(jdbc.queryForObject("select count(*) from fa_period where tenant_id=:tenant and book_id=:book and month=:month and status<>'OPEN'",base(p).addValue("book",bk.get("id")).addValue("month",month),Long.class)>0)throw conflict("期间已关账，请先按逆序授权重开");}
 private Map<String,Object> book(TenantPrincipal p,UUID id){var r=one("select * from fa_book where tenant_id=:tenant and id=:id",base(p).addValue("id",id));access.requireOrgScope(id(r.get("hotel_id")));return r;}
 private TenantPrincipal read(){access.requireAnyPermission("finance.read","finance.accounting.read","finance.accounting.close","finance.review");var p=access.principal();database.apply(p.tenantId());return p;}
 private TenantPrincipal write(){var p=read();access.requirePermission("finance.accounting.close");return p;}
 private TenantPrincipal review(){access.requirePermission("finance.review");var p=access.principal();database.apply(p.tenantId());return p;}
 private void lock(TenantPrincipal p,UUID book,LocalDate month){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,99))",Map.of("key",p.tenantId()+":"+book+":"+month),Object.class);}
 private Map<String,Object> one(String sql,MapSqlParameterSource b){var r=jdbc.queryForList(sql,b);if(r.isEmpty())throw missing();return r.getFirst();}
 private static LocalDate date(Object value){return value instanceof java.sql.Date d?d.toLocalDate():LocalDate.parse(value.toString().substring(0,10));}
}
