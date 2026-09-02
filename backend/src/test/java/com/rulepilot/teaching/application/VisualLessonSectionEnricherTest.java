package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonSectionEnricherTest {

    @Test
    void targetsEveryCitedTeachingMoveThatHasNoVisualGroupYet() {
        UUID evidence = UUID.randomUUID();
        LessonStep understand = step(1, TeachingMove.UNDERSTAND, evidence);
        LessonStep visual = step(2, TeachingMove.VISUAL, evidence);
        LessonStep action = step(3, TeachingMove.DO, evidence);
        VisualFocus existing = new VisualFocus(2, "existing", 10, 20, 200, 120);
        LessonStep alreadyIllustrated = new LessonStep(
                4,
                "已有图组",
                TeachingMove.VISUAL,
                "这个步骤已经有图文讲解。",
                List.of(2),
                List.of(evidence),
                existing);
        LessonStep uncitedCheck = new LessonStep(
                5, "自己检查", TeachingMove.CHECK, "复述一次。", List.of(), List.of());
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
                List.of(understand, visual, action, alreadyIllustrated, uncitedCheck));

        assertThat(VisualLessonSectionEnricher.visualTargets(section))
                .extracting(LessonStep::position)
                .containsExactly(1, 2, 3);
    }

    @Test
    void treatsACompleteVisualSectionAsAlreadyPresentWithoutCallingTheLocator() {
        UUID evidence = UUID.randomUUID();
        VisualFocus existing = new VisualFocus(2, "existing", 10, 20, 200, 120);
        LessonStep illustrated = new LessonStep(
                1,
                "已有图组",
                TeachingMove.VISUAL,
                "这个步骤已经有图文讲解。",
                List.of(2),
                List.of(evidence),
                existing);
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
                List.of(illustrated));
        VisualLessonStepLocator locator = mock(VisualLessonStepLocator.class);
        var enricher = new VisualLessonSectionEnricher(
                mock(VisualLessonMergePolicy.class), locator);

        VisualLessonSectionEnricher.Result result = enricher.enrich(
                mock(com.rulepilot.ingestion.layout.RulebookUnderstanding.class),
                UUID.randomUUID(),
                section,
                "player",
                null,
                java.time.Instant.now().plusSeconds(60),
                null,
                new VisualLessonEnricher.VisualProgressListener() {},
                List.of(existing),
                List.of(2));

        assertThat(result.section()).isEqualTo(section);
        assertThat(result.outcome()).isEqualTo(VisualLessonEnricher.Outcome.ALREADY_PRESENT);
        verifyNoInteractions(locator);
    }

    @Test
    void classifiesValidNoVisualAndAlreadyCompleteAsSuccessfulLocalDecisions() {
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(VisualLessonEnricher.Outcome.ALREADY_PRESENT)).isTrue();
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(
                        VisualLessonEnricher.Outcome.MODEL_EXPLICIT_NO_REGION))
                .isTrue();
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(VisualLessonEnricher.Outcome.ADDED)).isTrue();
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(
                        VisualLessonEnricher.Outcome.NO_CITED_CANDIDATE))
                .isTrue();
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(
                        VisualLessonEnricher.Outcome.MODEL_PROVIDER_FAILURE))
                .isFalse();
        assertThat(VisualLessonEnricher.isSuccessfulOutcome(VisualLessonEnricher.Outcome.NO_PAGE_IMAGE)).isFalse();
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
