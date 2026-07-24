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

    @Test
    void asksForTheDirectCollisionRuleWhenTheDraftCitesOnlySetupEvidence() {
        UUID setup = UUID.randomUUID();
        UUID collision = UUID.randomUUID();
        ModelRequest request = request(
                "两位玩家出了相同数字时，该怎么处理？",
                List.of(
                        new EvidenceInput(setup, "SETUP", "Setup", "Each player receives numbered cards.", 4, 4),
                        new EvidenceInput(
                                collision,
                                "ROUND_STRUCTURE",
                                "Resolving a bump",
                                "Players who played the same number resolve the bump in priority order.",
                                10,
                                10)));
        ModelDraft draft = new ModelDraft(
                "无法确定。", "组件说明没有给出同数字的处理。", List.of(setup), List.of(), "LOW");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request, draft))
                .anyMatch(item -> item.startsWith("MATCHING_VALUE_RESOLUTION_CITATION:"));
    }

    private ModelRequest request(String question, List<EvidenceInput> evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                evidence);
    }
}
