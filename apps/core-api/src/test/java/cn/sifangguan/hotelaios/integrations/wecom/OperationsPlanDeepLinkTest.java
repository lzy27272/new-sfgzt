package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OperationsPlanDeepLinkTest {
    @Test void planNotificationsOpenTheAuthorizedPlanInsideTheExistingOperationsPage() {
        UUID id=UUID.randomUUID();
        assertThat(WeComDeliveryWorker.returnToFor("WORK_PLAN",id)).isEqualTo("#/operations-work?tab=plans&planId="+id);
        assertThat(WeComDeliveryWorker.useGroupChat("WORK_PLAN","unrelated-group")).isFalse();
        assertThat(WeComOAuthService.validateReturnTo("#/operations-work?tab=plans&planId="+id))
                .isEqualTo("#/operations-work?tab=plans&planId="+id);
        assertThatThrownBy(()->WeComOAuthService.validateReturnTo("#/operations-work?tab=plans&planId="+id+"&planId="+id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WeComOAuthService.validateReturnTo("#/operations-work?tab=monthly&planId="+id)).isInstanceOf(IllegalArgumentException.class);
    }
}
