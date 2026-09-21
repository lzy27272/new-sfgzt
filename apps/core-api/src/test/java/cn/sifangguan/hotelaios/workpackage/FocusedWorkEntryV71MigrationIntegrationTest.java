package cn.sifangguan.hotelaios.workpackage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class FocusedWorkEntryV71MigrationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID HOTEL = UUID.fromString("12000000-0000-0000-0000-000000000003");

    @Test void upgradesPopulatedV70WithoutRewritingSubmittedRecordsOrPublishedContracts() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            DataSource source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("70").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID version = jdbc.queryForObject("""
                    select v.id from work_package_version v
                    join work_package_definition d on d.tenant_id=v.tenant_id and d.id=v.work_package_definition_id
                    where d.code='WP-PILOT-HK-ATTENDANT-DAILY' and v.lifecycle_status='PUBLISHED'
                    order by v.version_no desc limit 1
                    """,UUID.class);
            UUID account=UUID.randomUUID(), employee=UUID.randomUUID(), assignment=UUID.randomUUID(), allocation=UUID.randomUUID();
            jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values (?,?,'v71-worker','升级验证员工')",account,TENANT);
            jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values (?,?,?,'v71-worker','升级验证员工',current_date-10)",employee,TENANT,account);
            jdbc.update("""
                    insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from)
                    select ?,?,?,?,id,current_date-10 from position_definition where tenant_id=? and code='HOUSEKEEPING_ATTENDANT'
                    """,assignment,TENANT,employee,HOTEL,TENANT);
            jdbc.update("""
                    insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,valid_from)
                    values (?,?,?,?,?,current_date-5)
                    """,allocation,TENANT,version,assignment,HOTEL);
            UUID cart=expectation(jdbc,version,allocation,assignment,"HKA_CART_PREPARATION",0,"IN_PROGRESS");
            UUID lost=expectation(jdbc,version,allocation,assignment,"HKA_LOST_PROPERTY",0,"SUBMITTED");
            UUID historical=expectation(jdbc,version,allocation,assignment,"HKA_CART_PREPARATION",-1,"SUBMITTED");
            UUID draft=record(jdbc,cart,"DRAFT",1), prior=record(jdbc,lost,"SUBMITTED",1);
            UUID lostDraft=record(jdbc,lost,"DRAFT",2), past=record(jdbc,historical,"SUBMITTED",1);
            UUID evidence=UUID.randomUUID();
            jdbc.update("""
                    insert into attachment(id,tenant_id,work_record_id,object_key,original_name,media_type,size_bytes)
                    values (?,?,?,'v71/history.jpg','原始证据.jpg','image/jpeg',1024)
                    """,evidence,TENANT,prior);
            String priorSnapshot=snapshot(jdbc,"work_record",prior), pastSnapshot=snapshot(jdbc,"work_record",past);
            String historySnapshot=snapshot(jdbc,"work_expectation",historical), evidenceSnapshot=snapshot(jdbc,"attachment",evidence);
            String versionSnapshot=snapshot(jdbc,"work_package_version",version);
            var oldItems=jdbc.queryForList("select id,to_jsonb(i)::text as snapshot from work_package_item i where work_package_version_id=?",version);
            String reports=jdbc.queryForObject("select md5(coalesce(jsonb_agg(to_jsonb(v) order by id)::text,'')) from daily_report_template_version v",String.class);

            jdbc.execute("CREATE ROLE focused_v71_owner LOGIN NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO focused_v71_owner");
            jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO focused_v71_owner");
            jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO focused_v71_owner");
            DataSource owner=postgres.getDatabase("focused_v71_owner","postgres");
            assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from work_package_item",Integer.class)).isZero();
            Flyway.configure().dataSource(owner).locations("classpath:db/migration").target("71").load().migrate();

            assertThat(snapshot(jdbc,"work_record",prior)).isEqualTo(priorSnapshot);
            assertThat(snapshot(jdbc,"work_record",past)).isEqualTo(pastSnapshot);
            assertThat(snapshot(jdbc,"work_expectation",historical)).isEqualTo(historySnapshot);
            assertThat(snapshot(jdbc,"attachment",evidence)).isEqualTo(evidenceSnapshot);
            assertThat(snapshot(jdbc,"work_package_version",version)).isEqualTo(versionSnapshot);
            oldItems.forEach(row -> assertThat(snapshot(jdbc,"work_package_item",(UUID)row.get("id"))).isEqualTo(row.get("snapshot")));
            assertThat(jdbc.queryForObject("select md5(coalesce(jsonb_agg(to_jsonb(v) order by id)::text,'')) from daily_report_template_version v",String.class)).isEqualTo(reports);
            assertThat(jdbc.queryForObject("""
                    select json_schema->'properties' from work_record r
                    join form_version f on f.tenant_id=r.tenant_id and f.id=r.form_version_id where r.id=?
                    """,String.class,draft)).contains("notes").doesNotContain("issueFound","workSummary","checkoutRooms","lostPropertyOccurred");
            assertThat(jdbc.queryForObject("select payload->>'workSummary' from work_record where id=?",String.class,draft)).isEqualTo("旧草稿内容");
            assertThat(jdbc.queryForObject("select repeatable_entry and record_kind='EVENT' from work_record where id=?",Boolean.class,lostDraft)).isTrue();
            assertThat(jdbc.queryForObject("""
                    select not i.required and i.execution_policy->>'registrationMode'='WHEN_OCCURRED'
                      and (i.execution_policy->>'repeatableDailyRecords')::boolean
                      and (i.report_policy->>'aggregateAllRecords')::boolean
                    from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                    where x.id=?
                    """,Boolean.class,lost)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where position_assignment_id=? and status='ACTIVE'",Integer.class,assignment)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select valid_to=current_date from work_package_allocation where id=?",Boolean.class,allocation)).isTrue();
            assertThat(jdbc.queryForObject("select valid_from=current_date+1 from work_package_allocation where position_assignment_id=? and id<>?",Boolean.class,assignment,allocation)).isTrue();
            assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from work_package_item",Integer.class)).isZero();
        }
    }

    private UUID expectation(JdbcTemplate jdbc,UUID version,UUID allocation,UUID assignment,String code,int days,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                  position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status)
                select ?,tenant_id,id,?,?,?,current_date+?,?::text,now()-interval '2 hours',now()-interval '1 hour',?
                from work_package_item where work_package_version_id=? and item_code=?
                """,id,allocation,assignment,HOTEL,days,id.toString(),status,version,code);
        return id;
    }

    private UUID record(JdbcTemplate jdbc,UUID expectation,String status,int attempt) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
                  business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,
                  work_expectation_id,record_kind,target_org_unit_id,occurred_at,attempt_no,content_hash)
                select ?,x.tenant_id,a.org_unit_id,a.employee_id,a.id,i.form_version_id,x.business_date,?,
                  '{"workSummary":"旧草稿内容","issueFound":false,"checkoutRooms":3}'::jsonb,
                  case when ?='SUBMITTED' then now() else null end,i.work_package_version_id,i.id,x.id,
                  'SCHEDULED',x.target_org_unit_id,now(),?,repeat('a',64)
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id where x.id=?
                """,id,status,status,attempt,expectation);
        return id;
    }

    private String snapshot(JdbcTemplate jdbc,String table,UUID id) {
        return jdbc.queryForObject("select to_jsonb(row)::text from "+table+" row where id=?",String.class,id);
    }
}
