package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ModelExecutionIdentity;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRejection;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogerTest {

    @Test
    void keepsReadablePageLedgersWhenOneStoredPageImageCannotBeRead() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        List<Integer> interpretedPages = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> {
                    int pageNumber = pages.iterator().next();
                    if (pageNumber == 2) {
                        throw new IllegalStateException("stored page image cannot be decoded");
                    }
                    return List.of(new DocumentPageImages.PageImage(
                            pageNumber, "image/png", new byte[] {1}, 100, 120));
                },
                request -> {
                    int pageNumber = request.pages().getFirst().pageNumber();
                    interpretedPages.add(pageNumber);
                    return new CatalogDraft(List.of(teachingSummary(
                            pageNumber,
                            "PAGE " + pageNumber,
                            "A visible rule on page " + pageNumber,
                            List.of("page " + pageNumber))));
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                null);

        assertThat(interpretedPages).containsExactlyInAnyOrder(1, 3);
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 3);
        assertThat(inputs).extracting(PageInput::pageNumber).containsExactly(1, 2, 3);
        assertThat(inputs.get(1).pageLedgerState())
                .isEqualTo(com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE);
        assertThat(inputs.get(1).text()).contains("visual interpretation did not finish");
    }

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
                            .map(page -> teachingSummary(
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

        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2, 3);
        assertThat(inputs).hasSize(4);
        assertThat(inputs.get(3).text()).contains("visual interpretation did not finish");
    }

    @Test
    void doesNotReplayAnAuditedTypedContractFailureAndKeepsOtherPages() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        List<String> operations = new java.util.ArrayList<>();
        AtomicInteger failedPageCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                int pageNumber = request.pages().getFirst().pageNumber();
                if (pageNumber == 1) {
                    failedPageCalls.incrementAndGet();
                    throw new IllegalArgumentException("page one response violated the typed contract");
                }
                return new CatalogDraft(List.of(teachingSummary(
                        pageNumber,
                        "PAGE " + pageNumber,
                        "Visible rule on page " + pageNumber,
                        List.of("page " + pageNumber))));
            }
        };
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                model,
                facts,
                audit,
                Duration.ofSeconds(2),
                                1);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2)),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(failedPageCalls).hasValue(1);
        assertThat(operations).containsExactly(
                "inspectTeachingVisualPageCandidate|1|2|candidate-1",
                "inspectTeachingVisualPageCandidate|2|2|candidate-1");
        assertThat(facts.find(documentVersionId, Set.of(1, 2)))
                .extracting(PageFact::pageNumber)
                .containsExactly(2);
        assertThat(inputs).hasSize(2);
        assertThat(inputs.getFirst().text()).contains("visual interpretation did not finish");
        assertThat(inputs.get(1).text()).contains("Visible rule on page 2");
    }

    @Test
    void doesNotRetryWhenTheAssistantRunHasStopped() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        AtomicInteger reservations = new AtomicInteger();
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    throw new AssertionError("a stopped run must not invoke the provider");
                },
                new InMemoryFacts(),
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
                        reservations.incrementAndGet();
                        throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
                    }
                },
                Duration.ofSeconds(2),
                                1);

        assertThatThrownBy(() -> cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        assistantRunId))
                .isInstanceOfSatisfying(
                        AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.MODEL_BUDGET));
        assertThat(reservations).hasValue(1);
    }

    @Test
    void reportsPageAttemptsAgainstTheFullRulebookWhenEarlierPagesAreCached() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(fact(1, "CACHED")));
        List<String> operations = new java.util.ArrayList<>();
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> new CatalogDraft(request.pages().stream()
                        .map(image -> teachingSummary(
                                image.pageNumber(),
                                "PAGE " + image.pageNumber(),
                                "Visible rule on page " + image.pageNumber(),
                                List.of("page " + image.pageNumber())))
                        .toList()),
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
                        operations.add(operation);
                        return invocation.get();
                    }

                    @Override
                    public void record(
                            UUID runId,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                            String operation,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                            String summary) {
                        operations.add(operation);
                    }
                },
                Duration.ofSeconds(2),
                                1);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(operations).containsExactly(
                "reuseVisualPageFacts",
                "inspectTeachingVisualPageCandidate|2|3|candidate-1",
                "settleTeachingVisualPageCandidate|2|3|candidate-1|accepted|NONE",
                "persistTeachingVisualPage|2|3",
                "inspectTeachingVisualPageCandidate|3|3|candidate-1",
                "settleTeachingVisualPageCandidate|3|3|candidate-1|accepted|NONE",
                "persistTeachingVisualPage|3|3");
        assertThat(facts.mergeCalls()).isEqualTo(3);
    }

    @Test
    void doesNotPublishAggregateCompletionWhenTheMissingPageExhaustsItsRetry() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(fact(1, "CACHED")));
        List<String> records = new java.util.ArrayList<>();
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    throw new IllegalArgumentException("typed page ledger was invalid");
                },
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

                    @Override
                    public void record(
                            UUID runId,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                            String operation,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                            String summary) {
                        records.add(operation + ":" + outcome + ":" + summary);
                    }
                },
                Duration.ofSeconds(2),
                                1);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2)),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(records)
                .noneSatisfy(record -> assertThat(record).startsWith("completeVisualPageFacts:SUCCEEDED"));
    }

    @Test
    void stopsThePageWindowWhenItsCallerIsInterruptedInsteadOfRetrying() throws InterruptedException {
        UUID documentVersionId = UUID.randomUUID();
        CountDownLatch providerStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    calls.incrementAndGet();
                    providerStarted.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("provider request interrupted", interrupted);
                    }
                    throw new AssertionError("the interrupted provider request must not finish normally");
                },
                new InMemoryFacts());
        Thread caller = new Thread(() -> {
            try {
                cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        null);
            } catch (Throwable stopped) {
                failure.set(stopped);
            }
        }, "visual-page-window-caller");

        caller.start();
        assertThat(providerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(1_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOfSatisfying(
                IllegalStateException.class,
                stopped -> assertThat(stopped.getCause()).isInstanceOf(InterruptedException.class));
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRepeatThePaidModelCallWhenPersistingItsValidPageFactFails() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        AtomicInteger modelCalls = new AtomicInteger();
        List<String> operations = new java.util.ArrayList<>();
        VisualRulebookPageFacts failingFacts = new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID versionId, List<PageFact> pages) {
                throw new AssertionError("teaching startup persists with merge");
            }

            @Override
            public void merge(UUID versionId, List<PageFact> pages) {
                throw new IllegalStateException("page fact store unavailable");
            }

            @Override
            public List<PageFact> find(UUID versionId, Set<Integer> pageNumbers) {
                return List.of();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    modelCalls.incrementAndGet();
                    int pageNumber = request.pages().getFirst().pageNumber();
                    return new CatalogDraft(List.of(teachingSummary(
                            pageNumber,
                            "PAGE " + pageNumber,
                            "Visible rule on page " + pageNumber,
                            List.of("page " + pageNumber))));
                },
                failingFacts,
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
                        operations.add(operation);
                        return invocation.get();
                    }

                    @Override
                    public void record(
                            UUID runId,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                            String operation,
                            com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                            String summary) {
                        operations.add(operation);
                    }
                },
                Duration.ofSeconds(2),
                                1);

        assertThatThrownBy(() -> cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        assistantRunId))
                .isInstanceOf(TeachingPreparationStorageException.class)
                .hasRootCauseMessage("page fact store unavailable");
        assertThat(modelCalls).hasValue(1);
        assertThat(operations).containsExactly(
                "inspectTeachingVisualPageCandidate|1|1|candidate-1",
                "settleTeachingVisualPageCandidate|1|1|candidate-1|accepted|NONE");
    }

    @Test
    void classifiesPageFactReadFailureBeforeStartingAnyPaidModelWork() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger modelCalls = new AtomicInteger();
        VisualRulebookPageFacts unavailableFacts = new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID versionId, List<PageFact> pages) {}

            @Override
            public void merge(UUID versionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID versionId, Set<Integer> pageNumbers) {
                throw new IllegalStateException("page fact read unavailable");
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new CatalogDraft(List.of());
                },
                unavailableFacts);

        assertThatThrownBy(() -> cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        UUID.randomUUID()))
                .isInstanceOf(TeachingPreparationStorageException.class)
                .hasRootCauseMessage("page fact read unavailable");
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    void preservesAPartialPageInventoryWithoutRepeatingTheSamePaidRead() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger calls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", new byte[] {1}, 100, 120)),
                request -> {
                    calls.incrementAndGet();
                    return new CatalogDraft(List.of(new PageSummary(
                            1,
                            "MOVE; BUILD",
                            "MOVE: 当前玩家按照可见条件移动。\nBUILD: 当前玩家按照可见条件建造。",
                            List.of("MOVE", "BUILD"),
                            List.of(),
                            List.of(),
                            List.of("MOVE", "BUILD"),
                            false,
                            List.of(),
                            List.of(
                                    new RuleGroupFact("MOVE", "MOVE", "当前玩家按照可见条件移动。"),
                                    new RuleGroupFact("BUILD", "BUILD", "当前玩家按照可见条件建造。")))));
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(calls).hasValue(1);
        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.sourceRuleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
            assertThat(input.sourceRuleGroupInventoryComplete()).isFalse();
            assertThat(input.sourceRuleGroupFacts())
                    .extracting(RuleGroupFact::identifier)
                    .containsExactly("MOVE", "BUILD");
            assertThat(input.text()).contains("当前玩家按照可见条件移动", "当前玩家按照可见条件建造");
        });
    }

    @Test
    void completeRetryReplacesCurrentIncompleteLedgerWithoutDiscardingPriorVisualAudit() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        VisualAnchor priorAnchor = new VisualAnchor(
                "diagram", "Prior board map", "A previously localized board map.", 40, 50, 300, 220);
        facts.merge(documentVersionId, List.of(new PageFact(
                1,
                "OLD PARTIAL",
                "An earlier full-page observation admitted that it was partial.",
                List.of("old"),
                List.of(priorAnchor),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(new SourceDependency("Obsolete leaflet", List.of("setup"))),
                List.of("OLD PARTIAL"),
                false,
                List.of())));
        AtomicInteger calls = new AtomicInteger();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(
                        new DocumentPageImages.PageImage(1, "image/png", new byte[] {1}, 100, 120)),
                request -> {
                    calls.incrementAndGet();
                    return new CatalogDraft(List.of(new PageSummary(
                            1,
                            "MOVE; BUILD",
                            "MOVE: Move one pawn.\nBUILD: Place one building.",
                            List.of("move", "build"),
                            List.of(),
                            List.of(new SourceDependency("First Session Guide", List.of("setup"))),
                            List.of("MOVE", "BUILD"),
                            true,
                            List.of(),
                            List.of(
                                    new RuleGroupFact("MOVE", "MOVE", "Move one pawn."),
                                    new RuleGroupFact("BUILD", "BUILD", "Place one building.")))));
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(calls).hasValue(1);
        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.sourceRuleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
            assertThat(input.sourceRuleGroupInventoryComplete()).isTrue();
            assertThat(input.text()).contains("MOVE: Move one pawn.", "BUILD: Place one building.")
                    .doesNotContain("earlier full-page observation");
        });
        assertThat(facts.find(documentVersionId, Set.of(1))).singleElement().satisfies(fact -> {
            assertThat(fact.printedTerms()).isEqualTo("MOVE; BUILD");
            assertThat(fact.factualSummary()).doesNotContain("earlier full-page observation");
            assertThat(fact.visualAnchors()).containsExactly(priorAnchor);
            assertThat(fact.sourceDependencies())
                    .containsExactly(new SourceDependency("First Session Guide", List.of("setup")));
            assertThat(fact.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
            assertThat(fact.ruleGroupInventoryComplete()).isTrue();
        });
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
                    return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(teachingSummary(
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
                            .map(page -> teachingSummary(
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
        CountDownLatch firstWindowReady = new CountDownLatch(10);
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    int active = activeRequests.incrementAndGet();
                    peakRequests.accumulateAndGet(active, Math::max);
                    try {
                        if (request.pages().getFirst().pageNumber() <= 10) {
                            firstWindowReady.countDown();
                            assertThat(firstWindowReady.await(1, TimeUnit.SECONDS)).isTrue();
                        }
                        Thread.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(page -> teachingSummary(
                                    page.pageNumber(),
                                    "PAGE " + page.pageNumber(),
                                    "Visible rule " + page.pageNumber(),
                                    List.of("page " + page.pageNumber())))
                            .toList());
                },
                new InMemoryFacts(),
                10);

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId,
                java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(VisualRulebookCatalogerTest::page)
                        .toList(),
                "Example game",
                "owner",
                null);

        assertThat(peakRequests).hasValue(10);
        assertThat(result).extracting(PageInput::pageNumber)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList());
    }

    @Test
    void tenWayWindowSharesOneProviderDeadlineAndKeepsEveryCompletedPage() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    int pageNumber = request.pages().getFirst().pageNumber();
                    if (pageNumber <= 2) {
                        long providerDeadline = System.nanoTime() + Duration.ofMillis(350).toNanos();
                        while (System.nanoTime() < providerDeadline) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ignored) {
                                // A real HTTP client may not stop until its own socket timeout after cancellation.
                            }
                        }
                    }
                    return new CatalogDraft(request.pages().stream()
                            .map(image -> teachingSummary(
                                    image.pageNumber(),
                                    "PAGE " + image.pageNumber(),
                                    "Visible rule " + image.pageNumber(),
                                    List.of("page " + image.pageNumber())))
                            .toList());
                },
                facts,
                10,
                Duration.ofMillis(100));

        long started = System.nanoTime();
        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                IntStream.rangeClosed(1, 10).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                null);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(175);
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
                .extracting(PageFact::pageNumber)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(inputs).hasSize(10);
        assertThat(inputs.subList(0, 2))
                .allSatisfy(input -> assertThat(input.text()).contains("visual interpretation did not finish"));
    }

    @Test
    void compatibilityWorkflowDeadlineStopsLaterPagesWithoutInventingACallCountBudget() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        List<List<Integer>> requestedBatches = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                request -> {
                    List<Integer> requested = request.pages().stream()
                            .map(image -> image.pageNumber())
                            .toList();
                    requestedBatches.add(requested);
                    if (requested.getFirst() == 1) {
                        long deadline = System.nanoTime() + Duration.ofMillis(150).toNanos();
                        while (System.nanoTime() < deadline) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ignored) {
                                // Simulate an HTTP provider that does not terminate when Future.cancel interrupts it.
                            }
                        }
                    }
                    return new VisualRulebookPageCatalogModel.CatalogDraft(request.pages().stream()
                            .map(image -> teachingSummary(
                                    image.pageNumber(),
                                    "PAGE " + image.pageNumber(),
                                    "Visible rule " + image.pageNumber(),
                                    List.of("page " + image.pageNumber())))
                            .toList());
                },
                facts,
                1,
                Duration.ofMillis(40));

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(
                        page(1), page(2), page(3), page(4), page(5),
                        page(6), page(7), page(8), page(9), page(10)),
                "Example game",
                "owner",
                null);

        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
                .isEmpty();
        assertThat(inputs).hasSize(10);
        assertThat(inputs).allSatisfy(input ->
                assertThat(input.text()).contains("visual interpretation did not finish"));
        assertThat(requestedBatches).containsExactly(List.of(1));
    }

    @Test
    void readsAShortRulebookAsIndependentlyDurablePageLedgersWithoutRunningTheHeavyCatalog() {
        UUID documentVersionId = UUID.randomUUID();
        List<List<Integer>> batches = new java.util.ArrayList<>();
        List<List<Integer>> imageReads = new java.util.ArrayList<>();
        AtomicInteger heavyCatalogCalls = new AtomicInteger();
        InMemoryFacts facts = new InMemoryFacts();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                heavyCatalogCalls.incrementAndGet();
                throw new AssertionError("teaching startup must not run the legacy full-page catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                List<Integer> requested = request.pages().stream().map(image -> image.pageNumber()).toList();
                batches.add(requested);
                return new CatalogDraft(request.pages().stream()
                        .map(image -> teachingSummary(
                                image.pageNumber(),
                                "PAGE " + image.pageNumber(),
                                "Visible rule " + image.pageNumber(),
                                List.of("page " + image.pageNumber())))
                        .toList());
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> {
                    assertThat(pages).hasSizeLessThanOrEqualTo(DocumentPageImages.MAX_PAGES_PER_READ);
                    imageReads.add(List.copyOf(pages));
                    return pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList();
                },
                model,
                facts);

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3), page(4), page(5), page(6), page(7), page(8)),
                "Example game",
                "owner",
                null);

        assertThat(batches).containsExactly(
                List.of(1), List.of(2), List.of(3), List.of(4),
                List.of(5), List.of(6), List.of(7), List.of(8));
        assertThat(imageReads)
                .containsExactly(
                        List.of(1),
                        List.of(2),
                        List.of(3),
                        List.of(4),
                        List.of(5),
                        List.of(6),
                        List.of(7),
                        List.of(8));
        assertThat(heavyCatalogCalls).hasValue(0);
        assertThat(result).extracting(PageInput::pageNumber).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(result).allSatisfy(input -> assertThat(input.text()).contains("Visible rule"));
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4, 5, 6, 7, 8)))
                .allSatisfy(fact -> assertThat(fact.schemaVersion()).isEqualTo(PageFact.CURRENT_SCHEMA_VERSION));
    }

    @Test
    void observesCandidateCorrectionProviderFailureAndPersistenceWithOnlyLowCardinalityTags() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger pageTwoInitialCalls = new AtomicInteger();
        var recorded = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if (!"rulepilot.teaching.visual_page_catalog".equals(context.getName())) return;
                List<String> keys = java.util.stream.StreamSupport.stream(
                                context.getLowCardinalityKeyValues().spliterator(), false)
                        .map(io.micrometer.common.KeyValue::getKey)
                        .sorted()
                        .toList();
                assertThat(keys).containsExactly("outcome", "retry_kind", "stage");
                assertThat(context.getHighCardinalityKeyValues()).isEmpty();
                recorded.add(context.getLowCardinalityKeyValue("stage").getValue()
                        + ":" + context.getLowCardinalityKeyValue("retry_kind").getValue()
                        + ":" + context.getLowCardinalityKeyValue("outcome").getValue());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("Teaching facts must use the Teaching catalog");
            }

            @Override
            public boolean available(String owner) {
                return true;
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                int pageNumber = request.pages().getFirst().pageNumber();
                if (pageNumber == 1) {
                    throw rejectedPageCandidate(pageNumber, "candidate-a");
                }
                if (pageTwoInitialCalls.incrementAndGet() == 1) {
                    throw new org.springframework.ai.retry.TransientAiException("provider reset");
                }
                return new CatalogDraft(List.of(teachingSummary(
                        pageNumber, "TURN", "The player performs one action.", List.of("turn"))));
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                return new CatalogDraft(List.of(teachingSummary(
                        1, "SETUP", "The player prepares the board.", List.of("setup"))));
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                directAudit(),
                Duration.ofSeconds(2),
                                1,
                1,
                observations);

        cataloger.ensureTeachingPageFacts(documentVersionId, Set.of(1), 2, "Example game", "owner", null);
        cataloger.ensureTeachingPageFacts(documentVersionId, Set.of(2), 2, "Example game", "owner", null);

        assertThat(recorded).containsExactly(
                "semantic:candidate:rejected",
                "semantic:correction:accepted",
                "persist:correction:completed",
                "semantic:candidate:provider_failed");
    }

    @Test
    void recordsTheActualTeachingStartupProviderAndRequestModelInTheRunActivity() {
        UUID documentVersionId = UUID.randomUUID();
        List<String> activitySummaries = new java.util.ArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use its lightweight model operation");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                return new CatalogDraft(List.of(teachingSummary(
                        1, "TURN", "The active player takes one action.", List.of("turn"))));
            }

            @Override
            public Optional<ModelExecutionIdentity> teachingStartupExecutionIdentity(String owner) {
                return Optional.of(new ModelExecutionIdentity("qwen", "qwen3.6-flash"));
            }
        };
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                activitySummaries.add(successSummary);
                return invocation.get();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                                1);

        cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(activitySummaries)
                .containsExactly("Teaching-start page candidate received via qwen/qwen3.6-flash");
    }

    @Test
    void ordinaryColdPagesUseOneSemanticCallWithTheBoundedFourRequestLane()
            throws InterruptedException {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger activeSemantic = new AtomicInteger();
        AtomicInteger peakSemantic = new AtomicInteger();
        CountDownLatch firstSemanticWindow = new CountDownLatch(4);
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                semanticCalls.incrementAndGet();
                int active = activeSemantic.incrementAndGet();
                peakSemantic.accumulateAndGet(active, Math::max);
                int pageNumber = request.pages().getFirst().pageNumber();
                try {
                    if (pageNumber <= 4) {
                        firstSemanticWindow.countDown();
                        assertThat(firstSemanticWindow.await(2, TimeUnit.SECONDS)).isTrue();
                    }
                    return new CatalogDraft(List.of(teachingSummary(
                            pageNumber,
                            "PAGE " + pageNumber,
                            "Visible rule on page " + pageNumber,
                            List.of("page " + pageNumber))));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    activeSemantic.decrementAndGet();
                }
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                directAudit(),
                Duration.ofSeconds(3),
                                10,
                4);

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId,
                IntStream.rangeClosed(1, 12).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                null);

        assertThat(semanticCalls).hasValue(12);
        assertThat(peakSemantic).hasValue(4);
        assertThat(result).hasSize(12);
    }

    @Test
    void keepsLargeTeachingImageReadsBoundedToTheCurrentSemanticWindow() {
        UUID documentVersionId = UUID.randomUUID();
        List<List<Integer>> imageReads = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                int pageNumber = request.pages().getFirst().pageNumber();
                return new CatalogDraft(List.of(teachingSummary(
                        pageNumber,
                        "PAGE " + pageNumber,
                        "Visible rule on page " + pageNumber,
                        List.of("page " + pageNumber))));
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> {
                    imageReads.add(List.copyOf(pages));
                    return pages.stream()
                            .map(page -> new DocumentPageImages.PageImage(
                                    page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                            .toList();
                },
                model,
                new InMemoryFacts(),
                directAudit(),
                Duration.ofSeconds(2),
                                10,
                4);

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId,
                IntStream.rangeClosed(1, 18).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                null);

        assertThat(result).hasSize(18);
        assertThat(imageReads).allSatisfy(read -> assertThat(read)
                .hasSizeLessThanOrEqualTo(DocumentPageImages.MAX_PAGES_PER_READ));
        assertThat(imageReads).containsExactly(
                List.of(1, 2, 3, 4),
                List.of(5, 6, 7, 8),
                List.of(9, 10, 11, 12),
                List.of(13, 14, 15, 16),
                List.of(17, 18));
        Map<Integer, Long> readsPerPage = imageReads.stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.groupingBy(page -> page, java.util.stream.Collectors.counting()));
        assertThat(readsPerPage).hasSize(18).allSatisfy((page, reads) -> assertThat(reads).isEqualTo(1));
    }

    @Test
    void twoDifferentInvalidCandidatesCanBeFollowedByAValidThirdCandidateOnEveryPage() {
        UUID documentVersionId = UUID.randomUUID();
        List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
        Map<Integer, AtomicInteger> repairCalls = new java.util.concurrent.ConcurrentHashMap<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                throw rejectedPageCandidate(request.pages().getFirst().pageNumber(), "candidate-a");
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                int pageNumber = request.pages().getFirst().pageNumber();
                int correction = repairCalls.computeIfAbsent(pageNumber, ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (correction == 1) throw rejectedPageCandidate(pageNumber, "candidate-b");
                return new CatalogDraft(List.of(teachingSummary(
                        pageNumber,
                        "PAGE " + pageNumber,
                        "Visible rule on page " + pageNumber,
                        List.of("page " + pageNumber))));
            }

        };
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                                3,
                3);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                UUID.randomUUID());

        assertThat(repairCalls).hasSize(3).allSatisfy((page, calls) -> assertThat(calls).hasValue(2));
        assertThat(operations).contains(
                "inspectTeachingVisualPageCandidate|1|3|candidate-1",
                "inspectTeachingVisualPageCandidate|1|3|candidate-2",
                "inspectTeachingVisualPageCandidate|1|3|candidate-3",
                "inspectTeachingVisualPageCandidate|2|3|candidate-3",
                "inspectTeachingVisualPageCandidate|3|3|candidate-3");
    }

    @Test
    void exactRejectedObservationStopsOnTheSecondCandidateWithoutAnotherCorrection() {
        AtomicInteger corrections = new AtomicInteger();
        List<String> activities = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw rejectedPageCandidate(1, "candidate-a");
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                corrections.incrementAndGet();
                throw rejectedPageCandidate(1, "candidate-a");
            }
        };
        VisualRulebookCataloger cataloger = catalogerWithActivityCapture(model, activities);

        List<PageInput> result = cataloger.catalogVisualPages(
                UUID.randomUUID(), List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(corrections).hasValue(1);
        assertThat(activities).contains(
                "settleTeachingVisualPageCandidate|1|1|candidate-1|correction-follows|SCHEMA_MISMATCH",
                "settleTeachingVisualPageCandidate|1|1|candidate-2|no-progress|SCHEMA_MISMATCH");
        assertThat(result).singleElement().satisfies(input ->
                assertThat(input.text()).contains("visual interpretation did not finish"));
    }

    @Test
    void fullHistoryStopsAnABACycleOnTheThirdCandidate() {
        AtomicInteger corrections = new AtomicInteger();
        List<String> activities = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw rejectedPageCandidate(1, "candidate-a");
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                int correction = corrections.incrementAndGet();
                throw rejectedPageCandidate(1, correction == 1 ? "candidate-b" : "candidate-a");
            }
        };
        VisualRulebookCataloger cataloger = catalogerWithActivityCapture(model, activities);

        cataloger.catalogVisualPages(
                UUID.randomUUID(), List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(corrections).hasValue(2);
        assertThat(activities).contains(
                "settleTeachingVisualPageCandidate|1|1|candidate-1|correction-follows|SCHEMA_MISMATCH",
                "settleTeachingVisualPageCandidate|1|1|candidate-2|correction-follows|SCHEMA_MISMATCH",
                "settleTeachingVisualPageCandidate|1|1|candidate-3|no-progress|SCHEMA_MISMATCH");
    }

    @Test
    void successfulSiblingPersistsBeforeAnotherPageCorrectionAndIsNotRegenerated() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        AtomicInteger pageTwoCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                int pageNumber = request.pages().getFirst().pageNumber();
                if (pageNumber == 1) throw rejectedPageCandidate(1, "candidate-a");
                pageTwoCalls.incrementAndGet();
                return new CatalogDraft(List.of(teachingSummary(
                        2, "TURN", "The active player takes one action.", List.of("turn"))));
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                assertThat(facts.find(documentVersionId, Set.of(2)))
                        .as("a successful sibling must be durable before this correction starts")
                        .singleElement();
                return new CatalogDraft(List.of(teachingSummary(
                        1, "SETUP", "Prepare the shared board.", List.of("setup"))));
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                facts,
                directAudit(),
                Duration.ofSeconds(2),
                2,
                2);

        cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1), page(2)), "Example game", "owner", UUID.randomUUID());

        assertThat(pageTwoCalls).hasValue(1);
        assertThat(facts.find(documentVersionId, Set.of(1, 2)))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2);
    }

    @Test
    void providerTransportFailureIsLocalAndDoesNotMasqueradeAsJsonCorrection() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger imageReads = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                if (semanticCalls.incrementAndGet() == 1) {
                    throw new org.springframework.ai.retry.TransientAiException("provider connection reset");
                }
                return new CatalogDraft(List.of(teachingSummary(
                        1, "TURN", "The active player takes one action.", List.of("turn"))));
            }
        };
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> {
                    imageReads.incrementAndGet();
                    return List.of(new DocumentPageImages.PageImage(
                            1, "image/png", new byte[] {1}, 100, 120));
                },
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                                10,
                4);

        cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(imageReads).hasValue(1);
        assertThat(semanticCalls).hasValue(1);
        assertThat(operations).containsExactly(
                "inspectTeachingVisualPageCandidate|1|1|candidate-1");
    }

    @Test
    void neverMakesAThirdSemanticCallWhenTypedRepairFails() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger initialCalls = new AtomicInteger();
        AtomicInteger repairCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                initialCalls.incrementAndGet();
                throw rejectedPageCandidate(1, "candidate-a");
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                repairCalls.incrementAndGet();
                throw new org.springframework.ai.retry.TransientAiException("repair provider unavailable");
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts());

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(initialCalls).hasValue(1);
        assertThat(repairCalls).hasValue(1);
        assertThat(result).singleElement().satisfies(input ->
                assertThat(input.text()).contains("visual interpretation did not finish"));
    }

    @Test
    void doesNotRetryUntypedIoNonTransientOrUnknownSemanticFailures() {
        List<RuntimeException> failures = List.of(
                new IllegalStateException("ordinary IO", new java.io.IOException("connection failed")),
                new org.springframework.ai.retry.TransientAiException(
                        "socket timeout",
                        new IllegalStateException(new java.net.SocketTimeoutException("socket timed out"))),
                new org.springframework.ai.retry.TransientAiException(
                        "HTTP timeout",
                        new IllegalStateException(new java.net.http.HttpTimeoutException("request timed out"))),
                new org.springframework.ai.retry.TransientAiException(
                        "future timeout", new IllegalStateException(new java.util.concurrent.TimeoutException("slow"))),
                new org.springframework.ai.retry.TransientAiException(
                        "interrupted IO",
                        new IllegalStateException(new java.io.InterruptedIOException("request interrupted"))),
                new org.springframework.ai.retry.NonTransientAiException("401 unauthorized"),
                new org.springframework.ai.retry.NonTransientAiException("provider rejected request"),
                new IllegalArgumentException("untyped schema failure"),
                new IllegalStateException("unknown failure"));

        for (RuntimeException failure : failures) {
            AtomicInteger semanticCalls = new AtomicInteger();
            VisualRulebookCataloger cataloger = cataloger(
                    (id, pages) -> List.of(new DocumentPageImages.PageImage(
                            1, "image/png", new byte[] {1}, 100, 120)),
                    request -> {
                        semanticCalls.incrementAndGet();
                        throw failure;
                    },
                    new InMemoryFacts());

            cataloger.catalogVisualPages(
                    UUID.randomUUID(), List.of(page(1)), "Example game", "owner", null);

            assertThat(semanticCalls).hasValue(1);
        }
    }

    @Test
    void nestedInterruptedFailureIsNotRetriedAndRestoresCallerInterruptStatus() throws InterruptedException {
        AtomicInteger semanticCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean callerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var nestedInterrupt = new org.springframework.ai.retry.TransientAiException(
                "transient wrapper",
                new IllegalStateException("provider wrapper", new InterruptedException("provider interrupted")));
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                request -> {
                    semanticCalls.incrementAndGet();
                    throw nestedInterrupt;
                },
                new InMemoryFacts());
        Thread caller = new Thread(() -> {
            try {
                cataloger.catalogVisualPages(
                        UUID.randomUUID(), List.of(page(1)), "Example game", "owner", null);
            } catch (Throwable stopped) {
                failure.set(stopped);
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "nested-semantic-interruption-caller");

        caller.start();
        caller.join(1_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isSameAs(nestedInterrupt);
        assertThat(callerInterrupted).isTrue();
        assertThat(semanticCalls).hasValue(1);
    }

    @Test
    void stoppedInitialSemanticBudgetPropagatesBeforeProviderOrRepair() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                semanticCalls.incrementAndGet();
                throw new AssertionError("provider must not start after the audited semantic budget stops");
            }
        };
        AuditedAgentInvocations stoppedAudit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                reservations.incrementAndGet();
                throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts(),
                stoppedAudit,
                Duration.ofSeconds(2),
                                10,
                4);

        assertThatThrownBy(() -> cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.MODEL_BUDGET));
        assertThat(reservations).hasValue(1);
        assertThat(semanticCalls).hasValue(0);
    }

    @Test
    void concurrentSectionsShareOneSemanticAttemptForTheSameMissingPage() throws Exception {
        UUID documentVersionId = UUID.randomUUID();
        CountDownLatch distinctReaders = new CountDownLatch(2);
        CountDownLatch semanticStarted = new CountDownLatch(1);
        CountDownLatch releaseSemantic = new CountDownLatch(1);
        AtomicInteger semanticCalls = new AtomicInteger();
        InMemoryFacts facts = new InMemoryFacts(distinctReaders);
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("Teaching facts must use the Teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                semanticCalls.incrementAndGet();
                semanticStarted.countDown();
                awaitLatch(distinctReaders, "both section callers must observe the initial durable miss");
                awaitLatch(releaseSemantic, "test must release the single semantic owner");
                return new CatalogDraft(List.of(teachingSummary(
                        1, "TURN", "The active player takes one action.", List.of("turn"))));
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                facts);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<PageFact>> first = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", null));
            Future<List<PageFact>> second = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", null));

            assertThat(semanticStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(distinctReaders.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(semanticCalls).hasValue(1);
            releaseSemantic.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.pageNumber()).isEqualTo(1));
            assertThat(second.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.pageNumber()).isEqualTo(1));
            assertThat(semanticCalls).hasValue(1);
        } finally {
            releaseSemantic.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void concurrentTypedContractRepairRunsOneInitialAndOneRepairCall() throws Exception {
        UUID documentVersionId = UUID.randomUUID();
        CountDownLatch distinctReaders = new CountDownLatch(2);
        AtomicInteger initialCalls = new AtomicInteger();
        AtomicInteger repairCalls = new AtomicInteger();
        InMemoryFacts facts = new InMemoryFacts(distinctReaders);
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("Teaching facts must use the Teaching catalog");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                initialCalls.incrementAndGet();
                awaitLatch(distinctReaders, "both section callers must join before typed repair");
                throw rejectedPageCandidate(1, "candidate-a");
            }

            @Override
            public CatalogDraft correctTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRejection rejection) {
                repairCalls.incrementAndGet();
                return new CatalogDraft(List.of(teachingSummary(
                        1, "SETUP", "Prepare the shared board before play.", List.of("setup"))));
            }
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                facts);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<PageFact>> first = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", null));
            Future<List<PageFact>> second = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", null));

            assertThat(first.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.factualSummary()).contains("Prepare the shared board"));
            assertThat(second.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.factualSummary()).contains("Prepare the shared board"));
            assertThat(initialCalls).hasValue(1);
            assertThat(repairCalls).hasValue(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void sequentialSectionsReuseALocalizedNegativeOutcomeWithinOneRunButANewRunMayRetry() {
        UUID documentVersionId = UUID.randomUUID();
        UUID firstRunId = UUID.randomUUID();
        AtomicInteger semanticCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = request -> {
            if (semanticCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("provider rejected this page");
            }
            return new CatalogDraft(List.of(teachingSummary(
                    1, "TURN", "The active player takes one action.", List.of("turn"))));
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts());

        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", firstRunId))
                .isEmpty();
        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", firstRunId))
                .isEmpty();
        assertThat(semanticCalls).hasValue(1);

        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", UUID.randomUUID()))
                .singleElement()
                .satisfies(fact -> assertThat(fact.pageNumber()).isEqualTo(1));
        assertThat(semanticCalls).hasValue(2);
    }

    @Test
    void concurrentRunsDoNotShareRunSpecificBudgetFailure() throws Exception {
        UUID documentVersionId = UUID.randomUUID();
        UUID budgetStoppedRunId = UUID.randomUUID();
        UUID independentRunId = UUID.randomUUID();
        CountDownLatch stoppedRunReserved = new CountDownLatch(1);
        CountDownLatch releaseStoppedRun = new CountDownLatch(1);
        CountDownLatch independentSemanticStarted = new CountDownLatch(1);
        AtomicInteger stoppedRunReservations = new AtomicInteger();
        AtomicInteger independentRunReservations = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                if (budgetStoppedRunId.equals(runId)) {
                    stoppedRunReservations.incrementAndGet();
                    stoppedRunReserved.countDown();
                    awaitLatch(releaseStoppedRun, "test must release the budget-stopped run");
                    throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
                }
                assertThat(runId).isEqualTo(independentRunId);
                independentRunReservations.incrementAndGet();
                return invocation.get();
            }
        };
        VisualRulebookPageCatalogModel model = request -> {
            semanticCalls.incrementAndGet();
            independentSemanticStarted.countDown();
            return new CatalogDraft(List.of(teachingSummary(
                    1, "TURN", "The active player takes one action.", List.of("turn"))));
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                                1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<List<PageFact>> stoppedRun = null;
        Future<List<PageFact>> independentRun = null;
        try {
            stoppedRun = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", budgetStoppedRunId));
            assertThat(stoppedRunReserved.await(2, TimeUnit.SECONDS)).isTrue();
            independentRun = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", independentRunId));

            assertThat(independentSemanticStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(independentRun.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.pageNumber()).isEqualTo(1));
            assertThat(stoppedRunReservations).hasValue(1);
            assertThat(independentRunReservations).hasValue(1);
            assertThat(semanticCalls).hasValue(1);

            releaseStoppedRun.countDown();
            Future<List<PageFact>> failedRun = stoppedRun;
            assertThatThrownBy(() -> failedRun.get(2, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class, failed ->
                            assertThat(failed.getCause()).isInstanceOfSatisfying(
                                            AgentExecutionStoppedException.class,
                                            stopped -> assertThat(stopped.reason())
                                                    .isEqualTo(StopReason.MODEL_BUDGET)));
        } finally {
            releaseStoppedRun.countDown();
            if (stoppedRun != null) stoppedRun.cancel(true);
            if (independentRun != null) independentRun.cancel(true);
            callers.shutdownNow();
        }
    }

    @Test
    void expiredRunNegativeOutcomeAllowsTheSameRunToAttemptAgain() {
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        AtomicInteger semanticCalls = new AtomicInteger();
        VisualRulebookCataloger cataloger = catalogerWithRunAttemptRetention(
                request -> {
                    semanticCalls.incrementAndGet();
                    throw new IllegalStateException("provider rejected this page");
                },
                clock,
                Duration.ofMinutes(1),
                8);

        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", runId))
                .isEmpty();
        clock.advance(Duration.ofSeconds(59));
        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", runId))
                .isEmpty();
        assertThat(semanticCalls).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", runId))
                .isEmpty();
        assertThat(semanticCalls).hasValue(2);
    }

    @Test
    void settledRunNegativeOutcomesEvictTheOldestEntryAtCapacity() {
        UUID documentVersionId = UUID.randomUUID();
        UUID oldestRunId = UUID.randomUUID();
        UUID retainedRunId = UUID.randomUUID();
        AtomicInteger semanticCalls = new AtomicInteger();
        VisualRulebookCataloger cataloger = catalogerWithRunAttemptRetention(
                request -> {
                    semanticCalls.incrementAndGet();
                    throw new IllegalStateException("provider rejected this page");
                },
                Clock.systemUTC(),
                Duration.ofHours(1),
                2);

        for (UUID runId : List.of(oldestRunId, retainedRunId, UUID.randomUUID())) {
            assertThat(cataloger.ensureTeachingPageFacts(
                            documentVersionId, Set.of(1), 1, "Example game", "owner", runId))
                    .isEmpty();
        }
        assertThat(semanticCalls).hasValue(3);

        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", retainedRunId))
                .isEmpty();
        assertThat(semanticCalls).hasValue(3);
        assertThat(cataloger.ensureTeachingPageFacts(
                        documentVersionId, Set.of(1), 1, "Example game", "owner", oldestRunId))
                .isEmpty();
        assertThat(semanticCalls).hasValue(4);
    }

    @Test
    void failedTeachingPageOwnerSharesAndSettlesTheFailureForItsRunButANewRunMayRetry() throws Exception {
        UUID documentVersionId = UUID.randomUUID();
        CountDownLatch distinctReaders = new CountDownLatch(2);
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AuditedAgentInvocations failOnceAudit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                if (reservations.incrementAndGet() == 1) {
                    awaitLatch(distinctReaders, "both section callers must share the failed owner");
                    throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
                }
                return invocation.get();
            }
        };
        VisualRulebookPageCatalogModel model = request -> {
            semanticCalls.incrementAndGet();
            return new CatalogDraft(List.of(teachingSummary(
                    1, "TURN", "The active player takes one action.", List.of("turn"))));
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts(distinctReaders),
                failOnceAudit,
                Duration.ofSeconds(2),
                                1);
        UUID runId = UUID.randomUUID();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<PageFact>> first = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", runId));
            Future<List<PageFact>> second = callers.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", runId));

            assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class, failed ->
                            assertThat(failed.getCause()).isInstanceOfSatisfying(
                                            AgentExecutionStoppedException.class,
                                            stopped -> assertThat(stopped.reason())
                                                    .isEqualTo(StopReason.MODEL_BUDGET)));
            assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class, failed ->
                            assertThat(failed.getCause()).isInstanceOfSatisfying(
                                            AgentExecutionStoppedException.class,
                                            stopped -> assertThat(stopped.reason())
                                                    .isEqualTo(StopReason.MODEL_BUDGET)));
            assertThat(reservations).hasValue(1);

            assertThatThrownBy(() -> cataloger.ensureTeachingPageFacts(
                            documentVersionId, Set.of(1), 1, "Example game", "owner", runId))
                    .isInstanceOfSatisfying(
                            AgentExecutionStoppedException.class,
                            stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.MODEL_BUDGET));
            assertThat(reservations).hasValue(1);

            assertThat(cataloger.ensureTeachingPageFacts(
                            documentVersionId, Set.of(1), 1, "Example game", "owner", UUID.randomUUID()))
                    .singleElement()
                    .satisfies(fact -> assertThat(fact.pageNumber()).isEqualTo(1));
            assertThat(reservations).hasValue(2);
            assertThat(semanticCalls).hasValue(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void interruptedFollowerStopsWaitingWithoutCancellingTheTeachingPageOwner() throws Exception {
        UUID documentVersionId = UUID.randomUUID();
        CountDownLatch distinctReaders = new CountDownLatch(2);
        CountDownLatch semanticStarted = new CountDownLatch(1);
        CountDownLatch releaseSemantic = new CountDownLatch(1);
        AtomicInteger semanticCalls = new AtomicInteger();
        InMemoryFacts facts = new InMemoryFacts(distinctReaders);
        VisualRulebookPageCatalogModel model = request -> {
            semanticCalls.incrementAndGet();
            semanticStarted.countDown();
            awaitLatch(releaseSemantic, "test must release the Teaching page owner");
            return new CatalogDraft(List.of(teachingSummary(
                    1, "TURN", "The active player takes one action.", List.of("turn"))));
        };
        VisualRulebookCataloger cataloger = cataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                facts);
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        AtomicBoolean followerInterrupted = new AtomicBoolean();
        try {
            Future<List<PageFact>> owner = ownerExecutor.submit(() -> cataloger.ensureTeachingPageFacts(
                    documentVersionId, Set.of(1), 1, "Example game", "owner", null));
            assertThat(semanticStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread follower = new Thread(() -> {
                try {
                    cataloger.ensureTeachingPageFacts(
                            documentVersionId, Set.of(1), 1, "Example game", "owner", null);
                } catch (Throwable failure) {
                    followerFailure.set(failure);
                    followerInterrupted.set(Thread.currentThread().isInterrupted());
                }
            }, "teaching-page-fact-follower");
            follower.start();
            assertThat(distinctReaders.await(2, TimeUnit.SECONDS)).isTrue();
            follower.interrupt();
            follower.join(2_000);

            assertThat(follower.isAlive()).isFalse();
            assertThat(followerInterrupted).isTrue();
            assertThat(followerFailure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(semanticCalls).hasValue(1);

            releaseSemantic.countDown();
            assertThat(owner.get(2, TimeUnit.SECONDS)).singleElement().satisfies(fact ->
                    assertThat(fact.pageNumber()).isEqualTo(1));
            assertThat(semanticCalls).hasValue(1);
        } finally {
            releaseSemantic.countDown();
            ownerExecutor.shutdownNow();
        }
    }

    @Test
    void reusesAnchorlessFactsBecauseAnchorsAreOptionalCropHints() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        facts.merge(documentVersionId, List.of(new PageFact(
                1,
                "SETUP",
                "SETUP: Visible setup instruction.",
                List.of("setup"),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("SETUP"),
                true,
                List.of(new RuleGroupFact("SETUP", "SETUP", "Visible setup instruction.")))));
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

    private static VisualRulebookCataloger catalogerWithRunAttemptRetention(
            VisualRulebookPageCatalogModel model,
            Clock clock,
            Duration retention,
            int settledCapacity) {
        return new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(
                                page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                directAudit(),
                Duration.ofSeconds(2),
                                1,
                1,
                ObservationRegistry.NOOP,
                clock,
                retention,
                settledCapacity);
    }

    private static VisualRulebookCataloger catalogerWithActivityCapture(
            VisualRulebookPageCatalogModel model, List<String> activities) {
        AuditedAgentInvocations audit = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                activities.add(operation);
                return invocation.get();
            }

            @Override
            public void record(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                    String summary) {
                activities.add(operation);
            }
        };
        return new VisualRulebookCataloger(
                (id, pages) -> List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 100, 120)),
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                1,
                1);
    }

    private static AuditedAgentInvocations directAudit() {
        return new AuditedAgentInvocations() {
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
        };
    }

    private static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError(timeoutMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
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
                term + ": Visible " + term + " rule.",
                List.of(term),
                List.of(new VisualAnchor("diagram", term, "Visible " + term + " diagram.", 10, 10, 100, 100)),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(term),
                true,
                List.of(new RuleGroupFact(term, term, "Visible " + term + " rule.")));
    }

    private static PageSummary teachingSummary(
            int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
        List<String> ruleGroups = keywords.stream().limit(16).toList();
        List<RuleGroupFact> ruleGroupFacts = ruleGroups.stream()
                .map(identifier -> new RuleGroupFact(identifier, identifier, factualSummary))
                .toList();
        return new PageSummary(
                pageNumber,
                printedTerms,
                factualSummary,
                keywords,
                List.of(),
                List.of(),
                ruleGroups,
                true,
                List.of(),
                ruleGroupFacts);
    }

    private static TeachingCatalogContractViolation rejectedPageCandidate(int pageNumber, String candidate) {
        var rejection = new TeachingCatalogRejection(
                "{\"candidate\":\"" + candidate + "\",\"pageNumber\":" + pageNumber + "}",
                "ruleGroups item must contain fact",
                "visual-page-teaching-catalog-v6",
                Set.of(pageNumber));
        return new TeachingCatalogContractViolation(
                TeachingCatalogRepairCode.SCHEMA_MISMATCH,
                rejection,
                new IllegalArgumentException(rejection.validationError()));
    }

    private static final class InMemoryFacts implements VisualRulebookPageFacts {

        private final Map<UUID, List<PageFact>> factsByVersion = new HashMap<>();
        private final AtomicInteger mergeCalls = new AtomicInteger();
        private final CountDownLatch distinctReaderLatch;
        private final Set<Thread> distinctReaders = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private InMemoryFacts() {
            this(null);
        }

        private InMemoryFacts(CountDownLatch distinctReaderLatch) {
            this.distinctReaderLatch = distinctReaderLatch;
        }

        @Override
        public synchronized void replace(UUID documentVersionId, List<PageFact> pages) {
            factsByVersion.put(documentVersionId, List.copyOf(pages));
        }

        @Override
        public synchronized void merge(UUID documentVersionId, List<PageFact> pages) {
            mergeCalls.incrementAndGet();
            Map<Integer, PageFact> byPage = new HashMap<>();
            factsByVersion.getOrDefault(documentVersionId, List.of())
                    .forEach(fact -> byPage.put(fact.pageNumber(), fact));
            pages.forEach(fact -> byPage.put(fact.pageNumber(), fact));
            factsByVersion.put(
                    documentVersionId,
                    byPage.values().stream().sorted(java.util.Comparator.comparingInt(PageFact::pageNumber)).toList());
        }

        private int mergeCalls() {
            return mergeCalls.get();
        }

        @Override
        public synchronized List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
            if (distinctReaderLatch != null && distinctReaders.add(Thread.currentThread())) {
                distinctReaderLatch.countDown();
            }
            return factsByVersion.getOrDefault(documentVersionId, List.of()).stream()
                    .filter(fact -> pageNumbers.contains(fact.pageNumber()))
                    .toList();
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            current.updateAndGet(now -> now.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) throw new UnsupportedOperationException("test clock uses UTC");
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
