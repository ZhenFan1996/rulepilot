package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RulePriorityRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRulePriorityResolverTest {

    private final AnswerRulePriorityResolver resolver = new AnswerRulePriorityResolver();
    private final UUID citationId = UUID.randomUUID();
    private final ModelRequest request = new ModelRequest(
            "When a card conflicts with the general rule, which rule takes precedence?",
            QuestionType.RULE_QUERY,
            new AnswerContext(null, null, PlayerLocale.EN),
            List.of(new EvidenceInput(citationId, "PRIORITY", "Priority", "Card effects override rules.", 4, 4)));

    @Test
    void resolvesAnExplicitlyCitedOverride() {
        var result = resolver.resolve(request, draft(List.of(new RulePriorityRequest(
                "The general rule forbids the action.",
                "The card effect permits the action.",
                "Apply the card effect for this conflict.",
                "EXPLICIT_OVERRIDE",
                List.of(citationId)))));

        assertThat(result).singleElement().satisfies(resolution -> {
            assertThat(resolution.basis()).isEqualTo(RulePriorityBasis.EXPLICIT_OVERRIDE);
            assertThat(resolution.citationIds()).containsExactly(citationId);
        });
    }

    @Test
    void rejectsAProseOnlyPriorityAnswer() {
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted cited rule resolutions");
    }

    @Test
    void acceptsACitedScopeDistinctionInsteadOfForcingAFalsePriorityWinner() {
        RuleConceptComparisonRequest comparison = new RuleConceptComparisonRequest(
                "archive entry rule",
                "The archive entry rule applies only during Dawn.",
                "vault entry rule",
                "The vault entry rule applies only during Dusk.",
                "Both rules govern entry into a named area.",
                "They apply during different timing conditions.",
                "Use the archive rule only during Dawn and the vault rule only during Dusk; neither overrides the other.",
                "RULE_SCOPE",
                List.of(citationId));
        ModelRequest conflictQuestion = new ModelRequest(
                "Do the archive entry rule and vault entry rule conflict?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        citationId, "SCOPE", "Entry timing",
                        "The archive entry rule applies only during Dawn. The vault entry rule applies only during Dusk.",
                        4, 4)));

        assertThat(AnswerRulePriorityResolver.asksForPriority(conflictQuestion.question())).isTrue();
        assertThat(resolver.resolve(conflictQuestion, draftWithComparison(comparison))).isEmpty();
        assertThat(new AnswerConceptComparisonResolver().resolve(
                        conflictQuestion, draftWithComparison(comparison)))
                .singleElement().extracting(result -> result.basis())
                .isEqualTo(com.rulepilot.assistant.domain.ConceptComparisonBasis.RULE_SCOPE);
    }

    @Test
    void rejectsABareNonConflictLabelThatHidesTheApplicabilityBoundary() {
        RuleConceptComparisonRequest comparison = new RuleConceptComparisonRequest(
                "archive entry rule", "It applies only during Dawn.",
                "vault entry rule", "It applies only during Dusk.",
                "Both govern entry.", "They apply at different times.",
                "Use the archive rule during Dawn and the vault rule during Dusk.",
                "RULE_SCOPE", List.of(citationId));
        ModelDraft bareVerdict = draftWithComparison(comparison);
        bareVerdict = new ModelDraft(
                bareVerdict.answerable(), bareVerdict.insufficiencyReason(), "No conflict.", bareVerdict.explanation(),
                bareVerdict.citationIds(), bareVerdict.exceptions(), bareVerdict.confidence(), bareVerdict.answerBasis(),
                bareVerdict.calculations(), bareVerdict.situationChecks(), bareVerdict.walkthroughSteps(),
                bareVerdict.decisionBranches(), bareVerdict.exceptionClauses(), bareVerdict.termDefinitions(),
                bareVerdict.workedExamples(), bareVerdict.priorityResolutions(), bareVerdict.timingResolutions(),
                bareVerdict.tieResolutions(), bareVerdict.scopeResolutions(), bareVerdict.conceptComparisons());

        ModelDraft rejected = bareVerdict;
        assertThatThrownBy(() -> resolver.resolve(new ModelRequest(
                        "Do the archive entry rule and vault entry rule conflict?",
                        QuestionType.RULE_QUERY, new AnswerContext(null, null, PlayerLocale.EN), request.evidence()),
                        rejected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicability boundary");
    }

    @Test
    void rejectsUnsupportedBasisAndOutOfScopeCitation() {
        ModelDraft unsupportedBasis = draft(List.of(new RulePriorityRequest(
                "General rule", "Special rule", "Special rule wins", "MORE_SPECIFIC", List.of(citationId))));
        assertThatThrownBy(() -> resolver.resolve(request, unsupportedBasis))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis");
        ModelDraft outOfScope = draft(List.of(new RulePriorityRequest(
                "General rule", "Special rule", "Special rule wins", "EXPLICIT_OVERRIDE",
                List.of(UUID.randomUUID()))));
        assertThatThrownBy(() -> resolver.resolve(request, outOfScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the answer scope");
    }

    private ModelDraft draft(List<RulePriorityRequest> resolutions) {
        return new ModelDraft(
                true, null, "The card effect applies.", "The cited priority rule resolves the conflict.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), resolutions);
    }

    private ModelDraft draftWithComparison(RuleConceptComparisonRequest comparison) {
        return new ModelDraft(
                true, null, "The rules do not conflict because one applies during Dawn and the other during Dusk.",
                "Each cited rule applies during a different timing condition.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(comparison));
    }
}
