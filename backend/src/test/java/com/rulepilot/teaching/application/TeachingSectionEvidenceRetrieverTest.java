package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
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
        assertThat(result.toolCalls()).isEqualTo(3);
    }

    @Test
    void recoversOneBoundPageWhenProgressiveBackgroundPrefetchFailed() {
        UUID chunkId = UUID.randomUUID();
        AtomicInteger catalogCalls = new AtomicInteger();
        Map<Integer, VisualRulebookPageFacts.PageFact> stored = new java.util.LinkedHashMap<>();
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
                        TeachingVisualEvidenceSelector.VISUAL_PAGE_PLACEHOLDER,
                        4,
                        4,
                        List.of(new RulePageImage(4, "image/png", new byte[] {4}, 100, 120))));
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
                        "牌库耗尽且市场无法补满时游戏结束；玩家随后按照可见条件结算分数。",
                        List.of("GAME END", "SCORE"))));
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
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(chunkId);
            assertThat(evidence.excerpt()).contains("Visual-transcribed rule evidence", "无法补满时游戏结束");
            assertThat(evidence.pageImages()).singleElement().satisfies(image -> assertThat(image.pageNumber())
                    .isEqualTo(4));
        });
        assertThat(catalogCalls).hasValue(1);
        assertThat(stored.keySet()).containsExactly(4);
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
