package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.*;

@RestController
@RequestMapping("/api/v1/integrations/wecom/directory-onboarding")
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComDirectoryOnboardingController {
    static final String VERIFIER_COOKIE = "__Host-wecom_directory_onboarding_verifier";

    private final WeComDirectoryOnboardingService employeeService;
    private final WeComDirectoryOnboardingAdministrationService administrationService;
    private final WeComDirectoryProperties properties;

    public WeComDirectoryOnboardingController(
            WeComDirectoryOnboardingService employeeService,
            WeComDirectoryOnboardingAdministrationService administrationService,
            WeComDirectoryProperties properties
    ) {
        this.employeeService = employeeService;
        this.administrationService = administrationService;
        this.properties = properties;
    }

    @PostMapping("/start")
    public ResponseEntity<AuthorizationResponse> start(
            @Valid @RequestBody InvitationStartRequest request
    ) {
        WeComDirectoryOnboardingService.Start start = employeeService.start(request.invitationToken());
        return authorizationResponse(start);
    }

    @PostMapping("/self-service/start")
    public ResponseEntity<AuthorizationResponse> startSelfService() {
        return authorizationResponse(employeeService.startSelfService());
    }

    private ResponseEntity<AuthorizationResponse> authorizationResponse(WeComDirectoryOnboardingService.Start start) {
        ResponseCookie verifier = ResponseCookie.from(VERIFIER_COOKIE, start.browserVerifier())
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(start.maxAgeSeconds()).build();
        return noStore(ResponseEntity.ok())
                .header(HttpHeaders.SET_COOKIE, verifier.toString())
                .body(new AuthorizationResponse(start.authorizationUri()));
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(name = VERIFIER_COOKIE, required = false) String browserVerifier
    ) {
        try {
            URI location = employeeService.callback(code, state, browserVerifier);
            return noStore(ResponseEntity.status(HttpStatus.FOUND))
                    .header(HttpHeaders.LOCATION, location.toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifier().toString())
                    .build();
        } catch (RuntimeException exception) {
            URI location = WeComDirectoryOnboardingService.oauthFailureRedirect(
                    properties.frontendBaseUrl(), exception);
            return noStore(ResponseEntity.status(HttpStatus.FOUND))
                    .header(HttpHeaders.LOCATION, location.toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifier().toString())
                    .build();
        }
    }

    @PostMapping("/exchange")
    public ResponseEntity<ExchangeResponse> exchange(@Valid @RequestBody ExchangeRequest request) {
        return noStore(ResponseEntity.ok()).body(employeeService.exchange(request.exchangeCode()));
    }

    @PostMapping("/context")
    public ResponseEntity<OnboardingContext> context(@Valid @RequestBody SessionRequest request) {
        return noStore(ResponseEntity.ok()).body(employeeService.context(request.sessionToken()));
    }

    @PostMapping("/submit")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitRequest request) {
        return noStore(ResponseEntity.ok()).body(employeeService.submit(request));
    }

    @GetMapping("/candidates")
    public CandidateList candidates(@RequestParam(required = false) String status) {
        return administrationService.list(status);
    }

    @GetMapping("/candidates/{candidateId}/assignment-options")
    public ReviewOptions assignmentOptions(@PathVariable UUID candidateId) {
        return administrationService.reviewOptions(candidateId);
    }

    @PostMapping("/invitations")
    public OpenInvitationResponse createOpenInvitation() {
        return administrationService.createOpenInvitation();
    }

    @PostMapping("/candidates/{candidateId}/approve")
    public ApprovalResponse approve(
            @PathVariable UUID candidateId,
            @Valid @RequestBody DecisionRequest request
    ) {
        return administrationService.approve(candidateId, request);
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public ApprovalResponse reject(
            @PathVariable UUID candidateId,
            @Valid @RequestBody DecisionRequest request
    ) {
        return administrationService.reject(candidateId, request);
    }

    @PostMapping("/candidates/{candidateId}/retry")
    public InvitationActionResponse retry(
            @PathVariable UUID candidateId,
            @Valid @RequestBody RetryRequest request
    ) {
        return administrationService.retryTechnicalFailure(candidateId, request);
    }

    @PostMapping("/candidates/{candidateId}/regenerate-invitation")
    public InvitationActionResponse regenerateInvitation(
            @PathVariable UUID candidateId,
            @Valid @RequestBody RetryRequest request
    ) {
        return administrationService.regenerateInvitation(candidateId, request);
    }

    @GetMapping("/directory-events")
    public DirectoryEventList directoryEvents(
            @RequestParam(required = false) String status
    ) {
        return administrationService.listDirectoryEvents(status);
    }

    @PostMapping("/directory-events/{receiptId}/retry")
    public DirectoryEventRetryResponse retryDirectoryEvent(
            @PathVariable UUID receiptId,
            @Valid @RequestBody RetryRequest request
    ) {
        return administrationService.retryDirectoryEvent(receiptId, request);
    }

    private static ResponseCookie clearVerifier() {
        return ResponseCookie.from(VERIFIER_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(0).build();
    }

    private static <T extends ResponseEntity.HeadersBuilder<T>> T noStore(T builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff");
    }
}
