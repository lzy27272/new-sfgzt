package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in delivery worker over the existing notification_delivery queue.
 * Application chats are selected only for explicitly shareable management tasks
 * through active wecom_chat_binding rows whose allowlist contains
 * TASK_NOTIFICATION. Work expectations, daily reports and work-record reviews
 * are always delivered directly to the bound member.
 */
@Component
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.wecom.worker.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComDeliveryWorker {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final TransactionTemplate transactions;
    private final WeComApiClient apiClient;
    private final WeComProperties properties;
    private final int batchSize;
    private final String workerId = "wecom-" + UUID.randomUUID();

    public WeComDeliveryWorker(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            TransactionTemplate transactions,
            WeComApiClient apiClient,
            WeComProperties properties,
            @Value("${app.wecom.worker.batch-size:50}") int batchSize
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.transactions = transactions;
        this.apiClient = apiClient;
        this.properties = properties;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
    }

    @Scheduled(
            fixedDelayString = "${app.wecom.worker.fixed-delay-ms:30000}",
            initialDelayString = "${app.wecom.worker.initial-delay-ms:30000}"
    )
    public void run() {
        enqueueTaskNotifications();
        for (Delivery delivery : claim()) {
            try {
                boolean groupDelivery = useGroupChat(delivery.sourceType(), delivery.chatId());
                String endpoint = groupDelivery
                        ? "chat:" + delivery.chatId() : "user:" + delivery.userId();
                if (!groupDelivery && (delivery.userId() == null || delivery.userId().isBlank())) {
                    throw new IllegalStateException("WeCom personal delivery has no active user binding");
                }
                if (!MessageDigest.isEqual(
                        delivery.endpointHash().getBytes(StandardCharsets.US_ASCII),
                        sha256(properties.corpId() + ":" + endpoint).getBytes(StandardCharsets.US_ASCII))) {
                    throw new IllegalStateException("WeCom delivery endpoint binding changed");
                }
                URI deepLink = oauthStartLink(delivery.sourceType(), delivery.sourceId(), delivery.notificationType());
                String externalMessageId = groupDelivery
                        ? apiClient.sendApplicationChatTaskLink(delivery.chatId(), delivery.title(), delivery.content(), deepLink)
                        : apiClient.sendApplicationTaskLink(delivery.userId(), delivery.title(), delivery.content(), deepLink);
                markSent(delivery.id(), externalMessageId);
            } catch (RuntimeException exception) {
                markFailed(delivery.id(), delivery.attemptCount() + 1, exception.getClass().getSimpleName());
            }
        }
    }

    private void enqueueTaskNotifications() {
        transactions.executeWithoutResult(status -> {
            prepare();
            List<PendingNotification> rows = jdbc.query("""
                    select n.id, n.recipient_account_id, n.source_type, ub.wecom_user_id,
                           chat.chat_id
                    from notification n
                    left join management_task task
                      on n.source_type = 'TASK' and task.tenant_id = n.tenant_id and task.id = n.source_id
                    left join work_expectation expectation
                      on n.source_type = 'WORK_EXPECTATION'
                     and expectation.tenant_id = n.tenant_id and expectation.id = n.source_id
                    left join daily_report report
                      on n.source_type = 'DAILY_REPORT'
                     and report.tenant_id = n.tenant_id and report.id = n.source_id
                    left join work_record record
                      on n.source_type = 'WORK_RECORD'
                     and record.tenant_id = n.tenant_id and record.id = n.source_id
                    left join lateral (
                        select count(*)::integer as pending_count
                        from daily_report_item_result result
                        join daily_report_template_item template_item
                          on template_item.tenant_id = result.tenant_id
                         and template_item.id = result.template_item_id
                        where result.tenant_id = report.tenant_id
                          and result.revision_id = report.current_revision_id
                          and result.result_status = 'PENDING'
                          and result.system_prefilled
                          and result.source_summary ->> 'sourceType' = 'WORK_RECORD'
                          and exists (
                            select 1
                            from work_package_item_responsibility responsibility
                            where responsibility.tenant_id = template_item.tenant_id
                              and responsibility.work_package_item_id = template_item.work_package_item_id
                              and responsibility.participant_type in ('REVIEWER', 'ACCEPTOR')
                              and responsibility.resolver_type = 'DIRECT_MANAGER_ASSIGNMENT'
                          )
                    ) review_state on true
                    left join wecom_user_binding ub
                      on ub.tenant_id = n.tenant_id and ub.corp_id = :corpId
                     and ub.account_id = n.recipient_account_id and ub.status = 'ACTIVE'
                    left join lateral (
                        select cb.chat_id
                        from wecom_chat_binding cb
                        where cb.tenant_id = n.tenant_id and cb.corp_id = :corpId
                          and cb.org_unit_id = task.org_unit_id and cb.status = 'ACTIVE'
                          and jsonb_exists(cb.allowed_actions, 'TASK_NOTIFICATION')
                          and case
                                when jsonb_typeof(task.source_snapshot #> '{delivery,wecomGroupShareable}') = 'boolean'
                                then (task.source_snapshot #>> '{delivery,wecomGroupShareable}')::boolean
                                else false
                              end
                        order by cb.created_at, cb.id
                        limit 1
                    ) chat on true
                    where n.tenant_id = :tenantId
                      and ((n.source_type = 'TASK' and task.id is not null)
                           or (n.source_type = 'WORK_EXPECTATION' and expectation.id is not null)
                           or (n.source_type = 'DAILY_REPORT' and report.id is not null
                               and (n.notification_type <> 'DAILY_REPORT_REVIEW_PENDING'
                                    or (review_state.pending_count > 0
                                        and n.idempotency_key = concat(
                                            'daily-report:review-pending:', report.id::text,
                                            ':', review_state.pending_count::text))))
                           or (n.source_type = 'WORK_RECORD' and record.id is not null
                               and (n.notification_type <> 'WORK_RECORD_REVIEW_PENDING'
                                    or record.status = 'SUBMITTED')))
                      and (ub.id is not null
                           or (n.source_type = 'TASK' and chat.chat_id is not null))
                      and not exists (
                          select 1 from notification_delivery d
                          where d.tenant_id = n.tenant_id and d.notification_id = n.id and d.channel = 'WECHAT'
                      )
                    order by n.created_at, n.id
                    limit :batchSize
                    """, params().addValue("corpId", properties.corpId()).addValue("batchSize", batchSize),
                    (rs, rowNum) -> new PendingNotification(
                            rs.getObject("id", UUID.class), rs.getObject("recipient_account_id", UUID.class),
                            rs.getString("source_type"), rs.getString("wecom_user_id"),
                            rs.getString("chat_id")));
            for (PendingNotification row : rows) {
                boolean groupDelivery = useGroupChat(row.sourceType(), row.chatId());
                String endpoint = groupDelivery ? "chat:" + row.chatId() : "user:" + row.userId();
                jdbc.update("""
                        insert into notification_delivery
                            (tenant_id, notification_id, channel, recipient_endpoint_hash, status)
                        values (:tenantId, :notificationId, 'WECHAT', :endpointHash, 'PENDING')
                        on conflict do nothing
                        """, params().addValue("notificationId", row.notificationId())
                        .addValue("endpointHash", sha256(properties.corpId() + ":" + endpoint)));
            }
        });
    }

    private List<Delivery> claim() {
        return transactions.execute(status -> {
            prepare();
            List<Delivery> rows = jdbc.query("""
                    select d.id, d.attempt_count, d.recipient_endpoint_hash,
                           n.title, n.content, n.source_type, n.source_id, n.notification_type,
                           ub.wecom_user_id, chat.chat_id
                    from notification_delivery d
                    join notification n on n.tenant_id = d.tenant_id and n.id = d.notification_id
                    left join management_task task
                      on n.source_type = 'TASK' and task.tenant_id = n.tenant_id and task.id = n.source_id
                    left join work_expectation expectation
                      on n.source_type = 'WORK_EXPECTATION'
                     and expectation.tenant_id = n.tenant_id and expectation.id = n.source_id
                    left join daily_report report
                      on n.source_type = 'DAILY_REPORT'
                     and report.tenant_id = n.tenant_id and report.id = n.source_id
                    left join work_record record
                      on n.source_type = 'WORK_RECORD'
                     and record.tenant_id = n.tenant_id and record.id = n.source_id
                    left join lateral (
                        select count(*)::integer as pending_count
                        from daily_report_item_result result
                        join daily_report_template_item template_item
                          on template_item.tenant_id = result.tenant_id
                         and template_item.id = result.template_item_id
                        where result.tenant_id = report.tenant_id
                          and result.revision_id = report.current_revision_id
                          and result.result_status = 'PENDING'
                          and result.system_prefilled
                          and result.source_summary ->> 'sourceType' = 'WORK_RECORD'
                          and exists (
                            select 1
                            from work_package_item_responsibility responsibility
                            where responsibility.tenant_id = template_item.tenant_id
                              and responsibility.work_package_item_id = template_item.work_package_item_id
                              and responsibility.participant_type in ('REVIEWER', 'ACCEPTOR')
                              and responsibility.resolver_type = 'DIRECT_MANAGER_ASSIGNMENT'
                          )
                    ) review_state on true
                    left join wecom_user_binding ub
                      on ub.tenant_id = n.tenant_id and ub.corp_id = :corpId
                     and ub.account_id = n.recipient_account_id and ub.status = 'ACTIVE'
                    left join lateral (
                        select cb.chat_id
                        from wecom_chat_binding cb
                        where cb.tenant_id = n.tenant_id and cb.corp_id = :corpId
                          and cb.org_unit_id = task.org_unit_id and cb.status = 'ACTIVE'
                          and jsonb_exists(cb.allowed_actions, 'TASK_NOTIFICATION')
                          and case
                                when jsonb_typeof(task.source_snapshot #> '{delivery,wecomGroupShareable}') = 'boolean'
                                then (task.source_snapshot #>> '{delivery,wecomGroupShareable}')::boolean
                                else false
                              end
                        order by cb.created_at, cb.id limit 1
                    ) chat on true
                    where d.tenant_id = :tenantId and d.channel = 'WECHAT'
                      and ((n.source_type = 'TASK' and task.id is not null)
                           or (n.source_type = 'WORK_EXPECTATION' and expectation.id is not null)
                           or (n.source_type = 'DAILY_REPORT' and report.id is not null
                               and (n.notification_type <> 'DAILY_REPORT_REVIEW_PENDING'
                                    or (review_state.pending_count > 0
                                        and n.idempotency_key = concat(
                                            'daily-report:review-pending:', report.id::text,
                                            ':', review_state.pending_count::text))))
                           or (n.source_type = 'WORK_RECORD' and record.id is not null
                               and (n.notification_type <> 'WORK_RECORD_REVIEW_PENDING'
                                    or record.status = 'SUBMITTED')))
                      and d.status in ('PENDING', 'FAILED')
                      and d.available_at <= now() and (d.next_retry_at is null or d.next_retry_at <= now())
                      and (d.locked_until is null or d.locked_until < now())
                      and (ub.id is not null
                           or (n.source_type = 'TASK' and chat.chat_id is not null))
                    order by d.available_at, d.created_at, d.id
                    for update of d skip locked
                    limit :batchSize
                    """, params().addValue("corpId", properties.corpId()).addValue("batchSize", batchSize),
                    (rs, rowNum) -> new Delivery(
                            rs.getObject("id", UUID.class), rs.getInt("attempt_count"),
                            rs.getString("title"), rs.getString("content"),
                            rs.getString("source_type"), rs.getObject("source_id", UUID.class), rs.getString("notification_type"),
                            rs.getString("recipient_endpoint_hash"),
                            rs.getString("wecom_user_id"),
                            rs.getString("chat_id")));
            for (Delivery row : rows) {
                jdbc.update("""
                        update notification_delivery
                        set locked_by = :workerId, locked_until = now() + interval '2 minutes'
                        where tenant_id = :tenantId and id = :id
                        """, params().addValue("id", row.id()).addValue("workerId", workerId));
            }
            return rows;
        });
    }

    private void markSent(UUID id, String externalMessageId) {
        transactions.executeWithoutResult(status -> {
            prepare();
            jdbc.update("""
                    update notification_delivery
                    set status = 'SENT', attempt_count = attempt_count + 1, sent_at = now(),
                        next_retry_at = null, locked_by = null, locked_until = null,
                        last_error = null, provider_message_id = :messageId,
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id and locked_by = :workerId
                    """, params().addValue("id", id).addValue("workerId", workerId)
                    .addValue("messageId", externalMessageId));
        });
    }

    private void markFailed(UUID id, int attempts, String errorType) {
        long retryMinutes = Math.min(1440, 1L << Math.min(attempts, 10));
        transactions.executeWithoutResult(status -> {
            prepare();
            jdbc.update("""
                    update notification_delivery
                    set status = 'FAILED', attempt_count = attempt_count + 1, failed_at = now(),
                        next_retry_at = :nextRetryAt, locked_by = null, locked_until = null,
                        last_error = :errorType, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id and locked_by = :workerId
                    """, params().addValue("id", id).addValue("workerId", workerId)
                    .addValue("nextRetryAt", OffsetDateTime.now().plusMinutes(retryMinutes))
                    .addValue("errorType", errorType));
        });
    }

    private URI oauthStartLink(String sourceType, UUID sourceId, String notificationType) {
        URI callback = properties.oauthCallbackUrl();
        String returnTo = returnToFor(sourceType, sourceId, notificationType);
        URI start = UriComponentsBuilder.newInstance().scheme(callback.getScheme()).host(callback.getHost())
                .port(callback.getPort()).path("/api/v1/integrations/wecom/oauth/start")
                .queryParam("returnTo", returnTo)
                .build().encode().toUri();
        return start;
    }

    static String returnToFor(String sourceType, UUID sourceId) {
        return returnToFor(sourceType, sourceId, null);
    }

    static String returnToFor(String sourceType, UUID sourceId, String notificationType) {
        if (sourceId == null) {
            throw new IllegalArgumentException("WeCom notification source id is required");
        }
        if (("WORK_EXPECTATION".equals(sourceType) && "WORK_EXPECTATION_SUPERVISION".equals(notificationType))
                || ("TASK".equals(sourceType) && ("TASK_OVERDUE".equals(notificationType)
                    || "TASK_ESCALATED".equals(notificationType)))) {
            return "#/workbench";
        }
        return switch (sourceType) {
            case "TASK" -> "#/tasks?view=mine&taskId=" + sourceId;
            case "WORK_EXPECTATION" -> "#/my-work?expectationId=" + sourceId;
            case "DAILY_REPORT" -> "#/daily-reports/" + sourceId;
            // The OAuth return-target allowlist intentionally does not admit team-work yet.
            // Keep review reminders usable without widening that security boundary here; the
            // reviewer lands on the authenticated workbench and can open 团队工作 from there.
            case "WORK_RECORD" -> "#/workbench";
            default -> throw new IllegalArgumentException("Unsupported WeCom notification source type");
        };
    }

    static boolean useGroupChat(String sourceType, String chatId) {
        return "TASK".equals(sourceType) && chatId != null && !chatId.isBlank();
    }

    private void prepare() { databaseContext.apply(properties.tenantId()); }
    private MapSqlParameterSource params() { return new MapSqlParameterSource("tenantId", properties.tenantId()); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record PendingNotification(UUID notificationId, UUID accountId, String sourceType,
                                       String userId, String chatId) { }
    private record Delivery(UUID id, int attemptCount, String title, String content,
                            String sourceType, UUID sourceId, String notificationType,
                            String endpointHash, String userId, String chatId) { }
}
