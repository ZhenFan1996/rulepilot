package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Acquires and reconciles source-scoped answer evidence; it never produces a player-facing rule conclusion. */
public final class AnswerEvidenceRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerEvidenceRetriever.class);
    private final HybridRuleSearch retrieval;
    private final VisualRulebookPageFactSearch visualFacts;
    private final RuleEvidenceLookup evidenceLookup;
    private final AnswerRetrievalInvocations invocations;
    private final AnswerRetrievalQueryRewriter queryRewriter;
    private final AnswerVisualEvidenceEnricher visualEvidenceEnricher;

    public AnswerEvidenceRetriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            AnswerRetrievalInvocations invocations,
            AnswerRetrievalQueryRewriter queryRewriter) {
        this.retrieval = retrieval;
        this.visualFacts = visualFacts;
        this.evidenceLookup = evidenceLookup;
        this.invocations = invocations;
        this.queryRewriter = queryRewriter;
        this.visualEvidenceEnricher = new AnswerVisualEvidenceEnricher(evidenceLookup, invocations);
    }

    public Result retrieve(
            UUID assistantRunId,
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            String username) {
        return retrieve(
                assistantRunId,
                question,
                context,
                username,
                AnswerRetrievalPlan.fallback(question));
    }

    public Result retrieve(
            UUID assistantRunId,
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            String username,
            AnswerRetrievalPlan questionPlan) {
        Map<UUID, HybridEvidenceHit> evidenceById = new LinkedHashMap<>();
        Map<UUID, HybridEvidenceHit> intentAnchors = new LinkedHashMap<>();
        Map<Integer, PageFactMatch> visualFactsByPage = new LinkedHashMap<>();
        Set<Integer> directQuestionVisualFactPages = new LinkedHashSet<>();
        boolean visualRequested = questionPlan.evidenceNeeds().contains(EvidenceNeed.VISUAL_REFERENCE);
        boolean conflicting = false;
        int successfulCoreRetrievals = 0;
        int failedCoreRetrievals = 0;
        retrieveIdentifierBoundVisualFacts(
                assistantRunId,
                context.documentVersionId(),
                question.normalizedQuestion(),
                visualFactsByPage,
                directQuestionVisualFactPages);
        List<String> rewrittenQueries = rewriteCrossLanguageQueries(assistantRunId, question, context, username);
        List<RetrievalIntent> intents = AnswerRetrievalPlanner.plan(
                question, context, rewrittenQueries, questionPlan);
        for (int intentIndex = 0; intentIndex < intents.size(); intentIndex++) {
            RetrievalIntent intent = intents.get(intentIndex);
            List<HybridEvidenceHit> retrieved;
            try {
                retrieved = invocations.invoke(
                        assistantRunId,
                        "hybridRuleSearch",
                        estimateTokens(intent.query()),
                        "Version-scoped answer evidence retrieved",
                        () -> retrieval.search(
                                context.documentVersionId(),
                                intent.query(),
                                new RetrievalOptions(
                                        questionPlan.subquestions().size() > 1
                                                        || questionPlan.evidenceNeeds().contains(EvidenceNeed.COMPLETE_LIST)
                                                ? 8
                                                : 5,
                                        intent.sectionTypes(),
                                        intent.currentSectionType())),
                        this::evidenceTokens);
                successfulCoreRetrievals++;
            } catch (RuntimeException retrievalFailure) {
                if (invocations.executionStopped(retrievalFailure)) throw retrievalFailure;
                failedCoreRetrievals++;
                LOGGER.warn(
                        "Answer retrieval intent failed for document version {}: {}",
                        context.documentVersionId(),
                        retrievalFailure.getClass().getSimpleName());
                continue;
            }
            boolean visualTranscriptionFallback = retrieved.isEmpty()
                    || retrieved.stream().anyMatch(AnswerEvidencePolicy::isVisualPlaceholder);
            // The last intent is deliberately broad recall support. It may fill a genuine retrieval gap, but it
            // must never become the answer's primary anchor merely because it happens to rank a generic paragraph.
            boolean supplementaryIntent = intentIndex == intents.size() - 1;
            List<HybridEvidenceHit> answerEvidence = retrieved;
            selectIntentAnchor(intent, answerEvidence, supplementaryIntent, intentAnchors);
            for (HybridEvidenceHit hit : answerEvidence) {
                HybridEvidenceHit existing = evidenceById.get(hit.evidence().chunkId());
                if (existing != null && !AnswerEvidencePolicy.sameEvidenceSnapshot(existing, hit)) {
                    conflicting = true;
                    break;
                }
                if (existing == null || hit.score() > existing.score()) {
                    evidenceById.put(hit.evidence().chunkId(), hit);
                }
            }
            // A text chunk can be non-empty yet still be the wrong subsection on a compact illustrated page. The
            // page-fact index is a cheap, document-scoped locator, so every direct subquestion gets one bounded
            // lookup instead of reserving it only for image placeholders or explicitly visual questions.
            if (visualRequested
                    || visualTranscriptionFallback
                    || intent.directQuestion()
                    || !directQuestionVisualFactPages.isEmpty()) try {
                List<PageFactMatch> visualMatches = invocations.invoke(
                        assistantRunId,
                        "searchVisualRulebookPageFacts",
                        estimateTokens(intent.query()),
                        "Page-scoped visual rule facts retrieved",
                        () -> visualFacts.search(context.documentVersionId(), intent.query(), 2),
                        matches -> matches.size() * 80);
                visualMatches.forEach(match -> visualFactsByPage.merge(
                        match.pageNumber(), match, (first, candidate) -> candidate.score() > first.score() ? candidate : first));
                if (intent.directQuestion()) {
                    visualMatches.forEach(match -> directQuestionVisualFactPages.add(match.pageNumber()));
                }
            } catch (RuntimeException visualLookupFailure) {
                if (invocations.executionStopped(visualLookupFailure)) throw visualLookupFailure;
                LOGGER.warn(
                        "Optional visual fact lookup failed for document version {}: {}",
                        context.documentVersionId(),
                        visualLookupFailure.getClass().getSimpleName());
            }
            if (conflicting) {
                break;
            }
        }
        if (!conflicting && visualRequested) {
            try {
                List<PageFactMatch> legendMatches = invocations.invoke(
                        assistantRunId,
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
            } catch (RuntimeException lookupFailure) {
                if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
                LOGGER.warn(
                        "Optional icon-legend lookup failed for document version {}: {}",
                        context.documentVersionId(),
                        lookupFailure.getClass().getSimpleName());
            }
        }
        if (conflicting) {
            return new Result(List.of(), State.CONFLICTING);
        }
        if (successfulCoreRetrievals == 0 && failedCoreRetrievals > 0) {
            return new Result(List.of(), State.UNAVAILABLE);
        }
        Set<Integer> visualPagePriority = new LinkedHashSet<>(directQuestionVisualFactPages);
        Set<UUID> visualEvidenceIds = visualEvidenceEnricher.enrich(
                assistantRunId, context.documentVersionId(), evidenceById, visualFactsByPage, visualPagePriority);
        List<HybridEvidenceHit> selectedEvidence = AnswerEvidenceSelectionPolicy.select(
                evidenceById, intentAnchors.values(), visualEvidenceIds, questionPlan, List.of());
        return new Result(selectedEvidence, State.READY);
    }

    private void retrieveIdentifierBoundVisualFacts(
            UUID assistantRunId,
            UUID documentVersionId,
            String normalizedQuestion,
            Map<Integer, PageFactMatch> visualFactsByPage,
            Set<Integer> directQuestionVisualFactPages) {
        List<String> identifiers = AnswerEvidencePolicy.printedIdentifiers(normalizedQuestion);
        if (identifiers.isEmpty()) return;
        String query = String.join(" ", identifiers);
        try {
            List<PageFactMatch> matches = invocations.invoke(
                    assistantRunId,
                    "searchIdentifierBoundVisualFacts",
                    estimateTokens(query),
                    "Page facts retrieved by printed identifier",
                    () -> visualFacts.search(documentVersionId, query, Math.min(5, Math.max(4, identifiers.size()))),
                    result -> result.size() * 80);
            matches.forEach(match -> {
                visualFactsByPage.merge(
                        match.pageNumber(),
                        match,
                        (first, candidate) -> candidate.score() > first.score() ? candidate : first);
                directQuestionVisualFactPages.add(match.pageNumber());
            });
        } catch (RuntimeException lookupFailure) {
            if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
            LOGGER.warn(
                    "Optional identifier-bound visual fact lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
        }
    }

    private void selectIntentAnchor(
            RetrievalIntent intent,
            List<HybridEvidenceHit> retrieved,
            boolean supplementaryIntent,
            Map<UUID, HybridEvidenceHit> intentAnchors) {
        if (retrieved.isEmpty() || supplementaryIntent) return;
        HybridEvidenceHit anchor = retrieved.stream()
                .filter(hit -> !intentAnchors.containsKey(hit.evidence().chunkId()))
                .findFirst()
                .orElse(retrieved.getFirst());
        intentAnchors.putIfAbsent(anchor.evidence().chunkId(), anchor);
    }

    private List<String> rewriteCrossLanguageQueries(
            UUID assistantRunId,
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            String username) {
        if (!AnswerEvidencePolicy.requiresCrossLanguageExpansion(question.normalizedQuestion())) {
            return List.of();
        }
        try {
            return queryRewriter.rewrite(
                    assistantRunId,
                    username,
                    question.normalizedQuestion(),
                    context.previousQuestion());
        } catch (RuntimeException exception) {
            if (queryRewriter.timedOut(exception)) {
                LOGGER.info("Cross-language retrieval rewrite timed out; continuing with the original question");
            } else {
                LOGGER.info("Cross-language retrieval rewrite unavailable; continuing with the original question");
            }
            return List.of();
        }
    }

    private int evidenceTokens(List<HybridEvidenceHit> evidence) {
        return evidence.stream().mapToInt(hit -> estimateTokens(hit.evidence().excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    public enum State { READY, CONFLICTING, UNAVAILABLE }

    public record Result(List<HybridEvidenceHit> evidence, State state) {}
}
