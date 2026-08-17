package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.TeachingUnitInput;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlannedUnitCoveragePolicyTest {

    private final UUID versionId = UUID.randomUUID();
    private final RuleEvidence alpha = evidence("R-alpha", "R-alpha starts with one choice and then resolves it.", 2);
    private final RuleEvidence beta = evidence("R-beta", "R-beta is the tightly coupled stopping check.", 2);
    private final RuleEvidence gamma = evidence("R-gamma", "R-gamma is a different conditional procedure.", 3);

    @Test
    void acceptsThePlanningAgentsOwnGroupingWithoutApplyingAFixedStepMinimum() {
        List<TeachingUnitInput> units = List.of(new TeachingUnitInput(
                "coupled-resolution", List.of("R-alpha", "R-beta"), List.of(alpha.chunkId(), beta.chunkId())));
        SectionDraft draft = draft(List.of(step(
                "完成一个连贯单元",
                "先作出选择并完成结算，再检查这一单元是否满足停止条件。",
                List.of(alpha.chunkId(), beta.chunkId()),
                List.of("coupled-resolution"))));

        assertThatCode(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, beta), draft))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOneCoherentStepThatConnectsTwoAgentPlannedUnitsWithBothDirectSources() {
        List<TeachingUnitInput> units = List.of(
                new TeachingUnitInput("first-decision", List.of("R-alpha"), List.of(alpha.chunkId())),
                new TeachingUnitInput("conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft compressed = draft(List.of(step(
                "全部一起做",
                "执行 R-alpha；如果情况变化，再执行 R-gamma。",
                List.of(alpha.chunkId(), gamma.chunkId()),
                List.of("first-decision", "conditional-procedure"))));

        assertThatCode(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, gamma), compressed))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsARedundantCrossUnitCheckWhenEachUnitAlreadyHasItsOwnInstruction() {
        List<TeachingUnitInput> units = List.of(
                new TeachingUnitInput("first-decision", List.of("R-alpha"), List.of(alpha.chunkId())),
                new TeachingUnitInput("conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft draft = draft(List.of(
                step(
                        "先做决定",
                        "按 R-alpha 作出选择并完成结算。",
                        List.of(alpha.chunkId()),
                        List.of("first-decision")),
                step(
                        "再处理条件",
                        "条件成立时执行 R-gamma。",
                        List.of(gamma.chunkId()),
                        List.of("conditional-procedure")),
                step(
                        "检查整个流程",
                        TeachingMove.CHECK,
                        "确认两个已讲清的单元都已完成。",
                        List.of(alpha.chunkId(), gamma.chunkId()),
                        List.of("first-decision", "conditional-procedure"))));

        assertThatCode(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, gamma), draft))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsACrossUnitCheckWhenItDirectlyCitesEveryClaimedUnit() {
        List<TeachingUnitInput> units = List.of(
                new TeachingUnitInput("first-decision", List.of("R-alpha"), List.of(alpha.chunkId())),
                new TeachingUnitInput("conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft compressed = draft(List.of(
                step(
                        "先做决定",
                        "按 R-alpha 作出选择并完成结算。",
                        List.of(alpha.chunkId()),
                        List.of("first-decision")),
                step(
                        "一起检查",
                        TeachingMove.CHECK,
                        "检查 R-alpha 和 R-gamma。",
                        List.of(alpha.chunkId(), gamma.chunkId()),
                        List.of("first-decision", "conditional-procedure"))));

        assertThatCode(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, gamma), compressed))
                .doesNotThrowAnyException();
    }

    @Test
    void aCrossUnitStepStillFailsWhenItDoesNotCiteOneUnitsDirectSource() {
        List<TeachingUnitInput> units = List.of(
                new TeachingUnitInput("first-decision", List.of("R-alpha"), List.of(alpha.chunkId())),
                new TeachingUnitInput("conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft unsupported = draft(List.of(step(
                "关联两个流程",
                "先按 R-alpha 作出选择，再处理 R-gamma 的条件。",
                List.of(alpha.chunkId()),
                List.of("first-decision", "conditional-procedure"))));

        assertThatThrownBy(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, gamma), unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditional-procedure", "must cite direct evidence");
    }

    @Test
    void rejectsAnOtherwiseReadableDraftThatOmitsOnePlanOwnedUnit() {
        List<TeachingUnitInput> units = List.of(
                new TeachingUnitInput("first-decision", List.of("R-alpha"), List.of(alpha.chunkId())),
                new TeachingUnitInput("conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft incomplete = draft(List.of(step(
                "先做第一个决定",
                "按 R-alpha 作出选择并完成结算。",
                List.of(alpha.chunkId()),
                List.of("first-decision"))));

        assertThatThrownBy(() -> TeachingPlannedUnitCoveragePolicy.validate(
                        units, List.of(alpha, gamma), incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted planned teaching unit conditional-procedure");
    }

    @Test
    void rejectsAVisibleAnchorWhenItsStepCitesOnlyAnUnrelatedSource() {
        List<TeachingUnitInput> units = List.of(new TeachingUnitInput(
                "conditional-procedure", List.of("R-gamma"), List.of(gamma.chunkId())));
        SectionDraft wrongCitation = draft(List.of(step(
                "处理条件",
                "条件成立时执行 R-gamma。",
                List.of(alpha.chunkId()),
                List.of("conditional-procedure"))));

        assertThatThrownBy(() -> TeachingPlannedUnitCoveragePolicy.validate(
                units, List.of(alpha, gamma), wrongCitation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must cite direct evidence");
    }

    @Test
    void rejectsAUnitWhosePlannedSourceAnchorWasNotRetrieved() {
        List<TeachingUnitInput> units = List.of(new TeachingUnitInput(
                "conditional-procedure", List.of("R-missing")));
        SectionDraft unsupported = draft(List.of(step(
                "处理条件",
                "条件成立时执行这项程序。",
                List.of(gamma.chunkId()),
                List.of("conditional-procedure"))));

        assertThatThrownBy(() -> TeachingPlannedUnitCoveragePolicy.validate(
                units, List.of(gamma), unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must cite direct evidence");
    }

    private SectionDraft draft(List<StepDraft> steps) {
        return new SectionDraft(
                "按规划完成这一节",
                VisualKind.REFERENCE_CARD,
                "用来源锚点完成本节操作。",
                List.of(alpha.chunkId()),
                steps);
    }

    private StepDraft step(
            String heading,
            String text,
            List<UUID> citations,
            List<String> teachingUnitIds) {
        return step(heading, TeachingMove.DO, text, citations, teachingUnitIds);
    }

    private StepDraft step(
            String heading,
            TeachingMove move,
            String text,
            List<UUID> citations,
            List<String> teachingUnitIds) {
        return new StepDraft(
                heading,
                move,
                text,
                citations,
                teachingUnitIds,
                null);
    }

    private RuleEvidence evidence(String heading, String excerpt, int page) {
        return new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "PAGE",
                heading,
                excerpt,
                page,
                page);
    }
}
