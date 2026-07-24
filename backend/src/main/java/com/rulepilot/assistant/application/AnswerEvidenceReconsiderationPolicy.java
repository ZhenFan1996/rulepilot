package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;

/** Bounded feedback for a model that abstained despite potentially answerable cited evidence. */
final class AnswerEvidenceReconsiderationPolicy {

    private static final String BASE_FEEDBACK = "EVIDENCE_SUFFICIENCY: Re-evaluate the question against every supplied excerpt. The "
            + "condition written into a player's question is available table context: when an excerpt states "
            + "the outcome for that exact condition, answer the rule directly instead of treating unrelated "
            + "live-state details as missing. This includes a named replenishment condition, a stated end trigger, "
            + "and an explicitly described tie state. If the evidence gives a prerequisite or conditional branch "
            + "but the current table state is otherwise unknown, answer conditionally instead of assuming the "
            + "condition or refusing. Preserve relative rules, scope, timing, negation, and exceptions exactly. "
            + "When two or more cited premises jointly determine the result for the exact table condition the player "
            + "stated, provide a bounded grounded application: identify the condition, apply only those premises, "
            + "and label any still-unknown branch instead of refusing. Remain unanswerable only when the excerpts "
            + "cannot support either a direct conclusion or a bounded conditional application.";
    private static final String DIRECT_REPLENISHMENT_FEEDBACK = " DIRECT_REPLENISHMENT_PROCEDURE: A supplied excerpt explicitly gives the sequence for "
            + "continuing when the named draw or supply area becomes empty. Apply that stated sequence to "
            + "a question about reaching the required draw amount, and cite its source. Do not abstain merely "
            + "because the player did not state how many items were present before that area became empty.";

    private AnswerEvidenceReconsiderationPolicy() {}

    static List<String> feedbackFor(ModelRequest request) {
        String feedback = AnswerReplenishmentPolicy.hasEvidencedProcedure(request)
                ? BASE_FEEDBACK + DIRECT_REPLENISHMENT_FEEDBACK
                : BASE_FEEDBACK;
        return List.of(feedback);
    }
}
