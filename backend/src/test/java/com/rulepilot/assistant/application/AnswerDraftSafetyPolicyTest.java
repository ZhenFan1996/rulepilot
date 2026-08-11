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

class AnswerDraftSafetyPolicyTest {

    @Test
    void removesOnlyInternalProtocolReferencesAndDanglingPunctuation() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "支付 🟢 [E1]",
                "参见 chunk 48a31827-0ebf-42f2-8b9f-8a33c842e15e（，）后执行。",
                List.of(chunkId),
                List.of("例外见 evidence E2。"),
                "HIGH",
                "DIRECT_RULE");

        ModelDraft normalized = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(draft);
        normalized = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(normalized);

        assertThat(normalized.shortVerdict()).isEqualTo("支付 🟢");
        assertThat(normalized.explanation()).doesNotContain("chunk", "48a31827", "（，）");
        assertThat(normalized.exceptions()).containsExactly("例外见。 ".strip());
        assertThat(normalized.shortVerdict()).contains("🟢");
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(normalized)).isFalse();
    }

    @Test
    void leavesSemanticSourceClaimsUntouchedForTheEvidenceCriticToReview() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "Can I use this card?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        chunkId, "RULE", "Permission", "You may use this card.", 4, 4)));
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "Yes.",
                "The rule grants permission without any additional condition.",
                List.of(chunkId),
                List.of(),
                "HIGH",
                "DIRECT_RULE");

        assertThat(AnswerDraftSafetyPolicy.normalizeSourceAbsenceClaims(request, draft)).isSameAs(draft);
    }

    @Test
    void detectsInternalReferencesAcrossPlayerFacingFields() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft visible = new ModelDraft(
                true, null, "结论", "普通解释。", List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE");
        ModelDraft leaked = new ModelDraft(
                true, null, "结论", "Use source [E3].", List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE");

        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(visible)).isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(leaked)).isTrue();
    }
}
