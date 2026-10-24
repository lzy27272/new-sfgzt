package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Current-node reminders reuse the same paginated and authorized queue as the position workbench. */
@Service @Transactional
public class FinanceReminderService {
 private final NamedParameterJdbcTemplate jdbc;private final TenantDatabaseContext database;private final FinanceService finance;
 public FinanceReminderService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,FinanceService finance){this.jdbc=jdbc;this.database=database;this.finance=finance;}
 @Transactional(readOnly=true)
 public List<Map<String,Object>> candidates(UUID tenant,int page){database.apply(tenant);return jdbc.queryForList("select u.id account_id,a.id assignment_id from user_account u left join employee e on e.tenant_id=u.tenant_id and e.account_id=u.id and e.employment_status='ACTIVE' left join employee_position_assignment a on a.tenant_id=e.tenant_id and a.employee_id=e.id and a.status='ACTIVE' and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date) where u.tenant_id=:tenant and u.status='ACTIVE' order by u.id,a.id nulls first limit 500 offset :offset",Map.of("tenant",tenant,"offset",page*500));}
 public int remindCurrent(){var p=TenantContext.require();database.apply(p.tenantId());var permissions=Set.of("finance.submit","finance.read","finance.review","finance.pay","finance.configure","finance.tax","finance.accounting.write","finance.accounting.close","finance.archive.read","finance.accounting.read","finance.accounting.review","finance.accounting.post","finance.archive.grant");if(!p.hasPermission("*")&&permissions.stream().noneMatch(p::hasPermission))return 0;
  LocalDate day=jdbc.queryForObject("select (clock_timestamp() at time zone timezone)::date from tenant where id=:tenant",base(p),LocalDate.class);int created=0;
  for(int page=0;page<=10000;page++){var result=finance.todos(page);var items=rows(result.get("items"));for(var item:items){String domain=item.get("domain").toString(),kind=item.get("kind").toString(),type=item.get("documentType").toString();String source=FinanceCurrentNode.sourceType(item);if(source==null)continue;
    String notification=switch(source){case "FINANCE"->kind.equals("returned")?"FINANCE_RETURNED":kind.equals("review")?"FINANCE_REVIEW_PENDING":"FINANCE_PAYMENT_PENDING";case "FINANCE_OPERATION"->kind.equals("returned")?"FINANCE_OPERATION_RETURNED":kind.equals("review")?"FINANCE_OPERATION_REVIEW_PENDING":"FINANCE_OPERATION_PAYMENT_PENDING";case "FINANCE_CASH"->kind.equals("review")?"FINANCE_CASH_REVIEW_PENDING":"FINANCE_CASH_PAYMENT_PENDING";default->"FINANCE_REVIEW_PENDING";};
    String key="finance-daily:"+day+":"+p.actorId()+":"+source+":"+item.get("id")+":"+kind+":"+item.get("version");
    created+=jdbc.update("insert into notification(tenant_id,recipient_account_id,notification_type,title,content,source_type,source_id,idempotency_key) values(:tenant,:actor,:type, :title,'您有当前权限范围内的财务事项待处理，请进入岗位工作台核对。提醒不代表审核、付款、过账或归档已完成。',:source,:id,:key) on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing",base(p).addValue("title",item.get("dueAt")!=null&&LocalDate.parse(item.get("dueAt").toString()).isBefore(day)?"财务事项已逾期，请处理当前节点":"财务当前节点待办提醒").addValue("type",notification).addValue("source",source).addValue("id",item.get("id")).addValue("key",key));
   }if((page+1)*50>=((Number)result.get("total")).longValue())return created;
  }throw conflict("财务待办超过安全分页范围，请处理来源积压；未将第一页冒充全部提醒");
 }
}
