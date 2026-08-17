package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftSafetyPolicyTest {

    @Test
    void detectsTheActiveEvidenceIdentityWithoutSilentlyDeletingPlayerProse() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "支付 🟢 [E1]",
                "参见 chunk " + chunkId + "（，）后执行。",
                List.of(chunkId),
                List.of("例外见 evidence E2。"),
                "HIGH",
                "DIRECT_RULE");

        assertThat(draft.shortVerdict()).isEqualTo("支付 🟢 [E1]");
        assertThat(draft.explanation()).contains("chunk", chunkId.toString(), "（，）");
        assertThat(draft.exceptions()).containsExactly("例外见 evidence E2。");
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft, List.of(chunkId))).isTrue();
    }

    @Test
    void detectsActiveEvidenceIdentityAcrossPlayerFacingFields() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft visible = new ModelDraft(
                true, null, "结论", "普通解释。", List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE");
        ModelDraft leaked = new ModelDraft(
                true, null, "结论", "Use source " + chunkId + ".", List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE");

        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(visible, List.of(chunkId))).isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(leaked, List.of(chunkId))).isTrue();
    }

    @Test
    void doesNotTreatOrdinaryRuleVocabularyAsAnInternalProtocolLeak() {
        String naturalRule = "Place one chunk in the model area; an invalid response loses one point.";

        assertThat(AnswerDraftSafetyPolicy.containsKnownEvidenceReference(naturalRule, List.of(UUID.randomUUID())))
                .isFalse();
    }
}
