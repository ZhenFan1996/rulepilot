package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonQualityEvaluatorTest {

    @Test
    void evaluatesDynamicCoverageTagsWithoutRequiringFixedChapterNames() {
        UUID versionId = UUID.randomUUID();
        var outline = new OutlineDraft("SETI", "Search for life.", List.of(
                topic("table", "Build the solar system", List.of("setup"), "setup"),
                topic("turn", "Launch and scan", List.of("core_loop"), "turn"),
                topic("finish", "End round five and score", List.of("end", "scoring"), "scoring")));
        var plan = new TeachingPlanFactory().create(versionId, 4, 3, 30, "player", outline);
        var lesson = new GroundedTeachingAgent(
                        request -> "turn".equals(request.query())
                                ? List.of()
                                : List.of(new RuleEvidence(
                                        UUID.randomUUID(), versionId, "GENERAL", request.query(),
                                        "A directly supported rule for " + request.query(), 2, 2)),
                        new FakeTeachingLessonModel(),
                        new PolicyEvidenceVerifier(),
                        acceptedCritic(),
                        new ImmediateAuditedAgentInvocations(),
                        24)
                .create(plan, UUID.randomUUID());

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.CITATION_SUPPORT)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.PASS);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.PLAYER_COUNT_SCOPE)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.NOT_EVALUATED);
    }

    private TopicDraft topic(String key, String title, List<String> tags, String query) {
        return new TopicDraft(key, title, "Explain " + title, true, false, List.of(query), tags);
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }
}
