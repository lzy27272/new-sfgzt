package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;

/** Public, allowlisted failure categories; never include provider credentials or member IDs. */
public final class WeComLoginException extends IdentityAuthenticationException {
    public enum Reason { MEMBER_UNBOUND, ACCOUNT_UNAVAILABLE, ASSIGNMENT_INACTIVE }

    private final Reason reason;

    public WeComLoginException(Reason reason) {
        super("WeCom login rejected: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
