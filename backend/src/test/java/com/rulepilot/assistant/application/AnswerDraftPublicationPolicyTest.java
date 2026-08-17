package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftPublicationPolicyTest {

    private final UUID citationId = UUID.randomUUID();

    @Test
    void preservesOrdinaryEvidenceLabelsInsteadOfTreatingThemAsAnInternalProtocol() {
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "Allowed（，见证据 E1）。",
                "The governing rule is cited by [E1].",
                List.of(citationId),
                List.of("Keep the semantic exception."),
                "HIGH",
                "model prose");

        var preparation = AnswerDraftPublicationPolicy.prepare(request(), draft);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.draft().shortVerdict()).isEqualTo(draft.shortVerdict());
        assertThat(preparation.draft().explanation()).isEqualTo(draft.explanation());
    }

    @Test
    void preservesCompliantProviderProseExactly() {
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "【裁决】Allowed（，但标点由作者决定）。",
                "【理由】**The cited clause** supplies the condition.\n【边界】Only this situation is decided.",
                List.of(citationId),
                List.of("Keep the semantic exception."),
                "HIGH",
                "model prose");

        var preparation = AnswerDraftPublicationPolicy.prepare(request(), draft);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.draft().shortVerdict()).isEqualTo(draft.shortVerdict());
        assertThat(preparation.draft().explanation()).isEqualTo(draft.explanation());
        assertThat(preparation.draft().exceptions()).isEqualTo(draft.exceptions());
        assertThat(preparation.draft().answerBasis()).isEqualTo("DIRECT_RULE");
    }

    @Test
    void assignsGroundedApplicationOnlyWhenAValidatedCalculationIsPresent() {
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "10 points.",
                "The application recomputes the total.",
                List.of(citationId),
                List.of(),
                "HIGH",
                "DIRECT_RULE",
                List.of(new CalculationRequest("floor(8 / 3) * 5")));

        assertThat(AnswerDraftPublicationPolicy.prepare(calculationRequest(), draft).draft().answerBasis())
                .isEqualTo("GROUNDED_APPLICATION");
    }

    private ModelRequest request() {
        return new ModelRequest(
                "What is the rule?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "Direct evidence.", 1, 1)));
    }

    private ModelRequest calculationRequest() {
        return new ModelRequest(
                "I have 8 resources. How many points do I get?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Scoring", "Score 5 per set of 3.", 1, 1)),
                java.util.Set.of(EvidenceNeed.DIRECT_RULE),
                AnswerAid.CALCULATION);
    }
}
