package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerExceptionClauseResolverTest {

    private final AnswerExceptionClauseResolver resolver = new AnswerExceptionClauseResolver();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void validatesIndependentlyCitedClausesWhenThePlanSelectedExceptions() {
        ModelDraft draft = draft(List.of(
                clause("When the supply is empty", "The item cannot be taken.", first),
                clause("When the effect is active", "A duplicate cannot be created.", second)));

        var clauses = resolver.resolve(request(AnswerAid.EXCEPTIONS), draft);

        assertThat(clauses).hasSize(2);
        assertThat(clauses.get(0).citationIds()).containsExactly(first);
        assertThat(clauses.get(1).citationIds()).containsExactly(second);
    }

    @Test
    void selectionDependsOnAnswerAidRatherThanExceptionWordsInTheQuestion() {
        assertThat(resolver.requiresExceptionClauses(request(AnswerAid.EXCEPTIONS))).isTrue();
        assertThat(resolver.requiresExceptionClauses(request(AnswerAid.NONE))).isFalse();
        assertThatThrownBy(() -> resolver.resolve(request(AnswerAid.EXCEPTIONS), draft(List.of())))
                .hasMessageContaining("required");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.NONE),
                        draft(List.of(clause("Only at night", "The action is prohibited.", first)))))
                .hasMessageContaining("not selected");
    }

    @Test
    void rejectsDuplicateAndOutOfScopeClauseEvidence() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.EXCEPTIONS),
                        draft(List.of(clause("Only at night", "Prohibited.", UUID.randomUUID())))))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.EXCEPTIONS),
                        draft(List.of(
                                clause("Only at night", "Prohibited.", first),
                                clause("only at night", "Delayed.", second)))))
                .hasMessageContaining("duplicate");
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Exception-shaped words must not control routing.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(first, "RULE", "Restrictions", "First restriction.", 3, 3),
                        new EvidenceInput(second, "RULE", "Restrictions", "Second restriction.", 4, 4)),
                Set.of(EvidenceNeed.EXCEPTION),
                aid);
    }

    private ExceptionClauseRequest clause(String condition, String effect, UUID citation) {
        return new ExceptionClauseRequest(condition, effect, List.of(citation));
    }

    private ModelDraft draft(List<ExceptionClauseRequest> clauses) {
        return new ModelDraft(
                true, null, "The rule has cited limits.", "Apply each cited limit.",
                List.of(first, second), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), clauses);
    }
}
