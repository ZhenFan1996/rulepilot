package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.SituationCheckRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerSituationCheckResolverTest {

    private final AnswerSituationCheckResolver resolver = new AnswerSituationCheckResolver();
    private final UUID citation = UUID.randomUUID();

    @Test
    void acceptsOnlyAnEmptySituationCheckList() {
        assertThat(resolver.resolve(request(), draft(List.of()))).isEmpty();
    }

    @Test
    void rejectsModelGeneratedLiveTableFactsEvenWhenTheyAreCited() {
        SituationCheckRequest proposed = new SituationCheckRequest(
                "The relay is connected.", "CONFIRMED", "我已经接通中继器", List.of(citation));

        assertThatThrownBy(() -> resolver.resolve(request(), draft(List.of(proposed))))
                .hasMessageContaining("not accepted");
    }

    private ModelRequest request() {
        return new ModelRequest(
                "我现在可以启动吗？",
                QuestionType.SITUATION_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        citation, "RULE", "Activation", "Start only while the relay is connected.", 4, 4)));
    }

    private ModelDraft draft(List<SituationCheckRequest> checks) {
        return new ModelDraft(
                true, null, "需要玩家确认桌面事实。", "规则条件来自证据。",
                List.of(citation), List.of(), "MEDIUM", "DIRECT_RULE",
                List.of(), checks);
    }
}
