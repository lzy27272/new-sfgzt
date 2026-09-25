package cn.sifangguan.hotelaios.aireview;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;

class AiEvidenceValidationTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode config() throws Exception { return json.readTree("""
        {"criteria":[{"code":"BADGE","enabled":true,"standard":"佩戴工牌"},
        {"code":"UNIFORM","enabled":true,"standard":"藏蓝制服"}],"exceptions":""}
        """); }
    JsonNode check(String checks,int people) throws Exception {
        return json.readTree("{\"overall\":\"PASS\",\"personCount\":"+people+",\"checks\":"+checks+"}");
    }
    @Test void missingObservationsNeverPass() throws Exception {
        var result=AiEvidenceResultValidator.validate(check("[]",1),config(),Set.of("photo"));
        assertThat(result.path("overall").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("checks")).hasSize(2);
    }
    @Test void multiplePeopleCannotBeDeclaredCompliant() throws Exception {
        var result=AiEvidenceResultValidator.validate(check("""
           [{"code":"BADGE","status":"PASS","observation":"有工牌","evidenceId":"photo"}]
           """,2),config(),Set.of("photo"));
        assertThat(result.at("/checks/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.at("/checks/0/observation").asText()).contains("分人");
    }
    @Test void recomputesSuggestionAndIgnoresModelActionInstructions() throws Exception {
        var result=AiEvidenceResultValidator.validate(check("""
           [{"code":"BADGE","status":"CONCERN","observation":"规范位置未见工牌","evidenceId":"photo","box":[0.2,0.2,0.4,0.4]},
            {"code":"UNIFORM","status":"PASS","observation":"可见藏蓝制服","evidenceId":"photo","deductKpi":50}]
           """,1),config(),Set.of("photo"));
        assertThat(result.path("overall").asText()).isEqualTo("CONCERN");
        assertThat(result.toString()).doesNotContain("deductKpi");
        assertThat(result.at("/checks/0/box")).hasSize(4);
    }
    @Test void unknownCodesAndDuplicateFindingsAreRejected() throws Exception {
        assertThatThrownBy(()->AiEvidenceResultValidator.validate(check("[{\"code\":\"MAKEUP\"}]",1),config(),Set.of("photo")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AiEvidenceResultValidator.validate(check("[{\"code\":\"BADGE\"},{\"code\":\"BADGE\"}]",1),config(),Set.of("photo")))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidEvidenceAndBoxCannotBecomeProof() throws Exception {
        var result=AiEvidenceResultValidator.validate(check("""
          [{"code":"BADGE","status":"PASS","observation":"工牌可见","evidenceId":"sample-other","box":[-1,0,4,1]}]
          """,1),config(),Set.of("photo"));
        assertThat(result.at("/checks/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.at("/checks/0/box").isMissingNode()).isTrue();
    }
    @Test void providerRequiresExplicitOutboundApprovalAndRejectsArbitraryEndpoints() {
        String url="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        assertThat(new QwenVisionGateway(json,true,false,"test-only","qwen3-vl-plus",url).ready()).isFalse();
        assertThat(new QwenVisionGateway(json,false,true,"test-only","qwen3-vl-plus",url).ready()).isFalse();
        assertThat(new QwenVisionGateway(json,true,true,"","qwen3-vl-plus",url).ready()).isFalse();
        assertThatThrownBy(()->new QwenVisionGateway(json,true,true,"test-only","qwen3-vl-plus","http://localhost:1"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requestIsStructuredAndContainsNoAccountOrPhotoLinks() throws Exception {
        var provider=new QwenVisionGateway(json,false,false,"","qwen3-vl-plus","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        var body=provider.request(config(),List.of(new AiVisionGateway.Image("photo","image/jpeg",new byte[]{1,2},"证据")));
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(body.toString()).contains("data:image/jpeg;base64,").doesNotContain("Bearer","accountId");
    }
    @Test void responseBodyIsBounded() {
        var subscriber=new ChatCompletionsVisionGateway.LimitedBody();
        boolean[] cancelled={false};
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled[0]=true; }
        });
        subscriber.onNext(List.of(ByteBuffer.allocate(131073)));
        assertThat(cancelled[0]).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture().isCompletedExceptionally()).isTrue();
    }
    JsonNode makeupConfig() throws Exception {
        return json.readTree("""
            {"criteria":[{"code":"MAKEUP","enabled":true,"standard":"按已确认的妆面整洁规范"}],"exceptions":"门店批准的合理例外"}
            """);
    }
    @Test void unclearOrMissingMakeupVisibilityCannotPassOrMarkNonCompliance() throws Exception {
        for(String visibility:List.of("", "UNCLEAR", "INVALID")) for(String status:List.of("PASS","CONCERN")) {
            var raw=check("""
                [{"code":"MAKEUP","status":"%s","observation":"模型猜测未化妆","visibility":"%s","evidenceId":"photo"}]
                """.formatted(status,visibility),1);
            var result=AiEvidenceResultValidator.validate(raw,makeupConfig(),Set.of("photo"));
            assertThat(result.path("overall").asText()).isEqualTo("UNKNOWN");
            assertThat(result.at("/checks/0/observation").asText()).contains("看不清不等于未化妆", "人工复核");
        }
    }
    @Test void clearMakeupCanOnlyProduceASuggestionWithEvidenceAndOnePerson() throws Exception {
        for(String status:List.of("PASS","CONCERN","UNKNOWN")) {
            String checks="""
                [{"code":"MAKEUP","status":"%s","observation":"按所填规范描述可见妆面特征","visibility":"CLEAR","evidenceId":"photo"}]
                """.formatted(status);
            var result=AiEvidenceResultValidator.validate(check(checks,1),makeupConfig(),Set.of("photo"));
            assertThat(result.path("overall").asText()).isEqualTo(status);
            assertThat(result.path("mode").asText()).isEqualTo("SHADOW");
            assertThat(result.path("promptVersion").asText()).isEqualTo("appearance-v2");
            assertThat(AiEvidenceResultValidator.validate(check(checks,2),makeupConfig(),Set.of("photo")).path("overall").asText()).isEqualTo("UNKNOWN");
            assertThat(AiEvidenceResultValidator.validate(check(checks,1),makeupConfig(),Set.of("other")).path("overall").asText()).isEqualTo("UNKNOWN");
        }
    }
    @Test void fourCriteriaContractAllowsMakeupButRejectsOtherInferenceCategories() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var criteria=List.of("UNIFORM","BADGE","EQUIPMENT","MAKEUP").stream().map(c->new AiEvidenceModels.Criterion(c,true,"已确认规范")).toList();
            var configuration=new AiEvidenceModels.Configuration(List.of(UUID.randomUUID()),List.of("GROOMING"),criteria,"",List.of());
            assertThat(validator.validate(configuration)).isEmpty();
            assertThat(validator.validate(new AiEvidenceModels.Criterion("GENDER",true,"不允许推断"))).isNotEmpty();
        }
    }
    @Test void promptRequiresVisibleMakeupStandardsAndUncertaintyWithoutIdentityInference() throws Exception {
        var provider=new QwenVisionGateway(json,false,false,"","qwen3-vl-plus","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        var request=provider.request(makeupConfig(),List.of(new AiVisionGateway.Image("photo","image/png",new byte[]{1},"证据")));
        assertThat(request.at("/messages/0/content").asText()).contains("MAKEUP", "清晰正面近照", "UNKNOWN", "visibility", "不做人脸识别", "不推断身份、性别", "不按外貌推断");
        assertThat(request.at("/messages/1/content/0/text").asText()).contains("门店批准的合理例外");
    }
}
