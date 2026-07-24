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
    void retainsCompletedPageFactsWhenOneVisualBatchFails() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    if (request.pages().stream().anyMatch(page -> page.pageNumber() == 3)) {
                        throw new IllegalStateException("provider rejected page three");
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
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                null);

        assertThat(inputs).extracting(PageInput::pageNumber).containsExactly(1, 2, 3);
        assertThat(inputs.getFirst().text()).contains("PAGE 1", "A visible rule on page 1");
        assertThat(inputs.get(1).text()).contains("PAGE 2", "A visible rule on page 2");
        assertThat(inputs.getLast().text()).contains("No factual visual claim", "page 3");
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2);
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

    private static VisualRulebookCataloger cataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel model,
            VisualRulebookPageFacts facts) {
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
                4);
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
