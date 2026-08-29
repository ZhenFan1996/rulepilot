package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.AnswerWarning.Type;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an optional answer Critic after an evidence-validated candidate is already readable.
 *
 * <p>Normal answers take the deterministic publication path without a paid semantic review. Evaluation mode may
 * surface defects for measurement, but it never splices a subset of newly generated prose fields into an already
 * published answer. An unavailable optional Critic cannot erase the validated candidate.</p>
 */
final class AnswerPostPublicationReviewer {

    private static final Logger log = LoggerFactory.getLogger(AnswerPostPublicationReviewer.class);

    private final GeneratedContentCritic critic;

    AnswerPostPublicationReviewer(GeneratedContentCritic critic) {
        this.critic = critic;
    }

    StructuredRuleAnswer review(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            ModelRequest modelRequest,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        try {
            ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(question, context, modelRequest, answer);
            Review review = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, question, context, modelRequest, answer, evidence),
                    risk,
                    username);
            if (!review.performed() || review.accepted()) return answer;
            log.info(
                    "Evaluation-only answer Critic reported {} issue(s) for run {}; preserving the complete validated answer",
                    review.issues().size(),
                    assistantRunId);
            return warned(answer, Type.REVIEW_UNRESOLVED);
        } catch (AgentExecutionStoppedException exception) {
            log.info(
                    "Optional answer Critic stopped for run {} at {}; preserving the complete validated answer",
                    assistantRunId,
                    exception.reason());
            return warned(answer, Type.REVIEW_UNRESOLVED);
        } catch (RuntimeException exception) {
            log.warn(
                    "Optional answer Critic failed for run {}: {} ({})",
                    assistantRunId,
                    exception.getMessage(),
                    exception.getClass().getSimpleName());
            return warned(answer, Type.REVIEW_UNRESOLVED);
        }
    }

    private StructuredRuleAnswer warned(StructuredRuleAnswer answer, Type warningType) {
        return AnswerOutcomePolicy.withWarnings(
                answer, List.of(new AnswerWarning(warningType)));
    }
}
