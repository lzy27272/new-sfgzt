package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
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
    static final String ASSISTANT="19000000-0000-0000-0000-000000000008";
    static final String ASSISTANT_ASSIGNMENT="19200000-0000-0000-0000-000000000008";
    static final String ASSISTANT_POSITION="14000000-0000-0000-0000-000000000007";
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
    @Autowired AttachmentService files;
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
        jdbc.update("update data_retention_policy set image_retention_days=5,image_cleanup_enabled=false where tenant_id=?::uuid",TENANT);
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
        return rule(shadow,config());
    }
    JsonNode rule(boolean shadow,String configuration) throws Exception {
        var existing=response(as(get("/api/v1/ai-evidence/rules").param("positionId",position()),PlatformAdminTestFixture.ACCOUNT_ID),200);
        String expected=existing.isEmpty()?"null":"\""+existing.get(0).path("id").asText()+"\"";
        return response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content("{\"positionId\":\""+position()+"\",\"expectedLatestId\":"+expected+",\"shadow\":"+shadow+",\"configuration\":"+configuration+"}"),200);
    }
    UUID record(String org) {
        return record(org,MANAGER,ASSIGNMENT,ITEM);
    }
    UUID record(String org,String actor,String assignment,String item) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into work_record(id,tenant_id,org_unit_id,employee_id,position_assignment_id,form_version_id,
              business_date,status,payload,submitted_at,work_package_version_id,work_package_item_id,record_kind,
              target_org_unit_id,occurred_at,submitted_by_account_id,attempt_no,content_hash,completion_statement)
            select ?,a.tenant_id,?::uuid,a.employee_id,a.id,i.form_version_id,current_date,'SUBMITTED','{}'::jsonb,
              now(),i.work_package_version_id,i.id,'INSPECTION',?::uuid,now(),?::uuid,1,repeat('b',64),'AI影子测试'
            from employee_position_assignment a cross join work_package_item i where a.id=?::uuid and i.id=?::uuid
            """,id,org,org,actor,assignment,item);
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
    String sample() throws Exception {
        return response(as(multipart("/api/v1/ai-evidence/samples")
            .file(new MockMultipartFile("file","sample.png","image/png",photo()))
            .param("positionId",position()).param("kind","PASS"),PlatformAdminTestFixture.ACCOUNT_ID),200).path("id").asText();
    }
    String sampleKey(String id) {
        return jdbc.queryForObject("select object_key from ai_evidence_sample where id=?::uuid",String.class,id);
    }
    void ageImage(String id,int days) {
        jdbc.update("update image_retention_object set uploaded_at=now()-?*interval '24 hours' where source_id=?::uuid",days,id);
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
        assertThat(options.path("hotels").size()).isGreaterThan(1);
        assertThat(options.path("pilotHotels").size()).isEqualTo(1);
        assertThat(options.path("pilotHotels").get(0).path("id").asText()).isEqualTo(HOTEL);
        response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content("{\"positionId\":\""+position()+"\",\"shadow\":true,\"configuration\":"+config().replace(HOTEL,OTHER_HOTEL)+"}"),200);
        assertThat(jdbc.queryForObject("select count(*) from ai_evidence_rule",Integer.class)).isEqualTo(1);
        var id=record(OTHER_HOTEL);
        var context=response(as(get("/api/v1/ai-evidence/records/{id}",id),MANAGER),200);
        assertThat(context.path("pilotEnabled").asBoolean()).isFalse();
        assertThat(context.path("canSubmit").asBoolean()).isFalse();
        request(id,UUID.randomUUID().toString(),null,403);
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void makeupDraftEnableReadbackAndWorkerRemainHumanReviewOnly() throws Exception {
        var configuration=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config());
        var criteria=configuration.putArray("criteria");
        for(String code:List.of("UNIFORM","BADGE","EQUIPMENT","MAKEUP"))
            criteria.addObject().put("code",code).put("enabled",true).put("standard","门店已确认规范："+code);
        var draft=rule(false,configuration.toString());
        assertThat(draft.path("state").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("configuration").path("criteria")).hasSize(4);
        var published=rule(true,configuration.toString());
        assertThat(published.path("state").asText()).isEqualTo("SHADOW");
        var readback=response(as(get("/api/v1/ai-evidence/rules").param("positionId",position()),PlatformAdminTestFixture.ACCOUNT_ID),200);
        assertThat(readback.get(0).path("configuration")).isEqualTo(configuration);
        when(gateway.ready()).thenReturn(true);
        UUID id=record(HOTEL);var before=jdbc.queryForMap("select status,submitted_at,row_version from work_record where id=?",id);
        var check=request(id,upload(id),null,200);principal();
        var job=service.claim(UUID.fromString(check.path("id").asText()));
        service.finish(job,new AiVisionGateway.Observation(json.readTree("{\"personCount\":1,\"checks\":[]}"),120,30,"offline-test"),null);
        var result=(JsonNode)service.checks(id).getFirst().get("result");
        assertThat(result.path("checks").get(3).path("code").asText()).isEqualTo("MAKEUP");
        assertThat(result.path("checks").get(3).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForMap("select status,submitted_at,row_version from work_record where id=?",id)).isEqualTo(before);
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void activeVersionRemainsVisibleEvenAfterFiftyNewDrafts() throws Exception {
        var active=rule(true);
        jdbc.update("""
            insert into ai_evidence_rule(id,tenant_id,position_id,version_no,state,configuration,created_by)
            select gen_random_uuid(),tenant_id,position_id,n,'DRAFT',configuration,created_by
            from ai_evidence_rule cross join generate_series(2,52) n where id=?::uuid
            """,active.path("id").asText());
        var versions=response(as(get("/api/v1/ai-evidence/rules").param("positionId",position()),PlatformAdminTestFixture.ACCOUNT_ID),200);
        assertThat(versions).hasSize(51);
        assertThat(versions.get(0).path("version_no").asInt()).isEqualTo(52);
        assertThat(versions.get(50).path("id")).isEqualTo(active.path("id"));
        assertThat(versions.get(50).path("state").asText()).isEqualTo("SHADOW");
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
    @Test void oldSamplesRemainReadableAndUnlinkingPreservesOriginalAndHistoricalRule() throws Exception {
        String sample=sample();
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config());
        config.putArray("sampleIds").add(sample);
        var historical=rule(true,config.toString());
        // Both pre-existing and newly uploaded samples use their registered source type, not an upload-age bypass.
        ageImage(sample,3650);
        jdbc.update("update data_retention_policy set image_cleanup_enabled=true where tenant_id=?::uuid",TENANT);
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",sample),PlatformAdminTestFixture.ACCOUNT_ID)).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",sample),MANAGER)).andExpect(status().isForbidden());
        assertThat(rule(false,config.toString()).path("configuration").path("sampleIds").get(0).asText()).isEqualTo(sample);
        var unlinked=rule(true);
        assertThat(unlinked.path("configuration").path("sampleIds")).isEmpty();
        assertThat(json.readTree(jdbc.queryForObject("select configuration::text from ai_evidence_rule where id=?::uuid",String.class,
            historical.path("id").asText())).path("sampleIds").get(0).asText()).isEqualTo(sample);
        assertThat(Files.exists(PHOTOS.resolve(sampleKey(sample)))).isTrue();
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",sample),PlatformAdminTestFixture.ACCOUNT_ID)).andExpect(status().isOk());
    }
    @Test void permanentSampleIsUsableByModelButExpiredEmployeeEvidenceIsStillBlocked() throws Exception {
        String sample=sample();ageImage(sample,3650);
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config());config.putArray("sampleIds").add(sample);
        rule(true,config.toString());when(gateway.ready()).thenReturn(true);
        jdbc.update("update data_retention_policy set image_cleanup_enabled=true where tenant_id=?::uuid",TENANT);
        UUID record=record(HOTEL);String photo=upload(record);var check=request(record,photo,null,200);
        principal();var job=service.claim(UUID.fromString(check.path("id").asText()));
        assertThat(service.images(job)).hasSize(2); // Builds in-memory inputs only, never calls the external model.
        ageImage(photo,6);
        mvc.perform(as(get("/api/v1/ai-evidence/photos/{id}/content",photo),MANAGER)).andExpect(status().isGone());
        principal();assertThatThrownBy(()->service.images(job)).isInstanceOf(RuntimeException.class);
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void committedSampleCannotBeDeletedButUncommittedUploadCleanupStillWorks() throws Exception {
        String sample=sample(),key=sampleKey(sample);
        TenantContext.set(identities.resolve(UUID.fromString(TENANT),UUID.fromString(PlatformAdminTestFixture.ACCOUNT_ID),UUID.randomUUID(),null));
        assertThatThrownBy(()->files.removeStoredObject(key)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("规范样例照片长期保留");
        assertThat(Files.exists(PHOTOS.resolve(key))).isTrue();
        String orphanKey=TENANT+"/ai-evidence-samples/"+UUID.randomUUID();
        files.storeObject(orphanKey,new MockMultipartFile("file","uncommitted.png","image/png",photo()));
        files.removeStoredObject(orphanKey);
        assertThat(Files.exists(PHOTOS.resolve(orphanKey))).isFalse();
    }
    @Test void permanentPolicyDoesNotReviveDeletedOrMissingSampleBytes() throws Exception {
        String deleted=sample();
        jdbc.update("update image_retention_object set state='DELETED',deleted_at=now() where source_id=?::uuid",deleted);
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",deleted),PlatformAdminTestFixture.ACCOUNT_ID)).andExpect(status().isGone());
        String missing=sample();Files.delete(PHOTOS.resolve(sampleKey(missing)));
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{id}/content",missing),PlatformAdminTestFixture.ACCOUNT_ID)).andExpect(status().isBadRequest());
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

    void copyAssistantMigration() throws Exception {
        var script=new org.springframework.core.io.ClassPathResource("db/migration/V87__assistant_grooming_participant_rules.sql");
        try(var input=script.getInputStream()) { jdbc.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
    }
    JsonNode assistantRule() throws Exception {
        return response(as(get("/api/v1/ai-evidence/rules").param("positionId",ASSISTANT_POSITION),PlatformAdminTestFixture.ACCOUNT_ID),200).get(0);
    }
    UUID assistantRecord() {
        var item=jdbc.queryForObject("select id::text from work_package_item where tenant_id=?::uuid and item_code='AGM_NS_APPEARANCE_MEETING' limit 1",String.class,TENANT);
        return record(HOTEL,ASSISTANT,ASSISTANT_ASSIGNMENT,item);
    }
    @Test void migrationCopiesExactStandardsAndPermanentSamplesWithoutChangingSourceOrHistory() throws Exception {
        String sample=sample();
        var configuration=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config());
        configuration.put("exceptions","按已批准例外执行，不推断身份");
        configuration.putArray("sampleIds").add(sample);
        configuration.putArray("criteria").addObject().put("code","MAKEUP").put("enabled",true).put("standard","清晰近照；模糊不判违规");
        var source=rule(true,configuration.toString());
        var original=jdbc.queryForMap("select * from ai_evidence_rule where id=?::uuid",source.path("id").asText());
        var history=record(HOTEL);var before=jdbc.queryForMap("select * from work_record where id=?",history);
        copyAssistantMigration();var copied=assistantRule();
        assertThat(copied.path("configuration").path("criteria")).isEqualTo(source.path("configuration").path("criteria"));
        assertThat(copied.path("configuration").path("exceptions")).isEqualTo(configuration.path("exceptions"));
        assertThat(copied.path("configuration").path("sampleIds")).isEqualTo(configuration.path("sampleIds"));
        assertThat(copied.path("configuration").path("sourceRuleId")).isEqualTo(source.path("id"));
        assertThat(copied.path("configuration").path("subjectScope").asText()).isEqualTo("WORK_PARTICIPANTS");
        assertThat(copied.path("configuration").path("itemCodes").toString()).contains("AGM_WS_APPEARANCE_MEETING","AGM_NS_APPEARANCE_MEETING");
        assertThat(copied.path("configuration").path("hotelIds").toString()).contains(HOTEL,OTHER_HOTEL);
        assertThat(jdbc.queryForMap("select * from ai_evidence_rule where id=?::uuid",source.path("id").asText())).isEqualTo(original);
        assertThat(jdbc.queryForMap("select * from work_record where id=?",history)).isEqualTo(before);
        copyAssistantMigration();assertThat(assistantRule().path("id")).isEqualTo(copied.path("id"));
        // Editing a copied version may reuse the linked samples but cannot attach arbitrary other-role samples.
        var saved=response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("positionId",ASSISTANT_POSITION,"expectedLatestId",copied.path("id").asText(),
                "configuration",copied.path("configuration"),"shadow",true))),200);
        assertThat(saved.path("version_no").asInt()).isEqualTo(2);
        assertThat(Files.exists(PHOTOS.resolve(sampleKey(sample)))).isTrue();
        verify(gateway,never()).inspect(any(),any());
    }
    @Test void participantsReadStandardsAndSamplesButNotOtherEmployeePhotosOrAdminEditor() throws Exception {
        String sample=sample(),unlinked=sample();
        var configuration=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config());configuration.putArray("sampleIds").add(sample);
        rule(true,configuration.toString());copyAssistantMigration();var copied=assistantRule();
        ageImage(sample,3650);
        var mine=response(as(get("/api/v1/ai-evidence/my-rules"),FRONT),200);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("id")).isEqualTo(copied.path("id"));
        assertThat(mine.toString()).doesNotContain("object_key","created_by",OTHER_HOTEL);
        mvc.perform(as(get("/api/v1/ai-evidence/rules/{r}/samples/{s}/content",copied.path("id").asText(),sample),FRONT)).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/ai-evidence/rules/{r}/samples/{s}/content",copied.path("id").asText(),unlinked),FRONT)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/ai-evidence/samples/{s}/content",sample),FRONT)).andExpect(status().isForbidden());
        response(as(get("/api/v1/ai-evidence/options"),FRONT),403);
        UUID record=assistantRecord();
        response(as(get("/api/v1/ai-evidence/records/{id}",record),FRONT),403);
        response(as(get("/api/v1/ai-evidence/records/{id}/rules",record),FRONT),403);
        mvc.perform(as(get("/api/v1/ai-evidence/rules/{r}/samples/{s}/content",copied.path("id").asText(),sample).param("recordId",record.toString()),FRONT)).andExpect(status().isForbidden());
    }
    @Test void assistantTaskChecksOtherPositionsWithoutCopyingTheirWorkAndRetakesKeepHistoricalRule() throws Exception {
        rule(true);copyAssistantMigration();var copied=assistantRule();UUID record=assistantRecord();
        var context=response(as(get("/api/v1/ai-evidence/records/{id}",record),ASSISTANT),200);
        assertThat(context.path("participantScope").asBoolean()).isTrue();
        var frontAssignment=jdbc.queryForObject("select a.id::text from employee_position_assignment a join employee e on e.id=a.employee_id where e.account_id=?::uuid limit 1",String.class,FRONT);
        assertThat(context.path("subjects").toString()).contains(frontAssignment);
        for(var subject:context.path("subjects")) assertThat(subject.path("configured").asBoolean()).isTrue();
        var before=jdbc.queryForMap("select status,submitted_at,content_hash,row_version from work_record where id=?",record);
        var photo=response(as(multipart("/api/v1/ai-evidence/records/{id}/photos",record)
            .file(new MockMultipartFile("file","synthetic.png","image/png",photo())),ASSISTANT),200).path("id").asText();
        var body=Map.of("subjectAssignmentId",frontAssignment,"attachmentIds",List.of(photo));
        var check=response(as(post("/api/v1/ai-evidence/records/{id}/checks",record),ASSISTANT).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),200);
        assertThat(check.path("rule_id")).isEqualTo(copied.path("id"));
        assertThat(check.path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForMap("select status,submitted_at,content_hash,row_version from work_record where id=?",record)).isEqualTo(before);
        // A new version must not rewrite the standard attached to an existing check.
        response(as(post("/api/v1/ai-evidence/rules"),PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("positionId",ASSISTANT_POSITION,"expectedLatestId",copied.path("id").asText(),
                "configuration",copied.path("configuration"),"shadow",true))),200);
        var references=response(as(get("/api/v1/ai-evidence/records/{id}/rules",record),ASSISTANT),200);
        assertThat(references).hasSize(2);
        assertThat(references.toString()).contains(copied.path("id").asText(),"RETIRED","SHADOW");
        var replacement=response(as(multipart("/api/v1/ai-evidence/records/{id}/photos",record)
            .file(new MockMultipartFile("file","retake.png","image/png",photo())),ASSISTANT),200).path("id").asText();
        var next=response(as(post("/api/v1/ai-evidence/records/{id}/checks",record),ASSISTANT).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("subjectAssignmentId",frontAssignment,"attachmentIds",List.of(replacement),"supersedesId",check.path("id").asText()))),200);
        assertThat(next.path("rule_id")).isEqualTo(copied.path("id"));
        verify(gateway,never()).inspect(any(),any());
    }
}
