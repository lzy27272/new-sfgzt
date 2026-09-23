package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.imports.XlsxWorkbook;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StoreResourceImportService {
    private static final String IMPORT_TYPE = "STORE_RESOURCE_INITIALIZATION";
    private static final int TEMPLATE_VERSION = 3;
    private static final String ITEM_SHEET = "物品与期初库存";
    private static final String ASSET_SHEET = "固定资产";
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> ITEM_HEADERS = List.of(
            "物品编码*", "物品名称*", "分类*", "计量单位*", "单位成本*", "最低库存*", "期初数量*");
    private static final List<String> ITEM_TEMPLATE_HEADERS = List.of(
            "物品编码*", "物品名称*", "分类*", "计量单位*", "单位成本*", "最低库存*", "期初数量*",
            "规格型号", "采购单位", "采购换算比例", "领用耗用单位", "领用耗用换算比例", "适用部门*", "适用岗位");
    private static final List<String> ASSET_HEADERS = List.of(
            "资产编号*", "资产名称*", "资产分类*", "规格型号", "序列号", "计量单位*",
            "原值*", "购置日期", "存放位置", "责任人工号", "责任人岗位编码", "状态");

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final XlsxWorkbook workbook;
    private final AttachmentMalwareScanner malwareScanner;
    private final StoreItemUsageScopes usageScopes;

    public StoreResourceImportService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            XlsxWorkbook workbook,
            AttachmentMalwareScanner malwareScanner,
            StoreItemUsageScopes usageScopes
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.workbook = workbook;
        this.malwareScanner = malwareScanner;
        this.usageScopes = usageScopes;
    }

    @Transactional(readOnly = true)
    public ImportFile template(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.import-preview", hotelId);
        var options = usageScopes.options(principal.tenantId(), hotelId, false);
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("填写说明", List.of(
                List.of("新店库存与固定资产导入说明"),
                List.of("1", "只支持.xlsx，文件不超过10MB、合计不超过5000行；禁止公式、宏和外部链接。"),
                List.of("2", "不要修改工作表名称和首行字段。整份文件先预检，有任一错误时不会写入正式数据。"),
                List.of("3", "物品分类可填：耗品、布草、清洁用品、经营物品。期初数量为0时只创建物品主档。"),
                List.of("4", "计量单位是库存基础单位；单位成本、最低库存、期初数量全部按基础单位填写。期初数量×单位成本为期初金额，并生成不可变流水。"),
                List.of("5", "责任人工号可留空；同一员工有多个任职时请同时填写责任人岗位编码。"),
                List.of("6", "资产状态可填：在用、维修中、损坏、盘亏、已退役；留空默认为在用。"),
                List.of("7", "当前版本为仅新增模式：门店内重复SKU或资产编号会被拦截，不会覆盖原数据。"),
                List.of("8", "规格和采购/领用耗用单位为选填；不填则沿用基础单位。1个采购或领用耗用单位=换算比例个基础单位；异名单位必须填比例，同名单位比例必须相同。"),
                List.of("9", "例如基础单位支、采购单位箱、采购换算比例100；2箱期初库存请填200支，单支成本填0.5而非整箱50。旧V1/V2模板仍可导入，缺少使用范围的物品标为待配置，不自动改成全店通用。"),
                List.of("10", "适用部门必填：全店通用，或部门岗位参考表中的本门店有效部门名称/编码；多项用分号分隔，例如前厅部;客房部。名称重名时请填写编码。"),
                List.of("11", "适用岗位选填：使用集团有效岗位标准名称/编码，多项用分号分隔；留空表示所选部门内全部岗位，不表示全店所有部门。岗位定义不以当前是否有人任职为条件。"),
                List.of("12", "参考表仅供填写，不参与导入；系统以后台当前有效数据为准校验。此配置不拆分库存、不改变权限、审批或常用品清单；按范围快捷领用在后续接入。")
        ));
        sheets.put(ITEM_SHEET, List.of(ITEM_TEMPLATE_HEADERS));
        sheets.put(ASSET_SHEET, List.of(ASSET_HEADERS));
        List<List<String>> references = new ArrayList<>();
        references.add(List.of("类型", "编码", "名称", "适用说明"));
        options.departments().forEach(option -> references.add(List.of("部门", option.code(), option.name(), "当前门店有效部门")));
        options.positions().forEach(option -> references.add(List.of("岗位", option.code(), option.name(), "集团有效岗位标准；与所选部门取交集")));
        sheets.put("部门岗位参考", references);
        return new ImportFile("新店库存与固定资产导入模板_V3.xlsx", XLSX_MEDIA_TYPE, workbook.write(sheets));
    }

    @Transactional
    public Map<String, Object> preview(UUID hotelId, MultipartFile file) {
        TenantPrincipal principal = prepareHotel("store-resource.import-preview", hotelId);
        Upload upload = validatedUpload(file);
        MapSqlParameterSource fileKey = hotel(principal, hotelId).addValue("importType", IMPORT_TYPE)
                .addValue("sha256", upload.sha256());
        // Serialize first uploads before a job row exists. Existing jobs also take
        // the same row lock as apply, so a refresh can never overwrite APPLIED.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:fileKey, 0))",
                new MapSqlParameterSource("fileKey", principal.tenantId() + ":" + hotelId
                        + ":" + IMPORT_TYPE + ":" + upload.sha256()));
        List<Map<String, Object>> duplicate = jdbc.queryForList("""
                select * from tabular_import_job
                where tenant_id = :tenantId and target_org_unit_id = :hotelId
                  and import_type = :importType and sha256 = :sha256
                for update
                """, fileKey);
        Map<String, Object> existing = duplicate.isEmpty() ? null : duplicate.getFirst();
        if (existing != null && "APPLIED".equals(existing.get("status"))) {
            return jobResponse(existing, true);
        }

        Parsed parsed = parse(principal, hotelId, workbook.read(upload.bytes()));
        List<ImportError> errors = new ArrayList<>(parsed.errors());
        errors.addAll(databaseConflicts(principal, hotelId, parsed.payload()));
        int rowCount = parsed.payload().items().size() + parsed.payload().assets().size();
        Set<String> errorRows = new HashSet<>();
        for (ImportError error : errors) {
            if (error.rowNumber() >= 2) errorRows.add(error.sheetName() + ":" + error.rowNumber());
        }
        int validRows = Math.max(0, rowCount - errorRows.size());
        String status = errors.isEmpty() && rowCount > 0 ? "READY" : "INVALID";
        if (rowCount == 0 && errors.isEmpty()) {
            errors.add(new ImportError("工作簿", 1, "数据", "工作簿没有可导入的数据", ""));
            status = "INVALID";
        }
        UUID jobId = existing == null ? UUID.randomUUID() : uuid(existing.get("id"));
        MapSqlParameterSource params = hotel(principal, hotelId)
                .addValue("id", jobId).addValue("importType", IMPORT_TYPE)
                .addValue("templateVersion", TEMPLATE_VERSION).addValue("name", upload.name())
                .addValue("mediaType", XLSX_MEDIA_TYPE).addValue("sizeBytes", upload.bytes().length)
                .addValue("sha256", upload.sha256()).addValue("scanStatus", upload.scanStatus())
                .addValue("status", status).addValue("payload", json(parsed.payload()))
                .addValue("errors", json(errors)).addValue("rowCount", rowCount)
                .addValue("validRows", validRows).addValue("errorCount", errors.size())
                .addValue("actorId", principal.actorId());
        int inserted = existing == null ? jdbc.update("""
                    insert into tabular_import_job
                      (id, tenant_id, target_org_unit_id, import_type, template_version,
                       original_name, media_type, size_bytes, sha256, scan_status, status,
                       parsed_payload, validation_errors, row_count, valid_row_count,
                       error_count, created_by)
                    values
                      (:id, :tenantId, :hotelId, :importType, :templateVersion,
                       :name, :mediaType, :sizeBytes, :sha256, :scanStatus, :status,
                       cast(:payload as jsonb), cast(:errors as jsonb), :rowCount, :validRows,
                       :errorCount, :actorId)
                    on conflict do nothing
                    """, params) : 0;
        if (inserted == 0) {
            if (existing == null) existing = jdbc.queryForMap("""
                    select * from tabular_import_job
                    where tenant_id = :tenantId and target_org_unit_id = :hotelId
                      and import_type = :importType and sha256 = :sha256
                    for update
                    """, params);
            if ("APPLIED".equals(existing.get("status"))) return jobResponse(existing, true);
            jobId = uuid(existing.get("id"));
            int changed = jdbc.update("""
                    update tabular_import_job
                    set original_name = :name, scan_status = :scanStatus, status = :status, template_version = :templateVersion,
                        parsed_payload = cast(:payload as jsonb), validation_errors = cast(:errors as jsonb),
                        row_count = :rowCount, valid_row_count = :validRows, error_count = :errorCount
                    where tenant_id = :tenantId and target_org_unit_id = :hotelId
                      and id = :id and status <> 'APPLIED'
                    """, params.addValue("id", jobId));
            if (changed != 1) throw conflict("导入任务状态已变化，请刷新后重试");
        }
        auditWriter.record(inserted == 1 ? "STORE_RESOURCE_IMPORT_PREVIEWED" : "STORE_RESOURCE_IMPORT_REPREVIEWED",
                "TABULAR_IMPORT_JOB", jobId,
                json(Map.of("hotelId", hotelId, "fileName", upload.name(), "rowCount", rowCount,
                        "errorCount", errors.size(), "sha256", upload.sha256(),
                        "previousStatus", existing == null ? "NEW" : existing.get("status"), "status", status)));
        Map<String, Object> result = jobResponse(requireJob(principal, hotelId, jobId, false), inserted == 0);
        result.put("revalidated", inserted == 0);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.import-preview", hotelId);
        return jdbc.queryForList("""
                select id, original_name as "originalName", size_bytes as "sizeBytes",
                       sha256, status, row_count as "rowCount",
                       valid_row_count as "validRowCount", error_count as "errorCount",
                       result_summary as "resultSummary", created_at as "createdAt",
                       applied_at as "appliedAt"
                from tabular_import_job
                where tenant_id = :tenantId and target_org_unit_id = :hotelId
                  and import_type = :importType
                order by created_at desc, id desc
                limit 30
                """, hotel(principal, hotelId).addValue("importType", IMPORT_TYPE));
    }

    @Transactional(readOnly = true)
    public ImportFile errors(UUID hotelId, UUID jobId) {
        TenantPrincipal principal = prepareHotel("store-resource.import-preview", hotelId);
        Map<String, Object> job = requireJob(principal, hotelId, jobId, false);
        List<ImportError> errors = readErrors(job.get("validation_errors"));
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("工作表", "行号", "字段", "错误说明", "原值"));
        for (ImportError error : errors) {
            rows.add(List.of(error.sheetName(), String.valueOf(error.rowNumber()), error.field(),
                    error.message(), error.value() == null ? "" : error.value()));
        }
        sheets.put("导入错误", rows);
        return new ImportFile("导入错误_" + jobId + ".xlsx", XLSX_MEDIA_TYPE, workbook.write(sheets));
    }

    @Transactional
    public Map<String, Object> apply(UUID hotelId, UUID jobId, String idempotencyKey) {
        TenantPrincipal principal = prepareHotel("store-resource.import-apply", hotelId);
        requireIdempotencyKey(idempotencyKey);
        Map<String, Object> job = requireJob(principal, hotelId, jobId, true);
        if ("APPLIED".equals(job.get("status"))) return jobResponse(job, true);
        if (!"READY".equals(job.get("status"))) {
            throw conflict("导入任务未通过预检，不能确认入账");
        }
        ImportPayload payload = readPayload(job.get("parsed_payload"));
        List<ImportError> conflicts = databaseConflicts(principal, hotelId, payload);
        if (!conflicts.isEmpty()) throw conflict("正式数据已变化，请重新上传并预检：" + conflicts.getFirst().message());
        requireAssignmentsStillValid(principal, hotelId, payload);
        if (payload.items().stream().anyMatch(item -> item.usageScope() != null)) {
            var options = usageScopes.options(principal.tenantId(), hotelId, true);
            for (ImportedItem item : payload.items()) {
                try { usageScopes.validate(item.usageScope(), options); }
                catch (StoreItemUsageScopes.ScopeValidationException exception) {
                    throw conflict("物品与期初库存第" + item.rowNumber() + "行" + exception.field()
                            + "已变化，请重新上传并预检：" + exception.getMessage());
                }
            }
        }

        BigDecimal openingQuantity = BigDecimal.ZERO;
        BigDecimal openingValue = BigDecimal.ZERO;
        List<UUID> itemIds = new ArrayList<>();
        List<UUID> assetIds = new ArrayList<>();
        String fileSha = String.valueOf(job.get("sha256"));
        for (ImportedItem item : payload.items()) {
            StoreItemUnits units = new StoreItemUnits(item.unit(), item.purchaseUnit(), item.purchaseFactor(),
                    item.issueUnit(), item.issueFactor());
            UUID itemId = UUID.randomUUID();
            itemIds.add(itemId);
            MapSqlParameterSource params = hotel(principal, hotelId)
                    .addValue("itemId", itemId).addValue("sku", item.sku()).addValue("name", item.name())
                    .addValue("category", item.category()).addValue("unit", item.unit())
                    .addValue("specification", text(item.specification()))
                    .addValue("purchaseUnit", units.purchaseUnit()).addValue("purchaseFactor", units.purchaseFactor())
                    .addValue("issueUnit", units.issueUnit()).addValue("issueFactor", units.issueFactor())
                    .addValue("unitCost", scale4(item.unitCost()))
                    .addValue("minimumQuantity", scale4(item.minimumQuantity()))
                    .addValue("actorId", principal.actorId());
            jdbc.update("""
                    insert into store_resource_item
                      (id, tenant_id, hotel_org_unit_id, sku, name, category, unit,
                       unit_cost, minimum_quantity, specification, purchase_unit, purchase_factor, issue_unit, issue_factor, created_by)
                    values (:itemId, :tenantId, :hotelId, :sku, :name, :category, :unit,
                            :unitCost, :minimumQuantity, :specification, :purchaseUnit, :purchaseFactor, :issueUnit, :issueFactor, :actorId)
                    """, params);
            usageScopes.save(principal.tenantId(), hotelId, itemId, item.usageScope());
            BigDecimal quantity = scale4(item.openingQuantity());
            BigDecimal value = quantity.multiply(scale4(item.unitCost())).setScale(4, RoundingMode.HALF_UP);
            jdbc.update("""
                    insert into store_inventory_balance
                      (tenant_id, hotel_org_unit_id, item_id, quantity, inventory_value)
                    values (:tenantId, :hotelId, :itemId, :quantity, :inventoryValue)
                    """, params.addValue("quantity", quantity).addValue("inventoryValue", value));
            resultLink(principal, jobId, ITEM_SHEET, item.rowNumber(), "STORE_RESOURCE_ITEM", itemId, item.sku());
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                UUID movementId = UUID.randomUUID();
                String movementKey = "store-import:" + jobId + ":item:" + item.rowNumber();
                jdbc.update("""
                        insert into store_inventory_movement
                          (id, tenant_id, hotel_org_unit_id, item_id, movement_type,
                           quantity_delta, quantity_before, quantity_after,
                           unit_cost_snapshot, value_delta, reason, source_type, source_id,
                           idempotency_key, request_hash, actor_assignment_id, actor_account_id,
                           occurred_at, base_unit_snapshot, input_unit, input_quantity, conversion_factor, input_unit_cost)
                        values
                          (:movementId, :tenantId, :hotelId, :itemId, 'OPENING',
                           :quantity, 0, :quantity, :unitCost, :inventoryValue,
                           '新店期初库存导入', 'IMPORT', :jobId,
                           :movementKey, :requestHash, :actorAssignmentId, :actorId, :occurredAt,
                           :unit, :unit, :quantity, 1, :unitCost)
                        """, params.addValue("movementId", movementId).addValue("jobId", jobId)
                        .addValue("movementKey", movementKey)
                        .addValue("requestHash", sha256((fileSha + "|" + item.rowNumber()).getBytes(StandardCharsets.UTF_8)))
                        .addValue("actorAssignmentId", principal.businessActorAssignmentId())
                        .addValue("occurredAt", OffsetDateTime.now(ZoneOffset.UTC)));
                resultLink(principal, jobId, ITEM_SHEET, item.rowNumber(),
                        "STORE_INVENTORY_MOVEMENT", movementId, item.sku());
                openingQuantity = openingQuantity.add(quantity);
                openingValue = openingValue.add(value);
            }
        }
        BigDecimal assetValue = BigDecimal.ZERO;
        for (ImportedAsset asset : payload.assets()) {
            UUID assetId = UUID.randomUUID();
            assetIds.add(assetId);
            jdbc.update("""
                    insert into store_fixed_asset
                      (id, tenant_id, hotel_org_unit_id, asset_code, name, category,
                       specification, serial_number, unit, original_value, acquired_on,
                       location, responsible_assignment_id, status, created_by)
                    values
                      (:assetId, :tenantId, :hotelId, :assetCode, :name, :category,
                       :specification, :serialNumber, :unit, :originalValue, :acquiredOn,
                       :location, :responsibleAssignmentId, :status, :actorId)
                    """, hotel(principal, hotelId).addValue("assetId", assetId)
                    .addValue("assetCode", asset.assetCode()).addValue("name", asset.name())
                    .addValue("category", asset.category()).addValue("specification", asset.specification())
                    .addValue("serialNumber", asset.serialNumber()).addValue("unit", asset.unit())
                    .addValue("originalValue", asset.originalValue().setScale(2, RoundingMode.HALF_UP))
                    .addValue("acquiredOn", asset.acquiredOn()).addValue("location", asset.location())
                    .addValue("responsibleAssignmentId", asset.responsibleAssignmentId())
                    .addValue("status", asset.status()).addValue("actorId", principal.actorId()));
            resultLink(principal, jobId, ASSET_SHEET, asset.rowNumber(),
                    "STORE_FIXED_ASSET", assetId, asset.assetCode());
            assetValue = assetValue.add(asset.originalValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemCount", itemIds.size());
        result.put("assetCount", assetIds.size());
        result.put("openingQuantity", scale4(openingQuantity));
        result.put("openingInventoryValue", scale4(openingValue));
        result.put("assetOriginalValue", assetValue.setScale(2, RoundingMode.HALF_UP));
        int changed = jdbc.update("""
                update tabular_import_job
                set status = 'APPLIED', applied_by = :actorId, applied_at = now(),
                    result_summary = cast(:result as jsonb)
                where tenant_id = :tenantId and target_org_unit_id = :hotelId
                  and id = :jobId and status = 'READY'
                """, hotel(principal, hotelId).addValue("jobId", jobId)
                .addValue("actorId", principal.actorId()).addValue("result", json(result)));
        if (changed != 1) throw conflict("导入任务状态已变化，请刷新后重试");
        auditWriter.record("STORE_RESOURCE_IMPORT_APPLIED", "TABULAR_IMPORT_JOB", jobId,
                json(Map.of("hotelId", hotelId, "result", result, "idempotencyKey", idempotencyKey)));
        return jobResponse(requireJob(principal, hotelId, jobId, false), false);
    }

    private Parsed parse(TenantPrincipal principal, UUID hotelId, XlsxWorkbook.WorkbookData source) {
        List<ImportError> errors = new ArrayList<>();
        List<ImportedItem> items = parseItems(source.sheet(ITEM_SHEET), errors,
                usageScopes.options(principal.tenantId(), hotelId, false));
        List<ImportedAssetDraft> assetDrafts = parseAssets(source.sheet(ASSET_SHEET), errors);
        List<ImportedAsset> assets = resolveResponsibleAssignments(principal, hotelId, assetDrafts, errors);
        return new Parsed(new ImportPayload(items, assets), errors);
    }

    private List<ImportedItem> parseItems(XlsxWorkbook.SheetData sheet, List<ImportError> errors,
                                        StoreItemUsageScopes.Options options) {
        if (sheet == null) {
            errors.add(new ImportError(ITEM_SHEET, 1, "工作表", "缺少工作表：" + ITEM_SHEET, ""));
            return List.of();
        }
        Map<String, Integer> columns = columns(sheet, ITEM_HEADERS, errors);
        boolean hasScope = columns.containsKey("适用部门") || columns.containsKey("适用岗位");
        if (hasScope && (!columns.containsKey("适用部门") || !columns.containsKey("适用岗位")))
            errors.add(new ImportError(ITEM_SHEET, 1, "使用范围", "新版模板必须保留适用部门和适用岗位两列；岗位内容可以留空", ""));
        if (sheet.rows().isEmpty()) return List.of();
        List<ImportedItem> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 1; index < sheet.rows().size(); index++) {
            List<String> row = sheet.rows().get(index);
            if (blankRow(row)) continue;
            int rowNumber = index + 1;
            String sku = required(cell(row, columns, "物品编码"), ITEM_SHEET, rowNumber, "物品编码", 80, errors);
            if (sku != null) sku = sku.toUpperCase(Locale.ROOT);
            String name = required(cell(row, columns, "物品名称"), ITEM_SHEET, rowNumber, "物品名称", 160, errors);
            String categoryRaw = required(cell(row, columns, "分类"), ITEM_SHEET, rowNumber, "分类", 32, errors);
            String category = itemCategory(categoryRaw);
            if (category == null) errors.add(new ImportError(ITEM_SHEET, rowNumber, "分类", "分类必须为耗品、布草、清洁用品或经营物品", categoryRaw));
            String unit = required(cell(row, columns, "计量单位"), ITEM_SHEET, rowNumber, "计量单位", 32, errors);
            BigDecimal unitCost = decimal(cell(row, columns, "单位成本"), ITEM_SHEET, rowNumber, "单位成本", 4, 14, errors);
            BigDecimal minimum = decimal(cell(row, columns, "最低库存"), ITEM_SHEET, rowNumber, "最低库存", 4, 14, errors);
            BigDecimal opening = decimal(cell(row, columns, "期初数量"), ITEM_SHEET, rowNumber, "期初数量", 4, 14, errors);
            String specification = optional(cell(row, columns, "规格型号"), ITEM_SHEET, rowNumber, "规格型号", 240, errors);
            String purchaseUnit = optional(cell(row, columns, "采购单位"), ITEM_SHEET, rowNumber, "采购单位", 32, errors);
            String issueUnit = optional(cell(row, columns, "领用耗用单位"), ITEM_SHEET, rowNumber, "领用耗用单位", 32, errors);
            BigDecimal purchaseFactor = optionalFactor(row, columns, "采购换算比例", rowNumber, errors);
            BigDecimal issueFactor = optionalFactor(row, columns, "领用耗用换算比例", rowNumber, errors);
            if (unit != null) {
                try {
                    StoreItemUnits units = new StoreItemUnits(unit, purchaseUnit, purchaseFactor, issueUnit, issueFactor);
                    purchaseUnit = units.purchaseUnit();
                    purchaseFactor = units.purchaseFactor();
                    issueUnit = units.issueUnit();
                    issueFactor = units.issueFactor();
                } catch (IllegalArgumentException exception) {
                    errors.add(new ImportError(ITEM_SHEET, rowNumber, "单位换算", exception.getMessage(), ""));
                }
            }
            if (unitCost != null && opening != null) {
                BigDecimal openingValue = opening.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
                if ((long) openingValue.precision() - openingValue.scale() > 16) {
                    errors.add(new ImportError(ITEM_SHEET, rowNumber, "期初金额",
                            "期初数量乘以单位成本后的金额过大", openingValue.toPlainString()));
                }
            }
            if (sku != null && !codes.add(sku.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportError(ITEM_SHEET, rowNumber, "物品编码", "文件内物品编码重复", sku));
            }
            StoreResourceModels.UsageScopeInput usageScope = null;
            String departments = cell(row, columns, "适用部门"), positions = cell(row, columns, "适用岗位");
            if (hasScope) {
                try { usageScope = usageScopes.parse(departments, positions, options); }
                catch (StoreItemUsageScopes.ScopeValidationException exception) {
                    errors.add(new ImportError(ITEM_SHEET, rowNumber, exception.field(), exception.getMessage(),
                            exception.field().equals("适用岗位") ? positions : departments));
                }
            }
            result.add(new ImportedItem(rowNumber, text(sku), text(name), text(category), text(unit),
                    value(unitCost), value(minimum), value(opening), specification,
                    purchaseUnit, purchaseFactor, issueUnit, issueFactor, usageScope, departments, positions));
        }
        return List.copyOf(result);
    }

    private BigDecimal optionalFactor(List<String> row, Map<String, Integer> columns, String field,
                                      int rowNumber, List<ImportError> errors) {
        String raw = cell(row, columns, field);
        return raw.isBlank() ? null : decimal(raw, ITEM_SHEET, rowNumber, field, 6, 12, errors);
    }

    private List<ImportedAssetDraft> parseAssets(XlsxWorkbook.SheetData sheet, List<ImportError> errors) {
        if (sheet == null) {
            errors.add(new ImportError(ASSET_SHEET, 1, "工作表", "缺少工作表：" + ASSET_SHEET, ""));
            return List.of();
        }
        Map<String, Integer> columns = columns(sheet, ASSET_HEADERS, errors);
        if (sheet.rows().isEmpty()) return List.of();
        List<ImportedAssetDraft> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 1; index < sheet.rows().size(); index++) {
            List<String> row = sheet.rows().get(index);
            if (blankRow(row)) continue;
            int rowNumber = index + 1;
            String code = required(cell(row, columns, "资产编号"), ASSET_SHEET, rowNumber, "资产编号", 80, errors);
            if (code != null) code = code.toUpperCase(Locale.ROOT);
            String name = required(cell(row, columns, "资产名称"), ASSET_SHEET, rowNumber, "资产名称", 160, errors);
            String category = required(cell(row, columns, "资产分类"), ASSET_SHEET, rowNumber, "资产分类", 80, errors);
            String specification = optional(cell(row, columns, "规格型号"), ASSET_SHEET, rowNumber, "规格型号", 240, errors);
            String serial = optional(cell(row, columns, "序列号"), ASSET_SHEET, rowNumber, "序列号", 160, errors);
            String unit = required(cell(row, columns, "计量单位"), ASSET_SHEET, rowNumber, "计量单位", 32, errors);
            BigDecimal originalValue = decimal(cell(row, columns, "原值"), ASSET_SHEET, rowNumber, "原值", 2, 18, errors);
            LocalDate acquiredOn = date(cell(row, columns, "购置日期"), ASSET_SHEET, rowNumber, errors);
            String location = optional(cell(row, columns, "存放位置"), ASSET_SHEET, rowNumber, "存放位置", 160, errors);
            String employeeNo = optional(cell(row, columns, "责任人工号"), ASSET_SHEET, rowNumber, "责任人工号", 64, errors);
            String positionCode = optional(cell(row, columns, "责任人岗位编码"), ASSET_SHEET, rowNumber, "责任人岗位编码", 80, errors);
            if (employeeNo == null && positionCode != null) {
                errors.add(new ImportError(ASSET_SHEET, rowNumber, "责任人工号",
                        "填写责任人岗位编码时必须同时填写责任人工号", ""));
            }
            String statusRaw = cell(row, columns, "状态");
            String status = statusRaw.isBlank() ? "ACTIVE" : assetStatus(statusRaw);
            if (status == null) errors.add(new ImportError(ASSET_SHEET, rowNumber, "状态", "资产状态无效", statusRaw));
            if (code != null && !codes.add(code.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportError(ASSET_SHEET, rowNumber, "资产编号", "文件内资产编号重复", code));
            }
            result.add(new ImportedAssetDraft(rowNumber, text(code), text(name), text(category), specification,
                    serial, text(unit), value(originalValue), acquiredOn, location, employeeNo,
                    positionCode, text(status)));
        }
        return List.copyOf(result);
    }

    private List<ImportedAsset> resolveResponsibleAssignments(
            TenantPrincipal principal, UUID hotelId, List<ImportedAssetDraft> drafts, List<ImportError> errors
    ) {
        Set<String> employeeNos = new LinkedHashSet<>();
        for (ImportedAssetDraft draft : drafts) {
            if (draft.employeeNo() != null) employeeNos.add(draft.employeeNo().toLowerCase(Locale.ROOT));
        }
        Map<String, List<Map<String, Object>>> candidates = new HashMap<>();
        if (!employeeNos.isEmpty()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select lower(employee.employee_no) as employee_key, assignment.id as assignment_id,
                           position.code as position_code, position.name as position_name
                    from employee
                    join employee_position_assignment assignment
                      on assignment.tenant_id = employee.tenant_id and assignment.employee_id = employee.id
                    join position_definition position
                      on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                    where employee.tenant_id = :tenantId and lower(employee.employee_no) in (:employeeNos)
                      and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                      and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                      and (assignment.valid_to is null or assignment.valid_to >= current_date)
                      and position.status = 'ACTIVE' and position.deleted_at is null
                      and exists (
                        select 1 from org_unit_closure scope
                        where scope.tenant_id = assignment.tenant_id
                          and scope.ancestor_id = :hotelId and scope.descendant_id = assignment.org_unit_id
                      )
                    """, hotel(principal, hotelId).addValue("employeeNos", employeeNos));
            for (Map<String, Object> row : rows) {
                candidates.computeIfAbsent(String.valueOf(row.get("employee_key")), ignored -> new ArrayList<>()).add(row);
            }
        }
        List<ImportedAsset> result = new ArrayList<>();
        for (ImportedAssetDraft draft : drafts) {
            UUID assignmentId = null;
            if (draft.employeeNo() != null) {
                List<Map<String, Object>> matched = new ArrayList<>(candidates.getOrDefault(
                        draft.employeeNo().toLowerCase(Locale.ROOT), List.of()));
                if (draft.positionCode() != null) {
                    matched.removeIf(row -> !draft.positionCode().equalsIgnoreCase(String.valueOf(row.get("position_code"))));
                }
                if (matched.isEmpty()) {
                    errors.add(new ImportError(ASSET_SHEET, draft.rowNumber(), "责任人工号",
                            "未找到该门店范围内的有效任职", draft.employeeNo()));
                } else if (matched.size() > 1) {
                    errors.add(new ImportError(ASSET_SHEET, draft.rowNumber(), "责任人岗位编码",
                            "该员工有多个有效任职，请填写岗位编码以明确责任任职", text(draft.positionCode())));
                } else {
                    assignmentId = uuid(matched.getFirst().get("assignment_id"));
                }
            }
            result.add(new ImportedAsset(draft.rowNumber(), draft.assetCode(), draft.name(), draft.category(),
                    draft.specification(), draft.serialNumber(), draft.unit(), draft.originalValue(),
                    draft.acquiredOn(), draft.location(), assignmentId, draft.status()));
        }
        return List.copyOf(result);
    }

    private List<ImportError> databaseConflicts(
            TenantPrincipal principal, UUID hotelId, ImportPayload payload
    ) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, ImportedItem> itemsByCode = new HashMap<>();
        for (ImportedItem item : payload.items()) itemsByCode.put(item.sku().toLowerCase(Locale.ROOT), item);
        if (!itemsByCode.isEmpty()) {
            for (Map<String, Object> row : jdbc.queryForList("""
                    select sku from store_resource_item
                    where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                      and lower(sku) in (:codes)
                    """, hotel(principal, hotelId).addValue("codes", itemsByCode.keySet()))) {
                ImportedItem item = itemsByCode.get(String.valueOf(row.get("sku")).toLowerCase(Locale.ROOT));
                errors.add(new ImportError(ITEM_SHEET, item.rowNumber(), "物品编码",
                        "该门店已存在相同物品编码（当前导入为仅新增模式）", item.sku()));
            }
        }
        Map<String, ImportedAsset> assetsByCode = new HashMap<>();
        for (ImportedAsset asset : payload.assets()) assetsByCode.put(asset.assetCode().toLowerCase(Locale.ROOT), asset);
        if (!assetsByCode.isEmpty()) {
            for (Map<String, Object> row : jdbc.queryForList("""
                    select asset_code from store_fixed_asset
                    where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                      and lower(asset_code) in (:codes)
                    """, hotel(principal, hotelId).addValue("codes", assetsByCode.keySet()))) {
                ImportedAsset asset = assetsByCode.get(String.valueOf(row.get("asset_code")).toLowerCase(Locale.ROOT));
                errors.add(new ImportError(ASSET_SHEET, asset.rowNumber(), "资产编号",
                        "该门店已存在相同资产编号（当前导入为仅新增模式）", asset.assetCode()));
            }
        }
        return errors;
    }

    private void requireAssignmentsStillValid(TenantPrincipal principal, UUID hotelId, ImportPayload payload) {
        Set<UUID> ids = new HashSet<>();
        for (ImportedAsset asset : payload.assets()) {
            if (asset.responsibleAssignmentId() != null) ids.add(asset.responsibleAssignmentId());
        }
        if (ids.isEmpty()) return;
        Integer count = jdbc.queryForObject("""
                select count(*) from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                where assignment.tenant_id = :tenantId and assignment.id in (:assignmentIds)
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and position.status = 'ACTIVE' and position.deleted_at is null
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = assignment.tenant_id
                      and scope.ancestor_id = :hotelId and scope.descendant_id = assignment.org_unit_id
                  )
                """, hotel(principal, hotelId).addValue("assignmentIds", ids), Integer.class);
        if (count == null || count != ids.size()) throw conflict("资产责任人任职已变化，请重新上传并预检");
    }

    private Map<String, Integer> columns(
            XlsxWorkbook.SheetData sheet, List<String> expected, List<ImportError> errors
    ) {
        Map<String, Integer> result = new HashMap<>();
        if (!sheet.rows().isEmpty()) {
            List<String> headers = sheet.rows().getFirst();
            for (int index = 0; index < headers.size(); index++) {
                String key = normalizeHeader(headers.get(index));
                if (!key.isEmpty() && result.putIfAbsent(key, index) != null)
                    errors.add(new ImportError(sheet.name(), 1, headers.get(index), "列名重复，请只保留一列，避免忽略已填内容", ""));
            }
        }
        for (String header : expected) {
            String key = normalizeHeader(header);
            if (!result.containsKey(key)) {
                errors.add(new ImportError(sheet.name(), 1, header.replace("*", ""), "缺少必填列", ""));
            }
        }
        return result;
    }

    private String cell(List<String> row, Map<String, Integer> columns, String header) {
        Integer index = columns.get(normalizeHeader(header));
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private String required(
            String raw, String sheet, int row, String field, int maxLength, List<ImportError> errors
    ) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            errors.add(new ImportError(sheet, row, field, "必填内容不能为空", ""));
            return null;
        }
        if (value.length() > maxLength) {
            errors.add(new ImportError(sheet, row, field, "内容不能超过" + maxLength + "个字符", value));
            return null;
        }
        return value;
    }

    private String optional(
            String raw, String sheet, int row, String field, int maxLength, List<ImportError> errors
    ) {
        if (raw == null || raw.trim().isBlank()) return null;
        String value = raw.trim();
        if (value.length() > maxLength) {
            errors.add(new ImportError(sheet, row, field, "内容不能超过" + maxLength + "个字符", value));
            return null;
        }
        return value;
    }

    private BigDecimal decimal(
            String raw, String sheet, int row, String field,
            int maxScale, int maxIntegerDigits, List<ImportError> errors
    ) {
        if (raw == null || raw.trim().isBlank()) {
            errors.add(new ImportError(sheet, row, field, "必填数字不能为空", ""));
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", "").replace("￥", "").trim());
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new ImportError(sheet, row, field, "数字不能小于0", raw));
                return null;
            }
            if (value.scale() > maxScale) {
                errors.add(new ImportError(sheet, row, field, "最多支持" + maxScale + "位小数", raw));
                return null;
            }
            if ((long) value.precision() - value.scale() > maxIntegerDigits) {
                errors.add(new ImportError(sheet, row, field,
                        "整数部分不能超过" + maxIntegerDigits + "位", raw));
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            errors.add(new ImportError(sheet, row, field, "不是有效数字", raw));
            return null;
        }
    }

    private LocalDate date(String raw, String sheet, int row, List<ImportError> errors) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            if (value.matches("\\d+(\\.\\d+)?")) {
                long days = new BigDecimal(value).longValue();
                return LocalDate.of(1899, 12, 30).plusDays(days);
            }
            for (DateTimeFormatter formatter : List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("yyyy/M/d"),
                    DateTimeFormatter.ofPattern("yyyy.M.d"))) {
                try {
                    return LocalDate.parse(value, formatter);
                } catch (DateTimeParseException ignored) {
                    // Try the next explicitly supported format.
                }
            }
        } catch (RuntimeException ignored) {
            // Report one stable validation error below.
        }
        errors.add(new ImportError(sheet, row, "购置日期", "日期格式应为YYYY-MM-DD", raw));
        return null;
    }

    private String itemCategory(String raw) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "耗品", "CONSUMABLE" -> "CONSUMABLE";
            case "布草", "LINEN" -> "LINEN";
            case "清洁用品", "CLEANING_SUPPLY" -> "CLEANING_SUPPLY";
            case "经营物品", "OPERATING_ITEM" -> "OPERATING_ITEM";
            default -> null;
        };
    }

    private String assetStatus(String raw) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "在用", "ACTIVE" -> "ACTIVE";
            case "维修中", "MAINTENANCE" -> "MAINTENANCE";
            case "损坏", "DAMAGED" -> "DAMAGED";
            case "盘亏", "MISSING" -> "MISSING";
            case "已退役", "RETIRED" -> "RETIRED";
            default -> null;
        };
    }

    private Upload validatedUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择需要导入的Excel文件");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("Excel文件不能超过10MB");
        String name = file.getOriginalFilename() == null ? "新店初始化.xlsx" : file.getOriginalFilename().trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("当前仅支持无宏的.xlsx文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传文件", exception);
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new IllegalArgumentException("文件内容不是有效的.xlsx工作簿");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("store-resource-import-", ".xlsx");
            Files.write(temporary, bytes);
            String scanStatus = malwareScanner.scan(temporary);
            return new Upload(name, bytes, sha256(bytes), scanStatus);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法完成导入文件安全检查", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Temporary import content is never used after this request.
                }
            }
        }
    }

    private Map<String, Object> requireJob(
            TenantPrincipal principal, UUID hotelId, UUID jobId, boolean lock
    ) {
        List<Map<String, Object>> jobs = jdbc.queryForList("""
                select * from tabular_import_job
                where tenant_id = :tenantId and target_org_unit_id = :hotelId
                  and import_type = :importType and id = :jobId
                """ + (lock ? " for update" : ""), hotel(principal, hotelId)
                .addValue("importType", IMPORT_TYPE).addValue("jobId", jobId));
        if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入任务不存在");
        return jobs.getFirst();
    }

    private Map<String, Object> jobResponse(Map<String, Object> job, boolean duplicate) {
        ImportPayload payload = readPayload(job.get("parsed_payload"));
        List<ImportError> errors = readErrors(job.get("validation_errors"));
        BigDecimal openingQuantity = payload.items().stream()
                .map(ImportedItem::openingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openingValue = payload.items().stream()
                .map(item -> item.openingQuantity().multiply(item.unitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assetValue = payload.assets().stream()
                .map(ImportedAsset::originalValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", payload.items().size());
        summary.put("assetCount", payload.assets().size());
        summary.put("openingQuantity", scale4(openingQuantity));
        summary.put("openingInventoryValue", scale4(openingValue));
        summary.put("assetOriginalValue", assetValue.setScale(2, RoundingMode.HALF_UP));
        summary.put("unconfiguredUsageCount", payload.items().stream()
                .filter(item -> item.usageScope() == null && text(item.usageDepartmentNames()).isBlank()
                        && text(item.usagePositionNames()).isBlank()).count());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.get("id"));
        result.put("fileName", job.get("original_name"));
        result.put("status", job.get("status"));
        result.put("rowCount", job.get("row_count"));
        result.put("validRowCount", job.get("valid_row_count"));
        result.put("errorCount", job.get("error_count"));
        result.put("duplicateUpload", duplicate);
        result.put("canApply", "READY".equals(job.get("status")));
        result.put("summary", summary);
        result.put("preview", Map.of(
                "items", payload.items().stream().limit(30).toList(),
                "assets", payload.assets().stream().limit(30).toList()));
        result.put("errors", errors.stream().limit(200).toList());
        result.put("result", jsonValue(job.get("result_summary"), Map.of()));
        result.put("createdAt", job.get("created_at"));
        result.put("appliedAt", job.get("applied_at"));
        return result;
    }

    private void resultLink(
            TenantPrincipal principal, UUID jobId, String sheet, int row, String type, UUID resourceId, String code
    ) {
        jdbc.update("""
                insert into tabular_import_result_link
                  (tenant_id, job_id, sheet_name, row_number, resource_type, resource_id, resource_code)
                values (:tenantId, :jobId, :sheet, :row, :type, :resourceId, :code)
                """, base(principal).addValue("jobId", jobId).addValue("sheet", sheet)
                .addValue("row", row).addValue("type", type)
                .addValue("resourceId", resourceId).addValue("code", code));
    }

    private TenantPrincipal prepareHotel(String permission, UUID hotelId) {
        accessPolicy.requirePermission(permission);
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        accessPolicy.requireOrgScope(hotelId);
        Integer hotel = jdbc.queryForObject("""
                select count(*) from org_unit
                where tenant_id = :tenantId and id = :hotelId
                  and unit_type = 'HOTEL' and status = 'ACTIVE'
                """, hotel(principal, hotelId), Integer.class);
        if (hotel == null || hotel != 1) throw new IllegalArgumentException("目标门店不存在或已停用");
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private MapSqlParameterSource hotel(TenantPrincipal principal, UUID hotelId) {
        return base(principal).addValue("hotelId", hotelId);
    }

    private ImportPayload readPayload(Object value) {
        if (value == null) return new ImportPayload(List.of(), List.of());
        try {
            return objectMapper.readValue(String.valueOf(value), ImportPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入任务数据损坏", exception);
        }
    }

    private List<ImportError> readErrors(Object value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readerForListOf(ImportError.class).readValue(String.valueOf(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入错误数据损坏", exception);
        }
    }

    private Object jsonValue(Object value, Object fallback) {
        if (value == null) return fallback;
        try {
            return objectMapper.readValue(String.valueOf(value), Object.class);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化导入数据", exception);
        }
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少幂等键");
        if (value.length() > 200) throw new IllegalArgumentException("幂等键不能超过200个字符");
    }

    private boolean blankRow(List<String> row) {
        return row.stream().allMatch(value -> value == null || value.isBlank());
    }

    private String normalizeHeader(String value) {
        String normalized = (value == null ? "" : value).trim()
                .replaceAll("[\\s*＊()（）:_：-]", "").toLowerCase(Locale.ROOT);
        return normalized.equals("适用岗位选填") ? "适用岗位" : normalized;
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal scale4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record ImportFile(String name, String mediaType, byte[] content) {
    }

    private record Upload(String name, byte[] bytes, String sha256, String scanStatus) {
    }

    private record Parsed(ImportPayload payload, List<ImportError> errors) {
    }

    public record ImportError(String sheetName, int rowNumber, String field, String message, String value) {
    }

    public record ImportPayload(List<ImportedItem> items, List<ImportedAsset> assets) {
    }

    public record ImportedItem(
            int rowNumber, String sku, String name, String category, String unit,
            BigDecimal unitCost, BigDecimal minimumQuantity, BigDecimal openingQuantity,
            String specification, String purchaseUnit, BigDecimal purchaseFactor,
            String issueUnit, BigDecimal issueFactor, StoreResourceModels.UsageScopeInput usageScope,
            String usageDepartmentNames, String usagePositionNames
    ) {
    }

    private record ImportedAssetDraft(
            int rowNumber, String assetCode, String name, String category, String specification,
            String serialNumber, String unit, BigDecimal originalValue, LocalDate acquiredOn,
            String location, String employeeNo, String positionCode, String status
    ) {
    }

    public record ImportedAsset(
            int rowNumber, String assetCode, String name, String category, String specification,
            String serialNumber, String unit, BigDecimal originalValue, LocalDate acquiredOn,
            String location, UUID responsibleAssignmentId, String status
    ) {
    }
}
