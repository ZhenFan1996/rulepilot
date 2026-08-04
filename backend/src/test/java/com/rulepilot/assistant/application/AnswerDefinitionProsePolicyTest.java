package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.domain.RuleTermDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDefinitionProsePolicyTest {

    private final List<RuleTermDefinition> definitions = List.of(new RuleTermDefinition(
            "Reveal turn", "Reveal the remaining cards.", "It follows Agent turns.", List.of(UUID.randomUUID())));

    @Test
    void replacesOnlyOverlongProseWithoutChangingValidatedDefinitions() {
        String longVerdict = "v".repeat(241);
        String longExplanation = "e".repeat(1501);

        var result = AnswerDefinitionProsePolicy.normalize(longVerdict, longExplanation, definitions);

        assertThat(result.shortVerdict()).isEqualTo(
                "The requested rule terms are defined separately below with their citations.");
        assertThat(result.explanation()).contains("definitions below").hasSizeLessThanOrEqualTo(1500);
        assertThat(definitions).singleElement().satisfies(definition ->
                assertThat(definition.definition()).isEqualTo("Reveal the remaining cards."));
    }

    @Test
    void leavesInLimitProseAndAnswersWithoutDefinitionsUnchanged() {
        assertThat(AnswerDefinitionProsePolicy.normalize("Defined.", "Explanation.", definitions))
                .isEqualTo(new AnswerDefinitionProsePolicy.Result("Defined.", "Explanation."));
        assertThat(AnswerDefinitionProsePolicy.normalize("v".repeat(241), "e".repeat(1501), List.of()))
                .isEqualTo(new AnswerDefinitionProsePolicy.Result("v".repeat(241), "e".repeat(1501)));
    }
}
