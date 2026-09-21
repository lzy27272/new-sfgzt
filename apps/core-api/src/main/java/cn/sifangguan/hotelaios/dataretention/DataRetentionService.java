package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

@Service
public class DataRetentionService {
    private static final Set<String> RETENTION_ADMIN_ROLES = Set.of("PLATFORM_ADMIN", "GROUP_ADMIN");

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public DataRetentionService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DataRetentionModels.Policy policy() {
        TenantPrincipal principal = prepareRetentionAdmin();
        ensurePolicy(principal);
        return loadPolicy(principal);
    }

    @Transactional
    public DataRetentionModels.Policy updatePolicy(DataRetentionModels.UpdatePolicy request) {
        TenantPrincipal principal = prepareRetentionAdmin();
        ensurePolicy(principal);
        int changed = jdbc.update("""
                update data_retention_policy
                set image_retention_days = :imageRetentionDays,
                    document_retention_days = :documentRetentionDays,
                    employee_metric_retention_months = :employeeMetricRetentionMonths,
                    pms_retention_months = :pmsRetentionMonths,
                    ota_retention_months = :otaRetentionMonths,
                    kpi_retention_months = :kpiRetentionMonths,
                    raw_payload_retention_days = :rawPayloadRetentionDays,
                    image_cleanup_enabled = :imageCleanupEnabled,
                    updated_by = :actorId,
                    row_version = row_version + 1
                where tenant_id = :tenantId and row_version = :expectedVersion
                """, base(principal)
                .addValue("actorId", principal.actorId())
                .addValue("imageRetentionDays", request.imageRetentionDays())
                .addValue("documentRetentionDays", request.documentRetentionDays())
                .addValue("employeeMetricRetentionMonths", request.employeeMetricRetentionMonths())
                .addValue("pmsRetentionMonths", request.pmsRetentionMonths())
                .addValue("otaRetentionMonths", request.otaRetentionMonths())
                .addValue("kpiRetentionMonths", request.kpiRetentionMonths())
                .addValue("rawPayloadRetentionDays", request.rawPayloadRetentionDays())
                .addValue("imageCleanupEnabled", request.imageCleanupEnabled())
                .addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数据留存策略版本已变化，请刷新后重试");
        }

        jdbc.update("""
                update attachment a set retention_basis_at=o.uploaded_at,
                    retention_until=o.uploaded_at + :days * interval '24 hours'
                from image_retention_object o
                where a.tenant_id=:tenantId and o.tenant_id=a.tenant_id and o.object_key=a.object_key
                  and a.media_type like 'image/%' and a.deleted_at is null
                  and a.retention_until is distinct from o.uploaded_at + :days * interval '24 hours'
                """,base(principal).addValue("days",request.imageRetentionDays()));

        DataRetentionModels.Policy result = loadPolicy(principal);
        auditWriter.record(
                "DATA_RETENTION_POLICY_UPDATED",
                "DATA_RETENTION_POLICY",
                principal.tenantId(),
                objectMapper.valueToTree(Map.of(
                        "imageRetentionDays", result.imageRetentionDays(),
                        "documentRetentionDays", result.documentRetentionDays(),
                        "employeeMetricRetentionMonths", result.employeeMetricRetentionMonths(),
                        "pmsRetentionMonths", result.pmsRetentionMonths(),
                        "otaRetentionMonths", result.otaRetentionMonths(),
                        "kpiRetentionMonths", result.kpiRetentionMonths(),
                        "rawPayloadRetentionDays", result.rawPayloadRetentionDays(),
                        "imageCleanupEnabled", result.imageCleanupEnabled(),
                        "version", result.version()
                )).toString()
        );
        return result;
    }

    @Transactional
    public DataRetentionModels.CleanupPreview cleanupPreview() {
        TenantPrincipal principal = prepareRetentionAdmin();
        ensurePolicy(principal);
        DataRetentionModels.Policy policy = loadPolicy(principal);
        return jdbc.queryForObject("""
                select count(*) as candidate_count,
                       coalesce(sum(attachment_item.size_bytes), 0) as total_size_bytes,
                       min(attachment_item.uploaded_at + policy.image_retention_days * interval '24 hours') as oldest_retention_until,
                       max(attachment_item.uploaded_at + policy.image_retention_days * interval '24 hours') as newest_retention_until
                from image_retention_object attachment_item
                join data_retention_policy policy using (tenant_id)
                where attachment_item.tenant_id = :tenantId
                  and attachment_item.state <> 'DELETED'
                  and attachment_item.legal_hold = false
                  and attachment_item.uploaded_at <= now() - policy.image_retention_days * interval '24 hours'
                """, base(principal), (rs, rowNum) -> new DataRetentionModels.CleanupPreview(
                rs.getLong("candidate_count"),
                rs.getLong("total_size_bytes"),
                rs.getObject("oldest_retention_until", OffsetDateTime.class),
                rs.getObject("newest_retention_until", OffsetDateTime.class),
                OffsetDateTime.now(ZoneOffset.UTC),
                policy.imageCleanupEnabled()
        ));
    }

    private TenantPrincipal prepareRetentionAdmin() {
        TenantPrincipal principal = accessPolicy.principal();
        if (principal.roleCodes().stream().noneMatch(RETENTION_ADMIN_ROLES::contains)) {
            throw new AccessDeniedException("数据留存策略仅允许平台管理员或集团管理员维护");
        }
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private void ensurePolicy(TenantPrincipal principal) {
        jdbc.update("""
                insert into data_retention_policy (tenant_id)
                values (:tenantId)
                on conflict (tenant_id) do nothing
                """, base(principal));
    }

    private DataRetentionModels.Policy loadPolicy(TenantPrincipal principal) {
        return jdbc.queryForObject("""
                select image_retention_days, document_retention_days,
                       employee_metric_retention_months, pms_retention_months,
                       ota_retention_months, kpi_retention_months,
                       raw_payload_retention_days, image_cleanup_enabled,
                       row_version, updated_at, updated_by
                from data_retention_policy
                where tenant_id = :tenantId
                """, base(principal), (rs, rowNum) -> new DataRetentionModels.Policy(
                rs.getInt("image_retention_days"),
                rs.getInt("document_retention_days"),
                rs.getInt("employee_metric_retention_months"),
                rs.getInt("pms_retention_months"),
                rs.getInt("ota_retention_months"),
                rs.getInt("kpi_retention_months"),
                rs.getInt("raw_payload_retention_days"),
                rs.getBoolean("image_cleanup_enabled"),
                rs.getLong("row_version"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("updated_by", java.util.UUID.class)
        ));
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }
}
