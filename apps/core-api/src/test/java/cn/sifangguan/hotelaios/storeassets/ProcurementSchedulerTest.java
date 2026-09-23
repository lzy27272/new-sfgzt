package cn.sifangguan.hotelaios.storeassets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcurementSchedulerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ProcurementSchedulingConfiguration.class, ProcurementScheduler.class, Dependencies.class);

    @Test void enablingProcurementAloneActuallySchedulesIt() {
        UUID tenant = UUID.randomUUID();
        runner.withPropertyValues("app.procurement.scheduler-enabled=true", "app.procurement.tenant-ids=" + tenant,
            "app.procurement.initial-delay-ms=0", "app.procurement.fixed-delay-ms=10").run(context -> {
                assertThat(context).hasNotFailed();
                verify(context.getBean(ProcurementService.class), timeout(3000).atLeastOnce()).automate(tenant);
            });
    }

    @Test void disabledByDefaultDoesNotSchedule() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ProcurementScheduler.class);
            verifyNoInteractions(context.getBean(ProcurementService.class));
        });
    }

    @Test void enabledWithoutExplicitTenantsFailsFast() {
        runner.withPropertyValues("app.procurement.scheduler-enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("PROCUREMENT_TENANT_IDS");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean ProcurementService service() { return mock(ProcurementService.class); }
    }
}
