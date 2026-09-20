package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComIdentityResolverTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final EffectiveIdentityService identities = mock(EffectiveIdentityService.class);

    @Test
    void unboundMemberNeverFallsBackToANamePhoneOrUnrelatedAccount() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        assertReason(WeComLoginException.Reason.MEMBER_UNBOUND);
        verifyNoInteractions(identities);
    }

    @Test
    void disabledBoundAccountDoesNotMasqueradeAsAnExpiredCodeOrAnUnboundMember() throws Exception {
        bind(null);
        when(identities.resolve(tenant, account, correlation)).thenThrow(new IdentityAuthenticationException("account disabled"));
        assertReason(WeComLoginException.Reason.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void stalePreferredAssignmentRemainsFailClosedWithASpecificReason() throws Exception {
        bind(UUID.randomUUID());
        when(identities.resolve(tenant, account, correlation)).thenReturn(principal(Set.of(UUID.randomUUID())));
        assertReason(WeComLoginException.Reason.ASSIGNMENT_INACTIVE);
    }

    @Test
    void validBindingPreservesTheVerifiedAccountAndPreferredAssignment() throws Exception {
        UUID assignment = UUID.randomUUID();
        bind(assignment);
        when(identities.resolve(tenant, account, correlation)).thenReturn(principal(Set.of(assignment)));
        var resolved = resolver().resolve("verified-wecom-member", correlation);
        assertThat(resolved.principal().actorId()).isEqualTo(account);
        assertThat(resolved.chooseAssignment(null)).isEqualTo(assignment);
        assertThatThrownBy(() -> resolved.chooseAssignment(UUID.randomUUID())).isInstanceOf(IdentityAuthenticationException.class);
    }

    private void assertReason(WeComLoginException.Reason reason) {
        assertThatThrownBy(() -> resolver().resolve("verified-wecom-member", correlation))
                .isInstanceOfSatisfying(WeComLoginException.class, failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private WeComIdentityResolver resolver() {
        WeComProperties properties = mock(WeComProperties.class);
        when(properties.tenantId()).thenReturn(tenant);
        when(properties.corpId()).thenReturn("test-corp");
        return new WeComIdentityResolver(jdbc, mock(TenantDatabaseContext.class), identities, properties);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void bind(UUID preferred) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("account_id", UUID.class)).thenReturn(account);
        when(row.getObject("preferred_assignment_id", UUID.class)).thenReturn(preferred);
        when(row.getString("display_name")).thenReturn("Verified employee");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(call -> List.of(((RowMapper) call.getArgument(2)).mapRow(row, 0)));
    }

    private TenantPrincipal principal(Set<UUID> assignments) {
        return new TenantPrincipal(tenant, account, "GROUP_VICE_PRESIDENT", Set.of("GROUP_VICE_PRESIDENT"),
                Set.of(), Set.of(), assignments, false, correlation);
    }
}
