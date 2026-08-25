package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesNaturalParagraphsAndCandidateScopedCardJudgmentsThroughOnePermit() throws Exception {
        Fixture fixture = fixture(List.of(101));
        RecommendationPublication.Permit permit = fixture.publication.permit(
                decision(101),
                fixture.state,
                seed(101));
        List<String> streamed = new ArrayList<>();
        RecommendationPublication.Session session = fixture.publication.open(
                permit, fixture.state, "zh-CN", streamed::add);

        session.acceptBlock(block(
                "MESSAGE",
                "NARRATIVE",
                null,
                List.of("B101:mechanics"),
                "我会先看《Signal Grove》。它的合作机制让三个人很容易围绕同一个目标讨论。"));
        session.acceptBlock(block(
                "MESSAGE",
                "NARRATIVE",
                null,
                List.of("B101:complexity"),
                "复杂度是 2.8，入门压力不算大；不过仍需要有人把核心流程讲清楚。"));
        session.acceptBlock(block(
                "CARD",
                "WHY_FIT",
                101,
                List.of("B101:mechanics", "B101:complexity"),
                "合作机制有共同焦点，复杂度也在这桌可接受的范围内。"));
        session.acceptBlock(block(
                "CARD",
                "TRADEOFF",
                101,
                List.of("B101:complexity"),
                "它不是完全不需要讲规则的派对游戏。"));

        var response = session.finish();
        String completeReply = "我会先看《Signal Grove》。它的合作机制让三个人很容易围绕同一个目标讨论。"
                + "\n\n复杂度是 2.8，入门压力不算大；不过仍需要有人把核心流程讲清楚。";
        assertThat(streamed).containsExactly(
                "我会先看《Signal Grove》。它的合作机制让三个人很容易围绕同一个目标讨论。",
                completeReply);
        assertThat(response.assistantMessage()).isEqualTo(completeReply);
        assertThat(response.recommendationLead()).isEqualTo(completeReply);
        assertThat(response.games()).singleElement().satisfies(recommended -> {
            assertThat(recommended.game().ranking().bggId()).isEqualTo(101);
            assertThat(recommended.replyParts()).hasSize(2).allSatisfy(part ->
                    assertThat(part.claim().evidence())
                            .extracting(com.rulepilot.recommendation.CandidateObservation::bggId)
                            .containsOnly(101));
        });
        assertThat(fixture.state.finalResponseGameIds).containsExactly(101);
        assertThat(fixture.state.finalResponseEvidenceIds)
                .containsExactlyInAnyOrder("B101:mechanics", "B101:complexity");
        assertThat(fixture.state.actions).containsExactly("RECOMMEND_GAMES");
    }

    @Test
    void rejectsTheRetiredDuplicateSelectionEvidenceContract() {
        Fixture fixture = fixture(List.of(101));

        assertFailure(
                () -> fixture.publication.permit(
                        json("""
                                {"requestedCount":1,"selections":[{"bggId":101,"internalEvidenceIds":["B101:mechanics"]}],"referenceBggIds":[]}
                                """),
                        fixture.state,
                        seed(101)),
                RecommendationPublication.Code.OBJECT_FIELDS_INVALID);
        assertUncommitted(fixture.state);
    }

    @Test
    void rejectsAnUnverifiedIdentityEvenWhenASeedClaimsIt() {
        Fixture fixture = fixture(List.of(101));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101, 999));

        assertFailure(
                () -> fixture.publication.permit(
                        decision(999),
                        fixture.state,
                        seed(999)),
                RecommendationPublication.Code.FINAL_ID_NOT_VERIFIED);
        assertUncommitted(fixture.state);
    }

    @Test
    void localizesALateProseFailureWithoutLeakingTheDraftOrDiscardingVerifiedCards() throws Exception {
        Fixture fixture = fixture(List.of(101, 102));
        RecommendationPublication.Permit permit = fixture.publication.permit(
                decision(101),
                fixture.state,
                new PublicationSeed(List.of(101, 102), List.of(), CandidateUse.PUBLISH_CARDS));
        List<String> streamed = new ArrayList<>();
        RecommendationPublication.Session session = fixture.publication.open(
                permit, fixture.state, "zh-CN", streamed::add);
        session.acceptBlock(block(
                "MESSAGE", "NARRATIVE", null, List.of("B101:mechanics"), "先看这一款。"));

        assertFailure(
                () -> session.acceptBlock(block(
                        "CARD", "WHY_FIT", 101, List.of("B102:playerCount"), "错误地借用了另一款的事实。")),
                RecommendationPublication.Code.BLOCK_EVIDENCE_NOT_GROUNDED);
        assertThat(streamed)
                .as("model prose remains provisional until the complete publication validates")
                .isEmpty();
        assertUncommitted(fixture.state);

        var recovered = session.finishWithVerifiedCandidates("BLOCK_EVIDENCE_NOT_GROUNDED");

        assertThat(recovered.outcome()).isEqualTo(BoardGameRecommendationAgent.Outcome.RECOMMENDATIONS);
        assertThat(recovered.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101);
        assertThat(recovered.assistantMessage())
                .contains("候选已经通过身份和基础资料校验")
                .doesNotContain("先看这一款", "错误地借用");
        assertThat(streamed).containsExactly(recovered.assistantMessage());
        assertThat(fixture.state.finalResponseGameIds).containsExactly(101);
        assertThat(fixture.state.finalResponseEvidenceIds).isEmpty();
        assertThat(fixture.state.actions)
                .containsExactly(
                        "RECOMMENDATION_PUBLICATION_RECOVERED:BLOCK_EVIDENCE_NOT_GROUNDED",
                        "RECOMMEND_GAMES");
    }

    @Test
    void acceptsEveryKnownSameCandidateObservationNeededByOneFactualParagraph() throws Exception {
        Fixture fixture = fixture(List.of(101));
        RecommendationPublication.Permit permit = fixture.publication.permit(
                decision(101),
                fixture.state,
                seed(101));
        RecommendationPublication.Session session = fixture.publication.open(
                permit, fixture.state, "zh-CN", ignored -> {});
        List<String> paragraphEvidence = List.of(
                "B101:playerCount",
                "B101:durationMinutes",
                "B101:complexity",
                "B101:bggType",
                "B101:categories",
                "B101:mechanics",
                "B101:minimumAge",
                "B101:bestWith",
                "B101:recommendedWith");

        session.acceptBlock(block(
                "MESSAGE",
                "NARRATIVE",
                101,
                paragraphEvidence,
                "这一段同时概括人数、时长、复杂度、类型和机制，但每条事实仍只属于这一款游戏。"));
        session.acceptBlock(block(
                "CARD", "WHY_FIT", 101, List.of("B101:mechanics"), "合作机制给了三个人共同目标。"));

        var response = session.finish();
        assertThat(response.outcome()).isEqualTo(BoardGameRecommendationAgent.Outcome.RECOMMENDATIONS);
        assertThat(fixture.state.finalResponseEvidenceIds).containsAll(paragraphEvidence);
    }

    @Test
    void responseProjectionFailureCanRecoverWithVerifiedCardsWithoutHalfPublishedState() throws Exception {
        Fixture fixture = fixture(List.of(101));
        RecommendationPublication.Permit permit = fixture.publication.permit(
                decision(101),
                fixture.state,
                seed(101));
        RecommendationPublication.Session session = fixture.publication.open(
                permit, fixture.state, "zh-CN", ignored -> {});
        session.acceptBlock(block(
                "MESSAGE", "NARRATIVE", null, List.of("B101:mechanics"), "先看这一款。"));
        session.acceptBlock(block(
                "CARD", "WHY_FIT", 101, List.of("B101:mechanics"), "合作机制适合一起讨论。"));
        when(fixture.runtime.responseSources(eq(fixture.state), anyList(), anySet()))
                .thenThrow(new IllegalStateException("source projection failed"));

        assertThatThrownBy(session::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source projection failed");
        assertUncommitted(fixture.state);

        var recovered = session.finishWithVerifiedCandidates("PUBLICATION_MODEL_FAILED");

        assertThat(recovered.outcome()).isEqualTo(BoardGameRecommendationAgent.Outcome.RECOMMENDATIONS);
        assertThat(recovered.games()).singleElement().satisfies(game ->
                assertThat(game.game().ranking().bggId()).isEqualTo(101));
        assertThat(fixture.state.finalResponseGameIds).containsExactly(101);
        assertThat(fixture.state.finalResponseEvidenceIds).isEmpty();
    }

    @Test
    void neverTurnsAPlayerDeliveryFailureIntoAFakeRecoveredPublication() throws Exception {
        Fixture fixture = fixture(List.of(101));
        RecommendationPublication.Permit permit = fixture.publication.permit(
                decision(101),
                fixture.state,
                seed(101));
        RecommendationPublication.Session session = fixture.publication.open(
                permit,
                fixture.state,
                "zh-CN",
                ignored -> {
                    throw new IllegalStateException("client disconnected");
                });
        session.acceptBlock(block(
                "MESSAGE", "NARRATIVE", null, List.of(), "这段完整内容已经通过模型协议校验。"));
        session.acceptBlock(block(
                "CARD", "WHY_FIT", 101, List.of("B101:mechanics"), "合作机制适合一起讨论。"));

        assertThatThrownBy(session::finish)
                .isInstanceOf(RecommendationPublication.DeliveryFailure.class)
                .hasRootCauseMessage("client disconnected");
        assertUncommitted(fixture.state);
    }

    private Fixture fixture(List<Integer> verifiedIds) {
        RecommendationProfile profile = new RecommendationProfile(
                3, 60, null, BggGameType.ALL, InteractionPreference.ANY);
        RecommendationAgentState state = new RecommendationAgentState(
                new ConversationRequest(profile, "三个人，六十分钟以内"),
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

    private JsonNode decision(int bggId) {
        var root = json.createObjectNode();
        root.put("requestedCount", 1);
        var selection = root.putArray("selections").addObject();
        selection.put("bggId", bggId);
        root.putArray("referenceBggIds");
        return root;
    }

    private JsonNode block(
            String surface,
            String role,
            Integer bggId,
            List<String> evidenceIds,
            String text) {
        var block = json.createObjectNode();
        block.put("surface", surface);
        block.put("role", role);
        if (bggId == null) block.putNull("bggId");
        else block.put("bggId", bggId);
        evidenceIds.forEach(block.putArray("internalEvidenceIds")::add);
        block.put("text", text);
        return block;
    }

    private JsonNode json(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private PublicationSeed seed(int bggId) {
        return new PublicationSeed(List.of(bggId), List.of(), CandidateUse.PUBLISH_CARDS);
    }

    private void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            RecommendationPublication.Code expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(
                RecommendationPublication.InvalidPublication.class,
                failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private void assertUncommitted(RecommendationAgentState state) {
        assertThat(state.finalResponseGameIds).isEmpty();
        assertThat(state.finalResponseEvidenceIds).isEmpty();
        assertThat(state.actions).isEmpty();
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

    private record Fixture(
            RecommendationAgentState state,
            RecommendationReActLoop runtime,
            RecommendationPublication publication) {}
}
