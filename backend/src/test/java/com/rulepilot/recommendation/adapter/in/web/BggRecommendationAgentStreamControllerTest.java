package com.rulepilot.recommendation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionTurn;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.TurnResult;
import com.rulepilot.recommendation.application.RecommendationConversationException;
import com.rulepilot.recommendation.application.RecommendationConversationException.Code;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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
        verify(agent, never()).converse(any(), any(), any(), any(), any());
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
        verify(agent, never()).converse(any(), any(), any(), any(), any());
    }

    @Test
    void resolvesTheAuthenticatedCaptureBeforeDispatchAndCapturesTheExactSentResult() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateAgentTraceService> traceServices = mock(ObjectProvider.class);
        PrivateAgentTraceService traceService = mock(PrivateAgentTraceService.class);
        CaptureHandle capture = mock(CaptureHandle.class);
        MockHttpSession session = new MockHttpSession();
        Principal principal = () -> "player";
        AtomicReference<Runnable> queued = new AtomicReference<>();
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        ConversationResponse response = conversationResponse("captured");
        when(traceServices.getIfAvailable()).thenReturn(traceService);
        when(traceService.current(principal, session)).thenReturn(capture);
        when(capture.enabled()).thenReturn(true);
        when(conversations.converse(
                        any(SessionTurn.class),
                        eq("en"),
                        eq("player"),
                        any(),
                        any(),
                        same(capture),
                        any(UUID.class)))
                .thenReturn(new TurnResult(conversationId, 2, clientTurnId, true, "en", response));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "en"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, queued::set, conversations, traceServices);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        var pending = mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .session(session)
                        .principal(principal)
                        .queryParam("locale", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "message", "continue",
                                "conversationId", conversationId,
                                "revision", 1,
                                "clientTurnId", clientTurnId))))
                .andExpect(request().asyncStarted())
                .andReturn();

        verify(traceService).current(principal, session);
        verify(capture).bind(any(ResourceRef.class));
        ArgumentCaptor<UserTurn> userTurn = ArgumentCaptor.forClass(UserTurn.class);
        verify(capture).userTurn(userTurn.capture());
        UUID traceTurnOperationId = userTurn.getValue().context().operationId();
        assertThat(traceTurnOperationId).isNotEqualTo(clientTurnId);
        assertThat(userTurn.getValue().context().resource().id()).isEqualTo(traceTurnOperationId);
        assertThat(userTurn.getValue().typedRequestJson())
                .contains(conversationId.toString(), clientTurnId.toString());
        assertThat(queued).hasValueSatisfying(task -> assertThat(task).isNotNull());
        queued.get().run();
        String stream = mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        verify(conversations).converse(
                any(SessionTurn.class),
                eq("en"),
                eq("player"),
                any(),
                any(),
                same(capture),
                eq(traceTurnOperationId));
        verify(conversations, never()).converse(
                any(SessionTurn.class), eq("en"), eq("player"), any(), any());
        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        verify(capture).publication(publication.capture());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        assertThat(json.readTree(publication.getValue().playerFacingJson()))
                .isEqualTo(json.readTree(eventData(stream, "result")));
        assertThat(json.readTree(publication.getValue().playerFacingJson()).path("replayed").asBoolean())
                .isTrue();
        assertThat(publication.getValue().context().parentOperationId()).isEqualTo(traceTurnOperationId);
    }

    @Test
    void executorRejectionTracesThePredispatchUserTurnGapAndExactErrorPayload() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateAgentTraceService> traceServices = mock(ObjectProvider.class);
        PrivateAgentTraceService traceService = mock(PrivateAgentTraceService.class);
        CaptureHandle capture = mock(CaptureHandle.class);
        MockHttpSession session = new MockHttpSession();
        Principal principal = () -> "player";
        UUID clientTurnId = UUID.randomUUID();
        when(traceServices.getIfAvailable()).thenReturn(traceService);
        when(traceService.current(principal, session)).thenReturn(capture);
        when(capture.enabled()).thenReturn(true);
        var controller = new BggRecommendationAgentStreamController(
                agent,
                presentation,
                ignored -> {
                    throw new IllegalStateException("executor rejected the turn");
                },
                conversations,
                traceServices);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        String stream = performStreamRequest(
                mockMvc, session, principal, UUID.randomUUID(), clientTurnId);

        ArgumentCaptor<UserTurn> userTurn = ArgumentCaptor.forClass(UserTurn.class);
        verify(capture).userTurn(userTurn.capture());
        ArgumentCaptor<BindingOrFailure> lifecycle = ArgumentCaptor.forClass(BindingOrFailure.class);
        verify(capture).bindingOrFailure(lifecycle.capture());
        assertThat(lifecycle.getValue().signal()).isEqualTo(LifecycleSignal.GAP);
        assertThat(lifecycle.getValue().code()).isEqualTo("RECOMMENDATION_STREAM_QUEUE_REJECTED");
        assertThat(lifecycle.getValue().context().operationId())
                .isEqualTo(userTurn.getValue().context().operationId());
        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        verify(capture).publication(publication.capture());
        assertThat(publication.getValue().channel()).isEqualTo(PublicationChannel.FALLBACK);
        assertThat(publication.getValue().context().parentOperationId())
                .isEqualTo(userTurn.getValue().context().operationId());
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readTree(publication.getValue().playerFacingJson()))
                .isEqualTo(json.readTree(eventData(stream, "error")));
        assertThat(eventData(stream, "error")).contains("recommendation_unavailable");
    }

    @Test
    void coordinatorConflictAndUnexpectedFailureTraceFailureAndTheirExactErrorPayloads() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateAgentTraceService> traceServices = mock(ObjectProvider.class);
        PrivateAgentTraceService traceService = mock(PrivateAgentTraceService.class);
        CaptureHandle capture = mock(CaptureHandle.class);
        MockHttpSession session = new MockHttpSession();
        Principal principal = () -> "player";
        when(traceServices.getIfAvailable()).thenReturn(traceService);
        when(traceService.current(principal, session)).thenReturn(capture);
        when(capture.enabled()).thenReturn(true);
        when(conversations.converse(
                        any(SessionTurn.class),
                        eq("en"),
                        eq("player"),
                        any(),
                        any(),
                        same(capture),
                        any(UUID.class)))
                .thenThrow(
                        new RecommendationConversationException(Code.REVISION_CONFLICT, "stale revision"),
                        new IllegalStateException("unexpected coordinator failure"));
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor(), conversations, traceServices);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        String conflict = performStreamRequest(
                mockMvc, session, principal, UUID.randomUUID(), UUID.randomUUID());
        String unexpected = performStreamRequest(
                mockMvc, session, principal, UUID.randomUUID(), UUID.randomUUID());

        assertThat(eventData(conflict, "error")).contains("revision_conflict");
        assertThat(eventData(unexpected, "error")).contains("recommendation_unavailable");
        ArgumentCaptor<BindingOrFailure> lifecycle = ArgumentCaptor.forClass(BindingOrFailure.class);
        verify(capture, times(2)).bindingOrFailure(lifecycle.capture());
        assertThat(lifecycle.getAllValues())
                .extracting(BindingOrFailure::signal)
                .containsOnly(LifecycleSignal.FAILURE);
        assertThat(lifecycle.getAllValues())
                .extracting(BindingOrFailure::code)
                .containsExactly(
                        "RECOMMENDATION_CONVERSATION_REVISION_CONFLICT",
                        "RECOMMENDATION_STREAM_FAILED");
        ArgumentCaptor<Publication> publications = ArgumentCaptor.forClass(Publication.class);
        verify(capture, times(2)).publication(publications.capture());
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readTree(publications.getAllValues().get(0).playerFacingJson()))
                .isEqualTo(json.readTree(eventData(conflict, "error")));
        assertThat(json.readTree(publications.getAllValues().get(1).playerFacingJson()))
                .isEqualTo(json.readTree(eventData(unexpected, "error")));
    }

    @Test
    void traceDisabledKeepsThePersistedStreamFailOpenOnTheLegacyConversationPath() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        var request = sessionRequest(conversationId, clientTurnId);
        when(conversations.converse(any(SessionTurn.class), eq("en"), eq("player"), any(), any()))
                .thenReturn(new TurnResult(
                        conversationId, 2, clientTurnId, false, "en", conversationResponse("trace disabled")));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "en"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor(), conversations);

        controller.converse(request, "en", () -> "player", mock(HttpSession.class));

        verify(conversations).converse(any(SessionTurn.class), eq("en"), eq("player"), any(), any());
        verify(conversations, never()).converse(
                any(SessionTurn.class), eq("en"), eq("player"), any(), any(), any(CaptureHandle.class));
    }

    @Test
    void traceLookupFailureDoesNotFailThePersistedRecommendationStream() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateAgentTraceService> traceServices = mock(ObjectProvider.class);
        PrivateAgentTraceService traceService = mock(PrivateAgentTraceService.class);
        HttpSession session = mock(HttpSession.class);
        Principal principal = () -> "player";
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        var request = sessionRequest(conversationId, clientTurnId);
        when(traceServices.getIfAvailable()).thenReturn(traceService);
        when(traceService.current(principal, session)).thenThrow(new IllegalStateException("trace store unavailable"));
        when(conversations.converse(any(SessionTurn.class), eq("en"), eq("player"), any(), any()))
                .thenReturn(new TurnResult(
                        conversationId, 2, clientTurnId, false, "en", conversationResponse("trace failed open")));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "en"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor(), conversations, traceServices);

        controller.converse(request, "en", principal, session);

        verify(conversations).converse(any(SessionTurn.class), eq("en"), eq("player"), any(), any());
        verify(conversations, never()).converse(
                any(SessionTurn.class), eq("en"), eq("player"), any(), any(), any(CaptureHandle.class));
    }

    @Test
    void acceptsALongNaturalTurnAndPassesItUnchangedToTheStreamingAgent() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        String accepted = "😀".repeat(1_500) + "  A\n中";
        when(agent.converse(any(), eq("zh-CN"), eq("player"), any(), any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, BoardGameRecommendationAgent.ConversationRequest.class);
            assertThat(command.message()).isEqualTo(accepted);
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
        var controller = new BggRecommendationAgentStreamController(
                agent, presentation, new SyncTaskExecutor());
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
        assertThat(stream).contains("event:result", "完整收到。");
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
                    BoardGameRecommendationAgent.ProgressAction.BROWSE_BGG_CATALOG,
                    18,
                    2,
                    2,
                    1,
                    1,
                    0,
                    8,
                    6,
                    3,
                    20));
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
                .contains("\"phase\":\"completed\"")
                .contains("\"action\":\"understand_request\"")
                .contains("searching_bgg_catalog")
                .contains("\"action\":\"browse_bgg_catalog\"")
                .contains("\"observedCandidates\":8")
                .contains("\"verifiedCandidates\":6")
                .contains("\"hardRejectedCandidates\":3")
                .doesNotContain("decisionCycle", "modelCalls", "actionCalls", "catalogCalls", "webResearchCalls")
                .contains("event:answer_part")
                .contains("event:result")
                .contains("没有未经验证的推荐")
                .containsSubsequence("event:progress", "event:answer_part", "event:result");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsConversationalTextWithoutExposingTheInternalFastRoute() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        when(agent.converse(any(), eq("zh-CN"), eq("player"), any(), any())).thenAnswer(invocation -> {
            Consumer<ProgressUpdate> progress = invocation.getArgument(3);
            Consumer<String> answerParts = invocation.getArgument(4);
            progress.accept(new ProgressUpdate(
                    ProgressStage.COMPOSING_RESPONSE,
                    BoardGameRecommendationAgent.ProgressPhase.STARTED,
                    BoardGameRecommendationAgent.ProgressAction.STREAM_NATURAL_REPLY,
                    2,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0));
            answerParts.accept("嗨，");
            answerParts.accept("嗨，今天想聊哪款桌游？");
            return new ConversationResponse(
                    Outcome.CONVERSATION,
                    DecisionMode.MODEL_FAST_PATH,
                    "嗨，今天想聊哪款桌游？",
                    RecommendationProfile.empty(),
                    null,
                    0,
                    0,
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
                        .content("{\"profile\":null,\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String stream = mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream)
                .contains("event:answer_part", "嗨，", "嗨，今天想聊哪款桌游？", "event:result")
                .contains("\"stage\":\"composing_response\"")
                .contains("\"action\":null")
                .doesNotContain("stream_natural_reply", "\"decisionCycle\":1", "\"modelCalls\":1")
                .containsSubsequence("嗨，", "嗨，今天想聊哪款桌游？", "event:result");
    }

    private static BggRecommendationAgentController.RecommendationConversationRequest sessionRequest(
            UUID conversationId, UUID clientTurnId) {
        return new BggRecommendationAgentController.RecommendationConversationRequest(
                null,
                "continue",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                conversationId,
                1L,
                clientTurnId);
    }

    private static ConversationResponse conversationResponse(String message) {
        return new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                message,
                RecommendationProfile.empty(),
                null,
                0,
                0,
                List.of());
    }

    private static String eventData(String stream, String eventName) {
        boolean result = false;
        for (String line : stream.lines().toList()) {
            if (line.equals("event:" + eventName)) {
                result = true;
            } else if (result && line.startsWith("data:")) {
                return line.substring("data:".length());
            }
        }
        throw new AssertionError("recommendation stream omitted its " + eventName + " payload");
    }

    private static String performStreamRequest(
            org.springframework.test.web.servlet.MockMvc mockMvc,
            MockHttpSession session,
            Principal principal,
            UUID conversationId,
            UUID clientTurnId) throws Exception {
        var pending = mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream")
                        .session(session)
                        .principal(principal)
                        .queryParam("locale", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "message", "continue",
                                "conversationId", conversationId,
                                "revision", 1,
                                "clientTurnId", clientTurnId))))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
