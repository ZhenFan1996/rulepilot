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
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ModelExecutionIdentity;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageTranscript;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogerTest {

    @Test
    void awaitCatalogPropagatesTheOriginalRuntimeFailureForTypedClassification() {
        TeachingCatalogContractViolation violation =
                new TeachingCatalogContractViolation(TeachingCatalogRepairCode.DUPLICATE_RULE_GROUP);
        Future<CatalogDraft> failed = java.util.concurrent.CompletableFuture.failedFuture(violation);

        assertThatThrownBy(() -> VisualRulebookCataloger.awaitCatalog(failed, Duration.ofSeconds(1)))
                .isSameAs(violation);
    }

    @Test
    void progressiveStartReadsEightPagesAsFivePlusThreeAndPersistsOnlyTheSelectedEarlyJourneyPage() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        List<List<Integer>> storageReads = new java.util.ArrayList<>();
        List<String> operations = new java.util.ArrayList<>();
        List<String> summaries = new java.util.ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("progressive startup must not run the complete visual catalog");
            }

            @Override
            public boolean supportsProgressiveTeachingStart(String owner) {
                return true;
            }

            @Override
            public Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
                modelCalls.incrementAndGet();
                assertThat(request.pages()).extracting(image -> image.pageNumber())
                        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
                return Optional.of(progressiveStart(2));
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
                operations.add(operation);
                summaries.add(successSummary);
                return invocation.get();
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> {
                    storageReads.add(List.copyOf(pages));
                    return pages.stream()
                            .map(page -> new DocumentPageImages.PageImage(
                                    page, "image/png", new byte[] {(byte) (int) page}, 100, 120))
                            .toList();
                },
                model,
                facts,
                audit,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4,
                1);

        var start = cataloger.progressiveTeachingStart(
                documentVersionId,
                java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                UUID.randomUUID());

        assertThat(start).isPresent();
        assertThat(storageReads).containsExactly(List.of(1, 2, 3, 4, 5), List.of(6, 7, 8));
        assertThat(modelCalls).hasValue(1);
        assertThat(operations).containsExactly("selectProgressiveTeachingStart");
        assertThat(summaries).containsExactly("First cited-page candidate selected via qwen/qwen3.6-flash");
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4, 5, 6, 7, 8)))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.pageNumber()).isEqualTo(2);
                    assertThat(fact.factualSummary()).contains("摆好市场");
                    assertThat(fact.iconInventoryComplete()).isFalse();
                });
    }

    @Test
    void keepsAUsableProgressiveStartAndPersistsItsFactAsIncompleteWithoutPromotingIt() {
        UUID documentVersionId = UUID.randomUUID();
        InMemoryFacts facts = new InMemoryFacts();
        List<String> rejectedOperations = new java.util.ArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("fallback belongs to the existing complete preparation path");
            }

            @Override
            public boolean supportsProgressiveTeachingStart(String owner) {
                return true;
            }

            @Override
            public Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
                return Optional.of(new ProgressiveTeachingStartDraft(
                        List.of(
                                new TeachingPageSketch(
                                        1, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("market"), List.of("setup")),
                                new TeachingPageSketch(
                                        2, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take"), List.of("core_loop"))),
                        new PageSummary(2, "take", "当前玩家必须执行一个可见动作。", List.of("take", "turn"))));
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
                return invocation.get();
            }

            @Override
            public void record(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                    String summary) {
                rejectedOperations.add(operation + ":" + outcome);
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
                Duration.ofSeconds(2),
                4,
                1);

        var result = cataloger.progressiveTeachingStart(
                documentVersionId,
                List.of(page(1), page(2)),
                "Example game",
                "owner",
                UUID.randomUUID());

        assertThat(result).isPresent();
        assertThat(facts.find(documentVersionId, Set.of(1, 2)))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.pageNumber()).isEqualTo(2);
                    assertThat(fact.factualSummary()).isEqualTo("当前玩家必须执行一个可见动作。");
                    assertThat(fact.ruleGroupInventoryComplete()).isFalse();
                });
        assertThat(rejectedOperations).isEmpty();
    }

    @Test
    void timesOutProgressiveStartSettlesItsRunningActivityAndInterruptsItsWorker() throws InterruptedException {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        List<String> stoppedOperations = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("fallback belongs to the existing complete preparation path");
            }

            @Override
            public boolean supportsProgressiveTeachingStart(String owner) {
                return true;
            }

            @Override
            public Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
                workerStarted.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException interrupted) {
                    workerInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("provider request interrupted", interrupted);
                }
                return Optional.of(progressiveStart(4));
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
                return invocation.get();
            }

            @Override
            public void stopRunning(
                    UUID runId,
                    String operation,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome outcome,
                    String summary) {
                stoppedOperations.add(operation + ":" + outcome);
            }
        };
        VisualRulebookCataloger cataloger = new VisualRulebookCataloger(
                (id, pages) -> pages.stream()
                        .map(page -> new DocumentPageImages.PageImage(page, "image/png", new byte[] {1}, 100, 120))
                        .toList(),
                model,
                new InMemoryFacts(),
                audit,
                Duration.ofSeconds(2),
                Duration.ofMillis(40),
                4,
                1);

        var result = cataloger.progressiveTeachingStart(
                documentVersionId,
                java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isEmpty();
        assertThat(stoppedOperations).containsExactly("selectProgressiveTeachingStart:FAILED");
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
    void doesNotReplayADeterministicRejectedTeachingPageAfterTheInitialWindowSettles() {
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
                            .map(page -> teachingSummary(
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

        assertThat(calls).hasValue(3);
        assertThat(inputs.getFirst().text()).contains("visual interpretation did not finish");
        assertThat(inputs.subList(1, 3))
                .allSatisfy(input -> assertThat(input.text()).contains("Visible rule on page"));
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3)))
                .extracting(PageFact::pageNumber)
                .containsExactly(2, 3);
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
                Duration.ofSeconds(2),
                4,
                1);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2)),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(failedPageCalls).hasValue(1);
        assertThat(operations).containsExactly(
                "inspectTeachingVisualPage|1|2",
                "inspectTeachingVisualPage|2|2");
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
                Duration.ofSeconds(2),
                4,
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
                },
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4,
                1);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                assistantRunId);

        assertThat(operations).containsExactly(
                "inspectTeachingVisualPage|2|3",
                "inspectTeachingVisualPage|3|3");
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
                Duration.ofSeconds(2),
                4,
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
                },
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4,
                1);

        assertThatThrownBy(() -> cataloger.catalogVisualPages(
                        documentVersionId,
                        List.of(page(1)),
                        "Example game",
                        "owner",
                        assistantRunId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("page fact store unavailable");
        assertThat(modelCalls).hasValue(1);
        assertThat(operations).containsExactly("inspectTeachingVisualPage|1|1");
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
                            false,
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
        IconOccurrence priorIcon = new IconOccurrence(
                "resource",
                "Resource",
                "A previously verified circular resource mark.",
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                80,
                90,
                40,
                40);
        facts.merge(documentVersionId, List.of(new PageFact(
                1,
                "OLD PARTIAL",
                "An earlier full-page observation admitted that it was partial.",
                List.of("old"),
                List.of(priorAnchor),
                List.of(priorIcon),
                true,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(new SourceDependency("Obsolete leaflet", List.of("setup"))),
                List.of("OLD PARTIAL"),
                false)));
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
                            List.of(),
                            false,
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
            assertThat(fact.iconOccurrences()).containsExactly(priorIcon);
            assertThat(fact.iconInventoryComplete()).isTrue();
            assertThat(fact.sourceDependencies())
                    .containsExactly(new SourceDependency("First Session Guide", List.of("setup")));
            assertThat(fact.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
            assertThat(fact.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void keepsAnExplicitlyPartialInventoryAvailableToTheOutlineAgent() {
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
                            "MOVE",
                            "Only one visible relation was returned.",
                            List.of("MOVE"),
                            List.of(),
                            List.of(),
                            false,
                            List.of(),
                            List.of("MOVE"),
                            false,
                            List.of(),
                            List.of(new RuleGroupFact(
                                    "MOVE", "MOVE", "Only one visible relation was returned.")))));
                },
                facts);

        List<PageInput> inputs = cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", null);

        assertThat(calls).hasValue(1);
        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.sourceRuleGroupIdentifiers()).containsExactly("MOVE");
            assertThat(input.sourceRuleGroupInventoryComplete()).isFalse();
            assertThat(input.sourceRuleGroupFacts())
                    .extracting(RuleGroupFact::identifier)
                    .containsExactly("MOVE");
            assertThat(input.text()).contains("Only one visible relation was returned.");
        });
        assertThat(facts.find(documentVersionId, Set.of(1))).singleElement().satisfies(fact -> {
            assertThat(fact.ruleGroupIdentifiers()).containsExactly("MOVE");
            assertThat(fact.ruleGroupInventoryComplete()).isFalse();
            assertThat(fact.ruleGroupFacts())
                    .extracting(RuleGroupFact::identifier)
                    .containsExactly("MOVE");
        });
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
    void aTimedOutProviderBatchDoesNotConsumeTheTimeoutOfLaterBatches() {
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
                .extracting(PageFact::pageNumber)
                .containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(inputs).hasSize(10);
        assertThat(inputs.getFirst().text()).contains("visual interpretation did not finish");
        assertThat(requestedBatches)
                .containsExactly(
                        List.of(1),
                        List.of(2),
                        List.of(3),
                        List.of(4),
                        List.of(5),
                        List.of(6),
                        List.of(7),
                        List.of(8),
                        List.of(9),
                        List.of(10));
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
                throw new AssertionError("teaching startup must not run the complete icon catalog");
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
        assertThat(facts.find(documentVersionId, Set.of(1, 2, 3, 4, 5, 6, 7, 8))).allSatisfy(fact -> {
            assertThat(fact.iconOccurrences()).isEmpty();
            assertThat(fact.iconInventoryComplete()).isFalse();
        });
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
                Duration.ofSeconds(2),
                4,
                1);

        cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(activitySummaries)
                .containsExactly("Teaching-start page facts interpreted via qwen/qwen3.6-flash");
    }

    @Test
    void transcribesPagesBeforeSemanticCatalogWithIndependentTenAndFourRequestLanes()
            throws InterruptedException {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger completedOcr = new AtomicInteger();
        AtomicInteger activeOcr = new AtomicInteger();
        AtomicInteger peakOcr = new AtomicInteger();
        AtomicInteger activeSemantic = new AtomicInteger();
        AtomicInteger peakSemantic = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean semanticStartedEarly = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch firstOcrWindow = new CountDownLatch(10);
        CountDownLatch firstSemanticWindow = new CountDownLatch(4);
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public boolean supportsTeachingPageTranscription(String owner) {
                return true;
            }

            @Override
            public PageTranscript transcribeTeachingPage(
                    com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                    String owner) {
                int active = activeOcr.incrementAndGet();
                peakOcr.accumulateAndGet(active, Math::max);
                try {
                    if (page.pageNumber() <= 10) {
                        firstOcrWindow.countDown();
                        assertThat(firstOcrWindow.await(2, TimeUnit.SECONDS)).isTrue();
                    }
                    return new PageTranscript(page.pageNumber(), "OCR page " + page.pageNumber());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    completedOcr.incrementAndGet();
                    activeOcr.decrementAndGet();
                }
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                if (completedOcr.get() != 12) semanticStartedEarly.set(true);
                int active = activeSemantic.incrementAndGet();
                peakSemantic.accumulateAndGet(active, Math::max);
                int pageNumber = request.pages().getFirst().pageNumber();
                try {
                    if (pageNumber <= 4) {
                        firstSemanticWindow.countDown();
                        assertThat(firstSemanticWindow.await(2, TimeUnit.SECONDS)).isTrue();
                    }
                    assertThat(request.transcripts()).singleElement().satisfies(transcript -> {
                        assertThat(transcript.pageNumber()).isEqualTo(pageNumber);
                        assertThat(transcript.text()).isEqualTo("OCR page " + pageNumber);
                    });
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
                Duration.ofSeconds(3),
                4,
                10,
                4,
                10);

        List<PageInput> result = cataloger.catalogVisualPages(
                documentVersionId,
                IntStream.rangeClosed(1, 12).mapToObj(VisualRulebookCatalogerTest::page).toList(),
                "Example game",
                "owner",
                null);

        assertThat(semanticStartedEarly).isFalse();
        assertThat(completedOcr).hasValue(12);
        assertThat(peakOcr).hasValue(10);
        assertThat(peakSemantic).hasValue(4);
        assertThat(result).hasSize(12);
    }

    @Test
    void keepsLargeTeachingImageReadsBoundedAndRereadsOnlyTheCurrentSemanticWindowAfterOcr() {
        UUID documentVersionId = UUID.randomUUID();
        List<List<Integer>> imageReads = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger completedOcr = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public boolean supportsTeachingPageTranscription(String owner) {
                return true;
            }

            @Override
            public PageTranscript transcribeTeachingPage(
                    com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                    String owner) {
                completedOcr.incrementAndGet();
                return new PageTranscript(page.pageNumber(), "literal page " + page.pageNumber());
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                assertThat(completedOcr).hasValue(18);
                int pageNumber = request.pages().getFirst().pageNumber();
                assertThat(request.transcripts()).singleElement().satisfies(transcript -> {
                    assertThat(transcript.pageNumber()).isEqualTo(pageNumber);
                    assertThat(transcript.text()).isEqualTo("literal page " + pageNumber);
                });
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
                Duration.ofSeconds(2),
                4,
                10,
                4,
                10);

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
                List.of(1, 2, 3, 4, 5),
                List.of(6, 7, 8, 9, 10),
                List.of(11, 12, 13, 14, 15),
                List.of(16, 17, 18),
                List.of(1, 2, 3, 4),
                List.of(5, 6, 7, 8),
                List.of(9, 10, 11, 12),
                List.of(13, 14, 15, 16),
                List.of(17, 18));
        Map<Integer, Long> readsPerPage = imageReads.stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.groupingBy(page -> page, java.util.stream.Collectors.counting()));
        assertThat(readsPerPage).hasSize(18).allSatisfy((page, reads) -> assertThat(reads).isEqualTo(2));
    }

    @Test
    void keepsSuccessfulPageBoundTranscriptsWhenOneOcrPageFailsAndFallsBackToItsOriginalImage() {
        UUID documentVersionId = UUID.randomUUID();
        List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
        Map<Integer, List<PageTranscript>> semanticTranscripts = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> ocrCalls = new java.util.concurrent.ConcurrentHashMap<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public boolean supportsTeachingPageTranscription(String owner) {
                return true;
            }

            @Override
            public PageTranscript transcribeTeachingPage(
                    com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                    String owner) {
                ocrCalls.computeIfAbsent(page.pageNumber(), ignored -> new AtomicInteger()).incrementAndGet();
                if (page.pageNumber() == 2) return new PageTranscript(1, "misbound transcript");
                return new PageTranscript(page.pageNumber(), "literal page " + page.pageNumber());
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                int pageNumber = request.pages().getFirst().pageNumber();
                semanticTranscripts.put(pageNumber, request.transcripts());
                return new CatalogDraft(List.of(teachingSummary(
                        pageNumber,
                        "PAGE " + pageNumber,
                        "Visible rule on page " + pageNumber,
                        List.of("page " + pageNumber))));
            }

            @Override
            public Optional<ModelExecutionIdentity> teachingPageTranscriptionExecutionIdentity(String owner) {
                return Optional.of(new ModelExecutionIdentity("qwen", "qwen3.5-ocr"));
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
                Duration.ofSeconds(2),
                4,
                3,
                3,
                3);

        cataloger.catalogVisualPages(
                documentVersionId,
                List.of(page(1), page(2), page(3)),
                "Example game",
                "owner",
                UUID.randomUUID());

        assertThat(ocrCalls).hasSize(3).allSatisfy((page, calls) -> assertThat(calls).hasValue(1));
        assertThat(semanticTranscripts.get(1)).singleElement().satisfies(transcript -> {
            assertThat(transcript.pageNumber()).isEqualTo(1);
            assertThat(transcript.text()).isEqualTo("literal page 1");
        });
        assertThat(semanticTranscripts.get(2)).isEmpty();
        assertThat(semanticTranscripts.get(3)).singleElement().satisfies(transcript -> {
            assertThat(transcript.pageNumber()).isEqualTo(3);
            assertThat(transcript.text()).isEqualTo("literal page 3");
        });
        assertThat(operations).contains(
                "transcribeTeachingVisualPage|1|3",
                "transcribeTeachingVisualPage|2|3",
                "transcribeTeachingVisualPage|3|3",
                "inspectTeachingVisualPage|1|3",
                "inspectTeachingVisualPage|2|3",
                "inspectTeachingVisualPage|3|3");
    }

    @Test
    void semanticRecoveryReusesTheFirstTranscriptWithoutCallingOcrAgain() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger imageReads = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        List<PageTranscript> suppliedTranscripts = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public boolean supportsTeachingPageTranscription(String owner) {
                return true;
            }

            @Override
            public PageTranscript transcribeTeachingPage(
                    com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                    String owner) {
                ocrCalls.incrementAndGet();
                return new PageTranscript(page.pageNumber(), "stable literal transcript");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                suppliedTranscripts.add(request.transcripts().getFirst());
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
                Duration.ofSeconds(2),
                4,
                10,
                4,
                10);

        cataloger.catalogVisualPages(
                documentVersionId, List.of(page(1)), "Example game", "owner", UUID.randomUUID());

        assertThat(imageReads).hasValue(3);
        assertThat(ocrCalls).hasValue(1);
        assertThat(semanticCalls).hasValue(2);
        assertThat(operations).containsExactly(
                "transcribeTeachingVisualPage|1|1",
                "inspectTeachingVisualPage|1|1",
                "inspectTeachingVisualRetry|1|1");
        assertThat(suppliedTranscripts).hasSize(2).allSatisfy(transcript -> {
            assertThat(transcript.pageNumber()).isEqualTo(1);
            assertThat(transcript.text()).isEqualTo("stable literal transcript");
        });
    }

    @Test
    void repairsEachTypedSemanticViolationOnceWithItsCodeAndTheOriginalOcrTranscript() {
        for (TeachingCatalogRepairCode repairCode : TeachingCatalogRepairCode.values()) {
            UUID documentVersionId = UUID.randomUUID();
            AtomicInteger imageReads = new AtomicInteger();
            AtomicInteger ocrCalls = new AtomicInteger();
            AtomicInteger initialCalls = new AtomicInteger();
            AtomicInteger repairCalls = new AtomicInteger();
            List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
            VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
                @Override
                public CatalogDraft summarize(CatalogRequest request) {
                    throw new AssertionError("teaching startup must use the teaching catalog");
                }

                @Override
                public boolean supportsTeachingPageTranscription(String owner) {
                    return true;
                }

                @Override
                public PageTranscript transcribeTeachingPage(
                        com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                        String owner) {
                    ocrCalls.incrementAndGet();
                    return new PageTranscript(page.pageNumber(), "stable literal transcript");
                }

                @Override
                public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                    initialCalls.incrementAndGet();
                    assertThat(request.transcripts()).singleElement().satisfies(transcript ->
                            assertThat(transcript.text()).isEqualTo("stable literal transcript"));
                    throw new TeachingCatalogContractViolation(repairCode);
                }

                @Override
                public CatalogDraft repairTeachingCatalog(
                        CatalogRequest request, TeachingCatalogRepairCode requestedCode) {
                    repairCalls.incrementAndGet();
                    assertThat(requestedCode).isEqualTo(repairCode);
                    assertThat(request.transcripts()).singleElement().satisfies(transcript ->
                            assertThat(transcript.text()).isEqualTo("stable literal transcript"));
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
                    Duration.ofSeconds(2),
                    4,
                    10,
                    4,
                    10);

            List<PageInput> result = cataloger.catalogVisualPages(
                    documentVersionId,
                    List.of(page(1)),
                    "Example game",
                    "owner",
                    UUID.randomUUID());

            assertThat(imageReads).hasValue(3);
            assertThat(ocrCalls).hasValue(1);
            assertThat(initialCalls).hasValue(1);
            assertThat(repairCalls).hasValue(1);
            assertThat(operations).containsExactly(
                    "transcribeTeachingVisualPage|1|1",
                    "inspectTeachingVisualPage|1|1",
                    "inspectTeachingVisualRepair|1|1|" + repairCode);
            assertThat(result).singleElement().satisfies(input ->
                    assertThat(input.text()).contains("The active player takes one action."));
        }
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
                throw new TeachingCatalogContractViolation(TeachingCatalogRepairCode.SCHEMA_MISMATCH);
            }

            @Override
            public CatalogDraft repairTeachingCatalog(
                    CatalogRequest request, TeachingCatalogRepairCode repairCode) {
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
    void stoppedOcrBudgetPropagatesWithoutProviderOrSemanticRetry() {
        UUID documentVersionId = UUID.randomUUID();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        VisualRulebookPageCatalogModel model = new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new AssertionError("teaching startup must use the teaching catalog");
            }

            @Override
            public boolean supportsTeachingPageTranscription(String owner) {
                return true;
            }

            @Override
            public PageTranscript transcribeTeachingPage(
                    com.rulepilot.teaching.TeachingOutlineModel.PageImageInput page,
                    String owner) {
                providerCalls.incrementAndGet();
                return new PageTranscript(page.pageNumber(), "must not be called");
            }

            @Override
            public CatalogDraft summarizeForTeaching(CatalogRequest request) {
                semanticCalls.incrementAndGet();
                throw new AssertionError("semantic catalog must not start after a stopped OCR budget");
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
                Duration.ofSeconds(2),
                4,
                10,
                4,
                10);

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
        assertThat(providerCalls).hasValue(0);
        assertThat(semanticCalls).hasValue(0);
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
                1,
                "SETUP",
                "SETUP: Visible setup instruction.",
                List.of("setup"),
                List.of(),
                List.of(),
                false,
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
                timeout,
                4,
                visualRequestParallelism);
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

    private static PageView page(int pageNumber) {
        return new PageView(pageNumber, "", 0);
    }

    private static ProgressiveTeachingStartDraft progressiveStart(int selectedPage) {
        PageSummary selectedFacts = switch (selectedPage) {
            case 2 -> progressiveSelectedSummary(
                    2,
                    "SETUP MARKET",
                    "开始第一回合前，按照页面所示位置摆好市场。",
                    List.of("SETUP MARKET", "setup"),
                    "market");
            case 3 -> progressiveSelectedSummary(
                    3,
                    "TAKE CARDS",
                    "当前玩家从可用区域选择并拿取卡牌。",
                    List.of("TAKE CARDS", "turn"),
                    "take cards");
            default -> progressiveSelectedSummary(
                    selectedPage,
                    "REFILL MARKET",
                    "回合结束后，当前玩家按照页面所示顺序补满市场。",
                    List.of("REFILL MARKET", "refill"),
                    "refill");
        };
        return new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(1, TeachingPageRole.NON_GAMEPLAY, "Example game", List.of(), List.of()),
                        new TeachingPageSketch(2, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("market"), List.of("setup")),
                        new TeachingPageSketch(3, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take cards"), List.of("core_loop")),
                        new TeachingPageSketch(4, TeachingPageRole.GAMEPLAY_RULES, "Refill", List.of("refill"), List.of("source_coverage")),
                        new TeachingPageSketch(5, TeachingPageRole.UNCERTAIN, "", List.of(), List.of()),
                        new TeachingPageSketch(6, TeachingPageRole.GAMEPLAY_RULES, "Game end", List.of("end"), List.of("end")),
                        new TeachingPageSketch(7, TeachingPageRole.GAMEPLAY_RULES, "Scoring", List.of("score"), List.of("scoring")),
                        new TeachingPageSketch(8, TeachingPageRole.NON_GAMEPLAY, "Credits", List.of(), List.of())),
                selectedFacts);
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
                List.of(),
                false,
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
                false,
                List.of(),
                ruleGroups,
                true,
                List.of(),
                ruleGroupFacts);
    }

    private static PageSummary progressiveSelectedSummary(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            String ruleGroupIdentifier) {
        return new PageSummary(
                pageNumber,
                printedTerms,
                ruleGroupIdentifier + ": " + factualSummary,
                keywords,
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(ruleGroupIdentifier),
                true,
                List.of(),
                List.of(new RuleGroupFact(ruleGroupIdentifier, ruleGroupIdentifier, factualSummary)));
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
