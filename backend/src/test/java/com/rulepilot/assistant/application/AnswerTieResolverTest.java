package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTieRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerTieResolverTest {

    @Test
    void distinguishesDrawingACardFromAGameEndingInADraw() {
        assertThat(AnswerTieResolver.asksForTieResolution("When do players draw a card?")).isFalse();
        assertThat(AnswerTieResolver.asksForTieResolution("What happens if the game ends in a draw?")).isTrue();
    }

    private final AnswerTieResolver resolver = new AnswerTieResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void preservesEveryOrderedCriterionAndTheStillTiedOutcome() {
        RuleTieRequest tie = new RuleTieRequest(
                "Players are tied for the most treasure.",
                List.of(
                        "Compare total difficulty of collected treasure cards.",
                        "If still tied, compare total hero cost.",
                        "If still tied, compare gold."),
                "If still tied after gold, the tied players share the win.",
                "ORDERED_TIEBREAKERS",
                List.of(citationId));

        var result = resolver.resolve(request("How is the tie broken?"), draft(tie));

        assertThat(result).singleElement().satisfies(resolution -> {
            assertThat(resolution.basis()).isEqualTo(TieResolutionBasis.ORDERED_TIEBREAKERS);
            assertThat(resolution.resolutionSteps()).hasSize(3);
            assertThat(resolution.finalOutcome()).contains("share the win");
        });
    }

    @Test
    void rejectsProseOnlyTieAnswerAndSingleStepOrderedBasis() {
        assertThatThrownBy(() -> resolver.resolve(request("平局到底怎么判？"), draft(null)))
                .hasMessageContaining("omitted");

        RuleTieRequest oneStep = new RuleTieRequest(
                "Players tie.", List.of("Compare gold."), "Highest gold wins.",
                "ORDERED_TIEBREAKERS", List.of(citationId));
        assertThatThrownBy(() -> resolver.resolve(request("Who wins a tie?"), draft(oneStep)))
                .hasMessageContaining("at least two");
    }

    @Test
    void validatesRankRewardAndPositionalMeaningInsteadOfTrustingTheEnum() {
        RuleTieRequest rankShift = new RuleTieRequest(
                "Players tie for combat ranks.",
                List.of("A tie for first receives the second reward.", "A tie for third receives nothing."),
                "Each covered rank uses the stated lower reward or no reward.",
                "RANK_REWARD_SHIFT",
                List.of(citationId));
        assertThat(resolver.resolve(request("What happens when combat strength is tied?"), draft(rankShift)))
                .singleElement().extracting(resolution -> resolution.basis())
                .isEqualTo(TieResolutionBasis.RANK_REWARD_SHIFT);

        RuleTieRequest positional = new RuleTieRequest(
                "Players remain tied after two scoring criteria.",
                List.of("Compare location cards.", "Then compare VP from ships and bandits."),
                "If still tied, the player closest to the starting player wins.",
                "POSITIONAL_PRIORITY",
                List.of(citationId));
        assertThat(resolver.resolve(request("If the scores are tied, who wins?"), draft(positional)))
                .singleElement().extracting(resolution -> resolution.basis())
                .isEqualTo(TieResolutionBasis.POSITIONAL_PRIORITY);

        RuleTieRequest duplicatedPosition = new RuleTieRequest(
                "Players remain tied.",
                List.of("Compare cards.", "If still tied, closest to the starting player wins."),
                "The player closest to the starting player wins.",
                "POSITIONAL_PRIORITY",
                List.of(citationId));
        assertThatThrownBy(() -> resolver.resolve(request("Who wins a tie?"), draft(duplicatedPosition)))
                .hasMessageContaining("final outcome");

        RuleTieRequest inventedPosition = new RuleTieRequest(
                "Players remain tied.", List.of("Compare cards."), "Roll a die.",
                "POSITIONAL_PRIORITY", List.of(citationId));
        assertThatThrownBy(() -> resolver.resolve(request("Who wins a tie?"), draft(inventedPosition)))
                .hasMessageContaining("positional");
    }

    @Test
    void rejectsCitationsOutsideThePublishedAnswerScope() {
        RuleTieRequest outside = new RuleTieRequest(
                "Players tie.", List.of("Compare cards.", "Then compare gold."), "Highest gold wins.",
                "ORDERED_TIEBREAKERS", List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> resolver.resolve(request("How is a tie broken?"), draft(outside)))
                .hasMessageContaining("outside");
    }

    private ModelRequest request(String question) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Ties", "Explicit tie rule", 12, 12)));
    }

    private ModelDraft draft(RuleTieRequest tie) {
        return new ModelDraft(
                true, null, "Use the tie rule.", "Apply every cited step in order.", List.of(citationId), List.of(),
                "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), tie == null ? List.of() : List.of(tie));
    }
}
