package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.AnswerWarning.Type;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an optional answer Critic after an evidence-validated candidate is already readable.
 *
 * <p>Normal answers take the deterministic publication path without a paid semantic review. Evaluation mode may
 * still surface one concrete defect and request one bounded correction. The reviewer cannot search or alter the
 * evidence selection, and an unavailable optional Critic cannot erase the already validated candidate.</p>
 */
final class AnswerPostPublicationReviewer {

    private static final Logger log = LoggerFactory.getLogger(AnswerPostPublicationReviewer.class);

    private final GeneratedContentCritic critic;
    private final AnswerModelGateway modelGateway;
    private final AnswerPublicationValidator publicationValidator;
    private final AnswerSourceEvidenceResolver sourceEvidenceResolver = new AnswerSourceEvidenceResolver();
    private final AnswerPermissionResolver permissionResolver = new AnswerPermissionResolver();

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
        return review(
                assistantRunId,
                question,
                context,
                username,
                gameSessionId,
                modelRequest,
                draft,
                answer,
                evidence,
                true);
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
            List<HybridEvidenceHit> evidence,
            boolean correctionAllowed) {
        try {
            ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(question, context, modelRequest, answer);
            Review review = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, question, context, modelRequest, answer, evidence),
                    risk,
                    username);
            if (review.accepted()) return Result.accepted(answer);
            if (!correctionAllowed || !AnswerCritiquePolicy.allowsBoundedCorrection(question, context)) {
                return unresolvedReview(answer, review, context);
            }
            Set<PlayerFacingField> editableFields = AnswerCritiquePolicy.editablePlayerFacingFields(answer, review);
            if (editableFields.isEmpty()) {
                return Result.warned(withoutStructuredDetails(answer), Type.REVIEW_UNRESOLVED);
            }
            boolean isolateStructuredDetails = AnswerCritiquePolicy.hasStructuredClaimIssues(answer, review);
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
                        editableFields,
                        answer,
                        evidence);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException correctionFailure) {
                return hasMaterialDefect(review)
                        ? unsupportedReview(context)
                        : Result.warned(answer, Type.REVIEW_UNRESOLVED);
            }
            // The correction has already passed the same schema, citation ownership/version, and structured-aid
            // gates as the original candidate. A second semantic review used to repeat the complete Critic flow
            // (including atomic confirmation) without adding a new evidence boundary.
            return isolateStructuredDetails
                    ? Result.warned(withoutStructuredDetails(revised), Type.REVIEW_UNRESOLVED)
                    : Result.accepted(revised);
        } catch (AgentExecutionStoppedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "Adaptive answer validation failed for run {}: {} ({})",
                    assistantRunId,
                    exception.getMessage(),
                    exception.getClass().getSimpleName());
            return Result.warned(answer, Type.REVIEW_UNRESOLVED);
        }
    }

    private Result unresolvedReview(
            StructuredRuleAnswer answer, Review review, QuestionContext context) {
        return hasMaterialDefect(review)
                ? unsupportedReview(context)
                : Result.warned(answer, Type.REVIEW_UNRESOLVED);
    }

    private Result unsupportedReview(QuestionContext context) {
        boolean english = context != null && context.outputLanguage() == com.rulepilot.assistant.PlayerLocale.EN;
        return Result.rejected(
                AnswerStatus.INSUFFICIENT_EVIDENCE,
                english
                        ? "Candidate rule pages were found, but the generated conclusion could not be verified by "
                                + "its own citations. Open the cited pages or ask again with the exact rulebook object name."
                        : "已找到候选规则页，但生成结论无法由自己的引用核对；请打开来源页核对，或用规则书中的准确对象名称重新提问。");
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
            Set<PlayerFacingField> editableFields,
            StructuredRuleAnswer previousAnswer,
            List<HybridEvidenceHit> evidence) {
        List<String> feedback = AnswerCritiquePolicy.playerFacingRevisionFeedback(previousAnswer, review);
        ModelDraft revised = modelGateway.revisePlayerFacing(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                feedback,
                editableFields,
                "reviseLearningResponse",
                "Learning response revised from bounded critic feedback");
        if (revised == null || !revised.answerable() || revised.equals(previousDraft)) {
            throw new IllegalArgumentException("revised learning response is not answerable");
        }
        ModelDraft classified = AnswerBasisPolicy.classify(modelRequest, revised);
        classified = AnswerDraftSafetyPolicy.normalizeSourceAbsenceClaims(modelRequest, classified);
        verifyPermissionRuling(modelRequest, classified);
        verifySourceEvidence(modelRequest, classified);
        return publicationValidator.publish(
                documentVersionId,
                classified,
                evidence,
                previousAnswer.calculations(),
                previousAnswer.situationChecks(),
                previousAnswer.walkthroughSteps(),
                previousAnswer.decisionBranches(),
                previousAnswer.exceptionClauses(),
                previousAnswer.termDefinitions(),
                previousAnswer.workedExamples(),
                previousAnswer.priorityResolutions(),
                previousAnswer.timingResolutions(),
                previousAnswer.tieResolutions(),
                previousAnswer.scopeResolutions(),
                previousAnswer.conceptComparisons(),
                previousAnswer.ruleOptions());
    }

    private void verifySourceEvidence(ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerSourceEvidenceResolver.requiresSourceEvidence(modelRequest)) return;
        sourceEvidenceResolver.resolve(modelRequest, draft);
    }

    private void verifyPermissionRuling(ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerPermissionResolver.requiresPermission(modelRequest)) return;
        permissionResolver.resolve(modelRequest, draft);
    }

    private StructuredRuleAnswer withoutStructuredDetails(StructuredRuleAnswer answer) {
        return new StructuredRuleAnswer(
                answer.documentVersionId(),
                answer.status(),
                answer.shortVerdict(),
                answer.explanation(),
                answer.citations(),
                answer.exceptions(),
                answer.confidence(),
                answer.answerBasis(),
                answer.official(),
                answer.confirmedRulingId(),
                answer.confirmedRulingVersion(),
                answer.clarification(),
                answer.warnings(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
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
