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
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnswerConceptComparisonResolverTest {

    private final AnswerConceptComparisonResolver resolver = new AnswerConceptComparisonResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void resolvesACompleteCitedComparisonSchema() {
        var result = resolver.resolve(request(AnswerAid.CONCEPT_COMPARISON), draft(List.of(comparison(
                "Left concept", "Left definition", "Right concept", "Right definition",
                "Shared property", "Material difference", "Practical boundary", "RULE_SCOPE", citationId))));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.basis()).isEqualTo(ConceptComparisonBasis.RULE_SCOPE);
            assertThat(item.keyDifference()).isEqualTo("Material difference");
            assertThat(item.citationIds()).containsExactly(citationId);
        });
    }

    @Test
    void followsTheAcceptedAidRatherThanTryingToParseThePlayersNouns() {
        ModelRequest selected = request(AnswerAid.CONCEPT_COMPARISON);
        assertThat(resolver.requiresConceptComparison(selected)).isTrue();
        assertThat(resolver.resolve(selected, draft(List.of()))).isEmpty();

        ModelRequest notSelected = request(AnswerAid.NONE);
        assertThat(resolver.requiresConceptComparison(notSelected)).isFalse();
        assertThat(resolver.resolve(notSelected, draft(List.of()))).isEmpty();
    }

    @Test
    void rejectsInvalidStructureEnumAndCitationScope() {
        ModelRequest request = request(AnswerAid.CONCEPT_COMPARISON);
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(comparison(
                        "Same", "Definition A", " same ", "Definition B",
                        "Common", "Different", "Boundary", "RULE_SCOPE", citationId)))))
                .hasMessageContaining("distinct");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(comparison(
                        "Left", "Definition A", "Right", "Definition B",
                        "Common", "Different", "Boundary", "MODEL_GUESS", citationId)))))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(comparison(
                        "Left", "Definition A", "Right", "Definition B",
                        "Common", "Different", "Boundary", "RULE_SCOPE", UUID.randomUUID())))))
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsDuplicatePairsAndBoundsTheComparisonCount() {
        RuleConceptComparisonRequest one = comparison(
                "Left", "Definition A", "Right", "Definition B",
                "Common", "Different", "Boundary", "RULE_SCOPE", citationId);
        RuleConceptComparisonRequest duplicate = comparison(
                " left ", "Another A", " right ", "Another B",
                "Common", "Different", "Boundary", "RULE_SCOPE", citationId);
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.CONCEPT_COMPARISON), draft(List.of(one, duplicate))))
                .hasMessageContaining("duplicate");

        List<RuleConceptComparisonRequest> tooMany = IntStream.rangeClosed(1, 4)
                .mapToObj(index -> comparison(
                        "Left " + index, "Definition A " + index,
                        "Right " + index, "Definition B " + index,
                        "Common", "Different", "Boundary", "RULE_SCOPE", citationId))
                .toList();
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.CONCEPT_COMPARISON), draft(tooMany)))
                .hasMessageContaining("too many concept comparisons");
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Compare the cited concepts.", QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Comparison", "Cited comparison.", 11, 11)),
                Set.of(EvidenceNeed.RELATIONSHIP),
                aid);
    }

    private ModelDraft draft(List<RuleConceptComparisonRequest> comparisons) {
        List<UUID> citations = comparisons.stream().flatMap(item -> item.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(citationId);
        return new ModelDraft(
                true, null, "The concepts differ.", "Compare the cited functions.", citations,
                List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), comparisons);
    }

    private RuleConceptComparisonRequest comparison(
            String left, String leftDefinition, String right, String rightDefinition, String common,
            String difference, String boundary, String basis, UUID citation) {
        return new RuleConceptComparisonRequest(
                left, leftDefinition, right, rightDefinition, common, difference, boundary, basis,
                List.of(citation));
    }
}
