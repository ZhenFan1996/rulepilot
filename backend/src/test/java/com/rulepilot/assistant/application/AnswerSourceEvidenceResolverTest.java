package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerSourceEvidenceResolverTest {

    private final AnswerSourceEvidenceResolver resolver = new AnswerSourceEvidenceResolver();
    private final UUID citationId = UUID.randomUUID();
    private final ModelRequest request = new ModelRequest(
            "Where does the rulebook say when players draw from the beacon?",
            QuestionType.RULE_QUERY,
            new AnswerContext(null, LearningIntent.SOURCE, PlayerLocale.EN),
            List.of(new EvidenceInput(
                    citationId, "ACTIONS", "Beacon",
                    "When the beacon is lit, each player may draw one card.", 9, 9)));

    @Test
    void selectsTheDirectClauseForApplicationRenderedSourceEvidence() {
        assertThat(resolver.resolve(request, draft(
                "Players draw when the beacon is lit.",
                "This means lighting the beacon is the trigger, and the permission applies to each player.",
                List.of(citationId))))
                .containsExactly(citationId);
        assertThat(AnswerSourceEvidenceResolver.asksForSource("这条规则的原文依据在哪一页？")).isTrue();
    }

    @Test
    void rejectsAPageRedirectInsteadOfAPlayerFacingAnswer() {
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "See the excerpt below.",
                "It contains the rule.",
                List.of(citationId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer instead of redirecting");
    }

    @Test
    void rejectsCitationPaddingAndNonDirectEvidence() {
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        ModelRequest expanded = new ModelRequest(
                request.question(), request.questionType(), request.context(),
                List.of(
                        request.evidence().getFirst(),
                        new EvidenceInput(second, "BACKGROUND", "Theme", "Players gather around the beacon artwork.", 2, 2),
                        new EvidenceInput(third, "BACKGROUND", "History", "The beacon is an ancient landmark.", 3, 3)));
        assertThatThrownBy(() -> resolver.resolve(expanded, draft(
                "Players draw when the beacon is lit.",
                "Lighting it is the trigger for the draw permission.",
                List.of(citationId, second, third))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one or two direct citations");
    }

    @Test
    void rejectsOwnershipScopeAddedOnlyByTheExplanation() {
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "Players draw when the beacon is lit.",
                "This applies to all beacon cards you have during the next round.",
                List.of(citationId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope qualifier");
    }

    @Test
    void rejectsAnInventedPluralForAnOfficialResourceTerm() {
        UUID persuasion = UUID.randomUUID();
        ModelRequest persuasionRequest = new ModelRequest(
                "What does the rule say about unused Persuasion?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.SOURCE, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        persuasion, "RESOURCE", "Persuasion",
                        "Persuasion can never be saved; unused Persuasion is lost.", 11, 11)));

        assertThatThrownBy(() -> resolver.resolve(persuasionRequest, draft(
                "Unused Persuasion is lost.",
                "Persuasions cannot be carried forward.",
                List.of(persuasion))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grammatical number");
    }

    @Test
    void rejectsAnExactTurnEndBoundaryAbsentFromTheClause() {
        UUID persuasion = UUID.randomUUID();
        ModelRequest persuasionRequest = new ModelRequest(
                "What is the exact source for unused Persuasion?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.SOURCE, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        persuasion, "RESOURCE", "Persuasion",
                        "Any Persuasion you don't use during your Reveal turn is lost.", 11, 11)));

        assertThatThrownBy(() -> resolver.resolve(persuasionRequest, draft(
                "Unused Persuasion is lost at the end of your Reveal turn.",
                "The loss happens on completion of that turn.",
                List.of(persuasion))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temporal boundary");
    }

    @Test
    void rejectsAnInventedMandatoryModal() {
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "Players draw when the beacon is lit.",
                "The word may implicitly means every player must draw.",
                List.of(citationId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mandatory modal");
    }

    private ModelDraft draft(String verdict, String explanation, List<UUID> citations) {
        return new ModelDraft(
                true, null, verdict, explanation, citations, List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
