package cn.sifangguan.hotelaios.storeassets;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.sifangguan.hotelaios.storeassets.StoreResourceModels.UsageScopeInput;

/** Selection scope enforced at employee entry/submission; never grants access or partitions the stock ledger. */
@Component
public class StoreItemUsageScopes {
    private final NamedParameterJdbcTemplate jdbc;

    public StoreItemUsageScopes(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Option(UUID id, String code, String name) { }
    public record Options(List<Option> departments, List<Option> positions, PrefixConfig prefixConfig) {
        public Options(List<Option> departments, List<Option> positions) { this(departments,positions,null); }
    }

    public record PrefixRule(String prefix, String defaultName, UUID departmentId) { }
    public record PrefixConfig(long rowVersion, List<PrefixRule> rules) { }

    public PrefixConfig prefixConfig(UUID tenantId, UUID hotelId, Options options) {
        var rows = jdbc.queryForList("select * from store_department_prefix_config where tenant_id=:tenantId and hotel_org_unit_id=:hotelId",params(tenantId,hotelId));
        var stored = rows.isEmpty() ? null : rows.getFirst();
        var rules = new ArrayList<PrefixRule>();
        for (var entry : List.of(Map.entry("QT","前厅部"),Map.entry("KF","客房部"),Map.entry("WX","维修部"))) {
            var matches = options.departments().stream().filter(d -> d.name().equals(entry.getValue())).toList();
            UUID id = stored == null ? (matches.size()==1 ? matches.getFirst().id() : null)
                    : (UUID)stored.get(entry.getKey().toLowerCase(Locale.ROOT)+"_department_id");
            // Stale mappings never infer a disabled or foreign department.
            if (id != null) { UUID selected=id; if (options.departments().stream().noneMatch(d -> d.id().equals(selected))) id=null; }
            rules.add(new PrefixRule(entry.getKey(),entry.getValue(),id));
        }
        return new PrefixConfig(stored==null ? -1 : ((Number)stored.get("row_version")).longValue(),rules);
    }

    public static UUID inferredDepartment(String code, PrefixConfig config) {
        String normalized = Objects.toString(code, "").trim().toUpperCase(Locale.ROOT);
        return config.rules().stream().filter(rule -> normalized.startsWith(rule.prefix()+"-"))
                .map(PrefixRule::departmentId).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public UsageScopeInput infer(UUID tenantId, UUID hotelId, String code, Options options) {
        UUID department = inferredDepartment(code,prefixConfig(tenantId,hotelId,options));
        return department == null ? new UsageScopeInput("ALL_DEPARTMENTS",List.of(),List.of())
                : new UsageScopeInput("DEPARTMENTS",List.of(department),List.of());
    }

    // Callers have already applied tenant context and checked the hotel/action permission.
    public Options options(UUID tenantId, UUID hotelId, boolean lock) {
        var params = params(tenantId, hotelId);
        List<Option> departments = jdbc.query("""
                select d.id, d.code, d.name from org_unit d
                where d.tenant_id=:tenantId and d.unit_type='DEPARTMENT' and d.status='ACTIVE'
                  and exists (select 1 from org_unit_closure c where c.tenant_id=d.tenant_id
                    and c.ancestor_id=:hotelId and c.descendant_id=d.id)
                  and not exists (select 1 from org_unit_closure c join org_unit ancestor
                    on ancestor.tenant_id=c.tenant_id and ancestor.id=c.ancestor_id
                    where c.tenant_id=d.tenant_id and c.descendant_id=d.id and ancestor.status<>'ACTIVE')
                order by d.id
                """ + (lock ? " for share of d" : ""), params,
                (rs, n) -> new Option(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")));
        // Positions are tenant-wide definitions, not inferred from currently employed staff.
        List<Option> positions = jdbc.query("""
                select p.id,p.code,p.name from position_definition p
                where p.tenant_id=:tenantId and p.status='ACTIVE'
                  and p.deleted_at is null and p.permanently_deleted_at is null
                order by p.id
                """ + (lock ? " for share of p" : ""), params,
                (rs, n) -> new Option(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")));
        var choices = new Options(departments, positions);
        return new Options(departments,positions,prefixConfig(tenantId,hotelId,choices));
    }

    public UsageScopeInput validate(UsageScopeInput input, Options options) {
        if (input == null) return null; // old client / old template: preserve legacy behavior
        if (!Set.of("ALL_DEPARTMENTS", "DEPARTMENTS").contains(Objects.toString(input.mode(), "")))
            throw invalid("适用部门", "请选择全店通用或指定部门");
        List<UUID> departments = ids(input.departmentIds(), "适用部门");
        List<UUID> positions = ids(input.positionIds(), "适用岗位");
        if (input.mode().equals("DEPARTMENTS") && departments.isEmpty())
            throw invalid("适用部门", "指定部门时至少选择一个有效部门");
        if (input.mode().equals("ALL_DEPARTMENTS") && !departments.isEmpty())
            throw invalid("适用部门", "全店通用不能同时指定部门");
        requireAvailable(departments, options.departments(), "适用部门", "部门不存在、已停用或不属于当前门店");
        requireAvailable(positions, options.positions(), "适用岗位", "岗位不存在或已停用，请选择有效的集团岗位标准");
        return new UsageScopeInput(input.mode(), departments, positions);
    }

    public UsageScopeInput parse(String departmentNames, String positionNames, Options options) {
        if ("不限岗位".equals(positionNames.trim())) positionNames = "";
        if (departmentNames.isBlank()) throw invalid("适用部门", "请填写全店通用或本门店有效部门名称/编码");
        List<String> departments = split(departmentNames, "适用部门");
        boolean all = departments.contains("全店通用");
        if (all && departments.size() != 1) throw invalid("适用部门", "全店通用不能与具体部门混填");
        return validate(new UsageScopeInput(all ? "ALL_DEPARTMENTS" : "DEPARTMENTS",
                all ? List.of() : resolve(departments, options.departments(), "适用部门"),
                resolve(split(positionNames, "适用岗位"), options.positions(), "适用岗位")), options);
    }

    private List<String> split(String text, String field) {
        if (text.isBlank()) return List.of();
        List<String> values = Arrays.stream(text.split("[;；]", -1)).map(String::trim).toList();
        if (values.size() > 200 || values.stream().anyMatch(String::isBlank))
            throw invalid(field, "多个名称请用分号分隔，不要留下空项，最多200项");
        return values;
    }

    private List<UUID> resolve(List<String> values, List<Option> options, String field) {
        List<UUID> result = new ArrayList<>();
        for (String value : values) {
            var matched = options.stream().filter(o -> o.name().equals(value) || o.code().equalsIgnoreCase(value)).toList();
            if (matched.isEmpty()) throw invalid(field, "未找到有效" + field.substring(2) + "：" + value + "，请核对部门岗位参考表");
            if (matched.size() > 1) throw invalid(field, "名称存在歧义：" + value + "，请改用唯一编码");
            result.add(matched.getFirst().id());
        }
        return ids(result, field);
    }

    private List<UUID> ids(List<UUID> input, String field) {
        List<UUID> ids = input == null ? List.of() : input;
        if (ids.size() > 200 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size())
            throw invalid(field, "不能重复或包含空项，最多选择200项");
        return ids.stream().sorted().toList();
    }

    private void requireAvailable(List<UUID> ids, List<Option> options, String field, String message) {
        Set<UUID> available = new HashSet<>();
        options.forEach(option -> available.add(option.id()));
        if (!available.containsAll(ids)) throw invalid(field, message);
    }

    /** Parent item is locked by the caller; omitted update fields never clear prior configuration. */
    public void save(UUID tenantId, UUID hotelId, UUID itemId, UsageScopeInput input) {
        if (input == null) return;
        var params = params(tenantId, hotelId).addValue("itemId", itemId).addValue("mode", input.mode());
        jdbc.update("update store_resource_item set usage_scope_mode=:mode where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:itemId", params);
        jdbc.update("delete from store_item_usage_department where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId", params);
        jdbc.update("delete from store_item_usage_position where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and item_id=:itemId", params);
        for (UUID id : input.departmentIds()) jdbc.update("insert into store_item_usage_department(tenant_id,hotel_org_unit_id,item_id,department_id) values(:tenantId,:hotelId,:itemId,:id)", params.addValue("id", id));
        for (UUID id : input.positionIds()) jdbc.update("insert into store_item_usage_position(tenant_id,hotel_org_unit_id,item_id,position_id) values(:tenantId,:hotelId,:itemId,:id)", params.addValue("id", id));
    }

    public void saveAsset(UUID tenantId, UUID hotelId, UUID assetId, UsageScopeInput input) {
        if (input == null) return;
        var p = params(tenantId, hotelId).addValue("assetId",assetId).addValue("mode",input.mode());
        jdbc.update("update store_fixed_asset set usage_scope_mode=:mode where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:assetId",p);
        jdbc.update("delete from store_asset_usage_department where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and asset_id=:assetId",p);
        jdbc.update("delete from store_asset_usage_position where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and asset_id=:assetId",p);
        for (UUID id:input.departmentIds()) jdbc.update("insert into store_asset_usage_department values(:tenantId,:hotelId,:assetId,:id)",p.addValue("id",id));
        for (UUID id:input.positionIds()) jdbc.update("insert into store_asset_usage_position values(:tenantId,:hotelId,:assetId,:id)",p.addValue("id",id));
    }

    public List<Map<String,Object>> decorateAssets(UUID tenantId,UUID hotelId,List<Map<String,Object>> assets) {
        if(assets.isEmpty()) return assets;
        var p=params(tenantId,hotelId).addValue("ids",assets.stream().map(a->a.get("id")).toList());
        var deps=group(jdbc.queryForList("""
            select s.asset_id as item_id,d.id,d.code,d.name from store_asset_usage_department s
            join org_unit d on d.tenant_id=s.tenant_id and d.id=s.department_id
            where s.tenant_id=:tenantId and s.hotel_org_unit_id=:hotelId and s.asset_id in (:ids) order by d.name,d.id
            """,p));
        var positions=group(jdbc.queryForList("""
            select s.asset_id as item_id,d.id,d.code,d.name from store_asset_usage_position s
            join position_definition d on d.tenant_id=s.tenant_id and d.id=s.position_id
            where s.tenant_id=:tenantId and s.hotel_org_unit_id=:hotelId and s.asset_id in (:ids) order by d.name,d.id
            """,p));
        for(var asset:assets) {
            var d=deps.getOrDefault(asset.get("id"),List.of());var v=positions.getOrDefault(asset.get("id"),List.of());
            asset.put("usage_scope",Map.of("mode",asset.getOrDefault("usage_scope_mode","LEGACY"),
                "departmentIds",d.stream().map(Option::id).toList(),"positionIds",v.stream().map(Option::id).toList(),
                "departments",d,"positions",v));
        }
        return assets;
    }

    public List<Map<String, Object>> decorate(UUID tenantId, UUID hotelId, List<Map<String, Object>> items) {
        if (items.isEmpty()) return items;
        var params = params(tenantId, hotelId).addValue("itemIds", items.stream().map(i -> i.get("id")).toList());
        var departments = jdbc.queryForList("""
                select s.item_id, d.id,d.code,d.name from store_item_usage_department s
                join org_unit d on d.tenant_id=s.tenant_id and d.id=s.department_id
                where s.tenant_id=:tenantId and s.hotel_org_unit_id=:hotelId and s.item_id in (:itemIds) order by d.name,d.id
                """, params);
        var positions = jdbc.queryForList("""
                select s.item_id,p.id,p.code,p.name from store_item_usage_position s
                join position_definition p on p.tenant_id=s.tenant_id and p.id=s.position_id
                where s.tenant_id=:tenantId and s.hotel_org_unit_id=:hotelId and s.item_id in (:itemIds) order by p.name,p.id
                """, params);
        Map<Object, List<Option>> departmentMap = group(departments), positionMap = group(positions);
        for (var item : items) {
            List<Option> deps = departmentMap.getOrDefault(item.get("id"), List.of());
            List<Option> pos = positionMap.getOrDefault(item.get("id"), List.of());
            item.put("usage_scope", Map.of("mode", item.getOrDefault("usage_scope_mode", "LEGACY"),
                    "departmentIds", deps.stream().map(Option::id).toList(), "positionIds", pos.stream().map(Option::id).toList(),
                    "departments", deps, "positions", pos));
        }
        return items;
    }

    private Map<Object, List<Option>> group(List<Map<String, Object>> rows) {
        Map<Object, List<Option>> result = new HashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.get("item_id"), key -> new ArrayList<>())
                .add(new Option((UUID) row.get("id"), (String) row.get("code"), (String) row.get("name"))));
        return result;
    }

    private MapSqlParameterSource params(UUID tenantId, UUID hotelId) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("hotelId", hotelId);
    }

    private ScopeValidationException invalid(String field, String message) { return new ScopeValidationException(field, message); }
    public static class ScopeValidationException extends IllegalArgumentException {
        private final String field;
        ScopeValidationException(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }
}
