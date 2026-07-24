package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerVisualEvidenceEnricherTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void addsVisualFactsToTheExistingSourceChunk() {
        RuleEvidenceHit source = source(UUID.randomUUID(), 4, "Gain one point after the action.");
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        evidence.put(source.chunkId(), new HybridEvidenceHit(source, 0.7, 2, null, false));
        AnswerVisualEvidenceEnricher enricher = enricher(List.of(source));

        Set<UUID> enriched = enricher.enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(4, fact(4, "The score marker advances one space.")),
                Set.of(4));

        assertThat(enriched).containsExactly(source.chunkId());
        assertThat(evidence.get(source.chunkId()).evidence().excerpt())
                .contains("Gain one point", "The score marker advances one space.");
    }

    @Test
    void retainsPriorityPagesBeforeHigherScoringVisualMatches() {
        RuleEvidenceHit priority = source(UUID.randomUUID(), 2, "Priority page text");
        RuleEvidenceHit highScore = source(UUID.randomUUID(), 8, "High-score page text");
        RuleEvidenceHit second = source(UUID.randomUUID(), 3, "Second page text");
        RuleEvidenceHit third = source(UUID.randomUUID(), 4, "Third page text");
        RuleEvidenceHit fourth = source(UUID.randomUUID(), 5, "Fourth page text");
        RuleEvidenceHit omitted = source(UUID.randomUUID(), 6, "Omitted page text");
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        AnswerVisualEvidenceEnricher enricher = enricher(List.of(priority, highScore, second, third, fourth, omitted));

        enricher.enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(
                        2, fact(2, "Priority"),
                        3, fact(3, "Second"),
                        4, fact(4, "Third"),
                        5, fact(5, "Fourth"),
                        6, fact(6, "Omitted"),
                        8, new PageFactMatch(8, "High-score", "High-score", List.of("high-score"), 0.99)),
                Set.of(2));

        assertThat(evidence).containsKeys(priority.chunkId(), highScore.chunkId(), second.chunkId(), third.chunkId());
        assertThat(evidence).doesNotContainKeys(fourth.chunkId(), omitted.chunkId());
    }

    private AnswerVisualEvidenceEnricher enricher(List<RuleEvidenceHit> sources) {
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID versionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID versionId, Set<Integer> pages) {
                return sources.stream().filter(source -> pages.contains(source.pageFrom())).toList();
            }
        };
        return new AnswerVisualEvidenceEnricher(lookup, new ImmediateAuditedAgentInvocations());
    }

    private PageFactMatch fact(int pageNumber, String evidenceText) {
        return new PageFactMatch(pageNumber, "Visible fact", evidenceText, List.of("visible"), 0.5);
    }

    private RuleEvidenceHit source(UUID chunkId, int page, String excerpt) {
        return new RuleEvidenceHit(
                chunkId, documentVersionId, "ACTIONS", "Action", excerpt, page, page, 0.6);
    }
}
