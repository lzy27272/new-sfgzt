package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.math.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Auxiliary labels are frozen from actual identities, not client supplied names or inferred display codes. */
final class FinanceAuxiliaryDimensions {
 private final NamedParameterJdbcTemplate jdbc;
 FinanceAuxiliaryDimensions(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 boolean installed(){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema=current_schema() and table_name='fa_voucher_line' and column_name='auxiliary')",Map.of(),Boolean.class));}
 Map<String,Object> context(TenantPrincipal p,UUID hotel,UUID company){
  var b=base(p).addValue("hotel",hotel);
  var departments=jdbc.queryForList("select d.id,d.name,d.status from org_unit d where d.tenant_id=:tenant and (d.id=:hotel or d.unit_type='DEPARTMENT' and exists(select 1 from org_unit_closure c where c.tenant_id=d.tenant_id and c.ancestor_id=:hotel and c.descendant_id=d.id)) order by d.name,d.id",b);
  var employees=jdbc.queryForList("select distinct u.id,u.display_name,e.employee_no,e.employment_status from user_account u join employee e on e.tenant_id=u.tenant_id and e.account_id=u.id join employee_position_assignment a on a.tenant_id=e.tenant_id and a.employee_id=e.id where u.tenant_id=:tenant and (a.org_unit_id=:hotel or exists(select 1 from org_unit_closure c where c.tenant_id=a.tenant_id and c.ancestor_id=:hotel and c.descendant_id=a.org_unit_id)) order by u.display_name,u.id",b);
  var parties=jdbc.queryForList("select id,name,kind from fo_party where tenant_id=:tenant and hotel_id=:hotel order by name,id",b);
  return Map.of("departments",value(departments),"employees",value(employees),"parties",value(parties),"projects",new FinanceProjectDimensions(jdbc,new AccessPolicy()).choices(p,hotel,company),"meaning","实际账套范围内的部门、员工账号、交易对方与已批项目；历史未分配维度不按名称猜测");
 }
 Map<String,Object> manual(TenantPrincipal p,UUID hotel,UUID company,Map<String,Object> input){
  if(!Set.of("departmentId","employeeAccountId","partyId","projectKind","projectId").containsAll(input.keySet()))throw bad("手工辅助维度只能提供实际来源编号，不能提交伪造名称或冻结快照");
  return freeze(p,hotel,company,input,false);
 }
 private Map<String,Object> freeze(TenantPrincipal p,UUID hotel,UUID company,Map<String,Object> input,boolean inherited){
  var result=new LinkedHashMap<String,Object>();result.put("hotelId",hotel);if(company!=null)result.put("companyId",company);result.put("state","UNASSIGNED");
  if(inherited&&input.get("unassignedReason") instanceof String reason)result.put("unassignedReason",reason);
  var b=base(p).addValue("hotel",hotel);
  UUID department=ref(input.get("departmentId"));if(department!=null){var rows=jdbc.queryForList("select d.id,d.name,d.code,d.status from org_unit d where d.tenant_id=:tenant and d.id=:id and (d.id=:hotel or d.unit_type='DEPARTMENT' and exists(select 1 from org_unit_closure c where c.tenant_id=d.tenant_id and c.ancestor_id=:hotel and c.descendant_id=d.id))",b.addValue("id",department));if(rows.size()!=1){if(!inherited)throw bad("实际辅助部门不属于账套组织");result.put("departmentAssignmentState","HISTORICAL_SOURCE_DEPARTMENT_OUTSIDE_CURRENT_BOOK");}else result.put("department",output(rows.getFirst()));}
  UUID employee=ref(input.get("employeeAccountId"));if(employee!=null){var rows=jdbc.queryForList("select u.id account_id,u.display_name,e.id employee_id,e.employee_no,e.employment_status from user_account u join employee e on e.tenant_id=u.tenant_id and e.account_id=u.id where u.tenant_id=:tenant and u.id=:id and exists(select 1 from employee_position_assignment a where a.tenant_id=e.tenant_id and a.employee_id=e.id and (a.org_unit_id=:hotel or exists(select 1 from org_unit_closure c where c.tenant_id=a.tenant_id and c.ancestor_id=:hotel and c.descendant_id=a.org_unit_id)))",b.addValue("id",employee));if(rows.size()!=1){if(!inherited)throw bad("实际辅助员工账号未曾归属账套组织");result.put("employeeAssignmentState","SOURCE_ACCOUNT_UNMAPPED_NOT_GUESSED");}else result.put("employee",output(rows.getFirst()));}
  UUID party=ref(input.get("partyId"));if(party!=null){var rows=jdbc.queryForList("select id,name,kind,version from fo_party where tenant_id=:tenant and hotel_id=:hotel and id=:id",b.addValue("id",party));if(rows.size()!=1){if(!inherited)throw bad("交易对方不属于账套组织");result.put("partyAssignmentState","HISTORICAL_SOURCE_PARTY_OUTSIDE_CURRENT_BOOK");}else result.put("party",output(rows.getFirst()));}
  var project=map(input.get("projectSnapshot"));
  if(inherited&&!project.isEmpty()){
   if(!"NONE".equals(project.get("kind"))&&(!hotel.toString().equals(Objects.toString(project.get("hotelId"),""))||company!=null&&!company.toString().equals(Objects.toString(project.get("companyId"),""))))throw conflict("原冻结项目主体或组织与经济事件不同");result.put("project",project);
  }else if(input.containsKey("projectKind")||input.get("projectId")!=null){result.put("project",new FinanceProjectDimensions(jdbc,new AccessPolicy()).freeze(p,hotel,company,new FinanceProjectDimensions.ProjectRef(Objects.toString(input.get("projectKind"),"NONE"),ref(input.get("projectId")))));}
  if(result.containsKey("department")||result.containsKey("employee")||result.containsKey("party")||result.containsKey("project"))result.put("state","FROZEN_REAL_IDENTITIES");return result;
 }
 Map<String,Object> source(TenantPrincipal p,UUID hotel,String type,UUID source,Map<String,String> metadata){
  var m=metadata==null?Map.<String,String>of():metadata;UUID company=ref(m.get("companyId"));var inputs=new ArrayList<Map<String,Object>>();var b=base(p).addValue("hotel",hotel).addValue("source",source);
  if(type.equals("FX_CASH_CORRECTION")){
   var originals=jdbc.queryForList("select e.id,e.auxiliary_snapshot,e.rule_snapshot from fx_cash_correction c join fa_source_event e on e.tenant_id=c.tenant_id and e.id=c.original_source_event_id where c.tenant_id=:tenant and c.id=:source and c.hotel_id=:hotel and c.status='CONFIRMED'",b);
   if(originals.size()==1){var row=originals.getFirst();var original=map(row.get("auxiliary_snapshot"));var completion=map(map(row.get("rule_snapshot")).get("auxiliaryCompletion"));if(!completion.isEmpty())original=map(completion.get("snapshot"));var allocations=rows(original.getOrDefault("allocations",List.of()));if(!allocations.isEmpty())return Map.of("sourceType",type,"sourceId",source,"capturedFrom","FROZEN_ORIGINAL_ECONOMIC_EVENT","originalSourceEventId",row.get("id"),"allocations",allocations);}
   inputs.add(unassigned("ORIGINAL_CASH_EVENT_HAS_NO_FROZEN_AUXILIARY_IDENTITIES"));
  }
  if(type.startsWith("REIMBURSEMENT")){
   UUID claim=ref(m.get("claimId"));if(claim==null)claim=source;
   var claims=jdbc.queryForList("select * from finance_claim where tenant_id=:tenant and id=:id and hotel_id=:hotel",b.addValue("id",claim));if(!claims.isEmpty()){
    var c=claims.getFirst();company=id(c.get("company_id"));UUID line=ref(m.get("lineId"));
    var rows=line==null?jdbc.queryForList("select * from finance_claim_line where tenant_id=:tenant and claim_id=:claim order by sequence_no",b.addValue("claim",claim)):jdbc.queryForList("select * from finance_claim_line where tenant_id=:tenant and claim_id=:claim and id=:line",b.addValue("claim",claim).addValue("line",line));
    for(var row:rows){var in=fromRow(row);in.put("employeeAccountId",c.get("applicant_id"));in.put("weight",row.get("amount"));inputs.add(in);}if(inputs.isEmpty())inputs.add(new LinkedHashMap<>(Map.of("employeeAccountId",c.get("applicant_id"),"weight",BigDecimal.ONE)));
   }
  }else if(type.startsWith("FO_")){
   var docs=jdbc.queryForList("select * from fo_document where tenant_id=:tenant and id=:id and hotel_id=:hotel",b.addValue("id",ref(m.get("documentId"))));
   if(!docs.isEmpty()){var d=docs.getFirst();company=id(d.get("company_id"));var details=map(d.get("details"));for(var line:rows(d.get("lines"))){var in=new LinkedHashMap<String,Object>();in.put("departmentId",line.get("departmentId"));in.put("partyId",d.get("party_id"));in.put("projectSnapshot",details.get("projectSnapshot"));in.put("employeeAccountId",details.get("borrowerAccountId"));in.put("weight",amount(line.get("quantity")).multiply(amount(line.get("unitPrice"))));inputs.add(in);}}
  }else if(type.equals("FAR_ACCRUAL")){
   var rows=jdbc.queryForList("select r.allocation_snapshot from far_run r join far_plan p on p.tenant_id=r.tenant_id and p.id=r.plan_id where r.tenant_id=:tenant and r.id=:source and p.hotel_id=:hotel",b);if(!rows.isEmpty())for(var share:rows(rows.getFirst().get("allocation_snapshot"))){var in=new LinkedHashMap<>(share);in.put("weight",share.get("amount"));inputs.add(in);}
  }else if(type.equals("FS_PROCUREMENT")){
   UUID line=ref(m.get("lineId"));
   if(line!=null&&"CONSUMED_PRICE_ADJUSTMENT".equals(m.get("nature"))){
    var found=jdbc.queryForList("select a.allocations from fs_price_cost_allocation a join fa_source_event e on e.tenant_id=a.tenant_id and e.id=a.source_event_id where e.tenant_id=:tenant and e.hotel_id=:hotel and e.source_type='FS_PROCUREMENT' and e.source_id=:source and e.metadata->>'lineId'=:lineText and coalesce(e.metadata->>'ruleEventKey',regexp_replace(e.event_key,'_[0-9a-fA-F-]{36}$',''))=:eventKey",b.addValue("lineText",line.toString()).addValue("eventKey",m.get("ruleEventKey")));
    if(found.size()==1){for(var share:rows(found.getFirst().get("allocations"))){var in=new LinkedHashMap<>(share);in.put("weight",share.get("amount"));inputs.add(in);}}
    else inputs.add(unassigned("CONSUMED_PRICE_REQUIRES_INDEPENDENT_ACTUAL_DEPARTMENT_ALLOCATION"));
   }
   else if(line!=null)inputs.addAll(procurementInputs(p,hotel,line));
  }else if(type.equals("FS_PROCUREMENT_RETURN")||type.equals("FS_RETURN_SETTLEMENT")){
   String origin=type.equals("FS_PROCUREMENT_RETURN")?"select r.line_id,f.company_id from fs_return r join fs_recognition f on f.tenant_id=r.tenant_id and f.id=r.recognition_id where r.tenant_id=:tenant and r.id=:source and r.hotel_id=:hotel and r.status='APPROVED'":"select r.line_id,f.company_id from fs_settlement s join fs_return r on r.tenant_id=s.tenant_id and r.id=s.return_id join fs_recognition f on f.tenant_id=r.tenant_id and f.id=r.recognition_id where s.tenant_id=:tenant and s.id=:source and r.hotel_id=:hotel and r.status='APPROVED'";
   var returns=jdbc.queryForList(origin,b);if(returns.size()==1){company=id(returns.getFirst().get("company_id"));inputs.addAll(procurementInputs(p,hotel,id(returns.getFirst().get("line_id"))));}else inputs.add(unassigned("ACTUAL_RETURN_OR_SETTLEMENT_HAS_NO_SINGLE_REVIEWED_ORIGINAL_LINE_NOT_GUESSED"));
  }else if(type.equals("FS_INVENTORY")){
   var found=jdbc.queryForList("select m.*,coalesce(m.financial_department_id,c.financial_department_id) actual_department,case when m.project_kind<>'NONE' then m.project_snapshot else coalesce(c.project_snapshot,m.project_snapshot) end actual_project from store_inventory_movement m left join fs_movement_finance_classification c on c.tenant_id=m.tenant_id and c.movement_id=m.id where m.tenant_id=:tenant and m.id=:source and m.hotel_org_unit_id=:hotel",b);if(!found.isEmpty()){var row=found.getFirst();var in=fromRow(row);in.put("departmentId",row.get("actual_department"));in.put("projectSnapshot",map(row.get("actual_project")));in.put("weight",BigDecimal.ONE);inputs.add(in);}
  }else if(type.equals("FS_ASSET_DEPRECIATION")){
   for(var input:assetInputs(p,hotel,source))inputs.add(assetOriginStillScoped(p,hotel,company,input)?input:unassigned("ORIGINAL_ASSET_AUXILIARY_NOT_CURRENT_ORGANIZATION_NOT_GUESSED"));
  }else if(type.contains("PAYROLL")){
   var in=new LinkedHashMap<String,Object>();in.put("departmentId",m.get("departmentId"));in.put("employeeAccountId",m.get("employeeId"));in.put("weight",BigDecimal.ONE);
   var paid=jdbc.queryForList("select l.department_id,l.employee_id from fb_payroll_payment x join fb_payroll_line l on l.tenant_id=x.tenant_id and l.id=x.line_id join fb_payroll_batch batch on batch.tenant_id=l.tenant_id and batch.id=l.batch_id where x.tenant_id=:tenant and x.id=:source and batch.hotel_id=:hotel",b);if(!paid.isEmpty()){in.put("departmentId",paid.getFirst().get("department_id"));in.put("employeeAccountId",paid.getFirst().get("employee_id"));}inputs.add(in);
  }else if(type.equals("FS_ASSET_GAIN")){
   var found=jdbc.queryForList("select * from fs_asset_gain where tenant_id=:tenant and id=:source and hotel_id=:hotel",b);if(!found.isEmpty()){var in=fromRow(found.getFirst());in.put("weight",BigDecimal.ONE);inputs.add(in);}
  }
  var allocations=new ArrayList<Map<String,Object>>();for(var input:inputs){BigDecimal weight=amount(input.get("weight")).abs();if(weight.signum()>0)allocations.add(Map.of("weight",weight.toPlainString(),"auxiliary",freeze(p,hotel,company,input,true)));}
  if(allocations.isEmpty())allocations.add(Map.of("weight","1","auxiliary",freeze(p,hotel,company,Map.of(),true)));
  return Map.of("sourceType",type,"sourceId",source,"capturedFrom","ACTUAL_SOURCE_IDENTITIES","allocations",allocations);
 }
 private static Map<String,Object> unassigned(String reason){return Map.of("weight",BigDecimal.ONE,"unassignedReason",reason);}
 private boolean assetOriginStillScoped(TenantPrincipal p,UUID hotel,UUID company,Map<String,Object> input){var b=base(p).addValue("hotel",hotel);UUID department=ref(input.get("departmentId"));if(department!=null&&jdbc.queryForObject("select count(*) from org_unit d where d.tenant_id=:tenant and d.id=:department and (d.id=:hotel or d.unit_type='DEPARTMENT' and exists(select 1 from org_unit_closure c where c.tenant_id=d.tenant_id and c.ancestor_id=:hotel and c.descendant_id=d.id))",b.addValue("department",department),Integer.class)!=1)return false;UUID party=ref(input.get("partyId"));if(party!=null&&jdbc.queryForObject("select count(*) from fo_party where tenant_id=:tenant and id=:party and hotel_id=:hotel",b.addValue("party",party),Integer.class)!=1)return false;var project=map(input.get("projectSnapshot"));return project.isEmpty()||"NONE".equals(project.get("kind"))||hotel.toString().equals(Objects.toString(project.get("hotelId"),""))&&Objects.equals(Objects.toString(company,""),Objects.toString(project.get("companyId"),""));}
 private List<Map<String,Object>> procurementInputs(TenantPrincipal p,UUID hotel,UUID line){var found=jdbc.queryForList("select l.*,coalesce(l.financial_department_id,c.financial_department_id) actual_department,case when l.project_kind<>'NONE' then l.project_snapshot else coalesce(c.project_snapshot,l.project_snapshot) end actual_project from procurement_line l join procurement_order o on o.tenant_id=l.tenant_id and o.id=l.order_id left join fs_procurement_finance_classification c on c.tenant_id=l.tenant_id and c.line_id=l.id where l.tenant_id=:tenant and l.id=:line and o.hotel_id=:hotel",base(p).addValue("line",line).addValue("hotel",hotel));if(found.size()!=1)return List.of(unassigned("ORIGINAL_ASSET_SOURCE_BELONGS_TO_ANOTHER_HOTEL_OR_IS_UNASSIGNED"));var row=found.getFirst();var input=fromRow(row);input.put("departmentId",row.get("actual_department"));input.put("projectSnapshot",map(row.get("actual_project")));input.put("weight",BigDecimal.ONE);return List.of(input);}
 private List<Map<String,Object>> assetInputs(TenantPrincipal p,UUID hotel,UUID card){var b=base(p).addValue("card",card).addValue("hotel",hotel);var found=jdbc.queryForList("select * from fs_asset_card where tenant_id=:tenant and id=:card and hotel_id=:hotel",b);if(found.size()!=1)return List.of(unassigned("REAL_ASSET_CARD_NOT_IN_THIS_BOOK_SCOPE"));var row=found.getFirst();
  if(row.get("procurement_line_id")!=null)return procurementInputs(p,hotel,id(row.get("procurement_line_id")));
  if(row.get("gain_id")!=null){var gains=jdbc.queryForList("select * from fs_asset_gain where tenant_id=:tenant and id=:gain and hotel_id=:hotel and company_id=:company",b.addValue("gain",row.get("gain_id")).addValue("company",row.get("company_id")));if(gains.size()!=1)return List.of(unassigned("TRANSFERRED_ASSET_GAIN_HAS_NO_CONFIRMED_CURRENT_HOTEL_DEPARTMENT"));var input=fromRow(gains.getFirst());input.put("weight",BigDecimal.ONE);return List.of(input);}
  if(row.get("source_document_id")!=null){var docs=jdbc.queryForList("select * from fo_document where tenant_id=:tenant and id=:document and hotel_id=:hotel and company_id=:company",b.addValue("document",row.get("source_document_id")).addValue("company",row.get("company_id")));if(docs.size()!=1)return List.of(unassigned("TRANSFERRED_ASSET_ORIGINAL_DEPARTMENT_NOT_CURRENT_HOTEL"));var doc=docs.getFirst();var result=new ArrayList<Map<String,Object>>();for(var line:rows(doc.get("lines"))){var input=new LinkedHashMap<String,Object>();input.put("departmentId",line.get("departmentId"));input.put("projectSnapshot",map(doc.get("details")).get("projectSnapshot"));input.put("partyId",doc.get("party_id"));input.put("weight",amount(line.get("quantity")).multiply(amount(line.get("unitPrice"))));result.add(input);}return result.isEmpty()?List.of(unassigned("ORIGINAL_ASSET_DOCUMENT_HAS_NO_AUXILIARY_IDENTITIES")):result;}
  return List.of(unassigned("OPENING_ASSET_HAS_NO_MIGRATED_AUXILIARY_IDENTITIES_NOT_GUESSED"));
 }
 private static Map<String,Object> fromRow(Map<String,Object> row){var in=new LinkedHashMap<String,Object>();in.put("departmentId",row.get("department_id"));in.put("projectSnapshot",map(row.get("project_snapshot")));return in;}
 private static UUID ref(Object value){if(value==null||value.toString().isBlank())return null;try{return UUID.fromString(value.toString());}catch(IllegalArgumentException e){throw bad("辅助维度须使用实际来源编号");}}
 void completeMovement(TenantPrincipal p,UUID movement){
  if(!completionInstalled())return;
  var b=base(p).addValue("movement",movement);
  if(jdbc.queryForObject("select count(*) from fs_movement_finance_classification where tenant_id=:tenant and movement_id=:movement",b,Integer.class)!=1)throw conflict("缺少实际已独立确认的不可变领用分类");
  for(var event:jdbc.queryForList("select id from fa_source_event where tenant_id=:tenant and source_type='FS_INVENTORY' and source_id=:movement order by id",b))complete(p,id(event.get("id")),"MOVEMENT_CLASSIFICATION",movement);
 }
 void completePriceCosts(TenantPrincipal p,UUID event){
  if(!completionInstalled())return;
  if(jdbc.queryForObject("select count(*) from fs_price_cost_allocation where tenant_id=:tenant and source_event_id=:event",base(p).addValue("event",event),Integer.class)!=1)throw conflict("缺少实际已独立确认的不可变耗用价差分配");
  complete(p,event,"PRICE_COST",event);
 }
 private boolean completionInstalled(){return installed()&&Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('fa_auxiliary_completion') is not null",Map.of(),Boolean.class));}
 private void complete(TenantPrincipal p,UUID eventId,String kind,UUID classification){
  var access=new AccessPolicy();access.requirePermission("finance.review");access.requireAnyPermission("finance.read","finance.accounting.read");
  var b=base(p).addValue("event",eventId);var events=jdbc.queryForList("select * from fa_source_event where tenant_id=:tenant and id=:event for update",b);if(events.size()!=1)throw missing();var event=events.getFirst();UUID hotel=id(event.get("hotel_id"));access.requireOrgScope(hotel);
  var prior=jdbc.queryForList("select * from fa_auxiliary_completion where tenant_id=:tenant and event_id=:event",b);
  if(!prior.isEmpty()){var original=prior.getFirst();if(!kind.equals(original.get("classification_kind"))||!classification.equals(original.get("classification_id"))||!Objects.equals(event.get("request_hash"),original.get("source_request_hash")))throw conflict("原来源已有不同不可变辅助分类补充，不覆盖历史");return;}
  // Classification may still complete budget attribution after posting; it must not retrofit ledger history.
  if(event.get("voucher_id")!=null)return;
  var metadata=new LinkedHashMap<String,String>();for(var entry:map(event.get("metadata")).entrySet())if(entry.getValue()!=null)metadata.put(entry.getKey(),entry.getValue().toString());
  metadata.putIfAbsent("ruleEventKey",event.get("event_key").toString().replaceFirst("_[0-9a-fA-F-]{36}$",""));
  var snapshot=source(p,hotel,event.get("source_type").toString(),id(event.get("source_id")),metadata);
  preserveExplicitSource(map(event.get("auxiliary_snapshot")),snapshot);
  jdbc.update("insert into fa_auxiliary_completion(event_id,tenant_id,source_request_hash,classification_kind,classification_id,movement_id,price_cost_event_id,snapshot,actor_id) values(:event,:tenant,:hash,:kind,:classification,:movement,:price,cast(:snapshot as jsonb),:actor)",b.addValue("hash",event.get("request_hash")).addValue("kind",kind).addValue("classification",classification).addValue("movement",kind.equals("MOVEMENT_CLASSIFICATION")?classification:null).addValue("price",kind.equals("PRICE_COST")?classification:null).addValue("snapshot",json(snapshot)));
 }
 private void preserveExplicitSource(Map<String,Object> original,Map<String,Object> completed){
  for(var allocation:rows(original.getOrDefault("allocations",List.of()))){var before=map(allocation.get("auxiliary"));for(var dimension:List.of("department","employee","party","project")){var identity=map(before.get(dimension));Object key=identity.get(dimension.equals("employee")?"accountId":"id");boolean explicit=key!=null||dimension.equals("project")&&"EXPLICIT_NONE".equals(identity.get("designation"));if(!explicit)continue;
    for(var next:rows(completed.get("allocations"))){var actual=map(map(next.get("auxiliary")).get(dimension));Object after=actual.get(dimension.equals("employee")?"accountId":"id");if(!Objects.equals(Objects.toString(key,null),Objects.toString(after,null))||dimension.equals("project")&&!Objects.equals(identity.get("kind"),actual.get("kind")))throw conflict("辅助分类补充不能覆盖原来源已明确冻结的真实"+dimension+"；已形成凭证只能走开放期受控重分类");map(next.get("auxiliary")).put(dimension,identity);}
   }}
 }
 List<FinanceAccountingModels.Line> inherit(List<FinanceAccountingModels.Line> lines,Map<String,Object> snapshot){
  var allocations=rows(snapshot.getOrDefault("allocations",List.of()));if(allocations.isEmpty())return lines;BigDecimal total=allocations.stream().map(a->amount(a.get("weight"))).reduce(BigDecimal.ZERO,BigDecimal::add);if(total.signum()<=0)throw conflict("真实来源辅助分摊基数无效");var result=new ArrayList<FinanceAccountingModels.Line>();
  for(var line:lines){BigDecimal left=line.debit().add(line.credit());for(int i=0;i<allocations.size();i++){var allocation=allocations.get(i);BigDecimal part=i==allocations.size()-1?left:line.debit().add(line.credit()).multiply(amount(allocation.get("weight"))).divide(total,2,RoundingMode.DOWN);left=left.subtract(part);if(part.signum()>0)result.add(new FinanceAccountingModels.Line(line.accountCode(),line.debit().signum()>0?part:BigDecimal.ZERO,line.credit().signum()>0?part:BigDecimal.ZERO,line.summary(),line.cashFlowCategory(),map(allocation.get("auxiliary"))));}}
  if(result.size()>500)throw conflict("实际辅助分摊超过500行，请按真实经济事项分拆来源，未截断或合并为虚假维度");return result;
 }
 Map<String,Object> inherited(UUID hotel,UUID company,Map<String,Object> snapshot){
  if(snapshot.isEmpty())return Map.of("hotelId",hotel,"companyId",company,"state","LEGACY_UNASSIGNED");
  if(!hotel.toString().equals(Objects.toString(snapshot.get("hotelId"),""))||snapshot.get("companyId")!=null&&!company.toString().equals(snapshot.get("companyId").toString()))throw conflict("冻结辅助维度不属于凭证账套主体");return snapshot;
 }
 void required(Map<String,Object> account,Map<String,Object> auxiliary){for(Object required:list(account.get("required_dimensions"))){boolean present=switch(required.toString()){case "DEPARTMENT"->map(auxiliary.get("department")).get("id")!=null;case "EMPLOYEE"->map(auxiliary.get("employee")).get("accountId")!=null;case "PARTY"->map(auxiliary.get("party")).get("id")!=null;case "PROJECT"->Set.of("CONTRACT","CIP").contains(map(auxiliary.get("project")).get("kind"));default->false;};if(!present)throw conflict("科目 "+account.get("code")+" 缺少已发布要求的真实辅助维度 "+required+"；请补原来源或独立调整，不能按空维度过账");}}
 private static List<?> list(Object value){if(value==null)return List.of();if(value instanceof List<?> values)return values;try{return JSON.readValue(value.toString(),List.class);}catch(Exception e){throw bad("辅助维度设置无效");}}
}
