package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.PilotPasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.ApprovalResponse;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.DirectoryEventRetryResponse;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.DecisionRequest;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.InvitationActionResponse;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.OpenInvitationResponse;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.RetryRequest;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.SubmitRequest;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComDirectoryOnboardingModels.SubmitResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComDirectoryOnboardingAdministrationServiceIntegrationTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CEO = UUID.fromString("19000000-0000-0000-0000-000000000001");
    private static final UUID FRONT_ACCOUNT = UUID.fromString("19000000-0000-0000-0000-000000000003");
    private static final UUID GROUP_HEADQUARTERS = UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000004");
    private static final UUID FRONT_DEPARTMENT = UUID.fromString("12000000-0000-0000-0000-000000000005");
    private static final UUID HOUSEKEEPING_DEPARTMENT = UUID.fromString("12000000-0000-0000-0000-000000000006");
    private static final UUID FRONT_POSITION = UUID.fromString("14000000-0000-0000-0000-000000000001");
    private static final UUID HOUSEKEEPING_POSITION = UUID.fromString("14000000-0000-0000-0000-000000000002");
    private static final String CORP_ID = "ww-directory-integration-test";
    private static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String TEST_PASSWORD_HASH = new PilotPasswordHasher().hash("Directory-Test-Password-2026");

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private WeComDirectorySecretCodec codec;
    private WeComDirectoryProperties properties;
    private WeComDirectoryOnboardingAdministrationService service;
    private WeComDirectoryOnboardingService employeeService;
    private WeComDirectoryEventProcessor eventProcessor;
    private AccessPolicy accessPolicy;

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(DATA_SOURCE);
        transactionManager = new DataSourceTransactionManager(DATA_SOURCE);
        jdbc.update("delete from wecom_person_onboarding where corp_id = ?", CORP_ID);
        jdbc.update("delete from wecom_open_onboarding_invitation where corp_id = ?", CORP_ID);
        jdbc.update("delete from wecom_user_binding where corp_id = ?", CORP_ID);
        jdbc.update("delete from wecom_directory_event_receipt where corp_id = ?", CORP_ID);

        TenantPrincipal principal = new TenantPrincipal(
                TENANT, CEO, "CEO", Set.of("CEO"), Set.of("wecom-binding.approve"),
                Set.of(), Set.of(), true, UUID.randomUUID());
        accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.principal()).thenReturn(principal);

        properties = mock(WeComDirectoryProperties.class);
        when(properties.tenantId()).thenReturn(TENANT);
        when(properties.corpId()).thenReturn(CORP_ID);
        when(properties.encryptionKey()).thenReturn(ENCRYPTION_KEY);
        when(properties.frontendBaseUrl()).thenReturn(URI.create("https://www.sfgzt.cn"));
        when(properties.oauthCallbackUrl()).thenReturn(URI.create(
                "https://www.sfgzt.cn/api/v1/integrations/wecom/directory-onboarding/oauth/callback"));
        when(properties.agentId()).thenReturn(1000002L);
        when(properties.invitationTtl()).thenReturn(Duration.ofMinutes(120));
        when(properties.sharedInvitationTtl()).thenReturn(Duration.ofDays(7));
        when(properties.exchangeTtl()).thenReturn(Duration.ofMinutes(2));
        when(properties.sessionTtl()).thenReturn(Duration.ofMinutes(20));
        codec = new WeComDirectorySecretCodec(properties);
        employeeService = mock(WeComDirectoryOnboardingService.class);
        eventProcessor = mock(WeComDirectoryEventProcessor.class);
        when(employeeService.newInvitationToken())
                .thenAnswer(ignored -> "integration-" + UUID.randomUUID());

        service = new WeComDirectoryOnboardingAdministrationService(
                new NamedParameterJdbcTemplate(DATA_SOURCE),
                mock(TenantDatabaseContext.class),
                accessPolicy,
                mock(AuditWriter.class),
                mock(cn.sifangguan.hotelaios.workpackage.WorkPackageService.class),
                new ObjectMapper(),
                properties,
                codec,
                mock(WeComApiClient.class),
                employeeService,
                Optional.of(eventProcessor));

        publishSelectableProfile(FRONT_POSITION);
        publishSelectableProfile(HOUSEKEEPING_POSITION);
    }

    @Test
    void manualInvitationCreatesAReusableRegistrationLinkWithoutAnApplicant() {
        Counts before = identityCounts();

        OpenInvitationResponse response = inTransaction(service::createOpenInvitation);

        assertThat(response.enrollmentUrl().toString())
                .startsWith("https://www.sfgzt.cn/#/wecom-onboarding?token=");
        assertThat(response.status()).isEqualTo("WAITING_PROFILE");
        assertThat(response.message()).contains("员工").contains("自行填写");
        assertThat(response.message()).contains("多人").contains("独立");
        assertThat(jdbc.queryForMap("""
                select created_by, expires_at > now() as active,
                       token_hash is not null as has_token
                from wecom_open_onboarding_invitation where id = ?
                """, response.candidateId()))
                .containsEntry("created_by", CEO)
                .containsEntry("active", true)
                .containsEntry("has_token", true);
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where open_invitation_id = ?
                """, Integer.class, response.candidateId())).isZero();
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void sharedInvitationLastsSevenDaysButEachEmployeeCredentialStaysShortLived() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        assertThat(jdbc.queryForObject("""
                select expires_at - issued_at = interval '7 days'
                from wecom_open_onboarding_invitation where id = ?
                """, Boolean.class, invitation.candidateId())).isTrue();
        // A QR code forwarded the previous evening must still work the next day.
        jdbc.update("""
                update wecom_open_onboarding_invitation
                set issued_at = now() - interval '1 day',
                    expires_at = now() + interval '6 days'
                where id = ?
                """, invitation.candidateId());
        UUID candidateId = startReusableCandidate(invitation, lifecycleService());
        assertThat(jdbc.queryForObject("""
                select invitation_expires_at - invitation_issued_at = interval '120 minutes'
                from wecom_person_onboarding where id = ?
                """, Boolean.class, candidateId)).isTrue();
    }

    @Test
    void employeeCredentialCannotOutliveSharedInvitation() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        jdbc.update("""
                update wecom_open_onboarding_invitation
                set issued_at = now() - interval '6 days',
                    expires_at = now() + interval '3 minutes'
                where id = ?
                """, invitation.candidateId());
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        var start = inTransaction(() -> lifecycle.start(openInvitationToken(invitation)));
        assertThat(start.maxAgeSeconds()).isBetween(1L, 180L);
        assertThat(jdbc.queryForObject("""
                select p.invitation_expires_at = i.expires_at
                from wecom_person_onboarding p
                join wecom_open_onboarding_invitation i on i.id = p.open_invitation_id
                where i.id = ?
                """, Boolean.class, invitation.candidateId())).isTrue();
    }

    @Test
    void regeneratingCreatesANewLinkWithoutRevivingExpiredQrCodes() {
        OpenInvitationResponse expired = inTransaction(service::createOpenInvitation);
        jdbc.update("""
                update wecom_open_onboarding_invitation
                set issued_at = now() - interval '8 days',
                    expires_at = now() - interval '1 day'
                where id = ?
                """, expired.candidateId());
        OpenInvitationResponse replacement = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        assertThat(replacement.enrollmentUrl()).isNotEqualTo(expired.enrollmentUrl());
        assertThatThrownBy(() -> inTransaction(() -> lifecycle.start(openInvitationToken(expired))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重新生成二维码");
        startReusableCandidate(replacement, lifecycle);
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding where open_invitation_id = ?
                """, Integer.class, expired.candidateId())).isZero();
    }

    @Test
    void sharedQrRemainsUsableAfterAnIndividualScanSessionExpires() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        UUID firstCandidate = startReusableCandidate(invitation, lifecycle);
        jdbc.update("""
                update wecom_person_onboarding
                set invitation_issued_at = now() - interval '3 hours',
                    invitation_expires_at = now() - interval '1 hour'
                where id = ?
                """, firstCandidate);
        inTransaction(() -> { lifecycle.expireDueInvitations(); return null; });
        assertThat(jdbc.queryForObject("select status from wecom_person_onboarding where id = ?",
                String.class, firstCandidate)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForList("""
                select content from notification
                where source_id = ? and notification_type = 'WECOM_ONBOARDING_INVITATION_EXPIRED'
                """, String.class, firstCandidate))
                .isNotEmpty()
                .allSatisfy(content -> assertThat(content)
                        .contains("共享二维码仍在有效期")
                        .doesNotContain("原链接已失效"));
        assertThat(startReusableCandidate(invitation, lifecycle)).isNotEqualTo(firstCandidate);
    }

    @Test
    void reusableInvitationCreatesIndependentConcurrentApplicantsAndRemainsValid() throws Exception {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        String token = openInvitationToken(invitation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> scan = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            inTransaction(() -> lifecycle.start(token));
            return null;
        };
        try {
            Future<Void> first = executor.submit(scan);
            Future<Void> second = executor.submit(scan);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForList("""
                select id from wecom_person_onboarding
                where open_invitation_id = ? order by id
                """, UUID.class, invitation.candidateId()))
                .hasSize(2)
                .doesNotHaveDuplicates();
        assertThat(jdbc.queryForMap("""
                select expires_at > now() as active,
                       token_hash is not null as reusable_token_preserved
                from wecom_open_onboarding_invitation where id = ?
                """, invitation.candidateId()))
                .containsEntry("active", true)
                .containsEntry("reusable_token_preserved", true);
        assertThat(jdbc.queryForObject("""
                select count(distinct oauth_state_hash) = 2
                from wecom_person_onboarding where open_invitation_id = ?
                """, Boolean.class, invitation.candidateId())).isTrue();
    }

    @Test
    void manualInvitationAdoptsVerifiedWeComIdentityWithoutPrefillingProfile() {
        OpenInvitationResponse response = inTransaction(service::createOpenInvitation);
        String userId = "manual-onboarding-" + UUID.randomUUID();
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        UUID candidateId = startReusableCandidate(response, lifecycle);

        URI redirect = inTransaction(() -> lifecycle.completeOAuth(candidateId, userId));

        assertThat(redirect.toString())
                .startsWith("https://www.sfgzt.cn/#/wecom-onboarding?exchange_code=");
        var row = jdbc.queryForMap("""
                select user_id_fingerprint, user_id_ciphertext,
                       identity_verified_at is not null as verified,
                       requested_display_name, requested_login_name,
                       requested_org_unit_id, requested_position_id
                from wecom_person_onboarding where id = ?
                """, candidateId);
        assertThat(row)
                .containsEntry("user_id_fingerprint", codec.fingerprint(userId))
                .containsEntry("verified", true)
                .containsEntry("requested_display_name", null)
                .containsEntry("requested_login_name", null)
                .containsEntry("requested_org_unit_id", null)
                .containsEntry("requested_position_id", null);
        assertThat(codec.decrypt((String) row.get("user_id_ciphertext"))).isEqualTo(userId);
    }

    @Test
    void registrationHidesUnavailablePositionsAndLetsReviewerAssignAPendingPosition() {
        jdbc.update("update org_unit set status = 'ACTIVE' where tenant_id = ? and id = ?",
                TENANT, SECOND_HOTEL);
        try {
            OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
            WeComDirectoryOnboardingService lifecycle = lifecycleService();
            UUID candidateId = startReusableCandidate(invitation, lifecycle);
            inTransaction(() -> lifecycle.completeOAuth(
                    candidateId, "hotel-direct-" + UUID.randomUUID()));
            String session = "hotel-direct-session-" + UUID.randomUUID();
            jdbc.update("""
                    update wecom_person_onboarding
                    set session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                    where id = ?
                    """, WeComDirectorySecretCodec.sha256(session), candidateId);

            var context = inTransaction(() -> lifecycle.context(session));
            var hotel = context.hotels().stream()
                    .filter(option -> SECOND_HOTEL.equals(option.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(hotel.departments()).singleElement().satisfies(group -> {
                assertThat(group.id()).isEqualTo(SECOND_HOTEL);
                assertThat(group.positions())
                        .extracting(WeComDirectoryOnboardingModels.PositionOption::id)
                        .contains(FRONT_POSITION);
                assertThat(group.positions())
                        .filteredOn(position -> "前台员工".equals(position.name()))
                        .singleElement()
                        .satisfies(position -> assertThat(position.selectable()).isTrue());
                assertThat(group.positions())
                        .noneMatch(position -> "OTA运营经理".equals(position.name()));
            });

            SubmitResponse submitted = inTransaction(() -> lifecycle.submit(new SubmitRequest(
                    session, "Hotel direct employee", "13900004601",
                    "hotel.direct." + candidateId,
                    "Directory-Test-Password-2026", "Directory-Test-Password-2026",
                    SECOND_HOTEL, null, context.rowVersion())));

            assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
            assertThat(uuidValue("select requested_org_unit_id from wecom_person_onboarding where id = ?",
                    candidateId)).isEqualTo(SECOND_HOTEL);
            assertThat(jdbc.queryForObject(
                    "select requested_position_id is null from wecom_person_onboarding where id = ?",
                    Boolean.class, candidateId)).isTrue();

            assertThat(inTransaction(() -> service.list(null)).items())
                    .filteredOn(candidate -> candidateId.equals(candidate.id()))
                    .singleElement()
                    .satisfies(candidate -> {
                        assertThat(candidate.status()).isEqualTo("PENDING_APPROVAL");
                        assertThat(candidate.requestedHotelName()).isEqualTo("上海滨江店");
                        assertThat(candidate.requestedDepartmentName()).isNull();
                        assertThat(candidate.requestedPositionId()).isNull();
                    });

            jdbc.update("""
                    update position_function_profile_version version
                    set wecom_self_selectable = false
                    from position_function_profile profile
                    where version.tenant_id = profile.tenant_id
                      and version.profile_id = profile.id
                      and profile.tenant_id = ? and profile.position_id = ?
                      and profile.scope_type = 'GROUP'
                      and version.lifecycle_status = 'PUBLISHED'
                    """, TENANT, FRONT_POSITION);

            var reviewOptions = inTransaction(() -> service.reviewOptions(candidateId));
            assertThat(reviewOptions.hotelId()).isEqualTo(SECOND_HOTEL);
            assertThat(reviewOptions.positions())
                    .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                    .contains(FRONT_POSITION);
            assertThat(reviewOptions.positions())
                    .noneMatch(position -> "OTA运营经理".equals(position.name()));

            assertThatThrownBy(() -> inTransaction(() -> service.approve(
                    candidateId, new DecisionRequest(
                            submitted.rowVersion(), "missing assignment", false))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("请先为员工分配具体岗位再确认启用");

            ApprovalResponse approved = inTransaction(() -> service.approve(
                    candidateId, new DecisionRequest(
                            submitted.rowVersion(), "hotel direct reviewed", false,
                            SECOND_HOTEL, FRONT_POSITION, "REGULAR")));
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(uuidValue("""
                    select assignment.org_unit_id
                    from employee_position_assignment assignment
                    join employee employee_item
                      on employee_item.tenant_id = assignment.tenant_id
                     and employee_item.id = assignment.employee_id
                    where employee_item.account_id = ? and assignment.status = 'ACTIVE'
                    """, approved.accountId())).isEqualTo(SECOND_HOTEL);
            assertThat(uuidValue("select requested_position_id from wecom_person_onboarding where id = ?",
                    candidateId)).isEqualTo(FRONT_POSITION);
        } finally {
            jdbc.update("update org_unit set status = 'INACTIVE' where tenant_id = ? and id = ?",
                    TENANT, SECOND_HOTEL);
        }
    }

    @Test
    void registrationCanChooseGroupHeadquartersAndReviewerOnlySeesSafeGroupPositions() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        UUID candidateId = startReusableCandidate(invitation, lifecycle);
        inTransaction(() -> lifecycle.completeOAuth(
                candidateId, "group-headquarters-" + UUID.randomUUID()));
        String session = "group-headquarters-session-" + UUID.randomUUID();
        jdbc.update("""
                update wecom_person_onboarding
                set session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                where id = ?
                """, WeComDirectorySecretCodec.sha256(session), candidateId);

        var context = inTransaction(() -> lifecycle.context(session));
        assertThat(context.hotels())
                .filteredOn(option -> GROUP_HEADQUARTERS.equals(option.id()))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.name()).isEqualTo("集团总部");
                    assertThat(option.unitType()).isEqualTo("GROUP");
                });

        SubmitResponse submitted = inTransaction(() -> lifecycle.submit(new SubmitRequest(
                session, "Group headquarters employee", "13900004609",
                "group.headquarters." + candidateId,
                "Directory-Test-Password-2026", "Directory-Test-Password-2026",
                GROUP_HEADQUARTERS, null, context.rowVersion())));

        assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(inTransaction(() -> service.list(null)).items())
                .filteredOn(candidate -> candidateId.equals(candidate.id()))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.requestedHotelName())
                        .isEqualTo("集团总部"));

        var reviewOptions = inTransaction(() -> service.reviewOptions(candidateId));
        assertThat(reviewOptions.hotelId()).isEqualTo(GROUP_HEADQUARTERS);
        assertThat(reviewOptions.hotelName()).isEqualTo("集团总部");
        assertThat(reviewOptions.positions()).isNotEmpty();
        assertThat(reviewOptions.positions())
                .noneMatch(position -> "集团董事长".equals(position.name()));
        assertThat(reviewOptions.positions()).allSatisfy(position -> {
            assertThat(jdbc.queryForObject("""
                    select position.job_family = 'GROUP_MANAGEMENT'
                           and version.authorization_scope_type <> 'TENANT'
                           and not exists (
                               select 1 from role_permission grant_item
                               join permission permission_item
                                 on permission_item.id = grant_item.permission_id
                               where grant_item.tenant_id = profile.tenant_id
                                 and grant_item.role_id = profile.default_role_id
                                 and permission_item.delegable_to_position = false
                           )
                    from position_definition position
                    join position_function_profile profile
                      on profile.tenant_id = position.tenant_id
                     and profile.position_id = position.id
                     and profile.scope_type = 'GROUP'
                    join position_function_profile_version version
                      on version.tenant_id = profile.tenant_id
                     and version.profile_id = profile.id
                     and version.lifecycle_status = 'PUBLISHED'
                    where position.tenant_id = ? and position.id = ?
                    """, Boolean.class, TENANT, position.positionId())).isTrue();
        });

        var approvedPosition = reviewOptions.positions().getFirst();
        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(
                        submitted.rowVersion(), "group headquarters reviewed", false,
                        GROUP_HEADQUARTERS, approvedPosition.positionId(), "REGULAR")));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(uuidValue("""
                select assignment.org_unit_id
                from employee_position_assignment assignment
                join employee employee_item
                  on employee_item.tenant_id = assignment.tenant_id
                 and employee_item.id = assignment.employee_id
                where employee_item.account_id = ? and assignment.status = 'ACTIVE'
                """, approved.accountId())).isEqualTo(GROUP_HEADQUARTERS);
    }

    @Test
    void assignmentPermissionIsRecheckedWhenApprovingAfterOptionsWereLoaded() {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId("GROUP_GENERAL_MANAGER");
        assertThat(inTransaction(() -> service.reviewOptions(candidateId)).positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                .contains(positionId);

        useReviewerPermissions(Set.of("wecom-onboarding.review"));
        Counts before = identityCounts();
        ApprovalResponse response = inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "permission revoked", false, GROUP_HEADQUARTERS, positionId, "REGULAR")));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.accountId()).isNull();
        assertThat(identityCounts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP_CHAIRMAN", "GROUP_GENERAL_MANAGER", "GROUP_VICE_PRESIDENT",
            "HR_ADMINISTRATION", "HR_ADMINISTRATION_SUPERVISOR"})
    void assignmentManagerCanReviewAndAssignEveryPublishedHeadquartersRole(String positionCode) {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId(positionCode);
        var options = inTransaction(() -> service.reviewOptions(candidateId));
        assertThat(options.positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::name)
                .contains("集团董事长", "集团总经理", "集团副总", "行政人事", "行政人事主管")
                .doesNotContain("区域经理", "前台员工");
        assertThat(options.positions()).allSatisfy(option ->
                assertThat(option.orgUnitId()).isEqualTo(GROUP_HEADQUARTERS));

        var expectedGrant = jdbc.queryForMap("""
                select profile.default_role_id, version.authorization_scope_type
                from position_function_profile profile
                join position_function_profile_version version
                  on version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                 and version.lifecycle_status = 'PUBLISHED'
                where profile.tenant_id = ? and profile.position_id = ? and profile.scope_type = 'GROUP'
                """, TENANT, positionId);
        ApprovalResponse response = inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "headquarters assignment reviewed", false,
                        GROUP_HEADQUARTERS, positionId, "REGULAR")));

        assertThat(response.status()).isEqualTo("APPROVED");
        var scopeType = expectedGrant.get("authorization_scope_type");
        assertThat(jdbc.queryForMap("""
                select assignment.position_id, assignment.org_unit_id,
                       grant_item.role_id, grant_item.scope_type, grant_item.scope_org_unit_id
                from employee_position_assignment assignment
                join role_assignment grant_item
                  on grant_item.tenant_id = assignment.tenant_id
                 and grant_item.source_assignment_id = assignment.id
                where grant_item.tenant_id = ? and grant_item.account_id = ?
                """, TENANT, response.accountId()))
                .containsEntry("position_id", positionId)
                .containsEntry("org_unit_id", GROUP_HEADQUARTERS)
                .containsEntry("role_id", expectedGrant.get("default_role_id"))
                .containsEntry("scope_type", scopeType)
                .containsEntry("scope_org_unit_id",
                        Set.of("ORG_UNIT", "ORG_TREE").contains(scopeType) ? GROUP_HEADQUARTERS : null);
        assertThat(jdbc.queryForMap("""
                select status, preferred_assignment_id is not null as has_assignment
                from wecom_user_binding where tenant_id = ? and account_id = ?
                """, TENANT, response.accountId()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("has_assignment", true);
        verify(accessPolicy, atLeastOnce()).requireOrgScope(GROUP_HEADQUARTERS);

        Counts after = identityCounts();
        assertThat(inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "repeated approval", false, GROUP_HEADQUARTERS, positionId, "REGULAR")))
                .accountId()).isEqualTo(response.accountId());
        assertThat(identityCounts()).isEqualTo(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP_CHAIRMAN", "GROUP_GENERAL_MANAGER",
            "HR_ADMINISTRATION", "HR_ADMINISTRATION_SUPERVISOR"})
    void ordinaryReviewerCannotForgeAProtectedHeadquartersAssignment(String positionCode) {
        useReviewerPermissions(Set.of("wecom-onboarding.review"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId(positionCode);
        assertThat(inTransaction(() -> service.reviewOptions(candidateId)).positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                .doesNotContain(positionId);
        Counts before = identityCounts();

        ApprovalResponse response = inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "forged protected assignment", false,
                        GROUP_HEADQUARTERS, positionId, "REGULAR")));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.accountId()).isNull();
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void assignmentManagerCannotBypassEmployeeSelfSelectionRestrictions() {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID candidateId = insertPendingHeadquartersCandidate(positionId("GROUP_GENERAL_MANAGER"));
        Counts before = identityCounts();

        ApprovalResponse response = inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "employee selected a protected position", false, null, null, "REGULAR")));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.accountId()).isNull();
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void wildcardAdministratorCanReviewAndAssignAHeadquartersPosition() {
        useReviewerPermissions(Set.of("*"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId("HR_ADMINISTRATION");
        assertThat(inTransaction(() -> service.reviewOptions(candidateId)).positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                .contains(positionId);
        assertThat(inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "administrator assignment", false,
                        GROUP_HEADQUARTERS, positionId, "REGULAR"))).status()).isEqualTo("APPROVED");
    }

    @Test
    void assignmentManagementPermissionDoesNotReplaceOnboardingReviewPermission() {
        useReviewerPermissions(Set.of("org.manage"));
        doCallRealMethod().when(accessPolicy)
                .requireAnyPermission("wecom-binding.approve", "wecom-onboarding.review");
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId("GROUP_CHAIRMAN");
        Counts before = identityCounts();

        assertThatThrownBy(() -> inTransaction(() -> service.reviewOptions(candidateId)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "no review permission", false, GROUP_HEADQUARTERS, positionId, "REGULAR"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void assignmentManagerStillNeedsHeadquartersOrganizationScope() {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId("GROUP_CHAIRMAN");
        doThrow(new AccessDeniedException("目标组织不在当前账号的有效数据范围内"))
                .when(accessPolicy).requireOrgScope(GROUP_HEADQUARTERS);
        Counts before = identityCounts();

        assertThatThrownBy(() -> inTransaction(() -> service.reviewOptions(candidateId)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.approve(candidateId,
                new DecisionRequest(0, "outside scope", false, GROUP_HEADQUARTERS, positionId, "REGULAR"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(identityCounts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INACTIVE", "DRAFT", "DELETED"})
    void assignmentManagerCannotUseAHeadquartersPositionThatBecameUnavailable(String unavailableState) {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID candidateId = insertPendingHeadquartersCandidate(null);
        UUID positionId = positionId("GROUP_CHAIRMAN");
        assertThat(inTransaction(() -> service.reviewOptions(candidateId)).positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                .contains(positionId);

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            if ("DRAFT".equals(unavailableState)) {
                jdbc.update("""
                        update position_function_profile_version version
                        set lifecycle_status = 'DRAFT', published_at = null, published_by = null
                        from position_function_profile profile
                        where version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                          and profile.tenant_id = ? and profile.position_id = ?
                          and profile.scope_type = 'GROUP' and version.lifecycle_status = 'PUBLISHED'
                        """, TENANT, positionId);
            } else if ("DELETED".equals(unavailableState)) {
                jdbc.update("""
                        update position_definition
                        set deleted_at = now(), deleted_by = ?, deletion_batch_id = ?
                        where id = ?
                        """, CEO, UUID.randomUUID(), positionId);
            } else {
                jdbc.update("update position_definition set status = 'INACTIVE' where id = ?", positionId);
            }
            assertThat(service.reviewOptions(candidateId).positions())
                    .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                    .doesNotContain(positionId);
            Counts before = identityCounts();
            ApprovalResponse response = service.approve(candidateId,
                    new DecisionRequest(0, "position became unavailable", false,
                            GROUP_HEADQUARTERS, positionId, "REGULAR"));
            assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
            assertThat(response.accountId()).isNull();
            assertThat(identityCounts()).isEqualTo(before);
        });
    }

    @Test
    void assignmentManagementDoesNotBroadenStoreReviewOrAllowStorePositionsAtHeadquarters() {
        useReviewerPermissions(Set.of("wecom-onboarding.review", "org.manage"));
        UUID headquartersCandidate = insertPendingHeadquartersCandidate(null);
        Counts before = identityCounts();
        assertThat(inTransaction(() -> service.approve(headquartersCandidate,
                new DecisionRequest(0, "store position at headquarters", false,
                        GROUP_HEADQUARTERS, FRONT_POSITION, "REGULAR"))).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(identityCounts()).isEqualTo(before);

        UUID storeCandidate = UUID.randomUUID();
        insertCandidate(storeCandidate, "store-review-" + storeCandidate, "NEW_MEMBER", null, null,
                "PENDING_APPROVAL", null, FRONT_DEPARTMENT, null);
        UUID protectedStorePosition = positionId("OTA_OPERATION_MANAGER");
        assertThat(inTransaction(() -> service.reviewOptions(storeCandidate)).positions())
                .extracting(WeComDirectoryOnboardingModels.ReviewAssignmentOption::positionId)
                .doesNotContain(protectedStorePosition);
        assertThat(inTransaction(() -> service.approve(storeCandidate,
                new DecisionRequest(0, "protected store role unchanged", false,
                        FRONT_DEPARTMENT, protectedStorePosition, "REGULAR"))).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void reviewedRegistrationCopiesMobileToTheApprovedAccountAndEmployee() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        UUID candidateId = startReusableCandidate(invitation, lifecycle);
        inTransaction(() -> lifecycle.completeOAuth(
                candidateId, "mobile-registration-" + UUID.randomUUID()));
        String session = "mobile-session-" + UUID.randomUUID();
        jdbc.update("""
                update wecom_person_onboarding
                set session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                where id = ?
                """, WeComDirectorySecretCodec.sha256(session), candidateId);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, candidateId);

        SubmitResponse submitted = inTransaction(() -> lifecycle.submit(new SubmitRequest(
                session, "Mobile employee", "13900004501",
                "mobile.employee." + candidateId,
                "Directory-Test-Password-2026", "Directory-Test-Password-2026",
                FRONT_DEPARTMENT, FRONT_POSITION, version)));
        assertThat(jdbc.queryForObject("""
                select requested_mobile from wecom_person_onboarding where id = ?
                """, String.class, candidateId)).isEqualTo("13900004501");

        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(
                        submitted.rowVersion(), "mobile reviewed", false, null, null, "REGULAR")));

        assertThat(jdbc.queryForObject(
                "select mobile from user_account where id = ?", String.class,
                approved.accountId())).isEqualTo("13900004501");
        assertThat(jdbc.queryForObject(
                "select mobile from employee where account_id = ?", String.class,
                approved.accountId())).isEqualTo("13900004501");
    }

    @Test
    void registrationRejectsAMobileThatAlreadyBelongsToAPlatformAccount() {
        OpenInvitationResponse invitation = inTransaction(service::createOpenInvitation);
        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        UUID candidateId = startReusableCandidate(invitation, lifecycle);
        inTransaction(() -> lifecycle.completeOAuth(
                candidateId, "duplicate-mobile-" + UUID.randomUUID()));
        String session = "duplicate-mobile-session-" + UUID.randomUUID();
        jdbc.update("""
                update wecom_person_onboarding
                set session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                where id = ?
                """, WeComDirectorySecretCodec.sha256(session), candidateId);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, candidateId);
        String registeredMobile = jdbc.queryForObject(
                "select mobile from user_account where id = ?", String.class, FRONT_ACCOUNT);

        assertThatThrownBy(() -> inTransaction(() -> lifecycle.submit(new SubmitRequest(
                session, "Duplicate mobile", registeredMobile,
                "duplicate.mobile." + candidateId,
                "Directory-Test-Password-2026", "Directory-Test-Password-2026",
                FRONT_DEPARTMENT, FRONT_POSITION, version))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("手机号已注册");
    }

    @Test
    void concurrentConfirmedTransferCreatesOneIdentityChainAndReturnsTheSameAccount() throws Exception {
        String userId = "concurrent-transfer-" + UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertBinding(bindingId, userId, FRONT_ACCOUNT, null);
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "CONFLICT", FRONT_ACCOUNT, FRONT_DEPARTMENT, FRONT_POSITION);

        Counts before = identityCounts();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Attempt> approval = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                ApprovalResponse response = inTransaction(() -> service.approve(
                        candidateId, new DecisionRequest(0, "confirmed transfer", true, null, null, "REGULAR")));
                return new Attempt(response, null);
            } catch (Throwable failure) {
                return new Attempt(null, failure);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> first = executor.submit(approval);
            Future<Attempt> second = executor.submit(approval);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Attempt firstAttempt = first.get(20, TimeUnit.SECONDS);
            Attempt secondAttempt = second.get(20, TimeUnit.SECONDS);
            assertThat(firstAttempt.failure()).isNull();
            assertThat(secondAttempt.failure()).isNull();
            assertThat(firstAttempt.response().status()).isEqualTo("APPROVED");
            assertThat(secondAttempt.response().status()).isEqualTo("APPROVED");
            assertThat(firstAttempt.response().accountId())
                    .isEqualTo(secondAttempt.response().accountId())
                    .isNotEqualTo(FRONT_ACCOUNT);
        } finally {
            executor.shutdownNow();
        }

        UUID approvedAccount = uuidValue(
                "select account_id from wecom_person_onboarding where id = ?", candidateId);
        Counts after = identityCounts();
        assertThat(after.accounts() - before.accounts()).isOne();
        assertThat(after.employees() - before.employees()).isOne();
        assertThat(after.assignments() - before.assignments()).isOne();
        assertThat(after.roles() - before.roles()).isOne();
        assertThat(after.bindings() - before.bindings()).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from user_account where id = ?", Integer.class, approvedAccount)).isOne();
        assertThat(jdbc.queryForMap("""
                select login_name, password_hash, password_changed_at is not null as password_initialized
                from user_account where id = ?
                """, approvedAccount))
                .containsEntry("login_name", "candidate." + candidateId)
                .containsEntry("password_hash", TEST_PASSWORD_HASH)
                .containsEntry("password_initialized", true);
        assertThat(jdbc.queryForObject(
                "select requested_password_hash is null from wecom_person_onboarding where id = ?",
                Boolean.class, candidateId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from employee where account_id = ?", Integer.class, approvedAccount)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from employee_position_assignment assignment "
                        + "join employee on employee.id = assignment.employee_id "
                        + "where employee.account_id = ?", Integer.class, approvedAccount)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from role_assignment where account_id = ?", Integer.class, approvedAccount)).isOne();
        assertThat(jdbc.queryForMap("""
                select account_id, status, status_reason
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("account_id", FRONT_ACCOUNT)
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "IDENTITY_TRANSFERRED_BY_ONBOARDING");
        UUID approvedBinding = uuidValue(
                "select binding_id from wecom_person_onboarding where id = ?", candidateId);
        assertThat(approvedBinding).isNotEqualTo(bindingId);
        assertThat(uuidValue("select account_id from wecom_user_binding where id = ?", approvedBinding))
                .isEqualTo(approvedAccount);
        assertThat(jdbc.queryForObject(
                "select count(*) from wecom_user_binding where tenant_id = ? and corp_id = ? and wecom_user_id = ?",
                Integer.class, TENANT, CORP_ID, userId)).isOne();
    }

    @Test
    void assignmentUpdateAfterNewMemberTransferKeepsTheCurrentAccountAndNeverRevivesTheOldOne() {
        String userId = "transfer-then-assignment-" + UUID.randomUUID();
        UUID oldBindingId = UUID.randomUUID();
        UUID transferCandidateId = UUID.randomUUID();
        insertBinding(oldBindingId, userId, FRONT_ACCOUNT, null);
        insertCandidate(transferCandidateId, userId, "NEW_MEMBER", null, null,
                "CONFLICT", FRONT_ACCOUNT, FRONT_DEPARTMENT, FRONT_POSITION);

        ApprovalResponse transferred = inTransaction(() -> service.approve(
                transferCandidateId, new DecisionRequest(0, "confirmed identity transfer", true, null, null, "REGULAR")));
        UUID currentAccountId = transferred.accountId();
        UUID currentBindingId = uuidValue(
                "select binding_id from wecom_person_onboarding where id = ?", transferCandidateId);
        assertThat(currentAccountId).isNotEqualTo(FRONT_ACCOUNT);
        assertThat(currentBindingId).isNotEqualTo(oldBindingId);

        OffsetDateTime changedAt = OffsetDateTime.now().plusMinutes(1);
        WeComDirectoryEvent changed = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Transferred employee", "1", "new-department", "new-position", changedAt);
        UUID receiptId = insertReceipt(changed, changedAt.plusSeconds(1));

        assertThat(inTransaction(() -> lifecycleService().handleDirectoryEvent(
                changed, receiptId, UUID.randomUUID()))).isTrue();

        UUID assignmentCandidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                  and status = 'WAITING_PROFILE' and onboarding_kind = 'ASSIGNMENT_CHANGE'
                """, CORP_ID, codec.fingerprint(userId));
        assertThat(jdbc.queryForMap("""
                select source_account_id, source_binding_id
                from wecom_person_onboarding where id = ?
                """, assignmentCandidateId))
                .containsEntry("source_account_id", currentAccountId)
                .containsEntry("source_binding_id", currentBindingId);
        makeCandidateApprovable(
                assignmentCandidateId, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, assignmentCandidateId);

        ApprovalResponse reassigned = inTransaction(() -> service.approve(
                assignmentCandidateId, new DecisionRequest(version, "assignment reviewed", false, null, null, "REGULAR")));

        assertThat(reassigned.accountId()).isEqualTo(currentAccountId);
        assertThat(jdbc.queryForMap("""
                select account_id, status, status_reason
                from wecom_user_binding where id = ?
                """, oldBindingId))
                .containsEntry("account_id", FRONT_ACCOUNT)
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "IDENTITY_TRANSFERRED_BY_ONBOARDING");
        assertThat(jdbc.queryForMap("""
                select account_id, status from wecom_user_binding where id = ?
                """, currentBindingId))
                .containsEntry("account_id", currentAccountId)
                .containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_user_binding
                where tenant_id = ? and corp_id = ? and user_id_fingerprint = ?
                  and account_id = ? and status <> 'REVOKED'
                """, Integer.class, TENANT, CORP_ID, codec.fingerprint(userId), FRONT_ACCOUNT))
                .isZero();
    }

    @Test
    void assignmentUpdateAfterManualRevocationRequiresFreshIdentityAndNeverRevivesTransferHistory() {
        String userId = "transfer-manual-revoke-assignment-" + UUID.randomUUID();
        UUID transferredFromBindingId = UUID.randomUUID();
        UUID transferCandidateId = UUID.randomUUID();
        insertBinding(transferredFromBindingId, userId, FRONT_ACCOUNT, null);
        insertCandidate(transferCandidateId, userId, "NEW_MEMBER", null, null,
                "CONFLICT", FRONT_ACCOUNT, FRONT_DEPARTMENT, FRONT_POSITION);

        ApprovalResponse transferred = inTransaction(() -> service.approve(
                transferCandidateId, new DecisionRequest(0, "confirmed identity transfer", true, null, null, "REGULAR")));
        UUID manuallyRevokedAccountId = transferred.accountId();
        UUID manuallyRevokedBindingId = uuidValue(
                "select binding_id from wecom_person_onboarding where id = ?", transferCandidateId);
        jdbc.update("""
                update wecom_user_binding
                set status = 'REVOKED', wecom_user_id = 'revoked:' || id::text,
                    status_reason = 'MANUALLY_REVOKED', updated_by = ?,
                    row_version = row_version + 1, updated_at = now()
                where id = ?
                """, CEO, manuallyRevokedBindingId);

        OffsetDateTime changedAt = OffsetDateTime.now().plusMinutes(1);
        WeComDirectoryEvent changed = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Employee after manual revocation", "1",
                "new-department", "new-position", changedAt);
        UUID receiptId = insertReceipt(changed, changedAt.plusSeconds(1));

        assertThat(inTransaction(() -> lifecycleService().handleDirectoryEvent(
                changed, receiptId, UUID.randomUUID()))).isTrue();

        UUID freshCandidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                  and status = 'WAITING_PROFILE' and onboarding_kind = 'NEW_MEMBER'
                """, CORP_ID, codec.fingerprint(userId));
        assertThat(jdbc.queryForObject("""
                select source_binding_id is null and source_account_id is null
                       and identity_verified_at is null
                       and requested_org_unit_id is null and requested_position_id is null
                from wecom_person_onboarding where id = ?
                """, Boolean.class, freshCandidateId)).isTrue();
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, transferredFromBindingId))
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "IDENTITY_TRANSFERRED_BY_ONBOARDING");
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, manuallyRevokedBindingId))
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "MANUALLY_REVOKED");

        makeCandidateApprovable(
                freshCandidateId, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, freshCandidateId);
        ApprovalResponse approved = inTransaction(() -> service.approve(
                freshCandidateId, new DecisionRequest(version, "fresh identity reviewed", false, null, null, "REGULAR")));

        assertThat(approved.accountId()).isNotIn(FRONT_ACCOUNT, manuallyRevokedAccountId);
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_user_binding
                where tenant_id = ? and corp_id = ? and user_id_fingerprint = ?
                  and status = 'ACTIVE' and account_id = ?
                """, Integer.class, TENANT, CORP_ID, codec.fingerprint(userId), approved.accountId()))
                .isOne();
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, transferredFromBindingId))
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "IDENTITY_TRANSFERRED_BY_ONBOARDING");
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, manuallyRevokedBindingId))
                .containsEntry("status", "REVOKED")
                .containsEntry("status_reason", "MANUALLY_REVOKED");
    }

    @Test
    void sameSecondProfileAndDisabledUpdatesCreateDistinctReceiptsAndRemovalWins() {
        String userId = "same-second-disable-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(
                accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        OffsetDateTime occurredAt = OffsetDateTime.now().plusMinutes(1);
        WeComDirectoryEvent profileUpdate = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Current profile", "1", "original-department", "original-position", occurredAt);
        WeComDirectoryEvent disabledUpdate = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Current profile", "2", "original-department", "original-position", occurredAt);
        WeComDirectoryEventReceiptStore receipts = new WeComDirectoryEventReceiptStore(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                properties, codec, new ObjectMapper().findAndRegisterModules());

        WeComDirectoryEventReceiptStore.Reservation profileReceipt =
                receipts.reserve(profileUpdate, UUID.randomUUID());
        WeComDirectoryEventReceiptStore.Reservation disabledReceipt =
                receipts.reserve(disabledUpdate, UUID.randomUUID());

        assertThat(profileReceipt.duplicate()).isFalse();
        assertThat(disabledReceipt.duplicate()).isFalse();
        assertThat(disabledReceipt.id()).isNotEqualTo(profileReceipt.id());
        assertThat(receipts.reserve(profileUpdate, UUID.randomUUID()))
                .isEqualTo(new WeComDirectoryEventReceiptStore.Reservation(profileReceipt.id(), true));
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_directory_event_receipt
                where tenant_id = ? and corp_id = ? and user_id_fingerprint = ?
                """, Integer.class, TENANT, CORP_ID, codec.fingerprint(userId))).isEqualTo(2);

        WeComDirectoryOnboardingService lifecycle = lifecycleService();
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                profileUpdate, profileReceipt.id(), UUID.randomUUID()))).isFalse();
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                disabledUpdate, disabledReceipt.id(), UUID.randomUUID()))).isTrue();
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "DIRECTORY_MEMBER_DISABLED");
    }

    @Test
    void reusedIdentityCancelsTheOlderApplicationAndRequiresFreshVerificationAndSelection() {
        String userId = "reuse-clears-old-application-" + UUID.randomUUID();
        UUID oldBindingId = UUID.randomUUID();
        UUID oldCandidateId = UUID.randomUUID();
        insertBinding(oldBindingId, userId, FRONT_ACCOUNT, null);
        insertCandidate(oldCandidateId, userId, "NEW_MEMBER", null, null,
                "PENDING_APPROVAL", null, FRONT_DEPARTMENT, FRONT_POSITION);
        jdbc.update("""
                update wecom_person_onboarding
                set invitation_token_hash = ?, invitation_issued_at = now(),
                    invitation_expires_at = now() + interval '60 minutes',
                    oauth_state_hash = ?, browser_verifier_hash = ?, provider_code_hash = ?,
                    exchange_code_hash = ?, exchange_expires_at = now() + interval '5 minutes',
                    session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                where id = ?
                """, WeComDirectorySecretCodec.sha256("old-invitation"),
                WeComDirectorySecretCodec.sha256("old-state"),
                WeComDirectorySecretCodec.sha256("old-verifier"),
                WeComDirectorySecretCodec.sha256("old-provider-code"),
                WeComDirectorySecretCodec.sha256("old-exchange"),
                WeComDirectorySecretCodec.sha256("old-session"), oldCandidateId);
        OffsetDateTime departedAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime reusedAt = departedAt.plusMinutes(1);
        WeComDirectoryEvent delayedDelete = new WeComDirectoryEvent(
                "change_contact", "delete_user", userId, null,
                "Former employee", "5", null, null, departedAt);
        WeComDirectoryEvent reusedCreate = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Replacement employee", "1", null, null, reusedAt);
        UUID deleteReceipt = insertReceipt(delayedDelete, departedAt.plusSeconds(1));
        UUID createReceipt = insertReceipt(reusedCreate, reusedAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                reusedCreate, createReceipt, UUID.randomUUID()))).isTrue();

        assertThat(jdbc.queryForMap("""
                select status, user_id_ciphertext is null as identity_cleared,
                       invitation_token_hash is null as invitation_cleared,
                       oauth_state_hash is null and browser_verifier_hash is null
                           and provider_code_hash is null as oauth_cleared,
                       exchange_code_hash is null and session_token_hash is null
                           as session_cleared,
                       identity_verified_at is null and requested_org_unit_id is null
                           and requested_position_id is null and profile_submitted_at is null
                           and conflicting_account_id is null as profile_cleared
                from wecom_person_onboarding where id = ?
                """, oldCandidateId))
                .containsEntry("status", "CANCELLED")
                .containsEntry("identity_cleared", true)
                .containsEntry("invitation_cleared", true)
                .containsEntry("oauth_cleared", true)
                .containsEntry("session_cleared", true)
                .containsEntry("profile_cleared", true);
        assertThatThrownBy(() -> inTransaction(() -> service.approve(
                oldCandidateId, new DecisionRequest(1, "must not approve", false, null, null, "REGULAR"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前状态不允许确认启用");

        UUID freshCandidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                  and status = 'WAITING_PROFILE' and onboarding_kind = 'NEW_MEMBER'
                """, CORP_ID, codec.fingerprint(userId));
        assertThat(freshCandidateId).isNotEqualTo(oldCandidateId);
        assertThat(jdbc.queryForMap("""
                select identity_verified_at is null as not_verified,
                       requested_org_unit_id is null and requested_position_id is null
                           and profile_submitted_at is null as no_selection,
                       conflicting_account_id is null as no_inherited_conflict,
                       invitation_token_hash is not null as fresh_invitation,
                       invitation_expires_at - invitation_issued_at <= interval '120 minutes 5 seconds'
                           as bounded_invitation
                from wecom_person_onboarding where id = ?
                """, freshCandidateId))
                .containsEntry("not_verified", true)
                .containsEntry("no_selection", true)
                .containsEntry("no_inherited_conflict", true)
                .containsEntry("fresh_invitation", true)
                .containsEntry("bounded_invitation", true);
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                delayedDelete, deleteReceipt, UUID.randomUUID()))).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, Integer.class, CORP_ID, codec.fingerprint(userId))).isOne();
    }

    @Test
    void laterDeleteFollowsAnUnprocessedRenameAndSuspendsTheCanonicalBinding() {
        assertLaterRemovalFollowsUnprocessedRename("delete_user", "5",
                "DIRECTORY_MEMBER_DELETED");
    }

    @Test
    void laterDisableFollowsAnUnprocessedRenameAndSuspendsTheCanonicalBinding() {
        assertLaterRemovalFollowsUnprocessedRename("update_user", "2",
                "DIRECTORY_MEMBER_DISABLED");
    }

    @Test
    void chainedRenameProcessedNewestFirstStillReusesTheCanonicalAccount() {
        String firstUserId = "rename-chain-a-" + UUID.randomUUID();
        String secondUserId = "rename-chain-b-" + UUID.randomUUID();
        String thirdUserId = "rename-chain-c-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId,
                bindingId, firstUserId);
        Counts before = identityCounts();
        OffsetDateTime firstAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime secondAt = firstAt.plusMinutes(1);
        WeComDirectoryEvent firstRename = new WeComDirectoryEvent(
                "change_contact", "update_user", firstUserId, secondUserId,
                "Existing employee", "1", null, null, firstAt);
        WeComDirectoryEvent secondRename = new WeComDirectoryEvent(
                "change_contact", "update_user", secondUserId, thirdUserId,
                "Existing employee", "1", null, null, secondAt);
        UUID firstReceipt = insertReceipt(firstRename, firstAt.plusSeconds(1));
        UUID secondReceipt = insertReceipt(secondRename, secondAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                secondRename, secondReceipt, UUID.randomUUID()))).isTrue();
        UUID candidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, CORP_ID, codec.fingerprint(thirdUserId));
        assertThat(jdbc.queryForMap("""
                select onboarding_kind, source_account_id, source_binding_id
                from wecom_person_onboarding where id = ?
                """, candidateId))
                .containsEntry("onboarding_kind", "USER_ID_CHANGE")
                .containsEntry("source_account_id", accountId)
                .containsEntry("source_binding_id", bindingId);
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                firstRename, firstReceipt, UUID.randomUUID()))).isFalse();
        makeCandidateApprovable(candidateId, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, candidateId);

        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(version, "rename chain reviewed", false, null, null, "REGULAR")));

        assertThat(approved.accountId()).isEqualTo(accountId);
        assertThat(identityCounts().accounts()).isEqualTo(before.accounts());
        assertThat(jdbc.queryForMap("""
                select account_id, wecom_user_id, user_id_fingerprint, status
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("account_id", accountId)
                .containsEntry("wecom_user_id", thirdUserId)
                .containsEntry("user_id_fingerprint", codec.fingerprint(thirdUserId))
                .containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and onboarding_kind = 'NEW_MEMBER'
                """, Integer.class, CORP_ID, codec.fingerprint(thirdUserId))).isZero();
    }

    @Test
    void assignmentUpdateOnRenameDestinationReusesTheCanonicalAccount() {
        String firstUserId = "rename-assignment-a-" + UUID.randomUUID();
        String secondUserId = "rename-assignment-b-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId,
                bindingId, firstUserId);
        Counts before = identityCounts();
        OffsetDateTime renameAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime assignmentAt = renameAt.plusMinutes(1);
        WeComDirectoryEvent rename = new WeComDirectoryEvent(
                "change_contact", "update_user", firstUserId, secondUserId,
                "Existing employee", "1", null, null, renameAt);
        WeComDirectoryEvent assignmentUpdate = new WeComDirectoryEvent(
                "change_contact", "update_user", secondUserId, null,
                "Existing employee", "1", "changed-department", "changed-position", assignmentAt);
        UUID renameReceipt = insertReceipt(rename, renameAt.plusSeconds(1));
        UUID assignmentReceipt = insertReceipt(assignmentUpdate, assignmentAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                assignmentUpdate, assignmentReceipt, UUID.randomUUID()))).isTrue();
        UUID candidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, CORP_ID, codec.fingerprint(secondUserId));
        assertThat(jdbc.queryForMap("""
                select onboarding_kind, source_account_id, source_binding_id
                from wecom_person_onboarding where id = ?
                """, candidateId))
                .containsEntry("onboarding_kind", "ASSIGNMENT_CHANGE")
                .containsEntry("source_account_id", accountId)
                .containsEntry("source_binding_id", bindingId);
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                rename, renameReceipt, UUID.randomUUID()))).isFalse();
        makeCandidateApprovable(candidateId, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?",
                Long.class, candidateId);

        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(version, "assignment reviewed", false, null, null, "REGULAR")));

        assertThat(approved.accountId()).isEqualTo(accountId);
        assertThat(identityCounts().accounts()).isEqualTo(before.accounts());
        assertThat(jdbc.queryForMap("""
                select account_id, wecom_user_id, status from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("account_id", accountId)
                .containsEntry("wecom_user_id", secondUserId)
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void approvalFailsClosedWhenANewerDisabledReceiptIsAlreadyDurable() {
        String userId = "approval-watermark-" + UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        OffsetDateTime candidateAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime disabledAt = candidateAt.plusMinutes(1);
        WeComDirectoryEvent candidateEvent = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Pending employee", "1", null, null, candidateAt);
        WeComDirectoryEvent disabled = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Pending employee", "2", null, null, disabledAt);
        UUID candidateReceipt = insertReceipt(candidateEvent, candidateAt.plusSeconds(1));
        jdbc.update("""
                update wecom_directory_event_receipt
                set status = 'SUCCEEDED', processed_at = now(), payload_ciphertext = ''
                where id = ?
                """,
                candidateReceipt);
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "PENDING_APPROVAL", null, FRONT_DEPARTMENT, FRONT_POSITION);
        jdbc.update("""
                update wecom_person_onboarding
                set last_event_at = ?, last_event_receipt_id = ? where id = ?
                """, candidateAt, candidateReceipt, candidateId);
        insertReceipt(disabled, disabledAt.plusSeconds(1));
        Counts before = identityCounts();

        assertThatThrownBy(() -> inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(0, "must not approve", false, null, null, "REGULAR"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正在同步");

        assertThat(identityCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?",
                String.class, candidateId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void retryAndRegenerateCancelOldApplicationsWhenANewerCreateIsDurable() {
        UUID retryCandidate = insertSupersededInvitationCandidate(
                "retry-superseded-" + UUID.randomUUID(), "WAITING_PROFILE",
                "WECOM_OAUTH_TECHNICAL_FAILURE");
        InvitationActionResponse retryResponse = inTransaction(() -> service.retryTechnicalFailure(
                retryCandidate, new RetryRequest(0, "retry old invitation")));
        assertThat(retryResponse.status()).isEqualTo("CANCELLED");
        assertCancelledWithoutIdentityOrInvitation(retryCandidate);

        UUID expiredCandidate = insertSupersededInvitationCandidate(
                "expired-superseded-" + UUID.randomUUID(), "EXPIRED",
                "INVITATION_EXPIRED");
        InvitationActionResponse regenerateResponse = inTransaction(() -> service.regenerateInvitation(
                expiredCandidate, new RetryRequest(0, "regenerate old invitation")));
        assertThat(regenerateResponse.status()).isEqualTo("CANCELLED");
        assertCancelledWithoutIdentityOrInvitation(expiredCandidate);
        verify(employeeService, never()).newInvitationToken();
    }

    @Test
    void confirmedConflictTransferRequiresANonBlankServerSideReason() {
        String userId = "transfer-reason-" + UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertBinding(bindingId, userId, FRONT_ACCOUNT, null);
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "CONFLICT", FRONT_ACCOUNT, FRONT_DEPARTMENT, FRONT_POSITION);
        Counts before = identityCounts();

        assertThatThrownBy(() -> inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(0, "   ", true, null, null, "REGULAR"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须填写确认原因");

        assertThat(identityCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?", String.class, candidateId))
                .isEqualTo("CONFLICT");
        assertThat(uuidValue("select account_id from wecom_user_binding where id = ?", bindingId))
                .isEqualTo(FRONT_ACCOUNT);
    }

    @Test
    void failureOnFinalCandidateUpdateRollsBackTheEntireIdentityChain() {
        String userId = "forced-rollback-" + UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "PENDING_APPROVAL", null, FRONT_DEPARTMENT, FRONT_POSITION);
        Counts before = identityCounts();

        String triggerName = "trg_test_wecom_approval_rollback";
        String functionName = "test_wecom_approval_rollback";
        jdbc.execute("drop trigger if exists " + triggerName + " on wecom_person_onboarding");
        jdbc.execute("drop function if exists " + functionName + "()");
        jdbc.execute("""
                create function %s() returns trigger language plpgsql as $$
                begin
                    if new.id = '%s'::uuid and new.status = 'APPROVED' then
                        raise exception 'forced approval rollback';
                    end if;
                    return new;
                end
                $$
                """.formatted(functionName, candidateId));
        jdbc.execute("create trigger " + triggerName
                + " before update on wecom_person_onboarding for each row execute function "
                + functionName + "()");

        try {
            assertThatThrownBy(() -> inTransaction(() -> service.approve(
                    candidateId, new DecisionRequest(0, "must roll back", false, null, null, "REGULAR"))))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("forced approval rollback");
        } finally {
            jdbc.execute("drop trigger if exists " + triggerName + " on wecom_person_onboarding");
            jdbc.execute("drop function if exists " + functionName + "()");
        }

        Counts after = identityCounts();
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?", String.class, candidateId))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject(
                "select account_id is null and employee_id is null and assignment_id is null "
                        + "and role_assignment_id is null and binding_id is null "
                        + "and user_id_ciphertext is not null "
                        + "from wecom_person_onboarding where id = ?",
                Boolean.class, candidateId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from wecom_user_binding where tenant_id = ? and corp_id = ? and wecom_user_id = ?",
                Integer.class, TENANT, CORP_ID, userId)).isZero();
    }

    @Test
    void assignmentChangeReusesAccountAndEmployeeAndRetiresTheOldAssignmentAndRole() {
        String userId = "assignment-change-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID oldAssignmentId = UUID.randomUUID();
        UUID oldRoleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, oldAssignmentId, oldRoleAssignmentId,
                bindingId, userId);
        insertCandidate(candidateId, userId, "ASSIGNMENT_CHANGE", bindingId, accountId,
                "PENDING_APPROVAL", null, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        UUID manualGrantId = UUID.randomUUID();
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id,
                     scope_type, valid_from, granted_by)
                select ?, ?, ?, profile.default_role_id, ?, 'ORG_UNIT', now(), ?
                from position_function_profile profile
                where profile.tenant_id = ? and profile.position_id = ?
                  and profile.scope_type = 'GROUP'
                """, manualGrantId, TENANT, accountId, FRONT_DEPARTMENT, CEO, TENANT, FRONT_POSITION);
        Counts before = identityCounts();

        ApprovalResponse response = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(0, "assignment approved", false, null, null, "REGULAR")));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.accountId()).isEqualTo(accountId);
        Counts after = identityCounts();
        assertThat(after.accounts() - before.accounts()).isZero();
        assertThat(after.employees() - before.employees()).isZero();
        assertThat(after.assignments() - before.assignments()).isOne();
        assertThat(after.roles() - before.roles()).isOne();
        assertThat(after.bindings() - before.bindings()).isZero();

        assertThat(jdbc.queryForMap(
                "select status, is_primary, valid_to is not null as ended "
                        + "from employee_position_assignment where id = ?", oldAssignmentId))
                .containsEntry("status", "INACTIVE")
                .containsEntry("is_primary", false)
                .containsEntry("ended", true);
        assertThat(jdbc.queryForObject(
                "select valid_to is not null from role_assignment where id = ?",
                Boolean.class, oldRoleAssignmentId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select valid_to is null from role_assignment where id = ?",
                Boolean.class, manualGrantId)).isTrue();

        UUID newAssignmentId = uuidValue(
                "select assignment_id from wecom_person_onboarding where id = ?", candidateId);
        UUID newRoleAssignmentId = uuidValue(
                "select role_assignment_id from wecom_person_onboarding where id = ?", candidateId);
        assertThat(newAssignmentId).isNotEqualTo(oldAssignmentId);
        assertThat(newRoleAssignmentId).isNotEqualTo(oldRoleAssignmentId);
        assertThat(jdbc.queryForObject(
                "select employee_id = ? and org_unit_id = ? and position_id = ? "
                        + "and status = 'ACTIVE' and is_primary "
                        + "from employee_position_assignment where id = ?",
                Boolean.class, employeeId, HOUSEKEEPING_DEPARTMENT,
                HOUSEKEEPING_POSITION, newAssignmentId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select account_id = ? and preferred_assignment_id = ? and status = 'ACTIVE' "
                        + "and row_version = 1 from wecom_user_binding where id = ?",
                Boolean.class, accountId, newAssignmentId, bindingId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select account_id = ? and employee_id = ? and binding_id = ? "
                        + "from wecom_person_onboarding where id = ?",
                Boolean.class, accountId, employeeId, bindingId, candidateId)).isTrue();
    }

    @Test
    void technicalRetryRotatesTheInvitationWithoutReturningItsSecret() {
        String userId = "technical-retry-" + UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "WAITING_PROFILE", null, null, null);
        jdbc.update("""
                update wecom_person_onboarding
                set failure_code = 'INVITATION_DELIVERY_FAILED'
                where id = ?
                """, candidateId);

        InvitationActionResponse response = inTransaction(() -> service.retryTechnicalFailure(
                candidateId, new RetryRequest(0, "retry provider delivery")));

        assertThat(response.status()).isEqualTo("WAITING_PROFILE");
        assertThat(response.invitationExpiresAt()).isNotNull();
        assertThat(response.message()).doesNotContain("integration-");
        assertThat(jdbc.queryForObject("""
                select invitation_token_hash ~ '^[0-9a-f]{64}$' and invitation_expires_at is not null
                       and failure_code is null and row_version = 1
                from wecom_person_onboarding where id = ?
                """, Boolean.class, candidateId)).isTrue();
        verify(employeeService).deliverInvitationAfterCommit(
                org.mockito.ArgumentMatchers.eq(candidateId),
                org.mockito.ArgumentMatchers.eq(userId), anyString());
    }

    @Test
    void expiredInvitationCanOnlyBeRegeneratedAsANewCredential() {
        String userId = "expired-regenerate-" + UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "WAITING_PROFILE", null, null, null);
        jdbc.update("""
                update wecom_person_onboarding
                set status = 'EXPIRED', expired_at = now(),
                    failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                where id = ?
                """, candidateId);

        InvitationActionResponse response = inTransaction(() -> service.regenerateInvitation(
                candidateId, new RetryRequest(1, "employee still needs access")));

        assertThat(response.status()).isEqualTo("WAITING_PROFILE");
        assertThat(response.rowVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select expired_at is null and invitation_token_hash ~ '^[0-9a-f]{64}$'
                       and invitation_issued_at is not null and invitation_expires_at is not null
                       and failure_code is null and row_version = 2
                from wecom_person_onboarding where id = ?
                """, Boolean.class, candidateId)).isTrue();
        verify(employeeService).deliverInvitationAfterCommit(
                org.mockito.ArgumentMatchers.eq(candidateId),
                org.mockito.ArgumentMatchers.eq(userId), anyString());
    }

    @Test
    void expiredInvitationRegenerationRejectsANewerOpenCandidateExplicitly() {
        String userId = "expired-with-newer-" + UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        UUID newerId = UUID.randomUUID();
        insertCandidate(expiredId, userId, "NEW_MEMBER", null, null,
                "WAITING_PROFILE", null, null, null);
        jdbc.update("""
                update wecom_person_onboarding
                set status = 'EXPIRED', expired_at = now(),
                    failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                where id = ?
                """, expiredId);
        insertCandidate(newerId, userId, "NEW_MEMBER", null, null,
                "WAITING_PROFILE", null, null, null);

        assertThatThrownBy(() -> inTransaction(() -> service.regenerateInvitation(
                expiredId, new RetryRequest(1, "try stale invitation"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已有更新的申请");
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?", String.class, expiredId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?", String.class, newerId))
                .isEqualTo("WAITING_PROFILE");
    }

    @Test
    void dueInvitationExpiresPersistentlyAndClearsEveryTemporaryCredential() {
        String userId = "scheduled-expiry-" + UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "WAITING_PROFILE", null, null, null);
        jdbc.update("""
                update wecom_person_onboarding
                set invitation_token_hash = ?, invitation_issued_at = now() - interval '121 minutes',
                    invitation_expires_at = now() - interval '1 minute',
                    oauth_state_hash = ?, browser_verifier_hash = ?, provider_code_hash = ?,
                    exchange_code_hash = ?, exchange_expires_at = now() + interval '1 minute',
                    session_token_hash = ?, session_expires_at = now() + interval '10 minutes'
                where id = ?
                """, WeComDirectorySecretCodec.sha256("expired-token"),
                WeComDirectorySecretCodec.sha256("state"),
                WeComDirectorySecretCodec.sha256("verifier"),
                WeComDirectorySecretCodec.sha256("provider"),
                WeComDirectorySecretCodec.sha256("exchange"),
                WeComDirectorySecretCodec.sha256("session"), candidateId);
        WeComDirectoryOnboardingService lifecycle = new WeComDirectoryOnboardingService(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                properties, codec, mock(WeComApiClient.class), new ObjectMapper(),
                new PilotPasswordHasher(), transactionManager);

        int expired = inTransaction(lifecycle::expireDueInvitations);

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select status = 'EXPIRED' and expired_at is not null
                       and user_id_ciphertext is not null
                       and invitation_token_hash is null and invitation_issued_at is null
                       and invitation_expires_at is null and oauth_state_hash is null
                       and browser_verifier_hash is null and provider_code_hash is null
                       and exchange_code_hash is null and exchange_expires_at is null
                       and session_token_hash is null and session_expires_at is null
                       and failure_code = 'INVITATION_EXPIRED'
                from wecom_person_onboarding where id = ?
                """, Boolean.class, candidateId)).isTrue();
    }

    @Test
    void deadLetterRetryUsesOptimisticLockAndRequeuesOnlyTheEncryptedReceipt() {
        UUID receiptId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_directory_event_receipt
                    (id, tenant_id, corp_id, event_key_hash, payload_hash,
                     payload_ciphertext, event_type, change_type, event_priority,
                     user_id_fingerprint, occurred_at, status, correlation_id,
                     attempt_count, last_attempt_at, processed_at, last_error_code)
                values (?, ?, ?, ?, ?, ?, 'change_contact', 'update_user', 40,
                        ?, now(), 'DEAD_LETTER', ?, 8, now(), now(), 'TEST_FAILURE')
                """, receiptId, TENANT, CORP_ID,
                WeComDirectorySecretCodec.sha256("event:" + receiptId),
                WeComDirectorySecretCodec.sha256("payload:" + receiptId),
                codec.encrypt("safe encrypted retry payload"),
                codec.fingerprint("dead-letter-user-" + receiptId), correlationId);

        var listing = inTransaction(() -> service.listDirectoryEvents(null));
        assertThat(listing.items()).anySatisfy(row -> {
            assertThat(row.id()).isEqualTo(receiptId);
            assertThat(row.status()).isEqualTo("DEAD_LETTER");
            assertThat(row.lastErrorCode()).isEqualTo("TEST_FAILURE");
            assertThat(row.suggestedAction()).contains("无需登录服务器");
        });

        DirectoryEventRetryResponse response = inTransaction(() -> service.retryDirectoryEvent(
                receiptId, new RetryRequest(0, "retry technical dead letter")));

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.rowVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select status = 'PROCESSING' and attempt_count = 0
                       and last_attempt_at is null and processed_at is null
                       and last_error_code is null and payload_ciphertext <> ''
                from wecom_directory_event_receipt where id = ?
                """, Boolean.class, receiptId)).isTrue();
        verify(eventProcessor).processNew(receiptId, correlationId);
        assertThatThrownBy(() -> inTransaction(() -> service.retryDirectoryEvent(
                receiptId, new RetryRequest(0, "stale duplicate"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅死信状态");
    }

    @Test
    void delayedRenameDoesNotSuspendAReusedIdentityApprovedByANewerDirectoryFact() {
        String oldUserId = "reuse-old-" + UUID.randomUUID();
        String newUserId = "rename-new-" + UUID.randomUUID();
        UUID sourceAccount = UUID.randomUUID();
        UUID sourceEmployee = UUID.randomUUID();
        UUID sourceAssignment = UUID.randomUUID();
        UUID sourceRole = UUID.randomUUID();
        UUID sourceBinding = UUID.randomUUID();
        insertSourceIdentity(sourceAccount, sourceEmployee, sourceAssignment, sourceRole,
                sourceBinding, oldUserId);

        OffsetDateTime renameAt = OffsetDateTime.now().minusMinutes(2);
        OffsetDateTime reuseAt = renameAt.plusMinutes(1);
        jdbc.update("update wecom_user_binding set last_verified_at = ? where id = ?",
                renameAt.minusMinutes(1), sourceBinding);
        WeComDirectoryEvent rename = new WeComDirectoryEvent(
                "change_contact", "update_user", oldUserId, newUserId,
                "Original employee", "1", null, null, renameAt);
        WeComDirectoryEvent reuse = new WeComDirectoryEvent(
                "change_contact", "create_user", oldUserId, null,
                "Replacement employee", "1", null, null, reuseAt);
        UUID renameReceipt = insertReceipt(rename, renameAt.plusSeconds(1));
        UUID reuseReceipt = insertReceipt(reuse, reuseAt.plusSeconds(1));

        UUID replacementCandidate = UUID.randomUUID();
        insertCandidate(replacementCandidate, oldUserId, "NEW_MEMBER", null, null,
                "CONFLICT", sourceAccount, FRONT_DEPARTMENT, FRONT_POSITION);
        jdbc.update("""
                update wecom_person_onboarding
                set last_event_at = ?, last_event_receipt_id = ?
                where id = ?
                """, reuseAt, reuseReceipt, replacementCandidate);
        ApprovalResponse replacement = inTransaction(() -> service.approve(
                replacementCandidate, new DecisionRequest(0, "confirmed reused identity", true, null, null, "REGULAR")));
        UUID replacementBinding = uuidValue(
                "select binding_id from wecom_person_onboarding where id = ?", replacementCandidate);
        assertThat(replacement.accountId()).isNotEqualTo(sourceAccount);
        assertThat(jdbc.queryForObject("""
                select status = 'ACTIVE' and identity_event_receipt_id = ?
                from wecom_user_binding where id = ?
                """, Boolean.class, reuseReceipt, replacementBinding)).isTrue();

        WeComDirectoryOnboardingService lifecycle = new WeComDirectoryOnboardingService(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                properties, codec, mock(WeComApiClient.class), new ObjectMapper(),
                new PilotPasswordHasher(), transactionManager);
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                rename, renameReceipt, UUID.randomUUID()))).isTrue();

        assertThat(jdbc.queryForObject(
                "select status from wecom_user_binding where id = ?",
                String.class, replacementBinding)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*)
                from wecom_person_onboarding
                where tenant_id = ? and corp_id = ? and user_id_fingerprint = ?
                  and onboarding_kind = 'USER_ID_CHANGE' and source_account_id = ?
                  and status = 'WAITING_PROFILE'
                """, Integer.class, TENANT, CORP_ID, codec.fingerprint(newUserId), sourceAccount)).isOne();
        assertThat(jdbc.queryForObject(
                "select status from wecom_person_onboarding where id = ?",
                String.class, replacementCandidate)).isEqualTo("APPROVED");
    }

    @Test
    void directorySuspensionDoesNotOverwriteAnExistingManualSuspensionReasonOrActor() {
        String userId = "manual-suspension-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        jdbc.update("""
                update wecom_user_binding
                set status = 'SUSPENDED', status_reason = 'MANUAL_REVIEW_PRIVATE_REASON',
                    updated_by = ?, row_version = 7
                where id = ?
                """, CEO, bindingId);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        WeComDirectoryEvent disabled = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Disabled employee", "2", null, null, occurredAt);
        UUID receiptId = insertReceipt(disabled, occurredAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = new WeComDirectoryOnboardingService(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                properties, codec, mock(WeComApiClient.class), new ObjectMapper(),
                new PilotPasswordHasher(), transactionManager);

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                disabled, receiptId, UUID.randomUUID()))).isTrue();

        assertThat(jdbc.queryForMap("""
                select status, status_reason, updated_by, row_version
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "MANUAL_REVIEW_PRIVATE_REASON")
                .containsEntry("updated_by", CEO)
                .containsEntry("row_version", 7L);
    }

    @Test
    void disabledThenReenabledMemberKeepsTheOriginalAccountAndRequiresManualResume() {
        String userId = "reenabled-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        Counts before = identityCounts();
        OffsetDateTime disabledAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime enabledAt = disabledAt.plusMinutes(1);
        WeComDirectoryEvent disabled = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Existing employee", "2", null, null, disabledAt);
        WeComDirectoryEvent enabled = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Existing employee", "1", null, null, enabledAt);
        UUID disabledReceipt = insertReceipt(disabled, disabledAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                disabled, disabledReceipt, UUID.randomUUID()))).isTrue();
        UUID enabledReceipt = insertReceipt(enabled, enabledAt.plusSeconds(1));
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                enabled, enabledReceipt, UUID.randomUUID()))).isTrue();

        UUID candidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, CORP_ID, codec.fingerprint(userId));
        assertThat(jdbc.queryForMap("""
                select onboarding_kind, source_account_id, source_binding_id
                from wecom_person_onboarding where id = ?
                """, candidateId))
                .containsEntry("onboarding_kind", "ASSIGNMENT_CHANGE")
                .containsEntry("source_account_id", accountId)
                .containsEntry("source_binding_id", bindingId);
        makeCandidateApprovable(candidateId, FRONT_DEPARTMENT, FRONT_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?", Long.class, candidateId);

        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(version, "reactivation reviewed", false, null, null, "REGULAR")));

        assertThat(approved.accountId()).isEqualTo(accountId);
        assertThat(approved.bindingStatus()).isEqualTo("SUSPENDED");
        Counts after = identityCounts();
        assertThat(after.accounts() - before.accounts()).isZero();
        assertThat(after.employees() - before.employees()).isZero();
        assertThat(jdbc.queryForMap("""
                select account_id, status, status_reason
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("account_id", accountId)
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "DIRECTORY_MEMBER_DISABLED");
    }

    @Test
    void manualSuspensionSurvivesDirectoryAssignmentApproval() {
        String userId = "manual-hold-change-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        jdbc.update("""
                update wecom_user_binding
                set status = 'SUSPENDED', status_reason = 'MANUAL_RISK_HOLD',
                    updated_by = ?, row_version = 4
                where id = ?
                """, CEO, bindingId);
        OffsetDateTime changedAt = OffsetDateTime.now().plusSeconds(1);
        WeComDirectoryEvent changed = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Existing employee", "1", "department changed", null, changedAt);
        UUID receiptId = insertReceipt(changed, changedAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                changed, receiptId, UUID.randomUUID()))).isTrue();
        UUID candidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, CORP_ID, codec.fingerprint(userId));
        makeCandidateApprovable(candidateId, HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?", Long.class, candidateId);

        ApprovalResponse approved = inTransaction(() -> service.approve(
                candidateId, new DecisionRequest(version, "assignment reviewed", false, null, null, "REGULAR")));

        assertThat(approved.accountId()).isEqualTo(accountId);
        assertThat(approved.bindingStatus()).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForMap("""
                select status, status_reason, updated_by
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "MANUAL_RISK_HOLD")
                .containsEntry("updated_by", CEO);
    }

    @Test
    void delayedDeleteFollowedByIdentifierReuseCreatesAConflictCandidateAndSuspendsOldIdentity() {
        String userId = "delete-reuse-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        OffsetDateTime deletedAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime recreatedAt = deletedAt.plusMinutes(1);
        WeComDirectoryEvent deleted = new WeComDirectoryEvent(
                "change_contact", "delete_user", userId, null,
                "Departed employee", null, null, null, deletedAt);
        WeComDirectoryEvent recreated = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Replacement employee", "1", null, null, recreatedAt);
        UUID deleteReceipt = insertReceipt(deleted, deletedAt.plusSeconds(1));
        UUID createReceipt = insertReceipt(recreated, recreatedAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                recreated, createReceipt, UUID.randomUUID()))).isTrue();
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                deleted, deleteReceipt, UUID.randomUUID()))).isFalse();
        assertThat(jdbc.queryForMap("""
                select status, status_reason, identity_event_receipt_id
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "DIRECTORY_IDENTITY_REUSED")
                .containsEntry("identity_event_receipt_id", createReceipt);

        UUID candidateId = uuidValue("""
                select id from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ? and status = 'WAITING_PROFILE'
                """, CORP_ID, codec.fingerprint(userId));
        String session = "session-" + UUID.randomUUID();
        jdbc.update("""
                update wecom_person_onboarding
                set session_token_hash = ?, session_expires_at = now() + interval '10 minutes',
                    identity_verified_at = now()
                where id = ?
                """, WeComDirectorySecretCodec.sha256(session), candidateId);
        long version = jdbc.queryForObject(
                "select row_version from wecom_person_onboarding where id = ?", Long.class, candidateId);

        SubmitResponse submitted = inTransaction(() -> lifecycle.submit(new SubmitRequest(
                session, "New employee", "13900004502", "new.employee." + candidateId,
                "Directory-Test-Password-2026", "Directory-Test-Password-2026",
                FRONT_DEPARTMENT, FRONT_POSITION, version)));

        assertThat(submitted.status()).isEqualTo("CONFLICT");
        assertThat(jdbc.queryForObject(
                "select conflicting_account_id from wecom_person_onboarding where id = ?",
                UUID.class, candidateId)).isEqualTo(accountId);
    }

    @Test
    void reconciliationNeverAutomaticallyReactivatesAnAssignmentSuspendedBinding() {
        String userId = "reconcile-manual-resume-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        jdbc.update("update employee_position_assignment set status = 'INACTIVE' where id = ?", assignmentId);
        WeComProperties weComProperties = mock(WeComProperties.class);
        when(weComProperties.tenantId()).thenReturn(TENANT);
        when(weComProperties.corpId()).thenReturn(CORP_ID);
        WeComBindingReconciliationWorker worker = new WeComBindingReconciliationWorker(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                weComProperties, new ObjectMapper());

        inTransaction(() -> { worker.reconcile(); return true; });
        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "ACCOUNT_OR_ASSIGNMENT_INACTIVE");

        jdbc.update("update employee_position_assignment set status = 'ACTIVE' where id = ?", assignmentId);
        inTransaction(() -> { worker.reconcile(); return true; });

        assertThat(jdbc.queryForMap("""
                select status, status_reason, preferred_assignment_id,
                       assignment_selection_required
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "ACCOUNT_OR_ASSIGNMENT_INACTIVE")
                .containsEntry("preferred_assignment_id", assignmentId)
                .containsEntry("assignment_selection_required", false);
    }

    @Test
    void reconciliationKeepsMultiPositionBindingActiveWhenPreferredAssignmentRemainsValid() {
        String userId = "reconcile-multi-position-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID preferredAssignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID additionalAssignmentId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, preferredAssignmentId,
                roleAssignmentId, bindingId, userId);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (?, ?, ?, ?, ?, false, 'PERMANENT', current_date, 'ACTIVE')
                """, additionalAssignmentId, TENANT, employeeId,
                HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        WeComProperties weComProperties = mock(WeComProperties.class);
        when(weComProperties.tenantId()).thenReturn(TENANT);
        when(weComProperties.corpId()).thenReturn(CORP_ID);
        WeComBindingReconciliationWorker worker = new WeComBindingReconciliationWorker(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                weComProperties, new ObjectMapper());

        inTransaction(() -> { worker.reconcile(); return true; });

        String expectedSnapshot = WeComUserBindingAdministrationService.sha256(
                java.util.stream.Stream.of(preferredAssignmentId, additionalAssignmentId)
                        .map(UUID::toString).sorted().reduce((left, right) -> left + "," + right).orElse(""));
        assertThat(jdbc.queryForMap("""
                select status, status_reason, preferred_assignment_id,
                       assignment_selection_required, assignment_snapshot_hash
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("status_reason", null)
                .containsEntry("preferred_assignment_id", preferredAssignmentId)
                .containsEntry("assignment_selection_required", false)
                .containsEntry("assignment_snapshot_hash", expectedSnapshot);
    }

    @Test
    void reconciliationRecoversLegacyMultiPositionSuspensionWhenPreferredAssignmentIsValid() {
        String userId = "reconcile-multi-position-recovery-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID preferredAssignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID additionalAssignmentId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, preferredAssignmentId,
                roleAssignmentId, bindingId, userId);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (?, ?, ?, ?, ?, false, 'PERMANENT', current_date, 'ACTIVE')
                """, additionalAssignmentId, TENANT, employeeId,
                HOUSEKEEPING_DEPARTMENT, HOUSEKEEPING_POSITION);
        jdbc.update("""
                update wecom_user_binding
                set status = 'SUSPENDED', status_reason = 'MULTIPLE_ACTIVE_ASSIGNMENTS',
                    assignment_selection_required = true
                where id = ?
                """, bindingId);
        WeComProperties weComProperties = mock(WeComProperties.class);
        when(weComProperties.tenantId()).thenReturn(TENANT);
        when(weComProperties.corpId()).thenReturn(CORP_ID);
        WeComBindingReconciliationWorker worker = new WeComBindingReconciliationWorker(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                weComProperties, new ObjectMapper());

        inTransaction(() -> { worker.reconcile(); return true; });

        assertThat(jdbc.queryForMap("""
                select status, status_reason, preferred_assignment_id,
                       assignment_selection_required
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("status_reason", null)
                .containsEntry("preferred_assignment_id", preferredAssignmentId)
                .containsEntry("assignment_selection_required", false);
    }

    @Test
    void unchangedPopulatedAssignmentSnapshotOnlyRefreshesTheDisplayName() {
        String userId = "unchanged-snapshot-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        OffsetDateTime occurredAt = OffsetDateTime.now().plusSeconds(1);
        WeComDirectoryEvent unchanged = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Refreshed employee name", "1",
                "original-department", "original-position", occurredAt);
        UUID receiptId = insertReceipt(unchanged, occurredAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                unchanged, receiptId, UUID.randomUUID()))).isTrue();

        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, bindingId)).containsEntry("status", "ACTIVE").containsEntry("status_reason", null);
        assertThat(jdbc.queryForObject(
                "select display_name from user_account where id = ?", String.class, accountId))
                .isEqualTo("Refreshed employee name");
        assertThat(jdbc.queryForObject(
                "select name from employee where id = ?", String.class, employeeId))
                .isEqualTo("Refreshed employee name");
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                """, Integer.class, CORP_ID, codec.fingerprint(userId))).isZero();
    }

    @Test
    void missingAssignmentSnapshotBaselineFailsClosedIntoReviewedChange() {
        String userId = "missing-snapshot-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        jdbc.update("update wecom_user_binding set directory_assignment_snapshot_hash = null where id = ?",
                bindingId);
        OffsetDateTime occurredAt = OffsetDateTime.now().plusSeconds(1);
        WeComDirectoryEvent populated = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Existing employee", "1",
                "original-department", "original-position", occurredAt);
        UUID receiptId = insertReceipt(populated, occurredAt.plusSeconds(1));

        assertThat(inTransaction(() -> lifecycleService().handleDirectoryEvent(
                populated, receiptId, UUID.randomUUID()))).isTrue();

        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "DIRECTORY_ASSIGNMENT_CHANGED");
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                  and onboarding_kind = 'ASSIGNMENT_CHANGE' and status = 'WAITING_PROFILE'
                """, Integer.class, CORP_ID, codec.fingerprint(userId))).isOne();
    }

    @Test
    void delayedDisableFollowedByIdentifierReuseCreatesANewMemberCandidate() {
        String userId = "disable-reuse-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId, bindingId, userId);
        OffsetDateTime disabledAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime recreatedAt = disabledAt.plusMinutes(1);
        WeComDirectoryEvent disabled = new WeComDirectoryEvent(
                "change_contact", "update_user", userId, null,
                "Departed employee", "2", null, null, disabledAt);
        WeComDirectoryEvent recreated = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Replacement employee", "1", null, null, recreatedAt);
        UUID disableReceipt = insertReceipt(disabled, disabledAt.plusSeconds(1));
        UUID createReceipt = insertReceipt(recreated, recreatedAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                recreated, createReceipt, UUID.randomUUID()))).isTrue();
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                disabled, disableReceipt, UUID.randomUUID()))).isFalse();

        assertThat(jdbc.queryForMap("""
                select status, status_reason from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", "DIRECTORY_IDENTITY_REUSED");
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and user_id_fingerprint = ?
                  and onboarding_kind = 'NEW_MEMBER' and status = 'WAITING_PROFILE'
                """, Integer.class, CORP_ID, codec.fingerprint(userId))).isOne();
    }

    private void useReviewerPermissions(Set<String> permissions) {
        when(accessPolicy.principal()).thenReturn(new TenantPrincipal(
                TENANT, CEO, "CEO", Set.of("CEO"), permissions,
                Set.of(), Set.of(), true, UUID.randomUUID()));
    }

    private UUID insertPendingHeadquartersCandidate(UUID positionId) {
        UUID candidateId = UUID.randomUUID();
        insertCandidate(candidateId, "headquarters-review-" + candidateId, "NEW_MEMBER", null, null,
                "PENDING_APPROVAL", null, GROUP_HEADQUARTERS, positionId);
        return candidateId;
    }

    private UUID positionId(String code) {
        return jdbc.queryForObject("select id from position_definition where tenant_id = ? and code = ?",
                UUID.class, TENANT, code);
    }

    private void publishSelectableProfile(UUID positionId) {
        int configured = jdbc.update("""
                update position_function_profile_version version
                set authorization_scope_type = 'ORG_UNIT',
                    wecom_self_selectable = true, published_by = ?, published_at = now()
                from position_function_profile profile
                where version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                  and profile.tenant_id = ? and profile.position_id = ?
                  and profile.scope_type = 'GROUP' and version.lifecycle_status = 'PUBLISHED'
                """, CEO, TENANT, positionId);
        if (configured == 0) {
            configured = jdbc.update("""
                    update position_function_profile_version version
                    set lifecycle_status = 'PUBLISHED', authorization_scope_type = 'ORG_UNIT',
                        wecom_self_selectable = true, published_by = ?, published_at = now()
                    from position_function_profile profile
                    where version.tenant_id = profile.tenant_id and version.profile_id = profile.id
                      and profile.tenant_id = ? and profile.position_id = ?
                      and profile.scope_type = 'GROUP' and version.lifecycle_status = 'DRAFT'
                    """, CEO, TENANT, positionId);
        }
        assertThat(configured).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from position_function_profile_version version
                join position_function_profile profile
                  on profile.tenant_id = version.tenant_id and profile.id = version.profile_id
                where profile.tenant_id = ? and profile.position_id = ?
                  and profile.scope_type = 'GROUP' and version.lifecycle_status = 'PUBLISHED'
                  and version.wecom_self_selectable = true
                """, Integer.class, TENANT, positionId)).isOne();
    }

    @Test
    void selfServiceRegistrationResumesPendingApplicationAndStillRequiresApproval() {
        var lifecycle = lifecycleService();
        String userId = "self-registration-" + UUID.randomUUID();
        Counts before = identityCounts();
        UUID id = startSelfCandidate(lifecycle);
        assertThat(jdbc.queryForObject("select identity_verified_at is null from wecom_person_onboarding where id=?", Boolean.class, id)).isTrue();
        URI uri = inTransaction(() -> lifecycle.completeOAuth(id, userId));
        String code = uri.toString().substring(uri.toString().lastIndexOf('=') + 1);
        var session = inTransaction(() -> lifecycle.exchange(code));
        assertThatThrownBy(() -> inTransaction(() -> lifecycle.exchange(code))).isInstanceOf(IllegalArgumentException.class);
        var context = inTransaction(() -> lifecycle.context(session.sessionToken()));
        String login = "self." + id;
        var submitted = inTransaction(() -> lifecycle.submit(new SubmitRequest(session.sessionToken(), "自主注册测试", "13900004879",
                login, "Self-Registration-2026", "Self-Registration-2026", FRONT_DEPARTMENT, FRONT_POSITION, context.rowVersion())));
        assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(identityCounts()).isEqualTo(before);
        assertThatThrownBy(() -> inTransaction(() -> lifecycle.submit(new SubmitRequest(session.sessionToken(), "重复", "13900004879",
                login, "Self-Registration-2026", "Self-Registration-2026", FRONT_DEPARTMENT, FRONT_POSITION, submitted.rowVersion()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不要重复提交");
        UUID second = startSelfCandidate(lifecycle);
        URI resumed = inTransaction(() -> lifecycle.completeOAuth(second, userId));
        var resumeSession = inTransaction(() -> lifecycle.exchange(resumed.toString().substring(resumed.toString().lastIndexOf('=') + 1)));
        var pending = inTransaction(() -> lifecycle.context(resumeSession.sessionToken()));
        assertThat(pending.candidateId()).isEqualTo(id);
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.requestedOrganizationName()).isEqualTo(jdbc.queryForObject("select name from org_unit where id=?", String.class, FRONT_DEPARTMENT));
        assertThat(pending.requestedPositionName()).isEqualTo(jdbc.queryForObject("select name from position_definition where id=?", String.class, FRONT_POSITION));
        var approved = inTransaction(() -> service.approve(id, new DecisionRequest(pending.rowVersion(), "本人核实通过", false, null, null, "REGULAR")));
        assertThat(approved.status()).isEqualTo("APPROVED");
        String storedHash = jdbc.queryForObject("select password_hash from user_account where id=?", String.class, approved.accountId());
        assertThat(new PilotPasswordHasher().matches("Self-Registration-2026", storedHash)).isTrue();
        UUID third = startSelfCandidate(lifecycle);
        assertThat(inTransaction(() -> lifecycle.completeOAuth(third, userId)).toString()).endsWith("error_code=ACCOUNT_READY");
    }

    @Test
    void selfServiceCannotRecreateAnAccountAfterBindingWasSuspendedOrRevoked() {
        var lifecycle = lifecycleService();
        String userId = "self-blocked-" + UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        insertBinding(binding, userId, FRONT_ACCOUNT, null);
        Counts before = identityCounts();
        for (String status : java.util.List.of("SUSPENDED", "REVOKED")) {
            jdbc.update("update wecom_user_binding set status=?,status_reason='ADMIN_REVOKED' where id=?", status, binding);
            UUID candidate = startSelfCandidate(lifecycle);
            assertThat(inTransaction(() -> lifecycle.completeOAuth(candidate, userId)).toString()).endsWith("error_code=MEMBER_UNAVAILABLE");
            assertThat(jdbc.queryForObject("select exchange_code_hash from wecom_person_onboarding where id=?", String.class, candidate)).isNull();
        }
        assertThat(identityCounts()).isEqualTo(before);
    }

    @Test
    void selfServiceUsesCurrentDirectoryFactInsteadOfOldDisabledHistory() {
        var lifecycle = lifecycleService();
        String userId = "self-reactivated-" + UUID.randomUUID();
        UUID old = UUID.randomUUID(), current = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_person_onboarding(id,tenant_id,corp_id,user_id_fingerprint,display_name,
                  onboarding_kind,directory_status,status,source_event_hash,last_event_at)
                values(?,?,?,?,'停用历史','NEW_MEMBER','DISABLED','CANCELLED',?,now()-interval '2 days')
                """, old,TENANT,CORP_ID,codec.fingerprint(userId),WeComDirectorySecretCodec.sha256("old-event:"+old));
        UUID blocked = startSelfCandidate(lifecycle);
        assertThat(inTransaction(() -> lifecycle.completeOAuth(blocked, userId)).toString()).endsWith("error_code=MEMBER_UNAVAILABLE");
        insertCandidate(current, userId, "NEW_MEMBER", null, null, "WAITING_PROFILE", null, null, null);
        UUID resumed = startSelfCandidate(lifecycle);
        URI uri = inTransaction(() -> lifecycle.completeOAuth(resumed, userId));
        String code = uri.toString().substring(uri.toString().lastIndexOf('=') + 1);
        var session = inTransaction(() -> lifecycle.exchange(code));
        assertThat(inTransaction(() -> lifecycle.context(session.sessionToken())).candidateId()).isEqualTo(current);
    }

    private UUID startSelfCandidate(WeComDirectoryOnboardingService lifecycle) {
        var start = inTransaction(lifecycle::startSelfService);
        String state = org.springframework.web.util.UriComponentsBuilder.fromUri(start.authorizationUri()).build().getQueryParams().getFirst("state");
        return uuidValue("select id from wecom_person_onboarding where oauth_state_hash=?", WeComDirectorySecretCodec.sha256(state));
    }

    private WeComDirectoryOnboardingService lifecycleService() {
        return new WeComDirectoryOnboardingService(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                properties, codec, mock(WeComApiClient.class), new ObjectMapper(),
                new PilotPasswordHasher(), transactionManager);
    }

    private UUID startReusableCandidate(
            OpenInvitationResponse invitation,
            WeComDirectoryOnboardingService lifecycle
    ) {
        inTransaction(() -> lifecycle.start(openInvitationToken(invitation)));
        return uuidValue("""
                select id from wecom_person_onboarding
                where open_invitation_id = ?
                order by created_at desc, id desc
                limit 1
                """, invitation.candidateId());
    }

    private static String openInvitationToken(OpenInvitationResponse invitation) {
        String enrollmentUrl = invitation.enrollmentUrl().toString();
        int tokenStart = enrollmentUrl.lastIndexOf("token=");
        if (tokenStart < 0) throw new IllegalArgumentException("邀请链接缺少令牌");
        return enrollmentUrl.substring(tokenStart + "token=".length());
    }

    private void makeCandidateApprovable(UUID candidateId, UUID orgUnitId, UUID positionId) {
        jdbc.update("""
                update wecom_person_onboarding
                set status = 'PENDING_APPROVAL', requested_org_unit_id = ?,
                    requested_position_id = ?, identity_verified_at = now(),
                    profile_submitted_at = now(),
                    requested_display_name = display_name,
                    requested_login_name = ?, requested_password_hash = ?,
                    registration_completed_at = now()
                where id = ?
                """, orgUnitId, positionId, "candidate." + candidateId, TEST_PASSWORD_HASH, candidateId);
    }

    private void insertBinding(UUID bindingId, String userId, UUID accountId, UUID assignmentId) {
        jdbc.update("""
                insert into wecom_user_binding
                    (id, tenant_id, corp_id, wecom_user_id, user_id_fingerprint,
                     account_id, preferred_assignment_id, status, last_verified_at,
                     assignment_snapshot_hash, directory_assignment_snapshot_hash,
                     assignment_selection_required, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now(), ?, ?, false, ?)
                """, bindingId, TENANT, CORP_ID, userId, codec.fingerprint(userId),
                accountId, assignmentId,
                assignmentId == null ? null : WeComDirectorySecretCodec.sha256(assignmentId.toString()),
                WeComDirectorySecretCodec.sha256("original-department|original-position"),
                CEO);
    }

    private void insertCandidate(
            UUID candidateId,
            String userId,
            String onboardingKind,
            UUID sourceBindingId,
            UUID sourceAccountId,
            String status,
            UUID conflictingAccountId,
            UUID orgUnitId,
            UUID positionId
    ) {
        jdbc.update("""
                insert into wecom_person_onboarding
                    (id, tenant_id, corp_id, user_id_fingerprint, user_id_ciphertext,
                     display_name, onboarding_kind, source_binding_id, source_account_id,
                     directory_status, status, requested_org_unit_id, requested_position_id,
                     identity_verified_at, profile_submitted_at, conflicting_account_id,
                     requested_display_name, requested_login_name, requested_password_hash,
                     registration_completed_at,
                     source_event_hash, last_event_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, now(), now(), ?,
                        ?, ?, ?, now(), ?, now())
                """, candidateId, TENANT, CORP_ID, codec.fingerprint(userId), codec.encrypt(userId),
                "Directory integration test", onboardingKind, sourceBindingId, sourceAccountId,
                status, orgUnitId, positionId, conflictingAccountId,
                "Directory integration test", "candidate." + candidateId, TEST_PASSWORD_HASH,
                WeComDirectorySecretCodec.sha256("event:" + candidateId));
    }

    private void insertSourceIdentity(
            UUID accountId,
            UUID employeeId,
            UUID assignmentId,
            UUID roleAssignmentId,
            UUID bindingId,
            String userId
    ) {
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name, status)
                values (?, ?, ?, 'Assignment source', 'ACTIVE')
                """, accountId, TENANT, "source." + accountId);
        jdbc.update("""
                insert into employee
                    (id, tenant_id, account_id, employee_no, name, employment_status, hired_on)
                values (?, ?, ?, ?, 'Assignment source', 'ACTIVE', current_date)
                """, employeeId, TENANT, accountId, "SOURCE-" + employeeId);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id,
                     is_primary, assignment_type, valid_from, status)
                values (?, ?, ?, ?, ?, true, 'PERMANENT', current_date, 'ACTIVE')
                """, assignmentId, TENANT, employeeId, FRONT_DEPARTMENT, FRONT_POSITION);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id,
                     scope_type, valid_from, granted_by, source_type, source_assignment_id)
                select ?, ?, ?, profile.default_role_id, ?, 'ORG_UNIT', now(), ?,
                       'POSITION_ASSIGNMENT', ?
                from position_function_profile profile
                where profile.tenant_id = ? and profile.position_id = ? and profile.scope_type = 'GROUP'
                """, roleAssignmentId, TENANT, accountId, FRONT_DEPARTMENT, CEO,
                assignmentId, TENANT, FRONT_POSITION);
        insertBinding(bindingId, userId, accountId, assignmentId);
    }

    private void assertLaterRemovalFollowsUnprocessedRename(
            String removalChangeType, String removalStatus, String expectedReason
    ) {
        String firstUserId = "removal-lineage-a-" + UUID.randomUUID();
        String secondUserId = "removal-lineage-b-" + UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        insertSourceIdentity(accountId, employeeId, assignmentId, roleAssignmentId,
                bindingId, firstUserId);
        OffsetDateTime renameAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime removalAt = renameAt.plusMinutes(1);
        WeComDirectoryEvent rename = new WeComDirectoryEvent(
                "change_contact", "update_user", firstUserId, secondUserId,
                "Existing employee", "1", null, null, renameAt);
        WeComDirectoryEvent removal = new WeComDirectoryEvent(
                "change_contact", removalChangeType, secondUserId, null,
                "Existing employee", removalStatus, null, null, removalAt);
        UUID renameReceipt = insertReceipt(rename, renameAt.plusSeconds(1));
        UUID removalReceipt = insertReceipt(removal, removalAt.plusSeconds(1));
        WeComDirectoryOnboardingService lifecycle = lifecycleService();

        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                removal, removalReceipt, UUID.randomUUID()))).isTrue();
        assertThat(jdbc.queryForMap("""
                select account_id, status, status_reason, wecom_user_id
                from wecom_user_binding where id = ?
                """, bindingId))
                .containsEntry("account_id", accountId)
                .containsEntry("status", "SUSPENDED")
                .containsEntry("status_reason", expectedReason)
                .containsEntry("wecom_user_id", firstUserId);
        assertThat(inTransaction(() -> lifecycle.handleDirectoryEvent(
                rename, renameReceipt, UUID.randomUUID()))).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_person_onboarding
                where corp_id = ? and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                  and user_id_fingerprint in (?, ?)
                """, Integer.class, CORP_ID,
                codec.fingerprint(firstUserId), codec.fingerprint(secondUserId))).isZero();
    }

    private UUID insertSupersededInvitationCandidate(
            String userId, String status, String failureCode
    ) {
        UUID candidateId = UUID.randomUUID();
        OffsetDateTime candidateAt = OffsetDateTime.now().plusSeconds(1);
        OffsetDateTime replacementAt = candidateAt.plusMinutes(1);
        WeComDirectoryEvent candidateEvent = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Old private display name", "1", null, null, candidateAt);
        WeComDirectoryEvent replacement = new WeComDirectoryEvent(
                "change_contact", "create_user", userId, null,
                "Replacement employee", "1", null, null, replacementAt);
        UUID candidateReceipt = insertReceipt(candidateEvent, candidateAt.plusSeconds(1));
        jdbc.update("""
                update wecom_directory_event_receipt
                set status = 'SUCCEEDED', processed_at = now(), payload_ciphertext = ''
                where id = ?
                """,
                candidateReceipt);
        insertCandidate(candidateId, userId, "NEW_MEMBER", null, null,
                "EXPIRED".equals(status) ? "WAITING_PROFILE" : status,
                null, FRONT_DEPARTMENT, FRONT_POSITION);
        jdbc.update("""
                update wecom_person_onboarding
                set status = ?, expired_at = case when ? = 'EXPIRED' then now() else null end,
                    last_event_at = ?, last_event_receipt_id = ?, failure_code = ?
                where id = ?
                """, status, status, candidateAt, candidateReceipt, failureCode, candidateId);
        insertReceipt(replacement, replacementAt.plusSeconds(1));
        return candidateId;
    }

    private void assertCancelledWithoutIdentityOrInvitation(UUID candidateId) {
        assertThat(jdbc.queryForMap("""
                select status, user_id_ciphertext is null as identity_cleared,
                       invitation_token_hash is null and invitation_issued_at is null
                           and invitation_expires_at is null as invitation_cleared,
                       oauth_state_hash is null and browser_verifier_hash is null
                           and provider_code_hash is null as oauth_cleared,
                       exchange_code_hash is null and session_token_hash is null
                           as session_cleared,
                       identity_verified_at is null and requested_org_unit_id is null
                           and requested_position_id is null and profile_submitted_at is null
                           and conflicting_account_id is null as profile_cleared
                from wecom_person_onboarding where id = ?
                """, candidateId))
                .containsEntry("status", "CANCELLED")
                .containsEntry("identity_cleared", true)
                .containsEntry("invitation_cleared", true)
                .containsEntry("oauth_cleared", true)
                .containsEntry("session_cleared", true)
                .containsEntry("profile_cleared", true);
    }

    private Counts identityCounts() {
        return new Counts(
                count("user_account"),
                count("employee"),
                count("employee_position_assignment"),
                count("role_assignment"),
                count("wecom_user_binding"));
    }

    private UUID insertReceipt(WeComDirectoryEvent event, OffsetDateTime receivedAt) {
        UUID receiptId = UUID.randomUUID();
        String payload = event.toString() + receiptId;
        jdbc.update("""
                insert into wecom_directory_event_receipt
                    (id, tenant_id, corp_id, event_key_hash, payload_hash, payload_ciphertext,
                     event_type, change_type, event_priority, user_id_fingerprint,
                     previous_user_id_fingerprint, occurred_at, status, correlation_id, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                """, receiptId, TENANT, CORP_ID,
                WeComDirectorySecretCodec.sha256("receipt:" + receiptId),
                WeComDirectorySecretCodec.sha256(payload), codec.encrypt(payload),
                event.eventType(), event.changeType(), event.priority(),
                codec.fingerprint(event.effectiveUserId()),
                event.newUserId() == null ? null : codec.fingerprint(event.userId()),
                event.occurredAt(), UUID.randomUUID(), receivedAt);
        return receiptId;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private UUID uuidValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, UUID.class, arguments);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Counts(int accounts, int employees, int assignments, int roles, int bindings) { }
    private record Attempt(ApprovalResponse response, Throwable failure) { }
}
