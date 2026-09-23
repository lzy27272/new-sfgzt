package cn.sifangguan.hotelaios.academy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static cn.sifangguan.hotelaios.academy.AcademySupport.*;

/** Progress advances only under an employee lock and a current, ordered learning lease. */
@Service
@Transactional
public class AcademyLearning {
    private final AcademySupport s;
    private final AcademyPlans plans;
    public AcademyLearning(AcademySupport s,AcademyPlans plans){this.s=s;this.plans=plans;}

    public Map<String,Object> start(AcademyModels.Session body){
        var actor=s.prepare("academy.learn");
        var plan=plans.ownPlan(body.planId(),true);
        var course=plans.accessibleCourse(body.planId(),body.courseId());
        var p=s.params().addValue("employee",id(plan,"employee_id")).addValue("course",body.courseId());
        s.db.update("insert into academy_progress(tenant_id,employee_id,course_id) values(:tid,:employee,:course) on conflict do nothing",p);
        var progress=s.one("select * from academy_progress where tenant_id=:tid and employee_id=:employee and course_id=:course",p);
        double position=Double.isFinite(body.position())?Math.min(body.position(),number(progress,"watched_seconds")):0;
        int page=Math.min(body.page(),integer(progress,"completed_pages")+1);
        if("PPT".equals(str(course,"kind")))page=Math.min(page,integer(course,"page_count"));
        UUID session=UUID.randomUUID();
        s.db.update("""
            insert into academy_learning_session(id,tenant_id,employee_id,account_id,plan_id,course_id,last_position,page_no,expires_at)
            values(:id,:tid,:employee,:actor,:plan,:course,:position,:page,clock_timestamp()+interval '45 seconds')
            on conflict(tenant_id,employee_id) do update set id=excluded.id,account_id=excluded.account_id,
              plan_id=excluded.plan_id,course_id=excluded.course_id,sequence_no=0,last_position=excluded.last_position,
              page_no=excluded.page_no,last_seen=clock_timestamp(),expires_at=excluded.expires_at,was_playing=false,was_visible=false
            """,p.addValue("id",session).addValue("actor",actor.actorId()).addValue("plan",body.planId()).addValue("position",position).addValue("page",page));
        course.remove("object_key");progress.put("sessionId",session);progress.put("sequence",0);
        progress.put("position",position);progress.put("page",page);progress.put("course",course);
        return progress;
    }

    public Map<String,Object> heartbeat(UUID sessionId,AcademyModels.Heartbeat body){
        s.prepare("academy.learn");
        var lease=s.one("select * from academy_learning_session where tenant_id=:tid and id=:id and account_id=:actor",
            s.params().addValue("id",sessionId).addValue("actor",s.access.principal().actorId()));
        var plan=plans.ownPlan(id(lease,"plan_id"),true);
        lease=s.one("select * from academy_learning_session where tenant_id=:tid and id=:id for update",s.params().addValue("id",sessionId));
        plans.requireActive(plan);
        var now=s.now();
        if(!now.isBefore(instant(lease.get("expires_at"))))throw conflict("学习会话已过期，请重新打开课程");
        if(body.sequence()!=integer(lease,"sequence_no")+1)throw conflict("学习进度已在其他请求更新，请重新打开课程");
        if(!Double.isFinite(body.position())||!Double.isFinite(body.playbackRate()))throw bad("学习进度无效");
        var course=plans.accessibleCourse(id(plan,"id"),id(lease,"course_id"));
        var p=s.params().addValue("employee",id(plan,"employee_id")).addValue("course",id(lease,"course_id"));
        var progress=s.one("select * from academy_progress where tenant_id=:tid and employee_id=:employee and course_id=:course for update",p);
        double elapsed=Math.max(0,Duration.between(instant(lease.get("last_seen")),now).toMillis()/1000.0);
        boolean continuous=elapsed<=15 && Boolean.TRUE.equals(lease.get("was_visible")) && body.visible();
        double watched=number(progress,"watched_seconds");
        int completed=integer(progress,"completed_pages");
        List<Double> pageElapsed=s.decode(progress.get("page_elapsed"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        boolean done=progress.get("completed_at")!=null;
        double position=body.position();int page=body.page();
        if("VIDEO".equals(str(course,"kind"))){
            double duration=number(course,"duration_seconds"),previous=number(lease,"last_position"),delta=position-previous;
            if(position>duration+0.25 || position<0)throw bad("视频位置超出范围");
            if(body.playbackRate()!=1)throw bad("培训视频只支持正常速度播放");
            if(position>watched+0.25){
                if(!continuous || !Boolean.TRUE.equals(lease.get("was_playing")) || delta<0 || delta>elapsed+0.25 || previous>watched+0.25)
                    throw conflict("不能跳过尚未观看的内容，请从已学习位置继续");
                // The cursor and the frontier must remain contiguous; elapsed time alone never adds progress.
                watched=Math.min(duration,Math.max(watched,position));
            }
            done=done || watched>=duration-0.25;
        }else{
            int count=integer(course,"page_count"),previous=integer(lease,"page_no");
            if(page<1 || page>count || page>completed+1)throw conflict("请先完成前面的课件页面");
            var required=s.integers(course.get("page_seconds"));
            pageElapsed=new ArrayList<>(pageElapsed);
            while(pageElapsed.size()<count)pageElapsed.add(0.0);
            if(continuous && page==previous){
                int index=page-1;
                pageElapsed.set(index,Math.min(required.get(index),pageElapsed.get(index)+elapsed));
            }
            while(completed<count && pageElapsed.get(completed)>=required.get(completed))completed++;
            done=done || completed==count;
        }
        s.db.update("""
            update academy_progress set watched_seconds=:watched,completed_pages=:pages,page_elapsed=cast(:elapsed as jsonb),
             completed_at=case when :done then coalesce(completed_at,clock_timestamp()) else completed_at end,updated_at=clock_timestamp()
            where tenant_id=:tid and employee_id=:employee and course_id=:course
            """,p.addValue("watched",watched).addValue("pages",completed).addValue("elapsed",s.encode(pageElapsed)).addValue("done",done));
        s.db.update("""
            update academy_learning_session set sequence_no=:seq,last_position=:position,page_no=:page,
              last_seen=clock_timestamp(),expires_at=clock_timestamp()+interval '45 seconds',was_playing=:playing,was_visible=:visible
            where tenant_id=:tid and id=:id
            """,s.params().addValue("id",sessionId).addValue("seq",body.sequence()).addValue("position",position)
            .addValue("page",page).addValue("playing",body.playing()).addValue("visible",body.visible()));
        if(done && progress.get("completed_at")==null){
            s.audit("ACADEMY_COURSE_COMPLETED",id(lease,"course_id"),Map.of("planId",id(plan,"id")));
            for(var active:s.db.queryForList("select * from academy_plan where tenant_id=:tid and employee_id=:employee and state='ACTIVE' order by id for update",p))plans.refresh(active);
        }
        return Map.of("sequence",body.sequence(),"watchedSeconds",watched,"completedPages",completed,"pageElapsed",pageElapsed,"completed",done);
    }
}

