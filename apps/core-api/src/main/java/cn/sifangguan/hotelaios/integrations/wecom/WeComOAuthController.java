package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.security.FederatedSessionCookie;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/integrations/wecom/oauth")
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComOAuthController {
    static final String VERIFIER_COOKIE = "__Host-wecom_oauth_verifier";
    static final String BINDING_VERIFIER_COOKIE = "__Host-wecom_binding_verifier";
    private final WeComOAuthService service;
    private final WeComBindingEnrollmentService enrollmentService;

    public WeComOAuthController(
            WeComOAuthService service, WeComBindingEnrollmentService enrollmentService
    ) {
        this.service = service;
        this.enrollmentService = enrollmentService;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String returnTo) {
        WeComOAuthService.Start start = service.start(returnTo);
        return startResponse(start, false);
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = VERIFIER_COOKIE, required = false) String browserVerifier,
            @CookieValue(name = BINDING_VERIFIER_COOKIE, required = false) String bindingBrowserVerifier
    ) {
        if (isBlank(code) && isBlank(state)) {
            return noStore(ResponseEntity.status(HttpStatus.FOUND))
                    .header(HttpHeaders.LOCATION, service.frontendEntryLocation().toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                    .build();
        }
        if (isBlank(code) || isBlank(state)) {
            return unauthorizedResponse();
        }
        boolean taskFlow = browserVerifier != null && !browserVerifier.isBlank();
        boolean bindingFlow = bindingBrowserVerifier != null && !bindingBrowserVerifier.isBlank();
        if (taskFlow == bindingFlow) {
            return unauthorizedResponse();
        }
        try {
            if (taskFlow) {
                WeComOAuthService.CallbackAuthorization authorization =
                        service.callback(code, state, browserVerifier);
                URI location = service.frontendExchangeLocation(authorization.exchangeCode());
                return noStore(ResponseEntity.status(HttpStatus.FOUND))
                        .header(HttpHeaders.LOCATION, location.toString())
                        .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                        .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                        .build();
            }
            URI location = enrollmentService.callback(code, state, bindingBrowserVerifier);
            return noStore(ResponseEntity.status(HttpStatus.FOUND))
                    .header(HttpHeaders.LOCATION, location.toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                    .build();
        } catch (RuntimeException exception) {
            if (taskFlow) {
                return noStore(ResponseEntity.status(HttpStatus.FOUND))
                        .header(HttpHeaders.LOCATION, "/wecom-auth?error_code=" + loginErrorCode(exception))
                        .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                        .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                        .build();
            }
            return unauthorizedResponse();
        }
    }

    private static String loginErrorCode(RuntimeException exception) {
        if (exception instanceof WeComLoginException failure) return failure.reason().name();
        if (exception instanceof org.springframework.web.server.ResponseStatusException failure
                && failure.getStatusCode().value() == 401) return "SESSION_INVALID";
        return "VERIFICATION_FAILED";
    }

    private static ResponseEntity<Void> unauthorizedResponse() {
        return noStore(ResponseEntity.status(HttpStatus.UNAUTHORIZED))
                .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                .build();
    }

    private static ResponseEntity<Void> startResponse(WeComOAuthService.Start start, boolean clearBindingVerifier) {
        ResponseEntity.BodyBuilder response = noStore(ResponseEntity.status(HttpStatus.FOUND))
                .header(HttpHeaders.LOCATION, start.authorizationUri().toString())
                .header(HttpHeaders.SET_COOKIE,
                        verifierCookie(start.browserVerifier(), start.maxAgeSeconds()).toString());
        if (clearBindingVerifier) {
            response.header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString());
        }
        return response.build();
    }

    @PostMapping("/exchange")
    public ResponseEntity<WeComOAuthModels.ExchangeResponse> exchange(
            @Valid @RequestBody WeComOAuthModels.ExchangeRequest request
    ) {
        WeComOAuthModels.ExchangeResponse session = service.exchange(request.exchangeCode());
        return noStore(ResponseEntity.ok())
                .header(HttpHeaders.SET_COOKIE,
                        FederatedSessionCookie.create(session.accessToken(), session.expiresAt()).toString())
                .body(session);
    }

    @PostMapping(value = "/browser-exchange", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> browserExchange(@RequestParam String exchangeCode) {
        WeComOAuthModels.ExchangeResponse session = service.exchange(exchangeCode);
        return noStore(ResponseEntity.status(HttpStatus.SEE_OTHER))
                .header(HttpHeaders.SET_COOKIE,
                        FederatedSessionCookie.create(session.accessToken(), session.expiresAt()).toString())
                .header(HttpHeaders.LOCATION, service.browserSessionLocation(session.returnTo()).toString())
                .build();
    }

    private static ResponseCookie verifierCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(VERIFIER_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(maxAgeSeconds).build();
    }

    private static ResponseCookie clearVerifierCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(0).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T extends ResponseEntity.HeadersBuilder<T>> T noStore(T builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer");
    }
}
