package cn.sifangguan.hotelaios.operations;

import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.events.TenantSystemAccountResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class OperationsPlanReminderService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final TenantSystemAccountResolver system;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    public OperationsPlanReminderService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,
            TenantSystemAccountResolver system,NotificationService notifications,ObjectMapper mapper) {
        this.jdbc=jdbc;this.database=database;this.system=system;this.notifications=notifications;this.mapper=mapper;
    }
    @Transactional
    public int processTenant(UUID tenant,OffsetDateTime now) {
        UUID account=system.resolveOrCreate(tenant);var before=TenantContext.current().orElse(null);
        TenantContext.set(new TenantPrincipal(tenant,account,"SYSTEM_AUTOMATION",Set.of("SYSTEM_AUTOMATION"),Set.of(),Set.of(),Set.of(),true,UUID.randomUUID()));
        try {
            database.apply(tenant);var args=new MapSqlParameterSource("tenant",tenant);
            ZoneId zone=ZoneId.of(jdbc.queryForObject("select timezone from tenant where id=:tenant",args,String.class));
            var owners=jdbc.queryForList("""
                    select distinct a.id from employee_position_assignment a
                    join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                    join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
                    join position_definition p on p.tenant_id=a.tenant_id and p.id=a.position_id
                    join position_function_profile f on f.tenant_id=p.tenant_id and f.position_id=p.id and f.scope_type='GROUP'
                    join position_function_profile_version v on v.tenant_id=f.tenant_id and v.profile_id=f.id and v.lifecycle_status='PUBLISHED'
                    where a.tenant_id=:tenant and a.status='ACTIVE' and a.valid_from<=:today
                      and (a.valid_to is null or a.valid_to>=:today) and e.employment_status='ACTIVE' and e.deleted_at is null
                      and p.status='ACTIVE' and p.deleted_at is null and p.permanently_deleted_at is null
                      and p.code in ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER')
                      and v.version_no=(select max(latest.version_no) from position_function_profile_version latest
                        where latest.tenant_id=f.tenant_id and latest.profile_id=f.id and latest.lifecycle_status='PUBLISHED')
                      and exists(select 1 from position_function_profile_permission pp join permission permission on permission.id=pp.permission_id
                        where pp.tenant_id=v.tenant_id and pp.profile_version_id=v.id and permission.code='work-plan.submit')
                    """,args.addValue("today",now.atZoneSameInstant(zone).toLocalDate()),UUID.class);
            LocalDate today=now.atZoneSameInstant(zone).toLocalDate();
            for (UUID owner:owners) {
                for (String type:List.of("WEEKLY","MONTHLY")) ensureDraft(tenant,owner,type,today,zone);
                // Prepare an empty next-month plan at month end; facts remain explicitly requested and live.
                if (today.equals(today.withDayOfMonth(today.lengthOfMonth())))
                    ensureDraft(tenant,owner,"MONTHLY",today.plusDays(1),zone);
            }
            var plans=jdbc.queryForList("""
                    select p.*,a.manager_assignment_id from management_work_plan p
                    join employee_position_assignment a on a.tenant_id=p.tenant_id and a.id=p.owner_assignment_id
                    where p.tenant_id=:tenant and p.operations_plan and p.status in ('DRAFT','REJECTED')
                      and p.owner_assignment_id in (:owners)
                      and p.due_at between :fromTime and :toTime
                    """,args.addValue("owners",owners.isEmpty()?List.of(new UUID(0,0)):owners)
                    .addValue("fromTime",now.minusDays(14)).addValue("toTime",now.plusDays(32)));
            int count=0;
            for (var plan:plans) {
                UUID id=(UUID)plan.get("id"),owner=(UUID)plan.get("owner_assignment_id");
                LocalDate start=LocalDate.parse(plan.get("period_start").toString());String type=plan.get("plan_type").toString();
                var moments=OperationsPlanTiming.moments(type,start,zone);
                for (int index=0;index<3;index++) {
                    var moment=moments.get(index);var until=index<2?(index==0?moments.get(1):OperationsPlanTiming.due(type,start,zone)):moment.plusDays(1);
                    if (now.isBefore(moment)||!now.isBefore(until)) continue;
                    UUID recipient=index<2?owner:eligibleManager(tenant,owner);
                    if (recipient==null) continue;
                    notifications.createForAssignment(recipient,index<2?"OPERATIONS_PLAN_REMINDER":"OPERATIONS_PLAN_OVERDUE",
                            index<2?"运营工作计划待填报":"直属下级运营计划逾期",
                            "WEEKLY".equals(type)?"本周总结与下周计划截止周日24:00，请按负责门店核对填报。":"月运营计划截止每月5日24:00，补交不会覆盖原提交时间。",
                            "WORK_PLAN",id,"operations-plan:reminder:"+id+":"+index);
                    count++;
                }
            }
            return count;
        } finally {if(before==null)TenantContext.clear();else TenantContext.set(before);}
    }
    private void ensureDraft(UUID tenant,UUID owner,String type,LocalDate today,ZoneId zone) {
        var args=new MapSqlParameterSource("tenant",tenant).addValue("owner",owner);
        var hotels=jdbc.queryForList("""
                select s.hotel_org_unit_id from employee_assignment_hotel_scope s join org_unit h on h.tenant_id=s.tenant_id and h.id=s.hotel_org_unit_id
                where s.tenant_id=:tenant and s.assignment_id=:owner and h.status='ACTIVE' and h.unit_type='HOTEL' order by h.id
                """,args,UUID.class);
        if (hotels.isEmpty()) return;
        LocalDate start=OperationsPlanTiming.start(type,today);
        var ids=jdbc.queryForList("""
                insert into management_work_plan(tenant_id,owner_assignment_id,plan_type,period_start,period_end,operations_plan,due_at)
                select :tenant,:owner,:type,:start,:end,true,:due from employee_position_assignment a
                join position_definition p on p.tenant_id=a.tenant_id and p.id=a.position_id
                where a.tenant_id=:tenant and a.id=:owner
                  and :due>=greatest(a.valid_from::timestamp at time zone :timezone,p.created_at)
                on conflict(tenant_id,owner_assignment_id,plan_type,period_start) do nothing returning id
                """,args.addValue("type",type).addValue("start",start).addValue("end",OperationsPlanTiming.end(type,start))
                .addValue("due",OperationsPlanTiming.due(type,start,zone)).addValue("timezone",zone.getId()),UUID.class);
        if (ids.isEmpty()) return;
        UUID id=ids.getFirst(),revision=UUID.randomUUID();var payload=mapper.createObjectNode();
        payload.put("automaticDraft",true);payload.set("hotelIds",mapper.valueToTree(hotels));
        payload.set("content",mapper.valueToTree(new OperationsPlanModels.Draft("","","",Map.of(),List.of(),null,null)));
        jdbc.update("""
                insert into management_work_plan_revision(id,tenant_id,work_plan_id,revision_no,created_by_assignment_id,operations_payload)
                values(:revision,:tenant,:id,1,:owner,cast(:payload as jsonb))
                """,args.addValue("revision",revision).addValue("id",id).addValue("payload",payload.toString()));
        jdbc.update("update management_work_plan set current_revision_id=:revision where tenant_id=:tenant and id=:id",args);
    }
    private UUID eligibleManager(UUID tenant,UUID owner) {
        var rows=jdbc.queryForList("""
                select distinct leader.id from employee_position_assignment a join employee_position_assignment leader on leader.tenant_id=a.tenant_id
                  and (leader.id=a.manager_assignment_id or (a.manager_assignment_id is null and exists(
                    select 1 from position_assignment_reporting_relation r where r.tenant_id=a.tenant_id and r.subordinate_assignment_id=a.id
                      and r.leader_assignment_id=leader.id and r.valid_from<=current_date and (r.valid_to is null or r.valid_to>=current_date))))
                join employee e on e.tenant_id=leader.tenant_id and e.id=leader.employee_id
                join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
                where a.tenant_id=:tenant and a.id=:owner and leader.status='ACTIVE' and leader.valid_from<=current_date
                  and (leader.valid_to is null or leader.valid_to>=current_date) and e.employment_status='ACTIVE' and e.deleted_at is null
                  and e.id<>a.employee_id and e.account_id is distinct from (select account_id from employee where tenant_id=a.tenant_id and id=a.employee_id)
                """,new MapSqlParameterSource("tenant",tenant).addValue("owner",owner),UUID.class);
        return rows.size()==1?rows.getFirst():null;
    }
}
