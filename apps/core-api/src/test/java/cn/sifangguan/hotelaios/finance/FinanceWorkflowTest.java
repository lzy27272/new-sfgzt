package cn.sifangguan.hotelaios.finance;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FinanceWorkflowTest {
    @Test void newChainRequiresDirectorAndCeoWhileLegacyChainRemainsUnchanged() {
        assertThat(FinanceWorkflow.next("MANAGER_REVIEW",2)).isEqualTo("FINANCE_REVIEW");
        assertThat(FinanceWorkflow.next("FINANCE_REVIEW",2)).isEqualTo("DIRECTOR_REVIEW");
        assertThat(FinanceWorkflow.next("DIRECTOR_REVIEW",2)).isEqualTo("LEADER_REVIEW");
        assertThat(FinanceWorkflow.next("LEADER_REVIEW",2)).isEqualTo("APPROVED");
        assertThat(FinanceWorkflow.next("FINANCE_REVIEW",1)).isEqualTo("LEADER_REVIEW");
        assertThatThrownBy(()->FinanceWorkflow.next("DIRECTOR_REVIEW",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FinanceWorkflow.next("DRAFT",2)).isInstanceOf(IllegalArgumentException.class);
    }
}
