package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.DecisionBranchRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDecisionTableResolverTest {

    private final AnswerDecisionTableResolver resolver = new AnswerDecisionTableResolver();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void acceptsSeparatelyCitedExplicitRuleAndRulebookExample() {
        ModelRequest request = request("What happens in each case?", first, second);
        ModelDraft draft = draft(List.of(
                branch("The player has the required token.", "Resolve the action.", "EXPLICIT_RULE", first),
                branch("In the rulebook's illustrated example.", "The blue player waits.", "RULEBOOK_EXAMPLE", second)));

        var result = resolver.resolve(request, draft);

        assertThat(result).extracting(item -> item.basis()).containsExactly(
                DecisionBranchBasis.EXPLICIT_RULE, DecisionBranchBasis.RULEBOOK_EXAMPLE);
        assertThat(result.getFirst().citationIds()).containsExactly(first);
        assertThat(result.getLast().citationIds()).containsExactly(second);
    }

    @Test
    void followsTheAcceptedAnswerAidInsteadOfInferringFromQuestionWording() {
        assertThat(resolver.requiresDecisionTable(request("What does this icon mean?", first))).isTrue();
        assertThat(resolver.resolve(
                        request("What happens if the supply is empty?", first), draft(List.of())))
                .isEmpty();

        ModelRequest noAid = request(AnswerAid.NONE, "What happens in each case?", first);
        assertThat(resolver.requiresDecisionTable(noAid)).isFalse();
        assertThat(resolver.resolve(noAid, draft(List.of()))).isEmpty();
    }

    @Test
    void rejectsOutOfScopeEvidenceInvalidBasisAndDuplicateConditions() {
        UUID outside = UUID.randomUUID();
        ModelRequest request = request("What happens in each case?", first, second);

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                branch("Supply is empty.", "Do not take an item.", "EXPLICIT_RULE", outside)))))
                .hasMessageContaining("outside the answer scope");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                branch("Supply is empty.", "Do not take an item.", "MODEL_EXAMPLE", first)))))
                .hasMessageContaining("basis is invalid");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                branch("Supply is empty.", "Do not take an item.", "EXPLICIT_RULE", first),
                branch(" supply is empty. ", "Take an item.", "EXPLICIT_RULE", second)))))
                .hasMessageContaining("duplicate decision branch condition");
    }

    @Test
    void boundsBranchCountWithoutDependingOnRulebookVocabulary() {
        List<DecisionBranchRequest> tooMany = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> branch(
                        "Condition " + index, "Outcome " + index, "EXPLICIT_RULE", first))
                .toList();

        assertThatThrownBy(() -> resolver.resolve(request("What happens in each case?", first), draft(tooMany)))
                .hasMessageContaining("too many decision branches");
    }

    private ModelRequest request(String question, UUID... evidenceIds) {
        return request(AnswerAid.DECISION_TABLE, question, evidenceIds);
    }

    private ModelRequest request(AnswerAid answerAid, String question, UUID... evidenceIds) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                java.util.Arrays.stream(evidenceIds)
                        .map(id -> new EvidenceInput(id, "RULE", "Branches", "Cited branch evidence.", 2, 2))
                        .toList(),
                Set.of(EvidenceNeed.CONDITION),
                answerAid);
    }

    private ModelDraft draft(List<DecisionBranchRequest> branches) {
        List<UUID> citations = branches.stream().flatMap(branch -> branch.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(first);
        return new ModelDraft(
                true, null, "Compare the cited cases.", "Each row is separately sourced.", citations, List.of(),
                "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), branches);
    }

    private DecisionBranchRequest branch(
            String condition, String outcome, String basis, UUID citationId) {
        return new DecisionBranchRequest(condition, outcome, basis, List.of(citationId));
    }
}
