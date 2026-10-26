package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.rows;

/** A queued notification is not an authorization: re-resolve the recipient immediately before sending. */
@Service
@Transactional(readOnly=true)
public class FinanceDeliveryEligibilityService {
 private final NamedParameterJdbcTemplate jdbc;
 private final TenantDatabaseContext database;
 private final EffectiveIdentityService identities;
 private final FinanceService finance;
 public FinanceDeliveryEligibilityService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,EffectiveIdentityService identities,FinanceService finance) {
  this.jdbc=jdbc;this.database=database;this.identities=identities;this.finance=finance;
 }
 public boolean eligible(UUID tenant,UUID account,UUID requestedAssignment,String sourceType,UUID sourceId) {
  if(account==null||sourceId==null||sourceType==null||!sourceType.startsWith("FINANCE"))return false;
  var prior=TenantContext.current();
  try {
   database.apply(tenant);
   var candidates=new ArrayList<UUID>();
   if(requestedAssignment!=null)candidates.add(requestedAssignment);
   else {
    // Null still resolves the account's real primary/business identity or platform role. Never synthesize permissions.
    candidates.add(null);
    candidates.addAll(jdbc.queryForList("select a.id from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where a.tenant_id=:tenant and e.account_id=:account and e.employment_status='ACTIVE' and a.status='ACTIVE' and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date) order by a.id",Map.of("tenant",tenant,"account",account),UUID.class));
   }
   for(UUID assignment:candidates) {
    var identity=identities.inspectNotificationRecipient(tenant,account,assignment);
    if(identity.isEmpty())continue;
    var p=identity.get();
    if(!p.hasPermission("*")&&Set.of("finance.submit","finance.read","finance.review","finance.pay","finance.configure","finance.tax","finance.accounting.write","finance.accounting.close","finance.archive.read","finance.accounting.read","finance.accounting.review","finance.accounting.post","finance.archive.grant").stream().noneMatch(p::hasPermission))continue;
    TenantContext.set(p);
    for(int page=0;page<=10000;page++) {
     var result=finance.todos(page);
     if(rows(result.get("items")).stream().anyMatch(item->sourceId.toString().equals(String.valueOf(item.get("id")))&&sourceType.equals(FinanceCurrentNode.sourceType(item))))return true;
     if((page+1)*50>=((Number)result.get("total")).longValue())break;
     if(page==10000)throw new IllegalStateException("财务发送权限复核超过完整分页上限，未发送未经确认的提醒");
    }
   }
   return false;
  } finally {
   if(prior.isPresent())TenantContext.set(prior.get());else TenantContext.clear();
  }
 }
}
