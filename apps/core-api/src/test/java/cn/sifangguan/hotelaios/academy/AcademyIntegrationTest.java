package cn.sifangguan.hotelaios.academy;

import cn.sifangguan.hotelaios.shared.context.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static cn.sifangguan.hotelaios.academy.AcademySupport.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AcademyIntegrationTest {
    static final UUID T=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final EmbeddedPostgres PG=start();static final String URL=url();static final Path FILES=temp();
    @Autowired AcademyAdministration admin;@Autowired AcademyPlans plans;@Autowired AcademyLearning learning;
    @Autowired AcademyMedia media;@Autowired JdbcTemplate db;
    @Autowired AcademyController controller;
    UUID actor,employee,assignment,position,org,course,map;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);r.add("app.academy.storage-root",FILES::toString);
    }
    @AfterAll static void close()throws Exception{PG.close();}
    @AfterEach void clear(){TenantContext.clear();}
    @BeforeEach void setup(){
        actor=UUID.randomUUID();employee=UUID.randomUUID();assignment=UUID.randomUUID();org=UUID.randomUUID();position=UUID.randomUUID();
        db.update("insert into user_account(id,tenant_id,login_name,display_name,status) values(?,?,?,'学院测试员工','ACTIVE')",actor,T,actor.toString());
        db.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?,?,?,'学院测试门店','HOTEL')",org,T,org.toString());
        db.update("insert into org_unit_closure(tenant_id,ancestor_id,descendant_id,depth) values(?,?,?,0)",T,org,org);
        db.update("insert into position_definition(id,tenant_id,code,name,job_family) values(?,?,?,'学院测试岗位','OPERATIONS')",position,T,position.toString());
        db.update("insert into employee(id,tenant_id,account_id,employee_no,name,employee_type) values(?,?,?,?,'学院测试员工','INTERN')",employee,T,actor,employee.toString());
        db.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) values(?,?,?,?,?,current_date)",assignment,T,employee,org,position);
        db.update("insert into wecom_user_binding(tenant_id,corp_id,wecom_user_id,account_id,preferred_assignment_id,user_id_fingerprint) values(?,'academy-test',?,?,?,?)",T,actor.toString(),actor,assignment,actor.toString().replace("-","").repeat(2));
        as(actor,true,"academy.manage","academy.learn","academy.team-read");
        course=id(admin.saveCourse(null,new AcademyModels.Course("入职视频","测试","VIDEO",List.of(),List.of(),0)),"id");
        db.update("update academy_course set state='READY',object_key='fixture',duration_seconds=10 where id=?",course);
        admin.publishCourse(course,0);
        for(int n=0;n<6;n++)admin.saveQuestion(null,new AcademyModels.Question(null,course,"SINGLE","第"+n+"题",List.of("正确","错误"),List.of(0),"解释","礼仪","NORMAL",true,0));
        map=id(admin.saveMap(null,new AcademyModels.GrowthMap("岗位成长",position,org,List.of(stage("第一阶段"),stage("第二阶段")),0)),"id");
        admin.publishMap(map,0);
    }
    private AcademyModels.Stage stage(String title){return new AcademyModels.Stage(title,"目标",List.of(course),List.of(new AcademyModels.DrawRule("SINGLE",2,50,null,null,List.of())),80,30,2,0,1);}
    private UUID plan(){plans.mine();return db.queryForObject("select id from academy_plan where assignment_id=?",UUID.class,assignment);}
    private void completeCourse(){db.update("insert into academy_progress(tenant_id,employee_id,course_id,watched_seconds,completed_at) values(?,?,?,10,now()) on conflict(tenant_id,employee_id,course_id) do update set completed_at=now(),watched_seconds=10",T,employee,course);}
    private UUID exam(UUID plan){return db.queryForObject("select id from academy_exam where plan_id=? order by created_at desc,attempt_no desc limit 1",UUID.class,plan);}
    private Map<String,List<Integer>> correct(UUID exam){
        var json=new com.fasterxml.jackson.databind.ObjectMapper();try{
            List<Map<String,Object>> paper=json.readValue(db.queryForObject("select paper::text from academy_exam where id=?",String.class,exam),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            Map<String,List<Integer>> answers=new LinkedHashMap<>();for(var q:paper)answers.put(q.get("id").toString(),List.of(0));return answers;
        }catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void internsOnlyAndExistingPlansContinueAfterBothTypeChanges(){
        UUID p=plan();plans.mine();assertThat(db.queryForObject("select count(*) from academy_plan where assignment_id=?",Integer.class,assignment)).isEqualTo(1);
        db.update("update employee set employee_type='REGULAR' where id=?",employee);completeCourse();plans.detail(p);
        UUID first=exam(p);var started=plans.startExam(first);assertThat((List<?>)started.get("questions")).hasSize(2);
        plans.answer(first,new AcademyModels.Responses(correct(first),integer(started,"version")),true);
        assertThat(db.queryForObject("select current_stage from academy_plan where id=?",Integer.class,p)).isEqualTo(2);
        db.update("update employee set employee_type='SECONDMENT' where id=?",employee);
        UUID second=exam(p);var next=plans.startExam(second);plans.answer(second,new AcademyModels.Responses(correct(second),integer(next,"version")),true);
        assertThat(db.queryForObject("select state from academy_plan where id=?",String.class,p)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("select employee_type from employee where id=?",String.class,employee)).isEqualTo("SECONDMENT");
    }
    @Test void regularAndUnknownDoNotGetNewPlansAndBindingIsRequired(){
        for(String type:new String[]{"REGULAR","SECONDMENT",null}){
            db.update("update employee set employee_type=? where id=?",type,employee);plans.mine();
            assertThat(db.queryForObject("select count(*) from academy_plan where assignment_id=?",Integer.class,assignment)).isZero();
        }
        db.update("update employee set employee_type='INTERN' where id=?",employee);
        db.update("update wecom_user_binding set status='SUSPENDED' where account_id=?",actor);plans.mine();
        assertThat(db.queryForObject("select count(*) from academy_plan where assignment_id=?",Integer.class,assignment)).isZero();
        db.update("update wecom_user_binding set status='ACTIVE' where account_id=?",actor);assertThat(plan()).isNotNull();
    }
    @Test void rejectsSkippedVideoAcceleratedPlaybackAndSupersededSessions(){
        UUID p=plan();var session=learning.start(new AcademyModels.Session(p,course,0,1));UUID sid=(UUID)session.get("sessionId");
        learning.heartbeat(sid,new AcademyModels.Heartbeat(1,0,1,true,true,1));
        assertThatThrownBy(()->learning.heartbeat(sid,new AcademyModels.Heartbeat(2,9,1,true,true,1))).hasMessageContaining("不能跳过");
        assertThatThrownBy(()->learning.heartbeat(sid,new AcademyModels.Heartbeat(2,0,1,true,true,2))).hasMessageContaining("正常速度");
        db.update("update academy_learning_session set last_seen=clock_timestamp()-interval '5 seconds' where id=?",sid);
        assertThat(learning.heartbeat(sid,new AcademyModels.Heartbeat(2,5,1,true,true,1))).containsEntry("watchedSeconds",5.0);
        learning.heartbeat(sid,new AcademyModels.Heartbeat(3,1,1,true,true,1)); // replay is permitted
        learning.start(new AcademyModels.Session(p,course,0,1));
        assertThatThrownBy(()->learning.heartbeat(sid,new AcademyModels.Heartbeat(4,2,1,true,true,1))).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("select count(*) from academy_exam where plan_id=?",Integer.class,p)).isZero();
    }
    @Test void accountReassignmentRevokesTheOldOwnersLearningLease(){
        UUID p=plan();UUID session=(UUID)learning.start(new AcademyModels.Session(p,course,0,1)).get("sessionId");
        UUID replacement=UUID.randomUUID();
        db.update("insert into user_account(id,tenant_id,login_name,display_name,status) values(?,?,?,'替换账号','ACTIVE')",replacement,T,replacement.toString());
        db.update("update employee set account_id=? where id=?",replacement,employee);
        assertThatThrownBy(()->learning.heartbeat(session,new AcademyModels.Heartbeat(1,0,1,true,true,1))).hasMessageContaining("失效");
        plans.detail(p);
        assertThat(db.queryForObject("select state from academy_plan where id=?",String.class,p)).isEqualTo("SUSPENDED");
    }
    @Test void missingMapIsRecoveredByTheWorkerAndPublishedPlansKeepTheirVersion(){
        db.update("update academy_map set state='RETIRED' where id=?",map);
        plans.processTenant(T);
        assertThat(db.queryForObject("select count(*) from academy_dispatch_issue where assignment_id=?",Integer.class,assignment)).isEqualTo(1);
        UUID replacement=id(admin.cloneMap(map),"id");admin.publishMap(replacement,0);plans.processTenant(T);
        UUID p=plan();
        assertThat(db.queryForObject("select map_id from academy_plan where id=?",UUID.class,p)).isEqualTo(replacement);
        assertThat(db.queryForObject("select count(*) from academy_dispatch_issue where assignment_id=?",Integer.class,assignment)).isZero();
        UUID next=id(admin.cloneMap(replacement),"id");admin.publishMap(next,0);plans.processTenant(T);
        assertThat(db.queryForObject("select map_id from academy_plan where id=?",UUID.class,p)).isEqualTo(replacement);
        assertThat(db.queryForObject("select count(*) from academy_plan where assignment_id=?",Integer.class,assignment)).isEqualTo(1);
    }
    @Test void insufficientQuestionsPersistTheConfigurationIssueAndRecoverWithoutSkippingTheExam(){
        UUID p=plan();db.update("update academy_question set active=false where course_id=?",course);completeCourse();
        plans.detail(p);
        assertThat(db.queryForObject("select state from academy_plan_stage where plan_id=?",String.class,p)).isEqualTo("CONFIG_ERROR");
        assertThat(db.queryForObject("select count(*) from academy_exam where plan_id=?",Integer.class,p)).isZero();
        db.update("update academy_question set active=true where course_id=?",course);plans.detail(p);
        assertThat(db.queryForObject("select state from academy_plan_stage where plan_id=?",String.class,p)).isEqualTo("READY");
        assertThat(db.queryForObject("select current_stage from academy_plan where id=?",Integer.class,p)).isEqualTo(1);
    }
    @Test void concurrentMapPublishingDoesNotCreateAmbiguousAssignmentRules()throws Exception{
        db.update("update academy_map set state='RETIRED' where id=?",map);
        var candidates=new ArrayList<UUID>();
        for(int i=0;i<2;i++)candidates.add(id(admin.saveMap(null,new AcademyModels.GrowthMap("并发发布"+i,position,org,List.of(stage("培训")),0)),"id"));
        var ready=new java.util.concurrent.CyclicBarrier(2);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(UUID candidate:candidates)results.add(workers.submit(()->{
                as(actor,true,"academy.manage");
                try{ready.await();admin.publishMap(candidate,0);return true;}
                catch(ResponseStatusException conflict){return false;}
                finally{TenantContext.clear();}
            }));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(db.queryForObject("select count(*) from academy_map where tenant_id=? and position_id=? and scope_org_id=? and state='PUBLISHED'",Integer.class,T,position,org)).isEqualTo(1);
    }
    @Test void apiReturnsAnActionableLearningError()throws Exception{
        var mvc=standaloneSetup(controller)
            .setControllerAdvice(new AcademyExceptionHandler()).build();
        mvc.perform(get("/api/v1/academy/plans/"+UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("学院记录不存在或无权访问"));
        UUID p=plan();
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/academy/team/"+p+"/remind")).andExpect(status().isNoContent());
        assertThat(db.queryForObject("select count(*) from notification where source_id=? and notification_type='ACADEMY_REMINDER'",Integer.class,p)).isEqualTo(1);
    }
    @Test void paperIsStableNoAnswersLeakAndTimeoutUsesSavedResponses(){
        UUID p=plan();completeCourse();plans.detail(p);UUID exam=exam(p);
        assertThat((List<?>)plans.exam(exam).get("questions")).isEmpty();
        var started=plans.startExam(exam);
        assertThat(started.get("questions")).isEqualTo(plans.exam(exam).get("questions"));
        assertThat(started.get("questions").toString()).doesNotContain("answers=","explanation=");
        var answers=correct(exam);var saved=plans.answer(exam,new AcademyModels.Responses(answers,integer(started,"version")),false);
        assertThatThrownBy(()->plans.answer(exam,new AcademyModels.Responses(Map.of(),integer(started,"version")),false)).hasMessageContaining("更新");
        db.update("update academy_question set answers='[1]',explanation='changed' where course_id=?",course);
        db.update("update academy_exam set deadline=clock_timestamp()-interval '1 second' where id=?",exam);
        var result=plans.exam(exam);assertThat(result).containsEntry("state","PASSED").containsEntry("score",100);
        assertThat(result.get("questions").toString()).doesNotContain("changed");
    }
    @Test void failedAttemptsExhaustThenAdministratorAddsOne(){
        UUID p=plan();completeCourse();plans.detail(p);
        for(int i=0;i<2;i++){UUID exam=exam(p);var started=plans.startExam(exam);var result=plans.answer(exam,new AcademyModels.Responses(Map.of(),integer(started,"version")),true);assertThat(result).containsEntry("state","FAILED");assertThat(result.get("questions").toString()).doesNotContain("answers=");}
        UUID stage=db.queryForObject("select stage_id from academy_plan_stage where plan_id=?",UUID.class,p);
        assertThat(db.queryForObject("select state from academy_plan_stage where plan_id=?",String.class,p)).isEqualTo("NEEDS_ATTENTION");
        plans.addAttempt(p,new AcademyModels.ExtraAttempt(stage,"复习完成，追加补考"));assertThat(db.queryForObject("select count(*) from academy_exam where plan_id=?",Integer.class,p)).isEqualTo(3);
    }
    @Test void planOwnershipScopeAndTenantRlsAreEnforced()throws Exception{
        UUID p=plan();as(UUID.randomUUID(),false,"academy.learn");
        assertThatThrownBy(()->plans.detail(p)).isInstanceOf(ResponseStatusException.class);
        as(actor,false,"academy.manage");assertThatThrownBy(()->admin.catalogue()).isInstanceOf(ResponseStatusException.class);
        try(var connection=PG.getPostgresDatabase().getConnection();var statement=connection.createStatement()){
            statement.execute("set role hotel_ai_os_app");statement.execute("select set_config('app.tenant_id','"+UUID.randomUUID()+"',false)");
            try(var rows=statement.executeQuery("select count(*) from academy_plan")){rows.next();assertThat(rows.getInt(1)).isZero();}
        }
    }
    @Test void pptRendersEveryPageAndOnlyCreditedTimeCompletesIt()throws Exception{
        UUID ppt=id(admin.saveCourse(null,new AcademyModels.Course("PPT","说明","PPT",List.of(),List.of(),0)),"id");
        byte[] bytes;try(var show=new XMLSlideShow();var output=new ByteArrayOutputStream()){
            for(int i=0;i<2;i++){var box=show.createSlide().createTextBox();box.setAnchor(new Rectangle2D.Double(40,40,500,80));box.setText("Training page "+(i+1));}show.write(output);bytes=output.toByteArray();
        }
        var uploaded=media.upload(ppt,0,new MockMultipartFile("file","training.pptx","application/octet-stream",bytes));
        assertThat(uploaded).containsEntry("page_count",2);assertThat(media.content(ppt,null,1).getBody().contentLength()).isGreaterThan(100);
        admin.publishCourse(ppt,integer(uploaded,"revision"));
        UUID differentMap=id(admin.cloneMap(map),"id");
        admin.saveMap(differentMap,new AcademyModels.GrowthMap("PPT成长",position,org,List.of(new AcademyModels.Stage("PPT阶段","",List.of(ppt),List.of(new AcademyModels.DrawRule("SINGLE",1,100,null,null,List.of())),80,30,3,30,null)),0));
        admin.saveQuestion(null,new AcademyModels.Question(null,ppt,"SINGLE","培训题",List.of("是","否"),List.of(0),"","","NORMAL",true,0));
        admin.publishMap(differentMap,1);
        UUID p=plan();UUID sid=(UUID)learning.start(new AcademyModels.Session(p,ppt,0,1)).get("sessionId");
        learning.heartbeat(sid,new AcademyModels.Heartbeat(1,0,1,false,true,1));
        assertThatThrownBy(()->learning.heartbeat(sid,new AcademyModels.Heartbeat(2,0,2,false,true,1))).hasMessageContaining("前面");
        db.update("update academy_learning_session set last_seen=clock_timestamp()-interval '6 seconds' where id=?",sid);
        assertThat(learning.heartbeat(sid,new AcademyModels.Heartbeat(2,0,1,false,true,1))).containsEntry("completedPages",1).containsEntry("completed",false);
        learning.heartbeat(sid,new AcademyModels.Heartbeat(3,0,2,false,true,1));
        db.update("update academy_learning_session set last_seen=clock_timestamp()-interval '6 seconds' where id=?",sid);
        assertThat(learning.heartbeat(sid,new AcademyModels.Heartbeat(4,0,2,false,true,1))).containsEntry("completed",true);
        assertThat(exam(p)).isNotNull();
    }
    private void as(UUID id,boolean all,String... permissions){TenantContext.set(new TenantPrincipal(T,id,"ACADEMY_TEST",Set.of("ACADEMY_TEST"),Set.of(permissions),Set.of(org),Set.of(assignment),all,UUID.randomUUID()));}
    private static EmbeddedPostgres start(){try{var pg=EmbeddedPostgres.builder().start();try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");}return pg;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Path temp(){try{return Files.createTempDirectory("academy-integration-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
