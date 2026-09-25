package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="app.ai-evidence",name="enabled",havingValue="true")
public class AiEvidenceWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AiEvidenceWorker.class);
    private final AiEvidenceService service;
    private final AiVisionGateway provider;
    private final EffectiveIdentityService identities;
    private final List<UUID> tenants;
    public AiEvidenceWorker(AiEvidenceService service,AiVisionGateway provider,EffectiveIdentityService identities,
            @Value("${app.ai-evidence.tenant-ids:}") String tenants) {
        this.service=service;this.provider=provider;this.identities=identities;
        this.tenants=Arrays.stream(tenants.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).distinct().toList();
        if(this.tenants.isEmpty()) throw new IllegalArgumentException("启用AI检查必须配置明确的租户清单");
    }
    @Scheduled(scheduler="aiEvidenceScheduler",fixedDelayString="${app.ai-evidence.fixed-delay-ms:10000}",initialDelay=15000)
    public void process() {
        if(!provider.ready()) return;
        for(UUID tenant:tenants) for(var queued:service.pending(tenant)) {
            UUID id=(UUID)queued.get("id");
            var previous=TenantContext.current();
            try {
                TenantPrincipal principal;
                try { principal=identities.resolve(tenant,(UUID)queued.get("requested_by"),UUID.randomUUID(),(UUID)queued.get("requested_assignment_id")); }
                catch(RuntimeException denied) { service.invalidateRequester(tenant,id);continue; }
                TenantContext.set(principal);
                Map<String,Object> job;
                try { job=service.claim(id); }
                catch(RuntimeException denied) { service.invalidateRequester(tenant,id);continue; }
                if(job.isEmpty()) continue;
                AiVisionGateway.Observation result=null;String error=null;
                try { var images=service.images(job);result=provider.inspect(service.node(job.get("configuration")),images); }
                catch(RuntimeException failed) { error="PROVIDER_OR_EVIDENCE_UNAVAILABLE"; }
                service.finish(job,result,error);
            } catch(RuntimeException failure) {
                // Do not log model responses, photographs, employee names or provider credentials.
                log.warn("AI evidence job persistence interrupted; its lease will recover. checkId={}",id);
            } finally { if(previous.isPresent()) TenantContext.set(previous.get());else TenantContext.clear(); }
        }
    }
}
