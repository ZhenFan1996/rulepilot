package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftValidatorTest {

    @Test
    void preservesNaturalPlayerTextAndChecksOnlyRequiredStructureAndEvidenceIdentity() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String naturalText = "你可以先修复受损系统；若局势更紧迫，也可以把这次行动留给武器。";
        SectionDraft draft = new SectionDraft(
                "处理系统故障",
                List.of(new StepDraft("做出选择", TeachingMove.DO, naturalText, List.of(evidenceId))));

        LessonDraftValidator.validateDraft(draft);
        var step = LessonDraftValidator.validatedStep(
                1,
                draft.steps().getFirst(),
                Map.of(evidenceId, new RuleEvidence(
                        evidenceId, versionId, "RULE", "Systems", "Repair or activate a system.", 8, 9)));

        assertThat(step.text()).isEqualTo(naturalText);
        assertThat(step.sourcePages()).containsExactly(8, 9);
    }

    @Test
    void rejectsMissingStructureOrAnEvidenceIdentityOutsideTheRequest() {
        assertThatThrownBy(() -> LessonDraftValidator.validateDraft(new SectionDraft("Chapter", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LessonDraftValidator.validatedStep(
                        1,
                        new StepDraft("Do", TeachingMove.DO, "Act.", List.of(UUID.randomUUID())),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside retrieval scope");
    }
}
