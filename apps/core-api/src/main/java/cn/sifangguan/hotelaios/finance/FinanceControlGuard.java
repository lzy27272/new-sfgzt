package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Shared by old and new endpoints. No HTTP parameter can enable the execution context. */
final class FinanceControlGuard {
 private final NamedParameterJdbcTemplate jdbc;
 FinanceControlGuard(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 boolean installed(){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('fc_cash_instruction') is not null",Map.of(),Boolean.class));}
 boolean table(String name){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(:table) is not null",Map.of("table",name),Boolean.class));}

 Map<String,Object> workflow(TenantPrincipal p,Map<String,Object> config,String documentType,BigDecimal amount,LocalDate day,UUID source){
  if(!installed())return config;
  UUID hotel=id(config.get("hotel_id"));
  var candidates=jdbc.queryForList("select * from fc_policy where tenant_id=:tenant and hotel_id=:hotel and kind='WORKFLOW' and status='PUBLISHED' and effective_from<=:day and (effective_to is null or effective_to>=:day) order by id",base(p).addValue("hotel",hotel).addValue("day",day));
  var matches=candidates.stream().filter(r->{var s=map(r.get("spec"));return documentType.equals(s.get("documentType"))&&amount.compareTo(amount(s.get("minimum")))>=0&&(s.get("maximum")==null||amount.compareTo(amount(s.get("maximum")))<0);}).toList();
  if(matches.size()>1)throw conflict("同一日期及金额命中多个审批方案，请先停用重叠版本");
  if(matches.isEmpty())return config;
  var policy=matches.getFirst();var spec=map(policy.get("spec"));var result=new LinkedHashMap<>(config);
  var keys=List.of("manager_account_id","finance_account_id","director_account_id","leader_account_id");
  var route=(List<?>)spec.get("reviewerIds");for(int i=0;i<4;i++)result.put(keys.get(i),id(route.get(i)));
  result.put("cashier_account_id",id(spec.get("cashierId")));result.put("skip_self_review",Boolean.TRUE.equals(spec.get("skipInitialSelf")));
  event(p,hotel,"WORKFLOW",source,"ROUTE_FROZEN",Map.of("policyId",policy.get("id"),"policyVersion",policy.get("version"),"spec",spec));
  return result;
 }
 void travel(TenantPrincipal p,Map<String,Object> claim){
  if(!installed())return;
  var b=base(p).addValue("id",claim.get("id"));var rows=jdbc.queryForList("select * from fc_travel where tenant_id=:tenant and claim_id=:id",b);
  if(rows.isEmpty())return;
  var travel=rows.getFirst();BigDecimal sum=amount(travel.get("lodging_amount")).add(amount(travel.get("transport_amount"))).add(amount(travel.get("meal_amount")));
  if(sum.compareTo(amount(claim.get("requested_amount")))!=0)throw conflict("差旅分项合计与本次报销金额不一致，请重新保存并核对");
  if(travel.get("policy_id")!=null){var policy=jdbc.queryForList("select * from fc_policy where tenant_id=:tenant and id=:policy and status='PUBLISHED' and effective_from<=:day and (effective_to is null or effective_to>=:day)",b.addValue("policy",travel.get("policy_id")).addValue("day",travel.get("trip_start")));
   if(policy.isEmpty()||!map(policy.getFirst().get("spec")).equals(map(travel.get("policy_snapshot"))))throw conflict("差旅标准版本已失效，请重新核对差旅草稿");}
  event(p,id(claim.get("hotel_id")),"CLAIM",id(claim.get("id")),"TRAVEL_FROZEN",output(travel));
 }
 void cash(TenantPrincipal p,String sourceKind,Map<String,Object> source,String action,BigDecimal value,LocalDate day,UUID bankId){
  if(!installed())return;
  String token=jdbc.queryForObject("select current_setting('app.finance_instruction',true)",Map.of(),String.class);
  if(token==null||token.isBlank())throw conflict("请到财务中心“受控付款”建立并复核资金指令；原入口不能绕过账户及收款人核验");
  var rows=jdbc.queryForList("select * from fc_cash_instruction where tenant_id=:tenant and id=:id for update",base(p).addValue("id",UUID.fromString(token)));
  if(rows.isEmpty())throw conflict("资金指令不存在");var r=rows.getFirst();
  if(!"APPROVED".equals(r.get("status"))||!sourceKind.equals(r.get("source_kind"))||!source.get("id").equals(r.get("source_id"))||!action.equals(r.get("action"))||value.compareTo(amount(r.get("amount")))!=0||!day.toString().equals(r.get("occurred_on").toString())||!p.actorId().equals(r.get("cashier_id"))||((Number)source.get("version")).intValue()!=((Number)r.get("source_version")).intValue())throw conflict("资金指令与当前单据、金额、日期或操作人不匹配，请重新复核");
  if("OPERATION".equals(sourceKind)&&"LOAN".equals(source.get("type"))&&"PAYMENT".equals(action))new FinanceEmployeeExitGuard(jdbc,new cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext(jdbc.getJdbcTemplate(),true),new cn.sifangguan.hotelaios.shared.security.AccessPolicy()).assertActiveLoanBorrower(id(source.get("id")));
  if(bankId!=null&&!bankId.equals(r.get("bank_account_id")))throw conflict("付款账户不匹配");
  var banks=jdbc.queryForList("select * from fo_bank_account where tenant_id=:tenant and id=:bank",base(p).addValue("bank",r.get("bank_account_id")));
  if(banks.isEmpty()||!map(json(output(banks.getFirst()))).equals(map(r.get("bank_snapshot"))))throw conflict("付款账户版本已变化，原指令失效");
  if(!Objects.equals(banks.getFirst().get("company_id"),source.get("company_id")))throw conflict("银行账户与原单据冻结法人不一致");
  if(r.get("beneficiary_id")!=null){var party=jdbc.queryForList("select * from fo_party where tenant_id=:tenant and id=:party",base(p).addValue("party",r.get("beneficiary_id")));
   if(party.isEmpty()||!map(json(output(party.getFirst()))).equals(map(r.get("beneficiary_snapshot"))))throw conflict("收款人账户资料已变化，必须重建并复核指令");}
  new FinanceBudgetControl(jdbc).cash(p,r,source);
  if("OPERATION".equals(sourceKind)&&"CONTRACT".equals(source.get("type")))contract(p,source,r,value);
  if("OPERATION".equals(sourceKind)&&"PAYMENT".equals(action)&&"FS_PROCUREMENT".equals(source.get("source_type")))supply(p,source,value);
 }
 void contract(TenantPrincipal p,Map<String,Object> source,Map<String,Object> instruction,BigDecimal value){
  if(!"PAYMENT".equals(instruction.get("action")))return;
  var revisions=jdbc.queryForList("select * from fc_contract_revision where tenant_id=:tenant and document_id=:doc and status='APPROVED' order by reviewed_at desc,id desc limit 1",base(p).addValue("doc",source.get("id")));
  List<Map<String,Object>> nodes;BigDecimal ceiling,retainage;
  if(revisions.isEmpty()){nodes=rows(map(source.get("details")).get("paymentPlan"));ceiling=amount(source.get("amount"));retainage=BigDecimal.ZERO;}
  else{nodes=rows(revisions.getFirst().get("nodes"));ceiling=amount(revisions.getFirst().get("amount"));retainage=amount(revisions.getFirst().get("retainage"));}
  LocalDate day=LocalDate.parse(instruction.get("occurred_on").toString());String node=Objects.toString(instruction.get("contract_node"),"");
  Map<String,Object> target=null;for(int i=0;i<nodes.size();i++){var n=nodes.get(i);String key=Objects.toString(n.get("code"),Integer.toString(i+1));if(key.equals(node))target=n;}
  if(target==null)throw conflict("必须选择当前合同已复核的付款节点");
  if(day.isBefore(LocalDate.parse(target.get("date").toString())))throw conflict("合同付款节点尚未到期");
  BigDecimal paid=operationAmount(p,id(source.get("id")),"PAYMENT").subtract(operationAmount(p,id(source.get("id")),"REFUND"));
  if(paid.add(value).compareTo(ceiling.subtract(retainage))>0&&!Boolean.TRUE.equals(target.get("releaseRetainage")))throw conflict("本次付款将占用尚未释放的质保金");
  if(paid.add(value).compareTo(ceiling)>0)throw conflict("付款超过已复核合同总额");
  String net=table("fx_cash_correction")?"fx_cash_effective_amount(a.tenant_id,'OPERATION',a.id,a.amount,'9999-12-31'::date)":"a.amount";BigDecimal used=jdbc.queryForObject("select coalesce(sum("+net+"),0) from fc_cash_instruction i join fo_action a on a.tenant_id=i.tenant_id and a.request_id=i.id and a.document_id=i.source_id and a.action=i.action where i.tenant_id=:tenant and i.source_id=:doc and i.source_kind='OPERATION' and i.action='PAYMENT' and i.contract_node=:node and i.status='EXECUTED'",base(p).addValue("doc",source.get("id")).addValue("node",node),BigDecimal.class);
  if(used.add(value).compareTo(amount(target.get("amount")))>0)throw conflict("付款超过当前节点额度；变更须先独立复核");
 }
 private void supply(TenantPrincipal p,Map<String,Object> source,BigDecimal value){
  if(!table("fs_recognition"))throw conflict("采购应付来源未就绪");
  var b=base(p).addValue("id",source.get("source_id"));var r=jdbc.queryForList("select * from fs_recognition where tenant_id=:tenant and id=:id for update",b);if(r.isEmpty()||!"CONFIRMED".equals(r.getFirst().get("status"))||!source.get("hotel_id").equals(r.getFirst().get("hotel_id"))||!source.get("company_id").equals(r.getFirst().get("company_id")))throw conflict("采购来源尚未完成独立复核或主体不符");
  BigDecimal credit=jdbc.queryForObject("select coalesce(sum(supplier_credit),0) from fs_return where tenant_id=:tenant and recognition_id=:id and status='APPROVED'",b,BigDecimal.class).add(jdbc.queryForObject("select coalesce(sum(amount),0) from fs_settlement where tenant_id=:tenant and target_recognition_id=:id and kind='CREDIT'",b,BigDecimal.class));
  BigDecimal paid=jdbc.queryForObject("select coalesce(sum("+cashAmountExpression("a")+" ) filter(where a.action='PAYMENT'),0)-coalesce(sum("+cashAmountExpression("a")+") filter(where a.action='REFUND'),0) from fo_action a join fo_document d on d.tenant_id=a.tenant_id and d.id=a.document_id where d.tenant_id=:tenant and d.source_type='FS_PROCUREMENT' and d.source_id=:id",b,BigDecimal.class);
  if(paid.add(value).compareTo(amount(r.getFirst().get("invoice_amount")).subtract(credit))>0)throw conflict("付款超过采购净应付（已扣原批次退货及抵款）");
 }
 boolean disposed(TenantPrincipal p,UUID source){
  return table("fs_asset_event")&&jdbc.queryForObject("select count(*) from fs_asset_event e join fs_asset_card c on c.tenant_id=e.tenant_id and c.id=e.asset_id where e.tenant_id=:tenant and c.source_document_id=:id and e.action='DISPOSE'",base(p).addValue("id",source),Integer.class)>0;
 }
 void requireLegacyAccrualAvailable(TenantPrincipal p,UUID source){
  var b=base(p).addValue("source",source);
  if(table("far_plan")&&jdbc.queryForObject("select count(*) from far_plan where tenant_id=:tenant and source_document_id=:source",b,Integer.class)>0)throw conflict("该来源已纳入新版受控租赁/待摊计划，不能从旧入口重复摊销；暂停或终止也不释放历史占用");
  if(table("fs_asset_card")&&jdbc.queryForObject("select count(*) from fs_asset_card where tenant_id=:tenant and source_document_id=:source",b,Integer.class)>0)throw conflict("该资产已有真实财务卡片，请从采购与资产办理折旧/处置，不能走旧入口重复计提");
 }
 void requireActiveSourceForPayment(TenantPrincipal p,UUID source){if(table("far_plan")&&jdbc.queryForObject("select count(*) from far_plan where tenant_id=:tenant and source_document_id=:source and status in ('TERMINATING','TERMINATED')",base(p).addValue("source",source),Integer.class)>0)throw conflict("来源租赁/受益计划已进入终止流程，停止新付款；真实退款须按独立受控资金流程办理");}
 BigDecimal supplyMatched(TenantPrincipal p,UUID transaction){
  var b=base(p).addValue("id",transaction);BigDecimal total=table("fs_settlement")?jdbc.queryForObject("select coalesce(sum(amount),0) from fs_settlement where tenant_id=:tenant and bank_transaction_id=:id",b,BigDecimal.class):BigDecimal.ZERO;
  if(table("fb_bank_match"))total=total.add(jdbc.queryForObject("select fb_bank_allocated_amount(:tenant,:id,'9999-12-31'::date)",b,BigDecimal.class));
  if(table("fx_cash_correction")){BigDecimal netOld=jdbc.queryForObject("select fx_bank_allocated_amount(:tenant,:id,'9999-12-31'::date)-coalesce((select sum(amount) from fo_bank_match where tenant_id=:tenant and transaction_id=:id),0)",b,BigDecimal.class);return total.add(netOld);}
  return installed()?total.add(jdbc.queryForObject("select coalesce(sum(amount),0) from fc_claim_bank_match where tenant_id=:tenant and transaction_id=:id",b,BigDecimal.class)):total;
 }
 String cashAmountExpression(String alias){return table("fx_cash_correction")?"fx_cash_effective_amount("+alias+".tenant_id,'OPERATION',"+alias+".id,"+alias+".amount,'9999-12-31'::date)":alias+".amount";}
 BigDecimal bankAllocated(TenantPrincipal p,UUID transaction,LocalDate asOf){var b=base(p).addValue("id",transaction).addValue("day",asOf);BigDecimal total=table("fx_cash_correction")?jdbc.queryForObject("select fx_bank_allocated_amount(:tenant,:id,:day)",b,BigDecimal.class):jdbc.queryForObject("select coalesce(sum(amount),0) from fo_bank_match where tenant_id=:tenant and transaction_id=:id",b,BigDecimal.class);if(!table("fx_cash_correction")&&installed())total=total.add(jdbc.queryForObject("select coalesce(sum(amount),0) from fc_claim_bank_match where tenant_id=:tenant and transaction_id=:id",b,BigDecimal.class));if(table("fb_bank_match"))total=total.add(jdbc.queryForObject("select fb_bank_allocated_amount(:tenant,:id,:day)",b,BigDecimal.class));if(table("fs_settlement"))total=total.add(jdbc.queryForObject("select coalesce(sum(amount),0) from fs_settlement where tenant_id=:tenant and bank_transaction_id=:id and accounting_date<=:day",b,BigDecimal.class));return total;}
 BigDecimal claimPaid(TenantPrincipal p,UUID claim){return jdbc.queryForObject(table("fx_cash_correction")?"select fx_claim_paid_amount(:tenant,:id,'9999-12-31'::date)":"select coalesce(sum(amount),0) from finance_payment where tenant_id=:tenant and claim_id=:id",base(p).addValue("id",claim),BigDecimal.class);}
 BigDecimal operationAmount(TenantPrincipal p,UUID document,String action){return jdbc.queryForObject(table("fx_cash_correction")?"select fx_operation_action_amount(:tenant,:id,:action,'9999-12-31'::date)":"select coalesce(sum(amount),0) from fo_action where tenant_id=:tenant and document_id=:id and action=:action",base(p).addValue("id",document).addValue("action",action),BigDecimal.class);}
 BigDecimal contractAmount(TenantPrincipal p,Map<String,Object> source){if(!installed()||!"CONTRACT".equals(source.get("type")))return amount(source.get("amount"));var r=jdbc.queryForList("select amount from fc_contract_revision where tenant_id=:tenant and document_id=:id and status='APPROVED' order by reviewed_at desc,id desc limit 1",base(p).addValue("id",source.get("id")));return r.isEmpty()?amount(source.get("amount")):amount(r.getFirst().get("amount"));}
 void event(TenantPrincipal p,UUID hotel,String kind,UUID source,String event,Map<String,?> snapshot){
  jdbc.update("insert into fc_event(tenant_id,hotel_id,source_kind,source_id,event,snapshot,actor_id) values(:tenant,:hotel,:kind,:source,:event,cast(:snapshot as jsonb),:actor)",base(p).addValue("hotel",hotel).addValue("kind",kind).addValue("source",source).addValue("event",event).addValue("snapshot",json(snapshot)));
 }
}
