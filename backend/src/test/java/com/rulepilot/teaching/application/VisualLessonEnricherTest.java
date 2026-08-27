package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VisualLessonEnricherTest {

    @Test
    void attaches_a_cited_crop_to_the_supported_rule_without_adding_a_seventh_step() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        RulebookUnderstandingCatalog catalog = ignored -> understanding();
        DocumentPageImages images = (ignored, pages) -> pages.contains(2)
                ? List.of(new DocumentPageImages.PageImage(2, "image/png", new byte[] {1}, 1_000, 1_000))
                : List.of();
        VisualRegionLocator locator = request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                2, "轨道", "一个探测器标记位于弧形刻度轨道上", 120, 220, 180, 120, List.of(chunk)));
        IllustratedLesson source = lesson(chunk);

        IllustratedLesson enriched = new VisualLessonEnricher(
                catalog, images, new VisualRegionCandidateSelector(), locator).enrich(version, source);

        var section = enriched.sections().getFirst();
        assertThat(section.steps()).hasSize(1);
        assertThat(section.steps().getFirst().text()).contains("把探测器放到轨道上。");
        assertThat(section.steps().getFirst().kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(section.steps().getFirst().visualFocus().pageNumber()).isEqualTo(2);
        assertThat(section.visualSourcePages()).containsExactly(2);
    }

    @Test
    void reports_each_visual_section_immediately_after_its_bounded_work_finishes() {
        UUID chunk = UUID.randomUUID();
        List<VisualLessonEnricher.SectionProgress> progress = new java.util.ArrayList<>();
        List<IllustratedLesson> published = new java.util.ArrayList<>();

        new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "轨道", "一枚探测器标记位于弧形刻度轨道上", 120, 220, 180, 120, List.of(chunk))))
                .enrichWithProgress(UUID.randomUUID(), lesson(chunk), "owner", new VisualLessonEnricher.VisualProgressListener() {
                    @Override
                    public void sectionFinished(VisualLessonEnricher.SectionProgress update) {
                        progress.add(update);
                    }

                    @Override
                    public void sectionUpdated(VisualLessonEnricher.SectionProgress update, IllustratedLesson lesson) {
                        published.add(lesson);
                    }
                });

        assertThat(progress).singleElement().satisfies(update -> {
            assertThat(update.sectionPosition()).isEqualTo(1);
            assertThat(update.sectionTitle()).isEqualTo("开局设置");
            assertThat(update.outcome().outcome()).isEqualTo(VisualLessonEnricher.Outcome.ADDED);
        });
        assertThat(published).singleElement().satisfies(lesson ->
                assertThat(lesson.sections().getFirst().steps().getFirst().kind())
                        .isEqualTo(IllustratedLesson.TeachingMove.VISUAL));
    }

    @Test
    void compatibilityEnrichmentSharesOneWallDeadlineAcrossAllSections() {
        UUID firstEvidence = UUID.randomUUID();
        UUID secondEvidence = UUID.randomUUID();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-26T00:00:00Z"));
        Clock clock = new Clock() {
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
                return now.get();
            }
        };
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        VisualRegionLocator visual = request -> {
            calls.incrementAndGet();
            now.set(now.get().plusSeconds(2));
            var candidate = request.candidates().getFirst();
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    candidate.pageNumber(),
                    "可核对图示",
                    "候选区域中可见组件与相邻的流程箭头",
                    candidate.rectangle().x(),
                    candidate.rectangle().y(),
                    candidate.rectangle().width(),
                    candidate.rectangle().height(),
                    List.of(request.claims().getFirst().evidenceId())));
        };
        var enricher = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> pages.stream().map(page -> new DocumentPageImages.PageImage(
                        page, "image/png", new byte[] {1}, 1_000, 1_000)).toList(),
                new VisualRegionCandidateSelector(),
                visual,
                new VisualSectionPrioritizer(),
                null,
                Duration.ofSeconds(1),
                clock);

        var result = enricher.enrichWithReport(
                UUID.randomUUID(),
                twoSectionsWithOverlappingVisuals(firstEvidence, secondEvidence),
                "owner");

        assertThat(calls).hasValue(1);
        assertThat(result.outcomes()).extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .containsExactly(
                        VisualLessonEnricher.Outcome.ADDED,
                        VisualLessonEnricher.Outcome.MODEL_TIMEOUT);
    }

    @Test
    void consecutiveProposalRuntimeFailuresAcrossSectionsOpenOnlyTheCurrentEnrichmentCircuit() {
        List<UUID> evidence = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        java.util.concurrent.atomic.AtomicInteger proposalCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger visualCalls = new java.util.concurrent.atomic.AtomicInteger();
        VisualRegionProposer proposer = (page, timeout) -> {
            proposalCalls.incrementAndGet();
            return VisualRegionProposer.ProposalResult.timeout();
        };
        VisualRegionLocator visual = request -> {
            visualCalls.incrementAndGet();
            var candidate = request.candidates().getFirst();
            var claim = request.claims().getFirst();
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    candidate.pageNumber(),
                    "可核对图示",
                    "候选区域中可见组件与相邻流程箭头",
                    candidate.rectangle().x(),
                    candidate.rectangle().y(),
                    candidate.rectangle().width(),
                    candidate.rectangle().height(),
                    List.of(claim.evidenceId()),
                    List.of(claim.stepPosition())));
        };
        var enricher = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> pages.stream().map(page -> new DocumentPageImages.PageImage(
                        page, "image/png", new byte[] {1}, 1_000, 1_000)).toList(),
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualSectionPrioritizer(),
                null,
                Duration.ofMinutes(1),
                Clock.systemUTC());

        var firstRun = enricher.enrichWithProgress(
                UUID.randomUUID(),
                multiSectionVisualLesson(evidence),
                "owner",
                UUID.randomUUID(),
                new VisualLessonEnricher.VisualProgressListener() {});
        var secondRun = enricher.enrichWithProgress(
                UUID.randomUUID(),
                multiSectionVisualLesson(evidence),
                "owner",
                UUID.randomUUID(),
                new VisualLessonEnricher.VisualProgressListener() {});

        assertThat(proposalCalls)
                .as("each enrichment stops the local proposer after two runtime failures, then the next run resets it")
                .hasValue(4);
        assertThat(visualCalls).hasValue(6);
        assertThat(firstRun.outcomes()).extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .containsExactly(
                        VisualLessonEnricher.Outcome.ADDED,
                        VisualLessonEnricher.Outcome.ADDED,
                        VisualLessonEnricher.Outcome.ADDED);
        assertThat(secondRun.outcomes()).extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .containsExactly(
                        VisualLessonEnricher.Outcome.ADDED,
                        VisualLessonEnricher.Outcome.ADDED,
                        VisualLessonEnricher.Outcome.ADDED);
    }

    @Test
    void pageLocalProposalFailureResetsRuntimeFailureStreakAcrossSections() {
        List<UUID> evidence = List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        java.util.concurrent.atomic.AtomicInteger proposalCalls = new java.util.concurrent.atomic.AtomicInteger();
        VisualRegionProposer proposer = (page, timeout) -> switch (proposalCalls.incrementAndGet()) {
            case 1, 3 -> VisualRegionProposer.ProposalResult.timeout();
            case 2 -> VisualRegionProposer.ProposalResult.failed();
            default -> VisualRegionProposer.ProposalResult.none();
        };
        VisualRegionLocator visual = request -> {
            var candidate = request.candidates().getFirst();
            var claim = request.claims().getFirst();
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    candidate.pageNumber(),
                    "可核对图示",
                    "候选区域中可见组件与相邻流程箭头",
                    candidate.rectangle().x(),
                    candidate.rectangle().y(),
                    candidate.rectangle().width(),
                    candidate.rectangle().height(),
                    List.of(claim.evidenceId()),
                    List.of(claim.stepPosition())));
        };
        var enricher = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> pages.stream().map(page -> new DocumentPageImages.PageImage(
                        page, "image/png", new byte[] {1}, 1_000, 1_000)).toList(),
                new VisualRegionCandidateSelector(),
                proposer,
                visual,
                new VisualSectionPrioritizer(),
                null,
                Duration.ofMinutes(1),
                Clock.systemUTC());

        var result = enricher.enrichWithProgress(
                UUID.randomUUID(),
                multiSectionVisualLesson(evidence),
                "owner",
                UUID.randomUUID(),
                new VisualLessonEnricher.VisualProgressListener() {});

        assertThat(proposalCalls)
                .as("a page-local input or geometry failure proves the process is runnable and breaks the streak")
                .hasValue(4);
        assertThat(result.outcomes()).extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .containsOnly(VisualLessonEnricher.Outcome.ADDED);
    }

    @Test
    void announces_the_exact_rule_step_before_the_visual_model_is_called() {
        UUID chunk = UUID.randomUUID();
        List<String> events = new java.util.ArrayList<>();

        new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.empty())
                .enrichWithProgress(UUID.randomUUID(), lesson(chunk), "owner", new VisualLessonEnricher.VisualProgressListener() {
                    @Override
                    public void targetStarted(VisualLessonEnricher.VisualTarget target) {
                        events.add("started:" + target.sectionTitle() + ":" + target.stepHeading());
                    }

                    @Override
                    public void targetFinished(VisualLessonEnricher.VisualTarget target, VisualLessonEnricher.Outcome outcome) {
                        events.add("finished:" + outcome);
                    }
                });

        assertThat(events).containsExactly("started:开局设置:放置探测器", "finished:LOCATOR_RETURNED_NONE");
    }

    @Test
    void plansIndependentVisualStepsInOneBoundedTypedRequest() {
        UUID iconEvidence = UUID.randomUUID();
        UUID stateEvidence = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        VisualRegionLocator locator = new VisualRegionLocator() {
            @Override
            public java.util.Optional<VisualRegionLocator.LocatedRegion> locate(
                    VisualRegionLocator.VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public VisualRegionLocator.LocateGuideResult locateGuideWithResult(
                    VisualRegionLocator.VisualLocationRequest request) {
                calls.incrementAndGet();
                assertThat(request.claims()).extracting(VisualRegionLocator.Claim::stepPosition)
                        .containsExactly(1, 2);
                assertThat(request.candidates())
                        .hasSizeLessThanOrEqualTo(VisualRegionLocator.VisualLocationRequest.MAX_CANDIDATES_PER_BATCH);
                assertThat(request.batchNumber()).isOne();
                assertThat(request.hasMoreCandidates()).isFalse();
                return VisualRegionLocator.LocateGuideResult.found(List.of(
                        new VisualRegionLocator.LocatedRegion(
                                2, "行动标记", "圆形行动标记旁有指向轨道的箭头", 120, 220, 180, 120,
                                List.of(iconEvidence), List.of(1)),
                        new VisualRegionLocator.LocatedRegion(
                                2, "轨道状态", "探测器位于轨道第三格", 420, 420, 180, 120,
                                List.of(stateEvidence), List.of(2))));
            }
        };
        var enricher = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                        2, "image/png", new byte[] {1}, 1_000, 1_000)),
                new VisualRegionCandidateSelector(),
                locator,
                new VisualSectionPrioritizer());

        var result = enricher.enrich(UUID.randomUUID(), twoRuleLesson(iconEvidence, stateEvidence));

        assertThat(calls).hasValue(1);
        assertThat(result.sections().getFirst().steps())
                .allSatisfy(step -> assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL));
    }

    @Test
    void turns_a_vision_observation_into_a_player_facing_crop_explanation() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "轨道",
                                "圆形标记位于一条弧形刻度旁，箭头指向前进方向",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(version, lesson(chunk));

        var step = enriched.sections().getFirst().steps().getFirst();
        assertThat(step.text()).isEqualTo("把探测器放到轨道上。");
        assertThat(step.visualFocus().visibleDescription())
                .isEqualTo("圆形标记位于一条弧形刻度旁，箭头指向前进方向");
    }

    @Test
    void keeps_a_models_existing_image_introducer_instead_of_repeating_it_for_players() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "轨道",
                                "图中展示一枚探测器标记位于弧形轨道旁",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        var step = enriched.sections().getFirst().steps().getFirst();
        assertThat(step.text()).isEqualTo("把探测器放到轨道上。");
        assertThat(step.visualFocus().visibleDescription()).isEqualTo("图中展示一枚探测器标记位于弧形轨道旁");
    }

    @Test
    void rejects_a_visual_response_that_only_crops_a_section_heading() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "Setup section header",
                                "The word 'Setup' printed in a large, bold serif font at the top left of the page.",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void rejects_a_crop_without_a_literal_visual_observation() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "轨道", 120, 220, 180, 120, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.REJECTED_MISSING_OBSERVATION);
    }

    @Test
    void rejects_a_visual_response_that_only_repeats_a_numbered_text_step() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "Setup step 7 text",
                                "Text for the 7th setup step listing the faction order.",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void acceptsACompactLocatorRegionWithoutReclassifyingItsDescriptionVocabulary() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "RESOURCE PLACEMENT 规则框",
                                "一个白色边框文本框，内含四条资源放置规则",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk),
                                List.of(1))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.lesson().sections().getFirst().steps().getFirst().kind())
                .isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(result.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.ADDED);
    }

    @Test
    void expandsSmallBoundedRegionsAndDoesNotSemanticallyReclassifyReturnedDescriptions() {
        UUID chunk = UUID.randomUUID();
        var tiny = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "首任大师标记", "一个印有文字的小标记", 38, 259, 76, 20, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");
        var paragraph = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "示例段落", "一段文字描述三名玩家的行动顺序", 120, 220, 180, 120, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(tiny.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.ADDED);
        assertThat(paragraph.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.ADDED);
        assertThat(tiny.lesson().sections().getFirst().steps().getFirst().visualFocus())
                .extracting(
                        IllustratedLesson.VisualFocus::width,
                        IllustratedLesson.VisualFocus::height)
                .containsExactly(180, 120);
    }

    @Test
    void expands_a_tight_visual_focus_into_a_player_readable_contextual_viewport() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "卡牌行动图标",
                                "卡牌中央的金色圆形图标紧挨着向右箭头",
                                300,
                                400,
                                34,
                                34,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps().getFirst().visualFocus())
                .isEqualTo(new IllustratedLesson.VisualFocus(
                        2, "卡牌行动图标", "卡牌中央的金色圆形图标紧挨着向右箭头", 227, 357, 180, 120));
    }

    @Test
    void turns_two_grounded_visual_anchors_into_two_different_rule_steps() {
        UUID iconEvidence = UUID.randomUUID();
        UUID stateEvidence = UUID.randomUUID();
        VisualRegionLocator locator = new VisualRegionLocator() {
            @Override
            public java.util.Optional<VisualRegionLocator.LocatedRegion> locate(
                    VisualRegionLocator.VisualLocationRequest ignored) {
                return java.util.Optional.empty();
            }

            @Override
            public VisualRegionLocator.LocateGuideResult locateGuideWithResult(
                    VisualRegionLocator.VisualLocationRequest ignored) {
                return VisualRegionLocator.LocateGuideResult.found(List.of(
                        new VisualRegionLocator.LocatedRegion(
                                2, "行动图标", "一个六点骰子图标紧挨着向右箭头", 120, 220, 34, 34,
                                List.of(iconEvidence), List.of(1)),
                        new VisualRegionLocator.LocatedRegion(
                                2, "棋子状态", "一枚探测器标记位于轨道的第三格", 440, 420, 180, 120,
                                List.of(stateEvidence), List.of(2))));
            }
        };
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrichWithReport(UUID.randomUUID(), twoRuleLesson(iconEvidence, stateEvidence), "owner");

        var steps = result.lesson().sections().getFirst().steps();
        assertThat(steps).extracting(IllustratedLesson.LessonStep::kind)
                .containsExactly(IllustratedLesson.TeachingMove.VISUAL, IllustratedLesson.TeachingMove.VISUAL);
        assertThat(steps.get(0).text()).isEqualTo("掷骰后执行行动。");
        assertThat(steps.get(0).visualFocus().visibleDescription()).contains("六点骰子图标", "向右箭头");
        assertThat(steps.get(1).text()).isEqualTo("把探测器移到轨道上。");
        assertThat(steps.get(1).visualFocus().visibleDescription()).contains("探测器标记", "第三格");
        assertThat(steps).extracting(step -> step.visualFocus().label()).containsExactly("行动图标", "棋子状态");
        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.outcome()).isEqualTo(VisualLessonEnricher.Outcome.ADDED);
            assertThat(outcome.summary()).contains("2 处");
        });
    }

    @Test
    void attempts_a_distinct_visual_for_every_published_rule_step_in_a_section() {
        UUID sharedEvidence = UUID.randomUUID();
        java.util.List<Integer> requestedSteps = new java.util.ArrayList<>();
        VisualRegionLocator locator = new VisualRegionLocator() {
            @Override
            public java.util.Optional<VisualRegionLocator.LocatedRegion> locate(
                    VisualRegionLocator.VisualLocationRequest request) {
                return java.util.Optional.empty();
            }

            @Override
            public VisualRegionLocator.LocateGuideResult locateGuideWithResult(
                    VisualRegionLocator.VisualLocationRequest request) {
                requestedSteps.addAll(request.claims().stream()
                        .map(VisualRegionLocator.Claim::stepPosition)
                        .toList());
                return VisualRegionLocator.LocateGuideResult.found(request.claims().stream()
                        .map(claim -> new VisualRegionLocator.LocatedRegion(
                                2,
                                "规则图例 " + claim.stepPosition(),
                                "一个彩色行动图标组，旁边有指向下一步的箭头",
                                100 + claim.stepPosition() * 120,
                                180,
                                100,
                                100,
                                List.of(sharedEvidence),
                                List.of(claim.stepPosition())))
                        .toList());
            }
        };

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrich(UUID.randomUUID(), sixRuleLesson(sharedEvidence));

        assertThat(requestedSteps).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(enriched.sections().getFirst().steps())
                .allSatisfy(step -> assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL));
    }

    @Test
    void attemptsOnlyTheExplicitVisualStepWhenThePublishedSectionDeclaresVisualIntent() {
        UUID sharedEvidence = UUID.randomUUID();
        java.util.List<Integer> requestedSteps = new java.util.ArrayList<>();
        VisualRegionLocator locator = request -> {
            int stepPosition = request.claims().getFirst().stepPosition();
            requestedSteps.add(stepPosition);
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    2,
                    "探测器轨道图",
                    "一枚探测器标记位于弧形刻度轨道上",
                    180,
                    220,
                    220,
                    160,
                    List.of(sharedEvidence),
                    List.of(stepPosition)));
        };

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrich(UUID.randomUUID(), lessonWithExplicitVisualIntent(sharedEvidence));

        assertThat(requestedSteps).containsExactly(2);
        assertThat(enriched.sections().getFirst().steps().get(0).kind())
                .isEqualTo(IllustratedLesson.TeachingMove.DO);
        assertThat(enriched.sections().getFirst().steps().get(1).kind())
                .isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(enriched.sections().getFirst().steps().get(1).visualFocus()).isNotNull();
    }

    @Test
    void binds_a_visual_crop_to_its_source_page_even_when_one_evidence_chunk_spans_two_steps() {
        UUID sharedEvidence = UUID.randomUUID();
        RulebookUnderstanding crossPageUnderstanding = new RulebookUnderstanding(
                List.of(block(2, "Game goal", 100, 200), block(3, "Artifact cards", 100, 200)),
                List.of(), List.of(), List.of());
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> crossPageUnderstanding,
                        (ignored, pages) -> List.of(
                                new DocumentPageImages.PageImage(2, "image/png", new byte[] {2}, 1_000, 1_000),
                                new DocumentPageImages.PageImage(3, "image/png", new byte[] {3}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                3,
                                "文物卡字段",
                                "两张卡牌上有编号圆圈，指向不同字段",
                                60,
                                40,
                                860,
                                520,
                                List.of(sharedEvidence),
                                List.of(2))))
                .enrich(UUID.randomUUID(), sameEvidenceAcrossPagesLesson(sharedEvidence));

        var steps = enriched.sections().getFirst().steps();
        assertThat(steps.get(0).kind()).isEqualTo(IllustratedLesson.TeachingMove.DO);
        assertThat(steps.get(1).kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(steps.get(1).visualFocus().pageNumber()).isEqualTo(3);
    }

    @Test
    void binds_a_visual_crop_to_the_exact_step_when_multiple_steps_share_a_page_and_evidence_chunk() {
        UUID sharedEvidence = UUID.randomUUID();
        IllustratedLesson source = twoSamePageRulesLesson(sharedEvidence);
        List<Integer> requestedSteps = new java.util.ArrayList<>();
        VisualRegionLocator locator = request -> {
            assertThat(request.claims()).hasSize(2);
            requestedSteps.addAll(request.claims().stream().map(VisualRegionLocator.Claim::stepPosition).toList());
            assertThat(request.claims()).extracting(VisualRegionLocator.Claim::text)
                    .allSatisfy(text -> assertThat(text).startsWith("步骤 "));
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    2,
                    "资源图例",
                    "五种彩色木方旁分别印有对应资源名称",
                    45,
                    510,
                    310,
                    490,
                    List.of(sharedEvidence),
                    List.of(2)));
        };

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrich(UUID.randomUUID(), source);

        var steps = enriched.sections().getFirst().steps();
        assertThat(steps.get(0).kind()).isEqualTo(IllustratedLesson.TeachingMove.DO);
        assertThat(steps.get(1).kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(steps.get(1).heading()).isEqualTo("创建公共供应区");
        assertThat(steps.get(1).text()).contains("资源木方放入公共供应区");
        assertThat(requestedSteps).containsExactly(1, 2);
    }

    @Test
    void retains_the_original_step_when_a_later_section_returns_the_same_reader_viewport() {
        UUID firstEvidence = UUID.randomUUID();
        UUID secondEvidence = UUID.randomUUID();
        VisualRegionLocator locator = request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                2,
                "建造示例",
                "蓝色资源方块被移除后替换成木制建筑模型，旁边有箭头",
                350,
                420,
                650,
                580,
                List.of(request.claims().getFirst().evidenceId()),
                List.of(request.claims().getFirst().stepPosition())));

        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrichWithReport(UUID.randomUUID(), twoSectionsWithOverlappingVisuals(firstEvidence, secondEvidence), "owner");

        assertThat(result.lesson().sections().get(0).steps().getFirst().kind())
                .isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(result.lesson().sections().get(1).steps().getFirst().kind())
                .isEqualTo(IllustratedLesson.TeachingMove.DO);
        assertThat(result.outcomes()).extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .containsExactly(VisualLessonEnricher.Outcome.ADDED, VisualLessonEnricher.Outcome.REJECTED_DUPLICATE);
    }

    @Test
    void acceptsTheLocatorExplicitStepBindingWithoutHeadingKeywordComparison() {
        UUID evidence = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "资源图标与名称对照表",
                                "五种资源名称下方分别对应一个彩色立方体图标",
                                120,
                                220,
                                180,
                                120,
                                List.of(evidence),
                                List.of(1))))
                .enrichWithReport(UUID.randomUUID(), playerBoardLesson(evidence), "owner");

        assertThat(result.lesson().sections().getFirst().steps().getFirst().kind())
                .isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(result.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.ADDED);
    }

    @Test
    void leaves_the_section_unchanged_when_the_locator_misses_every_candidate() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lesson(chunk);
        var enriched = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> List.of(),
                new VisualRegionCandidateSelector(),
                request -> java.util.Optional.empty()).enrich(UUID.randomUUID(), source);

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void reports_when_a_visual_model_cannot_find_a_reliable_region() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.empty())
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.sectionPosition()).isEqualTo(1);
            assertThat(outcome.outcome()).isEqualTo(VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE);
            assertThat(outcome.summary()).contains("视觉模型未找到可靠局部图示");
        });
    }

    @Test
    void acceptsAWholePageWhenTheLocatorTypesItAsTheReadableFallback() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lesson(chunk);

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "完整流程图",
                                "整页是一张由箭头连接的连续流程图",
                                0,
                                0,
                                1_000,
                                1_000,
                                List.of(chunk),
                                List.of(1),
                                false,
                                IllustratedLesson.VisualSourceKind.FULL_PAGE)))
                .enrich(UUID.randomUUID(), source);

        var focus = enriched.sections().getFirst().steps().getFirst().visualFocus();
        assertThat(focus.sourceKind()).isEqualTo(IllustratedLesson.VisualSourceKind.FULL_PAGE);
        assertThat(focus.width()).isEqualTo(1_000);
    }

    @Test
    void acceptsALargeOwnedPageRegionWithoutTheLegacyAreaThreshold() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "组件总览", "桌面上的组件图示与名称", 50, 60, 900, 850, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.lesson().sections().getFirst().steps().getFirst().kind())
                .isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(result.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.ADDED);
    }

    @Test
    void keepsAnExistingLargeVisualAndCanAddASeparateOwnedCropForTheSameRule() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lessonWithOverlyBroadVisual(chunk);
        String originalText = source.sections().getFirst().steps().getFirst().text();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "轨道与探测器", "一枚圆形探测器标记位于弧形轨道旁", 320, 420, 260, 180, List.of(chunk), List.of(1))))
                .enrichWithReport(UUID.randomUUID(), source, "owner");

        var step = result.lesson().sections().getFirst().steps().getFirst();
        assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(step.visualFoci()).hasSize(2);
        assertThat(step.visualFoci().get(1)).isEqualTo(new IllustratedLesson.VisualFocus(
                2, "轨道与探测器", "一枚圆形探测器标记位于弧形轨道旁", 320, 420, 260, 180));
        assertThat(step.text()).isEqualTo(originalText);
    }

    @Test
    void keepsAnExistingLargeVisualWhenOptionalVisualWorkFindsNothing() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lessonWithOverlyBroadVisual(chunk);
        String originalText = source.sections().getFirst().steps().getFirst().text();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.empty())
                .enrichWithReport(UUID.randomUUID(), source, "owner");

        var step = result.lesson().sections().getFirst().steps().getFirst();
        assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(step.visualFocus()).isNotNull();
        assertThat(step.text()).isEqualTo(originalText);
    }

    @Test
    void preservesBroadModelVisualIntentForTargetingWithoutProbingOrdinarySteps() {
        UUID chunk = UUID.randomUUID();
        List<Integer> requestedSteps = new java.util.ArrayList<>();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> {
                            requestedSteps.add(request.claims().getFirst().stepPosition());
                            return java.util.Optional.empty();
                        })
                .enrichWithReport(
                        UUID.randomUUID(), lessonWithBroadVisualIntentAndOrdinaryStep(chunk), "owner");

        assertThat(requestedSteps).containsExactly(1);
        assertThat(result.lesson().sections().getFirst().steps())
                .extracting(IllustratedLesson.LessonStep::kind)
                .containsExactly(IllustratedLesson.TeachingMove.VISUAL, IllustratedLesson.TeachingMove.DO);
    }

    @Test
    void keepsAnExistingBoundedCropWithoutGameSpecificAspectRatioRules() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "鲑鱼计分卡",
                                "四张鲑鱼计分卡的粉色鲑鱼图标与相邻分数格",
                                45,
                                510,
                                300,
                                180,
                                List.of(chunk),
                                List.of(1))))
                .enrichWithReport(UUID.randomUUID(), lessonWithNarrowTallScoreVisual(chunk), "owner");

        var step = result.lesson().sections().getFirst().steps().getFirst();
        assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(step.visualFocus())
                .isEqualTo(new IllustratedLesson.VisualFocus(
                        2, "鲑鱼计分卡示例", 45, 460, 260, 540));
    }

    private RulebookUnderstanding understanding() {
        return new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        2, 0, 0, RulebookUnderstanding.BlockRole.BODY, "把探测器放到轨道上",
                        new RulebookUnderstanding.Rectangle(100, 200, 300, 180), null)),
                List.of(), List.of(), List.of());
    }

    private RulebookUnderstanding.PageBlock block(int page, String text, int x, int y) {
        return new RulebookUnderstanding.PageBlock(
                page, 0, 0, RulebookUnderstanding.BlockRole.BODY, text,
                new RulebookUnderstanding.Rectangle(x, y, 300, 160), null);
    }

    private IllustratedLesson lesson(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1, "放置探测器", IllustratedLesson.TeachingMove.DO, "把探测器放到轨道上。", List.of(2), List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "开局设置", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "把探测器放到轨道上。", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoPageLesson(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1, "Launch a probe", IllustratedLesson.TeachingMove.DO, "Launch a probe.", List.of(2, 3), List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                1, "launch", List.of("launch"), "Launch a probe", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "Launch a probe.", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson threePageTranslatedLesson(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1,
                "放置动物标记",
                IllustratedLesson.TeachingMove.DO,
                "把动物标记放到符合条件的六边形地块上。",
                List.of(2, 3, 4),
                List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                1, "animals", List.of("animals"), "动物标记", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "放置动物标记。", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson lessonWithOverlyBroadVisual(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1,
                "放置探测器",
                IllustratedLesson.TeachingMove.VISUAL,
                "图中可见旧的整页组件总览。结合图片完成这一步：把探测器放到轨道上。",
                List.of(2),
                List.of(chunk),
                new IllustratedLesson.VisualFocus(
                        2,
                        "旧的整页组件总览",
                        "整页展示所有组件与轨道关系",
                        0,
                        0,
                        1_000,
                        1_000,
                        IllustratedLesson.VisualSourceKind.FULL_PAGE));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "开局设置", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "把探测器放到轨道上。", List.of(2), List.of(chunk), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson lessonWithBroadVisualIntentAndOrdinaryStep(UUID chunk) {
        var source = lessonWithOverlyBroadVisual(chunk);
        var original = source.sections().getFirst();
        var ordinary = new IllustratedLesson.LessonStep(
                2,
                "Resolve an ordinary exception",
                IllustratedLesson.TeachingMove.DO,
                "Resolve the cited exception without a diagram.",
                List.of(2),
                List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                original.position(),
                original.topicKey(),
                original.coverageTags(),
                original.title(),
                original.required(),
                original.evidenceStatus(),
                original.visualKind(),
                original.visualCaption(),
                original.visualSourcePages(),
                original.visualSourceChunkIds(),
                List.of(original.steps().getFirst(), ordinary));
        return new IllustratedLesson(
                source.id(), source.teachingPlanId(), source.status(), List.of(section), source.generatorVersion(), source.createdAt());
    }

    private IllustratedLesson lessonWithNarrowTallScoreVisual(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1,
                "鲑鱼游群计分",
                IllustratedLesson.TeachingMove.VISUAL,
                "图中图标提示：四张鲑鱼计分卡。先认出这组图标，再按规则处理：鲑鱼按相邻游群计分。",
                List.of(2),
                List.of(chunk),
                new IllustratedLesson.VisualFocus(2, "鲑鱼计分卡示例", 45, 460, 260, 540));
        var section = new IllustratedLesson.LessonSection(
                1, "scoring", List.of("scoring"), "鲑鱼计分", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "鲑鱼按相邻游群计分。", List.of(2), List.of(chunk), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson sameEvidenceAcrossPagesLesson(UUID sharedEvidence) {
        var overview = new IllustratedLesson.LessonStep(
                1, "游戏目标", IllustratedLesson.TeachingMove.DO, "收集最有价值的文物。", List.of(2), List.of(sharedEvidence));
        var cardAnatomy = new IllustratedLesson.LessonStep(
                2, "文物卡字段", IllustratedLesson.TeachingMove.DO, "对照卡牌上的字段。", List.of(3), List.of(sharedEvidence));
        var section = new IllustratedLesson.LessonSection(
                1, "overview", List.of("overview"), "先认识游戏", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "知道目标和卡牌字段", List.of(), List.of(), List.of(overview, cardAnatomy));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoRuleLesson(UUID iconEvidence, UUID stateEvidence) {
        var first = new IllustratedLesson.LessonStep(
                1, "掷骰并行动", IllustratedLesson.TeachingMove.DO, "掷骰后执行行动。", List.of(2), List.of(iconEvidence));
        var second = new IllustratedLesson.LessonStep(
                2, "移动探测器", IllustratedLesson.TeachingMove.DO, "把探测器移到轨道上。", List.of(2), List.of(stateEvidence));
        var section = new IllustratedLesson.LessonSection(
                1, "turn", List.of("turn"), "轮到你时", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                "完成一次行动", List.of(), List.of(), List.of(first, second));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson sixRuleLesson(UUID evidence) {
        var steps = new java.util.ArrayList<IllustratedLesson.LessonStep>();
        for (int position = 1; position <= 6; position++) {
            steps.add(new IllustratedLesson.LessonStep(
                    position,
                    "规则 " + position,
                    IllustratedLesson.TeachingMove.DO,
                    "按顺序完成第 " + position + " 个行动。",
                    List.of(2),
                    List.of(evidence)));
        }
        var section = new IllustratedLesson.LessonSection(
                1, "turn", List.of("turn"), "完整回合", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                "完成完整回合", List.of(), List.of(), steps);
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson lessonWithExplicitVisualIntent(UUID evidence) {
        var ordinary = new IllustratedLesson.LessonStep(
                1,
                "Read the exception",
                IllustratedLesson.TeachingMove.DO,
                "Resolve the cited exception before continuing.",
                List.of(2),
                List.of(evidence));
        var visual = new IllustratedLesson.LessonStep(
                2,
                "查看探测器轨道",
                IllustratedLesson.TeachingMove.VISUAL,
                "把探测器放到轨道上。",
                List.of(2),
                List.of(evidence));
        var section = new IllustratedLesson.LessonSection(
                1, "opaque-flow", List.of("opaque"), "Opaque procedure", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "Complete the procedure", List.of(), List.of(), List.of(ordinary, visual));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoSamePageRulesLesson(UUID sharedEvidence) {
        var playerBoard = new IllustratedLesson.LessonStep(
                1, "每位玩家拿取玩家板", IllustratedLesson.TeachingMove.DO, "给每位玩家一块玩家板。", List.of(2), List.of(sharedEvidence));
        var supply = new IllustratedLesson.LessonStep(
                2, "创建公共供应区", IllustratedLesson.TeachingMove.DO, "把五种资源木方放入公共供应区。", List.of(2), List.of(sharedEvidence));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "标准游戏设置流程", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "完成开局设置", List.of(), List.of(), List.of(playerBoard, supply));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoSectionsWithOverlappingVisuals(UUID firstEvidence, UUID secondEvidence) {
        var firstStep = new IllustratedLesson.LessonStep(
                1, "建造建筑", IllustratedLesson.TeachingMove.DO, "移除资源方块并放上建筑模型。", List.of(2), List.of(firstEvidence));
        var secondStep = new IllustratedLesson.LessonStep(
                1, "跟随示例", IllustratedLesson.TeachingMove.DO, "按照示例完成一次建造。", List.of(2), List.of(secondEvidence));
        var first = new IllustratedLesson.LessonSection(
                1, "build", List.of("core_loop"), "建造建筑", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                "建造", List.of(), List.of(), List.of(firstStep));
        var second = new IllustratedLesson.LessonSection(
                2, "example", List.of("examples"), "跟随示例", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                "示例", List.of(), List.of(), List.of(secondStep));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(first, second), "test", Instant.now());
    }

    private IllustratedLesson multiSectionVisualLesson(List<UUID> evidence) {
        List<IllustratedLesson.LessonSection> sections = new java.util.ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            int position = index + 1;
            int page = position + 1;
            var step = new IllustratedLesson.LessonStep(
                    1,
                    "执行步骤 " + position,
                    IllustratedLesson.TeachingMove.DO,
                    "按照规则图完成第 " + position + " 个步骤。",
                    List.of(page),
                    List.of(evidence.get(index)));
            sections.add(new IllustratedLesson.LessonSection(
                    position,
                    "step_" + position,
                    List.of("core_loop"),
                    "流程 " + position,
                    true,
                    IllustratedLesson.EvidenceStatus.SUPPORTED,
                    IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                    "完成流程步骤",
                    List.of(),
                    List.of(),
                    List.of(step)));
        }
        return new IllustratedLesson(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IllustratedLesson.LessonStatus.DRAFT_READY,
                sections,
                "test",
                Instant.now());
    }

    private IllustratedLesson playerBoardLesson(UUID evidence) {
        var step = new IllustratedLesson.LessonStep(
                1, "每位玩家拿取玩家板", IllustratedLesson.TeachingMove.DO, "给每位玩家一块玩家板，作为个人城镇的4x4网格。", List.of(2), List.of(evidence));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "设置", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "设置", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }
}
