package cn.sifangguan.hotelaios.notifications;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;

    public NotificationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean unreadOnly) {
        accessPolicy.requirePermission("notification.read");
        TenantPrincipal principal = prepare();
        return jdbc.queryForList("""
                select id, notification_type, title, content, source_type, source_id,
                       recipient_assignment_id, created_at, delivered_at, read_at, row_version,
                       case when exists (
                           select 1 from notification_delivery delivery
                           where delivery.tenant_id=notification.tenant_id and delivery.notification_id=notification.id
                             and delivery.channel='WECHAT' and delivery.status='SENT') then 'SENT'
                         when not exists (
                           select 1 from wecom_user_binding binding
                           where binding.tenant_id=notification.tenant_id
                             and binding.account_id=notification.recipient_account_id
                             and binding.status='ACTIVE') then 'UNBOUND'
                         else coalesce((select delivery.status from notification_delivery delivery
                           where delivery.tenant_id=notification.tenant_id and delivery.notification_id=notification.id
                             and delivery.channel='WECHAT'
                           order by delivery.created_at desc,delivery.id desc limit 1),'NOT_QUEUED')
                       end as wecom_delivery_status
                from notification
                where tenant_id = :tenantId and recipient_account_id = :actorId
                  and (:unreadOnly = false or read_at is null)
                order by delivered_at desc
                limit 200
                """, base(principal).addValue("actorId", principal.actorId()).addValue("unreadOnly", unreadOnly));
    }

    @Transactional
    public Map<String, Object> markRead(UUID notificationId, long expectedVersion) {
        accessPolicy.requirePermission("notification.read");
        TenantPrincipal principal = prepare();
        int updated = jdbc.update("""
                update notification
                set read_at = coalesce(read_at, now()), row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and recipient_account_id = :actorId
                  and row_version = :expectedVersion
                """, base(principal)
                .addValue("id", notificationId)
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通知已变化、已被处理或不属于当前账号");
        }
        auditWriter.record("NOTIFICATION_READ", "NOTIFICATION", notificationId, "{}");
        return jdbc.queryForMap("""
                select id, read_at, row_version from notification
                where tenant_id = :tenantId and id = :id and recipient_account_id = :actorId
                """, base(principal).addValue("id", notificationId).addValue("actorId", principal.actorId()));
    }

    @Transactional
    public UUID createForAssignment(
            UUID assignmentId,
            String type,
            String title,
            String content,
            String sourceType,
            UUID sourceId,
            String idempotencyKey
    ) {
        TenantPrincipal principal = prepare();
        Map<String, Object> recipient = jdbc.queryForMap("""
                select e.account_id
                from employee_position_assignment a
                join employee e on e.tenant_id = a.tenant_id and e.id = a.employee_id
                where a.tenant_id = :tenantId and a.id = :assignmentId
                  and a.status = 'ACTIVE' and e.account_id is not null
                """, base(principal).addValue("assignmentId", assignmentId));
        UUID accountId = (UUID) recipient.get("account_id");
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into notification
                    (id, tenant_id, recipient_account_id, recipient_assignment_id, notification_type,
                     title, content, source_type, source_id, idempotency_key)
                values
                    (:id, :tenantId, :accountId, :assignmentId, :type,
                     :title, :content, :sourceType, :sourceId, :idempotencyKey)
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, base(principal)
                .addValue("id", id)
                .addValue("accountId", accountId)
                .addValue("assignmentId", assignmentId)
                .addValue("type", type)
                .addValue("title", title)
                .addValue("content", content)
                .addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId)
                .addValue("idempotencyKey", idempotencyKey));
        if (inserted == 0) {
            id = jdbc.queryForObject("""
                    select id from notification
                    where tenant_id = :tenantId and recipient_account_id = :accountId
                      and idempotency_key = :idempotencyKey
                    """, base(principal).addValue("accountId", accountId).addValue("idempotencyKey", idempotencyKey), UUID.class);
        } else {
            auditWriter.record("NOTIFICATION_CREATED", "NOTIFICATION", id,
                    "{\"type\":\"" + type + "\"}");
        }
        return id;
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }
}
