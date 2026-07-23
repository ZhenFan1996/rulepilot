package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import com.rulepilot.assistant.PlayerLocale;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class StructuredRuleAnswerService implements RuleAnswering {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerService.class);
    private static final String ANSWER_POLICY_VERSION = "answer-v58-document-scoped-resolution-evidence";
    private final QuestionUnderstanding understanding;
    private final HybridRuleSearch retrieval;
    private final VisualRulebookPageFactSearch visualFacts;
    private final RuleEvidenceLookup evidenceLookup;
    private final RuleAnswerModel model;
    private final RuleAnswerCache cache;
    private final RuleAnswerRateLimiter rateLimiter;
    private final RuleDataVersion ruleDataVersion;
    private final ConfirmedRulingLookup confirmedRulings;
    private final AnswerPublicationValidator publicationValidator;
    private final GeneratedContentCritic critic;
    private final AssistantRuns runs;
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
        this.retrieval = retrieval;
        this.visualFacts = visualFacts;
        this.evidenceLookup = evidenceLookup;
        this.model = model;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.ruleDataVersion = ruleDataVersion;
        this.confirmedRulings = confirmedRulings;
        this.publicationValidator = new AnswerPublicationValidator(evidenceVerifier);
        this.critic = critic;
        this.runs = runs;
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
        return toPublicReaderAnswer(creation);
    }

    static RuleAnswering.AnswerResult toPublicReaderAnswer(AnswerCreation creation) {
        StructuredRuleAnswer answer = creation.answer();
        return new RuleAnswering.AnswerResult(
                creation.assistantRunId(),
                new RuleAnswering.Answer(
                        answer.status().name(),
                        answer.shortVerdict(),
                        answer.explanation(),
                        answer.citations().stream()
                                .map(citation -> new RuleAnswering.Citation(
                                        citation.heading(), citation.pageFrom(), citation.pageTo()))
                                .toList(),
                        answer.exceptions(),
                        answer.confidence().name(),
                        answer.clarification()),
                answer.citations().stream()
                        .map(RuleCitation::chunkId)
                        .collect(Collectors.toUnmodifiableSet()));
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
        RunSnapshot run = runs.start(
                AssistantRunMode.QUESTION_ANSWER, context.documentVersionId(), username);
        try {
            StructuredRuleAnswer answer = answerInternal(
                    question, context, username, evaluationSessionId, run.id(), false);
            finishRun(run, answer);
            return new AnswerCreation(run.id(), answer);
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Evaluation stopped by execution budget", stopped);
            return new AnswerCreation(
                    run.id(), safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT,
                            "评测执行受到预算限制，未产生可验证答案。"));
        } catch (RuntimeException exception) {
            failRun(run, "ANSWER_EVALUATION_FAILED", "Answer evaluation failed safely", exception);
            return new AnswerCreation(
                    run.id(), safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT,
                            "评测执行失败，未产生可验证答案。"));
        }
    }

    private AnswerCreation answerWithRunObserved(
            String question, QuestionContext context, String username, UUID gameSessionId, boolean useCache) {
        RunSnapshot run = runs.start(
                AssistantRunMode.QUESTION_ANSWER,
                gameSessionId == null ? context.documentVersionId() : gameSessionId,
                username);
        try {
            StructuredRuleAnswer answer = answerInternal(
                    question, context, username, gameSessionId, run.id(), useCache);
            run = finishRun(run, answer);
            return new AnswerCreation(run.id(), answer);
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Question workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            failRun(run, "QUESTION_WORKFLOW_FAILED", "Question workflow failed safely", exception);
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
            return clarification(understood);
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
            return fromConfirmedRuling(confirmed.get());
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
        RetrievalResult retrievalResult = retrieveEvidence(assistantRunId, understood, context, username);
        List<HybridEvidenceHit> evidence = retrievalResult.evidence();
        if (retrievalResult.state() == RetrievalState.CONFLICTING) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突，无法可靠回答。");
        }
        if (retrievalResult.state() == RetrievalState.UNAVAILABLE) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "规则检索暂时不可用，尚未生成答案。");
        }
        if (evidence.isEmpty()) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "没有找到可引用的规则依据。");
        }
        var evidenceVerification = publicationValidator.verifySources(context.documentVersionId(), evidence);
        if (evidenceVerification.status() == VerificationStatus.VERSION_CONFLICT) {
            return safe(context.documentVersionId(), AnswerStatus.VERSION_CONFLICT, "检索证据与当前规则版本不一致。");
        }
        if (!evidenceVerification.verified()) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突或不足，无法可靠回答。");
        }
        ModelDraft draft;
        ModelRequest modelRequest;
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            modelRequest = toRequest(understood, context, evidence);
            draft = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "composeRuleAnswer",
                    estimateTokens(modelRequest.toString()),
                    "Rule answer model output received",
                    () -> model.compose(modelRequest),
                    result -> estimateTokens(result.toString()));
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        } finally {
            permit.close();
        }
        if (draft == null) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        if (!draft.answerable()) {
            draft = reviseEvidenceBackedAbstention(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
        }
        if (!draft.answerable()) {
            draft = AnswerReplenishmentPolicy.directFallback(modelRequest).orElse(null);
            if (draft == null) {
                return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "现有证据未能直接回答这个问题。");
            }
        }
        draft = AnswerReplenishmentPolicy.replaceMisdirectedDraft(modelRequest, draft);
        draft = removePeripheralEndgameCitations(modelRequest, draft);
        List<String> playerFacingRepair = AnswerPlayerFacingRepairPolicy.feedbackFor(modelRequest, draft);
        if (!playerFacingRepair.isEmpty()) {
            try {
                draft = revisePlayerFacingDraft(
                        assistantRunId, username, gameSessionId, modelRequest, draft, playerFacingRepair);
                if (AnswerRepairOutcomePolicy.shouldRetryWithEvidencedSuccessor(
                        modelRequest, draft, playerFacingRepair)) {
                    draft = revisePlayerFacingDraft(
                            assistantRunId,
                            username,
                            gameSessionId,
                            modelRequest,
                            AnswerRepairOutcomePolicy.retryDraft(draft),
                            AnswerRepairOutcomePolicy.successorRetryFeedback(playerFacingRepair));
                }
            } catch (RuleAnswerModelTimeoutException exception) {
                return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "视觉规则消歧超时，可以稍后重试或直接查看规则引用。");
            } catch (RuntimeException exception) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答修订结果未通过结构校验。");
            }
            if (draft == null || !draft.answerable()) {
                return safe(
                        context.documentVersionId(),
                        AnswerStatus.INSUFFICIENT_EVIDENCE,
                        AnswerRepairOutcomePolicy.insufficientRepairMessage(playerFacingRepair));
            }
            draft = AnswerDraftSafetyPolicy.normalizeSingleMappedVisualGlyph(
                    draft, AnswerVisualEvidencePolicy.resolvedComponents(modelRequest, draft));
            draft = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(draft);
            draft = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(draft);
            Optional<AnswerRepairOutcomePolicy.PublicationFailure> failure =
                    AnswerRepairOutcomePolicy.publicationFailure(modelRequest, draft);
            if (failure.isPresent()) {
                return safe(context.documentVersionId(), failure.get().status(), failure.get().message());
            }
        }
        draft = removePeripheralEndgameCitations(modelRequest, draft);
        if (AnswerEvidencePolicy.requiresEndTurnProcedureCitation(modelRequest.question(), modelRequest.evidence())
                && !AnswerEvidencePolicy.citesEndTurnProcedure(modelRequest.evidence(), draft.citationIds())) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答没有引用回合结束处理的直接规则依据。");
        }
        if (AnswerEvidencePolicy.requiresEndgameResolutionCitation(modelRequest.question(), modelRequest.evidence())
                && !AnswerEvidencePolicy.citesEndgameResolution(
                        modelRequest.question(), modelRequest.evidence(), draft.citationIds())) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答没有引用游戏结束结算的直接规则依据。");
        }
        draft = AnswerVisualEvidencePolicy.includeReferenceCitations(modelRequest, draft);
        StructuredRuleAnswer answer;
        try {
            answer = publicationValidator.publish(context.documentVersionId(), draft, evidence);
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        try {
            ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(context, gameSessionId, answer);
            Review review = critic.review(
                    AnswerCritiquePolicy.request(assistantRunId, understood, context, answer, evidence), risk);
            if (!review.accepted()) {
                if (context.learningIntent() == null && gameSessionId == null) {
                    return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答未通过事实一致性审查。");
                }
                answer = reviseLearningResponse(
                        assistantRunId,
                        context.documentVersionId(),
                        username,
                        gameSessionId,
                        modelRequest,
                        draft,
                        review,
                        evidence);
                Review revisionReview = critic.review(
                        AnswerCritiquePolicy.request(assistantRunId, understood, context, answer, evidence),
                        ReviewRisk.HIGH_IMPACT);
                if (!revisionReview.accepted()) {
                    return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "局部重讲仍未通过事实一致性审查。");
                }
            }
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "局部重讲超时，可以稍后重试或直接查看规则引用。");
        } catch (AgentExecutionStoppedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Adaptive answer validation failed for run {}: {} ({})",
                    assistantRunId,
                    exception.getMessage(),
                    exception.getClass().getSimpleName());
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答事实一致性审查失败。");
        }
        if (cacheKey.isPresent()) {
            saveCached(cacheKey.get(), answer);
        }
        return answer;
    }

    private StructuredRuleAnswer reviseLearningResponse(
            UUID assistantRunId,
            UUID documentVersionId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            Review review,
            List<HybridEvidenceHit> evidence) {
        List<String> feedback = AnswerCritiquePolicy.revisionFeedback(review);
        ModelDraft revised;
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            revised = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "reviseLearningResponse",
                    estimateTokens(modelRequest.toString()) + estimateTokens(feedback.toString()),
                    "Learning response revised from bounded critic feedback",
                    () -> model.revise(modelRequest, previousDraft, feedback),
                    result -> estimateTokens(result.toString()));
        } catch (RuleAnswerModelTimeoutException exception) {
            throw exception;
        } finally {
            permit.close();
        }
        if (revised == null || !revised.answerable()) {
            throw new IllegalArgumentException("revised learning response is not answerable");
        }
        return publicationValidator.publish(documentVersionId, revised, evidence);
    }

    private ModelDraft reviseEvidenceBackedAbstention(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft) {
        String baseFeedback = "EVIDENCE_SUFFICIENCY: Re-evaluate the question against every supplied excerpt. The "
                + "condition written into a player's question is available table context: when an excerpt states "
                + "the outcome for that exact condition, answer the rule directly instead of treating unrelated "
                + "live-state details as missing. This includes a named replenishment condition, a stated end trigger, "
                + "and an explicitly described tie state. If the evidence gives a prerequisite or conditional branch "
                + "but the current table state is otherwise unknown, answer conditionally instead of assuming the "
                + "condition or refusing. Preserve relative rules, scope, timing, negation, and exceptions exactly. "
                + "Remain unanswerable when the excerpts still do not directly resolve the question.";
        String feedback = AnswerReplenishmentPolicy.hasEvidencedProcedure(modelRequest)
                ? baseFeedback + " DIRECT_REPLENISHMENT_PROCEDURE: A supplied excerpt explicitly gives the sequence for "
                        + "continuing when the named draw or supply area becomes empty. Apply that stated sequence to "
                        + "a question about reaching the required draw amount, and cite its source. Do not abstain merely "
                        + "because the player did not state how many items were present before that area became empty."
                : baseFeedback;
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            return invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "reconsiderEvidenceBackedAbstention",
                    estimateTokens(modelRequest.toString()) + estimateTokens(feedback),
                    "Evidence-backed table abstention reconsidered",
                    () -> model.revise(modelRequest, previousDraft, List.of(feedback)),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    private ModelDraft removePeripheralEndgameCitations(ModelRequest request, ModelDraft draft) {
        if (!AnswerEvidencePolicy.isEndgameTimingAndTieSummary(request.question(), request.evidence())
                || draft.citationIds().isEmpty()) return draft;
        List<UUID> decisive = AnswerEvidencePolicy.requiredEndgameCitationIds(
                request.question(), request.evidence(), draft.citationIds());
        if (decisive.isEmpty() || decisive.size() == draft.citationIds().size()) return draft;
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                decisive,
                draft.exceptions(),
                draft.confidence());
    }

    private ModelDraft revisePlayerFacingDraft(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            List<String> feedback) {
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            return invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "repairPlayerFacingRuleAnswer",
                    estimateTokens(modelRequest.toString()) + estimateTokens(feedback.toString()),
                    "Ambiguous visual identity or internal evidence language repaired",
                    () -> model.revise(modelRequest, previousDraft, feedback),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
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
        String conversationScopedQuestion = context.previousQuestion() == null
                ? question.normalizedQuestion()
                : context.previousQuestion().toLowerCase(Locale.ROOT) + " -> " + question.normalizedQuestion();
        conversationScopedQuestion = ANSWER_POLICY_VERSION + ":" + context.outputLanguage().name() + ":" + conversationScopedQuestion;
        if (gameSessionId != null) {
            conversationScopedQuestion = "LIVE_TABLE:" + conversationScopedQuestion;
        }
        if (context.learningIntent() != null) {
            conversationScopedQuestion = context.learningIntent().name() + ":" + conversationScopedQuestion;
        }
        try {
            return Optional.of(new AnswerCacheKey(
                    context.documentVersionId(), ruleDataVersion.current(context.documentVersionId()),
                    conversationScopedQuestion, context.currentLessonSection(),
                    context.gamePhase(), context.playerCount(), context.activeExpansions(), context.outputLanguage()));
        } catch (IllegalArgumentException unavailableRuleDataVersion) {
            LOGGER.warn(
                    "Rule data version is unavailable for document version {}; bypassing answer cache",
                    context.documentVersionId());
            return Optional.empty();
        }
    }

    private RetrievalResult retrieveEvidence(
            UUID assistantRunId, UnderstoodQuestion question, QuestionContext context, String username) {
        Map<UUID, HybridEvidenceHit> evidenceById = new LinkedHashMap<>();
        Map<UUID, HybridEvidenceHit> intentAnchors = new LinkedHashMap<>();
        Map<Integer, PageFactMatch> visualFactsByPage = new LinkedHashMap<>();
        Set<Integer> requiredVisualFactPages = new LinkedHashSet<>();
        Set<Integer> directQuestionVisualFactPages = new LinkedHashSet<>();
        boolean conflicting = false;
        int successfulCoreRetrievals = 0;
        int failedCoreRetrievals = 0;
        List<String> rewrittenQueries = rewriteCrossLanguageQueries(assistantRunId, question, context, username);
        List<RetrievalIntent> intents = AnswerRetrievalPlanner.plan(question, context, rewrittenQueries);
        if (intents.stream().anyMatch(intent -> intent.purpose() == RetrievalPurpose.EXHAUSTED_SOURCE)) {
            try {
                List<PageFactMatch> replenishmentMatches = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "searchExplicitReplenishmentProcedure",
                        18,
                        "Direct replenishment procedure evidence retrieved",
                        () -> visualFacts.search(
                                context.documentVersionId(),
                                AnswerReplenishmentPolicy.retrievalQuery(question.normalizedQuestion()),
                                3),
                        matches -> matches.size() * 80);
                replenishmentMatches.forEach(match -> visualFactsByPage.merge(
                        match.pageNumber(), match, (first, candidate) -> candidate.score() > first.score() ? candidate : first));
                replenishmentMatches.forEach(match -> requiredVisualFactPages.add(match.pageNumber()));
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException lookupFailure) {
                LOGGER.warn(
                        "Optional replenishment fact lookup failed for document version {}: {}",
                        context.documentVersionId(),
                        lookupFailure.getClass().getSimpleName());
            }
        }
        for (int intentIndex = 0; intentIndex < intents.size(); intentIndex++) {
            RetrievalIntent intent = intents.get(intentIndex);
            List<HybridEvidenceHit> retrieved;
            try {
                retrieved = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "hybridRuleSearch",
                        estimateTokens(intent.query()),
                        "Version-scoped answer evidence retrieved",
                        () -> retrieval.search(
                                context.documentVersionId(),
                                intent.query(),
                                new RetrievalOptions(
                                        intent.purpose() == RetrievalPurpose.ENDGAME_RESOLUTION ? 20 : 3,
                                        intent.sectionTypes(),
                                        intent.currentSectionType())),
                        this::evidenceTokens);
                successfulCoreRetrievals++;
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException retrievalFailure) {
                failedCoreRetrievals++;
                LOGGER.warn(
                        "Answer retrieval intent failed for document version {}: {}",
                        context.documentVersionId(),
                        retrievalFailure.getClass().getSimpleName());
                continue;
            }
            boolean supplementaryIntent = intents.size() > 2 && intentIndex == intents.size() - 1;
            if (!retrieved.isEmpty() && !supplementaryIntent
                    && intent.purpose() == RetrievalPurpose.STATE_TRANSITION) {
                retrieved.stream().limit(2).forEach(hit -> intentAnchors.putIfAbsent(hit.evidence().chunkId(), hit));
            } else if (!retrieved.isEmpty() && !supplementaryIntent
                    && intent.purpose() == RetrievalPurpose.ENDGAME_RESOLUTION) {
                HybridEvidenceHit directResolution = retrieved.stream()
                        .max(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore))
                        .orElse(retrieved.getFirst());
                intentAnchors.putIfAbsent(directResolution.evidence().chunkId(), directResolution);
            } else if (!retrieved.isEmpty() && !supplementaryIntent
                    && intent.purpose() == RetrievalPurpose.END_TURN_PROCEDURE) {
                HybridEvidenceHit directProcedure = retrieved.stream()
                        .filter(AnswerEvidencePolicy::hasEndTurnProcedure)
                        .findFirst()
                        .orElse(retrieved.getFirst());
                intentAnchors.putIfAbsent(directProcedure.evidence().chunkId(), directProcedure);
            } else if (!retrieved.isEmpty() && !supplementaryIntent) {
                HybridEvidenceHit diverseAnchor = retrieved.stream()
                        .filter(hit -> !intentAnchors.containsKey(hit.evidence().chunkId()))
                        .findFirst()
                        .orElse(retrieved.getFirst());
                intentAnchors.putIfAbsent(diverseAnchor.evidence().chunkId(), diverseAnchor);
            }
            for (HybridEvidenceHit hit : retrieved) {
                HybridEvidenceHit existing = evidenceById.get(hit.evidence().chunkId());
                if (existing != null && !AnswerEvidencePolicy.sameEvidenceSnapshot(existing, hit)) {
                    conflicting = true;
                    break;
                }
                if (existing == null || hit.score() > existing.score()) {
                    evidenceById.put(hit.evidence().chunkId(), hit);
                }
            }
            try {
                List<PageFactMatch> visualMatches = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "searchVisualRulebookPageFacts",
                        estimateTokens(intent.query()),
                        "Page-scoped visual rule facts retrieved",
                        () -> visualFacts.search(context.documentVersionId(), intent.query(), 2),
                        matches -> matches.size() * 80);
                visualMatches.forEach(match -> visualFactsByPage.merge(
                        match.pageNumber(), match, (first, candidate) -> candidate.score() > first.score() ? candidate : first));
                if (intent.directQuestion() || isDirectQuestionIntent(intent.query(), question.normalizedQuestion())) {
                    visualMatches.forEach(match -> directQuestionVisualFactPages.add(match.pageNumber()));
                }
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException visualLookupFailure) {
                LOGGER.warn(
                        "Optional visual fact lookup failed for document version {}: {}",
                        context.documentVersionId(),
                        visualLookupFailure.getClass().getSimpleName());
            }
            if (conflicting) {
                break;
            }
        }
        if (!conflicting && AnswerEvidencePolicy.isEndgameResolutionQuestion(question.normalizedQuestion())) {
            enrichAdjacentEndgameEvidence(
                    assistantRunId, context.documentVersionId(), evidenceById, intentAnchors);
        }
        if (!conflicting && AnswerEvidencePolicy.requiresIconLegend(visualFactsByPage.values())) {
            try {
                List<PageFactMatch> legendMatches = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "searchVisualRulebookIconLegend",
                        12,
                        "Cross-page icon legend evidence retrieved",
                        () -> visualFacts.search(
                                context.documentVersionId(),
                                "component legend setup contents token marker piece card tile resource icon symbol "
                                        + "starting components player reference 组件 图例 配件 设置 令牌 标记 棋子 卡牌 板块 资源 图标",
                                2),
                        matches -> matches.size() * 80);
                legendMatches.forEach(match -> visualFactsByPage.merge(
                        match.pageNumber(), match, (first, candidate) -> candidate.score() > first.score() ? candidate : first));
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException lookupFailure) {
                LOGGER.warn(
                        "Optional icon-legend lookup failed for document version {}: {}",
                        context.documentVersionId(),
                        lookupFailure.getClass().getSimpleName());
            }
        }
        if (conflicting) {
            return new RetrievalResult(List.of(), RetrievalState.CONFLICTING);
        }
        if (successfulCoreRetrievals == 0 && failedCoreRetrievals > 0) {
            return new RetrievalResult(List.of(), RetrievalState.UNAVAILABLE);
        }
        Set<Integer> visualPagePriority = new LinkedHashSet<>(directQuestionVisualFactPages);
        visualPagePriority.addAll(requiredVisualFactPages);
        Set<UUID> visualEvidenceIds = mergeVisualPageEvidence(
                assistantRunId, context.documentVersionId(), evidenceById, visualFactsByPage, visualPagePriority);
        List<HybridEvidenceHit> selectedEvidence = AnswerEvidenceSelectionPolicy.select(
                question.normalizedQuestion(), evidenceById, intentAnchors.values(), visualEvidenceIds);
        if (AnswerEvidencePolicy.isEndgameResolutionQuestion(question.normalizedQuestion())) {
            String pages = selectedEvidence.stream()
                    .map(hit -> Integer.toString(hit.evidence().pageFrom()))
                    .distinct()
                    .collect(Collectors.joining(","));
            String decisivePages = selectedEvidence.stream()
                    .filter(AnswerEvidencePolicy::hasEndgameResolution)
                    .map(hit -> Integer.toString(hit.evidence().pageFrom()))
                    .distinct()
                    .collect(Collectors.joining(","));
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "validateEndgameEvidenceScope",
                    ActivityOutcome.SUCCEEDED,
                    "Endgame evidence pages=" + pages + "; decisive pages=" + decisivePages);
        }
        return new RetrievalResult(selectedEvidence, RetrievalState.READY);
    }

    private void enrichAdjacentEndgameEvidence(
            UUID assistantRunId,
            UUID documentVersionId,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Map<UUID, HybridEvidenceHit> intentAnchors) {
        Set<UUID> proximityAnchors = evidenceById.values().stream()
                .filter(AnswerEvidencePolicy::hasEndgameResolution)
                .sorted(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore).reversed())
                .map(hit -> hit.evidence().chunkId())
                .limit(1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (proximityAnchors.isEmpty()) {
            proximityAnchors = evidenceById.values().stream()
                    .filter(this::isEndgameProximityAnchor)
                    .map(hit -> hit.evidence().chunkId())
                    .limit(2)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (proximityAnchors.isEmpty()) return;
        Set<UUID> lookupAnchors = proximityAnchors;
        try {
            List<RuleEvidenceHit> adjacent = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    "readAdjacentEndgameEvidence",
                    8,
                    "Adjacent endgame rule evidence retrieved",
                    () -> evidenceLookup.findAdjacent(documentVersionId, lookupAnchors, 2, Set.of()),
                    result -> result.size() * 80);
            for (RuleEvidenceHit source : adjacent) {
                HybridEvidenceHit candidate = new HybridEvidenceHit(source, 0.0, 1, null, false);
                HybridEvidenceHit existing = evidenceById.get(source.chunkId());
                if (existing == null || candidate.score() > existing.score()) {
                    evidenceById.put(source.chunkId(), candidate);
                }
            }
            Optional<HybridEvidenceHit> decisive = evidenceById.values().stream()
                    .filter(AnswerEvidencePolicy::hasEndgameResolution)
                    .max(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore));
            if (decisive.isPresent()) {
                Optional<HybridEvidenceHit> timing = evidenceById.values().stream()
                        .filter(AnswerEvidenceSelectionPolicy::hasEvidencedEndgameTiming)
                        .filter(hit -> hit.evidence().chunkId().equals(decisive.get().evidence().chunkId())
                                || hit.evidence().pageFrom() == decisive.get().evidence().pageFrom())
                        .filter(hit -> !hit.evidence().chunkId().equals(decisive.get().evidence().chunkId()))
                        .findFirst();
                prioritizeEndgameAnchors(intentAnchors, decisive.get(), timing.orElse(null));
            }
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn(
                    "Adjacent endgame evidence lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
        }
    }

    private void prioritizeEndgameAnchors(
            Map<UUID, HybridEvidenceHit> intentAnchors,
            HybridEvidenceHit decisive,
            HybridEvidenceHit timing) {
        LinkedHashMap<UUID, HybridEvidenceHit> reordered = new LinkedHashMap<>();
        reordered.put(decisive.evidence().chunkId(), decisive);
        if (timing != null) reordered.put(timing.evidence().chunkId(), timing);
        intentAnchors.forEach((chunkId, hit) -> reordered.putIfAbsent(chunkId, hit));
        intentAnchors.clear();
        intentAnchors.putAll(reordered);
    }

    private boolean isEndgameProximityAnchor(HybridEvidenceHit hit) {
        if (hit == null) return false;
        String text = (hit.evidence().heading() + "\n" + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        return text.contains("end of a round")
                || text.contains("end of the round")
                || text.contains("ending the round")
                || text.contains("游戏结束")
                || text.contains("轮末")
                || text.contains("回合结束");
    }

    private boolean isDirectQuestionIntent(String query, String normalizedQuestion) {
        String normalizedQuery = normalizeIntentComparison(query);
        String normalizedQuestionValue = normalizeIntentComparison(normalizedQuestion);
        return normalizedQuery.equals(normalizedQuestionValue)
                || (normalizedQuery.length() >= 4 && normalizedQuestionValue.contains(normalizedQuery));
    }

    private String normalizeIntentComparison(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("[?？!！;；,，]+$", "").strip();
    }

    private Set<UUID> mergeVisualPageEvidence(
            UUID assistantRunId,
            UUID documentVersionId,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Map<Integer, PageFactMatch> factsByPage,
            Set<Integer> requiredPages) {
        if (factsByPage.isEmpty()) return Set.of();
        Set<Integer> pages = new LinkedHashSet<>();
        requiredPages.stream()
                .filter(factsByPage::containsKey)
                .forEach(pages::add);
        factsByPage.values().stream()
                .sorted(Comparator.comparingDouble(PageFactMatch::score).reversed()
                        .thenComparingInt(PageFactMatch::pageNumber))
                .map(PageFactMatch::pageNumber)
                .forEach(pages::add);
        Set<Integer> selectedPages = pages.stream().limit(4).collect(Collectors.toCollection(LinkedHashSet::new));
        List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> pageSources;
        try {
            pageSources = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    "readVisualRulebookFactPages",
                    selectedPages.size(),
                    "Original rulebook pages " + selectedPages + " for visual facts retrieved",
                    () -> evidenceLookup.findByPageNumbers(documentVersionId, selectedPages),
                    sources -> sources.size() * 80);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn(
                    "Optional visual source-page lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
            return Set.of();
        }
        Set<UUID> enriched = new LinkedHashSet<>();
        Map<Integer, Integer> rankByPage = new LinkedHashMap<>();
        int rank = 1;
        for (Integer page : selectedPages) rankByPage.put(page, rank++);
        for (var source : pageSources) {
            if (source.pageFrom() != source.pageTo()) continue;
            PageFactMatch fact = factsByPage.get(source.pageFrom());
            if (fact == null) continue;
            HybridEvidenceHit existing = evidenceById.get(source.chunkId());
            if (existing != null && !AnswerEvidencePolicy.isVisualPlaceholder(existing)) {
                var textSource = existing.evidence();
                var enrichedSource = new com.rulepilot.retrieval.evidence.RuleEvidenceHit(
                        textSource.chunkId(),
                        textSource.documentVersionId(),
                        textSource.sectionType(),
                        textSource.heading(),
                        textSource.excerpt() + "\n\n" + fact.evidenceText(),
                        textSource.pageFrom(),
                        textSource.pageTo(),
                        Math.max(textSource.score(), fact.score()));
                evidenceById.put(source.chunkId(), new HybridEvidenceHit(
                        enrichedSource,
                        Math.max(existing.score(), fact.score()),
                        existing.fullTextRank() == null
                                ? rankByPage.get(source.pageFrom())
                                : Math.min(existing.fullTextRank(), rankByPage.get(source.pageFrom())),
                        existing.vectorRank(),
                        existing.currentSectionBoosted()));
                enriched.add(source.chunkId());
                continue;
            }
            var visualSource = new com.rulepilot.retrieval.evidence.RuleEvidenceHit(
                    source.chunkId(),
                    source.documentVersionId(),
                    source.sectionType(),
                    source.heading(),
                    fact.evidenceText(),
                    source.pageFrom(),
                    source.pageTo(),
                    Math.max(0.01, fact.score()));
            evidenceById.put(source.chunkId(), new HybridEvidenceHit(
                    visualSource,
                    Math.max(0.01, fact.score()),
                    rankByPage.get(source.pageFrom()),
                    null,
                    false));
            enriched.add(source.chunkId());
        }
        return Set.copyOf(enriched);
    }

    private static RuleEvidenceLookup emptyEvidenceLookup() {
        return (documentVersionId, chunkIds) -> List.of();
    }

    private List<String> rewriteCrossLanguageQueries(
            UUID assistantRunId, UnderstoodQuestion question, QuestionContext context, String username) {
        if (!AnswerEvidencePolicy.requiresCrossLanguageExpansion(question.normalizedQuestion())) {
            return List.of();
        }
        RuleAnswerRateLimiter.Permit permit = rateLimiter.acquireModel(username, null, model.providerId());
        try {
            return invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "rewriteAnswerRetrievalQueries",
                    estimateTokens(question.normalizedQuestion()),
                    "Cross-language retrieval phrases prepared",
                    () -> model.rewriteRetrievalQueries(new RetrievalQueryRequest(
                            question.normalizedQuestion(), context.previousQuestion(), context.currentLessonSection())),
                    result -> estimateTokens(result.toString()));
        } catch (RuleAnswerModelTimeoutException exception) {
            LOGGER.info("Cross-language retrieval rewrite timed out; continuing with the original question");
            return List.of();
        } catch (RuntimeException exception) {
            LOGGER.info("Cross-language retrieval rewrite unavailable; continuing with the original question");
            return List.of();
        } finally {
            permit.close();
        }
    }

    private ModelRequest toRequest(
            UnderstoodQuestion question, QuestionContext context, List<HybridEvidenceHit> evidence) {
        return new ModelRequest(
                question.normalizedQuestion(),
                question.type(),
                new AnswerContext(
                        context.currentLessonSection(),
                        context.gamePhase(),
                        context.playerCount(),
                        context.activeExpansions().size(),
                        context.previousQuestion(),
                        context.learningIntent(),
                        context.outputLanguage()),
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(hit -> new EvidenceInput(
                                hit.chunkId(), hit.sectionType(), hit.heading(), hit.excerpt(), hit.pageFrom(), hit.pageTo()))
                        .toList());
    }

    private RunSnapshot finishRun(RunSnapshot run, StructuredRuleAnswer answer) {
        for (AnswerRunProgressPolicy.ProgressUpdate update : AnswerRunProgressPolicy.updatesFor(answer)) {
            run = advance(run, update.state(), update.summary());
        }
        return run;
    }

    private RunSnapshot advance(RunSnapshot run, AssistantRunState state, String summary) {
        return runs.advance(run.id(), run.revision(), state, summary);
    }

    private void failRun(RunSnapshot run, String errorCode, String summary, RuntimeException exception) {
        if (!run.state().terminal()) {
            try {
                runs.fail(run.id(), run.revision(), errorCode, summary);
            } catch (RuntimeException trackingFailure) {
                exception.addSuppressed(trackingFailure);
            }
        }
    }

    private int evidenceTokens(List<HybridEvidenceHit> evidence) {
        return evidence.stream().mapToInt(hit -> estimateTokens(hit.evidence().excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    public record AnswerCreation(UUID assistantRunId, StructuredRuleAnswer answer) {}

    private enum RetrievalState { READY, CONFLICTING, UNAVAILABLE }

    private record RetrievalResult(List<HybridEvidenceHit> evidence, RetrievalState state) {}

    private StructuredRuleAnswer fromConfirmedRuling(ConfirmedRulingLookup.ConfirmedAnswer ruling) {
        List<RuleCitation> citations = ruling.citations().stream().map(citation -> new RuleCitation(
                citation.chunkId(), citation.documentVersionId(), citation.sectionType(), citation.heading(),
                citation.excerpt(), citation.pageFrom(), citation.pageTo())).toList();
        return new StructuredRuleAnswer(
                ruling.documentVersionId(), AnswerStatus.ANSWERED, ruling.shortVerdict(), ruling.explanation(),
                citations, ruling.exceptions(), AnswerConfidence.valueOf(ruling.confidence()), ruling.official(),
                ruling.rulingId(), ruling.version(), null);
    }

    private StructuredRuleAnswer clarification(UnderstoodQuestion question) {
        String missing = question.missingContext().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        return new StructuredRuleAnswer(
                question.documentVersionId(), AnswerStatus.CLARIFICATION_REQUIRED,
                "需要补充上下文后才能查证规则。", "缺少信息：" + missing, List.of(), List.of(),
                AnswerConfidence.LOW, false, null, null, "请补充 " + missing + "。");
    }

    private StructuredRuleAnswer safe(UUID versionId, AnswerStatus status, String message) {
        return new StructuredRuleAnswer(
                versionId, status, message, message, List.of(), List.of(), AnswerConfidence.LOW,
                false, null, null, null);
    }
}
