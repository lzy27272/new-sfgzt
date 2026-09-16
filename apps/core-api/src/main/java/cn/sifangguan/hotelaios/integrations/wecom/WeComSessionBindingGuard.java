package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Ensures an already-issued WeCom JWT follows the live binding state. */
@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComSessionBindingGuard {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComProperties properties;

    public WeComSessionBindingGuard(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            WeComProperties properties
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public long currentVersion(UUID tenantId, UUID accountId) {
        if (!properties.tenantId().equals(tenantId)) {
            throw new IdentityAuthenticationException("企业微信会话租户不匹配");
        }
        databaseContext.apply(tenantId);
        List<Long> versions = jdbc.queryForList("""
                select row_version
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId
                  and account_id = :accountId and status = 'ACTIVE'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("corpId", properties.corpId())
                .addValue("accountId", accountId), Long.class);
        if (versions.size() != 1) {
            throw new IdentityAuthenticationException("企业微信绑定已暂停、解除或不存在");
        }
        return versions.getFirst();
    }

    @Transactional(readOnly = true)
    public void requireActive(UUID tenantId, UUID accountId, long issuedBindingVersion) {
        long currentVersion = currentVersion(tenantId, accountId);
        if (currentVersion != issuedBindingVersion) {
            throw new IdentityAuthenticationException("企业微信绑定已变更，请重新验证");
        }
    }
}
