package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.BatchAction;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.VisualRegionProposer.Proposal;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonStepLocatorBatchingTest {

    @Test
    void offersPixelToolGeometryAsAnOpaqueCandidateBeforeTheVisionModelSelectsIt() {
        UUID evidence = UUID.randomUUID();
        Rectangle tightDiagram = new Rectangle(210, 280, 260, 180);
        java.util.concurrent.atomic.AtomicInteger proposalCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger pageReads = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            pageReads.incrementAndGet();
            return pages.stream().map(this::page).toList();
        };
        VisualRegionProposer proposer = (page, timeout) -> {
            proposalCalls.incrementAndGet();
            return VisualRegionProposer.ProposalResult.found(List.of(new Proposal(tightDiagram)));
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                assertThat(request.candidates().getFirst().rectangle()).isEqualTo(tightDiagram);
                return acceptedFirst(request, evidence, BatchAction.STOP);
            }
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(2)),
                List.of(step(evidence, List.of(2))),
                "owner");

        assertThat(proposalCalls).hasValue(1);
        assertThat(pageReads).hasValue(2);
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.x()).isEqualTo(tightDiagram.x());
            assertThat(region.y()).isEqualTo(tightDiagram.y());
            assertThat(region.width()).isEqualTo(tightDiagram.width());
            assertThat(region.height()).isEqualTo(tightDiagram.height());
        });
    }

    @Test
    void consecutivePixelToolTimeoutsOpenTheBreakerOnlyForTheCurrentWorkflow() {
        UUID evidence = UUID.randomUUID();
        List<Integer> proposedPages = new ArrayList<>();
        DocumentPageImages images = (ignored, pages) -> pages.stream().map(this::page).toList();
        VisualRegionProposer proposer = (page, timeout) -> {
            proposedPages.add(page.pageNumber());
            return VisualRegionProposer.ProposalResult.timeout();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                return acceptedFirst(request, evidence, BatchAction.STOP);
            }
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(1));

        var first = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1, 2, 3, 4, 5, 6, 7)),
                List.of(step(evidence, List.of(1, 2, 3, 4, 5, 6, 7))),
                "owner");
        var laterWorkflow = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(8)),
                List.of(step(evidence, List.of(8))),
                "owner");

        assertThat(proposedPages).containsExactly(1, 2, 8);
        assertThat(first.regions()).singleElement();
        assertThat(laterWorkflow.regions()).singleElement();
        assertThat(first.rejection()).isNull();
        assertThat(laterWorkflow.rejection()).isNull();
    }

    @Test
    void oneRuntimeFailureAndPageLocalOutcomesDoNotOpenTheBreaker() {
        UUID evidence = UUID.randomUUID();
        List<Integer> proposedPages = new ArrayList<>();
        DocumentPageImages images = (ignored, pages) -> pages.stream().map(this::page).toList();
        VisualRegionProposer proposer = (page, timeout) -> {
            proposedPages.add(page.pageNumber());
            if (page.pageNumber() == 1) return VisualRegionProposer.ProposalResult.unavailable();
            if (page.pageNumber() == 2) return VisualRegionProposer.ProposalResult.failed();
            return VisualRegionProposer.ProposalResult.none();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                return acceptedFirst(request, evidence, BatchAction.STOP);
            }
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1, 2, 3, 4, 5)),
                List.of(step(evidence, List.of(1, 2, 3, 4, 5))),
                "owner");

        assertThat(proposedPages).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.regions()).singleElement();
        assertThat(result.rejection()).isNull();
    }

    @Test
    void allContinueRereadsOnlyCurrentPageWindowAndKeepsEveryAgentAcceptedVisual() {
        List<Set<Integer>> pageReads = new ArrayList<>();
        List<Set<Integer>> requestPages = new ArrayList<>();
        List<Integer> candidateBatchSizes = new ArrayList<>();
        List<Integer> batchNumbers = new ArrayList<>();
        List<Boolean> hasMoreValues = new ArrayList<>();
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = List.of(1, 2, 3, 4, 5, 6, 7);
        DocumentPageImages images = (ignored, pages) -> {
            pageReads.add(Set.copyOf(pages));
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                candidateBatchSizes.add(request.candidates().size());
                batchNumbers.add(request.batchNumber());
                hasMoreValues.add(request.hasMoreCandidates());
                requestPages.add(request.pages().stream()
                        .map(VisualRegionLocator.PageImage::pageNumber)
                        .collect(java.util.stream.Collectors.toSet()));
                List<LocatedRegion> accepted = request.candidates().stream()
                        .map(candidate -> new LocatedRegion(
                                candidate.pageNumber(),
                                "规则图示 " + candidate.candidateId(),
                                "候选区域中可见一组桌面组件与相邻的流程箭头",
                                candidate.rectangle().x(),
                                candidate.rectangle().y(),
                                candidate.rectangle().width(),
                                candidate.rectangle().height(),
                                List.of(evidence),
                                List.of(1),
                                false,
                                candidate.sourceKind()))
                        .toList();
                return LocateGuideResult.found(
                        accepted, request.hasMoreCandidates() ? BatchAction.CONTINUE : BatchAction.STOP);
            }
        };

        var result = stepLocator(images, visual).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(pageReads).hasSize(3);
        assertThat(pageReads).allSatisfy(read ->
                assertThat(read).hasSizeLessThanOrEqualTo(DocumentPageImages.MAX_PAGES_PER_READ));
        assertThat(pageReads.stream().filter(read -> read.contains(1))).hasSizeGreaterThan(1);
        assertThat(requestPages).hasSize(3).allSatisfy(pages -> assertThat(pages).hasSizeLessThanOrEqualTo(7));
        assertThat(candidateBatchSizes).containsExactly(12, 8, 8);
        assertThat(batchNumbers).containsExactly(1, 2, 3);
        assertThat(hasMoreValues).containsExactly(true, true, false);
        assertThat(result.regions()).hasSize(28);
        assertThat(result.regions()).extracting(VisualRegionLocator.LocatedRegion::pageNumber)
                .contains(1, 2, 3, 4, 5, 6, 7);
        assertThat(result.rejection()).isNull();
    }

    @Test
    void allContinueCannotExceedTheAdmissionVisibleSectionBatchCeiling() {
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = java.util.stream.IntStream.rangeClosed(1, 13).boxed().toList();
        List<Set<Integer>> pageReads = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            pageReads.add(Set.copyOf(pages));
            return pages.stream().map(this::page).toList();
        };

        var result = stepLocator(images, continuingFirstCandidate(evidence, calls)).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(calls).hasValue(VisualLessonStepLocator.MAX_CANDIDATE_BATCHES_PER_SECTION);
        assertThat(pageReads).hasSize(VisualLessonStepLocator.MAX_CANDIDATE_BATCHES_PER_SECTION)
                .allSatisfy(read -> assertThat(read).allMatch(page -> page <= 10));
        assertThat(result.regions())
                .hasSize(VisualLessonStepLocator.MAX_CANDIDATE_BATCHES_PER_SECTION);
    }

    @Test
    void aLaterBatchFailureDoesNotEraseAlreadyValidatedVisuals() {
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = List.of(1, 2, 3, 4);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> pages.stream().map(this::page).toList();
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                if (calls.incrementAndGet() > 1) {
                    return LocateGuideResult.unavailable(Diagnostic.TIMEOUT);
                }
                var candidate = request.candidates().getFirst();
                return LocateGuideResult.found(List.of(new LocatedRegion(
                        candidate.pageNumber(),
                        "桌面组件布局",
                        "候选区域中可见棋盘、资源方块与一组相邻卡牌",
                        candidate.rectangle().x(),
                        candidate.rectangle().y(),
                        candidate.rectangle().width(),
                        candidate.rectangle().height(),
                        List.of(evidence),
                        List.of(1),
                        false,
                        candidate.sourceKind())), BatchAction.CONTINUE);
            }
        };

        var result = stepLocator(images, visual).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(calls).hasValue(2);
        assertThat(result.regions()).singleElement().satisfies(region ->
                assertThat(region.label()).isEqualTo("桌面组件布局"));
        assertThat(result.rejection()).isNull();
    }

    @Test
    void typedStopPreventsAnUnneededSecondModelBatch() {
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = List.of(1, 2, 3, 4);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> pages.stream().map(this::page).toList();
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                calls.incrementAndGet();
                var candidate = request.candidates().getFirst();
                return LocateGuideResult.found(List.of(new LocatedRegion(
                        candidate.pageNumber(),
                        "桌面组件布局",
                        "候选区域中可见棋盘、资源方块与一组相邻卡牌",
                        candidate.rectangle().x(),
                        candidate.rectangle().y(),
                        candidate.rectangle().width(),
                        candidate.rectangle().height(),
                        List.of(evidence),
                        List.of(1),
                        false,
                        candidate.sourceKind())), BatchAction.STOP);
            }
        };

        var result = stepLocator(images, visual).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(calls).hasValue(1);
        assertThat(result.regions()).hasSize(1);
    }

    @Test
    void typedStopDoesNotPreloadPagesThatBelongOnlyToLaterCandidateBatches() {
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList();
        List<Set<Integer>> pageReads = new ArrayList<>();
        List<Integer> proposedPages = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            pageReads.add(Set.copyOf(pages));
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                calls.incrementAndGet();
                var candidate = request.candidates().getFirst();
                return LocateGuideResult.found(List.of(new LocatedRegion(
                        candidate.pageNumber(),
                        "桌面组件布局",
                        "候选区域中可见棋盘、资源方块与一组相邻卡牌",
                        candidate.rectangle().x(),
                        candidate.rectangle().y(),
                        candidate.rectangle().width(),
                        candidate.rectangle().height(),
                        List.of(evidence),
                        List.of(1),
                        false,
                        candidate.sourceKind())), BatchAction.STOP);
            }
        };
        VisualRegionProposer proposer = (page, timeout) -> {
            proposedPages.add(page.pageNumber());
            return VisualRegionProposer.ProposalResult.none();
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(calls).hasValue(1);
        assertThat(result.regions()).hasSize(1);
        assertThat(pageReads).containsExactly(
                Set.of(1, 2, 3, 4, 5),
                Set.of(1, 2, 3, 4, 5));
        assertThat(proposedPages).containsExactly(1, 2, 3, 4, 5);
        assertThat(pageReads).allSatisfy(read ->
                assertThat(read).hasSizeLessThanOrEqualTo(DocumentPageImages.MAX_PAGES_PER_READ));
        assertThat(pageReads).allSatisfy(read -> assertThat(read).allMatch(page -> page <= 5));
    }

    @Test
    void aLaterPageReadFailurePreservesTheAlreadyValidatedPrefix() {
        UUID evidence = UUID.randomUUID();
        List<Integer> citedPages = List.of(1, 2, 3, 4);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            if (reads.incrementAndGet() > 1) throw new IllegalStateException("object storage unavailable");
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = continuingFirstCandidate(evidence, calls);

        var result = stepLocator(images, visual).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, citedPages),
                List.of(step(evidence, citedPages)),
                "owner");

        assertThat(reads).hasValue(2);
        assertThat(calls).hasValue(1);
        assertThat(result.regions()).singleElement();
        assertThat(result.rejection()).isNull();
    }

    @Test
    void anInitialPageReadFailureReturnsALocalVisualFailureWithoutCallingTheModel() {
        UUID evidence = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            throw new IllegalStateException("object storage unavailable");
        };
        VisualRegionLocator visual = continuingFirstCandidate(evidence, calls);

        var result = stepLocator(images, visual).locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1)),
                List.of(step(evidence, List.of(1))),
                "owner");

        assertThat(calls).hasValue(0);
        assertThat(result.regions()).isEmpty();
        assertThat(result.rejection()).isEqualTo(VisualLessonEnricher.Outcome.NO_PAGE_IMAGE);
    }

    @Test
    void cancellationBetweenBatchesStopsBeforeReadingOrCallingTheNextBatch() {
        UUID evidence = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        when(execution.budget(runId)).thenAnswer(ignored -> new AgentExecutionControl.BudgetSnapshot(
                40, 24, 192, 600_000, 0, calls.get(), 0, now.plusSeconds(60),
                cancelled.get() ? now : null));
        DocumentPageImages images = (ignored, pages) -> {
            reads.incrementAndGet();
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                calls.incrementAndGet();
                cancelled.set(true);
                return acceptedFirst(request, evidence, BatchAction.CONTINUE);
            }
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                visual,
                new VisualReaderCropPolicy(),
                execution,
                Clock.fixed(now, ZoneId.of("UTC")),
                Duration.ofMinutes(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1, 2, 3, 4)),
                List.of(step(evidence, List.of(1, 2, 3, 4))),
                "owner",
                runId);

        assertThat(calls).hasValue(1);
        assertThat(reads).hasValue(1);
        assertThat(result.regions()).singleElement();
    }

    @Test
    void exhaustedModelBudgetBetweenBatchesStopsBeforeReadingOrCallingTheNextBatch() {
        UUID evidence = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        when(execution.budget(runId)).thenAnswer(ignored -> new AgentExecutionControl.BudgetSnapshot(
                40, 24, 1, 600_000, 0, calls.get(), 0, now.plusSeconds(60), null));
        DocumentPageImages images = (ignored, pages) -> {
            reads.incrementAndGet();
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = continuingFirstCandidate(evidence, calls);
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                visual,
                new VisualReaderCropPolicy(),
                execution,
                Clock.fixed(now, ZoneId.of("UTC")),
                Duration.ofMinutes(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1, 2, 3, 4)),
                List.of(step(evidence, List.of(1, 2, 3, 4))),
                "owner",
                runId);

        assertThat(calls).hasValue(1);
        assertThat(reads).hasValue(1);
        assertThat(result.regions()).singleElement();
    }

    @Test
    void compatibilityCallsShareOneWallDeadlineAcrossAllContinueBatches() {
        UUID evidence = UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentPageImages images = (ignored, pages) -> {
            reads.incrementAndGet();
            return pages.stream().map(this::page).toList();
        };
        VisualRegionLocator visual = new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(
                    VisualLocationRequest request,
                    Duration remainingWorkflowTime) {
                calls.incrementAndGet();
                assertThat(remainingWorkflowTime).isLessThanOrEqualTo(Duration.ofSeconds(1));
                clock.advance(Duration.ofSeconds(2));
                return acceptedFirst(request, evidence, BatchAction.CONTINUE);
            }
        };
        var locator = new VisualLessonStepLocator(
                images,
                new VisualRegionCandidateSelector(),
                visual,
                new VisualReaderCropPolicy(),
                null,
                clock,
                Duration.ofSeconds(1));

        var result = locator.locate(
                understanding(),
                UUID.randomUUID(),
                section(evidence, List.of(1, 2, 3, 4)),
                List.of(step(evidence, List.of(1, 2, 3, 4))),
                "owner");

        assertThat(calls).hasValue(1);
        assertThat(reads).hasValue(1);
        assertThat(result.regions()).singleElement();
    }

    private VisualRegionLocator continuingFirstCandidate(
            UUID evidence,
            java.util.concurrent.atomic.AtomicInteger calls) {
        return new VisualRegionLocator() {
            @Override
            public java.util.Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
                calls.incrementAndGet();
                return acceptedFirst(request, evidence, BatchAction.CONTINUE);
            }
        };
    }

    private VisualRegionLocator.LocateGuideResult acceptedFirst(
            VisualRegionLocator.VisualLocationRequest request,
            UUID evidence,
            BatchAction action) {
        var candidate = request.candidates().getFirst();
        return VisualRegionLocator.LocateGuideResult.found(List.of(new VisualRegionLocator.LocatedRegion(
                candidate.pageNumber(),
                "桌面组件布局",
                "候选区域中可见棋盘、资源方块与一组相邻卡牌",
                candidate.rectangle().x(),
                candidate.rectangle().y(),
                candidate.rectangle().width(),
                candidate.rectangle().height(),
                List.of(evidence),
                List.of(1),
                false,
                candidate.sourceKind())), action);
    }

    private VisualLessonStepLocator stepLocator(DocumentPageImages images, VisualRegionLocator visual) {
        return new VisualLessonStepLocator(
                images, new VisualRegionCandidateSelector(), visual, new VisualReaderCropPolicy());
    }

    private RulebookUnderstanding understanding() {
        return new RulebookUnderstanding(List.of(), List.of(), List.of(), List.of());
    }

    private DocumentPageImages.PageImage page(int number) {
        return new DocumentPageImages.PageImage(number, "image/png", new byte[] {1}, 1_000, 1_000);
    }

    private IllustratedLesson.LessonStep step(UUID evidence, List<Integer> citedPages) {
        return new IllustratedLesson.LessonStep(
                1,
                "核对桌面组件",
                IllustratedLesson.TeachingMove.DO,
                "按引用规则核对桌面上的组件位置。",
                citedPages,
                List.of(evidence));
    }

    private IllustratedLesson.LessonSection section(UUID evidence, List<Integer> citedPages) {
        return new IllustratedLesson.LessonSection(
                1,
                "setup",
                List.of("setup"),
                "开局设置",
                true,
                IllustratedLesson.EvidenceStatus.SUPPORTED,
                IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "核对桌面组件",
                List.of(),
                List.of(),
                List.of(step(evidence, citedPages)));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
