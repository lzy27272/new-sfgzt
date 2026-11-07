package cn.sifangguan.hotelaios.operations;

import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsPlanIntegrationTest {
    private static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GROUP=UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final EmbeddedPostgres PG=start();
    @Autowired OperationsPlanService plans;
    @Autowired OperationsPlanReminderService reminders;
    @Autowired JdbcTemplate jdbc;
    UUID owner,ownerAccount,leader,leaderAccount; List<UUID> hotels;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->{try(var c=PG.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(Exception e){throw new RuntimeException(e);}});
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.database.rls-enabled",()->true);
        r.add("app.security.development-header-auth-enabled",()->true);
        r.add("app.work-expectation.sla.scheduler-enabled",()->false);
    }
    @BeforeEach void seed() {
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,TENANT.toString());
        hotels=jdbc.queryForList("select id from org_unit where tenant_id=? and unit_type='HOTEL' and status='ACTIVE' order by id limit 2",UUID.class,TENANT);
        assertThat(hotels).hasSize(2);
        leader=actor("OPERATIONS_DIRECTOR");leaderAccount=account(leader);
        owner=actor("OPERATIONS_MANAGER");ownerAccount=account(owner);
        jdbc.update("update employee_position_assignment set manager_assignment_id=? where tenant_id=? and id=?",leader,TENANT,owner);
        principal(owner,ownerAccount,"OPERATIONS_MANAGER");
    }
    @AfterEach void clear(){TenantContext.clear();}
    @AfterAll static void close() throws Exception {PG.close();}

    @Test void approvalIssuesTwoManualTasksWithImmutableSourceAndReplaysAcrossWeekAndMonth() {
        List<OperationsPlanModels.Item> actions=List.of(item(hotels.getFirst(),owner),item(hotels.getLast(),owner));
        var saved=save("WEEKLY",null,actions);UUID id=id(saved);plans.submit(id);
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");
        assertThat(plans.preview(id).get("newTaskCount")).isEqualTo(2L);
        var reviewed=plans.review(id,approve(saved));assertThat(reviewed.get("status")).isEqualTo("APPROVED");
        plans.review(id,approve(saved));
        assertThat(countTasks(owner)).isEqualTo(2);
        assertThat(jdbc.queryForList("select lifecycle_status from management_task where tenant_id=? and creation_source='WORK_PLAN' and created_by_assignment_id=?",String.class,TENANT,leader)).containsOnly("PENDING_ACK");
        assertThat(jdbc.queryForObject("select count(*) from management_work_plan_task_link l join operations_plan_action a on a.tenant_id=l.tenant_id and a.task_id=l.task_id where a.tenant_id=? and a.owner_assignment_id=?",Integer.class,TENANT,owner)).isEqualTo(2);
        jdbc.execute("set constraints all immediate");
        principal(owner,ownerAccount,"OPERATIONS_MANAGER");var monthly=save("MONTHLY",null,actions);plans.submit(id(monthly));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");assertThat(plans.preview(id(monthly)).get("newTaskCount")).isEqualTo(0L);
        plans.review(id(monthly),approve(monthly));assertThat(countTasks(owner)).isEqualTo(2);
    }
    @Test void draftsRestoreAndStaleDraftAndReviewRevisionsCannotOverwriteOrApproveNewContent() {
        var saved=save("WEEKLY",null,List.of(item(hotels.getFirst(),owner)));
        var updated=save("WEEKLY",saved,List.of(item(hotels.getFirst(),owner)));
        assertThatThrownBy(()->save("WEEKLY",saved,List.of())).isInstanceOf(ResponseStatusException.class);
        assertThat(plans.detail(id(updated)).get("draftVersion")).isEqualTo(updated.get("draftVersion"));
        plans.submit(id(updated));principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");
        plans.review(id(updated),new OperationsPlanModels.Review("RETURN","请补齐依据",(UUID)updated.get("revisionId")));
        principal(owner,ownerAccount,"OPERATIONS_MANAGER");var revised=save("WEEKLY",updated,List.of(item(hotels.getFirst(),owner)));plans.submit(id(revised));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");
        assertThatThrownBy(()->plans.review(id(revised),approve(updated))).isInstanceOf(ResponseStatusException.class);
        assertThat(countTasks(owner)).isZero();assertThat(plans.detail(id(revised)).get("status")).isEqualTo("PENDING_APPROVAL");
    }
    @Test void approvalMixesTwoNewActionsAndOneExplicitExistingTaskLinkAtomically() {
        var original=item(hotels.getFirst(),owner);var prior=save("WEEKLY",null,List.of(original));plans.submit(id(prior));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");plans.review(id(prior),approve(prior));
        UUID originalTask=jdbc.queryForObject("select task_id from operations_plan_action where tenant_id=? and owner_assignment_id=? and action_id=?",UUID.class,TENANT,owner,original.actionId());
        var linked=new OperationsPlanModels.Item(UUID.randomUUID(),original.hotelId(),owner,original.title(),original.description(),original.dueAt(),original.acceptanceCriteria(),originalTask,null);
        principal(owner,ownerAccount,"OPERATIONS_MANAGER");var combined=save("MONTHLY",null,List.of(item(hotels.getFirst(),owner),item(hotels.getLast(),owner),linked));plans.submit(id(combined));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");var preview=plans.preview(id(combined));
        assertThat(preview.get("newTaskCount")).isEqualTo(2L);assertThat(preview.get("linkedCount")).isEqualTo(1L);
        var approved=plans.review(id(combined),approve(combined));plans.review(id(combined),approve(combined));
        assertThat(approved.get("status")).isEqualTo("APPROVED");assertThat(countTasks(owner)).isEqualTo(3);
        @SuppressWarnings("unchecked") var resultItems=(List<Map<String,Object>>)approved.get("items");
        assertThat(resultItems).filteredOn(row->originalTask.equals(row.get("taskId"))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from management_work_plan_task_link where tenant_id=? and task_id=?",Integer.class,TENANT,originalTask)).isEqualTo(1);
        jdbc.execute("set constraints all immediate");
    }
    @Test void selfReviewPeersAssistantsAndCrossTenantReadsFailClosed() {
        var saved=save("WEEKLY",null,List.of(item(hotels.getFirst(),owner)));plans.submit(id(saved));
        assertThatThrownBy(()->plans.review(id(saved),approve(saved))).isInstanceOf(AccessDeniedException.class);
        UUID peer=actor("OPERATIONS_MANAGER");principal(peer,account(peer),"OPERATIONS_MANAGER");
        assertThatThrownBy(()->plans.detail(id(saved))).isInstanceOf(AccessDeniedException.class);
        UUID assistant=actor("OTA_OPERATION_ASSISTANT");principal(assistant,account(assistant),"OTA_OPERATION_ASSISTANT");
        assertThatThrownBy(()->plans.save("WEEKLY",LocalDate.now(),new OperationsPlanModels.Draft("s","o","",Map.of(),List.of(),null,null))).isInstanceOf(AccessDeniedException.class);
        TenantContext.set(new TenantPrincipal(UUID.randomUUID(),ownerAccount,"OPERATIONS_MANAGER",Set.of("OPERATIONS_MANAGER"),Set.of("*"),Set.copyOf(hotels),Set.of(owner),owner,false,UUID.randomUUID()));
        assertThatThrownBy(()->plans.detail(id(saved))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void headquartersSupervisorCanApproveOwnersDirectSubordinateActionWithoutBeingItsDirectLeader() {
        UUID subordinate=actor("OPERATIONS_MANAGER");jdbc.update("update employee_position_assignment set manager_assignment_id=? where tenant_id=? and id=?",owner,TENANT,subordinate);
        var saved=save("WEEKLY",null,List.of(item(hotels.getFirst(),subordinate)));plans.submit(id(saved));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");plans.review(id(saved),approve(saved));
        assertThat(countTasks(owner)).isEqualTo(1);
    }
    @Test void explicitReportingRelationsSupportTargetsSubmissionAndApproval() {
        jdbc.update("update employee_position_assignment set manager_assignment_id=null where tenant_id=? and id=?",TENANT,owner);
        jdbc.update("insert into position_assignment_reporting_relation(tenant_id,subordinate_assignment_id,leader_assignment_id,valid_from) values(?,?,?,current_date)",TENANT,owner,leader);
        var saved=save("WEEKLY",null,List.of(item(hotels.getFirst(),owner)));plans.submit(id(saved));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");plans.review(id(saved),approve(saved));assertThat(countTasks(owner)).isEqualTo(1);
    }
    @Test void missingPortfolioAndUnauthorizedHotelBlockSubmissionWithoutCreatingTasks() {
        jdbc.update("delete from employee_assignment_hotel_scope where tenant_id=? and assignment_id=?",TENANT,owner);
        assertThatThrownBy(()->save("WEEKLY",null,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(countTasks(owner)).isZero();
        jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)",TENANT,owner,hotels.getFirst());
        var outside=save("WEEKLY",null,List.of(item(hotels.getLast(),owner)));
        assertThatThrownBy(()->plans.submit(id(outside))).isInstanceOf(AccessDeniedException.class);
        assertThat(countTasks(owner)).isZero();
    }
    @Test void monthlyLateSubmissionKeepsSubmissionTimeWhenLeaderApprovesLater() {
        LocalDate past=LocalDate.now().minusMonths(1).withDayOfMonth(1);
        var saved=plans.save("MONTHLY",past,draft(null,List.of(item(hotels.getFirst(),owner))));
        var submitted=plans.submit(id(saved));assertThat(submitted.get("late")).isEqualTo(true);
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");var approved=plans.review(id(saved),approve(saved));
        assertThat(approved.get("submittedAt")).isEqualTo(submitted.get("submittedAt"));assertThat(approved.get("late")).isEqualTo(true);
    }
    @Test void changingPublishedActionDeadlineIsRejectedInsteadOfErasingOverdueFacts() {
        var original=item(hotels.getFirst(),owner);var saved=save("WEEKLY",null,List.of(original));plans.submit(id(saved));
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");plans.review(id(saved),approve(saved));
        principal(owner,ownerAccount,"OPERATIONS_MANAGER");
        var changed=new OperationsPlanModels.Item(original.actionId(),original.hotelId(),owner,original.title(),"",original.dueAt().plusDays(1),original.acceptanceCriteria(),null,null);
        var month=save("MONTHLY",null,List.of(changed));assertThatThrownBy(()->plans.submit(id(month))).isInstanceOf(IllegalArgumentException.class);
        assertThat(countTasks(owner)).isEqualTo(1);
    }
    @Test void reminderTicksDoNotDuplicateAndOverdueEscalatesAtWorkingTime() {
        ZoneId zone=ZoneId.of("Asia/Shanghai");LocalDate start=OperationsPlanTiming.start("WEEKLY",LocalDate.now(zone));
        OffsetDateTime sunday=start.plusDays(6).atTime(18,0).atZone(zone).toOffsetDateTime();
        reminders.processTenant(TENANT,sunday);reminders.processTenant(TENANT,sunday.plusMinutes(1));
        @SuppressWarnings("unchecked") var own=(List<Map<String,Object>>)plans.list("WEEKLY",start,"OWN").get("items");
        UUID planId=(UUID)own.getFirst().get("id");
        assertThat(jdbc.queryForObject("select count(*) from notification where tenant_id=? and source_id=? and recipient_assignment_id=? and notification_type='OPERATIONS_PLAN_REMINDER'",Integer.class,TENANT,planId,owner)).isEqualTo(1);
        reminders.processTenant(TENANT,sunday.plusHours(14));
        assertThat(jdbc.queryForObject("select count(*) from notification where tenant_id=? and source_id=? and notification_type='OPERATIONS_PLAN_OVERDUE'",Integer.class,TENANT,planId)).isZero();
        reminders.processTenant(TENANT,sunday.plusHours(15));reminders.processTenant(TENANT,sunday.plusHours(15).plusMinutes(1));
        assertThat(jdbc.queryForObject("select count(*) from notification where tenant_id=? and source_id=? and recipient_assignment_id=? and notification_type='OPERATIONS_PLAN_OVERDUE'",Integer.class,TENANT,planId,leader)).isEqualTo(1);
    }
    @Test void reviewListAppliesReportingAuthorizationBeforeLimitAndLoadsOnlySummary() {
        var saved=save("WEEKLY",null,List.of(item(hotels.getFirst(),owner)));plans.submit(id(saved));
        LocalDate start=OperationsPlanTiming.start("WEEKLY",LocalDate.now());
        for(int i=0;i<201;i++) {
            UUID peer=actor("OPERATIONS_MANAGER");
            jdbc.update("""
                    insert into management_work_plan(tenant_id,owner_assignment_id,plan_type,period_start,period_end,operations_plan,due_at,updated_at)
                    values(?,?,'WEEKLY',?,?,true,?,now()+interval '1 hour')
                    """,TENANT,peer,start,start.plusDays(6),OperationsPlanTiming.due("WEEKLY",start,ZoneId.of("Asia/Shanghai")));
        }
        principal(leader,leaderAccount,"OPERATIONS_DIRECTOR");
        @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)plans.list("WEEKLY",start,"REVIEW").get("items");
        assertThat(visible).hasSize(1);assertThat(visible.getFirst().get("id")).isEqualTo(id(saved));
        assertThat((List<?>)visible.getFirst().get("items")).isEmpty();assertThat(visible.getFirst().get("itemCount")).isEqualTo(1);
        assertThat((List<?>)plans.detail(id(saved)).get("items")).hasSize(1);
    }
    @Test void monthlyPrefillSummarizesThePriorMonthWhileWeeklyUsesTheCurrentWeek() {
        LocalDate month=LocalDate.of(2026,11,1);
        var monthly=plans.prefill("MONTHLY",month);
        assertThat(monthly.get("sourcePeriodStart")).isEqualTo(LocalDate.of(2026,10,1));
        assertThat(monthly.get("sourcePeriodEnd")).isEqualTo(LocalDate.of(2026,10,31));
        assertThat(monthly.get("summary").toString()).startsWith("上月简评");
        var weekly=plans.prefill("WEEKLY",LocalDate.of(2026,11,7));
        assertThat(weekly.get("sourcePeriodStart")).isEqualTo(LocalDate.of(2026,11,2));
        assertThat(weekly.get("sourcePeriodEnd")).isEqualTo(LocalDate.of(2026,11,8));
        assertThat(weekly.get("mode")).isEqualTo("RULE_BASED");
    }
    @Test void monthEndPreparesOneEmptyNextMonthDraftWithoutExtraNotification() {
        ZoneId zone=ZoneId.of("Asia/Shanghai");LocalDate end=LocalDate.now(zone).withDayOfMonth(LocalDate.now(zone).lengthOfMonth());
        OffsetDateTime noon=end.atTime(12,0).atZone(zone).toOffsetDateTime();
        reminders.processTenant(TENANT,noon);reminders.processTenant(TENANT,noon.plusMinutes(1));
        @SuppressWarnings("unchecked") var next=(List<Map<String,Object>>)plans.list("MONTHLY",end.plusDays(1),"OWN").get("items");
        assertThat(next).hasSize(1);var draft=plans.detail((UUID)next.getFirst().get("id"));
        assertThat(draft.get("status")).isEqualTo("DRAFT");assertThat(draft.get("summary")).isEqualTo("");
        assertThat((List<?>)draft.get("items")).isEmpty();assertThat(draft.get("submittedAt")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notification where tenant_id=? and source_type='WORK_PLAN' and source_id=?",Integer.class,TENANT,draft.get("id"))).isZero();
    }
    private OperationsPlanModels.Item item(UUID hotel,UUID assignee){return new OperationsPlanModels.Item(UUID.randomUUID(),hotel,assignee,"核对平台房型信息","实际检查",OffsetDateTime.now().plusDays(3).withSecond(0).withNano(0),"提交检查前后截图和页面链接",null,null);}
    private OperationsPlanModels.Draft draft(Map<String,Object> previous,List<OperationsPlanModels.Item> items){return new OperationsPlanModels.Draft("本周期工作总结","下周期执行目标","待核对经营结算",Map.of(hotels.getFirst().toString(),"门店检查"),items,previous==null?null:(UUID)previous.get("revisionId"),previous==null?null:((Number)previous.get("draftVersion")).longValue());}
    private Map<String,Object> save(String type,Map<String,Object> previous,List<OperationsPlanModels.Item> items){return plans.save(type,LocalDate.now(),draft(previous,items));}
    private OperationsPlanModels.Review approve(Map<String,Object> saved){return new OperationsPlanModels.Review("APPROVE","核对无误",(UUID)saved.get("revisionId"));}
    private UUID id(Map<String,Object> value){return (UUID)value.get("id");}
    private int countTasks(UUID owner){return jdbc.queryForObject("select count(*) from operations_plan_action where tenant_id=? and owner_assignment_id=?",Integer.class,TENANT,owner);}
    private UUID account(UUID assignment){return jdbc.queryForObject("select e.account_id from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id where a.tenant_id=? and a.id=?",UUID.class,TENANT,assignment);}
    private UUID actor(String position){UUID a=UUID.randomUUID(),e=UUID.randomUUID(),assignment=UUID.randomUUID();String key="ops-"+a;
        jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,?,?)",a,TENANT,key,key);
        jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?,?,?,?,?,current_date)",e,TENANT,a,key,key);
        jdbc.update("insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from) select ?,?,?,?,id,current_date from position_definition where tenant_id=? and code=?",assignment,TENANT,e,GROUP,TENANT,position);
        for(UUID h:hotels)jdbc.update("insert into employee_assignment_hotel_scope(tenant_id,assignment_id,hotel_org_unit_id) values(?,?,?)",TENANT,assignment,h);return assignment;}
    private void principal(UUID assignment,UUID actor,String role){TenantContext.set(new TenantPrincipal(TENANT,actor,role,Set.of(role),Set.of("*"),Set.copyOf(hotels),Set.of(assignment),assignment,false,UUID.randomUUID()));}
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
