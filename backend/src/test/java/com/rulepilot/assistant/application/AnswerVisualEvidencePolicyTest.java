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

class AnswerVisualEvidencePolicyTest {

    @Test
    void resolvesTheExactComponentAndAddsItsReferencedLabelPage() {
        UUID operationalChunk = UUID.randomUUID();
        UUID referenceChunk = UUID.randomUUID();
        ModelRequest request = request(
                operationalChunk,
                referenceChunk,
                "The operational icon is visually identical to the same icon labeled 'Energy token' on page 5.",
                "Energy token is listed in the components legend.");
        ModelDraft unnamed = new ModelDraft(
                "Pay the required resource.",
                "Use the icon shown by the rule.",
                List.of(operationalChunk),
                List.of(),
                "HIGH");

        assertThat(AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)).isTrue();
        assertThat(AnswerVisualEvidencePolicy.requiresIdentityReconciliation(request, unnamed)).isFalse();
        assertThat(AnswerVisualEvidencePolicy.resolvedComponents(request, unnamed)).containsExactly("Energy token");
        assertThat(AnswerVisualEvidencePolicy.namesEveryResolvedComponent(request, unnamed)).isFalse();
        assertThat(AnswerVisualEvidencePolicy.includeReferenceCitations(request, unnamed).citationIds())
                .containsExactly(operationalChunk, referenceChunk);
    }

    @Test
    void keepsAnUnresolvedVisualFactBlockedUntilAnExplicitMappingExists() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "What does this icon mean?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        chunkId,
                        "RULES",
                        "Visual facts",
                        "Visual page facts: the green icon indicates an unspecified resource.",
                        2,
                        2)));
        ModelDraft draft = new ModelDraft(
                "Pay the icon.", "The icon is used for payment.", List.of(chunkId), List.of(), "LOW");

        assertThat(AnswerVisualEvidencePolicy.requiresIdentityReconciliation(request, draft)).isTrue();
        assertThat(AnswerVisualEvidencePolicy.resolvedComponents(request, draft)).isEmpty();
    }

    private ModelRequest request(
            UUID operationalChunk, UUID referenceChunk, String operationalExcerpt, String referenceExcerpt) {
        return new ModelRequest(
                "What resource does this icon represent?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(
                        new EvidenceInput(operationalChunk, "RULES", "Operation", operationalExcerpt, 2, 2),
                        new EvidenceInput(referenceChunk, "COMPONENTS", "Legend", referenceExcerpt, 5, 5)));
    }
}
