package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.storeassets.StoreWorkUsageService;
import cn.sifangguan.hotelaios.workdata.WorkDataModels;
import cn.sifangguan.hotelaios.workdata.WorkDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** The report is an entry to the existing daily-use workflow, not another inventory journal. */
@Service
public class DailyReportWorkEntryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final StoreWorkUsageService usage;
    private final WorkDataService work;
    private final ObjectMapper mapper;
    public DailyReportWorkEntryService(NamedParameterJdbcTemplate jdbc, AccessPolicy access,
            StoreWorkUsageService usage, WorkDataService work, ObjectMapper mapper) {
        this.jdbc=jdbc; this.access=access; this.usage=usage; this.work=work; this.mapper=mapper;
    }
    private MapSqlParameterSource params(UUID reportId) {
        return new MapSqlParameterSource("tenantId",access.principal().tenantId()).addValue("reportId",reportId);
    }
    public void lockDay(UUID reportId) {
        var rows=jdbc.queryForList("""
                select d.org_unit_id,d.employee_id,d.business_date from daily_report d
                join employee e on e.tenant_id=d.tenant_id and e.id=d.employee_id
                where d.tenant_id=:tenantId and d.id=:reportId and e.account_id=:actorId
                """,params(reportId).addValue("actorId",access.principal().actorId()));
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            usage.lockDay((UUID)row.get("org_unit_id"),(UUID)row.get("employee_id"),((java.sql.Date)row.get("business_date")).toLocalDate());
        }
    }
    private Map<String,Object> context(UUID reportId) {
        var rows=jdbc.queryForList("""
                select d.employee_id,d.position_assignment_id,d.business_date,a.org_unit_id,
                       x.id as expectation_id,i.work_package_version_id,x.work_package_item_id,
                       x.target_org_unit_id,i.form_version_id,i.item_code,i.item_type
                from daily_report d
                join employee_position_assignment a on a.tenant_id=d.tenant_id and a.id=d.position_assignment_id
                join work_expectation x on x.tenant_id=d.tenant_id and x.position_assignment_id=d.position_assignment_id
                  and x.business_date=d.business_date
                join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                where d.tenant_id=:tenantId and d.id=:reportId and i.item_code='HKA_DAILY_REPORT'
                  and i.work_package_version_id=d.work_package_version_id
                  and x.status not in ('CANCELLED','WAIVED')
                order by x.created_at desc limit 2
                """,params(reportId));
        if(rows.size()>1) throw new IllegalArgumentException("当日日报工作配置重复，请联系管理员核对，系统不会重复登记耗用");
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Map<String,Object> editableContext(UUID reportId) {
        var ctx=context(reportId);
        if(ctx==null) return null;
        if(!Objects.equals(ctx.get("position_assignment_id"),access.principal().businessActorAssignmentId())) return null;
        var catalog=usage.catalogue((UUID)ctx.get("target_org_unit_id"),((java.sql.Date)ctx.get("business_date")).toLocalDate(),"HKA_DAILY_REPORT");
        var existing=jdbc.queryForList("""
                select r.id from work_record r join store_work_usage u on u.tenant_id=r.tenant_id and u.work_record_id=r.id
                where r.tenant_id=:tenantId and r.work_expectation_id=:expectationId and u.status in ('PENDING','APPROVED')
                order by r.created_at desc limit 1
                """,params(reportId).addValue("expectationId",ctx.get("expectation_id")),UUID.class);
        if(!existing.isEmpty()) catalog.put("reference_usage",usage.detail(existing.getFirst()));
        boolean legacyDraft=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_record where tenant_id=:tenantId and work_expectation_id=:expectationId and status='DRAFT')",
                params(reportId).addValue("expectationId",ctx.get("expectation_id")),Boolean.class));
        return Map.of("catalogue",catalog,"expectationId",ctx.get("expectation_id"),"legacyDraft",legacyDraft);
    }
    /** Called in the report submission transaction. Failures roll back both report and work submission. */
    public void submit(UUID reportId, JsonNode snapshot) {
        var ctx=context(reportId);
        if(ctx==null) {
            if(snapshot.hasNonNull("inventoryQuantities")) throw new IllegalArgumentException("本日报未关联可填写耗用的工作，请核对岗位与营业日");
            return;
        }
        var existing=jdbc.queryForList("""
                select id,status from work_record where tenant_id=:tenantId and work_expectation_id=:expectationId
                  and status in ('DRAFT','SUBMITTED','APPROVED') order by created_at desc limit 1
                """,params(reportId).addValue("expectationId",ctx.get("expectation_id")));
        if(!existing.isEmpty()) {
            if("DRAFT".equals(existing.getFirst().get("status")))
                throw new IllegalArgumentException("日清入口已有未提交草稿，请先完成该草稿，再返回日报刷新关联数量");
            // Historical work already submitted: use its immutable evidence; never submit/post again.
            if(snapshot.hasNonNull("inventoryQuantities")) {
                JsonNode input=snapshot.path("inventoryQuantities");
                var referenced=usage.detail((UUID)existing.getFirst().get("id"));
                if(!Set.of("PENDING","APPROVED").contains(Objects.toString(referenced.get("status"),""))
                        || !Objects.toString(referenced.get("work_record_id"),"").equals(input.path("referenceRecordId").asText())
                        || input.has("lines") || input.path("schemaVersion").asInt()!=1)
                    throw new IllegalArgumentException("日报工作已提交，请刷新关联用量；数量更正须通过耗用审核人留痕处理");
            }
            return;
        }
        ObjectNode payload=mapper.createObjectNode();
        payload.put("workSummary","岗位日报提交，详见关联日报及当日工作证据");
        payload.put("issueFound",false);
        payload.put("dailyReportId",reportId.toString());
        if(snapshot.hasNonNull("inventoryQuantities")) payload.set("inventoryQuantities",snapshot.get("inventoryQuantities"));
        JsonNode counts=snapshot.path("roomCounts");
        for(String key:List.of("checkoutRooms","stayoverRooms")) {
            JsonNode count=counts.path(key);
            if(!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt()<0)
                throw new IllegalArgumentException("请填写退房打扫数和续住房打扫数（非负整数）");
            payload.set(key,count);
        }
        work.submit(new WorkDataModels.SubmitWorkRecord((UUID)ctx.get("org_unit_id"),(UUID)ctx.get("employee_id"),
                (UUID)ctx.get("position_assignment_id"),(UUID)ctx.get("form_version_id"),
                ((java.sql.Date)ctx.get("business_date")).toLocalDate(),payload,"已提交岗位日报",null,null,
                (UUID)ctx.get("work_package_version_id"),(UUID)ctx.get("work_package_item_id"),(UUID)ctx.get("expectation_id"),
                (String)ctx.get("item_type"),(UUID)ctx.get("target_org_unit_id"),null,null,false));
    }
}
