package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComOAuthService {
    private static final Set<String> ALLOWED_TASK_QUERY_KEYS = Set.of("view", "taskId");
    private static final Set<String> ALLOWED_MY_WORK_QUERY_KEYS = Set.of("expectationId");
    private final SecureRandom secureRandom = new SecureRandom();
    private final WeComProperties properties;
    private final WeComOAuthStore store;
    private final WeComApiClient apiClient;
    private final WeComIdentityResolver identityResolver;
    private final EffectiveIdentityService effectiveIdentityService;
    private final WeComSessionTokenIssuer tokenIssuer;
    private final TenantDatabaseContext databaseContext;
    private final NamedParameterJdbcTemplate jdbc;

    public WeComOAuthService(
            WeComProperties properties,
            WeComOAuthStore store,
            WeComApiClient apiClient,
            WeComIdentityResolver identityResolver,
            EffectiveIdentityService effectiveIdentityService,
            WeComSessionTokenIssuer tokenIssuer,
            TenantDatabaseContext databaseContext,
            NamedParameterJdbcTemplate jdbc
    ) {
        this.properties = properties;
        this.store = store;
        this.apiClient = apiClient;
        this.identityResolver = identityResolver;
        this.effectiveIdentityService = effectiveIdentityService;
        this.tokenIssuer = tokenIssuer;
        this.databaseContext = databaseContext;
        this.jdbc = jdbc;
    }

    public Start start(String requestedReturnTo) {
        String returnTo = validateReturnTo(requestedReturnTo);
        String state = randomCode();
        String browserVerifier = randomCode();
        store.start(UUID.randomUUID(), sha256(state), sha256(browserVerifier), returnTo);
        URI authorizationUri = UriComponentsBuilder.fromUriString("https://open.weixin.qq.com/connect/oauth2/authorize")
                .queryParam("appid", properties.corpId())
                .queryParam("redirect_uri", properties.oauthCallbackUrl().toString())
                .queryParam("response_type", "code")
                .queryParam("scope", "snsapi_base")
                .queryParam("agentid", properties.agentId())
                .queryParam("state", state)
                .fragment("wechat_redirect")
                .build().encode().toUri();
        return new Start(authorizationUri, browserVerifier, properties.stateTtl().toSeconds());
    }

    public CallbackAuthorization callback(String providerCode, String state, String browserVerifier) {
        String code = boundedSecret(providerCode, "WeCom authorization code");
        String rawState = boundedSecret(state, "WeCom OAuth state");
        String rawVerifier = boundedSecret(browserVerifier, "WeCom browser verifier");
        UUID attemptId = store.claim(sha256(rawState), sha256(rawVerifier), sha256(code));
        try {
            String wecomUserId = apiClient.exchangeOAuthCode(code);
            WeComIdentityResolver.ResolvedIdentity identity = identityResolver.resolve(wecomUserId, UUID.randomUUID());
            String exchangeCode = randomCode();
            store.authorize(attemptId, sha256(exchangeCode), identity.principal().actorId(),
                    identity.preferredAssignmentId());
            return new CallbackAuthorization(exchangeCode);
        } catch (RuntimeException exception) {
            store.fail(attemptId, exception);
            throw exception;
        }
    }

    @Transactional
    public WeComOAuthModels.ExchangeResponse exchange(String rawExchangeCode) {
        String exchangeCode = boundedSecret(rawExchangeCode, "WeCom exchange code");
        WeComOAuthStore.Exchange exchange = store.consume(sha256(exchangeCode));
        TenantPrincipal current = effectiveIdentityService.resolve(
                properties.tenantId(), exchange.accountId(), UUID.randomUUID());
        if (exchange.assignmentId() != null && !current.assignmentIds().contains(exchange.assignmentId())) {
            throw new IllegalArgumentException("The WeCom OAuth assignment is no longer active");
        }
        databaseContext.apply(properties.tenantId());
        String displayName = jdbc.queryForObject("""
                select display_name from user_account
                where tenant_id = :tenantId and id = :accountId and status = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", properties.tenantId())
                .addValue("accountId", exchange.accountId()), String.class);
        WeComSessionTokenIssuer.Session session = tokenIssuer.issue(properties.tenantId(), exchange.accountId());
        return new WeComOAuthModels.ExchangeResponse(
                session.accessToken(), "Bearer", session.expiresAt(), exchange.accountId(),
                displayName, exchange.returnTo());
    }

    URI browserSessionLocation(String returnTo) {
        String safeReturnTo = validateReturnTo(returnTo);
        String hashRoute = safeReturnTo.startsWith("#/") ? safeReturnTo : "#" + safeReturnTo;
        String base = properties.frontendBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/?wecom_session=1" + hashRoute);
    }

    URI frontendEntryLocation() {
        return URI.create(properties.frontendBaseUrl().toString().replaceAll("/+$", "") + "/#/workbench");
    }

    URI frontendExchangeLocation(String rawExchangeCode) {
        String exchangeCode = boundedSecret(rawExchangeCode, "WeCom exchange code");
        if (!exchangeCode.matches("[A-Za-z0-9._~-]{16,512}")) {
            throw new IllegalArgumentException("WeCom exchange code is invalid");
        }
        String base = properties.frontendBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/wecom-auth?exchange_code=" + exchangeCode);
    }

    static String validateReturnTo(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("WeCom returnTo must include one allowed internal destination");
        }
        String value = raw.trim();
        if (value.length() > 500 || value.contains("\\") || value.contains("://")
                || value.startsWith("//") || value.chars().anyMatch(character -> character < 32)) {
            throw new IllegalArgumentException("WeCom returnTo must be a safe internal task path");
        }
        String withoutHash = value.startsWith("#") ? value.substring(1) : value;
        URI uri;
        try {
            uri = URI.create(withoutHash);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("WeCom returnTo is invalid", exception);
        }
        if (uri.isAbsolute() || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("WeCom returnTo must target an allowed work item");
        }
        if ("/academy".equals(uri.getPath())) {
            var query=UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            if(!query.keySet().equals(Set.of("tab","id")) || query.get("tab").size()!=1
                    || !"mine".equals(query.getFirst("tab")) || query.get("id").size()!=1)
                throw new IllegalArgumentException("WeCom academy returnTo must include one plan id");
            UUID.fromString(query.getFirst("id"));return value;
        }
        if ("/workbench".equals(uri.getPath())) {
            if (uri.getRawQuery() != null) {
                throw new IllegalArgumentException("WeCom workbench returnTo cannot contain query parameters");
            }
            return value;
        }
        if (uri.getPath().startsWith("/daily-reports/")) {
            if (uri.getRawQuery() != null) {
                throw new IllegalArgumentException("WeCom daily-report returnTo cannot contain query parameters");
            }
            String reportId = uri.getPath().substring("/daily-reports/".length());
            if (reportId.isBlank() || reportId.contains("/")) {
                throw new IllegalArgumentException("WeCom returnTo must include one reportId");
            }
            UUID.fromString(reportId);
            return value;
        }
        if ("/lost-properties".equals(uri.getPath())) {
            var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            if (!query.keySet().equals(Set.of("id")) || query.get("id").size() != 1) {
                throw new IllegalArgumentException("遗留物入口必须包含一个 id");
            }
            UUID.fromString(query.getFirst("id"));
            return value;
        }
        if ("/procurement".equals(uri.getPath())) {
            var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            if (!query.keySet().equals(Set.of("orderId")) || query.get("orderId").size() != 1) {
                throw new IllegalArgumentException("采购入口必须包含一个 orderId");
            }
            UUID.fromString(query.getFirst("orderId"));
            return value;
        }
        if ("/my-work".equals(uri.getPath())) {
            if (uri.getRawQuery() == null) {
                throw new IllegalArgumentException("WeCom returnTo must include one expectationId");
            }
            var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            if (!ALLOWED_MY_WORK_QUERY_KEYS.containsAll(query.keySet())) {
                throw new IllegalArgumentException("WeCom returnTo contains an unsupported query parameter");
            }
            List<String> expectationIds = query.get("expectationId");
            if (expectationIds == null || expectationIds.size() != 1) {
                throw new IllegalArgumentException("WeCom returnTo must include one expectationId");
            }
            UUID.fromString(expectationIds.getFirst());
            return value;
        }
        if (!"/tasks".equals(uri.getPath())) {
            throw new IllegalArgumentException("WeCom returnTo must target /workbench, /my-work, /tasks or /daily-reports/{reportId}");
        }
        if (uri.getRawQuery() != null) {
            var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            if (!ALLOWED_TASK_QUERY_KEYS.containsAll(query.keySet())) {
                throw new IllegalArgumentException("WeCom returnTo contains an unsupported query parameter");
            }
            List<String> views = query.get("view");
            if (views != null && (views.size() != 1 || !Set.of("mine", "team").contains(views.getFirst()))) {
                throw new IllegalArgumentException("WeCom returnTo view is invalid");
            }
            List<String> taskIds = query.get("taskId");
            if (taskIds == null || taskIds.size() != 1) {
                throw new IllegalArgumentException("WeCom returnTo must include one taskId");
            }
            UUID.fromString(taskIds.getFirst());
        } else {
            throw new IllegalArgumentException("WeCom returnTo must include one taskId");
        }
        return value;
    }

    private String randomCode() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String boundedSecret(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(label + " is missing or too long");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Start(URI authorizationUri, String browserVerifier, long maxAgeSeconds) { }

    /** Server-only handoff between the verified OAuth callback and the session exchange. */
    public record CallbackAuthorization(String exchangeCode) { }
}
