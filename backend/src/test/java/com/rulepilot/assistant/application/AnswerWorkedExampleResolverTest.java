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
import com.rulepilot.assistant.RuleAnswerModel.WorkedExampleRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnswerWorkedExampleResolverTest {

    private final UUID chunkId = UUID.randomUUID();
    private final AnswerWorkedExampleResolver resolver = new AnswerWorkedExampleResolver();

    @Test
    void resolvesAnIndependentlyCitedStructuredExample() {
        var result = resolver.resolve(request(AnswerAid.EXAMPLE), draft(List.of(example(
                "A cited starting state.",
                "Apply the cited action.",
                "Reach the cited outcome.",
                "RULEBOOK_EXAMPLE",
                chunkId))));

        assertThat(result).singleElement().satisfies(example -> {
            assertThat(example.basis()).isEqualTo(WorkedExampleBasis.RULEBOOK_EXAMPLE);
            assertThat(example.citationIds()).containsExactly(chunkId);
        });
    }

    @Test
    void followsTheAcceptedAidInsteadOfInspectingExampleWording() {
        ModelRequest selected = request(AnswerAid.EXAMPLE);
        assertThat(resolver.requiresWorkedExamples(selected)).isTrue();
        assertThat(resolver.resolve(selected, draft(List.of()))).isEmpty();

        ModelRequest notSelected = request(AnswerAid.NONE);
        assertThat(resolver.requiresWorkedExamples(notSelected)).isFalse();
        assertThat(resolver.resolve(notSelected, draft(List.of()))).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(notSelected, draft(List.of(example(
                        "Setup", "Action", "Outcome", "RULEBOOK_EXAMPLE", chunkId)))))
                .hasMessageContaining("not selected");
    }

    @Test
    void rejectsInvalidBasisOutOfScopeCitationsAndDuplicates() {
        ModelRequest request = request(AnswerAid.EXAMPLE);
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(example(
                        "Setup", "Action", "Outcome", "INVENTED", chunkId)))))
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(example(
                        "Setup", "Action", "Outcome", "RULEBOOK_EXAMPLE", UUID.randomUUID())))))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> resolver.resolve(request, draft(List.of(
                        example("Setup", "Action", "Outcome", "RULEBOOK_EXAMPLE", chunkId),
                        example(" setup ", " action ", " outcome ", "RULEBOOK_EXAMPLE", chunkId)))))
                .hasMessageContaining("duplicate");
    }

    @Test
    void preservesEverySupportedExampleBeyondTheOldPresentationCap() {
        List<WorkedExampleRequest> examples = IntStream.rangeClosed(1, 4)
                .mapToObj(index -> example(
                        "Setup " + index,
                        "Action " + index,
                        "Outcome " + index,
                        "EVIDENCE_BOUND_ILLUSTRATION",
                        chunkId))
                .toList();

        assertThat(resolver.resolve(request(AnswerAid.EXAMPLE), draft(examples))).hasSize(4);
    }

    private ModelRequest request(AnswerAid aid) {
        return new ModelRequest(
                "Explain this rule.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, "EXAMPLE", "Example", "Cited example evidence.", 1, 1)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                aid);
    }

    private ModelDraft draft(List<WorkedExampleRequest> examples) {
        List<UUID> citations = examples.stream().flatMap(item -> item.citationIds().stream()).distinct().toList();
        if (citations.isEmpty()) citations = List.of(chunkId);
        return new ModelDraft(
                true, null, "Example.", "Cited worked example.", citations, List.of(), "HIGH",
                "DIRECT_RULE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), examples);
    }

    private WorkedExampleRequest example(
            String setup, String action, String outcome, String basis, UUID citationId) {
        return new WorkedExampleRequest(setup, action, outcome, basis, List.of(citationId));
    }
}
