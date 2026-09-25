package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.util.Set;

/** Official DeepSeek vision only; third-party proxies require a separate integration review. */
public final class DeepSeekVisionGateway extends ChatCompletionsVisionGateway {
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    public DeepSeekVisionGateway(ObjectMapper json, boolean enabled, boolean approved, String key, String model, String endpoint) {
        this(json, enabled, approved, key, model, endpoint, defaultClient());
    }

    // Transport injection for offline contract tests; endpoint/redirect restrictions still apply.
    DeepSeekVisionGateway(ObjectMapper json, boolean enabled, boolean approved, String key,
                          String model, String endpoint, HttpClient http) {
        super(json, enabled, approved, key, validModel(model), configuredOrDefault(endpoint, DEFAULT_ENDPOINT),
                Set.of(DEFAULT_ENDPOINT, "https://api.deepseek.com/v1/chat/completions"), http);
    }

    private static String validModel(String value) {
        String model = configuredOrDefault(value, "deepseek-flash");
        if (!"deepseek-flash".equals(model))
            throw new IllegalArgumentException("DeepSeek图片检查请使用已适配的deepseek-flash视觉模型");
        return model;
    }

    @Override void configureProvider(ObjectNode request) {
        // DeepSeek uses thinking.type, not Qwen's enable_thinking flag.
        request.putObject("thinking").put("type", "disabled");
    }
}
