package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WorkedExampleRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerWorkedExampleResolverTest {

    private final UUID chunkId = UUID.randomUUID();
    private final AnswerWorkedExampleResolver resolver = new AnswerWorkedExampleResolver();

    @Test
    void resolvesAnIndependentlyCitedRulebookExample() {
        ModelRequest request = request("Give me an example", LearningIntent.EXAMPLE);
        ModelDraft draft = draft(List.of(new WorkedExampleRequest(
                "A card starts at value 1.", "Apply the cited -4 modifier.",
                "The card counts as -3.", "RULEBOOK_EXAMPLE", List.of(chunkId))));

        assertThat(resolver.resolve(request, draft)).singleElement().satisfies(example -> {
            assertThat(example.outcome()).isEqualTo("The card counts as -3.");
            assertThat(example.basis()).isEqualTo(WorkedExampleBasis.RULEBOOK_EXAMPLE);
            assertThat(example.citationIds()).containsExactly(chunkId);
        });
    }

    @Test
    void requiresStructuredExamplesForExampleIntent() {
        assertThatThrownBy(() -> resolver.resolve(
                        request("Explain this with an example", LearningIntent.EXAMPLE), draft(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted");
    }

    @Test
    void rejectsInvalidBasisAndOutOfScopeCitation() {
        assertThatThrownBy(() -> resolver.resolve(request("Show an example", null), draft(List.of(
                        new WorkedExampleRequest("Setup", "Action", "Outcome", "INVENTED", List.of(chunkId))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request("Show an example", null), draft(List.of(
                        new WorkedExampleRequest("Setup", "Action", "Outcome", "RULEBOOK_EXAMPLE",
                                List.of(UUID.randomUUID()))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsNumbersThatDoNotOccurInTheExamplesOwnCitations() {
        ModelRequest request = new ModelRequest(
                "Show an example", QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.EXAMPLE, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        chunkId, "EXAMPLE", "Worked example",
                        "A value 1 card receives a -4 modifier and counts as -3.", 1, 1)));

        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(new WorkedExampleRequest(
                        "A value 1 card.", "Apply a -4 modifier.", "It counts as -7.",
                        "RULEBOOK_EXAMPLE", List.of(chunkId))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported number");
    }

    @Test
    void validatesEachExamplesNumbersAgainstItsOwnCitationInsteadOfTheWholeEvidenceSet() {
        UUID unrelatedId = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "Give me an example", QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.EXAMPLE, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(chunkId, "EXAMPLE", "Example", "Pay 2 and gain 3.", 1, 1),
                        new EvidenceInput(unrelatedId, "RULE", "Other rule", "The limit is 9.", 2, 2)));
        ModelDraft draft = new ModelDraft(
                true, null, "Example.", "Cited worked example.", List.of(chunkId, unrelatedId), List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new WorkedExampleRequest(
                        "You can pay 2.", "Pay 2.", "Gain 9.",
                        "EVIDENCE_BOUND_ILLUSTRATION", List.of(chunkId))));

        assertThatThrownBy(() -> resolver.resolve(request, draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported number");
    }

    private ModelRequest request(String question, LearningIntent intent) {
        return new ModelRequest(question, QuestionType.RULE_QUERY, new AnswerContext(null, intent, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        chunkId, "EXAMPLE", "Worked example",
                        "A card starts at value 1. Apply the cited -4 modifier. The card counts as -3.", 1, 1)));
    }

    private ModelDraft draft(List<WorkedExampleRequest> examples) {
        return new ModelDraft(true, null, "Example.", "Cited worked example.", List.of(chunkId), List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), examples);
    }
}
