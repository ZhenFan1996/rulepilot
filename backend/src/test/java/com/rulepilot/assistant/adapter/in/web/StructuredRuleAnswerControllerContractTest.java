package com.rulepilot.assistant.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.application.PlayerFacingAnswerPresenter;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerControllerContractTest {

    @Test
    void keepsTheStreamOpenUntilThePersistedRunDeadlineAndCompletionGrace() {
        Instant runDeadline = Instant.parse("2026-08-29T10:00:00Z");

        assertThat(StructuredRuleAnswerController.runBoundEmitter().getTimeout()).isZero();
        assertThat(StructuredRuleAnswerController.streamCompletionDeadline(runDeadline))
                .isEqualTo(runDeadline.plusSeconds(5));
        assertThat(StructuredRuleAnswerController.streamCompletionDeadlineReached(
                        runDeadline, runDeadline.plusSeconds(4)))
                .isFalse();
        assertThat(StructuredRuleAnswerController.streamCompletionDeadlineReached(
                        runDeadline, runDeadline.plusSeconds(5)))
                .isTrue();
    }

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
                        answer, "When does the violet dial advance?", PlayerLocale.EN),
                Instant.parse("2026-08-16T00:00:00Z"),
                null,
                StructuredRuleAnswerController.RulingReference.from(answer));
        var restoredJson = mapper.readTree(mapper.writeValueAsBytes(restored));

        assertThat(restoredJson.path("answer")).isEqualTo(json.path("answer"));
        assertThat(restoredJson.path("answer").toString())
                .doesNotContain(versionId.toString(), chunkId.toString(), rulingId.toString())
                .doesNotContain("documentVersionId", "chunkId", "confirmedRulingId", "assistantRunId");
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
    void keepsRecoverableValidationRejectionsInProgressInsteadOfReportingAStoppedAnswer() {
        List<String> recoverableOperations = List.of(
                "nativeCompletionRequirement",
                "nativeEmptyCompletion",
                "nativeCompletionProtocol",
                "nativeActionProtocol",
                "nativeToolSchema",
                "nativeObs|search_rule_evidence|schema-hash|call-id");

        recoverableOperations.forEach(operation -> {
            ActivitySnapshot rejected = activity(operation, ActivityOutcome.REJECTED, "private validation detail");

            var projected = StructuredRuleAnswerController.playerActivity(rejected, PlayerLocale.ZH_CN);

            assertThat(projected.actor()).isEqualTo("answer_validator");
            assertThat(projected.stage()).isEqualTo("correcting_answer");
            assertThat(projected.status()).isEqualTo("running");
            assertThat(projected.message()).isEqualTo("回答草稿未通过校验，答疑助手正在修正");
            assertThat(projected.nextAction()).isEqualTo("下一步：用有效操作或有依据的回答继续");
        });
    }

    @Test
    void reportsRepeatedIdenticalObservationAsAStoppedSupplementarySearch() {
        ActivitySnapshot stalled = activity(
                "nativeObservationNoProgress|read_rule_pages",
                ActivityOutcome.REJECTED,
                "raw observation fingerprint");

        var projected = StructuredRuleAnswerController.playerActivity(stalled, PlayerLocale.EN);

        assertThat(projected.actor()).isEqualTo("answer_validator");
        assertThat(projected.stage()).isEqualTo("evidence_search_stalled");
        assertThat(projected.status()).isEqualTo("rejected");
        assertThat(projected.message())
                .contains("Supplementary evidence search stopped")
                .contains("answer can continue with evidence already checked")
                .doesNotContain("fingerprint");
        assertThat(projected.nextAction())
                .isEqualTo("Next: compose and validate from the evidence already checked");
    }

    @Test
    void mapsInvalidAnswerContextToARepairFirstRecoveryWithoutLeakingTheException() throws Exception {
        var request = request("Can I keep this card?", "en");

        var error = StructuredRuleAnswerController.streamFailure(
                new StructuredRuleAnswerController.InvalidAnswerContextException(
                        "private session id and document mismatch"), request);

        assertThat(error.code()).isEqualTo("answer_context_invalid");
        assertThat(error.recovery().message()).contains("Reopen Q&A");
        assertThat(error.recovery().actionLabel()).isEqualTo("Reopen Q&A");
        assertThat(error.recovery().draft()).isEqualTo("Can I keep this card?");
        assertThat(error.recovery().canRetryUnchanged()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(error))
                .doesNotContain("private session id", "document mismatch", "IllegalArgumentException");
    }

    @Test
    void mapsUnknownWorkflowFailureToReviewFirstRecoveryWithoutLeakingTheException() throws Exception {
        var request = request("这个效果什么时候结算？", "zh-CN");

        var error = StructuredRuleAnswerController.streamFailure(
                new IllegalArgumentException("provider payload and secret transport detail"), request);

        assertThat(error.code()).isEqualTo("answer_workflow_failed");
        assertThat(error.recovery().message()).contains("内部流程错误");
        assertThat(error.recovery().actionLabel()).isEqualTo("检查问题");
        assertThat(error.recovery().draft()).isEqualTo("这个效果什么时候结算？");
        assertThat(error.recovery().canRetryUnchanged()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(error))
                .doesNotContain("provider payload", "secret transport detail", "IllegalArgumentException");
    }

    @Test
    void identifiesExecutorUnavailabilityAsSafeToRetryAfterRecovery() {
        var error = StructuredRuleAnswerController.serviceUnavailable(
                request("How many actions may I take?", "en-US"));

        assertThat(error.code()).isEqualTo("answer_service_unavailable");
        assertThat(error.recovery().message()).contains("has not started");
        assertThat(error.recovery().actionLabel()).isEqualTo("Retry later");
        assertThat(error.recovery().draft()).isEqualTo("How many actions may I take?");
        assertThat(error.recovery().canRetryUnchanged()).isTrue();
    }

    @Test
    void identifiesRunDeadlineAsSafeToRetryAndKeepsTheQuestionDraft() {
        var error = StructuredRuleAnswerController.timeout("什么时候补充手牌？", PlayerLocale.ZH_CN);

        assertThat(error.code()).isEqualTo("answer_timeout");
        assertThat(error.recovery().message()).contains("运行时限");
        assertThat(error.recovery().actionLabel()).isEqualTo("重试这个问题");
        assertThat(error.recovery().draft()).isEqualTo("什么时候补充手牌？");
        assertThat(error.recovery().canRetryUnchanged()).isTrue();
    }

    private static ActivitySnapshot activity(
            String operation, ActivityOutcome outcome, String privateSummary) {
        return new ActivitySnapshot(
                7,
                ActivityType.VALIDATION,
                operation,
                outcome,
                100,
                30,
                45,
                privateSummary,
                Instant.parse("2026-08-29T12:00:00Z"));
    }

    private static StructuredRuleAnswerController.AnswerRequest request(String question, String language) {
        return new StructuredRuleAnswerController.AnswerRequest(
                question,
                UUID.randomUUID(),
                null,
                null,
                language);
    }
}
