package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Employee selection is tied to one assignment, never the union of the account's roles. */
@Component
final class StoreEmployeeItemScope {
    private final NamedParameterJdbcTemplate jdbc;
    StoreEmployeeItemScope(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Both department and optional position restrictions must match. LEGACY is not an allow-all.
    static final String ELIGIBLE = """
            (i.usage_scope_mode='ALL_DEPARTMENTS' or
             (i.usage_scope_mode='DEPARTMENTS' and exists (
               select 1 from store_item_usage_department d
               join org_unit_closure c on c.tenant_id=d.tenant_id and c.ancestor_id=d.department_id
               where d.tenant_id=i.tenant_id and d.hotel_org_unit_id=i.hotel_org_unit_id
                 and d.item_id=i.id and c.descendant_id=:assignmentOrgId)))
            and (not exists (select 1 from store_item_usage_position pos
                  where pos.tenant_id=i.tenant_id and pos.hotel_org_unit_id=i.hotel_org_unit_id and pos.item_id=i.id)
                 or exists (select 1 from store_item_usage_position pos
                  where pos.tenant_id=i.tenant_id and pos.hotel_org_unit_id=i.hotel_org_unit_id
                    and pos.item_id=i.id and pos.position_id=:positionId))
            """;

    Map<String,Object> assignment(TenantPrincipal p, UUID hotelId, UUID assignmentId, boolean requireOwner) {
        var rows = jdbc.queryForList("""
                select a.id,a.position_id,a.org_unit_id,
                  exists(select 1 from org_unit_closure c join org_unit d on d.tenant_id=c.tenant_id and d.id=c.ancestor_id
                    where c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and d.unit_type='DEPARTMENT') as department_configured
                from employee_position_assignment a
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition pos on pos.tenant_id=a.tenant_id and pos.id=a.position_id
                join org_unit_closure hotel on hotel.tenant_id=a.tenant_id and hotel.descendant_id=a.org_unit_id and hotel.ancestor_id=:hotelId
                where a.tenant_id=:tenantId and a.id=:assignmentId
                  and (:requireOwner=false or e.account_id=:actorId)
                  and a.status='ACTIVE' and e.employment_status='ACTIVE' and pos.status='ACTIVE'
                  and pos.deleted_at is null and pos.permanently_deleted_at is null
                  and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                  and not exists(select 1 from org_unit_closure c join org_unit o on o.tenant_id=c.tenant_id and o.id=c.ancestor_id
                    where c.tenant_id=a.tenant_id and c.descendant_id=a.org_unit_id and o.status<>'ACTIVE')
                """, new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId)
                .addValue("assignmentId",assignmentId).addValue("actorId",p.actorId()).addValue("requireOwner",requireOwner));
        if (rows.size()!=1) throw new AccessDeniedException("请切换到本人在该门店的有效任职后填写物品数量");
        return rows.getFirst();
    }

    MapSqlParameterSource parameters(TenantPrincipal p, UUID hotelId, Map<String,Object> assignment) {
        return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("hotelId",hotelId)
                .addValue("assignmentOrgId",assignment.get("org_unit_id")).addValue("positionId",assignment.get("position_id"));
    }

    /** Call after locking the item; configuration changes lock this same row before updating mappings. */
    void requireEligible(TenantPrincipal p, UUID hotelId, Map<String,Object> assignment, UUID itemId) {
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store_resource_item i
                  where i.tenant_id=:tenantId and i.hotel_org_unit_id=:hotelId and i.id=:itemId and
                """ + ELIGIBLE + ")", parameters(p,hotelId,assignment).addValue("itemId",itemId), Boolean.class));
        if (!allowed) throw new IllegalArgumentException("物品未配置使用范围或不适用于本次任职的部门、岗位，请刷新并核对；已填数量不会自动转给其他物品");
    }
}
