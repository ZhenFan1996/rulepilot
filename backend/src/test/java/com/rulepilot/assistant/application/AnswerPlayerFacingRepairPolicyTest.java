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

    private final UUID citationId = UUID.randomUUID();

    @Test
    void requestsOneBoundedRepairForInternalProtocolLeakage() {
        ModelDraft draft = draft(
                "Allowed according to evidence E1.",
                "Internal chunkId " + UUID.randomUUID() + " must not be shown.");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft))
                .containsExactly(
                        "PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                                + "and other internal references. Teach the same cited rule directly; preserve citationIds.");
    }

    @Test
    void leavesSemanticClaimsForTheEvidenceBoundCritic() {
        ModelDraft draft = draft(
                "The source does not define the term.",
                "This strategy is recommended only if the cited rule actually says so.");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft)).isEmpty();
    }

    @Test
    void doesNothingWithoutValidInputsOrLeakage() {
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(null, draft("Plain.", "Plain."))).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), null)).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft("Plain.", "Plain."))).isEmpty();
    }

    private ModelDraft draft(String verdict, String explanation) {
        return new ModelDraft(verdict, explanation, List.of(citationId), List.of(), "HIGH");
    }

    private ModelRequest request() {
        return new ModelRequest(
                "What is the rule?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "Evidence.", 1, 1)));
    }
}
