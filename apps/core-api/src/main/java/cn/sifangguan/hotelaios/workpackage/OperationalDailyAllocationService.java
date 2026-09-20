package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Provision published operational packages for active assignments. Never changes historical expectations. */
@Component
public class OperationalDailyAllocationService {
    private final NamedParameterJdbcTemplate jdbc;

    public OperationalDailyAllocationService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Recover only initial gaps: never reactivate revoked/expired administrator allocations. */
    public void ensureInitialAllocations(TenantPrincipal principal, LocalDate businessDate) {
        List<UUID> missing = jdbc.queryForList("""
                select a.id from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join user_account account on account.tenant_id = e.tenant_id and account.id = e.account_id
                where a.tenant_id = :tenantId and a.status = 'ACTIVE'
                  and a.valid_from <= :businessDate and (a.valid_to is null or a.valid_to >= :businessDate)
                  and e.employment_status = 'ACTIVE' and e.deleted_at is null and account.status = 'ACTIVE'
                  and exists (select 1 from work_package_definition definition
                    join work_package_version published on published.tenant_id = definition.tenant_id
                      and published.work_package_definition_id = definition.id
                    where definition.tenant_id = a.tenant_id and definition.position_id = a.position_id
                      and definition.code like 'WP-PILOT-%-DAILY'
                      and published.lifecycle_status = 'PUBLISHED'
                      and published.effective_from::date <= :businessDate
                      and (published.effective_to is null or published.effective_to::date >= :businessDate))
                  and not exists (
                    select 1 from work_package_allocation allocation
                    join work_package_version version on version.tenant_id = allocation.tenant_id
                      and version.id = allocation.work_package_version_id
                    join work_package_definition definition on definition.tenant_id = version.tenant_id
                      and definition.id = version.work_package_definition_id
                    where allocation.tenant_id = a.tenant_id and allocation.position_assignment_id = a.id
                      and definition.code like 'WP-PILOT-%-DAILY'
                      and (allocation.valid_from <= :businessDate or allocation.status = 'REVOKED')
                  )
                order by a.id
                """, base(principal).addValue("businessDate", businessDate), UUID.class);
        for (UUID assignmentId : missing) reconcile(principal, assignmentId, businessDate);
    }

    public void reconcile(TenantPrincipal principal, UUID assignmentId, LocalDate effectiveDate) {
        MapSqlParameterSource params = base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("today", effectiveDate.minusDays(1))
                .addValue("effectiveDate", effectiveDate)
                .addValue("actorId", principal.actorId());

        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                params.addValue("lockKey", principal.tenantId() + ":operational-allocation:" + assignmentId),
                (rs, row) -> true);
        jdbc.update("""
                update work_package_allocation allocation
                set status = case when allocation.valid_from > :today then 'REVOKED'
                                  else allocation.status end,
                    valid_to = case when allocation.valid_from <= :today then :today
                                    else allocation.valid_to end,
                    updated_at = now()
                from work_package_version version
                join work_package_definition definition
                  on definition.tenant_id = version.tenant_id
                 and definition.id = version.work_package_definition_id
                where allocation.tenant_id = :tenantId
                  and allocation.position_assignment_id = :assignmentId
                  and allocation.work_package_version_id = version.id
                  and allocation.allocation_source = 'SYSTEM'
                  and allocation.status = 'ACTIVE'
                  and definition.code in (
                    'WP-PILOT-GM-DAILY','WP-PILOT-AGM-DAILY',
                    'WP-PILOT-FO-SUPERVISOR-DAILY','WP-PILOT-SERVICE-STEWARD-DAILY',
                    'WP-PILOT-FRONT-DAILY','WP-PILOT-HK-SUPERVISOR-DAILY',
                    'WP-PILOT-HK-ATTENDANT-DAILY','WP-PILOT-NIGHT-ATTENDANT-DAILY',
                    'WP-PILOT-BREAKFAST-LEAD-DAILY'
                  )
                  and (allocation.valid_to is null or allocation.valid_to >= :today)
                """, params);

