package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
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
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
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
import java.util.Locale;
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
    private static final String ANSWER_POLICY_VERSION = "answer-v62-grounded-application-spatial-scope";
    private final QuestionUnderstanding understanding;
    private final AnswerModelGateway modelGateway;
    private final AnswerEvidenceRetriever evidenceRetriever;
    private final AnswerModelRequestFactory modelRequestFactory;
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
        this.modelGateway = new AnswerModelGateway(model, rateLimiter, invocations);
        this.evidenceRetriever = new AnswerEvidenceRetriever(
                retrieval, visualFacts, evidenceLookup, invocations, modelGateway);
        this.modelRequestFactory = new AnswerModelRequestFactory();
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
                        answer.answerBasis() == null ? null : answer.answerBasis().name(),
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
        AnswerEvidenceRetriever.Result retrievalResult = evidenceRetriever.retrieve(assistantRunId, understood, context, username);
        List<HybridEvidenceHit> evidence = retrievalResult.evidence();
        if (retrievalResult.state() == AnswerEvidenceRetriever.State.CONFLICTING) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突，无法可靠回答。");
        }
        if (retrievalResult.state() == AnswerEvidenceRetriever.State.UNAVAILABLE) {
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
        try {
            modelRequest = modelRequestFactory.create(understood, context, evidence);
            draft = modelGateway.compose(assistantRunId, username, gameSessionId, modelRequest);
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
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
        draft = AnswerSpatialScopePolicy.boundRepeatedInference(modelRequest, draft);
        draft = AnswerBasisPolicy.classify(modelRequest, draft);
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
                + "When two or more cited premises jointly determine the result for the exact table condition the player "
                + "stated, provide a bounded grounded application: identify the condition, apply only those premises, "
                + "and label any still-unknown branch instead of refusing. Remain unanswerable only when the excerpts "
                + "cannot support either a direct conclusion or a bounded conditional application.";
        String feedback = AnswerReplenishmentPolicy.hasEvidencedProcedure(modelRequest)
                ? baseFeedback + " DIRECT_REPLENISHMENT_PROCEDURE: A supplied excerpt explicitly gives the sequence for "
                        + "continuing when the named draw or supply area becomes empty. Apply that stated sequence to "
                        + "a question about reaching the required draw amount, and cite its source. Do not abstain merely "
                        + "because the player did not state how many items were present before that area became empty."
                : baseFeedback;
        return modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                List.of(feedback),
                "reconsiderEvidenceBackedAbstention",
                "Evidence-backed table abstention reconsidered");
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
                draft.confidence(),
                draft.answerBasis());
    }

    private ModelDraft revisePlayerFacingDraft(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            List<String> feedback) {
        return modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                feedback,
                "repairPlayerFacingRuleAnswer",
                "Ambiguous visual identity or internal evidence language repaired");
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

    private static RuleEvidenceLookup emptyEvidenceLookup() {
        return (documentVersionId, chunkIds) -> List.of();
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

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    public record AnswerCreation(UUID assistantRunId, StructuredRuleAnswer answer) {}

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
