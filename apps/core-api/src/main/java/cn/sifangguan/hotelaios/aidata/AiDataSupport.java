package cn.sifangguan.hotelaios.aidata;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.*;
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
    /** Stable across JSONB key ordering, whitespace and equivalent decimal scales. */
    static String dataHash(ObjectMapper json,JsonNode data) {
        try {return hash(json.writeValueAsString(canonical(data)));}
        catch(Exception e){throw new IllegalArgumentException("分析证据无法校验",e);}
    }
    private static Object canonical(JsonNode node) {
        if(node.isObject()) {var values=new TreeMap<String,Object>();node.fields().forEachRemaining(e->values.put(e.getKey(),canonical(e.getValue())));return values;}
        if(node.isArray()) {var values=new ArrayList<Object>();node.forEach(v->values.add(canonical(v)));return values;}
        if(node.isNumber()) return node.decimalValue().stripTrailingZeros();
        if(node.isBoolean()) return node.booleanValue();
        return node.isNull()?null:node.asText();
    }
    private static String sorted(Collection<?> items) { return items.stream().map(Object::toString).sorted().toList().toString(); }
}
