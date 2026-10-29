package cn.sifangguan.hotelaios.notifications;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy;
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
                select n.id, n.notification_type, n.title,
                       case when n.notification_type='DAILY_REPORT_REVIEW_PENDING' and n.source_type='DAILY_REPORT'
                           and n.idempotency_key like 'daily-report:review-pending:%'
                         then concat(report.business_date,' 当前共有 ',review_state.pending_count,
                           ' 项普通工作待复核，请查看日报来源记录。') else n.content end as content,
                       n.source_type, n.source_id,
                       n.recipient_assignment_id, n.created_at, n.delivered_at, n.read_at, n.row_version,
                       case when exists (
                           select 1 from notification_delivery delivery
                           where delivery.tenant_id=n.tenant_id and delivery.notification_id=n.id
                             and delivery.channel='WECHAT' and delivery.status='SENT') then 'SENT'
                         when not exists (
                           select 1 from wecom_user_binding binding
                           where binding.tenant_id=n.tenant_id
                             and binding.account_id=n.recipient_account_id
                             and binding.status='ACTIVE') then 'UNBOUND'
                         else coalesce((select delivery.status from notification_delivery delivery
                           where delivery.tenant_id=n.tenant_id and delivery.notification_id=n.id
                             and delivery.channel='WECHAT'
                           order by delivery.created_at desc,delivery.id desc limit 1),'NOT_QUEUED')
                       end as wecom_delivery_status
                from notification n
                left join work_record review_record
                  on n.source_type='WORK_RECORD' and review_record.tenant_id=n.tenant_id and review_record.id=n.source_id
                left join work_package_item review_item
                  on review_item.tenant_id=review_record.tenant_id and review_item.id=review_record.work_package_item_id
                left join work_expectation review_expectation
                  on review_expectation.tenant_id=review_record.tenant_id and review_expectation.id=review_record.work_expectation_id
                left join daily_report report
                  on n.source_type='DAILY_REPORT' and report.tenant_id=n.tenant_id and report.id=n.source_id
                left join lateral (
                  select count(*) as pending_count from work_record pending_record
                  left join work_package_item pending_item
                    on pending_item.tenant_id=pending_record.tenant_id and pending_item.id=pending_record.work_package_item_id
                  left join work_expectation pending_expectation
                    on pending_expectation.tenant_id=pending_record.tenant_id and pending_expectation.id=pending_record.work_expectation_id
                  where n.notification_type='DAILY_REPORT_REVIEW_PENDING' and n.idempotency_key like 'daily-report:review-pending:%'
                    and pending_record.tenant_id=report.tenant_id and pending_record.position_assignment_id=report.position_assignment_id
                    and pending_record.business_date=report.business_date and pending_record.status='SUBMITTED'
                    and coalesce(
                """ + WorkReviewPolicy.effectiveModeSql("pending_item", "pending_expectation") + """
                    ,'MANUAL')<>'NONE'
                    and exists (select 1 from work_package_item_responsibility responsibility
                      where responsibility.tenant_id=pending_record.tenant_id and responsibility.work_package_item_id=pending_record.work_package_item_id
                        and responsibility.participant_type in ('REVIEWER','ACCEPTOR') and responsibility.resolver_type='DIRECT_MANAGER_ASSIGNMENT')
                    and not exists (select 1 from store_work_usage usage
                      where usage.tenant_id=pending_record.tenant_id and usage.work_record_id=pending_record.id)
                ) review_state on true
                where n.tenant_id = :tenantId and n.recipient_account_id = :actorId
                  and (:unreadOnly = false or n.read_at is null)
                  and (n.notification_type<>'WORK_RECORD_REVIEW_PENDING' or n.source_type<>'WORK_RECORD'
                    or (review_record.status='SUBMITTED' and coalesce(
                """ + WorkReviewPolicy.effectiveModeSql("review_item", "review_expectation") + """
                    ,'MANUAL')<>'NONE'))
                  and (n.notification_type<>'DAILY_REPORT_REVIEW_PENDING' or n.source_type<>'DAILY_REPORT'
                    or n.idempotency_key not like 'daily-report:review-pending:%' or review_state.pending_count>0)
                order by n.delivered_at desc
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
