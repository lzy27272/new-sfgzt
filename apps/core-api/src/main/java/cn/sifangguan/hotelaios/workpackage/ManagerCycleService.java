package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy;
import cn.sifangguan.hotelaios.workdata.WorkDataModels;
import cn.sifangguan.hotelaios.workdata.WorkDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class ManagerCycleService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TenantDatabaseContext database;
    private final WorkVisibilityPolicy visibility;
    private final WeeklyAssessmentService assessments;
    private final WorkDataService records;
    private final ObjectMapper mapper;
    public ManagerCycleService(NamedParameterJdbcTemplate jdbc, AccessPolicy access, TenantDatabaseContext database,
            WorkVisibilityPolicy visibility, WeeklyAssessmentService assessments, WorkDataService records, ObjectMapper mapper) {
        this.jdbc=jdbc; this.access=access; this.database=database; this.visibility=visibility;
        this.assessments=assessments; this.records=records; this.mapper=mapper;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(String period, LocalDate date) {
        access.requireAnyPermission("work-record.read","work-record.submit");
        var p=access.principal(); database.apply(p.tenantId());
        String timezone=jdbc.queryForObject("select timezone from tenant where id=:id",Map.of("id",p.tenantId()),String.class);
        LocalDate today=LocalDate.now(ZoneId.of(timezone));
        LocalDate start=CycleSchedule.start(period,date==null ? today : date);
        LocalDate end=CycleSchedule.end(period,start);
        var params=new MapSqlParameterSource("tenantId",p.tenantId()).addValue("start",start).addValue("end",end).addValue("period",period);
        String own=visibility.scope(p,params).personal("x");
        var rows=jdbc.queryForList("""
                select x.id,x.status,x.period_start,x.period_end,x.due_at,x.position_assignment_id,
                       i.item_code,i.name,i.description,i.sort_order, h.name as hotel_name,
                       r.id as record_id,r.status as record_status,r.submitted_at,r.payload::text as payload,
                       f.json_schema::text as form_schema,
                       review_policy.mode as review_mode,
                       case when r.status in ('REJECTED','RETURNED') or x.status='FAILED'
                           then case when x.due_at<=now() then 'OVERDUE' else 'PENDING' end
                         when r.status='SUBMITTED' and (review_policy.mode='MANUAL'
                           or (review_policy.mode='STANDARD_EVALUATION' and x.status<>'SATISFIED')) then 'REVIEW'
                         when x.status='SATISFIED' or r.status='APPROVED'
                           or (review_policy.mode='NONE' and x.status='SUBMITTED') then 'COMPLETED'
                         when x.status='SUBMITTED' then 'REVIEW'
                         when x.status in ('MISSED','OVERDUE') or x.due_at<=now() then 'OVERDUE' else 'PENDING' end as board_status,
                       coalesce(r.submitted_at>x.due_at,false) as late,
                       (select count(*) from attachment a where a.tenant_id=x.tenant_id and a.work_record_id=r.id) as evidence_count
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join org_unit h on h.tenant_id=x.tenant_id and h.id=x.target_org_unit_id
                join form_version f on f.tenant_id=i.tenant_id and f.id=i.form_version_id
                cross join lateral (select
                """+WorkReviewPolicy.effectiveModeSql("i","x")+"""
                  as mode) review_policy
                left join lateral(select r.* from work_record r where r.tenant_id=x.tenant_id and r.work_expectation_id=x.id
                  order by r.series_entry_no desc,r.attempt_no desc,r.id desc limit 1) r on true
                where x.tenant_id=:tenantId and x.period_start=:start and i.period_type=:period
                  and coalesce((i.execution_policy->>'managerCycle')::boolean,false) and x.status not in ('CANCELLED','WAIVED')
                  and (
                """+own+") order by i.sort_order,x.id",params);
        for (var row:rows) {
            for (String key:List.of("payload","form_schema")) {
                Object raw=row.get(key);
                try { row.put(key,raw==null ? null : mapper.readTree(raw.toString())); }
                catch (Exception e) { throw new IllegalArgumentException("周期记录内容读取失败",e); }
            }
        }
        var counts=new LinkedHashMap<String,Long>();
        for (String state:List.of("PENDING","REVIEW","COMPLETED","OVERDUE"))
            counts.put(state,rows.stream().filter(r -> state.equals(r.get("board_status"))).count());
        Integer carryover=jdbc.queryForObject("""
                select count(*) from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                where x.tenant_id=:tenantId and x.period_start<:start and i.period_type=:period
                  and coalesce((i.execution_policy->>'managerCycle')::boolean,false)
                  and x.status in ('AVAILABLE','IN_PROGRESS','FAILED','MISSED') and (
                """+own+")",params,Integer.class);
        return Map.of("period",period,"start",start,"end",end,"today",today,"items",rows,"counts",counts,"carryover",carryover);
    }
    @Transactional
    public Map<String,Object> submitAssessment(UUID expectation) {
        var x=assessments.context(expectation,true);
        return records.submit(new WorkDataModels.SubmitWorkRecord((UUID)x.get("org_unit_id"),(UUID)x.get("employee_id"),
                (UUID)x.get("position_assignment_id"),(UUID)x.get("form_version_id"),LocalDate.parse(x.get("business_date").toString()),
                assessments.snapshot(expectation),null,null,null,(UUID)x.get("work_package_version_id"),
                (UUID)x.get("work_package_item_id"),expectation,"SCHEDULED",(UUID)x.get("target_org_unit_id"),null,null,false));
    }
}
