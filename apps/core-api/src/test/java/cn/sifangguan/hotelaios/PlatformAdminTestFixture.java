package cn.sifangguan.hotelaios;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates an account-level platform administrator for integration tests.
 *
 * <p>The product demo seed intentionally models business roles. Configuration
 * tests must not borrow the CEO identity now that rules and work packages are
 * an administrator-only control plane.</p>
 */
public final class PlatformAdminTestFixture {
    public static final String ACCOUNT_ID = "19000000-0000-0000-0000-000000000099";
    private static final String ROLE_ID = "19400000-0000-0000-0000-000000000099";
    private static final String ROLE_ASSIGNMENT_ID = "19500000-0000-0000-0000-000000000099";

    private PlatformAdminTestFixture() {
    }

    public static void ensure(JdbcTemplate jdbc, String tenantId, String grantingActorId) {
        jdbc.update("""
                insert into user_account
                    (id, tenant_id, login_name, display_name, status)
                values (?::uuid, ?::uuid, 'integration.platform.admin',
                        '集成测试平台管理员', 'ACTIVE')
                on conflict (tenant_id, login_name) do nothing
                """, ACCOUNT_ID, tenantId);
        jdbc.update("""
                insert into app_role (id, tenant_id, code, name, role_type)
                values (?::uuid, ?::uuid, 'PLATFORM_ADMIN', '平台管理员', 'SYSTEM')
                on conflict (tenant_id, code) do nothing
                """, ROLE_ID, tenantId);
        jdbc.update("""
                insert into role_permission (tenant_id, role_id, permission_id)
                select ?::uuid, role.id, permission_item.id
                from app_role role
                cross join permission permission_item
                where role.tenant_id = ?::uuid and role.code = 'PLATFORM_ADMIN'
                on conflict do nothing
                """, tenantId, tenantId);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id,
                     scope_type, granted_by, valid_from)
                select ?::uuid, ?::uuid, ?::uuid, role.id, null,
                       'TENANT', ?::uuid, now()
                from app_role role
                where role.tenant_id = ?::uuid and role.code = 'PLATFORM_ADMIN'
                on conflict (tenant_id, id) do nothing
                """, ROLE_ASSIGNMENT_ID, tenantId, ACCOUNT_ID,
                grantingActorId, tenantId);
    }
}
