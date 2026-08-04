package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerConceptComparisonResolverTest {

    private final AnswerConceptComparisonResolver resolver = new AnswerConceptComparisonResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void distinguishesAgentAndRevealTurnsByActionWindow() {
        var item = comparison(
                "Agent turn", "Play one card, send an Agent, and use its Agent box.",
                "Reveal turn", "Reveal all remaining cards and use their Reveal boxes.",
                "Both use cards during the player turn phase.",
                "An Agent turn places an Agent; a Reveal turn instead totals persuasion and combat strength.",
                "After taking a Reveal turn, take no more turns in that phase.", "ACTION_WINDOW");

        assertThat(resolver.resolve(request(
                        "What is the difference between an Agent turn and a Reveal turn?",
                        "On an Agent turn play one card, send an Agent, and use its Agent box. On a Reveal turn reveal all remaining cards, use Reveal boxes, total persuasion and combat strength, and take no more turns in the phase."),
                draft(item)))
                .singleElement().extracting(result -> result.basis())
                .isEqualTo(ConceptComparisonBasis.ACTION_WINDOW);
    }

    @Test
    void preservesInfluenceAndGoodwillAsymmetricFunctions() {
        var item = comparison(
                "Influence", "Spend Influence to skip cards; Influence placed on cards accumulates.",
                "Goodwill", "Goodwill goes to Reputation coffers and every 4 Goodwill is worth 1 VP.",
                "Both originate from Influence tokens.",
                "Goodwill may not be spent as Influence.",
                "Use Influence to skip; keep Goodwill for end-game scoring.", "RESOURCE_FUNCTION");

        assertThat(resolver.resolve(request(
                        "What is the difference between Influence and Goodwill?",
                        "Spend Influence to skip cards. Drafted Influence flips to Goodwill in Reputation coffers. Goodwill may not be spent as Influence. Every 4 Goodwill is worth 1 VP."),
                draft(item))).singleElement().satisfies(result -> {
                    assertThat(result.basis()).isEqualTo(ConceptComparisonBasis.RESOURCE_FUNCTION);
                    assertThat(result.keyDifference()).contains("may not be spent");
                });
    }

    @Test
    void distinguishesStockResourcesFromUnlimitedPassTokens() {
        var item = comparison(
                "resources in stock", "Keep one of each resource type: wood, stone, and iron; return extras.",
                "pass tokens", "Keep any number of pass tokens; they are not stock.",
                "Both may remain between turns.",
                "Resources have a one-per-type stock limit; pass tokens have no such limit and are not stock.",
                "Apply stock limits only to resources, not pass tokens.", "STORAGE_STATUS");

        assertThat(resolver.resolve(request(
                        "How are resources in stock different from pass tokens?",
                        "Keep one of each resource type: wood, stone, and iron; return extras. Keep any number of pass tokens. Pass tokens are not stock."),
                draft(item))).singleElement().extracting(result -> result.basis())
                .isEqualTo(ConceptComparisonBasis.STORAGE_STATUS);

        var wrongBasis = comparison(
                "resources in stock", "Keep one of each resource type and return extras.",
                "pass tokens", "Keep any number; they are not stock.", "Both may be kept.",
                "Their retention limits differ.", "Apply stock limits only to resources.", "RESOURCE_FUNCTION");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "How are resources in stock different from pass tokens?",
                        "Keep one of each resource type and return extras. Keep any number of pass tokens; they are not stock."),
                draft(wrongBasis))).hasMessageContaining("STORAGE_STATUS");

        var missingReturn = comparison(
                "resources in stock", "Keep one of each resource type.",
                "pass tokens", "Keep any number; they are not stock.", "Both may be kept.",
                "Their retention limits differ.", "Apply stock limits only to resources.", "STORAGE_STATUS");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "How are resources in stock different from pass tokens?",
                        "Keep one of each resource type and return extras. Keep any number of pass tokens; they are not stock."),
                draft(missingReturn))).hasMessageContaining("return consequence");
    }

    @Test
    void rejectsProseOnlyComparisonAndConceptNotNamedByPlayer() {
        assertThatThrownBy(() -> resolver.resolve(request(
                        "What is the difference between Influence and Goodwill?", "Influence and Goodwill differ."),
                draft(null))).hasMessageContaining("omitted");

        var invented = comparison(
                "Influence", "Spend Influence to skip cards.", "Reputation", "Tracks score.",
                "Both use tokens.", "They have different uses.", "Use each only for its stated use.",
                "RESOURCE_FUNCTION");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "What is the difference between Influence and Goodwill?", "Influence, Goodwill, Reputation."),
                draft(invented))).hasMessageContaining("not named");

        var shortened = comparison(
                "resources", "Keep one of each type.", "pass tokens", "Keep any number.",
                "Both may be kept.", "Only resources count as stock.", "Apply stock limits to resources.",
                "STORAGE_STATUS");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "How are resources in stock different from pass tokens?", "Resources in stock and pass tokens differ."),
                draft(shortened))).hasMessageContaining("complete name");
    }

    @Test
    void rejectsUnsupportedNumericRuleAndCitation() {
        var numeric = comparison(
                "Influence", "Spend Influence to skip cards.", "Goodwill", "Every 5 Goodwill is 1 VP.",
                "Both use tokens.", "Goodwill scores.", "Use them differently.", "RESOURCE_FUNCTION");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "Compare Influence and Goodwill.", "Every 4 Goodwill is 1 VP."), draft(numeric)))
                .hasMessageContaining("numeric");

        var outside = new RuleConceptComparisonRequest(
                "Influence", "Spend it.", "Goodwill", "Score it.", "Both are tokens.",
                "Different functions.", "Use each for its function.", "RESOURCE_FUNCTION", List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> resolver.resolve(request(
                        "Compare Influence and Goodwill.", "Influence and Goodwill are tokens."), draft(outside)))
                .hasMessageContaining("outside");
    }

    @Test
    void distinguishesCompatibleRulesWithDifferentActorsAndConditions() {
        var item = comparison(
                "visitor entry rule", "The visitor entry rule applies only when the visitor carries a pass.",
                "custodian entry rule", "The custodian entry rule applies only while performing maintenance.",
                "Both rules permit entry into the same area.",
                "They govern different actors under different conditions.",
                "Use the visitor rule only for a visitor with a pass; use the custodian rule only during maintenance.",
                "RULE_SCOPE");

        assertThat(resolver.resolve(request(
                        "Do the visitor entry rule and custodian entry rule conflict?",
                        "The visitor entry rule applies only when the visitor carries a pass. "
                                + "The custodian entry rule applies only while performing maintenance."),
                draft(item))).singleElement().extracting(result -> result.basis())
                .isEqualTo(ConceptComparisonBasis.RULE_SCOPE);

        var noBoundary = comparison(
                "visitor entry rule", "Visitors enter the area.",
                "custodian entry rule", "Custodians enter the area.",
                "Both concern entry.", "They use different words.", "The rules are different.", "RULE_SCOPE");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "Do the visitor entry rule and custodian entry rule conflict?",
                        "Visitors enter the area. Custodians enter the area."), draft(noBoundary)))
                .hasMessageContaining("applicability boundary");
    }

    private RuleConceptComparisonRequest comparison(
            String left, String leftDefinition, String right, String rightDefinition, String common,
            String difference, String boundary, String basis) {
        return new RuleConceptComparisonRequest(
                left, leftDefinition, right, rightDefinition, common, difference, boundary, basis,
                List.of(citationId));
    }

    private ModelRequest request(String question, String evidence) {
        return new ModelRequest(
                question, QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Comparison", evidence, 11, 11)));
    }

    private ModelDraft draft(RuleConceptComparisonRequest comparison) {
        return new ModelDraft(
                true, null, "The concepts differ.", "Compare their cited functions and boundary.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                comparison == null ? List.of() : List.of(comparison));
    }
}
