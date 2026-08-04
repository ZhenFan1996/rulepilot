package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerScopeResolverTest {

    private final AnswerScopeResolver resolver = new AnswerScopeResolver();
    private final UUID citationId = UUID.randomUUID();

    @Test
    void resolvesTwoPlayerComponentRestrictionFromTheStatedSetup() {
        RuleScopeRequest scope = scope(
                "Dominance cards in a two-player game.",
                "When playing with two players, do not use the dominance cards.",
                "We are playing a two-player game.",
                "MATCHES_SCOPE",
                "Do not use dominance cards.",
                "PLAYER_COUNT");

        assertThat(resolver.resolve(request("We are playing a two-player game. Can we use dominance cards?"), draft(scope)))
                .singleElement().satisfies(result -> {
                    assertThat(result.matchStatus()).isEqualTo(ScopeMatchStatus.MATCHES_SCOPE);
                    assertThat(result.basis()).isEqualTo(ScopeBasis.PLAYER_COUNT);
                    assertThat(result.effect()).contains("Do not use");
                });
    }

    @Test
    void acceptsAnExplicitFivePlayerVariantRulingAndRejectsAnUngroundedCount() {
        RuleScopeRequest scope = scope(
                "Role selection.",
                "If you have five players, play with all roles; if four or fewer, choose a variant.",
                "We have five players.",
                "MATCHES_SCOPE",
                "Use all roles; no reduced-player variant is needed.",
                "PLAYER_COUNT");
        assertThat(resolver.resolve(request("We have five players. Do we choose a variant or use all roles?"), draft(scope)))
                .singleElement().extracting(result -> result.matchStatus())
                .isEqualTo(ScopeMatchStatus.MATCHES_SCOPE);

        RuleScopeRequest invented = scope(
                "Role selection.",
                "If you have five players, play with all roles.",
                "We have four players.",
                "OUTSIDE_SCOPE",
                "The five-player rule does not govern this setup.",
                "PLAYER_COUNT");
        assertThatThrownBy(() -> resolver.resolve(
                request("We have five players. Which roles do we use?"), draft(invented)))
                .hasMessageContaining("not grounded");
    }

    @Test
    void preservesTheExplicitThreePlayerTieRewardException() {
        RuleScopeRequest scope = scope(
                "Combat rank rewards in a three-player game.",
                "Although the ordinary third reward is used only in a four-player game, players tied for second each receive the third reward.",
                "This is a three-player game and players are tied for second.",
                "MATCHES_SCOPE",
                "Each tied second-place player receives the third reward.",
                "PLAYER_COUNT_EXCEPTION");

        assertThat(resolver.resolve(request(
                        "In a three-player game, if players tie for second, do they receive the third reward?"), draft(scope)))
                .singleElement().satisfies(result -> {
                    assertThat(result.matchStatus()).isEqualTo(ScopeMatchStatus.MATCHES_SCOPE);
                    assertThat(result.basis()).isEqualTo(ScopeBasis.PLAYER_COUNT_EXCEPTION);
                    assertThat(result.effect()).contains("third reward");
                });
    }

    @Test
    void rejectsAnExceptionThatDropsTheGeneralCountOrInventsUniversalApplicability() {
        RuleScopeRequest missingGeneralCount = scope(
                "Combat rank rewards.",
                "Players tied for second each receive the third reward.",
                "This is a three-player game and players are tied for second.",
                "MATCHES_SCOPE",
                "Each tied second-place player receives the third reward.",
                "PLAYER_COUNT_EXCEPTION");
        assertThatThrownBy(() -> resolver.resolve(request(
                        "In a three-player game, if players tie for second, do they receive the third reward?"),
                draft(missingGeneralCount)))
                .hasMessageContaining("general count");

        RuleScopeRequest preserved = scope(
                "Combat rank rewards.",
                "The ordinary third reward is used only in a four-player game; tied second-place players each receive it.",
                "This is a three-player game and players are tied for second.",
                "MATCHES_SCOPE",
                "Each tied second-place player receives the third reward.",
                "PLAYER_COUNT_EXCEPTION");
        ModelDraft universal = new ModelDraft(
                true, null, "This applies regardless of player count.", "Use this at any player count.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(preserved));
        assertThatThrownBy(() -> resolver.resolve(request(
                        "In a three-player game, if players tie for second, do they receive the third reward?"),
                universal))
                .hasMessageContaining("universal");
    }

    @Test
    void rejectsProseOnlyScopeAnswersAndCitationsOutsideTheAnswer() {
        assertThatThrownBy(() -> resolver.resolve(
                request("Does this rule apply in a two-player game?"), draft(null)))
                .hasMessageContaining("omitted");

        RuleScopeRequest outside = new RuleScopeRequest(
                "Two-player setup.", "Only in a two-player game.", "This is a two-player game.",
                "MATCHES_SCOPE", "Apply the rule.", "PLAYER_COUNT", List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> resolver.resolve(
                request("Does this rule apply in a two-player game?"), draft(outside)))
                .hasMessageContaining("outside");
    }

    @Test
    void requiresAnExplicitMissingContextStatusInsteadOfGuessing() {
        RuleScopeRequest missing = scope(
                "Role-dependent setup.",
                "If there is no Cave player, still use the Event and Treasure deck.",
                "unknown",
                "NEEDS_CONTEXT",
                "Confirm whether a Cave player is present before applying this clause.",
                "ROLE_PRESENCE");

        assertThat(resolver.resolve(request("Do we still use the Event and Treasure deck?"), draft(missing)))
                .singleElement().extracting(result -> result.matchStatus())
                .isEqualTo(ScopeMatchStatus.NEEDS_CONTEXT);
    }

    private RuleScopeRequest scope(
            String context, String condition, String situation, String status, String effect, String basis) {
        return new RuleScopeRequest(context, condition, situation, status, effect, basis, List.of(citationId));
    }

    private ModelRequest request(String question) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Scope", "Explicit scope rule", 2, 2)));
    }

    private ModelDraft draft(RuleScopeRequest scope) {
        return new ModelDraft(
                true, null, "Apply the cited scope rule.", "Match the stated setup to the cited condition.",
                List.of(citationId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                scope == null ? List.of() : List.of(scope));
    }
}
