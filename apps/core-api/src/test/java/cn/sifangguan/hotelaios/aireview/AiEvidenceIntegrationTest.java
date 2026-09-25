package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AiEvidenceIntegrationTest {
    static final String TENANT="10000000-0000-0000-0000-000000000001";
    static final String CEO="19000000-0000-0000-0000-000000000001";
    static final String MANAGER="19000000-0000-0000-0000-000000000002";
    static final String FRONT="19000000-0000-0000-0000-000000000003";
    static final String ASSIGNMENT="19200000-0000-0000-0000-000000000001";
    static final String HOTEL="12000000-0000-0000-0000-000000000003";
    static final String OTHER_HOTEL="12000000-0000-0000-0000-000000000004";
    static final String DEPT="12000000-0000-0000-0000-000000000005";
    static final String ITEM="47050000-0000-0000-0000-000000000002";
    static final EmbeddedPostgres PG=postgres();
    static final Path PHOTOS=temp();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AiEvidenceService service;
    @Autowired EffectiveIdentityService identities;
    @MockitoBean AiVisionGateway gateway;
    @MockitoBean AttachmentMalwareScanner scanner;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.user",()->"postgres");r.add("spring.flyway.password",()->"postgres");
        r.add("app.security.development-header-auth-enabled",()->true);
        r.add("app.database.rls-enabled",()->true);
        r.add("app.attachments.root",PHOTOS::toString);
        r.add("app.ai-evidence.enabled",()->false);
        r.add("app.ai-evidence.daily-request-limit",()->2);
        r.add("app.ai-evidence.pilot-hotel-ids",()->HOTEL);
    }
    static EmbeddedPostgres postgres() { try { return EmbeddedPostgres.builder().start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    static Path temp() { try { return Files.createTempDirectory("ai-evidence-test-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @AfterAll static void close() throws Exception { PG.close(); }
    @AfterEach void clear() { TenantContext.clear(); }
    @BeforeEach void prepare() {
        // Fresh, disposable database owned by this test class, never a configured external database.
        for(String table:List.of("ai_evidence_event","ai_evidence_check","ai_evidence_photo","ai_evidence_sample","ai_evidence_rule"))
            jdbc.update("delete from "+table);
        PlatformAdminTestFixture.ensure(jdbc,TENANT,CEO);
        when(gateway.ready()).thenReturn(false);
        when(scanner.scan(any())).thenReturn("CLEAN");
        when(scanner.canDownload(anyString())).thenReturn(true);
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,String actor) {
        return request.header("X-Tenant-Id",TENANT).header("X-Actor-Id",actor);
    }
    JsonNode response(MockHttpServletRequestBuilder request,int status) throws Exception {
        var result=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    String position() { return jdbc.queryForObject("select position_id::text from employee_position_assignment where id=?::uuid",String.class,ASSIGNMENT); }
    String itemCode() { return jdbc.queryForObject("select item_code from work_package_item where id=?::uuid",String.class,ITEM); }
    String config() { return """
        {"hotelIds":["%s"],"itemCodes":["%s"],"criteria":[{"code":"BADGE","enabled":true,"standard":"制服胸前佩戴工牌"}],"sampleIds":[],"exceptions":""}
        """.formatted(HOTEL,itemCode()); }
    JsonNode rule(boolean shadow) throws Exception {
        var existing=response(as(get("/api/v1/ai-evidence/rules").param("positionId",position()),PlatformAdminTestFixture.ACCOUNT_ID),200);
        String expected=existing.isEmpty()?"null":"\""+existing.get(0).path("id").asText()+"\"";
        return response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content("{\"positionId\":\""+position()+"\",\"expectedLatestId\":"+expected+",\"shadow\":"+shadow+",\"configuration\":"+config()+"}"),200);
    }
    UUID record(String org) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
              business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,record_kind,
              target_org_unit_id,occurred_at,submitted_by_account_id,attempt_no,content_hash,completion_statement)
            select ?,a.tenant_id,?::uuid,a.employee_id,a.id,i.form_version_id,current_date,'SUBMITTED','{}'::jsonb,
              now(),i.work_package_version_id,i.id,'INSPECTION',?::uuid,now(),?::uuid,1,repeat('b',64),'AI影子测试'
            from employee_position_assignment a cross join work_package_item i where a.id=?::uuid and i.id=?::uuid
            """,id,org,org,MANAGER,ASSIGNMENT,ITEM);
        return id;
    }
    byte[] photo() throws Exception {
        var bytes=new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(40,60,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }
    String upload(UUID record) throws Exception {
        return response(as(multipart("/api/v1/ai-evidence/records/{id}/photos",record)
            .file(new MockMultipartFile("file","synthetic.png","image/png",photo())).param("captureSource","CAMERA"),MANAGER),200).path("id").asText();
    }
    JsonNode request(UUID record,String photo,String previous,int expected) throws Exception {
        return response(as(post("/api/v1/ai-evidence/records/{id}/checks",record),MANAGER).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subjectAssignmentId\":\""+ASSIGNMENT+"\",\"attachmentIds\":[\""+photo+"\"],\"supersedesId\":"+(previous==null?"null":"\""+previous+"\"")+"}"),expected);
    }
    JsonNode decision(JsonNode check,String actor,String action,String reason,int expected) throws Exception {
        return response(as(post("/api/v1/ai-evidence/checks/{id}/decisions",check.path("id").asText()),actor)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",check.path("row_version").asInt(),"action",action,"reason",reason))),expected);
    }
    void principal() { TenantContext.set(identities.resolve(UUID.fromString(TENANT),UUID.fromString(MANAGER),UUID.randomUUID(),UUID.fromString(ASSIGNMENT))); }

    @Test void fullShadowRetakeAppealReviewFlowPreservesFormalWorkAndEvidence() throws Exception {
        rule(true);UUID id=record(DEPT);String photo=upload(id);
        var before=jdbc.queryForMap("select status,submitted_at,content_hash,row_version from work_record where id=?",id);
        var context=response(as(get("/api/v1/ai-evidence/records/{id}",id),MANAGER),200);
        assertThat(context.path("canSubmit").asBoolean()).isTrue();
        var check=request(id,photo,null,200);
        assertThat(check.path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(check.path("failure_code").asText()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(request(id,photo,null,200).path("id")).isEqualTo(check.path("id"));
        var team=response(as(get("/api/v1/ai-evidence/team").param("hotelId",HOTEL),CEO),200);
        assertThat(team.toString()).contains(id.toString()).doesNotContain("租户外");
        assertThat(response(as(get("/api/v1/ai-evidence/team").param("hotelId",OTHER_HOTEL),CEO),200).toString()).doesNotContain(id.toString());
        decision(check,MANAGER,"PASS","自己审批",403);
        var retake=decision(check,CEO,"RETAKE","请补拍工牌近照",200);
        decision(check,CEO,"PASS","旧版本重放",409);
        var appeal=decision(retake,MANAGER,"APPEAL","反光造成工牌不清晰",200);
        var overturned=decision(appeal,CEO,"OVERTURNED","现场人工核对符合规范",200);
        assertThat(overturned.path("human_outcome").asText()).isEqualTo("OVERTURNED");
        // Formal work may already be locked; shadow supplements must still work without reopening it.
        jdbc.update("update work_record set status='APPROVED' where id=?",id);
        String replacement=upload(id);
        var next=request(id,replacement,check.path("id").asText(),200);
        assertThat(next.path("supersedes_id")).isEqualTo(check.path("id"));
        decision(overturned,CEO,"PASS","旧证据不能继续操作",409);
        var history=response(as(get("/api/v1/ai-evidence/checks/{id}/history",check.path("id").asText()),CEO),200);
        assertThat(history.toString()).contains("RETAKE","APPEAL","OVERTURNED");
        var after=jdbc.queryForMap("select status,submitted_at,content_hash,row_version from work_record where id=?",id);
        assertThat(after.get("status")).isEqualTo("APPROVED");
        assertThat(after.get("submitted_at")).isEqualTo(before.get("submitted_at"));
        assertThat(after.get("content_hash")).isEqualTo(before.get("content_hash"));
        assertThat(after.get("row_version")).isEqualTo(before.get("row_version"));
        assertThat(jdbc.queryForObject("select count(*) from attachment where work_record_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from image_retention_object where source_id=?::uuid",Integer.class,replacement)).isEqualTo(1);
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void permissionsIsolationAndEvidenceOwnershipAreEnforced() throws Exception {
        assertThat(response(as(get("/api/v1/ai-evidence/options"),PlatformAdminTestFixture.ACCOUNT_ID),200).path("hotels").isArray()).isTrue();
        response(as(get("/api/v1/ai-evidence/options"),FRONT),403);
        response(as(get("/api/v1/ai-evidence/options"),CEO),403);
        rule(true);UUID id=record(HOTEL);String photo=upload(id);
        response(as(get("/api/v1/ai-evidence/records/{id}",id),FRONT),403);
        mvc.perform(as(get("/api/v1/ai-evidence/photos/{id}/content",photo),FRONT)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/ai-evidence/photos/{id}/content",photo),CEO)).andExpect(status().isOk());
        request(record(HOTEL),photo,null,404);
        request(record(OTHER_HOTEL),photo,null,403);
        when(scanner.scan(any())).thenReturn("INFECTED");
        response(as(multipart("/api/v1/ai-evidence/records/{id}/photos",id).file(new MockMultipartFile("file","bad.png","image/png",photo())),MANAGER),400);
    }
    @Test void versionPublicationUsesCompareAndSwapAndKeepsHistory() throws Exception {
        var first=rule(true);var draft=rule(false);
        assertThat(draft.path("version_no").asInt()).isEqualTo(first.path("version_no").asInt()+1);
        assertThat(jdbc.queryForObject("select state from ai_evidence_rule where id=?::uuid",String.class,first.path("id").asText())).isEqualTo("SHADOW");
        response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content("{\"positionId\":\""+position()+"\",\"expectedLatestId\":null,\"shadow\":true,\"configuration\":"+config()+"}"),409);
        var published=rule(true);
        assertThat(jdbc.queryForObject("select state from ai_evidence_rule where id=?::uuid",String.class,first.path("id").asText())).isEqualTo("RETIRED");
        assertThat(published.path("configuration")).isEqualTo(first.path("configuration"));
    }
    @Test void serverPilotAllowlistCannotBeExpandedByAnAdministratorRule() throws Exception {
        var options=response(as(get("/api/v1/ai-evidence/options"),PlatformAdminTestFixture.ACCOUNT_ID),200);
        assertThat(options.path("hotels").size()).isEqualTo(1);
        assertThat(options.path("hotels").get(0).path("id").asText()).isEqualTo(HOTEL);
        response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content("{\"positionId\":\""+position()+"\",\"shadow\":true,\"configuration\":"+config().replace(HOTEL,OTHER_HOTEL)+"}"),403);
        assertThat(jdbc.queryForObject("select count(*) from ai_evidence_rule",Integer.class)).isZero();
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void supplementsAreOneChainAndSampleContentIsAdminOnly() throws Exception {
        rule(true);UUID id=record(HOTEL);String photo=upload(id);var check=request(id,photo,null,200);
        request(id,photo,check.path("id").asText(),409);
        String nextPhoto=upload(id);request(id,nextPhoto,null,409);
        var next=request(id,nextPhoto,check.path("id").asText(),200);
        assertThat(request(id,nextPhoto,check.path("id").asText(),200).path("id")).isEqualTo(next.path("id"));
        request(id,upload(id),check.path("id").asText(),409);
        var sample=response(as(multipart("/api/v1/ai-evidence/samples")
            .file(new MockMultipartFile("file","sample.png","image/png",photo()))
            .param("positionId",position()).param("kind","PASS"),PlatformAdminTestFixture.ACCOUNT_ID),200);
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",sample.path("id").asText()),MANAGER)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",sample.path("id").asText()),PlatformAdminTestFixture.ACCOUNT_ID)).andExpect(status().isOk());
    }
    @Test void workerStoresValidatedSuggestionsDeduplicatesClaimsAndCapsCalls() throws Exception {
        rule(true);when(gateway.ready()).thenReturn(true);
        UUID id=record(HOTEL);String photo=upload(id);var check=request(id,photo,null,200);
        principal();var job=service.claim(UUID.fromString(check.path("id").asText()));
        assertThat(service.claim(UUID.fromString(check.path("id").asText()))).isEmpty();
        assertThat(service.images(job)).hasSize(1);
        service.finish(job,new AiVisionGateway.Observation(json.readTree("{\"personCount\":1,\"checks\":[]}"),950,90,"test-double"),null);
        service.finish(job,null,"duplicate-finish");
        var finished=service.checks(id).getFirst();
        assertThat(finished.get("state")).isEqualTo("COMPLETE");
        assertThat(((JsonNode)finished.get("result")).path("overall").asText()).isEqualTo("UNKNOWN");
        assertThat(finished.get("input_tokens")).isEqualTo(950);
        var next=request(id,upload(id),check.path("id").asText(),200);
        principal();var nextJob=service.claim(UUID.fromString(next.path("id").asText()));
        service.finish(nextJob,null,"PROVIDER_OR_EVIDENCE_UNAVAILABLE");
        var third=request(id,upload(id),next.path("id").asText(),200);
        principal();assertThat(service.claim(UUID.fromString(third.path("id").asText()))).isEmpty();
        assertThat(service.checks(id).getFirst().get("failure_code")).isEqualTo("DAILY_LIMIT");
        assertThat(jdbc.queryForObject("select status from work_record where id=?",String.class,id)).isEqualTo("SUBMITTED");
    }
    @Test void expiredEvidenceIsNotSentAndWorkerLeaseRecovers() throws Exception {
        rule(true);when(gateway.ready()).thenReturn(true);
        UUID id=record(HOTEL);String photo=upload(id);var check=request(id,photo,null,200);
        principal();var job=service.claim(UUID.fromString(check.path("id").asText()));
        jdbc.update("update image_retention_object set state='DELETED',deleted_at=now() where source_id=?::uuid",photo);
        assertThatThrownBy(()->service.images(job)).isInstanceOf(RuntimeException.class);
        service.finish(job,new AiVisionGateway.Observation(json.readTree("{\"personCount\":1,\"checks\":[]}"),50,10,"test-double"),null);
        assertThat(service.checks(id).getFirst().get("state")).isEqualTo("STALE");
        var next=request(id,upload(id),check.path("id").asText(),200);principal();
        // No provider invocation is made in this test; simulate an interrupted worker lease.
        jdbc.update("update ai_evidence_check set state='RUNNING',lease_until=now()-interval '1 minute' where id=?::uuid",next.path("id").asText());
        service.pending(UUID.fromString(TENANT));
        assertThat(service.checks(id).getFirst().get("failure_code")).isEqualTo("WORKER_INTERRUPTED");
    }
}
