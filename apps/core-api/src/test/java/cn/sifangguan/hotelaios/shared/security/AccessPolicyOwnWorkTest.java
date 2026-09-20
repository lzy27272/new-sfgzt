package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessPolicyOwnWorkTest {
    private final AccessPolicy policy = new AccessPolicy();
    private final UUID hotel = UUID.randomUUID();
    private final UUID headquarters = UUID.randomUUID();
    private final UUID ownAssignment = UUID.randomUUID();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void personalWorkDoesNotGrantHeadquartersWideAccessOrOperationPermissions() {
        TenantContext.set(new TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "GROUP_VICE_PRESIDENT",
                Set.of("GROUP_VICE_PRESIDENT"), Set.of("work-record.read"), Set.of(hotel), Set.of(ownAssignment), false, UUID.randomUUID()));
        assertThatCode(() -> policy.requireOrgScopeOrOwnWork(headquarters, ownAssignment)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireOrgScopeOrOwnWork(hotel, UUID.randomUUID())).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireOrgScope(headquarters)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireOrgScopeOrOwnWork(headquarters, UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireOrgScopeOrOwnWork(headquarters, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requirePermission("work-record.submit")).isInstanceOf(AccessDeniedException.class);
    }
}
