package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSectionDraftComposerTest {

    @Test
    void givesTheModelVisualPageFactsAlongsideTheCitedSourceImage() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<TeachingLessonModel.SectionRequest> requests = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                requests.add(request);
                return visualDraft(chunkId);
            }
        };
        VisualRulebookPageFacts facts = pageFacts(4);
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), new ImmediateAuditedAgentInvocations(), facts);
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                new TeachingPacingPolicy.SectionPacing(60, 2),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                true);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.pageImages()).extracting(TeachingLessonModel.PageImageInput::pageNumber).containsExactly(4);
            assertThat(request.evidence()).singleElement().satisfies(source ->
                    assertThat(source.excerpt())
                            .contains("Visual presentation data only", "Cataloged visual anchors")
                            .doesNotContain("The central board shows the shared setup area"));
        });
        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(candidate.section().visualSourcePages()).containsExactly(4);
    }

    @Test
    void fallsBackToCitedTextWhenOnlyVisualCompositionIsUnavailable() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<TeachingLessonModel.SectionRequest> requests = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                requests.add(request);
                if (!request.pageImages().isEmpty()) {
                    throw new IllegalStateException("vision provider unavailable");
                }
                return textDraft(chunkId);
            }
        };
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model,
                new PolicyEvidenceVerifier(),
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                new TeachingPacingPolicy.SectionPacing(60, 2),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                true);

        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().pageImages()).isNotEmpty();
        assertThat(requests.getLast().pageImages()).isEmpty();
        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(candidate.section().steps()).singleElement().satisfies(step ->
                assertThat(step.text()).contains("主棋盘放在桌面中央"));
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
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

    private RuleEvidence evidence(UUID chunkId, UUID versionId) {
        return new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle of the table before the first turn.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_000, 1_000)));
    }

    private SectionDraft visualDraft(UUID chunkId) {
        return new SectionDraft(
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
    }

    private SectionDraft textDraft(UUID chunkId) {
        return new SectionDraft(
                "摆好中央展示区",
                VisualKind.REFERENCE_CARD,
                "先摆好主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "放置主棋盘",
                        TeachingMove.DO,
                        "把主棋盘放在桌面中央。",
                        List.of(chunkId))));
    }

    private VisualRulebookPageFacts pageFacts(int pageNumber) {
        VisualRulebookPageFacts.PageFact fact = new VisualRulebookPageFacts.PageFact(
                pageNumber,
                "Central board",
                "The central board shows the shared setup area.",
                List.of("board"),
                List.of(new VisualRulebookPageFacts.VisualAnchor(
                        "table layout",
                        "中央设置区",
                        "主棋盘位于资源供应区和卡牌行之间。",
                        120,
                        180,
                        620,
                        480)));
        return new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.contains(pageNumber) ? List.of(fact) : List.of();
            }
        };
    }
}
