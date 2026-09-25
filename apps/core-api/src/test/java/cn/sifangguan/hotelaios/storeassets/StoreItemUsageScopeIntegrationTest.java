package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.imports.XlsxWorkbook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreItemUsageScopeIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String HOTEL = "12000000-0000-0000-0000-000000000003";
    private static final String OTHER_HOTEL = "12000000-0000-0000-0000-000000000004";
    private static final String FRONT = "12000000-0000-0000-0000-000000000005";
    private static final String HOUSEKEEPING = "12000000-0000-0000-0000-000000000006";
    private static final String POSITION = "14000000-0000-0000-0000-000000000001";
    private static final String API = "/api/v1/store-resources/hotels/" + HOTEL;
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String URL = jdbcUrl();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired XlsxWorkbook workbook;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
    }

    @BeforeEach void administrator() {
        PlatformAdminTestFixture.ensure(jdbc, TENANT, "19000000-0000-0000-0000-000000000001");
    }
    @AfterAll static void closeDatabase() throws Exception { POSTGRES.close(); }

    @Test void optionsAndTemplateUseStoreDepartmentsAndTenantPositionStandards() throws Exception {
        JsonNode options = response(get(API + "/usage-scope-options"), 200);
        assertThat(options.path("departments").toString()).contains(FRONT, HOUSEKEEPING).doesNotContain(OTHER_HOTEL);
        assertThat(options.path("positions").toString()).contains(POSITION);
        assertThat(response(get("/api/v1/store-resources/hotels/" + OTHER_HOTEL + "/usage-scope-options"), 200)
                .path("departments").isEmpty()).isTrue();
        var download = mvc.perform(admin(get(API + "/imports/template"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(download.getHeader("Content-Disposition")).contains("V5.xlsx");
        var book = workbook.read(download.getContentAsByteArray());
        assertThat(book.sheet("物品与期初库存").rows().getFirst()).contains("使用部门*", "适用岗位", "采购换算比例");
        assertThat(book.sheet("固定资产").rows().getFirst()).contains("使用部门*", "适用岗位");
        assertThat(book.sheet("部门岗位参考").rows().toString()).contains("HZ-FRONT", "HZ-HOUSEKEEPING", "FRONT_DESK");
        assertThat(book.sheet("填写说明").rows().toString()).contains("旧V1/V2", "不拆分库存", "所选部门内全部岗位");
    }

    @Test void createEditAndOldClientRoundtripPreserveLedgerAndOptimisticVersion() throws Exception {
        ObjectNode input = itemInput();
        input.set("usageScope", scope("DEPARTMENTS", List.of(FRONT, HOUSEKEEPING), List.of(POSITION)));
        JsonNode created = response(post(API + "/items").contentType("application/json").content(input.toString()), 201);
        String id = created.path("id").asText();
        assertThat(created.path("usage_scope").path("departments").size()).isEqualTo(2);
        assertThat(created.path("usage_scope").path("positions").get(0).path("name").asText()).isEqualTo("前台员工");
        response(post(API + "/movements").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("""
                        {"itemId":"%s","movementType":"RECEIPT","quantity":10,"unitCost":2,"reason":"范围回归测试"}
                        """.formatted(id)), 201);
        input.remove("usageScope"); // existing client does not know about the new property
        input.put("name", "旧客户端编辑名称").put("expectedVersion", 0);
        JsonNode updated = response(put(API + "/items/" + id).contentType("application/json").content(input.toString()), 200);
        assertThat(updated.path("usage_scope")).isEqualTo(created.path("usage_scope"));
        input.set("usageScope", scope("ALL_DEPARTMENTS", List.of(), List.of()));
        response(put(API + "/items/" + id).contentType("application/json").content(input.toString()), 409);
        input.put("expectedVersion", 1);
        JsonNode all = response(put(API + "/items/" + id).contentType("application/json").content(input.toString()), 200);
        assertThat(all.path("usage_scope").path("mode").asText()).isEqualTo("ALL_DEPARTMENTS");
        assertThat(all.path("usage_scope").path("departmentIds").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("select quantity from store_inventory_balance where item_id=?::uuid", Integer.class, id)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select inventory_value from store_inventory_balance where item_id=?::uuid", Integer.class, id)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where item_id=?::uuid", Integer.class, id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from store_item_usage_department where item_id=?::uuid", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id=?::uuid and action='STORE_RESOURCE_ITEM_UPDATED'", Integer.class, id)).isEqualTo(2);
    }

    @Test void prefixesInferBlankDepartmentsButPreserveExplicitScopeInCreateAndImport() throws Exception {
        ObjectNode inferred=itemInput(); inferred.put("sku","KF-"+UUID.randomUUID());
        var created=response(post(API+"/items").contentType("application/json").content(inferred.toString()),201);
        assertThat(created.path("usage_scope").path("departmentIds")).isEqualTo(mapper.valueToTree(List.of(HOUSEKEEPING)));
        ObjectNode manual=itemInput(); manual.put("sku","QT-"+UUID.randomUUID()); manual.set("usageScope",scope("DEPARTMENTS",List.of(HOUSEKEEPING),List.of(POSITION)));
        var explicit=response(post(API+"/items").contentType("application/json").content(manual.toString()),201);
        assertThat(explicit.path("usage_scope").path("departmentIds")).isEqualTo(mapper.valueToTree(List.of(HOUSEKEEPING)));
        String sku="QT-"+UUID.randomUUID();
        var job=preview(file(List.of(row(sku,"","FRONT_DESK")),true));
        assertThat(job.path("status").asText()).isEqualTo("READY");
        assertThat(job.path("preview").path("items").get(0).path("usageScope").path("departmentIds")).isEqualTo(mapper.valueToTree(List.of(FRONT)));
        apply(job.path("id").asText(),200);
        assertThat(jdbc.queryForObject("select count(*) from store_item_usage_position p join store_resource_item i on i.id=p.item_id where i.sku=?",Integer.class,sku.toUpperCase(Locale.ROOT))).isOne();
        var unknown=preview(file(List.of(row("XX-"+UUID.randomUUID(),"","")),true));
        assertThat(unknown.path("status").asText()).isEqualTo("INVALID");
    }

    @Test void prefixBackfillIsPreviewedVersionedIdempotentAndMetadataOnly() throws Exception {
        var created=response(post(API+"/items").contentType("application/json").content(itemInput().toString()),201);
        String id=created.path("id").asText();
        jdbc.update("update store_resource_item set sku=? where id=?::uuid","QT-"+UUID.randomUUID(),id);
        jdbc.update("insert into store_item_usage_position values(?::uuid,?::uuid,?::uuid,?::uuid)",TENANT,HOTEL,id,POSITION);
        var preview=response(get(API+"/department-prefix"),200);
        JsonNode row=null;for(JsonNode entry:preview.path("rows")) if(entry.path("id").asText().equals(id))row=entry;
        assertThat(row).isNotNull();assertThat(row.path("action").asText()).isEqualTo("FILL");
        var body=mapper.createObjectNode().put("configVersion",preview.path("config").path("rowVersion").asLong());
        body.set("selections",mapper.valueToTree(List.of(Map.of("type","ITEM","id",id,"expectedVersion",0,"departmentId",FRONT))));
        String key=UUID.randomUUID().toString();
        response(post(API+"/department-prefix/apply").header("Idempotency-Key",key).contentType("application/json").content(body.toString()),200);
        response(post(API+"/department-prefix/apply").header("Idempotency-Key",key).contentType("application/json").content(body.toString()),200);
        response(post(API+"/department-prefix/apply").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body.toString()),409);
        assertThat(jdbc.queryForObject("select row_version from store_resource_item where id=?::uuid",Integer.class,id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from store_item_usage_position where item_id=?::uuid",Integer.class,id)).isOne();
        assertThat(jdbc.queryForObject("select quantity from store_inventory_balance where item_id=?::uuid",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where item_id=?::uuid",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='STORE_DEPARTMENT_PREFIX_FILLED' and resource_id=?::uuid",Integer.class,id)).isOne();
        var wrong=mapper.createObjectNode().put("expectedVersion",-1).put("qtDepartmentId",OTHER_HOTEL);
        response(put(API+"/department-prefix").contentType("application/json").content(wrong.toString()),400);
    }

    @Test void savedRulesAreStoreLocalAndStaleBackfillsCannotOverwriteNewerRules() throws Exception {
        var original=response(get(API+"/department-prefix"),200).path("config");
        long previous=original.path("rowVersion").asLong();
        var rules=mapper.createObjectNode().put("expectedVersion",previous).put("qtDepartmentId",HOUSEKEEPING).put("kfDepartmentId",HOUSEKEEPING);
        try {
            var saved=response(put(API+"/department-prefix").contentType("application/json").content(rules.toString()),200);
            assertThat(saved.path("config").path("rowVersion").asLong()).isEqualTo(previous+1);
            response(put(API+"/department-prefix").contentType("application/json").content(rules.toString()),409);
            ObjectNode input=itemInput();input.put("sku","QT-"+UUID.randomUUID());
            var created=response(post(API+"/items").contentType("application/json").content(input.toString()),201);
            assertThat(created.path("usage_scope").path("departmentIds")).isEqualTo(mapper.valueToTree(List.of(HOUSEKEEPING)));
            var foreign=response(get("/api/v1/store-resources/hotels/"+OTHER_HOTEL+"/department-prefix"),200);
            assertThat(foreign.path("config").path("rowVersion").asLong()).isEqualTo(-1);
            var assetBody=mapper.createObjectNode().put("assetCode","ASSET-"+UUID.randomUUID()).put("name","旧资产").put("category","设备").put("unit","台").put("originalValue",980).put("responsibleAssignmentId","19200000-0000-0000-0000-000000000002");
            String asset=response(post(API+"/assets").contentType("application/json").content(assetBody.toString()),201).path("id").asText();
            jdbc.update("update store_fixed_asset set asset_code=? where id=?::uuid","QT-"+UUID.randomUUID(),asset);
            var before=jdbc.queryForMap("select * from store_fixed_asset where id=?::uuid",asset);
            var inputBody=mapper.createObjectNode().put("configVersion",previous);
            inputBody.set("selections",mapper.valueToTree(List.of(Map.of("type","ASSET","id",asset,"expectedVersion",0,"departmentId",HOUSEKEEPING))));
            response(post(API+"/department-prefix/apply").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(inputBody.toString()),409);
            inputBody.put("configVersion",previous+1);
            response(post(API+"/department-prefix/apply").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(inputBody.toString()),200);
            var after=jdbc.queryForMap("select * from store_fixed_asset where id=?::uuid",asset);
            for(String field:List.of("asset_code","original_value","responsible_assignment_id","status")) assertThat(after.get(field)).isEqualTo(before.get(field));
            assertThat(after.get("usage_scope_mode")).isEqualTo("DEPARTMENTS");
        } finally { jdbc.update("delete from store_department_prefix_config where tenant_id=?::uuid and hotel_org_unit_id=?::uuid",TENANT,HOTEL); }
    }

    @Test void scopeValidationRejectsInvalidCrossStoreDuplicateAndInactiveReferences() throws Exception {
        for (JsonNode invalid : List.of(scope("DEPARTMENTS", List.of(), List.of()),
                scope("ALL_DEPARTMENTS", List.of(FRONT), List.of()),
                scope("DEPARTMENTS", List.of(FRONT, FRONT), List.of()),
                scope("DEPARTMENTS", List.of(UUID.randomUUID().toString()), List.of()),
                scope("ALL_DEPARTMENTS", List.of(), List.of(UUID.randomUUID().toString())),
                scope("LEGACY", List.of(), List.of()))) {
            ObjectNode input = itemInput(); input.set("usageScope", invalid);
            response(post(API + "/items").contentType("application/json").content(input.toString()), 400);
        }
        ObjectNode input = itemInput(); input.set("usageScope", scope("DEPARTMENTS", List.of(FRONT), List.of()));
        response(post("/api/v1/store-resources/hotels/" + OTHER_HOTEL + "/items").contentType("application/json").content(input.toString()), 400);
        jdbc.update("update org_unit set status='INACTIVE' where id=?::uuid", FRONT);
        try { response(post(API + "/items").contentType("application/json").content(input.toString()), 400); }
        finally { jdbc.update("update org_unit set status='ACTIVE' where id=?::uuid", FRONT); }
        assertThat(jdbc.queryForObject("select count(*) from store_resource_item where sku=?", Integer.class, input.path("sku").asText())).isZero();
    }

    @Test void v3ImportReportsExactRowAndDoesNotApplyPartially() throws Exception {
        String sku = "SCOPE-" + UUID.randomUUID();
        JsonNode job = preview(file(List.of(
                row(sku, "前厅部;客房部", "FRONT_DESK"), row(sku + "-BAD", "不存在的部门", "")), true));
        assertThat(job.path("status").asText()).isEqualTo("INVALID");
        assertThat(job.path("errors").get(0).path("rowNumber").asInt()).isEqualTo(3);
        assertThat(job.path("errors").get(0).path("field").asText()).isEqualTo("适用部门");
        apply(job.path("id").asText(), 409);
        assertThat(jdbc.queryForObject("select count(*) from store_resource_item where sku=?", Integer.class, sku)).isZero();
    }

    @Test void v3ImportResolvesNamesAndCodesWithOneInventoryBalanceAndIdempotentApply() throws Exception {
        var upload = file(List.of(row("SCOPE-" + UUID.randomUUID(), "HZ-FRONT；客房部", "FRONT_DESK")), true);
        JsonNode job = preview(upload);
        assertThat(job.path("status").asText()).isEqualTo("READY");
        assertThat(job.path("preview").path("items").get(0).path("usageScope").path("departmentIds").size()).isEqualTo(2);
        String jobId = job.path("id").asText();
        apply(jobId, 200); apply(jobId, 200);
        assertThat(preview(upload).path("status").asText()).isEqualTo("APPLIED");
        String id = jdbc.queryForObject("select resource_id::text from tabular_import_result_link where job_id=?::uuid and resource_type='STORE_RESOURCE_ITEM'", String.class, jobId);
        assertThat(jdbc.queryForObject("select usage_scope_mode from store_resource_item where id=?::uuid", String.class, id)).isEqualTo("DEPARTMENTS");
        assertThat(jdbc.queryForObject("select count(*) from store_item_usage_department where item_id=?::uuid", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_balance where item_id=?::uuid", Integer.class, id)).isOne();
        assertThat(jdbc.queryForObject("select quantity from store_inventory_balance where item_id=?::uuid", Integer.class, id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from store_inventory_movement where item_id=?::uuid", Integer.class, id)).isOne();
    }

    @Test void bothDepartmentsAndPositionsAreRevalidatedAtApplyBeforeAnyWrites() throws Exception {
        for (boolean position : List.of(false, true)) {
            var upload = file(List.of(row("SCOPE-" + UUID.randomUUID(), "前厅部", "FRONT_DESK")), true);
            var ready = preview(upload);
            String jobId = ready.path("id").asText();
            String table = position ? "position_definition" : "org_unit", id = position ? POSITION : FRONT;
            jdbc.update("update " + table + " set status='INACTIVE' where id=?::uuid", id);
            try {
                apply(jobId, 409);
                assertThat(preview(upload).path("status").asText()).isEqualTo("INVALID");
                assertThat(jdbc.queryForObject("select count(*) from tabular_import_result_link where job_id=?::uuid", Integer.class, jobId)).isZero();
            } finally { jdbc.update("update " + table + " set status='ACTIVE' where id=?::uuid", id); }
            assertThat(preview(upload).path("id").asText()).isEqualTo(jobId);
            apply(jobId, 200);
        }
    }

    @Test void legacyImportAndPreUpgradeSavedPreviewStayLegacyNotAllDepartments() throws Exception {
        var job = preview(file(List.of(row("LEGACY-" + UUID.randomUUID(), "", "").subList(0, 7)), false));
        assertThat(job.path("status").asText()).isEqualTo("READY");
        assertThat(job.path("summary").path("unconfiguredUsageCount").asInt()).isOne();
        String id = job.path("id").asText();
        jdbc.update("""
                update tabular_import_job set parsed_payload=jsonb_set(parsed_payload,'{items,0}',
                (parsed_payload #> '{items,0}') - 'usageScope' - 'usageDepartmentNames' - 'usagePositionNames'), template_version=1 where id=?::uuid
                """, id);
        apply(id, 200);
        assertThat(jdbc.queryForObject("select usage_scope_mode from store_resource_item where id in (select resource_id from tabular_import_result_link where job_id=?::uuid and resource_type='STORE_RESOURCE_ITEM')", String.class, id)).isEqualTo("LEGACY");
        var oldClient = response(post(API + "/items").contentType("application/json").content(itemInput().toString()), 201);
        assertThat(oldClient.path("usage_scope").path("mode").asText()).isEqualTo("LEGACY");
    }

    @Test void malformedV3ColumnsAndBlankRequiredScopeCannotMasqueradeAsLegacy() throws Exception {
        var blank = preview(file(List.of(row("BAD-" + UUID.randomUUID(), "", "")), true));
        assertThat(blank.path("status").asText()).isEqualTo("INVALID");
        var sheet = new LinkedHashMap<String, List<List<String>>>();
        sheet.put("物品与期初库存", List.of(List.of("物品编码*","物品名称*","分类*","计量单位*","单位成本*","最低库存*","期初数量*","适用岗位"),
                List.of("BAD-" + UUID.randomUUID(), "物品", "耗品", "支", "2", "0", "0", "前台员工")));
        sheet.put("固定资产", assetHeaders());
        assertThat(preview(upload(sheet)).path("status").asText()).isEqualTo("INVALID");
    }

    @Test void configurationStillRequiresManagementPermission() throws Exception {
        mvc.perform(get(API + "/usage-scope-options").header("X-Tenant-Id", TENANT)
                .header("X-Actor-Id", "19000000-0000-0000-0000-000000000003")
                .header("X-Assignment-Id", "19200000-0000-0000-0000-000000000002"))
                .andExpect(status().isForbidden());
    }

    @Test void duplicateScopeColumnsCannotSilentlyDiscardRestrictions() throws Exception {
        var sheet = new LinkedHashMap<String, List<List<String>>>();
        var headers = List.of("物品编码*","物品名称*","分类*","计量单位*","单位成本*","最低库存*","期初数量*","适用部门*","适用岗位","适用岗位（选填）");
        sheet.put("物品与期初库存", List.of(headers,
                List.of("DUP-" + UUID.randomUUID(),"物品","耗品","支","2","0","0","全店通用","前台员工","")));
        sheet.put("固定资产", assetHeaders());
        var job = preview(upload(sheet));
        assertThat(job.path("status").asText()).isEqualTo("INVALID");
        assertThat(job.path("errors").toString()).contains("列名重复");
        apply(job.path("id").asText(), 409);
        sheet.put("物品与期初库存", List.of(
                List.of("物品编码*","物品名称*","分类*","计量单位*","单位成本*","最低库存*","期初数量*","适用部门*","适用岗位（选填）"),
                row("ALIAS-" + UUID.randomUUID(),"全店通用","前台员工")));
        assertThat(preview(upload(sheet)).path("status").asText()).isEqualTo("READY");
    }

    @Test void scopeTablesEnforceRlsForNonBypassRoleAndSameTenantForeignKeys() throws Exception {
        var input = itemInput(); input.set("usageScope", scope("DEPARTMENTS", List.of(FRONT), List.of(POSITION)));
        String id = response(post(API + "/items").contentType("application/json").content(input.toString()), 201).path("id").asText();
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection(); var sql = connection.createStatement()) {
            sql.execute("create role usage_scope_reader nosuperuser nobypassrls");
            sql.execute("grant select, insert on store_item_usage_department,store_item_usage_position to usage_scope_reader");
            sql.execute("set role usage_scope_reader");
            sql.execute("select set_config('app.tenant_id','" + TENANT + "',false)");
            for (String table : List.of("store_item_usage_department", "store_item_usage_position")) {
                try (var rs = sql.executeQuery("select count(*) from " + table + " where item_id='" + id + "'")) { rs.next(); assertThat(rs.getInt(1)).isOne(); }
            }
            sql.execute("select set_config('app.tenant_id','20000000-0000-0000-0000-000000000001',false)");
            for (String table : List.of("store_item_usage_department", "store_item_usage_position")) {
                try (var rs = sql.executeQuery("select count(*) from " + table)) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
            }
            assertThatThrownBy(() -> sql.execute("insert into store_item_usage_department values ('" + TENANT + "','" + HOTEL + "','" + id + "','" + HOUSEKEEPING + "')"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501"));
        }
        assertThatThrownBy(() -> jdbc.update("insert into store_item_usage_department values (?::uuid,?::uuid,?::uuid,?::uuid)", TENANT, OTHER_HOTEL, id, HOUSEKEEPING))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void scopeOnlyImportUpdatesBothSheetsWithoutChangingInventoryCostOrCustodian() throws Exception {
        ObjectNode input=itemInput();
        String item=response(post(API+"/items").contentType("application/json").content(input.toString()),201).path("id").asText();
        String code="ASSET-"+UUID.randomUUID();
        var assetBody=mapper.valueToTree(Map.of("assetCode",code,"name","吸尘器","category","设备","unit","台","originalValue",980,
                "responsibleAssignmentId","19200000-0000-0000-0000-000000000002","status","ACTIVE"));
        String asset=response(post(API+"/assets").contentType("application/json").content(assetBody.toString()),201).path("id").asText();
        var beforeItem=jdbc.queryForMap("select * from store_resource_item where id=?::uuid",item);
        var beforeAsset=jdbc.queryForMap("select * from store_fixed_asset where id=?::uuid",asset);
        var sheets=new LinkedHashMap<String,List<List<String>>>();
        sheets.put("物品与期初库存",List.of(List.of("物品编码*","使用部门*","适用岗位"),List.of(input.path("sku").asText(),"前厅部","前台员工")));
        sheets.put("固定资产",List.of(List.of("资产编号*","使用部门*","适用岗位"),List.of(code,"客房部","不限岗位")));
        var file=upload(sheets);var job=response(multipart(API+"/imports/preview").file(file).param("scopeOnly","true"),201);
        assertThat(job.path("status").asText()).isEqualTo("READY");
        assertThat(job.path("scopeOnly").asBoolean()).isTrue();
        String jobId=job.path("id").asText();apply(jobId,200);apply(jobId,200);
        var afterItem=jdbc.queryForMap("select * from store_resource_item where id=?::uuid",item);
        var afterAsset=jdbc.queryForMap("select * from store_fixed_asset where id=?::uuid",asset);
        for(String key:List.of("unit_cost","minimum_quantity","unit","sku"))assertThat(afterItem.get(key)).isEqualTo(beforeItem.get(key));
        for(String key:List.of("original_value","responsible_assignment_id","status","asset_code"))assertThat(afterAsset.get(key)).isEqualTo(beforeAsset.get(key));
        assertThat(((Number)afterAsset.get("row_version")).longValue()).isEqualTo(((Number)beforeAsset.get("row_version")).longValue()+1);
        assertThat(afterItem.get("usage_scope_mode")).isEqualTo("DEPARTMENTS");
        assertThat(afterAsset.get("usage_scope_mode")).isEqualTo("DEPARTMENTS");
        assertThat(jdbc.queryForObject("select count(*) from store_asset_usage_department where asset_id=?::uuid and department_id=?::uuid",Integer.class,asset,HOUSEKEEPING)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from store_asset_usage_position where asset_id=?::uuid",Integer.class,asset)).isZero();
        assertThat(response(get(API+"/assets"),200).toString()).contains("客房部");
    }

    @Test void scopeOnlyRejectsBlankScopeAndStaleVersionsWithoutPartialWrites() throws Exception {
        ObjectNode first=itemInput(),second=itemInput();
        String firstId=response(post(API+"/items").contentType("application/json").content(first.toString()),201).path("id").asText();
        String secondId=response(post(API+"/items").contentType("application/json").content(second.toString()),201).path("id").asText();
        var sheets=new LinkedHashMap<String,List<List<String>>>();
        sheets.put("物品与期初库存",List.of(List.of("物品编码*","使用部门*","适用岗位"),List.of(first.path("sku").asText(),"","")));
        sheets.put("固定资产",List.of(List.of("资产编号*","使用部门*","适用岗位")));
        assertThat(response(multipart(API+"/imports/preview").file(upload(sheets)).param("scopeOnly","true"),201).path("status").asText()).isEqualTo("INVALID");
        sheets.put("物品与期初库存",List.of(List.of("物品编码*","使用部门*","适用岗位"),List.of(first.path("sku").asText(),"全店通用",""),List.of(second.path("sku").asText(),"前厅部","前台员工")));
        var job=response(multipart(API+"/imports/preview").file(upload(sheets)).param("scopeOnly","true"),201);
        assertThat(job.path("status").asText()).isEqualTo("READY");
        jdbc.update("update store_resource_item set row_version=row_version+1 where id=?::uuid",secondId);
        apply(job.path("id").asText(),409);
        assertThat(jdbc.queryForObject("select usage_scope_mode from store_resource_item where id=?::uuid",String.class,firstId)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("select count(*) from store_item_usage_department where item_id=?::uuid",Integer.class,firstId)).isZero();
    }

    private ObjectNode itemInput() {
        return mapper.createObjectNode().put("sku", "SCOPE-" + UUID.randomUUID()).put("name", "范围测试物品")
                .put("category", "CONSUMABLE").put("unit", "支").put("unitCost", 2).put("minimumQuantity", 0);
    }
    private JsonNode scope(String mode, List<String> departments, List<String> positions) {
        return mapper.valueToTree(Map.of("mode", mode, "departmentIds", departments, "positionIds", positions));
    }
    private List<String> row(String sku, String departments, String positions) { return List.of(sku,"测试物品","耗品","支","2","1","5",departments,positions); }
    private MockMultipartFile file(List<List<String>> rows, boolean v3) {
        var headers = new ArrayList<>(List.of("物品编码*","物品名称*","分类*","计量单位*","单位成本*","最低库存*","期初数量*"));
        if (v3) headers.addAll(List.of("适用部门*", "适用岗位"));
        var items = new ArrayList<List<String>>(); items.add(headers); items.addAll(rows);
        var sheets = new LinkedHashMap<String, List<List<String>>>(); sheets.put("物品与期初库存", items); sheets.put("固定资产", assetHeaders()); return upload(sheets);
    }
    private List<List<String>> assetHeaders() { return List.of(List.of("资产编号*","资产名称*","资产分类*","规格型号","序列号","计量单位*","原值*","购置日期","存放位置","责任人工号","责任人岗位编码","状态")); }
    private MockMultipartFile upload(LinkedHashMap<String, List<List<String>>> sheets) { return new MockMultipartFile("file","usage.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook.write(sheets)); }
    private JsonNode preview(MockMultipartFile file) throws Exception { return response(multipart(API + "/imports/preview").file(file),201); }
    private JsonNode apply(String id, int expected) throws Exception { return response(post(API + "/imports/" + id + "/actions/apply").header("Idempotency-Key",UUID.randomUUID().toString()),expected); }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", PlatformAdminTestFixture.ACCOUNT_ID); }
    private JsonNode response(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return mapper.readTree(mvc.perform(admin(request)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private static EmbeddedPostgres startPostgres() { try { return EmbeddedPostgres.builder().start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static String jdbcUrl() { try (Connection c=POSTGRES.getPostgresDatabase().getConnection()) { return c.getMetaData().getURL(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
}
