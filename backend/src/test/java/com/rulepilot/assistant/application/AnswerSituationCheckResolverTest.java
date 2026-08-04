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
import com.rulepilot.assistant.domain.SituationCheckStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerSituationCheckResolverTest {

    private final AnswerSituationCheckResolver resolver = new AnswerSituationCheckResolver();
    private final UUID requirementId = UUID.randomUUID();

    @Test
    void resolvesLiteralPlayerFactsAndKeepsAnUnstatedRequirementExplicitlyMissing() {
        ModelRequest request = request(
                "我已经接通中继器，现在可以启动吗？",
                "A player may start the device only while the relay is connected and the chamber is empty.");
        ModelDraft draft = draft(
                "是否可以启动还取决于舱室是否为空。",
                List.of(
                        check("The relay is connected.", "CONFIRMED", "我已经接通中继器"),
                        check("The chamber is empty.", "NOT_PROVIDED", "")));

        var result = resolver.resolve(request, draft);

        assertThat(result).extracting(value -> value.status())
                .containsExactly(SituationCheckStatus.CONFIRMED, SituationCheckStatus.NOT_PROVIDED);
        assertThat(result.getFirst().playerFact()).isEqualTo("我已经接通中继器");
        assertThat(result.get(1).playerFact()).isEmpty();
    }

    @Test
    void rejectsInventedPlayerFactsAndEvidenceOutsideThePublishedAnswer() {
        ModelRequest request = request(
                "我现在可以启动吗？",
                "A player may start the device only while the relay is connected.");

        assertThatThrownBy(() -> resolver.resolve(
                        request,
                        draft("如果中继器已连接就可以。", List.of(check(
                                "The relay is connected.", "CONFIRMED", "中继器已连接")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not literal");

        UUID uncited = UUID.randomUUID();
        SituationCheckRequest outside = new SituationCheckRequest(
                "The relay is connected.", "NOT_PROVIDED", "", List.of(uncited));
        assertThatThrownBy(() -> resolver.resolve(
                        request,
                        draft("是否可以取决于中继器状态。", List.of(outside))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the answer scope");
    }

    @Test
    void rejectsAnUnconditionalVerdictWhenARequiredPlayerFactWasNotProvided() {
        ModelRequest request = request(
                "我现在可以启动吗？",
                "A player may start the device only while the relay is connected.");

        assertThatThrownBy(() -> resolver.resolve(
                        request,
                        draft("可以启动。", List.of(check(
                                "The relay is connected.", "NOT_PROVIDED", "")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditional answer");
    }

    @Test
    void requiresChecksForLiveEligibilityButNotForNumericOrGeneralRuleQuestions() {
        ModelRequest eligibility = request(
                "我现在能启动装置吗？",
                "A player may start the device only while the relay is connected.");
        assertThat(resolver.requiresChecks(eligibility)).isTrue();
        assertThatThrownBy(() -> resolver.resolve(eligibility, draft("可以。", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted");

        ModelRequest scoring = new ModelRequest(
                "我有8个资源，得多少分？",
                QuestionType.SITUATION_QUERY,
                new AnswerContext("not provided", null, PlayerLocale.ZH_CN),
                eligibility.evidence());
        assertThat(resolver.requiresChecks(scoring)).isFalse();
        assertThat(resolver.resolve(scoring, draft("共得分。", List.of()))).isEmpty();

        ModelRequest general = new ModelRequest(
                "How does the device start?",
                QuestionType.RULE_QUERY,
                new AnswerContext("not provided", null, PlayerLocale.EN),
                eligibility.evidence());
        assertThatThrownBy(() -> resolver.resolve(
                        general,
                        draft("It starts after connection.", List.of(check(
                                "The relay is connected.", "NOT_PROVIDED", "")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only valid for situation questions");
    }

    private ModelRequest request(String question, String evidence) {
        return new ModelRequest(
                question,
                QuestionType.SITUATION_QUERY,
                new AnswerContext("not provided", null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(requirementId, "RULE", "Activation", evidence, 4, 4)));
    }

    private SituationCheckRequest check(String requirement, String status, String playerFact) {
        return new SituationCheckRequest(requirement, status, playerFact, List.of(requirementId));
    }

    private ModelDraft draft(String verdict, List<SituationCheckRequest> checks) {
        return new ModelDraft(
                true,
                null,
                verdict,
                "Apply only the explicitly stated table facts.",
                List.of(requirementId),
                List.of(),
                "MEDIUM",
                "GROUNDED_APPLICATION",
                List.of(),
                checks);
    }
}
