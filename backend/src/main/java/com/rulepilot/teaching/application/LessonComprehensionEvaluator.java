package com.rulepilot.teaching.application;

import com.rulepilot.teaching.application.LessonComprehensionRepository.SavedResult;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.LessonComprehensionReport;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerTask;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskReadiness;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LessonComprehensionEvaluator {

    private static final List<TaskDefinition> TASKS = List.of(
            new TaskDefinition(
                    TaskType.PREPARE_TABLE,
                    "我能照着摆好桌面",
                    List.of("setup"),
                    List.of(Set.of(TeachingMove.DO))),
            new TaskDefinition(
                    TaskType.PLAY_A_ROUND,
                    "我能带大家走完一轮",
                    List.of("first_round", "core_loop"),
                    List.of(Set.of(TeachingMove.DO), Set.of(TeachingMove.EXAMPLE, TeachingMove.FLOW))),
            new TaskDefinition(
                    TaskType.FINISH_GAME,
                    "我知道什么时候结束",
                    List.of("end"),
                    List.of(Set.of(TeachingMove.DO, TeachingMove.FLOW))),
            new TaskDefinition(
                    TaskType.SCORE_GAME,
                    "我能完成计分并判断胜负",
                    List.of("scoring"),
                    List.of(Set.of(TeachingMove.DO, TeachingMove.LEDGER))));

    private static final List<VisualTaskDefinition> VISUAL_TASKS = List.of(
            new VisualTaskDefinition(
                    TaskType.IDENTIFY_COMPONENTS,
                    "我能认出关键组件和用途",
                    List.of("components", "setup"),
                    Set.of()),
            new VisualTaskDefinition(
                    TaskType.COMPLETE_VISUAL_SETUP,
                    "我能看图完成关键摆放",
                    List.of("setup"),
                    Set.of(TeachingMove.DO)));

    public LessonComprehensionReport evaluate(
            IllustratedLesson lesson, Map<TaskType, SavedResult> savedResults) {
        List<PlayerTask> tasks = java.util.stream.Stream.concat(
                        TASKS.stream().map(definition -> task(
                                lesson, definition, savedResults.getOrDefault(definition.type(), notTried()))),
                        VISUAL_TASKS.stream().map(definition -> visualTask(
                                lesson, definition, savedResults.getOrDefault(definition.type(), notTried()))))
                .toList();
        int ready = (int) tasks.stream().filter(task -> task.readiness() == TaskReadiness.READY).count();
        int canDo = (int) tasks.stream().filter(task -> task.result() == PlayerResult.CAN_DO).count();
        int needsHelp = (int) tasks.stream().filter(task -> task.result() == PlayerResult.NEEDS_HELP).count();
        int readyVisual = (int) tasks.stream()
                .filter(task -> task.readiness() == TaskReadiness.READY && task.visualFocus() != null)
                .count();
        int visualRated = (int) tasks.stream()
                .filter(task -> task.visualAidResult() != VisualAidResult.NOT_RATED)
                .count();
        int visualHelpful = (int) tasks.stream()
                .filter(task -> task.visualAidResult() == VisualAidResult.HELPFUL)
                .count();
        return new LessonComprehensionReport(
                lesson.id(), ready, tasks.size(), canDo, needsHelp,
                readyVisual, visualRated, visualHelpful, tasks);
    }

    private PlayerTask task(IllustratedLesson lesson, TaskDefinition definition, SavedResult saved) {
        List<LessonSection> sections = matchingSections(lesson, definition.coverageTags());
        List<LessonSection> readySections = sections.stream()
                .filter(section -> sectionSatisfies(section, definition.requiredMoveGroups()))
                .toList();
        List<LessonStep> citedChecks = readySections.stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.kind() == TeachingMove.CHECK && !step.sourcePages().isEmpty())
                .toList();
        boolean ready = !readySections.isEmpty() && !citedChecks.isEmpty();
        List<Integer> positions = readySections.stream().map(LessonSection::position).distinct().sorted().toList();
        List<Integer> pages = citedChecks.stream()
                .flatMap(step -> step.sourcePages().stream())
                .distinct()
                .sorted()
                .toList();
        return new PlayerTask(
                definition.type(),
                definition.label(),
                ready ? citedChecks.getFirst().text() : "当前讲解还没有足够的可执行步骤和检查题。",
                ready ? TaskReadiness.READY : TaskReadiness.MISSING_LESSON_CHECK,
                ready ? saved.playerResult() : PlayerResult.NOT_TRIED,
                positions,
                pages,
                null,
                VisualAidResult.NOT_RATED);
    }

    private PlayerTask visualTask(
            IllustratedLesson lesson, VisualTaskDefinition definition, SavedResult saved) {
        List<LessonSection> readySections = matchingSections(lesson, definition.coverageTags()).stream()
                .filter(section -> visualSectionSatisfies(section, definition.requiredMoves()))
                .toList();
        LessonStep visualStep = readySections.stream()
                .flatMap(section -> section.steps().stream())
                .filter(this::isCitedVisual)
                .findFirst()
                .orElse(null);
        boolean ready = visualStep != null;
        VisualFocus focus = ready ? visualStep.visualFocus() : null;
        return new PlayerTask(
                definition.type(),
                definition.label(),
                ready ? visualPrompt(definition.type(), focus.label()) : "当前讲解还没有经过验证的规则书焦点区域。",
                ready ? TaskReadiness.READY : TaskReadiness.MISSING_VISUAL_EVIDENCE,
                ready ? saved.playerResult() : PlayerResult.NOT_TRIED,
                readySections.stream().map(LessonSection::position).distinct().sorted().toList(),
                ready ? List.of(focus.pageNumber()) : List.of(),
                focus,
                ready ? saved.visualAidResult() : VisualAidResult.NOT_RATED);
    }

    private boolean visualSectionSatisfies(LessonSection section, Set<TeachingMove> requiredMoves) {
        boolean hasVisual = section.steps().stream().anyMatch(this::isCitedVisual);
        boolean hasRequiredMoves = requiredMoves.stream().allMatch(required -> section.steps().stream()
                .anyMatch(step -> step.kind() == required && !step.sourcePages().isEmpty()));
        return hasVisual && hasRequiredMoves;
    }

    private boolean isCitedVisual(LessonStep step) {
        return step.kind() == TeachingMove.VISUAL
                && step.visualFocus() != null
                && step.sourcePages().contains(step.visualFocus().pageNumber())
                && !step.sourceChunkIds().isEmpty();
    }

    private String visualPrompt(TaskType type, String focusLabel) {
        return switch (type) {
            case IDENTIFY_COMPONENTS ->
                "先只看框选的“%s”：指出框内关键组件，并说出它在本节中的用途。".formatted(focusLabel);
            case COMPLETE_VISUAL_SETUP ->
                "先看框选的“%s”，再按照本节步骤把相关组件摆到对应位置。".formatted(focusLabel);
            default -> throw new IllegalArgumentException("task is not a visual comprehension task");
        };
    }

    private SavedResult notTried() {
        return new SavedResult(PlayerResult.NOT_TRIED, VisualAidResult.NOT_RATED);
    }

    private boolean sectionSatisfies(
            LessonSection section, List<Set<TeachingMove>> requiredMoveGroups) {
        boolean hasCitedCheck = section.steps().stream()
                .anyMatch(step -> step.kind() == TeachingMove.CHECK && !step.sourcePages().isEmpty());
        return hasCitedCheck && requiredMoveGroups.stream().allMatch(group -> section.steps().stream()
                .anyMatch(step -> group.contains(step.kind()) && !step.sourcePages().isEmpty()));
    }

    private List<LessonSection> matchingSections(IllustratedLesson lesson, List<String> preferredTags) {
        for (String tag : preferredTags) {
            List<LessonSection> matches = lesson.sections().stream()
                    .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                    .filter(section -> section.coverageTags().contains(tag))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private record TaskDefinition(
            TaskType type,
            String label,
            List<String> coverageTags,
            List<Set<TeachingMove>> requiredMoveGroups) {

        private TaskDefinition {
            coverageTags = List.copyOf(coverageTags);
            requiredMoveGroups = requiredMoveGroups.stream().map(Set::copyOf).toList();
        }
    }

    private record VisualTaskDefinition(
            TaskType type,
            String label,
            List<String> coverageTags,
            Set<TeachingMove> requiredMoves) {

        private VisualTaskDefinition {
            coverageTags = List.copyOf(coverageTags);
            requiredMoves = Set.copyOf(requiredMoves);
        }
    }
}
