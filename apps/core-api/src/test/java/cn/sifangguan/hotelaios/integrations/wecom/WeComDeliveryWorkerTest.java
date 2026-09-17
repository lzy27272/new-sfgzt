package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComDeliveryWorkerTest {
    private static final UUID SOURCE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test
    void buildsOnlyOAuthAllowlistedInternalReturnTargets() {
        assertSafe("TASK", "#/tasks?view=mine&taskId=" + SOURCE_ID);
        assertSafe("WORK_EXPECTATION", "#/my-work?expectationId=" + SOURCE_ID);
        assertSafe("DAILY_REPORT", "#/daily-reports/" + SOURCE_ID);
        assertSafe("WORK_RECORD", "#/workbench");
    }

    @Test
    void rejectsMissingIdsAndUnsupportedSources() {
        assertThatThrownBy(() -> WeComDeliveryWorker.returnToFor("DAILY_REPORT", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source id");
        assertThatThrownBy(() -> WeComDeliveryWorker.returnToFor("OTHER", SOURCE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void groupChatIsReservedForExplicitTaskNotifications() {
        assertThat(WeComDeliveryWorker.useGroupChat("TASK", "chat-1")).isTrue();
        assertThat(WeComDeliveryWorker.useGroupChat("TASK", " ")).isFalse();
        assertThat(WeComDeliveryWorker.useGroupChat("WORK_EXPECTATION", "chat-1")).isFalse();
        assertThat(WeComDeliveryWorker.useGroupChat("DAILY_REPORT", "chat-1")).isFalse();
        assertThat(WeComDeliveryWorker.useGroupChat("WORK_RECORD", "chat-1")).isFalse();
    }

    private static void assertSafe(String sourceType, String expected) {
        String returnTo = WeComDeliveryWorker.returnToFor(sourceType, SOURCE_ID);
        assertThat(returnTo).isEqualTo(expected);
        assertThat(WeComOAuthService.validateReturnTo(returnTo)).isEqualTo(expected);
    }
}
