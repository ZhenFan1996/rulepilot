package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRepairOutcomePolicyTest {

    private final UUID citationId = UUID.randomUUID();

    @Test
    void usesOneGenericMessageForARepairThatStillFailsPublication() {
        assertThat(AnswerRepairOutcomePolicy.insufficientRepairMessage(List.of("diagnostic")))
                .isEqualTo("回答修订后仍无法通过发布校验。");
    }

    @Test
    void blocksRemainingInternalEvidenceReferences() {
        var failure = AnswerRepairOutcomePolicy.publicationFailure(
                request(), draft("See evidence E1.", "Internal reference remains."));

        assertThat(failure).contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。"));
    }

    @Test
    void leavesUnquotedSemanticClaimsOutsideDeterministicStringHeuristics() {
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(
                request(), draft("Direct verdict.", "A possibly incorrect semantic claim.")))
                .isEmpty();
    }

    @Test
    void rejectsARepairThatStillOmitsTheSourceOfAnExplicitQuotation() {
        UUID quotedSourceId = UUID.randomUUID();
        String clause = "A player wins immediately after reaching thirty points.";
        ModelRequest request = new ModelRequest(
                "How does a player win?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(citationId, "RULE", "Overview", "Turns proceed clockwise.", 1, 1),
                        new EvidenceInput(quotedSourceId, "RULE", "Victory", clause, 2, 2)));
        ModelDraft draft = new ModelDraft(
                "Reach thirty points.",
                "The rule states: \u201c" + clause + "\u201d",
                List.of(citationId),
                List.of(),
                "HIGH");

        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, draft))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "回答中的直接引文没有归属到对应的规则证据。"));
    }

    private ModelDraft draft(String verdict, String explanation) {
        return new ModelDraft(verdict, explanation, List.of(citationId), List.of(), "HIGH");
    }

    private ModelRequest request() {
        return new ModelRequest(
                "Question", QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "Evidence.", 1, 1)));
    }
}
