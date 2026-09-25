package cn.sifangguan.hotelaios.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Configuration
public class SecurityConfiguration {
    private static final Set<String> COOKIE_ANONYMOUS_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/logout",
            "/api/v1/integrations/wecom/oauth/start",
            "/api/v1/integrations/wecom/oauth/callback",
            "/api/v1/integrations/wecom/oauth/exchange",
            "/api/v1/integrations/wecom/oauth/browser-exchange",
            "/api/v1/integrations/wecom/directory-onboarding/start",
            "/api/v1/integrations/wecom/directory-onboarding/self-service/start",
            "/api/v1/integrations/wecom/directory-onboarding/oauth/callback",
            "/api/v1/integrations/wecom/directory-onboarding/exchange",
            "/api/v1/integrations/wecom/directory-onboarding/context",
            "/api/v1/integrations/wecom/directory-onboarding/submit"
    );

    @Bean
    @ConditionalOnProperty(name = "app.security.development-header-auth-enabled", havingValue = "true")
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        return common(http)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.security.development-header-auth-enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    SecurityFilterChain productionSecurity(HttpSecurity http) throws Exception {
        return common(http)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/integrations/wecom/bot/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/integrations/wecom/bot/callback").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/integrations/wecom/oauth/start",
                                "/api/v1/integrations/wecom/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/integrations/wecom/oauth/exchange").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/integrations/wecom/oauth/browser-exchange").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/integrations/wecom/binding-enrollment/preview",
                                "/api/v1/integrations/wecom/binding-enrollment/start").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/integrations/wecom/directory/callback",
                                "/api/v1/integrations/wecom/directory-onboarding/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/integrations/wecom/directory/callback",
                                "/api/v1/integrations/wecom/directory-onboarding/start",
                                "/api/v1/integrations/wecom/directory-onboarding/self-service/start",
                                "/api/v1/integrations/wecom/directory-onboarding/exchange",
                                "/api/v1/integrations/wecom/directory-onboarding/context",
                                "/api/v1/integrations/wecom/directory-onboarding/submit").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(federatedCookieBearerTokenResolver())
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> writeUnauthorized(response)))
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.security.development-header-auth-enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    JwtDecoder jwtDecoder(
            @Value("${app.security.jwt.issuer-uri:}") String issuerUri,
            @Value("${app.security.jwt.audience:hotel-ai-os-api}") String audience,
            @Value("${app.security.local-login.enabled:false}") boolean localLoginEnabled,
            @Value("${app.security.local-login.secret:}") String localSecret,
            @Value("${app.security.local-login.issuer:hotel-ai-os-pilot}") String localIssuer
    ) {
        if (localLoginEnabled) {
            SecretKey key = localSecret(localSecret);
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(localIssuer);
            OAuth2TokenValidator<Jwt> audienceValidator = audienceValidator(audience);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator));
            return decoder;
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "JWT issuer未配置。生产运行必须设置JWT_ISSUER_URI；本地开发请显式启用dev profile。"
            );
        }
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
            OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "JWT audience不匹配", null));
            nimbus.setJwtValidator(jwt -> {
                OAuth2TokenValidatorResult issuerResult = issuerValidator.validate(jwt);
                return issuerResult.hasErrors() ? issuerResult : audienceValidator.validate(jwt);
            });
        }
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.local-login.enabled", havingValue = "true")
    JwtEncoder localJwtEncoder(@Value("${app.security.local-login.secret:}") String localSecret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(localSecret(localSecret)));
    }

    @Bean
    BearerTokenResolver federatedCookieBearerTokenResolver() {
        DefaultBearerTokenResolver authorizationHeader = new DefaultBearerTokenResolver();
        return request -> {
            String headerToken = authorizationHeader.resolve(request);
            if (headerToken != null) return headerToken;
            if (COOKIE_ANONYMOUS_PATHS.contains(request.getRequestURI())) return null;
            if (request.getCookies() == null) return null;
            for (var cookie : request.getCookies()) {
                if (FederatedSessionCookie.NAME.equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
            return null;
        };
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable());
    }

    private static void writeUnauthorized(HttpServletResponse response) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"身份认证失败\",\"detail\":\"需要有效的Bearer JWT\"}");
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "JWT audience不匹配", null));
    }

    private static SecretKey localSecret(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Pilot本地JWT密钥必须至少32字节");
        }
        return new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
