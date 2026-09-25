package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** No business-write capability is exposed to a vision provider. */
public interface AiVisionGateway {
    record Image(String id, String mediaType, byte[] bytes, String purpose) {}
    record Observation(JsonNode findings, int inputTokens, int outputTokens, String model) {}
    boolean ready();
    Observation inspect(JsonNode configuration, List<Image> images);
}
