package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeachingVisualEvidenceResolverWorkloadTest {

    @Test
    void countsEveryBoundPageEvenWhenASectionBindsMoreThanOneReadBatch() {
        TeachingPlan plan = plan(List.of(List.of(1, 2, 3, 4, 5, 6)), false);

        assertThat(TeachingVisualEvidenceResolver.maximumModelCalls(plan)).isEqualTo(18);
        assertThat(TeachingSectionEvidenceRetriever.maximumToolCalls(plan, plan.sections().getFirst(), 3))
                .isEqualTo(3);
        assertThat(TeachingSourcePageEvidenceRefiner.maximumToolCalls(plan, plan.sections().getFirst()))
                .isEqualTo(2);
    }

    @Test
    void readsEveryPageOfALegalSixPageSectionInBoundedBatches() {
        AtomicInteger reads = new AtomicInteger();
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                assertThat(pageNumbers).hasSizeLessThanOrEqualTo(5);
                reads.incrementAndGet();
                return pageNumbers.stream()
                        .map(page -> new RuleEvidence(
                                UUID.randomUUID(),
                                documentVersionId,
                                "RULES",
                                "Page " + page,
                                "Image-only page " + page,
                                page,
                                page,
                                List.of(new RulePageImage(page, "image/png", new byte[] {1}, 1, 1)),
                                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER))
                        .toList();
            }
        };
        TeachingVisualEvidenceResolver resolver = new TeachingVisualEvidenceResolver(
                tools,
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable());
        TeachingPlan plan = plan(List.of(List.of(1, 2, 3, 4, 5, 6)), true);

        var resolution = resolver.resolve(
                plan, plan.sections().getFirst(), List.of(), UUID.randomUUID());

        assertThat(reads).hasValue(2);
        assertThat(resolution.toolCalls()).isEqualTo(2);
        assertThat(resolution.evidence()).hasSize(6);
        assertThat(resolution.canonicalPageObservation())
                .get()
                .extracting(TeachingVisualEvidenceResolver.CanonicalPageObservation::requestedPages)
                .isEqualTo(Set.of(1, 2, 3, 4, 5, 6));
    }

    @Test
    void delegatesOneTransientReplayToThePageCatalogOwner() {
        UUID versionId = UUID.randomUUID();
        AtomicInteger interpretations = new AtomicInteger();
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                return List.of(new RuleEvidence(
                        UUID.randomUUID(),
                        versionId,
                        "RULES",
                        "Page 1",
                        "Image-only page",
                        1,
                        1,
                        List.of(new RulePageImage(1, "image/png", new byte[] {1}, 100, 100)),
                        RuleEvidence.ContentKind.VISUAL_PLACEHOLDER));
            }
        };
        VisualRulebookPageCatalogModel catalog = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("progressive teaching should use its page-scoped visual path");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                if (interpretations.incrementAndGet() == 1) {
                    throw new org.springframework.ai.retry.TransientAiException("temporary visual response failure");
                }
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "TURN",
                        "TURN: Perform the visible action.",
                        List.of("turn"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("turn"),
                        true,
                        List.of(),
                        List.of(new RuleGroupFact("turn", "Turn", "Perform the visible action.")))));
            }

            @Override
            public boolean available(String modelConfigurationOwner) {
                return true;
            }
        };
        StoredFacts facts = new StoredFacts();
        TeachingVisualEvidenceResolver resolver = new TeachingVisualEvidenceResolver(
                tools,
                new ImmediateAuditedAgentInvocations(),
                facts,
                catalog);
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Visual retry fixture",
                "Retry a failed page interpretation without repeating the page read.",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "progressive-visual-page-rules-1",
                        "Turn",
                        "Teach the visible turn.",
                        true,
                        true,
                        List.of("turn"),
                        List.of("source_coverage"),
                        List.of(1))),
                "player",
                Instant.now());

        var resolution = resolver.resolve(
                plan, plan.sections().getFirst(), List.of(), UUID.randomUUID());

        assertThat(interpretations).hasValue(2);
        assertThat(resolution.toolCalls()).isOne();
        assertThat(resolution.evidence()).singleElement().satisfies(source -> {
            assertThat(source.contentKind()).isEqualTo(RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
            assertThat(source.excerpt()).contains("Perform the visible action");
        });
    }

    @Test
    void reusesOneDurablePageAcrossThreeChaptersWithoutReadingAnImageOrCallingAModel() {
        UUID versionId = UUID.randomUUID();
        StoredFacts facts = new StoredFacts();
        facts.merge(versionId, List.of(completeFact(1)));
        AtomicInteger imageReads = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                modelCalls.incrementAndGet();
                throw new AssertionError("a durable complete fact must bypass the model");
            }

            @Override
            public boolean available(String owner) {
                return true;
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> {
                    imageReads.incrementAndGet();
                    throw new AssertionError("a durable complete fact must bypass page-image storage");
                },
                model,
                facts,
                new ImmediateAuditedAgentInvocations(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4,
                1);
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                assertThat(includePageImages).isFalse();
                evidenceReads.incrementAndGet();
                return List.of(new RuleEvidence(
                        UUID.nameUUIDFromBytes((documentVersionId + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        documentVersionId,
                        "RULES",
                        "Page 1",
                        "Image-only page",
                        1,
                        1,
                        List.of(),
                        RuleEvidence.ContentKind.VISUAL_PLACEHOLDER));
            }
        };
        TeachingVisualEvidenceResolver resolver = new TeachingVisualEvidenceResolver(
                tools, new ImmediateAuditedAgentInvocations(), facts, cataloger);
        TeachingPlan plan = plan(versionId, List.of(List.of(1), List.of(1), List.of(1)), true);

        plan.sections().forEach(section -> {
            var resolution = resolver.resolve(plan, section, List.of(), UUID.randomUUID());
            assertThat(resolution.evidence()).singleElement().satisfies(source -> {
                assertThat(source.contentKind()).isEqualTo(RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
                assertThat(source.excerpt()).contains("Perform the visible action");
                assertThat(source.pageImages()).isEmpty();
            });
        });

        assertThat(imageReads).hasValue(0);
        assertThat(modelCalls).hasValue(0);
        assertThat(evidenceReads).hasValue(3);
    }

    @Test
    void preservesPriorEvidenceWhenALaterVisualReadBatchFails() {
        AtomicInteger reads = new AtomicInteger();
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                reads.incrementAndGet();
                if (pageNumbers.contains(6)) throw new IllegalStateException("second batch unavailable");
                return pageNumbers.stream()
                        .map(page -> new RuleEvidence(
                                UUID.randomUUID(),
                                documentVersionId,
                                "RULES",
                                "Page " + page,
                                "Image-only page " + page,
                                page,
                                page,
                                List.of(new RulePageImage(page, "image/png", new byte[] {1}, 1, 1)),
                                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER))
                        .toList();
            }
        };
        TeachingVisualEvidenceResolver resolver = new TeachingVisualEvidenceResolver(
                tools,
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable());
        TeachingPlan plan = plan(List.of(List.of(1, 2, 3, 4, 5, 6)), true);
        List<RuleEvidence> prior = IntStream.rangeClosed(1, 6)
                .mapToObj(page -> new RuleEvidence(
                        UUID.randomUUID(),
                        plan.documentVersionId(),
                        "RULES",
                        "Text page " + page,
                        "Verified text " + page,
                        page,
                        page,
                        List.of(),
                        RuleEvidence.ContentKind.CANONICAL_TEXT))
                .toList();

        var resolution = resolver.resolve(
                plan, plan.sections().getFirst(), prior, UUID.randomUUID());

        assertThat(reads).hasValue(2);
        assertThat(resolution.toolCalls()).isEqualTo(2);
        assertThat(resolution.evidence()).containsAll(prior).hasSize(11);
        assertThat(resolution.evidence().stream()
                        .flatMap(source -> source.pageImages().stream())
                        .map(RulePageImage::pageNumber))
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(resolution.canonicalPageObservation()).isEmpty();
    }

    @Test
    void keepsAdmissionIndependentOfMutableCatalogAvailabilityAndAddsProgressivePrefetch() {
        TeachingPlan plan = plan(List.of(List.of(1), List.of(2), List.of(3)), true);

        // Reserve the longest mutually exclusive owner branch: initial semantic, OCR, then changed typed semantic.
        // The ordinary path remains one image-to-typed-facts call per distinct page.
        assertThat(TeachingVisualEvidenceResolver.maximumModelCalls(plan)).isEqualTo(9);
    }

    private TeachingPlan plan(List<List<Integer>> sourcePages, boolean progressive) {
        return plan(UUID.randomUUID(), sourcePages, progressive);
    }

    private TeachingPlan plan(UUID documentVersionId, List<List<Integer>> sourcePages, boolean progressive) {
        List<TeachingPlan.PlannedSection> sections = IntStream.range(0, sourcePages.size())
                .mapToObj(index -> new TeachingPlan.PlannedSection(
                        index + 1,
                        (progressive ? "progressive-visual-page-rules-" : "topic-") + (index + 1),
                        "Topic " + (index + 1),
                        "Teach only the bounded source-page relation.",
                        true,
                        true,
                        List.of("source relation " + (index + 1)),
                        List.of("source_coverage"),
                        sourcePages.get(index)))
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                "Visual workload fixture",
                "Count the immutable plan before execution.",
                sections,
                "player",
                Instant.now());
    }

    private static PageFact completeFact(int pageNumber) {
        return new PageFact(
                pageNumber,
                "TURN",
                "TURN: Perform the visible action.",
                List.of("turn"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("turn"),
                true,
                List.of(new VisualRulebookPageCatalogModel.RuleGroupFact(
                        "turn", "Turn", "Perform the visible action.")));
    }

    private static final class StoredFacts implements VisualRulebookPageFacts {
        private final Map<UUID, List<PageFact>> facts = new HashMap<>();

        @Override
        public void replace(UUID documentVersionId, List<PageFact> pages) {
            facts.put(documentVersionId, List.copyOf(pages));
        }

        @Override
        public void merge(UUID documentVersionId, List<PageFact> pages) {
            Map<Integer, PageFact> byPage = new HashMap<>();
            facts.getOrDefault(documentVersionId, List.of()).forEach(page -> byPage.put(page.pageNumber(), page));
            pages.forEach(page -> byPage.put(page.pageNumber(), page));
            facts.put(documentVersionId, List.copyOf(byPage.values()));
        }

        @Override
        public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
            return facts.getOrDefault(documentVersionId, List.of()).stream()
                    .filter(page -> pageNumbers.contains(page.pageNumber()))
                    .toList();
        }
    }
}
