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
        assertSafe("ACADEMY_PLAN", "#/academy?tab=mine&id=" + SOURCE_ID);
        assertSafe("PROCUREMENT", "#/procurement?orderId=" + SOURCE_ID);
        assertSafe("LOST_PROPERTY", "#/lost-properties?id=" + SOURCE_ID);
    }

    @Test
    void managementRemindersOpenManagementRatherThanTheEmployeesSubmissionPage() {
        for (String type : new String[]{"TASK_OVERDUE", "TASK_ESCALATED"}) {
            assertThat(WeComDeliveryWorker.returnToFor("TASK", SOURCE_ID, type)).isEqualTo("#/workbench");
        }
        String target = WeComDeliveryWorker.returnToFor("WORK_EXPECTATION", SOURCE_ID, "WORK_EXPECTATION_SUPERVISION");
        assertThat(target).isEqualTo("#/workbench");
        assertThat(WeComOAuthService.validateReturnTo(target)).isEqualTo(target);
        assertThat(WeComDeliveryWorker.returnToFor("WORK_EXPECTATION", SOURCE_ID, "WORK_EXPECTATION_REMINDER"))
                .isEqualTo("#/my-work?expectationId=" + SOURCE_ID);
        assertThat(WeComDeliveryWorker.returnToFor("TASK", SOURCE_ID, "TASK_DISPATCHED"))
                .isEqualTo("#/tasks?view=mine&taskId=" + SOURCE_ID);
    }

    @Test
    void rejectsMissingIdsAndUnsupportedSources() {
        assertThatThrownBy(() -> WeComDeliveryWorker.returnToFor("ACADEMY_PLAN", null)).isInstanceOf(IllegalArgumentException.class);
        for (String target : new String[]{"#/academy?tab=team&id=" + SOURCE_ID,
                "#/academy?tab=mine&id=invalid", "#/academy?tab=mine&id=" + SOURCE_ID + "&id=" + SOURCE_ID,
                "#/academy?tab=mine&id=" + SOURCE_ID + "&next=/admin"}) {
            assertThatThrownBy(() -> WeComOAuthService.validateReturnTo(target)).isInstanceOf(IllegalArgumentException.class);
        }
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
        assertThat(WeComDeliveryWorker.useGroupChat("PROCUREMENT", "chat-1")).isFalse();
        assertThat(WeComDeliveryWorker.useGroupChat("LOST_PROPERTY", "chat-1")).isFalse();
    }

    @Test
    void procurementDeepLinksRejectExtraOrDuplicateParameters() {
        for (String path : new String[]{"#/procurement", "#/procurement?orderId=bad",
                "#/procurement?orderId="+SOURCE_ID+"&orderId="+SOURCE_ID,
                "#/procurement?orderId="+SOURCE_ID+"&redirect=https://example.com"}) {
            assertThatThrownBy(() -> WeComOAuthService.validateReturnTo(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertSafe(String sourceType, String expected) {
        String returnTo = WeComDeliveryWorker.returnToFor(sourceType, SOURCE_ID);
        assertThat(returnTo).isEqualTo(expected);
        assertThat(WeComOAuthService.validateReturnTo(returnTo)).isEqualTo(expected);
    }

    @Test void financialV2CurrentNodesKeepStrictInternalTargetsAndNeverGroupShare() {
        for(String source:new String[]{"FINANCE_ASSET_GAIN","FINANCE_INPUT_BASIS","FINANCE_TAX_ACCRUAL","FINANCE_LOAN_OVERDUE","FINANCE_CASH","FINANCE_ACCRUAL","FINANCE_REVENUE","FINANCE_PAYROLL","FINANCE_INTERCOMPANY","FINANCE_CASH_ERROR","FINANCE_AR_ADJUSTMENT","FINANCE_AR_RECOVERY","FINANCE_TAX_CALENDAR","FINANCE_INSIGHT_ACTION","FINANCE_BUDGET_EXCEPTION","FINANCE_DELEGATION","FINANCE_CLOSE_REGISTER","FINANCE_CLOSE_BANK","FINANCE_ARCHIVE_REQUEST","FINANCE_ACCRUAL_POLICY","FINANCE_SUPPLY_INVOICE_RELEASE"}){
            String target=WeComDeliveryWorker.returnToFor(source,SOURCE_ID);assertThat(WeComOAuthService.validateReturnTo(target)).isEqualTo(target);assertThat(WeComDeliveryWorker.useGroupChat(source,"chat-1")).isFalse();
        }
        for(String path:new String[]{"#/finance?tab=insight-actions&actionId=bad","#/finance?tab=insight-actions&actionId="+SOURCE_ID+"&redirect=https://evil.example","#/finance?tab=authorizations&delegationId="+SOURCE_ID+"&delegationId="+SOURCE_ID,"#/finance?tab=business-exceptions&section=other&exceptionId="+SOURCE_ID})assertThatThrownBy(()->WeComOAuthService.validateReturnTo(path)).isInstanceOf(IllegalArgumentException.class);
    }
}
