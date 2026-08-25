package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredTurn;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolName;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RecommendationStructuredPublicationTest {

    @Test
    void validatesTheDecisionThenStreamsCompleteNaturalBlocksWithoutAdvertisingATerminalTool() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        AtomicReference<Request> structuredRequest = new AtomicReference<>();
        List<String> streamed = new ArrayList<>();
        String firstParagraph = "先给你一个明确答案：我会从《Signal Grove》开始。";
        String secondParagraph = "它是合作游戏，三个人会围绕同一目标讨论。";
        String payload = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":101}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"先给你一个明确答案：我会从《Signal Grove》开始。"},{"surface":"MESSAGE","role":"NARRATIVE","bggId":101,"internalEvidenceIds":["B101:mechanics"],"text":"它是合作游戏，三个人会围绕同一目标讨论。"},{"surface":"CARD","role":"WHY_FIT","bggId":101,"internalEvidenceIds":["B101:mechanics"],"text":"合作机制让三个人始终围绕共同目标交流。"},{"surface":"CARD","role":"TRADEOFF","bggId":101,"internalEvidenceIds":["B101:complexity"],"text":"复杂度 2.8 不算重，但仍需要先讲清核心流程。"}]}
                """
                .strip();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            structuredRequest.set(invocation.getArgument(0));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> listener = invocation.getArgument(2);
            int decisionEnd = payload.indexOf(",\"replyBlocks\"") + 1;
            int firstBlockEnd = payload.indexOf("},{\"surface\":\"MESSAGE\"", decisionEnd) + 1;
            int secondBlockEnd = payload.indexOf("},{\"surface\":\"CARD\"", firstBlockEnd) + 1;
            listener.accept(payload.substring(0, decisionEnd));
            listener.accept(payload.substring(decisionEnd, firstBlockEnd));
            listener.accept(payload.substring(firstBlockEnd, secondBlockEnd));
            listener.accept(payload.substring(secondBlockEnd));
            return new StructuredTurn(payload, CompletionStatus.COMPLETE);
        });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们三个人，想合作但不想太重。先认真推荐一款，并告诉我取舍。"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(streamed).containsExactly(firstParagraph, firstParagraph + "\n\n" + secondParagraph);
        assertThat(response.assistantMessage()).isEqualTo(streamed.getLast());
        assertThat(response.recommendationLead()).isEqualTo(response.assistantMessage());
        assertThat(response.games()).singleElement().satisfies(recommended -> {
            assertThat(recommended.game().ranking().bggId()).isEqualTo(101);
            assertThat(recommended.replyParts())
                    .extracting(part -> part.role().name())
                    .containsExactly("WHY_FIT", "TRADEOFF");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(structuredRequest.get()).satisfies(request -> {
            assertThat(request.tools()).isEmpty();
            assertThat(request.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.NONE);
            assertThat(request.structuredOutput().name()).isEqualTo("recommendation_publication");
            assertThat(request.structuredOutput().jsonSchema())
                    .contains("decision", "replyBlocks", "internalEvidenceIds");
        });

        loop.stopBoundedCalls();
    }

    @Test
    void keepsVerifiedCardsWhenALateModelRationaleViolatesTheEvidenceBoundary() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        List<String> streamed = new ArrayList<>();
        String unsafeDraft = "先给你一段还没有完整校验的推荐理由。";
        String payload = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":101}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"先给你一段还没有完整校验的推荐理由。"},{"surface":"CARD","role":"WHY_FIT","bggId":101,"internalEvidenceIds":["B999:mechanics"],"text":"这句错误地引用了别的候选。"}]}
                """
                .strip();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> listener = invocation.getArgument(2);
            int invalidCard = payload.indexOf(",{\"surface\":\"CARD\"");
            listener.accept(payload.substring(0, invalidCard));
            listener.accept(payload.substring(invalidCard));
            return new StructuredTurn(payload, CompletionStatus.COMPLETE);
        });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "请给我一款可选择的合作游戏卡片。"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(101);
        assertThat(response.assistantMessage())
                .contains("候选已经通过身份和基础资料校验")
                .doesNotContain(unsafeDraft, "错误地引用");
        assertThat(streamed).containsExactly(response.assistantMessage());
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .contains(
                        "RECOMMENDATION_PUBLICATION_RECOVERED:BLOCK_EVIDENCE_NOT_GROUNDED",
                        "RECOMMEND_GAMES")
                .doesNotContain("UNAVAILABLE:PUBLICATION_MODEL_FAILED");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesVerifiedSeedCardsWhenTheFinalWriterFailsBeforeItsDecision() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        List<String> streamed = new ArrayList<>();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenThrow(
                new IllegalStateException("raw provider failure stays private"));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "请给我一款可以继续查看详情的候选卡。"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(101);
        assertThat(response.assistantMessage())
                .contains("候选已经通过身份和基础资料校验")
                .doesNotContain("raw provider failure");
        assertThat(streamed).containsExactly(response.assistantMessage());
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .contains(
                        "RECOMMENDATION_PUBLICATION_RECOVERED:PUBLICATION_MODEL_FAILED",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesVerifiedCardsWhenOptionalFinalProseUsesTheRemainingRunBudget() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        List<String> streamed = new ArrayList<>();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("writer was cancelled at the run deadline", interrupted);
            }
            throw new AssertionError("the final writer should have been cancelled");
        });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofMillis(250));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "请给我一款可以继续查看详情的候选卡。"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(101);
        assertThat(streamed).containsExactly(response.assistantMessage());
        assertThat(response.harness().actions())
                .contains(
                        "RECOMMENDATION_PUBLICATION_RECOVERED:PUBLICATION_TIME_BUDGET",
                        "RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("UNAVAILABLE:"));

        loop.stopBoundedCalls();
    }

    @Test
    void dropsFinalWriterOutputThatArrivesAfterVerifiedCardsRecoveredFromTheTimeBudget()
            throws InterruptedException {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releaseLatePublication = new CountDownLatch(1);
        CountDownLatch latePublicationAttempted = new CountDownLatch(1);
        List<String> streamed = new CopyOnWriteArrayList<>();
        String latePayload = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":101}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"这段文案来自已经超时的最终写作调用，不能覆盖恢复结果。"},{"surface":"CARD","role":"WHY_FIT","bggId":101,"internalEvidenceIds":["B101:mechanics"],"text":"合作机制适合共同讨论。"}]}
                """
                .strip();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            publicationStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseLatePublication.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // This fake provider deliberately ignores cancellation to exercise the lifecycle fence.
                }
            }
            @SuppressWarnings("unchecked")
            Consumer<String> listener = invocation.getArgument(2);
            try {
                listener.accept(latePayload);
            } finally {
                latePublicationAttempted.countDown();
            }
            return new StructuredTurn(latePayload, CompletionStatus.COMPLETE);
        });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofMillis(150));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        try {
            var response = loop.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "请给我一款可以继续查看详情的候选卡。"),
                    "zh-CN",
                    "player",
                    ignored -> {},
                    streamed::add);

            assertThat(publicationStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.harness().modelCalls()).isEqualTo(2);
            assertThat(streamed).containsExactly(response.assistantMessage());

            releaseLatePublication.countDown();
            assertThat(latePublicationAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(streamed).containsExactly(response.assistantMessage());
        } finally {
            releaseLatePublication.countDown();
            loop.stopBoundedCalls();
        }
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove",
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Signal Grove",
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
}
