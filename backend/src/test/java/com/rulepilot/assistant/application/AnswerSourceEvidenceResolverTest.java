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

class AnswerSourceEvidenceResolverTest {

    private final AnswerSourceEvidenceResolver resolver = new AnswerSourceEvidenceResolver();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @Test
    void validatesOneOrTwoDirectCitationsForASelectedSourceAid() {
        assertThat(resolver.resolve(
                        request(AnswerAid.SOURCE),
                        draft("DIRECT_RULE", List.of(first, second))))
                .containsExactly(first, second);
        assertThat(AnswerSourceEvidenceResolver.requiresSourceEvidence(request(AnswerAid.SOURCE))).isTrue();
        assertThat(AnswerSourceEvidenceResolver.requiresSourceEvidence(request(AnswerAid.NONE))).isFalse();
    }

    @Test
    void rejectsWrongBasisCitationPaddingAndOutOfScopeEvidence() {
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.SOURCE), draft("GROUNDED_APPLICATION", List.of(first))))
                .hasMessageContaining("direct rule evidence");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.SOURCE), draft("DIRECT_RULE", List.of(first, second, third))))
                .hasMessageContaining("one or two");
        assertThatThrownBy(() -> resolver.resolve(
                        request(AnswerAid.SOURCE), draft("DIRECT_RULE", List.of(UUID.randomUUID()))))
                .hasMessageContaining("outside the answer scope");
    }

    @Test
    void ignoresSourceShapedQuestionWordingWhenThePlanDidNotSelectTheAid() {
        assertThat(resolver.resolve(
                        request(AnswerAid.NONE), draft("DIRECT_RULE", List.of(first))))
                .isEmpty();
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Where is the source? This wording is deliberately not the router.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        evidence(first, 3), evidence(second, 4), evidence(third, 5)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                aid);
    }

    private EvidenceInput evidence(UUID id, int page) {
        return new EvidenceInput(id, "RULE", "Source", "Direct cited clause.", page, page);
    }

    private ModelDraft draft(String basis, List<UUID> citations) {
        return new ModelDraft(
                true, null, "Direct ruling.", "Player-facing explanation.",
                citations, List.of(), "HIGH", basis);
    }
}
