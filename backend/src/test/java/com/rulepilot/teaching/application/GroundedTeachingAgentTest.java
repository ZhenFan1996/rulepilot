package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroundedTeachingAgentTest {

    @Test
    void retrievesVersionScopedEvidenceAndPersistsValidatedStepCitations() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit evidence = evidence(chunkId, versionId);
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> {
            assertThat(requestedVersion).isEqualTo(versionId);
            assertThat(options.sectionTypes()).containsExactly("SETUP");
            return List.of(new HybridEvidenceHit(evidence, 0.02, 1, null, true));
        };
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "三步完成开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(new TeachingLessonModel.StepDraft("将棋盘放在桌面中央。", List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(retrieval, model, 4);

        var lesson = agent.create(plan(versionId));

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourcePages()).containsExactly(2, 3);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds()).containsExactly(chunkId);
    }

    @Test
    void rejectsModelStepsThatCiteEvidenceOutsideTheRetrievedScope() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit evidence = evidence(UUID.randomUUID(), versionId);
        HybridRuleSearch retrieval = (requestedVersion, query, options) ->
                List.of(new HybridEvidenceHit(evidence, 0.02, 1, null, true));
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(new TeachingLessonModel.StepDraft("捏造的步骤", List.of(UUID.randomUUID()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(retrieval, model, 4);

        var lesson = agent.create(plan(versionId));

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds()).isEmpty();
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).doesNotContain("捏造");
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
                List.of(new PlannedSection(1, TeachingSectionType.SETUP, true, true, List.of(2, 3), List.of())),
                "player",
                Instant.now());
    }

    private RuleEvidenceHit evidence(UUID chunkId, UUID versionId) {
        return new RuleEvidenceHit(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the center of the table.",
                2,
                3,
                0.9);
    }
}
