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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Acquires and reconciles source-scoped answer evidence; it never produces a player-facing rule conclusion. */
final class AnswerEvidenceRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerEvidenceRetriever.class);
    private static final Pattern INTENT_TERM = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> INTENT_STOP_TERMS = Set.of(
            "about", "after", "before", "does", "from", "have", "happen", "happens", "into", "other",
            "players", "play", "rule", "rules", "that", "the", "then", "this", "what", "when", "with");

    private final HybridRuleSearch retrieval;
    private final VisualRulebookPageFactSearch visualFacts;
    private final RuleEvidenceLookup evidenceLookup;
    private final AuditedAgentInvocations invocations;
    private final AnswerModelGateway modelGateway;
    private final AnswerVisualEvidenceEnricher visualEvidenceEnricher;

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
        this.visualEvidenceEnricher = new AnswerVisualEvidenceEnricher(evidenceLookup, invocations);
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
            // The last intent is deliberately broad recall support. It may fill a genuine retrieval gap, but it
            // must never become the answer's primary anchor merely because it happens to rank a generic paragraph.
            boolean supplementaryIntent = intentIndex == intents.size() - 1;
            // Keep direct supplementary evidence and every visual-page observation. Compound questions regularly put
            // their second condition in this recall pass, while a broad recap paragraph can otherwise dilute a
            // precise primary answer. selectIntentAnchor still excludes supplementary results, so they cannot
            // displace the primary rule as the answer's anchor.
            List<HybridEvidenceHit> answerEvidence = supplementaryIntent && !intentAnchors.isEmpty()
                    ? directSupplementaryEvidence(question, retrieved, intentAnchors.values())
                    : retrieved;
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
        Set<UUID> visualEvidenceIds = visualEvidenceEnricher.enrich(
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
        HybridEvidenceHit anchor;
        if (intent.purpose() == RetrievalPurpose.ENDGAME_RESOLUTION) {
            anchor = retrieved.stream()
                    .max(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore))
                    .orElse(retrieved.getFirst());
        } else {
            anchor = rankedForIntent(intent.query(), retrieved).stream()
                    .filter(hit -> !intentAnchors.containsKey(hit.evidence().chunkId()))
                    .findFirst()
                    .orElse(rankedForIntent(intent.query(), retrieved).getFirst());
        }
        intentAnchors.putIfAbsent(anchor.evidence().chunkId(), anchor);
    }

    /**
     * The hybrid score can place a broad setup paragraph above a rule that repeats the player's exact condition.
     * Preserve the hybrid score as a tie-breaker, but anchor an answer intent on the candidate that carries more
     * distinctive terms from that intent.
     */
    private List<HybridEvidenceHit> rankedForIntent(String query, List<HybridEvidenceHit> retrieved) {
        Set<String> terms = intentTerms(query);
        return retrieved.stream()
                .sorted(Comparator.comparingInt((HybridEvidenceHit hit) -> intentCoverage(hit, terms))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(HybridEvidenceHit::score).reversed())
                        .thenComparing(hit -> hit.evidence().chunkId()))
                .toList();
    }

    /**
     * A supplementary query expands the player's wording with generic rule facets. Preserve it when its text still
     * carries the player's distinctive condition; otherwise keep the primary anchor alone. If the rulebook and
     * question use different languages there is no honest lexical signal, so the result remains available for the
     * model and its citation validator rather than being silently discarded.
     */
    private List<HybridEvidenceHit> directSupplementaryEvidence(
            UnderstoodQuestion question,
            List<HybridEvidenceHit> retrieved,
            java.util.Collection<HybridEvidenceHit> primaryAnchors) {
        Set<String> terms = intentTerms(question.normalizedQuestion());
        int strongestPrimaryCoverage = primaryAnchors.stream()
                .mapToInt(hit -> intentCoverage(hit, terms))
                .max()
                .orElse(0);
        if (terms.isEmpty() || strongestPrimaryCoverage == 0) return retrieved;
        int requiredCoverage = Math.min(2, strongestPrimaryCoverage);
        return retrieved.stream()
                .filter(hit -> intentCoverage(hit, terms) >= requiredCoverage)
                .toList();
    }

    private Set<String> intentTerms(String query) {
        if (query == null || query.isBlank()) return Set.of();
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = INTENT_TERM.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (!INTENT_STOP_TERMS.contains(term)) terms.add(term);
        }
        return Set.copyOf(terms);
    }

    private int intentCoverage(HybridEvidenceHit hit, Set<String> terms) {
        if (terms.isEmpty()) return 0;
        String candidate = (hit.evidence().heading() + " " + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(candidate::contains).count();
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
