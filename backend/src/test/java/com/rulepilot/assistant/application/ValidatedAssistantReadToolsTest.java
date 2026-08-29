package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.document.DocumentPageImages;
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
    void rejectsNonPositiveLimitAndCrossVersionEvidence() {
        UUID versionId = UUID.randomUUID();
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(
                new HybridEvidenceHit(evidence(UUID.randomUUID(), UUID.randomUUID()), 0.01, 1, null, true)));

        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 0, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 4, Set.of("SETUP"), "SETUP")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside document scope");
    }

    @Test
    void preservesCompleteSearchRequestBeyondFormerApplicationCaps() {
        UUID versionId = UUID.randomUUID();
        String query = "turn order condition ".repeat(40).strip();
        Set<String> sections = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "SECTION_" + index)
                .collect(java.util.stream.Collectors.toSet());
        List<RuleEvidenceHit> sources = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(page -> evidence(UUID.randomUUID(), versionId, "Rule " + page, page))
                .toList();
        HybridRuleSearch retrieval = (requestedVersion, requestedQuery, options) -> {
            assertThat(requestedVersion).isEqualTo(versionId);
            assertThat(requestedQuery).isEqualTo(query);
            assertThat(options.limit()).isEqualTo(12);
            assertThat(options.sectionTypes()).containsExactlyInAnyOrderElementsOf(sections);
            return java.util.stream.IntStream.range(0, sources.size())
                    .mapToObj(index -> hybrid(sources.get(index), index + 1))
                    .toList();
        };

        var result = new ValidatedAssistantReadTools(retrieval).searchRuleEvidence(
                new SearchRuleEvidence(versionId, query, 12, sections, null));

        assertThat(result).extracting(hit -> hit.chunkId())
                .containsExactlyElementsOf(sources.stream().map(RuleEvidenceHit::chunkId).toList());
    }

    @Test
    void clampsARequestedSearchWindowToTheActiveDocumentsRealCardinality() {
        UUID versionId = UUID.randomUUID();
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> {
            assertThat(options.limit()).isEqualTo(7);
            return List.of();
        };
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public int canonicalChunkCount(UUID documentVersionId) {
                assertThat(documentVersionId).isEqualTo(versionId);
                return 7;
            }

            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }
        };

        var result = new ValidatedAssistantReadTools(retrieval, lookup).searchRuleEvidence(
                new SearchRuleEvidence(versionId, "turn order", Integer.MAX_VALUE, Set.of(), null));

        assertThat(result).isEmpty();
    }

    @Test
    void pagedSearchCarriesPublishedIdentitiesInsteadOfSkippingBothRetrievalChannels() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit first = evidence(UUID.randomUUID(), versionId, "First candidate", 1);
        RuleEvidenceHit second = evidence(UUID.randomUUID(), versionId, "Second candidate", 2);
        List<HybridRuleSearch.RetrievalOptions> observed = new java.util.ArrayList<>();
        HybridRuleSearch retrieval = new HybridRuleSearch() {
            @Override
            public List<HybridEvidenceHit> search(
                    UUID requestedVersion, String query, HybridRuleSearch.RetrievalOptions options) {
                return searchPage(requestedVersion, query, options).hits();
            }

            @Override
            public SearchPage searchPage(
                    UUID requestedVersion, String query, HybridRuleSearch.RetrievalOptions options) {
                observed.add(options);
                return options.excludedEvidenceIds().isEmpty()
                        ? new SearchPage(List.of(hybrid(first, 1)), true)
                        : new SearchPage(List.of(hybrid(second, 2)), false);
            }
        };
        var tools = new ValidatedAssistantReadTools(retrieval);
        SearchRuleEvidence request = new SearchRuleEvidence(versionId, "candidate", 3, Set.of(), null);

        var firstPage = tools.searchRuleEvidencePage(request, 0, 1, Set.of());
        var secondPage = tools.searchRuleEvidencePage(request, 1, 1, Set.of(first.chunkId()));

        assertThat(firstPage.evidence()).extracting(hit -> hit.chunkId()).containsExactly(first.chunkId());
        assertThat(secondPage.evidence()).extracting(hit -> hit.chunkId()).containsExactly(second.chunkId());
        assertThat(observed).extracting(HybridRuleSearch.RetrievalOptions::offset).containsExactly(0, 0);
        assertThat(observed.get(1).excludedEvidenceIds()).containsExactly(first.chunkId());
    }

    @Test
    void expandsEveryRankedAnchorWithoutDroppingRankedOrAdjacentEvidence() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit first = evidence(UUID.randomUUID(), versionId, "Place the board in the center.", 2);
        RuleEvidenceHit second = evidence(UUID.randomUUID(), versionId, "Shuffle the deck.", 3);
        RuleEvidenceHit third = evidence(UUID.randomUUID(), versionId, "Choose a starting player.", 5);
        RuleEvidenceHit adjacent = evidence(UUID.randomUUID(), versionId, "Deal five cards to each player.", 4);
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> List.of(
                hybrid(first, 1), hybrid(second, 2), hybrid(third, 3));
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                throw new AssertionError("hybrid results are already canonical and must not be hydrated twice");
            }

            @Override
            public List<RuleEvidenceHit> findAdjacent(
                    UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(anchorChunkIds).containsExactlyInAnyOrder(
                        first.chunkId(), second.chunkId(), third.chunkId());
                assertThat(radius).isEqualTo(1);
                assertThat(sectionTypes).containsExactly("SETUP");
                return List.of(adjacent);
            }
        };
        var tools = new ValidatedAssistantReadTools(retrieval, lookup);

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, "setup", 3, Set.of("SETUP"), "SETUP", true));

        assertThat(result).extracting(hit -> hit.chunkId())
                .containsExactly(first.chunkId(), second.chunkId(), third.chunkId(), adjacent.chunkId());
        assertThat(result).extracting(hit -> hit.excerpt())
                .startsWith(first.excerpt(), second.excerpt(), third.excerpt());
    }

    @Test
    void attachesEveryVersionScopedPageImageRequestedBySearchEvidence() {
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
                    assertThat(pageNumbers).containsExactly(2, 3, 4);
                    return List.of(
                            new PageImage(2, "image/jpeg", new byte[] {1}, 800, 1200),
                            new PageImage(3, "image/jpeg", new byte[] {2}, 800, 1200),
                            new PageImage(4, "image/jpeg", new byte[] {3}, 800, 1200));
                });

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, "setup", 4, Set.of("SETUP"), "SETUP", false, true));

        assertThat(result.get(0).pageImages()).extracting(image -> image.pageNumber()).containsExactly(2);
        assertThat(result.get(1).pageImages()).extracting(image -> image.pageNumber()).containsExactly(3);
        assertThat(result.get(2).pageImages()).extracting(image -> image.pageNumber()).containsExactly(4);
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

    @Test
    void readsEveryExactPageWhileChunkingImageStorageRequests() {
        UUID versionId = UUID.randomUUID();
        Set<Integer> requestedPages = java.util.stream.IntStream.rangeClosed(1, 12)
                .boxed()
                .collect(java.util.stream.Collectors.toSet());
        List<Set<Integer>> imageBatches = new java.util.ArrayList<>();
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(pageNumbers).containsExactlyInAnyOrderElementsOf(requestedPages);
                return List.of();
            }
        };
        var tools = new ValidatedAssistantReadTools(
                (requestedVersion, query, options) -> List.of(),
                lookup,
                (documentVersionId, pageNumbers) -> {
                    imageBatches.add(Set.copyOf(pageNumbers));
                    return pageNumbers.stream()
                            .map(page -> new PageImage(page, "image/jpeg", new byte[] {page.byteValue()}, 800, 1200))
                            .toList();
                });

        assertThat(tools.readRuleEvidencePages(versionId, requestedPages, true)).isEmpty();
        assertThat(imageBatches).hasSize(3).allSatisfy(batch ->
                assertThat(batch).hasSizeLessThanOrEqualTo(DocumentPageImages.MAX_PAGES_PER_READ));
        assertThat(imageBatches.stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(requestedPages);
    }

    @Test
    void localizesOneUnavailableImageBatchAndPreservesTextAndHealthyPageImages() {
        UUID versionId = UUID.randomUUID();
        Set<Integer> requestedPages = java.util.stream.IntStream.rangeClosed(1, 12)
                .boxed()
                .collect(java.util.stream.Collectors.toSet());
        List<RuleEvidenceHit> sources = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(page -> evidence(UUID.randomUUID(), versionId, "Rule text " + page, page))
                .toList();
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return sources;
            }
        };
        var tools = new ValidatedAssistantReadTools(
                (requestedVersion, query, options) -> List.of(),
                lookup,
                (documentVersionId, pageNumbers) -> {
                    if (pageNumbers.contains(6)) throw new IllegalStateException("object storage unavailable");
                    return pageNumbers.stream()
                            .map(page -> new PageImage(page, "image/jpeg", new byte[] {page.byteValue()}, 800, 1200))
                            .toList();
                });

        var result = tools.readRuleEvidencePages(versionId, requestedPages, true);

        assertThat(result).hasSize(12).allSatisfy(item -> assertThat(item.excerpt()).startsWith("Rule text"));
        assertThat(result.stream()
                        .filter(item -> item.pageFrom() <= 5 || item.pageFrom() >= 11)
                        .flatMap(item -> item.pageImages().stream())
                        .map(image -> image.pageNumber())
                        .toList())
                .containsExactly(1, 2, 3, 4, 5, 11, 12);
        assertThat(result.stream()
                        .filter(item -> item.pageFrom() >= 6 && item.pageFrom() <= 10)
                        .flatMap(item -> item.pageImages().stream())
                        .toList())
                .isEmpty();
    }

    @Test
    void rejectsCrossVersionExactPageEvidenceInsteadOfLeakingIt() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit foreign = evidence(UUID.randomUUID(), UUID.randomUUID(), "Foreign page text.", 5);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(foreign);
            }
        };
        var tools = new ValidatedAssistantReadTools(
                (requestedVersion, query, options) -> List.of(), lookup, (documentVersionId, pageNumbers) -> List.of());

        assertThatThrownBy(() -> tools.readRuleEvidencePages(versionId, Set.of(5), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escaped document scope");
    }

    @Test
    void rejectsSameVersionEvidenceOutsideTheExactRequestedPages() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit unrelated = evidence(UUID.randomUUID(), versionId, "Different page text.", 9);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(unrelated);
            }
        };
        var tools = new ValidatedAssistantReadTools(
                (requestedVersion, query, options) -> List.of(), lookup, (documentVersionId, pageNumbers) -> List.of());

        assertThatThrownBy(() -> tools.readRuleEvidencePages(versionId, Set.of(5), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requested page scope");
    }

    @Test
    void rehydratesEveryRequestedVersionScopedEvidenceHandle() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> sources = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(page -> evidence(UUID.randomUUID(), versionId, "Canonical source " + page, page))
                .toList();
        Set<UUID> requestedIds = sources.stream()
                .map(RuleEvidenceHit::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        RuleEvidenceLookup lookup = (documentVersionId, chunkIds) -> {
            assertThat(documentVersionId).isEqualTo(versionId);
            assertThat(chunkIds).containsExactlyInAnyOrderElementsOf(requestedIds);
            return sources;
        };
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(), lookup);

        var result = tools.readRuleEvidenceIds(versionId, requestedIds);

        assertThat(result).extracting(evidence -> evidence.chunkId())
                .containsExactlyElementsOf(sources.stream().map(RuleEvidenceHit::chunkId).toList());
        assertThatThrownBy(() -> tools.readRuleEvidenceIds(versionId, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expandsEveryResolvedSameVersionAnchorAndPreservesEveryCanonicalNeighbor() {
        UUID versionId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        List<RuleEvidenceHit> anchors = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(page -> evidence(UUID.randomUUID(), versionId, "Anchor " + page, page))
                .toList();
        List<RuleEvidenceHit> neighbors = java.util.stream.IntStream.rangeClosed(6, 17)
                .mapToObj(page -> evidence(UUID.randomUUID(), versionId, "Neighbor " + page, page))
                .toList();
        Set<UUID> resolvedAnchorIds = anchors.stream()
                .map(RuleEvidenceHit::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> requestedAnchors = new java.util.LinkedHashSet<>(resolvedAnchorIds);
        requestedAnchors.add(missingId);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(chunkIds).containsExactlyInAnyOrderElementsOf(requestedAnchors);
                return anchors;
            }

            @Override
            public List<RuleEvidenceHit> findAdjacent(
                    UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(anchorChunkIds).containsExactlyInAnyOrderElementsOf(
                        anchors.stream().map(RuleEvidenceHit::chunkId).toList());
                assertThat(radius).isEqualTo(3);
                assertThat(sectionTypes).isEmpty();
                return neighbors;
            }
        };
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(), lookup);

        var result = tools.readRuleEvidenceContext(versionId, requestedAnchors, 3);

        assertThat(result.anchors()).extracting(evidence -> evidence.chunkId())
                .containsExactlyElementsOf(anchors.stream().map(RuleEvidenceHit::chunkId).toList());
        assertThat(result.surroundingEvidence()).extracting(evidence -> evidence.chunkId())
                .containsExactlyElementsOf(neighbors.stream().map(RuleEvidenceHit::chunkId).toList());
    }

    @Test
    void clampsAnExtremeContextRadiusToTheActiveDocumentsRealRange() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit anchor = evidence(UUID.randomUUID(), versionId, "Anchor", 1);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public int canonicalChunkCount(UUID documentVersionId) {
                return 9;
            }

            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of(anchor);
            }

            @Override
            public List<RuleEvidenceHit> findAdjacent(
                    UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
                assertThat(radius).isEqualTo(8);
                return List.of();
            }
        };

        var result = new ValidatedAssistantReadTools((version, query, options) -> List.of(), lookup)
                .readRuleEvidenceContext(versionId, Set.of(anchor.chunkId()), Integer.MAX_VALUE);

        assertThat(result.anchors()).singleElement();
        assertThat(result.surroundingEvidence()).isEmpty();
    }

    @Test
    void rejectsCrossVersionContextEvidenceInsteadOfLeakingIt() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit foreign = evidence(UUID.randomUUID(), UUID.randomUUID(), "Foreign text.", 9);
        RuleEvidenceLookup lookup = (documentVersionId, chunkIds) -> List.of(foreign);
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(), lookup);

        assertThatThrownBy(() -> tools.readRuleEvidenceContext(versionId, Set.of(foreign.chunkId()), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escaped document scope");
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
