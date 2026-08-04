package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates player-requested exceptions and restrictions against their own cited evidence. */
final class AnswerExceptionClauseResolver {

    private static final int MAX_CLAUSES = 6;
    private static final Pattern EXCEPTION_REQUEST = Pattern.compile(
            "(?iu)\\b(?:what (?:are|is) the exceptions?|any exceptions?|exceptions? or limits?|"
                    + "restrictions? and exceptions?|prohibitions? and exceptions?|when (?:can(?:not|'t)|may not))\\b|"
                    + "有(?:什么|哪些)?例外|例外(?:和|与|或)?限制|限制(?:和|与|或)?例外|有哪些限制|"
                    + "什么情况下不能|何时不能|禁止和例外");

    List<RuleExceptionClause> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("exception clause input is invalid");
        }
        if (draft.exceptionClauses().isEmpty()) {
            if (requiresExceptionClauses(request)) {
                throw new IllegalArgumentException("exception answer omitted cited exception clauses");
            }
            return List.of();
        }
        if (draft.exceptionClauses().size() > MAX_CLAUSES) {
            throw new IllegalArgumentException("too many exception clauses");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        LinkedHashSet<String> conditions = new LinkedHashSet<>();
        return draft.exceptionClauses().stream()
                .map(clause -> resolveOne(clause, availableEvidence, answerCitations))
                .peek(clause -> {
                    if (!conditions.add(clause.condition().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("duplicate exception clause condition");
                    }
                })
                .toList();
    }

    boolean requiresExceptionClauses(ModelRequest request) {
        return request != null
                && (request.context().learningIntent() == LearningIntent.EXCEPTIONS
                        || asksForExceptions(request.question()));
    }

    static boolean asksForExceptions(String question) {
        return question != null && EXCEPTION_REQUEST.matcher(question).find();
    }

    private RuleExceptionClause resolveOne(
            ExceptionClauseRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null || request.condition() == null || request.condition().isBlank()
                || request.condition().length() > 300
                || request.effect() == null || request.effect().isBlank() || request.effect().length() > 500
                || request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("exception clause request is invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("exception clause cites evidence outside the answer scope");
        }
        return new RuleExceptionClause(request.condition(), request.effect(), citationIds);
    }
}
