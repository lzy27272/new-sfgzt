package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Immutable source-bound deputy snapshots; expiry/revocation returns responsibility to the original reviewer. */
final class FinanceDelegationControl {
 private final NamedParameterJdbcTemplate jdbc;
 FinanceDelegationControl(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 boolean installed(){return new FinanceControlGuard(jdbc).table("fd_route");}
 UUID current(TenantPrincipal p,String kind,UUID source,int node,UUID original){
  if(!installed())return original;
  return jdbc.queryForObject("select fd_current_reviewer(:tenant,:kind,:source,:node,:original)",base(p).addValue("kind",kind).addValue("source",source).addValue("node",node).addValue("original",original),UUID.class);
 }
 void freeze(TenantPrincipal p,UUID hotel,String kind,UUID source,int sourceVersion,List<UUID> route,UUID applicant,UUID cashier){
  if(!installed())return;
  var b=base(p).addValue("hotel",hotel).addValue("kind",kind).addValue("source",source).addValue("version",sourceVersion);
  var deputyNodes=new HashMap<UUID,Integer>();
  for(int node=0;node<route.size();node++){
   UUID original=route.get(node);
   var rows=jdbc.queryForList("select * from fd_delegation where tenant_id=:tenant and hotel_id=:hotel and source_kind=:kind and original_id=:original and status='PUBLISHED' and valid_until>statement_timestamp() order by valid_from,id",b.addValue("original",original));
   Instant priorEnd=null;
   for(var row:rows){
    Instant from=instant(row.get("valid_from")),until=instant(row.get("valid_until"));
    if(priorEnd!=null&&from.isBefore(priorEnd))throw conflict("同一审核节点存在重叠代理授权，请先撤销重叠授权");
    priorEnd=until;
    UUID deputy=id(row.get("deputy_id"));Integer priorNode=deputyNodes.putIfAbsent(deputy,node);
    if(deputy.equals(applicant)||deputy.equals(cashier)||route.contains(deputy)||(priorNode!=null&&priorNode!=node))throw conflict("代理人不能是申请人、出纳或其他审批节点人员，请调整独立代理授权");
    jdbc.update("insert into fd_route(tenant_id,source_kind,source_id,source_version,node,delegation_id,original_id,deputy_id,valid_from,valid_until) values(:tenant,:kind,:source,:version,:node,:delegation,:original,:deputy,:from,:until)",b.addValue("node",node).addValue("delegation",row.get("id")).addValue("deputy",deputy).addValue("from",row.get("valid_from")).addValue("until",row.get("valid_until")));
   }
  }
 }
 private static Instant instant(Object value){return value instanceof OffsetDateTime date?date.toInstant():((java.util.Date)value).toInstant();}
 String currentSql(String kind,String alias,String node,String original){return installed()?"fd_current_reviewer("+alias+".tenant_id,'"+kind+"',"+alias+".id,"+node+","+original+")":original;}
}
