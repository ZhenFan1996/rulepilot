package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.AnswerWarning.Type;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the answer Critic after an evidence-validated candidate is already readable.
 *
 * <p>The reviewer may request at most one context/table-mode correction. It cannot search, alter the evidence
 * selection, or turn an insufficient answer into a new answer path; failed review stays a typed player-safe result
 * for the caller to publish instead of caching the rejected candidate.</p>
 */
final class AnswerPostPublicationReviewer {

    private static final Logger log = LoggerFactory.getLogger(AnswerPostPublicationReviewer.class);

    private final GeneratedContentCritic critic;
    private final AnswerModelGateway modelGateway;
    private final AnswerPublicationValidator publicationValidator;

    AnswerPostPublicationReviewer(
            GeneratedContentCritic critic,
            AnswerModelGateway modelGateway,
            AnswerPublicationValidator publicationValidator) {
        this.critic = critic;
        this.modelGateway = modelGateway;
        this.publicationValidator = publicationValidator;
    }

    Result review(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft draft,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        try {
            ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(question, context, answer);
            Review review = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, question, context, answer, evidence), risk);
            if (review.accepted()) return Result.accepted(answer);
            if (!AnswerCritiquePolicy.allowsBoundedCorrection(question, context)) {
                return unresolvedReview(answer, review, "事实一致性审查发现未修正的关键问题。");
            }
            StructuredRuleAnswer revised;
            try {
                revised = revise(
                        assistantRunId,
                        context.documentVersionId(),
                        username,
                        gameSessionId,
                        modelRequest,
                        draft,
                        review,
                        evidence);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException correctionFailure) {
                return hasMaterialDefect(review)
                        ? Result.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, "事实一致性审查发现未修正的关键问题。")
                        : Result.warned(answer, Type.REVIEW_UNRESOLVED);
            }
            Review revisionReview;
            try {
                revisionReview = critic.review(
                        AnswerCritiquePolicy.request(assistantRunId, question, context, revised, evidence),
                        ReviewRisk.HIGH_IMPACT);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException reviewFailure) {
                return Result.warned(revised, Type.REVIEW_UNAVAILABLE);
            }
            if (!revisionReview.accepted()) {
                return unresolvedReview(revised, revisionReview, "局部重讲仍未通过事实一致性审查。");
            }
            return Result.accepted(revised);
        } catch (AgentExecutionStoppedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "Adaptive answer validation failed for run {}: {} ({})",
                    assistantRunId,
                    exception.getMessage(),
                    exception.getClass().getSimpleName());
            return Result.warned(answer, Type.REVIEW_UNAVAILABLE);
        }
    }

    private Result unresolvedReview(StructuredRuleAnswer answer, Review review, String materialFailureMessage) {
        return hasMaterialDefect(review)
                ? Result.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, materialFailureMessage)
                : Result.warned(answer, Type.REVIEW_UNRESOLVED);
    }

    private boolean hasMaterialDefect(Review review) {
        return review.issues().stream().map(GeneratedContentCritic.Issue::type).anyMatch(type ->
                type == IssueType.UNSUPPORTED_CLAIM
                        || type == IssueType.CONTRADICTION
                        || type == IssueType.OVERREACH
                        || type == IssueType.MISSING_CRITICAL_RULE);
    }

    private StructuredRuleAnswer revise(
            UUID assistantRunId,
            UUID documentVersionId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            Review review,
            List<HybridEvidenceHit> evidence) {
        List<String> feedback = AnswerCritiquePolicy.revisionFeedback(review);
        ModelDraft revised = modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                feedback,
                "reviseLearningResponse",
                "Learning response revised from bounded critic feedback");
        if (revised == null || !revised.answerable()) {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    previousDraft,
                    completionRetryFeedback(feedback),
                    "retryEvidenceBackedAnswerRevision",
                    "Evidence-backed answer revision retried after an empty correction");
        }
        if (revised == null || !revised.answerable()) {
            throw new IllegalArgumentException("revised learning response is not answerable");
        }
        return publicationValidator.publish(
                documentVersionId, AnswerBasisPolicy.classify(modelRequest, revised), evidence);
    }

    /**
     * A confirmed critic defect means the first draft cannot be published, but it does not mean the retrieved rule
     * evidence disappeared. Give one high-risk correction a precise opportunity to narrow its claim instead of
     * turning a usable cited ruling into a player-visible generic failure.
     */
    private List<String> completionRetryFeedback(List<String> feedback) {
        List<String> retry = new ArrayList<>(feedback);
        retry.add("The supplied rulebook evidence remains available and a complete answer is required. Return "
                + "answerable=true with the narrowest cited ruling that resolves the player's condition. Preserve "
                + "every evidenced branch; do not replace a correctable answer with generic insufficiency.");
        return List.copyOf(retry);
    }

    record Result(StructuredRuleAnswer answer, AnswerStatus failureStatus, String failureMessage) {

        static Result accepted(StructuredRuleAnswer answer) {
            return new Result(answer, null, null);
        }

        static Result warned(StructuredRuleAnswer answer, Type warningType) {
            return accepted(AnswerOutcomePolicy.withWarnings(
                    answer, List.of(new AnswerWarning(warningType))));
        }

        static Result rejected(AnswerStatus failureStatus, String failureMessage) {
            return new Result(null, failureStatus, failureMessage);
        }

        boolean accepted() {
            return answer != null;
        }
    }
}
