package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnswerScopeResolverTest {

    private final AnswerScopeResolver resolver = new AnswerScopeResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void resolvesTheModelSelectedScopeSchemaWithoutReinterpretingProse() {
        var result = resolver.resolve(request(AnswerAid.SCOPE), draft(List.of(scope(
                "Role-dependent setup.",
                "Apply when the named role is absent.",
                "The current role state is unknown.",
                "NEEDS_CONTEXT",
                "Confirm the role state before applying the clause.",
                "ROLE_PRESENCE",
                citationId))));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.matchStatus()).isEqualTo(ScopeMatchStatus.NEEDS_CONTEXT);
            assertThat(item.basis()).isEqualTo(ScopeBasis.ROLE_PRESENCE);
            assertThat(item.citationIds()).containsExactly(citationId);
        });
    }

    @Test
    void followsTheAcceptedAidRatherThanPlayerCountKeywords() {
        ModelRequest selected = request(AnswerAid.SCOPE);
        assertThat(resolver.requiresScope(selected)).isTrue();
        assertThat(resolver.resolve(selected, draft(List.of()))).isEmpty();

        ModelRequest notSelected = request(AnswerAid.NONE);
        assertThat(resolver.requiresScope(notSelected)).isFalse();
        assertThat(resolver.resolve(notSelected, draft(List.of()))).isEmpty();
    }

    @Test
    void rejectsInvalidEnumsOutOfScopeCitationsAndDuplicates() {
        ModelRequest request = request(AnswerAid.SCOPE);
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(scope(
                        "Context", "Condition", "Situation", "MAYBE", "Effect",
                        "PLAYER_COUNT", citationId)))))
                .hasMessageContaining("status");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(scope(
                        "Context", "Condition", "Situation", "MATCHES_SCOPE", "Effect",
                        "UNKNOWN_SCOPE", citationId)))))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(scope(
                        "Context", "Condition", "Situation", "MATCHES_SCOPE", "Effect",
                        "PLAYER_COUNT", UUID.randomUUID())))))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                        scope("Context", "Condition", "Situation", "MATCHES_SCOPE", "Effect",
                                "PLAYER_COUNT", citationId),
                        scope(" context ", "Other", " situation ", "OUTSIDE_SCOPE", "Other effect",
                                "PLAYER_COUNT", citationId)))))
                .hasMessageContaining("duplicate");
    }

    @Test
    void preservesEverySupportedScopeBeyondTheOldPresentationCap() {
        List<RuleScopeRequest> resolutions = IntStream.rangeClosed(1, 4)
                .mapToObj(index -> scope(
                        "Context " + index,
                        "Condition " + index,
                        "Situation " + index,
                        "MATCHES_SCOPE",
                        "Effect " + index,
                        "PLAYER_COUNT",
                        citationId))
                .toList();

        assertThat(resolver.resolve(request(AnswerAid.SCOPE), draft(resolutions))).hasSize(4);
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Does the cited rule apply?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Scope", "Cited scope rule.", 2, 2)),
                Set.of(EvidenceNeed.CONDITION),
                aid);
    }

    private ModelDraft draft(List<RuleScopeRequest> scopes) {
        List<UUID> citations = scopes.stream().flatMap(item -> item.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(citationId);
        return new ModelDraft(
                true, null, "Apply the cited scope rule.", "Match the condition.", citations,
                List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), scopes);
    }

    private RuleScopeRequest scope(
            String context, String condition, String situation, String status,
            String effect, String basis, UUID citation) {
        return new RuleScopeRequest(
                context, condition, situation, status, effect, basis, List.of(citation));
    }
}
