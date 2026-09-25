package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StoreResourceService {
    private static final Set<String> ITEM_CATEGORIES = Set.of(
            "CONSUMABLE", "LINEN", "CLEANING_SUPPLY", "OPERATING_ITEM");
    private static final Set<String> MOVEMENT_TYPES = Set.of(
            "RECEIPT", "CONSUMPTION", "RETURN", "DAMAGE", "ADJUSTMENT");
    private static final Set<String> ASSET_STATUSES = Set.of(
            "ACTIVE", "MAINTENANCE", "DAMAGED", "MISSING", "RETIRED");
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final EffectiveIdentityService effectiveIdentityService;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final StoreItemUsageScopes usageScopes;

    public StoreResourceService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            EffectiveIdentityService effectiveIdentityService,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            StoreItemUsageScopes usageScopes
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.effectiveIdentityService = effectiveIdentityService;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.usageScopes = usageScopes;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> hotels() {
        TenantPrincipal principal = prepare("store-resource.read");
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) return List.of();
        MapSqlParameterSource params = base(principal)
                .addValue("tenantScope", principal.hasTenantScope())
                .addValue("orgScopes", principal.orgScopes().isEmpty()
                        ? List.of(principal.tenantId()) : principal.orgScopes());
        return jdbc.queryForList("""
                select hotel.id, hotel.code, hotel.name, profile.property_code, profile.city
                from org_unit hotel
                join hotel_profile profile
                  on profile.tenant_id = hotel.tenant_id and profile.org_unit_id = hotel.id
                where hotel.tenant_id = :tenantId
                  and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                  and (
                    :tenantScope = true
                    or exists (
                      select 1 from org_unit_closure scope
                      where scope.tenant_id = hotel.tenant_id
                        and scope.descendant_id = hotel.id
                        and scope.ancestor_id in (:orgScopes)
                    )
                  )
                order by hotel.sort_order, hotel.name
                """, params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return jdbc.queryForMap("""
                select
                  (select count(*) from store_resource_item item
                    where item.tenant_id = :tenantId and item.hotel_org_unit_id = :hotelId
                      and item.status = 'ACTIVE') as item_count,
                  (select count(*) from store_resource_item item
                    join store_inventory_balance balance
                      on balance.tenant_id = item.tenant_id and balance.hotel_org_unit_id = item.hotel_org_unit_id
                     and balance.item_id = item.id
                    where item.tenant_id = :tenantId and item.hotel_org_unit_id = :hotelId
                      and item.status = 'ACTIVE' and balance.quantity <= item.minimum_quantity) as low_stock_count,
                  (select count(*) from store_fixed_asset asset
                    where asset.tenant_id = :tenantId and asset.hotel_org_unit_id = :hotelId
                      and asset.status <> 'RETIRED') as asset_count,
                  (select count(*) from store_resource_handover handover
                    where handover.tenant_id = :tenantId and handover.hotel_org_unit_id = :hotelId
                      and handover.status in ('AWAITING_DISPUTE_DECISION','PHYSICAL_COUNT_REQUIRED',
                        'AWAITING_TRANSFEROR_CONFIRMATION','AWAITING_RECIPIENT_CONFIRMATION','AWAITING_APPROVAL'))
                    as pending_handover_count,
                  (select coalesce(sum(balance.inventory_value), 0)
                    from store_inventory_balance balance
                    where balance.tenant_id = :tenantId and balance.hotel_org_unit_id = :hotelId)
                    as inventory_value
                """, hotel(principal, hotelId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(UUID hotelId, boolean includeInactive) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return usageScopes.decorate(principal.tenantId(), hotelId, jdbc.queryForList("""
                select item.id, item.hotel_org_unit_id, item.sku, item.name,
                       item.category, item.unit, item.unit_cost, item.minimum_quantity,
                       item.employee_usage_enabled, item.specification, item.purchase_unit, item.purchase_factor, item.issue_unit, item.issue_factor,
                       (exists (select 1 from store_inventory_movement history
                         where history.tenant_id = item.tenant_id and history.hotel_org_unit_id = item.hotel_org_unit_id
                           and history.item_id = item.id)
                        or exists (select 1 from store_work_usage_line usage_line
                          where usage_line.tenant_id=item.tenant_id and usage_line.item_id=item.id)
                        or exists (select 1 from store_laundry_rate rate
                          where rate.tenant_id=item.tenant_id and rate.item_id=item.id)
                        or exists (select 1 from procurement_line purchase
                          where purchase.tenant_id=item.tenant_id and purchase.item_id=item.id)) as base_unit_locked,
                       item.status, item.row_version, item.usage_scope_mode,
                       coalesce(balance.quantity, 0) as current_quantity,
                       coalesce(balance.inventory_value, 0) as inventory_value,
                       item.created_at, item.updated_at
                from store_resource_item item
                left join store_inventory_balance balance
                  on balance.tenant_id = item.tenant_id
                 and balance.hotel_org_unit_id = item.hotel_org_unit_id
                 and balance.item_id = item.id
                where item.tenant_id = :tenantId and item.hotel_org_unit_id = :hotelId
                  and (:includeInactive = true or item.status = 'ACTIVE')
                order by item.status, item.category, item.name
                """, hotel(principal, hotelId).addValue("includeInactive", includeInactive)));
    }

    @Transactional(readOnly = true)
    public StoreItemUsageScopes.Options usageScopeOptions(UUID hotelId) {
        TenantPrincipal principal = prepareHotel(accessPolicy.principal().hasPermission("store-resource.item-manage")
                ? "store-resource.item-manage" : "store-resource.asset-manage", hotelId);
        return usageScopes.options(principal.tenantId(), hotelId, false);
    }

    @Transactional
    public Map<String, Object> createItem(UUID hotelId, StoreResourceModels.CreateItemRequest request) {
        TenantPrincipal principal = prepareHotel("store-resource.item-manage", hotelId);
        var options = usageScopes.options(principal.tenantId(), hotelId, true);
        var usageScope = request.usageScope() == null ? usageScopes.infer(principal.tenantId(),hotelId,request.sku(),options)
                : usageScopes.validate(request.usageScope(),options);
        String category = enumValue(request.category(), ITEM_CATEGORIES, "物品分类");
        StoreItemUnits units = new StoreItemUnits(request.unit(), request.purchaseUnit(), request.purchaseFactor(),
                request.issueUnit(), request.issueFactor());
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = hotel(principal, hotelId)
                .addValue("id", id)
                .addValue("sku", clean(request.sku()))
                .addValue("name", clean(request.name()))
                .addValue("category", category)
                .addValue("unit", units.baseUnit())
                .addValue("specification", request.specification() == null ? "" : request.specification().trim())
                .addValue("purchaseUnit", units.purchaseUnit()).addValue("purchaseFactor", units.purchaseFactor())
                .addValue("issueUnit", units.issueUnit()).addValue("issueFactor", units.issueFactor())
                .addValue("employeeUsageEnabled", !Boolean.FALSE.equals(request.employeeUsageEnabled()))
                .addValue("unitCost", StoreItemUnits.exact(request.unitCost(), 4, 14, "基础单位成本"))
                .addValue("minimumQuantity", StoreItemUnits.exact(request.minimumQuantity(), 4, 14, "最低库存"))
                .addValue("actorId", principal.actorId());
        try {
            jdbc.update("""
                    insert into store_resource_item
                      (id, tenant_id, hotel_org_unit_id, sku, name, category, unit,
                       unit_cost, minimum_quantity, specification, purchase_unit, purchase_factor, issue_unit, issue_factor, employee_usage_enabled, created_by)
                    values
                      (:id, :tenantId, :hotelId, :sku, :name, :category, :unit,
                       :unitCost, :minimumQuantity, :specification, :purchaseUnit, :purchaseFactor, :issueUnit, :issueFactor, :employeeUsageEnabled, :actorId)
                    """, params);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("该门店已存在相同物品编码");
        }
        jdbc.update("""
                insert into store_inventory_balance
                  (tenant_id, hotel_org_unit_id, item_id, quantity, inventory_value)
                values (:tenantId, :hotelId, :id, 0, 0)
                """, params);
        usageScopes.save(principal.tenantId(), hotelId, id, usageScope);
        var created = requireItem(principal, hotelId, id);
        audit(principal, "STORE_RESOURCE_ITEM_CREATED", "STORE_RESOURCE_ITEM", id,
                Map.of("hotelId", hotelId, "sku", clean(request.sku()), "category", category, "units", units,
                        "usageScope", created.get("usage_scope")));
        return created;
    }

    @Transactional
    public Map<String, Object> updateItem(
            UUID hotelId, UUID itemId, StoreResourceModels.UpdateItemRequest request
    ) {
        TenantPrincipal principal = prepareHotel("store-resource.item-manage", hotelId);
        String category = enumValue(request.category(), ITEM_CATEGORIES, "物品分类");
        Map<String, Object> current = lockItem(principal, hotelId, itemId);
        if (((Number) current.get("row_version")).longValue() != request.expectedVersion()) {
            throw conflict("物品配置已变化，请刷新后重试");
        }
        var beforeDetails = requireItem(principal, hotelId, itemId);
        var beforeScope = beforeDetails.get("usage_scope");
        var usageScope = request.usageScope() == null ? null : usageScopes.validate(request.usageScope(),
                usageScopes.options(principal.tenantId(), hotelId, true));
        // Omitted optional fields from older clients preserve existing conversion configuration.
        boolean baseChanged = !clean(request.unit()).equals(current.get("unit"));
        if (baseChanged && Boolean.TRUE.equals(beforeDetails.get("base_unit_locked"))) {
            throw conflict("该物品已有库存、耗用记录或洗涤配置，不能修改基础单位；规格变更请新建物品");
        }
        if (!category.equals(current.get("category")) && jdbc.queryForObject(
                "select count(*) from store_laundry_rate where tenant_id=:tenantId and item_id=:itemId",
                hotel(principal, hotelId).addValue("itemId", itemId), Long.class) > 0) {
            throw conflict("该物品已有洗涤配置，不能修改布草分类；请新建物品");
        }
        StoreItemUnits units = new StoreItemUnits(request.unit(),
                request.purchaseUnit() == null ? (baseChanged ? request.unit() : (String) current.get("purchase_unit")) : request.purchaseUnit(),
                request.purchaseFactor() == null && request.purchaseUnit() == null
                        ? (baseChanged ? BigDecimal.ONE : decimal(current.get("purchase_factor"))) : request.purchaseFactor(),
                request.issueUnit() == null ? (baseChanged ? request.unit() : (String) current.get("issue_unit")) : request.issueUnit(),
                request.issueFactor() == null && request.issueUnit() == null
                        ? (baseChanged ? BigDecimal.ONE : decimal(current.get("issue_factor"))) : request.issueFactor());
        int changed;
        try {
            changed = jdbc.update("""
                    update store_resource_item
                    set sku = :sku, name = :name, category = :category, unit = :unit,
                        unit_cost = :unitCost, minimum_quantity = :minimumQuantity,
                        specification = :specification, purchase_unit = :purchaseUnit, purchase_factor = :purchaseFactor,
                        issue_unit = :issueUnit, issue_factor = :issueFactor,
                        employee_usage_enabled = :employeeUsageEnabled,
                        row_version = row_version + 1
                    where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                      and id = :itemId and row_version = :expectedVersion
                    """, hotel(principal, hotelId)
                    .addValue("itemId", itemId)
                    .addValue("sku", clean(request.sku()))
                    .addValue("name", clean(request.name()))
                    .addValue("category", category)
                    .addValue("unit", units.baseUnit())
                    .addValue("specification", request.specification() == null ? current.get("specification") : request.specification().trim())
                    .addValue("purchaseUnit", units.purchaseUnit()).addValue("purchaseFactor", units.purchaseFactor())
                    .addValue("issueUnit", units.issueUnit()).addValue("issueFactor", units.issueFactor())
                    .addValue("employeeUsageEnabled", request.employeeUsageEnabled() == null ? current.get("employee_usage_enabled") : request.employeeUsageEnabled())
                    .addValue("unitCost", StoreItemUnits.exact(request.unitCost(), 4, 14, "基础单位成本"))
                    .addValue("minimumQuantity", StoreItemUnits.exact(request.minimumQuantity(), 4, 14, "最低库存"))
                    .addValue("expectedVersion", request.expectedVersion()));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("该门店已存在相同物品编码");
        }
        changed(changed, "物品配置已变化，请刷新后重试");
        usageScopes.save(principal.tenantId(), hotelId, itemId, usageScope);
        var updated = requireItem(principal, hotelId, itemId);
        audit(principal, "STORE_RESOURCE_ITEM_UPDATED", "STORE_RESOURCE_ITEM", itemId,
                Map.of("hotelId", hotelId, "sku", clean(request.sku()),
                        "unitsBefore", StoreItemUnits.from(current), "unitsAfter", units,
                        "usageScopeBefore", beforeScope, "usageScopeAfter", updated.get("usage_scope")));
        return updated;
    }

    @Transactional
    public Map<String, Object> setItemEnabled(
            UUID hotelId, UUID itemId, boolean enabled, StoreResourceModels.VersionCommand request
    ) {
        TenantPrincipal principal = prepareHotel("store-resource.item-manage", hotelId);
        int changed = jdbc.update("""
                update store_resource_item
                set status = :status, row_version = row_version + 1
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                  and id = :itemId and row_version = :expectedVersion
                """, hotel(principal, hotelId)
                .addValue("itemId", itemId)
                .addValue("status", enabled ? "ACTIVE" : "INACTIVE")
                .addValue("expectedVersion", request.expectedVersion()));
        changed(changed, "物品配置已变化，请刷新后重试");
        audit(principal, enabled ? "STORE_RESOURCE_ITEM_ENABLED" : "STORE_RESOURCE_ITEM_DISABLED",
                "STORE_RESOURCE_ITEM", itemId, Map.of("hotelId", hotelId));
        return requireItem(principal, hotelId, itemId);
    }

    @Transactional
    public void deactivateItem(UUID hotelId, UUID itemId) {
        TenantPrincipal principal = prepareHotel("store-resource.item-manage", hotelId);
        int changed = jdbc.update("""
                update store_resource_item
                set status = 'INACTIVE', row_version = row_version + 1
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId and id = :itemId
                  and status <> 'INACTIVE'
                """, hotel(principal, hotelId).addValue("itemId", itemId));
        changed(changed, "物品不存在或已经停用");
        audit(principal, "STORE_RESOURCE_ITEM_DISABLED", "STORE_RESOURCE_ITEM", itemId,
                Map.of("hotelId", hotelId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> balances(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return jdbc.queryForList("""
                select balance.id, balance.item_id, item.sku, item.name, item.category, item.unit,
                       item.minimum_quantity, item.unit_cost, item.status,
                       balance.quantity, balance.inventory_value, balance.row_version, balance.updated_at
                from store_inventory_balance balance
                join store_resource_item item
                  on item.tenant_id = balance.tenant_id
                 and item.hotel_org_unit_id = balance.hotel_org_unit_id
                 and item.id = balance.item_id
                where balance.tenant_id = :tenantId and balance.hotel_org_unit_id = :hotelId
                order by item.status, item.category, item.name
                """, hotel(principal, hotelId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> movements(UUID hotelId, int limit) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.queryForList("""
                select movement.id, movement.item_id, item.sku, item.name as item_name,
                       coalesce(movement.base_unit_snapshot, item.unit) as unit,
                       coalesce(movement.input_unit, item.unit) as input_unit,
                       coalesce(movement.input_quantity, movement.quantity_delta) as input_quantity,
                       coalesce(movement.conversion_factor, 1) as conversion_factor, movement.input_unit_cost,
                       movement.movement_type, movement.quantity_delta,
                       movement.quantity_before, movement.quantity_after,
                       movement.unit_cost_snapshot, movement.value_delta, movement.reason,
                       movement.source_type, movement.source_id,
                       coalesce(account.display_name, '系统') as operator_name,
                       movement.actor_assignment_id, movement.occurred_at
                from store_inventory_movement movement
                join store_resource_item item
                  on item.tenant_id = movement.tenant_id and item.id = movement.item_id
                left join user_account account
                  on account.tenant_id = movement.tenant_id and account.id = movement.actor_account_id
                where movement.tenant_id = :tenantId and movement.hotel_org_unit_id = :hotelId
                order by movement.occurred_at desc, movement.id desc
                limit :limit
                """, hotel(principal, hotelId).addValue("limit", bounded));
    }

    @Transactional
    public Map<String, Object> createMovement(
            UUID hotelId, String idempotencyKey, StoreResourceModels.CreateMovementRequest request
    ) {
        TenantPrincipal principal = prepareHotel("store-resource.movement-create", hotelId);
        if (request.occurredAt() != null && request.occurredAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("库存变动时间不能晚于当前时间");
        }
        String key = cleanKey(idempotencyKey);
        String type = enumValue(request.movementType(), MOVEMENT_TYPES, "库存变动类型");
        if (request.quantity() == null || request.quantity().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("库存变动数量不能为0");
        }
        BigDecimal submitted = StoreItemUnits.exact(request.quantity(), 4, 14, "录入数量");
        BigDecimal inputQuantity = switch (type) {
            case "RECEIPT", "RETURN" -> submitted.abs();
            case "CONSUMPTION", "DAMAGE" -> submitted.abs().negate();
            default -> submitted;
        };
        String hashInput = request.itemId() + "|" + type + "|" + inputQuantity + "|"
                + request.unitCost() + "|" + clean(request.reason()) + "|" + request.occurredAt();
        // Keep the old hash for base-unit-only callers, including in-flight retries after upgrade.
        if (request.quantityUnit() != null && !request.quantityUnit().isBlank()) {
            hashInput += "|unit=" + request.quantityUnit().trim().toLowerCase(java.util.Locale.ROOT);
        }
        String requestHash = sha256(hashInput);
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:movementKey, 63))",
                new MapSqlParameterSource("movementKey", principal.tenantId() + ":" + key));
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select movement.id, movement.request_hash, movement.hotel_org_unit_id
                from store_inventory_movement movement
                where movement.tenant_id = :tenantId and movement.idempotency_key = :idempotencyKey
                """, base(principal).addValue("idempotencyKey", key));
        if (!existing.isEmpty()) {
            if (!hotelId.equals(existing.getFirst().get("hotel_org_unit_id"))
                    || !requestHash.equals(existing.getFirst().get("request_hash"))) {
                throw conflict("同一幂等键不能用于不同的库存变动");
            }
            return requireMovement(principal, uuid(existing.getFirst().get("id")));
        }

        Map<String, Object> item = lockItem(principal, hotelId, request.itemId());
        if (!"ACTIVE".equals(item.get("status"))) throw conflict("该物品已停用，不能登记库存变动");
        if (request.expectedItemVersion() != null
                && request.expectedItemVersion() != ((Number) item.get("row_version")).longValue()) {
            throw conflict("物品规格或换算比例已变化，请刷新后核对再提交");
        }
        StoreItemUnits units = StoreItemUnits.from(item);
        String inputUnit = units.selectedUnit(request.quantityUnit());
        BigDecimal factor = units.factorFor(inputUnit);
        BigDecimal delta = units.baseQuantity(inputQuantity, inputUnit);
        return postMovementFact(principal, hotelId, request, item, type, inputQuantity, units,
                inputUnit, factor, delta, key, requestHash, "MANUAL", null, null);
    }

    // Package-private: called only after the linked work record is locked and its reviewer authorized.
    // Unit/quantity snapshots were validated at submission; later ratio edits must not change them.
    Map<String, Object> postWorkUsage(TenantPrincipal principal, UUID hotelId, UUID recordId,
                                    Map<String, Object> line, boolean reversal, BigDecimal originalValue) {
        return postUsageRevision(principal, hotelId, recordId, "WORK_RECORD", line, reversal, originalValue);
    }

    Map<String, Object> postUsageRevision(TenantPrincipal principal, UUID hotelId, UUID sourceId, String sourceType,
                                    Map<String, Object> line, boolean reversal, BigDecimal originalValue) {
        UUID itemId = uuid(line.get("item_id"));
        Map<String, Object> item = lockItem(principal, hotelId, itemId);
        String baseUnit = (String) line.get("base_unit");
        if (!baseUnit.equals(item.get("unit"))) throw conflict("库存基础单位发生变化，无法安全过账");
        String inputUnit = (String) line.get("input_unit");
        BigDecimal factor = decimal(line.get("conversion_factor"));
        BigDecimal quantity = decimal(line.get("input_quantity")).multiply(BigDecimal.valueOf(reversal ? 1 : -1));
        BigDecimal delta = decimal(line.get("base_quantity")).multiply(BigDecimal.valueOf(reversal ? 1 : -1));
        StoreItemUnits units = new StoreItemUnits(baseUnit, inputUnit, factor, inputUnit, factor);
        String key = "work-usage:" + sourceId + ":" + itemId + (reversal ? ":reverse" : ":consume");
        var request = new StoreResourceModels.CreateMovementRequest(itemId, reversal ? "RETURN" : "CONSUMPTION",
                quantity, null, (reversal ? "日清耗用冲销：" : "日清耗用审核：") + line.get("item_name"),
                null, inputUnit, null);
        return postMovementFact(principal, hotelId, request, item, request.movementType(), quantity, units,
                inputUnit, factor, delta, key, sha256(key), sourceType, sourceId, reversal ? originalValue : null);
    }

    // Called inside the laundry transaction, after order and item locks and explicit write-off confirmation.
    Map<String, Object> postLaundryLoss(TenantPrincipal principal, UUID hotelId, UUID receiptId,
                                       Map<String, Object> line, boolean reversal, BigDecimal originalValue) {
        UUID itemId = uuid(line.get("item_id"));
        Map<String, Object> item = lockItem(principal, hotelId, itemId);
        String unit = (String) line.get("base_unit");
        if (!unit.equals(item.get("unit"))) throw conflict("布草基础单位发生变化，无法安全过账");
        BigDecimal delta = decimal(line.get("written_off_quantity")).multiply(BigDecimal.valueOf(reversal ? 1 : -1));
        String key = "laundry:" + receiptId + ":" + itemId + (reversal ? ":reverse" : ":loss");
        var request = new StoreResourceModels.CreateMovementRequest(itemId, reversal ? "RETURN" : "DAMAGE", delta,
                null, (reversal ? "洗涤报损冲销：" : "洗涤确认报损：") + line.get("difference_reason"), null, unit, null);
        var units = new StoreItemUnits(unit, unit, BigDecimal.ONE, unit, BigDecimal.ONE);
        return postMovementFact(principal, hotelId, request, item, request.movementType(), delta, units,
                unit, BigDecimal.ONE, delta, key, sha256(key), "LAUNDRY", receiptId, reversal ? originalValue : null);
    }

    /** Internal entry point: caller holds the procurement receipt/order locks and authorizes its configured reviewer. */
    Map<String, Object> postProcurementReceipt(TenantPrincipal principal, UUID hotelId, UUID receiptId,
            UUID itemId, String baseUnit, BigDecimal quantity, BigDecimal receivedValue) {
        var item = lockItem(principal, hotelId, itemId);
        if (!"ACTIVE".equals(item.get("status")) || !baseUnit.equals(item.get("unit"))) {
            throw conflict("采购物品已停用或基础单位发生变化，请核对后再复核入库");
        }
        String key = "procurement:" + receiptId + ":" + itemId;
        var request = new StoreResourceModels.CreateMovementRequest(itemId, "RECEIPT", quantity,
                receivedValue.divide(quantity, 4, RoundingMode.HALF_UP), "采购收货复核入库", null, baseUnit, null);
        var units = new StoreItemUnits(baseUnit, baseUnit, BigDecimal.ONE, baseUnit, BigDecimal.ONE);
        return postMovementFact(principal, hotelId, request, item, "RECEIPT", quantity, units,
                baseUnit, BigDecimal.ONE, quantity, key, sha256(key), "PROCUREMENT", receiptId, receivedValue);
    }

    private Map<String, Object> postMovementFact(TenantPrincipal principal, UUID hotelId,
            StoreResourceModels.CreateMovementRequest request, Map<String, Object> item, String type,
            BigDecimal inputQuantity, StoreItemUnits units, String inputUnit, BigDecimal factor,
            BigDecimal delta, String key, String requestHash, String sourceType, UUID sourceId, BigDecimal reversalValue) {
        Map<String, Object> balance = jdbc.queryForMap("""
                select quantity, inventory_value, row_version
                from store_inventory_balance
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId and item_id = :itemId
                for update
                """, hotel(principal, hotelId).addValue("itemId", request.itemId()));
        BigDecimal before = decimal(balance.get("quantity"));
        BigDecimal after = StoreItemUnits.exact(before.add(delta), 4, 14, "变动后库存");
        if (after.compareTo(BigDecimal.ZERO) < 0) {
            throw conflict(item.get("name") + " 库存不足：当前库存为 " + before.stripTrailingZeros().toPlainString()
                    + " " + units.baseUnit() + "，本次审核未扣库，请先补充库存或退回更正");
        }
        if (delta.signum() < 0 && "LINEN".equals(item.get("category"))
                && StoreLaundryService.heldQuantity(jdbc, principal, hotelId, request.itemId()).compareTo(after) > 0) {
            throw conflict("本次扣减会占用尚未回收的送洗布草，请先核对洗涤台账");
        }
        BigDecimal valueBefore = decimal(balance.get("inventory_value"));
        BigDecimal configuredCost = decimal(item.get("unit_cost"));
        BigDecimal averageCost = before.compareTo(BigDecimal.ZERO) > 0
                ? valueBefore.divide(before, 4, RoundingMode.HALF_UP)
                : configuredCost;
        BigDecimal inputUnitCost = request.unitCost() == null ? null
                : StoreItemUnits.exact(request.unitCost(), 4, 14, "所选单位成本");
        if (inputUnitCost != null && inputUnitCost.signum() < 0) throw new IllegalArgumentException("单位成本不能小于0");
        if (delta.signum() < 0) inputUnitCost = null; // Outflows always use weighted-average stock value.
        BigDecimal unitCost = inputUnitCost == null ? averageCost
                : StoreItemUnits.exact(inputUnitCost.divide(factor, 4, RoundingMode.HALF_UP), 4, 14, "基础单位成本");
        BigDecimal valueDelta;
        if (delta.signum() < 0) {
            // Use the unrounded balance ratio, and consume all remaining value on the final outflow.
            valueDelta = after.signum() == 0 ? valueBefore.negate()
                    : valueBefore.multiply(delta).divide(before, 4, RoundingMode.HALF_UP);
        } else {
            valueDelta = inputUnitCost == null
                    ? (before.signum() > 0 ? valueBefore.multiply(delta).divide(before, 4, RoundingMode.HALF_UP)
                        : delta.multiply(configuredCost))
                    : inputQuantity.multiply(inputUnitCost);
            valueDelta = valueDelta.setScale(4, RoundingMode.HALF_UP);
        }
        if (reversalValue != null) {
            valueDelta = reversalValue;
            unitCost = reversalValue.divide(delta, 4, RoundingMode.HALF_UP);
        }
        valueDelta = StoreItemUnits.exact(valueDelta, 4, 16, "变动金额");
        BigDecimal valueAfter = StoreItemUnits.exact(valueBefore.add(valueDelta), 4, 16, "变动后库存金额");
        int updated = jdbc.update("""
                update store_inventory_balance
                set quantity = :after, inventory_value = :valueAfter, row_version = row_version + 1
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                  and item_id = :itemId and row_version = :expectedVersion
                """, hotel(principal, hotelId)
                .addValue("itemId", request.itemId())
                .addValue("after", after)
                .addValue("valueAfter", valueAfter)
                .addValue("expectedVersion", ((Number) balance.get("row_version")).longValue()));
        changed(updated, "库存已被其他操作更新，请刷新后重试");
        UUID movementId = UUID.randomUUID();
        jdbc.update("""
                insert into store_inventory_movement
                  (id, tenant_id, hotel_org_unit_id, item_id, movement_type,
                   quantity_delta, quantity_before, quantity_after, unit_cost_snapshot,
                   value_delta, reason, source_type, source_id, idempotency_key, request_hash,
                   actor_assignment_id, actor_account_id, occurred_at,
                   base_unit_snapshot, input_unit, input_quantity, conversion_factor, input_unit_cost)
                values
                  (:id, :tenantId, :hotelId, :itemId, :movementType,
                   :delta, :before, :after, :unitCost, :valueDelta, :reason,
                   :sourceType, :sourceId, :idempotencyKey, :requestHash,
                   :actorAssignmentId, :actorId, :occurredAt,
                   :baseUnit, :inputUnit, :inputQuantity, :conversionFactor, :inputUnitCost)
                """, hotel(principal, hotelId)
                .addValue("id", movementId)
                .addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId)
                .addValue("itemId", request.itemId())
                .addValue("movementType", type)
                .addValue("delta", delta)
                .addValue("before", before)
                .addValue("after", after)
                .addValue("unitCost", unitCost)
                .addValue("valueDelta", valueDelta)
                .addValue("baseUnit", units.baseUnit()).addValue("inputUnit", inputUnit)
                .addValue("inputQuantity", inputQuantity).addValue("conversionFactor", factor)
                .addValue("inputUnitCost", inputUnitCost)
                .addValue("reason", clean(request.reason()))
                .addValue("idempotencyKey", key)
                .addValue("requestHash", requestHash)
                .addValue("actorAssignmentId", principal.businessActorAssignmentId())
                .addValue("actorId", principal.actorId())
                .addValue("occurredAt", request.occurredAt() == null
                        ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt()));
        audit(principal, "STORE_INVENTORY_MOVEMENT_CREATED", "STORE_INVENTORY_MOVEMENT", movementId,
                Map.of("hotelId", hotelId, "itemId", request.itemId(), "type", type,
                        "quantityBefore", before, "quantityAfter", after,
                        "inputQuantity", inputQuantity, "inputUnit", inputUnit, "conversionFactor", factor,
                        "baseUnit", units.baseUnit(), "valueDelta", valueDelta));
        return requireMovement(principal, movementId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assets(UUID hotelId, boolean includeRetired) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return usageScopes.decorateAssets(principal.tenantId(),hotelId,jdbc.queryForList("""
                select asset.id, asset.hotel_org_unit_id, asset.asset_code,
                       asset.name, asset.category, asset.specification, asset.serial_number,
                       asset.unit, asset.original_value, asset.acquired_on, asset.location,
                       asset.responsible_assignment_id, asset.status, asset.row_version,
                       employee.name as responsible_name, position.name as responsible_position_name,
                       asset.created_at, asset.updated_at, asset.usage_scope_mode
                from store_fixed_asset asset
                left join employee_position_assignment assignment
                  on assignment.tenant_id = asset.tenant_id and assignment.id = asset.responsible_assignment_id
                left join employee on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                left join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                where asset.tenant_id = :tenantId and asset.hotel_org_unit_id = :hotelId
                  and (:includeRetired = true or asset.status <> 'RETIRED')
                order by asset.status, asset.category, asset.asset_code
                """, hotel(principal, hotelId).addValue("includeRetired", includeRetired)));
    }

    @Transactional
    public Map<String, Object> createAsset(UUID hotelId, StoreResourceModels.CreateAssetRequest request) {
        TenantPrincipal principal = prepareHotel("store-resource.asset-manage", hotelId);
        var options=usageScopes.options(principal.tenantId(),hotelId,true);
        var scope=request.usageScope()==null ? usageScopes.infer(principal.tenantId(),hotelId,request.assetCode(),options)
                : usageScopes.validate(request.usageScope(),options);
        UUID responsible = request.responsibleAssignmentId();
        if (responsible != null) requireAssignmentAtHotel(principal, hotelId, responsible);
        String status = request.status() == null || request.status().isBlank()
                ? "ACTIVE" : enumValue(request.status(), ASSET_STATUSES, "资产状态");
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = hotel(principal, hotelId)
                .addValue("id", id).addValue("assetCode", clean(request.assetCode()))
                .addValue("name", clean(request.name())).addValue("category", clean(request.category()))
                .addValue("specification", optional(request.specification()))
                .addValue("serialNumber", optional(request.serialNumber()))
                .addValue("unit", clean(request.unit())).addValue("originalValue", request.originalValue())
                .addValue("acquiredOn", request.acquiredOn()).addValue("location", optional(request.location()))
                .addValue("responsibleAssignmentId", responsible).addValue("status", status)
                .addValue("actorId", principal.actorId());
        try {
            jdbc.update("""
                    insert into store_fixed_asset
                      (id, tenant_id, hotel_org_unit_id, asset_code, name, category,
                       specification, serial_number, unit, original_value, acquired_on,
                       location, responsible_assignment_id, status, created_by)
                    values
                      (:id, :tenantId, :hotelId, :assetCode, :name, :category,
                       :specification, :serialNumber, :unit, :originalValue, :acquiredOn,
                       :location, :responsibleAssignmentId, :status, :actorId)
                    """, params);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("该门店已存在相同资产编号");
        }
        usageScopes.saveAsset(principal.tenantId(),hotelId,id,scope);
        audit(principal, "STORE_FIXED_ASSET_CREATED", "STORE_FIXED_ASSET", id,
                Map.of("hotelId", hotelId, "assetCode", clean(request.assetCode())));
        return requireAsset(principal, hotelId, id);
    }

    @Transactional
    public Map<String, Object> updateAsset(
            UUID hotelId, UUID assetId, StoreResourceModels.UpdateAssetRequest request
    ) {
        TenantPrincipal principal = prepareHotel("store-resource.asset-manage", hotelId);
        if (request.responsibleAssignmentId() != null) {
            requireAssignmentAtHotel(principal, hotelId, request.responsibleAssignmentId());
        }
        String status = enumValue(request.status(), ASSET_STATUSES, "资产状态");
        int changed;
        try {
            changed = jdbc.update("""
                    update store_fixed_asset
                    set asset_code = :assetCode, name = :name, category = :category,
                        specification = :specification, serial_number = :serialNumber,
                        unit = :unit, original_value = :originalValue, acquired_on = :acquiredOn,
                        location = :location, responsible_assignment_id = :responsibleAssignmentId,
                        status = :status, row_version = row_version + 1
                    where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                      and id = :assetId and row_version = :expectedVersion
                    """, hotel(principal, hotelId)
                    .addValue("assetId", assetId).addValue("assetCode", clean(request.assetCode()))
                    .addValue("name", clean(request.name())).addValue("category", clean(request.category()))
                    .addValue("specification", optional(request.specification()))
                    .addValue("serialNumber", optional(request.serialNumber()))
                    .addValue("unit", clean(request.unit())).addValue("originalValue", request.originalValue())
                    .addValue("acquiredOn", request.acquiredOn()).addValue("location", optional(request.location()))
                    .addValue("responsibleAssignmentId", request.responsibleAssignmentId())
                    .addValue("status", status).addValue("expectedVersion", request.expectedVersion()));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("该门店已存在相同资产编号");
        }
        changed(changed, "资产卡片已变化，请刷新后重试");
        if(request.usageScope()!=null) usageScopes.saveAsset(principal.tenantId(),hotelId,assetId,
                usageScopes.validate(request.usageScope(),usageScopes.options(principal.tenantId(),hotelId,true)));
        audit(principal, "STORE_FIXED_ASSET_UPDATED", "STORE_FIXED_ASSET", assetId,
                Map.of("hotelId", hotelId, "status", status));
        return requireAsset(principal, hotelId, assetId);
    }

    @Transactional
    public void retireAsset(UUID hotelId, UUID assetId) {
        TenantPrincipal principal = prepareHotel("store-resource.asset-manage", hotelId);
        int changed = jdbc.update("""
                update store_fixed_asset
                set status = 'RETIRED', row_version = row_version + 1
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId and id = :assetId
                  and status <> 'RETIRED'
                """, hotel(principal, hotelId).addValue("assetId", assetId));
        changed(changed, "资产不存在或已经退役");
        audit(principal, "STORE_FIXED_ASSET_RETIRED", "STORE_FIXED_ASSET", assetId,
                Map.of("hotelId", hotelId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignmentOptions(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return jdbc.queryForList("""
                select assignment.id as assignment_id, employee.id as employee_id,
                       employee.name as employee_name,
                       employee.account_id,
                       position.name as position_name, position.code as position_code,
                       assignment.org_unit_id, organization.name as organization_name,
                       assignment.is_primary
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                  and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                  and position.id = assignment.position_id
                join org_unit organization on organization.tenant_id = assignment.tenant_id
                  and organization.id = assignment.org_unit_id
                where assignment.tenant_id = :tenantId and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and position.status = 'ACTIVE' and position.deleted_at is null
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = assignment.tenant_id
                      and scope.ancestor_id = :hotelId and scope.descendant_id = assignment.org_unit_id
                )
                order by employee.name, position.name
                """, hotel(principal, hotelId)).stream().map(source -> {
                    Map<String, Object> option = new LinkedHashMap<>(source);
                    UUID assignmentId = uuid(option.get("assignment_id"));
                    Object accountValue = option.remove("account_id");
                    TenantPrincipal target = accountValue == null ? null
                            : resolveAssignmentPrincipal(principal, uuid(accountValue), assignmentId);
                    boolean canConfirm = assignmentCanParticipate(
                            target, "store-resource.handover-confirm");
                    boolean canApprove = assignmentCanParticipate(
                            target, "store-resource.handover-approve");
                    option.put("can_confirm_handover", canConfirm);
                    option.put("can_approve_handover", canApprove);
                    return option;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> handovers(UUID hotelId) {
        TenantPrincipal principal = prepareHotel("store-resource.read", hotelId);
        return jdbc.queryForList(handoverSelect() + """
                where handover.tenant_id = :tenantId and handover.hotel_org_unit_id = :hotelId
                order by handover.created_at desc
                limit 200
                """, hotel(principal, hotelId)).stream()
                .map(row -> decorateHandover(principal, row)).toList();
    }

    @Transactional
    public Map<String, Object> createHandover(
            UUID hotelId, StoreResourceModels.CreateHandoverRequest request
    ) {
        TenantPrincipal principal = prepareHotel("store-resource.handover-create", hotelId);
        requireDistinctPeople(principal, request.transferorAssignmentId(),
                request.recipientAssignmentId(), request.approverAssignmentId());
        requireAssignmentAtHotel(principal, hotelId, request.transferorAssignmentId());
        requireAssignmentAtHotel(principal, hotelId, request.recipientAssignmentId());
        requireAssignmentAtHotel(principal, hotelId, request.approverAssignmentId());
        requireAssignmentPermission(principal, request.transferorAssignmentId(),
                "store-resource.handover-confirm", "移交人缺少移交确认权限");
        requireAssignmentPermission(principal, request.recipientAssignmentId(),
                "store-resource.handover-confirm", "签收人缺少移交确认权限");
        requireAssignmentPermission(principal, request.approverAssignmentId(),
                "store-resource.handover-approve", "审批人缺少移交审批权限");
        Integer open = jdbc.queryForObject("""
                select count(*) from store_resource_handover
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId
                  and status in ('AWAITING_DISPUTE_DECISION','PHYSICAL_COUNT_REQUIRED',
                    'AWAITING_TRANSFEROR_CONFIRMATION','AWAITING_RECIPIENT_CONFIRMATION','AWAITING_APPROVAL')
                """, hotel(principal, hotelId), Integer.class);
        if (open != null && open > 0) throw conflict("该门店已有进行中的异动移交");
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = hotel(principal, hotelId).addValue("id", id)
                .addValue("transferor", request.transferorAssignmentId())
                .addValue("recipient", request.recipientAssignmentId())
                .addValue("approver", request.approverAssignmentId())
                .addValue("actorId", principal.actorId());
        try {
            jdbc.update("""
                    insert into store_resource_handover
                      (id, tenant_id, hotel_org_unit_id, transferor_assignment_id,
                       recipient_assignment_id, approver_assignment_id, created_by)
                    values (:id, :tenantId, :hotelId, :transferor, :recipient, :approver, :actorId)
                    """, params);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("该门店已有进行中的异动移交");
        }
        jdbc.update("""
                insert into store_resource_handover_snapshot_line
                  (tenant_id, handover_id, hotel_org_unit_id, resource_type, item_id,
                   resource_code, resource_name, unit, book_quantity, book_value)
                select item.tenant_id, :id, item.hotel_org_unit_id, 'INVENTORY', item.id,
                       item.sku, item.name, item.unit, balance.quantity, balance.inventory_value
                from store_resource_item item
                join store_inventory_balance balance
                  on balance.tenant_id = item.tenant_id
                 and balance.hotel_org_unit_id = item.hotel_org_unit_id
                 and balance.item_id = item.id
                where item.tenant_id = :tenantId and item.hotel_org_unit_id = :hotelId
                  and item.status = 'ACTIVE'
                """, params);
        jdbc.update("""
                insert into store_resource_handover_snapshot_line
                  (tenant_id, handover_id, hotel_org_unit_id, resource_type, asset_id,
                   resource_code, resource_name, unit, book_state, book_value)
                select asset.tenant_id, :id, asset.hotel_org_unit_id, 'ASSET', asset.id,
                       asset.asset_code, asset.name, asset.unit, asset.status, asset.original_value
                from store_fixed_asset asset
                where asset.tenant_id = :tenantId and asset.hotel_org_unit_id = :hotelId
                  and asset.status <> 'RETIRED'
                """, params);
        appendAction(principal, id, null, "CREATED", null, "AWAITING_DISPUTE_DECISION",
                optional(request.note()), Map.of("snapshotCreated", true));
        audit(principal, "STORE_RESOURCE_HANDOVER_CREATED", "STORE_RESOURCE_HANDOVER", id,
                Map.of("hotelId", hotelId, "transferorAssignmentId", request.transferorAssignmentId(),
                        "recipientAssignmentId", request.recipientAssignmentId(),
                        "approverAssignmentId", request.approverAssignmentId()));
        return handover(principal, id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> handover(UUID handoverId) {
        TenantPrincipal principal = prepare("store-resource.read");
        return handover(principal, handoverId);
    }

    @Transactional
    public Map<String, Object> decideDispute(
            UUID handoverId, StoreResourceModels.DisputeDecisionRequest request
    ) {
        TenantPrincipal principal = prepare("store-resource.handover-confirm");
        Map<String, Object> handover = lockHandover(principal, handoverId);
        requireStatus(handover, "AWAITING_DISPUTE_DECISION");
        requireVersion(handover, request.expectedVersion());
        UUID actorAssignment = requireActorAssignment(principal);
        UUID transferor = uuid(handover.get("transferor_assignment_id"));
        UUID recipient = uuid(handover.get("recipient_assignment_id"));
        if (!actorAssignment.equals(transferor) && !actorAssignment.equals(recipient)) {
            throw new AccessDeniedException("只有本次移交人或签收人可以确认账面快照异议");
        }
        if (request.hasDispute() && optional(request.reason()) == null) {
            throw new IllegalArgumentException("选择有异议时必须填写异议原因");
        }
        String next = request.hasDispute() ? "PHYSICAL_COUNT_REQUIRED" : "AWAITING_TRANSFEROR_CONFIRMATION";
        transition(principal, handoverId, request.expectedVersion(), "AWAITING_DISPUTE_DECISION", next,
                "DISPUTE_DECIDED", actorAssignment,
                request.hasDispute() ? "OBJECTION" : "NO_OBJECTION", optional(request.reason()), null,
                Map.of("hasDispute", request.hasDispute()));
        return handover(principal, handoverId);
    }

    @Transactional
    public Map<String, Object> recordPhysicalCount(
            UUID handoverId, StoreResourceModels.PhysicalCountRequest request
    ) {
        TenantPrincipal principal = prepare("store-resource.handover-confirm");
        Map<String, Object> handover = lockHandover(principal, handoverId);
        requireStatus(handover, "PHYSICAL_COUNT_REQUIRED");
        requireVersion(handover, request.expectedVersion());
        UUID actorAssignment = requireActorAssignment(principal);
        if (!actorAssignment.equals(uuid(handover.get("transferor_assignment_id")))) {
            throw new AccessDeniedException("只有移交人可以提交实物盘点");
        }
        Integer lineCount = jdbc.queryForObject("""
                select count(*) from store_resource_handover_snapshot_line
                where tenant_id = :tenantId and handover_id = :handoverId
                """, base(principal).addValue("handoverId", handoverId), Integer.class);
        if (lineCount == null || request.lines().size() != lineCount) {
            throw new IllegalArgumentException("实物盘点必须覆盖账面快照的全部项目");
        }
        if (new HashSet<>(request.lines().stream()
                .map(StoreResourceModels.PhysicalCountLineRequest::lineId).toList()).size()
                != request.lines().size()) {
            throw new IllegalArgumentException("实物盘点不能重复提交同一项目");
        }
        for (StoreResourceModels.PhysicalCountLineRequest line : request.lines()) {
            int changed = jdbc.update("""
                    update store_resource_handover_snapshot_line
                    set physical_quantity = case
                            when resource_type = 'INVENTORY' then cast(:physicalQuantity as numeric)
                            else null::numeric
                        end,
                        physical_state = case
                            when resource_type = 'ASSET' then cast(:physicalState as varchar)
                            else null::varchar
                        end,
                        difference_reason = :differenceReason
                    where tenant_id = :tenantId and handover_id = :handoverId and id = :lineId
                      and ((resource_type = 'INVENTORY' and cast(:physicalQuantity as numeric) is not null)
                        or (resource_type = 'ASSET' and nullif(trim(cast(:physicalState as varchar)), '') is not null))
                    """, base(principal).addValue("handoverId", handoverId)
                    .addValue("lineId", line.lineId())
                    .addValue("physicalQuantity", line.physicalQuantity())
                    .addValue("physicalState", optional(line.physicalState()))
                    .addValue("differenceReason", optional(line.differenceReason())));
            if (changed != 1) throw new IllegalArgumentException("盘点项目无效或缺少实际数量/状态");
        }
        transition(principal, handoverId, request.expectedVersion(), "PHYSICAL_COUNT_REQUIRED",
                "AWAITING_TRANSFEROR_CONFIRMATION", "PHYSICAL_COUNT_RECORDED", actorAssignment,
                null, null, optional(request.note()), Map.of("lineCount", request.lines().size()));
        return handover(principal, handoverId);
    }

    @Transactional
    public Map<String, Object> confirmTransferor(
            UUID handoverId, StoreResourceModels.VersionCommand request
    ) {
        TenantPrincipal principal = prepare("store-resource.handover-confirm");
        return confirmParticipant(principal, handoverId, request, "transferor_assignment_id",
                "AWAITING_TRANSFEROR_CONFIRMATION", "AWAITING_RECIPIENT_CONFIRMATION",
                "TRANSFEROR_CONFIRMED", "只有移交人可以执行本步骤");
    }

    @Transactional
    public Map<String, Object> confirmRecipient(
            UUID handoverId, StoreResourceModels.VersionCommand request
    ) {
        TenantPrincipal principal = prepare("store-resource.handover-confirm");
        return confirmParticipant(principal, handoverId, request, "recipient_assignment_id",
                "AWAITING_RECIPIENT_CONFIRMATION", "AWAITING_APPROVAL",
                "RECIPIENT_CONFIRMED", "只有签收人可以执行本步骤");
    }

    @Transactional
    public Map<String, Object> approve(UUID handoverId, StoreResourceModels.ApprovalRequest request) {
        TenantPrincipal principal = prepare("store-resource.handover-approve");
        Map<String, Object> handover = lockHandover(principal, handoverId);
        requireStatus(handover, "AWAITING_APPROVAL");
        requireVersion(handover, request.expectedVersion());
        UUID actorAssignment = requireActorAssignment(principal);
        if (!actorAssignment.equals(uuid(handover.get("approver_assignment_id")))) {
            throw new AccessDeniedException("只有指定审批人可以审批本次移交");
        }
        if (!request.approved() && optional(request.reason()) == null) {
            throw new IllegalArgumentException("退回移交时必须填写原因");
        }
        String next = request.approved() ? "COMPLETED" : "REJECTED";
        int changed = jdbc.update("""
                update store_resource_handover
                set status = :nextStatus,
                    completed_at = case when :approved = true then now() else null end,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :handoverId
                  and status = 'AWAITING_APPROVAL' and row_version = :expectedVersion
                """, base(principal).addValue("handoverId", handoverId)
                .addValue("nextStatus", next).addValue("approved", request.approved())
                .addValue("expectedVersion", request.expectedVersion()));
        changed(changed, "移交状态已变化，请刷新后重试");
        if (request.approved()) {
            jdbc.update("""
                    update store_fixed_asset asset
                    set responsible_assignment_id = :recipient, row_version = row_version + 1
                    from store_resource_handover_snapshot_line line
                    where line.tenant_id = :tenantId and line.handover_id = :handoverId
                      and line.resource_type = 'ASSET' and line.asset_id = asset.id
                      and asset.tenant_id = line.tenant_id
                    """, base(principal).addValue("handoverId", handoverId)
                    .addValue("recipient", handover.get("recipient_assignment_id")));
        }
        appendAction(principal, handoverId, actorAssignment,
                request.approved() ? "APPROVED" : "REJECTED",
                "AWAITING_APPROVAL", next, optional(request.reason()),
                Map.of("approved", request.approved()));
        audit(principal, request.approved() ? "STORE_RESOURCE_HANDOVER_APPROVED" : "STORE_RESOURCE_HANDOVER_REJECTED",
                "STORE_RESOURCE_HANDOVER", handoverId, Map.of("status", next));
        return handover(principal, handoverId);
    }

    private Map<String, Object> confirmParticipant(
            TenantPrincipal principal, UUID handoverId, StoreResourceModels.VersionCommand request,
            String assignmentColumn, String expectedStatus, String nextStatus,
            String action, String deniedMessage
    ) {
        Map<String, Object> handover = lockHandover(principal, handoverId);
        requireStatus(handover, expectedStatus);
        requireVersion(handover, request.expectedVersion());
        UUID actorAssignment = requireActorAssignment(principal);
        if (!actorAssignment.equals(uuid(handover.get(assignmentColumn)))) {
            throw new AccessDeniedException(deniedMessage);
        }
        transition(principal, handoverId, request.expectedVersion(), expectedStatus, nextStatus,
                action, actorAssignment, null, null, optional(request.reason()), Map.of());
        return handover(principal, handoverId);
    }

    private void transition(
            TenantPrincipal principal, UUID handoverId, long version,
            String expectedStatus, String nextStatus, String action, UUID actorAssignment,
            String disputeDecision, String disputeReason, String note, Map<String, Object> payload
    ) {
        int changed = jdbc.update("""
                update store_resource_handover
                set status = :nextStatus,
                    dispute_decision = coalesce(:disputeDecision, dispute_decision),
                    dispute_reason = coalesce(:disputeReason, dispute_reason),
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :handoverId
                  and status = :expectedStatus and row_version = :expectedVersion
                """, base(principal).addValue("handoverId", handoverId)
                .addValue("nextStatus", nextStatus).addValue("expectedStatus", expectedStatus)
                .addValue("expectedVersion", version).addValue("disputeDecision", disputeDecision)
                .addValue("disputeReason", disputeReason));
        changed(changed, "移交状态已变化，请刷新后重试");
        appendAction(principal, handoverId, actorAssignment, action, expectedStatus, nextStatus,
                note, payload);
        audit(principal, "STORE_RESOURCE_HANDOVER_" + action,
                "STORE_RESOURCE_HANDOVER", handoverId, Map.of("status", nextStatus));
    }

    private void appendAction(
            TenantPrincipal principal, UUID handoverId, UUID actorAssignment,
            String action, String fromStatus, String toStatus, String note,
            Map<String, Object> payload
    ) {
        jdbc.update("""
                insert into store_resource_handover_action
                  (tenant_id, handover_id, action_type, from_status, to_status,
                   actor_assignment_id, actor_account_id, note, action_payload)
                values
                  (:tenantId, :handoverId, :action, :fromStatus, :toStatus,
                   :actorAssignment, :actorId, :note, cast(:payload as jsonb))
                """, base(principal).addValue("handoverId", handoverId)
                .addValue("action", action).addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus).addValue("actorAssignment", actorAssignment)
                .addValue("actorId", principal.actorId()).addValue("note", note)
                .addValue("payload", json(payload)));
    }

    private Map<String, Object> handover(TenantPrincipal principal, UUID handoverId) {
        Map<String, Object> row = jdbc.queryForMap(handoverSelect() + """
                where handover.tenant_id = :tenantId and handover.id = :handoverId
                """, base(principal).addValue("handoverId", handoverId));
        UUID hotelId = uuid(row.get("hotel_org_unit_id"));
        accessPolicy.requireOrgScope(hotelId);
        Map<String, Object> result = new LinkedHashMap<>(decorateHandover(principal, row));
        result.put("lines", jdbc.queryForList("""
                select id, resource_type, resource_code, resource_name, unit,
                       book_quantity, book_state, book_value,
                       physical_quantity, physical_state, difference_reason
                from store_resource_handover_snapshot_line
                where tenant_id = :tenantId and handover_id = :handoverId
                order by resource_type, resource_code
                """, base(principal).addValue("handoverId", handoverId)));
        result.put("actions", jdbc.queryForList("""
                select action.action_type, action.from_status, action.to_status,
                       action.note, action.created_at,
                       coalesce(account.display_name, '系统') as actor_name
                from store_resource_handover_action action
                left join user_account account on account.tenant_id = action.tenant_id
                  and account.id = action.actor_account_id
                where action.tenant_id = :tenantId and action.handover_id = :handoverId
                order by action.created_at, action.id
                """, base(principal).addValue("handoverId", handoverId)));
        return result;
    }

    private Map<String, Object> decorateHandover(TenantPrincipal principal, Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        String status = String.valueOf(row.get("status"));
        UUID actor = principal.businessActorAssignmentId();
        List<String> actions = List.of();
        if (actor != null && principal.hasPermission("store-resource.handover-confirm")) {
            if ("AWAITING_DISPUTE_DECISION".equals(status)
                    && (actor.equals(uuid(row.get("transferor_assignment_id")))
                    || actor.equals(uuid(row.get("recipient_assignment_id"))))) {
                actions = List.of("MARK_NO_DISPUTE", "MARK_DISPUTED");
            } else if ("PHYSICAL_COUNT_REQUIRED".equals(status)
                    && actor.equals(uuid(row.get("transferor_assignment_id")))) {
                actions = List.of("SUBMIT_PHYSICAL_COUNT");
            } else if ("AWAITING_TRANSFEROR_CONFIRMATION".equals(status)
                    && actor.equals(uuid(row.get("transferor_assignment_id")))) {
                actions = List.of("CONFIRM_TRANSFEROR");
            } else if ("AWAITING_RECIPIENT_CONFIRMATION".equals(status)
                    && actor.equals(uuid(row.get("recipient_assignment_id")))) {
                actions = List.of("CONFIRM_RECIPIENT");
            }
        }
        if (actor != null && principal.hasPermission("store-resource.handover-approve")
                && "AWAITING_APPROVAL".equals(status)
                && actor.equals(uuid(row.get("approver_assignment_id")))) {
            actions = List.of("APPROVE", "REJECT");
        }
        row.put("allowed_actions", actions);
        row.put("has_dispute", "OBJECTION".equals(row.get("dispute_decision")));
        row.put("physical_count_required", "PHYSICAL_COUNT_REQUIRED".equals(status));
        row.put("handover_no", "YJ-" + row.get("created_at").toString().substring(0, 10).replace("-", "")
                + "-" + row.get("id").toString().substring(0, 8).toUpperCase(Locale.ROOT));
        return row;
    }

    private String handoverSelect() {
        return """
                select handover.id, handover.hotel_org_unit_id, handover.status,
                       handover.transferor_assignment_id, handover.recipient_assignment_id,
                       handover.approver_assignment_id, handover.dispute_decision,
                       handover.dispute_reason, handover.snapshot_at, handover.completed_at,
                       handover.row_version, handover.created_at, handover.updated_at,
                       transferor.name as transferor_name, recipient.name as recipient_name,
                       approver.name as approver_name,
                       (select count(*) from store_resource_handover_snapshot_line line
                         where line.tenant_id = handover.tenant_id and line.handover_id = handover.id
                           and line.resource_type = 'INVENTORY') as item_count,
                       (select count(*) from store_resource_handover_snapshot_line line
                         where line.tenant_id = handover.tenant_id and line.handover_id = handover.id
                           and line.resource_type = 'ASSET') as asset_count,
                       (select count(*) from store_resource_handover_snapshot_line line
                         where line.tenant_id = handover.tenant_id and line.handover_id = handover.id)
                         as line_count
                from store_resource_handover handover
                join employee_position_assignment transferor_assignment
                  on transferor_assignment.tenant_id = handover.tenant_id
                 and transferor_assignment.id = handover.transferor_assignment_id
                join employee transferor on transferor.tenant_id = transferor_assignment.tenant_id
                 and transferor.id = transferor_assignment.employee_id
                join employee_position_assignment recipient_assignment
                  on recipient_assignment.tenant_id = handover.tenant_id
                 and recipient_assignment.id = handover.recipient_assignment_id
                join employee recipient on recipient.tenant_id = recipient_assignment.tenant_id
                 and recipient.id = recipient_assignment.employee_id
                join employee_position_assignment approver_assignment
                  on approver_assignment.tenant_id = handover.tenant_id
                 and approver_assignment.id = handover.approver_assignment_id
                join employee approver on approver.tenant_id = approver_assignment.tenant_id
                 and approver.id = approver_assignment.employee_id
                """;
    }

    private Map<String, Object> lockHandover(TenantPrincipal principal, UUID handoverId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select * from store_resource_handover
                where tenant_id = :tenantId and id = :handoverId
                for update
                """, base(principal).addValue("handoverId", handoverId));
        accessPolicy.requireOrgScope(uuid(row.get("hotel_org_unit_id")));
        return row;
    }

    private Map<String, Object> requireItem(TenantPrincipal principal, UUID hotelId, UUID itemId) {
        return usageScopes.decorate(principal.tenantId(), hotelId, List.of(jdbc.queryForMap("""
                select item.id, item.hotel_org_unit_id, item.sku, item.name,
                       item.category, item.unit, item.unit_cost, item.minimum_quantity,
                       item.employee_usage_enabled, item.specification, item.purchase_unit, item.purchase_factor, item.issue_unit, item.issue_factor,
                       (exists (select 1 from store_inventory_movement history
                         where history.tenant_id = item.tenant_id and history.hotel_org_unit_id = item.hotel_org_unit_id
                           and history.item_id = item.id)
                        or exists (select 1 from store_work_usage_line usage_line
                          where usage_line.tenant_id=item.tenant_id and usage_line.item_id=item.id)
                        or exists (select 1 from store_laundry_rate rate
                          where rate.tenant_id=item.tenant_id and rate.item_id=item.id)
                        or exists (select 1 from procurement_line purchase
                          where purchase.tenant_id=item.tenant_id and purchase.item_id=item.id)) as base_unit_locked,
                       item.status, item.row_version, item.usage_scope_mode,
                       coalesce(balance.quantity, 0) as current_quantity,
                       coalesce(balance.inventory_value, 0) as inventory_value,
                       item.created_at, item.updated_at
                from store_resource_item item
                left join store_inventory_balance balance on balance.tenant_id = item.tenant_id
                  and balance.hotel_org_unit_id = item.hotel_org_unit_id and balance.item_id = item.id
                where item.tenant_id = :tenantId and item.hotel_org_unit_id = :hotelId and item.id = :itemId
                """, hotel(principal, hotelId).addValue("itemId", itemId)))).getFirst();
    }

    private Map<String, Object> lockItem(TenantPrincipal principal, UUID hotelId, UUID itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from store_resource_item
                where tenant_id = :tenantId and hotel_org_unit_id = :hotelId and id = :itemId
                for update
                """, hotel(principal, hotelId).addValue("itemId", itemId));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该门店不存在此物品");
        return rows.getFirst();
    }

    private Map<String, Object> requireMovement(TenantPrincipal principal, UUID movementId) {
        return jdbc.queryForMap("""
                select movement.id, movement.hotel_org_unit_id as hotel_id, movement.item_id,
                       item.name as item_name, coalesce(movement.base_unit_snapshot, item.unit) as unit,
                       coalesce(movement.input_unit, item.unit) as input_unit,
                       coalesce(movement.input_quantity, movement.quantity_delta) as input_quantity,
                       coalesce(movement.conversion_factor, 1) as conversion_factor, movement.input_unit_cost,
                       movement.movement_type,
                       movement.quantity_delta, movement.quantity_before, movement.quantity_after,
                       movement.unit_cost_snapshot, movement.value_delta, movement.reason,
                       movement.source_type, movement.source_id,
                       coalesce(account.display_name, '系统') as operator_name, movement.occurred_at
                from store_inventory_movement movement
                join store_resource_item item on item.tenant_id = movement.tenant_id and item.id = movement.item_id
                left join user_account account on account.tenant_id = movement.tenant_id
                  and account.id = movement.actor_account_id
                where movement.tenant_id = :tenantId and movement.id = :movementId
                """, base(principal).addValue("movementId", movementId));
    }

    private Map<String, Object> requireAsset(TenantPrincipal principal, UUID hotelId, UUID assetId) {
        return usageScopes.decorateAssets(principal.tenantId(),hotelId,List.of(jdbc.queryForMap("""
                select asset.id, asset.hotel_org_unit_id, asset.asset_code,
                       asset.name, asset.category, asset.specification, asset.serial_number,
                       asset.unit, asset.original_value, asset.acquired_on, asset.location,
                       asset.responsible_assignment_id, asset.status, asset.row_version,
                       employee.name as responsible_name, position.name as responsible_position_name,
                       asset.created_at, asset.updated_at, asset.usage_scope_mode
                from store_fixed_asset asset
                left join employee_position_assignment assignment
                  on assignment.tenant_id = asset.tenant_id and assignment.id = asset.responsible_assignment_id
                left join employee on employee.tenant_id = assignment.tenant_id and employee.id = assignment.employee_id
                left join position_definition position on position.tenant_id = assignment.tenant_id
                  and position.id = assignment.position_id
                where asset.tenant_id = :tenantId and asset.hotel_org_unit_id = :hotelId and asset.id = :assetId
                """, hotel(principal, hotelId).addValue("assetId", assetId)))).getFirst();
    }

    private void requireAssignmentAtHotel(TenantPrincipal principal, UUID hotelId, UUID assignmentId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                  and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                  and position.id = assignment.position_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and position.status = 'ACTIVE' and position.deleted_at is null
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = assignment.tenant_id
                      and scope.ancestor_id = :hotelId and scope.descendant_id = assignment.org_unit_id
                  )
                """, hotel(principal, hotelId).addValue("assignmentId", assignmentId), Integer.class);
        if (count == null || count != 1) throw new IllegalArgumentException("移交人员必须是该门店当前有效任职");
    }

    private void requireAssignmentPermission(
            TenantPrincipal principal, UUID assignmentId, String permission, String message
    ) {
        UUID accountId = jdbc.queryForObject("""
                select employee.account_id
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                  and employee.id = assignment.employee_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                """, base(principal).addValue("assignmentId", assignmentId), UUID.class);
        TenantPrincipal target = accountId == null ? null
                : resolveAssignmentPrincipal(principal, accountId, assignmentId);
        if (!assignmentCanParticipate(target, permission)) {
            throw new IllegalArgumentException(message + "，请先在岗位与功能配置中授权");
        }
    }

    private TenantPrincipal resolveAssignmentPrincipal(
            TenantPrincipal principal, UUID accountId, UUID assignmentId
    ) {
        try {
            return effectiveIdentityService.resolve(
                    principal.tenantId(), accountId, principal.correlationId(), assignmentId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean assignmentCanParticipate(TenantPrincipal target, String permission) {
        return target != null && (target.hasPermission("*")
                || (target.hasPermission("ui.module.store-resources")
                && target.hasPermission("store-resource.read")
                && target.hasPermission(permission)));
    }

    private TenantPrincipal prepare(String permission) {
        accessPolicy.requirePermission(permission);
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private TenantPrincipal prepareHotel(String permission, UUID hotelId) {
        TenantPrincipal principal = prepare(permission);
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

    private UUID requireActorAssignment(TenantPrincipal principal) {
        UUID assignment = principal.businessActorAssignmentId();
        if (assignment == null) throw new AccessDeniedException("当前操作必须选择本人有效业务任职");
        return assignment;
    }

    private void requireDistinctPeople(
            TenantPrincipal principal, UUID transferor, UUID recipient, UUID approver
    ) {
        if (transferor.equals(recipient) || transferor.equals(approver) || recipient.equals(approver)) {
            throw new IllegalArgumentException("移交人、签收人、审批人必须为三名不同人员任职");
        }
        Integer employeeCount = jdbc.queryForObject("""
                select count(distinct employee_id)
                from employee_position_assignment
                where tenant_id = :tenantId and id in (:assignmentIds)
                """, base(principal).addValue("assignmentIds", List.of(transferor, recipient, approver)),
                Integer.class);
        if (employeeCount == null || employeeCount != 3) {
            throw new IllegalArgumentException("移交人、签收人、审批人必须为三名不同员工");
        }
    }

    private void requireStatus(Map<String, Object> handover, String expected) {
        if (!expected.equals(handover.get("status"))) {
            throw conflict("当前移交不处于可执行该操作的步骤");
        }
    }

    private void requireVersion(Map<String, Object> handover, long expected) {
        if (((Number) handover.get("row_version")).longValue() != expected) {
            throw conflict("移交记录已变化，请刷新后重试");
        }
    }

    private static void changed(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = clean(value).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(label + "无效");
        return normalized;
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("必填内容不能为空");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String cleanKey(String value) {
        String key = clean(value);
        if (key.length() > 200) throw new IllegalArgumentException("幂等键长度不能超过200");
        return key;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(String.valueOf(value));
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private void audit(
            TenantPrincipal principal, String action, String resourceType, UUID resourceId,
            Map<String, Object> payload
    ) {
        auditWriter.record(action, resourceType, resourceId, json(payload));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计数据序列化失败", exception);
        }
    }
}
