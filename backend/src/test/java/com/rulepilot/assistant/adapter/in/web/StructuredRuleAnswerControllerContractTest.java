package com.rulepilot.assistant.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.application.GameSessionConversationService;
import com.rulepilot.assistant.application.PlayerFacingAnswerPresenter;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.PreparedAnswerRun;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.gamesession.GameSessionContextLookup;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

class StructuredRuleAnswerControllerContractTest {

    @Test
    void keepsOperationalReferencesOutsideTheSerializedPlayerAnswer() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID rulingId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "The violet dial advances after the copper track closes.",
                "That order is stated by the cited clause.",
                List.of(new RuleCitation(
                        chunkId, versionId, "TIMING", "Copper track",
                        "Advance the violet dial after the copper track closes.", 9, 9)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                rulingId,
                2L,
                null);
        var response = new StructuredRuleAnswerController.AnswerResponse(
                PlayerFacingAnswerPresenter.present(
                        answer, "When does the violet dial advance?", PlayerLocale.EN),
                turnId,
                StructuredRuleAnswerController.RulingReference.from(answer));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsBytes(response));
        String playerAnswer = json.path("answer").toString();

        assertThat(playerAnswer)
                .doesNotContain(versionId.toString(), chunkId.toString(), rulingId.toString())
                .doesNotContain("documentVersionId", "chunkId", "confirmedRulingId", "assistantRunId");
        assertThat(json.has("assistantRunId")).isFalse();
        assertThat(json.path("conversationTurnId").asText()).isEqualTo(turnId.toString());
        assertThat(json.path("rulingReference").path("citationIds").get(0).asText())
                .isEqualTo(chunkId.toString());

        var restored = new StructuredRuleAnswerController.ConversationTurnResponse(
                turnId,
                "When does the violet dial advance?",
                PlayerFacingAnswerPresenter.present(
                        answer, "When does the violet dial advance?", PlayerLocale.ZH_CN),
                Instant.parse("2026-08-16T00:00:00Z"),
                null,
                StructuredRuleAnswerController.RulingReference.from(answer));
        var restoredJson = mapper.readTree(mapper.writeValueAsBytes(restored));

        assertThat(restoredJson.path("answer")).isEqualTo(json.path("answer"));
        assertThat(restoredJson.path("answer").toString())
                .doesNotContain(versionId.toString(), chunkId.toString(), rulingId.toString())
                .doesNotContain("documentVersionId", "chunkId", "confirmedRulingId", "assistantRunId");

        UUID assistantRunId = UUID.randomUUID();
        CaptureHandle capture = mock(CaptureHandle.class);
        when(capture.enabled()).thenReturn(true);
        StructuredRuleAnswerController.capturePublication(
                capture,
                new StructuredRuleAnswerController.CompletedWebAnswer(response, assistantRunId, answer));
        ArgumentCaptor<Publication> publication = ArgumentCaptor.forClass(Publication.class);
        verify(capture).publication(publication.capture());
        assertThat(publication.getValue().channel()).isEqualTo(PublicationChannel.ANSWER);
        assertThat(publication.getValue().context().parentOperationId()).isEqualTo(assistantRunId);
        assertThat(mapper.readTree(publication.getValue().playerFacingJson())).isEqualTo(json);
    }

    @Test
    void projectsAuditActivityToAnAllowListedPlayerSafeStreamMessage() throws Exception {
        ActivitySnapshot raw = new ActivitySnapshot(
                4,
                ActivityType.TOOL,
                "nativeTool|read_rule_pages|internal-provider-argument",
                ActivityOutcome.SUCCEEDED,
                200,
                40,
                81,
                "raw tool result must not cross the stream boundary",
                Instant.parse("2026-08-21T00:00:00Z"));

        var projected = StructuredRuleAnswerController.playerActivity(raw, PlayerLocale.EN);
        String json = new ObjectMapper().writeValueAsString(projected);

        assertThat(json)
                .contains("\"actor\":\"rulebook_reader\"")
                .contains("\"stage\":\"reading_pages\"")
                .contains("\"message\":\"Reading the exact rulebook pages\"")
                .contains("\"status\":\"succeeded\"")
                .contains("\"nextAction\":\"Next: verify what the cited text actually supports\"")
                .doesNotContain("internal-provider-argument", "raw tool result", "operation", "summary");
    }

    @Test
    void terminalizesThePreparedRunWhenTheStreamExecutorRejectsTheHandoff() {
        StructuredRuleAnswerService answers = mock(StructuredRuleAnswerService.class);
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        var prepared = new PreparedAnswerRun(new AssistantRuns.RunSnapshot(
                runId,
                AssistantRunMode.QUESTION_ANSWER,
                versionId,
                "alice",
                AssistantRunState.RECEIVED,
                0,
                now,
                now,
                null,
                null));
        when(answers.prepareAnswerRun(any(), any(), eq("alice"), eq(null), any()))
                .thenReturn(prepared);
        TaskRejectedException rejection = new TaskRejectedException("answer stream queue is full");
        var controller = new StructuredRuleAnswerController(
                answers,
                mock(GameSessionContextLookup.class),
                mock(GameSessionConversationService.class),
                mock(AnswerFeedbackService.class),
                mock(AssistantRuns.class),
                task -> { throw rejection; },
                Optional.empty());
        Principal principal = () -> "alice";
        var request = new StructuredRuleAnswerController.AnswerRequest(
                "第一轮怎样开始？", null, null, null, "zh-CN");

        controller.answerStream(versionId, request, principal, mock(HttpSession.class));

        verify(answers).failPreparedAnswerBeforeExecution(
                eq(prepared),
                eq("alice"),
                eq("ANSWER_STREAM_QUEUE_REJECTED"),
                eq("Answer stream execution could not be scheduled"),
                eq(rejection),
                any());
    }
}
