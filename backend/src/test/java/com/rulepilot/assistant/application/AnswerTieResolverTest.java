package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTieRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerTieResolverTest {

    private final AnswerTieResolver resolver = new AnswerTieResolver();
    private final UUID citation = UUID.randomUUID();

    @Test
    void preservesEveryStructuredOrderedCriterionAndFinalOutcome() {
        RuleTieRequest tie = new RuleTieRequest(
                "Players tie on treasure.",
                List.of("Compare card difficulty.", "Then compare hero cost.", "Then compare gold."),
                "If still tied, share the win.",
                "ORDERED_TIEBREAKERS",
                List.of(citation));

        assertThat(resolver.resolve(request(AnswerAid.TIE), draft(List.of(tie))))
                .singleElement()
                .satisfies(resolution -> {
                    assertThat(resolution.basis()).isEqualTo(TieResolutionBasis.ORDERED_TIEBREAKERS);
                    assertThat(resolution.resolutionSteps()).hasSize(3);
                    assertThat(resolution.finalOutcome()).contains("share the win");
                });
    }

    @Test
    void routingUsesAnswerAidAndOrderedBasisRequiresAtLeastTwoSteps() {
        assertThat(resolver.requiresTie(request(AnswerAid.TIE))).isTrue();
        assertThat(resolver.requiresTie(request(AnswerAid.NONE))).isFalse();
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIE), draft(List.of())))
                .hasMessageContaining("required");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.NONE),
                        draft(List.of(tie("Context", List.of("A", "B"), "Outcome")))))
                .hasMessageContaining("not selected");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.TIE),
                        draft(List.of(tie("Context", List.of("Compare gold."), "Highest wins.")))))
                .hasMessageContaining("at least two");
    }

    @Test
    void acceptsOtherDeclaredBasesWithoutGuessingTheirMeaningFromProse() {
        RuleTieRequest positional = new RuleTieRequest(
                "Players remain tied.",
                List.of("Compare their positions."),
                "Use the stated positional winner.",
                "POSITIONAL_PRIORITY",
                List.of(citation));

        assertThat(resolver.resolve(request(AnswerAid.TIE), draft(List.of(positional))))
                .singleElement()
                .extracting(result -> result.basis())
                .isEqualTo(TieResolutionBasis.POSITIONAL_PRIORITY);
    }

    @Test
    void rejectsDuplicateContextsAndEvidenceOutsideTheAnswerScope() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.TIE),
                        draft(List.of(
                                tie("Same context", List.of("A", "B"), "C"),
                                tie("same context", List.of("D", "E"), "F")))))
                .hasMessageContaining("duplicate");

        RuleTieRequest outside = new RuleTieRequest(
                "Context", List.of("A", "B"), "Outcome", "ORDERED_TIEBREAKERS",
                List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIE), draft(List.of(outside))))
                .hasMessageContaining("outside");
    }

    private RuleTieRequest tie(String context, List<String> steps, String outcome) {
        return new RuleTieRequest(
                context, steps, outcome, "ORDERED_TIEBREAKERS", List.of(citation));
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Arbitrary wording.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citation, "RULE", "Tie", "Explicit tie rule.", 12, 12)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                aid);
    }

    private ModelDraft draft(List<RuleTieRequest> ties) {
        return new ModelDraft(
                true, null, "Tie ruling.", "Apply cited steps in order.",
                List.of(citation), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), ties);
    }
}
