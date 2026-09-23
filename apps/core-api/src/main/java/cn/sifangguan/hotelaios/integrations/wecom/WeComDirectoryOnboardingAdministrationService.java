package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.workpackage.WorkPackageService;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.*;

/**
 * Administrative half of directory onboarding.  Approval deliberately uses
 * direct SQL in one transaction: an account must never be visible without its
 * employee, assignment, role assignment and WeCom binding.
 */
@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComDirectoryOnboardingAdministrationService {
    private static final Set<String> CANDIDATE_STATES = Set.of(
            "WAITING_PROFILE", "PENDING_APPROVAL", "CONFLICT",
            "APPROVED", "REJECTED", "CANCELLED", "EXPIRED"
    );
    private static final Set<String> TECHNICAL_RETRY_FAILURES = Set.of(
            "INVITATION_DELIVERY_FAILED", "WECOM_OAUTH_TIMEOUT",
            "WECOM_OAUTH_TECHNICAL_FAILURE"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final WorkPackageService workPackages;
    private final ObjectMapper objectMapper;
    private final WeComDirectoryProperties properties;
    private final WeComDirectorySecretCodec codec;
    private final WeComApiClient apiClient;
    private final WeComDirectoryOnboardingService employeeService;
    private final Optional<WeComDirectoryEventProcessor> eventProcessor;

    public WeComDirectoryOnboardingAdministrationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            WorkPackageService workPackages,
            ObjectMapper objectMapper,
            WeComDirectoryProperties properties,
            WeComDirectorySecretCodec codec,
            WeComApiClient apiClient,
            WeComDirectoryOnboardingService employeeService,
            Optional<WeComDirectoryEventProcessor> eventProcessor
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.workPackages = workPackages;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.codec = codec;
        this.apiClient = apiClient;
        this.employeeService = employeeService;
        this.eventProcessor = eventProcessor;
    }

    @Transactional
    public CandidateList list(String requestedStatus) {
        TenantPrincipal principal = prepareAny("wecom-binding.read", "wecom-onboarding.review");
        employeeService.expireDueInvitations();
        String status = normalizeStatus(requestedStatus);
        MapSqlParameterSource parameters = base(principal)
                .addValue("status", status, Types.VARCHAR);
        List<CandidateRow> rows = jdbc.query("""
                select candidate.id, candidate.user_id_fingerprint,
                       coalesce(candidate.requested_display_name, candidate.display_name) as display_name,
                       candidate.requested_login_name,
                       candidate.onboarding_kind,
                       candidate.invitation_source,
                       candidate.requested_org_unit_id,
                       hotel.name as requested_hotel_name,
                       case when department.unit_type = 'DEPARTMENT'
                            then department.name end as requested_department_name,
                       candidate.requested_position_id,
                       position.name as requested_position_name,
                       candidate.status, candidate.failure_code,
                       candidate.invitation_expires_at,
                       candidate.updated_at, candidate.row_version
                from wecom_person_onboarding candidate
                left join org_unit department
                  on department.tenant_id = candidate.tenant_id
                 and department.id = candidate.requested_org_unit_id
                left join lateral (
                    select case when ancestor.unit_type = 'GROUP'
                                then '集团总部' else ancestor.name end as name
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = candidate.tenant_id
                      and closure.descendant_id = candidate.requested_org_unit_id
                      and ancestor.status = 'ACTIVE'
                      and (ancestor.unit_type = 'HOTEL'
                           or (ancestor.unit_type = 'GROUP' and ancestor.parent_id is null))
                    order by case ancestor.unit_type when 'HOTEL' then 0 else 1 end,
                             closure.depth asc
                    limit 1
                ) hotel on true
                left join position_definition position
                  on position.tenant_id = candidate.tenant_id
                 and position.id = candidate.requested_position_id
                where candidate.tenant_id = :tenantId and candidate.corp_id = :corpId
                  and (:status is null or candidate.status = :status)
                order by candidate.updated_at desc, candidate.id
                limit 500
        """, parameters, (rs, rowNum) -> {
            String candidateStatus = rs.getString("status");
            String failureCode = rs.getString("failure_code");
            OffsetDateTime expiresAt = rs.getObject("invitation_expires_at", OffsetDateTime.class);
            boolean expiringSoon = "WAITING_PROFILE".equals(candidateStatus)
                    && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now())
                    && !expiresAt.isAfter(OffsetDateTime.now().plusMinutes(30));
            return new CandidateRow(
                    rs.getObject("id", UUID.class), mask(rs.getString("user_id_fingerprint")),
                    rs.getString("display_name"), rs.getString("requested_login_name"),
                    rs.getString("onboarding_kind"),
                    rs.getString("invitation_source"),
                    rs.getObject("requested_org_unit_id", UUID.class),
                    rs.getString("requested_hotel_name"), rs.getString("requested_department_name"),
                    rs.getObject("requested_position_id", UUID.class),
                    rs.getString("requested_position_name"), candidateStatus,
                    failureCode, expiresAt, expiringSoon,
                    failureCode != null && TECHNICAL_RETRY_FAILURES.contains(failureCode),
                    "EXPIRED".equals(candidateStatus)
                            && "DIRECTORY_EVENT".equals(rs.getString("invitation_source")),
                    suggestedAction(candidateStatus, failureCode, expiringSoon,
                            rs.getString("invitation_source")),
                    rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("row_version")
            );
        });
        return new CandidateList(rows);
    }

    @Transactional(readOnly = true)
    public ReviewOptions reviewOptions(UUID candidateId) {
        TenantPrincipal principal = prepareAny("wecom-binding.approve", "wecom-onboarding.review");
        List<ReviewCandidate> candidates = jdbc.query("""
                select candidate.id, candidate.status,
                       organization.id as hotel_id,
                       case when organization.unit_type = 'GROUP'
                            then '集团总部' else organization.name end as hotel_name,
                       organization.unit_type
                from wecom_person_onboarding candidate
                join lateral (
                    select ancestor.id, ancestor.name, ancestor.unit_type
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = candidate.tenant_id
                      and closure.descendant_id = candidate.requested_org_unit_id
                      and ancestor.status = 'ACTIVE'
                      and (ancestor.unit_type = 'HOTEL'
                           or (ancestor.unit_type = 'GROUP' and ancestor.parent_id is null))
                    order by case ancestor.unit_type when 'HOTEL' then 0 else 1 end,
                             closure.depth asc
                    limit 1
                ) organization on true
                where candidate.tenant_id = :tenantId and candidate.corp_id = :corpId
                  and candidate.id = :candidateId
                """, base(principal).addValue("candidateId", candidateId), (rs, rowNum) ->
                new ReviewCandidate(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("hotel_id", UUID.class), rs.getString("hotel_name"),
                        rs.getString("unit_type")));
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("入职申请没有可用的申请组织");
        }
        ReviewCandidate candidate = candidates.getFirst();
        if (!Set.of("PENDING_APPROVAL", "CONFLICT").contains(candidate.status())) {
            throw new IllegalArgumentException("当前状态不允许分配岗位");
        }
        boolean canManageAssignments = "GROUP".equals(candidate.unitType())
                && canManageAssignments(principal);
        if (canManageAssignments) {
            accessPolicy.requireOrgScope(candidate.hotelId());
        }
        List<ReviewAssignmentOption> positions = jdbc.query("""
                select position.id, position.name
                from position_definition position
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
                 and hotel_profile.hotel_org_unit_id = :hotelId
                left join position_function_profile_version hotel_version
                  on hotel_version.tenant_id = hotel_profile.tenant_id
                 and hotel_version.profile_id = hotel_profile.id
                 and hotel_version.lifecycle_status = 'PUBLISHED'
                where position.tenant_id = :tenantId and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and (:canManageAssignments or (
                      coalesce(hotel_version.authorization_scope_type,
                               group_version.authorization_scope_type) <> 'TENANT'
                      and not exists (
                          select 1
                          from role_permission role_grant
                          join permission protected_permission
                            on protected_permission.id = role_grant.permission_id
                          where role_grant.tenant_id = group_profile.tenant_id
                            and role_grant.role_id = group_profile.default_role_id
                            and protected_permission.delegable_to_position = false
                      )
                  ))
                  and (
                    (:organizationType = 'GROUP'
                     and position.job_family = 'GROUP_MANAGEMENT')
                    or
                    (:organizationType = 'HOTEL' and (
                      position.applies_to_all_hotels = true or exists (
                        select 1 from position_applicable_hotel applicable
                        where applicable.tenant_id = position.tenant_id
                          and applicable.position_id = position.id
                          and applicable.hotel_org_unit_id = :hotelId
                      )
                    ))
                  )
                order by position.name, position.id
                """, base(principal).addValue("hotelId", candidate.hotelId())
                        .addValue("organizationType", candidate.unitType())
                        .addValue("canManageAssignments", canManageAssignments), (rs, rowNum) ->
                new ReviewAssignmentOption(
                        candidate.hotelId(), rs.getObject("id", UUID.class), rs.getString("name")));
        return new ReviewOptions(candidate.id(), candidate.hotelId(), candidate.hotelName(), positions);
    }

    @Transactional
    public OpenInvitationResponse createOpenInvitation() {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        employeeService.expireDueInvitations();
        Integer openInvitations = jdbc.queryForObject("""
                select count(*) from wecom_open_onboarding_invitation
                where tenant_id = :tenantId and corp_id = :corpId
                  and expires_at > now()
                """, base(principal), Integer.class);
        if (openInvitations != null && openInvitations >= 20) {
            throw new IllegalArgumentException("当前已有较多有效邀请，请等待过期后再生成");
        }

        UUID invitationId = UUID.randomUUID();
        String token = employeeService.newInvitationToken();
        OffsetDateTime issuedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = issuedAt.plus(properties.sharedInvitationTtl());
        jdbc.update("""
                insert into wecom_open_onboarding_invitation
                    (id, tenant_id, corp_id, token_hash, issued_at, expires_at, created_by)
                values
                    (:id, :tenantId, :corpId, :tokenHash, :issuedAt, :expiresAt, :actorId)
                """, base(principal).addValue("id", invitationId)
                .addValue("tokenHash", WeComDirectorySecretCodec.sha256(token))
                .addValue("issuedAt", issuedAt).addValue("expiresAt", expiresAt));
        auditWriter.record("WECOM_OPEN_ONBOARDING_INVITATION_CREATED",
                "WECOM_OPEN_ONBOARDING_INVITATION", invitationId, json(Map.of(
                        "expiresAt", expiresAt.toString(),
                        "invitationSource", "MANUAL_LINK",
                        "reusableUntilExpiry", true,
                        "employeeProfileProvidedByAdministrator", false,
                        "tokenExposedInAudit", false
                )));
        return new OpenInvitationResponse(invitationId,
                WeComDirectoryOnboardingService.invitationUri(properties.frontendBaseUrl(), token),
                expiresAt, "WAITING_PROFILE", 0,
                "多人注册链接已生成，有效期内每位员工可独立扫码并自行填写");
    }

    @Transactional
    public InvitationActionResponse retryTechnicalFailure(UUID candidateId, RetryRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        employeeService.expireDueInvitations();
        String fingerprint = candidateFingerprint(principal, candidateId);
        lockOnboardingIdentity(principal, fingerprint);
        Candidate candidate = lockCandidate(principal, candidateId);
        if (!"WAITING_PROFILE".equals(candidate.status())
                || !TECHNICAL_RETRY_FAILURES.contains(candidate.failureCode())) {
            throw new IllegalArgumentException(
                    "仅邀请发送失败或企业微信OAuth技术故障可重试；身份冲突必须审批处理");
        }
        InvitationActionResponse cancelled = cancelSupersededInvitation(principal, candidate);
        if (cancelled != null) return cancelled;
        return issueFreshInvitation(principal, candidate, request, "TECHNICAL_RETRY");
    }

    @Transactional
    public InvitationActionResponse regenerateInvitation(UUID candidateId, RetryRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        employeeService.expireDueInvitations();
        String fingerprint = candidateFingerprint(principal, candidateId);
        lockOnboardingIdentity(principal, fingerprint);
        Candidate candidate = lockCandidate(principal, candidateId);
        if (!"EXPIRED".equals(candidate.status())) {
            throw new IllegalArgumentException("仅已过期申请可以重新生成邀请");
        }
        InvitationActionResponse cancelled = cancelSupersededInvitation(principal, candidate);
        if (cancelled != null) return cancelled;
        if (hasNewerOpenCandidate(principal, candidate)) {
            throw new IllegalArgumentException("该员工已有更新的申请，请处理最新记录");
        }
        return issueFreshInvitation(principal, candidate, request, "EXPIRED_REGENERATED");
    }

    @Transactional(readOnly = true)
    public DirectoryEventList listDirectoryEvents(String requestedStatus) {
        TenantPrincipal principal = prepare("wecom-binding.read");
        String status = requestedStatus == null || requestedStatus.isBlank()
                ? "DEAD_LETTER" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!"DEAD_LETTER".equals(status)) {
            throw new IllegalArgumentException("当前仅提供需管理员处理的同步技术异常");
        }
        List<DirectoryEventRow> rows = jdbc.query("""
                select id, change_type, status, last_error_code, received_at, row_version
                from wecom_directory_event_receipt
                where tenant_id = :tenantId and corp_id = :corpId and status = 'DEAD_LETTER'
                order by received_at desc, id
                limit 500
                """, base(principal), (rs, rowNum) -> {
            String errorCode = rs.getString("last_error_code");
            return new DirectoryEventRow(
                    rs.getObject("id", UUID.class), rs.getString("change_type"),
                    rs.getString("status"), errorCode, directoryErrorMessage(errorCode),
                    rs.getObject("received_at", OffsetDateTime.class), rs.getLong("row_version"),
                    "管理员核对企业微信配置和成员状态后点击重试；无需登录服务器");
        });
        return new DirectoryEventList(rows);
    }

    @Transactional
    public DirectoryEventRetryResponse retryDirectoryEvent(
            UUID receiptId, RetryRequest request
    ) {
        WeComDirectoryEventProcessor processor = eventProcessor.orElseThrow(() ->
                new IllegalArgumentException("企业微信通讯录自动同步未启用，不能重试同步事件"));
        TenantPrincipal principal = prepare("wecom-binding.approve");
        List<DirectoryReceipt> rows = jdbc.query("""
                select id, status, correlation_id, row_version,
                       nullif(payload_ciphertext, '') is not null as payload_available
                from wecom_directory_event_receipt
                where tenant_id = :tenantId and corp_id = :corpId and id = :receiptId
                for update
                """, base(principal).addValue("receiptId", receiptId), (rs, rowNum) ->
                new DirectoryReceipt(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("correlation_id", UUID.class), rs.getLong("row_version"),
                        rs.getBoolean("payload_available")));
        if (rows.size() != 1) throw new IllegalArgumentException("企业微信人员同步异常记录不存在");
        DirectoryReceipt receipt = rows.getFirst();
        if (!"DEAD_LETTER".equals(receipt.status())) {
            throw new IllegalArgumentException("仅死信状态的人员同步事件可以人工重试");
        }
        if (!receipt.payloadAvailable()) {
            throw new IllegalArgumentException("该事件的安全载荷已清理，不能重试");
        }
        if (receipt.rowVersion() != request.expectedVersion()) throw stale();
        int updated = jdbc.update("""
                update wecom_directory_event_receipt
                set status = 'PROCESSING', attempt_count = 0, last_attempt_at = null,
                    processed_at = null, last_error_code = null, row_version = row_version + 1
                where tenant_id = :tenantId and corp_id = :corpId and id = :receiptId
                  and status = 'DEAD_LETTER' and row_version = :expectedVersion
                """, base(principal).addValue("receiptId", receipt.id())
                .addValue("expectedVersion", receipt.rowVersion()));
        if (updated != 1) throw stale();
        notifyGovernance(principal, receipt.id(), "WECOM_DIRECTORY_EVENT_RETRY_QUEUED",
                "企业微信人员同步已重新排队",
                "管理员已对技术死信执行重试，系统正在安全恢复处理。", "dead-letter-retry");
        auditWriter.record("WECOM_DIRECTORY_EVENT_MANUAL_RETRY",
                "WECOM_DIRECTORY_EVENT_RECEIPT", receipt.id(), json(Map.of(
                        "previousStatus", "DEAD_LETTER",
                        "reasonProvided", !safeReason(request.reason()).isBlank(),
                        "reasonCode", "TECHNICAL_RETRY_REQUESTED",
                        "userIdentifierExposed", false
                )));
        runAfterCommit(() -> processor.processNew(receipt.id(), receipt.correlationId()));
        return new DirectoryEventRetryResponse(receipt.id(), "PROCESSING",
                receipt.rowVersion() + 1, "技术异常已重新排队处理");
    }

    private InvitationActionResponse issueFreshInvitation(
            TenantPrincipal principal, Candidate candidate, RetryRequest request, String action
    ) {
        if (candidate.rowVersion() != request.expectedVersion()) throw stale();
        if (!"ACTIVE".equals(candidate.directoryStatus())) {
            throw new IllegalArgumentException("企业微信成员当前非启用状态，不能生成邀请");
        }
        boolean manualLink = "MANUAL_LINK".equals(candidate.invitationSource());
        String userId = manualLink && candidate.userIdCiphertext() == null
                ? null
                : codec.decrypt(required(candidate.userIdCiphertext(), "候选人身份已失效"));
        String token = employeeService.newInvitationToken();
        OffsetDateTime issuedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = issuedAt.plus(properties.invitationTtl());
        int updated = jdbc.update("""
                update wecom_person_onboarding
                set status = 'WAITING_PROFILE', expired_at = null,
                    invitation_token_hash = :tokenHash, invitation_issued_at = :issuedAt,
                    invitation_expires_at = :expiresAt, oauth_state_hash = null,
                    browser_verifier_hash = null, provider_code_hash = null,
                    identity_verified_at = null, exchange_code_hash = null,
                    exchange_expires_at = null, session_token_hash = null,
                    session_expires_at = null, requested_org_unit_id = null,
                    requested_position_id = null, profile_submitted_at = null,
                    requested_display_name = null, requested_mobile = null,
                    requested_login_name = null,
                    requested_password_hash = null, registration_completed_at = null,
                    conflicting_account_id = null, failure_code = null,
                    decision_reason = null, row_version = row_version + 1
                where tenant_id = :tenantId and corp_id = :corpId and id = :candidateId
                  and row_version = :expectedVersion and status = :expectedStatus
                """, base(principal).addValue("candidateId", candidate.id())
                .addValue("expectedVersion", candidate.rowVersion())
                .addValue("expectedStatus", candidate.status())
                .addValue("tokenHash", WeComDirectorySecretCodec.sha256(token))
                .addValue("issuedAt", issuedAt).addValue("expiresAt", expiresAt));
        if (updated != 1) throw stale();
        if (userId != null) {
            employeeService.deliverInvitationAfterCommit(candidate.id(), userId, token);
        }
        notifyGovernance(principal, candidate.id(), "WECOM_ONBOARDING_INVITATION_REISSUED",
                "企业微信入职邀请已重新生成",
                manualLink
                        ? "旧链接已失效，请复制新的注册链接或二维码发送给员工。"
                        : "旧链接已失效，新邀请仅通过企业微信应用消息发送，有效期120分钟。",
                "invitation-reissued-" + (candidate.rowVersion() + 1));
        auditWriter.record("WECOM_ONBOARDING_INVITATION_REISSUED",
                "WECOM_PERSON_ONBOARDING", candidate.id(), json(Map.of(
                        "action", action,
                        "previousFailureCode", candidate.failureCode() == null
                                ? "NONE" : candidate.failureCode(),
                        "expiresAt", expiresAt.toString(),
                        "fingerprint", mask(candidate.fingerprint()),
                        "reasonProvided", !safeReason(request.reason()).isBlank(),
                        "reasonCode", "INVITATION_REISSUED",
                        "tokenExposed", false
                )));
        return new InvitationActionResponse(candidate.id(), "WAITING_PROFILE", expiresAt,
                candidate.rowVersion() + 1,
                manualLink
                        ? "新注册链接已生成，旧链接已失效"
                        : "新邀请已通过企业微信应用消息发送，旧链接已失效");
    }

    @Transactional
    public ApprovalResponse approve(UUID candidateId, DecisionRequest request) {
        TenantPrincipal principal = prepareAny("wecom-binding.approve", "wecom-onboarding.review");
        String fingerprint = candidateFingerprint(principal, candidateId);
        lockOnboardingIdentity(principal, fingerprint);
        Candidate candidate = lockCandidate(principal, candidateId);
        if ("APPROVED".equals(candidate.status())) {
            return new ApprovalResponse(candidate.id(), candidate.accountId(), "APPROVED",
                    "ACTIVE", candidate.rowVersion(), "该申请已启用");
        }
        if (!Set.of("PENDING_APPROVAL", "CONFLICT").contains(candidate.status())) {
            throw new IllegalArgumentException("当前状态不允许确认启用");
        }
        requireVersion(candidate, request.expectedVersion());
        requireLatestDirectoryFact(principal, candidate);

        String userId = codec.decrypt(required(candidate.userIdCiphertext(), "候选人身份已失效"));
        boolean reuseExistingIdentity = !"NEW_MEMBER".equals(candidate.onboardingKind());
        SourceIdentity sourceIdentity = reuseExistingIdentity
                ? lockSourceIdentity(principal, candidate) : null;
        boolean activateSourceBinding = sourceIdentity == null
                || sourceBindingCanActivate(candidate, sourceIdentity);
        String resultingBindingStatus = activateSourceBinding ? "ACTIVE" : "SUSPENDED";
        List<BindingConflict> conflicts = bindingConflicts(principal, userId);
        if (sourceIdentity != null) {
            conflicts = conflicts.stream()
                    .filter(conflict -> !conflict.id().equals(sourceIdentity.bindingId()))
                    .toList();
        }
        BindingConflict transfer = null;
        if (!conflicts.isEmpty()) {
            BindingConflict conflict = conflicts.getFirst();
            if (!"CONFLICT".equals(candidate.status())
                    || !conflict.accountId().equals(candidate.conflictingAccountId())) {
                long version = markConflict(principal, candidate, conflict.accountId());
                notifyGovernance(principal, candidate.id(), "WECOM_ONBOARDING_CONFLICT",
                        "企业微信入职身份冲突",
                        "该企微身份已绑定其他账号，请在绑定异常中处理后再确认。",
                        "conflict");
                auditWriter.record("WECOM_ONBOARDING_CONFLICT_DETECTED",
                        "WECOM_PERSON_ONBOARDING", candidate.id(), json(Map.of(
                                "fingerprint", mask(candidate.fingerprint()),
                                "failureCode", "USER_ID_ALREADY_BOUND"
                        )));
                return new ApprovalResponse(candidate.id(), null, "CONFLICT", null,
                        version, "身份已绑定其他账号，需要先完成异常处理");
            }
            if (!request.transferExistingBinding()) {
                return new ApprovalResponse(candidate.id(), null, "CONFLICT", null,
                        candidate.rowVersion(), "身份仍绑定其他账号，请二次确认后执行原子转移");
            }
            required(trimToNull(request.reason()), "原子转移企业微信身份必须填写确认原因");
            transfer = conflict;
        }

        boolean reviewerAssignedPosition = candidate.positionId() == null;
        UUID assignmentOrgUnitId = candidate.orgUnitId();
        UUID assignmentPositionId = candidate.positionId();
        if (reviewerAssignedPosition) {
            if (request.orgUnitId() == null || request.positionId() == null) {
                throw new IllegalArgumentException("请先为员工分配具体岗位再确认启用");
            }
            if (!request.orgUnitId().equals(candidate.orgUnitId())) {
                throw new IllegalArgumentException("审核分配岗位必须属于员工申请的组织");
            }
            assignmentOrgUnitId = request.orgUnitId();
            assignmentPositionId = request.positionId();
        }

        // Match the authority needed by the normal employee assignment editor.
        // Only an explicit reviewer assignment may use it, never employee self-selection.
        boolean canManageAssignments = reviewerAssignedPosition && canManageAssignments(principal);
        if (canManageAssignments) {
            accessPolicy.requireOrgScope(assignmentOrgUnitId);
        }
        PositionGrant grant = resolvePositionGrant(
                principal, assignmentOrgUnitId, assignmentPositionId, canManageAssignments);
        if (grant == null) {
            long version = markFailure(principal, candidate,
                    "POSITION_ROLE_NOT_CONFIGURED");
            return new ApprovalResponse(candidate.id(), null, candidate.status(), null,
                    version, "岗位尚未发布可用的权限方案");
        }
        if ((reviewerAssignedPosition && !grant.reviewerAssignable())
                || (!reviewerAssignedPosition && !grant.selfSelectable())) {
            long version = markFailure(principal, candidate,
                    "POSITION_SELECTION_NO_LONGER_VALID");
            return new ApprovalResponse(candidate.id(), null, candidate.status(), null,
                    version, reviewerAssignedPosition
                    ? "所选岗位已失效、涉及受保护权限或不能由入职审核分配"
                    : "所选门店、部门或岗位已失效或不允许员工申请");
        }

        UUID accountId = sourceIdentity == null ? UUID.randomUUID() : sourceIdentity.accountId();
        UUID employeeId = sourceIdentity == null ? UUID.randomUUID() : sourceIdentity.employeeId();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = sourceIdentity != null ? sourceIdentity.bindingId() : UUID.randomUUID();
        String compactAccountId = accountId.toString().replace("-", "");
        String compactEmployeeId = employeeId.toString().replace("-", "");
        String displayName = sourceIdentity == null
                ? required(candidate.requestedDisplayName(), "员工尚未完成姓名与账号注册")
                : candidate.displayName();
        String loginName = sourceIdentity == null
                ? required(candidate.requestedLoginName(), "员工尚未完成登录账号注册")
                : "wecom." + compactAccountId;
        String passwordHash = sourceIdentity == null
                ? required(candidate.requestedPasswordHash(), "员工尚未完成登录密码注册")
                : null;
        String mobile = sourceIdentity == null ? candidate.requestedMobile() : null;
        if (mobile != null && mobileExists(principal, mobile)) {
            throw new IllegalArgumentException("手机号已注册");
        }
        if (sourceIdentity == null && loginExists(principal, loginName)) {
            throw new IllegalArgumentException("登录账号已被使用，请拒绝或让员工更换账号后重新提交");
        }
        MapSqlParameterSource identity = base(principal)
                .addValue("accountId", accountId)
                .addValue("employeeId", employeeId)
                .addValue("assignmentId", assignmentId)
                .addValue("roleAssignmentId", roleAssignmentId)
                .addValue("bindingId", bindingId)
                .addValue("displayName", displayName)
                .addValue("mobile", mobile)
                .addValue("loginName", loginName)
                .addValue("passwordHash", passwordHash)
                .addValue("employeeNo", "WECOM-" + compactEmployeeId)
                .addValue("orgUnitId", assignmentOrgUnitId)
                .addValue("positionId", assignmentPositionId)
                .addValue("roleId", grant.roleId())
                .addValue("scopeType", grant.scopeType())
                .addValue("scopeOrgUnitId", scopeOrgUnitId(grant.scopeType(), assignmentOrgUnitId))
                .addValue("userId", userId)
                .addValue("fingerprint", candidate.fingerprint())
                .addValue("assignmentSnapshot", WeComDirectorySecretCodec.sha256(assignmentId.toString()))
                .addValue("directoryAssignmentSnapshotHash",
                        candidate.directoryAssignmentSnapshotHash())
                .addValue("identityEventReceiptId", candidate.lastEventReceiptId())
                .addValue("expectedVersion", candidate.rowVersion());

        if (sourceIdentity == null) {
            try {
                identity.addValue("employeeType", cn.sifangguan.hotelaios.organization.EmployeeType.validate(request.employeeType(), true));
                jdbc.update("""
                        insert into user_account
                            (id, tenant_id, login_name, display_name, mobile, status,
                             password_hash, password_changed_at)
                        values (:accountId, :tenantId, :loginName, :displayName, :mobile, 'ACTIVE',
                                :passwordHash, now())
                        """, identity);
                jdbc.update("""
                        insert into employee
                            (id, tenant_id, account_id, employee_no, name, mobile,
                             employment_status, hired_on, employee_type, employee_type_updated_at, employee_type_updated_by)
                        values (:employeeId, :tenantId, :accountId, :employeeNo, :displayName, :mobile,
                                'ACTIVE', current_date, :employeeType, now(), :actorId)
                        """, identity);
            } catch (DuplicateKeyException exception) {
                if (isMobileConflict(exception)) {
                    throw new IllegalArgumentException("手机号已注册");
                }
                throw exception;
            }
        } else {
            retireSourceAssignmentAndRole(principal, sourceIdentity);
        }
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (:assignmentId, :tenantId, :employeeId, :orgUnitId, :positionId,
                        true, 'PERMANENT', current_date, 'ACTIVE')
                """, identity);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id,
                     scope_type, valid_from, granted_by, source_type, source_assignment_id)
                values (:roleAssignmentId, :tenantId, :accountId, :roleId, :scopeOrgUnitId,
                        :scopeType, now(), :actorId, 'POSITION_ASSIGNMENT', :assignmentId)
                """, identity);
        if ("ASSIGNED_HOTELS".equals(grant.scopeType())) {
            int insertedScope = jdbc.update("""
                    insert into employee_assignment_hotel_scope
                        (tenant_id, assignment_id, hotel_org_unit_id, created_by)
                    select :tenantId, :assignmentId, ancestor.id, :actorId
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                     and ancestor.unit_type = 'HOTEL'
                     and ancestor.status = 'ACTIVE'
                    where closure.tenant_id = :tenantId
                      and closure.descendant_id = :orgUnitId
                    order by closure.depth
                    limit 1
                    """, identity);
            if (insertedScope != 1) {
                throw new IllegalArgumentException("申请部门没有可用的所属门店，不能配置指定负责门店范围");
            }
        }
        workPackages.initializeOperationalAssignment(assignmentId);
        if (sourceIdentity != null) {
            if (transfer != null) revokeTransferredBinding(principal, transfer);
            int rebound = jdbc.update("""
                    update wecom_user_binding
                    set wecom_user_id = :userId, user_id_fingerprint = :fingerprint,
                        preferred_assignment_id = :assignmentId, status = :bindingStatus,
                        status_reason = :bindingReason, last_verified_at = now(),
                        assignment_snapshot_hash = :assignmentSnapshot,
                        directory_assignment_snapshot_hash = :directoryAssignmentSnapshotHash,
                        assignment_selection_required = false,
                        updated_by = case when :activateBinding then :actorId else updated_by end,
                        identity_event_receipt_id = :identityEventReceiptId,
                        row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and corp_id = :corpId and id = :bindingId
                      and account_id = :accountId and row_version = :sourceBindingVersion
                      and (status in ('ACTIVE','SUSPENDED')
                           or (status = 'REVOKED'
                               and status_reason = 'IDENTITY_TRANSFERRED_BY_ONBOARDING'))
                    """, identity.addValue("sourceBindingVersion", sourceIdentity.bindingVersion())
                    .addValue("bindingStatus", resultingBindingStatus)
                    .addValue("activateBinding", activateSourceBinding)
                    .addValue("bindingReason", activateSourceBinding
                            ? candidate.onboardingKind() : sourceIdentity.statusReason()));
            if (rebound != 1) throw stale();
        } else {
            if (transfer != null) revokeTransferredBinding(principal, transfer);
            jdbc.update("""
                    insert into wecom_user_binding
                        (id, tenant_id, corp_id, wecom_user_id, user_id_fingerprint,
                         account_id, preferred_assignment_id, status, last_verified_at,
                         assignment_snapshot_hash, assignment_selection_required, updated_by,
                         identity_event_receipt_id, directory_assignment_snapshot_hash)
                    values (:bindingId, :tenantId, :corpId, :userId, :fingerprint,
                            :accountId, :assignmentId, 'ACTIVE', now(),
                            :assignmentSnapshot, false, :actorId, :identityEventReceiptId,
                            :directoryAssignmentSnapshotHash)
                    """, identity);
        }
        int approved = jdbc.update("""
                update wecom_person_onboarding
                set status = 'APPROVED', failure_code = null, decision_reason = :reason,
                    requested_org_unit_id = :orgUnitId, requested_position_id = :positionId,
                    conflicting_account_id = null, reviewed_by = :actorId, reviewed_at = now(),
                    account_id = :accountId, employee_id = :employeeId,
                    assignment_id = :assignmentId, role_assignment_id = :roleAssignmentId,
                    binding_id = :bindingId, user_id_ciphertext = null,
                    invitation_token_hash = null, invitation_issued_at = null,
                    invitation_expires_at = null, oauth_state_hash = null,
                    browser_verifier_hash = null, provider_code_hash = null,
                    exchange_code_hash = null, exchange_expires_at = null,
                    session_token_hash = null, session_expires_at = null,
                    requested_password_hash = null,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :candidateId
                  and row_version = :expectedVersion
                  and status in ('PENDING_APPROVAL','CONFLICT')
                  and directory_status = 'ACTIVE'
                """, identity.addValue("candidateId", candidate.id())
                .addValue("reason", trimToNull(request.reason())));
        if (approved != 1) throw stale();

        notifyAccount(principal, accountId, candidate.id(), "WECOM_ONBOARDING_APPROVED",
                activateSourceBinding ? "中台账号已启用" : "任职变更已确认，绑定仍暂停",
                activateSourceBinding
                        ? "您申请的门店和岗位已审核通过，企业微信绑定已启用。"
                        : "您的任职变更已通过，原暂停原因已保留；请由管理员另行确认恢复绑定。",
                "approved-self");
        if (transfer != null) {
            notifyAccount(principal, transfer.accountId(), candidate.id(),
                    "WECOM_BINDING_IDENTITY_TRANSFERRED",
                    "企业微信身份绑定已转移",
                    "该企业微信身份已按管理员确认转移至新员工账号，原账号会话已立即失效。",
                    "transferred-from");
        }
        notifyGovernance(principal, candidate.id(), "WECOM_ONBOARDING_APPROVED",
                "企业微信入职已启用",
                reuseExistingIdentity
                        ? "既有员工账号已保留，旧任职及对应岗位角色已终止，新任职、角色和个人企微绑定已原子更新；群推送开关未改变。"
                        : "新员工账号、任职、角色和个人企微绑定已一次性建立，群推送开关未改变。",
                "approved-governance");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("accountId", accountId);
        audit.put("employeeId", employeeId);
        if(sourceIdentity==null)audit.put("employeeType", request.employeeType());
        audit.put("assignmentId", assignmentId);
        audit.put("roleAssignmentId", roleAssignmentId);
        audit.put("bindingId", bindingId);
        audit.put("fingerprint", mask(candidate.fingerprint()));
        audit.put("onboardingKind", candidate.onboardingKind());
        audit.put("identityReused", reuseExistingIdentity);
        audit.put("bindingTransferred", transfer != null);
        audit.put("bindingStatus", resultingBindingStatus);
        audit.put("reviewerAssignedPosition", reviewerAssignedPosition);
        audit.put("assignmentManagementAuthorized", canManageAssignments);
        audit.put("orgUnitId", assignmentOrgUnitId);
        audit.put("positionId", assignmentPositionId);
        if (transfer != null) {
            audit.put("transferredFromAccountId", transfer.accountId());
            audit.put("transferReasonProvided", trimToNull(request.reason()) != null);
            audit.put("transferReasonCode", "IDENTITY_TRANSFER_APPROVED");
        }
        audit.put("groupPushChanged", false);
        String auditAction = reuseExistingIdentity ? "WECOM_ONBOARDING_IDENTITY_UPDATED"
                : transfer == null ? "WECOM_ONBOARDING_APPROVED"
                : "WECOM_ONBOARDING_BINDING_TRANSFERRED";
        auditWriter.record(auditAction, "WECOM_PERSON_ONBOARDING",
                candidate.id(), json(audit));
        auditWriter.emit("WECOM_PERSON_ONBOARDING", candidate.id(),
                "WECOM_ONBOARDING_APPROVED", json(audit));
        sendEmployeeResultAfterCommit(userId,
                activateSourceBinding ? "中台账号已启用" : "任职变更已确认",
                activateSourceBinding
                        ? "您的门店和岗位申请已通过，可从企业微信打开中台。"
                        : "原暂停状态已保留，需管理员另行确认恢复后才可登录。",
                activateSourceBinding);
        String message = !activateSourceBinding
                ? "已保留原账号并更新任职与岗位角色；原暂停状态保留，需独立确认恢复，群推送开关未改变"
                : reuseExistingIdentity
                ? "已保留原账号并原子替换旧任职、岗位角色和个人企微绑定；旧企微会话已失效，群推送开关未改变"
                : transfer == null
                ? "已创建账号、任职、角色和个人企微绑定；群推送开关未改变"
                : "已创建账号、任职和角色，并原子转移个人企微绑定；原账号会话已失效，群推送开关未改变";
        return new ApprovalResponse(candidate.id(), accountId, "APPROVED", resultingBindingStatus,
                candidate.rowVersion() + 1, message);
    }

    @Transactional
    public ApprovalResponse reject(UUID candidateId, DecisionRequest request) {
        TenantPrincipal principal = prepareAny("wecom-binding.approve", "wecom-onboarding.review");
        Candidate candidate = lockCandidate(principal, candidateId);
        if ("REJECTED".equals(candidate.status())) {
            return new ApprovalResponse(candidate.id(), null, "REJECTED", null,
                    candidate.rowVersion(), "该申请已拒绝");
        }
        if (!Set.of("PENDING_APPROVAL", "CONFLICT").contains(candidate.status())) {
            throw new IllegalArgumentException("当前状态不允许拒绝");
        }
        requireVersion(candidate, request.expectedVersion());
        String reason = required(trimToNull(request.reason()), "请填写拒绝原因");
        String userId = codec.decrypt(required(candidate.userIdCiphertext(), "候选人身份已失效"));
        int updated = jdbc.update("""
                update wecom_person_onboarding
                set status = 'REJECTED', decision_reason = :reason,
                    failure_code = 'REJECTED_BY_REVIEWER', reviewed_by = :actorId,
                    reviewed_at = now(), user_id_ciphertext = null,
                    invitation_token_hash = null, invitation_issued_at = null,
                    invitation_expires_at = null, oauth_state_hash = null,
                    browser_verifier_hash = null, provider_code_hash = null,
                    exchange_code_hash = null, exchange_expires_at = null,
                    session_token_hash = null, session_expires_at = null,
                    requested_password_hash = null,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :candidateId
                  and row_version = :expectedVersion
                  and status in ('PENDING_APPROVAL','CONFLICT')
                """, base(principal).addValue("candidateId", candidate.id())
                .addValue("expectedVersion", candidate.rowVersion()).addValue("reason", reason));
        if (updated != 1) throw stale();
        notifyGovernance(principal, candidate.id(), "WECOM_ONBOARDING_REJECTED",
                "企业微信入职申请已拒绝",
                "一项入职绑定申请已完成拒绝处理。", "rejected");
        auditWriter.record("WECOM_ONBOARDING_REJECTED", "WECOM_PERSON_ONBOARDING",
                candidate.id(), json(Map.of(
                        "fingerprint", mask(candidate.fingerprint()),
                        "reasonProvided", true,
                        "reasonCode", "REJECTED_BY_REVIEWER"
                )));
        sendEmployeeResultAfterCommit(userId, "中台入职申请未通过",
                "申请未通过；为保护人员信息，详细审核意见仅保存在受控审批记录中，请联系管理员处理。", false);
        return new ApprovalResponse(candidate.id(), null, "REJECTED", null,
                candidate.rowVersion() + 1, "申请已拒绝");
    }

    private String candidateFingerprint(TenantPrincipal principal, UUID candidateId) {
        List<String> fingerprints = jdbc.queryForList("""
                select user_id_fingerprint
                from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId and id = :candidateId
                """, base(principal).addValue("candidateId", candidateId), String.class);
        if (fingerprints.size() != 1) throw new IllegalArgumentException("入职申请不存在");
        return fingerprints.getFirst();
    }

    private void lockOnboardingIdentity(TenantPrincipal principal, String fingerprint) {
        jdbc.queryForObject("""
                select pg_advisory_xact_lock(
                    hashtext(cast(:tenantId as text)),
                    hashtext(:corpId || ':' || :fingerprint))
                """, base(principal).addValue("fingerprint", fingerprint), Object.class);
    }

    private boolean hasNewerOpenCandidate(TenantPrincipal principal, Candidate candidate) {
        List<UUID> open = jdbc.query("""
                select id from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and user_id_fingerprint = :fingerprint and id <> :candidateId
                  and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                for update
                """, base(principal).addValue("fingerprint", candidate.fingerprint())
                .addValue("candidateId", candidate.id()),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return !open.isEmpty();
    }

    /**
     * Approval must never race ahead of a directory fact that is already
     * durable.  Receipt reservation and approval share the same per-identity
     * advisory lock; after acquiring it, any newer receipt is authoritative
     * even when its worker is still processing or has entered a retry/dead
     * letter state.
     */
    private void requireLatestDirectoryFact(TenantPrincipal principal, Candidate candidate) {
        DirectoryWatermark newer = latestDirectoryFactAfterCandidate(principal, candidate);
        if (newer == null) return;
        String status = newer.status();
        if (Set.of("FAILED", "DEAD_LETTER").contains(status)) {
            throw new IllegalArgumentException(
                    "企业微信人员同步异常，请先处理最新同步故障后再审批");
        }
        if ("PROCESSING".equals(status)) {
            throw new IllegalArgumentException(
                    "企业微信人员状态正在同步，请稍后刷新后再审批");
        }
        throw new IllegalArgumentException("企业微信人员状态已变化，请刷新并处理最新申请");
    }

    private DirectoryWatermark latestDirectoryFactAfterCandidate(
            TenantPrincipal principal, Candidate candidate
    ) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("fingerprint", candidate.fingerprint());
        List<DirectoryWatermark> newer;
        if (candidate.lastEventReceiptId() != null) {
            newer = jdbc.query("""
                    select receipt.id, receipt.status
                    from wecom_directory_event_receipt candidate_receipt
                    join wecom_directory_event_receipt receipt
                      on receipt.tenant_id = candidate_receipt.tenant_id
                     and receipt.corp_id = candidate_receipt.corp_id
                     and receipt.id <> candidate_receipt.id
                    where candidate_receipt.tenant_id = :tenantId
                      and candidate_receipt.corp_id = :corpId
                      and candidate_receipt.id = :candidateReceiptId
                      and (receipt.user_id_fingerprint = :fingerprint
                           or receipt.previous_user_id_fingerprint = :fingerprint)
                      and (
                        receipt.occurred_at > candidate_receipt.occurred_at
                        or (receipt.occurred_at = candidate_receipt.occurred_at
                            and receipt.event_priority > candidate_receipt.event_priority)
                        or (receipt.occurred_at = candidate_receipt.occurred_at
                            and receipt.event_priority = candidate_receipt.event_priority
                            and receipt.received_at > candidate_receipt.received_at)
                        or (receipt.occurred_at = candidate_receipt.occurred_at
                            and receipt.event_priority = candidate_receipt.event_priority
                            and receipt.received_at = candidate_receipt.received_at
                            and receipt.id::text > candidate_receipt.id::text)
                      )
                    order by receipt.occurred_at desc, receipt.event_priority desc,
                             receipt.received_at desc, receipt.id desc
                    limit 1
                    for update of receipt
                    """, parameters.addValue("candidateReceiptId", candidate.lastEventReceiptId()),
                    (rs, rowNum) -> new DirectoryWatermark(
                            rs.getObject("id", UUID.class), rs.getString("status")));
        } else {
            newer = jdbc.query("""
                    select receipt.id, receipt.status
                    from wecom_directory_event_receipt receipt
                    where receipt.tenant_id = :tenantId and receipt.corp_id = :corpId
                      and (receipt.user_id_fingerprint = :fingerprint
                           or receipt.previous_user_id_fingerprint = :fingerprint)
                      and receipt.occurred_at >= :candidateEventAt
                    order by receipt.occurred_at desc, receipt.event_priority desc,
                             receipt.received_at desc, receipt.id desc
                    limit 1
                    for update of receipt
                    """, parameters.addValue("candidateEventAt", candidate.lastEventAt()),
                    (rs, rowNum) -> new DirectoryWatermark(
                            rs.getObject("id", UUID.class), rs.getString("status")));
        }
        return newer.isEmpty() ? null : newer.getFirst();
    }

    private InvitationActionResponse cancelSupersededInvitation(
            TenantPrincipal principal, Candidate candidate
    ) {
        DirectoryWatermark newer = latestDirectoryFactAfterCandidate(principal, candidate);
        if (newer == null) return null;
        int updated = jdbc.update("""
                update wecom_person_onboarding
                set status = 'CANCELLED', user_id_ciphertext = null,
                    expired_at = null,
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
                    failure_code = 'SUPERSEDED_BY_DIRECTORY_EVENT',
                    decision_reason = '企业微信人员目录状态已更新',
                    row_version = row_version + 1
                where tenant_id = :tenantId and corp_id = :corpId and id = :candidateId
                  and row_version = :expectedVersion
                  and status in ('WAITING_PROFILE','EXPIRED')
                """, base(principal).addValue("candidateId", candidate.id())
                .addValue("expectedVersion", candidate.rowVersion()));
        if (updated != 1) throw stale();
        auditWriter.record("WECOM_ONBOARDING_SUPERSEDED", "WECOM_PERSON_ONBOARDING",
                candidate.id(), json(Map.of(
                        "failureCode", "SUPERSEDED_BY_DIRECTORY_EVENT",
                        "directoryReceiptStatus", newer.status(),
                        "fingerprint", mask(candidate.fingerprint()),
                        "tokenExposed", false
                )));
        return new InvitationActionResponse(candidate.id(), "CANCELLED", null,
                candidate.rowVersion() + 1,
                "人员目录状态已更新，旧申请已作废；请处理最新人员申请");
    }

    private Candidate lockCandidate(TenantPrincipal principal, UUID candidateId) {
        List<Candidate> rows = jdbc.query("""
                select id, status, display_name, requested_display_name,
                       requested_mobile, requested_login_name, requested_password_hash,
                       user_id_fingerprint, user_id_ciphertext,
                       onboarding_kind, invitation_source, source_binding_id, source_account_id,
                       directory_status, failure_code, invitation_expires_at,
                       requested_org_unit_id, requested_position_id, conflicting_account_id,
                       account_id, directory_assignment_snapshot_hash,
                       last_event_at, last_event_receipt_id, row_version
                from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId and id = :candidateId
                for update
                """, base(principal).addValue("candidateId", candidateId), (rs, rowNum) -> new Candidate(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("display_name"), rs.getString("requested_display_name"),
                rs.getString("requested_mobile"), rs.getString("requested_login_name"),
                rs.getString("requested_password_hash"),
                rs.getString("user_id_fingerprint"),
                rs.getString("user_id_ciphertext"),
                rs.getString("onboarding_kind"),
                rs.getString("invitation_source"),
                rs.getObject("source_binding_id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getString("directory_status"), rs.getString("failure_code"),
                rs.getObject("invitation_expires_at", OffsetDateTime.class),
                rs.getObject("requested_org_unit_id", UUID.class),
                rs.getObject("requested_position_id", UUID.class),
                rs.getObject("conflicting_account_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("directory_assignment_snapshot_hash"),
                rs.getObject("last_event_at", OffsetDateTime.class),
                rs.getObject("last_event_receipt_id", UUID.class), rs.getLong("row_version")
        ));
        if (rows.size() != 1) throw new IllegalArgumentException("入职申请不存在");
        return rows.getFirst();
    }

    private SourceIdentity lockSourceIdentity(TenantPrincipal principal, Candidate candidate) {
        if (candidate.sourceBindingId() == null || candidate.sourceAccountId() == null) {
            throw new IllegalArgumentException("既有员工变更缺少原绑定身份，请重新发起申请");
        }
        List<SourceIdentity> rows = jdbc.query("""
                select binding.id as binding_id, binding.account_id,
                       employee.id as employee_id, binding.preferred_assignment_id,
                       binding.row_version as binding_version, binding.status,
                       binding.status_reason, binding.identity_event_receipt_id
                from wecom_user_binding binding
                join user_account account
                  on account.tenant_id = binding.tenant_id and account.id = binding.account_id
                 and account.status = 'ACTIVE'
                join employee
                  on employee.tenant_id = binding.tenant_id and employee.account_id = binding.account_id
                 and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                where binding.tenant_id = :tenantId and binding.corp_id = :corpId
                  and binding.id = :sourceBindingId and binding.account_id = :sourceAccountId
                  and (binding.status in ('ACTIVE','SUSPENDED')
                       or (binding.status = 'REVOKED'
                           and binding.status_reason = 'IDENTITY_TRANSFERRED_BY_ONBOARDING'))
                for update of binding, account, employee
                """, base(principal).addValue("sourceBindingId", candidate.sourceBindingId())
                .addValue("sourceAccountId", candidate.sourceAccountId()), (rs, rowNum) -> new SourceIdentity(
                rs.getObject("binding_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("preferred_assignment_id", UUID.class),
                rs.getLong("binding_version"), rs.getString("status"),
                rs.getString("status_reason"),
                rs.getObject("identity_event_receipt_id", UUID.class)
        ));
        if (rows.size() != 1 || rows.getFirst().preferredAssignmentId() == null) {
            throw new IllegalArgumentException("原账号、员工或默认任职已失效，请在异常绑定中处理");
        }
        return rows.getFirst();
    }

    private static boolean sourceBindingCanActivate(
            Candidate candidate, SourceIdentity sourceIdentity
    ) {
        if (!"SUSPENDED".equals(sourceIdentity.status())) return true;
        if (candidate.lastEventReceiptId() == null
                || !candidate.lastEventReceiptId().equals(sourceIdentity.identityEventReceiptId())) {
            return false;
        }
        return ("ASSIGNMENT_CHANGE".equals(candidate.onboardingKind())
                && "DIRECTORY_ASSIGNMENT_CHANGED".equals(sourceIdentity.statusReason()))
                || ("USER_ID_CHANGE".equals(candidate.onboardingKind())
                && "DIRECTORY_USER_ID_CHANGED".equals(sourceIdentity.statusReason()));
    }

    private void retireSourceAssignmentAndRole(
            TenantPrincipal principal, SourceIdentity sourceIdentity
    ) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("accountId", sourceIdentity.accountId())
                .addValue("employeeId", sourceIdentity.employeeId())
                .addValue("assignmentId", sourceIdentity.preferredAssignmentId());
        jdbc.update("""
                update role_assignment role_grant
                set valid_to = greatest(role_grant.valid_from, now())
                where role_grant.tenant_id = :tenantId
                  and role_grant.account_id = :accountId
                  and role_grant.source_type = 'POSITION_ASSIGNMENT'
                  and role_grant.source_assignment_id = :assignmentId
                  and (role_grant.valid_to is null or role_grant.valid_to > now())
                """, parameters);
        int retired = jdbc.update("""
                update employee_position_assignment
                set status = 'INACTIVE', is_primary = false,
                    valid_to = case
                        when valid_to is null or valid_to > greatest(valid_from, current_date)
                        then greatest(valid_from, current_date) else valid_to end,
                    updated_at = now()
                where tenant_id = :tenantId and id = :assignmentId
                  and employee_id = :employeeId and status = 'ACTIVE'
                """, parameters);
        if (retired != 1) {
            throw new IllegalArgumentException("原默认任职已失效，请刷新后重新处理");
        }
    }

    private void revokeTransferredBinding(TenantPrincipal principal, BindingConflict conflict) {
        int revoked = jdbc.update("""
                update wecom_user_binding
                set status = 'REVOKED', wecom_user_id = 'revoked:' || id::text,
                    status_reason = 'IDENTITY_TRANSFERRED_BY_ONBOARDING',
                    updated_by = :actorId, row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenantId and corp_id = :corpId and id = :bindingId
                  and account_id = :accountId and row_version = :bindingVersion
                  and status in ('ACTIVE','SUSPENDED')
                """, base(principal).addValue("bindingId", conflict.id())
                .addValue("accountId", conflict.accountId())
                .addValue("bindingVersion", conflict.rowVersion()));
        if (revoked != 1) throw stale();
    }

    private PositionGrant resolvePositionGrant(
            TenantPrincipal principal, UUID orgUnitId, UUID positionId, boolean canManageAssignments
    ) {
        List<PositionGrant> rows = jdbc.query("""
                select group_profile.default_role_id,
                       coalesce(hotel_version.authorization_scope_type,
                                group_version.authorization_scope_type) as authorization_scope_type,
                       coalesce(hotel_version.wecom_self_selectable,
                                group_version.wecom_self_selectable) as wecom_self_selectable,
                       ((assignment_org.unit_type = 'GROUP' and :canManageAssignments)
                        or (coalesce(hotel_version.authorization_scope_type,
                                 group_version.authorization_scope_type) <> 'TENANT'
                        and not exists (
                          select 1
                          from role_permission role_grant
                          join permission protected_permission
                            on protected_permission.id = role_grant.permission_id
                          where role_grant.tenant_id = group_profile.tenant_id
                            and role_grant.role_id = group_profile.default_role_id
                            and protected_permission.delegable_to_position = false
                        ))) as reviewer_assignable
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
                  and (
                    (assignment_org.unit_type = 'GROUP'
                     and position.job_family = 'GROUP_MANAGEMENT'
                     and (:canManageAssignments or (
                         group_version.authorization_scope_type <> 'TENANT'
                         and not exists (
                           select 1
                           from role_permission protected_grant
                           join permission protected_permission
                             on protected_permission.id = protected_grant.permission_id
                           where protected_grant.tenant_id = group_profile.tenant_id
                             and protected_grant.role_id = group_profile.default_role_id
                             and protected_permission.delegable_to_position = false
                         )
                     )))
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
                for update of assignment_org, position, group_profile, group_version
                """, base(principal).addValue("orgUnitId", orgUnitId)
                .addValue("positionId", positionId)
                .addValue("canManageAssignments", canManageAssignments), (rs, rowNum) -> new PositionGrant(
                rs.getObject("default_role_id", UUID.class),
                rs.getString("authorization_scope_type"),
                rs.getBoolean("wecom_self_selectable"),
                rs.getBoolean("reviewer_assignable")
        ));
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private List<BindingConflict> bindingConflicts(TenantPrincipal principal, String userId) {
        return jdbc.query("""
                select id, account_id, status, row_version
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId and wecom_user_id = :userId
                for update
                """, base(principal).addValue("userId", userId), (rs, rowNum) -> new BindingConflict(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("status"), rs.getLong("row_version")
        ));
    }

    private long markConflict(TenantPrincipal principal, Candidate candidate, UUID accountId) {
        int updated = jdbc.update("""
                update wecom_person_onboarding
                set status = 'CONFLICT', conflicting_account_id = :conflictingAccountId,
                    failure_code = 'USER_ID_ALREADY_BOUND', row_version = row_version + 1
                where tenant_id = :tenantId and id = :candidateId
                  and row_version = :expectedVersion
                  and status in ('PENDING_APPROVAL','CONFLICT')
                """, base(principal).addValue("candidateId", candidate.id())
                .addValue("expectedVersion", candidate.rowVersion())
                .addValue("conflictingAccountId", accountId));
        if (updated != 1) throw stale();
        return candidate.rowVersion() + 1;
    }

    private long markFailure(TenantPrincipal principal, Candidate candidate, String code) {
        int updated = jdbc.update("""
                update wecom_person_onboarding
                set failure_code = :failureCode, row_version = row_version + 1
                where tenant_id = :tenantId and id = :candidateId
                  and row_version = :expectedVersion
                  and status in ('PENDING_APPROVAL','CONFLICT')
                """, base(principal).addValue("candidateId", candidate.id())
                .addValue("expectedVersion", candidate.rowVersion()).addValue("failureCode", code));
        if (updated != 1) throw stale();
        auditWriter.record("WECOM_ONBOARDING_APPROVAL_BLOCKED", "WECOM_PERSON_ONBOARDING",
                candidate.id(), json(Map.of(
                        "failureCode", code,
                        "fingerprint", mask(candidate.fingerprint())
                )));
        return candidate.rowVersion() + 1;
    }

    private void notifyAccount(
            TenantPrincipal principal, UUID accountId, UUID sourceId, String type,
            String title, String content, String suffix
    ) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                values
                    (:tenantId, :accountId, :type, :title, :content,
                     'WECOM_PERSON_ONBOARDING', :sourceId, :key)
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, base(principal).addValue("accountId", accountId).addValue("sourceId", sourceId)
                .addValue("type", type).addValue("title", title).addValue("content", content)
                .addValue("key", "wecom-onboarding:" + sourceId + ":" + suffix));
    }

    private void notifyGovernance(
            TenantPrincipal principal, UUID sourceId, String type,
            String title, String content, String suffix
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
                """, base(principal).addValue("sourceId", sourceId).addValue("type", type)
                .addValue("title", title).addValue("content", content).addValue("suffix", suffix));
    }

    private void sendEmployeeResultAfterCommit(
            String userId, String title, String description, boolean authenticatedWorkbench
    ) {
        Runnable send = () -> {
            try {
                URI link = properties.frontendBaseUrl();
                if (authenticatedWorkbench) {
                    String base = link.toString().replaceAll("/+$", "");
                    link = UriComponentsBuilder
                            .fromUriString(base + "/api/v1/integrations/wecom/oauth/start")
                            .queryParam("returnTo", "#/workbench")
                            .build().encode().toUri();
                }
                apiClient.sendApplicationTaskLink(userId, title, description, link);
            } catch (RuntimeException ignored) {
                // Provider exceptions are sanitized by WeComApiClient. The durable
                // administrative notification and audit record remain authoritative.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private TenantPrincipal prepare(String permission) {
        accessPolicy.requirePermission(permission);
        TenantPrincipal principal = accessPolicy.principal();
        return prepareTenant(principal);
    }

    private TenantPrincipal prepareAny(String... permissions) {
        accessPolicy.requireAnyPermission(permissions);
        return prepareTenant(accessPolicy.principal());
    }

    private static boolean canManageAssignments(TenantPrincipal principal) {
        return principal.hasPermission("org.manage") || principal.hasPermission("*");
    }

    private TenantPrincipal prepareTenant(TenantPrincipal principal) {
        if (!properties.tenantId().equals(principal.tenantId())) {
            throw new IllegalArgumentException("企业微信目录同步与当前租户不匹配");
        }
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId())
                .addValue("corpId", properties.corpId())
                .addValue("actorId", principal.actorId());
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CANDIDATE_STATES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的入职申请状态");
        }
        return normalized;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("审计数据序列化失败"); }
    }

    private static void requireVersion(Candidate candidate, long expectedVersion) {
        if (candidate.rowVersion() != expectedVersion) throw stale();
    }

    private static UUID scopeOrgUnitId(String scopeType, UUID requestedOrgUnitId) {
        return Set.of("ORG_UNIT", "ORG_TREE").contains(scopeType) ? requestedOrgUnitId : null;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String safeReason(String value) {
        String reason = trimToNull(value);
        return reason == null ? "管理员人工恢复" : reason;
    }

    private static String suggestedAction(
            String status, String failureCode, boolean expiringSoon, String invitationSource
    ) {
        if ("EXPIRED".equals(status) && "MANUAL_LINK".equals(invitationSource)) {
            return "本次填写会话已过期。如共享二维码仍在有效期，可重新扫码；"
                    + "否则请点击一键邀请生成新码";
        }
        if ("EXPIRED".equals(status)) return "管理员重新生成邀请；原链接不可恢复";
        if (failureCode != null && TECHNICAL_RETRY_FAILURES.contains(failureCode)) {
            return "管理员点击重试；系统会轮换凭据并发送新的120分钟邀请";
        }
        if ("USER_ID_ALREADY_BOUND".equals(failureCode) || "CONFLICT".equals(status)) {
            return "核对原账号后执行二次确认转移，不能使用技术重试";
        }
        if ("OAUTH_IDENTITY_MISMATCH".equals(failureCode)) {
            return "请员工使用收到邀请的同一企业微信账号重新验证";
        }
        if ("POSITION_ROLE_NOT_CONFIGURED".equals(failureCode)) {
            return "先发布岗位权限方案，再重新确认审批";
        }
        if ("POSITION_SELECTION_NO_LONGER_VALID".equals(failureCode)) {
            return "请员工重新选择当前有效的门店和岗位";
        }
        if (expiringSoon) return "邀请将在30分钟内过期，请提醒员工尽快完成";
        return switch (status) {
            case "WAITING_PROFILE" -> "等待员工通过企业微信完成门店和岗位申请";
            case "PENDING_APPROVAL" -> "管理员核对后确认启用或拒绝";
            case "APPROVED" -> "已完成，无需处理";
            case "REJECTED" -> "如需重新申请，请等待新的人员变更事件或联系管理员";
            case "CANCELLED" -> "企业微信成员状态已变化，请核对通讯录";
            default -> "请刷新状态后按提示处理";
        };
    }

    private static String directoryErrorMessage(String errorCode) {
        if ("PROCESS_INTERRUPTED_AFTER_RETRY_LIMIT".equals(errorCode)) {
            return "同步处理进程在达到重试上限后中断";
        }
        if (errorCode == null || errorCode.isBlank()) {
            return "企业微信人员同步发生未知技术异常";
        }
        return switch (errorCode) {
            case "DATAACCESSRESOURCEFAILUREEXCEPTION" -> "数据库暂时不可用";
            case "RESTCLIENTEXCEPTION", "RESOURCETIMEOUTEXCEPTION" -> "企业微信接口暂时不可用";
            case "ILLEGALARGUMENTEXCEPTION" -> "同步事件内容或配置校验失败";
            default -> "企业微信人员同步连续失败，请核对配置后重试";
        };
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private boolean loginExists(TenantPrincipal principal, String loginName) {
        Integer count = jdbc.queryForObject("""
                select count(*) from user_account
                where tenant_id = :tenantId and lower(login_name) = lower(:loginName)
                """, base(principal).addValue("loginName", loginName), Integer.class);
        return count != null && count > 0;
    }

    private boolean mobileExists(TenantPrincipal principal, String mobile) {
        Integer count = jdbc.queryForObject("""
                select count(*) from user_account
                where tenant_id = :tenantId
                  and regexp_replace(mobile, '[^0-9]', '', 'g')
                      in (:mobile, '86' || :mobile, '0086' || :mobile)
                """, base(principal).addValue("mobile", mobile), Integer.class);
        return count != null && count > 0;
    }

    private static boolean isMobileConflict(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains("ux_user_account_tenant_mobile");
    }

    private static IllegalArgumentException stale() {
        return new IllegalArgumentException("申请已变化，请刷新后重试");
    }

    private static String mask(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) return null;
        String value = fingerprint.toUpperCase(Locale.ROOT);
        return value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
    }

    private record Candidate(
            UUID id, String status, String displayName,
            String requestedDisplayName, String requestedMobile,
            String requestedLoginName, String requestedPasswordHash,
            String fingerprint,
            String userIdCiphertext, String onboardingKind,
            String invitationSource,
            UUID sourceBindingId, UUID sourceAccountId,
            String directoryStatus, String failureCode, OffsetDateTime invitationExpiresAt,
            UUID orgUnitId, UUID positionId,
            UUID conflictingAccountId, UUID accountId, String directoryAssignmentSnapshotHash,
            OffsetDateTime lastEventAt, UUID lastEventReceiptId, long rowVersion
    ) { }

    private record PositionGrant(
            UUID roleId, String scopeType, boolean selfSelectable, boolean reviewerAssignable
    ) { }
    private record ReviewCandidate(
            UUID id, String status, UUID hotelId, String hotelName, String unitType
    ) { }
    private record BindingConflict(UUID id, UUID accountId, String status, long rowVersion) { }
    private record SourceIdentity(
            UUID bindingId, UUID accountId, UUID employeeId,
            UUID preferredAssignmentId, long bindingVersion,
            String status, String statusReason, UUID identityEventReceiptId
    ) { }
    private record DirectoryReceipt(
            UUID id, String status, UUID correlationId, long rowVersion, boolean payloadAvailable
    ) { }
    private record DirectoryWatermark(UUID id, String status) { }
}
