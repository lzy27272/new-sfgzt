package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeComDirectoryOnboardingControllerTest {
    @Test
    void selfServiceRequiresNoInvitationButIssuesOnlyBrowserBoundOAuth() throws Exception {
        var employee = mock(WeComDirectoryOnboardingService.class);
        when(employee.startSelfService()).thenReturn(new WeComDirectoryOnboardingService.Start(
                URI.create("https://open.weixin.qq.com/authorize"), "browser-verifier", 600));
        var mvc = MockMvcBuilders.standaloneSetup(new WeComDirectoryOnboardingController(employee,
                mock(WeComDirectoryOnboardingAdministrationService.class), mock(WeComDirectoryProperties.class))).build();
        mvc.perform(post("/api/v1/integrations/wecom/directory-onboarding/self-service/start"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(jsonPath("$.authorizationUri").value("https://open.weixin.qq.com/authorize"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void administratorCanCreateAnEmployeeSelfRegistrationInvitation() throws Exception {
        WeComDirectoryOnboardingService employeeService = mock(WeComDirectoryOnboardingService.class);
        WeComDirectoryOnboardingAdministrationService administrationService =
                mock(WeComDirectoryOnboardingAdministrationService.class);
        WeComDirectoryProperties properties = mock(WeComDirectoryProperties.class);
        UUID candidateId = UUID.randomUUID();
        when(administrationService.createOpenInvitation()).thenReturn(
                new WeComDirectoryOnboardingModels.OpenInvitationResponse(
                        candidateId,
                        URI.create("https://www.sfgzt.cn/#/wecom-onboarding?token=one-time"),
                        OffsetDateTime.parse("2026-09-12T15:00:00+08:00"),
                        "WAITING_PROFILE", 0,
                        "注册链接已生成，请让员工使用企业微信扫码或打开链接自行填写"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComDirectoryOnboardingController(
                employeeService, administrationService, properties)).build();

        mvc.perform(post("/api/v1/integrations/wecom/directory-onboarding/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(candidateId.toString()))
                .andExpect(jsonPath("$.status").value("WAITING_PROFILE"))
                .andExpect(jsonPath("$.enrollmentUrl").value(
                        "https://www.sfgzt.cn/#/wecom-onboarding?token=one-time"));
    }

    @Test
    void oauthFailureRedirectsWithOnlyAnAllowlistedResultCode() throws Exception {
        WeComDirectoryOnboardingService employeeService = mock(WeComDirectoryOnboardingService.class);
        WeComDirectoryOnboardingAdministrationService administrationService =
                mock(WeComDirectoryOnboardingAdministrationService.class);
        WeComDirectoryProperties properties = mock(WeComDirectoryProperties.class);
        when(properties.frontendBaseUrl()).thenReturn(URI.create("https://www.sfgzt.cn"));
        when(employeeService.callback("provider-code", "state", "verifier"))
                .thenThrow(new WeComDirectoryOnboardingService.OAuthCallbackFailure(
                        "OAUTH_IDENTITY_MISMATCH",
                        new IllegalArgumentException("sensitive-full-user-id")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComDirectoryOnboardingController(
                employeeService, administrationService, properties)).build();

        mvc.perform(get("/api/v1/integrations/wecom/directory-onboarding/oauth/callback")
                        .param("code", "provider-code").param("state", "state")
                        .cookie(new Cookie(WeComDirectoryOnboardingController.VERIFIER_COOKIE, "verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_IDENTITY_MISMATCH"))
                .andExpect(header().string("Location", not(containsString("sensitive-full-user-id"))))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void unknownRuntimeFailureFallsBackToTheGenericAllowlistedCode() {
        URI result = WeComDirectoryOnboardingService.oauthFailureRedirect(
                URI.create("https://www.sfgzt.cn/"),
                new RuntimeException("never expose pasted UserID"));

        assertThat(result.toString()).isEqualTo(
                "https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_VERIFICATION_FAILED");
        assertThat(result.toString()).doesNotContain("UserID");
    }
}
