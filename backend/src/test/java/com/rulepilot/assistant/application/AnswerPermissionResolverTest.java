package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerPermissionResolverTest {

    private final AnswerPermissionResolver resolver = new AnswerPermissionResolver();

    @Test
    void validatesAnAffirmativePermissionRuling() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "After passing, may I play later in the trick?",
                citation,
                "If you pass, you may still play cards later in the trick if desired.");

        assertThat(resolver.resolve(request, draft(
                "Yes, you may play later in the same trick.",
                "Passing does not remove the permission stated by the cited clause.",
                citation))).containsExactly(citation);
    }

    @Test
    void validatesAProhibitionAndRejectsItsReversal() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "Can I save unused influence?",
                citation,
                "You can never save influence; any unused influence is lost.");

        assertThat(resolver.resolve(request, draft(
                "No, you cannot save unused influence.",
                "The rule says it is lost instead.",
                citation))).containsExactly(citation);
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "Yes, you can save unused influence.",
                "Keep it for later.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed a cited prohibition");
    }

    @Test
    void rejectsAContradictoryDenialOfPermission() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "May I draw after lighting the beacon?",
                citation,
                "Each player may draw one card.");

        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "No, you cannot draw a card.",
                "Drawing is forbidden.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed a cited permission");
    }

    @Test
    void doesNotConfuseAHowToQuestionWithPermission() {
        assertThat(AnswerPermissionResolver.asksForPermission("How can I acquire a card?")).isFalse();
        assertThat(AnswerPermissionResolver.asksForPermission("How often can I acquire a card?")).isFalse();
        assertThat(AnswerPermissionResolver.asksForPermission("Where can I place this card?")).isFalse();
        assertThat(AnswerPermissionResolver.asksForPermission("Can you explain this card?")).isFalse();
        assertThat(AnswerPermissionResolver.asksForPermission("需要两张牌才能发动吗？")).isFalse();
        assertThat(AnswerPermissionResolver.asksForPermission("Can I acquire this card?")).isTrue();
        assertThat(AnswerPermissionResolver.asksForPermission("Can unused influence be saved?")).isTrue();
        assertThat(AnswerPermissionResolver.asksForPermission("这张牌可以拿吗？")).isTrue();
    }

    @Test
    void leavesAnExplicitExceptionForTheGroundedAnswerToApply() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "Can I build after paying the permit?",
                citation,
                "You cannot build unless you first pay the permit.");

        assertThat(resolver.resolve(request, draft(
                "Yes, you can build after paying the permit.",
                "The stated exception is satisfied.",
                citation))).containsExactly(citation);
    }

    @Test
    void rejectsANewCurrentTurnScope() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "After passing, can I play later in the same trick?",
                citation,
                "If you pass, you may still play cards later in the trick if desired.");

        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "Yes, you may play later in the same trick.",
                "Passing only declines your opportunity for the current turn.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temporal scope");
    }

    @Test
    void rejectsANewEndOfTurnBoundary() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "Can unused influence be saved?",
                citation,
                "You can never save influence; any influence unused during your Reveal turn is lost.");

        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "No, unused influence cannot be saved.",
                "It is lost at the end of your Reveal turn.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact temporal boundary");
    }

    @Test
    void rejectsInventedObligationAndRepresentationImpossibility() {
        UUID citation = UUID.randomUUID();
        ModelRequest request = request(
                "Can unused influence be saved?",
                citation,
                "Influence is not represented by tokens because you can never save it; unused influence is lost.");

        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "No, unused influence cannot be saved.",
                "It must be used immediately or lost.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mandatory modal");
        assertThatThrownBy(() -> resolver.resolve(request, draft(
                "No, unused influence cannot be saved.",
                "Influence cannot be represented by tokens.",
                citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("representation fact");
    }

    private ModelRequest request(String question, UUID citation, String excerpt) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citation, "RULES", "Permission", excerpt, 4, 4)));
    }

    private ModelDraft draft(String verdict, String explanation, UUID citation) {
        return new ModelDraft(
                true, null, verdict, explanation, List.of(citation), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
