package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Opt-in only. A Qwen key is never sent to another provider. */
public final class QwenVisionGateway extends ChatCompletionsVisionGateway {
    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    public QwenVisionGateway(ObjectMapper json, boolean enabled, boolean approved, String key, String model, String endpoint) {
        super(json, enabled, approved, key, validModel(model), configuredOrDefault(endpoint, DEFAULT_ENDPOINT),
                Set.of(DEFAULT_ENDPOINT, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"), defaultClient());
    }

    private static String validModel(String value) {
        String model = configuredOrDefault(value, "qwen3-vl-plus");
        if (!model.matches("qwen3-vl-(plus|flash)(-[a-zA-Z0-9-]+)?"))
            throw new IllegalArgumentException("请配置支持结构化视觉输出的百炼模型");
        return model;
    }

    @Override void configureProvider(ObjectNode request) { request.put("enable_thinking", false); }
}
