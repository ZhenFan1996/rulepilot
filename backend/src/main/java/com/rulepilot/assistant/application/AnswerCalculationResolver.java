package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandRequest;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandSource;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.RuleCalculation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admits only arithmetic whose operands are grounded in the current question or cited evidence. */
final class AnswerCalculationResolver {

    private final BoundedRuleCalculator calculator = new BoundedRuleCalculator();

    List<RuleCalculation> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw invalid();
        List<CalculationRequest> requested = draft.calculations();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.CALCULATION, requested.isEmpty(), "calculations");
        if (requested.isEmpty()) return List.of();
        if (draft.citationIds().isEmpty()) throw invalid();

        Map<UUID, EvidenceInput> evidenceById = request.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(EvidenceInput::chunkId, Function.identity()));

        List<RuleCalculation> resolved = requested.stream().map(calculation -> {
            if (calculation == null) throw invalid();
            BoundedRuleCalculator.Evaluation evaluated = calculator.evaluate(calculation.expression());
            if (evaluated.literals().isEmpty()
                    || !sameNumericOccurrences(evaluated.literals(), calculation.operands())
                    || calculation.operands().stream()
                            .noneMatch(operand -> operand.source() == CalculationOperandSource.QUESTION)
                    || calculation.operands().stream()
                            .anyMatch(operand -> !grounded(request, draft, evidenceById, operand))
                    || new BigDecimal(evaluated.result()).compareTo(calculation.expectedResult()) != 0) {
                throw invalid();
            }
            return new RuleCalculation(evaluated.expression(), evaluated.result());
        }).toList();
        return resolved;
    }

    boolean requiresCalculation(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.CALCULATION);
    }

    private boolean sameNumericOccurrences(
            List<BigDecimal> expressionLiterals,
            List<CalculationOperandRequest> operands) {
        if (expressionLiterals.size() != operands.size()) return false;
        Comparator<BigDecimal> numericOrder = BigDecimal::compareTo;
        List<BigDecimal> expressionValues = new ArrayList<>(expressionLiterals);
        List<BigDecimal> declaredValues = operands.stream().map(CalculationOperandRequest::value).toList();
        expressionValues.sort(numericOrder);
        declaredValues = new ArrayList<>(declaredValues);
        declaredValues.sort(numericOrder);
        for (int index = 0; index < expressionValues.size(); index++) {
            if (expressionValues.get(index).compareTo(declaredValues.get(index)) != 0) return false;
        }
        return true;
    }

    private boolean grounded(
            ModelRequest request,
            ModelDraft draft,
            Map<UUID, EvidenceInput> evidenceById,
            CalculationOperandRequest operand) {
        if (operand.source() == CalculationOperandSource.QUESTION) {
            return operand.citationId() == null && request.question().contains(operand.sourceSpan());
        }
        if (operand.source() != CalculationOperandSource.EVIDENCE
                || operand.citationId() == null
                || !draft.citationIds().contains(operand.citationId())) {
            return false;
        }
        EvidenceInput evidence = evidenceById.get(operand.citationId());
        return evidence != null && evidence.excerpt().contains(operand.sourceSpan());
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("answer calculation is not grounded in current inputs");
    }
}
