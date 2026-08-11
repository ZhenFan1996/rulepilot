package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.TermDefinitionRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerTermDefinitionResolverTest {

    private final UUID chunkId = UUID.randomUUID();
    private final AnswerTermDefinitionResolver resolver = new AnswerTermDefinitionResolver();

    @Test
    void resolvesAnIndependentlyCitedDefinitionAndBoundary() {
        ModelRequest request = request("What does control mean?", LearningIntent.DEFINE);
        ModelDraft draft = draft(List.of(new TermDefinitionRequest(
                "Control", "A player controls a clearing when the cited majority condition holds.",
                "A tie does not satisfy control.", List.of(chunkId))));

        assertThat(resolver.resolve(request, draft)).singleElement().satisfies(definition -> {
            assertThat(definition.term()).isEqualTo("Control");
            assertThat(definition.boundary()).isEqualTo("A tie does not satisfy control.");
            assertThat(definition.citationIds()).containsExactly(chunkId);
        });
    }

    @Test
    void requiresStructuredDefinitionsForDefineIntent() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("Please explain this term", LearningIntent.DEFINE), draft(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsDuplicateTermsAndOutOfScopeCitations() {
        assertThatThrownBy(() -> resolver.resolve(request("What does control mean?", AnswerAid.DEFINITIONS), draft(List.of(
                        new TermDefinitionRequest("Control", "First", "", List.of(chunkId)),
                        new TermDefinitionRequest("control", "Second", "", List.of(chunkId))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> resolver.resolve(request("What does control mean?", AnswerAid.DEFINITIONS), draft(List.of(
                        new TermDefinitionRequest("Control", "Meaning", "", List.of(UUID.randomUUID()))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void normalizesAnOmittedOptionalBoundaryToEmptyText() {
        ModelRequest request = request("What does rule mean?", LearningIntent.DEFINE);
        ModelDraft draft = draft(List.of(new TermDefinitionRequest(
                "Rule", "An instruction that governs play.", null, List.of(chunkId))));

        assertThat(resolver.resolve(request, draft)).singleElement().satisfies(definition ->
                assertThat(definition.boundary()).isEmpty());
    }

    private ModelRequest request(String question, LearningIntent intent) {
        return request(question, AnswerAid.forLearningIntent(intent), intent);
    }

    private ModelRequest request(String question, AnswerAid answerAid) {
        return request(question, answerAid, null);
    }

    private ModelRequest request(String question, AnswerAid answerAid, LearningIntent intent) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, intent, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, "GLOSSARY", "Terms", "Direct definition", 1, 1)),
                java.util.Set.of(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.DEFINITION),
                answerAid);
    }

    private ModelDraft draft(List<TermDefinitionRequest> definitions) {
        return new ModelDraft(true, null, "Defined.", "Cited definition.", List.of(chunkId), List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), definitions);
    }
}
