package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.PlatformAdminTestFixture;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ImageRetentionWorkerIntegrationTest {
    static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final String CEO="19000000-0000-0000-0000-000000000001";
    static final EmbeddedPostgres POSTGRES=startPostgres();
    static final Path ROOT=tempRoot();
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired TenantDatabaseContext context;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    ImageRetentionWorker worker;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->POSTGRES.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.user",()->"postgres");
        registry.add("spring.flyway.password",()->"postgres");
        registry.add("app.security.development-header-auth-enabled",()->true);
        registry.add("app.attachments.root",()->ROOT.toString());
        registry.add("app.data-retention.cleanup-worker-enabled",()->false);
    }
    @BeforeEach void setup() {
        jdbc.update("delete from image_retention_object");
        jdbc.update("update data_retention_policy set image_retention_days=31,image_cleanup_enabled=true where tenant_id=?",TENANT);
        PlatformAdminTestFixture.ensure(jdbc,TENANT.toString(),CEO);
        worker=new ImageRetentionWorker(named,context,manager,new ImageFileCleanup(ROOT.toString()),true,TENANT.toString());
    }
    @AfterAll static void stop() throws Exception { POSTGRES.close(); }

    @Test void onlyExpiredOrdinaryImagesAreRemovedAndMetadataIsPreserved() throws Exception {
        UUID old=image(32,false), recent=image(30,false), held=image(70,true);
        Path document=ROOT.resolve("ordinary-document.pdf"); Files.writeString(document,"keep document");
        long records=jdbc.queryForObject("select count(*) from work_record",Long.class);
        assertThat(worker.processTenant(TENANT)).isEqualTo(1);
        assertThat(Files.exists(file(old))).isFalse();
        assertThat(Files.exists(file(recent))).isTrue();
        assertThat(Files.exists(file(held))).isTrue();
        assertThat(Files.exists(document)).isTrue();
        assertThat(state(old)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from image_retention_object",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from work_record",Long.class)).isEqualTo(records);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id=? and action='IMAGE_RETENTION_DELETED'",Integer.class,old)).isEqualTo(1);
        assertThat(worker.processTenant(TENANT)).isZero();
    }
    @Test void eitherDisabledGateAndUnconfiguredTenantPreventDeletion() throws Exception {
        UUID old=image(40,false);
        var disabled=new ImageRetentionWorker(named,context,manager,new ImageFileCleanup(ROOT.toString()),false,TENANT.toString());
        assertThat(disabled.processTenant(TENANT)).isZero();
        assertThat(worker.processTenant(UUID.randomUUID())).isZero();
        jdbc.update("update data_retention_policy set image_cleanup_enabled=false where tenant_id=?",TENANT);
        assertThat(worker.processTenant(TENANT)).isZero();
        assertThat(Files.exists(file(old))).isTrue();
    }
    @Test void permanentSamplesNeverExpireOrEnterCleanupEvenAfterAnOldFailedAttempt() throws Exception {
        UUID sample=image(3650,false), failedSample=image(3650,false), ordinary=image(6,false);
        jdbc.update("update image_retention_object set source_type='ai_evidence_sample' where id in (?,?)",sample,failedSample);
        jdbc.update("update image_retention_object set state='DELETE_FAILED',last_error='UNSAFE_OBJECT_NAMESPACE' where id=?",failedSample);
        jdbc.update("update data_retention_policy set image_retention_days=5 where tenant_id=?",TENANT);
        mvc.perform(get("/api/v1/admin/data-retention-preview").header("X-Tenant-Id",TENANT)
                .header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(1));
        mvc.perform(get("/api/v1/admin/image-retention").param("search",sample.toString())
                .header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].permanent_retention").value(true))
                .andExpect(jsonPath("$.items[0].expires_at").doesNotExist());
        assertThat(worker.processTenant(TENANT)).isEqualTo(1);
        assertThat(state(ordinary)).isEqualTo("DELETED");
        assertThat(Files.exists(file(sample))).isTrue();assertThat(Files.exists(file(failedSample))).isTrue();
        assertThat(state(sample)).isEqualTo("AVAILABLE");assertThat(state(failedSample)).isEqualTo("DELETE_FAILED");
        assertThat(worker.processTenant(TENANT)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id in (?,?) and action='IMAGE_RETENTION_DELETED'",
                Integer.class,sample,failedSample)).isZero();
    }
    @Test void samplePermanentRetentionCannotBeReleasedThroughTheOrdinaryHoldApi() throws Exception {
        UUID sample=image(40,false);
        jdbc.update("update image_retention_object set source_type='ai_evidence_sample' where id=?",sample);
        mvc.perform(put("/api/v1/admin/image-retention/"+sample+"/hold").header("X-Tenant-Id",TENANT)
                .header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"尝试解除\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        assertThat(worker.processTenant(TENANT)).isZero();assertThat(Files.exists(file(sample))).isTrue();
    }
    @Test void corruptOrMissingFilesAreHandledWithoutDeletingOtherObjects() throws Exception {
        UUID corrupt=image(40,false), missing=image(40,false);
        Files.writeString(file(corrupt),"changed"); Files.delete(file(missing));
        assertThat(worker.processTenant(TENANT)).isEqualTo(1);
        assertThat(state(corrupt)).isEqualTo("DELETE_FAILED");
        assertThat(Files.exists(file(corrupt))).isTrue();
        assertThat(state(missing)).isEqualTo("DELETED");
        assertThat(worker.processTenant(TENANT)).isZero();
    }
    @Test void holdRequiresAdminReasonAndCurrentVersionAndCanBeReleased() throws Exception {
        UUID id=image(40,false);
        String url="/api/v1/admin/image-retention/"+id+"/hold";
        mvc.perform(put(url).header("X-Tenant-Id",TENANT).header("X-Actor-Id",CEO)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"reason\":\"重要证据\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(url).header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"reason\":\"事故证据\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.legalHold").value(true));
        assertThat(worker.processTenant(TENANT)).isZero();
        mvc.perform(put(url).header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"reason\":\"已归档\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(put(url).header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"reason\":\"已结束保留\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(worker.processTenant(TENANT)).isEqualTo(1);
        mvc.perform(put(url).header("X-Tenant-Id",TENANT).header("X-Actor-Id",PlatformAdminTestFixture.ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"reason\":\"迟到标记\",\"expectedVersion\":3}"))
                .andExpect(status().isConflict());
    }
    @Test void lockedImportantEvidenceCannotRaceTheWorker() throws Exception {
        UUID id=image(40,false);
        try(var connection=POSTGRES.getPostgresDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try(var update=connection.prepareStatement("update image_retention_object set legal_hold=true,hold_reason='pending hold' where id=?")) {
                update.setObject(1,id); update.executeUpdate();
            }
            assertThat(worker.processTenant(TENANT)).isZero();
            connection.commit();
        }
        assertThat(worker.processTenant(TENANT)).isZero();
        assertThat(Files.exists(file(id))).isTrue();
    }
    @Test void exact31DayBoundaryUsesElapsedHours() throws Exception {
        UUID boundary=image(31,false), justBefore=image(31,false);
        jdbc.update("update image_retention_object set uploaded_at=now()-interval '744 hours'+interval '1 hour' where id=?",justBefore);
        assertThat(worker.processTenant(TENANT)).isEqualTo(1);
        assertThat(state(boundary)).isEqualTo("DELETED");
        assertThat(state(justBefore)).isEqualTo("AVAILABLE");
    }
    UUID image(int days,boolean held) throws Exception {
        UUID id=UUID.randomUUID(); Path file=file(id); Files.createDirectories(file.getParent());
        byte[] bytes=("test-image-"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file,bytes);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update("""
                insert into image_retention_object(id,tenant_id,object_key,source_type,source_id,original_name,
                    size_bytes,sha256,uploaded_at,legal_hold,hold_reason)
                values(?, ?, ?, 'attachment', ?, 'test.jpg', ?, ?, now()-?*interval '24 hours', ?, ?)
                """,id,TENANT,ROOT.relativize(file).toString().replace('\\','/'),id,bytes.length,sha,days,held,held?"important":null);
        return id;
    }
    Path file(UUID id) { return ROOT.resolve(TENANT+"/work-records/"+id+"/"+id+".jpg"); }
    String state(UUID id) { return jdbc.queryForObject("select state from image_retention_object where id=?",String.class,id); }
    static EmbeddedPostgres startPostgres() { try { return EmbeddedPostgres.builder().setPort(0).start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    static Path tempRoot() { try { return Files.createTempDirectory("image-retention-test-").toRealPath(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
}
