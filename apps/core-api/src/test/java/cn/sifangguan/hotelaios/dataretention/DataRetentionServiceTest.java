package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("19000000-0000-0000-0000-000000000001");

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private TenantDatabaseContext databaseContext;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private AuditWriter auditWriter;

    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        service = new DataRetentionService(
                jdbc, databaseContext, accessPolicy, auditWriter, new ObjectMapper());
    }

    @Test
    void ceoCannotUseRetentionAdminApiEvenWithWildcardPermission() {
        when(accessPolicy.principal()).thenReturn(principal("CEO", Set.of("CEO"), Set.of("*")));

        assertThatThrownBy(service::policy)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("平台管理员或集团管理员");

        verify(databaseContext, never()).apply(any());
        verify(jdbc, never()).update(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
    }

    @Test
    void groupAdminPassesRoleGateAndOptimisticConflictIsReported() {
        when(accessPolicy.principal()).thenReturn(
                principal("GROUP_ADMIN", Set.of("GROUP_ADMIN"), Set.of()));
        when(jdbc.update(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class)))
                .thenReturn(1, 0);

        DataRetentionModels.UpdatePolicy request = new DataRetentionModels.UpdatePolicy(
                5, 30, 36, 36, 36, 36, 90, false, 7L);

        assertThatThrownBy(() -> service.updatePolicy(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(databaseContext).apply(TENANT_ID);
    }

    private TenantPrincipal principal(String primaryRole, Set<String> roleCodes, Set<String> permissions) {
        return new TenantPrincipal(
                TENANT_ID,
                ACTOR_ID,
                primaryRole,
                roleCodes,
                permissions,
                Set.of(),
                Set.of(),
                true,
                UUID.randomUUID()
        );
    }
}
