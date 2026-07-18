package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.ingestion.RuleStructureCatalog.SectionView;
import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonQualityEvaluatorTest {

    @Test
    void blocksIncompleteLessonsWithoutTreatingUnknownScopeAsPassed() {
        var structure = new StructureView(
                List.of(
                        new SectionView("OBJECTIVE", "目标", true, "Win with points.", List.of(1)),
                        new SectionView("SETUP", "Setup", true, "Deal three cards.", List.of(2)),
                        new SectionView("SCORING", "计分", true, "Each coin scores one point.", List.of(8))),
                3,
                9);
        UUID versionId = UUID.randomUUID();
        var plan = new TeachingPlanFactory().create(versionId, 4, 3, 30, "player", structure);
        AssistantReadTools retrieval = request -> structure.sections().stream()
                .filter(section -> section.present() && request.sectionTypes().contains(section.type()))
                .map(section -> new RuleEvidence(
                        UUID.randomUUID(),
                        versionId,
                        section.type(),
                        section.label(),
                        section.content(),
                        section.pageNumbers().getFirst(),
                        section.pageNumbers().getLast()))
                .toList();
        var lesson = new GroundedTeachingAgent(
                        retrieval, new FakeTeachingLessonModel(), new PolicyEvidenceVerifier(), acceptedCritic(),
                        new ImmediateAuditedAgentInvocations(), 24)
                .create(plan, UUID.randomUUID());

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(report.score()).isEqualTo(33);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.CITATION_SUPPORT)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.PASS);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.PLAYER_COUNT_SCOPE)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.NOT_EVALUATED);
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }
}
