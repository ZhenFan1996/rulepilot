package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;

/** Bounded feedback for a model that abstained despite potentially answerable cited evidence. */
final class AnswerEvidenceReconsiderationPolicy {

    private static final String BASE_FEEDBACK = "EVIDENCE_SUFFICIENCY: Re-evaluate the question against every supplied excerpt. The "
            + "condition written into a player's question is available table context: when an excerpt states "
            + "the outcome for that exact condition, answer the rule directly instead of treating unrelated "
            + "live-state details as missing. If the evidence gives a prerequisite or conditional branch but the "
            + "current table state is otherwise unknown, answer conditionally instead of assuming the condition or "
            + "refusing. Preserve relative rules, scope, timing, negation, and exceptions exactly. "
            + "When two or more cited premises jointly determine the result for the exact table condition the player "
            + "stated, provide a bounded grounded application: identify the condition, apply only those premises, "
            + "and label any still-unknown branch instead of refusing. Remain unanswerable only when the excerpts "
            + "cannot support either a direct conclusion or a bounded conditional application.";
    private AnswerEvidenceReconsiderationPolicy() {}

    static List<String> feedbackFor(ModelRequest request) {
        return List.of(BASE_FEEDBACK);
    }
}
