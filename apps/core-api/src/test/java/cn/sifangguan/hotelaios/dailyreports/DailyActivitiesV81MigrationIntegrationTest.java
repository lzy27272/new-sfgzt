package cn.sifangguan.hotelaios.dailyreports;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DailyActivitiesV81MigrationIntegrationTest {
    private static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID HOTEL=UUID.fromString("12000000-0000-0000-0000-000000000003");

    @Test void mergePreservesHistoryDraftsOtherEventsAndCustomProfilesWithRlsMigrationOwner() throws Exception {
        try(var postgres=EmbeddedPostgres.builder().start()) {
            var source=postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("80").load().migrate();
            var jdbc=new JdbcTemplate(source);
            UUID version=jdbc.queryForObject("""
                select v.id from work_package_version v join work_package_definition d
                  on d.tenant_id=v.tenant_id and d.id=v.work_package_definition_id
                where d.code='WP-PILOT-HK-ATTENDANT-DAILY' and v.lifecycle_status='PUBLISHED'
                order by v.version_no desc limit 1
                """,UUID.class);
            UUID account=UUID.randomUUID(),employee=UUID.randomUUID(),assignment=UUID.randomUUID(),allocation=UUID.randomUUID();
            jdbc.update("insert into user_account(id,tenant_id,login_name,display_name) values(?,?,'v81-worker','日报告知升级测试')",account,TENANT);
            jdbc.update("insert into employee(id,tenant_id,account_id,employee_no,name,hired_on) values(?,?,?,'v81-worker','日报告知升级测试',current_date-10)",employee,TENANT,account);
            jdbc.update("""
                insert into employee_position_assignment(id,tenant_id,employee_id,org_unit_id,position_id,valid_from)
                select ?,?,?,?,id,current_date-10 from position_definition where tenant_id=? and code='HOUSEKEEPING_ATTENDANT'
                """,assignment,TENANT,employee,HOTEL,TENANT);
            jdbc.update("""
                insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,valid_from)
                values(?,?,?,?,?,current_date-5)
                """,allocation,TENANT,version,assignment,HOTEL);
            UUID today=expectation(jdbc,version,allocation,assignment,"HKA_LOST_PROPERTY",0,"AVAILABLE");
            UUID future=expectation(jdbc,version,allocation,assignment,"HKA_GUEST_SERVICE_EVENT",1,"PLANNED");
            UUID past=expectation(jdbc,version,allocation,assignment,"HKA_LOST_PROPERTY",-1,"MISSED");
            UUID withDraft=expectation(jdbc,version,allocation,assignment,"HKA_GUEST_SERVICE_EVENT",0,"IN_PROGRESS");
            UUID complaint=expectation(jdbc,version,allocation,assignment,"HKA_COMPLAINT_EVENT",0,"AVAILABLE");
            UUID submitted=expectation(jdbc,version,allocation,assignment,"HKA_LOST_PROPERTY",0,"SUBMITTED");
            record(jdbc,withDraft,"DRAFT");record(jdbc,submitted,"SUBMITTED");
            String records=fingerprint(jdbc,"work_record");
            String items=fingerprint(jdbc,"work_package_item");
            String templates=fingerprint(jdbc,"daily_report_template_version");
            var unchanged=new HashMap<UUID,String>();
            for(UUID id:List.of(past,withDraft,complaint,submitted))unchanged.put(id,snapshot(jdbc,"work_expectation",id));
            var publishedFrontDesk=jdbc.queryForList("""
                select v.id from position_function_profile f
                join app_role r on r.tenant_id=f.tenant_id and r.id=f.default_role_id
                join position_function_profile_version v on v.tenant_id=f.tenant_id and v.profile_id=f.id
                where f.scope_type='GROUP' and r.code='FRONT_DESK' and r.role_type='SYSTEM' and v.lifecycle_status='PUBLISHED'
                """,UUID.class);
            assertThat(publishedFrontDesk).isNotEmpty();
            // Add an administrator's unpublished draft; migration must not overwrite it.
            var drafts=jdbc.queryForList("select id from position_function_profile_version where lifecycle_status='DRAFT' and profile_id=(select profile_id from position_function_profile_version where id=?)",UUID.class,publishedFrontDesk.getFirst());
            UUID draft=drafts.isEmpty()?UUID.randomUUID():drafts.getFirst();
            if(drafts.isEmpty())jdbc.update("""
                insert into position_function_profile_version
                select (jsonb_populate_record(null::position_function_profile_version,to_jsonb(v)||jsonb_build_object(
                  'id',?::uuid,'version_no',(select max(version_no)+1 from position_function_profile_version where profile_id=v.profile_id),
                  'lifecycle_status','DRAFT','published_at',null,'published_by',null))).*
                from position_function_profile_version v where v.id=?
                """,draft,publishedFrontDesk.getFirst());
            String draftSnapshot=snapshot(jdbc,"position_function_profile_version",draft);
            jdbc.execute("create role custody_migration_owner nologin nosuperuser nobypassrls");
            jdbc.execute("create role hotel_ai_os_app nologin nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to custody_migration_owner");
            jdbc.execute("grant all on all tables in schema public to custody_migration_owner");
            jdbc.execute("grant usage,select on all sequences in schema public to custody_migration_owner");
            jdbc.execute("alter table tenant owner to custody_migration_owner");
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("81")
                    .initSql("set role custody_migration_owner").load().migrate();
            for(UUID id:List.of(today,future))
                assertThat(jdbc.queryForObject("select status from work_expectation where id=?",String.class,id)).isEqualTo("CANCELLED");
            unchanged.forEach((id,value)->assertThat(snapshot(jdbc,"work_expectation",id)).isEqualTo(value));
            assertThat(fingerprint(jdbc,"work_record")).isEqualTo(records);
            assertThat(fingerprint(jdbc,"work_package_item")).isEqualTo(items);
            assertThat(fingerprint(jdbc,"daily_report_template_version")).isEqualTo(templates);
            assertThat(snapshot(jdbc,"position_function_profile_version",draft)).isEqualTo(draftSnapshot);
            for(UUID id:publishedFrontDesk)
                assertThat(jdbc.queryForObject("select lifecycle_status from position_function_profile_version where id=?",String.class,id)).isEqualTo("ARCHIVED");
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SYSTEM_DAILY_ACTIVITY_ENTRY_MERGED'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from lost_property_case",Integer.class)).isZero();
            for(String table:List.of("lost_property_case","lost_property_event","daily_activity_photo","tenant"))
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','daily_activity_photo','UPDATE')",Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("select has_table_privilege('hotel_ai_os_app','lost_property_case','UPDATE')",Boolean.class)).isTrue();
        }
    }
    private UUID expectation(JdbcTemplate jdbc,UUID version,UUID allocation,UUID assignment,String code,int days,String state) {
        UUID id=UUID.randomUUID();
        int inserted=jdbc.update("""
            insert into work_expectation(id,tenant_id,work_package_item_id,work_package_allocation_id,position_assignment_id,
              target_org_unit_id,business_date,period_key,available_at,due_at,status)
            select ?,tenant_id,id,?,?,?,(now() at time zone 'Asia/Shanghai')::date+?,?::text,now()-interval '2 hours',now()-interval '1 hour',?
            from work_package_item where work_package_version_id=? and item_code=?
            """,id,allocation,assignment,HOTEL,days,id.toString(),state,version,code);
        assertThat(inserted).isEqualTo(1);return id;
    }
    private void record(JdbcTemplate jdbc,UUID expectation,String state) {
        jdbc.update("""
            insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,business_date,
              status,payload,submitted_at,work_package_version_id,work_package_item_id,work_expectation_id,record_kind,
              target_org_unit_id,occurred_at,attempt_no,content_hash)
            select ?,x.tenant_id,a.org_unit_id,a.employee_id,a.id,i.form_version_id,x.business_date,?,
              '{"notes":"保留旧内容"}'::jsonb,case when ?='SUBMITTED' then now() else null end,i.work_package_version_id,i.id,x.id,
              'EVENT',x.target_org_unit_id,now(),1,repeat('a',64)
            from work_expectation x join work_package_item i on i.tenant_id=x.tenant_id and i.id=x.work_package_item_id
            join employee_position_assignment a on a.tenant_id=x.tenant_id and a.id=x.position_assignment_id where x.id=?
            """,UUID.randomUUID(),state,state,expectation);
    }
    private String snapshot(JdbcTemplate jdbc,String table,UUID id) {
        return jdbc.queryForObject("select to_jsonb(t)::text from "+table+" t where id=?",String.class,id);
    }
    private String fingerprint(JdbcTemplate jdbc,String table) {
        return jdbc.queryForObject("select md5(coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]')) from "+table+" t",String.class);
    }
}
