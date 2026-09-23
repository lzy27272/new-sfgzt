package cn.sifangguan.hotelaios.storeassets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.UUID;

/** Retain the original quick-entry snapshot; only positive, explicitly entered use is posted. */
final class DailyUsageInput {
    private DailyUsageInput() { }

    static ObjectNode normalize(JsonNode input) {
        if (!input.path("schemaVersion").isIntegralNumber() || !input.path("schemaVersion").canConvertToInt() || input.path("schemaVersion").asInt() != 2
                || !input.path("remainingUnusedConfirmed").isBoolean() || !input.path("lines").isArray()
                || input.path("lines").size() > 100)
            throw new IllegalArgumentException("常用物品填写格式错误，请刷新后核对草稿");
        boolean confirmed = input.path("remainingUnusedConfirmed").asBoolean();
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1);
        var positive = result.putArray("lines");
        var seen = new HashSet<UUID>();
        for (JsonNode line : input.path("lines")) {
            UUID id;
            try { id = UUID.fromString(line.path("itemId").asText()); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("请选择有效的门店物品"); }
            if (!seen.add(id)) throw new IllegalArgumentException("同一种物品不能重复填报");
            if (!line.path("quantity").isTextual()) throw new IllegalArgumentException("物品数量格式错误");
            String value = line.path("quantity").asText().trim();
            if (value.isEmpty()) {
                if (!confirmed) throw new IllegalArgumentException("仍有未填物品，请填写数量或确认其余未填物品本日未使用");
                continue;
            }
            BigDecimal quantity;
            try { quantity = StoreItemUnits.exact(new BigDecimal(value), 4, 14, "使用数量"); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("使用数量必须是有效数字"); }
            if (quantity.signum() < 0) throw new IllegalArgumentException("使用数量不能小于0");
            if (quantity.signum() > 0) positive.add(((ObjectNode)line).deepCopy().put("quantity",quantity.toPlainString()));
        }
        if (input.path("lines").isEmpty() && !confirmed)
            throw new IllegalArgumentException("请添加使用物品，或明确确认本日未使用");
        result.put("noUsage", positive.isEmpty());
        return result;
    }
}
