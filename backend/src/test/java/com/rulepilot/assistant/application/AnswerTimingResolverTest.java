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
import com.rulepilot.assistant.RuleAnswerModel.RuleTimingRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnswerTimingResolverTest {

    private final AnswerTimingResolver resolver = new AnswerTimingResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void resolvesACompleteCitedTimingSchema() {
        var result = resolver.resolve(request(AnswerAid.TIMING), draft(List.of(timing(
                "Two effects share a timing window.",
                "Resolve them in the current player's chosen order.",
                "The player taking the current turn.",
                "CURRENT_PLAYER_CHOOSES",
                citationId))));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.basis()).isEqualTo(TimingOrderBasis.CURRENT_PLAYER_CHOOSES);
            assertThat(item.citationIds()).containsExactly(citationId);
        });
    }

    @Test
    void followsTheAcceptedAidRatherThanSimultaneousEffectKeywords() {
        ModelRequest selected = request(AnswerAid.TIMING);
        assertThat(resolver.requiresTiming(selected)).isTrue();
        assertThat(resolver.resolve(selected, draft(List.of()))).isEmpty();

        ModelRequest notSelected = request(AnswerAid.NONE);
        assertThat(resolver.requiresTiming(notSelected)).isFalse();
        assertThat(resolver.resolve(notSelected, draft(List.of()))).isEmpty();
    }

    @Test
    void acceptsSupportedEnumsAndRejectsInvalidStructureOrCitationScope() {
        assertThat(resolver.resolve(request(AnswerAid.TIMING), draft(List.of(timing(
                        "Printed effects.", "Resolve top to bottom.", "Printed order.",
                        "PRINTED_TOP_TO_BOTTOM", citationId)))))
                .singleElement().extracting(item -> item.basis())
                .isEqualTo(TimingOrderBasis.PRINTED_TOP_TO_BOTTOM);
        assertThat(resolver.resolve(request(AnswerAid.TIMING), draft(List.of(timing(
                        "Several actors move.", "Use normal turn order.", "The cited rule.",
                        "NORMAL_TURN_ORDER", citationId)))))
                .singleElement().extracting(item -> item.basis())
                .isEqualTo(TimingOrderBasis.NORMAL_TURN_ORDER);

        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIMING), draft(List.of(timing(
                        "Context", "Order", "Source", "NEWEST_FIRST", citationId)))))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIMING), draft(List.of(timing(
                        "Context", "Order", "Source", "NORMAL_TURN_ORDER", UUID.randomUUID())))))
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsDuplicateContextsAndBoundsTheResolutionCount() {
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIMING), draft(List.of(
                        timing("Same context", "First", "Source", "NORMAL_TURN_ORDER", citationId),
                        timing(" same context ", "Second", "Source", "NORMAL_TURN_ORDER", citationId)))))
                .hasMessageContaining("duplicate");

        List<RuleTimingRequest> tooMany = IntStream.rangeClosed(1, 4)
                .mapToObj(index -> timing(
                        "Context " + index, "Order " + index, "Source " + index,
                        "NORMAL_TURN_ORDER", citationId))
                .toList();
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.TIMING), draft(tooMany)))
                .hasMessageContaining("too many timing resolutions");
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "How are the cited effects ordered?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Timing", "Cited timing rule.", 2, 2)),
                Set.of(EvidenceNeed.SEQUENCE),
                aid);
    }

    private ModelDraft draft(List<RuleTimingRequest> timings) {
        List<UUID> citations = timings.stream().flatMap(item -> item.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(citationId);
        return new ModelDraft(
                true, null, "Order is fixed.", "Use the cited timing rule.", citations,
                List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), timings);
    }

    private RuleTimingRequest timing(
            String context, String order, String source, String basis, UUID citation) {
        return new RuleTimingRequest(context, order, source, basis, List.of(citation));
    }
}
