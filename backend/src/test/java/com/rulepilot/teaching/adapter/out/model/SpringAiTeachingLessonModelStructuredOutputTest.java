package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpringAiTeachingLessonModelStructuredOutputTest {

    @Test
    void acceptsAdditiveFieldsWithoutChangingNaturalChapterText() throws Exception {
        String natural = "先处理眼前最危险的故障；如果队友更适合修理，就把行动留给他。";
        var draft = SpringAiTeachingLessonModel.parseStructuredDraft("""
                {"title":"系统故障","providerNote":"ignored","steps":[{
                  "heading":"协商行动","kind":"DO","text":"%s","citationIds":["E1"],
                  "ruleFacts":[],"futureLayout":{"ignored":true}}]}
                """.formatted(natural));

        assertThat(draft.title()).isEqualTo("系统故障");
        assertThat(draft.steps().getFirst().text()).isEqualTo(natural);
    }

    @Test
    void keepsEvidenceReferencesTypedAndRejectsUnknownIdentity() {
        UUID evidence = UUID.randomUUID();

        assertThat(SpringAiTeachingLessonModel.resolveReferences(
                        java.util.List.of("e1", "E1"), Map.of("E1", evidence)))
                .containsExactly(evidence);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.resolveReferences(
                        java.util.List.of("E2"), Map.of("E1", evidence)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown evidence reference");
    }
}
