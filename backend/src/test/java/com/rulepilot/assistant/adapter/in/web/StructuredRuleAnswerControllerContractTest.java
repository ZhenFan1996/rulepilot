package com.rulepilot.assistant.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    }
}
