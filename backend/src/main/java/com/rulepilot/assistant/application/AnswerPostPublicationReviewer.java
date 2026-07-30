package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
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
            ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(question, context, gameSessionId, answer);
            Review review = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, question, context, answer, evidence), risk);
            if (review.accepted()) return Result.accepted(answer);
            if (!AnswerCritiquePolicy.allowsBoundedCorrection(question, context, gameSessionId)) {
                return Result.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, "回答未通过事实一致性审查。");
            }
            StructuredRuleAnswer revised = revise(
                    assistantRunId,
                    context.documentVersionId(),
                    username,
                    gameSessionId,
                    modelRequest,
                    draft,
                    review,
                    evidence);
            Review revisionReview = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, question, context, revised, evidence),
                    ReviewRisk.HIGH_IMPACT);
            if (!revisionReview.accepted()) {
                return Result.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, "局部重讲仍未通过事实一致性审查。");
            }
            return Result.accepted(revised);
        } catch (RuleAnswerModelTimeoutException exception) {
            throw exception;
        } catch (AgentExecutionStoppedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "Adaptive answer validation failed for run {}: {} ({})",
                    assistantRunId,
                    exception.getMessage(),
                    exception.getClass().getSimpleName());
            return Result.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, "回答事实一致性审查失败。");
        }
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
            throw new IllegalArgumentException("revised learning response is not answerable");
        }
        return publicationValidator.publish(
                documentVersionId, AnswerBasisPolicy.classify(modelRequest, revised), evidence);
    }

    record Result(StructuredRuleAnswer answer, AnswerStatus failureStatus, String failureMessage) {

        static Result accepted(StructuredRuleAnswer answer) {
            return new Result(answer, null, null);
        }

        static Result rejected(AnswerStatus failureStatus, String failureMessage) {
            return new Result(null, failureStatus, failureMessage);
        }

        boolean accepted() {
            return answer != null;
        }
    }
}
