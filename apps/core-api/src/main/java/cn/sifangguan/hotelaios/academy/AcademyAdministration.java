package cn.sifangguan.hotelaios.academy;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.sifangguan.hotelaios.academy.AcademySupport.*;

@Service
@Transactional
public class AcademyAdministration {
    private final AcademySupport s;
    public AcademyAdministration(AcademySupport s){this.s=s;}
    private void manage(){var p=s.prepare("academy.manage");if(!p.hasTenantScope())throw missing();}
    public Map<String,Object> catalogue() {
        manage();var p=s.params();
        return Map.of("courses",s.db.queryForList("""
            select id,family_id,version,title,description,kind,knowledge_tags,state,duration_seconds,page_count,
                   page_seconds,original_name,processing_note,revision from academy_course where tenant_id=:tid order by created_at desc
            """,p), "questions",s.db.queryForList("select * from academy_question where tenant_id=:tid order by created_at desc",p),
            "maps",s.db.queryForList("""
            select m.*,p.name as position_name,o.name as org_name from academy_map m
            join position_definition p on p.tenant_id=m.tenant_id and p.id=m.position_id
            left join org_unit o on o.tenant_id=m.tenant_id and o.id=m.scope_org_id
            where m.tenant_id=:tid order by m.created_at desc
            """,p),
            "positions",s.db.queryForList("select id,name from position_definition where tenant_id=:tid and status='ACTIVE' and deleted_at is null order by name",p),
            "orgs",s.db.queryForList("select id,name,unit_type from org_unit where tenant_id=:tid and status='ACTIVE' order by name",p));
    }
    public Map<String,Object> saveCourse(UUID id,AcademyModels.Course body) {
        manage();if(id==null)id=UUID.randomUUID();var existing=s.db.queryForList("select * from academy_course where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        var p=s.params().addValue("id",id).addValue("title",body.title().trim()).addValue("description",text(body.description()))
            .addValue("kind",body.kind()).addValue("tags",s.encode(body.knowledgeTags()==null?List.of():body.knowledgeTags()))
            .addValue("pages",s.encode(body.pageSeconds()==null?List.of():body.pageSeconds())).addValue("actor",s.access.principal().actorId()).addValue("rev",body.revision());
        if(existing.isEmpty()) {
            s.db.update("""
                insert into academy_course(id,tenant_id,family_id,title,description,kind,knowledge_tags,created_by)
                values(:id,:tid,:id,:title,:description,:kind,cast(:tags as jsonb),:actor)
                """,p);
        } else {
            var row=existing.getFirst();
            if(!Set.of("DRAFT","READY").contains(str(row,"state")))throw conflict("已发布课程须创建新版本后修改");
            if(!str(row,"kind").equals(body.kind()))throw bad("课件类型创建后不能更改，请创建新课程");
            var pages=body.pageSeconds();
            if(pages!=null&&!pages.isEmpty() && row.get("page_count")!=null && pages.size()!=integer(row,"page_count"))
                throw bad("每页停留时间必须与课件页数一致");
            int n=s.db.update("""
                update academy_course set title=:title,description=:description,knowledge_tags=cast(:tags as jsonb),
                page_seconds=case when :pages='[]' then page_seconds else cast(:pages as jsonb) end,revision=revision+1
                where tenant_id=:tid and id=:id and revision=:rev
                """,p);
            if(n!=1)throw conflict("课程已被其他页面更新，请刷新");
        }
        s.audit("ACADEMY_COURSE_SAVED",id,Map.of("title",body.title()));
        return course(id);
    }
    public Map<String,Object> course(UUID id) {
        manage();var row=s.one("select * from academy_course where tenant_id=:tid and id=:id",s.params().addValue("id",id));
        row.remove("object_key");return row;
    }
    public Map<String,Object> cloneCourse(UUID id) {
        manage();var row=s.one("select * from academy_course where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        UUID next=UUID.randomUUID();
        // A new version requires a fresh upload and preview confirmation. Older plans keep their exact course ID.
        s.db.update("""
            insert into academy_course(id,tenant_id,family_id,version,title,description,kind,knowledge_tags,created_by)
            select :next,tenant_id,family_id,(select coalesce(max(version),0)+1 from academy_course where tenant_id=:tid and family_id=:family),
            title,description,kind,knowledge_tags,:actor from academy_course where tenant_id=:tid and id=:id
            """,s.params().addValue("id",id).addValue("next",next).addValue("family",id(row,"family_id")).addValue("actor",s.access.principal().actorId()));
        s.audit("ACADEMY_COURSE_VERSION",next,Map.of("source",id));return course(next);
    }
    public Map<String,Object> publishCourse(UUID id,int revision) {
        manage();int n=s.db.update("""
            update academy_course set state='PUBLISHED',published_at=now(),revision=revision+1
            where tenant_id=:tid and id=:id and revision=:rev and state='READY' and object_key is not null
            """,s.params().addValue("id",id).addValue("rev",revision));
        if(n!=1)throw conflict("请完成课件上传与预览，刷新后再发布");
        s.audit("ACADEMY_COURSE_PUBLISHED",id,Map.of("revision",revision));return course(id);
    }
    public Map<String,Object> saveQuestion(UUID id,AcademyModels.Question q) {
        manage();validateQuestion(q);if(id==null)id=UUID.randomUUID();
        s.one("select id from academy_course where tenant_id=:tid and id=:course",s.params().addValue("course",q.courseId()));
        var existing=s.db.queryForList("select version from academy_question where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        var p=s.params().addValue("id",id).addValue("course",q.courseId()).addValue("kind",q.kind())
            .addValue("stem",q.stem().trim()).addValue("options",s.encode(q.options())).addValue("answers",s.encode(new TreeSet<>(q.answers())))
            .addValue("explanation",text(q.explanation())).addValue("knowledge",text(q.knowledge())).addValue("difficulty",q.difficulty())
            .addValue("active",q.active()).addValue("version",q.version());
        if(existing.isEmpty())s.db.update("""
            insert into academy_question(id,tenant_id,course_id,kind,stem,options,answers,explanation,knowledge,difficulty,active)
            values(:id,:tid,:course,:kind,:stem,cast(:options as jsonb),cast(:answers as jsonb),:explanation,:knowledge,:difficulty,:active)
            """,p);
        else if(s.db.update("""
            update academy_question set course_id=:course,kind=:kind,stem=:stem,options=cast(:options as jsonb),
            answers=cast(:answers as jsonb),explanation=:explanation,knowledge=:knowledge,difficulty=:difficulty,
            active=:active,version=version+1,updated_at=now() where tenant_id=:tid and id=:id and version=:version
            """,p)!=1)throw conflict("题目已被其他页面更新");
        s.audit("ACADEMY_QUESTION_SAVED",id,Map.of("active",q.active()));
        return s.one("select * from academy_question where tenant_id=:tid and id=:id",p);
    }
    static void validateQuestion(AcademyModels.Question q) {
        if(q.options()==null||q.options().size()<2||q.options().size()>8||q.answers()==null||q.answers().isEmpty())throw bad("题目选项或答案不完整");
        var answers=new HashSet<>(q.answers());
        if(answers.size()!=q.answers().size()||answers.stream().anyMatch(i->i==null||i<0||i>=q.options().size()))throw bad("答案选项无效");
        if(!"MULTIPLE".equals(q.kind())&&answers.size()!=1)throw bad("单选和判断题必须有且只有一个答案");
        if("BOOLEAN".equals(q.kind())&&q.options().size()!=2)throw bad("判断题只能有两个选项");
    }
    public Map<String,Object> importQuestions(List<AcademyModels.Question> rows) {
        manage();if(rows==null||rows.isEmpty()||rows.size()>500)throw bad("每次导入1至500道题");
        var ids=new ArrayList<UUID>();
        for(var row:rows){UUID id=UUID.randomUUID();saveQuestion(id,row);ids.add(id);}
        return Map.of("count",ids.size(),"ids",ids);
    }
    public Map<String,Object> map(UUID id) {
        manage();var p=s.params().addValue("id",id);
        var row=s.one("select * from academy_map where tenant_id=:tid and id=:id",p);
        var stages=s.db.queryForList("select * from academy_stage where tenant_id=:tid and map_id=:id order by stage_no",p);
        for(var stage:stages){
            stage.put("courseIds",s.db.queryForList("select course_id from academy_stage_course where tenant_id=:tid and stage_id=:sid order by sort_order",
                s.params().addValue("sid",id(stage,"id")),UUID.class));
            stage.put("rules",s.objects(stage.get("exam_rules")));
        }
        row.put("stages",stages);return row;
    }
    public Map<String,Object> saveMap(UUID id,AcademyModels.GrowthMap body) {
        manage();if(id==null)id=UUID.randomUUID();if(body.stages()==null||body.stages().isEmpty())throw bad("至少配置一个培训阶段");
        var p=s.params().addValue("id",id).addValue("title",body.title().trim()).addValue("position",body.positionId())
            .addValue("scope",body.scopeOrgId()).addValue("actor",s.access.principal().actorId()).addValue("rev",body.revision());
        s.one("select id from position_definition where tenant_id=:tid and id=:position and status='ACTIVE' and deleted_at is null",p);
        if(body.scopeOrgId()!=null)s.one("select id from org_unit where tenant_id=:tid and id=:scope and status='ACTIVE'",p);
        var rows=s.db.queryForList("select * from academy_map where tenant_id=:tid and id=:id for update",p);
        if(rows.isEmpty())s.db.update("""
            insert into academy_map(id,tenant_id,family_id,title,position_id,scope_org_id,created_by)
            values(:id,:tid,:id,:title,:position,:scope,:actor)
            """,p);
        else {
            if(!"DRAFT".equals(str(rows.getFirst(),"state")))throw conflict("已发布地图须创建新版本");
            if(s.db.update("update academy_map set title=:title,position_id=:position,scope_org_id=:scope,revision=revision+1 where tenant_id=:tid and id=:id and revision=:rev",p)!=1)
                throw conflict("地图已被其他页面更新");
            s.db.update("delete from academy_stage_course where tenant_id=:tid and stage_id in (select id from academy_stage where tenant_id=:tid and map_id=:id)",p);
            s.db.update("delete from academy_stage where tenant_id=:tid and map_id=:id",p);
        }
        int index=0;
        for(var stage:body.stages()){
            if(stage.courseIds()==null||stage.courseIds().isEmpty()||new HashSet<>(stage.courseIds()).size()!=stage.courseIds().size())throw bad("每阶段须选择不重复的必修课程");
            var courseCount=s.db.queryForObject("select count(*) from academy_course where tenant_id=:tid and id in (:courses)",
                s.params().addValue("courses",stage.courseIds()),Integer.class);
            if(courseCount==null||courseCount!=stage.courseIds().size())throw bad("课程不存在或不属于当前租户");
            validateRules(stage.rules(),stage.courseIds(),stage.passScore());
            UUID sid=UUID.randomUUID();
            s.db.update("""
                insert into academy_stage(id,tenant_id,map_id,stage_no,title,objective,due_days,exam_rules,pass_score,exam_minutes,attempt_limit,retry_minutes)
                values(:sid,:tid,:id,:n,:stageTitle,:objective,:due,cast(:rules as jsonb),:pass,:minutes,:attempts,:retry)
                """,p.addValue("sid",sid).addValue("n",++index).addValue("stageTitle",stage.title().trim()).addValue("objective",text(stage.objective()))
                    .addValue("due",stage.dueDays()).addValue("rules",s.encode(stage.rules())).addValue("pass",stage.passScore())
                    .addValue("minutes",stage.examMinutes()).addValue("attempts",stage.attemptLimit()).addValue("retry",stage.retryMinutes()));
            int order=0;for(UUID course:stage.courseIds())s.db.update("""
                insert into academy_stage_course(tenant_id,stage_id,course_id,sort_order) values(:tid,:sid,:course,:sort)
                """,s.params().addValue("sid",sid).addValue("course",course).addValue("sort",order++));
        }
        s.audit("ACADEMY_MAP_SAVED",id,Map.of("stages",index));return map(id);
    }
    static int validateRules(List<AcademyModels.DrawRule> rules,List<UUID> courses,int pass) {
        if(rules==null||rules.isEmpty())throw bad("请配置组卷规则");
        int total=0,questions=0;
        for(var r:rules){
            if(r.count()<1||r.points()<1)throw bad("题量和分值必须为正数");
            if(r.courseIds()!=null&&!courses.containsAll(r.courseIds()))throw bad("题库范围只能使用本阶段必修课程");
            total+=r.count()*r.points();questions+=r.count();
        }
        if(total>1000||questions>200||pass<=0||pass>total)throw bad("总分、题量或及格线无效");
        return total;
    }
    public Map<String,Object> cloneMap(UUID id) {
        manage();var source=map(id);UUID next=UUID.randomUUID();
        var stages=new ArrayList<AcademyModels.Stage>();
        for(Object value:(List<?>)source.get("stages")){
            @SuppressWarnings("unchecked") var stage=(Map<String,Object>)value;
            stages.add(new AcademyModels.Stage(str(stage,"title"),str(stage,"objective"),
                s.json.convertValue(stage.get("courseIds"),new TypeReference<List<UUID>>(){}),
                s.decode(stage.get("exam_rules"),new TypeReference<>(){}),integer(stage,"pass_score"),integer(stage,"exam_minutes"),
                integer(stage,"attempt_limit"),integer(stage,"retry_minutes"),(Integer)stage.get("due_days")));
        }
        saveMap(next,new AcademyModels.GrowthMap(str(source,"title"),id(source,"position_id"),id(source,"scope_org_id"),stages,0));
        s.one("select id from academy_map where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        s.db.update("""
            update academy_map set family_id=:family,version=(select max(version)+1 from academy_map where tenant_id=:tid and family_id=:family)
            where tenant_id=:tid and id=:next
            """,s.params().addValue("family",id(source,"family_id")).addValue("next",next));
        return map(next);
    }
    public Map<String,Object> publishMap(UUID id,int revision) {
        manage();var row=s.one("select * from academy_map where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        if(!"DRAFT".equals(str(row,"state"))||integer(row,"revision")!=revision)throw conflict("地图状态已变化，请刷新");
        // Serialize publication for one position so concurrent requests cannot publish competing maps.
        s.one("select id from position_definition where tenant_id=:tid and id=:position for update",s.params().addValue("position",id(row,"position_id")));
        var stages=s.db.queryForList("select * from academy_stage where tenant_id=:tid and map_id=:id order by stage_no",s.params().addValue("id",id));
        if(stages.isEmpty())throw bad("没有培训阶段");
        for(var stage:stages){
            var courses=s.db.queryForList("""
                select c.* from academy_stage_course sc join academy_course c on c.tenant_id=sc.tenant_id and c.id=sc.course_id
                where sc.tenant_id=:tid and sc.stage_id=:sid
                """,s.params().addValue("sid",id(stage,"id")));
            if(courses.isEmpty()||courses.stream().anyMatch(c->!"PUBLISHED".equals(str(c,"state"))))throw bad("所有阶段必修课必须先上传、预览并发布");
            draw(stage,Set.of()); // The same allocation algorithm validates coverage and overlapping buckets.
        }
        var conflictCount=s.db.queryForObject("""
            select count(*) from academy_map where tenant_id=:tid and state='PUBLISHED' and position_id=:position
            and scope_org_id is not distinct from :scope and family_id<>:family
            """,s.params().addValue("position",id(row,"position_id")).addValue("scope",id(row,"scope_org_id")).addValue("family",id(row,"family_id")),Integer.class);
        if(conflictCount!=null&&conflictCount>0)throw conflict("该岗位与适用范围已有其他已发布地图，请通过原地图创建新版本");
        s.db.update("update academy_map set state='RETIRED' where tenant_id=:tid and family_id=:family and state='PUBLISHED'",s.params().addValue("family",id(row,"family_id")));
        s.db.update("update academy_map set state='PUBLISHED',published_at=now(),revision=revision+1 where tenant_id=:tid and id=:id",s.params().addValue("id",id));
        s.db.update("""
            insert into academy_dispatch_queue(tenant_id,employee_id)
            select tenant_id,id from employee where tenant_id=:tid and employee_type='INTERN' and employment_status='ACTIVE'
            on conflict(tenant_id,employee_id) do update set requested_at=now()
            """,s.params());
        s.audit("ACADEMY_MAP_PUBLISHED",id,Map.of("revision",revision));return map(id);
    }
    /** Bipartite matching avoids false shortage for overlapping knowledge/difficulty buckets. */
    @Transactional(noRollbackFor = org.springframework.web.server.ResponseStatusException.class)
    List<Map<String,Object>> draw(Map<String,Object> stage,Set<String> previous) {
        var rules=s.decode(stage.get("exam_rules"),new TypeReference<List<AcademyModels.DrawRule>>(){});
        var courses=s.db.queryForList("select course_id from academy_stage_course where tenant_id=:tid and stage_id=:sid",
            s.params().addValue("sid",id(stage,"id")),UUID.class);
        var questions=s.db.queryForList("select * from academy_question where tenant_id=:tid and active and course_id in (:ids)",s.params().addValue("ids",courses));
        Collections.shuffle(questions,new java.security.SecureRandom());
        questions.sort(Comparator.comparing(q->previous.contains(str(q,"id"))));
        var slots=new ArrayList<AcademyModels.DrawRule>();
        for(var rule:rules)for(int i=0;i<rule.count();i++)slots.add(rule);
        int[] owners=new int[questions.size()];Arrays.fill(owners,-1);
        for(int i=0;i<slots.size();i++)if(!match(i,slots,questions,owners,new boolean[questions.size()]))
            throw bad("阶段“"+str(stage,"title")+"”的有效题量或知识点覆盖不足");
        var paper=new ArrayList<Map<String,Object>>();
        for(int i=0;i<owners.length;i++)if(owners[i]>=0){
            var q=questions.get(i);var item=new LinkedHashMap<String,Object>();
            item.put("id",str(q,"id"));item.put("version",q.get("version"));item.put("kind",q.get("kind"));
            item.put("stem",q.get("stem"));item.put("options",s.decode(q.get("options"),new TypeReference<List<String>>(){}));
            item.put("answers",s.integers(q.get("answers")));item.put("explanation",q.get("explanation"));
            item.put("knowledge",q.get("knowledge"));item.put("courseId",str(q,"course_id"));item.put("points",slots.get(owners[i]).points());
            paper.add(item);
        }
        Collections.shuffle(paper,new java.security.SecureRandom());return paper;
    }
    private boolean match(int slot,List<AcademyModels.DrawRule> slots,List<Map<String,Object>> questions,int[] owners,boolean[] seen) {
        var rule=slots.get(slot);
        for(int i=0;i<questions.size();i++){
            var q=questions.get(i);
            if(seen[i]||!rule.kind().equals(str(q,"kind"))
                ||(!text(rule.difficulty()).isEmpty()&&!rule.difficulty().equals(str(q,"difficulty")))
                ||(!text(rule.knowledge()).isEmpty()&&!rule.knowledge().equals(str(q,"knowledge")))
                ||(rule.courseIds()!=null&&!rule.courseIds().isEmpty()&&!rule.courseIds().contains(id(q,"course_id"))))continue;
            seen[i]=true;if(owners[i]<0||match(owners[i],slots,questions,owners,seen)){owners[i]=slot;return true;}
        }
        return false;
    }
}
