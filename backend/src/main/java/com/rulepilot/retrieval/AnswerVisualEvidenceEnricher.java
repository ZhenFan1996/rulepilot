package com.rulepilot.retrieval;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Joins visual page facts to source chunks without allowing visual search to replace source evidence. */
final class AnswerVisualEvidenceEnricher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerVisualEvidenceEnricher.class);
    private static final int MAX_VISUAL_SOURCE_PAGES = 4;

    private final RuleEvidenceLookup evidenceLookup;
    private final AnswerRetrievalInvocations invocations;

    AnswerVisualEvidenceEnricher(RuleEvidenceLookup evidenceLookup, AnswerRetrievalInvocations invocations) {
        this.evidenceLookup = evidenceLookup;
        this.invocations = invocations;
    }

    Set<UUID> enrich(
            UUID assistantRunId,
            UUID documentVersionId,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Map<Integer, PageFactMatch> factsByPage,
            Set<Integer> priorityPages) {
        if (factsByPage.isEmpty()) return Set.of();
        Set<Integer> selectedPages = selectedPages(factsByPage, priorityPages);
        List<RuleEvidenceHit> pageSources;
        try {
            pageSources = invocations.invoke(
                    assistantRunId,
                    "readVisualRulebookFactPages",
                    selectedPages.size(),
                    "Original rulebook pages " + selectedPages + " for visual facts retrieved",
                    () -> evidenceLookup.findByPageNumbers(documentVersionId, selectedPages),
                    sources -> sources.size() * 80);
        } catch (RuntimeException lookupFailure) {
            if (invocations.executionStopped(lookupFailure)) throw lookupFailure;
            LOGGER.warn(
                    "Optional visual source-page lookup failed for document version {}: {}",
                    documentVersionId,
                    lookupFailure.getClass().getSimpleName());
            return Set.of();
        }
        return mergePageSources(evidenceById, factsByPage, selectedPages, pageSources);
    }

    private Set<Integer> selectedPages(Map<Integer, PageFactMatch> factsByPage, Set<Integer> priorityPages) {
        Set<Integer> pages = new LinkedHashSet<>();
        priorityPages.stream().filter(factsByPage::containsKey).forEach(pages::add);
        factsByPage.values().stream()
                .sorted(Comparator.comparingDouble(PageFactMatch::score).reversed()
                        .thenComparingInt(PageFactMatch::pageNumber))
                .map(PageFactMatch::pageNumber)
                .forEach(pages::add);
        return pages.stream()
                .limit(MAX_VISUAL_SOURCE_PAGES)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> mergePageSources(
            Map<UUID, HybridEvidenceHit> evidenceById,
            Map<Integer, PageFactMatch> factsByPage,
            Set<Integer> selectedPages,
            List<RuleEvidenceHit> pageSources) {
        Set<UUID> enriched = new LinkedHashSet<>();
        Map<Integer, Integer> rankByPage = rankByPage(selectedPages);
        for (RuleEvidenceHit source : pageSources) {
            if (source.pageFrom() != source.pageTo()) continue;
            PageFactMatch fact = factsByPage.get(source.pageFrom());
            if (fact == null) continue;
            HybridEvidenceHit existing = evidenceById.get(source.chunkId());
            if (existing != null && !AnswerEvidencePolicy.isVisualPlaceholder(existing)) {
                evidenceById.put(source.chunkId(), enrichedTextHit(existing, fact, rankByPage.get(source.pageFrom())));
            } else {
                evidenceById.put(source.chunkId(), visualOnlyHit(source, fact, rankByPage.get(source.pageFrom())));
            }
            enriched.add(source.chunkId());
        }
        return Set.copyOf(enriched);
    }

    private Map<Integer, Integer> rankByPage(Set<Integer> selectedPages) {
        Map<Integer, Integer> rankByPage = new LinkedHashMap<>();
        int rank = 1;
        for (Integer page : selectedPages) {
            rankByPage.put(page, rank++);
        }
        return rankByPage;
    }

    private HybridEvidenceHit enrichedTextHit(HybridEvidenceHit existing, PageFactMatch fact, int pageRank) {
        RuleEvidenceHit textSource = existing.evidence();
        RuleEvidenceHit enrichedSource = new RuleEvidenceHit(
                textSource.chunkId(),
                textSource.documentVersionId(),
                textSource.sectionType(),
                textSource.heading(),
                AnswerVisualFactPresentationPolicy.evidenceText(fact)
                        + "\n\nExtracted page text (may omit inline visual symbols):\n"
                        + textSource.excerpt(),
                textSource.pageFrom(),
                textSource.pageTo(),
                Math.max(textSource.score(), fact.score()));
        return new HybridEvidenceHit(
                enrichedSource,
                Math.max(existing.score(), fact.score()),
                existing.fullTextRank() == null ? pageRank : Math.min(existing.fullTextRank(), pageRank),
                existing.vectorRank(),
                existing.currentSectionBoosted());
    }

    private HybridEvidenceHit visualOnlyHit(RuleEvidenceHit source, PageFactMatch fact, int pageRank) {
        RuleEvidenceHit visualSource = new RuleEvidenceHit(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.heading(),
                AnswerVisualFactPresentationPolicy.transcribedRuleEvidenceText(fact),
                source.pageFrom(),
                source.pageTo(),
                Math.max(0.01, fact.score()));
        return new HybridEvidenceHit(visualSource, Math.max(0.01, fact.score()), pageRank, null, false);
    }
}
