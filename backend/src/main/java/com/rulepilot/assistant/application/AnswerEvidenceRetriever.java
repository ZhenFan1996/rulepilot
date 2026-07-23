package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
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

/** Acquires and reconciles source-scoped answer evidence; it never produces a player-facing rule conclusion. */
final class AnswerEvidenceRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerEvidenceRetriever.class);

    private final HybridRuleSearch retrieval;
    private final VisualRulebookPageFactSearch visualFacts;
    private final RuleEvidenceLookup evidenceLookup;
    private final AuditedAgentInvocations invocations;
    private final AnswerModelGateway modelGateway;

    AnswerEvidenceRetriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            AuditedAgentInvocations invocations,
            AnswerModelGateway modelGateway) {
        this.retrieval = retrieval;
        this.visualFacts = visualFacts;
        this.evidenceLookup = evidenceLookup;
        this.invocations = invocations;
        this.modelGateway = modelGateway;
    }

    Result retrieve(UUID assistantRunId, UnderstoodQuestion question, QuestionContext context, String username) {
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
            selectIntentAnchor(intent, retrieved, supplementaryIntent, intentAnchors);
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
            return new Result(List.of(), State.CONFLICTING);
        }
        if (successfulCoreRetrievals == 0 && failedCoreRetrievals > 0) {
            return new Result(List.of(), State.UNAVAILABLE);
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
        return new Result(selectedEvidence, State.READY);
    }

    private void selectIntentAnchor(
            RetrievalIntent intent,
            List<HybridEvidenceHit> retrieved,
            boolean supplementaryIntent,
            Map<UUID, HybridEvidenceHit> intentAnchors) {
        if (retrieved.isEmpty() || supplementaryIntent) return;
        if (intent.purpose() == RetrievalPurpose.STATE_TRANSITION) {
            retrieved.stream().limit(2).forEach(hit -> intentAnchors.putIfAbsent(hit.evidence().chunkId(), hit));
            return;
        }
        HybridEvidenceHit anchor;
        if (intent.purpose() == RetrievalPurpose.ENDGAME_RESOLUTION) {
            anchor = retrieved.stream()
                    .max(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore))
                    .orElse(retrieved.getFirst());
        } else if (intent.purpose() == RetrievalPurpose.END_TURN_PROCEDURE) {
            anchor = retrieved.stream().filter(AnswerEvidencePolicy::hasEndTurnProcedure).findFirst().orElse(retrieved.getFirst());
        } else {
            anchor = retrieved.stream()
                    .filter(hit -> !intentAnchors.containsKey(hit.evidence().chunkId()))
                    .findFirst()
                    .orElse(retrieved.getFirst());
        }
        intentAnchors.putIfAbsent(anchor.evidence().chunkId(), anchor);
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
        requiredPages.stream().filter(factsByPage::containsKey).forEach(pages::add);
        factsByPage.values().stream()
                .sorted(Comparator.comparingDouble(PageFactMatch::score).reversed()
                        .thenComparingInt(PageFactMatch::pageNumber))
                .map(PageFactMatch::pageNumber)
                .forEach(pages::add);
        Set<Integer> selectedPages = pages.stream().limit(4).collect(Collectors.toCollection(LinkedHashSet::new));
        List<RuleEvidenceHit> pageSources;
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
        for (RuleEvidenceHit source : pageSources) {
            if (source.pageFrom() != source.pageTo()) continue;
            PageFactMatch fact = factsByPage.get(source.pageFrom());
            if (fact == null) continue;
            HybridEvidenceHit existing = evidenceById.get(source.chunkId());
            if (existing != null && !AnswerEvidencePolicy.isVisualPlaceholder(existing)) {
                RuleEvidenceHit textSource = existing.evidence();
                RuleEvidenceHit enrichedSource = new RuleEvidenceHit(
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
            RuleEvidenceHit visualSource = new RuleEvidenceHit(
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

    private List<String> rewriteCrossLanguageQueries(
            UUID assistantRunId, UnderstoodQuestion question, QuestionContext context, String username) {
        if (!AnswerEvidencePolicy.requiresCrossLanguageExpansion(question.normalizedQuestion())) {
            return List.of();
        }
        try {
            return modelGateway.rewriteRetrievalQueries(
                    assistantRunId,
                    username,
                    new RetrievalQueryRequest(
                            question.normalizedQuestion(), context.previousQuestion(), context.currentLessonSection()));
        } catch (RuleAnswerModelTimeoutException exception) {
            LOGGER.info("Cross-language retrieval rewrite timed out; continuing with the original question");
            return List.of();
        } catch (RuntimeException exception) {
            LOGGER.info("Cross-language retrieval rewrite unavailable; continuing with the original question");
            return List.of();
        }
    }

    private int evidenceTokens(List<HybridEvidenceHit> evidence) {
        return evidence.stream().mapToInt(hit -> estimateTokens(hit.evidence().excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    enum State { READY, CONFLICTING, UNAVAILABLE }

    record Result(List<HybridEvidenceHit> evidence, State state) {}
}
