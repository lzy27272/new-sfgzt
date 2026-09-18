package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.PilotPasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.*;

@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComDirectoryOnboardingService {
    private static final int MAX_SECRET_LENGTH = 512;
    private static final Pattern LOGIN_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,119}");
    private static final Pattern MOBILE_INPUT = Pattern.compile("[+0-9\\s-]+");
    private static final Pattern MAINLAND_MOBILE = Pattern.compile("1[3-9][0-9]{9}");
    private static final List<String> OPEN_STATES = List.of(
            "WAITING_PROFILE", "PENDING_APPROVAL", "CONFLICT");
    private final SecureRandom secureRandom = new SecureRandom();
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComDirectoryProperties properties;
    private final WeComDirectorySecretCodec codec;
    private final WeComApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final PilotPasswordHasher passwordHasher;
    private final TransactionTemplate isolatedTransaction;

    public WeComDirectoryOnboardingService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            WeComDirectoryProperties properties,
            WeComDirectorySecretCodec codec,
            WeComApiClient apiClient,
            ObjectMapper objectMapper,
            PilotPasswordHasher passwordHasher,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
        this.codec = codec;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.passwordHasher = passwordHasher;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public boolean handleDirectoryEvent(WeComDirectoryEvent event, UUID receiptId, UUID correlationId) {
        apply();
        if (!"change_contact".equals(event.eventType())) return false;
        if (isSuperseded(receiptId)) return false;
        if (event.isDelete() || event.isDisabled()) {
            suspendRemovedMember(event, receiptId, correlationId,
                    event.isDelete() ? "DIRECTORY_MEMBER_DELETED" : "DIRECTORY_MEMBER_DISABLED");
            return true;
        }
        if (event.isCreate()) {
            String fingerprint = codec.fingerprint(event.effectiveUserId());
            if (hasRecordedDepartureBeforeReuse(receiptId, fingerprint)) {
                // A newer member may reuse an identifier before the older
                // delete/rename receipt is processed. Suspend the older
                // identity immediately, but keep this candidate a NEW_MEMBER
                // so employee verification must surface the existing binding
                // as a CONFLICT and require an explicit atomic transfer.
                cancelOpenCandidateBefore(fingerprint, "DIRECTORY_IDENTITY_REUSED",
                        receiptId, event.occurredAt());
                suspendIdentity(event.effectiveUserId(), "DIRECTORY_IDENTITY_REUSED",
                        receiptId, correlationId);
            }
            beginCandidate(event, receiptId, correlationId, false, "NEW_MEMBER", null);
            return true;
        }
        if (event.isUpdate()) {
            if (event.newUserId() != null && !event.newUserId().equals(event.userId())) {
                ExistingBinding source = suspendIdentity(
                        event.userId(), "DIRECTORY_USER_ID_CHANGED", receiptId, correlationId);
                cancelOpenCandidateBefore(codec.fingerprint(event.userId()),
                        "DIRECTORY_USER_ID_CHANGED", receiptId, event.occurredAt());
                beginCandidate(event, receiptId, correlationId, true,
                        source == null ? "NEW_MEMBER" : "USER_ID_CHANGE", source);
                return true;
            }
            if (event.hasAssignmentSnapshot()) {
                String snapshotHash = sha256(event.assignmentSnapshotMaterial());
                ExistingBinding currentBinding = bindingsByUserId(event.effectiveUserId()).stream()
                        .filter(binding -> List.of("ACTIVE", "SUSPENDED").contains(binding.status()))
                        .findFirst().orElse(null);
                if (currentBinding != null
                        && snapshotHash.equals(currentBinding.directoryAssignmentSnapshotHash())) {
                    refreshUnchangedDirectoryProfile(
                            event, receiptId, correlationId, currentBinding, snapshotHash);
                    return true;
                }
                ExistingBinding source = suspendIdentity(
                        event.effectiveUserId(), "DIRECTORY_ASSIGNMENT_CHANGED", receiptId, correlationId);
                beginCandidate(event, receiptId, correlationId, true,
                        source == null ? "NEW_MEMBER" : "ASSIGNMENT_CHANGE", source);
            } else if ("1".equals(event.statusCode())) {
                // An unactivated member cannot complete OAuth. The first
                // activation update issues a fresh 120-minute invitation. If
                // the same provider identity already belongs to a suspended
                // account, preserve that identity and require a reviewed
                // assignment refresh instead of creating a second employee.
                ExistingBinding source = bindingsByUserId(event.effectiveUserId()).stream()
                        .filter(binding -> List.of("ACTIVE", "SUSPENDED").contains(binding.status()))
                        .findFirst().orElse(null);
                beginCandidate(event, receiptId, correlationId, true,
                        source == null ? "NEW_MEMBER" : "ASSIGNMENT_CHANGE", source);
            } else {
                updateOpenCandidateName(event, receiptId);
            }
            return true;
        }
        return false;
    }

    /**
     * Expiration is persisted rather than inferred only by the UI.  Every
     * temporary browser/OAuth credential is removed, so an expired link can
     * never be revived; an administrator must explicitly generate a new one.
     */
    @Scheduled(
            fixedDelayString = "${app.wecom.directory.candidate-expiry-delay-ms:60000}",
            initialDelayString = "${app.wecom.directory.candidate-expiry-initial-delay-ms:60000}"
    )
    @Transactional
    public int expireDueInvitations() {
        apply();
        List<ExpiredCandidate> expired = jdbc.query("""
                with due as (
                    select id
                    from wecom_person_onboarding
                    where tenant_id = :tenantId and corp_id = :corpId
                      and status = 'WAITING_PROFILE'
                      and invitation_expires_at <= now()
                    order by invitation_expires_at, id
                    for update skip locked
                    limit 500
                )
                update wecom_person_onboarding candidate
                set status = 'EXPIRED', expired_at = candidate.invitation_expires_at,
                    invitation_token_hash = null, invitation_issued_at = null,
                    invitation_expires_at = null, oauth_state_hash = null,
                    browser_verifier_hash = null, provider_code_hash = null,
                    identity_verified_at = null, exchange_code_hash = null,
                    exchange_expires_at = null, session_token_hash = null,
                    session_expires_at = null, failure_code = 'INVITATION_EXPIRED',
                    requested_password_hash = null,
                    row_version = candidate.row_version + 1
                from due
                where candidate.tenant_id = :tenantId and candidate.id = due.id
                returning candidate.id, candidate.user_id_fingerprint, candidate.open_invitation_id
                """, params(), (rs, rowNum) -> new ExpiredCandidate(
                rs.getObject("id", UUID.class), rs.getString("user_id_fingerprint"),
                rs.getObject("open_invitation_id", UUID.class) != null));
        for (ExpiredCandidate candidate : expired) {
            notifyGovernance(candidate.id(), "WECOM_ONBOARDING_INVITATION_EXPIRED",
                    candidate.sharedEntry() ? "企业微信入职填写会话已过期" : "企业微信入职邀请已过期",
                    candidate.sharedEntry()
                            ? "员工未在本次验证时限内完成申请。如共享二维码仍在有效期，可重新扫码验证；"
                              + "共享码已过期时，请管理员通过一键邀请生成新码。"
                            : "员工未在邀请时限内完成申请，原链接已失效；如需继续，请重新生成邀请。",
                    "invitation-expired");
            systemAudit("WECOM_ONBOARDING_INVITATION_EXPIRED", "wecom_person_onboarding",
                    candidate.id(), UUID.randomUUID(), Map.of(
                            "fingerprint", mask(candidate.fingerprint()),
                            "failureCode", "INVITATION_EXPIRED"));
        }
        return expired.size();
    }

    /**
     * All receipts are durable before processing starts. This makes the latest
     * directory fact a monotonic watermark even when async workers run out of
     * order. At the same provider timestamp a removal/disable wins; otherwise
     * the later received receipt wins deterministically.
     */
    private boolean isSuperseded(UUID receiptId) {
        Boolean superseded = jdbc.queryForObject("""
                select exists (
                    select 1
                    from wecom_directory_event_receipt current_receipt
                    join wecom_directory_event_receipt newer
                      on newer.tenant_id = current_receipt.tenant_id
                     and newer.corp_id = current_receipt.corp_id
                     and (
                       newer.user_id_fingerprint = current_receipt.user_id_fingerprint
                       or newer.previous_user_id_fingerprint = current_receipt.user_id_fingerprint
                     )
                     and newer.id <> current_receipt.id
                    where current_receipt.tenant_id = :tenantId
                      and current_receipt.id = :receiptId
                      and (
                        newer.occurred_at > current_receipt.occurred_at
                        or (newer.occurred_at = current_receipt.occurred_at
                            and newer.event_priority > current_receipt.event_priority)
                        or (newer.occurred_at = current_receipt.occurred_at
                            and newer.event_priority = current_receipt.event_priority
                            and newer.received_at > current_receipt.received_at)
                        or (newer.occurred_at = current_receipt.occurred_at
                            and newer.event_priority = current_receipt.event_priority
                            and newer.received_at = current_receipt.received_at
                            and newer.id::text > current_receipt.id::text)
                      )
                )
                """, params().addValue("receiptId", receiptId), Boolean.class);
        return Boolean.TRUE.equals(superseded);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyDeadLetter(UUID receiptId, UUID correlationId) {
        apply();
        notifyGovernance(receiptId, "WECOM_DIRECTORY_DEAD_LETTER", "企业微信人员同步异常",
                "一项人员变更连续重试失败，请在中台查看原因并重试。", "dead-letter");
        systemAudit("WECOM_DIRECTORY_EVENT_DEAD_LETTER", "wecom_directory_event_receipt", receiptId,
                correlationId, Map.of("failureCode", "DIRECTORY_EVENT_RETRY_EXHAUSTED"));
    }

    @Transactional
    public Start start(String invitationToken) {
        apply();
        String tokenHash = sha256(boundedSecret(invitationToken));
        String state = randomSecret();
        String verifier = randomSecret();
        List<ReusableInvitation> reusable = jdbc.query("""
                select id, expires_at, created_by
                from wecom_open_onboarding_invitation
                where tenant_id = :tenantId and corp_id = :corpId
                  and token_hash = :tokenHash and expires_at > now()
                for share
                """, params().addValue("tokenHash", tokenHash), (rs, rowNum) ->
                new ReusableInvitation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class)));
        if (reusable.size() > 1) throw new IllegalStateException("入职邀请数据不唯一");

        StartCandidate candidate;
        if (reusable.size() == 1) {
            ReusableInvitation invitation = reusable.getFirst();
            UUID candidateId = UUID.randomUUID();
            OffsetDateTime startedAt = OffsetDateTime.now();
            // A week-long shared entry must not create week-long employee credentials.
            OffsetDateTime candidateExpiresAt = startedAt.plus(properties.invitationTtl());
            if (invitation.expiresAt().isBefore(candidateExpiresAt)) {
                candidateExpiresAt = invitation.expiresAt();
            }
            String placeholderFingerprint = sha256(
                    "open-invitation:" + invitation.id() + ":" + candidateId);
            jdbc.update("""
                    insert into wecom_person_onboarding
                        (id, tenant_id, corp_id, user_id_fingerprint, display_name,
                         onboarding_kind, directory_status, status,
                         invitation_token_hash, invitation_issued_at, invitation_expires_at,
                         oauth_state_hash, browser_verifier_hash,
                         source_event_hash, last_event_at,
                         invitation_source, invitation_created_by, open_invitation_id)
                    values
                        (:id, :tenantId, :corpId, :fingerprint, '待员工填写',
                         'NEW_MEMBER', 'ACTIVE', 'WAITING_PROFILE',
                         :candidateTokenHash, :startedAt, :expiresAt,
                         :stateHash, :verifierHash,
                         :sourceHash, :startedAt,
                         'MANUAL_LINK', :createdBy, :openInvitationId)
                    """, params().addValue("id", candidateId)
                    .addValue("fingerprint", placeholderFingerprint)
                    .addValue("candidateTokenHash", sha256(randomSecret()))
                    .addValue("startedAt", startedAt)
                    .addValue("expiresAt", candidateExpiresAt)
                    .addValue("stateHash", sha256(state))
                    .addValue("verifierHash", sha256(verifier))
                    .addValue("sourceHash", sha256("open-invitation-candidate:" + candidateId))
                    .addValue("createdBy", invitation.createdBy())
                    .addValue("openInvitationId", invitation.id()));
            candidate = new StartCandidate(candidateId, candidateExpiresAt);
        } else {
            List<StartCandidate> rows = jdbc.query("""
                    select id, invitation_expires_at from wecom_person_onboarding
                    where tenant_id = :tenantId and corp_id = :corpId
                      and invitation_token_hash = :tokenHash
                      and status = 'WAITING_PROFILE' and directory_status = 'ACTIVE'
                      and invitation_expires_at > now()
                    for update
                    """, params().addValue("tokenHash", tokenHash), (rs, rowNum) -> new StartCandidate(
                    rs.getObject("id", UUID.class), rs.getObject("invitation_expires_at", OffsetDateTime.class)));
            if (rows.size() != 1) throw new IllegalArgumentException(
                    "入职邀请已过期或不可用。请联系管理员在“企微绑定与异常 → 一键邀请”重新生成二维码，再使用新码扫码。");
            candidate = rows.getFirst();
            jdbc.update("""
                    update wecom_person_onboarding
                    set oauth_state_hash = :stateHash, browser_verifier_hash = :verifierHash,
                        provider_code_hash = null, failure_code = null, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, params().addValue("id", candidate.id()).addValue("stateHash", sha256(state))
                    .addValue("verifierHash", sha256(verifier)));
        }
        URI authorizationUri = UriComponentsBuilder.fromUriString(
                        "https://open.weixin.qq.com/connect/oauth2/authorize")
                .queryParam("appid", properties.corpId())
                .queryParam("redirect_uri", properties.oauthCallbackUrl().toString())
                .queryParam("response_type", "code")
                .queryParam("scope", "snsapi_base")
                .queryParam("agentid", properties.agentId())
                .queryParam("state", state)
                .fragment("wechat_redirect").build().encode().toUri();
        long seconds = Math.max(1, Math.min(Duration.between(
                OffsetDateTime.now(), candidate.expiresAt()).toSeconds(), 600));
        return new Start(authorizationUri, verifier, seconds);
    }

    public URI callback(String providerCode, String state, String browserVerifier) {
        String code;
        String rawState;
        String verifier;
        try {
            code = boundedSecret(providerCode);
            rawState = boundedSecret(state);
            verifier = boundedSecret(browserVerifier);
        } catch (RuntimeException exception) {
            throw new OAuthCallbackFailure("OAUTH_SESSION_INVALID", exception);
        }
        UUID candidateId = isolatedTransaction.execute(status ->
                claimOAuth(sha256(rawState), sha256(verifier), sha256(code)));
        if (candidateId == null) throw new OAuthCallbackFailure("OAUTH_SESSION_INVALID");
        String userId;
        try {
            userId = boundedSecret(apiClient.exchangeOAuthCode(code));
        } catch (RuntimeException exception) {
            String failureCode = failureCode(exception);
            isolatedTransaction.executeWithoutResult(status -> failOAuth(candidateId, failureCode));
            throw new OAuthCallbackFailure(
                    "WECOM_OAUTH_TIMEOUT".equals(failureCode)
                            ? "OAUTH_PROVIDER_UNAVAILABLE" : "OAUTH_VERIFICATION_FAILED",
                    exception);
        }
        URI result = isolatedTransaction.execute(status -> completeOAuth(candidateId, userId));
        if (result == null) {
            throw new OAuthCallbackFailure("OAUTH_IDENTITY_MISMATCH");
        }
        return result;
    }

    static URI oauthFailureRedirect(URI frontendBaseUrl, RuntimeException exception) {
        String code = exception instanceof OAuthCallbackFailure failure
                ? failure.resultCode() : "OAUTH_VERIFICATION_FAILED";
        if (!List.of(
                "OAUTH_SESSION_INVALID",
                "OAUTH_IDENTITY_MISMATCH",
                "OAUTH_PROVIDER_UNAVAILABLE",
                "OAUTH_VERIFICATION_FAILED"
        ).contains(code)) {
            code = "OAUTH_VERIFICATION_FAILED";
        }
        String base = frontendBaseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + "/#/wecom-onboarding?error_code=" + code);
    }

    @Transactional
    public ExchangeResponse exchange(String exchangeCode) {
        apply();
        String raw = boundedSecret(exchangeCode);
        List<SessionCandidate> rows = jdbc.query("""
                select id, status from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and exchange_code_hash = :exchangeHash and exchange_expires_at > now()
                  and identity_verified_at is not null and status = 'WAITING_PROFILE'
                for update
                """, params().addValue("exchangeHash", sha256(raw)), (rs, rowNum) -> new SessionCandidate(
                rs.getObject("id", UUID.class), rs.getString("status")));
        if (rows.size() != 1) throw new IllegalArgumentException("入职交换码无效或已使用");
        SessionCandidate candidate = rows.getFirst();
        String sessionToken = randomSecret();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.sessionTtl());
        jdbc.update("""
                update wecom_person_onboarding
                set exchange_code_hash = null, exchange_expires_at = null,
                    session_token_hash = :sessionHash, session_expires_at = :sessionExpiresAt,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", candidate.id()).addValue("sessionHash", sha256(sessionToken))
                .addValue("sessionExpiresAt", expiresAt));
        return new ExchangeResponse(sessionToken, expiresAt, candidate.status());
    }

    @Transactional(readOnly = true)
    public OnboardingContext context(String sessionToken) {
        apply();
        CandidateSession candidate = requireSession(sessionToken, false);
        return new OnboardingContext(candidate.id(), candidate.status(), candidate.displayName(),
                candidate.requestedLoginName(), candidate.sourceAccountId() == null,
                candidate.invitationSource(),
                loadOptions(), candidate.rowVersion());
    }

    @Transactional
    public SubmitResponse submit(SubmitRequest request) {
        apply();
        CandidateSession candidate = requireSession(request.sessionToken(), true);
        if (candidate.rowVersion() != request.expectedVersion()) {
            throw new IllegalArgumentException("申请已变化，请刷新后重试");
        }
        if (request.positionId() == null) {
            requirePendingAssignmentOrganization(request.orgUnitId());
        } else {
            requireSelectable(request.orgUnitId(), request.positionId());
        }
        String requestedDisplayName = null;
        String requestedMobile = null;
        String requestedLoginName = null;
        String requestedPasswordHash = null;
        if (candidate.sourceAccountId() == null) {
            requestedDisplayName = requireDisplayName(request.displayName());
            requestedMobile = requireMobile(request.mobile());
            requestedLoginName = requireLoginName(request.loginName());
            if (!MessageDigest.isEqual(
                    safePassword(request.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    safePassword(request.passwordConfirmation()).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("两次输入的密码不一致");
            }
            passwordHasher.requirePassword(request.password());
            requireAvailableMobile(requestedMobile, candidate.id());
            requireAvailableLogin(requestedLoginName, candidate.id());
            requestedPasswordHash = passwordHasher.hash(request.password());
        }
        List<UUID> conflictingAccounts = jdbc.queryForList("""
                select account_id
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId
                  and user_id_fingerprint = :fingerprint
                  and status in ('ACTIVE','SUSPENDED')
                  and (cast(:sourceAccountId as uuid) is null
                       or account_id <> cast(:sourceAccountId as uuid))
                order by account_id
                for update
                """, params().addValue("fingerprint", candidate.fingerprint())
                .addValue("sourceAccountId", candidate.sourceAccountId()), UUID.class);
        UUID conflictingAccountId = conflictingAccounts.isEmpty() ? null : conflictingAccounts.getFirst();
        String nextStatus = conflictingAccountId == null ? "PENDING_APPROVAL" : "CONFLICT";
        int updated;
        try {
            updated = jdbc.update("""
                    update wecom_person_onboarding
                    set status = :nextStatus, requested_org_unit_id = :orgUnitId,
                        requested_position_id = :positionId, profile_submitted_at = now(),
                        requested_display_name = :requestedDisplayName,
                        requested_mobile = :requestedMobile,
                        requested_login_name = :requestedLoginName,
                        requested_password_hash = :requestedPasswordHash,
                        registration_completed_at = case when :requestedLoginName is null
                            then null else now() end,
                        failure_code = :failureCode, conflicting_account_id = :conflictingAccountId,
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id and row_version = :version
                      and status in ('WAITING_PROFILE','PENDING_APPROVAL')
                      and directory_status = 'ACTIVE' and identity_verified_at is not null
                    """, params().addValue("id", candidate.id()).addValue("version", request.expectedVersion())
                    .addValue("orgUnitId", request.orgUnitId()).addValue("positionId", request.positionId())
                    .addValue("requestedDisplayName", requestedDisplayName)
                    .addValue("requestedMobile", requestedMobile)
                    .addValue("requestedLoginName", requestedLoginName)
                    .addValue("requestedPasswordHash", requestedPasswordHash)
                    .addValue("nextStatus", nextStatus).addValue("conflictingAccountId", conflictingAccountId)
                    .addValue("failureCode", conflictingAccountId == null ? null : "USER_ID_ALREADY_BOUND"));
        } catch (DuplicateKeyException exception) {
            if (isMobileConflict(exception)) {
                throw new IllegalArgumentException("手机号已注册");
            }
            throw new IllegalArgumentException("登录账号已被使用，请更换后重试");
        }
        if (updated != 1) throw new IllegalArgumentException("申请已变化，请刷新后重试");
        if (conflictingAccountId == null) {
            notifyGovernance(candidate.id(), "WECOM_ONBOARDING_PENDING", "企业微信新员工待审核",
                    "员工已完成企业微信身份验证并提交门店岗位申请。", "pending-approval");
        } else {
            notifyGovernance(candidate.id(), "WECOM_ONBOARDING_CONFLICT", "企业微信身份冲突待处理",
                    "员工已验证并提交申请，但该企微身份已绑定其他账号，需二次确认。",
                    "profile-conflict");
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("orgUnitId", request.orgUnitId());
        audit.put("positionId", request.positionId());
        audit.put("positionPendingAssignment", request.positionId() == null);
        audit.put("fingerprint", mask(candidate.fingerprint()));
        audit.put("accountRegistration", candidate.sourceAccountId() == null);
        audit.put("status", nextStatus);
        systemAudit("WECOM_ONBOARDING_PROFILE_SUBMITTED", "wecom_person_onboarding", candidate.id(),
                UUID.randomUUID(), audit);
        return new SubmitResponse(candidate.id(), nextStatus,
                request.expectedVersion() + 1, conflictingAccountId == null
                ? "申请已提交，等待管理员审核"
                : "申请已提交，身份冲突将由管理员确认处理");
    }

    private void beginCandidate(
            WeComDirectoryEvent event, UUID receiptId, UUID correlationId, boolean replaceInvitation,
            String requestedKind, ExistingBinding sourceBinding
    ) {
        String userId = event.effectiveUserId();
        String fingerprint = codec.fingerprint(userId);
        lockOnboardingIdentity(fingerprint);
        List<ExistingBinding> bindings = bindingsByUserId(userId);
        ExistingBinding activeBinding = bindings.stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .findFirst().orElse(null);
        boolean sameSourceIdentity = activeBinding != null && sourceBinding != null
                && (activeBinding.id().equals(sourceBinding.id())
                || activeBinding.accountId().equals(sourceBinding.accountId()));
        boolean recordedDeparture = activeBinding != null && sourceBinding == null
                && hasRecordedDepartureBeforeReuse(receiptId, fingerprint);
        if (activeBinding != null && ((sourceBinding == null && !recordedDeparture)
                || sameSourceIdentity)) {
            jdbc.update("""
                    update wecom_user_binding set last_verified_at = now()
                    where tenant_id = :tenantId and corp_id = :corpId and wecom_user_id = :userId
                      and status = 'ACTIVE'
                    """, params().addValue("userId", userId));
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        String onboardingKind = sourceBinding == null ? "NEW_MEMBER" : requestedKind;
        String sourceHash = sha256(receiptId + "|" + fingerprint + "|" + event.occurredAt());
        List<OpenCandidate> open = jdbc.query("""
                select id, status, directory_status, last_event_at, invitation_expires_at
                from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and user_id_fingerprint = :fingerprint
                  and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                for update
                """, params().addValue("fingerprint", fingerprint), (rs, rowNum) -> new OpenCandidate(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("directory_status"),
                rs.getObject("last_event_at", OffsetDateTime.class),
                rs.getObject("invitation_expires_at", OffsetDateTime.class)));
        if (!open.isEmpty() && open.getFirst().lastEventAt().isAfter(event.occurredAt())) return;
        String token = null;
        UUID candidateId;
        if (open.isEmpty()) {
            candidateId = UUID.randomUUID();
            token = randomSecret();
            jdbc.update("""
                    insert into wecom_person_onboarding
                        (id, tenant_id, corp_id, user_id_fingerprint, user_id_ciphertext,
                         display_name, onboarding_kind, source_binding_id, source_account_id,
                         directory_status, status, invitation_token_hash,
                         invitation_issued_at, invitation_expires_at, source_event_hash,
                         directory_assignment_snapshot_hash,
                         last_event_at, last_event_receipt_id)
                    values
                        (:id, :tenantId, :corpId, :fingerprint, :ciphertext,
                         :displayName, :onboardingKind, :sourceBindingId, :sourceAccountId,
                         :directoryStatus, 'WAITING_PROFILE', :tokenHash,
                         :issuedAt, :expiresAt, :sourceHash, :directorySnapshotHash,
                         :lastEventAt, :receiptId)
                    """, params().addValue("id", candidateId).addValue("fingerprint", fingerprint)
                    .addValue("ciphertext", codec.encrypt(userId)).addValue("displayName", event.displayName())
                    .addValue("onboardingKind", onboardingKind)
                    .addValue("sourceBindingId", sourceBinding == null ? null : sourceBinding.id())
                    .addValue("sourceAccountId", sourceBinding == null ? null : sourceBinding.accountId())
                    .addValue("directoryStatus", directoryStatus(event)).addValue("tokenHash", sha256(token))
                    .addValue("issuedAt", now).addValue("expiresAt", now.plus(properties.invitationTtl()))
                    .addValue("sourceHash", sourceHash).addValue("lastEventAt", event.occurredAt())
                    .addValue("directorySnapshotHash", event.hasAssignmentSnapshot()
                            ? sha256(event.assignmentSnapshotMaterial()) : null)
                    .addValue("receiptId", receiptId));
        } else {
            OpenCandidate current = open.getFirst();
            candidateId = current.id();
            boolean issue = replaceInvitation && "WAITING_PROFILE".equals(current.status())
                    && (!"ACTIVE".equals(current.directoryStatus())
                    || current.invitationExpiresAt() == null
                    || !current.invitationExpiresAt().isAfter(now));
            if (issue) token = randomSecret();
            jdbc.update("""
                    update wecom_person_onboarding
                    set user_id_ciphertext = :ciphertext, display_name = :displayName,
                        onboarding_kind = case when :sourceBindingId is null
                            then onboarding_kind else :onboardingKind end,
                        source_binding_id = coalesce(:sourceBindingId, source_binding_id),
                        source_account_id = coalesce(:sourceAccountId, source_account_id),
                        directory_status = :directoryStatus, source_event_hash = :sourceHash,
                        directory_assignment_snapshot_hash = coalesce(
                            :directorySnapshotHash, directory_assignment_snapshot_hash),
                        last_event_at = :lastEventAt, last_event_receipt_id = :receiptId,
                        invitation_token_hash = case when :issue then :tokenHash else invitation_token_hash end,
                        invitation_issued_at = case when :issue then :issuedAt else invitation_issued_at end,
                        invitation_expires_at = case when :issue then :expiresAt else invitation_expires_at end,
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, params().addValue("id", candidateId).addValue("ciphertext", codec.encrypt(userId))
                    .addValue("displayName", event.displayName()).addValue("directoryStatus", directoryStatus(event))
                    .addValue("onboardingKind", onboardingKind)
                    .addValue("sourceBindingId", sourceBinding == null ? null : sourceBinding.id())
                    .addValue("sourceAccountId", sourceBinding == null ? null : sourceBinding.accountId())
                    .addValue("sourceHash", sourceHash).addValue("lastEventAt", event.occurredAt())
                    .addValue("directorySnapshotHash", event.hasAssignmentSnapshot()
                            ? sha256(event.assignmentSnapshotMaterial()) : null)
                    .addValue("issue", issue).addValue("tokenHash", token == null ? null : sha256(token))
                    .addValue("issuedAt", now).addValue("expiresAt", now.plus(properties.invitationTtl()))
                    .addValue("receiptId", receiptId));
        }
        if (token != null && "ACTIVE".equals(directoryStatus(event))) {
            deliverInvitationAfterCommit(candidateId, userId, token);
        }
        notifyGovernance(candidateId, "WECOM_ONBOARDING_STARTED", "企业微信新员工待完善",
                "系统已检测到新成员，等待员工选择门店和岗位。", "waiting-profile");
        systemAudit("WECOM_ONBOARDING_STARTED", "wecom_person_onboarding", candidateId,
                correlationId, Map.of("fingerprint", mask(fingerprint), "receiptId", receiptId));
    }

    /**
     * A create event can legitimately reuse an identifier that an earlier
     * rename event is moving away from.  The receipt itself is the durable
     * ordering fact: do not treat the still-active old binding as an
     * idempotent create in that window, otherwise both events can complete
     * without ever creating the replacement member's review candidate.
     */
    private boolean hasRecordedDepartureBeforeReuse(UUID receiptId, String fingerprint) {
        Boolean found = jdbc.queryForObject("""
                select exists (
                    select 1
                    from wecom_directory_event_receipt current_receipt
                    join wecom_directory_event_receipt departure_receipt
                      on departure_receipt.tenant_id = current_receipt.tenant_id
                     and departure_receipt.corp_id = current_receipt.corp_id
                     and (
                       (departure_receipt.change_type = 'update_user'
                        and departure_receipt.previous_user_id_fingerprint = :fingerprint
                        and departure_receipt.user_id_fingerprint <> :fingerprint)
                       or
                       (departure_receipt.change_type = 'delete_user'
                        and departure_receipt.user_id_fingerprint = :fingerprint)
                       or
                       (departure_receipt.event_priority = 90
                        and departure_receipt.user_id_fingerprint = :fingerprint)
                     )
                     and (
                       departure_receipt.occurred_at < current_receipt.occurred_at
                       or (departure_receipt.occurred_at = current_receipt.occurred_at
                           and departure_receipt.received_at <= current_receipt.received_at)
                     )
                    where current_receipt.tenant_id = :tenantId
                      and current_receipt.corp_id = :corpId
                      and current_receipt.id = :receiptId
                )
                """, params().addValue("receiptId", receiptId)
                .addValue("fingerprint", fingerprint), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    void deliverInvitationAfterCommit(UUID candidateId, String userId, String token) {
        Runnable delivery = () -> deliverInvitation(candidateId, userId, token);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { delivery.run(); }
            });
        } else {
            delivery.run();
        }
    }

    private void deliverInvitation(UUID candidateId, String userId, String token) {
        URI link = invitationUri(properties.frontendBaseUrl(), token);
        try {
            apiClient.sendApplicationTaskLink(userId, "完成中台人员绑定",
                    "请在120分钟内选择本人门店和岗位，提交后由管理员审核。", link);
        } catch (RuntimeException exception) {
            isolatedTransaction.executeWithoutResult(status -> {
                apply();
                jdbc.update("""
                        update wecom_person_onboarding
                        set failure_code = 'INVITATION_DELIVERY_FAILED', row_version = row_version + 1
                        where tenant_id = :tenantId and id = :id and status = 'WAITING_PROFILE'
                        """, params().addValue("id", candidateId));
            });
        }
    }

    private void updateOpenCandidateName(WeComDirectoryEvent event, UUID receiptId) {
        jdbc.update("""
                update wecom_person_onboarding
                set display_name = :displayName, last_event_at = :lastEventAt,
                    source_event_hash = :sourceHash, last_event_receipt_id = :receiptId,
                    row_version = row_version + 1
                where tenant_id = :tenantId and corp_id = :corpId
                  and user_id_fingerprint = :fingerprint
                  and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                  and last_event_at <= :lastEventAt
                """, params().addValue("displayName", event.displayName())
                .addValue("lastEventAt", event.occurredAt())
                .addValue("receiptId", receiptId)
                .addValue("sourceHash", sha256(event.occurredAt() + "|profile"))
                .addValue("fingerprint", codec.fingerprint(event.effectiveUserId())));
    }

    private void suspendRemovedMember(
            WeComDirectoryEvent event, UUID receiptId, UUID correlationId, String reason
    ) {
        String fingerprint = codec.fingerprint(event.effectiveUserId());
        cancelOpenCandidateBefore(fingerprint, reason, receiptId, event.occurredAt());
        suspendIdentity(event.effectiveUserId(), reason, receiptId, correlationId);
    }

    private ExistingBinding suspendIdentity(
            String userId, String reason, UUID receiptId, UUID correlationId
    ) {
        List<ExistingBinding> bindings = bindingsForDirectoryEvent(userId, receiptId);
        // A transferred binding deliberately keeps the provider fingerprint as
        // immutable lineage evidence.  Never let that revoked history win over
        // the current binding for a later directory fact, regardless of SQL
        // row order.  The transferred row is a fallback only for events where
        // no eligible current binding exists (for example, an old rename that
        // must still be correlated to its original account).
        ExistingBinding source = selectDirectorySource(bindings);
        for (ExistingBinding binding : bindings) {
            if (!binding.eligibleForEvent()) continue;
            if ("REVOKED".equals(binding.status())) continue;
            int updated = jdbc.update("""
                    update wecom_user_binding
                    set status = 'SUSPENDED', status_reason = :reason, updated_by = null,
                        identity_event_receipt_id = :receiptId,
                        row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and id = :id and status = 'ACTIVE'
                    """, params().addValue("id", binding.id()).addValue("reason", reason)
                    .addValue("receiptId", receiptId));
            if (updated != 1) continue;
            notifyGovernance(binding.id(), "WECOM_BINDING_DIRECTORY_SUSPENDED",
                    "企业微信绑定已自动暂停",
                    "检测到人员停用、删除或身份变更，绑定已立即暂停。", reason);
            systemAudit("WECOM_BINDING_DIRECTORY_SUSPENDED", "wecom_user_binding", binding.id(),
                    correlationId, Map.of("accountId", binding.accountId(), "reason", reason,
                            "fingerprint", mask(codec.fingerprint(userId)), "receiptId", receiptId));
        }
        return source;
    }

    private static ExistingBinding selectDirectorySource(List<ExistingBinding> bindings) {
        List<ExistingBinding> eligible = bindings.stream()
                .filter(ExistingBinding::eligibleForEvent)
                .toList();
        if (eligible.isEmpty()) return null;

        int nearestDepth = eligible.stream()
                .mapToInt(ExistingBinding::lineageDepth)
                .min()
                .orElseThrow();
        List<ExistingBinding> nearest = eligible.stream()
                .filter(binding -> binding.lineageDepth() == nearestDepth)
                .toList();
        ExistingBinding current = nearest.stream()
                .filter(binding -> !"REVOKED".equals(binding.status()))
                .findFirst()
                .orElse(null);
        if (current != null) return current;

        // A current/manual revocation is a terminal identity fact.  It must
        // block an older transferred row from becoming the source of a later
        // directory update; the employee must complete a fresh onboarding or
        // an administrator must explicitly initiate re-binding.
        boolean terminalRevocation = nearest.stream()
                .anyMatch(binding -> "REVOKED".equals(binding.status())
                        && !"IDENTITY_TRANSFERRED_BY_ONBOARDING".equals(binding.statusReason()));
        if (terminalRevocation) return null;

        return nearest.stream()
                .filter(binding -> "REVOKED".equals(binding.status())
                        && "IDENTITY_TRANSFERRED_BY_ONBOARDING".equals(binding.statusReason()))
                .findFirst()
                .orElse(null);
    }

    /**
     * A rename only invalidates an application sourced from the old identity
     * at or before that rename.  A later create can reuse the old provider ID
     * and may be processed first; its candidate must survive the older rename.
     */
    private void cancelOpenCandidateBefore(
            String fingerprint,
            String reason,
            UUID receiptId,
            OffsetDateTime occurredAt
    ) {
        jdbc.update("""
                update wecom_person_onboarding candidate
                set status = 'CANCELLED', directory_status = case
                        when :reason = 'DIRECTORY_MEMBER_DELETED' then 'DELETED' else 'DISABLED' end,
                    user_id_ciphertext = null, invitation_token_hash = null,
                    invitation_issued_at = null, invitation_expires_at = null,
                    oauth_state_hash = null, browser_verifier_hash = null,
                    provider_code_hash = null, exchange_code_hash = null,
                    exchange_expires_at = null, session_token_hash = null,
                    session_expires_at = null, identity_verified_at = null,
                    requested_org_unit_id = null, requested_position_id = null,
                    profile_submitted_at = null, conflicting_account_id = null,
                    requested_display_name = null, requested_mobile = null,
                    requested_login_name = null,
                    requested_password_hash = null, registration_completed_at = null,
                    failure_code = :reason,
                    decision_reason = '企业微信人员身份已变更', row_version = row_version + 1
                where candidate.tenant_id = :tenantId and candidate.corp_id = :corpId
                  and candidate.user_id_fingerprint = :fingerprint
                  and candidate.status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                  and (
                    (candidate.last_event_receipt_id is null
                     and candidate.last_event_at < :occurredAt)
                    or exists (
                      select 1
                      from wecom_directory_event_receipt current_receipt
                      join wecom_directory_event_receipt candidate_receipt
                        on candidate_receipt.tenant_id = current_receipt.tenant_id
                       and candidate_receipt.corp_id = current_receipt.corp_id
                       and candidate_receipt.id = candidate.last_event_receipt_id
                      where current_receipt.tenant_id = candidate.tenant_id
                        and current_receipt.corp_id = candidate.corp_id
                        and current_receipt.id = :receiptId
                        and (
                          candidate_receipt.occurred_at < current_receipt.occurred_at
                          or (candidate_receipt.occurred_at = current_receipt.occurred_at
                              and candidate_receipt.received_at < current_receipt.received_at)
                          or (candidate_receipt.occurred_at = current_receipt.occurred_at
                              and candidate_receipt.received_at = current_receipt.received_at
                              and candidate_receipt.id::text <= current_receipt.id::text)
                        )
                    )
                  )
                """, params().addValue("fingerprint", fingerprint)
                .addValue("reason", reason).addValue("receiptId", receiptId)
                .addValue("occurredAt", occurredAt));
    }

    UUID claimOAuth(String stateHash, String verifierHash, String providerCodeHash) {
        apply();
        List<UUID> ids = jdbc.query("""
                select id from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and oauth_state_hash = :stateHash and browser_verifier_hash = :verifierHash
                  and provider_code_hash is null and status = 'WAITING_PROFILE'
                  and directory_status = 'ACTIVE' and invitation_expires_at > now()
                for update
                """, params().addValue("stateHash", stateHash).addValue("verifierHash", verifierHash),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.size() != 1) throw new IllegalArgumentException("企业微信入职授权状态无效或已过期");
        UUID id = ids.getFirst();
        jdbc.update("""
                update wecom_person_onboarding
                set provider_code_hash = :providerCodeHash, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", id).addValue("providerCodeHash", providerCodeHash));
        return id;
    }

    URI completeOAuth(UUID candidateId, String observedUserId) {
        apply();
        String userId = boundedSecret(observedUserId);
        String observedFingerprint = codec.fingerprint(userId);
        List<OAuthCandidate> rows = jdbc.query("""
                select id, user_id_fingerprint, source_account_id, invitation_source
                from wecom_person_onboarding
                where tenant_id = :tenantId and id = :id and status = 'WAITING_PROFILE'
                  and directory_status = 'ACTIVE' and invitation_expires_at > now()
                for update
                """, params().addValue("id", candidateId), (rs, rowNum) -> new OAuthCandidate(
                rs.getObject("id", UUID.class), rs.getString("user_id_fingerprint"),
                rs.getObject("source_account_id", UUID.class),
                rs.getString("invitation_source")));
        if (rows.size() != 1) throw new IllegalArgumentException("入职申请状态已变化");
        OAuthCandidate candidate = rows.getFirst();
        boolean manualLink = "MANUAL_LINK".equals(candidate.invitationSource());
        if (manualLink) {
            jdbc.queryForObject("""
                    select pg_advisory_xact_lock(
                        hashtext(cast(:tenantId as text)),
                        hashtext(:corpId || ':' || :fingerprint))
                    """, params().addValue("fingerprint", observedFingerprint), Object.class);
            Integer duplicate = jdbc.queryForObject("""
                    select count(*) from wecom_person_onboarding
                    where tenant_id = :tenantId and corp_id = :corpId
                      and id <> :id and user_id_fingerprint = :fingerprint
                      and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                    """, params().addValue("id", candidateId)
                    .addValue("fingerprint", observedFingerprint), Integer.class);
            if (duplicate != null && duplicate > 0) {
                jdbc.update("""
                        update wecom_person_onboarding
                        set status = 'CANCELLED', invitation_token_hash = null,
                            invitation_issued_at = null, invitation_expires_at = null,
                            oauth_state_hash = null, browser_verifier_hash = null,
                            provider_code_hash = null, failure_code = 'IDENTITY_ALREADY_PENDING',
                            decision_reason = '该企业微信身份已有待处理入职申请',
                            row_version = row_version + 1
                        where tenant_id = :tenantId and id = :id
                        """, params().addValue("id", candidateId));
                return null;
            }
        } else if (!MessageDigest.isEqual(
                candidate.fingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                observedFingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            jdbc.update("""
                    update wecom_person_onboarding
                    set status = 'CANCELLED', user_id_ciphertext = null,
                        invitation_token_hash = null, invitation_issued_at = null,
                        invitation_expires_at = null, oauth_state_hash = null,
                        browser_verifier_hash = null, provider_code_hash = null,
                        identity_verified_at = null, exchange_code_hash = null,
                        exchange_expires_at = null, session_token_hash = null,
                        session_expires_at = null, requested_org_unit_id = null,
                        requested_position_id = null, profile_submitted_at = null,
                        requested_display_name = null, requested_mobile = null,
                        requested_login_name = null,
                        requested_password_hash = null, registration_completed_at = null,
                        conflicting_account_id = null,
                        failure_code = 'OAUTH_IDENTITY_MISMATCH',
                        decision_reason = '企业微信身份与入职邀请不匹配',
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, params().addValue("id", candidateId));
            if (candidate.sourceAccountId() != null) {
                notifyAccount(candidate.sourceAccountId(), candidateId,
                        "WECOM_ONBOARDING_IDENTITY_REJECTED", "企业微信身份验证未通过",
                        "验证到的企微身份与邀请不一致，本次链接已终止，如需继续请联系管理员重新发起。",
                        "oauth-identity-rejected");
            }
            notifyGovernance(candidateId, "WECOM_ONBOARDING_IDENTITY_REJECTED",
                    "企业微信身份验证已拒绝",
                    "OAuth身份与入职邀请不一致，旧链接已终止；需管理员重新发起。",
                    "oauth-identity-rejected");
            systemAudit("WECOM_ONBOARDING_IDENTITY_REJECTED", "wecom_person_onboarding",
                    candidateId, UUID.randomUUID(), Map.of(
                            "fingerprint", mask(candidate.fingerprint()),
                            "failureCode", "OAUTH_IDENTITY_MISMATCH",
                            "terminal", true));
            return null;
        }
        String exchangeCode = randomSecret();
        jdbc.update("""
                update wecom_person_onboarding
                set oauth_state_hash = null, browser_verifier_hash = null,
                    provider_code_hash = null, identity_verified_at = now(),
                    user_id_fingerprint = case when :manualLink
                        then :observedFingerprint else user_id_fingerprint end,
                    user_id_ciphertext = case when :manualLink
                        then :userIdCiphertext else user_id_ciphertext end,
                    exchange_code_hash = :exchangeHash, exchange_expires_at = :exchangeExpiresAt,
                    failure_code = null, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", candidateId).addValue("exchangeHash", sha256(exchangeCode))
                .addValue("exchangeExpiresAt", OffsetDateTime.now().plus(properties.exchangeTtl()))
                .addValue("manualLink", manualLink).addValue("observedFingerprint", observedFingerprint)
                .addValue("userIdCiphertext", manualLink ? codec.encrypt(userId) : null));
        String base = properties.frontendBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/#/wecom-onboarding?exchange_code=" + exchangeCode);
    }

    void failOAuth(UUID candidateId, String failureCode) {
        apply();
        jdbc.update("""
                update wecom_person_onboarding
                set oauth_state_hash = null, browser_verifier_hash = null,
                    provider_code_hash = null, failure_code = :failureCode,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and status = 'WAITING_PROFILE'
                """, params().addValue("id", candidateId).addValue("failureCode", failureCode));
    }

    private CandidateSession requireSession(String rawToken, boolean lock) {
        String suffix = lock ? " for update" : "";
        List<CandidateSession> rows = jdbc.query("""
                select id, status, coalesce(requested_display_name, display_name) as display_name,
                       requested_login_name, user_id_fingerprint, source_account_id,
                       invitation_source, row_version
                from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and session_token_hash = :sessionHash and session_expires_at > now()
                  and identity_verified_at is not null and directory_status = 'ACTIVE'
                  and status in ('WAITING_PROFILE','PENDING_APPROVAL')
                """ + suffix, params().addValue("sessionHash", sha256(boundedSecret(rawToken))),
                (rs, rowNum) -> new CandidateSession(rs.getObject("id", UUID.class),
                        rs.getString("status"), rs.getString("display_name"),
                        rs.getString("requested_login_name"),
                        rs.getString("user_id_fingerprint"),
                        rs.getObject("source_account_id", UUID.class),
                        rs.getString("invitation_source"), rs.getLong("row_version")));
        if (rows.size() != 1) throw new IllegalArgumentException("入职会话已过期或不可继续");
        return rows.getFirst();
    }

    private List<HotelOption> loadOptions() {
        Map<UUID, HotelBuilder> hotels = new LinkedHashMap<>();
        jdbc.query("""
                select id,
                       case when unit_type = 'GROUP' then '集团总部' else name end as name,
                       unit_type
                from org_unit
                where tenant_id = :tenantId and status = 'ACTIVE'
                  and ((unit_type = 'GROUP' and parent_id is null) or unit_type = 'HOTEL')
                order by case unit_type when 'GROUP' then 0 else 1 end, name, id
                """, params(), rs -> {
            UUID hotelId = rs.getObject("id", UUID.class);
            hotels.put(hotelId, new HotelBuilder(
                    hotelId, rs.getString("name"), rs.getString("unit_type")));
        });
        List<OptionRow> rows = jdbc.query("""
                select organization.id as hotel_id,
                       case when organization.unit_type = 'GROUP'
                            then '集团总部' else organization.name end as hotel_name,
                       organization.unit_type as organization_type,
                       organization.id as department_id,
                       case when organization.unit_type = 'GROUP'
                            then '集团总部' else organization.name end as department_name,
                       position.id as position_id, position.name as position_name,
                       true as position_selectable,
                       cast(null as text) as unavailable_reason
                from org_unit organization
                join position_definition position
                  on position.tenant_id = organization.tenant_id and position.status = 'ACTIVE'
                 and position.deleted_at is null and position.permanently_deleted_at is null
                join position_function_profile group_profile
                  on group_profile.tenant_id = position.tenant_id
                 and group_profile.position_id = position.id
                 and group_profile.scope_type = 'GROUP'
                 and group_profile.default_role_id is not null
                join position_function_profile_version group_version
                  on group_version.tenant_id = group_profile.tenant_id
                 and group_version.profile_id = group_profile.id
                 and group_version.lifecycle_status = 'PUBLISHED'
                left join position_function_profile hotel_profile
                  on hotel_profile.tenant_id = position.tenant_id
                 and hotel_profile.position_id = position.id
                 and hotel_profile.scope_type = 'HOTEL'
                 and hotel_profile.hotel_org_unit_id = organization.id
                left join position_function_profile_version hotel_version
                  on hotel_version.tenant_id = hotel_profile.tenant_id
                 and hotel_version.profile_id = hotel_profile.id
                 and hotel_version.lifecycle_status = 'PUBLISHED'
                where organization.tenant_id = :tenantId and organization.status = 'ACTIVE'
                  and ((organization.unit_type = 'GROUP' and organization.parent_id is null)
                       or organization.unit_type = 'HOTEL')
                  and coalesce(hotel_version.wecom_self_selectable,
                               group_version.wecom_self_selectable) = true
                  and (
                    (organization.unit_type = 'GROUP'
                     and position.job_family = 'GROUP_MANAGEMENT'
                     and group_version.authorization_scope_type <> 'TENANT'
                     and not exists (
                       select 1
                       from role_permission protected_grant
                       join permission protected_permission
                         on protected_permission.id = protected_grant.permission_id
                       where protected_grant.tenant_id = group_profile.tenant_id
                         and protected_grant.role_id = group_profile.default_role_id
                         and protected_permission.delegable_to_position = false
                     ))
                    or
                    (organization.unit_type = 'HOTEL' and (
                      position.applies_to_all_hotels = true or exists (
                        select 1 from position_applicable_hotel applicable
                        where applicable.tenant_id = position.tenant_id
                          and applicable.position_id = position.id
                          and applicable.hotel_org_unit_id = organization.id
                      )
                    ))
                  )
                order by case organization.unit_type when 'GROUP' then 0 else 1 end,
                         organization.name, position.name, position.id
                """, params(), (rs, rowNum) -> new OptionRow(
                rs.getObject("hotel_id", UUID.class), rs.getString("hotel_name"),
                rs.getString("organization_type"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                rs.getObject("position_id", UUID.class), rs.getString("position_name"),
                rs.getBoolean("position_selectable"), rs.getString("unavailable_reason")));
        for (OptionRow row : rows) {
            HotelBuilder hotel = hotels.computeIfAbsent(row.hotelId(),
                    ignored -> new HotelBuilder(
                            row.hotelId(), row.hotelName(), row.organizationType()));
            hotel.add(row);
        }
        return hotels.values().stream().map(HotelBuilder::build).toList();
    }

    private void requirePendingAssignmentOrganization(UUID orgUnitId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from org_unit
                where tenant_id = :tenantId and id = :orgUnitId
                  and status = 'ACTIVE'
                  and ((unit_type = 'GROUP' and parent_id is null) or unit_type = 'HOTEL')
                """, params().addValue("orgUnitId", orgUnitId), Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("所选组织已失效，不能提交岗位待分配申请");
        }
    }

    private void requireSelectable(UUID orgUnitId, UUID positionId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from org_unit assignment_org
                left join lateral (
                    select ancestor.id
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = assignment_org.tenant_id
                      and closure.descendant_id = assignment_org.id
                      and ancestor.unit_type = 'HOTEL' and ancestor.status = 'ACTIVE'
                    order by closure.depth asc
                    limit 1
                ) hotel on true
                join position_definition position
                  on position.tenant_id = assignment_org.tenant_id and position.id = :positionId
                 and position.status = 'ACTIVE' and position.deleted_at is null
                 and position.permanently_deleted_at is null
                join position_function_profile group_profile
                  on group_profile.tenant_id = position.tenant_id
                 and group_profile.position_id = position.id
                 and group_profile.scope_type = 'GROUP'
                 and group_profile.default_role_id is not null
                join position_function_profile_version group_version
                  on group_version.tenant_id = group_profile.tenant_id
                 and group_version.profile_id = group_profile.id
                 and group_version.lifecycle_status = 'PUBLISHED'
                left join position_function_profile hotel_profile
                  on hotel_profile.tenant_id = position.tenant_id
                  and hotel_profile.position_id = position.id
                  and hotel_profile.scope_type = 'HOTEL'
                 and hotel_profile.hotel_org_unit_id = hotel.id
                left join position_function_profile_version hotel_version
                  on hotel_version.tenant_id = hotel_profile.tenant_id
                 and hotel_version.profile_id = hotel_profile.id
                 and hotel_version.lifecycle_status = 'PUBLISHED'
                where assignment_org.tenant_id = :tenantId and assignment_org.id = :orgUnitId
                  and assignment_org.status = 'ACTIVE'
                  and ((assignment_org.unit_type = 'GROUP' and assignment_org.parent_id is null)
                       or assignment_org.unit_type in ('HOTEL', 'DEPARTMENT'))
                  and coalesce(hotel_version.wecom_self_selectable,
                               group_version.wecom_self_selectable) = true
                  and (
                    (assignment_org.unit_type = 'GROUP'
                     and position.job_family = 'GROUP_MANAGEMENT'
                     and group_version.authorization_scope_type <> 'TENANT'
                     and not exists (
                       select 1
                       from role_permission protected_grant
                       join permission protected_permission
                         on protected_permission.id = protected_grant.permission_id
                       where protected_grant.tenant_id = group_profile.tenant_id
                         and protected_grant.role_id = group_profile.default_role_id
                         and protected_permission.delegable_to_position = false
                     ))
                    or
                    (assignment_org.unit_type in ('HOTEL', 'DEPARTMENT')
                     and hotel.id is not null
                     and (position.applies_to_all_hotels = true or exists (
                       select 1 from position_applicable_hotel applicable
                       where applicable.tenant_id = position.tenant_id
                         and applicable.position_id = position.id
                         and applicable.hotel_org_unit_id = hotel.id
                     )))
                  )
                """, params().addValue("orgUnitId", orgUnitId).addValue("positionId", positionId),
                Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("所选门店、部门或岗位已失效、不适用或不允许员工申请");
        }
    }

    private List<ExistingBinding> bindingsByUserId(String userId) {
        return jdbc.query("""
                select id, account_id, status, status_reason, identity_event_receipt_id,
                       directory_assignment_snapshot_hash
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId and wecom_user_id = :userId
                for update
                """, params().addValue("userId", userId), (rs, rowNum) -> new ExistingBinding(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("status"), rs.getString("status_reason"),
                rs.getObject("identity_event_receipt_id", UUID.class),
                rs.getString("directory_assignment_snapshot_hash"), true, 0));
    }

    private List<ExistingBinding> bindingsForDirectoryEvent(String userId, UUID receiptId) {
        String fingerprint = codec.fingerprint(userId);
        return jdbc.query("""
                with recursive current_receipt as (
                  select id, tenant_id, corp_id, occurred_at, event_priority, received_at
                  from wecom_directory_event_receipt
                  where tenant_id = :tenantId and corp_id = :corpId and id = :receiptId
                ), identity_lineage(fingerprint, depth, path) as (
                  select cast(:fingerprint as text), 0,
                         array[cast(:fingerprint as text)]
                  union all
                  select cast(rename.previous_user_id_fingerprint as text),
                         lineage.depth + 1,
                         lineage.path || cast(rename.previous_user_id_fingerprint as text)
                  from identity_lineage lineage
                  join current_receipt current on true
                  join lateral (
                    select receipt.previous_user_id_fingerprint
                    from wecom_directory_event_receipt receipt
                    where receipt.tenant_id = current.tenant_id
                      and receipt.corp_id = current.corp_id
                      and receipt.change_type = 'update_user'
                      and receipt.previous_user_id_fingerprint is not null
                      and receipt.user_id_fingerprint = lineage.fingerprint
                      and (
                        receipt.occurred_at < current.occurred_at
                        or (receipt.occurred_at = current.occurred_at
                            and receipt.event_priority < current.event_priority)
                        or (receipt.occurred_at = current.occurred_at
                            and receipt.event_priority = current.event_priority
                            and receipt.received_at < current.received_at)
                        or (receipt.occurred_at = current.occurred_at
                            and receipt.event_priority = current.event_priority
                            and receipt.received_at = current.received_at
                            and receipt.id::text <= current.id::text)
                      )
                    order by receipt.occurred_at desc, receipt.event_priority desc,
                             receipt.received_at desc, receipt.id desc
                    limit 1
                  ) rename on true
                  where lineage.depth < 16
                    and not cast(rename.previous_user_id_fingerprint as text) = any(lineage.path)
                ), lineage as (
                  select fingerprint, min(depth) as depth
                  from identity_lineage
                  group by fingerprint
                )
                select binding.id, binding.account_id, binding.status, binding.status_reason,
                       binding.identity_event_receipt_id,
                       binding.directory_assignment_snapshot_hash,
                       lineage.depth as lineage_depth,
                       case when binding.identity_event_receipt_id is null then
                         coalesce(binding.last_verified_at, binding.created_at)
                             <= current.received_at
                       else exists (
                         select 1
                         from wecom_directory_event_receipt identity_receipt
                         where identity_receipt.tenant_id = current.tenant_id
                           and identity_receipt.corp_id = current.corp_id
                           and identity_receipt.id = binding.identity_event_receipt_id
                           and (identity_receipt.occurred_at < current.occurred_at
                            or (identity_receipt.occurred_at = current.occurred_at
                                and identity_receipt.received_at < current.received_at)
                            or (identity_receipt.occurred_at = current.occurred_at
                                and identity_receipt.received_at = current.received_at
                                and identity_receipt.id::text <= current.id::text))
                       ) end as eligible_for_event
                from wecom_user_binding binding
                join lineage on lineage.fingerprint = binding.user_id_fingerprint
                join current_receipt current on current.tenant_id = binding.tenant_id
                  and current.corp_id = binding.corp_id
                where binding.tenant_id = :tenantId and binding.corp_id = :corpId
                order by lineage.depth, binding.id
                for update of binding
                """, params().addValue("fingerprint", fingerprint)
                .addValue("receiptId", receiptId), (rs, rowNum) -> new ExistingBinding(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("status"), rs.getString("status_reason"),
                rs.getObject("identity_event_receipt_id", UUID.class),
                rs.getString("directory_assignment_snapshot_hash"),
                rs.getBoolean("eligible_for_event"),
                rs.getInt("lineage_depth")));
    }

    private void refreshUnchangedDirectoryProfile(
            WeComDirectoryEvent event,
            UUID receiptId,
            UUID correlationId,
            ExistingBinding binding,
            String snapshotHash
    ) {
        jdbc.update("""
                update wecom_user_binding
                set directory_assignment_snapshot_hash = :snapshotHash,
                    last_verified_at = now(), updated_at = now()
                where tenant_id = :tenantId and corp_id = :corpId and id = :bindingId
                  and status in ('ACTIVE','SUSPENDED')
                """, params().addValue("bindingId", binding.id())
                .addValue("snapshotHash", snapshotHash));
        if (event.displayName() != null && !event.displayName().isBlank()) {
            jdbc.update("""
                    update user_account
                    set display_name = :displayName, updated_at = now()
                    where tenant_id = :tenantId and id = :accountId
                    """, params().addValue("accountId", binding.accountId())
                    .addValue("displayName", event.displayName().trim()));
            jdbc.update("""
                    update employee
                    set name = :displayName, updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId
                      and employment_status = 'ACTIVE' and deleted_at is null
                    """, params().addValue("accountId", binding.accountId())
                    .addValue("displayName", event.displayName().trim()));
        }
        updateOpenCandidateName(event, receiptId);
        systemAudit("WECOM_DIRECTORY_PROFILE_REFRESHED", "wecom_user_binding", binding.id(),
                correlationId, Map.of(
                        "fingerprint", mask(codec.fingerprint(event.effectiveUserId())),
                        "receiptId", receiptId,
                        "assignmentSnapshotChanged", false));
    }

    private void lockOnboardingIdentity(String fingerprint) {
        jdbc.queryForObject("""
                select pg_advisory_xact_lock(
                    hashtext(cast(:tenantId as text)),
                    hashtext(:corpId || ':' || :fingerprint))
                """, params().addValue("fingerprint", fingerprint), Object.class);
    }

    private void notifyGovernance(
            UUID sourceId, String type, String title, String content, String suffix
    ) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                select distinct :tenantId, assignment.account_id, :type, :title, :content,
                       'WECOM_PERSON_ONBOARDING', :sourceId,
                       'wecom-onboarding:' || cast(:sourceId as text) || ':' || :suffix
                       || ':' || assignment.account_id::text
                from role_assignment assignment
                join app_role role
                  on role.tenant_id = assignment.tenant_id and role.id = assignment.role_id
                join user_account account
                  on account.tenant_id = assignment.tenant_id and account.id = assignment.account_id
                where assignment.tenant_id = :tenantId
                  and role.code in ('CEO','PLATFORM_ADMIN','HR_KPI_ADMIN',
                                    'HR_ADMINISTRATION','HR_ADMINISTRATION_SUPERVISOR')
                  and assignment.valid_from <= now()
                  and (assignment.valid_to is null or assignment.valid_to >= now())
                  and account.status = 'ACTIVE'
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params().addValue("sourceId", sourceId).addValue("type", type)
                .addValue("title", title).addValue("content", content).addValue("suffix", suffix));
    }

    private void notifyAccount(
            UUID accountId,
            UUID sourceId,
            String type,
            String title,
            String content,
            String suffix
    ) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                values (:tenantId, :accountId, :type, :title, :content,
                        'WECOM_PERSON_ONBOARDING', :sourceId,
                        'wecom-onboarding:' || cast(:sourceId as text) || ':' || :suffix
                        || ':account:' || cast(:accountId as text))
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params().addValue("accountId", accountId).addValue("sourceId", sourceId)
                .addValue("type", type).addValue("title", title)
                .addValue("content", content).addValue("suffix", suffix));
    }

    private void systemAudit(
            String action, String resourceType, UUID resourceId, UUID correlationId, Map<String, ?> data
    ) {
        jdbc.update("""
                insert into audit_log
                    (tenant_id, actor_id, action, resource_type, resource_id,
                     correlation_id, trace_id, outcome, sensitivity_level, after_data)
                values
                    (:tenantId, null, :action, :resourceType, :resourceId,
                     :correlationId, :correlationId, 'SUCCESS', 'INTERNAL', cast(:afterData as jsonb))
                """, params().addValue("action", action).addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId).addValue("correlationId", correlationId)
                .addValue("afterData", json(data)));
    }

    private String json(Map<String, ?> data) {
        try { return objectMapper.writeValueAsString(data); }
        catch (Exception exception) { throw new IllegalStateException("审计数据序列化失败"); }
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String newInvitationToken() { return randomSecret(); }

    static URI invitationUri(URI frontendBaseUrl, String token) {
        String base = frontendBaseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + "/#/wecom-onboarding?token=" + boundedSecret(token));
    }

    private static String boundedSecret(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException("安全凭据缺失或长度无效");
        }
        return value.trim();
    }

    private String requireDisplayName(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > 120) {
            throw new IllegalArgumentException("请填写不超过120个字符的员工姓名");
        }
        return normalized;
    }

    private String requireLoginName(String value) {
        String normalized = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized == null || !LOGIN_NAME.matcher(normalized).matches()
                || normalized.startsWith("system.") || normalized.startsWith("wecom.")) {
            throw new IllegalArgumentException("登录账号须为3至120位字母、数字、点、下划线或短横线");
        }
        return normalized;
    }

    private String requireMobile(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank() || !MOBILE_INPUT.matcher(raw).matches()) {
            throw new IllegalArgumentException("请输入正确的11位手机号");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 13 && digits.startsWith("86")) digits = digits.substring(2);
        if (digits.length() == 15 && digits.startsWith("0086")) digits = digits.substring(4);
        if (!MAINLAND_MOBILE.matcher(digits).matches()) {
            throw new IllegalArgumentException("请输入正确的11位手机号");
        }
        return digits;
    }

    private void requireAvailableMobile(String mobile, UUID candidateId) {
        Integer count = jdbc.queryForObject("""
                select (
                    (select count(*) from user_account
                     where tenant_id = :tenantId
                       and regexp_replace(mobile, '[^0-9]', '', 'g')
                           in (:mobile, '86' || :mobile, '0086' || :mobile))
                    +
                    (select count(*) from wecom_person_onboarding
                     where tenant_id = :tenantId and id <> :candidateId
                       and requested_mobile = :mobile
                       and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT'))
                )
                """, params().addValue("mobile", mobile)
                .addValue("candidateId", candidateId), Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("手机号已注册");
        }
    }

    private void requireAvailableLogin(String loginName, UUID candidateId) {
        Integer count = jdbc.queryForObject("""
                select (
                    (select count(*) from user_account
                     where tenant_id = :tenantId and lower(login_name) = :loginName)
                    +
                    (select count(*) from wecom_person_onboarding
                     where tenant_id = :tenantId and id <> :candidateId
                       and lower(requested_login_name) = :loginName
                       and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT'))
                )
                """, params().addValue("loginName", loginName)
                .addValue("candidateId", candidateId), Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("登录账号已被使用，请更换后重试");
        }
    }

    private static boolean isMobileConflict(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains("ux_wecom_onboarding_open_mobile");
    }

    private static String safePassword(String value) { return value == null ? "" : value; }

    private static String sha256(String value) { return WeComDirectorySecretCodec.sha256(value); }
    private static String mask(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) return null;
        String value = fingerprint.toUpperCase(java.util.Locale.ROOT);
        return value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
    }
    private static String directoryStatus(WeComDirectoryEvent event) {
        if (event.isDelete()) return "DELETED";
        if (event.isDisabled()) return "DISABLED";
        if ("4".equals(event.statusCode())) return "UNACTIVATED";
        return "ACTIVE";
    }
    private static String failureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("TIMEOUT")) return "WECOM_OAUTH_TIMEOUT";
        if (exception instanceof IllegalArgumentException) return "WECOM_OAUTH_INVALID_RESPONSE";
        return "WECOM_OAUTH_TECHNICAL_FAILURE";
    }
    private void apply() { databaseContext.apply(properties.tenantId()); }
    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenantId", properties.tenantId())
                .addValue("corpId", properties.corpId());
    }

    public record Start(URI authorizationUri, String browserVerifier, long maxAgeSeconds) { }
    static final class OAuthCallbackFailure extends RuntimeException {
        private final String resultCode;

        OAuthCallbackFailure(String resultCode) {
            super(resultCode);
            this.resultCode = resultCode;
        }

        OAuthCallbackFailure(String resultCode, RuntimeException cause) {
            super(resultCode, cause);
            this.resultCode = resultCode;
        }

        String resultCode() { return resultCode; }
    }
    private record StartCandidate(UUID id, OffsetDateTime expiresAt) { }
    private record ReusableInvitation(UUID id, OffsetDateTime expiresAt, UUID createdBy) { }
    private record SessionCandidate(UUID id, String status) { }
    private record CandidateSession(
            UUID id,
            String status,
            String displayName,
            String requestedLoginName,
            String fingerprint,
            UUID sourceAccountId,
            String invitationSource,
            long rowVersion
    ) { }
    private record OpenCandidate(
            UUID id, String status, String directoryStatus,
            OffsetDateTime lastEventAt, OffsetDateTime invitationExpiresAt
    ) { }
    private record ExistingBinding(
            UUID id,
            UUID accountId,
            String status,
            String statusReason,
            UUID identityEventReceiptId,
            String directoryAssignmentSnapshotHash,
            boolean eligibleForEvent,
            int lineageDepth
    ) { }
    private record OAuthCandidate(
            UUID id, String fingerprint, UUID sourceAccountId, String invitationSource
    ) { }
    private record ExpiredCandidate(UUID id, String fingerprint, boolean sharedEntry) { }
    private record OptionRow(
            UUID hotelId, String hotelName, String organizationType,
            UUID departmentId, String departmentName,
            UUID positionId, String positionName, boolean positionSelectable,
            String unavailableReason
    ) { }

    private static final class HotelBuilder {
        private final UUID id;
        private final String name;
        private final String unitType;
        private final Map<UUID, DepartmentBuilder> departments = new LinkedHashMap<>();
        private HotelBuilder(UUID id, String name, String unitType) {
            this.id = id;
            this.name = name;
            this.unitType = unitType;
        }
        private void add(OptionRow row) {
            departments.computeIfAbsent(row.departmentId(),
                    ignored -> new DepartmentBuilder(row.departmentId(), row.departmentName()))
                    .add(row.positionId(), row.positionName(), row.positionSelectable(),
                            row.unavailableReason());
        }
        private HotelOption build() {
            return new HotelOption(
                    id, name, unitType,
                    departments.values().stream().map(DepartmentBuilder::build).toList());
        }
    }

    private static final class DepartmentBuilder {
        private final UUID id;
        private final String name;
        private final List<PositionOption> positions = new ArrayList<>();
        private DepartmentBuilder(UUID id, String name) { this.id = id; this.name = name; }
        private void add(UUID id, String name, boolean selectable, String unavailableReason) {
            positions.add(new PositionOption(id, name, selectable, unavailableReason));
        }
        private DepartmentOption build() { return new DepartmentOption(id, name, List.copyOf(positions)); }
    }
}
