package cn.sifangguan.hotelaios.academy;
import cn.sifangguan.hotelaios.shared.context.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.util.*;
@Component
public class AcademyWorker {
    private static final Logger log=LoggerFactory.getLogger(AcademyWorker.class);
    private final AcademyPlans plans;private final boolean enabled;private final List<UUID> tenants;
    public AcademyWorker(AcademyPlans plans,@Value("${app.academy.worker-enabled:false}") boolean enabled,
            @Value("${app.academy.tenant-ids:}") String tenantIds){
        this.plans=plans;this.enabled=enabled;
        this.tenants=tenantIds.isBlank()?List.of():Arrays.stream(tenantIds.split(",")).map(String::trim).map(UUID::fromString).distinct().toList();
        if(enabled&&tenants.isEmpty())throw new IllegalArgumentException("Academy worker requires explicit tenant IDs");
    }
    @Scheduled(fixedDelayString="${app.academy.fixed-delay-ms:30000}",initialDelayString="${app.academy.initial-delay-ms:30000}")
    public void tick(){
        if(!enabled)return;
        for(UUID tenant:tenants){
            try{
                TenantContext.set(new TenantPrincipal(tenant,new UUID(0,0),"ACADEMY_WORKER",Set.of(),Set.of(),Set.of(),Set.of(),true,UUID.randomUUID()));
                plans.processTenant(tenant);
            }catch(RuntimeException error){log.error("Academy processing failed tenant={}",tenant,error);}
            finally{TenantContext.clear();}
        }
    }
}
