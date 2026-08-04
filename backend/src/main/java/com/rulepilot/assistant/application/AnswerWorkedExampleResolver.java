package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WorkedExampleRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates worked examples as cited setup-action-outcome records rather than unchecked prose. */
final class AnswerWorkedExampleResolver {

    private static final int MAX_EXAMPLES = 3;
    private static final Pattern EXAMPLE_REQUEST = Pattern.compile(
            "(?iu)\\b(?:give|show|provide|walk me through|can you give|can you show).{0,80}example\\b|"
                    + "\\b(?:example of|worked example|for example)\\b|举例|例子|示例|实例|演示一下");

    List<RuleWorkedExample> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("worked example input is invalid");
        if (draft.workedExamples().isEmpty()) {
            if (requiresWorkedExamples(request)) {
                throw new IllegalArgumentException("example answer omitted cited worked examples");
            }
            return List.of();
        }
        if (draft.workedExamples().size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("too many worked examples");
        }
        Map<UUID, EvidenceInput> availableEvidence = request.evidence().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        EvidenceInput::chunkId, evidence -> evidence, (first, duplicate) -> first));
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        return draft.workedExamples().stream()
                .map(example -> resolveOne(example, availableEvidence, answerCitations))
                .toList();
    }

    boolean requiresWorkedExamples(ModelRequest request) {
        return request != null
                && (request.context().learningIntent() == LearningIntent.EXAMPLE
                        || asksForExample(request.question()));
    }

    static boolean asksForExample(String question) {
        return question != null && EXAMPLE_REQUEST.matcher(question).find();
    }

    private RuleWorkedExample resolveOne(
            WorkedExampleRequest request,
            Map<UUID, EvidenceInput> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("worked example item is null");
        if (request.setup() == null || request.setup().isBlank() || request.setup().length() > 500) {
            throw new IllegalArgumentException("worked example setup is invalid");
        }
        if (request.action() == null || request.action().isBlank() || request.action().length() > 700) {
            throw new IllegalArgumentException("worked example action is invalid");
        }
        if (request.outcome() == null || request.outcome().isBlank() || request.outcome().length() > 500) {
            throw new IllegalArgumentException("worked example outcome is invalid");
        }
        WorkedExampleBasis basis;
        try {
            basis = WorkedExampleBasis.valueOf(request.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("worked example basis is invalid", invalidBasis);
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("worked example citations are invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.keySet().containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("worked example cites evidence outside the answer scope");
        }
        Set<String> supportedNumbers = citationIds.stream()
                .map(availableEvidence::get)
                .flatMap(evidence -> numericTokens(evidence.excerpt()).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> claimedNumbers = numericTokens(
                request.setup() + " " + request.action() + " " + request.outcome());
        if (!supportedNumbers.containsAll(claimedNumbers)) {
            throw new IllegalArgumentException("worked example introduced an unsupported number");
        }
        return new RuleWorkedExample(
                request.setup(), request.action(), request.outcome(), basis, citationIds);
    }

    private Set<String> numericTokens(String value) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?![\\p{L}\\p{N}])")
                .matcher(value == null ? "" : value)
                .results()
                .map(result -> result.group())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
