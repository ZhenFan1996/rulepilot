package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerPermissionResolverTest {

    private final AnswerPermissionResolver resolver = new AnswerPermissionResolver();
    private final UUID citation = UUID.randomUUID();

    @Test
    void validatesTheCitationScopeOfASelectedPermissionRuling() {
        assertThat(resolver.resolve(
                        request(AnswerAid.PERMISSION),
                        draft("Yes, the cited clause permits it.", citation)))
                .containsExactly(citation);
    }

    @Test
    void routesByAcceptedAidInsteadOfInterpretingCanOrMayVocabulary() {
        assertThat(AnswerPermissionResolver.requiresPermission(request(AnswerAid.PERMISSION))).isTrue();
        assertThat(AnswerPermissionResolver.requiresPermission(request(AnswerAid.NONE))).isFalse();
        assertThat(resolver.resolve(request(AnswerAid.NONE), draft("Can and may appear here.", citation)))
                .isEmpty();
    }

    @Test
    void rejectsUnknownOrUnpublishedPermissionEvidence() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.PERMISSION),
                        draft("Yes.", UUID.randomUUID())))
                .hasMessageContaining("outside the answer scope");
    }

    @Test
    void leavesSemanticDirectionAndTemporalFidelityToTheSingleEvidenceCritic() {
        assertThat(resolver.resolve(
                        request(AnswerAid.PERMISSION),
                        draft("A semantic reversal is schema-valid here and must be caught by the Critic.", citation)))
                .containsExactly(citation);
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Arbitrary player wording.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        citation, "RULE", "Permission", "You may use this card.", 4, 4)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                aid);
    }

    private ModelDraft draft(String explanation, UUID cited) {
        return new ModelDraft(
                true, null, "Permission ruling.", explanation,
                List.of(cited), List.of(), "HIGH", "DIRECT_RULE");
    }
}
