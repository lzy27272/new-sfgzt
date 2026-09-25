package cn.sifangguan.hotelaios.notifications;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.BusinessIdentityException;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Additional management recipients do not change the employee's organization or grant permissions. */
@Service
public class StoreManagerSupervisorResolver {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final EffectiveIdentityService identities;
    private final TransactionTemplate identityRead;

    public StoreManagerSupervisorResolver(NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext, EffectiveIdentityService identities,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.identities = identities;
        this.identityRead = new TransactionTemplate(transactionManager);
        this.identityRead.setReadOnly(true);
        // A revoked or invalid candidate must not roll back reminders for everyone else.
        this.identityRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Resolve once per SLA pass, not once per overdue work item. */
    public Audience load(TenantPrincipal operator, String readPermission) {
        databaseContext.apply(operator.tenantId());
        List<Candidate> candidates = jdbc.query("""
                select a.id, e.account_id, role.code
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                join position_function_profile profile
                  on profile.tenant_id = p.tenant_id and profile.position_id = p.id
                 and profile.scope_type = 'GROUP'
                join app_role role on role.tenant_id = profile.tenant_id and role.id = profile.default_role_id
                where a.tenant_id = :tenantId
                  and role.role_type = 'SYSTEM' and role.code in ('GENERAL_MANAGER', 'GROUP_VICE_PRESIDENT')
                  and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                  and e.employment_status = 'ACTIVE' and e.deleted_at is null
                  and u.status = 'ACTIVE' and o.status = 'ACTIVE'
                  and p.status = 'ACTIVE' and p.deleted_at is null and p.permanently_deleted_at is null
                order by a.is_primary desc, a.id
                """, new MapSqlParameterSource("tenantId", operator.tenantId()),
                (rs, row) -> new Candidate(rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("code")));
        Map<UUID, UUID> storeManagers = new LinkedHashMap<>();
        List<Supervisor> supervisors = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if ("GENERAL_MANAGER".equals(candidate.roleCode())) {
                storeManagers.put(candidate.assignmentId(), candidate.accountId());
                continue;
            }
            try {
                TenantPrincipal identity = identityRead.execute(status -> identities.resolve(
                        operator.tenantId(), candidate.accountId(), operator.correlationId(), candidate.assignmentId()));
                if (identity != null && (identity.hasPermission(readPermission) || identity.hasPermission("*"))
                        && (!"task.read".equals(readPermission) || identity.hasTenantScope()
                            || identity.hasPermission("task.review") || identity.hasPermission("task.dispatch")
                            || identity.hasPermission("task.create") || identity.hasPermission("*"))) {
                    supervisors.add(new Supervisor(candidate.accountId(), candidate.assignmentId(), identity));
                }
            } catch (IdentityAuthenticationException | BusinessIdentityException ignored) {
                // Fail closed for this account; no fallback to a role name or all tenant hotels.
            }
        }
        return new Audience(Map.copyOf(storeManagers), List.copyOf(supervisors));
    }

    /** The same ordinary-review policy used by the write endpoint, evaluated
     * against fresh permissions for each active recipient. Never used for stock reviews. */
    public List<Supervisor> ordinaryReviewers(TenantPrincipal operator, String permission, UUID executor, UUID target) {
        databaseContext.apply(operator.tenantId());
        var params = new MapSqlParameterSource("tenant", operator.tenantId()).addValue("executor", executor);
        List<Candidate> candidates = jdbc.query("""
                select distinct a.id, e.account_id
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                left join position_function_profile f on f.tenant_id = a.tenant_id and f.position_id = a.position_id and f.scope_type = 'GROUP'
                left join app_role r on r.tenant_id = f.tenant_id and r.id = f.default_role_id and r.role_type = 'SYSTEM'
                where a.tenant_id = :tenant and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date) and e.account_id is not null
                  and (r.code in ('GENERAL_MANAGER','GROUP_VICE_PRESIDENT') or a.id in (
                    select manager_assignment_id from employee_position_assignment where tenant_id = :tenant and id = :executor
                    union select leader_assignment_id from position_assignment_reporting_relation
                    where tenant_id = :tenant and subordinate_assignment_id = :executor
                      and valid_from <= current_date and (valid_to is null or valid_to >= current_date)))
                order by a.id
                """, params, (rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), ""));
        Map<UUID, Supervisor> recipients = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.accountId().equals(operator.actorId())) continue;
            try {
                TenantPrincipal identity = identityRead.execute(status -> identities.resolve(
                        operator.tenantId(), candidate.accountId(), operator.correlationId(), candidate.assignmentId()));
                databaseContext.apply(operator.tenantId());
                if (identity != null && (identity.hasPermission(permission) || identity.hasPermission("*"))
                        && new WorkReviewPolicy(jdbc).canManage(identity, executor, target)) {
                    recipients.putIfAbsent(candidate.accountId(), new Supervisor(candidate.accountId(), candidate.assignmentId(), identity));
                }
            } catch (IdentityAuthenticationException | BusinessIdentityException ignored) {
                // Deactivated accounts/assignments cannot receive actionable reminders.
            }
        }
        return List.copyOf(recipients.values());
    }

    private record Candidate(UUID assignmentId, UUID accountId, String roleCode) { }
    public record Supervisor(UUID accountId, UUID assignmentId, TenantPrincipal identity) { }

    public record Audience(Map<UUID, UUID> storeManagers, List<Supervisor> supervisors) {
        public List<Supervisor> forWork(UUID executorAssignmentId, UUID targetOrgUnitId) {
            if (executorAssignmentId == null || targetOrgUnitId == null) return List.of();
            UUID executorAccountId = storeManagers.get(executorAssignmentId);
            if (executorAccountId == null) return List.of();
            Map<UUID, Supervisor> recipients = new LinkedHashMap<>();
            for (Supervisor supervisor : supervisors) {
                if (!executorAccountId.equals(supervisor.accountId())
                        && (supervisor.identity().hasTenantScope()
                            || supervisor.identity().orgScopes().contains(targetOrgUnitId))) {
                    recipients.putIfAbsent(supervisor.accountId(), supervisor);
                }
            }
            return List.copyOf(recipients.values());
        }
    }
}
