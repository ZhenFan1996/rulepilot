package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerExceptionClauseResolverTest {

    private final AnswerExceptionClauseResolver resolver = new AnswerExceptionClauseResolver();

    @Test
    void requiresIndependentlyCitedClausesForExplicitExceptionRequests() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ModelRequest request = request(
                "What exceptions or limits apply?",
                null,
                List.of(evidence(first), evidence(second)));
        ModelDraft draft = draft(
                List.of(first, second),
                List.of(
                        new ExceptionClauseRequest("When the supply is empty", "The item cannot be taken.", List.of(first)),
                        new ExceptionClauseRequest("When the same effect is already active", "A duplicate cannot be created.", List.of(second))));

        var clauses = resolver.resolve(request, draft);

        assertThat(clauses).hasSize(2);
        assertThat(clauses.get(0).citationIds()).containsExactly(first);
        assertThat(clauses.get(1).citationIds()).containsExactly(second);
    }

    @Test
    void learningIntentAlsoRequiresClausesButOrdinaryQuestionsDoNot() {
        UUID citation = UUID.randomUUID();
        assertThat(resolver.requiresExceptionClauses(request(
                        "Explain this rule.", LearningIntent.EXCEPTIONS, List.of(evidence(citation)))))
                .isTrue();
        assertThat(resolver.requiresExceptionClauses(request(
                        "When does this resolve?", null, List.of(evidence(citation)))))
                .isFalse();
        assertThat(AnswerExceptionClauseResolver.asksForExceptions("What are the exceptions?"))
                .isTrue();
        assertThat(AnswerExceptionClauseResolver.asksForExceptions("什么时候结算？"))
                .isFalse();
    }

    @Test
    void rejectsMissingDuplicateAndOutOfScopeClauseEvidence() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request("有哪些例外和限制？", null, List.of(evidence(citation)));

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(citation), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted");
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                        List.of(citation),
                        List.of(new ExceptionClauseRequest("Only at night", "The action is prohibited.", List.of(UUID.randomUUID()))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                        List.of(citation),
                        List.of(
                                new ExceptionClauseRequest("Only at night", "The action is prohibited.", List.of(citation)),
                                new ExceptionClauseRequest("only at night", "The action is delayed.", List.of(citation))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private ModelRequest request(String question, LearningIntent intent, List<EvidenceInput> evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, intent, PlayerLocale.EN),
                evidence);
    }

    private EvidenceInput evidence(UUID id) {
        return new EvidenceInput(id, "RULE", "Restrictions", "A directly stated restriction.", 3, 3);
    }

    private ModelDraft draft(List<UUID> citations, List<ExceptionClauseRequest> clauses) {
        return new ModelDraft(
                true,
                null,
                "The rule has cited limits.",
                "Apply each limit only under its stated condition.",
                citations,
                List.of(),
                "HIGH",
                "DIRECT_RULE",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                clauses);
    }
}
