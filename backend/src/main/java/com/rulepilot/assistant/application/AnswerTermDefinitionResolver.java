package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.TermDefinitionRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates player-requested term definitions and distinctions against their own cited evidence. */
final class AnswerTermDefinitionResolver {

    private static final int MAX_DEFINITIONS = 4;
    private static final Pattern DEFINITION_REQUEST = Pattern.compile(
            "(?iu)\\b(?:what does .{1,120} mean|define .{1,120}|definition of .{1,120}|meaning of .{1,120}|"
                    + "difference between .{1,120}|how (?:is|are) .{1,120} different)\\b|"
                    + "什么是|是什么意思|如何定义|定义一下|区别|有什么不同|有何不同");

    List<RuleTermDefinition> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("term definition input is invalid");
        }
        if (draft.termDefinitions().isEmpty()) {
            if (requiresTermDefinitions(request)) {
                throw new IllegalArgumentException("definition answer omitted cited term definitions");
            }
            return List.of();
        }
        if (draft.termDefinitions().size() > MAX_DEFINITIONS) {
            throw new IllegalArgumentException("too many term definitions");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        return draft.termDefinitions().stream()
                .map(definition -> resolveOne(definition, availableEvidence, answerCitations))
                .peek(definition -> {
                    if (!terms.add(definition.term().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("duplicate rule term definition");
                    }
                })
                .toList();
    }

    boolean requiresTermDefinitions(ModelRequest request) {
        return request != null
                && (request.context().learningIntent() == LearningIntent.DEFINE
                        || asksForDefinition(request.question()));
    }

    static boolean asksForDefinition(String question) {
        return question != null && DEFINITION_REQUEST.matcher(question).find();
    }

    private RuleTermDefinition resolveOne(
            TermDefinitionRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("term definition item is null");
        if (request.term() == null || request.term().isBlank() || request.term().length() > 120) {
            throw new IllegalArgumentException("term definition term is invalid");
        }
        if (request.definition() == null || request.definition().isBlank() || request.definition().length() > 600) {
            throw new IllegalArgumentException("term definition text is invalid");
        }
        if (request.boundary() != null && request.boundary().length() > 400) {
            throw new IllegalArgumentException("term definition boundary is too long");
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("term definition citations are invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("term definition cites evidence outside the answer scope");
        }
        return new RuleTermDefinition(
                request.term(), request.definition(), request.boundary() == null ? "" : request.boundary(), citationIds);
    }
}
