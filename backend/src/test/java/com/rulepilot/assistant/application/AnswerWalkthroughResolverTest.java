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
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnswerWalkthroughResolverTest {

    private final AnswerWalkthroughResolver resolver = new AnswerWalkthroughResolver();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void acceptsSeparatelyCitedRuleAndExplanationOrderSteps() {
        var result = resolver.resolve(request(AnswerAid.WALKTHROUGH, first, second), draft(List.of(
                step("Pay the listed cost.", "The cited rule places payment first.", "RULE_ORDER", first),
                step("Group the outcomes.", "This is a reading aid.", "EXPLANATION_ORDER", second))));

        assertThat(result).extracting(item -> item.orderBasis()).containsExactly(
                WalkthroughOrderBasis.RULE_ORDER, WalkthroughOrderBasis.EXPLANATION_ORDER);
        assertThat(result.getFirst().citationIds()).containsExactly(first);
        assertThat(result.getLast().citationIds()).containsExactly(second);
    }

    @Test
    void followsTheAcceptedAidInsteadOfQuestionKeywords() {
        ModelRequest selected = request(AnswerAid.WALKTHROUGH, first);
        assertThat(resolver.requiresWalkthrough(selected)).isTrue();
        assertThat(resolver.resolve(selected, draft(List.of())))
                .as("a selected presentation aid remains optional when the cited core already answers the player")
                .isEmpty();

        ModelRequest notSelected = request(AnswerAid.NONE, first);
        assertThat(resolver.requiresWalkthrough(notSelected)).isFalse();
        assertThat(resolver.resolve(notSelected, draft(List.of()))).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(notSelected, draft(List.of(
                        step("Act.", "Resolve it.", "RULE_ORDER", first)))))
                .hasMessageContaining("not selected");
    }

    @Test
    void rejectsInvalidBasisOutOfScopeCitationsAndDuplicates() {
        UUID outside = UUID.randomUUID();
        ModelRequest request = request(AnswerAid.WALKTHROUGH, first, second);

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                        step("Act.", "Resolve it.", "RULE_ORDER", outside)))))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                        step("Act.", "Resolve it.", "PAGE_ORDER", first)))))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                        step("Act.", "First.", "RULE_ORDER", first),
                        step(" act. ", "Again.", "RULE_ORDER", second)))))
                .hasMessageContaining("duplicate");
    }

    @Test
    void boundsTheStructuredStepCount() {
        List<WalkthroughStepRequest> tooMany = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> step(
                        "Instruction " + index, "Explanation " + index, "RULE_ORDER", first))
                .toList();

        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.WALKTHROUGH, first), draft(tooMany)))
                .hasMessageContaining("too many walkthrough steps");
    }

    private ModelRequest request(AnswerAid aid, UUID... evidenceIds) {
        return new ModelRequest(
                "Explain the cited procedure.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                Arrays.stream(evidenceIds)
                        .map(id -> new EvidenceInput(id, "PROCEDURE", "Procedure", "Cited procedure.", 2, 2))
                        .toList(),
                Set.of(EvidenceNeed.SEQUENCE),
                aid);
    }

    private ModelDraft draft(List<WalkthroughStepRequest> steps) {
        List<UUID> citations = steps.stream().flatMap(step -> step.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(first);
        return new ModelDraft(
                true, null, "Follow the procedure.", "Use the steps.", citations, List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), steps);
    }

    private WalkthroughStepRequest step(
            String instruction, String explanation, String orderBasis, UUID citationId) {
        return new WalkthroughStepRequest(instruction, explanation, orderBasis, List.of(citationId));
    }
}
