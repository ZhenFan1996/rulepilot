package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
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
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
            MeterRegistry metrics) {
        this.understanding = understanding;
        this.retrieval = retrieval;
        this.model = model;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.ruleDataVersion = ruleDataVersion;
        this.confirmedRulings = confirmedRulings;
        this.cacheHits = metrics.counter("rulepilot.answer.cache.requests", "result", "hit");
        this.cacheMisses = metrics.counter("rulepilot.answer.cache.requests", "result", "miss");
        this.cacheReadErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "read");
        this.cacheWriteErrors = metrics.counter("rulepilot.answer.cache.errors", "operation", "write");
        this.confirmedRulingHits = metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling");
    }

    StructuredRuleAnswer answer(String question, QuestionContext context) {
        return answer(question, context, "test-user", null);
    }

    public StructuredRuleAnswer answer(
            String question, QuestionContext context, String username, UUID gameSessionId) {
        UnderstoodQuestion understood = understanding.understand(question, context);
        if (understood.needsClarification()) {
            return clarification(understood);
        }
        var confirmed = confirmedRulings.find(
                context.documentVersionId(), context.activeExpansions(), understood.normalizedQuestion(), username);
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
        List<HybridEvidenceHit> evidence = retrieval.search(
                context.documentVersionId(),
                understood.normalizedQuestion(),
                new RetrievalOptions(5, Set.of(), context.currentLessonSection()));
        if (evidence.isEmpty()) {
            return safe(context.documentVersionId(), AnswerStatus.INSUFFICIENT_EVIDENCE, "没有找到可引用的规则依据。");
        }
        if (evidence.stream().anyMatch(hit -> !context.documentVersionId().equals(hit.evidence().documentVersionId()))) {
            return safe(context.documentVersionId(), AnswerStatus.VERSION_CONFLICT, "检索证据与当前规则版本不一致。");
        }
        ModelDraft draft;
        RuleAnswerRateLimiter.Permit permit =
                rateLimiter.acquireModel(username, gameSessionId, model.providerId());
        try {
            draft = model.compose(toRequest(understood, evidence));
        } catch (RuleAnswerModelTimeoutException exception) {
            return safe(context.documentVersionId(), AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
        } finally {
            permit.close();
        }
        StructuredRuleAnswer answer;
        try {
            answer = validate(context.documentVersionId(), draft, evidence);
        } catch (RuntimeException exception) {
            return safe(context.documentVersionId(), AnswerStatus.INVALID_MODEL_OUTPUT, "回答生成结果未通过结构或引用校验。");
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

    private ModelRequest toRequest(UnderstoodQuestion question, List<HybridEvidenceHit> evidence) {
        return new ModelRequest(
                question.normalizedQuestion(),
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(hit -> new EvidenceInput(
                                hit.chunkId(), hit.sectionType(), hit.heading(), hit.excerpt(), hit.pageFrom(), hit.pageTo()))
                        .toList());
    }

    private StructuredRuleAnswer validate(UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence) {
        if (draft == null || draft.shortVerdict() == null || draft.shortVerdict().isBlank()
                || draft.explanation() == null || draft.explanation().isBlank() || draft.citationIds().isEmpty()) {
            throw new IllegalArgumentException("model draft is incomplete");
        }
        Map<UUID, HybridEvidenceHit> allowed = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(hit -> hit.evidence().chunkId(), Function.identity()));
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
