package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
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
import java.util.function.Function;
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
    private final EvidenceVerifier evidenceVerifier;
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
        this.evidenceVerifier = evidenceVerifier;
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
        var evidenceVerification = evidenceVerifier.verify(new VerificationRequest(
                context.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()));
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
        List<String> playerFacingRepair = playerFacingRepairFeedback(modelRequest, draft);
        if (!playerFacingRepair.isEmpty()) {
            try {
                draft = revisePlayerFacingDraft(
                        assistantRunId, username, gameSessionId, modelRequest, draft, playerFacingRepair);
                boolean inactiveActorRepair = playerFacingRepair.stream().anyMatch(item -> item.startsWith("INACTIVE_ACTOR:"));
                if ((draft == null || !draft.answerable())
                        && inactiveActorRepair
                        && AnswerDraftSafetyPolicy.hasEvidencedSuccessorRule(modelRequest)) {
                    ModelDraft abstainingDraft = draft == null
                            ? new ModelDraft(false, "First repair did not produce a draft", null, null, List.of(), List.of(), "LOW")
                            : draft;
                    List<String> retryFeedback = new ArrayList<>(playerFacingRepair);
                    retryFeedback.add("EVIDENCED_SUCCESSOR_RULE: The supplied evidence explicitly contains both the "
                            + "state-change condition and its replacement or successor actor. Apply that exact "
                            + "conditional rule directly; do not abstain and do not fall back to the default actor.");
                    draft = revisePlayerFacingDraft(
                            assistantRunId, username, gameSessionId, modelRequest, abstainingDraft, retryFeedback);
                }
            } catch (RuleAnswerModelTimeoutException exception) {
                return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "视觉规则消歧超时，可以稍后重试或直接查看规则引用。");
            } catch (RuntimeException exception) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答修订结果未通过结构校验。");
            }
            if (draft == null || !draft.answerable()) {
                boolean inactiveActorRepair = playerFacingRepair.stream().anyMatch(item -> item.startsWith("INACTIVE_ACTOR:"));
                String message = inactiveActorRepair
                        ? "现有证据未能确定状态变化后的下一位行动者。"
                        : "图标对应的规则资源无法从现有证据中可靠确定。";
                return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, message);
            }
            draft = AnswerDraftSafetyPolicy.normalizeSingleMappedVisualGlyph(
                    draft, AnswerVisualEvidencePolicy.resolvedComponents(modelRequest, draft));
            draft = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(draft);
            draft = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(draft);
            if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。");
            }
            if (AnswerDraftSafetyPolicy.containsResourceCardConflation(draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答混淆了规则资源与手牌数量，未向玩家发布。");
            }
            if (AnswerDraftSafetyPolicy.containsInactiveActorContinuation(draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答让已退出当前流程的玩家继续行动，未向玩家发布。");
            }
            if (AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(modelRequest, draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答附加了未被引用支持的次数限制，未向玩家发布。");
            }
            if (!AnswerVisualEvidencePolicy.namesEveryResolvedComponent(modelRequest, draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答未使用视觉证据确认的组件名称，未向玩家发布。");
            }
            if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(modelRequest)
                    && AnswerDraftSafetyPolicy.containsVisualGlyph(draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答用近似符号替代了规则书组件名称，未向玩家发布。");
            }
            if (AnswerVisualEvidencePolicy.requiresIdentityReconciliation(modelRequest, draft)
                    && AnswerDraftSafetyPolicy.containsUnresolvedVisualSymbol(draft)) {
                return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "图标对应的规则资源无法从现有证据中可靠确定。");
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
            answer = validate(context.documentVersionId(), draft, evidence);
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        try {
            ReviewRisk risk = gameSessionId != null || context.previousQuestion() != null || context.learningIntent() != null
                    ? ReviewRisk.HIGH_IMPACT
                    : answer.confidence() == AnswerConfidence.LOW
                            ? ReviewRisk.LOW_CONFIDENCE
                            : ReviewRisk.STANDARD;
            Review review = critic.review(
                    toCriticRequest(assistantRunId, understood, context, answer, evidence), risk);
            if (!review.accepted()) {
                if (context.learningIntent() == null && gameSessionId == null) {
                    return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答未通过事实一致性审查。");
                }
                answer = reviseLearningResponse(
                        assistantRunId, understood, context, username, gameSessionId,
                        modelRequest, draft, review, evidence);
                Review revisionReview = critic.review(
                        toCriticRequest(assistantRunId, understood, context, answer, evidence),
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
            UnderstoodQuestion understood,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            Review review,
            List<HybridEvidenceHit> evidence) {
        List<String> feedback = review.issues().stream()
                .map(issue -> issue.type().name() + ": " + issue.summary())
                .toList();
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
        return validate(context.documentVersionId(), revised, evidence);
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

    private List<String> playerFacingRepairFeedback(ModelRequest request, ModelDraft draft) {
        List<String> feedback = new ArrayList<>();
        if (AnswerEvidencePolicy.requiresEndTurnProcedureCitation(request.question(), request.evidence())
                && !AnswerEvidencePolicy.citesEndTurnProcedure(request.evidence(), draft.citationIds())) {
            feedback.add("END_TURN_PROCEDURE_CITATION: The question asks what happens after a player finishes a turn. "
                    + "Cite the supplied excerpt that explicitly connects turn end to drawing, revealing, reading, "
                    + "resolving, or executing the event/card effect. Setup instructions that only place that deck "
                    + "or card area are not sufficient evidence for the end-of-turn procedure.");
        }
        if (AnswerEvidencePolicy.requiresEndgameResolutionCitation(request.question(), request.evidence())
                && !AnswerEvidencePolicy.citesEndgameResolution(request.question(), request.evidence(), draft.citationIds())) {
            feedback.add("ENDGAME_RESOLUTION_CITATION: The question asks about an end trigger, end-of-round timing, "
                    + "final scoring, winner, or tie. Cite the supplied excerpt that states the actual end-game "
                    + "condition and resolution sequence. A component or inventory excerpt that merely names a "
                    + "marker, card, or resource cannot support that timing, scoring, or tie ruling. Preserve the "
                    + "printed order, including any numbered cleanup check, and do not invent a separate phase.");
        }
        if (AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(request, draft)) {
            feedback.add("EXACT_PHASE_NAME: The draft uses an English multi-word phase label that does not appear in "
                    + "its cited excerpts. Remove it rather than inventing a phase. If the source has a numbered "
                    + "end-game check within cleanup, state that evidenced check and its order directly.");
        }
        if (AnswerVisualEvidencePolicy.requiresIdentityReconciliation(request, draft)) {
            feedback.add("VISUAL_IDENTITY: The draft relies on an unresolved icon, color, shape, emoji, or guessed "
                    + "resource name. Reconcile every supplied page before answering. Do not reject an entire visual "
                    + "fact merely because it also transcribes an icon glyph. A mapping is resolved when the evidence "
                    + "explicitly says the operational icon is visually identical to an exact printed component label "
                    + "on another supplied page and that labeled page is also supplied. Treat a name as an untrusted "
                    + "guess only when it is based solely on emoji, color, shape, 'likely', or '可能'. Then verify "
                    + "the mapping against starting quantities, public/hidden placement, thresholds, and worked "
                    + "arithmetic. Cite both the labeled page and operational page. Use only the printed component "
                    + "term in player-facing text and emit no icon glyph. If one mapping is not directly supportable, "
                    + "set answerable to false.");
        }
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
            feedback.add("PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                    + "and other internal references. Teach the rule directly while preserving the same citations in "
                    + "the structured citationIds field.");
        }
        if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)
                && AnswerDraftSafetyPolicy.containsResourceCardConflation(draft)) {
            feedback.add("RESOURCE_CARD_CONFLATION: Cross-page evidence maps the missing inline icon to a named "
                    + "token, point, or other component. Remove every claim that turns that same icon prerequisite "
                    + "into cards in hand, a minimum card count, or a numeric card value. A consequence that the "
                    + "player starts the phase with fewer cards is separate from the component required to activate "
                    + "the effect. Keep only prerequisites directly stated by the supplied evidence.");
        }
        List<String> resolvedComponents = AnswerVisualEvidencePolicy.resolvedComponents(request, draft);
        if (!resolvedComponents.isEmpty()
                && !AnswerVisualEvidencePolicy.namesEveryResolvedComponent(request, draft)) {
            feedback.add("RESOLVED_VISUAL_COMPONENT: The supplied cross-page visual evidence explicitly resolves "
                    + "the operational icon to these original-language component labels: " + resolvedComponents
                    + ". The shortVerdict must include each applicable exact label in its original language, alongside "
                    + "a faithful Chinese translation if useful. Do not negate that mapped label or replace it with "
                    + "another component from the reference page.");
        }
        if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)
                && AnswerDraftSafetyPolicy.containsVisualGlyph(draft)) {
            feedback.add("MAPPED_COMPONENT_GLYPH: Remove every emoji or improvised symbol from the player-facing "
                    + "answer. A visually similar glyph can depict a different rulebook component. Use only the "
                    + "exact printed component label resolved by the supplied cross-page evidence; the UI will "
                    + "show the original cited page image.");
        }
        if (AnswerDraftSafetyPolicy.containsInactiveActorContinuation(draft)) {
            feedback.add("INACTIVE_ACTOR: The draft says an actor has emptied their hand or left active play, but "
                    + "also assigns that same actor the next action. Do not apply the default next-actor rule across "
                    + "that state change. Use the supplied evidence's explicit replacement, skip, or successor rule; "
                    + "if the evidence does not resolve the successor, set answerable to false.");
        }
        if (AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(request, draft)) {
            feedback.add("UNASKED_REPEATABILITY: The answer adds a once-only, twice-only, repeatability, or "
                    + "loop-prevention restriction that the player did not ask about and the draft's own cited "
                    + "evidence does not establish for this ruling. Remove that peripheral restriction. Keep a "
                    + "repeatability boundary only when the question asks about it or cite the exact evidence that "
                    + "governs the same action.");
        }
        return List.copyOf(feedback);
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
        Map<UUID, HybridEvidenceHit> selected = new LinkedHashMap<>();
        List<HybridEvidenceHit> selectedVisualEvidence = visualEvidenceIds.stream()
                .map(evidenceById::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(HybridEvidenceHit::score)
                        .reversed()
                        .thenComparing(hit -> hit.evidence().chunkId()))
                .toList();
        List<HybridEvidenceHit> selectedIntentAnchors = intentAnchors.values().stream()
                .map(hit -> evidenceById.get(hit.evidence().chunkId()))
                .filter(java.util.Objects::nonNull)
                .filter(hit -> visualEvidenceIds.isEmpty() || !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                .toList();
        boolean visualEvidencePriority = AnswerEvidencePolicy.visualEvidencePriority(question.normalizedQuestion());
        if (visualEvidencePriority) {
            selectedVisualEvidence.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
            selectedIntentAnchors.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        } else {
            selectedIntentAnchors.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
            selectedVisualEvidence.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        if (selected.size() < 3) {
            evidenceById.values().stream()
                    .filter(hit -> visualEvidenceIds.isEmpty() || !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                    .sorted(Comparator.comparingDouble(HybridEvidenceHit::score)
                            .reversed()
                            .thenComparing(hit -> hit.evidence().chunkId()))
                    .forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        List<HybridEvidenceHit> selectedEvidence = selected.values().stream().limit(5).toList();
        if (AnswerEvidencePolicy.isEndgameResolutionQuestion(question.normalizedQuestion())) {
            List<HybridEvidenceHit> decisiveEvidence = selectedEvidence.stream()
                    .filter(AnswerEvidencePolicy::hasEndgameResolution)
                    .sorted(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore).reversed())
                    .limit(1)
                    .toList();
            if (!decisiveEvidence.isEmpty()) {
                LinkedHashMap<UUID, HybridEvidenceHit> complementaryEvidence = new LinkedHashMap<>();
                decisiveEvidence.forEach(hit -> complementaryEvidence.put(hit.evidence().chunkId(), hit));
                String normalizedQuestion = question.normalizedQuestion().toLowerCase(Locale.ROOT);
                if (AnswerEvidencePolicy.asksScoring(normalizedQuestion)) {
                    selectedEvidence.stream()
                            .filter(hit -> AnswerEvidencePolicy.hasEndgameScoring(hit.evidence().excerpt()))
                            .findFirst()
                            .ifPresent(hit -> complementaryEvidence.putIfAbsent(hit.evidence().chunkId(), hit));
                }
                if (AnswerEvidencePolicy.asksTie(normalizedQuestion)) {
                    selectedEvidence.stream()
                            .filter(hit -> AnswerEvidencePolicy.hasEndgameTie(hit.evidence().excerpt()))
                            .findFirst()
                            .ifPresent(hit -> complementaryEvidence.putIfAbsent(hit.evidence().chunkId(), hit));
                }
                UUID decisiveId = decisiveEvidence.getFirst().evidence().chunkId();
                List<HybridEvidenceHit> timingEvidence = selectedEvidence.stream()
                        .filter(this::hasEvidencedEndgameTiming)
                        .filter(hit -> !decisiveId.equals(hit.evidence().chunkId()))
                        .limit(1)
                        .toList();
                timingEvidence.forEach(hit -> complementaryEvidence.putIfAbsent(hit.evidence().chunkId(), hit));
                selectedEvidence = complementaryEvidence.values().stream().toList();
            }
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
                        .filter(this::hasEvidencedEndgameTiming)
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

    private boolean hasEvidencedEndgameTiming(HybridEvidenceHit hit) {
        if (hit == null) return false;
        String text = (hit.evidence().heading() + "\n" + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        return text.contains("when the round ends")
                || text.contains("end of a round")
                || text.contains("end of the round")
                || text.contains("ending the round")
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

    private StructuredRuleAnswer validate(UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence) {
        if (draft.shortVerdict() == null || draft.shortVerdict().isBlank() || draft.shortVerdict().length() > 240
                || draft.explanation() == null || draft.explanation().isBlank() || draft.explanation().length() > 1500
                || draft.citationIds().isEmpty() || draft.exceptions().size() > 6
                || draft.exceptions().stream()
                        .anyMatch(exception -> exception == null || exception.isBlank() || exception.length() > 400)) {
            throw new IllegalArgumentException("model draft is incomplete");
        }
        String completeAnswer = draft.shortVerdict() + "\n" + draft.explanation() + "\n"
                + String.join("\n", draft.exceptions());
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(completeAnswer)) {
            throw new IllegalArgumentException("player-facing answer contains internal evidence references");
        }
        var verification = evidenceVerifier.verify(new VerificationRequest(
                versionId,
                evidence.stream().map(this::toVerifierEvidence).toList(),
                List.of(new EvidenceClaim(completeAnswer, draft.citationIds()))));
        if (!verification.verified()) {
            throw new IllegalArgumentException("answer evidence did not pass policy verification");
        }
        Map<UUID, HybridEvidenceHit> allowed = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        hit -> hit.evidence().chunkId(), Function.identity(), (first, duplicate) -> first));
        List<RuleCitation> citations = draft.citationIds().stream().distinct().map(id -> {
            HybridEvidenceHit hit = allowed.get(id);
            if (hit == null || !versionId.equals(hit.evidence().documentVersionId())) {
                throw new IllegalArgumentException("model cited evidence outside the allowed scope");
            }
            var source = hit.evidence();
            return new RuleCitation(
                    source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                    source.excerpt(), source.pageFrom(), source.pageTo());
        }).toList();
        AnswerConfidence confidence = AnswerConfidence.valueOf(draft.confidence().toUpperCase(Locale.ROOT));
        return new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, draft.shortVerdict(), draft.explanation(), citations,
                draft.exceptions(), confidence, false, null, null, null);
    }

    private EvidenceSource toVerifierEvidence(HybridEvidenceHit hit) {
        var source = hit.evidence();
        return new EvidenceSource(
                source.chunkId(), source.documentVersionId(), source.sectionType(), source.excerpt(),
                source.pageFrom(), source.pageTo());
    }

    private ReviewRequest toCriticRequest(
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
                                + "; preserve material exceptions for lesson section "
                                + contextValue(context.currentLessonSection()) + ", game phase "
                                + contextValue(context.gamePhase()) + ", and player count "
                                + contextValue(context.playerCount())
                                + ", and learning intent " + contextValue(context.learningIntent())
                                + ". Preserve every named eligibility and identity condition. Reject any claim that a condition is irrelevant, optional, or broader than stated unless evidence explicitly says so."
                                + " For an 'again' follow-up, reject any repeatability claim not explicitly supported by evidence."),
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
    }

    private String contextValue(Object value) {
        return value == null ? "not provided" : value.toString();
    }

    private RunSnapshot finishRun(RunSnapshot run, StructuredRuleAnswer answer) {
        run = advance(run, AssistantRunState.QUESTION_UNDERSTANDING, "Question context is normalized");
        if (answer.status() == AnswerStatus.CLARIFICATION_REQUIRED) {
            return advance(run, AssistantRunState.NEED_CLARIFICATION, "Question requires additional context");
        }
        run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, "Answer evidence scope is planned");
        run = advance(run, AssistantRunState.RETRIEVING, "Allow-listed answer source lookup completed");
        run = advance(run, AssistantRunState.VERIFYING_EVIDENCE, "Answer source scope is policy checked");
        if (answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE || answer.status() == AnswerStatus.VERSION_CONFLICT) {
            return advance(run, AssistantRunState.INSUFFICIENT_EVIDENCE, "Answer evidence is insufficient");
        }
        run = advance(run, AssistantRunState.ANSWER_COMPOSITION, "Structured cited answer is composed");
        if (answer.status() != AnswerStatus.ANSWERED) {
            return advance(run, AssistantRunState.DEGRADED, "Answer generation degraded safely");
        }
        if (answer.confidence() == AnswerConfidence.LOW) {
            run = advance(run, AssistantRunState.CRITIQUING, "Low-confidence answer critique completed");
        }
        return advance(run, AssistantRunState.COMPLETED, "Question workflow completed");
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
