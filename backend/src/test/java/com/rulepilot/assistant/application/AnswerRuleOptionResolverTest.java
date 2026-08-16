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
import com.rulepilot.assistant.RuleAnswerModel.RuleOptionRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRuleOptionResolverTest {

    private final AnswerRuleOptionResolver resolver = new AnswerRuleOptionResolver();
    private final UUID citation = UUID.randomUUID();

    @Test
    void validatesOneCoherentModelSelectedOptionSet() {
        var options = List.of(
                option("Park", "A card is available in the Park", "Take the card"),
                option("Yard", "A card is available in a rival Yard", "Take the card"),
                option("Deck", "The Park deck is available", "Draw its top card"));

        assertThat(resolver.resolve(request(AnswerAid.OPTIONS), draft(options)))
                .hasSize(3)
                .extracting(result -> result.basis())
                .containsOnly(RuleOptionBasis.SOURCE_SELECTION);
    }

    @Test
    void routesByAcceptedAidAndRequiresTwoToEightOptions() {
        assertThat(resolver.requiresRuleOptions(request(AnswerAid.OPTIONS))).isTrue();
        assertThat(resolver.requiresRuleOptions(request(AnswerAid.NONE))).isFalse();
        assertThat(resolver.resolve(request(AnswerAid.OPTIONS), draft(List.of()))).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.OPTIONS), draft(List.of(option("Only", "Available", "Take it")))))
                .hasMessageContaining("count");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.NONE),
                        draft(List.of(option("A", "Available", "Take A"), option("B", "Available", "Take B")))))
                .hasMessageContaining("not selected");
    }

    @Test
    void rejectsDuplicateNamesAndMixedChoiceSetsWithoutReadingEvidenceSemantics() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.OPTIONS),
                        draft(List.of(
                                option("Park", "Available", "Take it"),
                                option("park", "Available", "Take it")))))
                .hasMessageContaining("unique");

        RuleOptionRequest otherContext = new RuleOptionRequest(
                "A different decision",
                "Choose exactly one source",
                "Deck",
                "Available",
                "Draw it",
                "SOURCE_SELECTION",
                List.of(citation));
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.OPTIONS),
                        draft(List.of(option("Park", "Available", "Take it"), otherContext))))
                .hasMessageContaining("coherent");
    }

    @Test
    void rejectsEvidenceOutsideThePublishedAnswerScope() {
        RuleOptionRequest outside = new RuleOptionRequest(
                "Recruit one card",
                "Choose exactly one source",
                "Park",
                "Available",
                "Take it",
                "SOURCE_SELECTION",
                List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.OPTIONS), draft(List.of(outside, option("Deck", "Available", "Draw")))))
                .hasMessageContaining("outside the answer scope");
    }

    private RuleOptionRequest option(String name, String availability, String result) {
        return new RuleOptionRequest(
                "Recruit one card",
                "Choose exactly one source",
                name,
                availability,
                result,
                "SOURCE_SELECTION",
                List.of(citation));
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Arbitrary wording that does not select a presentation shape.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citation, "RULE", "Options", "Three source rules.", 1, 1)),
                Set.of(EvidenceNeed.COMPLETE_LIST),
                aid);
    }

    private ModelDraft draft(List<RuleOptionRequest> options) {
        return new ModelDraft(
                true, null, "Options", "Choose from the cited set.",
                List.of(citation), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), options);
    }
}
