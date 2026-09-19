package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComOAuthStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComProperties properties;

    public WeComOAuthStore(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            WeComProperties properties
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
    }

    @Transactional
    public void start(UUID attemptId, String stateHash, String browserVerifierHash, String returnTo) {
        prepare();
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(:tenantId as text), 22))",
                params(), resultSet -> null);
        jdbc.update("""
                delete from wecom_oauth_attempt
                where tenant_id = :tenantId and expires_at < now()
                """, params());
        Integer pending = jdbc.queryForObject("""
                select count(*) from wecom_oauth_attempt
                where tenant_id = :tenantId and status in ('STARTED', 'AUTHORIZING', 'AUTHORIZED')
                  and expires_at > now()
                """, params(), Integer.class);
        if (pending != null && pending >= properties.oauthPendingLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many pending WeCom OAuth attempts");
        }
        jdbc.update("""
                insert into wecom_oauth_attempt
                    (id, tenant_id, state_hash, browser_verifier_hash, return_path, status, expires_at)
                values (:id, :tenantId, :stateHash, :browserVerifierHash, :returnTo, 'STARTED', :expiresAt)
                """, params().addValue("id", attemptId).addValue("stateHash", stateHash)
                .addValue("browserVerifierHash", browserVerifierHash)
                .addValue("returnTo", returnTo)
                .addValue("expiresAt", OffsetDateTime.now().plus(properties.stateTtl())));
    }

    @Transactional
    public UUID claim(String stateHash, String browserVerifierHash, String providerCodeHash) {
        prepare();
        List<UUID> ids = jdbc.queryForList("""
                update wecom_oauth_attempt
                set status = 'AUTHORIZING', provider_code_hash = :providerCodeHash, updated_at = now()
                where tenant_id = :tenantId and state_hash = :stateHash
                  and browser_verifier_hash = :browserVerifierHash
                  and status = 'STARTED' and expires_at > now()
                returning id
                """, params().addValue("stateHash", stateHash)
                .addValue("browserVerifierHash", browserVerifierHash)
                .addValue("providerCodeHash", providerCodeHash), UUID.class);
        if (ids.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "WeCom OAuth state is invalid or expired");
        return ids.getFirst();
    }

    @Transactional
    public void authorize(
            UUID attemptId,
            String exchangeCodeHash,
            UUID accountId,
            UUID assignmentId
    ) {
        prepare();
        int updated = jdbc.update("""
                update wecom_oauth_attempt
                set status = 'AUTHORIZED', exchange_code_hash = :exchangeCodeHash,
                    account_id = :accountId, assignment_id = :assignmentId,
                    authorized_at = now(), expires_at = :expiresAt, last_error = null,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id and status = 'AUTHORIZING'
                """, params().addValue("id", attemptId)
                .addValue("exchangeCodeHash", exchangeCodeHash)
                .addValue("accountId", accountId)
                .addValue("assignmentId", assignmentId)
                .addValue("expiresAt", OffsetDateTime.now().plus(properties.exchangeTtl())));
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "WeCom OAuth attempt changed concurrently");
    }

    @Transactional
    public void fail(UUID attemptId, RuntimeException exception) {
        prepare();
        String error = exception instanceof WeComLoginException failure
                ? failure.reason().name() : exception.getClass().getSimpleName();
        jdbc.update("""
                update wecom_oauth_attempt
                set status = 'FAILED', last_error = :error, updated_at = now()
                where tenant_id = :tenantId and id = :id and status = 'AUTHORIZING'
                """, params().addValue("id", attemptId).addValue("error", error));
    }

    @Transactional
    public Exchange consume(String exchangeCodeHash) {
        prepare();
        List<Exchange> rows = jdbc.query("""
                update wecom_oauth_attempt
                set status = 'EXCHANGED', exchanged_at = now(), updated_at = now()
                where tenant_id = :tenantId and exchange_code_hash = :exchangeCodeHash
                  and status = 'AUTHORIZED' and expires_at > now()
                returning account_id, assignment_id, return_path
                """, params().addValue("exchangeCodeHash", exchangeCodeHash), (rs, rowNum) -> new Exchange(
                rs.getObject("account_id", UUID.class),
                rs.getObject("assignment_id", UUID.class),
                rs.getString("return_path")
        ));
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "WeCom exchange code is invalid, used or expired");
        return rows.getFirst();
    }

    private void prepare() { databaseContext.apply(properties.tenantId()); }
    private MapSqlParameterSource params() { return new MapSqlParameterSource("tenantId", properties.tenantId()); }

    public record Exchange(UUID accountId, UUID assignmentId, String returnTo) { }
}
