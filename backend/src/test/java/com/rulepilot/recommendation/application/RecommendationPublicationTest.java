package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validatesRawArgumentsOnceThenPublishesAllModelAuthoredTextUnchanged() {
        Fixture fixture = fixture(List.of(101, 102));
        fixture.state.pendingPublicationSeed = new PublicationSeed(List.of(101, 102), List.of(), 3);
        String lead = "  先说结论：这两款都满足已经确认的人数与时长边界，但走的是不同路线。目录事实只负责说明它们为什么进入候选，不会替你决定互动偏好。\n按你们今晚的节奏，我会先看卡片里分别写清的推荐理由和使用边界，再从两种方向中做选择。  ";
        String firstWhy = "Signal Grove 101 的人数和时长都落在你确认的范围里。";
        String secondWhy = "Signal Grove 102 同样满足三人、六十分钟内的硬条件。";
        String secondTradeoff = "它的公开资料没有替你决定偏好的互动感。";
        String arguments = recommendationJson(
                lead,
                List.of(evidenceId(fixture, 101), evidenceId(fixture, 102)),
                List.of(
                        candidate(fixture, 101, firstWhy, null),
                        candidate(fixture, 102, secondWhy, secondTradeoff)));

        RecommendationPublication.PreparedPublication prepared =
                fixture.publication.prepare(fixture.state, arguments);
        var response = fixture.publication.publish(fixture.state, prepared, "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(lead);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101, 102);
        assertThat(response.games().get(0).matches()).containsExactly(firstWhy);
        assertThat(response.games().get(0).tradeoffs()).isEmpty();
        assertThat(response.games().get(1).matches()).containsExactly(secondWhy);
        assertThat(response.games().get(1).tradeoffs()).containsExactly(secondTradeoff);
        assertThat(response.games()).allSatisfy(game -> {
            assertThat(game.claims()).isNotEmpty().allSatisfy(claim ->
                    assertThat(claim.type()).isEqualTo(CandidateClaim.Type.CONSTRAINT_FIT));
            assertThat(game.replyParts().getFirst().role()).isEqualTo(ReplyPartRole.WHY_FIT);
            assertThat(game.replyParts()).allSatisfy(part -> {
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.PREFERENCE_INFERENCE);
                assertThat(part.claim().evidence())
                        .isNotEmpty()
                        .allSatisfy(evidence -> assertThat(evidence.bggId())
                                .isEqualTo(game.game().ranking().bggId()));
            });
        });
        assertThat(response.shortfall()).satisfies(shortfall -> {
            assertThat(shortfall.requestedCount()).isEqualTo(3);
            assertThat(shortfall.availableCount()).isEqualTo(2);
        });
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions())
                .containsExactly(
                        "MODEL_AUTHORED_RECOMMENDATION",
                        "RECOMMENDATION_VERIFIED_SET_SHORTFALL",
                        "RECOMMEND_GAMES");
    }

    @Test
    void reportsTheExactDeepestCandidateAndEvidenceFailuresFromRawArguments() {
        Fixture fixture = fixture(List.of(101, 102));
        fixture.state.pendingPublicationSeed = new PublicationSeed(List.of(101), List.of(), 1);

        RecommendationPublication.PreparedPublication concise = fixture.publication.prepare(
                fixture.state,
                recommendationJson(
                        "已推荐。",
                        List.of(evidenceId(fixture, 101)),
                        List.of(candidate(fixture, 101, "合适。", null))));
        assertThat(concise.permit().selectedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(101);

        assertFailure(
                () -> fixture.publication.prepare(
                        fixture.state,
                        recommendationJson(
                                "候选漏掉了必需的适配理由。",
                                List.of(evidenceId(fixture, 101)),
                                List.of(new LinkedHashMap<>(Map.of("bggId", 101))))),
                RecommendationPublication.Code.RECOMMENDATION_REQUIRED_FIELD_MISSING,
                "$.selections[0].why");

        assertFailure(
                () -> fixture.publication.prepare(
                        fixture.state,
                        recommendationJson(
                                "候选越过了当前待发布集合。",
                                List.of(evidenceId(fixture, 102)),
                                List.of(candidate(fixture, 102, "越权候选", null)))),
                RecommendationPublication.Code.PUBLICATION_SELECTION_OUTSIDE_PENDING,
                "$.selections[0].bggId");

        String foreignEvidence = evidenceId(fixture, 102);
        assertFailure(
                () -> fixture.publication.prepare(
                        fixture.state,
                        recommendationJson(
                                "顶层事实不能借用另一款游戏的证据。",
                                List.of(foreignEvidence),
                                List.of(candidate(fixture, 101, "同候选理由", null)))),
                RecommendationPublication.Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED,
                "$.playerReplyEvidenceIds[0]");

        Map<String, Object> wrongEvidenceCandidate = candidate(fixture, 101, "证据属于另一款游戏。", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> why = (Map<String, Object>) wrongEvidenceCandidate.get("why");
        why.put("internalEvidenceIds", List.of(foreignEvidence));
        assertFailure(
                () -> fixture.publication.prepare(
                        fixture.state,
                        recommendationJson(
                                "顶层事实只绑定当前候选。",
                                List.of(evidenceId(fixture, 101)),
                                List.of(wrongEvidenceCandidate))),
                RecommendationPublication.Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED,
                "$.selections[0].why.internalEvidenceIds[0]");
    }

    @Test
    void rejectsASelectionThatFailsCurrentHardGatesWithoutPublishingAnything() {
        Fixture fixture = fixture(
                List.of(101),
                new RecommendationProfile(
                        ConstraintRange.hardExact(5),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
                "五个人，一小时内");
        fixture.state.pendingPublicationSeed = new PublicationSeed(List.of(101), List.of(), 1);
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101));

        assertFailure(
                () -> fixture.publication.prepare(
                        fixture.state,
                        recommendationJson(
                                "当前候选不满足硬条件。",
                                List.of(evidenceId(fixture, 101)),
                                List.of(candidate(fixture, 101, "不能发布", null)))),
                RecommendationPublication.Code.FINAL_ID_FAILS_HARD_GATES,
                "$.selections[0].bggId");
        assertThat(fixture.state.finalResponseGameIds).isEmpty();
        assertThat(fixture.state.finalResponseEvidenceIds).isEmpty();
    }

    private Map<String, Object> candidate(
            Fixture fixture,
            int bggId,
            String why,
            String tradeoff) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("bggId", bggId);
        candidate.put("why", note(why, evidenceId(fixture, bggId)));
        if (tradeoff != null) candidate.put("tradeoff", note(tradeoff, evidenceId(fixture, bggId)));
        return candidate;
    }

    private Map<String, Object> note(String text, String evidenceId) {
        return new LinkedHashMap<>(Map.of(
                "text", text,
                "internalEvidenceIds", List.of(evidenceId)));
    }

    private String recommendationJson(
            String playerReply,
            List<String> playerReplyEvidenceIds,
            List<Map<String, Object>> candidates) {
        try {
            return json.writeValueAsString(Map.of(
                    "playerReply", playerReply,
                    "playerReplyEvidenceIds", playerReplyEvidenceIds,
                    "selections", candidates));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String evidenceId(Fixture fixture, int bggId) {
        return fixture.observations
                .narrativeObservations(fixture.state.verified.get(bggId), fixture.state.research)
                .keySet()
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private Fixture fixture(List<Integer> verifiedIds) {
        return fixture(
                verifiedIds,
                new RecommendationProfile(
                        ConstraintRange.hardExact(3),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
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
                false);
        verifiedIds.stream().map(this::game).forEach(state::addVerified);
        BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(properties());
        RecommendationReActLoop runtime = mock(RecommendationReActLoop.class);
        when(runtime.recommendableIds(state)).thenReturn(verifiedIds);
        when(runtime.chinese("zh-CN")).thenReturn(true);
        when(runtime.chinese("en")).thenReturn(false);
        when(runtime.responseSources(eq(state), anyList(), anySet())).thenReturn(List.of());
        RecommendationEvidenceReview review = new RecommendationEvidenceReview(json, runtime);
        RecommendationActions observations = new RecommendationActions(
                null, selector, properties(), json, review, runtime);
        RecommendationPublication publication = new RecommendationPublication(
                selector, review, observations, runtime, json);
        return new Fixture(state, runtime, observations, publication);
    }

    private void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            RecommendationPublication.Code expected,
            String expectedPath) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(
                RecommendationPublication.InvalidPublication.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo(expected);
                    assertThat(failure.path()).isEqualTo(expectedPath);
                });
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
            RecommendationActions observations,
            RecommendationPublication publication) {}
}
