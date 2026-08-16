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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BggRecommendationAgentStreamControllerTest {

    @Test
    void keepsAThirtyFiveSecondTransportEnvelopeAroundTheThirtySecondAgentBudget() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, ignored -> {});

        SseEmitter emitter = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(null, "四人区控"),
                "zh-CN",
                () -> "player");

        assertThat(emitter.getTimeout()).isEqualTo(35_000L);
        verify(agent, never()).converse(any(), any(), any(), any());
    }

    @Test
    void acknowledgesTheTurnBeforeQueuedRecommendationWorkCanStart() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, queued::set);
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
        verify(agent, never()).converse(any(), any(), any(), any());
    }

    @Test
    void rejectsAnOversizedTurnBeforeStartingTheStreamOrCallingTheAgent() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor());
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RecommendationConversationExceptionHandler())
                .build();
        String rejected = "😀".repeat(500) + "中";

        mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .principal(() -> "player")
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsBytes(Map.of("message", rejected))))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("message_too_long"));

        verify(agent, never()).converse(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsRealProgressBeforeTheValidatedFinalResponse() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        when(agent.converse(any(), eq("zh-CN"), eq("player"), any())).thenAnswer(invocation -> {
            Consumer<ProgressUpdate> progress = invocation.getArgument(3);
            progress.accept(new ProgressUpdate(ProgressStage.SEARCHING_BGG_CATALOG, 18));
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
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor());
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
                .contains("searching_bgg_catalog")
                .contains("event:result")
                .contains("没有未经验证的推荐")
                .containsSubsequence("event:progress", "event:result");
    }
}
