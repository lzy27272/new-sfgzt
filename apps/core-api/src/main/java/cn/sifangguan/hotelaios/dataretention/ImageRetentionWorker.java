package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
public class ImageRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(ImageRetentionWorker.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext context;
    private final TransactionTemplate transaction;
    private final ImageFileCleanup files;
    private final boolean enabled;
    private final List<UUID> tenants;

    public ImageRetentionWorker(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext context,
            PlatformTransactionManager manager, ImageFileCleanup files,
            @Value("${app.data-retention.cleanup-worker-enabled:false}") boolean enabled,
            @Value("${app.data-retention.tenant-ids:}") String tenantIds) {
        this.jdbc = jdbc;
        this.context = context;
        this.files = files;
        this.enabled = enabled;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(15);
        this.tenants = tenantIds.isBlank() ? List.of() : java.util.Arrays.stream(tenantIds.split(","))
                .map(String::trim).map(UUID::fromString).distinct().toList();
        if (enabled && tenants.isEmpty()) throw new IllegalArgumentException("Image cleanup requires explicit tenant IDs");
    }

    public boolean enabledFor(UUID tenantId) { return enabled && tenants.contains(tenantId); }

    @Scheduled(fixedDelayString = "${app.data-retention.fixed-delay-ms:300000}", initialDelayString = "30000")
    public void tick() {
        if (!enabled) return;
        for (UUID tenantId : tenants) {
            try {
                int count = processTenant(tenantId);
                log.info("Image retention scan tenant={} deleted={} interval=300s", tenantId, count);
            } catch (RuntimeException error) {
                log.error("Image retention scan failed for tenant {}", tenantId, error);
            }
        }
    }

    public int processTenant(UUID tenantId) {
        if (!enabledFor(tenantId)) return 0;
        List<UUID> ids = transaction.execute(tx -> {
            context.apply(tenantId);
            return jdbc.queryForList("""
                    select o.id from image_retention_object o join data_retention_policy p using(tenant_id)
                    where o.tenant_id=:tenant and p.image_cleanup_enabled and not o.legal_hold
                      and o.state <> 'DELETED'
                      and o.uploaded_at <= now() - p.image_retention_days * interval '24 hours'
                      and (o.last_attempt_at is null or o.last_attempt_at < now() - interval '30 minutes')
                    order by o.uploaded_at,o.id limit 200
                    """, new MapSqlParameterSource("tenant", tenantId), UUID.class);
        });
        int deleted = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (Boolean.TRUE.equals(transaction.execute(tx -> deleteOne(tenantId, id)))) deleted++;
        }
        return deleted;
    }

    private boolean deleteOne(UUID tenantId, UUID id) {
        context.apply(tenantId);
        var params = new MapSqlParameterSource("tenant", tenantId).addValue("id", id);
        // The policy lock serializes a concurrent OFF switch; the object lock
        // serializes important-evidence marking. One small local file per tx.
        Boolean active = jdbc.queryForObject("select image_cleanup_enabled from data_retention_policy "
                + "where tenant_id=:tenant for share", params, Boolean.class);
        if (!Boolean.TRUE.equals(active)) return false;
        var rows = jdbc.queryForList("""
                select o.object_key,o.size_bytes,o.sha256 from image_retention_object o
                join data_retention_policy p using(tenant_id)
                where o.tenant_id=:tenant and o.id=:id and not o.legal_hold and o.state <> 'DELETED'
                  and o.uploaded_at <= now() - p.image_retention_days * interval '24 hours'
                for update of o skip locked
                """, params);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        params.addValue("key", row.get("object_key"));
        // An unclassified or non-image reference must never be silently removed.
        Long incompatible = jdbc.queryForObject("""
                select count(*) from (
                  select media_type from attachment where tenant_id=:tenant and object_key=:key
                  union all select media_type from task_evidence where tenant_id=:tenant and object_key=:key
                  union all select media_type from daily_report_evidence where tenant_id=:tenant and object_key=:key
                ) refs where media_type is null or media_type not like 'image/%'
                """, params, Long.class);
        try {
            if (incompatible != null && incompatible > 0) throw new IllegalStateException("NON_IMAGE_REFERENCE");
            boolean removed = files.delete(tenantId, (String) row.get("object_key"),
                    ((Number) row.get("size_bytes")).longValue(), ((String) row.get("sha256")).trim());
            jdbc.update("""
                    update image_retention_object set state='DELETED',deleted_at=now(),last_attempt_at=now(),
                      last_error=null,row_version=row_version+1 where tenant_id=:tenant and id=:id
                    """, params);
            jdbc.update("""
                    insert into audit_log(tenant_id,action,resource_type,resource_id,correlation_id,trace_id,
                                          outcome,sensitivity_level,after_data)
                    values(:tenant,'IMAGE_RETENTION_DELETED','IMAGE_RETENTION_OBJECT',:id,
                           :correlation,:correlation,'SUCCESS','INTERNAL',
                           jsonb_build_object('reason','UPLOAD_RETENTION_EXPIRED','fileRemoved',:removed))
                    """, params.addValue("correlation", UUID.randomUUID()).addValue("removed", removed));
            return true;
        } catch (java.io.IOException | IllegalStateException error) {
            recordFailure(params, error);
            return false;
        } catch (Exception error) {
            // SQL failures must roll back; retrying a missing file is idempotent.
            if (error instanceof org.springframework.dao.DataAccessException sql) throw sql;
            recordFailure(params, error);
            return false;
        }
    }

    private void recordFailure(MapSqlParameterSource params, Exception error) {
        String code = error.getClass().getSimpleName();
        if (error.getMessage() != null && error.getMessage().matches("[A-Z_]{1,120}")) code = error.getMessage();
        jdbc.update("""
                update image_retention_object set state='DELETE_FAILED',last_attempt_at=now(),
                  last_error=:error,row_version=row_version+1 where tenant_id=:tenant and id=:id
                """, params.addValue("error", code));
        log.warn("Image cleanup failed tenant={} object={} code={}", params.getValue("tenant"), params.getValue("id"), code);
    }
}
