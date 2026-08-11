package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected exception list. */
final class AnswerExceptionClauseResolver {

    private static final int MAX_CLAUSES = 6;

    List<RuleExceptionClause> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("exception clause input is invalid");
        List<ExceptionClauseRequest> proposed = draft.exceptionClauses();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.EXCEPTIONS, proposed.isEmpty(), "exception clauses");
        if (proposed.isEmpty()) return List.of();
        if (proposed.size() > MAX_CLAUSES) throw new IllegalArgumentException("too many exception clauses");

        LinkedHashSet<String> conditions = new LinkedHashSet<>();
        return proposed.stream()
                .map(clause -> resolveOne(request, draft, clause))
                .peek(clause -> {
                    if (!conditions.add(AnswerStructuredAidPolicy.identityKey(clause.condition()))) {
                        throw new IllegalArgumentException("duplicate exception clause condition");
                    }
                })
                .toList();
    }

    boolean requiresExceptionClauses(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.EXCEPTIONS);
    }

    private RuleExceptionClause resolveOne(ModelRequest modelRequest, ModelDraft draft, ExceptionClauseRequest item) {
        if (item == null) throw new IllegalArgumentException("exception clause is null");
        return new RuleExceptionClause(
                AnswerStructuredAidPolicy.requiredText(item.condition(), 300, "exception condition"),
                AnswerStructuredAidPolicy.requiredText(item.effect(), 500, "exception effect"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "exception clause"));
    }
}
