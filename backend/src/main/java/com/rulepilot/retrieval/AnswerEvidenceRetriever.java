package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.HybridRuleSearch.SourceAvailability;
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
    private final AnswerVisualEvidenceEnricher visualEvidenceEnricher;

    public AnswerEvidenceRetriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            AnswerRetrievalInvocations invocations) {
        this.retrieval = retrieval;
        this.visualFacts = visualFacts;
        this.evidenceLookup = evidenceLookup;
        this.invocations = invocations;
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
        int availableCoreRetrievals = 0;
        int failedCoreRetrievals = 0;
        boolean coreCoveragePartial = false;
        retrievePageHintCandidates(
                assistantRunId,
                context.documentVersionId(),
                context.allowedEvidencePages(),
                questionPlan,
                evidenceById,
                visualFactsByPage,
                directQuestionVisualFactPages);
        retrieveRuleObjectBoundVisualFacts(
                assistantRunId,
                context.documentVersionId(),
                context.allowedEvidencePages(),
                questionPlan.currentRuleObjectSpans(),
                visualFactsByPage,
                directQuestionVisualFactPages);
        List<RetrievalIntent> intents = AnswerRetrievalPlanner.plan(question, questionPlan);
        for (RetrievalIntent intent : intents) {
            List<HybridEvidenceHit> retrieved;
            try {
                HybridRuleSearch.SearchPage retrievalPage = invocations.invoke(
                        assistantRunId,
                        "hybridRuleSearch",
                        estimateTokens(intent.query()),
                        "Version-scoped answer evidence retrieved",
                        () -> retrieval.searchPage(
                                context.documentVersionId(),
                                intent.query(),
                                new RetrievalOptions(
                                        questionPlan.subquestions().size() > 1
                                                        || questionPlan.evidenceNeeds().contains(EvidenceNeed.COMPLETE_LIST)
                                                ? 8
                                                : 5,
                                        Set.of(),
                                        null,
                                        context.allowedEvidencePages())),
                        page -> evidenceTokens(page.hits()));
                retrieved = retrievalPage.hits();
                availableCoreRetrievals++;
                if (retrievalPage.sourceAvailability() == SourceAvailability.PARTIAL) {
                    coreCoveragePartial = true;
                }
            } catch (RuntimeException retrievalFailure) {
                if (invocations.executionStopped(retrievalFailure)) throw retrievalFailure;
                failedCoreRetrievals++;
                coreCoveragePartial = true;
                LOGGER.warn(
                        "Answer retrieval intent failed for document version {}: {}",
                        context.documentVersionId(),
                        retrievalFailure.getClass().getSimpleName());
                continue;
            }
            boolean visualTranscriptionFallback = retrieved.isEmpty()
                    || retrieved.stream().anyMatch(AnswerEvidencePolicy::isVisualPlaceholder);
            List<HybridEvidenceHit> answerEvidence = retrieved;
            selectIntentAnchor(answerEvidence, intent.directQuestion(), intentAnchors);
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
                visualMatches.stream()
                        .filter(match -> allowedPage(match.pageNumber(), context.allowedEvidencePages()))
                        .forEach(match -> visualFactsByPage.merge(
                                match.pageNumber(),
                                match,
                                (first, candidate) -> candidate.score() > first.score() ? candidate : first));
                if (intent.directQuestion()) {
                    visualMatches.stream()
                            .filter(match -> allowedPage(match.pageNumber(), context.allowedEvidencePages()))
                            .forEach(match -> directQuestionVisualFactPages.add(match.pageNumber()));
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
        if (conflicting) {
            return new Result(List.of(), State.CONFLICTING);
        }
        if (availableCoreRetrievals == 0 && failedCoreRetrievals > 0) {
            return new Result(List.of(), State.UNAVAILABLE);
        }
        Set<Integer> visualPagePriority = new LinkedHashSet<>(directQuestionVisualFactPages);
        Set<UUID> visualEvidenceIds = visualEvidenceEnricher.enrich(
                assistantRunId, context.documentVersionId(), evidenceById, visualFactsByPage, visualPagePriority);
        List<HybridEvidenceHit> selectedEvidence = AnswerEvidenceSelectionPolicy.select(
                evidenceById, intentAnchors.values(), visualEvidenceIds, questionPlan, List.of());
        return new Result(selectedEvidence, coreCoveragePartial ? State.PARTIAL : State.READY);
    }

    private void retrievePageHintCandidates(
            UUID assistantRunId,
            UUID documentVersionId,
            Set<Integer> allowedPages,
            AnswerRetrievalPlan plan,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Map<Integer, PageFactMatch> visualFactsByPage,
            Set<Integer> priorityPages) {
        Set<Integer> pageNumbers = plan.pageHints().stream()
                .map(AnswerRetrievalPlan.PageHint::pageNumber)
                .filter(page -> allowedPage(page, allowedPages))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (pageNumbers.isEmpty()) return;
        try {
            List<HybridEvidenceHit> hinted = invocations.invoke(
                    assistantRunId,
                    "readQuestionHintPages",
                    pageNumbers.size(),
                    "Player-supplied page locators checked against the active rulebook",
                    () -> evidenceLookup.findByPageNumbers(documentVersionId, pageNumbers).stream()
                            .filter(source -> documentVersionId.equals(source.documentVersionId()))
                            .map(source -> new HybridEvidenceHit(
                                    source, Math.max(0.01, source.score()), 1, null, false))
                            .toList(),
                    this::evidenceTokens);
            hinted.forEach(hit -> evidenceById.putIfAbsent(hit.evidence().chunkId(), hit));
        } catch (RuntimeException lookupFailure) {
            if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
            LOGGER.warn(
                    "Optional player page-hint lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
        }
        try {
            List<PageFactMatch> hintedFacts = invocations.invoke(
                    assistantRunId,
                    "readQuestionHintPageFacts",
                    pageNumbers.size(),
                    "Page-scoped fact readiness checked for player-supplied locators",
                    () -> visualFacts.findByPageNumbers(documentVersionId, pageNumbers),
                    matches -> matches.size() * 80);
            hintedFacts.forEach(match -> visualFactsByPage.put(match.pageNumber(), match));
            priorityPages.addAll(pageNumbers);
        } catch (RuntimeException lookupFailure) {
            if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
            LOGGER.warn(
                    "Optional player page-hint fact lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
        }
    }

    private void retrieveRuleObjectBoundVisualFacts(
            UUID assistantRunId,
            UUID documentVersionId,
            Set<Integer> allowedPages,
            List<String> currentRuleObjectSpans,
            Map<Integer, PageFactMatch> visualFactsByPage,
            Set<Integer> directQuestionVisualFactPages) {
        for (String ruleObjectSpan : currentRuleObjectSpans) {
            try {
                List<PageFactMatch> matches = invocations.invoke(
                        assistantRunId,
                        "searchRuleObjectBoundVisualFacts",
                        estimateTokens(ruleObjectSpan),
                        "Page facts retrieved for an Agent-selected current-question rule object",
                        () -> visualFacts.search(documentVersionId, ruleObjectSpan, 4),
                        result -> result.size() * 80);
                matches.stream().filter(match -> allowedPage(match.pageNumber(), allowedPages)).forEach(match -> {
                    visualFactsByPage.merge(
                            match.pageNumber(),
                            match,
                            (first, candidate) -> candidate.score() > first.score() ? candidate : first);
                    directQuestionVisualFactPages.add(match.pageNumber());
                });
            } catch (RuntimeException lookupFailure) {
                if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
                LOGGER.warn(
                        "Optional rule-object-bound visual fact lookup failed for document version {}: {}",
                        documentVersionId,
                        lookupFailure.getClass().getSimpleName());
            }
        }
    }

    private void selectIntentAnchor(
            List<HybridEvidenceHit> retrieved,
            boolean directQuestion,
            Map<UUID, HybridEvidenceHit> intentAnchors) {
        if (retrieved.isEmpty() || !directQuestion) return;
        HybridEvidenceHit anchor = retrieved.stream()
                .filter(hit -> !intentAnchors.containsKey(hit.evidence().chunkId()))
                .findFirst()
                .orElse(retrieved.getFirst());
        intentAnchors.putIfAbsent(anchor.evidence().chunkId(), anchor);
    }

    private int evidenceTokens(List<HybridEvidenceHit> evidence) {
        return evidence.stream().mapToInt(hit -> estimateTokens(hit.evidence().excerpt())).sum();
    }

    private boolean allowedPage(int pageNumber, Set<Integer> allowedPages) {
        return allowedPages == null || allowedPages.contains(pageNumber);
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    public enum State { READY, PARTIAL, CONFLICTING, UNAVAILABLE }

    public record Result(List<HybridEvidenceHit> evidence, State state) {}
}
