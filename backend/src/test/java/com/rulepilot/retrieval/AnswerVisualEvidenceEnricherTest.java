package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerVisualEvidenceEnricherTest {

    private static final String VISUAL_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
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
        assertThat(evidence.get(source.chunkId()).evidence()).satisfies(enrichedSource -> {
            assertThat(enrichedSource.contentKind())
                    .isEqualTo(RuleEvidenceHit.ContentKind.CANONICAL_TEXT_WITH_VISUAL_FACTS);
            assertThat(enrichedSource.excerpt()).isEqualTo("Gain one point after the action.");
            assertThat(enrichedSource.visualFacts()).isEqualTo("The score marker advances one space.");
        });
    }

    @Test
    void turnsTheVisualLedgerIntoBoundedRuleTranscriptionOnlyWhenCanonicalTextIsUnavailable() {
        RuleEvidenceHit placeholder = placeholder(UUID.randomUUID(), 3);
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        evidence.put(placeholder.chunkId(), new HybridEvidenceHit(placeholder, 0.2, 1, null, false));

        enricher(List.of(placeholder)).enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(3, fact(3, "Unused pieces move to the common area after the player takes one type.")),
                Set.of(3));

        assertThat(evidence.get(placeholder.chunkId()).evidence()).satisfies(transcription -> {
            assertThat(transcription.contentKind()).isEqualTo(RuleEvidenceHit.ContentKind.VISUAL_TRANSCRIPTION);
            assertThat(transcription.playerExcerpt())
                    .isEqualTo("Unused pieces move to the common area after the player takes one type.");
            assertThat(transcription.excerpt()).contains("Unused pieces move to the common area");
        });
    }

    @Test
    void keepsACompleteDenseVisualPageAvailableToTheAnswerInsteadOfFailingTheTurn() {
        RuleEvidenceHit placeholder = placeholder(UUID.randomUUID(), 3);
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        evidence.put(placeholder.chunkId(), new HybridEvidenceHit(placeholder, 0.2, 1, null, false));
        String completePageFacts = "一条完整的可见规则事实。".repeat(400);

        Set<UUID> enriched = enricher(List.of(placeholder)).enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(3, fact(3, completePageFacts)),
                Set.of(3));

        assertThat(enriched).containsExactly(placeholder.chunkId());
        assertThat(evidence.get(placeholder.chunkId()).evidence().excerpt()).contains(completePageFacts);
    }

    @Test
    void removesAPlaceholderWhenTheTypedPageInventorySaysThereIsNoRuleContent() {
        RuleEvidenceHit placeholder = placeholder(UUID.randomUUID(), 11);
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        evidence.put(placeholder.chunkId(), new HybridEvidenceHit(placeholder, 0.8, 1, null, false));
        PageFactMatch noRuleContent = new PageFactMatch(
                11,
                "Descriptive panel",
                "No page-owned rule group was transcribed.",
                List.of("panel"),
                0.9,
                RuleFactStatus.NO_RULE_CONTENT);

        Set<UUID> enriched = enricher(List.of(placeholder)).enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(11, noRuleContent),
                Set.of(11));

        assertThat(enriched).isEmpty();
        assertThat(evidence).doesNotContainKey(placeholder.chunkId());
    }

    @Test
    void retainsPriorityOrderingWithoutSilentlyDroppingOtherVerifiedVisualMatches() {
        RuleEvidenceHit priority = source(UUID.randomUUID(), 2, "Priority page text");
        RuleEvidenceHit highScore = source(UUID.randomUUID(), 8, "High-score page text");
        RuleEvidenceHit second = source(UUID.randomUUID(), 3, "Second page text");
        RuleEvidenceHit third = source(UUID.randomUUID(), 4, "Third page text");
        RuleEvidenceHit fourth = source(UUID.randomUUID(), 5, "Fourth page text");
        RuleEvidenceHit fifth = source(UUID.randomUUID(), 6, "Fifth page text");
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        AnswerVisualEvidenceEnricher enricher = enricher(List.of(priority, highScore, second, third, fourth, fifth));

        enricher.enrich(
                UUID.randomUUID(),
                documentVersionId,
                evidence,
                Map.of(
                        2, fact(2, "Priority"),
                        3, fact(3, "Second"),
                        4, fact(4, "Third"),
                        5, fact(5, "Fourth"),
                        6, fact(6, "Fifth"),
                        8, new PageFactMatch(
                                8,
                                "High-score",
                                "High-score",
                                List.of("high-score"),
                                0.99,
                                RuleFactStatus.CURRENT_RULE_FACTS)),
                Set.of(2));

        assertThat(evidence).containsKeys(
                priority.chunkId(), highScore.chunkId(), second.chunkId(), third.chunkId(),
                fourth.chunkId(), fifth.chunkId());
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
        return new AnswerVisualEvidenceEnricher(lookup, new ImmediateAnswerRetrievalInvocations());
    }

    private PageFactMatch fact(int pageNumber, String evidenceText) {
        return new PageFactMatch(
                pageNumber,
                "Visible fact",
                evidenceText,
                List.of("visible"),
                0.5,
                RuleFactStatus.CURRENT_RULE_FACTS);
    }

    private RuleEvidenceHit source(UUID chunkId, int page, String excerpt) {
        return new RuleEvidenceHit(
                chunkId, documentVersionId, "ACTIONS", "Action", excerpt, page, page, 0.6);
    }

    private RuleEvidenceHit placeholder(UUID chunkId, int page) {
        return new RuleEvidenceHit(
                chunkId,
                documentVersionId,
                "ACTIONS",
                "Action",
                VISUAL_PLACEHOLDER,
                page,
                page,
                0.6,
                RuleEvidenceHit.ContentKind.VISUAL_PLACEHOLDER,
                VISUAL_PLACEHOLDER);
    }
}
