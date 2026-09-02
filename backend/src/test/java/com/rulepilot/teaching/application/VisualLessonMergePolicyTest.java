package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonMergePolicyTest {

    private final VisualLessonMergePolicy policy = new VisualLessonMergePolicy(new VisualReaderCropPolicy());

    @Test
    void attachesSeveralOwnedVisualsToOneStepWithoutChangingValidatedProse() {
        UUID evidence = UUID.randomUUID();
        LessonSection source = section(List.of(ruleStep(evidence)), List.of(), List.of());
        List<LocatedRegion> regions = List.of(
                region(evidence, "行动图标", 80, 120, 160, 120),
                region(evidence, "轨道状态", 320, 360, 260, 180),
                new LocatedRegion(
                        2,
                        "牌面示例",
                        "卡牌下方有一排资源图标",
                        650,
                        180,
                        240,
                        340,
                        List.of(evidence),
                        List.of(1),
                        false,
                        VisualSourceKind.EMBEDDED_AUTHOR_IMAGE));

        VisualLessonMergePolicy.MergedVisualSection merged = policy.mergeVisualIntoSupportedSteps(
                source, regions, new ArrayList<>());

        LessonStep step = merged.section().steps().getFirst();
        assertThat(merged.addedCount()).isEqualTo(3);
        assertThat(step.kind()).isEqualTo(TeachingMove.VISUAL);
        assertThat(step.text()).isEqualTo("把探测器放到轨道上。");
        assertThat(step.visualFocus()).isEqualTo(step.visualFoci().getFirst());
        assertThat(step.visualFoci()).hasSize(3)
                .extracting(VisualFocus::label)
                .containsExactly("行动图标", "轨道状态", "牌面示例");
        assertThat(step.visualFoci().get(2).sourceKind()).isEqualTo(VisualSourceKind.EMBEDDED_AUTHOR_IMAGE);
    }

    @Test
    void keepsAnExistingFullPageVisualAndAddsASeparateOwnedRegion() {
        UUID evidence = UUID.randomUUID();
        VisualFocus fullPage = new VisualFocus(
                2,
                "完整流程图",
                "整页是一张由箭头连接的流程图",
                0,
                0,
                1_000,
                1_000,
                VisualSourceKind.FULL_PAGE);
        LessonStep existing = new LessonStep(
                1,
                "执行流程",
                TeachingMove.VISUAL,
                "依次执行三个阶段。",
                List.of(2),
                List.of(evidence),
                List.of(),
                fullPage,
                List.of(fullPage));
        LessonSection source = section(List.of(existing), List.of(2), List.of(evidence));

        var merged = policy.mergeVisualIntoSupportedSteps(
                source,
                List.of(region(evidence, "阶段图标", 120, 140, 180, 120)),
                new ArrayList<>(List.of(fullPage)));

        assertThat(merged.section().steps().getFirst().text()).isEqualTo("依次执行三个阶段。");
        assertThat(merged.section().steps().getFirst().visualFoci())
                .extracting(VisualFocus::sourceKind)
                .containsExactly(VisualSourceKind.FULL_PAGE, VisualSourceKind.PAGE_REGION);
    }

    @Test
    void rejectsOnlyAContradictingVisualAndKeepsTheValidatedSectionUntouched() {
        UUID evidence = UUID.randomUUID();
        LessonSection supported = section(List.of(ruleStep(evidence)), List.of(), List.of());
        LocatedRegion contradiction = region(evidence, "冲突计分示例", 120, 220, 420, 280)
                .withClaimContradiction();

        var merged = policy.mergeVisualIntoSupportedSteps(
                supported, List.of(contradiction), new ArrayList<>());

        assertThat(merged.addedCount()).isZero();
        assertThat(merged.claimConflictCount()).isEqualTo(1);
        assertThat(merged.section()).isEqualTo(supported);
    }

    @Test
    void rejectsOnlyADuplicateVisualAndDoesNotEraseTheStep() {
        UUID evidence = UUID.randomUUID();
        LessonSection source = section(List.of(ruleStep(evidence)), List.of(), List.of());
        VisualFocus accepted = new VisualFocus(2, "前文轨道", 100, 200, 220, 160);

        var merged = policy.mergeVisualIntoSupportedSteps(
                source,
                List.of(region(evidence, "重复轨道", 110, 210, 200, 150)),
                new ArrayList<>(List.of(accepted)));

        assertThat(merged.addedCount()).isZero();
        assertThat(merged.duplicateCount()).isEqualTo(1);
        assertThat(merged.section()).isEqualTo(source);
    }

    @Test
    void keepsRuleCitationsOnTheirOwnPagesWhenAVisualComesFromAnotherRulebookPage() {
        UUID evidence = UUID.randomUUID();
        LessonStep citedStep = new LessonStep(
                1,
                "建立市场",
                TeachingMove.DO,
                "按引用规则建立市场。",
                List.of(4),
                List.of(evidence));
        LessonSection source = section(List.of(citedStep), List.of(4), List.of(evidence));
        LocatedRegion illustration = new LocatedRegion(
                5,
                "市场示意图",
                "三叠牌下方各有两张蔬菜卡。",
                100,
                120,
                700,
                400,
                List.of(evidence),
                List.of(1));

        var merged = policy.mergeVisualIntoSupportedSteps(
                source, List.of(illustration), new ArrayList<>());

        assertThat(merged.addedCount()).isEqualTo(1);
        assertThat(merged.section().visualSourcePages()).containsExactly(4, 5);
        assertThat(merged.section().steps().getFirst().sourcePages()).containsExactly(4);
        assertThat(merged.section().steps().getFirst().visualFocus().pageNumber()).isEqualTo(5);
    }

    private LocatedRegion region(UUID evidence, String label, int x, int y, int width, int height) {
        return new LocatedRegion(
                2,
                label,
                "图中可见" + label,
                x,
                y,
                width,
                height,
                List.of(evidence),
                List.of(1));
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
                IllustratedLesson.EvidenceStatus.SUPPORTED,
                IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "按规则设置。",
                visualPages,
                visualChunks,
                steps);
    }
}
