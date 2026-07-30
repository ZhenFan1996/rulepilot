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
import java.util.regex.Pattern;

/** Pure preparation for a bounded answer critique; retrieval and model calls stay in the answer workflow. */
final class AnswerCritiquePolicy {

    private static final Pattern MATERIAL_CONDITION = Pattern.compile(
            "(?iu)\\b(?:if|when|after|before|unless|whether|then|once)\\b|"
                    + "如果|若|当|除非|否则|之后|以后|之前|以前|怎么办|如何处理|是否|能否|不能|必须");

    private AnswerCritiquePolicy() {}

    static ReviewRisk reviewRisk(
            UnderstoodQuestion question,
            QuestionContext context,
            UUID gameSessionId,
            StructuredRuleAnswer answer) {
        if (allowsBoundedCorrection(question, context, gameSessionId)) {
            return ReviewRisk.HIGH_IMPACT;
        }
        return answer.confidence() == AnswerConfidence.LOW ? ReviewRisk.LOW_CONFIDENCE : ReviewRisk.STANDARD;
    }

    static boolean allowsBoundedCorrection(
            UnderstoodQuestion question, QuestionContext context, UUID gameSessionId) {
        return gameSessionId != null
                || context.previousQuestion() != null
                || context.learningIntent() != null
                || requiresDirectFactualReview(question);
    }

    /**
     * A conditional table ruling has an observable branch and an immediate game consequence. It is cheap to identify
     * before composition, but expensive for a player when a fluent answer silently turns the fallback branch into a
     * no-op. Keep ordinary definition questions fast while reviewing these decision points before publication.
     */
    private static boolean requiresDirectFactualReview(UnderstoodQuestion question) {
        return question.type() == com.rulepilot.assistant.domain.QuestionType.SITUATION_QUERY
                || MATERIAL_CONDITION.matcher(question.normalizedQuestion()).find();
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
