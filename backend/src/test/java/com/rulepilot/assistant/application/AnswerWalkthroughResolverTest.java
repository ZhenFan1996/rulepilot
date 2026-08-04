package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerWalkthroughResolverTest {

    private final AnswerWalkthroughResolver resolver = new AnswerWalkthroughResolver();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void acceptsSeparatelyCitedRuleOrderAndTeachingOrder() {
        ModelRequest request = request("How do I resolve this procedure?", first, second);
        ModelDraft draft = draft(List.of(
                step("Pay the listed cost.", "The cost is paid before resolving the effect.", "RULE_ORDER", first),
                step("Group the possible outcomes.", "This grouping is only a reading aid.", "EXPLANATION_ORDER", second)));

        var result = resolver.resolve(request, draft);

        assertThat(result).extracting(item -> item.orderBasis()).containsExactly(
                WalkthroughOrderBasis.RULE_ORDER, WalkthroughOrderBasis.EXPLANATION_ORDER);
        assertThat(result.getFirst().citationIds()).containsExactly(first);
        assertThat(result.getLast().citationIds()).containsExactly(second);
    }

    @Test
    void requiresWalkthroughForConcreteEnglishAndChineseProcedureQuestions() {
        assertThat(resolver.requiresWalkthrough(request("How do we resolve this action?", first))).isTrue();
        assertThat(resolver.requiresWalkthrough(request("这个效果具体步骤是什么？", first))).isTrue();
        assertThatThrownBy(() -> resolver.resolve(request("How do I resolve this action?", first), draft(List.of())))
                .hasMessageContaining("omitted a cited walkthrough");
        assertThat(resolver.resolve(request("What does this term mean?", first), draft(List.of()))).isEmpty();
        assertThat(resolver.requiresWalkthrough(request(
                "How do I know whether this icon is active?", first))).isFalse();
    }

    @Test
    void rejectsOutOfScopeEvidenceInvalidBasisAndDuplicateInstructions() {
        UUID outside = UUID.randomUUID();
        ModelRequest request = request("How to perform the action?", first, second);

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Do the action.", "Follow the cited rule.", "RULE_ORDER", outside)))))
                .hasMessageContaining("outside the answer scope");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Do the action.", "Follow the cited rule.", "PAGE_ORDER", first)))))
                .hasMessageContaining("order basis is invalid");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Do the action.", "First explanation.", "RULE_ORDER", first),
                step("Do the action.", "Repeated explanation.", "RULE_ORDER", second)))))
                .hasMessageContaining("duplicate walkthrough instruction");
    }

    @Test
    void boundsStepCountWithoutDependingOnRulebookVocabulary() {
        List<WalkthroughStepRequest> tooMany = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> step("Instruction " + index, "Explanation " + index, "RULE_ORDER", first))
                .toList();

        assertThatThrownBy(() -> resolver.resolve(request("How do I perform the procedure?", first), draft(tooMany)))
                .hasMessageContaining("too many walkthrough steps");
    }

    @Test
    void requiresASeparatelyCitedRuleChainForWhyAnswersAcrossTerminology() {
        ModelRequest archive = request(
                "Why may I enter the archive?", LearningIntent.WHY,
                evidence(first, "Before entering the archive, spend 2 seals."),
                evidence(second, "After paying 2 seals, place the envoy in the archive."));
        ModelDraft archiveDraft = draft(List.of(
                step("Spend 2 seals.", "The payment is required before entry.", "RULE_ORDER", first),
                step("Place the envoy in the archive.", "This follows after the required payment.", "RULE_ORDER", second)));
        assertThat(resolver.resolve(archive, archiveDraft)).hasSize(2);

        ModelRequest greenhouse = request(
                "Why does the harvest advance?", LearningIntent.WHY,
                evidence(first, "If every bed is watered, complete the irrigation check."),
                evidence(second, "After the irrigation check, advance the harvest marker."));
        ModelDraft greenhouseDraft = draft(List.of(
                step("Complete the irrigation check.", "Every bed must first be watered.", "RULE_ORDER", first),
                step("Advance the harvest marker.", "The rule places this after the check.", "RULE_ORDER", second)));
        assertThat(resolver.resolve(greenhouse, greenhouseDraft)).hasSize(2);
    }

    @Test
    void rejectsMissingTeachingOrderAndUncitedNumberInWhyChains() {
        ModelRequest request = request(
                "Why does this happen?", LearningIntent.WHY,
                evidence(first, "Before resolving the gate, pay 2 keys."),
                evidence(second, "After payment, open the gate. A separate reward grants 4 coins."));

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Pay 2 keys.", "This is required first.", "RULE_ORDER", first)))))
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Pay 2 keys.", "This is required first.", "EXPLANATION_ORDER", first),
                step("Open the gate.", "This follows payment.", "RULE_ORDER", second)))))
                .hasMessageContaining("rule order");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Pay 4 keys.", "This is required first.", "RULE_ORDER", first),
                step("Open the gate.", "This follows payment.", "RULE_ORDER", second)))))
                .hasMessageContaining("numeric");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                step("Pay 2 keys.", "Pay 2 keys.", "RULE_ORDER", first),
                step("Open the gate.", "This follows payment.", "RULE_ORDER", second)))))
                .hasMessageContaining("repeats");
    }

    private ModelRequest request(String question, UUID... evidenceIds) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                java.util.Arrays.stream(evidenceIds)
                        .map(id -> new EvidenceInput(id, "PROCEDURE", "Procedure", "Cited procedure evidence.", 2, 2))
                        .toList());
    }

    private ModelRequest request(String question, LearningIntent intent, EvidenceInput... evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, intent, PlayerLocale.EN),
                List.of(evidence));
    }

    private EvidenceInput evidence(UUID id, String excerpt) {
        return new EvidenceInput(id, "RULE", "Rule dependency", excerpt, 2, 2);
    }

    private ModelDraft draft(List<WalkthroughStepRequest> steps) {
        List<UUID> citations = steps.stream().flatMap(step -> step.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(first);
        return new ModelDraft(
                true, null, "Follow the cited procedure.", "Use the steps below.", citations, List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), steps);
    }

    private WalkthroughStepRequest step(
            String instruction, String explanation, String orderBasis, UUID citationId) {
        return new WalkthroughStepRequest(instruction, explanation, orderBasis, List.of(citationId));
    }
}
