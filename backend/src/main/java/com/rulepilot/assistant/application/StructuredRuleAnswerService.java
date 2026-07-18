package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
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
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class StructuredRuleAnswerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerService.class);

    private final QuestionUnderstanding understanding;
    private final HybridRuleSearch retrieval;
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
        this.understanding = understanding;
        this.retrieval = retrieval;
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

    StructuredRuleAnswer answer(String question, QuestionContext context) {
        return answerInternal(question, context, "test-user", null, UUID.randomUUID());
    }

    public AnswerCreation answerWithRun(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        return Observation.createNotStarted("rulepilot.answer.workflow", observations)
                .contextualName("answer-workflow")
                .observe(() -> answerWithRunObserved(question, context, username, gameSessionId));
    }

    private AnswerCreation answerWithRunObserved(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        RunSnapshot run = runs.start(
                AssistantRunMode.QUESTION_ANSWER,
                gameSessionId == null ? context.documentVersionId() : gameSessionId,
                username);
        try {
            StructuredRuleAnswer answer = answerInternal(question, context, username, gameSessionId, run.id());
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
        AnswerCacheKey cacheKey = cacheKey(understood, context);
        var cached = findCached(cacheKey);
        if (cached.isPresent()) {
            cacheHits.increment();
            return cached.get();
        }
        cacheMisses.increment();
        RetrievalResult retrievalResult = retrieveEvidence(assistantRunId, understood, context);
        List<HybridEvidenceHit> evidence = retrievalResult.evidence();
        if (retrievalResult.conflicting()) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突，无法可靠回答。");
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
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            ModelRequest modelRequest = toRequest(understood, context, evidence);
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
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "现有证据未能直接回答这个问题。");
        }
        StructuredRuleAnswer answer;
        try {
            answer = validate(context.documentVersionId(), draft, evidence);
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        }
        try {
            ReviewRisk risk = answer.confidence() == AnswerConfidence.LOW
                    ? ReviewRisk.LOW_CONFIDENCE
                    : ReviewRisk.STANDARD;
            if (!critic.review(toCriticRequest(assistantRunId, answer, evidence), risk).accepted()) {
                return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答未通过事实一致性审查。");
            }
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答事实一致性审查失败。");
        }
        saveCached(cacheKey, answer);
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

    private AnswerCacheKey cacheKey(UnderstoodQuestion question, QuestionContext context) {
        return new AnswerCacheKey(
                context.documentVersionId(), ruleDataVersion.current(context.documentVersionId()),
                question.normalizedQuestion(), context.currentLessonSection(),
                context.gamePhase(), context.playerCount(), context.activeExpansions());
    }

    private RetrievalResult retrieveEvidence(
            UUID assistantRunId, UnderstoodQuestion question, QuestionContext context) {
        Map<UUID, HybridEvidenceHit> evidenceById = new LinkedHashMap<>();
        boolean conflicting = false;
        for (RetrievalIntent intent : AnswerRetrievalPlanner.plan(question, context)) {
            try {
                List<HybridEvidenceHit> retrieved = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "hybridRuleSearch",
                        estimateTokens(intent.query()),
                        "Version-scoped answer evidence retrieved",
                        () -> retrieval.search(
                                context.documentVersionId(),
                                intent.query(),
                                new RetrievalOptions(3, intent.sectionTypes(), intent.currentSectionType())),
                        this::evidenceTokens);
                for (HybridEvidenceHit hit : retrieved) {
                    HybridEvidenceHit existing = evidenceById.putIfAbsent(hit.evidence().chunkId(), hit);
                    if (existing != null && !sameEvidenceSnapshot(existing, hit)) {
                        conflicting = true;
                        break;
                    }
                }
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException retrievalFailure) {
                LOGGER.warn(
                        "Answer retrieval intent failed for document version {}: {}",
                        context.documentVersionId(),
                        retrievalFailure.getClass().getSimpleName());
            }
            if (conflicting) {
                break;
            }
        }
        if (conflicting) {
            return new RetrievalResult(List.of(), true);
        }
        return new RetrievalResult(evidenceById.values().stream().limit(5).toList(), false);
    }

    private boolean sameEvidenceSnapshot(HybridEvidenceHit first, HybridEvidenceHit second) {
        var left = first.evidence();
        var right = second.evidence();
        return left.chunkId().equals(right.chunkId())
                && left.documentVersionId().equals(right.documentVersionId())
                && left.sectionType().equals(right.sectionType())
                && left.heading().equals(right.heading())
                && left.excerpt().equals(right.excerpt())
                && left.pageFrom() == right.pageFrom()
                && left.pageTo() == right.pageTo();
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
                        context.activeExpansions().size()),
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
            UUID assistantRunId, StructuredRuleAnswer answer, List<HybridEvidenceHit> evidence) {
        List<UUID> citationIds = answer.citations().stream().map(RuleCitation::chunkId).toList();
        List<Claim> claims = new ArrayList<>();
        claims.add(new Claim(1, answer.shortVerdict() + "\n" + answer.explanation(), citationIds));
        for (int index = 0; index < answer.exceptions().size(); index++) {
            claims.add(new Claim(index + 2, answer.exceptions().get(index), citationIds));
        }
        return new ReviewRequest(
                assistantRunId,
                ContentType.ANSWER,
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
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

    private record RetrievalResult(List<HybridEvidenceHit> evidence, boolean conflicting) {}

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
