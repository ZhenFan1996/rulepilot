package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleOptionRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRuleOptionResolverTest {

    private final AnswerRuleOptionResolver resolver = new AnswerRuleOptionResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void detectsPlayerChoiceQuestionsWithoutHijackingVisualObservationQuestions() {
        assertThat(AnswerRuleOptionResolver.asksForOptions("What can I do with unresolved cards?")).isTrue();
        assertThat(AnswerRuleOptionResolver.asksForOptions("What special options do I have?")).isTrue();
        assertThat(AnswerRuleOptionResolver.asksForOptions("What types of cards are there?")).isTrue();
        assertThat(AnswerRuleOptionResolver.asksForOptions("Where can I recruit a card from?")).isTrue();
        assertThat(AnswerRuleOptionResolver.asksForOptions("What can you see in the diagram?")).isFalse();
        assertThat(AnswerRuleOptionResolver.asksForOptions("Where can I place this token?")).isFalse();
    }

    @Test
    void listsAllThreeRecruitSourcesAndTheirDifferentAfterEffects() {
        String evidence = "You must recruit one card. You may recruit in one of three ways: "
                + "Take any card from the Park. Immediately draw a new card from the Park deck to replace it. "
                + "Take any card from any rival player's Yard. They do not draw a new card. "
                + "Draw a card from the Park deck.";

        var options = List.of(
                option("Take from the Park", "A card is available in the Park",
                        "Take it and immediately replace it from the Park deck", "SOURCE_SELECTION",
                        "You must choose exactly one of the three recruit sources"),
                option("Take from a rival player's Yard", "A card is available in any rival player's Yard",
                        "Take it; that rival does not draw a replacement", "SOURCE_SELECTION",
                        "You must choose exactly one of the three recruit sources"),
                option("Draw from the Park deck", "The Park deck can be drawn from",
                        "Draw its top card", "SOURCE_SELECTION",
                        "You must choose exactly one of the three recruit sources"));

        assertThat(resolver.resolve(request("Where can I recruit a card from?", evidence), draft(options)))
                .hasSize(3)
                .extracting(result -> result.basis())
                .containsOnly(RuleOptionBasis.SOURCE_SELECTION);
    }

    @Test
    void listsIntrigueTypesWithDistinctTimingWindows() {
        String evidence = "A player who is passed must give the Alliance token to the opponent. "
                + "There are three types of Intrigue cards: Plot, Combat, and Endgame. "
                + "You may play a Plot Intrigue card any time during one of your Agent or Reveal turns. "
                + "You may play a Combat Intrigue card only during Combat. "
                + "You may play an Endgame Intrigue card only at the end of the game.";
        var options = List.of(
                option("Plot", "During one of your Agent or Reveal turns", "Play the card", "TIMING_CATALOG",
                        "Each Intrigue type has its own play window"),
                option("Combat", "Only during Combat", "Play the card", "TIMING_CATALOG",
                        "Each Intrigue type has its own play window"),
                option("Endgame", "Only at the end of the game", "Play the card", "TIMING_CATALOG",
                        "Each Intrigue type has its own play window"));

        assertThat(resolver.resolve(request(
                        "What types of Intrigue cards are there, and when can each be played?", evidence),
                draft(options))).extracting(result -> result.optionName())
                .containsExactly("Plot", "Combat", "Endgame");
    }

    @Test
    void preservesRepeatabilityForAlternativeUnresolvedCardUses() {
        String evidence = "The following options are available for unresolved cards. "
                + "Discard to get one Agent back: discard any 1 unresolved card and return any one Agent. "
                + "You may do this action multiple times. Discard to get an effect from the Street: "
                + "discard any 2 unresolved cards and resolve any one effect in the Street. "
                + "You may do this action multiple times.";
        var options = List.of(
                option("Discard to get one Agent back", "Discard 1 unresolved card",
                        "Return any one Agent; you may repeat this action", "ALTERNATIVE_ACTION",
                        "Choose either special use for unresolved cards during the Action Phase"),
                option("Discard to get an effect from the Street", "Discard 2 unresolved cards",
                        "Resolve any one Street effect; you may repeat this action", "ALTERNATIVE_ACTION",
                        "Choose either special use for unresolved cards during the Action Phase"));

        assertThat(resolver.resolve(request(
                        "What can I do with unresolved cards during my Action Phase?", evidence), draft(options)))
                .hasSize(2);
    }

    @Test
    void rejectsOmittedExplicitOptionAndProseOnlyAnswer() {
        String evidence = "There are three types of Intrigue cards: Plot, Combat, and Endgame.";
        var incomplete = List.of(
                option("Plot", "During an Agent turn", "Play it", "TIMING_CATALOG", "Use its play window"),
                option("Combat", "During Combat", "Play it", "TIMING_CATALOG", "Use its play window"));

        assertThatThrownBy(() -> resolver.resolve(
                        request("What types of Intrigue cards are there?", evidence), draft(incomplete)))
                .hasMessageContaining("explicit count");
        assertThatThrownBy(() -> resolver.resolve(
                        request("What types of Intrigue cards are there?", evidence), draft(List.of())))
                .hasMessageContaining("omitted");
    }

    @Test
    void rejectsDuplicateUnsupportedAndNonMandatoryChoiceClaims() {
        String evidence = "You must recruit one card in one of three ways: Park, Yard, or Park deck.";
        var duplicate = List.of(
                option("Park", "Available", "Take it", "SOURCE_SELECTION", "Choose exactly one source"),
                option("Park", "Available", "Take it", "SOURCE_SELECTION", "Choose exactly one source"),
                option("Yard", "Available", "Take it", "SOURCE_SELECTION", "Choose exactly one source"));
        assertThatThrownBy(() -> resolver.resolve(request("Where can I recruit?", evidence), draft(duplicate)))
                .hasMessageContaining("unique");

        var notMandatory = List.of(
                option("Park", "Available", "Take it", "SOURCE_SELECTION", "Choose one source"),
                option("Yard", "Available", "Take it", "SOURCE_SELECTION", "Choose one source"),
                option("Park deck", "Available", "Draw 4 cards", "SOURCE_SELECTION", "Choose one source"));
        assertThatThrownBy(() -> resolver.resolve(request("Where can I recruit?", evidence), draft(notMandatory)))
                .hasMessageContaining("numeric");
    }

    private RuleOptionRequest option(
            String name, String availability, String result, String basis, String selectionRule) {
        return new RuleOptionRequest(
                "The available rule choices", selectionRule, name, availability, result, basis,
                List.of(citationId));
    }

    private ModelRequest request(String question, String evidence) {
        return new ModelRequest(
                question, QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Options", evidence, 1, 1)));
    }

    private ModelDraft draft(List<RuleOptionRequest> options) {
        return new ModelDraft(
                true, null, "These are the available options.", "Choose according to the cited rule.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), options);
    }
}
