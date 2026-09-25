package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.idempotency.CommandIdempotencyService;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Explicit preview and confirmed metadata-only backfill; never touches stock, costs or custody. */
@Service
public class StoreDepartmentPrefixService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TenantDatabaseContext context;
    private final StoreItemUsageScopes scopes;
    private final AuditWriter audit;
    private final CommandIdempotencyService commands;
    private final ObjectMapper mapper;
    public StoreDepartmentPrefixService(NamedParameterJdbcTemplate jdbc, AccessPolicy access, TenantDatabaseContext context,
            StoreItemUsageScopes scopes, AuditWriter audit, CommandIdempotencyService commands, ObjectMapper mapper) {
        this.jdbc=jdbc;this.access=access;this.context=context;this.scopes=scopes;this.audit=audit;this.commands=commands;this.mapper=mapper;
    }
    public record RulesInput(long expectedVersion, UUID qtDepartmentId, UUID kfDepartmentId, UUID wxDepartmentId) { }
    public record Selection(@NotBlank String type, @NotNull UUID id, long expectedVersion, @NotNull UUID departmentId) { }
    public record ApplyInput(long configVersion, @NotEmpty @Size(max=200) List<@NotNull @Valid Selection> selections) { }

    @Transactional(readOnly=true)
    public Map<String,Object> preview(UUID hotelId) {
        var p=prepare(hotelId);
        var options=scopes.options(p.tenantId(),hotelId,false);
        var config=scopes.prefixConfig(p.tenantId(),hotelId,options);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String type:List.of("ITEM","ASSET")) {
            boolean asset=type.equals("ASSET");
            var records=jdbc.queryForList("select id,"+(asset ? "asset_code" : "sku")+" as code,name,row_version,usage_scope_mode from "+
                    (asset ? "store_fixed_asset" : "store_resource_item")+" where tenant_id=:tenantId and hotel_org_unit_id=:hotelId order by id",params(p,hotelId));
            if(asset) scopes.decorateAssets(p.tenantId(),hotelId,records); else scopes.decorate(p.tenantId(),hotelId,records);
            for(var row:records) {
                row.put("type",type);
                UUID inferred=StoreItemUsageScopes.inferredDepartment((String)row.get("code"),config);
                row.put("inferred_department_id",inferred);
                var scope=(Map<?,?>)row.get("usage_scope");
                row.put("action",!"LEGACY".equals(row.get("usage_scope_mode")) || !((List<?>)scope.get("departmentIds")).isEmpty()
                        ? "PRESERVE" : inferred==null ? "MANUAL" : "FILL");
                rows.add(row);
            }
        }
        return Map.of("config",config,"departments",options.departments(),"rows",rows);
    }

    @Transactional
    public Map<String,Object> saveRules(UUID hotelId, RulesInput input) {
        var p=prepare(hotelId); lock(p,hotelId);
        var options=scopes.options(p.tenantId(),hotelId,true);
        var before=scopes.prefixConfig(p.tenantId(),hotelId,options);
        if(before.rowVersion()!=input.expectedVersion()) throw conflict();
        var ids=Arrays.asList(input.qtDepartmentId(),input.kfDepartmentId(),input.wxDepartmentId());
        if(ids.stream().filter(Objects::nonNull).anyMatch(id->options.departments().stream().noneMatch(d->d.id().equals(id))))
            throw new IllegalArgumentException("编号规则只能选择当前门店的有效部门");
        jdbc.update("""
                insert into store_department_prefix_config(tenant_id,hotel_org_unit_id,qt_department_id,kf_department_id,wx_department_id,updated_by)
                values(:tenantId,:hotelId,:qt,:kf,:wx,:actorId)
                on conflict(tenant_id,hotel_org_unit_id) do update set qt_department_id=excluded.qt_department_id,
                  kf_department_id=excluded.kf_department_id,wx_department_id=excluded.wx_department_id,
                  row_version=store_department_prefix_config.row_version+1,updated_by=excluded.updated_by,updated_at=now()
                """,params(p,hotelId).addValue("qt",input.qtDepartmentId()).addValue("kf",input.kfDepartmentId()).addValue("wx",input.wxDepartmentId()));
        audit.record("STORE_DEPARTMENT_PREFIX_CONFIGURED","ORG_UNIT",hotelId,json(Map.of("before",before,"after",input)));
        return preview(hotelId);
    }

    @Transactional
    public Object apply(UUID hotelId, ApplyInput input, String key) {
        var p=prepare(hotelId);
        var reservation=commands.reserve("STORE_PREFIX_FILL:"+p.actorId()+":"+hotelId,key,input,UUID.randomUUID());
        if(reservation.replayed()) return reservation.responseSnapshot();
        lock(p,hotelId);
        // Lock parent item/asset rows in the same order as scope-only imports, then validate departments.
        var selected=input.selections().stream().sorted(Comparator.comparing(Selection::type).reversed().thenComparing(s->s.id().toString())).toList();
        Set<String> seen=new HashSet<>();
        Map<String,Map<String,Object>> snapshots=new HashMap<>();
        for(var row:selected) {
            if(!Set.of("ITEM","ASSET").contains(row.type()) || !seen.add(row.type()+row.id())) throw new IllegalArgumentException("请选择不重复的物品或资产");
            boolean asset=row.type().equals("ASSET");
            var records=jdbc.queryForList("select id,"+(asset ? "asset_code" : "sku")+" as code,usage_scope_mode,row_version from "+
                    (asset ? "store_fixed_asset" : "store_resource_item")+" where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:id for update",
                    params(p,hotelId).addValue("id",row.id()));
            if(records.isEmpty()) throw new IllegalArgumentException("所选记录不属于当前门店");
            var before=records.getFirst();
            if(((Number)before.get("row_version")).longValue()!=row.expectedVersion() || !"LEGACY".equals(before.get("usage_scope_mode"))) throw conflict();
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+(asset ? "store_asset_usage_department" : "store_item_usage_department")
                    +" where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and "+(asset ? "asset_id" : "item_id")+"=:id)",params(p,hotelId).addValue("id",row.id()),Boolean.class))) throw conflict();
            snapshots.put(row.type()+row.id(),before);
        }
        var options=scopes.options(p.tenantId(),hotelId,true);
        var config=scopes.prefixConfig(p.tenantId(),hotelId,options);
        if(config.rowVersion()!=input.configVersion()) throw conflict();
        for(var row:selected) {
            var before=snapshots.get(row.type()+row.id());
            if(!row.departmentId().equals(StoreItemUsageScopes.inferredDepartment((String)before.get("code"),config))) throw conflict();
            boolean asset=row.type().equals("ASSET");
            String table=asset ? "store_fixed_asset" : "store_resource_item";
            String mapping=asset ? "store_asset_usage_department" : "store_item_usage_department";
            String idColumn=asset ? "asset_id" : "item_id";
            var parameters=params(p,hotelId).addValue("id",row.id()).addValue("department",row.departmentId());
            // Position restrictions stay untouched; even legacy position restrictions are preserved.
            jdbc.update("delete from "+mapping+" where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and "+idColumn+"=:id",parameters);
            jdbc.update("insert into "+mapping+"(tenant_id,hotel_org_unit_id,"+idColumn+",department_id) values(:tenantId,:hotelId,:id,:department)",parameters);
            jdbc.update("update "+table+" set usage_scope_mode='DEPARTMENTS',row_version=row_version+1 where tenant_id=:tenantId and hotel_org_unit_id=:hotelId and id=:id",parameters);
            audit.record("STORE_DEPARTMENT_PREFIX_FILLED",asset ? "STORE_FIXED_ASSET" : "STORE_RESOURCE_ITEM",row.id(),json(Map.of("before",before,"departmentId",row.departmentId())));
        }
        var result=Map.of("updated",selected.size());
        commands.succeed(reservation,"ORG_UNIT",hotelId,200,result);
        return result;
    }
    private TenantPrincipal prepare(UUID hotelId) {
        access.requirePermission("store-resource.item-manage");access.requirePermission("store-resource.asset-manage");
        var p=access.principal();context.apply(p.tenantId());access.requireOrgScope(hotelId);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from org_unit where tenant_id=:tenantId and id=:hotelId and unit_type='HOTEL' and status='ACTIVE')",params(p,hotelId),Boolean.class)))
            throw new IllegalArgumentException("目标门店不存在或已停用");
        return p;
    }
    private void lock(TenantPrincipal p,UUID hotelId) { jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,82))",new MapSqlParameterSource("key",p.tenantId()+":"+hotelId)); }
    private MapSqlParameterSource params(TenantPrincipal p,UUID hotelId) { return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId).addValue("actorId",p.actorId()); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"部门规则或物品已变化，请重新预览后确认；不会覆盖人工配置"); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("审计序列化失败",e); } }
}
