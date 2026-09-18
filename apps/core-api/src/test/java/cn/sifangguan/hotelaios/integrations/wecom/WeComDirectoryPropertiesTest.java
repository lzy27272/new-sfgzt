package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComDirectoryPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(WeComProperties.class, () -> mock(WeComProperties.class))
            .withUserConfiguration(WeComDirectoryProperties.class)
            .withPropertyValues(
                    "app.wecom.enabled=true",
                    "app.wecom.directory.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                    "app.wecom.directory.oauth-callback-url=https://example.test/oauth/callback");

    @Test
    void defaultSharedLifetimeIsSevenDaysWithoutExtendingPersonalCredentials() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(WeComDirectoryProperties.class);
            assertThat(properties.sharedInvitationTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.invitationTtl()).isEqualTo(Duration.ofMinutes(120));
            assertThat(properties.exchangeTtl()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.sessionTtl()).isEqualTo(Duration.ofMinutes(30));
        });
    }

    @Test
    void sharedLifetimeCanBeShortenedIndependentlyOfPersonalLifetime() {
        context.withPropertyValues(
                "app.wecom.directory.shared-invitation-ttl-days=1",
                "app.wecom.directory.invitation-ttl-minutes=15").run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(WeComDirectoryProperties.class);
            assertThat(properties.sharedInvitationTtl()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.invitationTtl()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    @Test
    void invalidSharedLifetimesFailClosed() {
        for (int days : new int[]{0, -1, 8}) {
            context.withPropertyValues("app.wecom.directory.shared-invitation-ttl-days=" + days)
                    .run(application -> assertThat(application).hasFailed());
        }
    }
}
