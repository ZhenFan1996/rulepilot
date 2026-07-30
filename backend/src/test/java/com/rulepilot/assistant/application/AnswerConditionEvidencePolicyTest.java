package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerConditionEvidencePolicyTest {

    @Test
    void requiresTheEnglishEvidenceThatBestPreservesThePlayersCondition() {
        UUID setup = UUID.randomUUID();
        UUID direct = UUID.randomUUID();
        ModelRequest request = request(
                "What happens when two players choose equal values?",
                new EvidenceInput(setup, "SETUP", "Setup", "Give each player a numbered card.", 1, 1),
                new EvidenceInput(
                        direct,
                        "ROUND_STRUCTURE",
                        "Equal choices",
                        "When players choose equal values, follow the printed resolution order.",
                        4,
                        4));

        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(setup))).isTrue();
        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(direct))).isFalse();
    }

    @Test
    void requiresTheChineseEvidenceThatBestPreservesThePlayersCondition() {
        UUID overview = UUID.randomUUID();
        UUID direct = UUID.randomUUID();
        ModelRequest request = request(
                "红色区域填满时如何继续？",
                new EvidenceInput(overview, "COMPONENTS", "区域", "版图上有红色区域和蓝色区域。", 2, 2),
                new EvidenceInput(direct, "RULES", "区域填满", "红色区域填满时，移除最左侧标记并继续回合。", 6, 6));

        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(overview))).isTrue();
        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(direct))).isFalse();
    }

    @Test
    void doesNotCreateACitationRequirementWithoutCompetingEvidence() {
        UUID only = UUID.randomUUID();
        ModelRequest request = request(
                "如果供应区为空怎么办？",
                new EvidenceInput(only, "RULES", "供应区", "供应区为空时重新补充。", 3, 3));

        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(only))).isFalse();
    }

    @Test
    void doesNotTreatAnOrdinaryInventoryQuestionAsAConditionalProcedure() {
        UUID inventory = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        ModelRequest request = request(
                "How many markers are included?",
                new EvidenceInput(inventory, "COMPONENTS", "Markers", "The game includes twelve markers.", 1, 1),
                new EvidenceInput(turn, "ROUND_STRUCTURE", "Turn", "Place one marker during a turn.", 5, 5));

        assertThat(AnswerConditionEvidencePolicy.needsDirectCitation(request, List.of(turn))).isFalse();
    }

    private ModelRequest request(String question, EvidenceInput... evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, PlayerLocale.ZH_CN),
                List.of(evidence));
    }
}
