package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline transport contracts. No test sends data to an external model. */
class DeepSeekVisionGatewayTest {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = mock(HttpClient.class);
    final List<AiVisionGateway.Image> photos = List.of(new AiVisionGateway.Image("photo-1", "image/png", new byte[]{1, 2}, "检查证据"));
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new).withUserConfiguration(AiVisionConfiguration.class);

    @BeforeEach void noRedirects() { when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER); }

    JsonNode config() throws Exception {
        return json.readTree("""
                {"criteria":[{"code":"BADGE","enabled":true,"standard":"胸前佩戴工牌"}],
                 "exceptions":"允许佩戴经批准的临时工牌","accountId":"do-not-send-account","employeeName":"do-not-send-name"}
                """);
    }

    DeepSeekVisionGateway gateway(boolean enabled, boolean approved, String key) {
        return new DeepSeekVisionGateway(json, enabled, approved, key, "", "", http);
    }

    byte[] envelope(String content, String finish) throws Exception {
        var root = json.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("content", content);
        root.putObject("usage").put("prompt_tokens", 842).put("completion_tokens", 127);
        return json.writeValueAsBytes(root);
    }

    @SuppressWarnings("unchecked") void reply(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.<byte[]>sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test void usesDeepSeekVisionAndNonThinkingJsonWithoutLeakingIdentity() throws Exception {
        var request = gateway(false, false, "").request(config(), photos);
        assertThat(request.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(request.has("enable_thinking")).isFalse();
        assertThat(request.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(1200);
        assertThat(request.at("/messages/0/content").asText()).contains("JSON", "不做人脸识别", "MAKEUP", "visibility", "不能将看不清当成未化妆");
        assertThat(request.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(request.at("/messages/1/content/2/image_url/url").asText()).startsWith("data:image/png;base64,");
        assertThat(request.toString()).doesNotContain("do-not-send", "Bearer", "tools", "file_id");
    }

    @Test void allThreeReadinessConditionsAreRequiredAndNoImagesLeaveWhenDisabled() throws Exception {
        for (var provider : List.of(gateway(false, true, "test-secret"), gateway(true, false, "test-secret"), gateway(true, true, "  "))) {
            assertThat(provider.ready()).isFalse();
            assertThatThrownBy(() -> provider.inspect(config(), photos)).hasMessage("MODEL_NOT_CONFIGURED");
        }
        verify(http, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void doesNotSilentlySendPhotographsToUnvalidatedTextOrLegacyModels() {
        for (String model : List.of("deepseek-chat", "deepseek-reasoner", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp", "qwen3-vl-plus"))
            assertThatThrownBy(() -> new DeepSeekVisionGateway(json, true, true, "test", model, "", http))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void endpointAllowlistRejectsProxiesLookalikesAndCredentialMismatches() {
        for (String endpoint : List.of("http://api.deepseek.com/chat/completions", "http://localhost:1",
                "https://api.deepseek.com.example.com/chat/completions", "https://api.deepseek.com/chat/completions?redirect=1",
                "https://test@api.deepseek.com/chat/completions", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
            assertThatThrownBy(() -> new DeepSeekVisionGateway(json, true, true, "test", "", endpoint, http))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DeepSeekVisionGateway(json, true, true, "test", "", "https://api.deepseek.com/v1/chat/completions", http).ready()).isTrue();
    }

    @Test void transportCannotFollowCredentialBearingRedirects() {
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatThrownBy(() -> gateway(true, true, "test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void extractsActualUsageAndSendsOnlyTheSelectedOfficialRequest() throws Exception {
        reply(200, envelope("{\"personCount\":1,\"checks\":[]}", "stop"));
        var result = gateway(true, true, "mock-only-secret").inspect(config(), photos);
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.inputTokens()).isEqualTo(842);
        assertThat(result.outputTokens()).isEqualTo(127);
        assertThat(result.findings().path("personCount").asInt()).isEqualTo(1);
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer mock-only-secret");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(45));
        var collected = HttpResponse.BodySubscribers.ofByteArray();
        request.getValue().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { collected.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer chunk) { collected.onNext(List.of(chunk)); }
            public void onError(Throwable failure) { collected.onError(failure); }
            public void onComplete() { collected.onComplete(); }
        });
        String body = new String(collected.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8);
        assertThat(body).contains("deepseek-flash", "image_url").doesNotContain("mock-only-secret", "do-not-send");
    }

    @Test void nonSuccessIsSanitizedAndDoesNotAutomaticallyRetryOrSwitchProviders() throws Exception {
        reply(429, "mock-only-secret provider response must not escape".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gateway(true, true, "mock-only-secret").inspect(config(), photos))
                .hasMessage("PROVIDER_UNAVAILABLE").hasNoCause();
        verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void emptyInvalidAndNonObjectJsonCannotBecomeSuccessfulObservations() throws Exception {
        for (String content : List.of("", " ", "null", "[]", "not-json", "{\"broken\":")) {
            reply(200, envelope(content, "stop"));
            assertThatThrownBy(() -> gateway(true, true, "test").inspect(config(), photos))
                    .hasMessage("PROVIDER_UNAVAILABLE").hasNoCause();
        }
    }

    @Test void truncatedOutputFailsInsteadOfRecordingAPass() throws Exception {
        reply(200, envelope("{\"personCount\":1,\"checks\":[]}", "length"));
        assertThatThrownBy(() -> gateway(true, true, "test").inspect(config(), photos)).hasMessage("PROVIDER_UNAVAILABLE");
    }

    @Test void evidenceLimitsApplyBeforeAnyNetworkCall() throws Exception {
        var provider = gateway(true, true, "test");
        for (var images : List.of(List.<AiVisionGateway.Image>of(), Collections.nCopies(11, photos.getFirst()),
                List.of(new AiVisionGateway.Image("bad", "text/html", new byte[]{1}, "evidence")),
                List.of(new AiVisionGateway.Image("large", "image/png", new byte[16 * 1024 * 1024 + 1], "evidence"))))
            assertThatThrownBy(() -> provider.inspect(config(), images)).hasMessage("PROVIDER_UNAVAILABLE");
        verify(http, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void existingQwenDefaultRemainsOptInAndUnchanged() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(AiVisionGateway.class);
            assertThat(ctx.getBean(AiVisionGateway.class)).isInstanceOf(QwenVisionGateway.class);
            assertThat(ctx.getBean(AiVisionGateway.class).ready()).isFalse();
        });
    }

    @Test void deepSeekSelectionCreatesOnlyOneProviderAndDoesNotEnableIt() {
        context.withPropertyValues("app.ai-evidence.provider=deepseek").run(ctx -> {
            assertThat(ctx).hasSingleBean(AiVisionGateway.class);
            assertThat(ctx.getBean(AiVisionGateway.class)).isInstanceOf(DeepSeekVisionGateway.class);
            assertThat(ctx.getBean(AiVisionGateway.class).ready()).isFalse();
        });
    }

    @Test void unknownProviderFailsClosedRatherThanSilentlyPickingAnother() {
        context.withPropertyValues("app.ai-evidence.provider=third-party").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void switchingProvidersWithAnOldEndpointCannotSendTheNewKeyToTheOldVendor() {
        context.withPropertyValues("app.ai-evidence.provider=deepseek",
                "app.ai-evidence.endpoint=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
