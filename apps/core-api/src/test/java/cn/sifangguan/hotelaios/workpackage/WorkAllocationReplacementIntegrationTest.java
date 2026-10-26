package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkAllocationReplacementIntegrationTest {
    private static final String TENANT="10000000-0000-0000-0000-000000000001";
    private static final String CEO="19000000-0000-0000-0000-000000000001";
    private static final String ADMIN=PlatformAdminTestFixture.ACCOUNT_ID;
    private static final String FRONT_ACCOUNT="19000000-0000-0000-0000-000000000003";
    private static final String ASSIGNMENT="19200000-0000-0000-0000-000000000002";
    private static final String POSITION="14000000-0000-0000-0000-000000000001";
    private static final String HOTEL="12000000-0000-0000-0000-000000000003";
    private static final EmbeddedPostgres POSTGRES=startPostgres();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->POSTGRES.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres"); registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.user",()->"postgres"); registry.add("spring.flyway.password",()->"postgres");
        registry.add("app.security.development-header-auth-enabled",()->true); registry.add("app.database.rls-enabled",()->true);
    }
    @BeforeEach void administrator(){PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);}
    @AfterAll static void close() throws Exception {POSTGRES.close();}

    @Test void futureReplacementPreservesFactsAndExactRetriesReturnTheSameAllocation() throws Exception {
        Fixture f=fixture();
        ObjectNode generate=mapper.createObjectNode().put("positionAssignmentId",ASSIGNMENT).put("targetOrgUnitId",HOTEL)
                .put("businessDate",f.today().minusDays(1).toString()).put("periodType","DAY");
        request(identity(post("/api/v1/work-expectations/actions/generate"),ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(generate.toString()),201);
        assertThat(jdbc.queryForObject("select count(*) from work_expectation where work_package_version_id=?",Integer.class,f.oldVersion())).isOne();
        Map<String,String> facts=frozenFacts();
        JsonNode first=replace(f,f.body(),ADMIN,200);
        JsonNode again=replace(f,f.body(),ADMIN,200);
        assertThat(first.path("id").asText()).isEqualTo(again.path("id").asText());
        assertThat(first.path("replayed").asBoolean()).isFalse(); assertThat(again.path("replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select valid_to::text from work_package_allocation where id=?",String.class,f.oldAllocation()))
                .isEqualTo(f.date().minusDays(1).toString());
        assertThat(jdbc.queryForObject("select valid_from::text from work_package_allocation where id=?",String.class,f.oldAllocation()))
                .isEqualTo(f.today().minusDays(30).toString());
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,f.newVersion())).isOne();
        assertThat(frozenFacts()).isEqualTo(facts);
    }

    @Test void wrongPackageTargetAssignmentAndCurrentDayCannotReplaceAnAllocation() throws Exception {
        Fixture f=fixture(); String original=allocation(f.oldAllocation());
        request(identity(post("/api/v1/work-packages/"+UUID.randomUUID()+"/allocations/"+f.oldAllocation()+"/replace"),ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(f.body().toString()),400);
        ObjectNode target=f.body().put("targetOrgUnitId","12000000-0000-0000-0000-000000000001"); replace(f,target,ADMIN,400);
        ObjectNode assignment=f.body().put("positionAssignmentId","19200000-0000-0000-0000-000000000001");replace(f,assignment,ADMIN,400);
        replace(f,f.body().put("validFrom",f.today().toString()),ADMIN,400);
        replace(f,f.body().put("workPackageVersionId",f.oldVersion().toString()),ADMIN,400);
        replace(f,f.body(),FRONT_ACCOUNT,403);
        assertThat(allocation(f.oldAllocation())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,f.newVersion())).isZero();
    }

    @Test void anotherOverlappingAllocationRollsBackTheProposedClosure() throws Exception {
        Fixture f=fixture();
        jdbc.update("""
                insert into work_package_allocation(tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,valid_from,allocation_source)
                values(?::uuid,?,?::uuid,?::uuid,?,'MANUAL')
                """,TENANT,f.oldVersion(),ASSIGNMENT,HOTEL,f.today().minusDays(29));
        String original=allocation(f.oldAllocation());
        replace(f,f.body(),ADMIN,400);
        assertThat(allocation(f.oldAllocation())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,f.newVersion())).isZero();
    }

    @Test void concurrentExactReplacementRequestsCreateOneNewAllocation() throws Exception {
        Fixture f=fixture();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var action=(java.util.concurrent.Callable<JsonNode>)()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return replace(f,f.body(),ADMIN,200);};
            var first=pool.submit(action);var second=pool.submit(action);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            JsonNode a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);
            assertThat(a.path("id").asText()).isEqualTo(b.path("id").asText());
            assertThat(a.path("replayed").asBoolean()).isNotEqualTo(b.path("replayed").asBoolean());
        }
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,f.newVersion())).isOne();
    }

    @Test void replacementCannotLeaveAGapAfterTheOriginalVersionExpires() throws Exception {
        Fixture f=fixture(true);String original=allocation(f.oldAllocation());
        JsonNode rejected=replace(f,f.body().put("validFrom",f.date().plusDays(1).toString()),ADMIN,400);
        assertThat(rejected.toString()).contains("原版本有效期无法覆盖");
        assertThat(allocation(f.oldAllocation())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from work_package_allocation where work_package_version_id=?",Integer.class,f.newVersion())).isZero();
        replace(f,f.body(),ADMIN,200);
    }

    private Fixture fixture() {return fixture(false);}
    private Fixture fixture(boolean finiteOriginalVersion) {
        ZoneId zone=ZoneId.of(jdbc.queryForObject("select timezone from tenant where id=?::uuid",String.class,TENANT));
        LocalDate today=LocalDate.now(zone),date=today.plusDays(7);
        UUID definition=UUID.randomUUID(),oldVersion=UUID.randomUUID(),newVersion=UUID.randomUUID(),allocation=UUID.randomUUID();
        jdbc.update("insert into work_package_definition(id,tenant_id,code,name,position_id,created_by) values(?,?::uuid,?,'切换测试',?::uuid,?::uuid)",
                definition,TENANT,"WP-SWITCH-"+definition.toString().substring(0,8),POSITION,ADMIN);
        int number=0;
        for(UUID version:new UUID[]{oldVersion,newVersion}) {
            jdbc.update("insert into work_package_version(id,tenant_id,work_package_definition_id,version_no,title,created_by) values(?,?::uuid,?,?,'切换版本',?::uuid)",version,TENANT,definition,++number,ADMIN);
            jdbc.update("insert into work_package_scope(tenant_id,work_package_version_id,scope_type) values(?::uuid,?,'TENANT')",TENANT,version);
            jdbc.update("""
                    insert into work_package_item select (jsonb_populate_record(null::work_package_item,to_jsonb(source)||
                      jsonb_build_object('id',cast(? as text),'work_package_version_id',cast(? as text),
                        'item_code','REPLACEMENT_CHECK','period_type','DAY','execution_policy','{}'::jsonb,'applicability_policy','{}'::jsonb))).*
                    from work_package_item source where source.id='47050000-0000-0000-0000-000000000002'::uuid
                    """,UUID.randomUUID().toString(),version.toString());
            jdbc.update("""
                    update work_package_version set lifecycle_status='PUBLISHED',effective_from=?,effective_to=?,
                      content_hash=repeat('a',64),published_by=?::uuid,published_at=now() where id=?
                    """,(version.equals(oldVersion)?today.minusDays(31):date).atStartOfDay(zone).toOffsetDateTime(),
                    finiteOriginalVersion&&version.equals(oldVersion)?date.atStartOfDay(zone).toOffsetDateTime().minusNanos(1000):null,ADMIN,version);
        }
        jdbc.update("""
                insert into work_package_allocation(id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,valid_from,allocation_source)
                values(?,?::uuid,?,?::uuid,?::uuid,?,'MANUAL')
                """,allocation,TENANT,oldVersion,ASSIGNMENT,HOTEL,today.minusDays(30));
        return new Fixture(definition,oldVersion,newVersion,allocation,today,date);
    }
    private record Fixture(UUID definition,UUID oldVersion,UUID newVersion,UUID oldAllocation,LocalDate today,LocalDate date) {
        ObjectNode body(){return new ObjectMapper().createObjectNode().put("workPackageVersionId",newVersion.toString())
                .put("positionAssignmentId",ASSIGNMENT).put("targetOrgUnitId",HOTEL).put("validFrom",date.toString());}
    }
    private JsonNode replace(Fixture f,ObjectNode body,String actor,int status) throws Exception {
        return request(identity(post("/api/v1/work-packages/"+f.definition()+"/allocations/"+f.oldAllocation()+"/replace"),actor)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()),status);
    }
    private JsonNode request(MockHttpServletRequestBuilder request,int expected) throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request,String account) {
        return request.header("X-Tenant-Id",TENANT).header("X-Actor-Id",account);
    }
    private String allocation(UUID id){return jdbc.queryForObject("select to_jsonb(a)::text from work_package_allocation a where id=?",String.class,id);}
    private Map<String,String> frozenFacts(){
        var result=new LinkedHashMap<String,String>();
        for(String table:new String[]{"work_expectation","work_record","daily_report","daily_report_revision"})
            result.put(table,jdbc.queryForObject("select coalesce(string_agg(to_jsonb(facts)::text,'|' order by id),'') from "+table+" facts",String.class));
        return result;
    }
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().start();}catch(Exception error){throw new ExceptionInInitializerError(error);}}
}
