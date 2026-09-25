package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class WeComOAuthControllerTest {
    @Test
    void browserExchangeSetsSessionCookieAndRedirectsWithoutExposingTheJwt() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        UUID accountId = UUID.randomUUID();
        String returnTo = "#/my-work?expectationId=10000000-0000-0000-0000-000000000001";
        when(service.exchange("one-time-browser-code"))
                .thenReturn(new WeComOAuthModels.ExchangeResponse(
                        "signed.jwt.value", "Bearer", OffsetDateTime.now().plusMinutes(30),
                        accountId, "Employee", returnTo));
        when(service.browserSessionLocation(returnTo)).thenReturn(URI.create(
                "https://www.sfgzt.cn/?wecom_session=1#/my-work?expectationId=10000000-0000-0000-0000-000000000001"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(post("/api/v1/integrations/wecom/oauth/browser-exchange")
                        .contentType("application/x-www-form-urlencoded")
                        .param("exchangeCode", "one-time-browser-code"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location",
                        "https://www.sfgzt.cn/?wecom_session=1#/my-work?expectationId=10000000-0000-0000-0000-000000000001"))
                .andExpect(header().string("Set-Cookie", containsString(
                        "__Host-hotel_ai_wecom_session=signed.jwt.value")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(content().string(""));
    }

    @Test
    void exchangeEstablishesPersistentHttpOnlyWeComSessionCookie() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        UUID accountId = UUID.randomUUID();
        when(service.exchange("one-time-exchange-code"))
                .thenReturn(new WeComOAuthModels.ExchangeResponse(
                        "signed.jwt.value", "Bearer", OffsetDateTime.now().plusMinutes(30),
                        accountId, "Employee", "#/workbench"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(post("/api/v1/integrations/wecom/oauth/exchange")
                        .contentType(APPLICATION_JSON)
                        .content("{\"exchangeCode\":\"one-time-exchange-code\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(header().string("Set-Cookie", containsString(
                        "__Host-hotel_ai_wecom_session=signed.jwt.value")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void startBindsBrowserWithHostOnlySecureCookieAndNoStore() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        when(service.start("#/tasks?view=mine&taskId=10000000-0000-0000-0000-000000000001"))
                .thenReturn(new WeComOAuthService.Start(URI.create("https://open.weixin.qq.com/authorize"),
                        "browser-verifier", 600));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/start")
                        .param("returnTo", "#/tasks?view=mine&taskId=10000000-0000-0000-0000-000000000001"))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Set-Cookie", containsString("__Host-wecom_oauth_verifier=browser-verifier")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    void callbackWithoutProviderParametersOffersLoginAndRegistrationInsteadOfForcedOAuth() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        when(service.frontendEntryLocation()).thenReturn(URI.create("https://www.sfgzt.cn/#/login"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .cookie(new Cookie(WeComOAuthController.BINDING_VERIFIER_COOKIE, "stale-binding-verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://www.sfgzt.cn/#/login"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString(
                        "__Host-wecom_oauth_verifier=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString(
                        "__Host-wecom_binding_verifier=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("Max-Age=0"))))
                .andExpect(header().doesNotExist("Content-Disposition"));
        verify(service, never()).start(org.mockito.ArgumentMatchers.any());
        verify(service, never()).callback(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(enrollmentService, never()).callback(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackWithOnlyOneProviderParameterStillFailsClosed() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .param("code", "provider-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        verify(service, never()).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackHandsAOneTimeCodeToTheFrontendWithoutDependingOnCookiePersistence() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        when(service.callback("provider-code", "state", "browser-verifier"))
                .thenReturn(new WeComOAuthService.CallbackAuthorization("server-only-once"));
        when(service.frontendExchangeLocation("server-only-once")).thenReturn(URI.create(
                "https://www.sfgzt.cn/wecom-auth?exchange_code=server-only-once"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .param("code", "provider-code").param("state", "state")
                        .cookie(new Cookie(WeComOAuthController.VERIFIER_COOKIE, "browser-verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://www.sfgzt.cn/wecom-auth?exchange_code=server-only-once"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString(
                        "__Host-wecom_oauth_verifier=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("Max-Age=0"))))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().stringValues("Set-Cookie", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(containsString("hotel_ai_wecom_session")))))
                .andExpect(content().string(""));
        verify(service).callback("provider-code", "state", "browser-verifier");
        verify(service).frontendExchangeLocation("server-only-once");
        verify(service, never()).exchange(org.mockito.ArgumentMatchers.any());

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .param("code", "provider-code").param("state", "state"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void callbackRoutesEnrollmentCookieToEnrollmentFlow() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        when(enrollmentService.callback("provider-code", "state", "binding-verifier"))
                .thenReturn(URI.create("https://app.example.test/#/wecom-binding-result?status=pending"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .param("code", "provider-code").param("state", "state")
                        .cookie(new Cookie(WeComOAuthController.BINDING_VERIFIER_COOKIE, "binding-verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("wecom-binding-result")));
        verify(enrollmentService).callback("provider-code", "state", "binding-verifier");
    }

    @Test
    void taskIdentityFailureRedirectsToReadableHtmlEntryInsteadOfDownloadResponse() throws Exception {
        WeComOAuthService service = mock(WeComOAuthService.class);
        WeComBindingEnrollmentService enrollmentService = mock(WeComBindingEnrollmentService.class);
        when(service.callback("provider-code", "state", "browser-verifier"))
                .thenThrow(new IllegalStateException("private provider secret must not appear in redirect"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(service, enrollmentService)).build();

        mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                        .param("code", "provider-code").param("state", "state")
                        .cookie(new Cookie(WeComOAuthController.VERIFIER_COOKIE, "browser-verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/wecom-auth?error_code=VERIFICATION_FAILED"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString(
                        "__Host-wecom_oauth_verifier=;"))))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void missingBindingHasAnActionableErrorWithoutClaimingTheCredentialExpired() throws Exception {
        for (WeComLoginException.Reason reason : WeComLoginException.Reason.values()) {
            WeComOAuthService service = mock(WeComOAuthService.class);
            when(service.callback("provider-code", "state", "browser-verifier"))
                    .thenThrow(new WeComLoginException(reason));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComOAuthController(
                    service, mock(WeComBindingEnrollmentService.class))).build();
            mvc.perform(get("/api/v1/integrations/wecom/oauth/callback")
                            .param("code", "provider-code").param("state", "state")
                            .cookie(new Cookie(WeComOAuthController.VERIFIER_COOKIE, "browser-verifier")))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/wecom-auth?error_code=" + reason.name()))
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(content().string(""));
        }
    }
}
