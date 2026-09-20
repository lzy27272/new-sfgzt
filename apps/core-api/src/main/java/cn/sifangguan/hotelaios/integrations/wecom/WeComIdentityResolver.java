package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Maps a trusted, decrypted WeCom UserId to the current server-side IAM identity. */
@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComIdentityResolver {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final EffectiveIdentityService effectiveIdentityService;
    private final WeComProperties properties;

    public WeComIdentityResolver(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            EffectiveIdentityService effectiveIdentityService,
            WeComProperties properties
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.effectiveIdentityService = effectiveIdentityService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ResolvedIdentity resolve(String wecomUserId, UUID correlationId) {
        if (wecomUserId == null || wecomUserId.isBlank()) {
            throw new IdentityAuthenticationException("WeCom UserId is missing");
        }
        databaseContext.apply(properties.tenantId());
        List<Binding> bindings = jdbc.query("""
                select b.account_id, b.preferred_assignment_id, u.display_name
                from wecom_user_binding b
                join user_account u on u.tenant_id = b.tenant_id and u.id = b.account_id
                where b.tenant_id = :tenantId and b.corp_id = :corpId
                  and b.wecom_user_id = :userId and b.status = 'ACTIVE'
                """, params().addValue("userId", wecomUserId.trim()), (rs, rowNum) -> new Binding(
                rs.getObject("account_id", UUID.class),
                rs.getObject("preferred_assignment_id", UUID.class),
                rs.getString("display_name")
        ));
        if (bindings.size() != 1) {
            throw new WeComLoginException(WeComLoginException.Reason.MEMBER_UNBOUND);
        }
        Binding binding = bindings.getFirst();
        TenantPrincipal principal;
        try {
            principal = effectiveIdentityService.resolve(
                    properties.tenantId(), binding.accountId(), correlationId);
        } catch (IdentityAuthenticationException exception) {
            throw new WeComLoginException(WeComLoginException.Reason.ACCOUNT_UNAVAILABLE);
        }
        UUID preferred = binding.preferredAssignmentId();
        if (preferred != null && !principal.assignmentIds().contains(preferred)) {
            throw new WeComLoginException(WeComLoginException.Reason.ASSIGNMENT_INACTIVE);
        }
        return new ResolvedIdentity(principal, preferred, binding.displayName());
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource()
                .addValue("tenantId", properties.tenantId())
                .addValue("corpId", properties.corpId());
    }

    private record Binding(UUID accountId, UUID preferredAssignmentId, String displayName) { }

    public record ResolvedIdentity(TenantPrincipal principal, UUID preferredAssignmentId, String displayName) {
        public UUID chooseAssignment(UUID serverBoundAssignment) {
            if (serverBoundAssignment != null) {
                if (!principal.assignmentIds().contains(serverBoundAssignment)) {
                    throw new IdentityAuthenticationException("The task-card assignment is no longer active");
                }
                return serverBoundAssignment;
            }
            if (preferredAssignmentId != null) return preferredAssignmentId;
            if (principal.assignmentIds().size() == 1) return principal.assignmentIds().iterator().next();
            throw new IdentityAuthenticationException("No unambiguous active assignment is available for this action");
        }
    }
}
