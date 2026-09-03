package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.application.CitationScopeVerifier;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSectionCandidateValidatorTest {

    @Test
    void publishesExactNaturalTextWhenEveryCitationBelongsToTheRequest() {
        UUID versionId = UUID.randomUUID();
        UUID citation = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                citation, versionId, "RULE", "Repair", "Spend an action to repair a damaged system.", 8, 8);
        SectionRequest request = new SectionRequest(
                "repair",
                "Repair systems",
                "Explain repairs.",
                List.of(),
                List.of(new EvidenceInput(citation, "RULE", "Repair", evidence.excerpt(), 8, 8)));
        String naturalText = "系统受损时，你可以花一次行动修复它；若眼下更需要火力，也可以稍后再处理。";
        SectionDraft draft = new SectionDraft(
                "修复系统",
                List.of(new StepDraft("选择时机", TeachingMove.DO, naturalText, List.of(citation))));

        var published = new TeachingSectionCandidateValidator(new CitationScopeVerifier()).validate(
                plan(versionId), planned(), List.of(evidence), request, draft, EvidenceStatus.CITED_DRAFT);

        assertThat(published.title()).isEqualTo("修复系统");
        assertThat(published.steps().getFirst().text()).isEqualTo(naturalText);
        assertThat(published.steps().getFirst().sourceChunkIds()).containsExactly(citation);
    }

    @Test
    void rejectsACitationThatWasRetrievedButNotGrantedToThisModelRequest() {
        UUID versionId = UUID.randomUUID();
        UUID allowed = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        RuleEvidence allowedEvidence = new RuleEvidence(
                allowed, versionId, "RULE", "Turn", "Take one action.", 4, 4);
        RuleEvidence outsideEvidence = new RuleEvidence(
                outside, versionId, "RULE", "Secret", "Unrelated rule.", 12, 12);
        SectionRequest request = new SectionRequest(
                "turn",
                "Turn",
                "Take a turn.",
                List.of(),
                List.of(new EvidenceInput(allowed, "RULE", "Turn", allowedEvidence.excerpt(), 4, 4)));
        SectionDraft draft = new SectionDraft(
                "Turn",
                List.of(new StepDraft("Act", TeachingMove.DO, "Use unrelated evidence.", List.of(outside))));

        assertThatThrownBy(() -> new TeachingSectionCandidateValidator(new CitationScopeVerifier()).validate(
                        plan(versionId),
                        planned(),
                        List.of(allowedEvidence, outsideEvidence),
                        request,
                        draft,
                        EvidenceStatus.CITED_DRAFT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Learn.",
                List.of(planned()),
                "owner",
                Instant.EPOCH);
    }

    private TeachingPlan.PlannedSection planned() {
        return new TeachingPlan.PlannedSection(
                1, "repair", "Repair", "Explain repairs.", true, false,
                List.of("repair"), List.of(), List.of(8));
    }
}
