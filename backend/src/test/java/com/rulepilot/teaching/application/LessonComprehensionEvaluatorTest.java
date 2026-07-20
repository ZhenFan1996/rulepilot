package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskReadiness;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonComprehensionEvaluatorTest {

    @Test
    void buildsFourTextTasksAndKeepsVisualTasksUnavailableWithoutAValidatedFocus() {
        var lesson = lesson(List.of(
                section(1, "setup", List.of("setup"), TeachingMove.DO, TeachingMove.CHECK),
                section(2, "actions", List.of("core_loop"), TeachingMove.DO, TeachingMove.CHECK),
                section(3, "round", List.of("first_round"), TeachingMove.DO, TeachingMove.EXAMPLE, TeachingMove.CHECK),
                section(4, "finish", List.of("end"), TeachingMove.DO, TeachingMove.CHECK),
                section(5, "score", List.of("scoring", "tie_breaker"), TeachingMove.LEDGER, TeachingMove.CHECK)));

        var report = new LessonComprehensionEvaluator().evaluate(
                lesson, Map.of(TaskType.PLAY_A_ROUND, saved(PlayerResult.NEEDS_HELP)));

        assertThat(report.readyTaskCount()).isEqualTo(4);
        assertThat(report.needsHelpCount()).isEqualTo(1);
        assertThat(report.tasks()).extracting(task -> task.type()).containsExactly(
                TaskType.PREPARE_TABLE,
                TaskType.PLAY_A_ROUND,
                TaskType.FINISH_GAME,
                TaskType.SCORE_GAME,
                TaskType.IDENTIFY_COMPONENTS,
                TaskType.COMPLETE_VISUAL_SETUP);
        assertThat(report.readyVisualTaskCount()).isZero();
        var round = report.tasks().get(1);
        assertThat(round.chapterPositions()).containsExactly(3);
        assertThat(round.prompt()).isEqualTo("检查 round");
        assertThat(round.sourcePages()).containsExactly(13);
    }

    @Test
    void buildsVisualTasksFromTheSameCitedFocusAndMeasuresUsefulnessSeparately() {
        var lesson = lesson(List.of(section(
                1,
                "board-assembly",
                List.of("setup", "components"),
                TeachingMove.DO,
                TeachingMove.VISUAL)));

        var report = new LessonComprehensionEvaluator().evaluate(lesson, Map.of(
                TaskType.IDENTIFY_COMPONENTS,
                saved(PlayerResult.CAN_DO, VisualAidResult.HELPFUL),
                TaskType.COMPLETE_VISUAL_SETUP,
                saved(PlayerResult.NEEDS_HELP, VisualAidResult.NOT_HELPFUL)));

        assertThat(report.readyTaskCount()).isEqualTo(2);
        assertThat(report.readyVisualTaskCount()).isEqualTo(2);
        assertThat(report.visualAidRatedCount()).isEqualTo(2);
        assertThat(report.visualAidHelpfulCount()).isEqualTo(1);
        assertThat(report.canDoCount()).isEqualTo(1);
        assertThat(report.needsHelpCount()).isEqualTo(1);
        var componentTask = report.tasks().get(4);
        assertThat(componentTask.prompt()).contains("框选的“主板与组件区”");
        assertThat(componentTask.visualFocus()).isNotNull();
        assertThat(componentTask.sourcePages()).containsExactly(11);
        assertThat(report.tasks().get(5).prompt()).contains("按照本节步骤");
    }

    @Test
    void doesNotInviteSelfAssessmentWhenAnActionOrCitedCheckIsMissing() {
        var lesson = lesson(List.of(
                section(1, "setup", List.of("setup"), TeachingMove.UNDERSTAND, TeachingMove.CHECK),
                section(2, "round", List.of("first_round"), TeachingMove.DO, TeachingMove.EXAMPLE)));

        var report = new LessonComprehensionEvaluator().evaluate(lesson, Map.of(
                TaskType.PREPARE_TABLE, saved(PlayerResult.CAN_DO),
                TaskType.PLAY_A_ROUND, saved(PlayerResult.CAN_DO)));

        assertThat(report.readyTaskCount()).isZero();
        assertThat(report.canDoCount()).isZero();
        assertThat(report.tasks().subList(0, 4))
                .allMatch(task -> task.readiness() == TaskReadiness.MISSING_LESSON_CHECK);
        assertThat(report.tasks().subList(4, 6))
                .allMatch(task -> task.readiness() == TaskReadiness.MISSING_VISUAL_EVIDENCE);
        assertThat(report.tasks()).allMatch(task -> task.result() == PlayerResult.NOT_TRIED);
    }

    private IllustratedLesson lesson(List<LessonSection> sections) {
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), LessonStatus.COMPLETE, sections, "test", Instant.EPOCH);
    }

    private LessonSection section(
            int position, String topicKey, List<String> tags, TeachingMove... moves) {
        List<LessonStep> steps = java.util.stream.IntStream.range(0, moves.length)
                .mapToObj(index -> new LessonStep(
                        index + 1,
                        moves[index].name(),
                        moves[index],
                        moves[index] == TeachingMove.CHECK ? "检查 " + topicKey : "讲解 " + topicKey,
                        List.of(10 + position),
                        List.of(UUID.randomUUID()),
                        moves[index] == TeachingMove.VISUAL
                                ? new VisualFocus(10 + position, "主板与组件区", 100, 120, 600, 500)
                                : null))
                .toList();
        return new LessonSection(
                position,
                topicKey,
                tags,
                topicKey,
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "原文页",
                steps);
    }

    private LessonComprehensionRepository.SavedResult saved(PlayerResult playerResult) {
        return saved(playerResult, VisualAidResult.NOT_RATED);
    }

    private LessonComprehensionRepository.SavedResult saved(
            PlayerResult playerResult, VisualAidResult visualAidResult) {
        return new LessonComprehensionRepository.SavedResult(playerResult, visualAidResult);
    }
}
