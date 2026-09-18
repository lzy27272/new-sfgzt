package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** Fail-closed configuration for manual onboarding and optional directory callbacks. */
@Component
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComDirectoryProperties {
    private final WeComProperties wecom;
    private final boolean directorySyncEnabled;
    private final String callbackToken;
    private final String callbackAesKey;
    private final String receiveId;
    private final String encryptionKey;
    private final URI oauthCallbackUrl;
    private final Duration invitationTtl;
    private final Duration sharedInvitationTtl;
    private final Duration exchangeTtl;
    private final Duration sessionTtl;
    private final Duration callbackClockSkew;

    public WeComDirectoryProperties(
            WeComProperties wecom,
            @Value("${app.wecom.directory-sync-enabled:false}") boolean directorySyncEnabled,
            @Value("${app.wecom.directory.callback-token:}") String callbackToken,
            @Value("${app.wecom.directory.callback-aes-key:}") String callbackAesKey,
            @Value("${app.wecom.directory.receive-id:}") String receiveId,
            @Value("${app.wecom.directory.encryption-key:}") String encryptionKey,
            @Value("${app.wecom.directory.oauth-callback-url:}") String oauthCallbackUrl,
            @Value("${app.wecom.directory.invitation-ttl-minutes:120}") long invitationTtlMinutes,
            @Value("${app.wecom.directory.shared-invitation-ttl-days:7}") long sharedInvitationTtlDays,
            @Value("${app.wecom.directory.exchange-ttl-minutes:2}") long exchangeTtlMinutes,
            @Value("${app.wecom.directory.session-ttl-minutes:30}") long sessionTtlMinutes,
            @Value("${app.wecom.directory.callback-clock-skew-seconds:600}") long callbackClockSkewSeconds
    ) {
        this.wecom = wecom;
        this.directorySyncEnabled = directorySyncEnabled;
        this.callbackToken = directorySyncEnabled
                ? required(callbackToken, "WECOM_DIRECTORY_CALLBACK_TOKEN") : optional(callbackToken);
        this.callbackAesKey = directorySyncEnabled
                ? required(callbackAesKey, "WECOM_DIRECTORY_CALLBACK_AES_KEY") : optional(callbackAesKey);
        this.receiveId = receiveId == null || receiveId.isBlank() ? wecom.corpId() : receiveId.trim();
        this.encryptionKey = required(encryptionKey, "WECOM_DIRECTORY_ENCRYPTION_KEY");
        this.oauthCallbackUrl = URI.create(required(
                oauthCallbackUrl, "WECOM_DIRECTORY_OAUTH_CALLBACK_URL"));
        this.invitationTtl = Duration.ofMinutes(bounded(
                invitationTtlMinutes, 5, 120, "directory invitation TTL"));
        this.sharedInvitationTtl = Duration.ofDays(bounded(
                sharedInvitationTtlDays, 1, 7, "directory shared invitation TTL"));
        this.exchangeTtl = Duration.ofMinutes(bounded(
                exchangeTtlMinutes, 1, 10, "directory exchange TTL"));
        this.sessionTtl = Duration.ofMinutes(bounded(
                sessionTtlMinutes, 5, 120, "directory session TTL"));
        this.callbackClockSkew = Duration.ofSeconds(bounded(
                callbackClockSkewSeconds, 60, 1800, "directory callback clock skew"));
    }

    @PostConstruct
    void validate() {
        if (directorySyncEnabled && callbackAesKey.length() != 43) {
            throw new IllegalStateException(
                    "WECOM_DIRECTORY_CALLBACK_AES_KEY must contain exactly 43 Base64 characters");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("WECOM_DIRECTORY_ENCRYPTION_KEY must be Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("WECOM_DIRECTORY_ENCRYPTION_KEY must decode to 32 bytes");
        }
        if (!"https".equalsIgnoreCase(oauthCallbackUrl.getScheme())
                || oauthCallbackUrl.getRawQuery() != null
                || oauthCallbackUrl.getRawFragment() != null) {
            throw new IllegalStateException(
                    "WECOM_DIRECTORY_OAUTH_CALLBACK_URL must be an HTTPS URL without query or fragment");
        }
    }

    public UUID tenantId() { return wecom.tenantId(); }
    public boolean directorySyncEnabled() { return directorySyncEnabled; }
    public String corpId() { return wecom.corpId(); }
    public long agentId() { return wecom.agentId(); }
    public URI frontendBaseUrl() { return wecom.frontendBaseUrl(); }
    public String callbackToken() { return callbackToken; }
    public String callbackAesKey() { return callbackAesKey; }
    public String receiveId() { return receiveId; }
    public String encryptionKey() { return encryptionKey; }
    public URI oauthCallbackUrl() { return oauthCallbackUrl; }
    public Duration invitationTtl() { return invitationTtl; }
    public Duration sharedInvitationTtl() { return sharedInvitationTtl; }
    public Duration exchangeTtl() { return exchangeTtl; }
    public Duration sessionTtl() { return sessionTtl; }
    public Duration callbackClockSkew() { return callbackClockSkew; }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when WeCom employee onboarding is enabled");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static long bounded(long value, long min, long max, String label) {
        if (value < min || value > max) {
            throw new IllegalStateException(label + " must be between " + min + " and " + max);
        }
        return value;
    }
}
