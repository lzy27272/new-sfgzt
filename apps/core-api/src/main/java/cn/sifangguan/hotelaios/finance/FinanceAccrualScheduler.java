package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
@Component @ConditionalOnProperty(prefix="app.finance-accrual",name="scheduler-enabled",havingValue="true")
public class FinanceAccrualScheduler {
 private static final Logger LOG=LoggerFactory.getLogger(FinanceAccrualScheduler.class);
 private final FinanceAccrualControlService service;private final EffectiveIdentityService identities;private final List<UUID> tenants;
 public FinanceAccrualScheduler(FinanceAccrualControlService service,EffectiveIdentityService identities,@Value("${app.finance-accrual.tenant-ids:}")String tenants){this.service=service;this.identities=identities;this.tenants=Arrays.stream(tenants.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).distinct().toList();if(this.tenants.isEmpty())throw new IllegalArgumentException("启用租赁/待摊自动检查须明确租户白名单");}
 @Scheduled(fixedDelayString="${app.finance-accrual.fixed-delay-ms:60000}",initialDelayString="${app.finance-accrual.initial-delay-ms:30000}")
 public void process(){LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));for(UUID tenant:tenants){try{for(var row:service.scheduledPlans(tenant)){UUID plan=(UUID)row.get("id");String month=null;try{var principal=identities.inspectNotificationRecipient(tenant,(UUID)row.get("created_by"),(UUID)row.get("created_assignment_id")).orElseThrow(()->new IllegalArgumentException("原制订账号或任职失效，请后台配置有权执行人，不使用伪造身份"));TenantContext.set(principal);service.finalizeTermination(plan);for(int n=0;n<12;n++){month=service.nextScheduledMonth(tenant,plan,today);if(month==null)break;int version=((Number)service.detail(plan).get("version")).intValue();UUID request=UUID.nameUUIDFromBytes(("far-auto:"+tenant+":"+plan+":"+month).getBytes(java.nio.charset.StandardCharsets.UTF_8));service.run(plan,new FinanceAccrualModels.Run(request,month,version));}}catch(RuntimeException error){service.scheduleIssue(tenant,plan,month==null?today.withDayOfMonth(1):YearMonth.parse(month).atDay(1),Objects.toString(error.getMessage(),"未能完成自动生成，请查看原计划依赖"));LOG.warn("Accrual schedule needs attention tenant={} plan={}",tenant,plan);}finally{TenantContext.clear();}}}catch(RuntimeException e){LOG.error("Accrual scheduler failed tenant={}",tenant,e);TenantContext.clear();}}}
}
