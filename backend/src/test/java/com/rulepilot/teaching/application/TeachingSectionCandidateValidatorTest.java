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
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
    void rejectsAnUnsupportedOptionalExampleInsteadOfDeletingModelProse() {
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
        String groundedText = "满足条件时获得1个资源。";
        SectionDraft draft = new SectionDraft(
                "结算资源",
                VisualKind.REFERENCE_CARD,
                "按满足的条件结算资源。",
                List.of(chunkId),
                List.of(
                        new StepDraft("执行结算", TeachingMove.DO, groundedText, List.of(chunkId)),
                        new StepDraft(
                                "可选示例",
                                TeachingMove.EXAMPLE,
                                "例如，结算后总共有2个资源。",
                                List.of(chunkId))));

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        scoringRequest(chunkId),
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException.class)
                .hasMessageContaining("unsupported value", "2");
    }

    @Test
    void stillRejectsAnUnsupportedQuantityInTheOnlyRequiredInstruction() {
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
        SectionDraft unsupported = new SectionDraft(
                "结算资源",
                VisualKind.REFERENCE_CARD,
                "按满足的条件结算资源。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "执行结算",
                        TeachingMove.DO,
                        "满足条件时获得2个资源。",
                        List.of(chunkId))));

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        scoringRequest(chunkId),
                        unsupported,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException.class)
                .hasMessageContaining("unsupported value", "2");
    }

    @Test
    void acceptsAnAgentEnumerationCountOnlyWhenCitedEvidenceListsThatManyItems() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SCORING",
                "Ordered procedure",
                "This consists of the following steps in order: Reveal, Resolve, Set Strength, and Clean Up.",
                8,
                8);
        TeachingPlan plan = scoringPlan(versionId);
        SectionDraft draft = new SectionDraft(
                "按顺序完成结算",
                VisualKind.REFERENCE_CARD,
                "按证据列出的顺序完成结算。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "先看完整流程",
                        TeachingMove.FLOW,
                        "这个流程包含四个步骤：揭示、结算、设定强度、清理。",
                        List.of(chunkId))));

        var section = validator.validate(
                plan,
                plan.sections().getFirst(),
                List.of(evidence),
                scoringRequest(chunkId),
                draft,
                EvidenceStatus.CITED_DRAFT);

        assertThat(section.steps().getFirst().text()).isEqualTo(draft.steps().getFirst().text());
    }

    @Test
    void stillRejectsAnEnumerationCountWhenTheCitedEvidenceDoesNotContainThatManyAlternatives() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SCORING",
                "Stopping condition",
                "If the signal appears, stop the procedure.",
                8,
                8);
        TeachingPlan plan = scoringPlan(versionId);
        SectionDraft draft = new SectionDraft(
                "检查停止条件",
                VisualKind.REFERENCE_CARD,
                "检查停止条件。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "检查两个条件",
                        TeachingMove.CHECK,
                        "这个流程有两个触发条件，满足一个就停止。",
                        List.of(chunkId))));

        assertThatThrownBy(() -> validator.validate(
                        plan,
                        plan.sections().getFirst(),
                        List.of(evidence),
                        scoringRequest(chunkId),
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException.class)
                .hasMessageContaining("unsupported value", "2");
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
    void quantityRepairDiagnosticIdentifiesTheExactPlayerFacingStepWithoutRewritingIt() {
        UUID chunkId = UUID.randomUUID();
        SectionDraft draft = new SectionDraft(
                "结算资源",
                VisualKind.REFERENCE_CARD,
                "按条件结算资源。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "检查本轮总数",
                        TeachingMove.CHECK,
                        "确认本轮是否已经获得2个资源。",
                        List.of(chunkId))));
        var failure = new TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException(
                2, "Quantitative teaching claim at claim position 2 introduces unsupported value(s): [2]");

        String diagnostic = validator.repairDiagnostic(failure, draft);

        assertThat(diagnostic)
                .contains("step heading '检查本轮总数'", chunkId.toString(), "Repair only that field");
        assertThat(draft.steps().getFirst().text()).isEqualTo("确认本轮是否已经获得2个资源。");
    }

    @Test
    void quantityRepairDiagnosticPointsTheAgentToEvidenceContainingEveryClaimQuantity() {
        UUID versionId = UUID.randomUUID();
        UUID currentCitation = UUID.randomUUID();
        UUID partialCandidate = UUID.randomUUID();
        UUID completeCandidate = UUID.randomUUID();
        SectionDraft draft = new SectionDraft(
                "放置奖励",
                VisualKind.REFERENCE_CARD,
                "按当前状态放置奖励。",
                List.of(currentCitation),
                List.of(new StepDraft(
                        "奖励示例",
                        TeachingMove.EXAMPLE,
                        "原有1个奖励，再加入1个后共有2个奖励。",
                        List.of(currentCitation))));
        var failure = new TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException(
                2,
                Set.of("1", "2"),
                Set.of("2"),
                "Quantitative teaching claim at claim position 2 introduces unsupported value(s): [2]");
        RuleEvidence current = new RuleEvidence(
                currentCitation, versionId, "TURN", "Add a reward", "Add 1 reward.", 4, 4);
        RuleEvidence partial = new RuleEvidence(
                partialCandidate, versionId, "TURN", "Two rounds", "After 2 rounds, stop.", 5, 5);
        RuleEvidence complete = new RuleEvidence(
                completeCandidate,
                versionId,
                "TURN",
                "Worked example",
                "There is already 1 reward; after adding another, there are 2 rewards.",
                6,
                6);

        String diagnostic = validator.repairDiagnostic(failure, draft, List.of(current, partial, complete));

        assertThat(diagnostic)
                .contains("Quantity-only candidate citation IDs", completeCandidate.toString())
                .doesNotContain(partialCandidate.toString());
        assertThat(draft.steps().getFirst().text()).isEqualTo("原有1个奖励，再加入1个后共有2个奖励。");
    }

    @Test
    void unitRepairDiagnosticNamesOnlyTheAffectedAgentOwnedUnitAndItsDirectEvidence() {
        UUID directEvidence = UUID.randomUUID();
        SectionDraft draft = new SectionDraft(
                "部署与结算",
                VisualKind.REFERENCE_CARD,
                "先部署，再结算。",
                List.of(directEvidence),
                List.of(
                        new StepDraft(
                                "部署部队",
                                TeachingMove.DO,
                                "把允许的部队部署到冲突区。",
                                List.of(directEvidence),
                                List.of("deployment"),
                                null),
                        new StepDraft(
                                "结算奖励",
                                TeachingMove.DO,
                                "按当前顺序结算奖励。",
                                List.of(directEvidence),
                                List.of("resolution"),
                                null)));
        var failure = new TeachingPlannedUnitCoveragePolicy.MissingDirectUnitEvidenceException(
                "deployment", "Deploying Troops", Set.of(directEvidence));

        String diagnostic = validator.repairDiagnostic(failure, draft);

        assertThat(diagnostic)
                .contains("affected planned unit is 'deployment'", "部署部队", "Deploying Troops")
                .doesNotContain("结算奖励");
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
