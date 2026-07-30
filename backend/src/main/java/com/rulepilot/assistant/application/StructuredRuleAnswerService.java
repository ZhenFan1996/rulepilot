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
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
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
    // End-trigger follow-ups now retrieve their direct completion procedure, so older insufficiency cache entries are stale.
    private static final String ANSWER_POLICY_VERSION = "answer-v71-end-trigger-completion-retrieval";
    private final QuestionUnderstanding understanding;
    private final AnswerModelGateway modelGateway;
    private final AnswerEvidenceRetriever evidenceRetriever;
    private final AnswerModelRequestFactory modelRequestFactory;
    private final RuleAnswerCache cache;
    private final RuleAnswerRateLimiter rateLimiter;
    private final RuleDataVersion ruleDataVersion;
    private final ConfirmedRulingLookup confirmedRulings;
    private final AnswerPublicationValidator publicationValidator;
    private final AnswerEvidenceAdmissionGate evidenceAdmissionGate;
    private final AnswerDraftComposer draftComposer;
    private final AnswerPostPublicationReviewer postPublicationReviewer;
    private final AnswerRunLifecycle runLifecycle;
    private final AuditedAgentInvocations invocations;
    private final ObservationRegistry observations;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheReadErrors;
    private final Counter cacheWriteErrors;
    private final Counter confirmedRulingHits;

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
            MeterRegistry metrics) {
        this.understanding = understanding;
        this.modelGateway = new AnswerModelGateway(model, rateLimiter, invocations);
        this.evidenceRetriever = new AnswerEvidenceRetriever(
                retrieval, visualFacts, evidenceLookup, invocations, modelGateway);
        this.modelRequestFactory = new AnswerModelRequestFactory();
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.ruleDataVersion = ruleDataVersion;
        this.confirmedRulings = confirmedRulings;
        this.publicationValidator = new AnswerPublicationValidator(evidenceVerifier);
        this.evidenceAdmissionGate = new AnswerEvidenceAdmissionGate(publicationValidator);
        this.draftComposer = new AnswerDraftComposer(modelGateway);
        this.postPublicationReviewer = new AnswerPostPublicationReviewer(critic, modelGateway, publicationValidator);
        this.runLifecycle = new AnswerRunLifecycle(runs);
        this.invocations = invocations;
        this.observations = observations;
        this.cacheHits = metrics.counter("rulepilot.answer.cache.requests", "result", "hit");
        this.cacheMisses = metrics.counter("rulepilot.answer.cache.requests", "result", "miss");
        this.cacheReadErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "read");
        this.cacheWriteErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "write");
        this.confirmedRulingHits = metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling");
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
                metrics);
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
            UUID documentVersionId, String question, String currentLessonSection, String previousQuestion) {
        return answerForPublicReader(documentVersionId, question, currentLessonSection, previousQuestion, PlayerLocale.ZH_CN);
    }

    @Override
    public RuleAnswering.AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String currentLessonSection,
            String previousQuestion,
            PlayerLocale outputLanguage) {
        AnswerCreation creation = answerWithRun(
                question,
                new QuestionContext(
                        documentVersionId,
                        currentLessonSection,
                        null,
                        null,
                        Set.of(),
                        previousQuestion,
                        null,
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
        UnderstoodQuestion understood = understanding.understand(question, context);
        if (understood.needsClarification()) {
            return AnswerOutcomePolicy.clarification(understood);
        }
        var confirmed = invocations.invoke(
                assistantRunId,
                ActivityType.TOOL,
                "searchConfirmedRulings",
                estimateTokens(understood.normalizedQuestion()),
                "Confirmed ruling lookup completed",
                () -> confirmedRulings.find(
                        context.documentVersionId(), context.activeExpansions(), understood.normalizedQuestion(), username),
                result -> result.isPresent() ? 32 : 0);
        if (confirmed.isPresent()) {
            confirmedRulingHits.increment();
            return AnswerOutcomePolicy.confirmedRuling(confirmed.get());
        }
        rateLimiter.checkUser(username);
        Optional<AnswerCacheKey> cacheKey = useCache ? cacheKey(understood, context, gameSessionId) : Optional.empty();
        if (cacheKey.isPresent()) {
            var cached = findCached(cacheKey.get());
            if (cached.isPresent()) {
                cacheHits.increment();
                return cached.get();
            }
            cacheMisses.increment();
        }
        AnswerEvidenceRetriever.Result retrievalResult = evidenceRetriever.retrieve(assistantRunId, understood, context, username);
        AnswerEvidenceAdmissionGate.Admission admission = evidenceAdmissionGate.admit(
                context.documentVersionId(), retrievalResult);
        if (!admission.ready()) {
            return safe(context.documentVersionId(), admission.failureStatus(), admission.failureMessage());
        }
        List<HybridEvidenceHit> evidence = admission.evidence();
        ModelRequest modelRequest;
        try {
            modelRequest = modelRequestFactory.create(understood, context, evidence);
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        AnswerDraftComposer.Result draftResult = draftComposer.compose(
                assistantRunId, username, gameSessionId, modelRequest);
        if (!draftResult.ready()) {
            return safe(
                    context.documentVersionId(),
                    draftResult.failureStatus(),
                    draftResult.failureMessage());
        }
        ModelDraft draft = draftResult.draft();
        StructuredRuleAnswer answer;
        try {
            answer = publicationValidator.publish(context.documentVersionId(), draft, evidence);
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        AnswerPostPublicationReviewer.Result reviewResult;
        try {
            reviewResult = postPublicationReviewer.review(
                    assistantRunId,
                    understood,
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
            UnderstoodQuestion question, QuestionContext context, UUID gameSessionId) {
        try {
            return Optional.of(AnswerCacheScopePolicy.key(
                    ANSWER_POLICY_VERSION,
                    ruleDataVersion.current(context.documentVersionId()),
                    question,
                    context,
                    gameSessionId));
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

    public record AnswerCreation(UUID assistantRunId, StructuredRuleAnswer answer) {}

    private StructuredRuleAnswer safe(UUID versionId, AnswerStatus status, String message) {
        return AnswerOutcomePolicy.safeFailure(versionId, status, message);
    }
}
