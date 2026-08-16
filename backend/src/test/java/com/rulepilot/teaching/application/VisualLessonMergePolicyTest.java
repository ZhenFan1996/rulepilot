package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonMergePolicyTest {

    private final VisualLessonMergePolicy policy = new VisualLessonMergePolicy(new VisualReaderCropPolicy());

    @Test
    void dropsOnlyBroadVisualMetadataAndPreservesTheExactPlayerFacingText() {
        UUID evidence = UUID.randomUUID();
        LessonStep broad = new LessonStep(
                1,
                "放置探测器",
                TeachingMove.VISUAL,
                "图中可见旧的整页组件总览。结合图片完成这一步：把探测器放到轨道上。",
                List.of(2),
                List.of(evidence),
                new VisualFocus(2, "旧的整页组件总览", 50, 60, 900, 850));

        IllustratedLesson restored = policy.discardOverlyBroadVisuals(lesson(section(List.of(broad), List.of(2), List.of(evidence))));

        LessonSection section = restored.sections().getFirst();
        assertThat(section.steps().getFirst())
                .extracting(LessonStep::kind, LessonStep::text, LessonStep::visualFocus)
                .containsExactly(
                        TeachingMove.DO,
                        "图中可见旧的整页组件总览。结合图片完成这一步：把探测器放到轨道上。",
                        null);
        assertThat(section.visualSourcePages()).isEmpty();
        assertThat(section.visualSourceChunkIds()).isEmpty();
    }

    @Test
    void bindsARegionWithoutChangingOriginalStepOrVisualModelProse() {
        UUID evidence = UUID.randomUUID();
        LessonSection source = section(List.of(ruleStep(evidence)), List.of(), List.of());
        LocatedRegion region = new LocatedRegion(
                2,
                " 轨道与探测器 ",
                " 圆形探测器标记位于弧形刻度旁，箭头指向前进方向。 ",
                120,
                220,
                180,
                120,
                List.of(evidence),
                List.of(1));

        VisualLessonMergePolicy.MergedVisualSection merged = policy.mergeVisualIntoSupportedSteps(source, List.of(region));

        assertThat(merged.addedCount()).isEqualTo(1);
        assertThat(merged.section().steps().getFirst())
                .extracting(LessonStep::kind, LessonStep::heading, LessonStep::text, LessonStep::visualFocus)
                .containsExactly(
                        TeachingMove.VISUAL,
                        "放置探测器",
                        "把探测器放到轨道上。",
                        new VisualFocus(
                                2,
                                " 轨道与探测器 ",
                                " 圆形探测器标记位于弧形刻度旁，箭头指向前进方向。 ",
                                120,
                                220,
                        180,
                        120));
    }

    @Test
    void skipsAnInvalidVisualLabelInsteadOfReplacingItWithLessonProse() {
        UUID evidence = UUID.randomUUID();
        LessonSection source = section(List.of(ruleStep(evidence)), List.of(), List.of());
        LocatedRegion region = new LocatedRegion(
                2,
                "Probe track",
                "圆形探测器标记位于弧形刻度旁。",
                120,
                220,
                180,
                120,
                List.of(evidence),
                List.of(1));

        VisualLessonMergePolicy.MergedVisualSection merged = policy.mergeVisualIntoSupportedSteps(source, List.of(region));

        assertThat(merged.addedCount()).isZero();
        assertThat(merged.section()).isEqualTo(source);
    }

    @Test
    void rejectsOnlyAContradictingCropAndKeepsTheValidatedSectionUntouched() {
        UUID evidence = UUID.randomUUID();
        LessonSection supported = new LessonSection(
                1,
                "scoring",
                List.of("scoring"),
                "计分",
                true,
                IllustratedLesson.EvidenceStatus.SUPPORTED,
                IllustratedLesson.VisualKind.SCOREBOARD,
                "核对计分示例。",
                List.of(),
                List.of(),
                List.of(new LessonStep(
                        1, "计算总分", TeachingMove.DO, "4 个对象每个 3 分，共 8 分。", List.of(5), List.of(evidence))));
        LocatedRegion contradiction = new LocatedRegion(
                        5,
                        "计分示例",
                        "图中列出 4 个对象，每个 3 分，右侧总计 12 分。",
                        120,
                        220,
                        420,
                        280,
                        List.of(evidence),
                        List.of(1))
                .withClaimContradiction();

        VisualLessonMergePolicy.MergedVisualSection merged =
                policy.mergeVisualIntoSupportedSteps(supported, List.of(contradiction));

        assertThat(merged.addedCount()).isZero();
        assertThat(merged.claimConflictCount()).isEqualTo(1);
        assertThat(merged.section()).isEqualTo(supported);
    }

    @Test
    void restoresTheOriginalStepWhenTheNewViewportDuplicatesAnAcceptedAid() {
        UUID evidence = UUID.randomUUID();
        LessonSection original = section(List.of(ruleStep(evidence)), List.of(), List.of());
        VisualFocus focus = new VisualFocus(2, "轨道与探测器", 120, 220, 180, 120);
        LessonStep visual = new LessonStep(
                1,
                "放置探测器",
                TeachingMove.VISUAL,
                "图中可见探测器。结合图片完成这一步：把探测器放到轨道上。",
                List.of(2),
                List.of(evidence),
                focus);
        LessonSection candidate = section(List.of(visual), List.of(2), List.of(evidence));

        VisualLessonMergePolicy.DistinctVisualSection distinct =
                policy.keepDistinctVisuals(original, candidate, new java.util.ArrayList<>(List.of(focus)));

        assertThat(distinct.hadDuplicate()).isTrue();
        assertThat(distinct.addedCount()).isZero();
        assertThat(distinct.section()).isEqualTo(original);
    }

    private LessonStep ruleStep(UUID evidence) {
        return new LessonStep(
                1, "放置探测器", TeachingMove.DO, "把探测器放到轨道上。", List.of(2), List.of(evidence));
    }

    private LessonSection section(List<LessonStep> steps, List<Integer> visualPages, List<UUID> visualChunks) {
        return new LessonSection(
                1,
                "setup",
                List.of("setup"),
                "开局设置",
                true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT,
                IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "把探测器放到轨道上。",
                visualPages,
                visualChunks,
                steps);
    }

    private IllustratedLesson lesson(LessonSection section) {
        return new IllustratedLesson(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section),
                "test",
                Instant.now());
    }
}
