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
import com.rulepilot.assistant.RuleAnswerModel.RulePriorityRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRulePriorityResolverTest {

    private final AnswerRulePriorityResolver resolver = new AnswerRulePriorityResolver();
    private final UUID citation = UUID.randomUUID();

    @Test
    void validatesAModelSelectedCitedPriorityResolution() {
        var result = resolver.resolve(
                request(AnswerAid.RULE_PRIORITY),
                draft(List.of(priority("General rule", "Card effect", "Apply the card effect"))));

        assertThat(result).singleElement().satisfies(resolution -> {
            assertThat(resolution.basis()).isEqualTo(RulePriorityBasis.EXPLICIT_OVERRIDE);
            assertThat(resolution.citationIds()).containsExactly(citation);
        });
    }

    @Test
    void selectionComesFromAnswerAidRatherThanConflictVocabulary() {
        assertThat(resolver.requiresRulePriority(request(AnswerAid.RULE_PRIORITY))).isTrue();
        assertThat(resolver.requiresRulePriority(request(AnswerAid.NONE))).isFalse();
        assertThat(resolver.resolve(request(AnswerAid.RULE_PRIORITY), draft(List.of()))).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.NONE),
                        draft(List.of(priority("Base", "Special", "Use special")))))
                .hasMessageContaining("not selected");
    }

    @Test
    void rejectsDuplicatePairsInvalidEnumsAndOutOfScopeCitations() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.RULE_PRIORITY),
                        draft(List.of(
                                priority("General", "Card", "Use card"),
                                priority("general", "card", "Use card")))))
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.RULE_PRIORITY),
                        draft(List.of(new RulePriorityRequest(
                                "General", "Card", "Use card", "MORE_SPECIFIC", List.of(citation))))))
                .hasMessageContaining("basis");

        RulePriorityRequest outside = new RulePriorityRequest(
                "General", "Card", "Use card", "EXPLICIT_OVERRIDE", List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.RULE_PRIORITY), draft(List.of(outside))))
                .hasMessageContaining("outside the answer scope");
    }

    private RulePriorityRequest priority(String base, String competing, String resolution) {
        return new RulePriorityRequest(
                base, competing, resolution, "EXPLICIT_OVERRIDE", List.of(citation));
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Arbitrary wording.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        citation, "RULE", "Priority", "Card effects override rules.", 4, 4)),
                Set.of(EvidenceNeed.RELATIONSHIP),
                aid);
    }

    private ModelDraft draft(List<RulePriorityRequest> resolutions) {
        return new ModelDraft(
                true, null, "Priority", "Apply the cited priority.",
                List.of(citation), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), resolutions);
    }
}
