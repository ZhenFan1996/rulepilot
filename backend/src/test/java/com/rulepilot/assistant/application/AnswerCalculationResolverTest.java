package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerCalculationResolverTest {

    private final AnswerCalculationResolver resolver = new AnswerCalculationResolver();
    private final UUID citedId = UUID.randomUUID();

    @Test
    void resolvesAFormulaUsingPlayerStateAndCitedRuleValues() {
        var result = resolver.resolve(
                request("I have 8 resources. How many points do I score?", "Each complete set of 3 scores 5 points."),
                draft("floor(8 / 3) * 5"));

        assertThat(result).singleElement().satisfies(calculation -> {
            assertThat(calculation.expression()).isEqualTo("floor(8 / 3) * 5");
            assertThat(calculation.result()).isEqualTo("10");
        });
    }

    @Test
    void rejectsInventedOperandsUncitedOperandsAndCalculationsWithoutPlayerState() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        draft("floor(8 / 4) * 5")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> resolver.resolve(
                        request("How many points does a complete set score?", "Each set of 3 scores 5 points."),
                        draft("3 * 5")))
                .isInstanceOf(IllegalArgumentException.class);

        UUID uncitedId = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "I have 8 resources. How many points?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        evidence(citedId, "Each set of 3 scores 5 points."),
                        evidence(uncitedId, "A bonus is worth 4 points.")),
                Set.of(EvidenceNeed.DIRECT_RULE),
                AnswerAid.CALCULATION);
        assertThatThrownBy(() -> resolver.resolve(request, draft("floor(8 / 4) * 5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOmittedCalculationWhenTheAcceptedPlanRequiresRecomputation() {
        ModelDraft omitted = new ModelDraft(
                true,
                null,
                "You score 10 points.",
                "Two complete sets score 10 points.",
                List.of(citedId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION");

        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        omitted))
                .hasMessageContaining("required");
    }

    @Test
    void rejectsAPlayerFacingTotalThatDisagreesWithTheRecomputedResult() {
        ModelDraft inconsistent = new ModelDraft(
                true,
                null,
                "You score 15 points.",
                "The calculation gives 15 points.",
                List.of(citedId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION",
                List.of(new CalculationRequest("floor(8 / 3) * 5")));

        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        inconsistent))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ModelRequest request(String question, String evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(evidence(citedId, evidence)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                AnswerAid.CALCULATION);
    }

    private EvidenceInput evidence(UUID id, String excerpt) {
        return new EvidenceInput(id, "RULE", "Scoring", excerpt, 4, 4);
    }

    private ModelDraft draft(String expression) {
        return new ModelDraft(
                true,
                null,
                "You score 10 points.",
                "Two complete sets score 10 points; two resources remain.",
                List.of(citedId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION",
                List.of(new CalculationRequest(expression)));
    }
}
