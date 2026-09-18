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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreResourceFlowIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";

    private static final String TRANSFEROR_ACCOUNT = "19000000-0000-0000-0000-000000000002";
    private static final String TRANSFEROR_ASSIGNMENT = "19200000-0000-0000-0000-000000000001";
    private static final String INELIGIBLE_ASSIGNMENT = "19200000-0000-0000-0000-000000000002";
    private static final String RECIPIENT_ACCOUNT = "19000000-0000-0000-0000-000000000008";
    private static final String RECIPIENT_ASSIGNMENT = "19200000-0000-0000-0000-000000000008";
    private static final String APPROVER_ACCOUNT = "19000000-0000-0000-0000-000000000005";
    private static final String APPROVER_ASSIGNMENT = "19200000-0000-0000-0000-000000000005";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private XlsxWorkbook workbook;

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
    void grantTestActorsAdministrativeStoreResourceAccess() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, CEO);
        grantPlatformAdministration(TRANSFEROR_ACCOUNT,
                "19580000-0000-0000-0000-000000000001");
        grantPlatformAdministration(RECIPIENT_ACCOUNT,
                "19580000-0000-0000-0000-000000000002");
        grantPlatformAdministration(APPROVER_ACCOUNT,
                "19580000-0000-0000-0000-000000000003");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void stockLedgerIsIdempotentAndThreePartyHandoverTransfersAssetResponsibility() throws Exception {
        JsonNode item = response(identity(post("/api/v1/store-resources/hotels/{hotelId}/items", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "sku":"TEST-TOOTHBRUSH",
                          "name":"测试牙刷",
                          "category":"CONSUMABLE",
                          "unit":"支",
                          "unitCost":0,
                          "minimumQuantity":2
                        }
                        """), 201);
        String itemId = item.path("id").asText();
        assertThat(item.path("current_quantity").decimalValue()).isEqualByComparingTo("0.0000");

        String receiptKey = "store-resource-test-receipt-" + UUID.randomUUID();
        String receiptBody = """
                {
                  "itemId":"%s",
                  "movementType":"RECEIPT",
                  "quantity":10,
                  "unitCost":1.25,
                  "reason":"测试入库"
                }
                """.formatted(itemId);
        JsonNode receipt = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", receiptKey)
                .contentType("application/json")
                .content(receiptBody), 201);
        JsonNode replayedReceipt = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", receiptKey)
                .contentType("application/json")
                .content(receiptBody), 201);
        assertThat(replayedReceipt.path("id").asText()).isEqualTo(receipt.path("id").asText());
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement
                where tenant_id = ?::uuid and idempotency_key = ?
                """, Integer.class, TENANT, receiptKey)).isOne();

        response(identity(post("/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", receiptKey)
                .contentType("application/json")
                .content("""
                        {
                          "itemId":"%s",
                          "movementType":"RECEIPT",
                          "quantity":11,
                          "unitCost":1.25,
                          "reason":"测试入库"
                        }
                        """.formatted(itemId)), 409);

        JsonNode consumption = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", "store-resource-test-consumption-" + UUID.randomUUID())
                .contentType("application/json")
                .content("""
                        {
                          "itemId":"%s",
                          "movementType":"CONSUMPTION",
                          "quantity":3,
                          "reason":"客房工作消耗"
                        }
                        """.formatted(itemId)), 201);
        assertThat(consumption.path("quantity_delta").decimalValue()).isEqualByComparingTo("-3.0000");
        assertThat(consumption.path("quantity_after").decimalValue()).isEqualByComparingTo("7.0000");
        assertThat(consumption.path("unit_cost_snapshot").decimalValue()).isEqualByComparingTo("1.2500");
        assertThat(consumption.path("value_delta").decimalValue()).isEqualByComparingTo("-3.7500");

        JsonNode balances = response(identity(get(
                        "/api/v1/store-resources/hotels/{hotelId}/balances", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT), 200);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).path("quantity").decimalValue()).isEqualByComparingTo("7.0000");
        assertThat(balances.get(0).path("inventory_value").decimalValue())
                .isEqualByComparingTo("8.7500");
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement
                where tenant_id = ?::uuid and item_id = ?::uuid
                """, Integer.class, TENANT, itemId)).isEqualTo(2);

        response(identity(post("/api/v1/store-resources/hotels/{hotelId}/movements", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", "store-resource-test-overdraw-" + UUID.randomUUID())
                .contentType("application/json")
                .content("""
                        {
                          "itemId":"%s",
                          "movementType":"CONSUMPTION",
                          "quantity":8,
                          "reason":"测试负库存拦截"
                        }
                        """.formatted(itemId)), 409);
        assertThat(jdbc.queryForObject("""
                select quantity from store_inventory_balance
                where tenant_id = ?::uuid and item_id = ?::uuid
                """, BigDecimal.class, TENANT, itemId)).isEqualByComparingTo("7.0000");
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement
                where tenant_id = ?::uuid and item_id = ?::uuid
                """, Integer.class, TENANT, itemId)).isEqualTo(2);

        JsonNode asset = response(identity(post("/api/v1/store-resources/hotels/{hotelId}/assets", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "assetCode":"TEST-ASSET-001",
                          "name":"测试洗衣机",
                          "category":"客房设备",
                          "unit":"台",
                          "originalValue":3500,
                          "location":"洗衣房",
                          "responsibleAssignmentId":"%s"
                        }
                        """.formatted(TRANSFEROR_ASSIGNMENT)), 201);
        String assetId = asset.path("id").asText();

        JsonNode participants = response(identity(get(
                        "/api/v1/store-resources/hotels/{hotelId}/assignment-options", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT), 200);
        assertThat(participants).anySatisfy(participant -> {
            assertThat(participant.path("assignment_id").asText()).isEqualTo(TRANSFEROR_ASSIGNMENT);
            assertThat(participant.path("can_confirm_handover").asBoolean()).isTrue();
        });
        assertThat(participants).anySatisfy(participant -> {
            assertThat(participant.path("assignment_id").asText()).isEqualTo(APPROVER_ASSIGNMENT);
            assertThat(participant.path("can_approve_handover").asBoolean()).isTrue();
        });
        assertThat(participants).anySatisfy(participant -> {
            assertThat(participant.path("assignment_id").asText()).isEqualTo(INELIGIBLE_ASSIGNMENT);
            assertThat(participant.path("can_confirm_handover").asBoolean()).isFalse();
            assertThat(participant.path("can_approve_handover").asBoolean()).isFalse();
        });

        response(identity(post("/api/v1/store-resources/hotels/{hotelId}/handovers", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "transferorAssignmentId":"%s",
                          "recipientAssignmentId":"%s",
                          "approverAssignmentId":"%s",
                          "note":"无权限参与人不得生成死单"
                        }
                        """.formatted(INELIGIBLE_ASSIGNMENT, RECIPIENT_ASSIGNMENT,
                        APPROVER_ASSIGNMENT)), 400);
        assertThat(jdbc.queryForObject("""
                select count(*) from store_resource_handover
                where tenant_id = ?::uuid and hotel_org_unit_id = ?::uuid
                """, Integer.class, TENANT, HOTEL)).isZero();

        JsonNode createdHandover = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/handovers", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "transferorAssignmentId":"%s",
                          "recipientAssignmentId":"%s",
                          "approverAssignmentId":"%s",
                          "note":"店长异动移交集成测试"
                        }
                        """.formatted(TRANSFEROR_ASSIGNMENT, RECIPIENT_ASSIGNMENT,
                        APPROVER_ASSIGNMENT)), 201);
        String handoverId = createdHandover.path("id").asText();
        assertThat(createdHandover.path("status").asText()).isEqualTo("AWAITING_DISPUTE_DECISION");
        assertThat(createdHandover.path("item_count").asInt()).isOne();
        assertThat(createdHandover.path("asset_count").asInt()).isOne();
        assertThat(createdHandover.path("lines")).hasSize(2);

        JsonNode disputeDecision = handoverAction(handoverId, "dispute-decision",
                TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT,
                "{\"hasDispute\":false,\"expectedVersion\":0}");
        assertThat(disputeDecision.path("status").asText())
                .isEqualTo("AWAITING_TRANSFEROR_CONFIRMATION");
        assertThat(disputeDecision.path("row_version").asLong()).isEqualTo(1);

        JsonNode transferorConfirmed = handoverAction(handoverId, "transferor-confirm",
                TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT,
                "{\"expectedVersion\":1,\"reason\":\"移交人确认\"}");
        assertThat(transferorConfirmed.path("status").asText())
                .isEqualTo("AWAITING_RECIPIENT_CONFIRMATION");

        JsonNode recipientConfirmed = handoverAction(handoverId, "recipient-confirm",
                RECIPIENT_ACCOUNT, RECIPIENT_ASSIGNMENT,
                "{\"expectedVersion\":2,\"reason\":\"签收人确认\"}");
        assertThat(recipientConfirmed.path("status").asText()).isEqualTo("AWAITING_APPROVAL");

        JsonNode approved = handoverAction(handoverId, "approve",
                APPROVER_ACCOUNT, APPROVER_ASSIGNMENT,
                "{\"approved\":true,\"expectedVersion\":3,\"reason\":\"审批通过\"}");
        assertThat(approved.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(approved.path("row_version").asLong()).isEqualTo(4);
        assertThat(approved.path("completed_at").isTextual()).isTrue();
        assertThat(approved.path("actions")).hasSize(5);

        assertThat(jdbc.queryForObject("""
                select responsible_assignment_id::text from store_fixed_asset
                where tenant_id = ?::uuid and id = ?::uuid
                """, String.class, TENANT, assetId)).isEqualTo(RECIPIENT_ASSIGNMENT);
        assertThat(jdbc.queryForObject("""
                select count(*) from store_resource_handover_action
                where tenant_id = ?::uuid and handover_id = ?::uuid
                """, Integer.class, TENANT, handoverId)).isEqualTo(5);

        JsonNode disputedHandover = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/handovers", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .contentType("application/json")
                .content("""
                        {
                          "transferorAssignmentId":"%s",
                          "recipientAssignmentId":"%s",
                          "approverAssignmentId":"%s",
                          "note":"盘点分支集成测试"
                        }
                        """.formatted(TRANSFEROR_ASSIGNMENT, RECIPIENT_ASSIGNMENT,
                        APPROVER_ASSIGNMENT)), 201);
        String disputedHandoverId = disputedHandover.path("id").asText();
        JsonNode disputed = handoverAction(disputedHandoverId, "dispute-decision",
                RECIPIENT_ACCOUNT, RECIPIENT_ASSIGNMENT,
                "{\"hasDispute\":true,\"reason\":\"需要现场复盘\",\"expectedVersion\":0}");
        assertThat(disputed.path("status").asText()).isEqualTo("PHYSICAL_COUNT_REQUIRED");

        var physicalCountRequest = objectMapper.createObjectNode();
        physicalCountRequest.put("expectedVersion", 1);
        physicalCountRequest.put("note", "实物盘点已覆盖全部快照项目");
        var physicalCountLines = physicalCountRequest.putArray("lines");
        disputed.path("lines").forEach(line -> {
            var countedLine = physicalCountLines.addObject();
            countedLine.put("lineId", line.path("id").asText());
            if ("INVENTORY".equals(line.path("resource_type").asText())) {
                countedLine.put("physicalQuantity", line.path("book_quantity").decimalValue());
            } else {
                countedLine.put("physicalState", line.path("book_state").asText());
            }
            countedLine.put("differenceReason", "账实一致");
        });
        JsonNode counted = handoverAction(disputedHandoverId, "physical-count",
                TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT,
                objectMapper.writeValueAsString(physicalCountRequest));
        assertThat(counted.path("status").asText())
                .isEqualTo("AWAITING_TRANSFEROR_CONFIRMATION");
        assertThat(counted.path("row_version").asLong()).isEqualTo(2);
        assertThat(counted.path("actions")).hasSize(3);
    }

    @Test
    void newStoreWorkbookIsPreviewedBeforeAtomicIdempotentImport() throws Exception {
        String employeeNo = jdbc.queryForObject("""
                select employee_no from employee where tenant_id = ?::uuid and account_id = ?::uuid
                """, String.class, TENANT, TRANSFEROR_ACCOUNT);
        String positionCode = jdbc.queryForObject("""
                select position.code from employee_position_assignment assignment
                join position_definition position on position.tenant_id = assignment.tenant_id
                  and position.id = assignment.position_id
                where assignment.tenant_id = ?::uuid and assignment.id = ?::uuid
                """, String.class, TENANT, TRANSFEROR_ASSIGNMENT);
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("填写说明", List.of(List.of("集成测试")));
        sheets.put("物品与期初库存", List.of(
                List.of("物品编码*", "物品名称*", "分类*", "计量单位*", "单位成本*", "最低库存*", "期初数量*"),
                List.of("IMPORT-TOOTHBRUSH", "导入牙刷", "耗品", "支", "1.2500", "10", "50")
        ));
        sheets.put("固定资产", List.of(
                List.of("资产编号*", "资产名称*", "资产分类*", "规格型号", "序列号", "计量单位*",
                        "原值*", "购置日期", "存放位置", "责任人工号", "责任人岗位编码", "状态"),
                List.of("IMPORT-TV-001", "导入电视", "电器", "55寸", "SN-IMPORT-001", "台",
                        "2600", "2026-09-18", "客房801", employeeNo, positionCode, "在用")
        ));
        byte[] content = workbook.write(sheets);
        MockMultipartFile file = new MockMultipartFile("file", "new-store.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        JsonNode preview = response(identity(multipart(
                        "/api/v1/store-resources/hotels/{hotelId}/imports/preview", HOTEL).file(file),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT), 201);
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        assertThat(preview.path("canApply").asBoolean()).isTrue();
        assertThat(preview.path("summary").path("openingInventoryValue").decimalValue())
                .isEqualByComparingTo("62.5000");
        String jobId = preview.path("id").asText();

        JsonNode duplicate = response(identity(multipart(
                        "/api/v1/store-resources/hotels/{hotelId}/imports/preview", HOTEL).file(file),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT), 201);
        assertThat(duplicate.path("id").asText()).isEqualTo(jobId);
        assertThat(duplicate.path("duplicateUpload").asBoolean()).isTrue();

        JsonNode history = response(identity(get(
                        "/api/v1/store-resources/hotels/{hotelId}/imports", HOTEL),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT), 200);
        assertThat(history.isArray()).isTrue();
        assertThat(history.get(0).path("id").asText()).isEqualTo(jobId);
        assertThat(history.get(0).path("originalName").asText()).isEqualTo("new-store.xlsx");
        assertThat(history.get(0).path("rowCount").asInt()).isEqualTo(2);
        assertThat(history.get(0).path("validRowCount").asInt()).isEqualTo(2);
        assertThat(history.get(0).path("errorCount").asInt()).isZero();
        assertThat(history.get(0).path("createdAt").asText()).isNotBlank();

        JsonNode applied = response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply", HOTEL, jobId),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", "store-import-test-" + UUID.randomUUID())
                .contentType("application/json").content("{}"), 200);
        assertThat(applied.path("status").asText()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("""
                select count(*) from store_resource_item
                where tenant_id = ?::uuid and hotel_org_unit_id = ?::uuid and sku = 'IMPORT-TOOTHBRUSH'
                """, Integer.class, TENANT, HOTEL)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement movement
                join store_resource_item item on item.tenant_id = movement.tenant_id and item.id = movement.item_id
                where movement.tenant_id = ?::uuid and movement.hotel_org_unit_id = ?::uuid
                  and item.sku = 'IMPORT-TOOTHBRUSH' and movement.movement_type = 'OPENING'
                  and movement.source_type = 'IMPORT' and movement.source_id = ?::uuid
                """, Integer.class, TENANT, HOTEL, jobId)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from store_fixed_asset
                where tenant_id = ?::uuid and hotel_org_unit_id = ?::uuid and asset_code = 'IMPORT-TV-001'
                  and responsible_assignment_id = ?::uuid
                """, Integer.class, TENANT, HOTEL, TRANSFEROR_ASSIGNMENT)).isOne();

        response(identity(post(
                        "/api/v1/store-resources/hotels/{hotelId}/imports/{jobId}/actions/apply", HOTEL, jobId),
                        TRANSFEROR_ACCOUNT, TRANSFEROR_ASSIGNMENT)
                .header("Idempotency-Key", "store-import-test-replay-" + UUID.randomUUID())
                .contentType("application/json").content("{}"), 200);
        assertThat(jdbc.queryForObject("""
                select count(*) from store_inventory_movement
                where tenant_id = ?::uuid and source_type = 'IMPORT' and source_id = ?::uuid
                """, Integer.class, TENANT, jobId)).isOne();
    }

    private JsonNode handoverAction(
            String handoverId,
            String action,
            String accountId,
            String assignmentId,
            String body
    ) throws Exception {
        return response(identity(post(
                        "/api/v1/store-resources/handovers/{handoverId}/actions/{action}",
                        handoverId, action), accountId, assignmentId)
                .contentType("application/json")
                .content(body), 200);
    }

    private void grantPlatformAdministration(String accountId, String roleAssignmentId) {
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id,
                     scope_type, valid_from, granted_by)
                select ?::uuid, ?::uuid, ?::uuid, role.id, null,
                       'TENANT', now(), ?::uuid
                from app_role role
                where role.tenant_id = ?::uuid and role.code = 'PLATFORM_ADMIN'
                on conflict (tenant_id, id) do nothing
                """, roleAssignmentId, TENANT, accountId, CEO, TENANT);
    }

    private MockHttpServletRequestBuilder identity(
            MockHttpServletRequestBuilder request,
            String accountId,
            String assignmentId
    ) {
        return request.header("X-Tenant-Id", TENANT)
                .header("X-Actor-Id", accountId)
                .header("X-Assignment-Id", assignmentId);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
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
