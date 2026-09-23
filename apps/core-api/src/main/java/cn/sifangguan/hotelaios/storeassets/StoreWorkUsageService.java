package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.dailyreports.DailyReportRoutineProjectionService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Opt-in, typed actual-use registration. Issuing stock is not the same as consuming it. */
@Service
public class StoreWorkUsageService {
    private static final Set<String> USAGE_ITEMS = Set.of("HKA_USAGE_RETURN", "HKA_DAILY_REPORT", "NSA_DAILY_HANDOVER");
    private static final Set<String> SHARED_ITEMS = Set.of("HKA_USAGE_RETURN", "HKA_DAILY_REPORT");
    private static final Set<String> ISSUE_ITEMS = Set.of("HKA_ITEM_ISSUE", "NSA_ITEM_ISSUE");
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy access;
    private final EffectiveIdentityService identities;
    private final StoreResourceService stock;
    private final AuditWriter audit;
    private final ObjectMapper mapper;
    private final DailyReportRoutineProjectionService projection;

    public StoreWorkUsageService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext databaseContext,
            AccessPolicy access, EffectiveIdentityService identities, StoreResourceService stock,
            AuditWriter audit, ObjectMapper mapper, DailyReportRoutineProjectionService projection) {
        this.jdbc = jdbc; this.databaseContext = databaseContext; this.access = access;
        this.identities = identities; this.stock = stock; this.audit = audit; this.mapper = mapper;
        this.projection = projection;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> catalogue(UUID orgUnitId) {
        return catalogue(orgUnitId, null, null);
    }

    /** Take this before expectation/report row locks to keep cross-entry submission lock order stable. */
    public void lockDay(UUID orgUnitId, UUID employeeId, LocalDate date) {
        TenantPrincipal p=access.principal();
        // Work outside hotels has no inventory linkage.
        List<UUID> hotels=jdbc.queryForList("""
                select o.id from org_unit_closure c join org_unit o on o.tenant_id=c.tenant_id and o.id=c.ancestor_id
                where c.tenant_id=:tenantId and c.descendant_id=:orgId and o.unit_type='HOTEL' order by c.depth limit 1
                """,base(p).addValue("orgId",orgUnitId),UUID.class);
        if(!hotels.isEmpty()) jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,67))",
                new MapSqlParameterSource("key",p.tenantId()+":"+hotels.getFirst()+":"+employeeId+":"+date));
    }
    public void lockAssignmentDay(UUID orgUnitId, UUID assignmentId, LocalDate date) {
        UUID employee=jdbc.queryForObject("select employee_id from employee_position_assignment where tenant_id=:tenantId and id=:id",
                base(access.principal()).addValue("id",assignmentId),UUID.class);
        lockDay(orgUnitId,employee,date);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> catalogue(UUID orgUnitId, LocalDate businessDate, String itemCode) {
        TenantPrincipal p = prepare(access.principal().hasPermission("work-record.submit") ? "work-record.submit" : "work-record.review");
        if (!p.hasPermission("work-record.submit")) access.requirePermission("store-resource.read");
        UUID hotelId = hotelFor(p, orgUnitId);
        if (!p.hasTenantScope() && !p.orgScopes().contains(hotelId)) {
            Integer member = jdbc.queryForObject("""
                    select count(*) from employee_position_assignment a
                    join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                    join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id
                    where a.tenant_id=:tenantId and a.id=:assignmentId and e.account_id=:actorId
                      and c.ancestor_id=:hotelId and a.status='ACTIVE' and e.employment_status='ACTIVE'
                      and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                    """, base(p).addValue("hotelId", hotelId).addValue("assignmentId", p.businessActorAssignmentId())
                    .addValue("actorId", p.actorId()), Integer.class);
            if (member == null || member == 0) throw new AccessDeniedException("只能填写本人任职门店的物品数量");
        }
        Map<String, Object> config = configuration(p, hotelId);
        Map<String, Object> result = new LinkedHashMap<>(config);
        boolean issue = ISSUE_ITEMS.contains(Objects.toString(itemCode, ""));
        result.put("mode", issue ? "ISSUE" : "USAGE");
        if (issue) result.put("enabled", true);
        // Employees must not receive financial values or unrestricted inventory balances.
        result.put("items", jdbc.queryForList("""
                select id, sku, name, category, specification, unit, issue_unit, issue_factor, row_version
                from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                  and status='ACTIVE' and employee_usage_enabled and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN')
                order by category,name,id
                """, base(p).addValue("hotelId", hotelId)));
        if (!issue) {
            List<UUID> positions = jdbc.queryForList("select position_id from employee_position_assignment where tenant_id=:tenantId and id=:assignmentId",
                    base(p).addValue("assignmentId", p.businessActorAssignmentId()), UUID.class);
            if (!positions.isEmpty()) result.put("quick_list", quickList(p, hotelId, positions.getFirst()));
        }
        if (businessDate != null && SHARED_ITEMS.contains(Objects.toString(itemCode, ""))) {
            List<UUID> employees = jdbc.queryForList("select employee_id from employee_position_assignment where tenant_id=:tenantId and id=:assignmentId",
                    base(p).addValue("assignmentId", p.businessActorAssignmentId()), UUID.class);
            if (!employees.isEmpty()) {
                UUID reference = sharedUsage(p, hotelId, employees.getFirst(), businessDate, itemCode);
                if (reference != null) result.put("reference_usage", detail(reference));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> quickListPositions(UUID hotelId) {
        TenantPrincipal p = prepareHotel("store-resource.item-manage", hotelId);
        return jdbc.queryForList("select id,name,code from position_definition where tenant_id=:tenantId and status='ACTIVE' and deleted_at is null and permanently_deleted_at is null order by name,id", base(p));
    }

    @Transactional(readOnly = true)
    public Map<String,Object> quickList(UUID hotelId, UUID positionId) {
        TenantPrincipal p = prepareHotel("store-resource.item-manage", hotelId);
        requireQuickListPosition(p, positionId);
        var result = new LinkedHashMap<>(quickList(p, hotelId, positionId));
        result.put("items", selectableItems(p, hotelId));
        return result;
    }

    private List<Map<String,Object>> selectableItems(TenantPrincipal p, UUID hotelId) {
        return jdbc.queryForList("""
                select id,sku,name,category,specification,unit,issue_unit,issue_factor,row_version
                from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                  and status='ACTIVE' and employee_usage_enabled and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN')
                order by case category when 'LINEN' then 0 when 'CONSUMABLE' then 1 else 2 end,name,id
                """, base(p).addValue("hotelId", hotelId));
    }

    private void requireQuickListPosition(TenantPrincipal p, UUID positionId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from position_definition where tenant_id=:tenantId and id=:positionId and status='ACTIVE' and deleted_at is null and permanently_deleted_at is null)",
                base(p).addValue("positionId", positionId), Boolean.class)))
            throw new IllegalArgumentException("请选择有效岗位");
    }

    private Map<String,Object> quickList(TenantPrincipal p, UUID hotelId, UUID positionId) {
        var params = base(p).addValue("hotelId", hotelId).addValue("positionId", positionId);
        var versions = jdbc.queryForList("select row_version from store_usage_quick_list where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and position_id=:positionId", params, Long.class);
        List<UUID> itemIds = versions.isEmpty() ? selectableItems(p, hotelId).stream().limit(100).map(item -> (UUID)item.get("id")).toList()
                : jdbc.queryForList("select item_id from store_usage_quick_list_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and position_id=:positionId order by display_order", params, UUID.class);
        return Map.of("position_id", positionId, "row_version", versions.isEmpty() ? -1L : versions.getFirst(), "configured", !versions.isEmpty(), "item_ids", itemIds);
    }

    @Transactional
    public Map<String,Object> configureQuickList(UUID hotelId, UUID positionId, List<UUID> itemIds, long expectedVersion) {
        TenantPrincipal p = prepareHotel("store-resource.item-manage", hotelId);
        requireQuickListPosition(p, positionId);
        if (itemIds == null || itemIds.size() > 100 || new HashSet<>(itemIds).size() != itemIds.size() || itemIds.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("常用品最多100项，同一物品不能重复选择");
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,75))", new MapSqlParameterSource("key",p.tenantId()+":"+hotelId+":"+positionId));
        var params = base(p).addValue("hotelId",hotelId).addValue("positionId",positionId).addValue("actorId",p.actorId());
        var before = quickList(p,hotelId,positionId);
        if (((Number)before.get("row_version")).longValue() != expectedVersion) throw conflict("常用品配置已被修改，请刷新后核对再保存");
        // Lock selected items in UUID order; concurrent disabling cannot slip through configuration validation.
        for (UUID id : itemIds.stream().sorted().toList()) {
            var items = jdbc.queryForList("""
                    select id from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                      and id=:itemId and status='ACTIVE' and employee_usage_enabled
                      and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN') for share
                    """, params.addValue("itemId",id));
            if (items.isEmpty()) throw new IllegalArgumentException("所选物品已停用或不属于本门店，请刷新后核对");
        }
        jdbc.update("""
                insert into store_usage_quick_list(tenant_id,hotel_org_unit_id,position_id,updated_by)
                values(:tenantId,:hotelId,:positionId,:actorId)
                on conflict(tenant_id,hotel_org_unit_id,position_id) do update
                set row_version=store_usage_quick_list.row_version+1,updated_by=excluded.updated_by,updated_at=now()
                """, params);
        jdbc.update("delete from store_usage_quick_list_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and position_id=:positionId",params);
        for (int order=0; order<itemIds.size(); order++) jdbc.update("""
                insert into store_usage_quick_list_item(tenant_id,hotel_org_unit_id,position_id,item_id,display_order)
                values(:tenantId,:hotelId,:positionId,:itemId,:displayOrder)
                """,params.addValue("itemId",itemIds.get(order)).addValue("displayOrder",order));
        audit.record("STORE_USAGE_QUICK_LIST_CONFIGURED","ORG_UNIT",hotelId,json(Map.of("before",before,"positionId",positionId,"itemIds",itemIds)));
        return quickList(hotelId,positionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> configuration(UUID hotelId) {
        TenantPrincipal p = prepareHotel("store-resource.read", hotelId);
        return configuration(p, hotelId);
    }

    private Map<String, Object> configuration(TenantPrincipal p, UUID hotelId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.hotel_org_unit_id, c.reviewer_assignment_id, c.row_version,
                       e.name as reviewer_name, pos.name as reviewer_position_name
                from store_usage_review_config c
                join employee_position_assignment a on a.tenant_id=c.tenant_id and a.id=c.reviewer_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pos on pos.tenant_id=a.tenant_id and pos.id=a.position_id
                where c.tenant_id=:tenantId and c.hotel_org_unit_id=:hotelId
                """, base(p).addValue("hotelId", hotelId));
        Map<String, Object> result = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.getFirst());
        result.put("hotel_org_unit_id", hotelId);
        result.put("enabled", !rows.isEmpty());
        result.putIfAbsent("row_version", -1L);
        result.put("reviewer_available", !rows.isEmpty() && eligibleReviewer(p, hotelId, (UUID) result.get("reviewer_assignment_id")));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviewers(UUID hotelId) {
        TenantPrincipal p = prepareHotel("store-resource.item-manage", hotelId);
        return jdbc.queryForList("""
                select a.id as assignment_id,e.name as employee_name,pos.name as position_name
                from employee_position_assignment a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pos on pos.tenant_id=a.tenant_id and pos.id=a.position_id
                join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotelId
                where a.tenant_id=:tenantId and a.status='ACTIVE' and e.employment_status='ACTIVE'
                  and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                  and e.account_id is not null
                order by pos.name,e.name,a.id
                """, base(p).addValue("hotelId", hotelId)).stream()
                .filter(row -> eligibleReviewer(p, hotelId, (UUID) row.get("assignment_id"))).toList();
    }

    @Transactional
    public Map<String, Object> configure(UUID hotelId, UUID reviewerId, long expectedVersion) {
        TenantPrincipal p = prepareHotel("store-resource.item-manage", hotelId);
        // Serialize even the first insert, without changing any existing configuration implicitly.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,64))",
                new MapSqlParameterSource("key", p.tenantId() + ":usage-config:" + hotelId));
        if (!eligibleReviewer(p, hotelId, reviewerId)) {
            throw new IllegalArgumentException("审核人必须是该门店有效任职，且具备工作复核、门店库存查看及模块权限");
        }
        List<Map<String, Object>> before = jdbc.queryForList("""
                select reviewer_assignment_id,row_version from store_usage_review_config
                where tenant_id=:tenantId and hotel_org_unit_id=:hotelId for update
                """, base(p).addValue("hotelId", hotelId));
        long version = before.isEmpty() ? -1 : ((Number) before.getFirst().get("row_version")).longValue();
        if (version != expectedVersion) throw conflict("审核配置已变化，请刷新后再保存");
        jdbc.update("""
                insert into store_usage_review_config(tenant_id,hotel_org_unit_id,reviewer_assignment_id,updated_by)
                values(:tenantId,:hotelId,:reviewer,:actorId)
                on conflict(tenant_id,hotel_org_unit_id) do update
                set reviewer_assignment_id=excluded.reviewer_assignment_id,updated_by=excluded.updated_by,
                    updated_at=now(),row_version=store_usage_review_config.row_version+1
                """, base(p).addValue("hotelId", hotelId).addValue("reviewer", reviewerId).addValue("actorId", p.actorId()));
        audit.record("STORE_USAGE_REVIEWER_CONFIGURED", "ORG_UNIT", hotelId,
                json(Map.of("before", before, "reviewerAssignmentId", reviewerId, "pendingRequestsRerouted", true)));
        // Pending work follows current configuration; submission snapshots remain immutable.
        for (UUID recordId : jdbc.queryForList("""
                select work_record_id from store_work_usage where tenant_id=:tenantId
                  and hotel_org_unit_id=:hotelId and status='PENDING'
                """, base(p).addValue("hotelId", hotelId), UUID.class)) notifyReviewer(p, recordId, reviewerId);
        return configuration(p, hotelId);
    }

    /** Called inside the work-record submission transaction, never from a public stock endpoint. */
    @Transactional
    public void registerSubmitted(UUID recordId) {
        TenantPrincipal p = access.principal();
        Map<String, Object> record = jdbc.queryForMap("""
                select r.target_org_unit_id,r.work_expectation_id,r.employee_id,r.business_date,r.payload::text,r.status,i.item_code,i.submission_policy::text
                from work_record r left join work_package_item i on i.tenant_id=r.tenant_id and i.id=r.work_package_item_id
                where r.tenant_id=:tenantId and r.id=:recordId
                """, base(p).addValue("recordId", recordId));
        JsonNode input = read((String) record.get("payload")).path("inventoryQuantities");
        boolean typed = input.path("schemaVersion").isIntegralNumber()
                && input.path("schemaVersion").canConvertToInt() && Set.of(1,2).contains(input.path("schemaVersion").intValue());
        if (ISSUE_ITEMS.contains(Objects.toString(record.get("item_code"), ""))) {
            validateIssue(p, record, input);
            audit.record("WORK_ITEM_ISSUE_REGISTERED", "WORK_RECORD", recordId, input.toString());
            return; // An issue declaration is not actual consumption and never posts cost.
        }
        boolean supported = USAGE_ITEMS.contains(Objects.toString(record.get("item_code"), ""))
                || read((String) record.get("submission_policy")).path("inventoryUsage").asBoolean(false);
        if (!supported) {
            if (typed) throw new IllegalArgumentException("此工作项不是实际耗用登记，不能触发扣库；请勿把领用或日报重复计为消耗");
            return;
        }
        UUID hotelId = hotelFor(p, (UUID) record.get("target_org_unit_id"));
        String itemCode = Objects.toString(record.get("item_code"), "");
        if (SHARED_ITEMS.contains(itemCode)) {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,67))", new MapSqlParameterSource("key",
                    p.tenantId() + ":" + hotelId + ":" + record.get("employee_id") + ":" + record.get("business_date")));
            UUID reference = sharedUsage(p, hotelId, (UUID) record.get("employee_id"),
                    ((java.sql.Date) record.get("business_date")).toLocalDate(), itemCode);
            if (reference != null) {
                if (!reference.toString().equals(input.path("referenceRecordId").asText()) || input.has("lines"))
                    throw conflict("本营业日已填写耗用，请刷新并关联已有登记，不要在日报重复扣库");
                jdbc.update("insert into store_work_usage_report_link(tenant_id,work_record_id,usage_record_id) values(:tenantId,:recordId,:reference)",
                        base(p).addValue("recordId", recordId).addValue("reference", reference));
                audit.record("WORK_USAGE_REFERENCED", "WORK_RECORD", recordId, json(Map.of("usageRecordId", reference)));
                return;
            }
            if (input.has("referenceRecordId")) throw conflict("所关联耗用已失效，请刷新并核对后重新填写");
        }
        UUID reviewerId = currentReviewer(p, hotelId, true);
        if (reviewerId == null) {
            if (typed) throw conflict("门店尚未配置耗用审核人，请联系管理员配置后提交");
            return; // Legacy pilot stores retain their existing reporting flow until enabled.
        }
        if (input.path("schemaVersion").asInt() == 2) input = normalizeDailyUsage(p, hotelId, input);
        if (!typed || !input.path("noUsage").isBoolean() || !input.path("lines").isArray()) {
            throw new IllegalArgumentException("请按门店物品清单填写实际耗用数量，或明确勾选本次无耗用");
        }
        if (record.get("work_expectation_id") == null) throw new IllegalArgumentException("耗用必须从已分配的日清工作提交，不能重复新建独立扣库记录");
        JsonNode lines = input.path("lines");
        boolean none = input.path("noUsage").asBoolean();
        if (lines.size() > 100 || (none ? !lines.isEmpty() : lines.isEmpty())) {
            throw new IllegalArgumentException("每次填写1至100种物品；无耗用时不能同时填写数量");
        }
        if (!eligibleReviewer(p, hotelId, reviewerId)) throw conflict("门店耗用审核人已失效，请联系管理员重新配置");
        // Canonical UUID order is identical to approval/reversal locking, including mixed-case input.
        Map<UUID, JsonNode> ordered = new TreeMap<>(Comparator.comparing(UUID::toString));
        for (JsonNode line : lines) {
            UUID itemId;
            try { itemId = UUID.fromString(line.path("itemId").asText()); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("请选择有效的门店物品"); }
            if (ordered.putIfAbsent(itemId, line) != null) throw new IllegalArgumentException("同一种物品不能重复填报");
        }
        jdbc.update("""
                insert into store_work_usage(tenant_id,work_record_id,hotel_org_unit_id,reviewer_assignment_id_snapshot,no_usage)
                values(:tenantId,:recordId,:hotelId,:reviewer,:none)
                """, base(p).addValue("recordId", recordId).addValue("hotelId", hotelId)
                .addValue("reviewer", reviewerId).addValue("none", none));
        for (Map.Entry<UUID, JsonNode> entry : ordered.entrySet()) {
            UUID itemId = entry.getKey();
            JsonNode line = entry.getValue();
            List<Map<String, Object>> items = jdbc.queryForList("""
                    select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                      and id=:itemId and status='ACTIVE' and employee_usage_enabled and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN') for update
                    """, base(p).addValue("hotelId", hotelId).addValue("itemId", itemId));
            if (items.isEmpty()) throw new IllegalArgumentException("所选物品已停用或不属于本门店，请刷新物品清单");
            Map<String, Object> item = items.getFirst();
            long itemVersion = ((Number) item.get("row_version")).longValue();
            if (!line.path("expectedItemVersion").isIntegralNumber()
                    || !line.path("expectedItemVersion").canConvertToLong()
                    || line.path("expectedItemVersion").asLong(-1) != itemVersion) {
                throw conflict(item.get("name") + " 的物品配置已变化，请刷新后重新核对数量");
            }
            BigDecimal quantity;
            try { quantity = StoreItemUnits.exact(new BigDecimal(line.path("quantity").asText()), 4, 14, "耗用数量"); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("耗用数量必须是有效数字"); }
            if (quantity.signum() <= 0) throw new IllegalArgumentException("已选物品的耗用数量必须大于0，无耗用请移除该行");
            StoreItemUnits units = StoreItemUnits.from(item);
            String unit = units.selectedUnit(line.path("unit").asText());
            // Daily use uses either the base or configured issue unit, not arbitrary purchase packaging.
            if (!unit.equals(units.baseUnit()) && !unit.equals(units.issueUnit())) throw new IllegalArgumentException("请选择库存基础单位或耗用单位");
            jdbc.update("""
                    insert into store_work_usage_line(tenant_id,hotel_org_unit_id,work_record_id,item_id,item_name,
                        category,base_unit,input_unit,input_quantity,conversion_factor,base_quantity,item_version)
                    values(:tenantId,:hotelId,:recordId,:itemId,:name,:category,:base,:unit,:quantity,:factor,:baseQuantity,:version)
                    """, base(p).addValue("hotelId", hotelId).addValue("recordId", recordId).addValue("itemId", itemId)
                    .addValue("name", item.get("name")).addValue("category", item.get("category"))
                    .addValue("base", units.baseUnit()).addValue("unit", unit).addValue("quantity", quantity)
                    .addValue("factor", units.factorFor(unit)).addValue("baseQuantity", units.baseQuantity(quantity, unit))
                    .addValue("version", itemVersion));
        }
        audit.record("WORK_USAGE_PENDING", "WORK_RECORD", recordId, json(Map.of("hotelId", hotelId, "lineCount", lines.size(), "noUsage", none)));
        notifyReviewer(p, recordId, reviewerId);
    }

    private JsonNode normalizeDailyUsage(TenantPrincipal p, UUID hotelId, JsonNode input) {
        JsonNode normalized = DailyUsageInput.normalize(input);
        // Check even zero/blank rows: old units, disabled and foreign items are never silently discarded.
        List<JsonNode> lines = new ArrayList<>();
        input.path("lines").forEach(lines::add);
        lines.sort(Comparator.comparing(line -> UUID.fromString(line.path("itemId").asText()).toString()));
        for (JsonNode line : lines) {
            var items = jdbc.queryForList("""
                    select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                      and id=:itemId and status='ACTIVE' and employee_usage_enabled
                      and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN') for update
                    """,base(p).addValue("hotelId",hotelId).addValue("itemId",UUID.fromString(line.path("itemId").asText())));
            if (items.isEmpty()) throw conflict("清单中有已停用或不属于本门店的物品，请刷新后核对");
            var item = items.getFirst();
            if (!line.path("expectedItemVersion").isIntegralNumber() || !line.path("expectedItemVersion").canConvertToLong()
                    || line.path("expectedItemVersion").asLong(-1) != ((Number)item.get("row_version")).longValue())
                throw conflict(item.get("name")+" 的配置已变化，请刷新后核对数量和单位");
            var units = StoreItemUnits.from(item);
            if (!units.baseUnit().equals(line.path("unit").asText()) && !units.issueUnit().equals(line.path("unit").asText()))
                throw new IllegalArgumentException("请选择物品支持的耗用单位");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public boolean hasUsage(UUID recordId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from store_work_usage where tenant_id=:tenantId and work_record_id=:recordId)",
                base(access.principal()).addValue("recordId", recordId), Boolean.class));
    }

    private void validateIssue(TenantPrincipal p, Map<String, Object> record, JsonNode input) {
        if (input.path("schemaVersion").asInt() != 1 || !input.path("noUsage").isBoolean() || !input.path("lines").isArray())
            throw new IllegalArgumentException("请搜索选择门店物品并填写领用数量，或勾选本次没有领用");
        JsonNode lines = input.path("lines");
        if (lines.size() > 100 || (input.path("noUsage").asBoolean() ? !lines.isEmpty() : lines.isEmpty()))
            throw new IllegalArgumentException("领用登记应包含1至100种物品，无领用时不能填写数量");
        UUID hotelId = hotelFor(p, (UUID) record.get("target_org_unit_id"));
        Set<UUID> seen = new HashSet<>();
        for (JsonNode line : lines) {
            UUID itemId = UUID.fromString(line.path("itemId").asText());
            if (!seen.add(itemId)) throw new IllegalArgumentException("同一种物品不能重复填报");
            List<Map<String, Object>> items = jdbc.queryForList("""
                    select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                      and id=:itemId and status='ACTIVE' and employee_usage_enabled and category in ('CONSUMABLE','CLEANING_SUPPLY','LINEN')
                    """, base(p).addValue("hotelId", hotelId).addValue("itemId", itemId));
            if (items.isEmpty()) throw new IllegalArgumentException("所选物品不可用或不属于本门店");
            Map<String, Object> item = items.getFirst();
            if (!line.path("expectedItemVersion").isIntegralNumber()
                    || line.path("expectedItemVersion").asLong(-1) != ((Number) item.get("row_version")).longValue())
                throw conflict("物品配置已变化，请刷新后重新选择并核对数量");
            StoreItemUnits units = StoreItemUnits.from(item);
            if (!units.issueUnit().equals(line.path("unit").asText())) throw new IllegalArgumentException("领用单位必须使用后台配置的单位");
            BigDecimal quantity;
            try { quantity = StoreItemUnits.exact(new BigDecimal(line.path("quantity").asText()), 4, 14, "领用数量"); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("请输入有效的领用数量"); }
            if (quantity.signum() <= 0) throw new IllegalArgumentException("领用数量必须大于0");
            units.baseQuantity(quantity, units.issueUnit());
        }
    }

    @Transactional
    public void requireReviewer(UUID recordId) {
        TenantPrincipal p = access.principal();
        Map<String, Object> usage = usage(p, recordId);
        UUID hotelId = (UUID) usage.get("hotel_org_unit_id");
        access.requireOrgScope(hotelId);
        UUID reviewer = currentReviewer(p, hotelId, true);
        if (!p.hasPermission("*") && (!Objects.equals(reviewer, p.businessActorAssignmentId())
                || !eligibleReviewer(p, hotelId, reviewer))) throw new AccessDeniedException("仅该门店配置的耗用审核人可以复核，请切换到指定任职");
        Boolean self = jdbc.queryForObject("""
                select exists(select 1 from work_record r join employee e on e.tenant_id=r.tenant_id and e.id=r.employee_id
                    where r.tenant_id=:tenantId and r.id=:recordId and (e.account_id=:actorId or exists(
                        select 1 from employee_position_assignment a where a.tenant_id=r.tenant_id and a.employee_id=r.employee_id and a.id=:assignmentId)))
                """, base(p).addValue("recordId", recordId).addValue("assignmentId", p.businessActorAssignmentId()).addValue("actorId", p.actorId()), Boolean.class);
        if (Boolean.TRUE.equals(self)) throw new AccessDeniedException("不能审核本人提交的耗用记录");
    }

    @Transactional
    public void onReview(UUID recordId, String outcome) {
        TenantPrincipal p = access.principal();
        if (!hasUsage(recordId)) return;
        requireReviewer(recordId);
        Map<String, Object> usage = usage(p, recordId);
        if (!"PENDING".equals(usage.get("status"))) throw conflict("耗用记录已经处理，请刷新");
        if ("APPROVED".equals(outcome)) {
            for (Map<String, Object> line : lines(p, recordId)) {
                if (!"LINEN".equals(line.get("category"))) stock.postWorkUsage(p, (UUID) usage.get("hotel_org_unit_id"), recordId, line, false, null);
            }
        }
        jdbc.update("""
                update store_work_usage set status=:status,reviewed_by=:actorId,reviewed_at=now()
                where tenant_id=:tenantId and work_record_id=:recordId and status='PENDING'
                """, base(p).addValue("recordId", recordId).addValue("status", outcome).addValue("actorId", p.actorId()));
        audit.record("WORK_USAGE_" + outcome, "WORK_RECORD", recordId, json(Map.of("linenDeducted", false)));
        projectLinkedReports(p, recordId);
    }

    @Transactional(readOnly = true)
    public boolean isReviewRetry(UUID recordId, String outcome, long expectedVersion) {
        TenantPrincipal p = access.principal();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store_work_usage u join work_record r on r.tenant_id=u.tenant_id and r.id=u.work_record_id
                    where u.tenant_id=:tenantId and r.id=:recordId and u.status=:outcome and r.status=:outcome
                    and u.reviewed_by=:actorId and r.row_version=:version)
                """, base(p).addValue("recordId", recordId).addValue("actorId", p.actorId())
                .addValue("outcome", outcome).addValue("version", expectedVersion + 1), Boolean.class));
    }

    /** Caller already authorized access to the work record. No financial values are disclosed here. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID recordId) {
        TenantPrincipal p = access.principal();
        UUID originalRecordId = recordId;
        if (!hasUsage(recordId)) {
            List<UUID> links = jdbc.queryForList("select usage_record_id from store_work_usage_report_link where tenant_id=:tenantId and work_record_id=:recordId",
                    base(p).addValue("recordId", recordId), UUID.class);
            if (links.isEmpty()) return Map.of();
            recordId = links.getFirst();
        }
        Map<String, Object> result = new LinkedHashMap<>(usage(p, recordId));
        result.put("lines", lines(p, recordId));
        List<Map<String,Object>> history = correctionHistory(p, recordId);
        result.put("correction_version", history.isEmpty() ? 0 : history.getFirst().get("revision_no"));
        result.put("corrections", history);
        if (!history.isEmpty()) result.put("no_usage", history.getFirst().get("no_usage"));
        result.put("referenced", !recordId.equals(originalRecordId));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID hotelId, String status) {
        TenantPrincipal p = prepareHotel("store-resource.read", hotelId);
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "REVERSED", "ALL").contains(status)) throw new IllegalArgumentException("无效审核状态");
        UUID reviewer = currentReviewer(p, hotelId, false);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.*,r.business_date,r.row_version as record_version,r.work_expectation_id,
                       e.name as employee_name,coalesce(i.name,'工作记录') as work_title,
                       r.review_reason,r.submitted_by_account_id,r.employee_id
                from store_work_usage u join work_record r on r.tenant_id=u.tenant_id and r.id=u.work_record_id
                join employee e on e.tenant_id=r.tenant_id and e.id=r.employee_id
                left join work_package_item i on i.tenant_id=r.tenant_id and i.id=r.work_package_item_id
                where u.tenant_id=:tenantId and u.hotel_org_unit_id=:hotelId and (:status='ALL' or u.status=:status)
                order by u.created_at desc,u.work_record_id limit 100
                """, base(p).addValue("hotelId", hotelId).addValue("status", status));
        if (rows.isEmpty()) return rows;
        List<UUID> ids = rows.stream().map(row -> (UUID) row.get("work_record_id")).toList();
        Map<UUID, List<Map<String, Object>>> byRecord = new HashMap<>();
        for (Map<String, Object> line : jdbc.queryForList("""
                select * from store_work_usage_current_line where tenant_id=:tenantId and work_record_id in (:ids)
                order by work_record_id,item_id
                """, base(p).addValue("ids", ids))) {
            byRecord.computeIfAbsent((UUID) line.get("work_record_id"), ignored -> new ArrayList<>()).add(line);
        }
        boolean allowed = p.hasPermission("*") || (p.hasPermission("work-record.review")
                && Objects.equals(reviewer, p.businessActorAssignmentId()) && eligibleReviewer(p, hotelId, reviewer));
        Map<UUID,List<Map<String,Object>>> historyByRecord=new HashMap<>();
        for (Map<String,Object> revision:jdbc.queryForList("""
                select c.id,c.work_record_id,c.revision_no,c.no_usage,c.reason,c.created_at,a.display_name as operator_name
                from store_work_usage_correction c join user_account a on a.tenant_id=c.tenant_id and a.id=c.actor_account_id
                where c.tenant_id=:tenantId and c.work_record_id in (:ids) order by c.work_record_id,c.revision_no desc
                """,base(p).addValue("ids",ids))) historyByRecord.computeIfAbsent((UUID)revision.get("work_record_id"),ignored->new ArrayList<>()).add(revision);
        for (Map<String, Object> row : rows) {
            row.put("lines", byRecord.getOrDefault(row.get("work_record_id"), List.of()));
            row.put("can_review", allowed && "PENDING".equals(row.get("status")) && !p.actorId().equals(row.get("submitted_by_account_id")));
            row.put("can_correct", allowed && "APPROVED".equals(row.get("status")) && !p.actorId().equals(row.get("submitted_by_account_id")));
            List<Map<String,Object>> history = historyByRecord.getOrDefault(row.get("work_record_id"),List.of());
            row.put("correction_version", history.isEmpty() ? 0 : history.getFirst().get("revision_no"));
            row.put("corrections", history);
            if (!history.isEmpty()) row.put("no_usage", history.getFirst().get("no_usage"));
            row.remove("submitted_by_account_id"); row.remove("employee_id");
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> reverse(UUID hotelId, UUID recordId, String reason) {
        TenantPrincipal p = prepareHotel("store-resource.movement-create", hotelId);
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("冲销必须填写原因（最多1000字）");
        // Same first lock as work review. Opposite entries preserve original quantity AND original cost.
        List<Map<String, Object>> records = jdbc.queryForList("select id from work_record where tenant_id=:tenantId and id=:recordId for update",
                base(p).addValue("recordId", recordId));
        if (records.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作记录不存在");
        Map<String, Object> usage = usage(p, recordId);
        if (!hotelId.equals(usage.get("hotel_org_unit_id"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "门店耗用记录不存在");
        if ("REVERSED".equals(usage.get("status"))) {
            if (!reason.trim().equals(usage.get("reversal_reason"))) throw conflict("该记录已冲销，不能修改原冲销原因");
            return detail(recordId);
        }
        if (!"APPROVED".equals(usage.get("status"))) throw conflict("只能冲销已审核通过的耗用记录");
        reverseLines(p, hotelId, lines(p, recordId));
        jdbc.update("""
                update store_work_usage set status='REVERSED',reversed_by=:actorId,reversed_at=now(),reversal_reason=:reason
                where tenant_id=:tenantId and work_record_id=:recordId and status='APPROVED'
                """, base(p).addValue("recordId", recordId).addValue("actorId", p.actorId()).addValue("reason", reason.trim()));
        audit.record("WORK_USAGE_REVERSED", "WORK_RECORD", recordId, json(Map.of("reason", reason.trim())));
        projection.projectWorkRecord(recordId);
        projectLinkedReports(p, recordId);
        return detail(recordId);
    }

    private List<Map<String, Object>> lines(TenantPrincipal p, UUID recordId) {
        return jdbc.queryForList("select * from store_work_usage_current_line where tenant_id=:tenantId and work_record_id=:recordId order by item_id",
                base(p).addValue("recordId", recordId));
    }

    private UUID sharedUsage(TenantPrincipal p, UUID hotelId, UUID employeeId, LocalDate date, String itemCode) {
        List<UUID> rows = jdbc.queryForList("""
                select u.work_record_id from store_work_usage u
                join work_record r on r.tenant_id=u.tenant_id and r.id=u.work_record_id
                join work_package_item i on i.tenant_id=r.tenant_id and i.id=r.work_package_item_id
                where u.tenant_id=:tenantId and u.hotel_org_unit_id=:hotelId and r.employee_id=:employeeId
                  and r.business_date=:date and u.status in ('PENDING','APPROVED')
                  and i.item_code in ('HKA_USAGE_RETURN','HKA_DAILY_REPORT') and i.item_code<>:code
                order by u.created_at desc limit 2
                """, base(p).addValue("hotelId", hotelId).addValue("employeeId", employeeId).addValue("date", date).addValue("code", itemCode), UUID.class);
        if (rows.size()>1) throw conflict("当天存在多条耗用登记，请由主管核对，不能自动合并扣库");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String,Object>> correctionHistory(TenantPrincipal p, UUID recordId) {
        return jdbc.queryForList("""
                select c.id,c.revision_no,c.no_usage,c.reason,c.created_at,a.display_name as operator_name
                from store_work_usage_correction c join user_account a on a.tenant_id=c.tenant_id and a.id=c.actor_account_id
                where c.tenant_id=:tenantId and c.work_record_id=:recordId order by c.revision_no desc
                """, base(p).addValue("recordId", recordId));
    }

    private void reverseLines(TenantPrincipal p, UUID hotelId, List<Map<String,Object>> oldLines) {
        for (Map<String,Object> line : oldLines) {
            if ("LINEN".equals(line.get("category"))) continue;
            BigDecimal original = jdbc.queryForObject("""
                    select -value_delta from store_inventory_movement where tenant_id=:tenantId and source_type=:sourceType
                    and source_id=:sourceId and item_id=:itemId and movement_type='CONSUMPTION'
                    """, base(p).addValue("sourceType",line.get("posting_source_type")).addValue("sourceId",line.get("posting_source_id"))
                    .addValue("itemId",line.get("item_id")),BigDecimal.class);
            stock.postUsageRevision(p,hotelId,(UUID)line.get("posting_source_id"),(String)line.get("posting_source_type"),line,true,original);
        }
    }

    /** An authorized supervisor replaces quantities atomically, preserving every version and exact reversal value. */
    @Transactional
    public Map<String,Object> correct(UUID hotelId, UUID recordId, JsonNode input, int expectedVersion, String reason) {
        TenantPrincipal p = prepareHotel("work-record.review", hotelId);
        access.requirePermission("store-resource.read");
        if (reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("更正必须填写原因（最多1000字）");
        if (expectedVersion<0) throw new IllegalArgumentException("更正版本无效");
        if (jdbc.queryForList("select id from work_record where tenant_id=:tenantId and id=:recordId for update",
                base(p).addValue("recordId",recordId)).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工作记录不存在");
        Map<String,Object> usage = usage(p,recordId);
        if (!hotelId.equals(usage.get("hotel_org_unit_id"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"门店耗用不存在");
        requireReviewer(recordId);
        if (!"APPROVED".equals(usage.get("status"))) throw conflict("只能更正已审核的耗用；待审核请退回员工修改");
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((json(input)+"|"+reason.trim()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        List<Map<String,Object>> revisions=jdbc.queryForList("select * from store_work_usage_correction where tenant_id=:tenantId and work_record_id=:recordId order by revision_no desc limit 1",
                base(p).addValue("recordId",recordId));
        int current=revisions.isEmpty()?0:((Number)revisions.getFirst().get("revision_no")).intValue();
        if (current!=expectedVersion) {
            if (current==expectedVersion+1 && hash.equals(revisions.getFirst().get("request_hash")) && p.actorId().equals(revisions.getFirst().get("actor_account_id"))) return detail(recordId);
            throw conflict("耗用数量已被更正，请刷新后重新核对");
        }
        if (input==null || !input.path("schemaVersion").isIntegralNumber() || !input.path("schemaVersion").canConvertToInt()
                || input.path("schemaVersion").asInt()!=1 || !input.path("noUsage").isBoolean() || !input.path("lines").isArray())
            throw new IllegalArgumentException("请按物品清单填写更正后的数量");
        boolean none=input.path("noUsage").asBoolean();
        if (input.path("lines").size()>100 || (none?!input.path("lines").isEmpty():input.path("lines").isEmpty())) throw new IllegalArgumentException("请选择1至100种物品，或勾选无耗用");
        Map<UUID,JsonNode> inputs=new TreeMap<>(Comparator.comparing(UUID::toString));
        for (JsonNode line:input.path("lines")) {
            UUID id;
            try { id=UUID.fromString(line.path("itemId").asText()); } catch (IllegalArgumentException error) { throw new IllegalArgumentException("请选择有效物品"); }
            if (inputs.putIfAbsent(id,line)!=null) throw new IllegalArgumentException("物品不能重复");
        }
        List<Map<String,Object>> before=lines(p,recordId);
        Set<UUID> ids=new TreeSet<>(Comparator.comparing(UUID::toString)); ids.addAll(inputs.keySet());
        before.forEach(line->ids.add((UUID)line.get("item_id")));
        // Lock the union, not first the old set then the new set, to prevent cross-correction deadlocks.
        List<Map<String,Object>> items=ids.isEmpty()?List.of():jdbc.queryForList("select * from store_resource_item where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id in (:ids) order by id for update",
                base(p).addValue("hotelId",hotelId).addValue("ids",ids));
        UUID correctionId=UUID.randomUUID();
        jdbc.update("""
                insert into store_work_usage_correction(id,tenant_id,work_record_id,revision_no,no_usage,reason,request_hash,actor_account_id)
                values(:id,:tenantId,:recordId,:version,:none,:reason,:hash,:actor)
                """,base(p).addValue("id",correctionId).addValue("recordId",recordId).addValue("version",current+1)
                .addValue("none",none).addValue("reason",reason.trim()).addValue("hash",hash).addValue("actor",p.actorId()));
        for (var entry:inputs.entrySet()) {
            Map<String,Object> item=items.stream().filter(row->entry.getKey().equals(row.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("物品不属于本门店"));
            JsonNode line=entry.getValue();
            if (!"ACTIVE".equals(item.get("status")) || !Set.of("LINEN","CONSUMABLE","CLEANING_SUPPLY").contains(item.get("category"))) throw new IllegalArgumentException("请选择启用的耗品、清洁用品或布草");
            if (!line.path("expectedItemVersion").isIntegralNumber() || !line.path("expectedItemVersion").canConvertToLong()
                    || line.path("expectedItemVersion").longValue()!=((Number)item.get("row_version")).longValue()) throw conflict("物品配置已变化，请刷新后核对");
            BigDecimal quantity;
            try { quantity=StoreItemUnits.exact(new BigDecimal(line.path("quantity").asText()),4,14,"使用数量"); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("使用数量必须是数字"); }
            if (quantity.signum()<=0) throw new IllegalArgumentException("使用数量必须大于0");
            StoreItemUnits units=StoreItemUnits.from(item); String unit=units.selectedUnit(line.path("unit").asText());
            if (!unit.equals(units.baseUnit()) && !unit.equals(units.issueUnit())) throw new IllegalArgumentException("请选择库存或使用单位");
            jdbc.update("""
                    insert into store_work_usage_correction_line(tenant_id,correction_id,hotel_org_unit_id,work_record_id,item_id,item_name,
                       category,base_unit,input_unit,input_quantity,conversion_factor,base_quantity,item_version)
                    values(:tenantId,:id,:hotelId,:recordId,:itemId,:name,:category,:base,:unit,:qty,:factor,:baseQty,:version)
                    """,base(p).addValue("id",correctionId).addValue("hotelId",hotelId).addValue("recordId",recordId).addValue("itemId",entry.getKey())
                    .addValue("name",item.get("name")).addValue("category",item.get("category")).addValue("base",units.baseUnit()).addValue("unit",unit)
                    .addValue("qty",quantity).addValue("factor",units.factorFor(unit)).addValue("baseQty",units.baseQuantity(quantity,unit)).addValue("version",item.get("row_version")));
        }
        reverseLines(p,hotelId,before);
        for (Map<String,Object> line:lines(p,recordId)) if (!"LINEN".equals(line.get("category")))
            stock.postUsageRevision(p,hotelId,correctionId,"WORK_USAGE_REVISION",line,false,null);
        audit.record("WORK_USAGE_CORRECTED","WORK_RECORD",recordId,json(Map.of("reason",reason.trim(),"revision",current+1,"before",before,"after",lines(p,recordId))));
        projection.projectWorkRecord(recordId);
        projectLinkedReports(p, recordId);
        return detail(recordId);
    }
    private void projectLinkedReports(TenantPrincipal p, UUID recordId) {
        for (UUID linked:jdbc.queryForList("select work_record_id from store_work_usage_report_link where tenant_id=:tenantId and usage_record_id=:recordId",base(p).addValue("recordId",recordId),UUID.class)) projection.projectWorkRecord(linked);
    }
    private Map<String, Object> usage(TenantPrincipal p, UUID recordId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from store_work_usage where tenant_id=:tenantId and work_record_id=:recordId",
                base(p).addValue("recordId", recordId));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "耗用记录不存在");
        return rows.getFirst();
    }
    private UUID currentReviewer(TenantPrincipal p, UUID hotelId, boolean lock) {
        return jdbc.query("select reviewer_assignment_id from store_usage_review_config where tenant_id=:tenantId and hotel_org_unit_id=:hotelId"
                + (lock ? " for share" : ""), base(p).addValue("hotelId", hotelId), rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
    }
    private boolean eligibleReviewer(TenantPrincipal p, UUID hotelId, UUID assignmentId) {
        if (assignmentId == null) return false;
        List<UUID> accounts = jdbc.queryForList("""
                select e.account_id from employee_position_assignment a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join user_account account on account.tenant_id=e.tenant_id and account.id=e.account_id and account.status='ACTIVE'
                join org_unit_closure c on c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and c.ancestor_id=:hotelId
                where a.tenant_id=:tenantId and a.id=:assignmentId and a.status='ACTIVE' and e.employment_status='ACTIVE'
                  and e.account_id is not null and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                """, base(p).addValue("hotelId", hotelId).addValue("assignmentId", assignmentId), UUID.class);
        if (accounts.isEmpty()) return false;
        return identities.inspectAssignment(p.tenantId(), accounts.getFirst(), p.correlationId(), assignmentId)
                .map(target -> (target.hasTenantScope() || target.orgScopes().contains(hotelId)) && (target.hasPermission("*")
                    || (target.hasPermission("work-record.review") && target.hasPermission("store-resource.read")
                        && target.hasPermission("ui.module.store-resources")))).orElse(false);
    }
    private UUID hotelFor(TenantPrincipal p, UUID orgId) {
        List<UUID> ids = jdbc.queryForList("""
                select o.id from org_unit_closure c join org_unit o on o.tenant_id=c.tenant_id and o.id=c.ancestor_id
                where c.tenant_id=:tenantId and c.descendant_id=:orgId and o.unit_type='HOTEL' and o.status='ACTIVE'
                order by c.depth limit 1
                """, base(p).addValue("orgId", orgId), UUID.class);
        if (ids.isEmpty()) throw new IllegalArgumentException("此工作未关联有效门店，不能登记门店耗用");
        return ids.getFirst();
    }
    private void notifyReviewer(TenantPrincipal p, UUID recordId, UUID reviewerId) {
        jdbc.update("""
                insert into notification(tenant_id,recipient_account_id,recipient_assignment_id,notification_type,
                    title,content,source_type,source_id,idempotency_key)
                select :tenantId,e.account_id,a.id,'WORK_RECORD_REVIEW_PENDING','门店耗用待审核',
                    '员工已提交耗用登记，请在门店库存的耗用审核中核对；通过后才会扣库。','WORK_RECORD',:recordId,:key
                from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                where a.tenant_id=:tenantId and a.id=:reviewer and e.account_id is not null
                on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing
                """, base(p).addValue("recordId", recordId).addValue("reviewer", reviewerId)
                .addValue("key", "usage-review:" + recordId + ":" + reviewerId));
    }
    private TenantPrincipal prepare(String permission) {
        access.requirePermission(permission);
        TenantPrincipal p = access.principal(); databaseContext.apply(p.tenantId()); return p;
    }
    private TenantPrincipal prepareHotel(String permission, UUID hotelId) {
        TenantPrincipal p = prepare(permission); access.requireOrgScope(hotelId);
        if (!hotelId.equals(hotelFor(p, hotelId))) throw new IllegalArgumentException("请选择有效门店");
        return p;
    }
    private MapSqlParameterSource base(TenantPrincipal p) { return new MapSqlParameterSource("tenantId", p.tenantId()); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private JsonNode read(String value) {
        try { return mapper.readTree(value == null ? "{}" : value); }
        catch (Exception ex) { throw new IllegalArgumentException("耗用登记数据格式错误"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("无法保存耗用审计记录"); }
    }
}
