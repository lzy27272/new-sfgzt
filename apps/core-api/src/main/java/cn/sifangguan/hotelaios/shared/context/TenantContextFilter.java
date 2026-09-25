package cn.sifangguan.hotelaios.shared.context;

import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.BusinessIdentityException;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import cn.sifangguan.hotelaios.integrations.wecom.WeComSessionBindingGuard;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
    private final boolean developmentHeaderAuthEnabled;
    private final EffectiveIdentityService identityService;
    private final ObjectProvider<WeComSessionBindingGuard> weComSessionBindingGuard;
    private final String tenantIdClaim;
    private final String accountIdClaim;
    private final boolean legacyUnitMode;

    @Autowired
    public TenantContextFilter(
            @Value("${app.security.development-header-auth-enabled:false}") boolean developmentHeaderAuthEnabled,
            @Value("${app.security.jwt.tenant-id-claim:tenant_id}") String tenantIdClaim,
            @Value("${app.security.jwt.account-id-claim:account_id}") String accountIdClaim,
            EffectiveIdentityService identityService,
            ObjectProvider<WeComSessionBindingGuard> weComSessionBindingGuard
    ) {
        this.developmentHeaderAuthEnabled = developmentHeaderAuthEnabled;
        this.identityService = identityService;
        this.weComSessionBindingGuard = weComSessionBindingGuard;
        this.tenantIdClaim = tenantIdClaim;
        this.accountIdClaim = accountIdClaim;
        this.legacyUnitMode = false;
    }

    /** Compatibility constructor used only by the isolated Sprint 1 filter tests. */
    public TenantContextFilter(boolean developmentHeaderAuthEnabled) {
        this.developmentHeaderAuthEnabled = developmentHeaderAuthEnabled;
        this.identityService = null;
        this.weComSessionBindingGuard = null;
        this.tenantIdClaim = "tenant_id";
        this.accountIdClaim = "account_id";
        this.legacyUnitMode = true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/")
                // This exact endpoint resolves a restricted MCP grant itself, never cookies/JWT.
                || request.getRequestURI().equals("/api/v1/ai-data/mcp")
                || request.getRequestURI().equals("/api/v1/auth/login")
                || request.getRequestURI().equals("/api/v1/auth/logout")
                || isAnonymousWeComEndpoint(request)
                || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    private boolean isAnonymousWeComEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/integrations/wecom/bot/callback")) {
            return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.POST.matches(request.getMethod());
        }
        if (path.equals("/api/v1/integrations/wecom/directory/callback")) {
            return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.POST.matches(request.getMethod());
        }
        if (HttpMethod.GET.matches(request.getMethod())) {
            return path.equals("/api/v1/integrations/wecom/oauth/start")
                    || path.equals("/api/v1/integrations/wecom/oauth/callback")
                    || path.equals("/api/v1/integrations/wecom/directory-onboarding/oauth/callback");
        }
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        return path.equals("/api/v1/integrations/wecom/oauth/exchange")
                || path.equals("/api/v1/integrations/wecom/oauth/browser-exchange")
                || path.equals("/api/v1/integrations/wecom/binding-enrollment/preview")
                || path.equals("/api/v1/integrations/wecom/binding-enrollment/start")
                || path.equals("/api/v1/integrations/wecom/directory-onboarding/start")
                || path.equals("/api/v1/integrations/wecom/directory-onboarding/self-service/start")
                || path.equals("/api/v1/integrations/wecom/directory-onboarding/exchange")
                || path.equals("/api/v1/integrations/wecom/directory-onboarding/context")
                || path.equals("/api/v1/integrations/wecom/directory-onboarding/submit");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            UUID correlationId = optionalUuid(request.getHeader("X-Correlation-Id"), UUID.randomUUID());
            TenantPrincipal principal = developmentHeaderAuthEnabled
                    ? resolveDevelopmentIdentity(request, correlationId)
                    : resolveJwtIdentity(request, correlationId);

            TenantContext.set(principal);
            response.setHeader("X-Correlation-Id", correlationId.toString());
            filterChain.doFilter(request, response);
        } catch (BusinessIdentityException exception) {
            writeProblem(response, exception.status(), "业务身份无效", exception.getMessage(), exception.code());
        } catch (IdentityAuthenticationException exception) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "身份认证失败", exception.getMessage(),
                    "AUTHENTICATION_FAILED");
        } catch (IllegalArgumentException exception) {
            writeProblem(response, HttpStatus.BAD_REQUEST, "无效的身份上下文", exception.getMessage(),
                    "REQUEST_INVALID");
        } finally {
            TenantContext.clear();
        }
    }

    private TenantPrincipal resolveDevelopmentIdentity(HttpServletRequest request, UUID correlationId) {
        UUID tenantId = requiredUuid(request, "X-Tenant-Id");
        UUID actorId = requiredUuid(request, "X-Actor-Id");
        if (!legacyUnitMode) {
            return identityService.resolve(
                    tenantId,
                    actorId,
                    correlationId,
                    optionalAssignmentUuid(request.getHeader("X-Assignment-Id"))
            );
        }

        String roleCode = requiredHeader(request, "X-Role-Code").trim().toUpperCase();
        Set<UUID> scopes = parseScopes(request.getHeader("X-Org-Scope"));
        return new TenantPrincipal(tenantId, actorId, roleCode, scopes, correlationId);
    }

    private TenantPrincipal resolveJwtIdentity(HttpServletRequest request, UUID correlationId) {
        if (legacyUnitMode) {
            throw new IdentityAuthenticationException("开发请求头认证已关闭");
        }
        UUID requestedAssignmentId = optionalAssignmentUuid(request.getHeader("X-Assignment-Id"));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new IdentityAuthenticationException("需要有效的Bearer JWT");
        }
        Object tenantClaim = jwtAuthentication.getToken().getClaims().get(tenantIdClaim);
        Object accountClaim = jwtAuthentication.getToken().getClaims().get(accountIdClaim);
        if (accountClaim == null) {
            accountClaim = jwtAuthentication.getToken().getSubject();
        }
        if (tenantClaim == null || accountClaim == null) {
            throw new IdentityAuthenticationException("JWT缺少租户或账号标识");
        }
        try {
            UUID tenantId = UUID.fromString(tenantClaim.toString());
            UUID accountId = UUID.fromString(accountClaim.toString());
            if ("wecom".equals(jwtAuthentication.getToken().getClaimAsString("auth_source"))) {
                Number version = jwtAuthentication.getToken().getClaim("wecom_binding_version");
                WeComSessionBindingGuard guard = weComSessionBindingGuard == null
                        ? null : weComSessionBindingGuard.getIfAvailable();
                if (version == null || guard == null) {
                    throw new IdentityAuthenticationException(
                            "企业微信会话缺少绑定状态，请重新验证");
                }
                guard.requireActive(tenantId, accountId, version.longValue());
            }
            return identityService.resolve(
                    tenantId,
                    accountId,
                    correlationId,
                    requestedAssignmentId
            );
        } catch (IllegalArgumentException exception) {
            throw new IdentityAuthenticationException("JWT租户或账号标识不是有效UUID");
        }
    }

    private UUID requiredUuid(HttpServletRequest request, String name) {
        return UUID.fromString(requiredHeader(request, name));
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少请求头 " + name);
        }
        return value;
    }

    private UUID optionalUuid(String value, UUID fallback) {
        return value == null || value.isBlank() ? fallback : UUID.fromString(value);
    }

    private UUID optionalAssignmentUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw BusinessIdentityException.invalidFormat();
        }
    }

    private Set<UUID> parseScopes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String code
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write("{\"title\":\"" + escape(title) + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + escape(detail) + "\",\"code\":\""
                + escape(code) + "\"}");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
