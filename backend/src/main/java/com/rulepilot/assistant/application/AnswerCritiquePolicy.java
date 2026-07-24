package com.rulepilot.assistant.application;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure preparation for a bounded answer critique; retrieval and model calls stay in the answer workflow. */
final class AnswerCritiquePolicy {

    private AnswerCritiquePolicy() {}

    static ReviewRisk reviewRisk(QuestionContext context, UUID gameSessionId, StructuredRuleAnswer answer) {
        if (gameSessionId != null || context.previousQuestion() != null || context.learningIntent() != null) {
            return ReviewRisk.HIGH_IMPACT;
        }
        return answer.confidence() == AnswerConfidence.LOW ? ReviewRisk.LOW_CONFIDENCE : ReviewRisk.STANDARD;
    }

    static ReviewRequest request(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        List<UUID> citationIds = answer.citations().stream().map(RuleCitation::chunkId).toList();
        List<Claim> claims = new ArrayList<>();
        claims.add(new Claim(1, answer.shortVerdict() + "\n" + answer.explanation(), citationIds));
        for (int index = 0; index < answer.exceptions().size(); index++) {
            claims.add(new Claim(index + 2, answer.exceptions().get(index), citationIds));
        }
        return new ReviewRequest(
                assistantRunId,
                ContentType.ANSWER,
                new TaskContext(
                        "Answer the user's normalized rule question: " + question.normalizedQuestion()
                                + "; previous question for reference resolution only: "
                                + contextValue(context.previousQuestion()),
                        "Give a supported verdict and explanation for question type " + question.type()
                                + "; answer basis " + (answer.answerBasis() == null
                                        ? "not applicable"
                                        : answer.answerBasis().name())
                                + "; preserve material exceptions for lesson section "
                                + contextValue(context.currentLessonSection()) + ", game phase "
                                + contextValue(context.gamePhase()) + ", and player count "
                                + contextValue(context.playerCount())
                                + ", and learning intent " + contextValue(context.learningIntent())
                                + ". Preserve every named eligibility and identity condition. Reject any claim that a "
                                + "condition is irrelevant, optional, or broader than stated unless evidence explicitly "
                                + "says so. For an 'again' follow-up, reject any repeatability claim not explicitly "
                                + "supported by evidence. A GROUNDED_APPLICATION may combine cited premises only to "
                                + "apply the player's explicitly stated table condition; reject it if it invents a game "
                                + "fact or silently assumes a missing branch."),
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
    }

    static List<String> revisionFeedback(Review review) {
        return review.issues().stream()
                .map(issue -> issue.type().name() + ": " + issue.summary())
                .toList();
    }

    private static String contextValue(Object value) {
        return value == null ? "not provided" : value.toString();
    }
}
