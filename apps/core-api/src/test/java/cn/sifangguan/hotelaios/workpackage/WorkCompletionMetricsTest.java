package cn.sifangguan.hotelaios.workpackage;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkCompletionMetricsTest {
    private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-07T18:30:00+08:00");
    private static WorkCompletionMetrics.Fact fact(String source,String status,int dueHours,int submitHours) {
        return new WorkCompletionMetrics.Fact(UUID.randomUUID(),"检查",UUID.randomUUID(),"测试门店",source,
                NOW.plusHours(dueHours),NOW.plusHours(submitHours),status,null,null,null);
    }
    @Test void aFutureAcceptedTaskDoesNotEnterCurrentDenominatorOrNumerator() {
        var result=WorkCompletionMetrics.summarize(List.of(fact("ROUTINE","COMPLETED",-2,-3),fact("PLAN","COMPLETED",24,0)),NOW);
        assertThat(result.get("denominator")).isEqualTo(1L);assertThat(result.get("qualifiedCompleted")).isEqualTo(1L);
        assertThat(result.get("futurePlanned")).isEqualTo(1L);assertThat(result.get("completionRate")).isEqualTo(new BigDecimal("100.00"));
    }
    @Test void aLateReviewDoesNotMakeAnOnTimeAcceptedVersionLate() {
        var result=WorkCompletionMetrics.summarize(List.of(fact("PLAN","COMPLETED",-1,-2),fact("ASSIGNED","REVIEW",-1,-2)),NOW);
        assertThat(result.get("onTimeCompleted")).isEqualTo(1L);assertThat(result.get("pendingReview")).isEqualTo(1L);
        assertThat(result.get("onTimeSubmitted")).isEqualTo(2L);assertThat(result.get("completionRate")).isEqualTo(new BigDecimal("50.00"));
    }
    @Test void failingThenLateRemediatingAWorkNeverRestoresItsOnTimeCredit() {
        var failed=WorkCompletionMetrics.summarize(List.of(fact("ROUTINE","OVERDUE",-4,-5)),NOW);
        var repaired=WorkCompletionMetrics.summarize(List.of(fact("ROUTINE","COMPLETED",-4,-1)),NOW);
        assertThat(failed.get("qualifiedCompleted")).isEqualTo(0L);assertThat(repaired.get("qualifiedCompleted")).isEqualTo(1L);
        assertThat(repaired.get("onTimeCompleted")).isEqualTo(0L);assertThat(repaired.get("lateQualified")).isEqualTo(1L);
    }
    @Test void unavailableSubmissionTimeIsNotInventedAsOnTimeOrLate() {
        var unknown=new WorkCompletionMetrics.Fact(UUID.randomUUID(),"旧任务",null,null,"ASSIGNED",NOW.minusHours(1),null,"COMPLETED",null,null,null);
        var result=WorkCompletionMetrics.summarize(List.of(unknown),NOW);
        assertThat(result.get("qualifiedCompleted")).isEqualTo(1L);assertThat(result.get("onTimeCompleted")).isEqualTo(0L);
        assertThat(result.get("lateQualified")).isEqualTo(0L);
    }
    @Test void anEmptyOrFutureOnlyMonthDoesNotShowOneHundredPercent() {
        assertThat(WorkCompletionMetrics.summarize(List.of(),NOW).get("completionRate")).isNull();
        assertThat(WorkCompletionMetrics.summarize(List.of(fact("PLAN","PENDING",3,0)),NOW).get("onTimeRate")).isNull();
    }
}
