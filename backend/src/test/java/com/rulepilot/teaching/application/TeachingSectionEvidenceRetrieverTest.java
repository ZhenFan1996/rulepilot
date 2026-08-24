package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeachingSectionEvidenceRetrieverTest {

    private final UUID documentVersionId = UUID.randomUUID();
    private final TeachingPlan plan = new TeachingPlan(
            UUID.randomUUID(),
            documentVersionId,
            "Test game",
            "A focused retrieval fixture.",
            List.of(new PlannedSection(
                    1,
                    "setup",
                    "Setup",
                    "Explain setup.",
                    true,
                    false,
                    List.of("setup", "starting pieces"),
                    List.of("setup"))),
            "player",
            Instant.now());

    @Test
    void rejectsConflictingSnapshotsBeforeTheyReachComposition() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence first = evidence(chunkId, documentVersionId, "Place the board in the center.");
        RuleEvidence conflicting = new RuleEvidence(
                chunkId, documentVersionId, "SETUP", "Different heading", "Place it elsewhere.", 2, 2);
        AtomicInteger calls = new AtomicInteger();
        TeachingSectionEvidenceRetriever retriever = retriever(request ->
                calls.getAndIncrement() == 0 ? List.of(first) : List.of(conflicting));

        TeachingSectionEvidenceRetriever.Result result = retriever.retrieve(
                plan, plan.sections().getFirst(), UUID.randomUUID(), 3, false);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.EMPTY);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void rejectsAnotherDocumentVersionsEvidenceAfterVersionScopedSearch() {
        RuleEvidence wrongVersion = evidence(UUID.randomUUID(), UUID.randomUUID(), "Place the board in the center.");
        TeachingSectionEvidenceRetriever retriever = retriever(request -> {
            assertThat(request.documentVersionId()).isEqualTo(documentVersionId);
            assertThat(request.includeAdjacentContext()).isTrue();
            assertThat(request.includePageImages()).isFalse();
            return List.of(wrongVersion);
        });

        TeachingSectionEvidenceRetriever.Result result = retriever.retrieve(
                plan, plan.sections().getFirst(), UUID.randomUUID(), 3, false);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void countsAFailedVisualPageReadWithoutClaimingExactPageProvenance() {
        TeachingPlan visualPlan = new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                "Test game",
                "Keep the exact-page fallback explicit.",
                List.of(new PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Explain setup from the bound page.",
                        true,
                        true,
                        List.of("setup"),
                        List.of("setup"),
                        List.of(2))),
                "player",
                Instant.now());
        RuleEvidence placeholder = new RuleEvidence(
                UUID.randomUUID(),
                documentVersionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of(placeholder);
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID requestedVersion, java.util.Set<Integer> pages, boolean includePageImages) {
                throw new IllegalStateException("page image storage unavailable");
            }
        };

        var result = retriever(tools).retrieve(
                visualPlan, visualPlan.sections().getFirst(), UUID.randomUUID(), 3, true);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).containsExactly(placeholder);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.canonicalPageObservation()).isEmpty();
    }

    @Test
    void doesNotClaimACompleteObservationWhenOneRequestedPageIsAbsent() {
        RuleEvidence pageTwo = evidence(UUID.randomUUID(), documentVersionId, "Only page two was returned.");

        var observation = TeachingVisualEvidenceResolver.CanonicalPageObservation.complete(
                UUID.randomUUID(), documentVersionId, java.util.Set.of(2, 5), List.of(pageTwo));

        assertThat(observation).isEmpty();
    }

    @Test
    void recoversOneBoundPageWhenProgressiveBackgroundPrefetchFailed() {
        UUID chunkId = UUID.randomUUID();
        AtomicInteger catalogCalls = new AtomicInteger();
        Map<Integer, VisualRulebookPageFacts.PageFact> stored = new java.util.LinkedHashMap<>();
        VisualAnchor priorAnchor = new VisualAnchor(
                "diagram", "Prior end track", "A previously localized end track.", 30, 40, 280, 180);
        IconOccurrence priorIcon = new IconOccurrence(
                "end marker",
                "End marker",
                "A previously verified compact end marker.",
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                80,
                90,
                30,
                30);
        stored.put(4, new PageFact(
                4,
                "OLD PARTIAL",
                "An earlier current-schema observation admitted that it was partial.",
                List.of("old"),
                List.of(priorAnchor),
                List.of(priorIcon),
                true,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(new SourceDependency("Obsolete leaflet", List.of("setup"))),
                List.of("OLD PARTIAL"),
                false));
        VisualRulebookPageFacts facts = new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {
                pages.forEach(page -> stored.put(page.pageNumber(), page));
            }

            @Override
            public void merge(UUID documentVersionId, List<PageFact> pages) {
                pages.forEach(page -> stored.put(page.pageNumber(), page));
            }

            @Override
            public List<PageFact> find(UUID documentVersionId, java.util.Set<Integer> pages) {
                return pages.stream().map(stored::get).filter(java.util.Objects::nonNull).toList();
            }
        };
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("progressive recovery must use the exact page binding");
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID requestedVersion, java.util.Set<Integer> pages, boolean includePageImages) {
                assertThat(requestedVersion).isEqualTo(documentVersionId);
                assertThat(pages).containsExactly(4);
                return List.of(new RuleEvidence(
                        chunkId,
                        documentVersionId,
                        "GENERAL",
                        "Visual rulebook page 4",
                        "Image-only page",
                        4,
                        4,
                        List.of(new RulePageImage(4, "image/png", new byte[] {4}, 100, 120)),
                        RuleEvidence.ContentKind.VISUAL_PLACEHOLDER));
            }
        };
        VisualRulebookPageCatalogModel catalog = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("progressive recovery must retain the lightweight Teaching model path");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                catalogCalls.incrementAndGet();
                assertThat(request.pages()).extracting(page -> page.pageNumber()).containsExactly(4);
                return new CatalogDraft(List.of(new PageSummary(
                        4,
                        "GAME END; SCORE",
                        "GAME END: 牌库耗尽且市场无法补满时游戏结束。\nSCORE: 玩家随后按照可见条件结算分数。",
                        List.of("GAME END", "SCORE"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(new SourceDependency("First Session Booklet", List.of("setup"))),
                        List.of("GAME END", "SCORE"),
                        true,
                        List.of(),
                        List.of(
                                new VisualRulebookPageCatalogModel.RuleGroupFact(
                                        "GAME END", "GAME END", "牌库耗尽且市场无法补满时游戏结束。"),
                                new VisualRulebookPageCatalogModel.RuleGroupFact(
                                        "SCORE", "SCORE", "玩家随后按照可见条件结算分数。")))));
            }

            @Override
            public boolean available(String modelConfigurationOwner) {
                return true;
            }
        };
        TeachingPlan progressivePlan = new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                "Test game",
                "Recover an exact bound page.",
                List.of(new PlannedSection(
                        1,
                        "progressive-visual-page-rules-4",
                        "Game end",
                        "Explain only the ending and scoring visibly supported on page 4.",
                        true,
                        true,
                        List.of("GAME END", "SCORE"),
                        List.of("setup", "core_loop", "end", "scoring"),
                        List.of(4))),
                "player",
                Instant.now());
        ImmediateAuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionEvidenceRetriever retriever = new TeachingSectionEvidenceRetriever(
                tools,
                new PolicyEvidenceVerifier(),
                invocations,
                new TeachingVisualEvidenceResolver(tools, invocations, facts, catalog));

        TeachingSectionEvidenceRetriever.Result result = retriever.retrieve(
                progressivePlan,
                progressivePlan.sections().getFirst(),
                UUID.randomUUID(),
                3,
                true);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.canonicalPageObservation()).isPresent();
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(chunkId);
            assertThat(evidence.contentKind()).isEqualTo(RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
            assertThat(evidence.excerpt()).contains("无法补满时游戏结束");
            assertThat(evidence.excerpt()).doesNotContain("Visual-transcribed rule evidence");
            assertThat(evidence.pageImages()).singleElement().satisfies(image -> assertThat(image.pageNumber())
                    .isEqualTo(4));
        });
        assertThat(catalogCalls).hasValue(1);
        assertThat(stored.keySet()).containsExactly(4);
        assertThat(stored.get(4)).satisfies(fact -> {
            assertThat(fact.factualSummary()).doesNotContain("earlier current-schema observation");
            assertThat(fact.visualAnchors()).containsExactly(priorAnchor);
            assertThat(fact.iconOccurrences()).containsExactly(priorIcon);
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.sourceDependencies())
                    .containsExactly(new SourceDependency("First Session Booklet", List.of("setup")));
            assertThat(fact.ruleGroupIdentifiers()).containsExactly("GAME END", "SCORE");
            assertThat(fact.ruleGroupInventoryComplete()).isTrue();
        });
    }

    private TeachingSectionEvidenceRetriever retriever(AssistantReadTools tools) {
        ImmediateAuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        return new TeachingSectionEvidenceRetriever(
                tools,
                new PolicyEvidenceVerifier(),
                invocations,
                new TeachingVisualEvidenceResolver(
                        tools,
                        invocations,
                        VisualRulebookPageFacts.empty(),
                        VisualRulebookPageCatalogModel.unavailable()));
    }

    private RuleEvidence evidence(UUID chunkId, UUID versionId, String excerpt) {
        return new RuleEvidence(chunkId, versionId, "SETUP", "Setup", excerpt, 2, 2);
    }
}
