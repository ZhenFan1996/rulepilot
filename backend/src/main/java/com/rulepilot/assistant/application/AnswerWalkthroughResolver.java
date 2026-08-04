package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates a player-facing walkthrough without treating presentation order as a rule-mandated sequence. */
final class AnswerWalkthroughResolver {

    private static final int MAX_STEPS = 6;
    private static final Pattern PROCEDURE_QUESTION = Pattern.compile(
            "(?iu)\\b(?:how do (?:i|we) (?:resolve|perform|play|take|complete|run|set up|finish|carry out)|"
                    + "how to (?:resolve|perform|play|complete|run|set up|finish|carry out)|"
                    + "what happens (?:first|next)|in what order|step[- ]by[- ]step)\\b|"
                    + "怎么一步步|如何一步步|具体步骤|按什么顺序|先做什么|下一步做什么|流程是什么");

    List<RuleWalkthroughStep> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("walkthrough input is invalid");
        }
        if (draft.walkthroughSteps().isEmpty()) {
            if (requiresWalkthrough(request)) {
                throw new IllegalArgumentException(requiresDependencyTrace(request)
                        ? "why answer omitted a cited rule dependency trace"
                        : "procedural answer omitted a cited walkthrough");
            }
            return List.of();
        }
        if (requiresDependencyTrace(request) && draft.walkthroughSteps().size() < 2) {
            throw new IllegalArgumentException("rule dependency trace must connect at least two cited steps");
        }
        if (draft.walkthroughSteps().size() > MAX_STEPS) {
            throw new IllegalArgumentException("too many walkthrough steps");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        Map<UUID, String> evidenceById = request.evidence().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        EvidenceInput::chunkId, EvidenceInput::excerpt, (first, duplicate) -> first));
        LinkedHashSet<String> instructions = new LinkedHashSet<>();
        List<RuleWalkthroughStep> resolved = draft.walkthroughSteps().stream()
                .map(step -> resolveOne(step, availableEvidence, answerCitations, evidenceById))
                .peek(step -> {
                    if (!instructions.add(step.instruction().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("duplicate walkthrough instruction");
                    }
                })
                .toList();
        if (requiresDependencyTrace(request)
                && resolved.stream().anyMatch(step -> step.orderBasis() != WalkthroughOrderBasis.RULE_ORDER)) {
            throw new IllegalArgumentException("rule dependency trace must preserve cited rule order");
        }
        return resolved;
    }

    boolean requiresWalkthrough(ModelRequest request) {
        return request != null && (asksForProcedure(request.question()) || requiresDependencyTrace(request));
    }

    boolean requiresDependencyTrace(ModelRequest request) {
        return request != null && request.context().learningIntent() == LearningIntent.WHY;
    }

    static boolean asksForProcedure(String question) {
        return question != null && PROCEDURE_QUESTION.matcher(question).find();
    }

    private RuleWalkthroughStep resolveOne(
            WalkthroughStepRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations,
            Map<UUID, String> evidenceById) {
        if (request == null || request.instruction() == null || request.instruction().isBlank()
                || request.instruction().length() > 240
                || request.explanation() == null || request.explanation().isBlank()
                || request.explanation().length() > 500
                || request.orderBasis() == null
                || request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("walkthrough step request is invalid");
        }
        WalkthroughOrderBasis orderBasis;
        try {
            orderBasis = WalkthroughOrderBasis.valueOf(request.orderBasis().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidOrderBasis) {
            throw new IllegalArgumentException("walkthrough order basis is invalid", invalidOrderBasis);
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("walkthrough step cites evidence outside the answer scope");
        }
        String citedEvidence = citationIds.stream().map(evidenceById::get)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));
        if (!numericTokens(citedEvidence).containsAll(numericTokens(
                request.instruction() + " " + request.explanation()))) {
            throw new IllegalArgumentException("walkthrough step introduced an unsupported numeric rule");
        }
        if (normalized(request.instruction()).equals(normalized(request.explanation()))) {
            throw new IllegalArgumentException("walkthrough explanation merely repeats its instruction");
        }
        return new RuleWalkthroughStep(
                request.instruction(), request.explanation(), orderBasis, citationIds);
    }

    private static Set<String> numericTokens(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(value.split("[^0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
