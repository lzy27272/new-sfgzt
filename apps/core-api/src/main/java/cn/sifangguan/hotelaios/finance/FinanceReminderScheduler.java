package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import java.util.*;
@Component @EnableScheduling @ConditionalOnProperty(name="app.finance-reminders.enabled",havingValue="true")
public class FinanceReminderScheduler {
 private static final Logger LOG=LoggerFactory.getLogger(FinanceReminderScheduler.class);
 private final FinanceReminderService service;private final EffectiveIdentityService identities;private final List<UUID> tenants;
 public FinanceReminderScheduler(FinanceReminderService service,EffectiveIdentityService identities,@Value("${app.finance-reminders.tenant-ids:}")String tenantIds){this.service=service;this.identities=identities;this.tenants=Arrays.stream(tenantIds.split(",")).map(String::trim).filter(v->!v.isBlank()).map(UUID::fromString).distinct().toList();if(tenants.isEmpty())throw new IllegalArgumentException("财务自动提醒必须明确租户白名单");}
 @Scheduled(cron="${app.finance-reminders.cron:0 0 9,15 * * *}",zone="Asia/Shanghai")
 public void process(){for(UUID tenant:tenants){for(int page=0;page<=10000;page++){List<Map<String,Object>> candidates=service.candidates(tenant,page);for(var row:candidates){UUID account=(UUID)row.get("account_id"),assignment=(UUID)row.get("assignment_id");try{var p=identities.inspectNotificationRecipient(tenant,account,assignment);if(p.isPresent()){TenantContext.set(p.get());service.remindCurrent();}}catch(RuntimeException e){LOG.error("Finance reminder failed tenant={} account={} assignment={}",tenant,account,assignment,e);}finally{TenantContext.clear();}}if(candidates.size()<500)break;if(page==10000)LOG.error("Finance reminder candidate paging limit reached tenant={}",tenant);}}}
}
