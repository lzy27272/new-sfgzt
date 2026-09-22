package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.dailyreporttemplates.DailyReportTemplateSnapshotReader;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Projects submitted and approved routine work into a still-editable daily report. The
 * projection is fact-only and idempotent: unreviewed work remains pending, rejected work
 * is removed from the draft projection, and manually entered "other work" is untouched.
 */
@Service
@Transactional
public class DailyReportRoutineProjectionService {
    private static final String RULE_PROVIDER = "INTERNAL_RULE_ENGINE";
    private static final int MAX_CONTENT_ISSUES = 6;
    private static final int MAX_DECLARED_ACTIONS = 4;
    private static final int MAX_FACT_LENGTH = 180;
    private static final int MAX_FORM_DEPTH = 4;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final ObjectMapper objectMapper;
    private final DailyReportTemplateSnapshotReader snapshotReader;
    private final AuditWriter auditWriter;

    public DailyReportRoutineProjectionService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            ObjectMapper objectMapper,
            DailyReportTemplateSnapshotReader snapshotReader,
            AuditWriter auditWriter
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.objectMapper = objectMapper;
        this.snapshotReader = snapshotReader;
        this.auditWriter = auditWriter;
    }

    public void projectApprovedWorkRecord(UUID workRecordId) {
        projectWorkRecord(workRecordId);
    }

    public void projectWorkRecord(UUID workRecordId) {
        TenantPrincipal principal = prepare();
        List<Map<String, Object>> reports = jdbc.queryForList("""
                select distinct report.id as report_id, report.current_revision_id as revision_id
                from work_record record
                join work_package_item record_package_item
                  on record_package_item.tenant_id = record.tenant_id
                 and record_package_item.id = record.work_package_item_id
                join daily_report report
                  on report.tenant_id = record.tenant_id
                 and report.position_assignment_id = record.position_assignment_id
                 and report.business_date = record.business_date
                 and report.report_status = 'DRAFT'
                where record.tenant_id = :tenantId and record.id = :recordId
                  and record.status in ('SUBMITTED', 'APPROVED', 'REJECTED')
                  and report.current_revision_id is not null
                  and exists (
                      select 1 from org_unit_closure hotel_scope
                      where hotel_scope.tenant_id = report.tenant_id
                        and hotel_scope.ancestor_id = report.hotel_org_unit_id
                        and hotel_scope.descendant_id = record.target_org_unit_id
                  )
                  and exists (
                      select 1
                      from daily_report_template_section relation
                      join daily_report_template_item item
                        on item.tenant_id = relation.tenant_id
                       and item.section_version_id = relation.section_version_id
                       and item.work_package_item_id is not null
                      join work_package_item template_package_item
                        on template_package_item.tenant_id = item.tenant_id
                       and template_package_item.id = item.work_package_item_id
                      where relation.tenant_id = report.tenant_id
                        and relation.template_version_id = report.template_version_id
                        and (
                            item.work_package_item_id = record.work_package_item_id
                            or (template_package_item.item_code = record_package_item.item_code
                                and exists (select 1 from work_package_version template_version
                                  join work_package_version record_version
                                    on record_version.tenant_id=template_version.tenant_id
                                   and record_version.work_package_definition_id=template_version.work_package_definition_id
                                  where template_version.tenant_id=record.tenant_id
                                    and template_version.id=template_package_item.work_package_version_id
                                    and record_version.id=record_package_item.work_package_version_id))
                            or (
                                record_package_item.execution_policy ->> 'sharedReportProjectionMode'
                                    = 'ACTUAL_EXECUTOR_ONLY'
                                and nullif(btrim(record_package_item.execution_policy ->> 'sharedKey'), '')
                                    is not null
                                and record_package_item.execution_policy ->> 'sharedKey'
                                    = template_package_item.execution_policy ->> 'sharedKey'
                            )
                        )
                  )
                """, base(principal).addValue("recordId", workRecordId));
        for (Map<String, Object> report : reports) {
            projectReport((UUID) report.get("report_id"), (UUID) report.get("revision_id"));
        }
    }

    public void projectReport(UUID reportId, UUID revisionId) {
        TenantPrincipal principal = prepare();
        if (!lockDraftReport(principal, reportId, revisionId)) return;
        repairEmptyTemplateSnapshot(principal, reportId, revisionId);
        List<ProjectableRecord> records = jdbc.query("""
                select matched_item.template_item_id, record.id as work_record_id,
                       record.position_assignment_id, record.submitted_by_account_id,
                       executor_employee.id as executor_employee_id,
                       executor_employee.name as executor_employee_name,
                       executor_position.name as executor_position_name,
                       record.status, record.series_entry_no, record.attempt_no,
                       record.payload::text, record.completion_statement,
                       record.exception_statement, record.next_action, record.content_hash,
                       record.occurred_at, record.reviewed_at, matched_item.report_policy,
                       coalesce((record_package_item.report_policy ->> 'aggregateAllRecords')::boolean,
                                false) as aggregate_all_records
                from daily_report report
                join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = :revisionId and revision.report_id = report.id
                 and revision.revision_status = 'DRAFT'
                join work_record record
                 on record.tenant_id = report.tenant_id
                 and record.business_date = report.business_date
                 and record.position_assignment_id = report.position_assignment_id
                 and record.status in ('SUBMITTED', 'APPROVED')
                join work_package_item record_package_item
                  on record_package_item.tenant_id = record.tenant_id
                 and record_package_item.id = record.work_package_item_id
                join lateral (
                    select item.id as template_item_id,
                           template_package_item.report_policy::text as report_policy
                    from daily_report_template_section relation
                    join daily_report_template_item item
                      on item.tenant_id = relation.tenant_id
                     and item.section_version_id = relation.section_version_id
                     and item.work_package_item_id is not null
                    join work_package_item template_package_item
                      on template_package_item.tenant_id = item.tenant_id
                     and template_package_item.id = item.work_package_item_id
                    where relation.tenant_id = report.tenant_id
                      and relation.template_version_id = report.template_version_id
                      and coalesce((template_package_item.report_policy ->> 'dailyReport')::boolean, true)
                      and (
                          item.work_package_item_id = record.work_package_item_id
                          or (template_package_item.item_code = record_package_item.item_code
                              and exists (select 1 from work_package_version template_version
                                join work_package_version record_version
                                  on record_version.tenant_id=template_version.tenant_id
                                 and record_version.work_package_definition_id=template_version.work_package_definition_id
                                where template_version.tenant_id=record.tenant_id
                                  and template_version.id=template_package_item.work_package_version_id
                                  and record_version.id=record_package_item.work_package_version_id))
                          or (
                              record_package_item.execution_policy ->> 'sharedReportProjectionMode'
                                  = 'ACTUAL_EXECUTOR_ONLY'
                              and nullif(btrim(record_package_item.execution_policy ->> 'sharedKey'), '')
                                  is not null
                              and record_package_item.execution_policy ->> 'sharedKey'
                                  = template_package_item.execution_policy ->> 'sharedKey'
                          )
                      )
                    order by (item.work_package_item_id = record.work_package_item_id) desc,
                             item.sort_order, item.id
                    limit 1
                ) matched_item on true
                join employee_position_assignment executor_assignment
                  on executor_assignment.tenant_id = record.tenant_id
                 and executor_assignment.id = record.position_assignment_id
                join employee executor_employee
                  on executor_employee.tenant_id = executor_assignment.tenant_id
                 and executor_employee.id = executor_assignment.employee_id
                join position_definition executor_position
                  on executor_position.tenant_id = executor_assignment.tenant_id
                 and executor_position.id = executor_assignment.position_id
                join work_expectation expectation
                  on expectation.tenant_id = record.tenant_id
                 and expectation.id = record.work_expectation_id
                where report.tenant_id = :tenantId and report.id = :reportId
                  and report.report_status = 'DRAFT'
                  and exists (
                      select 1 from org_unit_closure hotel_scope
                      where hotel_scope.tenant_id = report.tenant_id
                        and hotel_scope.ancestor_id = report.hotel_org_unit_id
                        and hotel_scope.descendant_id = record.target_org_unit_id
                  )
                order by matched_item.template_item_id, record.series_entry_no desc,
                         record.attempt_no desc, record.id desc
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId),
                (rs, rowNum) -> new ProjectableRecord(
                        rs.getObject("template_item_id", UUID.class),
                        rs.getObject("work_record_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getObject("submitted_by_account_id", UUID.class),
                        rs.getObject("executor_employee_id", UUID.class),
                        rs.getString("executor_employee_name"),
                        rs.getString("executor_position_name"),
                        rs.getString("status"), rs.getInt("series_entry_no"),
                        rs.getInt("attempt_no"), rs.getString("payload"),
                        rs.getString("completion_statement"), rs.getString("exception_statement"),
                        rs.getString("next_action"), rs.getString("content_hash"),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class),
                        rs.getObject("reviewed_at", java.time.OffsetDateTime.class),
                        rs.getString("report_policy"), rs.getBoolean("aggregate_all_records")));
        removeStaleRoutineProjections(principal, reportId, revisionId);
        Map<UUID, List<ProjectableRecord>> recordsByTemplateItem = new LinkedHashMap<>();
        for (ProjectableRecord record : records) {
            recordsByTemplateItem.computeIfAbsent(record.templateItemId(), ignored -> new ArrayList<>()).add(record);
        }
        for (List<ProjectableRecord> itemRecords : recordsByTemplateItem.values()) {
            if (itemRecords.getFirst().aggregateAllRecords()) {
                projectRecordCollection(principal, revisionId, itemRecords);
            } else {
                projectRecord(principal, revisionId, itemRecords.getFirst());
            }
        }
        refreshRuleAnalysis(principal, reportId, revisionId);
    }

    private void repairEmptyTemplateSnapshot(TenantPrincipal principal, UUID reportId, UUID revisionId) {
        var params = base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId);
        Map<String, Object> row = jdbc.queryForMap("""
                select report.template_version_id, revision.payload_snapshot::text as snapshot
                from daily_report report join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id and revision.report_id = report.id
                where report.tenant_id = :tenantId and report.id = :reportId and revision.id = :revisionId
                """, params);
        ObjectNode snapshot = readObject(String.valueOf(row.get("snapshot")));
        JsonNode frozen = snapshot.path("resolvedTemplate");
        if (frozen.path("sections").isArray() && !frozen.path("sections").isEmpty()) return;
        // Use only the originally selected versions; never resolve today's/latest template here.
        ObjectNode repaired = snapshotReader.complete((UUID) row.get("template_version_id"), frozen);
        ArrayNode sections = (ArrayNode) repaired.path("sections");
        for (JsonNode supplement : snapshot.path("templateResolution").path("supplementTemplates")) {
            UUID versionId = UUID.fromString(supplement.path("templateVersionId").asText());
            snapshotReader.complete(versionId, supplement.path("configuration")).path("sections")
                    .forEach(sections::add);
        }
        if (sections.isEmpty()) return;
        snapshot.set("resolvedTemplate", repaired);
        jdbc.update("""
                update daily_report_revision set payload_snapshot = cast(:snapshot as jsonb),
                       row_version = row_version + 1
                where tenant_id = :tenantId and id = :revisionId and revision_status = 'DRAFT'
                """, params.addValue("snapshot", snapshot.toString()));
        jdbc.update("update daily_report set row_version = row_version + 1 where tenant_id = :tenantId and id = :reportId", params);
        auditWriter.record("DAILY_REPORT_TEMPLATE_SNAPSHOT_REPAIRED", "DAILY_REPORT", reportId,
                objectMapper.createObjectNode().put("revisionId", revisionId.toString())
                        .put("templateVersionId", row.get("template_version_id").toString()).toString());
    }

    private boolean lockDraftReport(TenantPrincipal principal, UUID reportId, UUID revisionId) {
        return !jdbc.query("""
                select report.id
                from daily_report report
                join daily_report_revision revision
                  on revision.tenant_id = report.tenant_id
                 and revision.id = :revisionId
                 and revision.report_id = report.id
                where report.tenant_id = :tenantId
                  and report.id = :reportId
                  and report.current_revision_id = revision.id
                  and report.report_status = 'DRAFT'
                  and revision.revision_status = 'DRAFT'
                for update of report, revision
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId),
                (rs, rowNum) -> rs.getObject("id", UUID.class)).isEmpty();
    }

    /**
     * Removes only our own system projection when an item no longer has a submitted or
     * approved record (for example after a supervisor rejects the pending submission).
     * The canonical work record and its audit history remain untouched.
     */
    private void removeStaleRoutineProjections(
            TenantPrincipal principal,
            UUID reportId,
            UUID revisionId
    ) {
        MapSqlParameterSource params = base(principal)
                .addValue("reportId", reportId)
                .addValue("revisionId", revisionId);
        String stalePredicate = """
                result.tenant_id = :tenantId
                  and result.revision_id = :revisionId
                  and result.system_prefilled
                  and result.source_summary ->> 'sourceType' = 'WORK_RECORD'
                  and exists (
                      select 1
                      from daily_report report
                      join daily_report_template_section relation
                        on relation.tenant_id = report.tenant_id
                       and relation.template_version_id = report.template_version_id
                      join daily_report_template_item item
                        on item.tenant_id = relation.tenant_id
                       and item.section_version_id = relation.section_version_id
                       and item.id = result.template_item_id
                      where report.tenant_id = result.tenant_id
                        and report.id = :reportId
                        and not exists (
                            select 1
                            from work_record record
                            where record.tenant_id = report.tenant_id
                              and record.id::text = result.source_summary ->> 'workRecordId'
                              and record.business_date = report.business_date
                              and record.status in ('SUBMITTED', 'APPROVED')
                              and record.position_assignment_id = report.position_assignment_id
                              and exists (
                                  select 1 from org_unit_closure hotel_scope
                                  where hotel_scope.tenant_id = report.tenant_id
                                    and hotel_scope.ancestor_id = report.hotel_org_unit_id
                                    and hotel_scope.descendant_id = record.target_org_unit_id
                              )
                        )
                  )
                """;
        jdbc.update("""
                delete from daily_report_evidence evidence
                using daily_report_item_result result
                where evidence.tenant_id = result.tenant_id
                  and evidence.revision_id = result.revision_id
                  and evidence.item_result_id = result.id
                  and
                """ + stalePredicate, params);
        jdbc.update("""
                delete from daily_report_source_reference source
                using daily_report_item_result result
                where source.tenant_id = result.tenant_id
                  and source.revision_id = result.revision_id
                  and source.item_result_id = result.id
                  and
                """ + stalePredicate, params);
        jdbc.update("""
                delete from daily_report_item_result result
                where
                """ + stalePredicate, params);

        String staleCollectionPredicate = """
                result.tenant_id = :tenantId
                  and result.revision_id = :revisionId
                  and result.system_prefilled
                  and result.source_summary ->> 'sourceType' = 'WORK_RECORD_COLLECTION'
                  and exists (
                      select 1
                      from daily_report report
                      where report.tenant_id = result.tenant_id
                        and report.id = :reportId
                        and not exists (
                            select 1
                            from work_record record
                            where record.tenant_id = report.tenant_id
                              and record.business_date = report.business_date
                              and record.position_assignment_id = report.position_assignment_id
                              and record.status in ('SUBMITTED', 'APPROVED')
                              and exists (
                                  select 1 from org_unit_closure hotel_scope
                                  where hotel_scope.tenant_id = report.tenant_id
                                    and hotel_scope.ancestor_id = report.hotel_org_unit_id
                                    and hotel_scope.descendant_id = record.target_org_unit_id
                              )
                              and record.id::text in (
                                  select jsonb_array_elements_text(
                                      coalesce(result.source_summary -> 'workRecordIds', '[]'::jsonb)
                                  )
                              )
                        )
                  )
                """;
        jdbc.update("""
                delete from daily_report_evidence evidence
                using daily_report_item_result result
                where evidence.tenant_id = result.tenant_id
                  and evidence.revision_id = result.revision_id
                  and evidence.item_result_id = result.id
                  and
                """ + staleCollectionPredicate, params);
        jdbc.update("""
                delete from daily_report_source_reference source
                using daily_report_item_result result
                where source.tenant_id = result.tenant_id
                  and source.revision_id = result.revision_id
                  and source.item_result_id = result.id
                  and
                """ + staleCollectionPredicate, params);
        jdbc.update("""
                delete from daily_report_item_result result
                where
                """ + staleCollectionPredicate, params);
    }

    private void projectRecord(TenantPrincipal principal, UUID revisionId, ProjectableRecord record) {
        ObjectNode reportPolicy = readObject(record.reportPolicy());
        boolean includeEvidence = reportPolicy.path("includeEvidence").asBoolean(true);
        boolean includeExceptions = reportPolicy.path("includeExceptions").asBoolean(true);
        boolean approved = "APPROVED".equals(record.status());
        ObjectNode value = objectMapper.createObjectNode();
        value.put("workRecordId", record.workRecordId().toString());
        value.put("status", record.status());
        ObjectNode actualExecutor = value.putObject("actualExecutor");
        actualExecutor.put("assignmentId", record.assignmentId().toString());
        actualExecutor.put("employeeId", record.executorEmployeeId().toString());
        actualExecutor.put("employeeName", record.executorEmployeeName());
        actualExecutor.put("positionName", record.executorPositionName());
        value.put("completion", blankToFallback(record.completionStatement(),
                approved ? "已完成" : "已提交，待主管审核"));
        String factLabel = reportPolicy.path("factLabel").asText("").trim();
        if (!factLabel.isBlank()) value.put("factLabel", factLabel);
        if (includeExceptions && !isBlank(record.exceptionStatement())) value.put("exception", record.exceptionStatement());
        if (!isBlank(record.nextAction())) value.put("nextAction", record.nextAction());
        value.set("formData", readObject(record.payload()));
        projectUsageFacts(principal, record.workRecordId(), value);
        if (record.reviewedAt() != null) value.put("reviewedAt", record.reviewedAt().toString());
        boolean exception = approved && includeExceptions && !isBlank(record.exceptionStatement())
                && !"无".equals(record.exceptionStatement().trim());
        int evidenceCount = includeEvidence ? countAttachments(principal, record.workRecordId()) : 0;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("sourceType", "WORK_RECORD");
        summary.put("workRecordId", record.workRecordId().toString());
        summary.put("seriesEntryNo", record.seriesEntryNo());
        summary.put("attemptNo", record.attemptNo());
        summary.put("evidenceCount", evidenceCount);
        summary.put("reviewStatus", record.status());
        summary.put("actualExecutorAssignmentId", record.assignmentId().toString());
        summary.put("actualExecutorEmployeeId", record.executorEmployeeId().toString());
        UUID resultId = jdbc.queryForObject("""
                with changed as (
                    insert into daily_report_item_result
                        (id, tenant_id, revision_id, template_item_id, result_status, value,
                         system_prefilled, employee_confirmed, exception_flag,
                         exception_statement, source_summary, content_hash)
                    values (:id, :tenantId, :revisionId, :templateItemId, :resultStatus,
                            cast(:value as jsonb), true, false, :exception,
                            :exceptionStatement, cast(:sourceSummary as jsonb), :contentHash)
                    on conflict (tenant_id, revision_id, template_item_id) do update
                    set result_status = excluded.result_status, value = excluded.value,
                        system_prefilled = true, employee_confirmed = false,
                        exception_flag = excluded.exception_flag,
                        exception_statement = excluded.exception_statement,
                        source_summary = excluded.source_summary, content_hash = excluded.content_hash,
                        row_version = daily_report_item_result.row_version + 1
                    where (daily_report_item_result.result_status,
                           daily_report_item_result.value,
                           daily_report_item_result.system_prefilled,
                           daily_report_item_result.employee_confirmed,
                           daily_report_item_result.exception_flag,
                           daily_report_item_result.exception_statement,
                           daily_report_item_result.source_summary,
                           daily_report_item_result.content_hash)
                          is distinct from
                          (excluded.result_status,
                           excluded.value,
                           true,
                           false,
                           excluded.exception_flag,
                           excluded.exception_statement,
                           excluded.source_summary,
                           excluded.content_hash)
                    returning id
                )
                select id from changed
                union all
                select existing.id
                from daily_report_item_result existing
                where existing.tenant_id = :tenantId
                  and existing.revision_id = :revisionId
                  and existing.template_item_id = :templateItemId
                  and not exists (select 1 from changed)
                limit 1
                """, base(principal).addValue("id", UUID.randomUUID())
                .addValue("revisionId", revisionId).addValue("templateItemId", record.templateItemId())
                .addValue("resultStatus", approved ? (exception ? "EXCEPTION" : "COMPLETED") : "PENDING")
                .addValue("value", value.toString()).addValue("exception", exception)
                .addValue("exceptionStatement", exception ? record.exceptionStatement() : null)
                .addValue("sourceSummary", summary.toString()).addValue("contentHash", record.contentHash()),
                UUID.class);

        ObjectNode source = objectMapper.createObjectNode();
        source.put("status", record.status());
        source.put("seriesEntryNo", record.seriesEntryNo());
        source.put("attemptNo", record.attemptNo());
        source.set("value", value.deepCopy());
        jdbc.update("""
                insert into daily_report_source_reference
                    (id, tenant_id, revision_id, item_result_id, source_type, source_id,
                     source_version, source_snapshot, content_hash, source_occurred_at,
                     linked_by_account_id)
                select :id, :tenantId, :revisionId, :itemResultId, 'WORK_RECORD', :recordId,
                       :sourceVersion, cast(:sourceSnapshot as jsonb), :contentHash,
                       :occurredAt, :actorId
                where not exists (
                    select 1 from daily_report_source_reference existing
                    where existing.tenant_id = :tenantId and existing.revision_id = :revisionId
                      and existing.source_type = 'WORK_RECORD' and existing.source_id = :recordId
                      and existing.source_version = :sourceVersion
                )
                """, base(principal).addValue("id", UUID.randomUUID()).addValue("revisionId", revisionId)
                .addValue("itemResultId", resultId).addValue("recordId", record.workRecordId())
                .addValue("sourceVersion", "entry-" + record.seriesEntryNo()
                        + ":attempt-" + record.attemptNo() + ":" + record.status())
                .addValue("sourceSnapshot", source.toString()).addValue("contentHash", record.contentHash())
                .addValue("occurredAt", record.occurredAt()).addValue("actorId", principal.actorId()));

        if (includeEvidence) {
            jdbc.update("""
                    insert into daily_report_evidence
                        (id, tenant_id, revision_id, item_result_id, evidence_type, object_key,
                         original_name, media_type, size_bytes, sha256, structured_snapshot,
                         scan_status, sensitivity_level, uploaded_by_account_id,
                         uploaded_by_assignment_id)
                    select gen_random_uuid(), attachment.tenant_id, :revisionId, :itemResultId,
                           case when attachment.media_type like 'image/%' then 'IMAGE' else 'DOCUMENT' end,
                           attachment.object_key, attachment.original_name, attachment.media_type,
                           attachment.size_bytes, attachment.sha256,
                           jsonb_build_object(
                               'attachmentId', attachment.id::text,
                               'captureSource', attachment.capture_source,
                               'checkpointCode', attachment.checkpoint_code,
                               'evidenceInstanceKey', attachment.evidence_metadata ->> 'evidenceInstanceKey',
                               'receivedAt', attachment.received_at,
                               'sourceSha256', attachment.source_sha256
                           ), attachment.scan_status, 'INTERNAL',
                           coalesce(:submittedByAccountId, :actorId), :assignmentId
                    from attachment
                    where attachment.tenant_id = :tenantId
                      and attachment.work_record_id = :recordId
                      and attachment.scan_status <> 'REJECTED'
                      and not exists (
                          select 1 from daily_report_evidence existing
                          where existing.tenant_id = attachment.tenant_id
                            and existing.revision_id = :revisionId
                            and existing.object_key = attachment.object_key
                      )
                    """, base(principal).addValue("revisionId", revisionId).addValue("itemResultId", resultId)
                    .addValue("recordId", record.workRecordId())
                    .addValue("submittedByAccountId", record.submittedByAccountId())
                    .addValue("actorId", principal.actorId()).addValue("assignmentId", record.assignmentId()));
        }
    }

    private void projectRecordCollection(
            TenantPrincipal principal,
            UUID revisionId,
            List<ProjectableRecord> sourceRecords
    ) {
        List<ProjectableRecord> records = sourceRecords.stream()
                .sorted(Comparator.comparingInt(ProjectableRecord::seriesEntryNo))
                .toList();
        ProjectableRecord representative = records.getFirst();
        ObjectNode reportPolicy = readObject(representative.reportPolicy());
        boolean includeEvidence = reportPolicy.path("includeEvidence").asBoolean(true);
        boolean includeExceptions = reportPolicy.path("includeExceptions").asBoolean(true);
        boolean approved = records.stream().allMatch(record -> "APPROVED".equals(record.status()));
        List<String> exceptionStatements = records.stream()
                .map(ProjectableRecord::exceptionStatement)
                .filter(statement -> !isBlank(statement) && !"无".equals(statement.trim()))
                .map(String::trim)
                .toList();
        boolean exception = approved && includeExceptions && !exceptionStatements.isEmpty();

        ObjectNode value = objectMapper.createObjectNode();
        value.put("recordCount", records.size());
        value.put("completion", "当日共记录 " + records.size() + " 条");
        String factLabel = reportPolicy.path("factLabel").asText("").trim();
        if (!factLabel.isBlank()) value.put("factLabel", factLabel);
        ArrayNode entries = value.putArray("entries");
        ArrayNode workRecordIds = objectMapper.createArrayNode();
        Map<UUID, ObjectNode> entrySnapshots = new LinkedHashMap<>();
        int evidenceCount = 0;
        for (ProjectableRecord record : records) {
            ObjectNode entry = entries.addObject();
            entry.put("workRecordId", record.workRecordId().toString());
            entry.put("seriesEntryNo", record.seriesEntryNo());
            entry.put("status", record.status());
            entry.put("occurredAt", record.occurredAt().toString());
            ObjectNode executor = entry.putObject("actualExecutor");
            executor.put("assignmentId", record.assignmentId().toString());
            executor.put("employeeId", record.executorEmployeeId().toString());
            executor.put("employeeName", record.executorEmployeeName());
            executor.put("positionName", record.executorPositionName());
            entry.put("completion", blankToFallback(record.completionStatement(),
                    "APPROVED".equals(record.status()) ? "已完成" : "已提交，待主管审核"));
            if (includeExceptions && !isBlank(record.exceptionStatement())) {
                entry.put("exception", record.exceptionStatement());
            }
            if (!isBlank(record.nextAction())) entry.put("nextAction", record.nextAction());
            entry.set("formData", readObject(record.payload()));
            projectUsageFacts(principal, record.workRecordId(), entry);
            if (record.reviewedAt() != null) entry.put("reviewedAt", record.reviewedAt().toString());
            entrySnapshots.put(record.workRecordId(), entry.deepCopy());
            workRecordIds.add(record.workRecordId().toString());
            if (includeEvidence) evidenceCount += countAttachments(principal, record.workRecordId());
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("sourceType", "WORK_RECORD_COLLECTION");
        summary.put("recordCount", records.size());
        summary.set("workRecordIds", workRecordIds);
        summary.put("evidenceCount", evidenceCount);
        summary.put("reviewStatus", approved ? "APPROVED" : "SUBMITTED");
        String combinedHash = sha256(value.toString() + "|" + summary);
        String exceptionSummary = exception ? String.join("；", exceptionStatements) : null;

        UUID resultId = jdbc.queryForObject("""
                with changed as (
                    insert into daily_report_item_result
                        (id, tenant_id, revision_id, template_item_id, result_status, value,
                         system_prefilled, employee_confirmed, exception_flag,
                         exception_statement, source_summary, content_hash)
                    values (:id, :tenantId, :revisionId, :templateItemId, :resultStatus,
                            cast(:value as jsonb), true, false, :exception,
                            :exceptionStatement, cast(:sourceSummary as jsonb), :contentHash)
                    on conflict (tenant_id, revision_id, template_item_id) do update
                    set result_status = excluded.result_status, value = excluded.value,
                        system_prefilled = true, employee_confirmed = false,
                        exception_flag = excluded.exception_flag,
                        exception_statement = excluded.exception_statement,
                        source_summary = excluded.source_summary, content_hash = excluded.content_hash,
                        row_version = daily_report_item_result.row_version + 1
                    where (daily_report_item_result.result_status,
                           daily_report_item_result.value,
                           daily_report_item_result.system_prefilled,
                           daily_report_item_result.employee_confirmed,
                           daily_report_item_result.exception_flag,
                           daily_report_item_result.exception_statement,
                           daily_report_item_result.source_summary,
                           daily_report_item_result.content_hash)
                          is distinct from
                          (excluded.result_status, excluded.value, true, false,
                           excluded.exception_flag, excluded.exception_statement,
                           excluded.source_summary, excluded.content_hash)
                    returning id
                )
                select id from changed
                union all
                select existing.id
                from daily_report_item_result existing
                where existing.tenant_id = :tenantId
                  and existing.revision_id = :revisionId
                  and existing.template_item_id = :templateItemId
                  and not exists (select 1 from changed)
                limit 1
                """, base(principal).addValue("id", UUID.randomUUID())
                .addValue("revisionId", revisionId)
                .addValue("templateItemId", representative.templateItemId())
                .addValue("resultStatus", approved ? (exception ? "EXCEPTION" : "COMPLETED") : "PENDING")
                .addValue("value", value.toString())
                .addValue("exception", exception)
                .addValue("exceptionStatement", exceptionSummary)
                .addValue("sourceSummary", summary.toString())
                .addValue("contentHash", combinedHash), UUID.class);

        MapSqlParameterSource cleanup = base(principal)
                .addValue("revisionId", revisionId)
                .addValue("itemResultId", resultId);
        jdbc.update("""
                delete from daily_report_evidence
                where tenant_id = :tenantId and revision_id = :revisionId
                  and item_result_id = :itemResultId
                """, cleanup);
        jdbc.update("""
                delete from daily_report_source_reference
                where tenant_id = :tenantId and revision_id = :revisionId
                  and item_result_id = :itemResultId
                """, cleanup);

        for (ProjectableRecord record : records) {
            ObjectNode source = objectMapper.createObjectNode();
            source.put("status", record.status());
            source.put("seriesEntryNo", record.seriesEntryNo());
            source.put("attemptNo", record.attemptNo());
            source.set("value", entrySnapshots.get(record.workRecordId()).deepCopy());
            jdbc.update("""
                    insert into daily_report_source_reference
                        (id, tenant_id, revision_id, item_result_id, source_type, source_id,
                         source_version, source_snapshot, content_hash, source_occurred_at,
                         linked_by_account_id)
                    values (:id, :tenantId, :revisionId, :itemResultId, 'WORK_RECORD', :recordId,
                            :sourceVersion, cast(:sourceSnapshot as jsonb), :contentHash,
                            :occurredAt, :actorId)
                    """, base(principal).addValue("id", UUID.randomUUID())
                    .addValue("revisionId", revisionId).addValue("itemResultId", resultId)
                    .addValue("recordId", record.workRecordId())
                    .addValue("sourceVersion", "entry-" + record.seriesEntryNo()
                            + ":attempt-" + record.attemptNo() + ":" + record.status())
                    .addValue("sourceSnapshot", source.toString())
                    .addValue("contentHash", record.contentHash())
                    .addValue("occurredAt", record.occurredAt())
                    .addValue("actorId", principal.actorId()));
            if (includeEvidence) {
                jdbc.update("""
                        insert into daily_report_evidence
                            (id, tenant_id, revision_id, item_result_id, evidence_type, object_key,
                             original_name, media_type, size_bytes, sha256, structured_snapshot,
                             scan_status, sensitivity_level, uploaded_by_account_id,
                             uploaded_by_assignment_id)
                        select gen_random_uuid(), attachment.tenant_id, :revisionId, :itemResultId,
                               case when attachment.media_type like 'image/%' then 'IMAGE' else 'DOCUMENT' end,
                               attachment.object_key, attachment.original_name, attachment.media_type,
                               attachment.size_bytes, attachment.sha256,
                               jsonb_build_object(
                                   'attachmentId', attachment.id::text,
                                   'workRecordId', cast(:recordId as text),
                                   'seriesEntryNo', :seriesEntryNo,
                                   'captureSource', attachment.capture_source,
                                   'checkpointCode', attachment.checkpoint_code,
                                   'evidenceInstanceKey', attachment.evidence_metadata ->> 'evidenceInstanceKey',
                                   'receivedAt', attachment.received_at,
                                   'sourceSha256', attachment.source_sha256
                               ), attachment.scan_status, 'INTERNAL',
                               coalesce(:submittedByAccountId, :actorId), :assignmentId
                        from attachment
                        where attachment.tenant_id = :tenantId
                          and attachment.work_record_id = :recordId
                          and attachment.scan_status <> 'REJECTED'
                        """, base(principal).addValue("revisionId", revisionId)
                        .addValue("itemResultId", resultId).addValue("recordId", record.workRecordId())
                        .addValue("seriesEntryNo", record.seriesEntryNo())
                        .addValue("submittedByAccountId", record.submittedByAccountId())
                        .addValue("actorId", principal.actorId())
                        .addValue("assignmentId", record.assignmentId()));
            }
        }
    }

    private void refreshRuleAnalysis(TenantPrincipal principal, UUID reportId, UUID revisionId) {
        Map<String, Object> facts = jdbc.queryForMap("""
                with report_context as (
                    select report.*
                    from daily_report report
                    where report.tenant_id = :tenantId and report.id = :reportId
                ),
                report_template_items as (
                    select distinct
                           item.id as template_item_id,
                           item.work_package_item_id,
                           nullif(btrim(package_item.execution_policy ->> 'sharedKey'), '') as shared_key,
                           package_item.execution_policy ->> 'sharedReportProjectionMode' as projection_mode,
                           case
                               when package_item.execution_policy ->> 'sharedReportProjectionMode'
                                        = 'ACTUAL_EXECUTOR_ONLY'
                                and nullif(btrim(package_item.execution_policy ->> 'sharedKey'), '')
                                        is not null
                               then 'SHARED:' || (package_item.execution_policy ->> 'sharedKey')
                               else 'ITEM:' || item.id::text
                           end as semantic_key
                    from report_context report
                    join daily_report_template_section relation
                      on relation.tenant_id = report.tenant_id
                     and relation.template_version_id = report.template_version_id
                    join daily_report_template_item item
                      on item.tenant_id = relation.tenant_id
                     and item.section_version_id = relation.section_version_id
                     and item.work_package_item_id is not null
                    join work_package_item package_item
                      on package_item.tenant_id = item.tenant_id
                     and package_item.id = item.work_package_item_id
                    where coalesce((package_item.report_policy ->> 'dailyReport')::boolean, true)
                ),
                matched_expectations as (
                    select distinct template.semantic_key, expectation.id, expectation.status
                    from report_context report
                    join report_template_items template on true
                    join work_expectation expectation
                      on expectation.tenant_id = report.tenant_id
                     and expectation.business_date = report.business_date
                    join work_package_item expectation_item
                      on expectation_item.tenant_id = expectation.tenant_id
                     and expectation_item.id = expectation.work_package_item_id
                    left join lateral (
                        select record.position_assignment_id
                        from work_record record
                        where record.tenant_id = expectation.tenant_id
                          and record.work_expectation_id = expectation.id
                          and record.status in ('SUBMITTED', 'APPROVED')
                        order by record.attempt_no desc, record.id desc
                        limit 1
                    ) actual_record on true
                    where (
                        expectation.work_package_item_id = template.work_package_item_id
                        or (
                            template.projection_mode = 'ACTUAL_EXECUTOR_ONLY'
                            and expectation_item.execution_policy ->> 'sharedReportProjectionMode'
                                = 'ACTUAL_EXECUTOR_ONLY'
                            and template.shared_key is not null
                            and template.shared_key = expectation_item.execution_policy ->> 'sharedKey'
                        )
                    )
                      and coalesce(actual_record.position_assignment_id,
                                   expectation.position_assignment_id)
                            = report.position_assignment_id
                      and exists (
                          select 1 from org_unit_closure hotel_scope
                          where hotel_scope.tenant_id = report.tenant_id
                            and hotel_scope.ancestor_id = report.hotel_org_unit_id
                            and hotel_scope.descendant_id = expectation.target_org_unit_id
                      )
                )
                select report.hotel_org_unit_id, report.org_unit_id, report.business_date,
                       report.position_assignment_id, report.trace_id,
                       (select count(distinct semantic_key)
                        from matched_expectations
                        where status <> 'CANCELLED') as expected_count,
                       (select count(distinct template.semantic_key)
                        from daily_report_item_result result
                        join report_template_items template
                          on template.template_item_id = result.template_item_id
                        where result.tenant_id = report.tenant_id
                          and result.revision_id = :revisionId
                          and result.system_prefilled
                          and result.result_status in ('COMPLETED', 'EXCEPTION')) as completed_count,
                       (select count(distinct template.semantic_key)
                        from daily_report_item_result result
                        join report_template_items template
                          on template.template_item_id = result.template_item_id
                        where result.tenant_id = report.tenant_id
                          and result.revision_id = :revisionId
                          and result.system_prefilled
                          and result.result_status = 'PENDING') as pending_review_count,
                       (select count(distinct template.semantic_key)
                        from daily_report_item_result result
                        join report_template_items template
                          on template.template_item_id = result.template_item_id
                        where result.tenant_id = report.tenant_id
                          and result.revision_id = :revisionId and result.exception_flag) as exception_count,
                       (select count(*) from daily_report_evidence evidence
                        where evidence.tenant_id = report.tenant_id
                          and evidence.revision_id = :revisionId
                          and evidence.invalidated_at is null
                          and evidence.scan_status <> 'REJECTED') as evidence_count,
                       (select count(distinct semantic_key)
                        from matched_expectations
                        where status = 'MISSED') as missed_count
                from report_context report
                """, base(principal).addValue("reportId", reportId).addValue("revisionId", revisionId));
        int expected = ((Number) facts.get("expected_count")).intValue();
        int completed = ((Number) facts.get("completed_count")).intValue();
        int pendingReview = ((Number) facts.get("pending_review_count")).intValue();
        int exceptions = ((Number) facts.get("exception_count")).intValue();
        int missed = ((Number) facts.get("missed_count")).intValue();
        int evidence = ((Number) facts.get("evidence_count")).intValue();
        RuleContentFacts contentFacts = collectRuleContentFacts(principal, revisionId);
        ObjectNode dailySummary = collectDailySummary(principal, revisionId);
        dailySummary.put("expectedCount", expected);
        dailySummary.put("completedCount", completed);
        dailySummary.put("pendingReviewCount", pendingReview);
        dailySummary.put("exceptionCount", exceptions);
        dailySummary.put("missedCount", missed);
        String analysis = "规则分析：今日标准日常工作已审核完成 " + completed + "/" + expected
                + " 项，待主管审核 " + pendingReview + " 项，关联证据 " + evidence
                + " 份；异常 " + exceptions + " 项，逾期 " + missed + " 项。"
                + renderContentAnalysis(contentFacts)
                + (dailySummary.path("workItems").isEmpty()
                    ? " 暂未取得本营业日、本任职的已提交工作，不能据此判断已完成。"
                    : " 工作记录：" + renderWorkSummary(dailySummary) + "。")
                + (dailySummary.path("usageItems").isEmpty() ? ""
                    : " 物品数量已按台账去重汇总；待审核数量尚未计入库存及成本，布草使用不等于洗涤结算。");
        String recommendation = buildRuleRecommendation(
                pendingReview, exceptions, missed, completed, expected, contentFacts);
        jdbc.update("""
                update daily_report_revision
                set narrative = :analysis, row_version = row_version + 1
                where tenant_id = :tenantId and id = :revisionId and revision_status = 'DRAFT'
                  and (narrative is null or narrative like '规则分析：%')
                  and narrative is distinct from :analysis
                """, base(principal).addValue("revisionId", revisionId).addValue("analysis", analysis));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("reportId", reportId.toString());
        input.put("revisionId", revisionId.toString());
        input.put("expectedCount", expected);
        input.put("completedCount", completed);
        input.put("pendingReviewCount", pendingReview);
        input.put("exceptionCount", exceptions);
        input.put("missedCount", missed);
        input.put("evidenceCount", evidence);
        input.put("analysisMode", "FACT_ONLY_INTERNAL_RULES");
        input.set("issueFacts", toFactArray(contentFacts.issueFacts()));
        input.set("declaredNextActions", toFactArray(contentFacts.declaredActions()));
        input.set("dailySummary", dailySummary);
        jdbc.update("""
                update daily_report_revision
                set payload_snapshot = jsonb_set(payload_snapshot, '{dailySummary}', cast(:summary as jsonb)),
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :revisionId and revision_status = 'DRAFT'
                  and payload_snapshot -> 'dailySummary' is distinct from cast(:summary as jsonb)
                """, base(principal).addValue("revisionId", revisionId).addValue("summary", dailySummary.toString()));
        String inputHash = sha256(input.toString());
        UUID existingRecommendationId = jdbc.query("""
                select recommendation.id
                from ai_recommendation recommendation
                join ai_request request
                  on request.tenant_id = recommendation.tenant_id
                 and request.id = recommendation.ai_request_id
                join ai_recommendation_source source
                  on source.tenant_id = recommendation.tenant_id
                 and source.recommendation_id = recommendation.id
                 and source.source_type = 'DAILY_REPORT'
                 and source.source_id = :reportId
                where recommendation.tenant_id = :tenantId
                  and recommendation.status = 'AVAILABLE'
                  and request.request_type = 'DAILY_REPORT_ANALYSIS'
                  and request.provider_code = :provider
                  and request.input_hash = :inputHash
                order by recommendation.generated_at desc
                limit 1
                """, base(principal).addValue("reportId", reportId)
                .addValue("provider", RULE_PROVIDER).addValue("inputHash", inputHash),
                (rs, rowNum) -> rs.getObject("id", UUID.class)).stream().findFirst().orElse(null);
        if (existingRecommendationId != null) return;

        jdbc.update("""
                update ai_recommendation recommendation
                set status = 'INVALIDATED', invalidated_at = now(),
                    invalidation_reason = '日报事实快照已更新'
                from ai_request request
                where recommendation.tenant_id = :tenantId
                  and recommendation.status = 'AVAILABLE'
                  and request.tenant_id = recommendation.tenant_id
                  and request.id = recommendation.ai_request_id
                  and request.request_type = 'DAILY_REPORT_ANALYSIS'
                  and request.provider_code = :provider
                  and request.input_snapshot ->> 'reportId' = :reportIdText
                  and request.input_snapshot ->> 'revisionId' = :revisionIdText
                """, base(principal).addValue("provider", RULE_PROVIDER)
                .addValue("reportIdText", reportId.toString()).addValue("revisionIdText", revisionId.toString()));

        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                insert into ai_request
                    (id, tenant_id, request_type, status, hotel_org_unit_id, org_unit_id,
                     business_date, provider_code, model_name, model_version, prompt_version,
                     context_version, input_hash, input_snapshot, sensitivity_level,
                     requested_by_account_id, requested_by_assignment_id, trace_id,
                     correlation_id, completed_at)
                values (:id, :tenantId, 'DAILY_REPORT_ANALYSIS', 'SUCCEEDED', :hotelId, :orgId,
                        :businessDate, :provider, 'deterministic-summary', '2', 'store-manager-v3',
                        'daily-report-v2', :inputHash, cast(:input as jsonb), 'INTERNAL',
                        :actorId, :assignmentId, :traceId, :correlationId, now())
                """, base(principal).addValue("id", requestId)
                .addValue("hotelId", facts.get("hotel_org_unit_id")).addValue("orgId", facts.get("org_unit_id"))
                .addValue("businessDate", facts.get("business_date")).addValue("provider", RULE_PROVIDER)
                .addValue("inputHash", inputHash).addValue("input", input.toString())
                .addValue("actorId", principal.actorId()).addValue("assignmentId", facts.get("position_assignment_id"))
                .addValue("traceId", facts.get("trace_id")).addValue("correlationId", principal.correlationId()));

        ObjectNode factSummary = input.deepCopy();
        UUID recommendationId = UUID.randomUUID();
        jdbc.update("""
                insert into ai_recommendation
                    (id, tenant_id, ai_request_id, recommendation_no, recommendation_type,
                     fact_summary, analysis, recommendation, confidence, applicability_scope,
                     model_name, model_version, prompt_version, context_version)
                values (:id, :tenantId, :requestId, 1, 'DAILY_REPORT_RULE_ANALYSIS',
                        cast(:facts as jsonb), :analysis, :recommendation, 1.0,
                        jsonb_build_object('reportId', :reportIdText),
                        'deterministic-summary', '2', 'store-manager-v3', 'daily-report-v2')
                """, base(principal).addValue("id", recommendationId).addValue("requestId", requestId)
                .addValue("facts", factSummary.toString()).addValue("analysis", analysis)
                .addValue("recommendation", recommendation).addValue("reportIdText", reportId.toString()));
        jdbc.update("""
                insert into ai_recommendation_source
                    (id, tenant_id, recommendation_id, source_type, source_id,
                     source_version, source_snapshot, content_hash)
                values (:id, :tenantId, :recommendationId, 'DAILY_REPORT', :reportId,
                        :sourceVersion, cast(:sourceSnapshot as jsonb), :contentHash)
                """, base(principal).addValue("id", UUID.randomUUID())
                .addValue("recommendationId", recommendationId).addValue("reportId", reportId)
                .addValue("sourceVersion", revisionId + ":" + inputHash.substring(0, 12))
                .addValue("sourceSnapshot", input.toString()).addValue("contentHash", inputHash));
    }

    /**
     * Reads only facts that employees explicitly submitted into the projected work result.
     * This stays entirely inside the service and deliberately avoids free-form inference:
     * exception/next-action fields are authoritative, while completion/form fields are
     * included only when they contain an unambiguous problem marker.
     */
    private RuleContentFacts collectRuleContentFacts(
            TenantPrincipal principal,
            UUID revisionId
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select result.source_summary::text as source_summary_json,
                       result.value::text as value_json
                from daily_report_item_result result
                where result.tenant_id = :tenantId
                  and result.revision_id = :revisionId
                  and result.system_prefilled
                  and result.source_summary ->> 'sourceType' in ('WORK_RECORD', 'WORK_RECORD_COLLECTION')
                order by result.template_item_id, result.id
                """, base(principal).addValue("revisionId", revisionId));
        List<RuleFact> issueFacts = new ArrayList<>();
        List<RuleFact> declaredActions = new ArrayList<>();
        Set<String> issueKeys = new LinkedHashSet<>();
        Set<String> actionKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            ObjectNode sourceSummary = readObject(String.valueOf(row.get("source_summary_json")));
            ObjectNode value = readObject(String.valueOf(row.get("value_json")));
            if ("WORK_RECORD_COLLECTION".equals(sourceSummary.path("sourceType").asText())) {
                JsonNode entries = value.path("entries");
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        collectRuleFactsFromValue(entry.path("workRecordId").asText(),
                                entry.path("status").asText(), entry,
                                issueFacts, issueKeys, declaredActions, actionKeys);
                    }
                }
            } else {
                collectRuleFactsFromValue(sourceSummary.path("workRecordId").asText(),
                        sourceSummary.path("reviewStatus").asText(), value,
                        issueFacts, issueKeys, declaredActions, actionKeys);
            }
        }
        return new RuleContentFacts(List.copyOf(issueFacts), List.copyOf(declaredActions));
    }

    private void collectRuleFactsFromValue(
            String workRecordId,
            String reviewStatus,
            JsonNode value,
            List<RuleFact> issueFacts,
            Set<String> issueKeys,
            List<RuleFact> declaredActions,
            Set<String> actionKeys
    ) {
        addRuleFact(issueFacts, issueKeys, MAX_CONTENT_ISSUES, workRecordId, reviewStatus,
                "exception", textValue(value.get("exception")));
        addRuleFact(declaredActions, actionKeys, MAX_DECLARED_ACTIONS, workRecordId, reviewStatus,
                "nextAction", textValue(value.get("nextAction")));
        String completion = textValue(value.get("completion"));
        if (containsExplicitProblem(completion)) {
            addRuleFact(issueFacts, issueKeys, MAX_CONTENT_ISSUES, workRecordId, reviewStatus,
                    "completion", completion);
        }
        collectFormIssues(value.get("formData"), "formData", 0, workRecordId, reviewStatus,
                issueFacts, issueKeys);
    }

    private void collectFormIssues(
            JsonNode node,
            String path,
            int depth,
            String workRecordId,
            String reviewStatus,
            List<RuleFact> issueFacts,
            Set<String> issueKeys
    ) {
        if (node == null || node.isNull() || depth > MAX_FORM_DEPTH
                || issueFacts.size() >= MAX_CONTENT_ISSUES) {
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                collectFormIssues(node.get(name), path + "." + name, depth + 1,
                        workRecordId, reviewStatus, issueFacts, issueKeys);
                if (issueFacts.size() >= MAX_CONTENT_ISSUES) return;
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < Math.min(node.size(), 10); index++) {
                collectFormIssues(node.get(index), path + "[" + index + "]", depth + 1,
                        workRecordId, reviewStatus, issueFacts, issueKeys);
                if (issueFacts.size() >= MAX_CONTENT_ISSUES) return;
            }
            return;
        }
        if (isExplicitFormIssue(path, node)) {
            addRuleFact(issueFacts, issueKeys, MAX_CONTENT_ISSUES, workRecordId, reviewStatus,
                    path, textValue(node));
        }
    }

    private static boolean isExplicitFormIssue(String path, JsonNode value) {
        String normalizedKey = normalizeKey(path);
        boolean issueKey = containsAny(normalizedKey,
                "exception", "issue", "problem", "risk", "defect", "complaint", "fault",
                "abnormal", "hazard", "问题", "异常", "风险", "缺陷", "投诉", "故障", "隐患");
        boolean positiveKey = containsAny(normalizedKey,
                "passed", "qualified", "normal", "complete", "resolved",
                "合格", "正常", "通过", "完成", "达标", "解决");
        if (value.isBoolean()) {
            return (issueKey && value.booleanValue()) || (positiveKey && !value.booleanValue());
        }
        if (value.isNumber()) {
            return issueKey && value.decimalValue().signum() > 0;
        }
        String text = textValue(value);
        if (!isMeaningfulFact(text)) return false;
        if (issueKey) return !isNegativeAnswer(text);
        if (positiveKey && isNegativeAnswer(text)) return true;
        return containsExplicitProblem(text);
    }

    private static void addRuleFact(
            List<RuleFact> target,
            Set<String> deduplicationKeys,
            int limit,
            String workRecordId,
            String reviewStatus,
            String sourceField,
            String rawValue
    ) {
        String value = normalizeFact(rawValue);
        if (target.size() >= limit || !isMeaningfulFact(value)) return;
        String key = workRecordId + "|" + sourceField + "|" + value;
        if (deduplicationKeys.add(key)) {
            target.add(new RuleFact(workRecordId, reviewStatus, sourceField, value));
        }
    }

    private void projectUsageFacts(TenantPrincipal principal, UUID recordId, ObjectNode value) {
        List<UUID> links = jdbc.queryForList("select usage_record_id from store_work_usage_report_link where tenant_id=:tenantId and work_record_id=:recordId",
                base(principal).addValue("recordId", recordId), UUID.class);
        if (!links.isEmpty()) recordId=links.getFirst();
        List<Map<String, Object>> usage = jdbc.queryForList("""
                select u.status,coalesce(c.no_usage,u.no_usage) as no_usage,u.reversal_reason,c.revision_no,c.reason as correction_reason
                from store_work_usage u left join lateral (
                    select no_usage,revision_no,reason from store_work_usage_correction
                    where tenant_id=u.tenant_id and work_record_id=u.work_record_id order by revision_no desc limit 1
                ) c on true where u.tenant_id=:tenantId and u.work_record_id=:recordId
                """, base(principal).addValue("recordId", recordId));
        if (usage.isEmpty()) return;
        Map<String, Object> row = usage.getFirst();
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select item_id,item_name,category,input_quantity,input_unit,base_quantity,base_unit
                from store_work_usage_current_line where tenant_id=:tenantId and work_record_id=:recordId order by item_id
                """, base(principal).addValue("recordId", recordId));
        String status = String.valueOf(row.get("status"));
        ObjectNode fact = value.putObject("inventoryUsage");
        fact.put("usageRecordId", recordId.toString());
        fact.put("noUsage", Boolean.TRUE.equals(row.get("no_usage")));
        ArrayNode quantities = fact.putArray("lines");
        for (Map<String, Object> line : lines) {
            quantities.addObject().put("itemId", line.get("item_id").toString())
                    .put("itemName", String.valueOf(line.get("item_name")))
                    .put("category", String.valueOf(line.get("category")))
                    .put("quantity", ((BigDecimal) line.get("base_quantity")).stripTrailingZeros().toPlainString())
                    .put("unit", String.valueOf(line.get("base_unit")));
        }
        fact.put("status", status);
        if (row.get("revision_no")!=null) {
            fact.put("correctionVersion", ((Number)row.get("revision_no")).intValue());
            fact.put("correctionReason", String.valueOf(row.get("correction_reason")));
        }
        fact.put("referenced", !links.isEmpty());
        fact.put("statusLabel", switch (status) {
            case "APPROVED" -> "耗用已审核";
            case "REJECTED" -> "已退回，未扣库";
            case "REVERSED" -> "已冲销，原库存及成本已退回";
            default -> "待审核，未扣库";
        });
        fact.put("summary", Boolean.TRUE.equals(row.get("no_usage")) ? "本次无耗用"
                : lines.stream().map(line -> line.get("item_name") + " "
                    + ((java.math.BigDecimal) line.get("input_quantity")).stripTrailingZeros().toPlainString() + " "
                    + line.get("input_unit") + ("LINEN".equals(line.get("category")) ? "（布草使用，不扣总库存）" : ""))
                    .collect(java.util.stream.Collectors.joining("；")));
        if (row.get("reversal_reason") != null) fact.put("reversalReason", String.valueOf(row.get("reversal_reason")));
        // Keep raw ids/unit versions in the immutable work record, not in human-readable report facts.
        ((ObjectNode) value.path("formData")).remove("inventoryQuantities");
    }

    private ObjectNode collectDailySummary(TenantPrincipal principal, UUID revisionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select result.value::text as value, result.source_summary::text as source,
                       item.label
                from daily_report_item_result result
                join daily_report_template_item item
                  on item.tenant_id = result.tenant_id and item.id = result.template_item_id
                where result.tenant_id = :tenantId and result.revision_id = :revisionId
                  and result.system_prefilled
                  and result.source_summary ->> 'sourceType' in ('WORK_RECORD','WORK_RECORD_COLLECTION')
                order by item.sort_order, item.id
                """, base(principal).addValue("revisionId", revisionId));
        return summarizeWorkResults(rows);
    }

    ObjectNode summarizeWorkResults(List<Map<String, Object>> rows) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", "FACT_ONLY_INTERNAL_RULES");
        ArrayNode workItems = summary.putArray("workItems");
        Set<String> recordIds = new LinkedHashSet<>();
        Set<String> usageIds = new LinkedHashSet<>();
        Map<String, ObjectNode> quantities = new LinkedHashMap<>();
        int excluded = 0;
        for (Map<String, Object> row : rows) {
            ObjectNode value = readObject(String.valueOf(row.get("value")));
            ObjectNode source = readObject(String.valueOf(row.get("source")));
            Iterable<JsonNode> entries = value.path("entries").isArray() ? value.path("entries") : List.of(value);
            for (JsonNode entry : entries) {
                if (!recordIds.add(entry.path("workRecordId").asText())) continue;
                ObjectNode work = workItems.addObject();
                work.put("label", String.valueOf(row.get("label")));
                work.put("status", entry.path("status").asText());
                work.put("completion", entry.path("completion").asText());
                work.put("exception", entry.path("exception").asText());
                work.put("nextAction", entry.path("nextAction").asText());
                work.put("evidenceCount", source.path("evidenceCount").asInt());
                work.set("formData", entry.path("formData").deepCopy());
                JsonNode usage = entry.path("inventoryUsage");
                String usageId = usage.path("usageRecordId").asText();
                if (usageId.isBlank() || !usageIds.add(usageId)) continue;
                String status = usage.path("status").asText();
                if (!Set.of("PENDING", "APPROVED").contains(status)) { excluded++; continue; }
                for (JsonNode line : usage.path("lines")) {
                    String key = line.path("itemId").asText() + "|" + line.path("unit").asText();
                    ObjectNode total = quantities.computeIfAbsent(key, ignored -> {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("itemName", line.path("itemName").asText());
                        item.put("category", line.path("category").asText());
                        item.put("unit", line.path("unit").asText());
                        item.put("approvedQuantity", "0");
                        item.put("pendingQuantity", "0");
                        return item;
                    });
                    String quantityKey = "APPROVED".equals(status) ? "approvedQuantity" : "pendingQuantity";
                    BigDecimal quantity = new BigDecimal(total.path(quantityKey).asText())
                            .add(new BigDecimal(line.path("quantity").asText()));
                    total.put(quantityKey, quantity.stripTrailingZeros().toPlainString());
                }
            }
        }
        ArrayNode usageItems = summary.putArray("usageItems");
        quantities.values().forEach(usageItems::add);
        summary.put("usageRecordCount", usageIds.size());
        summary.put("excludedUsageRecordCount", excluded);
        return summary;
    }

    private static String renderWorkSummary(ObjectNode summary) {
        List<String> entries = new ArrayList<>();
        for (JsonNode work : summary.path("workItems")) {
            String state = "APPROVED".equals(work.path("status").asText()) ? "已审核" : "已提交待审核";
            entries.add(work.path("label").asText() + "（" + state + "）：" + work.path("completion").asText());
        }
        return String.join("；", entries);
    }

    private ArrayNode toFactArray(List<RuleFact> facts) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RuleFact fact : facts) {
            ObjectNode item = array.addObject();
            item.put("workRecordId", fact.workRecordId());
            item.put("reviewStatus", fact.reviewStatus());
            item.put("sourceField", fact.sourceField());
            item.put("fact", fact.value());
        }
        return array;
    }

    private static String renderContentAnalysis(RuleContentFacts facts) {
        StringBuilder analysis = new StringBuilder();
        if (!facts.issueFacts().isEmpty()) {
            analysis.append(" 从填报内容识别到 ")
                    .append(facts.issueFacts().size()).append(" 条明确问题事实：")
                    .append(renderFacts(facts.issueFacts(), facts.issueFacts().size())).append("。");
        }
        if (!facts.declaredActions().isEmpty()) {
            analysis.append(" 填报人已明确 ")
                    .append(facts.declaredActions().size()).append(" 条后续动作：")
                    .append(renderFacts(facts.declaredActions(), facts.declaredActions().size())).append("。");
        }
        return analysis.toString();
    }

    private static String buildRuleRecommendation(
            int pendingReview,
            int exceptions,
            int missed,
            int completed,
            int expected,
            RuleContentFacts facts
    ) {
        List<String> clauses = new ArrayList<>();
        if (pendingReview > 0) {
            clauses.add("请主管优先复核待审核事项；审核通过后才计入完成。");
        }
        if (!facts.declaredActions().isEmpty()) {
            clauses.add("按填报人已明确的后续动作推进："
                    + renderFacts(facts.declaredActions(), 2) + "。");
        }
        if (!facts.issueFacts().isEmpty()) {
            clauses.add("请主管逐项核实上述明确问题，补充责任人、完成时限和复核结论。");
        }
        if (exceptions + missed > 0) {
            clauses.add("请优先核对异常和逾期事项，确认整改责任人与时限。");
        } else if (completed < expected) {
            clauses.add("尚有标准事项未完成，请按截止时间跟进。");
        } else if (expected == 0 && completed == 0 && pendingReview == 0) {
            clauses.add("暂无可汇总工作，请核对当日排班、日清任务及提交情况；暂无记录不代表已经完成。");
        } else if (facts.issueFacts().isEmpty()) {
            clauses.add("标准事项闭环正常，请补充其他工作后提交日报。");
        }
        return String.join("", clauses);
    }

    private static String renderFacts(List<RuleFact> facts, int limit) {
        List<String> rendered = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, facts.size()); index++) {
            RuleFact fact = facts.get(index);
            rendered.add("[工作记录·" + displayField(fact.sourceField()) + "] " + fact.value());
        }
        return String.join("；", rendered);
    }

    private static String displayField(String field) {
        return switch (field) {
            case "exception" -> "异常说明";
            case "nextAction" -> "后续动作";
            case "completion" -> "完成说明";
            case "formData.issueSummary" -> "异常说明";
            case "formData.issueFound" -> "发现异常";
            case "formData.workSummary" -> "工作结果";
            case "formData.complaintContent" -> "客诉内容";
            case "formData.initialHandling" -> "初步处理";
            default -> "填报内容";
        };
    }

    private static String textValue(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) return null;
        return value.isTextual() ? value.textValue() : value.asText();
    }

    private static String normalizeFact(String value) {
        if (value == null) return null;
        String compact = value.trim().replaceAll("\\s+", " ");
        if (compact.length() <= MAX_FACT_LENGTH) return compact;
        return compact.substring(0, MAX_FACT_LENGTH - 1) + "…";
    }

    private static boolean isMeaningfulFact(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !Set.of("无", "无问题", "无异常", "否", "没有", "不存在", "正常", "合格", "通过",
                "已完成", "已解决", "none", "no", "false", "null", "n/a", "na", "-", "0")
                .contains(normalized);
    }

    private static boolean isNegativeAnswer(String value) {
        if (value == null) return false;
        return Set.of("否", "不", "未", "不合格", "不正常", "未完成", "未达标", "未解决",
                "no", "false", "failed", "abnormal", "incomplete").contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsExplicitProblem(String value) {
        if (!isMeaningfulFact(value)) return false;
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("未发现任何问题", "")
                .replace("未发现问题", "")
                .replace("没有任何问题", "")
                .replace("没有问题", "")
                .replace("无任何问题", "")
                .replace("无问题", "")
                .replace("无任何异常", "")
                .replace("无异常", "");
        return containsAny(normalized,
                "未完成", "待处理", "待整改", "需整改", "需要整改", "待维修", "需维修",
                "无法", "失败", "异常", "故障", "缺失", "不足", "损坏", "投诉", "隐患", "逾期", "超时",
                "未达标", "不合格", "未解决", "存在问题", "发现问题", "有问题", "遗漏", "漏水", "漏电",
                "incomplete", "pending repair", "needs repair", "failed", "failure", "abnormal",
                "fault", "defect", "complaint", "overdue", "unresolved", "missing");
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s.\\[\\]]", "");
    }

    private static boolean containsAny(String value, String... markers) {
        if (value == null || value.isBlank()) return false;
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private int countAttachments(TenantPrincipal principal, UUID workRecordId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from attachment
                where tenant_id = :tenantId and work_record_id = :recordId
                  and scan_status <> 'REJECTED'
                """, base(principal).addValue("recordId", workRecordId), Integer.class);
        return count == null ? 0 : count;
    }

    private ObjectNode readObject(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("工作记录内容不是有效JSON对象", exception);
        }
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = TenantContext.require();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToFallback(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成规则分析摘要", exception);
        }
    }

    private record RuleFact(
            String workRecordId,
            String reviewStatus,
            String sourceField,
            String value
    ) {
    }

    private record RuleContentFacts(
            List<RuleFact> issueFacts,
            List<RuleFact> declaredActions
    ) {
    }

    private record ProjectableRecord(
            UUID templateItemId,
            UUID workRecordId,
            UUID assignmentId,
            UUID submittedByAccountId,
            UUID executorEmployeeId,
            String executorEmployeeName,
            String executorPositionName,
            String status,
            int seriesEntryNo,
            int attemptNo,
            String payload,
            String completionStatement,
            String exceptionStatement,
            String nextAction,
            String contentHash,
            java.time.OffsetDateTime occurredAt,
            java.time.OffsetDateTime reviewedAt,
            String reportPolicy,
            boolean aggregateAllRecords
    ) {
    }
}
