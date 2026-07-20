package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.LessonComprehensionReport;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerTask;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskReadiness;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
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

    public LessonComprehensionReport evaluate(
            IllustratedLesson lesson, Map<TaskType, PlayerResult> savedResults) {
        List<PlayerTask> tasks = TASKS.stream()
                .map(definition -> task(lesson, definition, savedResults.getOrDefault(
                        definition.type(), PlayerResult.NOT_TRIED)))
                .toList();
        int ready = (int) tasks.stream().filter(task -> task.readiness() == TaskReadiness.READY).count();
        int canDo = (int) tasks.stream().filter(task -> task.result() == PlayerResult.CAN_DO).count();
        int needsHelp = (int) tasks.stream().filter(task -> task.result() == PlayerResult.NEEDS_HELP).count();
        return new LessonComprehensionReport(lesson.id(), ready, tasks.size(), canDo, needsHelp, tasks);
    }

    private PlayerTask task(IllustratedLesson lesson, TaskDefinition definition, PlayerResult result) {
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
                ready ? result : PlayerResult.NOT_TRIED,
                positions,
                pages);
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
}
