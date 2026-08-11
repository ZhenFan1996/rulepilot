package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicQuestionUnderstandingTest {

    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();
    private final UUID versionId = UUID.randomUUID();

    @Test
    void preservesThePlayersQuestionWhileNormalizingOnlyWhitespace() {
        var result = understanding.understand(
                "  Can I\n  play this card from my hand?  ",
                new QuestionContext(versionId));

        assertThat(result.documentVersionId()).isEqualTo(versionId);
        assertThat(result.originalQuestion()).isEqualTo("Can I play this card from my hand?");
        assertThat(result.normalizedQuestion()).isEqualTo(result.originalQuestion());
        assertThat(result.type()).isEqualTo(QuestionType.RULE_QUERY);
        assertThat(result.terms()).isEmpty();
        assertThat(result.missingContext()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void doesNotInferSemanticIntentFromWordingOrConversationHistory() {
        var result = understanding.understand(
                "那我还能再做一次吗？",
                new QuestionContext(versionId, "执行主要行动后还能做什么？", null, null));

        assertThat(result.type()).isEqualTo(QuestionType.RULE_QUERY);
        assertThat(result.terms()).isEmpty();
        assertThat(result.missingContext()).isEmpty();
    }

    @Test
    void rejectsMissingSyntaxInputs() {
        assertThatThrownBy(() -> understanding.understand(" ", new QuestionContext(versionId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> understanding.understand("question", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}
