package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Model text is untrusted. Missing observations never become a pass. */
public final class AiEvidenceResultValidator {
    private AiEvidenceResultValidator() {}
    public static ObjectNode validate(JsonNode result, JsonNode configuration, Set<String> evidenceIds) {
        if (!result.isObject() || !result.path("checks").isArray()) throw new IllegalArgumentException("INVALID_MODEL_OUTPUT");
        Set<String> allowed = new LinkedHashSet<>();
        configuration.path("criteria").forEach(c -> { if(c.path("enabled").asBoolean()) allowed.add(c.path("code").asText()); });
        Map<String,JsonNode> observed = new LinkedHashMap<>();
        for(JsonNode check : result.path("checks")) {
            String code=check.path("code").asText();
            if(!allowed.contains(code) || observed.putIfAbsent(code,check)!=null) throw new IllegalArgumentException("INVALID_MODEL_OUTPUT");
        }
        ObjectNode safe=JsonNodeFactory.instance.objectNode();
        ArrayNode checks=safe.putArray("checks");
        String overall="PASS";
        boolean onePerson=result.path("personCount").isIntegralNumber() && result.path("personCount").asInt()==1;
        for(String code:allowed) {
            JsonNode raw=observed.get(code);
            String status=raw==null ? "UNKNOWN":raw.path("status").asText();
            if(!Set.of("PASS","CONCERN","UNKNOWN").contains(status)) throw new IllegalArgumentException("INVALID_MODEL_OUTPUT");
            String evidence=raw==null ? "":raw.path("evidenceId").asText();
            String observation=raw==null ? "模型未提供该项观察，请人工复核":raw.path("observation").asText();
            if(observation.length()>1200) throw new IllegalArgumentException("INVALID_MODEL_OUTPUT");
            // Makeup cannot be treated as absent merely because the photograph is inconclusive.
            if(code.equals("MAKEUP") && (raw==null || !raw.path("visibility").asText().equals("CLEAR"))) {
                status="UNKNOWN";
                observation="妆容证据清晰度不足或未确认；看不清不等于未化妆，请补充清晰正面近照或人工复核";
            }
            if(!onePerson) { status="UNKNOWN"; observation="未能确认照片仅对应一名员工，请分人提供清晰证据"; }
            if(!evidenceIds.contains(evidence) || observation.isBlank()) {
                status="UNKNOWN";
                if(observation.isBlank()) observation="缺少可核对的观察事实，请人工复核";
                evidence="";
            }
            if(status.equals("CONCERN")) overall="CONCERN";
            else if(status.equals("UNKNOWN") && !overall.equals("CONCERN")) overall="UNKNOWN";
            ObjectNode check=checks.addObject().put("code",code).put("status",status).put("observation",observation).put("evidenceId",evidence);
            // Bounding boxes are optional hints, not identity matching or proof of correctness.
            JsonNode box=raw==null ? null:raw.get("box");
            if(box!=null && box.isArray() && box.size()==4) {
                boolean valid=true;
                for(JsonNode n:box) valid &= n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble()>=0 && n.asDouble()<=1;
                valid &= box.get(2).asDouble()>box.get(0).asDouble() && box.get(3).asDouble()>box.get(1).asDouble();
                if(valid && !evidence.isEmpty()) check.set("box",box.deepCopy());
            }
        }
        if(allowed.isEmpty()) throw new IllegalArgumentException("INVALID_RULE");
        return safe.put("overall",overall).put("mode","SHADOW").put("promptVersion","appearance-v2");
    }
}
