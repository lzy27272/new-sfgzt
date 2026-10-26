package cn.sifangguan.hotelaios.integrations.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Base64;
import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeComProtocolTest {
    @Test
    void botJsonCallbackRoundTripsWithSignatureReceiverAndBotValidation() {
        WeComProperties properties = properties();
        WeComCallbackCrypto crypto = new WeComCallbackCrypto(properties);
        WeComJson json = new WeComJson(new ObjectMapper(), properties);
        String plaintext = """
                {"msgid":"msg-1","aibotid":"bot-1","from":{"userid":"zhangsan"},
                 "msgtype":"event","event":{"eventtype":"template_card_event","event_key":"opaque-key"}}
                """;
        String encrypted = crypto.encrypt(plaintext);
        String signature = crypto.signature("1700000000", "nonce-1", encrypted);

        assertThat(crypto.signatureMatches(signature, "1700000000", "nonce-1", encrypted)).isTrue();
        WeComInboundMessage callback = json.parseCallback(crypto.decrypt(encrypted));
        assertThat(callback.messageId()).isEqualTo("msg-1");
        assertThat(callback.receiptType()).isEqualTo("TEMPLATE_CARD_EVENT");
        assertThat(callback.fromUserId()).isEqualTo("zhangsan");
        assertThat(callback.eventKey()).isEqualTo("opaque-key");

        String response = json.encryptedResponse(encrypted, signature, "1700000000", "nonce-1");
        assertThat(response).contains("\"encrypt\"", "\"msgsignature\"", "\"timestamp\"", "\"nonce\"");
    }

    @Test
    void rejectsUnsafeOrTasklessReturnTargets() {
        String task = UUID.randomUUID().toString();
        assertThat(WeComOAuthService.validateReturnTo("#/finance?tab=review&id="+task)).isEqualTo("#/finance?tab=review&id="+task);
        assertThat(WeComDeliveryWorker.returnToFor("FINANCE",UUID.fromString(task),"FINANCE_REVIEW_PENDING")).isEqualTo("#/finance?tab=review&id="+task);
        assertThatThrownBy(()->WeComOAuthService.validateReturnTo("#/finance?tab=configuration&id="+task)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WeComOAuthService.validateReturnTo("#/finance?tab=review&id="+task+"&id="+task)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WeComOAuthService.validateReturnTo("#/tasks?view=mine&taskId=" + task))
                .isEqualTo("#/tasks?view=mine&taskId=" + task);
        assertThat(WeComOAuthService.validateReturnTo("#/daily-reports/" + task))
                .isEqualTo("#/daily-reports/" + task);
        assertThat(WeComOAuthService.validateReturnTo("#/my-work?expectationId=" + task))
                .isEqualTo("#/my-work?expectationId=" + task);
        assertThat(WeComOAuthService.validateReturnTo("#/workbench"))
                .isEqualTo("#/workbench");
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("https://evil.example/tasks?taskId=" + task))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/tasks?view=mine"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/admin?taskId=" + task))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/daily-reports/" + task + "?admin=true"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/workbench?next=/admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/my-work"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/my-work?expectationId=not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeComOAuthService.validateReturnTo("#/my-work?expectationId=" + task + "&next=/admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void browserSessionRedirectKeepsOnlyTheSafeTaskHashAndNonSecretMarker() {
        WeComOAuthService service = new WeComOAuthService(
                properties(), null, null, null, null, null, null, null);
        String task = UUID.randomUUID().toString();

        assertThat(service.browserSessionLocation("#/my-work?expectationId=" + task))
                .isEqualTo(URI.create("http://localhost:5173/?wecom_session=1#/my-work?expectationId=" + task));
        assertThat(service.browserSessionLocation("/workbench"))
                .isEqualTo(URI.create("http://localhost:5173/?wecom_session=1#/workbench"));
        assertThatThrownBy(() -> service.browserSessionLocation("https://evil.example/tasks?taskId=" + task))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frontendExchangeRedirectContainsOnlyTheOpaqueOneTimeCode() {
        WeComOAuthService service = new WeComOAuthService(
                properties(), null, null, null, null, null, null, null);

        assertThat(service.frontendExchangeLocation("Abcd_efghijklmnopqrstuvwxyz0123456789-AB"))
                .isEqualTo(URI.create(
                        "http://localhost:5173/wecom-auth?exchange_code=Abcd_efghijklmnopqrstuvwxyz0123456789-AB"));
        assertThatThrownBy(() -> service.frontendExchangeLocation("bad&next=https://evil.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void botPostReturnsEncryptedJsonContentType() throws Exception {
        WeComCallbackService service = mock(WeComCallbackService.class);
        when(service.handleBotJson("sig", "1700000000", "nonce", "{\"encrypt\":\"cipher\"}"))
                .thenReturn("{\"encrypt\":\"reply\",\"msgsignature\":\"reply-sig\",\"timestamp\":\"1700000000\",\"nonce\":\"nonce\"}");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComCallbackController(service)).build();

        mvc.perform(post("/api/v1/integrations/wecom/bot/callback")
                        .param("msg_signature", "sig").param("timestamp", "1700000000").param("nonce", "nonce")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"encrypt\":\"cipher\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    static WeComProperties properties() {
        String aesKey = Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        WeComProperties properties = new WeComProperties(
                UUID.randomUUID().toString(), "corp-1", "100001", "corp-secret-value",
                "callback-token", aesKey, "bot-1", "bot-receiver-1",
                true,
                "http://localhost:5173", "https://api.example.test/api/v1/integrations/wecom/oauth/callback",
                10, 2, 30, 500);
        properties.validate();
        return properties;
    }
}
