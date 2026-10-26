package cn.sifangguan.hotelaios.dailyreporttemplates;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class DailyReportDeliveryPolicyService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;

    public DailyReportDeliveryPolicyService(
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
    public DailyReportDeliveryPolicyModels.Policy get(UUID templateId, UUID versionId) {
        accessPolicy.requirePermission("daily-report-template.read");
        TenantPrincipal principal = prepare();
        return requirePolicy(principal, templateId, versionId);
    }

    @Transactional
    public DailyReportDeliveryPolicyModels.Policy update(
            UUID templateId,
            UUID versionId,
            DailyReportDeliveryPolicyModels.UpdatePolicy request
    ) {
        TenantPrincipal principal = prepare();
        if (!principal.hasPermission("daily-report-template.manage")
                && !principal.hasPermission("daily-report-template.publish")
                && !principal.hasPermission("*")) {
            throw new AccessDeniedException("缺少日报投递策略维护权限");
        }
        if (!principal.hasTenantScope()) {
            throw new AccessDeniedException("总部日报投递策略只能由总部模板管理员维护");
        }
        DailyReportDeliveryPolicyModels.Policy current = requirePolicy(principal, templateId, versionId);
        if (current.deliveryTiming() != null && (!current.openLocalTime().equals(request.openLocalTime())
                || !current.dueLocalTime().equals(request.dueLocalTime()) || request.graceMinutes() != 0)) {
            throw new IllegalArgumentException("该版本按模板deliveryTiming执行，须修改并发布新模板版本调整时间；不得用旧固定时钟覆盖实际班次");
        }
        List<Integer> preDue = normalizeOffsets(request.preDueReminderMinutes());
        List<Integer> overdue = normalizeOffsets(request.overdueReminderMinutes());
        int updated = jdbc.update("""
                update daily_report_delivery_policy policy
                set enabled = :enabled,
                    open_local_time = :openLocalTime,
                    due_local_time = :dueLocalTime,
                    grace_minutes = :graceMinutes,
                    pre_due_reminder_minutes = cast(:preDueReminderMinutes as integer[]),
                    overdue_reminder_minutes = cast(:overdueReminderMinutes as integer[]),
                    backfill_days = :backfillDays,
                    updated_by = :actorId,
                    row_version = row_version + 1
                where policy.tenant_id = :tenantId and policy.id = :policyId
                  and policy.row_version = :expectedVersion
                """, base(principal)
                .addValue("policyId", current.id())
                .addValue("enabled", request.enabled())
                .addValue("openLocalTime", request.openLocalTime())
                .addValue("dueLocalTime", request.dueLocalTime())
                .addValue("graceMinutes", request.graceMinutes())
                .addValue("preDueReminderMinutes", postgresArray(preDue))
                .addValue("overdueReminderMinutes", postgresArray(overdue))
                .addValue("backfillDays", request.backfillDays())
                .addValue("actorId", principal.actorId())
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "日报投递策略已被其他请求修改，请刷新后重试");
        }
        DailyReportDeliveryPolicyModels.Policy response = requirePolicy(principal, templateId, versionId);
        auditWriter.record(
                "DAILY_REPORT_DELIVERY_POLICY_UPDATED",
                "DAILY_REPORT_DELIVERY_POLICY",
                response.id(),
                "{\"templateVersionId\":\"" + versionId + "\",\"enabled\":" + response.enabled() + "}");
        return response;
    }

    private DailyReportDeliveryPolicyModels.Policy requirePolicy(
            TenantPrincipal principal,
            UUID templateId,
            UUID versionId
    ) {
        List<DailyReportDeliveryPolicyModels.Policy> rows = jdbc.query("""
                select policy.id, policy.template_assignment_id,
                       definition.id as template_id, version.id as template_version_id,
                       policy.enabled, policy.open_local_time, policy.due_local_time,
                       policy.grace_minutes, policy.pre_due_reminder_minutes,
                       policy.overdue_reminder_minutes, policy.backfill_days,
                       policy.row_version, policy.created_at, policy.updated_at,
                       (version.configuration -> 'deliveryTiming')::text as delivery_timing
                from daily_report_delivery_policy policy
                join daily_report_template_assignment assignment
                  on assignment.tenant_id = policy.tenant_id
                 and assignment.id = policy.template_assignment_id
                join daily_report_template_version version
                  on version.tenant_id = assignment.tenant_id
                 and version.id = assignment.template_version_id
                join daily_report_template_definition definition
                  on definition.tenant_id = version.tenant_id
                 and definition.id = version.template_id
                where policy.tenant_id = :tenantId
                  and definition.id = :templateId and version.id = :versionId
                  and definition.template_origin = 'HQ'
                  and assignment.assignment_kind = 'BASE'
                order by assignment.priority, assignment.created_at
                """, base(principal)
                .addValue("templateId", templateId)
                .addValue("versionId", versionId),
                (rs, rowNum) -> new DailyReportDeliveryPolicyModels.Policy(
                        rs.getObject("id", UUID.class),
                        rs.getObject("template_assignment_id", UUID.class),
                        rs.getObject("template_id", UUID.class),
                        rs.getObject("template_version_id", UUID.class),
                        rs.getBoolean("enabled"),
                        rs.getObject("open_local_time", LocalTime.class),
                        rs.getObject("due_local_time", LocalTime.class),
                        rs.getInt("grace_minutes"),
                        integerList(rs.getArray("pre_due_reminder_minutes")),
                        integerList(rs.getArray("overdue_reminder_minutes")),
                        rs.getInt("backfill_days"),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        parseTiming(rs.getString("delivery_timing"))
                ));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "该已发布总部日报模板尚未配置投递策略");
        }
        if (rows.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "该日报模板版本存在多条基础投递策略，请先修复模板分配");
        }
        return rows.getFirst();
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private static com.fasterxml.jackson.databind.JsonNode parseTiming(String raw) {
        if (raw == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("日报时间配置无效", exception);
        }
    }

    private static List<Integer> normalizeOffsets(List<Integer> values) {
        return values.stream().distinct().sorted().toList();
    }

    private static String postgresArray(List<Integer> values) {
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }

    private static List<Integer> integerList(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object raw = sqlArray.getArray();
            if (raw instanceof Integer[] values) {
                return List.copyOf(Arrays.asList(values));
            }
            if (raw instanceof Object[] values) {
                return Arrays.stream(values).map(value -> ((Number) value).intValue()).toList();
            }
            return List.of();
        } finally {
            sqlArray.free();
        }
    }

    private static MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }
}
