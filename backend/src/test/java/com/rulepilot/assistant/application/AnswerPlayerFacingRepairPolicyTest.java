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

class AnswerPlayerFacingRepairPolicyTest {

    @Test
    void asksForAnExactComponentNameAndNoImprovisedGlyphAfterCrossPageMapping() {
        UUID operational = UUID.randomUUID();
        UUID legend = UUID.randomUUID();
        ModelRequest request = request(
                "What resource does this icon represent?",
                List.of(
                        new EvidenceInput(
                                operational,
                                "RULES",
                                "Cost",
                                "The operational icon is visually identical to the same icon labeled 'Energy token' on page 5.",
                                2,
                                2),
                        new EvidenceInput(legend, "COMPONENTS", "Legend", "Energy token is listed.", 5, 5)));
        ModelDraft draft = new ModelDraft("Pay 💎.", "Use the shown icon.", List.of(operational), List.of(), "HIGH");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request, draft))
                .anyMatch(item -> item.startsWith("RESOLVED_VISUAL_COMPONENT:"))
                .anyMatch(item -> item.startsWith("MAPPED_COMPONENT_GLYPH:"));
    }

    @Test
    void asksToRemoveInternalEvidenceLabelsWithoutChangingAValidCitation() {
        UUID chunk = UUID.randomUUID();
        ModelRequest request = request(
                "What happens next?",
                List.of(new EvidenceInput(chunk, "RULES", "Next", "After this action, continue the turn.", 3, 3)));
        ModelDraft draft = new ModelDraft("See [E12]: continue the turn.", "The rule says to continue.", List.of(chunk), List.of(), "HIGH");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request, draft))
                .anyMatch(item -> item.startsWith("PLAYER_FACING_OUTPUT:"));
    }

    @Test
    void leavesAPlainCitedAnswerAlone() {
        UUID chunk = UUID.randomUUID();
        ModelRequest request = request(
                "What happens next?",
                List.of(new EvidenceInput(chunk, "RULES", "Next", "After this action, continue the turn.", 3, 3)));
        ModelDraft draft = new ModelDraft("Continue the turn.", "After this action, continue the turn.", List.of(chunk), List.of(), "HIGH");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request, draft)).isEmpty();
    }

    private ModelRequest request(String question, List<EvidenceInput> evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                evidence);
    }
}
