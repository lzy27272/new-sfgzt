package cn.sifangguan.hotelaios.workpackage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class HousekeepingDailyOpenV76MigrationIntegrationTest {
    private static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID HOTEL=UUID.fromString("12000000-0000-0000-0000-000000000003");

    @Test void changesOnlyCurrentAndFutureOpeningAndPreservesHistoricalFacts() throws Exception {
        try (var postgres=EmbeddedPostgres.builder().start()) {
            var source=postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("75").load().migrate();
            var jdbc=new JdbcTemplate(source);
            UUID version=jdbc.queryForObject("""
                    select v.id from work_package_version v join work_package_definition d
                    on d.tenant_id=v.tenant_id and d.id=v.work_package_definition_id
                    where d.code='WP-PILOT-HK-ATTENDANT-DAILY' and v.lifecycle_status='PUBLISHED'
                    order by v.version_no desc limit 1
                    """,UUID.class);
            UUID account=UUID.randomUUID(),employee=UUID.randomUUID(),assignment=UUID.randomUUID(),allocation=UUID.randomUUID();
            jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values (?,?,'v76-worker','开放时间验证')",account,TENANT);
            jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values (?,?,?,'v76-worker','开放时间验证',current_date-10)",employee,TENANT,account);
            jdbc.update("""
                    insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from)
                    select ?,?,?,?,id,current_date-10 from position_definition where tenant_id=? and code='HOUSEKEEPING_ATTENDANT'
                    """,assignment,TENANT,employee,HOTEL,TENANT);
            jdbc.update("insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,valid_from) values (?,?,?,?,?,current_date-5)",allocation,TENANT,version,assignment,HOTEL);
            UUID today=expectation(jdbc,version,allocation,assignment,"HKA_DAILY_REPORT",0,"IN_PROGRESS");
            UUID future=expectation(jdbc,version,allocation,assignment,"HKA_DAILY_REPORT",1,"PLANNED");
            UUID past=expectation(jdbc,version,allocation,assignment,"HKA_DAILY_REPORT",-1,"MISSED");
            UUID submitted=expectation(jdbc,version,allocation,assignment,"HKA_DAILY_REPORT",2,"SUBMITTED");
            UUID other=expectation(jdbc,version,allocation,assignment,"HKA_CART_PREPARATION",0,"AVAILABLE");
            UUID draft=record(jdbc,today,"DRAFT"),submittedRecord=record(jdbc,submitted,"SUBMITTED");
            String draftBefore=snapshot(jdbc,"work_record",draft),submittedBefore=snapshot(jdbc,"work_record",submittedRecord);
            String pastBefore=snapshot(jdbc,"work_expectation",past),submittedWorkBefore=snapshot(jdbc,"work_expectation",submitted);
            String otherBefore=snapshot(jdbc,"work_expectation",other),versionBefore=snapshot(jdbc,"work_package_version",version);
            var originalItems=jdbc.queryForList("select id,to_jsonb(i)::text as snapshot from work_package_item i where work_package_version_id=?",version);
            String otherPolicies=otherPolicies(jdbc);
            var inactivePolicies=jdbc.queryForList("""
                    select policy.id,to_jsonb(policy)::text as snapshot from daily_report_delivery_policy policy
                    join daily_report_template_assignment a on a.tenant_id=policy.tenant_id and a.id=policy.template_assignment_id
                    where a.status<>'ACTIVE' or a.valid_to<current_date
                    """);
            var oldPolicy=jdbc.queryForMap("""
                    select p.* from daily_report_delivery_policy p
                    join daily_report_template_assignment a on a.tenant_id=p.tenant_id and a.id=p.template_assignment_id
                    join daily_report_template_version v on v.tenant_id=a.tenant_id and v.id=a.template_version_id
                    join daily_report_template_definition d on d.tenant_id=v.tenant_id and d.id=v.template_id
                    join position_definition position on position.tenant_id=d.tenant_id and position.id=d.position_id
                    where position.code='HOUSEKEEPING_ATTENDANT' and a.status='ACTIVE' and a.assignment_kind='BASE'
                      and (a.valid_to is null or a.valid_to>=current_date)
                    order by a.priority desc limit 1
                    """);

            jdbc.execute("CREATE ROLE hka_v76_owner LOGIN NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO hka_v76_owner");
            jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO hka_v76_owner");
            jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO hka_v76_owner");
            var owner=postgres.getDatabase("hka_v76_owner","postgres");
            Flyway.configure().dataSource(owner).locations("classpath:db/migration").target("76").load().migrate();

            assertThat(snapshot(jdbc,"work_record",draft)).isEqualTo(draftBefore);
            assertThat(snapshot(jdbc,"work_record",submittedRecord)).isEqualTo(submittedBefore);
            assertThat(snapshot(jdbc,"work_expectation",past)).isEqualTo(pastBefore);
            assertThat(snapshot(jdbc,"work_expectation",submitted)).isEqualTo(submittedWorkBefore);
            assertThat(snapshot(jdbc,"work_expectation",other)).isEqualTo(otherBefore);
            assertThat(snapshot(jdbc,"work_package_version",version)).isEqualTo(versionBefore);
            originalItems.forEach(row -> assertThat(snapshot(jdbc,"work_package_item",(UUID)row.get("id"))).isEqualTo(row.get("snapshot")));
            assertThat(otherPolicies(jdbc)).isEqualTo(otherPolicies);
            inactivePolicies.forEach(row -> assertThat(snapshot(jdbc,"daily_report_delivery_policy",(UUID)row.get("id"))).isEqualTo(row.get("snapshot")));
            for (UUID id:new UUID[]{today,future}) {
                assertThat(jdbc.queryForObject("select (available_at at time zone 'Asia/Shanghai')::time=time '15:30' and (due_at at time zone 'Asia/Shanghai')::time=time '18:00' from work_expectation where id=?",Boolean.class,id)).isTrue();
            }
            var newPolicy=jdbc.queryForMap("select * from daily_report_delivery_policy where id=?",oldPolicy.get("id"));
            assertThat(newPolicy.get("open_local_time").toString()).isEqualTo("15:30:00");
            for(String field:new String[]{"due_local_time","grace_minutes","enabled","backfill_days"})
                assertThat(newPolicy.get(field)).isEqualTo(oldPolicy.get(field));
            assertThat(jdbc.queryForObject("""
                    select i.work_window_start=time '15:30' and i.due_local_time=time '18:00' and a.valid_from=current_date
                    from work_package_allocation a join work_package_item i
                    on i.tenant_id=a.tenant_id and i.work_package_version_id=a.work_package_version_id
                    where a.position_assignment_id=? and a.id<>? and a.status='ACTIVE' and i.item_code='HKA_DAILY_REPORT'
                    """,Boolean.class,assignment,allocation)).isTrue();
            assertThat(jdbc.queryForObject("select valid_to=current_date-1 from work_package_allocation where id=?",Boolean.class,allocation)).isTrue();
            assertThat(jdbc.queryForObject("""
                    select count(*) from work_package_allocation where position_assignment_id=? and status='ACTIVE'
                      and valid_from<=current_date and (valid_to is null or valid_to>=current_date)
                    """,Integer.class,assignment)).isEqualTo(1);
            assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from work_package_item",Integer.class)).isZero();
        }
    }

    private UUID expectation(JdbcTemplate jdbc,UUID version,UUID allocation,UUID assignment,String code,int days,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,
                    position_assignment_id,target_org_unit_id,business_date,period_key,available_at,due_at,status)
                select ?,tenant_id,id,?,?,?,current_date+?,?::text,
                    (current_date+?+time '16:30') at time zone 'Asia/Shanghai',
                    (current_date+?+time '18:00') at time zone 'Asia/Shanghai',?
                from work_package_item where work_package_version_id=? and item_code=?
                """,id,allocation,assignment,HOTEL,days,id.toString(),days,days,status,version,code);
        return id;
    }
    private UUID record(JdbcTemplate jdbc,UUID expectation,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
                    business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,
                    work_expectation_id,record_kind,target_org_unit_id,occurred_at,attempt_no,content_hash)
                select ?,x.tenant_id,a.org_unit_id,a.employee_id,a.id,i.form_version_id,x.business_date,?,
                    '{"checkoutRooms":19,"stayoverRooms":6,"notes":"保留原始内容"}'::jsonb,
                    case when ?='SUBMITTED' then now() else null end,i.work_package_version_id,i.id,x.id,
                    'SCHEDULED',x.target_org_unit_id,now(),1,repeat('a',64)
                from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
                join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id where x.id=?
                """,id,status,status,expectation);
        return id;
    }
    private String snapshot(JdbcTemplate jdbc,String table,UUID id) {
        return jdbc.queryForObject("select to_jsonb(r)::text from "+table+" r where id=?",String.class,id);
    }
    private String otherPolicies(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select md5(coalesce(jsonb_agg(to_jsonb(policy) order by policy.id)::text,''))
                from daily_report_delivery_policy policy
                join daily_report_template_assignment a on a.tenant_id=policy.tenant_id and a.id=policy.template_assignment_id
                join daily_report_template_version v on v.tenant_id=a.tenant_id and v.id=a.template_version_id
                join daily_report_template_definition d on d.tenant_id=v.tenant_id and d.id=v.template_id
                join position_definition p on p.tenant_id=d.tenant_id and p.id=d.position_id
                where p.code<>'HOUSEKEEPING_ATTENDANT'
                """,String.class);
    }
}
