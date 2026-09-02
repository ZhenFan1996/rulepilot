package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonSectionEnricherTest {

    @Test
    void aPrelabelledVisualStepDoesNotHideOtherCitedTeachingMovesFromVisualUnderstanding() {
        UUID evidence = UUID.randomUUID();
        LessonStep understand = step(1, TeachingMove.UNDERSTAND, evidence);
        LessonStep visual = step(2, TeachingMove.VISUAL, evidence);
        LessonStep action = step(3, TeachingMove.DO, evidence);
        LessonStep uncitedCheck = new LessonStep(
                4, "自己检查", TeachingMove.CHECK, "复述一次。", List.of(), List.of());
        LessonSection section = new LessonSection(
                1,
                "turn",
                List.of("turn"),
                "完成一回合",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.FLOW_DIAGRAM,
                "按图完成回合。",
                List.of(2),
                List.of(evidence),
                List.of(understand, visual, action, uncitedCheck));

        assertThat(VisualLessonSectionEnricher.visualTargets(section))
                .extracting(LessonStep::position)
                .containsExactly(1, 2, 3);
    }

    private LessonStep step(int position, TeachingMove move, UUID evidence) {
        return new LessonStep(
                position,
                "步骤 " + position,
                move,
                "执行有引用的步骤 " + position + "。",
                List.of(2),
                List.of(evidence));
    }
}
