package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
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
            + "cannot support either a direct conclusion or a bounded conditional application. Preserve the kind of "
            + "answer the player requested: when they requested source-authored advice, mechanics that merely define "
            + "the objective, scoring, or legal actions do not become recommendations. Keep the abstention when no "
            + "supplied excerpt actually expresses advice, a priority, a caution, or a recommended choice.";
    private static final String ADVICE_EXISTENCE_FEEDBACK = "ADVICE_EXISTENCE_BOUNDARY: The application validated "
            + "this as a source-authored advice request. Do not silently upgrade a broad request asking whether any "
            + "strategy or advice exists into a request for a universal, exhaustive, optimal, guaranteed, or 'must-win' "
            + "strategy. If any supplied excerpt itself gives a bounded recommendation, caution, priority, preferred "
            + "choice, or tip, answer with that advice, cite the passage that states it, and preserve its faction, role, "
            + "player-count, matchup, phase, and situation scope. A passage that merely says another resource contains "
            + "tips is metadata, not the requested advice. Keep the abstention only when no supplied excerpt actually "
            + "expresses guidance. Include each named faction, role, map, mode, or other proper scope term exactly as "
            + "it appears in the source at least once; do not invent a translated replacement that could name a "
            + "different game object.";
    private AnswerEvidenceReconsiderationPolicy() {}

    static List<String> feedbackFor(ModelRequest request) {
        return request.evidenceNeeds().contains(EvidenceNeed.ADVICE)
                ? List.of(BASE_FEEDBACK, ADVICE_EXISTENCE_FEEDBACK)
                : List.of(BASE_FEEDBACK);
    }
}
