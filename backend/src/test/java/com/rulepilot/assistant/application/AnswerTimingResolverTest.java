package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTimingRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerTimingResolverTest {

    private final AnswerTimingResolver resolver = new AnswerTimingResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void resolvesCitedCurrentPlayerOrdering() {
        ModelRequest request = request("If two things happen at the same time, who chooses the order?");
        ModelDraft draft = draft(new RuleTimingRequest(
                "Two effects happen at the same time during a player's turn.",
                "Resolve them in the order selected by that player.",
                "The player taking the current turn.",
                "CURRENT_PLAYER_CHOOSES",
                List.of(citationId)));

        var result = resolver.resolve(request, draft);

        assertThat(result).singleElement().satisfies(resolution -> {
            assertThat(resolution.basis()).isEqualTo(TimingOrderBasis.CURRENT_PLAYER_CHOOSES);
            assertThat(resolution.citationIds()).containsExactly(citationId);
        });
    }

    @Test
    void rejectsProseOnlyDirectTimingAnswer() {
        ModelRequest request = request("同时触发时哪个先结算？");

        assertThatThrownBy(() -> resolver.resolve(request, draft(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted");
    }

    @Test
    void rejectsUnsupportedBasisAndOutOfScopeCitation() {
        ModelRequest request = request("What order do simultaneous effects resolve?");
        RuleTimingRequest inventedBasis = new RuleTimingRequest(
                "Two effects coincide.", "Resolve the newer effect first.", "The newest card.",
                "NEWEST_EFFECT_FIRST", List.of(citationId));
        RuleTimingRequest outsideScope = new RuleTimingRequest(
                "Two effects coincide.", "Resolve top to bottom.", "Printed card order.",
                "PRINTED_TOP_TO_BOTTOM", List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> resolver.resolve(request, draft(inventedBasis)))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request, draft(outsideScope)))
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsASectionTitleInPlaceOfTheCurrentPlayerOrderSource() {
        ModelRequest request = request("If two things happen at the same time, who chooses the order?");
        RuleTimingRequest shiftedMeaning = new RuleTimingRequest(
                "Two things happen at the same time.",
                "The player taking their turn chooses the order.",
                "Simultaneous Timing rule",
                "CURRENT_PLAYER_CHOOSES",
                List.of(citationId));

        assertThatThrownBy(() -> resolver.resolve(request, draft(shiftedMeaning)))
                .hasMessageContaining("current-player timing fields");
    }

    @Test
    void acceptsPrintedAndNormalTurnOrderOnlyWhenTheirSourcesStayExplicit() {
        ModelRequest request = request("What order do simultaneous effects resolve?");
        RuleTimingRequest printed = new RuleTimingRequest(
                "A card has two effects with the same timing.",
                "Resolve the effects from top to bottom.",
                "The card's printed top-to-bottom order.",
                "PRINTED_TOP_TO_BOTTOM",
                List.of(citationId));
        RuleTimingRequest turnOrder = new RuleTimingRequest(
                "Multiple pieces must move at once.",
                "Move them one at a time in normal turn order.",
                "The rule mandates normal turn order.",
                "NORMAL_TURN_ORDER",
                List.of(citationId));

        assertThat(resolver.resolve(request, draft(printed))).singleElement()
                .extracting(resolution -> resolution.basis())
                .isEqualTo(TimingOrderBasis.PRINTED_TOP_TO_BOTTOM);
        assertThat(resolver.resolve(request, draft(turnOrder))).singleElement()
                .extracting(resolution -> resolution.basis())
                .isEqualTo(TimingOrderBasis.NORMAL_TURN_ORDER);
    }

    private ModelRequest request(String question) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Timing", "Timing rule text", 2, 2)));
    }

    private ModelDraft draft(RuleTimingRequest timing) {
        return new ModelDraft(
                true, null, "Order is fixed.", "Use the cited timing rule.", List.of(citationId), List.of(),
                "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), timing == null ? List.of() : List.of(timing));
    }
}
