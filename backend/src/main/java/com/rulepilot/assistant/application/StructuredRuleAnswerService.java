package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.AnswerWarning.Type;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import com.rulepilot.assistant.PlayerLocale;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class StructuredRuleAnswerService implements RuleAnswering {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerService.class);
    // Context-resolved questions use a new semantic identity, so earlier answer-cache entries are stale.
    private static final String ANSWER_POLICY_VERSION = "answer-v113-player-facing-progression";
    private final QuestionUnderstanding understanding;
    private final AnswerModelGateway modelGateway;
    private final AnswerQuestionInterpretationPolicy questionInterpretation;
    private final AnswerEvidenceRetriever evidenceRetriever;
    private final AnswerEvidenceRefiner evidenceRefiner;
    private final AnswerModelRequestFactory modelRequestFactory;
    private final RuleAnswerCache cache;
    private final RuleAnswerRateLimiter rateLimiter;
    private final RuleDataVersion ruleDataVersion;
    private final ConfirmedRulingLookup confirmedRulings;
    private final AnswerPublicationValidator publicationValidator;
    private final AnswerEvidenceAdmissionGate evidenceAdmissionGate;
    private final AnswerDraftComposer draftComposer;
    private final AnswerCalculationResolver calculationResolver;
    private final AnswerSituationCheckResolver situationCheckResolver;
    private final AnswerWalkthroughResolver walkthroughResolver;
    private final AnswerDecisionTableResolver decisionTableResolver;
    private final AnswerExceptionClauseResolver exceptionClauseResolver;
    private final AnswerTermDefinitionResolver termDefinitionResolver;
    private final AnswerWorkedExampleResolver workedExampleResolver;
    private final AnswerRulePriorityResolver rulePriorityResolver;
    private final AnswerTimingResolver timingResolver;
    private final AnswerTieResolver tieResolver;
    private final AnswerScopeResolver scopeResolver;
    private final AnswerConceptComparisonResolver conceptComparisonResolver;
    private final AnswerRuleOptionResolver ruleOptionResolver;
    private final AnswerSourceEvidenceResolver sourceEvidenceResolver;
    private final AnswerPermissionResolver permissionResolver;
    private final AnswerPostPublicationReviewer postPublicationReviewer;
    private final AnswerRunLifecycle runLifecycle;
    private final AuditedAgentInvocations invocations;
    private final ObservationRegistry observations;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheReadErrors;
    private final Counter cacheWriteErrors;
    private final Counter confirmedRulingHits;
    private final Counter acceptedQuestionInterpretations;
    private final Counter fallbackQuestionInterpretations;

    @Autowired
    public StructuredRuleAnswerService(
            QuestionUnderstanding understanding,
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            RuleAnswerModel model,
            RuleAnswerCache cache,
            RuleAnswerRateLimiter rateLimiter,
            RuleDataVersion ruleDataVersion,
            ConfirmedRulingLookup confirmedRulings,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AssistantRuns runs,
            AuditedAgentInvocations invocations,
            ObservationRegistry observations,
            MeterRegistry metrics,
            AnswerEvidenceRefiner evidenceRefiner) {
        this.understanding = understanding;
        this.modelGateway = new AnswerModelGateway(model, rateLimiter, invocations);
        this.questionInterpretation = new AnswerQuestionInterpretationPolicy();
        this.evidenceRetriever = new AnswerEvidenceRetriever(
                retrieval, visualFacts, evidenceLookup, invocations, modelGateway);
        this.evidenceRefiner = evidenceRefiner;
        this.modelRequestFactory = new AnswerModelRequestFactory();
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.ruleDataVersion = ruleDataVersion;
        this.confirmedRulings = confirmedRulings;
        this.publicationValidator = new AnswerPublicationValidator(evidenceVerifier);
        this.evidenceAdmissionGate = new AnswerEvidenceAdmissionGate(publicationValidator);
        this.draftComposer = new AnswerDraftComposer(modelGateway);
        this.calculationResolver = new AnswerCalculationResolver();
        this.situationCheckResolver = new AnswerSituationCheckResolver();
        this.walkthroughResolver = new AnswerWalkthroughResolver();
        this.decisionTableResolver = new AnswerDecisionTableResolver();
        this.exceptionClauseResolver = new AnswerExceptionClauseResolver();
        this.termDefinitionResolver = new AnswerTermDefinitionResolver();
        this.workedExampleResolver = new AnswerWorkedExampleResolver();
        this.rulePriorityResolver = new AnswerRulePriorityResolver();
        this.timingResolver = new AnswerTimingResolver();
        this.tieResolver = new AnswerTieResolver();
        this.scopeResolver = new AnswerScopeResolver();
        this.conceptComparisonResolver = new AnswerConceptComparisonResolver();
        this.ruleOptionResolver = new AnswerRuleOptionResolver();
        this.sourceEvidenceResolver = new AnswerSourceEvidenceResolver();
        this.permissionResolver = new AnswerPermissionResolver();
        this.postPublicationReviewer = new AnswerPostPublicationReviewer(
                critic, modelGateway, publicationValidator, calculationResolver, situationCheckResolver, invocations);
        this.runLifecycle = new AnswerRunLifecycle(runs);
        this.invocations = invocations;
        this.observations = observations;
        this.cacheHits = metrics.counter("rulepilot.answer.cache.requests", "result", "hit");
        this.cacheMisses = metrics.counter("rulepilot.answer.cache.requests", "result", "miss");
        this.cacheReadErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "read");
        this.cacheWriteErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "write");
        this.confirmedRulingHits = metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling");
        this.acceptedQuestionInterpretations = metrics.counter(
                "rulepilot.answer.question.interpretations", "result", "accepted");
        this.fallbackQuestionInterpretations = metrics.counter(
                "rulepilot.answer.question.interpretations", "result", "fallback");
    }

    public StructuredRuleAnswerService(
            QuestionUnderstanding understanding,
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            RuleAnswerModel model,
            RuleAnswerCache cache,
            RuleAnswerRateLimiter rateLimiter,
            RuleDataVersion ruleDataVersion,
            ConfirmedRulingLookup confirmedRulings,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AssistantRuns runs,
            AuditedAgentInvocations invocations,
            ObservationRegistry observations,
            MeterRegistry metrics) {
        this(
                understanding,
                retrieval,
                visualFacts,
                evidenceLookup,
                model,
                cache,
                rateLimiter,
                ruleDataVersion,
                confirmedRulings,
                evidenceVerifier,
                critic,
                runs,
                invocations,
                observations,
                metrics,
                null);
    }

    public StructuredRuleAnswerService(
            QuestionUnderstanding understanding,
            HybridRuleSearch retrieval,
            RuleAnswerModel model,
            RuleAnswerCache cache,
            RuleAnswerRateLimiter rateLimiter,
            RuleDataVersion ruleDataVersion,
            ConfirmedRulingLookup confirmedRulings,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AssistantRuns runs,
            AuditedAgentInvocations invocations,
            ObservationRegistry observations,
            MeterRegistry metrics) {
        this(
                understanding,
                retrieval,
                VisualRulebookPageFactSearch.empty(),
                emptyEvidenceLookup(),
                model,
                cache,
                rateLimiter,
                ruleDataVersion,
                confirmedRulings,
                evidenceVerifier,
                critic,
                runs,
                invocations,
                observations,
                metrics,
                null);
    }

    StructuredRuleAnswer answer(String question, QuestionContext context) {
        return answerInternal(question, context, "test-user", null, UUID.randomUUID());
    }

    public AnswerCreation answerWithRun(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        return Observation.createNotStarted("rulepilot.answer.workflow", observations)
                .contextualName("answer-workflow")
                .observe(() -> answerWithRunObserved(question, context, username, gameSessionId, true));
    }

    @Override
    public RuleAnswering.AnswerResult answerForPublicReader(
            UUID documentVersionId, String question, String previousQuestion) {
        return answerForPublicReader(documentVersionId, question, previousQuestion, PlayerLocale.ZH_CN);
    }

    @Override
    public RuleAnswering.AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage) {
        return answerForPublicReader(documentVersionId, question, previousQuestion, outputLanguage, null);
    }

    @Override
    public RuleAnswering.AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage,
            RuleAnswering.PublicLearningIntent learningIntent) {
        AnswerCreation creation = answerWithRun(
                question,
                new QuestionContext(
                        documentVersionId,
                        previousQuestion,
                        learningIntent == null ? null : LearningIntent.valueOf(learningIntent.name()),
                        outputLanguage),
                "public-reader",
                null);
        return AnswerOutcomePolicy.publicReaderAnswer(creation.assistantRunId(), creation.answer());
    }

    public AnswerCreation evaluateWithRun(
            String question, QuestionContext context, String username, UUID evaluationSessionId) {
        return Observation.createNotStarted("rulepilot.answer.evaluation", observations)
                .contextualName("answer-evaluation")
                .observe(() -> answerEvaluationObserved(
                        question, context, username, evaluationSessionId));
    }

    private AnswerCreation answerEvaluationObserved(
            String question, QuestionContext context, String username, UUID evaluationSessionId) {
        RunSnapshot run = runLifecycle.start(
                AssistantRunMode.QUESTION_ANSWER, context.documentVersionId(), username);
        try {
            StructuredRuleAnswer answer = answerInternal(
                    question, context, username, evaluationSessionId, run.id(), false);
            runLifecycle.finish(run, answer);
            return new AnswerCreation(run.id(), answer);
        } catch (AgentExecutionStoppedException stopped) {
            runLifecycle.fail(run, "AGENT_" + stopped.reason().name(), "Evaluation stopped by execution budget", stopped);
            return new AnswerCreation(
                    run.id(), safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT,
                            "评测执行受到预算限制，未产生可验证答案。"));
        } catch (RuntimeException exception) {
            runLifecycle.fail(run, "ANSWER_EVALUATION_FAILED", "Answer evaluation failed safely", exception);
            return new AnswerCreation(
                    run.id(), safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT,
                            "评测执行失败，未产生可验证答案。"));
        }
    }

    private AnswerCreation answerWithRunObserved(
            String question, QuestionContext context, String username, UUID gameSessionId, boolean useCache) {
        RunSnapshot run = runLifecycle.start(
                AssistantRunMode.QUESTION_ANSWER,
                gameSessionId == null ? context.documentVersionId() : gameSessionId,
                username);
        try {
            StructuredRuleAnswer answer = answerInternal(
                    question, context, username, gameSessionId, run.id(), useCache);
            run = runLifecycle.finish(run, answer);
            return new AnswerCreation(run.id(), answer);
        } catch (AgentExecutionStoppedException stopped) {
            runLifecycle.fail(run, "AGENT_" + stopped.reason().name(), "Question workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            runLifecycle.fail(run, "QUESTION_WORKFLOW_FAILED", "Question workflow failed safely", exception);
            throw exception;
        }
    }

    StructuredRuleAnswer answer(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        return answerInternal(question, context, username, gameSessionId, UUID.randomUUID());
    }

    private StructuredRuleAnswer answerInternal(
            String question, QuestionContext context, String username, UUID gameSessionId, UUID assistantRunId) {
        return answerInternal(question, context, username, gameSessionId, assistantRunId, true);
    }

    private StructuredRuleAnswer answerInternal(
            String question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            UUID assistantRunId,
            boolean useCache) {
        UnderstoodQuestion deterministic = understanding.understand(question, context);
        UnderstoodQuestion understood = deterministic;
        AnswerQuestionPlan questionPlan = AnswerQuestionPlan.fallback(deterministic);
        QuestionContext suppliedContext = context;
        LearningIntent plannedLearningIntent = context.learningIntent();
        if (modelGateway.supportsQuestionInterpretation(username)) {
            try {
                Optional<AnswerQuestionInterpretationPolicy.Interpretation> interpreted = modelGateway
                        .interpretQuestion(
                                assistantRunId,
                                username,
                                gameSessionId,
                                interpretationRequest(deterministic, suppliedContext))
                        .flatMap(draft -> questionInterpretation.applyWithPlan(deterministic, suppliedContext, draft));
                if (interpreted.isPresent()) {
                    AnswerQuestionInterpretationPolicy.Interpretation accepted = interpreted.orElseThrow();
                    understood = accepted.question();
                    if (accepted.plan() != null) questionPlan = accepted.plan();
                    plannedLearningIntent = accepted.learningIntent();
                    acceptedQuestionInterpretations.increment();
                } else {
                    fallbackQuestionInterpretations.increment();
                }
            } catch (RuleAnswerModelTimeoutException timeout) {
                fallbackQuestionInterpretations.increment();
                LOGGER.warn("Answer question interpretation timed out; preserving deterministic understanding");
            } catch (RuntimeException failure) {
                fallbackQuestionInterpretations.increment();
                LOGGER.warn("Answer question interpretation failed validation; preserving deterministic understanding");
            }
        }
        QuestionContext resolvedContext = suppliedContext.withLearningIntent(plannedLearningIntent);
        context = resolvedContext;
        UnderstoodQuestion interpretedQuestion = understood;
        if (interpretedQuestion.needsClarification()) {
            return AnswerOutcomePolicy.clarification(interpretedQuestion, context.outputLanguage());
        }
        var confirmed = invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "searchConfirmedRulings",
                estimateTokens(interpretedQuestion.normalizedQuestion()),
                "Confirmed ruling lookup completed",
                () -> confirmedRulings.find(
                        resolvedContext.documentVersionId(), Set.of(), interpretedQuestion.normalizedQuestion(), username),
                result -> result.isPresent() ? 32 : 0);
        if (confirmed.isPresent()) {
            confirmedRulingHits.increment();
            return AnswerOutcomePolicy.confirmedRuling(confirmed.get());
        }
        rateLimiter.checkUser(username);
        Optional<AnswerCacheKey> cacheKey = useCache ? cacheKey(interpretedQuestion, context) : Optional.empty();
        if (cacheKey.isPresent()) {
            var cached = findCached(cacheKey.get());
            if (cached.isPresent()) {
                cacheHits.increment();
                return cached.get();
            }
            cacheMisses.increment();
        }
        AnswerEvidenceRetriever.Result retrievalResult = evidenceRetriever.retrieve(
                assistantRunId, interpretedQuestion, context, username, questionPlan);
        if (evidenceRefiner != null) {
            retrievalResult = evidenceRefiner.refine(
                    assistantRunId,
                    interpretedQuestion,
                    context,
                    username,
                    gameSessionId,
                    questionPlan,
                    retrievalResult);
        }
        AnswerEvidenceAdmissionGate.Admission admission = evidenceAdmissionGate.admit(
                context.documentVersionId(), retrievalResult);
        if (!admission.ready()) {
            return safe(context.documentVersionId(), admission.failureStatus(), admission.failureMessage());
        }
        List<HybridEvidenceHit> evidence = admission.evidence();
        ModelRequest modelRequest;
        try {
            modelRequest = modelRequestFactory.create(interpretedQuestion, context, evidence, questionPlan);
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        AnswerDraftComposer.Result draftResult = draftComposer.compose(
                assistantRunId, username, gameSessionId, modelRequest);
        if (!draftResult.ready()) {
            if (draftResult.failureStatus() == AnswerStatus.INSUFFICIENT_EVIDENCE) {
                return AnswerOutcomePolicy.insufficientWithSources(
                        context.documentVersionId(), draftResult.failureMessage(), evidence);
            }
            return safe(
                    context.documentVersionId(),
                    draftResult.failureStatus(),
                    draftResult.failureMessage());
        }
        ModelDraft draft = draftResult.draft();
        List<RuleWalkthroughStep> walkthroughSteps;
        try {
            walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedWalkthrough) {
            draftResult = draftComposer.repairAfterWalkthroughFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedWalkthroughFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "分步讲解未通过顺序标记或引用校验。");
            }
        }
        List<RuleSituationCheck> situationChecks;
        List<RuleDecisionBranch> decisionBranches;
        List<RuleExceptionClause> exceptionClauses;
        List<RuleTermDefinition> termDefinitions;
        List<RuleWorkedExample> workedExamples;
        List<com.rulepilot.assistant.domain.RulePriorityResolution> priorityResolutions;
        List<com.rulepilot.assistant.domain.RuleTimingResolution> timingResolutions;
        List<com.rulepilot.assistant.domain.RuleTieResolution> tieResolutions;
        List<com.rulepilot.assistant.domain.RuleScopeResolution> scopeResolutions;
        List<com.rulepilot.assistant.domain.RuleConceptComparison> conceptComparisons;
        List<com.rulepilot.assistant.domain.RuleOption> ruleOptions;
        try {
            decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedDecisionTable) {
            draftResult = draftComposer.repairAfterDecisionTableFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedDecisionTableFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "条件分支未通过结果来源或引用校验。");
            }
        }
        try {
            exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedExceptionClauses) {
            draftResult = draftComposer.repairAfterExceptionClauseFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedExceptionFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "例外和限制未通过条件、效果或引用校验。");
            }
        }
        try {
            termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedDefinitions) {
            draftResult = draftComposer.repairAfterTermDefinitionFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedDefinitionFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "术语定义未通过定义边界或引用校验。");
            }
        }
        try {
            workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedExamples) {
            draftResult = draftComposer.repairAfterWorkedExampleFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedExampleFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则示例未通过起始状态、动作、结果或引用校验。");
            }
        }
        try {
            priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedPriority) {
            draftResult = draftComposer.repairAfterRulePriorityFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedPriorityFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则冲突检查未通过适用范围、优先级或引用校验。");
            }
        }
        try {
            timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedTiming) {
            draftResult = draftComposer.repairAfterTimingFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedTimingFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "时序裁决未通过情境、顺序、来源或引用校验。");
            }
        }
        try {
            tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedTie) {
            draftResult = draftComposer.repairAfterTieFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedTieFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "平局判定未通过步骤、最终结果或引用校验。");
            }
        }
        try {
            scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedScope) {
            draftResult = draftComposer.repairAfterScopeFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedScopeFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则适用范围未通过条件、当前局面或引用校验。");
            }
        }
        try {
            conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedComparison) {
            draftResult = draftComposer.repairAfterConceptComparisonFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
                conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedComparisonFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则概念对比未通过定义、边界或引用校验。");
            }
        }
        try {
            situationChecks = resolveSituationChecks(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedSituationCheck) {
            draftResult = draftComposer.repairAfterSituationCheckFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
                conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
                situationChecks = resolveSituationChecks(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedSituationFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "局面条件未通过玩家输入或引用校验。");
            }
        }
        List<RuleCalculation> calculations;
        try {
            calculations = resolveCalculations(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedCalculation) {
            draftResult = draftComposer.repairAfterCalculationFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
                conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
                situationChecks = resolveSituationChecks(assistantRunId, modelRequest, draft);
                calculations = resolveCalculations(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedCalculationFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则计算未通过输入来源或表达式校验。");
            }
        }
        try {
            ruleOptions = resolveRuleOptions(assistantRunId, modelRequest, draft);
        } catch (RuntimeException rejectedOptions) {
            draftResult = draftComposer.repairAfterRuleOptionFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(context.documentVersionId(), draftResult.failureStatus(), draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
                conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
                situationChecks = resolveSituationChecks(assistantRunId, modelRequest, draft);
                calculations = resolveCalculations(assistantRunId, modelRequest, draft);
                ruleOptions = resolveRuleOptions(assistantRunId, modelRequest, draft);
            } catch (RuntimeException repeatedOptionFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "规则选项清单未通过完整性、选择语义或引用校验。");
            }
        }
        StructuredRuleAnswer answer;
        try {
            verifyPermissionRuling(assistantRunId, modelRequest, draft);
            verifySourceEvidence(assistantRunId, modelRequest, draft);
            answer = publicationValidator.publish(
                    context.documentVersionId(), draft, evidence, calculations, situationChecks, walkthroughSteps,
                    decisionBranches, exceptionClauses, termDefinitions, workedExamples, priorityResolutions,
                    timingResolutions, tieResolutions, scopeResolutions, conceptComparisons, ruleOptions);
        } catch (RuntimeException exception) {
            draftResult = draftComposer.repairAfterPublicationFailure(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
            if (!draftResult.ready()) {
                return safe(
                        context.documentVersionId(),
                        draftResult.failureStatus(),
                        draftResult.failureMessage());
            }
            draft = draftResult.draft();
            try {
                walkthroughSteps = resolveWalkthrough(assistantRunId, modelRequest, draft);
                decisionBranches = resolveDecisionTable(assistantRunId, modelRequest, draft);
                exceptionClauses = resolveExceptionClauses(assistantRunId, modelRequest, draft);
                termDefinitions = resolveTermDefinitions(assistantRunId, modelRequest, draft);
                workedExamples = resolveWorkedExamples(assistantRunId, modelRequest, draft);
                priorityResolutions = resolveRulePriority(assistantRunId, modelRequest, draft);
                timingResolutions = resolveTiming(assistantRunId, modelRequest, draft);
                tieResolutions = resolveTies(assistantRunId, modelRequest, draft);
                scopeResolutions = resolveScope(assistantRunId, modelRequest, draft);
                conceptComparisons = resolveConceptComparisons(assistantRunId, modelRequest, draft);
                situationChecks = resolveSituationChecks(assistantRunId, modelRequest, draft);
                calculations = resolveCalculations(assistantRunId, modelRequest, draft);
                ruleOptions = resolveRuleOptions(assistantRunId, modelRequest, draft);
                verifyPermissionRuling(assistantRunId, modelRequest, draft);
                verifySourceEvidence(assistantRunId, modelRequest, draft);
                answer = publicationValidator.publish(
                        context.documentVersionId(), draft, evidence, calculations, situationChecks, walkthroughSteps,
                        decisionBranches, exceptionClauses, termDefinitions, workedExamples, priorityResolutions,
                        timingResolutions, tieResolutions, scopeResolutions, conceptComparisons, ruleOptions);
            } catch (RuntimeException repairFailure) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "回答生成结果未通过结构或引用校验。");
            }
        }
        List<AnswerWarning> publicationWarnings = new java.util.ArrayList<>(draftResult.warnings());
        if (answer.confidence() == AnswerConfidence.LOW) {
            publicationWarnings.add(new AnswerWarning(Type.LOW_CONFIDENCE));
        }
        answer = AnswerOutcomePolicy.withWarnings(answer, publicationWarnings);
        AnswerPostPublicationReviewer.Result reviewResult;
        try {
            reviewResult = postPublicationReviewer.review(
                    assistantRunId,
                    interpretedQuestion,
                    context,
                    username,
                    gameSessionId,
                    modelRequest,
                    draft,
                    answer,
                    evidence);
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "局部重讲超时，可以稍后重试或直接查看规则引用。");
        } catch (AgentExecutionStoppedException exception) {
            throw exception;
        }
        if (!reviewResult.accepted()) {
            return safe(context.documentVersionId(), reviewResult.failureStatus(), reviewResult.failureMessage());
        }
        answer = reviewResult.answer();
        if (cacheKey.isPresent()) {
            saveCached(cacheKey.get(), answer);
        }
        return answer;
    }

    private QuestionInterpretationRequest interpretationRequest(
            UnderstoodQuestion deterministic, QuestionContext context) {
        String priorQuestion = context.priorTurnReference() == null
                ? ""
                : context.priorTurnReference().question();
        String priorVerdict = context.priorTurnReference() == null
                ? ""
                : context.priorTurnReference().groundedVerdict();
        return new QuestionInterpretationRequest(
                deterministic.originalQuestion(),
                context.previousQuestion(),
                priorQuestion,
                priorVerdict,
                deterministic.type(),
                deterministic.missingContext(),
                context.learningIntent(),
                context.outputLanguage());
    }

    private Optional<StructuredRuleAnswer> findCached(AnswerCacheKey key) {
        try {
            return cache.find(key);
        } catch (RuntimeException exception) {
            cacheReadErrors.increment();
            LOGGER.warn(
                    "Rule answer cache read failed for document version {}; using source retrieval",
                    key.documentVersionId());
            return Optional.empty();
        }
    }

    private void saveCached(AnswerCacheKey key, StructuredRuleAnswer answer) {
        try {
            cache.save(key, answer);
        } catch (RuntimeException exception) {
            cacheWriteErrors.increment();
            LOGGER.warn(
                    "Rule answer cache write failed for document version {}; returning the validated answer",
                    key.documentVersionId());
        }
    }

    private Optional<AnswerCacheKey> cacheKey(
            UnderstoodQuestion question, QuestionContext context) {
        try {
            return Optional.of(AnswerCacheScopePolicy.key(
                    ANSWER_POLICY_VERSION,
                    ruleDataVersion.current(context.documentVersionId()),
                    question,
                    context));
        } catch (IllegalArgumentException unavailableRuleDataVersion) {
            LOGGER.warn(
                    "Rule data version is unavailable for document version {}; bypassing answer cache",
                    context.documentVersionId());
            return Optional.empty();
        }
    }

    private static RuleEvidenceLookup emptyEvidenceLookup() {
        return (documentVersionId, chunkIds) -> List.of();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private List<RuleCalculation> resolveCalculations(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.calculations().isEmpty() && !calculationResolver.requiresCalculation(modelRequest)) {
            return List.of();
        }
        String expressions = draft.calculations().stream()
                .map(calculation -> calculation == null ? "" : calculation.expression())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "calculateRuleMath",
                estimateTokens(expressions),
                "Grounded rule arithmetic calculated",
                () -> calculationResolver.resolve(modelRequest, draft),
                calculations -> calculations.size() * 8);
    }

    private List<RuleSituationCheck> resolveSituationChecks(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        return situationCheckResolver.resolve(modelRequest, draft);
    }

    private List<RuleWalkthroughStep> resolveWalkthrough(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.walkthroughSteps().isEmpty() && !walkthroughResolver.requiresWalkthrough(modelRequest)) {
            return List.of();
        }
        String steps = draft.walkthroughSteps().stream()
                .map(step -> step == null ? "" : step.orderBasis() + " " + step.instruction())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleWalkthrough",
                estimateTokens(steps),
                "Cited rule walkthrough schema and evidence scope validated",
                () -> walkthroughResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<RuleDecisionBranch> resolveDecisionTable(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.decisionBranches().isEmpty() && !decisionTableResolver.requiresDecisionTable(modelRequest)) {
            return List.of();
        }
        String branches = draft.decisionBranches().stream()
                .map(branch -> branch == null ? "" : branch.basis() + " " + branch.condition())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleDecisionTable",
                estimateTokens(branches),
                "Cited rule condition and outcome branches validated",
                () -> decisionTableResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<RuleExceptionClause> resolveExceptionClauses(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.exceptionClauses().isEmpty() && !exceptionClauseResolver.requiresExceptionClauses(modelRequest)) {
            return List.of();
        }
        String clauses = draft.exceptionClauses().stream()
                .map(clause -> clause == null ? "" : clause.condition() + " " + clause.effect())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "buildRuleExceptionList",
                estimateTokens(clauses),
                "Cited rule exceptions and restrictions validated",
                () -> exceptionClauseResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<RuleTermDefinition> resolveTermDefinitions(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.termDefinitions().isEmpty() && !termDefinitionResolver.requiresTermDefinitions(modelRequest)) {
            return List.of();
        }
        String definitions = draft.termDefinitions().stream()
                .map(definition -> definition == null ? "" : definition.term() + " " + definition.definition())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "defineRuleTerms",
                estimateTokens(definitions),
                "Cited rulebook term definitions and boundaries validated",
                () -> termDefinitionResolver.resolve(modelRequest, draft),
                results -> results.size() * 16);
    }

    private List<RuleWorkedExample> resolveWorkedExamples(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.workedExamples().isEmpty() && !workedExampleResolver.requiresWorkedExamples(modelRequest)) {
            return List.of();
        }
        String examples = draft.workedExamples().stream()
                .map(example -> example == null ? "" : example.setup() + " " + example.action() + " " + example.outcome())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "illustrateRule",
                estimateTokens(examples),
                "Cited rule worked examples validated",
                () -> workedExampleResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RulePriorityResolution> resolveRulePriority(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.priorityResolutions().isEmpty() && !rulePriorityResolver.requiresRulePriority(modelRequest)) {
            return List.of();
        }
        String resolutions = draft.priorityResolutions().stream()
                .map(item -> item == null ? "" : item.baseRule() + " " + item.competingRule() + " " + item.resolution())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRulePriority",
                estimateTokens(resolutions),
                "Cited rule-priority schema and evidence scope validated",
                () -> rulePriorityResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleTimingResolution> resolveTiming(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.timingResolutions().isEmpty() && !timingResolver.requiresTiming(modelRequest)) {
            return List.of();
        }
        String resolutions = draft.timingResolutions().stream()
                .map(item -> item == null ? "" : item.timingContext() + " " + item.resolutionOrder() + " "
                        + item.orderSource())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleTiming",
                estimateTokens(resolutions),
                "Cited simultaneous-effect ordering validated",
                () -> timingResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleTieResolution> resolveTies(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.tieResolutions().isEmpty() && !tieResolver.requiresTie(modelRequest)) {
            return List.of();
        }
        String resolutions = draft.tieResolutions().stream()
                .map(item -> item == null ? "" : item.tieContext() + " "
                        + String.join(" ", item.resolutionSteps()) + " " + item.finalOutcome())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleTie",
                estimateTokens(resolutions),
                "Cited tie-resolution ladder validated",
                () -> tieResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleScopeResolution> resolveScope(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.scopeResolutions().isEmpty() && !scopeResolver.requiresScope(modelRequest)) {
            return List.of();
        }
        String resolutions = draft.scopeResolutions().stream()
                .map(item -> item == null ? "" : item.ruleContext() + " " + item.governingCondition() + " "
                        + item.currentSituation() + " " + item.effect())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "resolveRuleScope",
                estimateTokens(resolutions),
                "Cited rule applicability validated",
                () -> scopeResolver.resolve(modelRequest, draft),
                results -> results.size() * 20);
    }

    private List<com.rulepilot.assistant.domain.RuleConceptComparison> resolveConceptComparisons(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.conceptComparisons().isEmpty()
                && !conceptComparisonResolver.requiresConceptComparison(modelRequest)) {
            return List.of();
        }
        String comparisons = draft.conceptComparisons().stream()
                .map(item -> item == null ? "" : item.leftConcept() + " " + item.rightConcept() + " "
                        + item.keyDifference() + " " + item.practicalBoundary())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "compareRuleConcepts",
                estimateTokens(comparisons),
                "Cited rule concept distinction validated",
                () -> conceptComparisonResolver.resolve(modelRequest, draft),
                results -> results.size() * 24);
    }

    private List<com.rulepilot.assistant.domain.RuleOption> resolveRuleOptions(
            UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (draft.ruleOptions().isEmpty() && !ruleOptionResolver.requiresRuleOptions(modelRequest)) {
            return List.of();
        }
        String options = draft.ruleOptions().stream()
                .map(item -> item == null ? "" : item.optionName() + " " + item.availabilityCondition() + " "
                        + item.result())
                .collect(java.util.stream.Collectors.joining(" "));
        return invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "listRuleOptions",
                estimateTokens(options),
                "Complete cited rule option list validated",
                () -> ruleOptionResolver.resolve(modelRequest, draft),
                results -> results.size() * 18);
    }

    private void verifySourceEvidence(UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerSourceEvidenceResolver.requiresSourceEvidence(modelRequest)) return;
        invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "showRuleEvidence",
                estimateTokens(draft.shortVerdict() + " " + draft.explanation()),
                "Direct rulebook excerpt selected and its player-facing explanation validated",
                () -> sourceEvidenceResolver.resolve(modelRequest, draft),
                results -> results.size() * 8);
    }

    private void verifyPermissionRuling(UUID assistantRunId, ModelRequest modelRequest, ModelDraft draft) {
        if (!AnswerPermissionResolver.requiresPermission(modelRequest)) return;
        invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "checkRulePermission",
                estimateTokens(draft.shortVerdict() + " " + draft.explanation()),
                "Cited permission or prohibition direction validated",
                () -> permissionResolver.resolve(modelRequest, draft),
                results -> results.size() * 8);
    }

    public record AnswerCreation(UUID assistantRunId, StructuredRuleAnswer answer) {}

    private StructuredRuleAnswer safe(UUID versionId, AnswerStatus status, String message) {
        return AnswerOutcomePolicy.safeFailure(versionId, status, message);
    }
}
