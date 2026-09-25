package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** Explicit, single paid request with an in-memory synthetic image. Disabled in ordinary test runs. */
@EnabledIfSystemProperty(named = "ai.evidence.live-probe", matches = "synthetic-once")
class DeepSeekLiveProbeTest {
    @Test void oneSyntheticImageThroughTheProductionAdapter() throws Exception {
        String key = System.getenv("SFG_AI_EVIDENCE_LIVE_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("LIVE_PROBE_KEY_REQUIRED");
        String output = System.getProperty("ai.evidence.probe-output");
        if (output == null || !Path.of(output).isAbsolute()) throw new IllegalStateException("ABSOLUTE_PROBE_OUTPUT_REQUIRED");
        Path reportPath = Path.of(output);
        if (Files.exists(reportPath)) throw new IllegalStateException("PROBE_REPORT_ALREADY_EXISTS");
        Files.createDirectories(reportPath.getParent());

        ObjectMapper json = new ObjectMapper();
        var configuration = json.readTree("""
                {"criteria":[
                    {"code":"UNIFORM","enabled":true,"standard":"上身穿着藏蓝色制服上衣"},
                    {"code":"BADGE","enabled":true,"standard":"胸前可见矩形工牌"},
                    {"code":"EQUIPMENT","enabled":true,"standard":"腰间佩戴对讲机"}],
                 "exceptions":"这是非真人的合成连通测试图；无法确认真实员工时请明确返回无法确认，不推断身份。"}
                """);
        byte[] photo = syntheticPhoto();
        var gateway = new DeepSeekVisionGateway(json, true, true, key, "deepseek-flash", "");
        Instant started = Instant.now();
        // Exactly one inspect call. No retries, employee data, tenant queue, database or Spring context.
        var observation = gateway.inspect(configuration,
                List.of(new AiVisionGateway.Image("synthetic-1", "image/png", photo, "非真人合成测试图")));
        long elapsedMillis = java.time.Duration.between(started, Instant.now()).toMillis();
        var validated = AiEvidenceResultValidator.validate(observation.findings(), configuration, Set.of("synthetic-1"));
        assertThat(observation.inputTokens()).isPositive();
        assertThat(observation.outputTokens()).isPositive();
        assertThat(validated.path("checks")).hasSize(3);
        assertThat(validated.path("mode").asText()).isEqualTo("SHADOW");

        var report = json.createObjectNode().put("status", "CONNECTED_SYNTHETIC_PROBE_PASSED")
                .put("startedAt", started.toString()).put("elapsedMillis", elapsedMillis)
                .put("provider", "deepseek").put("requestedModel", observation.model())
                .put("paidRequests", 1).put("imageCount", 1).put("syntheticOnly", true)
                .put("employeeImagesSent", 0).put("productionChanged", false)
                .put("inputTokens", observation.inputTokens()).put("outputTokens", observation.outputTokens())
                .put("totalTokens", observation.inputTokens() + observation.outputTokens());
        report.set("validatedResult", validated);
        Files.writeString(reportPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        System.out.println("LIVE_PROBE_OK inputTokens=" + observation.inputTokens()
                + " outputTokens=" + observation.outputTokens() + " elapsedMillis=" + elapsedMillis);
    }

    private byte[] syntheticPhoto() throws Exception {
        BufferedImage image = new BufferedImage(420, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(240, 245, 249)); g.fillRect(0, 0, 420, 640);
            g.setColor(new Color(155, 171, 184)); g.fillOval(165, 65, 90, 100);
            g.setColor(new Color(19, 44, 70));
            g.fillPolygon(new int[]{160, 260, 285, 252, 245, 175, 168, 135}, new int[]{175, 175, 340, 354, 225, 225, 354, 340}, 8);
            g.fillRect(170, 195, 80, 180);
            g.fillRect(172, 370, 34, 160); g.fillRect(214, 370, 34, 160);
            g.setColor(Color.WHITE); g.fillPolygon(new int[]{190, 210, 230}, new int[]{176, 213, 176}, 3);
            g.setColor(new Color(234, 177, 64)); g.fillRoundRect(223, 226, 30, 39, 4, 4);
            g.setColor(new Color(26, 32, 39)); g.fillRoundRect(160, 321, 24, 48, 4, 4); g.fillRect(164, 305, 4, 20);
            g.setColor(new Color(93, 109, 126)); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString("SYNTHETIC TEST - NOT A REAL PERSON", 28, 595);
        } finally { g.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
