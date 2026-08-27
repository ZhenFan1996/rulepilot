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
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesModelWrittenLeadAndEvidenceBackedPartsForEverySeededCard() throws Exception {
        Fixture fixture = fixture(
                List.of(101, 102),
                new RecommendationProfile(3, 60, null, BggGameType.ALL, InteractionPreference.ANY),
                "三个人，六十分钟以内");
        String lead = "我按你给出的三人桌和一小时边界整理了两款，先看各自真正不同的抓手。";
        PublicationSeed seed = new PublicationSeed(List.of(102, 101), List.of(), 2);
        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                seed,
                Set.of("U1"));
        String evidence102 = evidenceId(permit, 102, "mechanics");
        String evidence101 = evidenceId(permit, 101, "publisherDescription");
        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead(lead, "U1"),
                        "cards", List.of(
                                card(
                                        102,
                                        "如果你们想把合作落到每一步的共同判断上，这款的行动结构更贴近今晚的方向。",
                                        evidence102,
                                        "它并不替你们消除分歧，反而要求大家愿意把有限信息说清楚。",
                                        evidence102),
                                card(
                                        101,
                                        "它把修复林间路径变成清晰的共同目标，新手比较容易抓住大家为何要配合。",
                                        evidence101,
                                        null,
                                        null)))),
                permit);

        var response = fixture.publication.publish(
                fixture.state,
                seed,
                narrative,
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
            assertThat(game.replyParts()).isNotEmpty();
            assertThat(game.replyParts().getFirst().role()).isEqualTo(ReplyPartRole.WHY_FIT);
            assertThat(game.replyParts()).allSatisfy(part -> {
                assertThat(part.claim().bggId()).isEqualTo(game.game().ranking().bggId());
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.PREFERENCE_INFERENCE);
                assertThat(part.claim().evidence()).isNotEmpty().allSatisfy(evidence ->
                        assertThat(evidence.bggId()).isEqualTo(game.game().ranking().bggId()));
                assertThat(part.claim().text()).isNotBlank();
            });
        });
        assertThat(response.games().getFirst().replyParts())
                .extracting(BoardGameRecommendationAgent.RecommendationReplyPart::role)
                .containsExactly(ReplyPartRole.WHY_FIT, ReplyPartRole.TRADEOFF);
        assertThat(response.games().get(1).replyParts())
                .extracting(BoardGameRecommendationAgent.RecommendationReplyPart::role)
                .containsExactly(ReplyPartRole.WHY_FIT);
        assertThat(response.games())
                .flatExtracting(game -> game.replyParts().stream()
                        .map(part -> part.claim().text())
                        .toList())
                .noneMatch(text -> text.contains("一条已核对") || text.contains("选择边界："));
        assertThat(fixture.state.finalResponseGameIds).containsExactlyInAnyOrder(101, 102);
        assertThat(fixture.state.finalResponseEvidenceIds).isNotEmpty();
        assertThat(fixture.state.actions)
                .containsExactly("WRITE_GROUNDED_RECOMMENDATION", "RECOMMEND_GAMES");
    }

    @Test
    void acceptsASelectedSlateLeadThatCitesMoreThanOneCardsAnnotationBudget() throws Exception {
        Fixture fixture = fixture(
                List.of(101, 102),
                new RecommendationProfile(3, 60, null, BggGameType.ALL, InteractionPreference.ANY),
                "三个人，六十分钟以内");
        PublicationSeed seed = new PublicationSeed(List.of(101, 102), List.of(), 2);
        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                seed,
                Set.of("U1"));
        List<String> leadEvidence = permit.allowedLeadEvidenceIds().stream().limit(7).toList();
        assertThat(leadEvidence).hasSizeGreaterThan(4);
        String evidence101 = evidenceId(permit, 101, "mechanics");
        String evidence102 = evidenceId(permit, 102, "mechanics");

        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", Map.of(
                                "text", "这段总述同时连接本轮桌况与两张候选各自通过核对的特点。",
                                "evidenceIds", leadEvidence),
                        "cards", List.of(
                                card(101, "第一张卡只使用自己拥有的证据。", evidence101, null, null),
                                card(102, "第二张卡也只使用自己拥有的证据。", evidence102, null, null)))),
                permit);

        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(response.assistantMessage()).contains("同时连接本轮桌况与两张候选");
        assertThat(response.games()).allSatisfy(game -> assertThat(game.replyParts()).isNotEmpty());
        assertThat(response.harness().actions())
                .containsExactly("WRITE_GROUNDED_RECOMMENDATION", "RECOMMEND_GAMES");
    }

    @Test
    void acceptsMoreThanFourOwnedCandidateObservationsWithoutDroppingTheCard() throws Exception {
        Fixture fixture = fixture(List.of(101));
        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);
        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        List<String> ownedEvidence = permit.allowedEvidenceByGame().get(101).keySet().stream()
                .limit(6)
                .toList();
        assertThat(ownedEvidence).hasSizeGreaterThan(4);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("bggId", 101);
        card.put("why", Map.of(
                "text", "这一段只引用本卡实际拥有的多项观察，不应因为安全无关的固定数量上限而丢失。",
                "evidenceIds", ownedEvidence));
        card.put("tradeoff", null);

        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead("先看这张通过约束与资料核对的候选。", ownedEvidence.getFirst()),
                        "cards", List.of(card))),
                permit);
        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).singleElement().satisfies(part ->
                        assertThat(part.claim().evidence())
                                .extracting(CandidateObservation::id)
                                .containsExactlyElementsOf(ownedEvidence)));
        assertThat(response.harness().actions())
                .containsExactly("WRITE_GROUNDED_RECOMMENDATION", "RECOMMEND_GAMES");
    }

    @Test
    void duplicateOwnedEvidenceStillRejectsOnlyTheAffectedCardNarrative() throws Exception {
        Fixture fixture = fixture(List.of(101));
        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);
        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        String evidence101 = evidenceId(permit, 101, "mechanics");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("bggId", 101);
        card.put("why", Map.of(
                "text", "重复同一引用不能伪装成更多支持。",
                "evidenceIds", List.of(evidence101, evidence101)));
        card.put("tradeoff", null);

        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead("候选卡仍可安全显示。", evidence101),
                        "cards", List.of(card))),
                permit);
        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(narrative.rejectedNarrativeParts()).isEqualTo(1);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).isEmpty());
        assertThat(response.harness().actions()).contains("RECOMMENDATION_NARRATIVE_PARTIAL");
    }

    @Test
    void selectsTheExplicitCountInSeedOrderAndReportsAnHonestAvailabilityShortfall() {
        Fixture fixture = fixture(List.of(101, 102));

        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                new PublicationSeed(List.of(102, 101), List.of(), 3));
        var response = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(List.of(102, 101), List.of(), 3),
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
    void missingNarrativeExplainsFailureWithoutDiscardingCards() {
        Fixture fixture = fixture(List.of(101));

        var missing = fixture.publication.publish(
                fixture.state,
                new PublicationSeed(List.of(101), List.of(), 1),
                "zh-CN");

        assertThat(missing.assistantMessage())
                .contains("硬条件核对", "自然讲解没有生成完成")
                .doesNotContain("失败", "fallback");
        assertThat(missing.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).isEmpty());
        assertThat(missing.harness().actions()).contains("RECOMMENDATION_NARRATIVE_UNAVAILABLE");
        assertThat(missing.harness().fallbackUsed()).isFalse();
    }

    @Test
    void modelNarrativeSelectsHardFitAndSoftConflictEvidenceWithoutTemplateCopy() throws Exception {
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

        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);
        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                seed,
                Set.of("U1"));
        String playerEvidence = evidenceId(permit, 101, "playerCount");
        String complexityEvidence = evidenceId(permit, 101, "complexity");
        String lead = "三人和一小时都是硬边界；真正需要你们判断的是今晚愿不愿意接住稍高的规则密度。";
        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead(lead, "U1"),
                        "cards", List.of(card(
                                101,
                                "三人桌落在它的支持范围内，人数不会先把这款排除。",
                                playerEvidence,
                                "不过你说最好别太重，而它的复杂度正是这次选择里要认真权衡的一项。",
                                complexityEvidence)))),
                permit);

        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.replyParts()).first().satisfies(part -> {
                assertThat(part.role()).isEqualTo(ReplyPartRole.WHY_FIT);
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.PREFERENCE_INFERENCE);
                assertThat(part.claim().relation()).isEqualTo(CandidateClaim.Relation.OBSERVED);
                assertThat(part.claim().text()).isEqualTo("三人桌落在它的支持范围内，人数不会先把这款排除。");
                assertThat(part.claim().evidence())
                        .extracting(CandidateObservation::attribute)
                        .containsExactly("playerCount");
            });
            assertThat(game.replyParts()).element(1).satisfies(part -> {
                assertThat(part.role()).isEqualTo(ReplyPartRole.TRADEOFF);
                assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.PREFERENCE_INFERENCE);
                assertThat(part.claim().relation()).isEqualTo(CandidateClaim.Relation.OBSERVED);
                assertThat(part.claim().text()).doesNotStartWith("选择边界：");
                assertThat(part.claim().evidence())
                        .extracting(CandidateObservation::attribute)
                        .containsExactly("complexity");
            });
        });
    }

    @Test
    void keepsModelWrittenExperienceNotesBoundToAttributedResearch() throws Exception {
        Fixture fixture = fixture(List.of(), RecommendationProfile.empty(), "请直接给我一款候选");
        fixture.state.addVerified(attributedOnlyGame(101));
        fixture.state.research = new Research(
                List.of(new GameResearch(
                        101,
                        List.of(new Observation("有玩家报告说首局节奏偏慢", List.of(1))))),
                List.of(new Source(1, "体验报告", "https://example.test/report", "example.test")));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101));

        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);
        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        String reportEvidence = permit.allowedEvidenceByGame().get(101).values().stream()
                .filter(value -> value.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT)
                .findFirst()
                .orElseThrow()
                .id();
        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead(
                                "这款方向明确，但首局节奏是你们今晚要预留耐心的地方。",
                                reportEvidence),
                        "cards", List.of(card(
                                101,
                                "有玩家体验报告把首局的推进描述得偏慢，适合愿意边玩边磨合的一桌。",
                                reportEvidence,
                                "这只是有来源的体验反馈，不代表你们这桌一定会遇到相同节奏。",
                                reportEvidence)))),
                permit);

        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).hasSize(2).allSatisfy(part -> {
                    assertThat(part.claim().type()).isEqualTo(CandidateClaim.Type.PREFERENCE_INFERENCE);
                    assertThat(part.claim().text()).doesNotContain("一条有来源的考虑依据是", "选择边界：");
                    assertThat(part.claim().evidence()).singleElement().satisfies(evidence -> {
                        assertThat(evidence.kind()).isEqualTo(CandidateObservation.Kind.ATTRIBUTED_REPORT);
                        assertThat(evidence.sourceIndexes()).containsExactly(1);
                    });
                }));
    }

    @Test
    void dropsOnlyTheCardNarrativeWhoseEvidenceBelongsToAnotherCandidate() throws Exception {
        Fixture fixture = fixture(List.of(101, 102));
        PublicationSeed seed = new PublicationSeed(List.of(101, 102), List.of(), 2);
        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        String evidence101 = evidenceId(permit, 101, "mechanics");
        String evidence102 = evidenceId(permit, 102, "mechanics");
        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead(
                                "两款都过了硬边界，但我只保留证据真正属于各自卡片的讲法。",
                                evidence101),
                        "cards", List.of(
                                card(101, "这段有本卡证据。", evidence101, null, null),
                                card(102, "这段错误地借了另一张卡的证据。", evidence101, null, null)))),
                permit);

        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(narrative.rejectedNarrativeParts()).isEqualTo(1);
        assertThat(response.games().getFirst().replyParts()).singleElement().satisfies(part ->
                assertThat(part.claim().evidence())
                        .extracting(CandidateObservation::id)
                        .containsExactly(evidence101));
        assertThat(response.games().get(1).replyParts()).isEmpty();
        assertThat(response.harness().actions()).contains("RECOMMENDATION_NARRATIVE_PARTIAL");
        assertThat(fixture.state.finalResponseEvidenceIds).containsExactly(evidence101);
        assertThat(fixture.state.finalResponseEvidenceIds).doesNotContain(evidence102);
    }

    @Test
    void dropsAnUnownedOptionalTradeoffWithoutDiscardingTheOwnedWhyPart() throws Exception {
        Fixture fixture = fixture(List.of(101, 102));
        PublicationSeed seed = new PublicationSeed(List.of(101, 102), List.of(), 2);
        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        String evidence101 = evidenceId(permit, 101, "mechanics");
        String evidence102 = evidenceId(permit, 102, "mechanics");
        Map<String, Object> firstCard = new LinkedHashMap<>();
        firstCard.put("bggId", 101);
        firstCard.put("why", Map.of(
                "text", "这段保留本卡自己的可靠理由。",
                "evidenceIds", List.of(evidence101)));
        firstCard.put("tradeoff", Map.of(
                "text", "这段混入另一张卡的证据，必须单独丢弃。",
                "evidenceIds", List.of(evidence101, evidence102)));

        var narrative = fixture.publication.validateNarrative(
                json.writeValueAsString(Map.of(
                        "lead", lead("两张卡都保留各自经过核对的理由。", evidence101),
                        "cards", List.of(
                                firstCard,
                                card(102, "第二张卡也有自己的可靠理由。", evidence102, null, null)))),
                permit);
        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(narrative.rejectedNarrativeParts()).isEqualTo(1);
        assertThat(response.games().getFirst().replyParts()).singleElement().satisfies(part -> {
            assertThat(part.role()).isEqualTo(ReplyPartRole.WHY_FIT);
            assertThat(part.claim().evidence())
                    .extracting(CandidateObservation::id)
                    .containsExactly(evidence101);
        });
        assertThat(response.games().get(1).replyParts()).singleElement();
        assertThat(response.harness().actions()).contains("RECOMMENDATION_NARRATIVE_PARTIAL");
        assertThat(fixture.state.finalResponseEvidenceIds)
                .containsExactlyInAnyOrder(evidence101, evidence102);
    }

    @Test
    void dropsOnlyTheLeadWhoseEvidenceIsOutsideTheCurrentRequestAndCandidatePermit() throws Exception {
        Fixture fixture = fixture(List.of(101));
        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);
        RecommendationPublication.Permit permit = fixture.publication.permit(
                fixture.state,
                seed,
                Set.of("U1"));
        String evidence101 = evidenceId(permit, 101, "mechanics");
        String invalidLead = "这一句冒用了不属于当前请求的用户证据。";
        String value = json.writeValueAsString(Map.of(
                "lead", lead(invalidLead, "U99"),
                "cards", List.of(card(101, "这段有本卡证据。", evidence101, null, null))));

        var narrative = fixture.publication.validateNarrative(value, permit);
        var response = fixture.publication.publish(fixture.state, seed, narrative, "zh-CN");

        assertThat(response.assistantMessage())
                .contains("自然讲解没有生成完成")
                .doesNotContain(invalidLead);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).singleElement().satisfies(part ->
                        assertThat(part.claim().evidence())
                                .extracting(CandidateObservation::id)
                                .containsExactly(evidence101)));
        assertThat(response.harness().actions()).contains("RECOMMENDATION_NARRATIVE_PARTIAL");
        assertThat(fixture.state.finalResponseEvidenceIds)
                .containsExactly(evidence101)
                .doesNotContain("U99");
    }

    @Test
    void rejectsAnUnverifiedOrHardIneligibleSeedBeforePublication() {
        Fixture fixture = fixture(List.of(101));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101, 999));

        assertFailure(
                () -> fixture.publication.permit(
                        fixture.state,
                        new PublicationSeed(List.of(999), List.of(), 1)),
                RecommendationPublication.Code.FINAL_ID_NOT_VERIFIED);

        Fixture ineligible = fixture(
                List.of(101),
                new RecommendationProfile(5, 60, null, BggGameType.ALL, InteractionPreference.ANY),
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

    private String evidenceId(
            RecommendationPublication.Permit permit,
            int bggId,
            String attribute) {
        return permit.allowedEvidenceByGame().get(bggId).values().stream()
                .filter(value -> attribute.equals(value.attribute()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Map<String, Object> card(
            int bggId,
            String whyText,
            String whyEvidenceId,
            String tradeoffText,
            String tradeoffEvidenceId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("bggId", bggId);
        card.put("why", Map.of(
                "text", whyText,
                "evidenceIds", List.of(whyEvidenceId)));
        card.put(
                "tradeoff",
                tradeoffText == null
                        ? null
                        : Map.of(
                                "text", tradeoffText,
                                "evidenceIds", List.of(tradeoffEvidenceId)));
        return card;
    }

    private Map<String, Object> lead(String text, String... evidenceIds) {
        return Map.of(
                "text", text,
                "evidenceIds", List.of(evidenceIds));
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
                selector, review, observations, runtime, json);
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
