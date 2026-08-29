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
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateReplyDraft;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationDraft;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationAgentState.RecommendationReplyDraft;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesModelAuthoredLeadAndCandidateNotesUnchangedWithAnHonestShortfall() {
        Fixture fixture = fixture(List.of(101, 102));
        fixture.state.pendingPublicationSeed = new PublicationSeed(List.of(101, 102), List.of(), 3);
        String lead = "  先说结论：这两款都满足已经确认的人数与时长边界，但走的是不同路线。目录事实只负责说明它们为什么进入候选，不会替你决定互动偏好。\n按你们今晚的节奏，我会先看卡片里分别写清的推荐理由和使用边界，再从两种方向中做选择。  ";
        String firstWhy = "Signal Grove 101 的人数和时长都落在你确认的范围里。";
        String secondWhy = "Signal Grove 102 同样满足三人、六十分钟内的硬条件。";
        PublicationDraft draft = new PublicationDraft(
                lead,
                List.of(evidenceId(fixture, 101), evidenceId(fixture, 102)),
                List.of(
                        candidateDraft(fixture, 101, firstWhy, null),
                        candidateDraft(fixture, 102, secondWhy, "它的公开资料没有替你决定偏好的互动感。")));

        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, draft);
        var response = fixture.publication.publish(fixture.state, permit, draft, "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(lead);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101, 102);
        assertThat(response.games().get(0).matches()).containsExactly(firstWhy);
        assertThat(response.games().get(0).tradeoffs()).isEmpty();
        assertThat(response.games().get(1).matches()).containsExactly(secondWhy);
        assertThat(response.games().get(1).tradeoffs())
                .containsExactly("它的公开资料没有替你决定偏好的互动感。");
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
                .containsExactly("RECOMMENDATION_VERIFIED_SET_SHORTFALL", "RECOMMEND_GAMES");
    }

    @Test
    void acceptsConciseModelProseButRejectsCandidateOrEvidenceOutsideTheVerifiedBoundary() {
        Fixture fixture = fixture(List.of(101, 102));
        fixture.state.pendingPublicationSeed = new PublicationSeed(List.of(101), List.of(), 1);

        PublicationDraft shortStatus = new PublicationDraft(
                "已推荐。",
                List.of(evidenceId(fixture, 101)),
                List.of(candidateDraft(fixture, 101, "人数和时长都有当前候选的目录事实支持。", null)));
        assertThat(fixture.publication.permit(fixture.state, shortStatus).selectedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(101);

        PublicationDraft shortCardNote = new PublicationDraft(
                "这是一段达到完整答复长度的模型回复，它只引用当前候选自己的已核验目录事实，也没有越过候选身份边界；不过卡片理由仍然过短，无法向玩家解释为什么值得考虑，所以整个正常发布动作必须被拒绝并由模型修复。",
                List.of(evidenceId(fixture, 101)),
                List.of(new CandidateReplyDraft(
                        101,
                        new RecommendationReplyDraft("合适。", List.of(evidenceId(fixture, 101))),
                        null)));
        assertThat(fixture.publication.permit(fixture.state, shortCardNote).selectedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(101);

        PublicationDraft outsideCandidate = new PublicationDraft(
                "这是一段长度完整的候选回复，但它故意选择了当前待发布集合以外的游戏。验证层必须先拒绝候选身份，不能因为自然语言听起来合理就放行，也不能由应用把它偷偷换成另一款已核验候选；模型只能按当前枚举重新提交。",
                List.of(evidenceId(fixture, 102)),
                List.of(candidateDraft(fixture, 102, "越权候选", null)));
        assertFailure(
                () -> fixture.publication.permit(fixture.state, outsideCandidate),
                RecommendationPublication.Code.PUBLICATION_SELECTION_OUTSIDE_PENDING);

        String foreignEvidence = evidenceId(fixture, 102);
        PublicationDraft foreignLeadEvidenceDraft = new PublicationDraft(
                "顶层事实也不能借用另一款游戏的证据。即使回复长度已经足够、措辞也很自然，只要它绑定的 observation 不属于最终选择的候选，发布边界就必须拒绝，而不能改写文字或把另一款游戏的资料冒充成当前候选事实。",
                List.of(foreignEvidence),
                List.of(candidateDraft(fixture, 101, "同候选理由", null)));
        assertFailure(
                () -> fixture.publication.permit(fixture.state, foreignLeadEvidenceDraft),
                RecommendationPublication.Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);

        PublicationDraft foreignEvidenceDraft = new PublicationDraft(
                "这段顶层回复只绑定当前候选自己的目录事实，长度也满足完整答复边界；但是下面卡片理由故意引用了另一款游戏的 observation，因此发布仍应失败。应用不能拼接修补，必须让模型重新提交同候选证据。",
                List.of(evidenceId(fixture, 101)),
                List.of(new CandidateReplyDraft(
                        101,
                        new RecommendationReplyDraft("看似合理但证据属于另一款游戏", List.of(foreignEvidence)),
                        null)));
        assertFailure(
                () -> fixture.publication.permit(fixture.state, foreignEvidenceDraft),
                RecommendationPublication.Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);
    }

    @Test
    void rejectsAnUnverifiedOrHardIneligibleSeedBeforeFallbackPublication() {
        Fixture fixture = fixture(List.of(101));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101, 999));

        assertFailure(
                () -> fixture.publication.permit(
                        fixture.state,
                        new PublicationSeed(List.of(999), List.of(), 1)),
                RecommendationPublication.Code.FINAL_ID_NOT_VERIFIED);

        Fixture ineligible = fixture(
                List.of(101),
                new RecommendationProfile(
                        ConstraintRange.hardExact(5),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
                "五个人，一小时内");
        when(ineligible.runtime.recommendableIds(ineligible.state)).thenReturn(List.of(101));
        assertFailure(
                () -> ineligible.publication.permit(
                        ineligible.state,
                        new PublicationSeed(List.of(101), List.of(), 1)),
                RecommendationPublication.Code.FINAL_ID_FAILS_HARD_GATES);
        assertThat(ineligible.state.finalResponseGameIds).isEmpty();
        assertThat(ineligible.state.finalResponseEvidenceIds).isEmpty();
    }

    private CandidateReplyDraft candidateDraft(
            Fixture fixture,
            int bggId,
            String why,
            String tradeoff) {
        RecommendationReplyDraft whyDraft = new RecommendationReplyDraft(
                why,
                List.of(evidenceId(fixture, bggId)));
        RecommendationReplyDraft tradeoffDraft = tradeoff == null
                ? null
                : new RecommendationReplyDraft(tradeoff, List.of(evidenceId(fixture, bggId)));
        return new CandidateReplyDraft(bggId, whyDraft, tradeoffDraft);
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
                false,
                3);
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
                selector, review, observations, runtime);
        return new Fixture(state, runtime, observations, publication);
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

    private record Fixture(
            RecommendationAgentState state,
            RecommendationReActLoop runtime,
            RecommendationActions observations,
            RecommendationPublication publication) {}
}
