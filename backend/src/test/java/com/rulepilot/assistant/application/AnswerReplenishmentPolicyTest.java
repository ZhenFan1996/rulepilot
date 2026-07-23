package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerReplenishmentPolicyTest {

    @Test
    void replacesAnIncorrectDrawAmountAnswerWithTheCitedRecoveryProcedure() {
        UUID procedureChunk = UUID.randomUUID();
        ModelRequest request = request(procedureChunk, "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。");
        ModelDraft misdirected = new ModelDraft(
                "按抽牌量计算。", "抽牌量由基础值和修正值组成。", List.of(UUID.randomUUID()), List.of(), "HIGH");

        assertThat(AnswerReplenishmentPolicy.hasEvidencedProcedure(request)).isTrue();
        assertThat(AnswerReplenishmentPolicy.directFallback(request))
                .get()
                .extracting(ModelDraft::shortVerdict, ModelDraft::citationIds)
                .containsExactly("若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。", List.of(procedureChunk));
        assertThat(AnswerReplenishmentPolicy.replaceMisdirectedDraft(request, misdirected))
                .extracting(ModelDraft::shortVerdict, ModelDraft::citationIds)
                .containsExactly("若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。", List.of(procedureChunk));
    }

    @Test
    void leavesAQuestionOutsideAnExhaustedSourceAreaUntouched() {
        UUID chunk = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "本轮抽牌量是多少？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        chunk,
                        "ROUND",
                        "Draw amount",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        3,
                        3)));
        ModelDraft original = new ModelDraft("抽两张。", "按本轮抽牌量抽取。", List.of(chunk), List.of(), "HIGH");

        assertThat(AnswerReplenishmentPolicy.directFallback(request)).isEmpty();
        assertThat(AnswerReplenishmentPolicy.replaceMisdirectedDraft(request, original)).isSameAs(original);
    }

    @Test
    void addsTheBoundedRecoveryTermsToTheSupplementaryLookup() {
        assertThat(AnswerReplenishmentPolicy.retrievalQuery("抽骰区耗尽后怎么办？"))
                .contains("耗尽", "回收", "洗混", "抽骰区耗尽后怎么办？");
    }

    private ModelRequest request(UUID chunkId, String excerpt) {
        return new ModelRequest(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(chunkId, "ROUND", "Draw recovery", excerpt, 3, 3)));
    }
}
