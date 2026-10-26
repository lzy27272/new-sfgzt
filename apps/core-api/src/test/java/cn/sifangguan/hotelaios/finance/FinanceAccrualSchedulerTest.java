package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinanceAccrualSchedulerTest {
 final UUID tenant=UUID.randomUUID(),plan=UUID.randomUUID(),actor=UUID.randomUUID(),assignment=UUID.randomUUID();
 FinanceAccrualControlService service;EffectiveIdentityService identities;
 @BeforeEach void setup(){service=mock(FinanceAccrualControlService.class);identities=mock(EffectiveIdentityService.class);when(service.scheduledPlans(tenant)).thenReturn(List.of(Map.of("id",plan,"created_by",actor,"created_assignment_id",assignment,"version",1)));}
 @AfterEach void cleanup(){TenantContext.clear();}
 @Test void enabledSchedulerCannotAssumeAnUnspecifiedTenant(){assertThatThrownBy(()->new FinanceAccrualScheduler(service,identities," , ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("租户白名单");}
 @Test void inactiveOriginalAccountDoesNotReceiveForgedWildcardIdentityOrGenerateAnything(){when(identities.inspectNotificationRecipient(tenant,actor,assignment)).thenReturn(Optional.empty());new FinanceAccrualScheduler(service,identities,tenant.toString()).process();verify(service,never()).run(any(),any());verify(service).scheduleIssue(eq(tenant),eq(plan),any(LocalDate.class),contains("失效"));assertThat(TenantContext.current()).isEmpty();}
 @Test void priorMonthUnpostedDependencyStopsLaterMonthsAndPreservesRetryableMonth(){
  var principal=new TenantPrincipal(tenant,actor,"TEST",Set.of("TEST"),Set.of("finance.accounting.read","finance.accounting.write"),Set.of(UUID.randomUUID()),Set.of(),false,assignment);when(identities.inspectNotificationRecipient(tenant,actor,assignment)).thenReturn(Optional.of(principal));when(service.nextScheduledMonth(eq(tenant),eq(plan),any())).thenReturn("2026-08","2026-09");when(service.detail(plan)).thenReturn(Map.of("version",1),Map.of("version",2));when(service.run(eq(plan),argThat(in->in!=null&&in.month().equals("2026-09")))).thenThrow(FinanceV2Support.conflict("前月凭证未复核过账"));
  new FinanceAccrualScheduler(service,identities,tenant.toString()).process();verify(service).finalizeTermination(plan);verify(service).run(eq(plan),argThat(in->in.month().equals("2026-08")));verify(service).run(eq(plan),argThat(in->in.month().equals("2026-09")));verify(service).scheduleIssue(eq(tenant),eq(plan),eq(LocalDate.of(2026,9,1)),contains("前月凭证"));assertThat(TenantContext.current()).isEmpty();
 }
 @Test void retryUsesTheSameBusinessRequestIdentifierRatherThanDuplicatingMonth(){
  var principal=new TenantPrincipal(tenant,actor,"TEST",Set.of("TEST"),Set.of("finance.accounting.read","finance.accounting.write"),Set.of(UUID.randomUUID()),Set.of(),false,assignment);when(identities.inspectNotificationRecipient(tenant,actor,assignment)).thenReturn(Optional.of(principal));when(service.nextScheduledMonth(eq(tenant),eq(plan),any())).thenReturn("2026-08",null,"2026-08",null);when(service.detail(plan)).thenReturn(Map.of("version",1));var scheduler=new FinanceAccrualScheduler(service,identities,tenant.toString());scheduler.process();scheduler.process();var capture=org.mockito.ArgumentCaptor.forClass(FinanceAccrualModels.Run.class);verify(service,times(2)).run(eq(plan),capture.capture());assertThat(capture.getAllValues().getFirst().requestId()).isEqualTo(capture.getAllValues().getLast().requestId());assertThat(TenantContext.current()).isEmpty();
 }
}
