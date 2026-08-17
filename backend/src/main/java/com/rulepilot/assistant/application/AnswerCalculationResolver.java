package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.RuleCalculation;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Admits only arithmetic whose operands are grounded in the current question or cited evidence. */
final class AnswerCalculationResolver {

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}.])\\d+(?:\\.\\d+)?");
    private final BoundedRuleCalculator calculator = new BoundedRuleCalculator();

    List<RuleCalculation> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw invalid();
        List<CalculationRequest> requested = draft.calculations();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.CALCULATION, requested.isEmpty(), "calculations");
        if (requested.isEmpty()) return List.of();
        if (draft.citationIds().isEmpty()) throw invalid();

        Set<BigDecimal> questionValues = values(request.question());
        if (questionValues.isEmpty()) throw invalid();
        Set<UUID> cited = Set.copyOf(draft.citationIds());
        LinkedHashSet<BigDecimal> allowed = new LinkedHashSet<>(questionValues);
        request.evidence().stream()
                .filter(source -> cited.contains(source.chunkId()))
                .forEach(source -> allowed.addAll(values(source.excerpt())));

        List<RuleCalculation> resolved = requested.stream().map(calculation -> {
            if (calculation == null || calculation.expression() == null) throw invalid();
            BoundedRuleCalculator.Evaluation evaluated = calculator.evaluate(calculation.expression());
            if (evaluated.literals().isEmpty()
                    || evaluated.literals().stream().anyMatch(value -> !allowed.contains(value.stripTrailingZeros()))
                    || evaluated.literals().stream().noneMatch(questionValues::contains)) {
                throw invalid();
            }
            return new RuleCalculation(evaluated.expression(), evaluated.result());
        }).toList();
        Set<BigDecimal> results = new LinkedHashSet<>();
        resolved.forEach(calculation -> results.add(new BigDecimal(calculation.result()).stripTrailingZeros()));
        Set<BigDecimal> playerFacingValues = values(
                draft.shortVerdict() + "\n" + draft.explanation() + "\n" + String.join("\n", draft.exceptions()));
        if (!playerFacingValues.containsAll(results)) throw invalid();
        LinkedHashSet<BigDecimal> permittedOutputValues = new LinkedHashSet<>(allowed);
        permittedOutputValues.addAll(results);
        if (playerFacingValues.stream().anyMatch(value -> !permittedOutputValues.contains(value))) throw invalid();
        return resolved;
    }

    boolean requiresCalculation(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.CALCULATION);
    }

    private Set<BigDecimal> values(String source) {
        LinkedHashSet<BigDecimal> values = new LinkedHashSet<>();
        if (source == null) return values;
        Matcher matcher = NUMBER.matcher(source);
        while (matcher.find() && values.size() < 32) {
            try {
                values.add(new BigDecimal(matcher.group()).stripTrailingZeros());
            } catch (NumberFormatException ignored) {
                // Untrusted source text may contain a token too large for a useful arithmetic operand.
            }
        }
        return values;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("answer calculation is not grounded in current inputs");
    }
}
