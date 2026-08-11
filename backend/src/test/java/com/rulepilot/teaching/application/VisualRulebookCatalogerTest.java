package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierLocalizationDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierLocalizationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;
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
    void aTimedOutProviderCallDoesNotConsumeTheTimeoutOfQueuedPages() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        Map<Integer, AtomicInteger> callsByPage = new java.util.concurrent.ConcurrentHashMap<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    int pageNumber = request.pages().getFirst().pageNumber();
                    int call = callsByPage
                            .computeIfAbsent(pageNumber, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    if (pageNumber == 1 && call == 1) {
                        long deadline = System.nanoTime() + Duration.ofMillis(150).toNanos();
                        while (System.nanoTime() < deadline) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ignored) {
                                // Simulate an HTTP provider that does not terminate when Future.cancel interrupts it.
                            }
                        }
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(
                            new VisualRulebookPageCatalogModel.PageSummary(
                                    pageNumber,
                                    "PAGE " + pageNumber,
                                    "Visible rule " + pageNumber,
                                    List.of("page " + pageNumber))));
                },
                facts,
                1,
                Duration.ofMillis(40));

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                null);

        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(2, 3);
        assertThat(callsByPage.get(1)).hasValue(1);
        assertThat(callsByPage.get(2)).hasValue(1);
        assertThat(callsByPage.get(3)).hasValue(1);
    }

    @Test
    void denseIconPageFallsBackToFourOverlappingTilesAfterWholePageRetryFails() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger fullPageCalls = new AtomicInteger();
        List<VisualRulebookPageCatalogModel.PageViewport> inspectedViewports =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        byte[] pageContent = renderedPage();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", pageContent, 100, 100))
                        .toList(),
                request -> {
                    if (request.viewport() == null) {
                        fullPageCalls.incrementAndGet();
                        throw new IllegalStateException("dense full page response was unavailable");
                    }
                    inspectedViewports.add(request.viewport());
                    return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(
                            new VisualRulebookPageCatalogModel.PageSummary(
                                    1,
                                    "VISIBLE LEGEND",
                                    "该分块中可见一个图标图例。",
                                    List.of("legend"),
                                    List.of(),
                                    List.of(new IconOccurrence(
                                            "victory point",
                                            "胜利点",
                                            "绿色圆形胜利点符号。",
                                            "代表胜利点。",
                                            "Victory Point",
                                            IconMeaningStatus.EXPLICIT,
                                            100,
                                            100,
                                            40,
                                            40)),
                                    true)));
                },
                facts);

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(fullPageCalls).hasValue(2);
        assertThat(inspectedViewports)
                .containsExactly(
                        new VisualRulebookPageCatalogModel.PageViewport(1, 0, 0, 550, 550),
                        new VisualRulebookPageCatalogModel.PageViewport(1, 450, 0, 550, 550),
                        new VisualRulebookPageCatalogModel.PageViewport(1, 0, 450, 550, 550),
                        new VisualRulebookPageCatalogModel.PageViewport(1, 450, 450, 550, 550));
        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.iconOccurrences()).singleElement().satisfies(icon -> {
                assertThat(icon.groupKey()).isEqualTo("victory point");
                assertThat(icon.x()).isEqualTo(55);
                assertThat(icon.y()).isEqualTo(55);
                assertThat(icon.width()).isEqualTo(22);
                assertThat(icon.height()).isEqualTo(22);
            });
        });
    }

    @Test
    void visuallyRichPageWithAnEmptyFullPageInventoryIsCheckedInTiles() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger fullPageCalls = new AtomicInteger();
        AtomicInteger tileCalls = new AtomicInteger();
        byte[] pageContent = renderedPage();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                request -> {
                    if (request.viewport() == null) {
                        fullPageCalls.incrementAndGet();
                        return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(
                                new VisualRulebookPageCatalogModel.PageSummary(
                                        1,
                                        "SCORING REFERENCE",
                                        "该页有多个计分示例。",
                                        List.of("scoring"),
                                        List.of(new VisualAnchor(
                                                "table", "Scoring", "一张带图形的计分表。", 100, 100, 300, 300)),
                                        List.of(),
                                        true)));
                    }
                    tileCalls.incrementAndGet();
                    return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(
                            new VisualRulebookPageCatalogModel.PageSummary(
                                    1,
                                    "VICTORY POINT",
                                    "分块内可见胜利点图例。",
                                    List.of("victory point"),
                                    List.of(),
                                    List.of(new IconOccurrence(
                                            "victory point",
                                            "胜利点",
                                            "绿色圆形符号。",
                                            "",
                                            "",
                                            IconMeaningStatus.UNEXPLAINED,
                                            100,
                                            100,
                                            40,
                                            40)),
                                    true)));
                },
                facts);

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(fullPageCalls).hasValue(1);
        assertThat(tileCalls).hasValue(4);
        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.iconOccurrences()).singleElement();
        });
    }

    @Test
    void verifiesIconRectanglesInADedicatedPassWithoutSemanticNameFiltering() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger localizationCalls = new AtomicInteger();
        AtomicInteger cropReviewCalls = new AtomicInteger();
        byte[] pageContent = renderedPage();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "VISIBLE ICONS",
                        "该页有两个候选图标。",
                        List.of("icons"),
                        List.of(),
                        List.of(
                                new IconOccurrence(
                                        "leaf",
                                        "叶子",
                                        "绿色叶片。",
                                        "",
                                        "",
                                        IconMeaningStatus.UNEXPLAINED,
                                        600,
                                        600,
                                        30,
                                        30),
                                new IconOccurrence(
                                        "point card",
                                        "积分卡",
                                        "Whole card with a scoring condition.",
                                        "",
                                        "",
                                        IconMeaningStatus.UNEXPLAINED,
                                        700,
                                        700,
                                        30,
                                        30)),
                        true)));
            }

            @Override
            public IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
                localizationCalls.incrementAndGet();
                return new IconLocalizationDraft(List.of(
                        new IconLocation(0, true, 120, 240, 24, 28),
                        new IconLocation(1, true, 700, 700, 120, 160)));
            }

            @Override
            public IconCropReviewDraft reviewIconCrops(IconCropReviewRequest request) {
                int call = cropReviewCalls.incrementAndGet();
                assertThat(request.candidates()).hasSize(1);
                int candidateIndex = request.locations().getFirst().candidateIndex();
                if (candidateIndex == 0 && call == 2) {
                    return new IconCropReviewDraft(List.of(new IconCropDecision(candidateIndex, true, 130, 250, 20, 22)));
                }
                return new IconCropReviewDraft(
                        List.of(candidateIndex == 0
                                ? new IconCropDecision(candidateIndex, true, 120, 240, 24, 28)
                                : new IconCropDecision(candidateIndex, true, 700, 700, 30, 30)));
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                model,
                facts);

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(localizationCalls).hasValue(1);
        assertThat(cropReviewCalls).hasValue(4);
        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.iconOccurrences()).hasSize(2);
            assertThat(fact.iconOccurrences().getFirst()).satisfies(icon -> {
                assertThat(icon.name()).isEqualTo("叶子");
                assertThat(icon.x()).isEqualTo(130);
                assertThat(icon.y()).isEqualTo(250);
                assertThat(icon.width()).isEqualTo(20);
                assertThat(icon.height()).isEqualTo(22);
            });
            assertThat(fact.iconOccurrences().get(1)).satisfies(icon -> {
                assertThat(icon.name()).isEqualTo("积分卡");
                assertThat(icon.x()).isEqualTo(700);
                assertThat(icon.y()).isEqualTo(700);
                assertThat(icon.width()).isEqualTo(30);
                assertThat(icon.height()).isEqualTo(30);
            });
        });
    }

    @Test
    void dropsAFullPageLocationWhenItsCloseUpDoesNotContainTheProposedIcon() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        byte[] pageContent = renderedPage();
        AtomicInteger cropReviewCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "LEAF",
                        "该页有一个候选图标。",
                        List.of("leaf"),
                        List.of(),
                        List.of(new IconOccurrence(
                                "leaf",
                                "叶子",
                                "绿色叶片。",
                                "",
                                "",
                                IconMeaningStatus.UNEXPLAINED,
                                600,
                                600,
                                30,
                                30)),
                        true)));
            }

            @Override
            public IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
                return new IconLocalizationDraft(List.of(
                        new IconLocation(0, true, 120, 240, 24, 28)));
            }

            @Override
            public IconCropReviewDraft reviewIconCrops(IconCropReviewRequest request) {
                cropReviewCalls.incrementAndGet();
                return new IconCropReviewDraft(List.of(new IconCropDecision(0, false)));
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                model,
                new InMemoryFacts());

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(cropReviewCalls).hasValue(1);
        assertThat(result).singleElement().satisfies(fact -> assertThat(fact.iconOccurrences()).isEmpty());
    }

    @Test
    void retriesOneMalformedLocalizationResponseBeforeLeavingThePageIncomplete() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        byte[] pageContent = renderedPage();
        AtomicInteger localizationCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "LEAF",
                        "该页有一个候选图标。",
                        List.of("leaf"),
                        List.of(),
                        List.of(new IconOccurrence(
                                "leaf",
                                "叶子",
                                "绿色叶片。",
                                "",
                                "",
                                IconMeaningStatus.UNEXPLAINED,
                                600,
                                600,
                                30,
                                30)),
                        true)));
            }

            @Override
            public IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
                if (localizationCalls.incrementAndGet() == 1) {
                    throw new IllegalArgumentException("provider omitted one candidate");
                }
                return new IconLocalizationDraft(List.of(
                        new IconLocation(0, true, 120, 240, 24, 28)));
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                model,
                new InMemoryFacts());

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(localizationCalls).hasValue(2);
        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.iconOccurrences()).singleElement().satisfies(icon -> {
                assertThat(icon.x()).isEqualTo(120);
                assertThat(icon.y()).isEqualTo(240);
            });
        });
    }

    @Test
    void preservesVerifiedPartialIconsWhenALaterRetryFindsDifferentSymbols() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(new PageFact(
                1,
                "WOOD; BRICK",
                "该页包含资源图例。",
                List.of("WOOD", "BRICK"),
                List.of(),
                List.of(new IconOccurrence(
                        "BRICK",
                        "砖块",
                        "红色方块。",
                        "",
                        "",
                        "BRICK",
                        IconMeaningStatus.UNEXPLAINED,
                        100,
                        100,
                        30,
                        30)),
                false,
                PageFact.CURRENT_SCHEMA_VERSION)));
        byte[] pageContent = renderedPage();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                request -> new CatalogDraft(List.of(new PageSummary(
                        1,
                        "WOOD; BRICK",
                        "该页包含资源图例。",
                        List.of("WOOD", "BRICK"),
                        List.of(),
                        List.of(new IconOccurrence(
                                "WOOD",
                                "木材",
                                "棕色方块。",
                                "",
                                "",
                                IconMeaningStatus.UNEXPLAINED,
                                200,
                                100,
                                30,
                                30)),
                        true))),
                facts);

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.iconOccurrences())
                    .extracting(IconOccurrence::groupKey)
                    .containsExactly("BRICK", "WOOD");
        });
        assertThat(facts.find(documentVersionId, Set.of(1))).singleElement().satisfies(fact ->
                assertThat(fact.iconOccurrences())
                        .extracting(IconOccurrence::groupKey)
                        .containsExactly("BRICK", "WOOD"));
    }

    @Test
    void retriesLocalizationFailureInTilesWithoutPublishingUnverifiedRectangles() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        byte[] pageContent = renderedPage();
        AtomicInteger summaryCalls = new AtomicInteger();
        AtomicInteger localizationCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                summaryCalls.incrementAndGet();
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "LEAF",
                        "该页有一个叶子图标。",
                        List.of("leaf"),
                        List.of(new VisualAnchor(
                                "icon legend", "Leaf icon", "A labeled symbol.", 100, 100, 200, 200)),
                        List.of(new IconOccurrence(
                                "leaf",
                                "叶子",
                                "绿色叶片。",
                                "",
                                "",
                                IconMeaningStatus.UNEXPLAINED,
                                600,
                                600,
                                30,
                                30)),
                        true)));
            }

            @Override
            public IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
                localizationCalls.incrementAndGet();
                throw new IllegalArgumentException("provider returned malformed coordinates");
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", pageContent, 100, 100)),
                model,
                new InMemoryFacts());

        List<PageFact> result = cataloger.catalogAllIconPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(summaryCalls).hasValue(5);
        assertThat(localizationCalls).hasValue(4);
        assertThat(result).singleElement().satisfies(fact -> {
            assertThat(fact.iconInventoryComplete()).isFalse();
            assertThat(fact.iconOccurrences()).isEmpty();
        });
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
    void preservesBothExactCatalogBlocksWithoutSemanticTokenRewriting() {
        String merged = VisualRulebookCataloger.mergeIdentifierFactsWithSharedRules(
                "A-01: Move one space.\nA-02: Draw one card.",
                "Move one space. Draw one card. Items may occupy any slot. Fill upper slots from left to right.");

        assertThat(merged)
                .contains("A-01: Move one space.", "A-02: Draw one card.", "Items may occupy any slot.",
                        "Fill upper slots from left to right.", "Move one space. Draw one card.");
        assertThat(VisualRulebookCataloger.mergeIdentifierFactsWithSharedRules("same block", "same block"))
                .isEqualTo("same block");
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

    @Test
    void rereadsDenseIdentifierCatalogsAsIndependentlyBoundCells() throws IOException {
        UUID documentVersionId = UUID.randomUUID();
        byte[] pageContent = renderedPage();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                return new CatalogDraft(List.of(new PageSummary(
                        1,
                        "A-01; A-02; B#03; B#04",
                        "A dense reference catalog is visible.",
                        List.of("reference"))));
            }

            @Override
            public IdentifierLocalizationDraft locateIdentifiers(IdentifierLocalizationRequest request) {
                return new IdentifierLocalizationDraft(List.of(
                        new IdentifierLocation("A-01", 80, 300, 40, 12),
                        new IdentifierLocation("A-02", 520, 300, 40, 12),
                        new IdentifierLocation("B#03", 80, 650, 40, 12),
                        new IdentifierLocation("B#04", 520, 650, 40, 12)));
            }

            @Override
            public IdentifierCellDraft summarizeIdentifierCells(IdentifierCellRequest request) {
                return new IdentifierCellDraft(request.cells().stream()
                        .map(cell -> new IdentifierCellFact(
                                cell.identifier(), cell.identifier() + "：可见效果。"))
                        .toList());
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", pageContent, 1_000, 1_000)),
                model,
                new InMemoryFacts());

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Fictional reference", "owner", null);

        assertThat(result).singleElement().satisfies(input -> assertThat(input.text())
                .contains("A-01：可见效果。", "A-02：可见效果。", "B#03：可见效果。", "B#04：可见效果。"));
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
        return cataloger(pageImages, model, facts, visualRequestParallelism, Duration.ofSeconds(2));
    }

    private static VisualRulebookCataloger cataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel model,
            VisualRulebookPageFacts facts,
            int visualRequestParallelism,
            Duration timeout) {
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
                timeout,
                4,
                visualRequestParallelism);
    }

    private static PageView page(int pageNumber) {
        return new PageView(pageNumber, "", 0);
    }

    private static byte[] renderedPage() throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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
