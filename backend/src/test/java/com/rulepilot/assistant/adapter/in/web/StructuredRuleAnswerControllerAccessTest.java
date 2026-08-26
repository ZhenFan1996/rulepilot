package com.rulepilot.assistant.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.application.DocumentNativeToolAccess;
import com.rulepilot.assistant.application.GameSessionConversationService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.gamesession.GameSessionContextLookup;
import com.rulepilot.gamesession.GameSessionContextLookup.SessionContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class StructuredRuleAnswerControllerAccessTest {

    private final StructuredRuleAnswerService answers = mock(StructuredRuleAnswerService.class);
    private final GameSessionContextLookup sessions = mock(GameSessionContextLookup.class);
    private final GameSessionConversationService conversations = mock(GameSessionConversationService.class);
    private final AnswerFeedbackService feedback = mock(AnswerFeedbackService.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
    private final PublicRulebookReferenceLookup publicRulebooks = mock(PublicRulebookReferenceLookup.class);
    private final AtomicReference<Runnable> submitted = new AtomicReference<>();
    private final TaskExecutor executor = submitted::set;
    private StructuredRuleAnswerController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new StructuredRuleAnswerController(
                answers,
                sessions,
                conversations,
                feedback,
                runs,
                new DocumentNativeToolAccess(documents, publicRulebooks),
                executor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void acceptsPrivateAnswerStreamOnlyForTheOwnerOfAReadyVersion() throws Exception {
        UUID versionId = UUID.randomUUID();
        readyVersion(versionId, "alice");

        mockMvc.perform(answerStream(versionId, "alice", null))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        assertThat(submitted.get()).isNotNull();
        verifyNoInteractions(answers, sessions);
    }

    @Test
    void hidesAnotherOwnersVersionBeforeSchedulingAnswerWork() throws Exception {
        UUID versionId = UUID.randomUUID();
        readyVersion(versionId, "bob");

        mockMvc.perform(answerStream(versionId, "alice", null))
                .andExpect(status().isNotFound());

        assertThat(submitted.get()).isNull();
        verifyNoInteractions(answers, sessions);
    }

    @Test
    void hidesMissingAndNotReadyVersionsBeforeSchedulingAnswerWork() throws Exception {
        UUID missingVersionId = UUID.randomUUID();
        mockMvc.perform(answerStream(missingVersionId, "alice", null))
                .andExpect(status().isNotFound());

        UUID processingVersionId = UUID.randomUUID();
        when(documents.findVersion(processingVersionId)).thenReturn(Optional.of(
                new VersionScope(processingVersionId, UUID.randomUUID(), "INDEXING", "alice")));
        mockMvc.perform(answerStream(processingVersionId, "alice", null))
                .andExpect(status().isNotFound());

        assertThat(submitted.get()).isNull();
        verifyNoInteractions(answers, sessions);
    }

    @Test
    void publicLessonExposureDoesNotOpenThePrivateAnswerStream() throws Exception {
        UUID versionId = UUID.randomUUID();
        readyVersion(versionId, "corpus-owner");
        when(publicRulebooks.findReference(versionId)).thenReturn(Optional.of(
                new PublicRulebookReferenceLookup.Reference(
                        versionId, UUID.randomUUID(), "Public rules", null, null)));

        mockMvc.perform(answerStream(versionId, "visitor", null))
                .andExpect(status().isNotFound());

        assertThat(submitted.get()).isNull();
        verifyNoInteractions(answers, sessions);
    }

    @Test
    void keepsOwnedMatchingSessionConversationSemanticsAfterTheVersionGate() {
        UUID versionId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        readyVersion(versionId, "alice");
        when(sessions.findOwned(sessionId, "alice")).thenReturn(Optional.of(
                new SessionContext(
                        sessionId,
                        UUID.randomUUID(),
                        versionId,
                        Set.of(),
                        3,
                        2,
                        "ACTION",
                        1,
                        "ACTIVE")));
        when(conversations.priorTurnReference(sessionId, "alice", versionId)).thenReturn(Optional.empty());
        StructuredRuleAnswer answer = unavailableAnswer(versionId);
        when(answers.answerWithRun(
                        eq("Can I move now?"),
                        any(),
                        eq("alice"),
                        eq(sessionId),
                        any()))
                .thenReturn(new AnswerCreation(UUID.randomUUID(), answer));
        when(conversations.record(sessionId, "Can I move now?", answer, "alice"))
                .thenReturn(new GameSessionConversationTurn(
                        UUID.randomUUID(), sessionId, "Can I move now?", answer, "alice", Instant.now()));

        controller.answerStream(
                versionId,
                new StructuredRuleAnswerController.AnswerRequest(
                        "Can I move now?", sessionId, null, null, "en"),
                () -> "alice");
        submitted.get().run();

        verify(sessions).findOwned(sessionId, "alice");
        verify(conversations).priorTurnReference(sessionId, "alice", versionId);
        verify(answers).answerWithRun(
                eq("Can I move now?"),
                argThat(context -> context.documentVersionId().equals(versionId)),
                eq("alice"),
                eq(sessionId),
                any());
        verify(conversations).record(sessionId, "Can I move now?", answer, "alice");
    }

    @Test
    void keepsRejectingAnOwnedSessionBoundToAnotherDocumentVersion() {
        UUID versionId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        readyVersion(versionId, "alice");
        when(sessions.findOwned(sessionId, "alice")).thenReturn(Optional.of(
                new SessionContext(
                        sessionId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Set.of(),
                        3,
                        2,
                        "ACTION",
                        1,
                        "ACTIVE")));

        controller.answerStream(
                versionId,
                new StructuredRuleAnswerController.AnswerRequest(
                        "Can I move now?", sessionId, null, null, "en"),
                () -> "alice");
        submitted.get().run();

        verify(sessions).findOwned(sessionId, "alice");
        verify(answers, never()).answerWithRun(any(), any(), any(), any(), any());
    }

    @Test
    void directJsonAnswerUsesTheSamePrivateVersionBoundary() {
        UUID versionId = UUID.randomUUID();
        readyVersion(versionId, "bob");

        assertThatThrownBy(() -> controller.answer(
                        versionId,
                        new StructuredRuleAnswerController.AnswerRequest(
                                "Can I move now?", null, null, null, "en"),
                        () -> "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verifyNoInteractions(answers, sessions);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder answerStream(
            UUID versionId, String username, UUID sessionId) {
        String sessionField = sessionId == null ? "" : ", \"gameSessionId\": \"" + sessionId + "\"";
        return post("/api/v1/document-versions/{versionId}/answers/stream", versionId)
                .principal(() -> username)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"question\":\"Can I move now?\",\"language\":\"en\"" + sessionField + "}");
    }

    private void readyVersion(UUID versionId, String owner) {
        when(documents.findVersion(versionId)).thenReturn(Optional.of(
                new VersionScope(versionId, UUID.randomUUID(), "READY", owner)));
    }

    private StructuredRuleAnswer unavailableAnswer(UUID versionId) {
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.INSUFFICIENT_EVIDENCE,
                "I cannot verify that from the cited rules.",
                "The available evidence is insufficient.",
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                null,
                false,
                null,
                null,
                null);
    }
}
