package cn.sifangguan.hotelaios.dashboard;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final WorkVisibilityPolicy workVisibility;

    public DashboardService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext databaseContext, AccessPolicy accessPolicy, WorkVisibilityPolicy workVisibility) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.workVisibility = workVisibility;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ceoDashboard() {
        accessPolicy.requirePermission("dashboard.ceo");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope()) {
            throw new IllegalArgumentException("CEO驾驶舱需要集团数据范围");
        }
        MapSqlParameterSource params = base(principal);
        WorkVisibilityPolicy.Scope workScope = workVisibility.scope(principal, params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hotelCount", scalar("select count(*) from org_unit where tenant_id = :tenantId and unit_type = 'HOTEL' and status = 'ACTIVE'", params));
        result.put("employeeCount", scalar("select count(*) from employee where tenant_id = :tenantId and employment_status = 'ACTIVE'", params));
        result.put("publishedStandardCount", scalar("select count(*) from standard_version where tenant_id = :tenantId and lifecycle_status = 'PUBLISHED'", params));
        result.put("todayWorkSubmissionCount", scalar("select count(*) from work_record w where w.tenant_id = :tenantId and w.business_date = current_date and w.status <> 'DRAFT' and " + workScope.summary("w"), params));
        result.put("hotels", jdbc.queryForList("""
                select o.id, o.name, h.city, h.room_count
                from org_unit o join hotel_profile h on h.tenant_id = o.tenant_id and h.org_unit_id = o.id
                where o.tenant_id = :tenantId and o.status = 'ACTIVE'
                order by o.name
                """, params));
        result.put("latestMetrics", jdbc.queryForList("""
                select distinct on (m.code, o.hotel_org_unit_id)
                       o.hotel_org_unit_id, h.name as hotel_name, m.code, m.name, m.unit,
                       o.business_date, o.value
                from metric_observation o
                join metric_definition m on m.tenant_id = o.tenant_id and m.id = o.metric_id
                join org_unit h on h.tenant_id = o.tenant_id and h.id = o.hotel_org_unit_id
                where o.tenant_id = :tenantId
                order by m.code, o.hotel_org_unit_id, o.business_date desc, o.created_at desc
                """, params));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hotelDashboard(UUID hotelId) {
        accessPolicy.requirePermission("dashboard.hotel");
        TenantPrincipal principal = prepare();
        requireHotelScope(principal, hotelId);
        MapSqlParameterSource params = base(principal).addValue("hotelId", hotelId);
        WorkVisibilityPolicy.Scope workScope = workVisibility.scope(principal, params);
        Map<String, Object> hotel = jdbc.queryForMap("""
                select o.id, o.name, h.city, h.room_count
                from org_unit o join hotel_profile h on h.tenant_id = o.tenant_id and h.org_unit_id = o.id
                where o.tenant_id = :tenantId and o.id = :hotelId
                """, params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hotel", hotel);
        result.put("activeEmployeeCount", scalar("""
                select count(distinct a.employee_id)
                from employee_position_assignment a
                join org_unit_closure c on c.tenant_id = a.tenant_id and c.descendant_id = a.org_unit_id
                where a.tenant_id = :tenantId and c.ancestor_id = :hotelId and a.status = 'ACTIVE'
                """, params));
        result.put("todayWorkSubmissionCount", scalar("""
                select count(*) from work_record w
                where w.tenant_id = :tenantId and org_unit_id in (
                    select descendant_id from org_unit_closure where tenant_id = :tenantId and ancestor_id = :hotelId
                ) and w.business_date = current_date and w.status <> 'DRAFT'
                """ + " and " + workScope.summary("w"), params));
        result.put("latestMetrics", jdbc.queryForList("""
                select distinct on (m.code) m.code, m.name, m.unit, o.business_date, o.value
                from metric_observation o
                join metric_definition m on m.tenant_id = o.tenant_id and m.id = o.metric_id
                where o.tenant_id = :tenantId and o.hotel_org_unit_id = :hotelId
                order by m.code, o.business_date desc, o.created_at desc
                """, params));
        result.put("risks", hotelRisks(params, workScope));
        result.put("incompleteTasks", incompleteTasks(params));
        result.put("openTaskCount", scalar("""
                select count(*) from management_task t
                where t.tenant_id = :tenantId
                  and t.org_unit_id in (
                    select descendant_id from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id = :hotelId
                  )
                  and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                """, params));
        result.put("overdueTaskCount", scalar("""
                select count(*) from management_task t
                where t.tenant_id = :tenantId
                  and t.org_unit_id in (
                    select descendant_id from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id = :hotelId
                  )
                  and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                  and t.sla_status = 'OVERDUE'
                """, params));
        result.put("missedWorkCount", scalar("""
                select count(*) from work_expectation x
                where x.tenant_id = :tenantId
                  and x.target_org_unit_id in (
                    select descendant_id from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id = :hotelId
                  ) and x.status = 'MISSED'
                """ + " and " + workScope.summary("x"), params));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accessibleHotels() {
        accessPolicy.requirePermission("dashboard.hotel");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return Map.of("hotels", List.of());
        }
        MapSqlParameterSource params = base(principal);
        String visibility = "";
        if (!principal.hasTenantScope()) {
            params.addValue("scopeIds", principal.orgScopes());
            visibility = """
                    and exists (
                      select 1 from org_unit_closure visible
                      where visible.tenant_id = hotel.tenant_id
                        and (
                          (visible.descendant_id = hotel.id and visible.ancestor_id in (:scopeIds))
                          or (visible.ancestor_id = hotel.id and visible.descendant_id in (:scopeIds))
                        )
                    )
                    """;
        }
        List<Map<String, Object>> hotels = jdbc.queryForList("""
                select hotel.id, hotel.code, hotel.name, profile.city, profile.room_count
                from org_unit hotel
                join hotel_profile profile
                  on profile.tenant_id = hotel.tenant_id and profile.org_unit_id = hotel.id
                where hotel.tenant_id = :tenantId
                  and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                """ + visibility + " order by hotel.name, hotel.id", params);
        return Map.of("hotels", hotels);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> operationsDashboard() {
        accessPolicy.requirePermission("dashboard.operations");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return Map.of("hotels", List.of());
        }
        MapSqlParameterSource params = base(principal);
        WorkVisibilityPolicy.Scope workScope = workVisibility.scope(principal, params);
        String visibility = "";
        if (!principal.hasTenantScope()) {
            params.addValue("scopeIds", principal.orgScopes());
            visibility = """
                    and exists (
                      select 1 from org_unit_closure visible
                      where visible.tenant_id = hotel.tenant_id
                        and (
                          (visible.descendant_id = hotel.id and visible.ancestor_id in (:scopeIds))
                          or (visible.ancestor_id = hotel.id and visible.descendant_id in (:scopeIds))
                        )
                    )
                    """;
        }
        List<Map<String, Object>> hotels = jdbc.queryForList("""
                select hotel.id, hotel.name, profile.city, profile.room_count,
                       (select count(*) from management_task t
                        where t.tenant_id = hotel.tenant_id
                          and t.org_unit_id in (
                            select descendant_id from org_unit_closure
                            where tenant_id = hotel.tenant_id and ancestor_id = hotel.id
                          ) and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')) as open_task_count,
                       (select count(*) from management_task t
                        where t.tenant_id = hotel.tenant_id
                          and t.org_unit_id in (
                            select descendant_id from org_unit_closure
                            where tenant_id = hotel.tenant_id and ancestor_id = hotel.id
                          ) and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                          and t.sla_status = 'OVERDUE') as overdue_task_count,
                       (select count(*) from standard_evaluation e
                        where e.tenant_id = hotel.tenant_id
                          and e.org_unit_id in (
                            select descendant_id from org_unit_closure
                            where tenant_id = hotel.tenant_id and ancestor_id = hotel.id
                          ) and e.outcome = 'FAIL') as failed_evaluation_count,
                       (select count(*) from work_expectation x
                        where x.tenant_id = hotel.tenant_id
                          and x.target_org_unit_id in (
                            select descendant_id from org_unit_closure
                            where tenant_id = hotel.tenant_id and ancestor_id = hotel.id
                          ) and x.status = 'MISSED' and %1$s) as missed_work_count,
                       (select count(*) from work_record w
                        where w.tenant_id = hotel.tenant_id
                          and w.target_org_unit_id in (
                            select descendant_id from org_unit_closure
                            where tenant_id = hotel.tenant_id and ancestor_id = hotel.id
                          ) and w.business_date = current_date and w.status <> 'DRAFT' and %2$s) as today_submission_count
                from org_unit hotel
                join hotel_profile profile on profile.tenant_id = hotel.tenant_id and profile.org_unit_id = hotel.id
                where hotel.tenant_id = :tenantId and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                """.formatted(workScope.summary("x"), workScope.summary("w")) + visibility + " order by hotel.name", params);
        long riskCount = hotels.stream()
                .mapToLong(item -> number(item.get("overdue_task_count"))
                        + number(item.get("failed_evaluation_count")) + number(item.get("missed_work_count")))
                .sum();
        return Map.of("hotelCount", hotels.size(), "riskCount", riskCount, "hotels", hotels);
    }

    /**
     * Dashboard-specific scope: a hotel manager scoped to a department may
     * inspect the containing hotel dashboard. This intentionally does not
     * alter generic organization or record permissions.
     */
    private void requireHotelScope(TenantPrincipal principal, UUID hotelId) {
        if (principal.hasTenantScope()) {
            return;
        }
        if (principal.orgScopes().isEmpty()) {
            throw new AccessDeniedException("Hotel dashboard is outside the current authorization range");
        }
        Integer visible = jdbc.queryForObject("""
                select count(*)
                from org_unit hotel
                where hotel.tenant_id = :tenantId and hotel.id = :hotelId
                  and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                  and exists (
                    select 1 from org_unit_closure relation
                    where relation.tenant_id = hotel.tenant_id
                      and (
                        (relation.descendant_id = hotel.id and relation.ancestor_id in (:scopeIds))
                        or (relation.ancestor_id = hotel.id and relation.descendant_id in (:scopeIds))
                      )
                  )
                """, base(principal).addValue("hotelId", hotelId)
                .addValue("scopeIds", principal.orgScopes()), Integer.class);
        if (visible == null || visible != 1) {
            throw new AccessDeniedException("Hotel dashboard is outside the current authorization range");
        }
    }

    private List<Map<String, Object>> hotelRisks(MapSqlParameterSource params, WorkVisibilityPolicy.Scope workScope) {
        return jdbc.queryForList("""
                select risk_type, source_id, title, severity, status, occurred_at, org_unit_id
                from (
                    select 'STANDARD_EVALUATION'::text as risk_type, e.id as source_id,
                           concat('标准评价未通过：', coalesce(d.name, v.title)) as title,
                           e.severity::text as severity, e.outcome::text as status,
                           coalesce(e.completed_at, e.created_at) as occurred_at, e.org_unit_id
                    from standard_evaluation e
                    join standard_version v on v.tenant_id = e.tenant_id and v.id = e.standard_version_id
                    join standard_definition d on d.tenant_id = v.tenant_id and d.id = v.standard_id
                    where e.tenant_id = :tenantId and e.outcome = 'FAIL'
                      and e.org_unit_id in (
                        select descendant_id from org_unit_closure
                        where tenant_id = :tenantId and ancestor_id = :hotelId
                      )
                    union all
                    select 'OVERDUE_TASK'::text, t.id, concat('任务逾期：', t.title),
                           case when t.priority = 'URGENT' then 'CRITICAL' else 'HIGH' end,
                           t.lifecycle_status::text, coalesce(t.due_at, t.updated_at), t.org_unit_id
                    from management_task t
                    where t.tenant_id = :tenantId and t.sla_status = 'OVERDUE'
                      and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                      and t.org_unit_id in (
                        select descendant_id from org_unit_closure
                        where tenant_id = :tenantId and ancestor_id = :hotelId
                      )
                    union all
                    select 'MISSED_WORK'::text, x.id, concat('岗位工作漏交：', i.name),
                           'HIGH'::text, x.status::text, coalesce(x.due_at, x.updated_at), x.target_org_unit_id
                    from work_expectation x
                    join work_package_item i on i.tenant_id = x.tenant_id and i.id = x.work_package_item_id
                    where x.tenant_id = :tenantId and x.status = 'MISSED' and %s
                      and x.target_org_unit_id in (
                        select descendant_id from org_unit_closure
                        where tenant_id = :tenantId and ancestor_id = :hotelId
                      )
                ) risk
                order by occurred_at desc, source_id
                limit 50
                """.formatted(workScope.expectation("x", true)), params);
    }

    private List<Map<String, Object>> incompleteTasks(MapSqlParameterSource params) {
        return jdbc.queryForList("""
                select t.id, t.task_no, t.title, t.lifecycle_status, t.sla_status, t.priority,
                       t.due_at, t.org_unit_id, o.name as org_unit_name,
                       assignee.employee_snapshot ->> 'name' as assignee_name,
                       reviewer.employee_snapshot ->> 'name' as reviewer_name
                from management_task t
                join org_unit o on o.tenant_id = t.tenant_id and o.id = t.org_unit_id
                left join lateral (
                    select p.employee_snapshot from task_participant p
                    where p.tenant_id = t.tenant_id and p.task_id = t.id
                      and p.participant_type = 'ASSIGNEE' and p.valid_to is null
                    order by p.created_at desc limit 1
                ) assignee on true
                left join lateral (
                    select p.employee_snapshot from task_participant p
                    where p.tenant_id = t.tenant_id and p.task_id = t.id
                      and p.participant_type = 'REVIEWER' and p.valid_to is null
                    order by p.created_at desc limit 1
                ) reviewer on true
                where t.tenant_id = :tenantId
                  and t.org_unit_id in (
                    select descendant_id from org_unit_closure
                    where tenant_id = :tenantId and ancestor_id = :hotelId
                  )
                  and t.lifecycle_status not in ('COMPLETED', 'CANCELLED')
                order by (t.sla_status = 'OVERDUE') desc, t.due_at nulls last, t.created_at desc
                limit 100
                """, params);
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private long scalar(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
