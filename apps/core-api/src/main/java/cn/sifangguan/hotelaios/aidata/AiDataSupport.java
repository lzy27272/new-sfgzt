package cn.sifangguan.hotelaios.aidata;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class AiDataSupport {
    private AiDataSupport() {}
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String scope(TenantPrincipal p) {
        return hash(p.tenantId()+"|"+p.actorId()+"|"+p.hasTenantScope()+"|"+p.businessActorAssignmentId()+"|"
            + sorted(p.permissions())+"|"+sorted(p.orgScopes())+"|"+sorted(p.assignmentIds())+"|"+sorted(p.roleCodes()));
    }
    private static String sorted(Collection<?> items) { return items.stream().map(Object::toString).sorted().toList().toString(); }
}
