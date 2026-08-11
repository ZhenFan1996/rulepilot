package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftPublicationPolicyTest {

    private final UUID citationId = UUID.randomUUID();

    @Test
    void preparesOnlyMechanicalPlayerFacingCleanup() {
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "Allowed（，见证据 E1）。",
                "The governing rule is cited by [E1].",
                List.of(citationId),
                List.of("Keep the semantic exception."),
                "HIGH",
                "model prose");

        var preparation = AnswerDraftPublicationPolicy.prepare(request(), draft);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.warnings()).isEmpty();
        assertThat(preparation.draft().answerBasis()).isEqualTo("DIRECT_RULE");
        assertThat(preparation.draft().shortVerdict()).doesNotContain("E1", "（，");
        assertThat(preparation.draft().explanation()).doesNotContain("[E1]");
        assertThat(preparation.draft().exceptions()).containsExactly("Keep the semantic exception.");
    }

    @Test
    void assignsGroundedApplicationOnlyWhenAValidatedCalculationIsPresent() {
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "10 points.",
                "The application recomputes the total.",
                List.of(citationId),
                List.of(),
                "HIGH",
                "DIRECT_RULE",
                List.of(new CalculationRequest("floor(8 / 3) * 5")));

        assertThat(AnswerDraftPublicationPolicy.prepare(request(), draft).draft().answerBasis())
                .isEqualTo("GROUNDED_APPLICATION");
    }

    @Test
    void compatibilityCitationHookDoesNotSemanticallyRewriteTheDraft() {
        ModelDraft draft = new ModelDraft(
                "The game ends at the cited boundary.",
                "A nearby scoring rule remains part of the model draft.",
                List.of(citationId),
                List.of(),
                "HIGH");

        assertThat(AnswerDraftPublicationPolicy.removePeripheralEndgameCitations(request(), draft))
                .isSameAs(draft);
    }

    private ModelRequest request() {
        return new ModelRequest(
                "What is the rule?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "Direct evidence.", 1, 1)));
    }
}
