package cn.sifangguan.hotelaios.workdata;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class WorkDataCaptureSourceIntegrationTest {
    @Test
    void actualAttachmentQueryAcceptsBothSourcesWithoutRelaxingOtherEvidenceChecks() throws Exception {
        // Isolated real PostgreSQL exercises the production query, not a permissive JDBC stub.
        try (var postgres = EmbeddedPostgres.start()) {
            var jdbc = new NamedParameterJdbcTemplate(postgres.getPostgresDatabase());
            jdbc.getJdbcTemplate().execute("""
                    create table attachment(tenant_id uuid,work_record_id uuid,checkpoint_code text,
                      capture_source text,scan_status text,media_type text,evidence_metadata jsonb)
                    """);
            var service = mock(WorkDataService.class, CALLS_REAL_METHODS);
            var mapper = new ObjectMapper();
            ReflectionTestUtils.setField(service, "jdbc", jdbc);
            ReflectionTestUtils.setField(service, "objectMapper", mapper);
            var principal = new TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "TEST", Set.of(), UUID.randomUUID());
            UUID record = UUID.randomUUID();
            var parameters = new MapSqlParameterSource("tenant", principal.tenantId()).addValue("record", record);
            var requirement = mapper.readTree("{\"allowedKinds\":[\"IMAGE\"]}");
            for (String actual : new String[]{"CAMERA", "FILE_PICKER", "SYSTEM"}) {
                jdbc.getJdbcTemplate().execute("truncate attachment");
                jdbc.update("""
                        insert into attachment values(:tenant,:record,'guest_service_photo',:source,'CLEAN','image/jpeg','{}')
                        """, parameters.addValue("source", actual));
                for (String policy : new String[]{"", "CAMERA", "FILE_PICKER", "CAMERA_OR_FILE", "UNKNOWN"}) {
                    Integer matching = ReflectionTestUtils.invokeMethod(service, "matchingEvidence", principal,
                            record, requirement, "guest_service_photo", policy, null);
                    boolean expected = policy.isEmpty() || policy.equals(actual)
                            || policy.equals("CAMERA_OR_FILE") && Set.of("CAMERA", "FILE_PICKER").contains(actual);
                    assertThat(matching).as("%s under %s", actual, policy).isEqualTo(expected ? 1 : 0);
                }
            }
            jdbc.getJdbcTemplate().execute("truncate attachment");
            jdbc.update("""
                    insert into attachment values
                     (:tenant,:record,'guest_service_photo','FILE_PICKER','REJECTED','image/jpeg','{}'),
                     (:tenant,:record,'other_checkpoint','FILE_PICKER','CLEAN','image/jpeg','{}'),
                     (:tenant,:record,'guest_service_photo','FILE_PICKER','CLEAN','application/pdf','{}'),
                     (:tenant,:record,'guest_service_photo','FILE_PICKER','CLEAN','image/jpeg','{"reference":true}'),
                     (:tenant,:other,'guest_service_photo','FILE_PICKER','CLEAN','image/jpeg','{}'),
                     (:other,:record,'guest_service_photo','FILE_PICKER','CLEAN','image/jpeg','{}')
                    """, parameters.addValue("other", UUID.randomUUID()));
            Integer matching = ReflectionTestUtils.invokeMethod(service, "matchingEvidence", principal,
                    record, requirement, "guest_service_photo", "CAMERA_OR_FILE", null);
            assertThat(matching).isZero();
        }
    }
}
