package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EmployeePerformanceSnapshotService {
    static final String CALCULATION_VERSION = "employee-work-completion-v1";
    private static final Set<String> PERIOD_TYPES = Set.of("DAY", "MONTH");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditWriter auditWriter;

    public EmployeePerformanceSnapshotService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AuditWriter auditWriter
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditWriter = auditWriter;
    }

    /**
     * Captures the same daily and month-to-date employee metrics used by the
     * team workbench. Equal content is idempotent; changed facts or rule
     * versions append a new immutable revision.
     */
    @Transactional
    public CaptureResult captureWorkbench(
            TenantPrincipal principal,
            LocalDate asOfDate,
            OffsetDateTime cutoff,
            Collection<UUID> visibleAssignmentIds
    ) {
        List<UUID> assignmentIds = stableAssignmentIds(visibleAssignmentIds);
        if (assignmentIds.isEmpty()) {
            return new CaptureResult(0, 0);
        }

        // Lock the complete visible set once, before either DAY or MONTH
        // capture starts. This prevents requests for different dates/scopes
        // from interleaving period-specific locks in opposite orders.
        lockAssignments(principal, assignmentIds);

        int daily = capturePeriod(principal, "DAY", asOfDate, asOfDate, asOfDate, cutoff, assignmentIds);
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        int monthly = capturePeriod(
                principal,
                "MONTH",
                monthStart,
                monthStart.with(TemporalAdjusters.lastDayOfMonth()),
                asOfDate,
                cutoff,
                assignmentIds
        );
        if (daily + monthly > 0) {
            ObjectNode audit = objectMapper.createObjectNode()
                    .put("asOfDate", asOfDate.toString())
                    .put("dailyRevisionCount", daily)
                    .put("monthlyRevisionCount", monthly)
                    .put("calculationVersion", CALCULATION_VERSION);
            auditWriter.record(
                    "EMPLOYEE_PERFORMANCE_SNAPSHOTS_CAPTURED",
                    "EMPLOYEE_PERFORMANCE_SNAPSHOT",
                    principal.tenantId(),
                    audit.toString()
            );
        }
        return new CaptureResult(daily, monthly);
    }

    private int capturePeriod(
            TenantPrincipal principal,
            String periodType,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate factsThrough,
            OffsetDateTime cutoff,
            List<UUID> assignmentIds
    ) {
        if (!PERIOD_TYPES.contains(periodType)) {
            throw new IllegalArgumentException("不支持的员工指标快照周期");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("assignmentIds", assignmentIds)
                .addValue("factsFrom", periodStart)
                .addValue("factsThrough", factsThrough)
                .addValue("cutoff", cutoff);
        List<Aggregate> aggregates = jdbc.query("""
                select assignment.id as assignment_id,
                       assignment.employee_id,
                       assignment.org_unit_id,
                       count(distinct expectation.id) as expected_count,
                       count(distinct expectation.id) filter (where
                           first_record.submitted_at is not null
                           or expectation.status in ('SUBMITTED', 'SATISFIED')) as completed_count,
                       count(distinct expectation.id) filter (where
                           first_record.submitted_at is not null
                           and first_record.submitted_at <= expectation.due_at) as on_time_count,
                       count(distinct expectation.id) filter (where
                           first_record.submitted_at is not null
                           and first_record.submitted_at > expectation.due_at) as late_count,
                       count(distinct expectation.id) filter (where
                           expectation.status in ('MISSED', 'FAILED')
                           or (first_record.submitted_at is null
                               and expectation.status not in ('SUBMITTED', 'SATISFIED')
                               and expectation.due_at <= :cutoff)) as overdue_count,
                       count(distinct expectation.id) filter (where
                           expectation.status in ('MISSED', 'FAILED')) as missed_count,
                       count(distinct expectation.id) filter (where
                           latest_record.status = 'REJECTED') as rejected_count,
                       count(distinct expectation.id) filter (where
                           supplement.has_supplement) as supplemented_count,
                       count(distinct expectation.id) filter (where
                           coalesce(latest_record.attempt_no, 1) > 1
                           or latest_record.status = 'REJECTED') as remediation_count,
                       count(distinct expectation.id) filter (where
                           coalesce((item.submission_policy ->> 'attachmentRequired')::boolean, false))
                           as evidence_required_count,
                       count(distinct expectation.id) filter (where
                           coalesce((item.submission_policy ->> 'attachmentRequired')::boolean, false)
                           and evidence.has_evidence) as evidence_complete_count,
                       string_agg(distinct item.work_package_version_id::text, ','
                           order by item.work_package_version_id::text) as work_package_versions,
                       string_agg(distinct standard_link.standard_version_id::text, ','
                           order by standard_link.standard_version_id::text) as standard_versions
                from work_expectation expectation
                join work_package_item item
                  on item.tenant_id = expectation.tenant_id
                 and item.id = expectation.work_package_item_id
                join employee_position_assignment assignment
                  on assignment.tenant_id = expectation.tenant_id
                 and assignment.id = expectation.position_assignment_id
                left join work_package_item_standard standard_link
                  on standard_link.tenant_id = item.tenant_id
                 and standard_link.work_package_item_id = item.id
                left join lateral (
                    select min(record.submitted_at) as submitted_at
                    from work_record record
                    where record.tenant_id = expectation.tenant_id
                      and record.work_expectation_id = expectation.id
                      and record.submitted_at is not null
                ) first_record on true
                left join lateral (
                    select record.status, record.attempt_no
                    from work_record record
                    where record.tenant_id = expectation.tenant_id
                      and record.work_expectation_id = expectation.id
                    order by record.attempt_no desc, record.created_at desc
                    limit 1
                ) latest_record on true
                left join lateral (
                    select exists (
                        select 1
                        from work_record record
                        join work_record_supplement supplement_item
                          on supplement_item.tenant_id = record.tenant_id
                         and supplement_item.work_record_id = record.id
                        where record.tenant_id = expectation.tenant_id
                          and record.work_expectation_id = expectation.id
                    ) as has_supplement
                ) supplement on true
                left join lateral (
                    select exists (
                        select 1
                        from work_record record
                        join attachment attachment_item
                          on attachment_item.tenant_id = record.tenant_id
                         and attachment_item.work_record_id = record.id
                        where record.tenant_id = expectation.tenant_id
                          and record.work_expectation_id = expectation.id
                          and attachment_item.scan_status <> 'REJECTED'
                    ) as has_evidence
                ) evidence on true
                where expectation.tenant_id = :tenantId
                  and expectation.position_assignment_id in (:assignmentIds)
                  and expectation.business_date between :factsFrom and :factsThrough
                  and expectation.status not in ('WAIVED', 'CANCELLED')
                group by assignment.id, assignment.employee_id, assignment.org_unit_id
                order by assignment.id
                """, params, (rs, rowNum) -> new Aggregate(
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("org_unit_id", UUID.class),
                rs.getInt("expected_count"),
                rs.getInt("completed_count"),
                rs.getInt("on_time_count"),
                rs.getInt("late_count"),
                rs.getInt("overdue_count"),
                rs.getInt("missed_count"),
                rs.getInt("rejected_count"),
                rs.getInt("supplemented_count"),
                rs.getInt("remediation_count"),
                rs.getInt("evidence_required_count"),
                rs.getInt("evidence_complete_count"),
                rs.getString("work_package_versions"),
                rs.getString("standard_versions")
        ));

        int inserted = 0;
        for (Aggregate aggregate : aggregates) {
            inserted += appendRevisionIfChanged(
                    principal,
                    periodType,
                    periodStart,
                    periodEnd,
                    factsThrough,
                    aggregate
            );
        }
        return inserted;
    }

    private int appendRevisionIfChanged(
            TenantPrincipal principal,
            String periodType,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate factsThrough,
            Aggregate aggregate
    ) {
        MapSqlParameterSource identity = new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("assignmentId", aggregate.assignmentId())
                .addValue("employeeId", aggregate.employeeId())
                .addValue("periodType", periodType)
                .addValue("periodStart", periodStart);

        ArrayNode ruleVersions = ruleVersions(aggregate);
        ObjectNode metrics = objectMapper.createObjectNode()
                .put("businessDateThrough", factsThrough.toString())
                .put("evaluationBasis", "CURRENT_KNOWN_FACTS")
                .put("lateCount", aggregate.lateCount())
                .put("evidenceRequiredCount", aggregate.evidenceRequiredCount())
                .put("evidenceCompleteCount", aggregate.evidenceCompleteCount());
        String contentHash = sha256(periodType + '|' + periodStart + '|' + periodEnd + '|'
                + ruleVersions + '|' + CALCULATION_VERSION + '|' + aggregate.canonicalMetrics(metrics));

        List<PreviousRevision> previousRows = jdbc.query("""
                select id, revision_no, content_hash
                from employee_performance_snapshot
                where tenant_id = :tenantId
                  and employee_id = :employeeId
                  and position_assignment_id = :assignmentId
                  and period_type = :periodType
                  and period_start = :periodStart
                order by revision_no desc
                limit 1
                """, identity, (rs, rowNum) -> new PreviousRevision(
                rs.getObject("id", UUID.class),
                rs.getInt("revision_no"),
                rs.getString("content_hash").trim()
        ));
        PreviousRevision previous = previousRows.isEmpty() ? null : previousRows.getFirst();
        int revision = nextRevision(
                previous == null ? null : previous.contentHash(),
                previous == null ? 0 : previous.revisionNo(),
                contentHash
        );
        if (revision == 0) {
            return 0;
        }

        int expected = aggregate.expectedCount();
        BigDecimal completionRate = rate(aggregate.completedCount(), expected);
        BigDecimal onTimeRate = rate(aggregate.onTimeCount(), expected);
        BigDecimal evidenceRate = aggregate.evidenceRequiredCount() == 0
                ? new BigDecimal("100.0000")
                : rate(aggregate.evidenceCompleteCount(), aggregate.evidenceRequiredCount());
        BigDecimal remediationRate = rate(aggregate.remediationCount(), expected);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into employee_performance_snapshot
                    (id, tenant_id, employee_id, position_assignment_id, org_unit_id,
                     period_type, period_start, period_end, revision_no, supersedes_snapshot_id,
                     source_rule_versions, calculation_version,
                     expected_count, completed_count, on_time_count, overdue_count,
                     missed_count, rejected_count, supplemented_count, remediation_count,
                     completion_rate, on_time_rate, evidence_complete_rate, remediation_rate,
                     metric_snapshot, content_hash, generated_by)
                values
                    (:id, :tenantId, :employeeId, :assignmentId, :orgUnitId,
                     :periodType, :periodStart, :periodEnd, :revisionNo, :supersedesId,
                     cast(:ruleVersions as jsonb), :calculationVersion,
                     :expectedCount, :completedCount, :onTimeCount, :overdueCount,
                     :missedCount, :rejectedCount, :supplementedCount, :remediationCount,
                     :completionRate, :onTimeRate, :evidenceRate, :remediationRate,
                     cast(:metrics as jsonb), :contentHash, :actorId)
                """, identity
                .addValue("id", id)
                .addValue("orgUnitId", aggregate.orgUnitId())
                .addValue("periodEnd", periodEnd)
                .addValue("revisionNo", revision)
                .addValue("supersedesId", previous == null ? null : previous.id())
                .addValue("ruleVersions", ruleVersions.toString())
                .addValue("calculationVersion", CALCULATION_VERSION)
                .addValue("expectedCount", expected)
                .addValue("completedCount", aggregate.completedCount())
                .addValue("onTimeCount", aggregate.onTimeCount())
                .addValue("overdueCount", aggregate.overdueCount())
                .addValue("missedCount", aggregate.missedCount())
                .addValue("rejectedCount", aggregate.rejectedCount())
                .addValue("supplementedCount", aggregate.supplementedCount())
                .addValue("remediationCount", aggregate.remediationCount())
                .addValue("completionRate", completionRate)
                .addValue("onTimeRate", onTimeRate)
                .addValue("evidenceRate", evidenceRate)
                .addValue("remediationRate", remediationRate)
                .addValue("metrics", metrics.toString())
                .addValue("contentHash", contentHash)
                .addValue("actorId", principal.actorId()));
        return 1;
    }

    private ArrayNode ruleVersions(Aggregate aggregate) {
        ArrayNode versions = objectMapper.createArrayNode();
        appendRuleVersions(versions, "WORK_PACKAGE_VERSION", aggregate.workPackageVersions());
        appendRuleVersions(versions, "STANDARD_VERSION", aggregate.standardVersions());
        return versions;
    }

    private void appendRuleVersions(ArrayNode versions, String type, String commaSeparatedIds) {
        if (commaSeparatedIds == null || commaSeparatedIds.isBlank()) {
            return;
        }
        for (String id : commaSeparatedIds.split(",")) {
            versions.add(objectMapper.createObjectNode().put("type", type).put("id", id));
        }
    }

    private static BigDecimal rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private void lockAssignments(TenantPrincipal principal, List<UUID> assignmentIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", principal.tenantId());
        for (UUID assignmentId : assignmentIds) {
            jdbc.queryForObject("""
                    select id from employee_position_assignment
                    where tenant_id = :tenantId and id = :assignmentId
                    for update
                    """, params.addValue("assignmentId", assignmentId), UUID.class);
        }
    }

    static List<UUID> stableAssignmentIds(Collection<UUID> assignmentIds) {
        if (assignmentIds == null) {
            return List.of();
        }
        return assignmentIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
    }

    /** Returns zero when content is unchanged, otherwise the revision to append. */
    static int nextRevision(String previousHash, int previousRevision, String contentHash) {
        if (previousHash != null && previousHash.equals(contentHash)) {
            return 0;
        }
        return previousHash == null ? 1 : previousRevision + 1;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算员工指标快照摘要", exception);
        }
    }

    public record CaptureResult(int dailyRevisionCount, int monthlyRevisionCount) {
    }

    private record PreviousRevision(UUID id, int revisionNo, String contentHash) {
    }

    private record Aggregate(
            UUID assignmentId,
            UUID employeeId,
            UUID orgUnitId,
            int expectedCount,
            int completedCount,
            int onTimeCount,
            int lateCount,
            int overdueCount,
            int missedCount,
            int rejectedCount,
            int supplementedCount,
            int remediationCount,
            int evidenceRequiredCount,
            int evidenceCompleteCount,
            String workPackageVersions,
            String standardVersions
    ) {
        private String canonicalMetrics(ObjectNode supplementary) {
            return expectedCount + "|" + completedCount + "|" + onTimeCount + "|"
                    + lateCount + "|" + overdueCount + "|" + missedCount + "|"
                    + rejectedCount + "|" + supplementedCount + "|" + remediationCount + "|"
                    + evidenceRequiredCount + "|" + evidenceCompleteCount + "|" + supplementary;
        }
    }
}