        jdbc.update("""
                insert into work_package_allocation
                    (id,tenant_id,work_package_version_id,position_assignment_id,
                     target_org_unit_id,allocation_source,valid_from,valid_to,status,allocated_by)
                select gen_random_uuid(), assignment.tenant_id, selected_version.id, assignment.id,
                       hotel_config.hotel_org_unit_id, 'SYSTEM',
                       selected_version.starts_on, assignment.valid_to,
                       'ACTIVE', :actorId
                from employee_position_assignment assignment
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                join employee employee_row
                  on employee_row.tenant_id = assignment.tenant_id
                 and employee_row.id = assignment.employee_id
                join user_account account
                  on account.tenant_id = employee_row.tenant_id
                 and account.id = employee_row.account_id
                join lateral (
                    select profile.org_unit_id as hotel_org_unit_id,
                           profile.assistant_work_template_mode,
                           coalesce(
                             (profile.operational_feature_flags ->> 'breakfastArea')::boolean,
                             profile.breakfast_service_enabled
                           ) as breakfast_area_enabled
                    from org_unit_closure scope
                    join hotel_profile profile
                      on profile.tenant_id = scope.tenant_id
                     and profile.org_unit_id = scope.ancestor_id
                    where scope.tenant_id = assignment.tenant_id
                      and scope.descendant_id = assignment.org_unit_id
                    order by scope.depth asc
                    limit 1
                ) hotel_config on true
                join lateral (
                    select version.id,
                           greatest(:effectiveDate, assignment.valid_from, version.effective_from::date) as starts_on
                    from work_package_definition definition
                    join work_package_version version
                      on version.tenant_id = definition.tenant_id
                     and version.work_package_definition_id = definition.id
                    where definition.tenant_id = assignment.tenant_id
                      and definition.status = 'ACTIVE'
                      and definition.code = case position.code
                        when 'GENERAL_MANAGER' then 'WP-PILOT-GM-DAILY'
                        when 'ASSISTANT_GENERAL_MANAGER' then 'WP-PILOT-AGM-DAILY'
                        when 'FRONT_OFFICE_SUPERVISOR' then 'WP-PILOT-FO-SUPERVISOR-DAILY'
                        when 'SERVICE_STEWARD' then 'WP-PILOT-SERVICE-STEWARD-DAILY'
                        when 'FRONT_DESK' then 'WP-PILOT-FRONT-DAILY'
                        when 'HOUSEKEEPING_SUPERVISOR' then 'WP-PILOT-HK-SUPERVISOR-DAILY'
                        when 'HOUSEKEEPING_ATTENDANT' then 'WP-PILOT-HK-ATTENDANT-DAILY'
                        when 'NIGHT_SERVICE_ATTENDANT' then 'WP-PILOT-NIGHT-ATTENDANT-DAILY'
                        when 'BREAKFAST_AREA_LEAD' then 'WP-PILOT-BREAKFAST-LEAD-DAILY'
                        else null end
                      and version.lifecycle_status = 'PUBLISHED'
                      and version.effective_from is not null
                      and (version.effective_to is null
                           or version.effective_to::date >= greatest(:effectiveDate, assignment.valid_from, version.effective_from::date))
                      and (
                        position.code <> 'ASSISTANT_GENERAL_MANAGER'
                        or exists (
                          select 1 from work_package_item item
                          where item.tenant_id = version.tenant_id
                            and item.work_package_version_id = version.id
                            and item.applicability_policy ->> 'assistantWorkTemplateMode'
                                = hotel_config.assistant_work_template_mode
                        )
                      )
                    order by (version.effective_from::date <= greatest(:effectiveDate, assignment.valid_from)) desc,
                             case when version.effective_from::date > greatest(:effectiveDate, assignment.valid_from)
                                  then version.effective_from end,
                             version.version_no desc
                    limit 1
                ) selected_version on true
                where assignment.tenant_id = :tenantId
                  and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                  and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and employee_row.employment_status = 'ACTIVE' and employee_row.deleted_at is null
                  and account.status = 'ACTIVE'
                  and (assignment.valid_to is null
                       or assignment.valid_to >= selected_version.starts_on)
                  and (position.code <> 'FRONT_OFFICE_SUPERVISOR'
                       or hotel_config.assistant_work_template_mode = 'WITH_FRONT_OFFICE_SUPERVISOR')
                  and (position.code <> 'BREAKFAST_AREA_LEAD'
                       or hotel_config.breakfast_area_enabled)
                on conflict (tenant_id,work_package_version_id,position_assignment_id,
                             target_org_unit_id,valid_from)
                do update set status = 'ACTIVE', valid_to = excluded.valid_to,
                              allocated_by = excluded.allocated_by, updated_at = now()
                """, params);
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }
}
