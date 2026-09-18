package cn.sifangguan.hotelaios.integrations.wecom;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComSharedInvitationV60MigrationIntegrationTest {
    @Test
    void upgradePreservesExistingExpiriesAndPersonalLimitsWhileAllowingSevenDaySharedCodes() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("59").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("""
                    insert into wecom_open_onboarding_invitation
                        (tenant_id, corp_id, token_hash, issued_at, expires_at, created_by)
                    values ('10000000-0000-0000-0000-000000000001', 'legacy-invitation-test',
                            repeat('a', 64), now() - interval '3 hours', now() - interval '1 hour',
                            '19000000-0000-0000-0000-000000000001'),
                           ('10000000-0000-0000-0000-000000000001', 'legacy-invitation-test',
                            repeat('b', 64), now(), now() + interval '2 hours',
                            '19000000-0000-0000-0000-000000000001')
                    """);
            var before = jdbc.queryForList("""
                    select id, issued_at, expires_at, token_hash
                    from wecom_open_onboarding_invitation order by id
                    """);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("60").load().migrate();
            assertThat(jdbc.queryForList("""
                    select id, issued_at, expires_at, token_hash
                    from wecom_open_onboarding_invitation order by id
                    """)).isEqualTo(before);
            assertThat(jdbc.queryForObject("""
                    select count(*) from wecom_open_onboarding_invitation where expires_at > now()
                    """, Integer.class)).isOne();
            jdbc.update("""
                    insert into wecom_open_onboarding_invitation
                        (tenant_id, corp_id, token_hash, issued_at, expires_at, created_by)
                    values ('10000000-0000-0000-0000-000000000001', 'new-invitation-test',
                            repeat('c', 64), now(), now() + interval '7 days',
                            '19000000-0000-0000-0000-000000000001')
                    """);
            assertThatThrownBy(() -> jdbc.update("""
                    update wecom_open_onboarding_invitation
                    set expires_at = issued_at + interval '8 days' where corp_id = 'new-invitation-test'
                    """)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    insert into wecom_person_onboarding
                        (tenant_id, corp_id, user_id_fingerprint, display_name,
                         source_event_hash, last_event_at, invitation_token_hash,
                         invitation_issued_at, invitation_expires_at)
                    values ('10000000-0000-0000-0000-000000000001', 'personal-invitation-test',
                            repeat('d', 64), 'test', repeat('e', 64), now(), repeat('f', 64),
                            now(), now() + interval '7 days')
                    """)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("""
                    select relrowsecurity and relforcerowsecurity
                    from pg_class where oid = 'wecom_open_onboarding_invitation'::regclass
                    """, Boolean.class)).isTrue();
        }
    }
}
