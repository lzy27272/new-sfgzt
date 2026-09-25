package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class AiVisionConfiguration {
    @Bean
    AiVisionGateway aiVisionGateway(ObjectMapper json,
            @Value("${app.ai-evidence.provider:qwen}") String provider,
            @Value("${app.ai-evidence.enabled:false}") boolean enabled,
            @Value("${app.ai-evidence.external-processing-approved:false}") boolean approved,
            @Value("${app.ai-evidence.api-key:}") String key,
            @Value("${app.ai-evidence.model:}") String model,
            @Value("${app.ai-evidence.endpoint:}") String endpoint) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "qwen" -> new QwenVisionGateway(json, enabled, approved, key, model, endpoint);
            case "deepseek" -> new DeepSeekVisionGateway(json, enabled, approved, key, model, endpoint);
            default -> throw new IllegalArgumentException("AI检查服务商仅支持qwen或deepseek，禁止默认回落到其他服务商");
        };
    }
}
