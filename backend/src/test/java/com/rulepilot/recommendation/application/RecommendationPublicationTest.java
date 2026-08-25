package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesTheTypedLeadAndTwoEvidenceBackedPartsForEverySeededCard() {
        Fixture fixture = fixture(
                List.of(101, 102),
                new RecommendationProfile(3, 60, null, BggGameType.ALL, InteractionPreference.ANY),
                "三个人，六十分钟以内");
        String lead = "我按你给出的三人桌和一小时边界整理了两款，先看各自的明确依据与选择边界。";

        var response = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(
                        List.of(102, 101),
                        List.of(),
                        CandidateUse.PUBLISH_CARDS,
                        2,
                        lead),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(lead);
        assertThat(response.recommendationLead()).isEqualTo(lead);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(102, 101);
        assertThat(response.games()).allSatisfy(game -> {
            assertThat(game.matches()).isEmpty();
            assertThat(game.tradeoffs()).isEmpty();
            assertThat(game.reasons()).isEmpty();
            assertThat(game.replyParts())
                    .extracting(BoardGameRecommendationAgent.RecommendationReplyPart::role)
                    .containsExactly(ReplyPartRole.WHY_FIT, ReplyPartRole.TRADEOFF);
            assertThat(game.replyParts()).allSatisfy(part -> {
                assertThat(part.claim().bggId()).isEqualTo(game.game().ranking().bggId());
                assertThat(part.claim().evidence()).isNotEmpty().allSatisfy(evidence ->
                        assertThat(evidence.bggId()).isEqualTo(game.game().ranking().bggId()));
                assertThat(part.claim().text()).isNotBlank();
            });
        });
        assertThat(fixture.state.finalResponseGameIds).containsExactlyInAnyOrder(101, 102);
        assertThat(fixture.state.finalResponseEvidenceIds).isNotEmpty();
        assertThat(fixture.state.actions).containsExactly("RECOMMEND_GAMES");
    }

    @Test
    void selectsTheExplicitCountInSeedOrderAndReportsAnHonestAvailabilityShortfall() {
        Fixture fixture = fixture(List.of(101, 102));

        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                new PublicationSeed(
                        List.of(102, 101),
                        List.of(),
                        CandidateUse.PUBLISH_CARDS,
                        3,
                        "先给你目前通过边界的候选。"));
        var response = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(
                        List.of(102, 101),
                        List.of(),
                        CandidateUse.PUBLISH_CARDS,
                        3,
                        "先给你目前通过边界的候选。"),
                "zh-CN");

        assertThat(permit.selectedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(102, 101);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(102, 101);
        assertThat(response.shortfall()).satisfies(shortfall -> {
            assertThat(shortfall.requestedCount()).isEqualTo(3);
            assertThat(shortfall.availableCount()).isEqualTo(2);
        });
        assertThat(response.harness().actions()).contains("RECOMMENDATION_VERIFIED_SET_SHORTFALL");
    }

    @Test
    void missingOrOverlongPlayerLeadUsesALocalNaturalLeadWithoutDiscardingCards() {
        Fixture fixture = fixture(List.of(101));
        String overlong = "很".repeat(RecommendationAgentState.MAX_PLAYER_LEAD_CODE_POINTS + 1);

        var missing = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(List.of(101), List.of(), CandidateUse.PUBLISH_CARDS, 1),
                "zh-CN");
        Fixture secondFixture = fixture(List.of(101));
        var invalid = secondFixture.publication.publish(
                secondFixture.state,
                new PublicationSeed(List.of(101), List.of(), CandidateUse.PUBLISH_CARDS, 1, overlong),
                "en");

        assertThat(missing.assistantMessage())
                .contains("已经核对", "选择边界")
                .doesNotContain("失败", "fallback");
        assertThat(invalid.assistantMessage())
                .contains("verified slate", "boundary")
                .doesNotContain(overlong, "fallback");
        assertThat(missing.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).hasSize(2));
        assertThat(invalid.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).hasSize(2));
        assertThat(missing.harness().fallbackUsed()).isFalse();
        assertThat(invalid.harness().fallbackUsed()).isFalse();
    }

    @Test
    void usesAnExplicitSatisfiedConstraintForWhyFitAndASoftConflictForTradeoff() {
        RecommendationProfile profile = new RecommendationProfile(
                ConstraintRange.hardExact(3),
                ConstraintRange.hardAtMost(60),
                new ConstraintRange<>(
                        null,
                        new BigDecimal("2.0"),
                        ConstraintRange.Strength.SOFT,
                        "最好别太重",
                        1),
                BggGameType.ALL,
                InteractionPreference.ANY);
        Fixture fixture = fixture(List.of(101), profile, "三个人，一小时内，最好别太重");

        var response = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(
                        List.of(101),
                        List.of(),
                        CandidateUse.PUBLISH_CARDS,
                        1,
                        "先看这张卡的硬条件和取舍。"),
                "zh-CN");

        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.replyParts()).first().satisfies(part -> {
                assertThat(part.role()).isEqualTo(ReplyPartRole.WHY_FIT);
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.CONSTRAINT_FIT);
                assertThat(part.claim().relation()).isEqualTo(CandidateClaim.Relation.SATISFIED);
            });
            assertThat(game.replyParts()).element(1).satisfies(part -> {
                assertThat(part.role()).isEqualTo(ReplyPartRole.TRADEOFF);
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.CONSTRAINT_FIT);
                assertThat(part.claim().strength()).isEqualTo(ConstraintRange.Strength.SOFT);
                assertThat(part.claim().relation()).isEqualTo(CandidateClaim.Relation.CONFLICT);
                assertThat(part.claim().evidence())
                        .extracting(CandidateObservation::attribute)
                        .containsExactly("complexity");
            });
        });
    }

    @Test
    void keepsResearchedExperienceAttributedInBothTheReasonAndItsBoundary() {
        Fixture fixture = fixture(List.of(), RecommendationProfile.empty(), "请直接给我一款候选");
        fixture.state.addVerified(attributedOnlyGame(101));
        fixture.state.research = new Research(
                List.of(new GameResearch(
                        101,
                        List.of(new Observation("有玩家报告说首局节奏偏慢", List.of(1))))),
                List.of(new Source(1, "体验报告", "https://example.test/report", "example.test")));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101));

        var response = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(
                        List.of(101),
                        List.of(),
                        CandidateUse.PUBLISH_CARDS,
                        1,
                        "先看有来源的体验边界。"),
                "zh-CN");

        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).allSatisfy(part -> {
                    assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.ATTRIBUTED_EXPERIENCE);
                    assertThat(part.claim().text()).containsAnyOf("有来源", "归因报告", "来源资料");
                    assertThat(part.claim().evidence()).singleElement().satisfies(evidence -> {
                        assertThat(evidence.kind()).isEqualTo(CandidateObservation.Kind.ATTRIBUTED_REPORT);
                        assertThat(evidence.sourceIndexes()).containsExactly(1);
                    });
                }));
    }

    @Test
    void rejectsAnUnverifiedOrHardIneligibleSeedBeforePublication() {
        Fixture fixture = fixture(List.of(101));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101, 999));

        assertFailure(
                () -> fixture.publication.permit(
                        fixture.state,
                        new PublicationSeed(List.of(999), List.of(), CandidateUse.PUBLISH_CARDS, 1)),
                RecommendationPublication.Code.FINAL_ID_NOT_VERIFIED);

        Fixture ineligible = fixture(
                List.of(101),
                new RecommendationProfile(5, 60, null, BggGameType.ALL, InteractionPreference.ANY),
                "五个人，一小时内");
        when(ineligible.runtime.recommendableIds(ineligible.state)).thenReturn(List.of(101));
        assertFailure(
                () -> ineligible.publication.permit(
                        ineligible.state,
                        new PublicationSeed(List.of(101), List.of(), CandidateUse.PUBLISH_CARDS, 1)),
                RecommendationPublication.Code.FINAL_ID_FAILS_HARD_GATES);
        assertThat(ineligible.state.finalResponseGameIds).isEmpty();
        assertThat(ineligible.state.finalResponseEvidenceIds).isEmpty();
    }

    private Fixture fixture(List<Integer> verifiedIds) {
        return fixture(
                verifiedIds,
                new RecommendationProfile(3, 60, null, BggGameType.ALL, InteractionPreference.ANY),
                "三个人，六十分钟以内");
    }

    private Fixture fixture(
            List<Integer> verifiedIds,
            RecommendationProfile profile,
            String message) {
        RecommendationAgentState state = new RecommendationAgentState(
                new ConversationRequest(profile, message),
                System.nanoTime(),
                null,
                false,
                3);
        verifiedIds.stream().map(this::game).forEach(state::addVerified);
        BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(properties());
        RecommendationReActLoop runtime = mock(RecommendationReActLoop.class);
        when(runtime.recommendableIds(state)).thenReturn(verifiedIds);
        when(runtime.chinese("zh-CN")).thenReturn(true);
        when(runtime.responseSources(eq(state), anyList(), anySet())).thenReturn(List.of());
        RecommendationEvidenceReview review = new RecommendationEvidenceReview(json, runtime);
        RecommendationActions observations = new RecommendationActions(
                null, selector, properties(), json, review, runtime);
        RecommendationPublication publication = new RecommendationPublication(
                selector, review, observations, runtime);
        return new Fixture(state, runtime, publication);
    }

    private void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            RecommendationPublication.Code expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(
                RecommendationPublication.InvalidPublication.class,
                failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private BoardGameRecommendationProperties properties() {
        return new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Signal Grove " + id,
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.8"),
                        List.of("Strategy"),
                        List.of("Cooperative Game"),
                        45,
                        60,
                        10,
                        10,
                        "4",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of(),
                        "Players restore paths through the grove.",
                        ""));
    }

    private Game attributedOnlyGame(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of()),
                new Details(
                        "Signal Grove " + id,
                        "",
                        "",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "",
                        ""));
    }

    private record Fixture(
            RecommendationAgentState state,
            RecommendationReActLoop runtime,
            RecommendationPublication publication) {}
}
