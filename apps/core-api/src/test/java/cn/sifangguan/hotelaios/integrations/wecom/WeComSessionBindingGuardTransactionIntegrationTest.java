package cn.sifangguan.hotelaios.integrations.wecom;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WeComSessionBindingGuardTransactionIntegrationTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("19000000-0000-0000-0000-000000000003");
    private static final UUID BINDING_ID = UUID.fromString("10000000-0000-0000-0000-000000000099");
    private static final String CORP_ID = "corp-transaction-test";
    private static final String APP_ROLE = "hotel_ai_os_app";
    private static final String APP_PASSWORD = "wecom-guard-test-password";
    private static final long BINDING_VERSION = 7L;

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource OWNER_DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(OWNER_DATA_SOURCE);

    static {
        new JdbcTemplate(OWNER_DATA_SOURCE).execute("""
                create role hotel_ai_os_app
                login password 'wecom-guard-test-password'
                nosuperuser nobypassrls
                """);
    }

    @Autowired
    private WeComSessionBindingGuard guard;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.security.development-header-auth-enabled", () -> false);
        registry.add("app.security.local-login.enabled", () -> true);
        registry.add("app.security.local-login.secret",
                () -> "wecom-transaction-integration-test-secret-32-bytes-minimum");
        registry.add("app.wecom.enabled", () -> true);
        registry.add("app.wecom.tenant-id", TENANT_ID::toString);
        registry.add("app.wecom.corp-id", () -> CORP_ID);
        registry.add("app.wecom.agent-id", () -> "1000001");
        registry.add("app.wecom.corp-secret", () -> "test-corp-secret");
        registry.add("app.wecom.frontend-base-url", () -> "https://www.sfgzt.cn");
        registry.add("app.wecom.oauth-callback-url",
                () -> "https://www.sfgzt.cn/api/v1/integrations/wecom/oauth/callback");
        registry.add("app.wecom.directory.encryption-key",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("app.wecom.directory.oauth-callback-url",
                () -> "https://www.sfgzt.cn/api/v1/integrations/wecom/directory-onboarding/oauth/callback");
        registry.add("app.wecom.worker.enabled", () -> false);
        registry.add("app.automation.worker.enabled", () -> false);
        registry.add("app.work-expectation.sla.scheduler-enabled", () -> false);
    }

    @BeforeEach
    void seedActiveBindingAsTableOwner() {
        JdbcTemplate owner = new JdbcTemplate(OWNER_DATA_SOURCE);
        owner.update("delete from wecom_user_binding where id = ?", BINDING_ID);
        owner.update("""
                insert into wecom_user_binding (
                    id, tenant_id, corp_id, wecom_user_id, account_id,
                    status, row_version, user_id_fingerprint, last_verified_at
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, repeat('a', 64), now())
                """, BINDING_ID, TENANT_ID, CORP_ID, "wecom-transaction-user", ACCOUNT_ID,
                BINDING_VERSION);
    }

    @Test
    void validatesActiveBindingInsideOneTransactionSoTenantRlsContextSurvivesTheQuery() {
        assertThatCode(() -> guard.requireActive(TENANT_ID, ACCOUNT_ID, BINDING_VERSION))
                .doesNotThrowAnyException();
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
