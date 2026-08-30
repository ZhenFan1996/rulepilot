package com.rulepilot.recommendation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BggRecommendationAgentStreamControllerTest {

    @Test
    void keepsAFiveSecondTransportGraceBeyondTheConfiguredAgentBudget() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        var controller = new BggRecommendationAgentStreamController(
                agent,
                presentation,
                ignored -> {},
                null,
                new BoardGameRecommendationProperties(
                        8, 3, new BigDecimal("0.66"), Duration.ofMinutes(2)));

        SseEmitter emitter = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(null, "四人区控"),
                "zh-CN",
                () -> "player");

        assertThat(emitter.getTimeout()).isEqualTo(125_000L);
        verify(agent, never()).converse(any(), any(), any(), any(), any());
    }

    @Test
    void acknowledgesTheTurnBeforeQueuedRecommendationWorkCanStart() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        var controller = controller(agent, presentation, queued::set);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        var pending = mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .principal(() -> "player")
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"profile\":null,\"message\":\"四人区控\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(queued).hasValueSatisfying(task -> assertThat(task).isNotNull());
        assertThat(pending.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:progress")
                .contains("understanding_request")
                .contains("\"elapsedMs\":0");
        verify(agent, never()).converse(any(), any(), any(), any(), any());
    }

    @Test
    void acceptsALongNaturalTurnAndPassesItUnchangedToTheStreamingAgent() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        String accepted = "😀".repeat(1_500) + "  A\n中";
        when(agent.converse(any(), eq("zh-CN"), eq("player"), any(), any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, BoardGameRecommendationAgent.ConversationRequest.class);
            assertThat(command.message()).isEqualTo(accepted);
            Consumer<String> answerPart = invocation.getArgument(4);
            answerPart.accept("完整");
            answerPart.accept("完整收到。");
            return new ConversationResponse(
                    Outcome.CONVERSATION,
                    DecisionMode.MODEL_ASSISTED,
                    "完整收到。",
                    RecommendationProfile.empty(),
                    null,
                    0,
                    0,
                    List.of());
        });
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var controller = controller(agent, presentation, new SyncTaskExecutor());
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        var pending = mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .principal(() -> "player")
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsBytes(Map.of("message", accepted))))
                .andExpect(request().asyncStarted())
                .andReturn();

        String stream = mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream)
                .contains("event:answer_part", "{\"text\":\"完整\"}", "{\"text\":\"完整收到。\"}")
                .contains("event:result", "完整收到。")
                .containsSubsequence("event:answer_part", "event:result");
        verify(agent).converse(any(), eq("zh-CN"), eq("player"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsRealProgressBeforeTheValidatedFinalResponse() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        when(agent.converse(any(), eq("zh-CN"), eq("player"), any(), any())).thenAnswer(invocation -> {
            Consumer<ProgressUpdate> progress = invocation.getArgument(3);
            progress.accept(new ProgressUpdate(
                    ProgressStage.UNDERSTANDING_REQUEST,
                    BoardGameRecommendationAgent.ProgressPhase.COMPLETED,
                    BoardGameRecommendationAgent.ProgressAction.UNDERSTAND_REQUEST,
                    4,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0));
            progress.accept(new ProgressUpdate(
                    ProgressStage.SEARCHING_BGG_CATALOG,
                    BoardGameRecommendationAgent.ProgressPhase.COMPLETED,
                    BoardGameRecommendationAgent.ProgressAction.SEARCH_BGG_CATALOG,
                    18,
                    2,
                    2,
                    1,
                    1,
                    0,
                    8,
                    6,
                    3,
                    20,
                    new BoardGameRecommendationAgent.ProgressFocus(
                            BoardGameRecommendationAgent.ProgressFocusKind.CATALOG_MECHANICS,
                            List.of("Deck Building"))));
            return new ConversationResponse(
                    Outcome.NO_MATCH,
                    DecisionMode.MODEL_ASSISTED,
                    "没有未经验证的推荐。",
                    RecommendationProfile.empty(),
                    null,
                    179_737,
                    20,
                    List.of());
        });
        var controller = controller(agent, presentation, new SyncTaskExecutor());
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        var pending = mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .principal(() -> "player")
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"profile\":null,\"message\":\"四人区控\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String stream = mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream)
                .contains("event:progress")
                .contains("\"phase\":\"completed\"")
                .contains("\"action\":\"understand_request\"")
                .contains("searching_bgg_catalog")
                .contains("\"action\":\"search_bgg_catalog\"")
                .contains("\"focus\":{\"kind\":\"catalog_mechanics\",\"values\":[\"Deck Building\"]}")
                .contains("\"observedCandidates\":8")
                .contains("\"verifiedCandidates\":6")
                .contains("\"hardRejectedCandidates\":3")
                .contains("\"modelCalls\":0", "\"catalogCalls\":0", "\"webResearchCalls\":0")
                .doesNotContain("decisionCycle", "actionCalls")
                .contains("event:result")
                .contains("没有未经验证的推荐")
                .containsSubsequence("event:progress", "event:result");
    }

    private BggRecommendationAgentStreamController controller(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            TaskExecutor executor) {
        return new BggRecommendationAgentStreamController(
                agent,
                presentation,
                executor,
                null,
                new BoardGameRecommendationProperties(
                        8, 3, new BigDecimal("0.66"), Duration.ofMinutes(2)));
    }
}
