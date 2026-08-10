package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSectionCandidateValidatorTest {

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
        TeachingSectionCandidateValidator validator = new TeachingSectionCandidateValidator(new PolicyEvidenceVerifier());
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
}
