package cn.sifangguan.hotelaios.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="app.work-expectation.sla",name="scheduler-enabled",havingValue="true")
public class OperationsPlanReminderScheduler {
    private static final Logger log=LoggerFactory.getLogger(OperationsPlanReminderScheduler.class);
    private final OperationsPlanReminderService service;
    private final List<UUID> tenants;
    public OperationsPlanReminderScheduler(OperationsPlanReminderService service,@Value("${app.work-expectation.sla.tenant-ids:}") String configured) {
        this.service=service;List<UUID> parsed=new ArrayList<>();
        try {for(String value:configured.split(","))if(!value.isBlank())parsed.add(UUID.fromString(value.trim()));}
        catch(IllegalArgumentException e){parsed.clear();log.error("Invalid tenant list; operations plan reminders are disabled");}
        this.tenants=List.copyOf(parsed);
    }
    @Scheduled(fixedDelayString="${app.work-expectation.sla.fixed-delay-ms:60000}",initialDelayString="${app.work-expectation.sla.initial-delay-ms:30000}")
    public void process() {for(UUID tenant:tenants)try{service.processTenant(tenant,OffsetDateTime.now());}catch(RuntimeException e){log.error("Operations plan reminder failed for tenant {}",tenant,e);}}
}
