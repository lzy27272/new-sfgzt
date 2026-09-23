package cn.sifangguan.hotelaios.storeassets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="app.procurement",name="scheduler-enabled",havingValue="true")
public class ProcurementScheduler {
    private static final Logger log=LoggerFactory.getLogger(ProcurementScheduler.class);
    private final ProcurementService service;
    private final List<UUID> tenants;
    public ProcurementScheduler(ProcurementService service,@Value("${app.procurement.tenant-ids:}") String tenants) {
        this.service=service;
        this.tenants=Arrays.stream(tenants.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).distinct().toList();
        if(this.tenants.isEmpty())throw new IllegalArgumentException("启用采购自动化必须配置 PROCUREMENT_TENANT_IDS");
    }
    @Scheduled(fixedDelayString="${app.procurement.fixed-delay-ms:60000}",initialDelayString="${app.procurement.initial-delay-ms:30000}")
    public void process() {
        for(UUID tenant:tenants)try{service.automate(tenant);}catch(RuntimeException e){log.error("Procurement automation failed for tenant {}",tenant,e);}
    }
}
