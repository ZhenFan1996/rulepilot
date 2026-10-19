package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.shared.PlayerLocale;
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
    void keepsOriginalPageSelectableWhenOptionalVisualObservationIsUnavailable() {
        UUID version = UUID.randomUUID(), citation = UUID.randomUUID();
        var source = new RuleEvidence(citation, version, "RULE", "Repair", "Read the illustrated repair chart.", 8, 8);
        var request = new TeachingSectionModelRequestFactory().create(plan(version), planned(), List.of(), List.of(source), List.of());
        var image = request.visuals().getFirst();
        var draft = new SectionDraft("Repair", List.of(new StepDraft("Check the chart", TeachingMove.UNDERSTAND,
                "Use the source chart to check the unresolved correspondence.", List.of(citation), List.of(), List.of(image.imageId()))));
        var section = new TeachingSectionCandidateValidator(new CitationScopeVerifier()).validate(
                plan(version), planned(), List.of(source), request, draft, EvidenceStatus.CITED_DRAFT);
        assertThat(section.steps().getFirst().visualFoci()).singleElement().satisfies(focus -> {
            assertThat(focus.pageNumber()).isEqualTo(8);
            assertThat(focus.sourceKind()).isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind.FULL_PAGE);
        });
    }

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
                List.of(new EvidenceInput(citation, "RULE", "Repair", evidence.excerpt(), 8, 8)), PlayerLocale.ZH_CN);
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
                List.of(new EvidenceInput(allowed, "RULE", "Turn", allowedEvidence.excerpt(), 4, 4)), PlayerLocale.ZH_CN);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"offered", "unknown", "wrong-source"})
    void keepsGroundedProseAndAttachesOnlyTheExplicitlySelectedOwnedImage(String selection) {
        UUID version = UUID.randomUUID(), citation = UUID.randomUUID();
        var source = new RuleEvidence(citation, version, "RULE", "Repair", "Spend an action to repair.", 8, 8);
        var focus = new com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus(
                8, "Repair", "An arrow points from a damaged component to an intact component.", 100, 100, 300, 300);
        var images = List.of(new com.rulepilot.teaching.TeachingLessonModel.VisualInput("offered", focus, List.of(citation)),
                new com.rulepilot.teaching.TeachingLessonModel.VisualInput("wrong-source", focus, List.of(UUID.randomUUID())));
        var request = new SectionRequest("repair", "Repair", "Explain repair", List.of(),
                List.of(new EvidenceInput(citation, "RULE", "Repair", source.excerpt(), 8, 8)), images, "owner", PlayerLocale.EN);
        var draft = new SectionDraft("Repair", List.of(new StepDraft("Repair", TeachingMove.DO,
                "Spend an action to repair.", List.of(citation), List.of(), List.of(selection))));
        var section = new TeachingSectionCandidateValidator(new CitationScopeVerifier()).validate(
                plan(version), planned(), List.of(source), request, draft, EvidenceStatus.CITED_DRAFT);
        assertThat(section.steps().getFirst().text()).isEqualTo("Spend an action to repair.");
        assertThat(section.steps().getFirst().visualFoci()).hasSize(selection.equals("offered") ? 1 : 0);
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Learn.",
                List.of(planned()),
                "owner",
                Instant.EPOCH, PlayerLocale.ZH_CN);
    }

    private TeachingPlan.PlannedSection planned() {
        return new TeachingPlan.PlannedSection(
                1, "repair", "Repair", "Explain repairs.", true, false,
                List.of("repair"), List.of(), List.of(8));
    }
}
