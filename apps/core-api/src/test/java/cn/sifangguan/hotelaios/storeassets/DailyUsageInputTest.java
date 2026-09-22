package cn.sifangguan.hotelaios.storeassets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DailyUsageInputTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String,Object> line(String value) { return Map.of("itemId",UUID.randomUUID().toString(),"quantity",value,"unit","条","expectedItemVersion",0); }
    @Test void blanksRequireConfirmationAndZerosDoNot() {
        assertThatThrownBy(() -> DailyUsageInput.normalize(mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",false,"lines",List.of(line("")))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未填");
        for (var lines : List.of(List.of(line("0")),List.of(line("")))) {
            var normalized=DailyUsageInput.normalize(mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",lines)));
            assertThat(normalized.path("noUsage").asBoolean()).isTrue(); assertThat(normalized.path("lines").isEmpty()).isTrue();
        }
    }
    @Test void onlyPositiveValuesPostAndTheOriginalSnapshotIsUnchanged() {
        var input=mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(" 2 "),line("0"),line(""))));
        var before=input.deepCopy(); var normalized=DailyUsageInput.normalize(input);
        assertThat(normalized.path("lines").size()).isEqualTo(1);
        assertThat(normalized.path("lines").get(0).path("quantity").asText()).isEqualTo("2.0000");
        assertThat(input).isEqualTo(before);
    }
    @Test void rejectsInvalidNegativeDuplicateAndOverprecisionEntries() {
        for(String value:List.of("-1","not-a-number","0.00001","100000000000000")) assertThatThrownBy(() -> DailyUsageInput.normalize(mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(line(value)))))).isInstanceOf(IllegalArgumentException.class);
        var same=line("1");
        assertThatThrownBy(() -> DailyUsageInput.normalize(mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",true,"lines",List.of(same,same))))).hasMessageContaining("重复");
        assertThatThrownBy(() -> DailyUsageInput.normalize(mapper.valueToTree(Map.of("schemaVersion",2,"remainingUnusedConfirmed",false,"lines",List.of())))).hasMessageContaining("确认");
    }
}
