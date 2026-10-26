package cn.sifangguan.hotelaios.finance;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FinanceInsightContentTest {
 private final Map<String,Object> facts=Map.of("facts",List.of(Map.of("id","same-version:revenue","value","100.00")));
 @Test void forecastMustHaveBothCitedSameVersionFactsAndExplicitAssumptionsAndUncertainty(){var valid=Map.<String,Object>of("analysis",List.of(Map.of("kind","FORECAST","text","下月收入预测为条件性估算，不是已发生收入","citations",List.of("same-version:revenue"),"assumptions",List.of("假设营业天数与已确认本期一致，且没有新增停业日"),"uncertainty","尚无已确认下月订单，不能保证预测；缺少渠道结构")),"recommendations",List.of());assertThatCode(()->FinanceInsightService.validateContent(valid,facts)).doesNotThrowAnyException();}
 @Test void aPredictionWithoutAssumptionsOrUncertaintyIsRejectedEvenIfItHasAValidCitation(){assertThatThrownBy(()->FinanceInsightService.validateContent(Map.of("analysis",List.of(Map.of("kind","FORECAST","text","估计下月增长","citations",List.of("same-version:revenue"),"uncertainty","渠道尚缺")),"recommendations",List.of()),facts)).hasMessageContaining("可核对假设");assertThatThrownBy(()->FinanceInsightService.validateContent(Map.of("analysis",List.of(Map.of("text","预计下月增长10%","citations",List.of("same-version:revenue"),"assumptions",List.of("营业天数一致"))),"recommendations",List.of()),facts)).hasMessageContaining("不确定性");}
 @Test void recommendationsNeedRealUncertaintyAndCannotCiteAnotherSnapshot(){assertThatThrownBy(()->FinanceInsightService.validateContent(Map.of("analysis",List.of(),"recommendations",List.of(Map.of("text","建议增加投入","citations",List.of("same-version:revenue")))),facts)).hasMessageContaining("不确定性");assertThatThrownBy(()->FinanceInsightService.validateContent(Map.of("analysis",List.of(Map.of("text","来源解释","citations",List.of("another-version:revenue"))),"recommendations",List.of()),facts)).hasMessageContaining("有效事实引用");}
}
