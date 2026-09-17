package cn.sifangguan.hotelaios.shared.time;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Resolves a hotel's authoritative business day and snapshots its cutoff context. */
@Service
public class BusinessDayService {
    private static final LocalTime DEFAULT_CUTOFF = LocalTime.of(4, 0);

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;

    public BusinessDayService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public BusinessDayContext resolveCurrent(UUID orgUnitId) {
        return resolve(orgUnitId, null, Instant.now());
    }

    @Transactional(readOnly = true)
    public BusinessDayContext resolveCurrent(UUID orgUnitId, Instant now) {
        return resolve(orgUnitId, null, now);
    }

    @Transactional(readOnly = true)
    public BusinessDayContext resolve(UUID orgUnitId, LocalDate requestedBusinessDate) {
        return resolve(orgUnitId, requestedBusinessDate, Instant.now());
    }

    @Transactional(readOnly = true)
    public BusinessDayContext resolve(UUID orgUnitId, LocalDate requestedBusinessDate, Instant now) {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        requireOrgScopeOrOwnAssignment(principal, orgUnitId);
        List<BusinessDayConfiguration> rows = jdbc.query("""
                select hotel.id as hotel_org_unit_id,
                       coalesce(config.timezone, tenant.timezone) as timezone,
                       coalesce(config.cutoff_local_time, cast('04:00' as time)) as cutoff_local_time,
                       coalesce(config.closing_grace_minutes, 0) as closing_grace_minutes
                from org_unit target
                join tenant on tenant.id = target.tenant_id
                join org_unit_closure relation
                  on relation.tenant_id = target.tenant_id and relation.descendant_id = target.id
                join org_unit hotel
                  on hotel.tenant_id = relation.tenant_id and hotel.id = relation.ancestor_id
                 and hotel.unit_type = 'HOTEL' and hotel.status = 'ACTIVE'
                left join hotel_business_day_config config
                  on config.tenant_id = hotel.tenant_id and config.hotel_org_unit_id = hotel.id
                 and config.status = 'ACTIVE'
                where target.tenant_id = :tenantId and target.id = :orgUnitId and target.status = 'ACTIVE'
                order by relation.depth
                limit 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("orgUnitId", orgUnitId),
                (rs, rowNum) -> new BusinessDayConfiguration(
                        rs.getObject("hotel_org_unit_id", UUID.class),
                        rs.getString("timezone"),
                        rs.getObject("cutoff_local_time", LocalTime.class),
                        rs.getInt("closing_grace_minutes")));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("目标组织不属于有效门店");
        }
        BusinessDayConfiguration config = rows.getFirst();
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("门店营业日时区配置无效", exception);
        }
        LocalDateTime localNow = LocalDateTime.ofInstant(now, zone);
        LocalTime cutoff = config.cutoffLocalTime() == null ? DEFAULT_CUTOFF : config.cutoffLocalTime();
        LocalDate calculated = localNow.toLocalTime().isBefore(cutoff)
                ? localNow.toLocalDate().minusDays(1) : localNow.toLocalDate();
        LocalDate businessDate = requestedBusinessDate == null ? calculated : requestedBusinessDate;
        return new BusinessDayContext(
                config.hotelOrgUnitId(), orgUnitId, businessDate, config.timezone(), cutoff,
                config.closingGraceMinutes(), now, calculated.equals(businessDate));
    }

    private void requireOrgScopeOrOwnAssignment(TenantPrincipal principal, UUID orgUnitId) {
        if (principal.hasTenantScope() || principal.orgScopes().contains(orgUnitId)) {
            return;
        }
        if (principal.assignmentIds().isEmpty()) {
            throw new AccessDeniedException("目标组织不在当前账号的有效数据范围内");
        }
        Boolean ownsActiveAssignment = jdbc.queryForObject("""
                select exists (
                  select 1
                  from employee_position_assignment assignment
                  join employee
                    on employee.tenant_id = assignment.tenant_id
                   and employee.id = assignment.employee_id
                   where assignment.tenant_id = :tenantId
                      and assignment.id in (:assignmentIds)
                      and assignment.status = 'ACTIVE'
                      and assignment.valid_from <= current_date
                      and (assignment.valid_to is null or assignment.valid_to >= current_date)
                      and employee.account_id = :actorId
                      and employee.employment_status = 'ACTIVE'
                      and exists (
                        select 1
                        from org_unit_closure assignment_scope
                        where assignment_scope.tenant_id = assignment.tenant_id
                          and assignment_scope.ancestor_id = :orgUnitId
                          and assignment_scope.descendant_id = assignment.org_unit_id
                      )
                )
                """, new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("assignmentIds", principal.assignmentIds())
                .addValue("orgUnitId", orgUnitId)
                .addValue("actorId", principal.actorId()), Boolean.class);
        if (!Boolean.TRUE.equals(ownsActiveAssignment)) {
            throw new AccessDeniedException("目标组织不在当前账号的有效数据范围内");
        }
    }

    private record BusinessDayConfiguration(
            UUID hotelOrgUnitId,
            String timezone,
            LocalTime cutoffLocalTime,
            int closingGraceMinutes
    ) {
    }

    public record BusinessDayContext(
            UUID hotelOrgUnitId,
            UUID orgUnitId,
            LocalDate businessDate,
            String timezone,
            LocalTime cutoffLocalTime,
            int closingGraceMinutes,
            Instant resolvedAt,
            boolean currentBusinessDay
    ) {
    }
}
