package cn.sifangguan.hotelaios.academy;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static cn.sifangguan.hotelaios.academy.AcademySupport.*;

@Service
@Transactional
public class AcademyPlans {
    private final AcademySupport s;
    private final AcademyAdministration admin;
    private final cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService identities;
    public AcademyPlans(AcademySupport s,AcademyAdministration admin,cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService identities){this.s=s;this.admin=admin;this.identities=identities;}
    public Map<String,Object> mine(){
        var principal=s.prepare("academy.learn");
        var employees=s.db.queryForList("select id,employee_type from employee where tenant_id=:tid and account_id=:actor and deleted_at is null",
            s.params().addValue("actor",principal.actorId()));
        if(!employees.isEmpty())reconcileEmployee(principal.tenantId(),id(employees.getFirst(),"id"));
        var plans=s.db.queryForList("""
            select p.*,m.title as map_title,pos.name as position_name,o.name as org_name,
              (select ps.state from academy_plan_stage ps join academy_stage st on st.tenant_id=ps.tenant_id and st.id=ps.stage_id
               where ps.tenant_id=p.tenant_id and ps.plan_id=p.id and st.stage_no=p.current_stage) as progress_state,
              (select count(*) from academy_stage st where st.tenant_id=p.tenant_id and st.map_id=p.map_id) as stage_count,
              (select count(*) from academy_plan_stage ps where ps.tenant_id=p.tenant_id and ps.plan_id=p.id and ps.state='PASSED') as passed_stages
            from academy_plan p join academy_map m on m.tenant_id=p.tenant_id and m.id=p.map_id
            join employee_position_assignment a on a.tenant_id=p.tenant_id and a.id=p.assignment_id
            join position_definition pos on pos.tenant_id=a.tenant_id and pos.id=a.position_id
            join org_unit o on o.tenant_id=p.tenant_id and o.id=p.org_id
            where p.tenant_id=:tid and p.account_id=:actor order by p.created_at desc
            """,s.params().addValue("actor",principal.actorId()));
        var issues=employees.isEmpty()?List.of():s.db.queryForList("select reason from academy_dispatch_issue where tenant_id=:tid and employee_id=:employee",s.params().addValue("employee",id(employees.getFirst(),"id")));
        return Map.of("plans",plans,"issues",issues,"employeeType",employees.isEmpty()?"":str(employees.getFirst(),"employee_type"));
    }
    public Map<String,Object> detail(UUID planId){
        s.prepare("academy.learn");var plan=ownPlan(planId,true);refresh(plan);
        plan=ownPlan(planId,false);
        var stages=s.db.queryForList("""
            select st.*,ps.state as progress_state,ps.due_at,ps.available_at,ps.extra_attempts,ps.last_error,ps.passed_at
            from academy_stage st left join academy_plan_stage ps on ps.tenant_id=st.tenant_id and ps.stage_id=st.id and ps.plan_id=:plan
            where st.tenant_id=:tid and st.map_id=:map order by st.stage_no
            """,s.params().addValue("plan",planId).addValue("map",id(plan,"map_id")));
        for(var stage:stages){
            stage.remove("exam_rules");
            var courses=s.db.queryForList("""
                select c.id,c.title,c.kind,c.duration_seconds,c.page_count,c.page_seconds,c.version,
                  coalesce(p.watched_seconds,0) as watched_seconds,coalesce(p.completed_pages,0) as completed_pages,p.completed_at
                from academy_stage_course sc join academy_course c on c.tenant_id=sc.tenant_id and c.id=sc.course_id
                left join academy_progress p on p.tenant_id=c.tenant_id and p.course_id=c.id and p.employee_id=:employee
                where sc.tenant_id=:tid and sc.stage_id=:stage order by sc.sort_order
                """,s.params().addValue("employee",id(plan,"employee_id")).addValue("stage",id(stage,"id")));
            stage.put("courses",courses);
            stage.put("exams",s.db.queryForList("""
                select id,state,attempt_no,score,total_score,pass_score,minutes,started_at,deadline,submitted_at
                from academy_exam where tenant_id=:tid and plan_id=:plan and stage_id=:stage order by attempt_no desc
                """,s.params().addValue("plan",planId).addValue("stage",id(stage,"id"))));
        }
        plan.put("stages",stages);return plan;
    }
    Map<String,Object> ownPlan(UUID id,boolean lock){
        var principal=s.access.principal();
        var row=s.one("select * from academy_plan where tenant_id=:tid and id=:id and account_id=:actor",
            s.params().addValue("id",id).addValue("actor",principal.actorId()));
        if(lock){
            s.lockEmployee(principal.tenantId(),id(row,"employee_id"));
            row=s.one("select * from academy_plan where tenant_id=:tid and id=:id and account_id=:actor for update",
                s.params().addValue("id",id).addValue("actor",principal.actorId()));
        }
        return row;
    }
    boolean activeAssignment(Map<String,Object> plan){
        return Boolean.TRUE.equals(s.db.queryForObject("""
            select exists(select 1 from employee_position_assignment a
            join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
            join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id
            where a.tenant_id=:tid and a.id=:assignment and a.status='ACTIVE'
              and a.employee_id=:employee and e.account_id=:account
              and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
              and e.employment_status='ACTIVE' and e.deleted_at is null and u.status='ACTIVE')
            """,params(id(plan,"tenant_id")).addValue("assignment",id(plan,"assignment_id"))
                .addValue("employee",id(plan,"employee_id")).addValue("account",id(plan,"account_id")),Boolean.class));
    }
    void requireActive(Map<String,Object> plan){
        if(!Set.of("ACTIVE","COMPLETED").contains(str(plan,"state"))||!activeAssignment(plan))throw conflict("当前账号或任职已失效，不能继续学习考试");
    }
    Map<String,Object> accessibleCourse(UUID planId,UUID courseId){
        var plan=ownPlan(planId,false);requireActive(plan);
        var row=s.one("""
            select c.* from academy_stage st join academy_stage_course sc on sc.tenant_id=st.tenant_id and sc.stage_id=st.id
            join academy_course c on c.tenant_id=sc.tenant_id and c.id=sc.course_id
            where st.tenant_id=:tid and st.map_id=:map and st.stage_no<=:stage and c.id=:course limit 1
            """,s.params().addValue("map",id(plan,"map_id")).addValue("stage",integer(plan,"current_stage")).addValue("course",courseId));
        if(!Set.of("PUBLISHED","RETIRED").contains(str(row,"state")))throw conflict("课件暂不可用");
        return row;
    }
    /** Caller provides an explicit, RLS-bound tenant; no cross-tenant discovery. */
    public void processTenant(UUID tenant){
        s.tenants.apply(tenant);
        // Only one dispatcher per tenant may sweep at a time, including across application instances.
        if(!Boolean.TRUE.equals(s.db.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(:key,0))",params(tenant).addValue("key","academy-worker:"+tenant),Boolean.class)))return;
        var employees=s.db.queryForList("""
            select employee_id from academy_dispatch_queue where tenant_id=:tid order by requested_at limit 100
            """,params(tenant),UUID.class);
        for(UUID employee:employees)reconcileEmployee(tenant,employee);
        var running=s.db.queryForList("select * from academy_plan where tenant_id=:tid and state in ('ACTIVE','SUSPENDED') order by created_at",params(tenant));
        for(var plan:running){
            s.lockEmployee(tenant,id(plan,"employee_id"));
            plan=s.one("select * from academy_plan where tenant_id=:tid and id=:id for update",params(tenant).addValue("id",id(plan,"id")));
            refresh(plan);
        }
    }
    void reconcileEmployee(UUID tenant,UUID employee){
        s.lockEmployee(tenant,employee);
        var p=params(tenant).addValue("employee",employee);
        // A durable queue entry survives missing bindings, future assignments and unavailable maps.
        s.db.update("update academy_dispatch_queue set requested_at=now() where tenant_id=:tid and employee_id=:employee",p);
        var candidates=s.db.queryForList("""
            select a.*,e.account_id from employee_position_assignment a
            join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
            join user_account u on u.tenant_id=e.tenant_id and u.id=e.account_id
            where a.tenant_id=:tid and e.id=:employee and e.employee_type='INTERN' and e.employment_status='ACTIVE'
              and e.deleted_at is null and u.status='ACTIVE' and a.status='ACTIVE' and a.valid_from<=current_date
              and (a.valid_to is null or a.valid_to>=current_date)
              and exists(select 1 from wecom_user_binding b where b.tenant_id=e.tenant_id and b.account_id=e.account_id and b.status='ACTIVE')
              and not exists(select 1 from academy_plan p where p.tenant_id=a.tenant_id and p.assignment_id=a.id)
            order by a.id
            """,p);
        for(var assignment:candidates){
            UUID assignmentId=id(assignment,"id");
            var maps=s.db.queryForList("""
                select m.*,coalesce(c.depth,100000) as priority from academy_map m
                left join org_unit_closure c on c.tenant_id=m.tenant_id and c.ancestor_id=m.scope_org_id and c.descendant_id=:org
                where m.tenant_id=:tid and m.position_id=:position and m.state='PUBLISHED'
                  and (m.scope_org_id is null or c.descendant_id is not null)
                order by priority,m.published_at desc
                """,p.addValue("org",id(assignment,"org_unit_id")).addValue("position",id(assignment,"position_id")));
            if(maps.isEmpty()||(maps.size()>1&&integer(maps.get(0),"priority")==integer(maps.get(1),"priority"))){
                String reason=maps.isEmpty()?"该岗位尚未配置已发布成长地图":"该岗位成长地图适用范围冲突";
                s.db.update("""
                    insert into academy_dispatch_issue(tenant_id,assignment_id,employee_id,org_id,reason)
                    values(:tid,:assignment,:employee,:org,:reason) on conflict(tenant_id,assignment_id)
                    do update set reason=excluded.reason,updated_at=now()
                    """,p.addValue("assignment",assignmentId).addValue("reason",reason));
                notifyManagers(tenant,id(assignment,"org_unit_id"),null,"ACADEMY_CONFIGURATION",reason,"academy:missing:"+assignmentId);
                continue;
            }
            UUID planId=UUID.randomUUID(),mapId=id(maps.getFirst(),"id");
            int n=s.db.update("""
                insert into academy_plan(id,tenant_id,employee_id,account_id,assignment_id,org_id,map_id)
                values(:plan,:tid,:employee,:account,:assignment,:org,:map) on conflict(tenant_id,assignment_id) do nothing
                """,p.addValue("plan",planId).addValue("account",id(assignment,"account_id")).addValue("assignment",assignmentId).addValue("map",mapId));
            if(n==1){
                var plan=s.one("select * from academy_plan where tenant_id=:tid and id=:plan",p);
                unlock(plan,1);
                s.audit(tenant,null,"ACADEMY_PLAN_ASSIGNED",planId,Map.of("assignmentId",assignmentId,"mapId",mapId));
                s.db.update("delete from academy_dispatch_issue where tenant_id=:tid and assignment_id=:assignment",p);
                refresh(plan);
            }
        }
        s.db.update("""
            delete from academy_dispatch_issue i where i.tenant_id=:tid and i.employee_id=:employee
            and not exists(select 1 from employee e where e.tenant_id=i.tenant_id and e.id=i.employee_id and e.employee_type='INTERN' and e.employment_status='ACTIVE')
            """,p);
        s.db.update("""
            delete from academy_dispatch_queue q where q.tenant_id=:tid and q.employee_id=:employee
            and exists(select 1 from employee e where e.tenant_id=q.tenant_id and e.id=q.employee_id and (e.employee_type is distinct from 'INTERN' or e.employment_status<>'ACTIVE'))
            """,p);
    }
    private void unlock(Map<String,Object> plan,int number){
        UUID tenant=id(plan,"tenant_id"),planId=id(plan,"id");
        var stage=s.one("select * from academy_stage where tenant_id=:tid and map_id=:map and stage_no=:n",
            params(tenant).addValue("map",id(plan,"map_id")).addValue("n",number));
        var p=params(tenant).addValue("plan",planId).addValue("stage",id(stage,"id")).addValue("days",stage.get("due_days"));
        s.db.update("""
            insert into academy_plan_stage(tenant_id,plan_id,stage_id,due_at)
            values(:tid,:plan,:stage,case when cast(:days as integer) is null then null else now()+cast(:days as integer)*interval '1 day' end)
            on conflict do nothing
            """,p);
        s.notify(tenant,id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_STAGE_AVAILABLE","新的培训阶段已开放",
            str(stage,"title")+"：完成全部必修课程后参加阶段考试。","academy:stage:"+planId+":"+id(stage,"id"));
    }
    void refresh(Map<String,Object> plan){
        UUID tenant=id(plan,"tenant_id"),planId=id(plan,"id");
        if(Set.of("COMPLETED","CANCELLED").contains(str(plan,"state")))return;
        var p=params(tenant).addValue("plan",planId).addValue("map",id(plan,"map_id")).addValue("n",integer(plan,"current_stage")).addValue("employee",id(plan,"employee_id"));
        if(!activeAssignment(plan)){
            s.db.update("update academy_plan set state='SUSPENDED' where tenant_id=:tid and id=:plan",p);return;
        }
        if("SUSPENDED".equals(str(plan,"state")))s.db.update("update academy_plan set state='ACTIVE' where tenant_id=:tid and id=:plan",p);
        var stage=s.one("""
            select st.*,ps.state as progress_state,ps.available_at,ps.extra_attempts,ps.due_at
            from academy_stage st join academy_plan_stage ps on ps.tenant_id=st.tenant_id and ps.stage_id=st.id and ps.plan_id=:plan
            where st.tenant_id=:tid and st.map_id=:map and st.stage_no=:n
            """,p);
        UUID sid=id(stage,"id");p.addValue("stage",sid);
        if(stage.get("due_at")!=null&&s.now().isAfter(instant(stage.get("due_at")))){
            s.notify(tenant,id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_OVERDUE","培训阶段已逾期",
                "请继续完成“"+str(stage,"title")+"”的课程与考核。","academy:overdue:"+planId+":"+sid);
            notifyManagers(tenant,id(plan,"org_id"),planId,"ACADEMY_MANAGER_OVERDUE","有员工培训阶段逾期，请查看团队学习。","academy:manager-overdue:"+planId+":"+sid);
        }
        var exams=s.db.queryForList("select * from academy_exam where tenant_id=:tid and plan_id=:plan and stage_id=:stage order by attempt_no desc",p);
        if(!exams.isEmpty()){
            var latest=exams.getFirst();
            if("RUNNING".equals(str(latest,"state")) && !s.now().isBefore(instant(latest.get("deadline")))){
                grade(plan,latest);return;
            }
            if(Set.of("READY","RUNNING","PASSED").contains(str(latest,"state")))return;
        }
        int incomplete=s.db.queryForObject("""
            select count(*) from academy_stage_course sc left join academy_progress p
             on p.tenant_id=sc.tenant_id and p.course_id=sc.course_id and p.employee_id=:employee
            where sc.tenant_id=:tid and sc.stage_id=:stage and p.completed_at is null
            """,p,Integer.class);
        if(incomplete>0)return;
        if(exams.size()>=integer(stage,"attempt_limit")+integer(stage,"extra_attempts")){
            s.db.update("update academy_plan_stage set state='NEEDS_ATTENTION' where tenant_id=:tid and plan_id=:plan and stage_id=:stage",p);
            notifyManagers(tenant,id(plan,"org_id"),planId,"ACADEMY_ATTEMPTS_EXHAUSTED","员工已用完阶段考试机会，请安排复习或追加机会。","academy:exhausted:"+planId+":"+sid+":"+exams.size());return;
        }
        if(s.now().isBefore(instant(stage.get("available_at"))))return;
        Set<String> previous=new HashSet<>();
        if(!exams.isEmpty())for(var question:s.objects(exams.getFirst().get("paper")))previous.add(str(question,"id"));
        List<Map<String,Object>> paper;
        try{paper=admin.draw(stage,previous);}
        catch(ResponseStatusException failure){
            s.db.update("update academy_plan_stage set state='CONFIG_ERROR',last_error=:error where tenant_id=:tid and plan_id=:plan and stage_id=:stage",p.addValue("error",failure.getReason()));
            notifyManagers(tenant,id(plan,"org_id"),planId,"ACADEMY_CONFIGURATION",failure.getReason(),"academy:paper-error:"+planId+":"+sid);return;
        }
        UUID examId=UUID.randomUUID();int total=paper.stream().mapToInt(q->integer(q,"points")).sum();
        s.db.update("""
            insert into academy_exam(id,tenant_id,plan_id,stage_id,attempt_no,paper,total_score,pass_score,minutes)
            values(:id,:tid,:plan,:stage,:attempt,cast(:paper as jsonb),:total,:pass,:minutes)
            """,p.addValue("id",examId).addValue("attempt",exams.size()+1).addValue("paper",s.encode(paper))
            .addValue("total",total).addValue("pass",stage.get("pass_score")).addValue("minutes",stage.get("exam_minutes")));
        s.db.update("update academy_plan_stage set state='READY',last_error=null where tenant_id=:tid and plan_id=:plan and stage_id=:stage",p);
        s.notify(tenant,id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_EXAM_READY","阶段考试已开放",
            str(stage,"title")+"：点击开始考试后计时。","academy:exam:"+examId);
    }
    public Map<String,Object> exam(UUID examId){
        s.prepare("academy.learn");var exam=ownedExam(examId,true);var plan=ownPlan(id(exam,"plan_id"),false);
        if("RUNNING".equals(str(exam,"state"))&&!s.now().isBefore(instant(exam.get("deadline")))){grade(plan,exam);exam=ownedExam(examId,false);}
        return presentExam(exam);
    }
    private Map<String,Object> ownedExam(UUID id,boolean lock){
        var row=s.one("""
            select e.* from academy_exam e join academy_plan p on p.tenant_id=e.tenant_id and p.id=e.plan_id
            where e.tenant_id=:tid and e.id=:id and p.account_id=:actor
            """,s.params().addValue("id",id).addValue("actor",s.access.principal().actorId()));
        var plan=ownPlan(id(row,"plan_id"),lock);requireActive(plan);
        return s.one("select * from academy_exam where tenant_id=:tid and id=:id"+(lock?" for update":""),s.params().addValue("id",id));
    }
    public Map<String,Object> startExam(UUID id){
        s.prepare("academy.learn");var exam=ownedExam(id,true);
        if("READY".equals(str(exam,"state"))){
            s.db.update("""
                update academy_exam set state='RUNNING',started_at=clock_timestamp(),
                deadline=clock_timestamp()+minutes*interval '1 minute',version=version+1 where tenant_id=:tid and id=:id and state='READY'
                """,s.params().addValue("id",id));
            s.db.update("update academy_plan_stage set state='EXAM' where tenant_id=:tid and plan_id=:plan and stage_id=:stage",
                s.params().addValue("plan",id(exam,"plan_id")).addValue("stage",id(exam,"stage_id")));
        }
        return exam(id);
    }
    public Map<String,Object> answer(UUID id,AcademyModels.Responses body,boolean submit){
        s.prepare("academy.learn");var exam=ownedExam(id,true);
        if(Set.of("PASSED","FAILED").contains(str(exam,"state")))return presentExam(exam);
        if(!"RUNNING".equals(str(exam,"state")))throw conflict("请先开始考试");
        var plan=ownPlan(id(exam,"plan_id"),false);
        if(!s.now().isBefore(instant(exam.get("deadline")))){grade(plan,exam);return presentExam(ownedExam(id,false));}
        if(body.version()!=integer(exam,"version"))throw conflict("答案已在其他页面更新，请重新读取试卷");
        var valid=new HashMap<String,Integer>();
        for(var q:s.objects(exam.get("paper")))valid.put(str(q,"id"),((List<?>)q.get("options")).size());
        if(body.answers().size()>valid.size())throw bad("答题数据无效");
        for(var entry:body.answers().entrySet()){
            Integer size=valid.get(entry.getKey());var values=entry.getValue();
            if(size==null||values==null||values.size()>size||new HashSet<>(values).size()!=values.size()
                ||values.stream().anyMatch(i->i==null||i<0||i>=size))throw bad("答题选项无效");
        }
        s.db.update("update academy_exam set responses=cast(:answers as jsonb),version=version+1 where tenant_id=:tid and id=:id",
            s.params().addValue("id",id).addValue("answers",s.encode(body.answers())));
        exam=ownedExam(id,false);if(submit)grade(plan,exam);
        return presentExam(ownedExam(id,false));
    }
    private void grade(Map<String,Object> plan,Map<String,Object> exam){
        if(!"RUNNING".equals(str(exam,"state")))return;
        var answers=s.decode(exam.get("responses"),new TypeReference<Map<String,List<Integer>>>(){});
        int score=0;for(var q:s.objects(exam.get("paper"))){
            var actual=new HashSet<>(answers.getOrDefault(str(q,"id"),List.of()));
            var expected=new HashSet<>((List<?>)q.get("answers"));
            if(actual.equals(expected))score+=integer(q,"points");
        }
        boolean passed=score>=integer(exam,"pass_score");
        UUID tenant=id(plan,"tenant_id"),planId=id(plan,"id"),stageId=id(exam,"stage_id"),examId=id(exam,"id");
        var p=params(tenant).addValue("id",examId).addValue("score",score).addValue("state",passed?"PASSED":"FAILED").addValue("plan",planId).addValue("stage",stageId);
        s.db.update("update academy_exam set state=:state,score=:score,submitted_at=clock_timestamp(),version=version+1 where tenant_id=:tid and id=:id and state='RUNNING'",p);
        var stage=s.one("select * from academy_stage where tenant_id=:tid and id=:stage",p);
        if(passed){
            s.db.update("update academy_plan_stage set state='PASSED',passed_at=now() where tenant_id=:tid and plan_id=:plan and stage_id=:stage",p);
            int next=integer(stage,"stage_no")+1;
            Integer hasNext=s.db.queryForObject("select count(*) from academy_stage where tenant_id=:tid and map_id=:map and stage_no=:next",p.addValue("map",id(plan,"map_id")).addValue("next",next),Integer.class);
            if(hasNext!=null&&hasNext>0){
                s.db.update("update academy_plan set current_stage=:next where tenant_id=:tid and id=:plan",p);plan.put("current_stage",next);unlock(plan,next);refresh(plan);
            } else {
                s.db.update("update academy_plan set state='COMPLETED',completed_at=now() where tenant_id=:tid and id=:plan",p);
                s.notify(tenant,id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_COMPLETED","岗位成长地图已完成","所有阶段已通过，学习与成绩记录已归档。","academy:complete:"+planId);
            }
        }else {
            s.db.update("""
                update academy_plan_stage set state='RETRY',available_at=clock_timestamp()+:minutes*interval '1 minute'
                where tenant_id=:tid and plan_id=:plan and stage_id=:stage
                """,p.addValue("minutes",integer(stage,"retry_minutes")));
            s.notify(tenant,id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_EXAM_FAILED","阶段考试未通过",
                "本次 "+score+" 分，请复习对应课程，按补考规则继续。","academy:failed:"+examId);
            refresh(plan);
        }
        s.audit(tenant,id(plan,"account_id"),"ACADEMY_EXAM_GRADED",examId,Map.of("score",score,"passed",passed));
    }
    private Map<String,Object> presentExam(Map<String,Object> exam){
        var result=new LinkedHashMap<>(exam);boolean passed="PASSED".equals(str(exam,"state"));
        result.remove("paper");result.put("questions","READY".equals(str(exam,"state"))?List.of():s.publicQuestions(exam.get("paper"),passed));
        result.put("answers",s.decode(exam.get("responses"),new TypeReference<Map<String,List<Integer>>>(){}));result.remove("responses");
        result.put("questionCount",s.objects(exam.get("paper")).size());result.put("serverTime",s.now().toString());
        var p=s.params().addValue("plan",id(exam,"plan_id")).addValue("stage",id(exam,"stage_id"));
        var stage=s.one("""
            select st.title,st.attempt_limit+ps.extra_attempts as limit,ps.available_at
            from academy_stage st join academy_plan_stage ps on ps.tenant_id=st.tenant_id and ps.stage_id=st.id and ps.plan_id=:plan
            where st.tenant_id=:tid and st.id=:stage
            """,p);
        result.put("stageTitle",stage.get("title"));result.put("attemptLimit",stage.get("limit"));result.put("availableAt",stage.get("available_at"));
        return result;
    }
    public Map<String,Object> team(){
        var principal=s.prepare("academy.team-read","academy.manage");
        var p=s.params().addValue("all",principal.hasTenantScope()).addValue("scopes",principal.orgScopes().isEmpty()?List.of(new UUID(0,0)):principal.orgScopes());
        var rows=s.db.queryForList("""
            select p.id,p.employee_id,p.assignment_id,p.org_id,p.state,p.current_stage,p.created_at,p.completed_at,
              e.name,e.employee_type,o.name as org_name,m.title as map_title,st.title as stage_title,
              ps.state as progress_state,ps.due_at,ps.available_at,ps.last_error,
              (select max(score) from academy_exam x where x.tenant_id=p.tenant_id and x.plan_id=p.id and x.stage_id=st.id) as best_score,
              (select count(*) from academy_plan_stage x where x.tenant_id=p.tenant_id and x.plan_id=p.id and x.state='PASSED') as passed_stages,
              (select count(*) from academy_stage x where x.tenant_id=p.tenant_id and x.map_id=p.map_id) as stage_count
            from academy_plan p join employee e on e.tenant_id=p.tenant_id and e.id=p.employee_id
            join org_unit o on o.tenant_id=p.tenant_id and o.id=p.org_id
            join academy_map m on m.tenant_id=p.tenant_id and m.id=p.map_id
            join academy_stage st on st.tenant_id=p.tenant_id and st.map_id=p.map_id and st.stage_no=p.current_stage
            join academy_plan_stage ps on ps.tenant_id=p.tenant_id and ps.plan_id=p.id and ps.stage_id=st.id
            where p.tenant_id=:tid and (:all or p.org_id in (:scopes)) order by p.created_at desc
            """,p);
        var issues=s.db.queryForList("""
            select i.*,e.name as employee_name,o.name as org_name from academy_dispatch_issue i join employee e on e.tenant_id=i.tenant_id and e.id=i.employee_id join org_unit o on o.tenant_id=i.tenant_id and o.id=i.org_id
            where i.tenant_id=:tid and (:all or i.org_id in (:scopes))
            """,p);
        return Map.of("plans",rows,"issues",issues);
    }
    public Map<String,Object> teamDetail(UUID planId){
        s.prepare("academy.team-read","academy.manage");
        var plan=s.one("select * from academy_plan where tenant_id=:tid and id=:id",s.params().addValue("id",planId));
        s.scope(id(plan,"org_id"));
        var exams=s.db.queryForList("select id,stage_id,attempt_no,state,score,total_score,pass_score,submitted_at from academy_exam where tenant_id=:tid and plan_id=:id order by created_at",
            s.params().addValue("id",planId));
        var stages=s.db.queryForList("""
            select st.id,st.title,st.stage_no,ps.state as progress_state,ps.extra_attempts,ps.due_at,ps.passed_at
            from academy_stage st left join academy_plan_stage ps on ps.tenant_id=st.tenant_id and ps.stage_id=st.id and ps.plan_id=:id
            where st.tenant_id=:tid and st.map_id=:map order by st.stage_no
            """,s.params().addValue("id",planId).addValue("map",id(plan,"map_id")));
        for(var stage:stages){stage.put("exams",exams.stream().filter(e->id(e,"stage_id").equals(id(stage,"id"))).toList());
            stage.put("courses",s.db.queryForList("select c.id,c.title,c.kind,p.completed_at,p.watched_seconds,p.completed_pages from academy_stage_course sc join academy_course c on c.tenant_id=sc.tenant_id and c.id=sc.course_id left join academy_progress p on p.tenant_id=c.tenant_id and p.course_id=c.id and p.employee_id=:employee where sc.tenant_id=:tid and sc.stage_id=:stage order by sc.sort_order",s.params().addValue("stage",id(stage,"id")).addValue("employee",id(plan,"employee_id"))));}
        plan.put("stages",stages);return plan;
    }
    public void addAttempt(UUID planId,AcademyModels.ExtraAttempt body){
        s.prepare("academy.manage");var plan=s.one("select * from academy_plan where tenant_id=:tid and id=:id",s.params().addValue("id",planId));s.scope(id(plan,"org_id"));
        s.lockEmployee(id(plan,"tenant_id"),id(plan,"employee_id"));
        var p=s.params().addValue("plan",planId).addValue("stage",body.stageId());
        int n=s.db.update("""
            update academy_plan_stage set extra_attempts=extra_attempts+1,state='RETRY',available_at=now()
            where tenant_id=:tid and plan_id=:plan and stage_id=:stage and state='NEEDS_ATTENTION'
            """,p);
        if(n!=1)throw conflict("只有考试机会已用完的当前阶段可以追加机会");
        s.audit("ACADEMY_EXTRA_ATTEMPT",planId,Map.of("stageId",body.stageId(),"reason",body.reason()));refresh(plan);
    }
    public void remind(UUID planId){
        var principal=s.prepare("academy.team-read","academy.manage");
        var plan=s.one("select * from academy_plan where tenant_id=:tid and id=:id",s.params().addValue("id",planId));s.scope(id(plan,"org_id"));
        if(!"ACTIVE".equals(str(plan,"state")))throw conflict("只可提醒仍在执行的计划");
        s.notify(principal.tenantId(),id(plan,"account_id"),id(plan,"assignment_id"),planId,"ACADEMY_REMINDER","培训学习提醒",
            "请查看当前阶段，继续完成课程与考核。","academy:manual:"+planId+":"+LocalDate.now(ZoneOffset.UTC));
    }
    private void notifyManagers(UUID tenant,UUID org,UUID plan,String type,String content,String key){
        // Candidate selection is broad; the shared resolver applies the published position profile,
        // current assignment and exact organization scope before any notification is written.
        var candidates=s.db.queryForList("""
            select distinct u.id as account_id,a.id as assignment_id from user_account u
            left join employee e on e.tenant_id=u.tenant_id and e.account_id=u.id and e.deleted_at is null and e.employment_status='ACTIVE'
            left join employee_position_assignment a on a.tenant_id=e.tenant_id and a.employee_id=e.id and a.status='ACTIVE'
              and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
            where u.tenant_id=:tid and u.status='ACTIVE' and (
              exists(select 1 from role_assignment ra join role_permission rp on rp.tenant_id=ra.tenant_id and rp.role_id=ra.role_id
                join permission p on p.id=rp.permission_id where ra.tenant_id=u.tenant_id and ra.account_id=u.id
                and ra.valid_from<=now() and (ra.valid_to is null or ra.valid_to>now()) and p.code in ('academy.manage','academy.team-read'))
              or exists(select 1 from position_function_profile f
                join position_function_profile_version v on v.tenant_id=f.tenant_id and v.profile_id=f.id and v.lifecycle_status='PUBLISHED'
                join position_function_profile_permission fp on fp.tenant_id=v.tenant_id and fp.profile_version_id=v.id
                join permission p on p.id=fp.permission_id where f.tenant_id=a.tenant_id and f.position_id=a.position_id
                and p.code in ('academy.manage','academy.team-read')))
            """,params(tenant));
        Set<UUID> sent=new HashSet<>();
        for(var candidate:candidates){
            UUID account=id(candidate,"account_id");if(sent.contains(account))continue;
            var identity=identities.inspectNotificationRecipient(tenant,account,id(candidate,"assignment_id"));
            if(identity.isPresent()){
                var recipient=identity.get();
                if((recipient.hasPermission("academy.manage")||recipient.hasPermission("academy.team-read"))
                    && (recipient.hasTenantScope()||recipient.orgScopes().contains(org))){
                    s.notify(tenant,account,id(candidate,"assignment_id"),plan,type,"学院管理提醒",content,key);sent.add(account);
                }
            }
        }
    }
}
