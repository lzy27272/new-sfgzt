package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AiEvidenceWorkerTest {
    final AiEvidenceService service=mock(AiEvidenceService.class);
    final AiVisionGateway gateway=mock(AiVisionGateway.class);
    final EffectiveIdentityService identities=mock(EffectiveIdentityService.class);
    final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),id=UUID.randomUUID();
    final AiEvidenceWorker worker=new AiEvidenceWorker(service,gateway,identities,tenant.toString());
    final Map<String,Object> queued=Map.of("id",id,"requested_by",actor);
    final Map<String,Object> job=Map.of("id",id,"configuration","{}");
    @AfterEach void clear() { TenantContext.clear(); }
    void ready() {
        when(gateway.ready()).thenReturn(true);
        when(service.pending(tenant)).thenReturn(List.of(queued));
        when(identities.resolve(eq(tenant),eq(actor),any(),isNull()))
            .thenReturn(new TenantPrincipal(tenant,actor,"FRONT_DESK",Set.of(),UUID.randomUUID()));
        when(service.claim(id)).thenReturn(job);
        when(service.images(job)).thenReturn(List.of());
        when(service.node("{}")).thenReturn(new ObjectMapper().createObjectNode());
    }
    @Test void disabledProviderNeverReadsOrSendsImages() {
        worker.process();verifyNoInteractions(service,identities);verify(gateway,never()).inspect(any(),any());
    }
    @Test void outboundFailureIsExplicitAndThreadIdentityIsRestored() {
        ready();var previous=new TenantPrincipal(UUID.randomUUID(),UUID.randomUUID(),"CEO",Set.of(),UUID.randomUUID());
        TenantContext.set(previous);
        when(gateway.inspect(any(),any())).thenThrow(new IllegalStateException("mock timeout"));
        worker.process();
        verify(service).finish(job,null,"PROVIDER_OR_EVIDENCE_UNAVAILABLE");
        assertThat(TenantContext.current()).contains(previous);
    }
    @Test void revokedRequesterNeverReachesProvider() {
        ready();when(identities.resolve(eq(tenant),eq(actor),any(),isNull())).thenThrow(new IllegalArgumentException("revoked"));
        worker.process();verify(service).invalidateRequester(tenant,id);
        verify(gateway,never()).inspect(any(),any());assertThat(TenantContext.current()).isEmpty();
    }
}
