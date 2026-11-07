package cn.sifangguan.hotelaios.workpackage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

/** Counts executable obligations, never plan headings or repeated references to an obligation. */
public final class WorkCompletionMetrics {
    private WorkCompletionMetrics() {}

    public record Fact(UUID id, String title, UUID hotelId, String hotelName, String source,
                       OffsetDateTime dueAt, OffsetDateTime submittedAt, String status,
                       UUID expectationId, UUID taskId, UUID recordId) {
        public boolean completed() { return "COMPLETED".equals(status); }
        public boolean onTime() {
            return completed() && submittedAt != null && !submittedAt.isAfter(dueAt);
        }
        public Map<String,Object> asMap() {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("id",id); result.put("title",title); result.put("hotelId",hotelId);
            result.put("hotelName",hotelName); result.put("source",source); result.put("dueAt",dueAt);
            result.put("submittedAt",submittedAt); result.put("status",status);
            result.put("expectationId",expectationId); result.put("taskId",taskId); result.put("recordId",recordId);
            result.put("qualified",completed()); result.put("onTime",onTime());
            return result;
        }
    }

    public static Map<String,Object> summarize(List<Fact> facts, OffsetDateTime asOf) {
        Map<String,Object> result = counts(facts, asOf);
        result.put("denominator",result.remove("due"));
        result.put("qualifiedCompleted",result.remove("completed"));
        result.put("onTimeCompleted",result.remove("onTime"));
        result.put("lateQualified",facts.stream().filter(f -> due(f,asOf) && f.completed()
                && f.submittedAt()!=null && f.submittedAt().isAfter(f.dueAt())).count());
        result.put("onTimeSubmitted",facts.stream().filter(f -> due(f,asOf)
                && Set.of("REVIEW","COMPLETED").contains(f.status())
                && f.submittedAt()!=null && !f.submittedAt().isAfter(f.dueAt())).count());
        List<Map<String,Object>> sources = new ArrayList<>();
        for (var entry: Map.of("ROUTINE","日清及周期工作","PLAN","计划任务","ASSIGNED","临时下达任务").entrySet()) {
            Map<String,Object> count=counts(facts.stream().filter(f -> entry.getKey().equals(f.source())).toList(),asOf);
            count.put("source",entry.getKey()); count.put("label",entry.getValue()); sources.add(count);
        }
        sources.sort(Comparator.comparingInt(row -> List.of("ROUTINE","PLAN","ASSIGNED").indexOf(row.get("source"))));
        result.put("bySource",sources);
        return result;
    }

    private static boolean due(Fact fact, OffsetDateTime asOf) { return !fact.dueAt().isAfter(asOf); }
    private static Map<String,Object> counts(List<Fact> facts, OffsetDateTime asOf) {
        long due=facts.stream().filter(f -> due(f,asOf)).count();
        long completed=facts.stream().filter(f -> due(f,asOf) && f.completed()).count();
        long onTime=facts.stream().filter(f -> due(f,asOf) && f.onTime()).count();
        long pending=facts.stream().filter(f -> due(f,asOf) && "REVIEW".equals(f.status())).count();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("due",due); result.put("completed",completed); result.put("onTime",onTime);
        result.put("pendingReview",pending);
        result.put("futurePlanned",facts.stream().filter(f -> !due(f,asOf)).count());
        result.put("completionRate",rate(completed,due)); result.put("onTimeRate",rate(onTime,due));
        return result;
    }
    private static BigDecimal rate(long count,long denominator) {
        return denominator==0 ? null : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP);
    }
}
