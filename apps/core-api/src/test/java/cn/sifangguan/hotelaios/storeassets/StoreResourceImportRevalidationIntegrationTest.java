package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.imports.XlsxWorkbook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreResourceImportRevalidationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String ADMIN = PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String MANAGER = "19000000-0000-0000-0000-000000000002";
    private static final String MANAGER_ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE = "19000000-0000-0000-0000-000000000003";
    private static final String EMPLOYEE_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String SUPERVISOR = "19000000-0000-0000-0000-000000000004";
    private static final String SUPERVISOR_ASSIGNMENT = "19200000-0000-0000-0000-000000000003";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String OTHER_HOTEL = "12000000-0000-0000-0000-000000000004";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String JDBC_URL = jdbcUrl();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private XlsxWorkbook workbook;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
    }

    @BeforeEach
    void provisionAdministratorOnly() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, "19000000-0000-0000-0000-000000000001");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void invalidAssignmentCanBeRepairedAndSameFileRevalidatedWithoutLosingJobIdentity() throws Exception {
        MockMultipartFile file = workbookFile(true);
        expireResponsibleAssignment(true);
        JsonNode invalid;
        try {
            invalid = preview(file);
            assertThat(invalid.path("status").asText()).isEqualTo("INVALID");
            assertThat(invalid.path("errorCount").asInt()).isOne();
        } finally {
            expireResponsibleAssignment(false);
        }
        JsonNode ready = preview(file);
        String jobId = ready.path("id").asText();
        assertThat(jobId).isEqualTo(invalid.path("id").asText());
        assertThat(ready.path("createdAt")).isEqualTo(invalid.path("createdAt"));
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("revalidated").asBoolean()).isTrue();
        assertThat(ready.path("errorCount").asInt()).isZero();
        assertThat(ready.path("preview").path("assets").get(0).path("responsibleAssignmentId").asText())
                .isEqualTo(MANAGER_ASSIGNMENT);
        assertThat(countJobs(ready)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log where tenant_id = ?::uuid and resource_id = ?::uuid
                  and action = 'STORE_RESOURCE_IMPORT_REPREVIEWED'
                """, Integer.class, TENANT, jobId)).isOne();
    }

    @Test
    void assignmentChangeAtApplyCanBeRevalidatedAndAppliedUploadRemainsImmutable() throws Exception {
        MockMultipartFile file = workbookFile(true);
        JsonNode initial = preview(file);
        String jobId = initial.path("id").asText();
        expireResponsibleAssignment(true);
        try {
            apply(jobId, 409);
            assertThat(preview(file).path("status").asText()).isEqualTo("INVALID");
            assertThat(jdbc.queryForObject("""
                    select count(*) from tabular_import_result_link where job_id = ?::uuid
                    """, Integer.class, jobId)).isZero();
        } finally {
            expireResponsibleAssignment(false);
        }
        JsonNode restored = preview(file);
        assertThat(restored.path("id").asText()).isEqualTo(jobId);
        assertThat(restored.path("status").asText()).isEqualTo("READY");
        JsonNode applied = apply(jobId, 200);
        JsonNode duplicate = preview(file);
        assertThat(duplicate.path("status").asText()).isEqualTo("APPLIED");
        assertThat(duplicate.path("id")).isEqualTo(applied.path("id"));
        assertThat(duplicate.path("result")).isEqualTo(applied.path("result"));
        assertThat(duplicate.path("appliedAt")).isEqualTo(applied.path("appliedAt"));
        assertThat(duplicate.path("duplicateUpload").asBoolean()).isTrue();
        assertThat(duplicate.path("revalidated").asBoolean()).isFalse();
        apply(jobId, 200);
        assertSingleAppliedJob(jobId);
        assertThat(countJobs(duplicate)).isOne();
    }

    @Test
    void newlyConflictingCodeIsRefreshedAndCorrectionCanRestoreReady() throws Exception {
        MockMultipartFile file = workbookFile(false);
        JsonNode ready = preview(file);
        String jobId = ready.path("id").asText();
        String sku = ready.path("preview").path("items").get(0).path("sku").asText();
        UUID conflictingItem = UUID.randomUUID();
        jdbc.update("""
                insert into store_resource_item (id, tenant_id, hotel_org_unit_id, sku, name, category, unit)
                values (?, ?::uuid, ?::uuid, ?, '冲突物品', 'CONSUMABLE', '支')
                """, conflictingItem, TENANT, HOTEL, sku);
        apply(jobId, 409);
        JsonNode invalid = preview(file);
        assertThat(invalid.path("id").asText()).isEqualTo(jobId);
        assertThat(invalid.path("status").asText()).isEqualTo("INVALID");
        jdbc.update("update store_resource_item set sku = ? where id = ?", sku + "-FIXED", conflictingItem);
        assertThat(preview(file).path("status").asText()).isEqualTo("READY");
        assertThat(apply(jobId, 200).path("status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void concurrentFirstUploadsAndApplyRefreshKeepOneJobAndOneLedgerFact() throws Exception {
        MockMultipartFile file = workbookFile(true);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return preview(file); });
            var second = executor.submit(() -> { start.await(); return preview(file); });
            start.countDown();
            JsonNode one = first.get(20, TimeUnit.SECONDS);
            JsonNode two = second.get(20, TimeUnit.SECONDS);
            assertThat(one.path("id")).isEqualTo(two.path("id"));
            assertThat(countJobs(one)).isOne();
            String jobId = one.path("id").asText();
            CountDownLatch applyStart = new CountDownLatch(1);
            var applying = executor.submit(() -> { applyStart.await(); return apply(jobId, 200); });
            var refreshing = executor.submit(() -> { applyStart.await(); return preview(file); });
            applyStart.countDown();
            assertThat(applying.get(20, TimeUnit.SECONDS).path("status").asText()).isEqualTo("APPLIED");
            assertThat(refreshing.get(20, TimeUnit.SECONDS).path("status").asText()).isIn("READY", "APPLIED");
            assertThat(preview(file).path("status").asText()).isEqualTo("APPLIED");
            assertSingleAppliedJob(jobId);
        }
    }

    @Test
    void ordinaryBusinessActorsCannotPreviewApplyOrMutateAndManagerReadKeepsHotelScope() throws Exception {
        JsonNode job = preview(workbookFile(false));
        for (String[] actor : List.of(new String[]{MANAGER, MANAGER_ASSIGNMENT},
                new String[]{"19000000-0000-0000-0000-000000000001", null},
                new String[]{EMPLOYEE, EMPLOYEE_ASSIGNMENT}, new String[]{SUPERVISOR, SUPERVISOR_ASSIGNMENT})) {
            response(identity(multipart("/api/v1/store-resources/hotels/{hotelId}/imports/preview", HOTEL)
                    .file(workbookFile(false)), actor[0], actor[1]), 403);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply",
                    HOTEL, job.path("id").asText()), actor[0], actor[1])
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("{}"), 403);
            response(identity(get("/api/v1/store-resources/hotels/{hotelId}/balances", HOTEL), actor[0], actor[1]),
                    actor[0].equals(EMPLOYEE) || actor[0].equals(SUPERVISOR) ? 403 : 200);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/items", HOTEL), actor[0], actor[1])
                    .contentType("application/json").content("""
                            {"sku":"DENIED","name":"拒绝写入","category":"CONSUMABLE","unit":"支","unitCost":1,"minimumQuantity":0}
                            """), 403);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/assets", HOTEL), actor[0], actor[1])
                    .contentType("application/json").content("""
                            {"assetCode":"DENIED","name":"拒绝写入","category":"电器","unit":"台","originalValue":10}
                            """), 403);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL), actor[0], actor[1])
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("""
                            {"itemId":"%s","movementType":"ADJUSTMENT","quantity":1,"reason":"不得直接调账"}
                            """.formatted(UUID.randomUUID())), 403);
        }
        response(identity(get("/api/v1/store-resources/hotels/{hotelId}/balances", OTHER_HOTEL), MANAGER,
                MANAGER_ASSIGNMENT), 403);
    }

    @Test
    void scopedImporterCannotReadOrRefreshAnotherHotelsJob() throws Exception {
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                insert into role_assignment (id, tenant_id, account_id, role_id, scope_org_unit_id,
                    scope_type, valid_from, granted_by)
                select ?, ?::uuid, ?::uuid, id, ?::uuid, 'ORG_TREE', now(), ?::uuid
                from app_role where tenant_id = ?::uuid and code = 'PLATFORM_ADMIN'
                """, grantId, TENANT, MANAGER, HOTEL, ADMIN, TENANT);
        try {
            MockMultipartFile file = workbookFile(false);
            JsonNode otherJob = response(identity(multipart(
                    "/api/v1/store-resources/hotels/{hotelId}/imports/preview", OTHER_HOTEL).file(file), ADMIN, null), 201);
            response(identity(get("/api/v1/store-resources/hotels/{hotelId}/imports", HOTEL), MANAGER, null), 200);
            response(identity(multipart("/api/v1/store-resources/hotels/{hotelId}/imports/preview", OTHER_HOTEL)
                    .file(file), MANAGER, null), 403);
            response(identity(get("/api/v1/store-resources/hotels/{hotelId}/imports", OTHER_HOTEL), MANAGER, null), 403);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply",
                    OTHER_HOTEL, otherJob.path("id").asText()), MANAGER, null)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("{}"), 403);
            response(identity(post("/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply",
                    HOTEL, otherJob.path("id").asText()), MANAGER, null)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("{}"), 404);
        } finally {
            jdbc.update("delete from role_assignment where id = ?", grantId);
        }
    }

    private JsonNode preview(MockMultipartFile file) throws Exception {
        return response(identity(multipart("/api/v1/store-resources/hotels/{hotelId}/imports/preview", HOTEL)
                .file(file), ADMIN, null), 201);
    }

    private JsonNode apply(String jobId, int expectedStatus) throws Exception {
        return response(identity(post("/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply",
                HOTEL, jobId), ADMIN, null).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{}"), expectedStatus);
    }

    private MockMultipartFile workbookFile(boolean withResponsibleAsset) {
        String suffix = UUID.randomUUID().toString();
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("物品与期初库存", List.of(
                List.of("物品编码*", "物品名称*", "分类*", "计量单位*", "单位成本*", "最低库存*", "期初数量*"),
                List.of("ITEM-" + suffix, "预检物品", "耗品", "支", "2", "1", "5")));
        List<String> headers = List.of("资产编号*", "资产名称*", "资产分类*", "规格型号", "序列号", "计量单位*",
                "原值*", "购置日期", "存放位置", "责任人工号", "责任人岗位编码", "状态");
        sheets.put("固定资产", withResponsibleAsset ? List.of(headers,
                List.of("ASSET-" + suffix, "预检资产", "电器", "", "", "台", "10", "", "", "E-GM-001", "GENERAL_MANAGER", "在用"))
                : List.of(headers));
        return new MockMultipartFile("file", "revalidation.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook.write(sheets));
    }

    private void expireResponsibleAssignment(boolean expired) {
        jdbc.update("update employee_position_assignment set valid_to = "
                + (expired ? "current_date - 1" : "null") + " where tenant_id = ?::uuid and id = ?::uuid",
                TENANT, MANAGER_ASSIGNMENT);
    }

    private int countJobs(JsonNode job) {
        return jdbc.queryForObject("""
                select count(*) from tabular_import_job where tenant_id = ?::uuid and sha256 =
                  (select sha256 from tabular_import_job where id = ?::uuid)
                """, Integer.class, TENANT, job.path("id").asText());
    }

    private void assertSingleAppliedJob(String jobId) {
        assertThat(jdbc.queryForObject("select status from tabular_import_job where id = ?::uuid", String.class, jobId))
                .isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement where tenant_id = ?::uuid and source_id = ?::uuid
                  and source_type = 'IMPORT'
                """, Integer.class, TENANT, jobId)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from tabular_import_result_link where job_id = ?::uuid",
                Integer.class, jobId)).isEqualTo(3);
    }

    private MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request, String actor, String assignment) {
        request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actor);
        if (assignment != null) request.header("X-Assignment-Id", assignment);
        return request;
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn()
                .getResponse().getContentAsString();
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static String jdbcUrl() {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
