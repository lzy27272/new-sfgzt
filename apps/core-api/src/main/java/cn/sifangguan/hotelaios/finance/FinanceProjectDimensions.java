package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** A project is an explicit reference, never a name/UUID inferred from an unrelated business source. */
public final class FinanceProjectDimensions {
 private final NamedParameterJdbcTemplate jdbc;private final AccessPolicy access;
 public FinanceProjectDimensions(NamedParameterJdbcTemplate jdbc,AccessPolicy access){this.jdbc=jdbc;this.access=access;}
 public boolean installed(){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema=current_schema() and table_name='finance_category' and column_name='budget_code')",Map.of(),Boolean.class));}
 public record ProjectRef(String kind,UUID id){public ProjectRef{kind=kind==null||kind.isBlank()?"NONE":kind;if(!Set.of("NONE","CONTRACT","CIP").contains(kind)||kind.equals("NONE")!=(id==null))throw bad("项目须明确选择无项目、实际已批合同或在建工程卡，不能只填任意编号");}public String key(){return kind.equals("NONE")?"NONE":kind+":"+id;}}
 public static ProjectRef from(Map<String,?> values){Object raw=values.get("projectId");return new ProjectRef(Objects.toString(values.get("projectKind"),"NONE"),raw==null||raw.toString().isBlank()?null:UUID.fromString(raw.toString()));}
 public Map<String,Object> freeze(TenantPrincipal p,UUID hotel,UUID company,ProjectRef ref){
  access.requireOrgScope(hotel);if(ref.kind().equals("NONE")){var out=new LinkedHashMap<String,Object>();out.put("kind","NONE");out.put("id",null);out.put("key","NONE");out.put("name","未指定项目");out.put("designation","EXPLICIT_NONE");return out;}
  if(company==null)throw conflict("实际法人主体尚未配置，不能指定项目，请先补齐主体而不是按零额处理");
  var b=base(p).addValue("hotel",hotel).addValue("company",company).addValue("project",ref.id()).addValue("reader",reader(p)).addValue("assetReader",p.hasPermission("*")||p.hasPermission("finance.read")||p.hasPermission("finance.accounting.read"));
  String query=ref.kind().equals("CONTRACT")?"select d.id,d.title name,d.version source_version,d.status,d.hotel_id,d.company_id from fo_document d where d.tenant_id=:tenant and d.id=:project and d.hotel_id=:hotel and d.company_id=:company and d.type='CONTRACT' and d.status='APPROVED' and "+contractVisible():"select d.id,d.asset_code name,d.version source_version,d.status,d.hotel_id,d.company_id from fs_asset_card d join store_fixed_asset a on a.tenant_id=d.tenant_id and a.id=d.physical_asset_id and a.hotel_org_unit_id=d.hotel_id where d.tenant_id=:tenant and d.id=:project and d.hotel_id=:hotel and d.company_id=:company and d.status='CIP' and a.status<>'RETIRED' and :assetReader";
  var rows=jdbc.queryForList(query,b);if(rows.size()!=1)throw conflict("项目锚点不在当前可见范围、未完成审批/不再在建或主体不一致；不能把来源缺失当作零预算");var row=rows.getFirst();var out=output(row);out.put("kind",ref.kind());out.put("key",ref.key());return out;
 }
 public Map<String,Object> choices(TenantPrincipal p,UUID hotel,UUID company){access.requireOrgScope(hotel);var b=base(p).addValue("hotel",hotel).addValue("company",company).addValue("reader",reader(p));var result=new ArrayList<Map<String,Object>>();
  for(var row:jdbc.queryForList("select d.id,d.title name,d.version from fo_document d where d.tenant_id=:tenant and d.hotel_id=:hotel and d.company_id=:company and d.type='CONTRACT' and d.status='APPROVED' and "+contractVisible()+" order by d.title,d.id limit 501",b)){var item=output(row);item.put("kind","CONTRACT");item.put("key","CONTRACT:"+row.get("id"));result.add(item);}
  if(p.hasPermission("*")||p.hasPermission("finance.read")||p.hasPermission("finance.accounting.read"))for(var row:jdbc.queryForList("select d.id,d.asset_code name,d.version from fs_asset_card d join store_fixed_asset a on a.tenant_id=d.tenant_id and a.id=d.physical_asset_id and a.hotel_org_unit_id=d.hotel_id where d.tenant_id=:tenant and d.hotel_id=:hotel and d.company_id=:company and d.status='CIP' and a.status<>'RETIRED' order by d.asset_code,d.id limit 501",b)){var item=output(row);item.put("kind","CIP");item.put("key","CIP:"+row.get("id"));result.add(item);}
  return Map.of("items",result.stream().limit(500).toList(),"complete",result.size()<=500,"meaning","仅当前可见、同组织同实际法人已批合同或在建卡；没有选择不代表来源完整或项目预算为零");}
 private static boolean reader(TenantPrincipal p){return p.hasPermission("*")||p.hasPermission("finance.read")||p.hasPermission("finance.configure")||p.hasPermission("finance.accounting.read");}
 public void verifyFrozen(TenantPrincipal p,UUID hotel,UUID company,Map<String,Object> values){var ref=from(values);if(ref.kind().equals("NONE"))return;var snapshot=map(values.get("projectSnapshot"));var actual=freeze(p,hotel,company,ref);if(!ref.key().equals(values.get("projectKey"))||!ref.key().equals(snapshot.get("key"))||!Objects.equals(actual.get("sourceVersion"),snapshot.get("sourceVersion")))throw conflict("项目锚点版本或冻结来源已变化，请退回核对并重新保存，不能覆盖原审核快照");}
 private static String contractVisible(){return "(:reader or d.applicant_id=:actor or d.cashier_account_id=:actor or d.approval_route @> jsonb_build_array(cast(:actor as text)))";}
}
