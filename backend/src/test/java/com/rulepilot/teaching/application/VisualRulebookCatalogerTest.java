package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogerTest {

    @Test
    void retainsCompletedPageFactsWhenALaterVisualBatchFails() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    if (request.pages().stream().anyMatch(page -> page.pageNumber() == 4)) {
                        throw new IllegalStateException("provider rejected page four");
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> new VisualRulebookPageCatalogModel.PageSummary(
                                    page.pageNumber(),
                                    "PAGE " + page.pageNumber(),
                                    "A visible rule on page " + page.pageNumber(),
                                    List.of("page " + page.pageNumber())))
                            .toList());
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3), page(4)),
                "Example game",
                "owner",
                null);

        assertThat(inputs).extracting(PageInput::pageNumber).containsExactly(1, 2, 3, 4);
        assertThat(inputs.getFirst().text()).contains("PAGE 1", "A visible rule on page 1");
        assertThat(inputs.get(1).text()).contains("PAGE 2", "A visible rule on page 2");
        assertThat(inputs.get(2).text()).contains("PAGE 3", "A visible rule on page 3");
        assertThat(inputs.getLast().text()).contains("No factual visual claim", "page 4");
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void retriesOnlyTheFailedPageWhenItsInitialVisualRequestFails() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger calls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    calls.incrementAndGet();
                    if (request.pages().getFirst().pageNumber() == 1 && calls.get() == 1) {
                        throw new IllegalArgumentException("first page response was truncated");
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> new VisualRulebookPageCatalogModel.PageSummary(
                                    page.pageNumber(),
                                    "PAGE " + page.pageNumber(),
                                    "Visible rule on page " + page.pageNumber(),
                                    List.of("page " + page.pageNumber())))
                            .toList());
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                null);

        assertThat(calls).hasValue(4);
        assertThat(inputs).allSatisfy(input -> assertThat(input.text()).contains("Visible rule on page"));
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void reusesAnchoredFactsWithoutCallingTheVisionModelAgain() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(
                fact(1, "SETUP"),
                fact(2, "TURN")));
        AtomicInteger pageReads = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> {
                    pageReads.incrementAndGet();
                    return List.of();
                },
                request -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("cached visual facts must avoid a new model call");
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2)),
                "Example game",
                "owner",
                null);

        assertThat(inputs).extracting(PageInput::pageNumber).containsExactly(1, 2);
        assertThat(pageReads).hasValue(0);
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    void recatalogsFactsWrittenByAnOlderVisualTranscriptionContract() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(new PageFact(
                1,
                "OLD",
                "旧摘要遗漏了规则条件。",
                List.of("old"),
                List.of(),
                1)));
        AtomicInteger modelCalls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(
                            new VisualRulebookPageCatalogModel.PageSummary(
                                    1,
                                    "CURRENT",
                                    "新转录保留完整的条件、动作和结果。",
                                    List.of("current"))));
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(modelCalls).hasValue(1);
        assertThat(inputs).singleElement().extracting(PageInput::text).asString()
                .contains("新转录保留完整的条件、动作和结果。")
                .doesNotContain("旧摘要遗漏");
        assertThat(facts.find(documentVersionId, Set.of(1))).singleElement()
                .extracting(PageFact::schemaVersion)
                .isEqualTo(PageFact.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void completesMissingPagesWhenAVisualOnlyRulebookHasPartialCachedFacts() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(fact(1, "COVER")));
        List<List<Integer>> requestedBatches = new java.util.ArrayList<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    requestedBatches.add(request.pages().stream().map(page -> page.pageNumber()).toList());
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> new VisualRulebookPageCatalogModel.PageSummary(
                                    page.pageNumber(), "PAGE " + page.pageNumber(), "Visible rule", List.of("page")))
                            .toList());
                },
                facts);

        cataloger.catalogVisualPages(documentVersionId, List.of(page(1), page(2), page(3)), "Example game", "owner", null);

        assertThat(requestedBatches).containsExactly(List.of(2), List.of(3));
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void respectsConfiguredVisualRequestParallelism() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger peakRequests = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    int active = activeRequests.incrementAndGet();
                    peakRequests.accumulateAndGet(active, Math::max);
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> new VisualRulebookPageCatalogModel.PageSummary(
                                    page.pageNumber(),
                                    "PAGE " + page.pageNumber(),
                                    "Visible rule " + page.pageNumber(),
                                    List.of("page " + page.pageNumber())))
                            .toList());
                },
                new InMemoryFacts(),
                1);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3), page(4)),
                "Example game",
                "owner",
                null);

        assertThat(peakRequests).hasValue(1);
    }

    @Test
    void catalogsEachPhotographedPageIndependently() {
        UUID documentVersionId = UUID.randomUUID();
        List<List<Integer>> batches = new java.util.ArrayList<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    batches.add(request.pages().stream().map(page -> page.pageNumber()).toList());
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> new VisualRulebookPageCatalogModel.PageSummary(
                                    page.pageNumber(), "PAGE", "Visible rule", List.of("page")))
                            .toList());
                },
                new InMemoryFacts());

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3), page(4), page(5)),
                "Example game",
                "owner",
                null);

        assertThat(batches).containsExactly(List.of(1), List.of(2), List.of(3), List.of(4), List.of(5));
    }

    @Test
    void catalogsLegendAndGameplayPagesIndependently() {
        assertThat(VisualRulebookCatalogPolicy.singlePageBatches(List.of(2, 3)))
                .containsExactly(List.of(2), List.of(3));
    }

    @Test
    void reusesAnchorlessFactsBecauseAnchorsAreOptionalCropHints() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(new PageFact(
                1, "SETUP", "Visible setup instruction.", List.of("setup"), List.of())));
        AtomicInteger modelCalls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> {
                    throw new AssertionError("cached page facts must avoid an image read");
                },
                request -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("anchorless facts must not be recataloged");
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(inputs.getFirst().text()).contains("Visible setup instruction");
        assertThat(modelCalls).hasValue(0);
    }

    private static VisualRulebookCataloger cataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel model,
            VisualRulebookPageFacts facts) {
        return cataloger(pageImages, model, facts, 1);
    }

    private static VisualRulebookCataloger cataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel model,
            VisualRulebookPageFacts facts,
            int visualRequestParallelism) {
        return new VisualRulebookCataloger(
                pageImages,
                model,
                facts,
                new AuditedAgentInvocations() {
                    @Override
                    public <T> T invoke(
                            UUID runId,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                            String operation,
                            int estimatedInputTokens,
                            String successSummary,
                            Supplier<T> invocation,
                            ToIntFunction<T> outputTokenEstimator) {
                        return invocation.get();
                    }
                },
                Duration.ofSeconds(2),
                4,
                visualRequestParallelism);
    }

    private static PageView page(int pageNumber) {
        return new PageView(pageNumber, "", 0);
    }

    private static PageFact fact(int pageNumber, String term) {
        return new PageFact(
                pageNumber,
                term,
                "Visible " + term + " rule.",
                List.of(term),
                List.of(new VisualAnchor("diagram", term, "Visible " + term + " diagram.", 10, 10, 100, 100)));
    }

    private static final class InMemoryFacts implements VisualRulebookPageFacts {

        private final Map<UUID, List<PageFact>> factsByVersion = new HashMap<>();

        @Override
        public void replace(UUID documentVersionId, List<PageFact> pages) {
            factsByVersion.put(documentVersionId, List.copyOf(pages));
        }

        @Override
        public void merge(UUID documentVersionId, List<PageFact> pages) {
            Map<Integer, PageFact> byPage = new HashMap<>();
            factsByVersion.getOrDefault(documentVersionId, List.of())
                    .forEach(fact -> byPage.put(fact.pageNumber(), fact));
            pages.forEach(fact -> byPage.put(fact.pageNumber(), fact));
            factsByVersion.put(
                    documentVersionId,
                    byPage.values().stream().sorted(java.util.Comparator.comparingInt(PageFact::pageNumber)).toList());
        }

        @Override
        public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
            return factsByVersion.getOrDefault(documentVersionId, List.of()).stream()
                    .filter(fact -> pageNumbers.contains(fact.pageNumber()))
                    .toList();
        }
    }
}
