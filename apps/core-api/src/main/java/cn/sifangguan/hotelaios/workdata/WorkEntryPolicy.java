package cn.sifangguan.hotelaios.workdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Current input contract. Does not rewrite stored submissions or published evidence. */
public final class WorkEntryPolicy {
    private WorkEntryPolicy() { }
    public static final Set<String> OCCURRENCE_ITEMS = Set.of(
            "HKA_GUEST_SERVICE_EVENT", "HKA_COMPLAINT_EVENT", "HKA_LOST_PROPERTY");
    private static final Set<String> GENERIC_KEYS = Set.of("workSummary", "issueFound", "workResult", "hasException");
    private static final Set<String> GENERIC_TITLES = Set.of(
            "工作结果", "是否有异常", "是否发现问题", "班次工作结果", "夜班工作结果", "服务结果", "完成情况");
    private static final Set<String> LEGACY_HOUSEKEEPING_KEYS = Set.of(
            "issueSummary", "checkoutRooms", "stayoverRooms", "inventoryQuantities",
            "lostPropertyOccurred", "handoverRecipientAssignmentId", "aiExtractionConfirmed");
    private static final Map<String, Set<String>> HOUSEKEEPING_FIELDS = Map.of(
            "HKA_ITEM_ISSUE", Set.of("inventoryQuantities"),
            "HKA_CART_PREPARATION", Set.of("notes"),
            "HKA_USAGE_RETURN", Set.of("inventoryQuantities", "notes"),
            "HKA_DAILY_REPORT", Set.of("checkoutRooms", "stayoverRooms", "inventoryQuantities",
                    "plannedHygieneContent", "plannedHygieneRoomNumbers"),
            "HKA_LOST_PROPERTY", Set.of("roomNumber", "itemName", "quantity", "foundAt", "foundLocation", "handoverRecipientAssignmentId", "notes"));

    public static boolean occursOnDemand(String itemCode) {
        String code = itemCode == null ? "" : itemCode.toUpperCase(Locale.ROOT).replaceFirst("_FACT$", "");
        return OCCURRENCE_ITEMS.contains(code);
    }

    public static boolean genericQuestion(String key, String title) {
        return GENERIC_KEYS.contains(key) || GENERIC_TITLES.contains(title);
    }

    /** Existing conditional evidence rules use actual issue details, not a removed yes/no question. */
    public static JsonNode policyValue(JsonNode payload, String field) {
        if ("issueFound".equals(field) || "hasException".equals(field)) {
            String details = payload == null ? "" : payload.path("issueSummary").asText("").trim();
            return JsonNodeFactory.instance.booleanNode(!details.isBlank()
                    && !Set.of("无","无异常","无问题","未发现问题","正常").contains(details));
        }
        return payload == null ? null : payload.get(field);
    }

    public static JsonNode schema(JsonNode original, String itemCode) {
        if (original == null || !original.isObject()) return original;
        ObjectNode result = original.deepCopy();
        ObjectNode properties = result.withObject("properties");
        List<String> removed = new ArrayList<>();
        properties.fields().forEachRemaining(field -> {
            if (GENERIC_KEYS.contains(field.getKey()) || GENERIC_TITLES.contains(field.getValue().path("title").asText())
                    || (HOUSEKEEPING_FIELDS.containsKey(itemCode == null ? "" : itemCode)
                        && !HOUSEKEEPING_FIELDS.get(itemCode).contains(field.getKey()))) removed.add(field.getKey());
        });
        properties.remove(removed);
        var required = result.putArray("required");
        for (JsonNode field : original.path("required")) if (properties.has(field.asText())) required.add(field.asText());
        if ("HKA_CART_PREPARATION".equals(itemCode) || "HKA_USAGE_RETURN".equals(itemCode)) {
            properties.set("notes", stringField("补充说明（选填）", 2000));
        }
        if ("HKA_DAILY_REPORT".equals(itemCode)) {
            properties.set("plannedHygieneContent", stringField("计划卫生内容（选填）", 2000));
            properties.set("plannedHygieneRoomNumbers", stringField("计划卫生房间号（选填）", 1000));
            ((ObjectNode) properties.get("plannedHygieneRoomNumbers"))
                    .put("description", "可填写多个房号，例如：1201、1202、1203；无安排时可留空。");
        }
        if ("HKA_LOST_PROPERTY".equals(itemCode)) {
            properties.set("roomNumber", stringField("房号", 32));
            properties.set("itemName", stringField("遗留物品名称", 200));
            properties.set("quantity", JsonNodeFactory.instance.objectNode().put("type", "integer").put("title", "数量").put("minimum", 1));
            properties.set("foundAt", stringField("发现时间", 80));
            properties.set("foundLocation", stringField("发现位置", 200));
            properties.set("handoverRecipientAssignmentId", stringField("遗留物接收人", 200));
            properties.set("notes", stringField("交接说明（选填）", 2000));
            required.removeAll();
            for (String key : List.of("roomNumber", "itemName", "quantity", "foundAt", "foundLocation", "handoverRecipientAssignmentId")) {
                required.add(key);
                if (!"quantity".equals(key)) ((ObjectNode) properties.get(key)).put("minLength", 1);
            }
        }
        return result;
    }

    private static JsonNode stringField(String title, int maxLength) {
        return JsonNodeFactory.instance.objectNode().put("type", "string").put("title", title).put("maxLength", maxLength);
    }

    /** Stale local drafts may carry the removed questions. Preserve storage, ignore them in input validation. */
    public static JsonNode validationPayload(JsonNode payload, JsonNode effectiveSchema, JsonNode originalSchema, String itemCode) {
        if (payload == null || !payload.isObject()) return payload;
        ObjectNode result = payload.deepCopy();
        GENERIC_KEYS.forEach(key -> { if (!effectiveSchema.path("properties").has(key)) result.remove(key); });
        if (HOUSEKEEPING_FIELDS.containsKey(itemCode == null ? "" : itemCode))
            LEGACY_HOUSEKEEPING_KEYS.forEach(key -> {
                if (!effectiveSchema.path("properties").has(key)) result.remove(key);
            });
        originalSchema.path("properties").fieldNames().forEachRemaining(key -> {
            if (!effectiveSchema.path("properties").has(key)) result.remove(key);
        });
        return result;
    }
}
