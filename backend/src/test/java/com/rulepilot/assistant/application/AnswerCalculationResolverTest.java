package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandRequest;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandSource;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerCalculationResolverTest {

    private final AnswerCalculationResolver resolver = new AnswerCalculationResolver();
    private final UUID citedId = UUID.randomUUID();

    @Test
    void resolvesTypedOperandsAndChecksTheDeclaredResult() {
        var result = resolver.resolve(
                request("I have 8 resources. How many points do I score?", "Each complete set of 3 scores 5 points."),
                draft(calculation(
                        "floor(8 / 3) * 5",
                        "10",
                        operand("available resources", "8", CalculationOperandSource.QUESTION, "8 resources", null),
                        operand("resources per set", "3", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                        operand("points per set", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId))));

        assertThat(result).singleElement().satisfies(calculation -> {
            assertThat(calculation.expression()).isEqualTo("floor(8 / 3) * 5");
            assertThat(calculation.result()).isEqualTo("10");
        });
    }

    @Test
    void acceptsNonArabicSourceSpansWithoutParsingNaturalLanguageNumbers() {
        var result = resolver.resolve(
                request("我有八个资源，可以得多少分？", "每三个资源组成一组，每组获得五分。"),
                draft(calculation(
                        "floor(8 / 3) * 5",
                        "10",
                        operand("现有资源", "8", CalculationOperandSource.QUESTION, "八个资源", null),
                        operand("每组资源", "3", CalculationOperandSource.EVIDENCE, "每三个资源", citedId),
                        operand("每组分数", "5", CalculationOperandSource.EVIDENCE, "获得五分", citedId))));

        assertThat(result).singleElement().extracting(calculation -> calculation.result()).isEqualTo("10");
    }

    @Test
    void rejectsAResultOrOperandDeclarationThatDisagreesWithTheExpression() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        draft(calculation(
                                "floor(8 / 3) * 5",
                                "15",
                                operand("available", "8", CalculationOperandSource.QUESTION, "8 resources", null),
                                operand("set size", "3", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                                operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        draft(calculation(
                                "floor(8 / 3) * 5",
                                "10",
                                operand("available", "8", CalculationOperandSource.QUESTION, "8 resources", null),
                                operand("set size", "4", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                                operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAParaphrasedOrUncitedSourceDeclaration() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        draft(calculation(
                                "floor(8 / 3) * 5",
                                "10",
                                operand("available", "8", CalculationOperandSource.QUESTION, "eight resources", null),
                                operand("set size", "3", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                                operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)))))
                .isInstanceOf(IllegalArgumentException.class);

        UUID otherEvidence = UUID.randomUUID();
        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        draft(calculation(
                                "floor(8 / 3) * 5",
                                "10",
                                operand("available", "8", CalculationOperandSource.QUESTION, "8 resources", null),
                                operand("set size", "3", CalculationOperandSource.EVIDENCE, "set of 3", otherEvidence),
                                operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAQuestionOperandAndEveryLiteralOccurrence() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("How many points does this score?", "Each set of 3 scores 5 points."),
                        draft(calculation(
                                "3 * 5",
                                "15",
                                operand("set size", "3", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                                operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> resolver.resolve(
                        request("I compare 8 with another 8. How many total?", "Add both stated amounts."),
                        draft(calculation(
                                "8 + 8",
                                "16",
                                operand("first amount", "8", CalculationOperandSource.QUESTION, "8 with", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotTreatPlayerFacingProseAsTheMachineProtocol() {
        ModelDraft proseWithAnUnrelatedNumber = draft(
                "This table state yields two complete sets; keep the answer concise for round 15.",
                calculation(
                        "floor(8 / 3) * 5",
                        "10",
                        operand("available", "8", CalculationOperandSource.QUESTION, "8 resources", null),
                        operand("set size", "3", CalculationOperandSource.EVIDENCE, "set of 3", citedId),
                        operand("points", "5", CalculationOperandSource.EVIDENCE, "5 points", citedId)));

        assertThat(resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        proseWithAnUnrelatedNumber))
                .singleElement()
                .extracting(calculation -> calculation.result())
                .isEqualTo("10");
    }

    @Test
    void rejectsAnOmittedCalculationWhenTheAcceptedPlanRequiresIt() {
        ModelDraft omitted = new ModelDraft(
                true,
                null,
                "The result follows from the stated quantities.",
                "The application will compute the exact result.",
                List.of(citedId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION");

        assertThatThrownBy(() -> resolver.resolve(
                        request("I have 8 resources. How many points?", "Each set of 3 scores 5 points."),
                        omitted))
                .hasMessageContaining("required");
    }

    private ModelRequest request(String question, String evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citedId, "RULE", "Scoring", evidence, 4, 4)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                AnswerAid.CALCULATION);
    }

    private ModelDraft draft(CalculationRequest calculation) {
        return draft("Two complete sets produce the computed result.", calculation);
    }

    private ModelDraft draft(String explanation, CalculationRequest calculation) {
        return new ModelDraft(
                true,
                null,
                "The calculation is shown below.",
                explanation,
                List.of(citedId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION",
                List.of(calculation));
    }

    private CalculationRequest calculation(
            String expression,
            String expectedResult,
            CalculationOperandRequest... operands) {
        return new CalculationRequest(
                expression,
                new BigDecimal(expectedResult),
                "points",
                List.of(operands));
    }

    private CalculationOperandRequest operand(
            String name,
            String value,
            CalculationOperandSource source,
            String sourceSpan,
            UUID citationId) {
        return new CalculationOperandRequest(name, new BigDecimal(value), source, sourceSpan, citationId);
    }
}
