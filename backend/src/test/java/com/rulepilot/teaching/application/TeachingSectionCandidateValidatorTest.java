package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.TeachingUnitInput;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSectionCandidateValidatorTest {

    private final TeachingSectionCandidateValidator validator =
            new TeachingSectionCandidateValidator(new PolicyEvidenceVerifier());

    @Test
    void producesACitedSectionWithTheAttachedVisualSourcePage() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle of the table before the first turn.",
                4,
                4);
        TeachingPlan plan = plan(versionId);
        TeachingPlan.PlannedSection planned = plan.sections().getFirst();
        SectionDraft draft = new SectionDraft(
                "摆好中央展示区",
                VisualKind.TABLE_LAYOUT,
                "先在图中找到主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "放置主棋盘",
                        TeachingMove.VISUAL,
                        "在图中找到主棋盘，再把它放在桌面中央。",
                        List.of(chunkId),
                        new VisualFocusDraft(4, "主棋盘", 120, 120, 500, 500))));

        var section = validator.validate(
                plan, planned, List.of(evidence), request(chunkId), draft, EvidenceStatus.CITED_DRAFT);

        assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(section.visualSourcePages()).containsExactly(4);
        assertThat(section.visualSourceChunkIds()).containsExactly(chunkId);
        assertThat(section.steps()).singleElement().satisfies(step -> {
            assertThat(step.sourcePages()).containsExactly(4);
            assertThat(step.visualFocus().pageNumber()).isEqualTo(4);
        });
    }

    @Test
    void rejectsAPlayerFacingStepWithoutItsOwnCitation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        TeachingPlan plan = plan(versionId);
        SectionDraft draft = new SectionDraft(
                "从公共区域开始",
                VisualKind.FLOW_DIAGRAM,
                "先准备公共区域，再开始行动。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "执行行动",
                        TeachingMove.DO,
                        "选择一项可用行动，结算完成后把回合交给下一位玩家。",
                        List.of())));

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        textRequest(chunkId),
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLAIM_WITHOUT_CITATION");
    }

    @Test
    void rejectsACitationThatWasNotIncludedInTheAllowedEvidence() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID unknownChunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        SectionDraft draft = new SectionDraft(
                "从公共区域开始",
                VisualKind.FLOW_DIAGRAM,
                "先准备公共区域，再开始行动。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "执行行动",
                        TeachingMove.DO,
                        "选择一项可用行动，结算完成后把回合交给下一位玩家。",
                        List.of(unknownChunkId))));

        TeachingPlan plan = plan(versionId);
        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        textRequest(chunkId),
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CITATION_OUTSIDE_EVIDENCE");
    }

    @Test
    void rejectsEvidenceFromAnotherDocumentVersionEvenWhenTheCitationIdIsAllowed() {
        UUID planVersionId = UUID.randomUUID();
        UUID otherVersionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, otherVersionId);
        SectionDraft draft = new SectionDraft(
                "从公共区域开始",
                VisualKind.FLOW_DIAGRAM,
                "先准备公共区域，再开始行动。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "执行行动",
                        TeachingMove.DO,
                        "选择一项可用行动，结算完成后把回合交给下一位玩家。",
                        List.of(chunkId))));
        TeachingPlan plan = plan(planVersionId);

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        textRequest(chunkId),
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERSION_MISMATCH");
    }

    @Test
    void preservesACompleteNaturalSectionExactlyAfterDeterministicValidation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        SectionDraft draft = new SectionDraft(
                "先摆好，再轮流行动",
                VisualKind.FLOW_DIAGRAM,
                "把公共区域放在所有人都方便操作的位置，然后按顺序开始。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "摆好公共区域",
                                TeachingMove.DO,
                                "把公共区域放在桌面中央，并让每位玩家拿好自己的组件。",
                                List.of(chunkId)),
                        new StepDraft(
                                "完成你的回合",
                                TeachingMove.FLOW,
                                "轮到你时选择一项可用行动，完整结算后再把回合交给下一位玩家。",
                                List.of(chunkId)),
                        new StepDraft(
                                "检查是否结束",
                                TeachingMove.CHECK,
                                "每轮结束时检查规则书所列的结束条件；尚未满足就继续下一轮。",
                                List.of(chunkId))));
        TeachingPlan plan = plan(versionId);

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                textRequest(chunkId),
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.title()).isEqualTo(draft.title());
        assertThat(section.visualCaption()).isEqualTo(draft.visualCaption());
        assertThat(section.steps())
                .extracting(step -> step.heading() + "\n" + step.text())
                .containsExactlyElementsOf(draft.steps().stream()
                        .map(step -> step.heading() + "\n" + step.text())
                        .toList());
        assertThat(section.steps()).allSatisfy(step -> {
            assertThat(step.sourceChunkIds()).containsExactly(chunkId);
            assertThat(step.sourcePages()).containsExactly(4);
        });
    }

    @Test
    void preservesEveryAcceptedProseByteInsteadOfTrimmingOrRewritingIt() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        String title = " 先摆好，再开始 ";
        String caption = "先确认公共区域。\n再按顺序行动。 ";
        String heading = " 完成当前回合 ";
        String text = "轮到你时选择可用行动；完整结算后，\n再把回合交给下一位玩家。 ";
        SectionDraft draft = new SectionDraft(
                title,
                VisualKind.FLOW_DIAGRAM,
                caption,
                List.of(chunkId),
                List.of(new StepDraft(heading, TeachingMove.FLOW, text, List.of(chunkId))));
        TeachingPlan plan = plan(versionId);

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                textRequest(chunkId),
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.title()).isEqualTo(title);
        assertThat(section.visualCaption()).isEqualTo(caption);
        assertThat(section.steps().getFirst().heading()).isEqualTo(heading);
        assertThat(section.steps().getFirst().text()).isEqualTo(text);
    }

    @Test
    void doesNotPretendThatNumberMatchingProvesOrDisprovesRuleMeaning() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SCORING",
                "Resource award",
                "Gain 1 resource when the condition is met.",
                8,
                8);
        TeachingPlan plan = scoringPlan(versionId);
        String caption = "按证据结算；原文中的 [bonus]、🎯 与 E1 都可能是合法规则标记。";
        String text = "满足条件时获得2个资源；这里的数量语义留给生成 Agent 与真实评测，不由字符串比对裁决。";
        SectionDraft draft = new SectionDraft(
                "结算资源",
                VisualKind.REFERENCE_CARD,
                caption,
                List.of(chunkId),
                List.of(new StepDraft("执行结算", TeachingMove.DO, text, List.of(chunkId))));

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                scoringRequest(chunkId),
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.visualCaption()).isEqualTo(caption);
        assertThat(section.steps().getFirst().text()).isEqualTo(text);
    }

    @Test
    void preservesARichCaptionBeyondTheHistoricalDisplayPreference() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        String caption = "把本章所有有来源的限制、顺序和例外放在同一张文字参考卡中；"
                + "先完成当前行动，再检查触发条件，然后按证据所列的顺序处理结果。".repeat(10);
        SectionDraft draft = new SectionDraft(
                "完整参考卡",
                VisualKind.REFERENCE_CARD,
                caption,
                List.of(chunkId),
                List.of(new StepDraft(
                        "执行行动",
                        TeachingMove.DO,
                        "选择一项可用行动，完整结算后把回合交给下一位玩家。",
                        List.of(chunkId))));
        TeachingPlan plan = plan(versionId);

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                textRequest(chunkId),
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(caption.length()).isGreaterThan(240);
        assertThat(section.visualCaption()).isEqualTo(caption);
    }

    @Test
    void agentRepairReplacesOnlyTheInvalidStructuredStepAndPreservesValidProseExactly() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID unknownChunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        TeachingPlan plan = plan(versionId);
        String groundedText = "轮到你时选择一项可用行动，完整结算后再把回合交给下一位玩家。";
        SectionDraft draft = new SectionDraft(
                "执行行动并交棒",
                VisualKind.FLOW_DIAGRAM,
                "完成当前行动后，再让下一位玩家继续。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "完成当前行动",
                                TeachingMove.FLOW,
                                groundedText,
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null),
                        new StepDraft(
                                "补充提醒",
                                TeachingMove.WATCH,
                                "额外提醒来自未检索到的材料。",
                                List.of(unknownChunkId))));
        SectionRequest request = unitRequest(
                chunkId,
                List.of(new TeachingUnitInput(
                        "turn-flow", List.of("Setup and turns"), List.of(chunkId))));

        SectionDraft repaired = new SectionDraft(
                "模型试图改掉标题",
                VisualKind.REFERENCE_CARD,
                "模型也改掉了原本合规的说明。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "改写过的行动",
                                TeachingMove.FLOW,
                                "这一句不应覆盖已经验证的原文。",
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null),
                        new StepDraft(
                                "补充提醒",
                                TeachingMove.WATCH,
                                "这条补充提醒现在引用了已检索证据。",
                                List.of(chunkId))));

        SectionDraft merged = validator.mergeRepairPreservingValidatedFields(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                draft,
                repaired);
        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                merged,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.title()).isEqualTo(draft.title());
        assertThat(section.visualCaption()).isEqualTo(draft.visualCaption());
        assertThat(section.steps()).hasSize(2);
        assertThat(section.steps().getFirst().heading()).isEqualTo("完成当前行动");
        assertThat(section.steps().getFirst().text()).isEqualTo(groundedText);
        assertThat(section.steps().get(1).text()).isEqualTo("这条补充提醒现在引用了已检索证据。");
    }

    @Test
    void agentRepairCanRestoreAUniqueUnitWithoutChangingTheAlreadyGroundedUnit() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID unknownChunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        TeachingPlan plan = plan(versionId);
        String groundedText = "把公共区域放在桌面中央，并让每位玩家拿好自己的组件。";
        SectionDraft draft = new SectionDraft(
                "从准备到检查",
                VisualKind.FLOW_DIAGRAM,
                "先完成有证据的准备，再检查后续条件。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "摆好公共区域",
                                TeachingMove.DO,
                                groundedText,
                                List.of(chunkId),
                                List.of("shared-area"),
                                null),
                        new StepDraft(
                                "检查后续条件",
                                TeachingMove.CHECK,
                                "这一条件引用了当前检索范围之外的材料。",
                                List.of(unknownChunkId),
                                List.of("ending-check"),
                                null)));
        SectionRequest request = unitRequest(
                chunkId,
                List.of(
                        new TeachingUnitInput("shared-area", List.of("Setup and turns"), List.of(chunkId)),
                        new TeachingUnitInput("ending-check", List.of("end condition"), List.of(chunkId))));

        SectionDraft repaired = new SectionDraft(
                draft.title(),
                draft.visualKind(),
                draft.visualCaption(),
                draft.visualCitationIds(),
                List.of(
                        new StepDraft(
                                "模型改写了准备",
                                TeachingMove.DO,
                                "这句改写不应覆盖原本合规的准备说明。",
                                List.of(chunkId),
                                List.of("shared-area"),
                                null),
                        new StepDraft(
                                "检查后续条件",
                                TeachingMove.CHECK,
                                "每轮结束时检查规则书所列的结束条件；未满足就开始下一轮。",
                                List.of(chunkId),
                                List.of("ending-check"),
                                null)));

        SectionDraft merged = validator.mergeRepairPreservingValidatedFields(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                draft,
                repaired);
        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                merged,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(section.steps()).hasSize(2);
        assertThat(section.steps().getFirst().heading()).isEqualTo("摆好公共区域");
        assertThat(section.steps().getFirst().text()).isEqualTo(groundedText);
        assertThat(section.steps().get(1).heading()).isEqualTo("检查后续条件");
    }

    @Test
    void agentCanOmitOneInvalidOptionalStepWhenSeveralStepsShareTheSameUnit() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID unknownChunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        TeachingPlan plan = plan(versionId);
        SectionDraft original = new SectionDraft(
                "执行完整回合",
                VisualKind.FLOW_DIAGRAM,
                "按顺序完成有依据的回合步骤。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "选择行动",
                                TeachingMove.DO,
                                "轮到你时选择一项可用行动。",
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null),
                        new StepDraft(
                                "可选算例",
                                TeachingMove.EXAMPLE,
                                "这个算例来自未检索到的材料。",
                                List.of(unknownChunkId),
                                List.of("turn-flow"),
                                null),
                        new StepDraft(
                                "交给下一位",
                                TeachingMove.FLOW,
                                "完整结算后把回合交给下一位玩家。",
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null)));
        SectionRequest request = unitRequest(
                chunkId,
                List.of(new TeachingUnitInput(
                        "turn-flow", List.of("Setup and turns"), List.of(chunkId))));
        SectionDraft repaired = new SectionDraft(
                original.title(),
                original.visualKind(),
                original.visualCaption(),
                original.visualCitationIds(),
                List.of(
                        new StepDraft(
                                "选择行动",
                                TeachingMove.DO,
                                "模型不应覆盖已经验证的第一步。",
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null),
                        new StepDraft(
                                "交给下一位",
                                TeachingMove.FLOW,
                                "模型不应覆盖已经验证的最后一步。",
                                List.of(chunkId),
                                List.of("turn-flow"),
                                null)));

        SectionDraft merged = validator.mergeRepairPreservingValidatedFields(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                original,
                repaired);
        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                merged,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.steps()).extracting(step -> step.heading())
                .containsExactly("选择行动", "交给下一位");
        assertThat(section.steps()).extracting(step -> step.text())
                .containsExactly(
                        "轮到你时选择一项可用行动。",
                        "完整结算后把回合交给下一位玩家。");
    }

    @Test
    void acceptsASourcedUnitWhenItsMissingSiblingHasNoOwnedEvidence() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        SectionRequest request = unitRequest(
                chunkId,
                List.of(
                        new TeachingUnitInput(
                                "sourced-turn",
                                List.of("Setup and turns"),
                                List.of(chunkId),
                                List.of(SourceCoverageRole.CORE_LOOP),
                                SourceCoverageAvailability.SOURCED),
                        new TeachingUnitInput(
                                "missing-ending",
                                List.of("External ending procedure"),
                                List.of(),
                                List.of(SourceCoverageRole.ENDING),
                                SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE)));
        SectionDraft draft = new SectionDraft(
                "完成有来源的回合",
                VisualKind.FLOW_DIAGRAM,
                "按当前来源完成回合行动。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "完成当前行动",
                        TeachingMove.DO,
                        "轮到你时选择一项可用行动，并完成它的结算。",
                        List.of(chunkId),
                        List.of("sourced-turn"),
                        null)));
        TeachingPlan plan = plan(versionId);

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                request,
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.steps()).singleElement()
                .extracting(step -> step.heading())
                .isEqualTo("完成当前行动");
    }

    @Test
    void rejectsAnUnresolvedUnitBeforeItCanBecomePlayerFacingContent() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = textEvidence(chunkId, versionId);
        SectionRequest request = unitRequest(
                chunkId,
                List.of(new TeachingUnitInput(
                        "unresolved-ending",
                        List.of("Unresolved ending relation"),
                        List.of(),
                        List.of(SourceCoverageRole.ENDING),
                        SourceCoverageAvailability.UNRESOLVED)));
        SectionDraft invented = new SectionDraft(
                "未解析的结束流程",
                VisualKind.FLOW_DIAGRAM,
                "把附近证据当作结束条件。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "检查结束条件",
                        TeachingMove.CHECK,
                        "按附近段落检查游戏是否结束。",
                        List.of(chunkId),
                        List.of("unresolved-ending"),
                        null)));
        TeachingPlan plan = plan(versionId);

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        request,
                        invented,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved", "cannot be presented as a rule claim");
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "setup",
                        "开局准备",
                        "Explain how to place the central board before the first turn.",
                        true,
                        true,
                        List.of("central board setup"),
                        List.of("setup"))),
                "player",
                Instant.now());
    }

    private TeachingPlan scoringPlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "resource-resolution",
                        "结算资源",
                        "Teach the source-grounded resource resolution.",
                        true,
                        false,
                        List.of("resource award"),
                        List.of("scoring"))),
                "player",
                Instant.now());
    }

    private SectionRequest request(UUID chunkId) {
        return new SectionRequest(
                "setup",
                "开局准备",
                "Explain how to place the central board before the first turn.",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(
                        chunkId,
                        "SETUP",
                        "Central board",
                        "Place the central board in the middle of the table before the first turn.",
                        4,
                        4)),
                List.of(new PageImageInput(4, "image/jpeg", new byte[] {1}, 1_000, 1_000)),
                List.of("central board setup"),
                "player",
                "完整章节分工");
    }

    private SectionRequest textRequest(UUID chunkId) {
        return new SectionRequest(
                "setup",
                "开局准备",
                "Explain setup, turn flow, and when play continues.",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(
                        chunkId,
                        "SETUP",
                        "Setup and turns",
                        "Place the shared area in reach. Each player takes their components. On your turn, choose an available action and resolve it before play passes. At the end of a round, check the stated end condition; otherwise begin another round.",
                        4,
                        4)),
                List.of(),
                List.of("setup and turn flow"),
                "player",
                "完整章节分工");
    }

    private SectionRequest scoringRequest(UUID chunkId) {
        return new SectionRequest(
                "resource-resolution",
                "结算资源",
                "Teach the source-grounded resource resolution.",
                List.of("scoring"),
                List.of(),
                List.of(new EvidenceInput(
                        chunkId,
                        "SCORING",
                        "Resource award",
                        "Gain 1 resource when the condition is met.",
                        8,
                        8)),
                List.of(),
                List.of("resource award"),
                "player",
                "完整章节分工");
    }

    private SectionRequest unitRequest(UUID chunkId, List<TeachingUnitInput> units) {
        return new SectionRequest(
                "setup",
                "开局准备",
                "Explain the Agent-planned units in a source-grounded order.",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(
                        chunkId,
                        "SETUP",
                        "Setup and turns",
                        "Place the shared area in reach. Each player takes their components. On your turn, choose an available action and resolve it before play passes. At the end of a round, check the stated end condition; otherwise begin another round.",
                        4,
                        4)),
                List.of(),
                List.of("Agent-planned units"),
                units,
                "player",
                "完整章节分工");
    }

    private RuleEvidence textEvidence(UUID chunkId, UUID versionId) {
        return new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup and turns",
                "Place the shared area in reach. Each player takes their components. On your turn, choose an available action and resolve it before play passes. At the end of a round, check the stated end condition; otherwise begin another round.",
                4,
                4);
    }
}
