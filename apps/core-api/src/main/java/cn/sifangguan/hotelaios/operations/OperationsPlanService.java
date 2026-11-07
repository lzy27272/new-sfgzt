package cn.sifangguan.hotelaios.operations;

import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkReviewPolicy;
import cn.sifangguan.hotelaios.tasks.TaskModels;
import cn.sifangguan.hotelaios.tasks.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class OperationsPlanService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final ObjectMapper mapper;
    private final TaskService tasks;
    private final NotificationService notifications;
    private final AuditWriter audit;

    public OperationsPlanService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,
            ObjectMapper mapper,TaskService tasks,NotificationService notifications,AuditWriter audit) {
        this.jdbc=jdbc; this.database=database; this.access=access; this.mapper=mapper;
        this.tasks=tasks; this.notifications=notifications; this.audit=audit;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> list(String type,LocalDate date,String scope) {
        access.requireAnyPermission("work-plan.read","work-plan.submit","work-plan.team-read","work-plan.review");
        var p=prepare();
        String normalized=OperationsPlanTiming.type(type);
        LocalDate start=OperationsPlanTiming.start(normalized,date==null ? LocalDate.now(zone(p)) : date);
        boolean team="REVIEW".equals(scope);
        if (!List.of("OWN","REVIEW").contains(scope)) throw new IllegalArgumentException("计划列表范围必须为OWN或REVIEW");
        if (team) access.requireAnyPermission("work-plan.team-read","work-plan.review");
        UUID own=p.businessActorAssignmentId();
        var rows=jdbc.queryForList("""
                select plan.id from management_work_plan plan
                join employee_position_assignment owner on owner.tenant_id=plan.tenant_id and owner.id=plan.owner_assignment_id
                join employee owner_employee on owner_employee.tenant_id=owner.tenant_id and owner_employee.id=owner.employee_id
                left join management_work_plan_revision revision on revision.tenant_id=plan.tenant_id and revision.id=plan.current_revision_id
                where plan.tenant_id=:tenant and plan.operations_plan and plan.plan_type=:type and plan.period_start=:start
                  and ((not :team and plan.owner_assignment_id=:own) or (:team
                    and owner_employee.account_id is distinct from :actor
                    and exists(select 1 from employee_position_assignment reviewer
                      join employee re on re.tenant_id=reviewer.tenant_id and re.id=reviewer.employee_id
                      join user_account account on account.tenant_id=re.tenant_id and account.id=re.account_id and account.status='ACTIVE'
                      join position_definition position on position.tenant_id=reviewer.tenant_id and position.id=reviewer.position_id
                      where reviewer.tenant_id=plan.tenant_id and reviewer.id=:own and re.account_id=:actor
                        and reviewer.employee_id<>owner.employee_id and reviewer.status='ACTIVE' and reviewer.valid_from<=current_date
                        and (reviewer.valid_to is null or reviewer.valid_to>=current_date) and re.employment_status='ACTIVE' and re.deleted_at is null
                        and position.status='ACTIVE' and position.deleted_at is null and position.permanently_deleted_at is null
                        and (owner.manager_assignment_id=reviewer.id or exists(select 1 from position_assignment_reporting_relation relation
                          where relation.tenant_id=owner.tenant_id and relation.subordinate_assignment_id=owner.id and relation.leader_assignment_id=reviewer.id
                            and relation.valid_from<=current_date and (relation.valid_to is null or relation.valid_to>=current_date))))
                    and jsonb_array_length(coalesce(revision.operations_payload->'hotelIds','[]'::jsonb))>0
                    and (:tenantScope or not exists(select 1 from jsonb_array_elements_text(revision.operations_payload->'hotelIds') snapshot(hotel)
                      where snapshot.hotel::uuid not in (:scopes)))))
                order by plan.updated_at desc,plan.id limit 200
                """,params(p).addValue("type",normalized).addValue("start",start).addValue("team",team).addValue("own",own)
                .addValue("actor",p.actorId()).addValue("tenantScope",p.hasTenantScope())
                .addValue("scopes",p.orgScopes().isEmpty()?Set.of(new UUID(0,0)):p.orgScopes()));
        List<Map<String,Object>> result=new ArrayList<>();
        for (var row:rows) {
            var plan=plan(p,(UUID)row.get("id"),false);
            if (!team || canReadTeam(p,plan)) result.add(project(p,plan,false));
        }
        return Map.of("items",result,"type",normalized,"periodStart",start);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID id) {
        access.requireAnyPermission("work-plan.read","work-plan.submit","work-plan.team-read","work-plan.review");
        var p=prepare(); var row=plan(p,id,false); requireRead(p,row); return project(p,row);
    }

    @Transactional
    public Map<String,Object> save(String type,LocalDate date,OperationsPlanModels.Draft content) {
        access.requirePermission("work-plan.submit"); var p=prepare(); UUID owner=owner(p);
        List<UUID> hotels=hotels(p,owner);
        if (hotels.isEmpty()) throw new IllegalArgumentException("请先在人员与任职中配置负责门店");
        String normalized=OperationsPlanTiming.type(type); LocalDate start=OperationsPlanTiming.start(normalized,date);
        var args=params(p).addValue("owner",owner).addValue("type",normalized).addValue("start",start)
                .addValue("end",OperationsPlanTiming.end(normalized,start))
                .addValue("due",OperationsPlanTiming.due(normalized,start,zone(p)));
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,122))",
                args.addValue("key",p.tenantId()+":"+owner+":"+normalized+":"+start),(rs,n)->true);
        List<UUID> ids=jdbc.queryForList("""
                insert into management_work_plan(tenant_id,owner_assignment_id,plan_type,period_start,period_end,operations_plan,due_at)
                values(:tenant,:owner,:type,:start,:end,true,:due)
                on conflict(tenant_id,owner_assignment_id,plan_type,period_start) do update
                  set updated_at=management_work_plan.updated_at
                returning id
                """,args,UUID.class);
        UUID id=ids.getFirst(); var row=plan(p,id,true);
        if (content==null) throw new IllegalArgumentException("请填写计划内容");
        UUID expected=content.expectedRevisionId();
        if (row.get("current_revision_id")!=null && (!Objects.equals(expected,row.get("current_revision_id"))
                || content.expectedDraftVersion()==null || content.expectedDraftVersion().longValue()!=((Number)row.get("operations_draft_version")).longValue()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"计划已被其他页面修改，请保留本机草稿并刷新对比");
        if (!Boolean.TRUE.equals(row.get("operations_plan"))) throw new IllegalArgumentException("该周期已有其他工作计划，请联系管理员核对");
        if ("PENDING_APPROVAL".equals(row.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"计划正在审核，请等待退回后修改");
        UUID revision=(UUID)row.get("current_revision_id");
        if (revision==null || !"DRAFT".equals(row.get("revision_status"))) {
            revision=UUID.randomUUID(); int number=((Number)row.getOrDefault("revision_no",0)).intValue()+1;
            jdbc.update("""
                    insert into management_work_plan_revision(id,tenant_id,work_plan_id,revision_no,created_by_assignment_id)
                    values(:revision,:tenant,:id,:number,:owner)
                    """,args.addValue("revision",revision).addValue("id",id).addValue("number",number));
            jdbc.update("update management_work_plan set current_revision_id=:revision,status='DRAFT',updated_at=now() where tenant_id=:tenant and id=:id",args);
        }
        if (content.items()!=null && content.items().size()>100) throw new IllegalArgumentException("每份计划最多100项行动");
        var payload=mapper.createObjectNode(); payload.set("content",mapper.valueToTree(content)); payload.set("hotelIds",mapper.valueToTree(hotels));
        jdbc.update("""
                update management_work_plan_revision set summary=:summary,operations_payload=cast(:payload as jsonb),
                  operations_draft_version=operations_draft_version+1,updated_at=now()
                where tenant_id=:tenant and id=:revision and status='DRAFT'
                """,args.addValue("revision",revision).addValue("summary",content.summary()).addValue("payload",payload.toString()));
        return project(p,plan(p,id,false));
    }

    @Transactional
    public Map<String,Object> submit(UUID id) {
        access.requirePermission("work-plan.submit");var p=prepare();UUID own=owner(p);var row=plan(p,id,true);
        if (!own.equals(row.get("owner_assignment_id"))) throw new AccessDeniedException("只能提交当前任职本人的计划");
        if ("PENDING_APPROVAL".equals(row.get("status")) || "APPROVED".equals(row.get("status"))) return project(p,row);
        if (!"DRAFT".equals(row.get("revision_status"))) throw new IllegalArgumentException("请先保存新的草稿版本");
        OperationsPlanModels.Draft draft=content(row); validate(p,row,draft,false);
        UUID manager=manager(p,own);
        if (manager==null) throw new IllegalArgumentException("请先在人员与任职中配置唯一有效的直属上级，不能指定本人或同账号其他任职");
        UUID revision=(UUID)row.get("current_revision_id");int n=0;
        for (var item:items(draft)) jdbc.update("""
                insert into management_work_plan_item(tenant_id,revision_id,item_no,title,description,target_assignment_id,due_at,
                  action_id,hotel_id,acceptance_criteria,linked_task_id,linked_expectation_id)
                values(:tenant,:revision,:n,:title,:description,:assignee,:due,:action,:hotel,:criteria,:task,:expectation)
                """,params(p).addValue("revision",revision).addValue("n",++n).addValue("title",item.title())
                .addValue("description",item.description()).addValue("assignee",item.assigneeAssignmentId()).addValue("due",item.dueAt())
                .addValue("action",item.actionId()).addValue("hotel",item.hotelId()).addValue("criteria",item.acceptanceCriteria())
                .addValue("task",item.linkedTaskId()).addValue("expectation",item.linkedExpectationId()));
        jdbc.update("update management_work_plan_revision set status='SUBMITTED',submitted_at=now(),updated_at=now() where tenant_id=:tenant and id=:revision",
                params(p).addValue("revision",revision));
        jdbc.update("update management_work_plan set status='PENDING_APPROVAL',updated_at=now() where tenant_id=:tenant and id=:id",params(p).addValue("id",id));
        if (manager!=null) notifications.createForAssignment(manager,"OPERATIONS_PLAN_REVIEW_PENDING","运营工作计划待审核",
                "直属下级已提交工作计划，请核对行动项、截止与验收标准。","WORK_PLAN",id,"operations-plan:submit:"+revision);
        audit.record("OPERATIONS_PLAN_SUBMITTED","WORK_PLAN",id,"{\"revisionId\":\""+revision+"\"}");
        return project(p,plan(p,id,false));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> preview(UUID id) {
        access.requireAnyPermission("work-plan.read","work-plan.submit","work-plan.review");
        var p=prepare();var row=plan(p,id,false);requireRead(p,row);
        boolean allowed=canReview(p,row); var draft=content(row);
        if (allowed) validate(p,row,draft,true);
        List<Map<String,Object>> result=projectItems(p,row,draft);
        long create=result.stream().filter(i->"CREATE".equals(i.get("mode"))).count();
        var response=new LinkedHashMap<String,Object>();response.put("planId",id);response.put("revisionId",row.get("current_revision_id"));
        response.put("approverAssignmentId",allowed?p.businessActorAssignmentId():manager(p,(UUID)row.get("owner_assignment_id")));
        response.put("canApprove",allowed);response.put("newTaskCount",create);response.put("linkedCount",result.size()-create);response.put("items",result);
        return response;
    }

    @Transactional
    public Map<String,Object> review(UUID id,OperationsPlanModels.Review request) {
        access.requirePermission("work-plan.review"); var p=prepare();var row=plan(p,id,true);
        if (!canReview(p,row)) throw new AccessDeniedException("仅有效上级可在授权门店范围审批，不能审核本人或同账号其他任职");
        if (request==null || !List.of("APPROVE","RETURN").contains(request.decision())) throw new IllegalArgumentException("请选择通过或退回");
        if (request.expectedRevisionId()==null || !request.expectedRevisionId().equals(row.get("current_revision_id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"计划修订已变化，请重新查看最新审批预览");
        if ("APPROVED".equals(row.get("status")) && "APPROVE".equals(request.decision())) return project(p,row);
        if (!"PENDING_APPROVAL".equals(row.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"计划状态已变化，请刷新");
        if ("RETURN".equals(request.decision()) && blank(request.comment())) throw new IllegalArgumentException("退回时请填写原因");
        UUID revision=(UUID)row.get("current_revision_id"), reviewer=access.requireBusinessActorAssignment();
        if ("APPROVE".equals(request.decision())) { access.requireAnyPermission("task.create","task.assign");access.requirePermission("task.dispatch");access.requirePermission("task.review");validate(p,row,content(row),true); }
        UUID reviewId=UUID.randomUUID();
        jdbc.update("""
                insert into management_work_plan_review(id,tenant_id,revision_id,reviewer_assignment_id,action,comment)
                values(:review,:tenant,:revision,:reviewer,:decision,:comment)
                """,params(p).addValue("review",reviewId).addValue("revision",revision).addValue("reviewer",reviewer)
                .addValue("decision",request.decision()).addValue("comment",request.comment()));
        if ("APPROVE".equals(request.decision())) {
            UUID planOwner=(UUID)row.get("owner_assignment_id");
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,122))",params(p).addValue("key",p.tenantId()+":actions:"+planOwner),(rs,n)->true);
            validate(p,row,content(row),true);
            for (var item:items(content(row))) {
                var existing=action(p,planOwner,item.actionId());
                if (item.linkedTaskId()!=null || item.linkedExpectationId()!=null || !existing.isEmpty()) continue;
                var persisted=jdbc.queryForMap("select id from management_work_plan_item where tenant_id=:tenant and revision_id=:revision and action_id=:action",
                        params(p).addValue("revision",revision).addValue("action",item.actionId()));
                UUID decision=UUID.randomUUID();
                jdbc.update("""
                        insert into management_work_plan_item_decision(id,tenant_id,review_id,item_id,content_decision,due_decision,reminder_decision,
                          approved_title,approved_description,approved_due_at)
                        values(:decision,:tenant,:review,:item,'ACCEPT','ACCEPT','ACCEPT',:title,:description,:due)
                        """,params(p).addValue("decision",decision).addValue("review",reviewId).addValue("item",persisted.get("id"))
                        .addValue("title",item.title()).addValue("description",item.description()).addValue("due",item.dueAt()));
                var source=mapper.createObjectNode().put("planId",id.toString()).put("revisionId",revision.toString())
                        .put("actionId",item.actionId().toString()).put("acceptanceCriteria",item.acceptanceCriteria()).put("reviewMode","MANUAL");
                UUID task=tasks.createFromWorkPlan(new TaskModels.CreateTask(item.hotelId(),item.assigneeAssignmentId(),reviewer,
                        null,null,item.title(),(item.description()==null?"":item.description())+"\n验收标准："+item.acceptanceCriteria(),
                        "NORMAL",item.dueAt(),source,reviewer,true),"operations-plan:"+planOwner+":"+item.actionId());
                jdbc.update("insert into management_work_plan_task_link(tenant_id,item_decision_id,task_id) values(:tenant,:decision,:task)",
                        params(p).addValue("decision",decision).addValue("task",task));
                jdbc.update("""
                        insert into operations_plan_action(tenant_id,owner_assignment_id,action_id,hotel_id,task_id,first_plan_id)
                        values(:tenant,:owner,:action,:hotel,:task,:id)
                        """,params(p).addValue("owner",planOwner).addValue("action",item.actionId()).addValue("hotel",item.hotelId()).addValue("task",task).addValue("id",id));
            }
        }
        String next="APPROVE".equals(request.decision())?"APPROVED":"RETURNED";
        jdbc.update("update management_work_plan_revision set status=:status,decided_at=now(),updated_at=now() where tenant_id=:tenant and id=:revision",
                params(p).addValue("status",next).addValue("revision",revision));
        jdbc.update("update management_work_plan set status=:status,updated_at=now() where tenant_id=:tenant and id=:id",
                params(p).addValue("status","APPROVE".equals(request.decision())?"APPROVED":"REJECTED").addValue("id",id));
        audit.record("OPERATIONS_PLAN_"+next,"WORK_PLAN",id,"{\"revisionId\":\""+revision+"\"}");
        return project(p,plan(p,id,false));
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> targets(UUID hotel) {
        access.requirePermission("work-plan.submit");var p=prepare();UUID own=owner(p);
        if (!hotels(p,own).contains(hotel)) throw new AccessDeniedException("该门店不在本人负责范围");
        return jdbc.queryForList("""
                select a.id as "assignmentId",e.name as "employeeName",d.name as "positionName",:hotel::uuid as "hotelId"
                from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id
                join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
                where a.tenant_id=:tenant and (a.id=:owner or a.manager_assignment_id=:owner or exists(
                  select 1 from position_assignment_reporting_relation r where r.tenant_id=a.tenant_id and r.subordinate_assignment_id=a.id
                    and r.leader_assignment_id=:owner and r.valid_from<=current_date and (r.valid_to is null or r.valid_to>=current_date)))
                  and a.status='ACTIVE' and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                  and e.employment_status='ACTIVE' and e.deleted_at is null and d.status='ACTIVE' and d.deleted_at is null
                  and (exists(select 1 from employee_assignment_hotel_scope s where s.tenant_id=a.tenant_id and s.assignment_id=a.id and s.hotel_org_unit_id=:hotel)
                    or exists(select 1 from org_unit_closure c where c.tenant_id=a.tenant_id and c.ancestor_id=:hotel and c.descendant_id=a.org_unit_id))
                order by e.name,a.id
                """,params(p).addValue("hotel",hotel).addValue("owner",own));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> prefill(String type,LocalDate date) {
        access.requirePermission("work-plan.submit");var p=prepare();UUID own=owner(p);var hs=hotels(p,own);
        if (hs.isEmpty()) throw new IllegalArgumentException("请先配置负责门店");
        LocalDate requestedStart=OperationsPlanTiming.start(type,date);
        LocalDate start="MONTHLY".equals(type)?requestedStart.minusMonths(1):requestedStart;
        LocalDate end="MONTHLY".equals(type)?requestedStart.minusDays(1):OperationsPlanTiming.end(type,start);
        var facts=jdbc.queryForMap("""
                select count(*) as "expectedCount",count(*) filter(where status='SATISFIED') as "qualifiedCount",
                  count(*) filter(where due_at<now() and status not in ('SATISFIED','CANCELLED','WAIVED')) as "openDueCount"
                from work_expectation where tenant_id=:tenant and position_assignment_id=:owner
                  and target_org_unit_id in (:hotels) and business_date between :start and :end
                  and status not in ('CANCELLED','WAIVED') and available_at<=now()
                """,params(p).addValue("owner",own).addValue("hotels",hs).addValue("start",start).addValue("end",end));
        return Map.of("mode","RULE_BASED","facts",facts,"dataAsOf",OffsetDateTime.now(zone(p)),"sourcePeriodStart",start,"sourcePeriodEnd",end,
                "summary",("MONTHLY".equals(type)?"上月简评":"本周阶段性总结")+"（"+start+"至"+end+"，截至当前）：已生成工作"+facts.get("expectedCount")+"项，合格完成"+facts.get("qualifiedCount")+"项，已到期待闭环"+facts.get("openDueCount")+"项。",
                "suggestions",List.of("优先处理已到期未闭环事项，并明确负责人、截止和验收证据。","周/月引用同一行动时关联原任务，避免重复下达。"),
                "missingData",List.of("未接入经营结算及平台业绩数据，不推断营业额、收益或转化率。"));
    }

    private void validate(TenantPrincipal p,Map<String,Object> plan,OperationsPlanModels.Draft draft,boolean approving) {
        if (blank(draft.summary()) || blank(draft.objective())) throw new IllegalArgumentException("请填写总结或简评以及工作目标");
        UUID owner=(UUID)plan.get("owner_assignment_id");List<UUID> hs=snapshot(plan);
        if (hs.isEmpty() || !hotels(p,owner).containsAll(hs)) throw new IllegalArgumentException("负责门店已变化，请退回计划并重新保存门店范围");
        Set<UUID> seen=new HashSet<>();
        for (var item:items(draft)) {
            if (item.actionId()==null || !seen.add(item.actionId())) throw new IllegalArgumentException("每项行动必须有独立稳定编号，不能重复");
            if (item.hotelId()==null || !hs.contains(item.hotelId())) throw new AccessDeniedException("行动门店超出负责范围");
            if (item.assigneeAssignmentId()==null || blank(item.title()) || item.title().length()>240 || item.dueAt()==null || blank(item.acceptanceCriteria()))
                throw new IllegalArgumentException("行动项须填写门店、负责人、内容、截止和验收标准");
            if (item.linkedTaskId()!=null && item.linkedExpectationId()!=null) throw new IllegalArgumentException("每项行动只能关联一个原工作或原任务");
            requireAssignee(p,item.assigneeAssignmentId(),item.hotelId());
            if (!item.assigneeAssignmentId().equals(owner)) {
                Boolean subordinate=jdbc.queryForObject("""
                        select exists(select 1 from employee_position_assignment a where a.tenant_id=:tenant and a.id=:id
                          and (a.manager_assignment_id=:owner or exists(select 1 from position_assignment_reporting_relation r
                            where r.tenant_id=a.tenant_id and r.subordinate_assignment_id=a.id and r.leader_assignment_id=:owner
                              and r.valid_from<=current_date and (r.valid_to is null or r.valid_to>=current_date))))
                        """,
                        params(p).addValue("id",item.assigneeAssignmentId()).addValue("owner",owner),Boolean.class);
                if (!Boolean.TRUE.equals(subordinate)) throw new AccessDeniedException("计划只能安排本人或有效直属下级，不能查询同事或上级任务");
            }
            if (approving) {
                access.requireOrgScope(item.hotelId());
                Boolean self=jdbc.queryForObject("select exists(select 1 from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where a.tenant_id=:tenant and a.id=:id and e.account_id=:actor)",
                        params(p).addValue("id",item.assigneeAssignmentId()).addValue("actor",p.actorId()),Boolean.class);
                if (Boolean.TRUE.equals(self)) throw new AccessDeniedException("不能审核本人或同账号其他任职执行的行动");
            }
            var prior=action(p,owner,item.actionId());
            if (!prior.isEmpty()) {
                UUID task=(UUID)prior.get("task_id");requireTaskLink(p,item,task);
                Boolean unchanged=jdbc.queryForObject("select title=:title and due_at=:due and source_snapshot->>'acceptanceCriteria'=:criteria from management_task where tenant_id=:tenant and id=:id",
                        params(p).addValue("id",task).addValue("title",item.title()).addValue("due",item.dueAt()).addValue("criteria",item.acceptanceCriteria()),Boolean.class);
                if (!Boolean.TRUE.equals(unchanged))
                    throw new IllegalArgumentException("已生成行动不能通过计划改标题或截止，请关联原任务并保留原责任");
            }
            if (item.linkedTaskId()!=null) requireTaskLink(p,item,item.linkedTaskId());
            if (item.linkedExpectationId()!=null) {
                Boolean allowed=jdbc.queryForObject("""
                        select exists(select 1 from work_expectation where tenant_id=:tenant and id=:id
                          and position_assignment_id=:assignee and target_org_unit_id=:hotel and status not in ('CANCELLED','WAIVED'))
                        """,params(p).addValue("id",item.linkedExpectationId()).addValue("assignee",item.assigneeAssignmentId()).addValue("hotel",item.hotelId()),Boolean.class);
                if (!Boolean.TRUE.equals(allowed)) throw new AccessDeniedException("关联工作不存在或责任人与门店不匹配");
            }
        }
    }
    private void requireTaskLink(TenantPrincipal p,OperationsPlanModels.Item item,UUID id) {
        Boolean matches=jdbc.queryForObject("""
                select exists(select 1 from management_task t join task_participant a on a.tenant_id=t.tenant_id and a.task_id=t.id
                  and a.participant_type='ASSIGNEE' and a.valid_to is null
                  where t.tenant_id=:tenant and t.id=:id and t.org_unit_id=:hotel and a.position_assignment_id=:assignee)
                """,params(p).addValue("id",id).addValue("hotel",item.hotelId()).addValue("assignee",item.assigneeAssignmentId()),Boolean.class);
        if (!Boolean.TRUE.equals(matches)) throw new AccessDeniedException("关联任务与负责人或门店不匹配");
    }
    private void requireAssignee(TenantPrincipal p,UUID assignment,UUID hotel) {
        Boolean active=jdbc.queryForObject("""
                select exists(select 1 from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                  join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
                  join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id and d.status='ACTIVE'
                  where a.tenant_id=:tenant and a.id=:id and a.status='ACTIVE' and a.valid_from<=current_date
                    and (a.valid_to is null or a.valid_to>=current_date) and e.employment_status='ACTIVE' and e.deleted_at is null
                    and d.deleted_at is null and d.permanently_deleted_at is null
                    and (exists(select 1 from employee_assignment_hotel_scope s where s.tenant_id=a.tenant_id and s.assignment_id=a.id and s.hotel_org_unit_id=:hotel)
                    or exists(select 1 from org_unit_closure c where c.tenant_id=a.tenant_id and c.ancestor_id=:hotel and c.descendant_id=a.org_unit_id)))
                """,params(p).addValue("id",assignment).addValue("hotel",hotel),Boolean.class);
        if (!Boolean.TRUE.equals(active)) throw new AccessDeniedException("行动责任任职无效或不负责该门店");
    }
    private Map<String,Object> action(TenantPrincipal p,UUID owner,UUID action) {
        var found=jdbc.queryForList("select * from operations_plan_action where tenant_id=:tenant and owner_assignment_id=:owner and action_id=:action",
                params(p).addValue("owner",owner).addValue("action",action));
        return found.isEmpty()?Map.of():found.getFirst();
    }
    private List<Map<String,Object>> projectItems(TenantPrincipal p,Map<String,Object> row,OperationsPlanModels.Draft draft) {
        List<Map<String,Object>> result=new ArrayList<>();
        for (var i:items(draft)) {
            var item=new LinkedHashMap<String,Object>();item.put("id",i.actionId());item.put("actionId",i.actionId());item.put("hotelId",i.hotelId());
            item.put("assigneeAssignmentId",i.assigneeAssignmentId());item.put("title",i.title());item.put("description",i.description());
            item.put("dueAt",i.dueAt());item.put("acceptanceCriteria",i.acceptanceCriteria());item.put("linkedTaskId",i.linkedTaskId());item.put("linkedExpectationId",i.linkedExpectationId());
            String name=i.assigneeAssignmentId()==null?"":jdbc.query("select e.name||' · '||d.name from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id where a.tenant_id=:tenant and a.id=:id",
                    params(p).addValue("id",i.assigneeAssignmentId()),rs->rs.next()?rs.getString(1):"");
            item.put("assigneeName",name);
            item.put("hotelName",i.hotelId()==null?"":jdbc.query("select name from org_unit where tenant_id=:tenant and id=:id",params(p).addValue("id",i.hotelId()),rs->rs.next()?rs.getString(1):""));
            var previous=action(p,(UUID)row.get("owner_assignment_id"),i.actionId());
            UUID task=i.linkedTaskId()!=null?i.linkedTaskId():(UUID)previous.get("task_id");
            item.put("mode",task!=null?"LINK_TASK":i.linkedExpectationId()!=null?"LINK_WORK":"CREATE");item.put("taskId",task);
            if (task!=null) item.put("taskStatus",jdbc.queryForObject("select lifecycle_status from management_task where tenant_id=:tenant and id=:id",params(p).addValue("id",task),String.class));
            result.add(item);
        }
        return result;
    }
    private Map<String,Object> project(TenantPrincipal p,Map<String,Object> row) {return project(p,row,true);}
    private Map<String,Object> project(TenantPrincipal p,Map<String,Object> row,boolean detailed) {
        var d=content(row);var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id"));result.put("type",row.get("plan_type"));result.put("periodStart",row.get("period_start"));result.put("periodEnd",row.get("period_end"));
        result.put("status",row.get("status"));result.put("revisionId",row.get("current_revision_id"));result.put("revisionNo",row.get("revision_no"));result.put("draftVersion",row.get("operations_draft_version"));
        result.put("ownerAssignmentId",row.get("owner_assignment_id"));result.put("ownerName",row.get("owner_name"));result.put("dueAt",offset(row.get("due_at")));
        result.put("submittedAt",offset(row.get("submitted_at")));result.put("late",row.get("submitted_at")!=null && offset(row.get("submitted_at")).isAfter(offset(row.get("due_at"))));
        result.put("summary",d.summary());result.put("objective",d.objective());result.put("risk",d.risk());result.put("hotelSummaries",d.hotelSummaries()==null?Map.of():d.hotelSummaries());
        result.put("hotelIds",snapshot(row));result.put("itemCount",items(d).size());result.put("items",detailed?projectItems(p,row,d):List.of());
        result.put("canReview",detailed?canReview(p,row):!Objects.equals(p.businessActorAssignmentId(),row.get("owner_assignment_id"))&&(p.hasPermission("work-plan.review")||p.hasPermission("*")));
        var planHotels=snapshot(row);
        result.put("hotels",planHotels.isEmpty()?List.of():jdbc.queryForList("select id,name from org_unit where tenant_id=:tenant and id in (:ids) order by name",
                params(p).addValue("ids",planHotels)));
        result.put("reviewHistory",detailed?jdbc.queryForList("""
                select r.action,r.comment,r.reviewed_at as "reviewedAt",r.reviewer_assignment_id as "reviewerAssignmentId"
                from management_work_plan_review r join management_work_plan_revision v on v.tenant_id=r.tenant_id and v.id=r.revision_id
                where v.tenant_id=:tenant and v.work_plan_id=:id order by r.reviewed_at desc
                """,params(p).addValue("id",row.get("id"))):List.of());return result;
    }
    private boolean canReview(TenantPrincipal p,Map<String,Object> row) {
        return (p.hasPermission("work-plan.review")||p.hasPermission("*")) && canReadTeam(p,row);
    }
    private boolean canReadTeam(TenantPrincipal p,Map<String,Object> row) {
        if (!(p.hasPermission("work-plan.review")||p.hasPermission("work-plan.team-read")||p.hasPermission("*"))) return false;
        List<UUID> hs=snapshot(row);if (hs.isEmpty()) return false;
        WorkReviewPolicy policy=new WorkReviewPolicy(jdbc);
        return hs.stream().allMatch(hotel->policy.canManage(p,(UUID)row.get("owner_assignment_id"),hotel));
    }
    private void requireRead(TenantPrincipal p,Map<String,Object> row) {
        if (!Objects.equals(p.businessActorAssignmentId(),row.get("owner_assignment_id")) && !canReadTeam(p,row)) throw new AccessDeniedException("无权读取该任职的运营计划");
    }
    private Map<String,Object> plan(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("""
                select p.*,r.status as revision_status,coalesce(r.revision_no,0) as revision_no,r.summary,r.operations_payload::text as payload,r.operations_draft_version,
                  r.submitted_at,e.name as owner_name from management_work_plan p
                left join management_work_plan_revision r on r.tenant_id=p.tenant_id and r.id=p.current_revision_id
                join employee_position_assignment a on a.tenant_id=p.tenant_id and a.id=p.owner_assignment_id
                join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                where p.tenant_id=:tenant and p.id=:id and p.operations_plan
                """+(lock?" for update of p":""),params(p).addValue("id",id));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"运营计划不存在或不在当前租户");return rows.getFirst();
    }
    private OperationsPlanModels.Draft content(Map<String,Object> row) {
        try {JsonNode payload=mapper.readTree(Objects.toString(row.get("payload"),"{}"));return payload.has("content")?mapper.treeToValue(payload.get("content"),OperationsPlanModels.Draft.class):new OperationsPlanModels.Draft("","","",Map.of(),List.of(),null,null);}
        catch(Exception e){throw new IllegalArgumentException("运营计划内容读取失败",e);}
    }
    private List<UUID> snapshot(Map<String,Object> row) {
        try {List<UUID> result=new ArrayList<>();mapper.readTree(Objects.toString(row.get("payload"),"{}")).path("hotelIds").forEach(n->result.add(UUID.fromString(n.asText())));return result;}
        catch(Exception e){throw new IllegalArgumentException("运营计划门店快照无效",e);}
    }
    private UUID owner(TenantPrincipal p) {
        UUID id=access.requireBusinessActorAssignment();
        Boolean valid=jdbc.queryForObject("""
                select exists(select 1 from employee_position_assignment a join position_definition d on d.tenant_id=a.tenant_id and d.id=a.position_id
                  join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
                  join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id and u.status='ACTIVE'
                  where a.tenant_id=:tenant and a.id=:id and e.account_id=:actor and a.status='ACTIVE'
                    and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
                    and d.code in ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER') and d.status='ACTIVE'
                    and d.deleted_at is null and d.permanently_deleted_at is null and e.employment_status='ACTIVE' and e.deleted_at is null)
                """,params(p).addValue("id",id).addValue("actor",p.actorId()),Boolean.class);
        if (!Boolean.TRUE.equals(valid)) throw new AccessDeniedException("当前任职尚未配置运营周/月计划模板");return id;
    }
    private List<UUID> hotels(TenantPrincipal p,UUID owner) {return jdbc.queryForList("""
            select s.hotel_org_unit_id from employee_assignment_hotel_scope s join org_unit h on h.tenant_id=s.tenant_id and h.id=s.hotel_org_unit_id
            where s.tenant_id=:tenant and s.assignment_id=:owner and h.unit_type='HOTEL' and h.status='ACTIVE' order by h.id
            """,params(p).addValue("owner",owner),UUID.class);}
    private UUID manager(TenantPrincipal p,UUID owner) {
        var rows=jdbc.queryForList("""
            select distinct m.id from employee_position_assignment a join employee_position_assignment m on m.tenant_id=a.tenant_id
              and (m.id=a.manager_assignment_id or (a.manager_assignment_id is null and exists(
                select 1 from position_assignment_reporting_relation r where r.tenant_id=a.tenant_id and r.subordinate_assignment_id=a.id
                  and r.leader_assignment_id=m.id and r.valid_from<=current_date and (r.valid_to is null or r.valid_to>=current_date))))
            join employee e on e.tenant_id=m.tenant_id and e.id=m.employee_id join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id
            where a.tenant_id=:tenant and a.id=:owner and m.status='ACTIVE' and m.valid_from<=current_date
              and (m.valid_to is null or m.valid_to>=current_date) and e.employment_status='ACTIVE' and e.deleted_at is null and u.status='ACTIVE'
              and e.id<>a.employee_id and e.account_id is distinct from (select account_id from employee where tenant_id=a.tenant_id and id=a.employee_id)
            """,params(p).addValue("owner",owner),UUID.class);
        return rows.size()==1?rows.getFirst():null;
    }
    private TenantPrincipal prepare(){var p=access.principal();database.apply(p.tenantId());return p;}
    private ZoneId zone(TenantPrincipal p){return ZoneId.of(jdbc.queryForObject("select timezone from tenant where id=:tenant",params(p),String.class));}
    private static List<OperationsPlanModels.Item> items(OperationsPlanModels.Draft d){return d.items()==null?List.of():d.items();}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static OffsetDateTime offset(Object value){return value==null?null:value instanceof Timestamp t?t.toInstant().atOffset(ZoneOffset.UTC):value instanceof OffsetDateTime t?t:OffsetDateTime.parse(value.toString());}
    private static MapSqlParameterSource params(TenantPrincipal p){return new MapSqlParameterSource("tenant",p.tenantId());}
}
