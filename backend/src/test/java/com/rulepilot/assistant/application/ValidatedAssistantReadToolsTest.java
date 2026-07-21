package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidatedAssistantReadToolsTest {

    @Test
    void validatesAndScopesRuleSearchBeforeReturningEvidence() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> {
            assertThat(requestedVersion).isEqualTo(versionId);
            assertThat(query).isEqualTo("setup board");
            assertThat(options.limit()).isEqualTo(4);
            assertThat(options.sectionTypes()).containsExactly("SETUP");
            return List.of(new HybridEvidenceHit(
                    evidence(chunkId, versionId), 0.01, 1, null, true));
        };
        var tools = new ValidatedAssistantReadTools(retrieval);

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, " setup board ", 4, Set.of("setup"), "setup"));

        assertThat(result).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo(chunkId);
            assertThat(hit.documentVersionId()).isEqualTo(versionId);
            assertThat(hit.pageFrom()).isEqualTo(2);
        });
    }

    @Test
    void rejectsInvalidParametersAndCrossVersionEvidence() {
        UUID versionId = UUID.randomUUID();
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(
                new HybridEvidenceHit(evidence(UUID.randomUUID(), UUID.randomUUID()), 0.01, 1, null, true)));

        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 11, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 4, Set.of("SETUP"), "SETUP")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside document scope");
    }

    @Test
    void expandsTopAnchorsWithSameSectionAdjacentEvidenceInsideTheResultLimit() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit first = evidence(UUID.randomUUID(), versionId, "Place the board in the center.", 2);
        RuleEvidenceHit second = evidence(UUID.randomUUID(), versionId, "Shuffle the deck.", 3);
        RuleEvidenceHit hydratedFirst = evidence(
                first.chunkId(), versionId, "Full setup paragraph: place the board in the center.", 2);
        RuleEvidenceHit hydratedSecond = evidence(
                second.chunkId(), versionId, "Full setup paragraph: shuffle the deck and deal cards.", 3);
        RuleEvidenceHit third = evidence(UUID.randomUUID(), versionId, "Choose a starting player.", 5);
        RuleEvidenceHit adjacent = evidence(UUID.randomUUID(), versionId, "Deal five cards to each player.", 4);
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> List.of(
                hybrid(first, 1), hybrid(second, 2), hybrid(third, 3));
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(chunkIds).containsExactlyInAnyOrder(first.chunkId(), second.chunkId());
                return List.of(hydratedFirst, hydratedSecond);
            }

            @Override
            public List<RuleEvidenceHit> findAdjacent(
                    UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(anchorChunkIds).containsExactlyInAnyOrder(first.chunkId(), second.chunkId());
                assertThat(radius).isEqualTo(1);
                assertThat(sectionTypes).containsExactly("SETUP");
                return List.of(adjacent);
            }
        };
        var tools = new ValidatedAssistantReadTools(retrieval, lookup);

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, "setup", 4, Set.of("SETUP"), "SETUP", true));

        assertThat(result).extracting(hit -> hit.chunkId())
                .containsExactly(first.chunkId(), second.chunkId(), adjacent.chunkId(), third.chunkId());
        assertThat(result).extracting(hit -> hit.excerpt())
                .startsWith(hydratedFirst.excerpt(), hydratedSecond.excerpt());
    }

    @Test
    void attachesOnlyBoundedVersionScopedPageImagesWhenRequested() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit first = evidence(UUID.randomUUID(), versionId, "Place the board in the center.", 2);
        RuleEvidenceHit second = evidence(UUID.randomUUID(), versionId, "Deal starting cards.", 3);
        RuleEvidenceHit third = evidence(UUID.randomUUID(), versionId, "Choose a player color.", 4);
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> List.of(
                hybrid(first, 1), hybrid(second, 2), hybrid(third, 3));
        var tools = new ValidatedAssistantReadTools(
                retrieval,
                (documentVersionId, chunkIds) -> List.of(),
                (documentVersionId, pageNumbers) -> {
                    assertThat(documentVersionId).isEqualTo(versionId);
                    assertThat(pageNumbers).containsExactly(2, 3);
                    return List.of(
                            new PageImage(2, "image/jpeg", new byte[] {1}, 800, 1200),
                            new PageImage(3, "image/jpeg", new byte[] {2}, 800, 1200));
                });

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, "setup", 4, Set.of("SETUP"), "SETUP", false, true));

        assertThat(result.get(0).pageImages()).extracting(image -> image.pageNumber()).containsExactly(2);
        assertThat(result.get(1).pageImages()).extracting(image -> image.pageNumber()).containsExactly(3);
        assertThat(result.get(2).pageImages()).isEmpty();
    }

    @Test
    void readsOnlyPlannerBoundPagesWithTheirStoredEvidenceAndImages() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit pageFive = evidence(UUID.randomUUID(), versionId, "Choose a habitat tile.", 5);
        RuleEvidenceHit pageSeven = evidence(UUID.randomUUID(), versionId, "Place the wildlife token.", 7);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(pageNumbers).containsExactlyInAnyOrder(5, 7);
                return List.of(pageFive, pageSeven);
            }
        };
        var tools = new ValidatedAssistantReadTools(
                (requestedVersion, query, options) -> List.of(),
                lookup,
                (documentVersionId, pageNumbers) -> List.of(
                        new PageImage(5, "image/jpeg", new byte[] {5}, 800, 1200),
                        new PageImage(7, "image/jpeg", new byte[] {7}, 800, 1200)));

        var result = tools.readRuleEvidencePages(versionId, Set.of(5, 7), true);

        assertThat(result).extracting(hit -> hit.pageFrom()).containsExactly(5, 7);
        assertThat(result).allSatisfy(hit -> assertThat(hit.pageImages())
                .singleElement()
                .extracting(image -> image.pageNumber())
                .isEqualTo(hit.pageFrom()));
    }

    private RuleEvidenceHit evidence(UUID chunkId, UUID versionId) {
        return evidence(chunkId, versionId, "Place the board in the center.", 2);
    }

    private RuleEvidenceHit evidence(UUID chunkId, UUID versionId, String excerpt, int page) {
        return new RuleEvidenceHit(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                excerpt,
                page,
                page,
                0.9);
    }

    private HybridEvidenceHit hybrid(RuleEvidenceHit evidence, int rank) {
        return new HybridEvidenceHit(evidence, 0.01, rank, null, true);
    }
}
