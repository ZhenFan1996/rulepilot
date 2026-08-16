package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
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
import java.util.List;
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
    private final AnswerCalculationResolver calculationResolver;
    private final AnswerSituationCheckResolver situationCheckResolver;
    private final AnswerWalkthroughResolver walkthroughResolver = new AnswerWalkthroughResolver();
    private final AnswerDecisionTableResolver decisionTableResolver = new AnswerDecisionTableResolver();
    private final AnswerExceptionClauseResolver exceptionClauseResolver = new AnswerExceptionClauseResolver();
    private final AnswerTermDefinitionResolver termDefinitionResolver = new AnswerTermDefinitionResolver();
    private final AnswerWorkedExampleResolver workedExampleResolver = new AnswerWorkedExampleResolver();
    private final AnswerRulePriorityResolver rulePriorityResolver = new AnswerRulePriorityResolver();
    private final AnswerTimingResolver timingResolver = new AnswerTimingResolver();
    private final AnswerTieResolver tieResolver = new AnswerTieResolver();
    private final AnswerScopeResolver scopeResolver = new AnswerScopeResolver();
    private final AnswerConceptComparisonResolver conceptComparisonResolver = new AnswerConceptComparisonResolver();
    private final AnswerRuleOptionResolver ruleOptionResolver = new AnswerRuleOptionResolver();
    private final AnswerSourceEvidenceResolver sourceEvidenceResolver = new AnswerSourceEvidenceResolver();
    private final AnswerPermissionResolver permissionResolver = new AnswerPermissionResolver();
    private final AuditedAgentInvocations invocations;

    AnswerPostPublicationReviewer(
            GeneratedContentCritic critic,
            AnswerModelGateway modelGateway,
            AnswerPublicationValidator publicationValidator) {
        this(
                critic,
                modelGateway,
                publicationValidator,
                new AnswerCalculationResolver(),
                new AnswerSituationCheckResolver(),
                null);
    }

    AnswerPostPublicationReviewer(
            GeneratedContentCritic critic,
            AnswerModelGateway modelGateway,
            AnswerPublicationValidator publicationValidator,
            AnswerCalculationResolver calculationResolver,
            AuditedAgentInvocations invocations) {
        this(
                critic,
                modelGateway,
                publicationValidator,
                calculationResolver,
                new AnswerSituationCheckResolver(),
                invocations);
    }

    AnswerPostPublicationReviewer(
            GeneratedContentCritic critic,
            AnswerModelGateway modelGateway,
            AnswerPublicationValidator publicationValidator,
            AnswerCalculationResolver calculationResolver,
            AnswerSituationCheckResolver situationCheckResolver,
            AuditedAgentInvocations invocations) {
        this.critic = critic;
        this.modelGateway = modelGateway;
        this.publicationValidator = publicationValidator;
        this.calculationResolver = calculationResolver;
        this.situationCheckResolver = situationCheckResolver;
        this.invocations = invocations;
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
                        ? unsupportedReview(context)
                        : Result.warned(answer, Type.REVIEW_UNRESOLVED);
            }
            // The correction has already passed the same schema, citation ownership/version, and structured-aid
            // gates as the original candidate. A second semantic review used to repeat the complete Critic flow
            // (including atomic confirmation) without adding a new evidence boundary.
            return Result.accepted(revised);
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
        if (revised == null || !revised.answerable() || revised.equals(previousDraft)) {
            throw new IllegalArgumentException("revised learning response is not answerable");
        }
        ModelDraft classified = AnswerBasisPolicy.classify(modelRequest, revised);
        classified = AnswerDraftSafetyPolicy.normalizeSourceAbsenceClaims(modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleCalculation> calculations = resolveCalculations(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleSituationCheck> situationChecks = resolveSituationChecks(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleWalkthroughStep> walkthroughSteps = resolveWalkthrough(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleDecisionBranch> decisionBranches = resolveDecisionTable(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleExceptionClause> exceptionClauses = resolveExceptionClauses(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleTermDefinition> termDefinitions = resolveTermDefinitions(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleWorkedExample> workedExamples = resolveWorkedExamples(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RulePriorityResolution> priorityResolutions = resolveRulePriority(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleTimingResolution> timingResolutions = resolveTiming(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleTieResolution> tieResolutions = resolveTies(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleScopeResolution> scopeResolutions = resolveScope(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleConceptComparison> conceptComparisons = resolveConceptComparisons(
                assistantRunId, modelRequest, classified);
        List<com.rulepilot.assistant.domain.RuleOption> ruleOptions = resolveRuleOptions(
                assistantRunId, modelRequest, classified);
        verifyPermissionRuling(assistantRunId, modelRequest, classified);
        verifySourceEvidence(assistantRunId, modelRequest, classified);
        return publicationValidator.publish(
                documentVersionId, classified, evidence, calculations, situationChecks, walkthroughSteps,
                decisionBranches, exceptionClauses, termDefinitions, workedExamples, priorityResolutions,
                timingResolutions, tieResolutions, scopeResolutions, conceptComparisons, ruleOptions);
    }

    private List<com.rulepilot.assistant.domain.RuleCalculation> resolveCalculations(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.calculations().isEmpty() && !calculationResolver.requiresCalculation(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return calculationResolver.resolve(modelRequest, draft);
        String expressions = draft.calculations().stream()
                .map(calculation -> calculation == null ? "" : calculation.expression())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "calculateRuleMath",
                Math.max(1, (expressions.length() + 3) / 4),
                "Revised grounded rule arithmetic calculated",
                () -> calculationResolver.resolve(modelRequest, draft),
                results -> results.size() * 8);
    }

    private List<com.rulepilot.assistant.domain.RuleSituationCheck> resolveSituationChecks(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        return situationCheckResolver.resolve(modelRequest, draft);
    }

    private List<com.rulepilot.assistant.domain.RuleWalkthroughStep> resolveWalkthrough(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.walkthroughSteps().isEmpty() && !walkthroughResolver.requiresWalkthrough(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return walkthroughResolver.resolve(modelRequest, draft);
        String steps = draft.walkthroughSteps().stream()
                .map(step -> step == null ? "" : step.orderBasis() + " " + step.instruction())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleWalkthrough",
                Math.max(1, (steps.length() + 3) / 4),
                "Revised walkthrough schema and evidence scope validated",
                () -> walkthroughResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<com.rulepilot.assistant.domain.RuleDecisionBranch> resolveDecisionTable(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.decisionBranches().isEmpty() && !decisionTableResolver.requiresDecisionTable(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return decisionTableResolver.resolve(modelRequest, draft);
        String branches = draft.decisionBranches().stream()
                .map(branch -> branch == null ? "" : branch.basis() + " " + branch.condition())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleDecisionTable",
                Math.max(1, (branches.length() + 3) / 4),
                "Revised cited rule decision table validated",
                () -> decisionTableResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<com.rulepilot.assistant.domain.RuleExceptionClause> resolveExceptionClauses(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.exceptionClauses().isEmpty() && !exceptionClauseResolver.requiresExceptionClauses(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return exceptionClauseResolver.resolve(modelRequest, draft);
        String clauses = draft.exceptionClauses().stream()
                .map(clause -> clause == null ? "" : clause.condition() + " " + clause.effect())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleExceptionList",
                Math.max(1, (clauses.length() + 3) / 4),
                "Revised cited rule exceptions and restrictions validated",
                () -> exceptionClauseResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<com.rulepilot.assistant.domain.RuleTermDefinition> resolveTermDefinitions(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.termDefinitions().isEmpty() && !termDefinitionResolver.requiresTermDefinitions(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return termDefinitionResolver.resolve(modelRequest, draft);
        String definitions = draft.termDefinitions().stream()
                .map(definition -> definition == null ? "" : definition.term() + " " + definition.definition())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "defineRuleTerms",
                Math.max(1, (definitions.length() + 3) / 4),
                "Revised cited rulebook term definitions validated",
                () -> termDefinitionResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<com.rulepilot.assistant.domain.RuleWorkedExample> resolveWorkedExamples(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.workedExamples().isEmpty() && !workedExampleResolver.requiresWorkedExamples(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return workedExampleResolver.resolve(modelRequest, draft);
        String examples = draft.workedExamples().stream()
                .map(example -> example == null ? "" : example.setup() + " " + example.action() + " " + example.outcome())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "illustrateRule",
                Math.max(1, (examples.length() + 3) / 4),
                "Revised cited rule worked examples validated",
                () -> workedExampleResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RulePriorityResolution> resolveRulePriority(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.priorityResolutions().isEmpty() && !rulePriorityResolver.requiresRulePriority(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return rulePriorityResolver.resolve(modelRequest, draft);
        String resolutions = draft.priorityResolutions().stream()
                .map(item -> item == null ? "" : item.baseRule() + " " + item.competingRule() + " " + item.resolution())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRulePriority",
                Math.max(1, (resolutions.length() + 3) / 4),
                "Revised rule-priority schema and evidence scope validated",
                () -> rulePriorityResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private void verifySourceEvidence(UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerSourceEvidenceResolver.requiresSourceEvidence(modelRequest)) return;
        if (invocations == null) {
            sourceEvidenceResolver.resolve(modelRequest, draft);
            return;
        }
        invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "showRuleEvidence",
                Math.max(1, (draft.shortVerdict().length() + draft.explanation().length() + 3) / 4),
                "Revised direct rulebook excerpt and explanation validated",
                () -> sourceEvidenceResolver.resolve(modelRequest, draft),
                results -> results.size() * 8);
    }

    private void verifyPermissionRuling(UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerPermissionResolver.requiresPermission(modelRequest)) return;
        if (invocations == null) {
            permissionResolver.resolve(modelRequest, draft);
            return;
        }
        invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "checkRulePermission",
                Math.max(1, (draft.shortVerdict().length() + draft.explanation().length() + 3) / 4),
                "Revised cited permission or prohibition direction validated",
                () -> permissionResolver.resolve(modelRequest, draft),
                results -> results.size() * 8);
    }

    private List<com.rulepilot.assistant.domain.RuleTimingResolution> resolveTiming(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.timingResolutions().isEmpty() && !timingResolver.requiresTiming(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return timingResolver.resolve(modelRequest, draft);
        String resolutions = draft.timingResolutions().stream()
                .map(item -> item == null ? "" : item.timingContext() + " " + item.resolutionOrder() + " "
                        + item.orderSource())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleTiming",
                Math.max(1, (resolutions.length() + 3) / 4),
                "Revised cited simultaneous-effect ordering validated",
                () -> timingResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleTieResolution> resolveTies(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.tieResolutions().isEmpty() && !tieResolver.requiresTie(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return tieResolver.resolve(modelRequest, draft);
        String resolutions = draft.tieResolutions().stream()
                .map(item -> item == null ? "" : item.tieContext() + " "
                        + String.join(" ", item.resolutionSteps()) + " " + item.finalOutcome())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleTie",
                Math.max(1, (resolutions.length() + 3) / 4),
                "Revised cited tie-resolution ladder validated",
                () -> tieResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleScopeResolution> resolveScope(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.scopeResolutions().isEmpty() && !scopeResolver.requiresScope(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return scopeResolver.resolve(modelRequest, draft);
        String resolutions = draft.scopeResolutions().stream()
                .map(item -> item == null ? "" : item.ruleContext() + " " + item.governingCondition() + " "
                        + item.currentSituation() + " " + item.effect())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleScope",
                Math.max(1, (resolutions.length() + 3) / 4),
                "Revised cited rule applicability validated",
                () -> scopeResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleConceptComparison> resolveConceptComparisons(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.conceptComparisons().isEmpty()
                && !conceptComparisonResolver.requiresConceptComparison(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return conceptComparisonResolver.resolve(modelRequest, draft);
        String comparisons = draft.conceptComparisons().stream()
                .map(item -> item == null ? "" : item.leftConcept() + " " + item.rightConcept() + " "
                        + item.keyDifference() + " " + item.practicalBoundary())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "compareRuleConcepts",
                Math.max(1, (comparisons.length() + 3) / 4),
                "Revised cited rule concept distinction validated",
                () -> conceptComparisonResolver.resolve(modelRequest, draft),
                results -> results.size() * 24);
    }

    private List<com.rulepilot.assistant.domain.RuleOption> resolveRuleOptions(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.ruleOptions().isEmpty() && !ruleOptionResolver.requiresRuleOptions(modelRequest)) {
            return List.of();
        }
        if (invocations == null) return ruleOptionResolver.resolve(modelRequest, draft);
        String options = draft.ruleOptions().stream()
                .map(item -> item == null ? "" : item.optionName() + " " + item.availabilityCondition() + " "
                        + item.result())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "listRuleOptions",
                Math.max(1, (options.length() + 3) / 4),
                "Revised complete cited rule option list validated",
                () -> ruleOptionResolver.resolve(modelRequest, draft),
                results -> results.size() * 18);
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
