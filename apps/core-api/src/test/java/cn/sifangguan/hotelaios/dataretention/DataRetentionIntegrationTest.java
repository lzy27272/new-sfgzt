package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataRetentionIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String WORK_RECORD = "19800000-0000-0000-0000-000000000001";
    private static final String GROUP_ADMIN = "55000000-0000-0000-0000-000000000010";
    private static final String GROUP_ADMIN_ROLE = "55000000-0000-0000-0000-000000000011";
    private static final String GROUP_ADMIN_ASSIGNMENT = "55000000-0000-0000-0000-000000000012";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
        registry.add("app.data-retention.cleanup-worker-enabled", () -> false);
    }

    @BeforeEach
    void ensureAdministratorFixtures() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, CEO);
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name, status)
                values (?::uuid, ?::uuid, 'integration.group.admin', '集成测试集团管理员', 'ACTIVE')
                on conflict (tenant_id, login_name) do nothing
                """, GROUP_ADMIN, TENANT);
        jdbc.update("""
                insert into app_role (id, tenant_id, code, name, role_type)
                values (?::uuid, ?::uuid, 'GROUP_ADMIN', '集团管理员', 'SYSTEM')
                on conflict (tenant_id, code) do nothing
                """, GROUP_ADMIN_ROLE, TENANT);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_type, granted_by, valid_from)
                select ?::uuid, ?::uuid, ?::uuid, role.id, 'TENANT', ?::uuid, now()
                from app_role role
                where role.tenant_id = ?::uuid and role.code = 'GROUP_ADMIN'
                on conflict (tenant_id, id) do nothing
                """, GROUP_ADMIN_ASSIGNMENT, TENANT, GROUP_ADMIN, CEO, TENANT);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void policyApiAndAttachmentLifecycleAreSafeByDefault() throws Exception {
        mockMvc.perform(identity(get("/api/v1/admin/data-retention-policy"), CEO))
                .andExpect(status().isForbidden());

        mockMvc.perform(identity(get("/api/v1/admin/data-retention-policy"),
                        PlatformAdminTestFixture.ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageRetentionDays").value(5))
                .andExpect(jsonPath("$.documentRetentionDays").value(30))
                .andExpect(jsonPath("$.employeeMetricRetentionMonths").value(36))
                .andExpect(jsonPath("$.pmsRetentionMonths").value(36))
                .andExpect(jsonPath("$.otaRetentionMonths").value(36))
                .andExpect(jsonPath("$.kpiRetentionMonths").value(36))
                .andExpect(jsonPath("$.rawPayloadRetentionDays").value(90))
                .andExpect(jsonPath("$.imageCleanupEnabled").value(false))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(identity(get("/api/v1/admin/data-retention-policy"), GROUP_ADMIN))
                .andExpect(status().isOk());

        mockMvc.perform(identity(put("/api/v1/admin/data-retention-policy"),
                        PlatformAdminTestFixture.ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageRetentionDays": 5,
                                  "documentRetentionDays": 30,
                                  "employeeMetricRetentionMonths": 36,
                                  "pmsRetentionMonths": 36,
                                  "otaRetentionMonths": 36,
                                  "kpiRetentionMonths": 36,
                                  "rawPayloadRetentionDays": 90,
                                  "imageCleanupEnabled": false,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(identity(put("/api/v1/admin/data-retention-policy"),
                        PlatformAdminTestFixture.ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageRetentionDays": 5,
                                  "documentRetentionDays": 30,
                                  "employeeMetricRetentionMonths": 36,
                                  "pmsRetentionMonths": 36,
                                  "otaRetentionMonths": 36,
                                  "kpiRetentionMonths": 36,
                                  "rawPayloadRetentionDays": 90,
                                  "imageCleanupEnabled": false,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = ?::uuid and action = 'DATA_RETENTION_POLICY_UPDATED'
                """, Integer.class, TENANT)).isEqualTo(1);

        UUID webp = UUID.fromString("55000000-0000-0000-0000-000000000101");
        UUID jpg = UUID.fromString("55000000-0000-0000-0000-000000000102");
        UUID pdf = UUID.fromString("55000000-0000-0000-0000-000000000103");
        UUID heic = UUID.fromString("55000000-0000-0000-0000-000000000104");
        insertAttachment(webp, "image/webp", 111);
        insertAttachment(jpg, "image/jpg", 222);
        insertAttachment(pdf, "application/pdf", 444);

        OffsetDateTime reviewedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(40).withNano(0);
        assertThat(jdbc.update("""
                update work_record
                set status = 'APPROVED', reviewed_by_account_id = ?::uuid,
                    reviewed_at = ?, review_reason = '留存策略集成测试', row_version = row_version + 1
                where tenant_id = ?::uuid and id = ?::uuid and status = 'SUBMITTED'
                """, CEO, reviewedAt, TENANT, WORK_RECORD)).isEqualTo(1);

        insertAttachment(heic, "image/heic", 333);
        jdbc.update("""
                update attachment
                set legal_hold = true, legal_hold_reason = '审计保留', legal_hold_set_at = now(),
                    legal_hold_set_by = ?::uuid
                where tenant_id = ?::uuid and id = ?
                """, PlatformAdminTestFixture.ACCOUNT_ID, TENANT, jpg);

        assertRetentionDays(webp, 5);
        assertRetentionDays(jpg, 5);
        assertRetentionDays(heic, 5);
        assertRetentionDays(pdf, 30);
        assertThat(jdbc.queryForObject("""
                select count(*) from attachment
                where tenant_id = ?::uuid and id in (?, ?, ?, ?)
                  and storage_status = 'AVAILABLE' and deleted_at is null
                """, Integer.class, TENANT, webp, jpg, pdf, heic)).isEqualTo(4);

        mockMvc.perform(identity(get("/api/v1/admin/data-retention-preview"), GROUP_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.totalSizeBytes").value(444))
                .andExpect(jsonPath("$.cleanupEnabled").value(false));

        UUID newTenant = UUID.fromString("55000000-0000-0000-0000-000000000201");
        jdbc.update("""
                insert into tenant (id, code, name)
                values (?, 'RETENTION-FUTURE', '未来租户默认策略')
                """, newTenant);
        assertThat(jdbc.queryForObject("""
                select image_retention_days = 5
                       and document_retention_days = 30
                       and employee_metric_retention_months = 36
                       and pms_retention_months = 36
                       and ota_retention_months = 36
                       and kpi_retention_months = 36
                       and raw_payload_retention_days = 90
                       and image_cleanup_enabled = false
                from data_retention_policy where tenant_id = ?
                """, Boolean.class, newTenant)).isTrue();
    }

    @Test
    void workbenchCaptureIsIdempotentVersionedAndConcurrentSafe() throws Exception {
        LocalDate businessDate = LocalDate.now();
        MockHttpServletRequestBuilder request = identity(
                get("/api/v1/team/workbench-summary").queryParam("businessDate", businessDate.toString()),
                PlatformAdminTestFixture.ACCOUNT_ID);

        mockMvc.perform(request).andExpect(status().isOk());
        int initialCount = snapshotCount(businessDate);
        assertThat(initialCount).isPositive().isEven();
        mockMvc.perform(identity(
                        get("/api/v1/team/workbench-summary").queryParam("businessDate", businessDate.toString()),
                        PlatformAdminTestFixture.ACCOUNT_ID))
                .andExpect(status().isOk());
        assertThat(snapshotCount(businessDate)).isEqualTo(initialCount);

        UUID expectationId = jdbc.queryForObject("""
                select expectation.id
                from work_expectation expectation
                where expectation.tenant_id = ?::uuid
                  and expectation.business_date = ?
                  and expectation.status = 'AVAILABLE'
                  and not exists (
                      select 1 from work_record record
                      where record.tenant_id = expectation.tenant_id
                        and record.work_expectation_id = expectation.id
                        and record.submitted_at is not null
                  )
                order by expectation.position_assignment_id, expectation.id
                limit 1
                """, UUID.class, TENANT, businessDate);
        assertThat(expectationId).isNotNull();
        jdbc.update("""
                update work_expectation set status = 'SATISFIED', row_version = row_version + 1
                where tenant_id = ?::uuid and id = ?
                """, TENANT, expectationId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = List.of(
                    executor.submit(() -> workbenchRequest(businessDate)),
                    executor.submit(() -> workbenchRequest(businessDate))
            );
            for (Future<MvcResult> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(snapshotCount(businessDate)).isEqualTo(initialCount + 2);
        assertThat(jdbc.queryForObject("""
                select count(*) from employee_performance_snapshot current_snapshot
                where current_snapshot.tenant_id = ?::uuid
                  and current_snapshot.period_start in (?, date_trunc('month', ?::date)::date)
                  and current_snapshot.revision_no = 2
                  and current_snapshot.supersedes_snapshot_id is not null
                  and current_snapshot.calculation_version = 'employee-work-completion-v1'
                  and jsonb_typeof(current_snapshot.source_rule_versions) = 'array'
                """, Integer.class, TENANT, businessDate, businessDate.toString())).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update("""
                update employee_performance_snapshot
                set completion_rate = completion_rate
                where tenant_id = ?::uuid
                  and period_start = ?
                """, TENANT, businessDate))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("employee performance snapshots are versioned");
    }

    private MvcResult workbenchRequest(LocalDate businessDate) throws Exception {
        return mockMvc.perform(identity(
                        get("/api/v1/team/workbench-summary").queryParam("businessDate", businessDate.toString()),
                        PlatformAdminTestFixture.ACCOUNT_ID))
                .andReturn();
    }

    private int snapshotCount(LocalDate businessDate) {
        return jdbc.queryForObject("""
                select count(*) from employee_performance_snapshot
                where tenant_id = ?::uuid
                  and period_start in (?, date_trunc('month', ?::date)::date)
                """, Integer.class, TENANT, businessDate, businessDate.toString());
    }

    private void insertAttachment(UUID id, String mediaType, long sizeBytes) {
        jdbc.update("""
                insert into attachment
                    (id, tenant_id, work_record_id, object_key, original_name,
                     media_type, size_bytes, sha256, scan_status)
                values (?, ?::uuid, ?::uuid, ?, ?, ?, ?, repeat('a', 64), 'CLEAN')
                """, id, TENANT, WORK_RECORD, "retention-test/" + id,
                id + ".bin", mediaType, sizeBytes);
    }

    private void assertRetentionDays(UUID attachmentId, int days) {
        assertThat(jdbc.queryForObject("""
                select extract(epoch from (retention_until - retention_basis_at)) / 86400
                from attachment where tenant_id = ?::uuid and id = ?
                """, Double.class, TENANT, attachmentId)).isEqualTo(days);
    }

    private MockHttpServletRequestBuilder identity(
            MockHttpServletRequestBuilder request,
            String accountId
    ) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", accountId);
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
