package com.rulepilot.teaching.domain;

import java.util.List;
import java.util.UUID;

public record LessonComprehensionReport(
        UUID lessonId,
        int readyTaskCount,
        int taskCount,
        int canDoCount,
        int needsHelpCount,
        List<PlayerTask> tasks) {

    public LessonComprehensionReport {
        if (lessonId == null || readyTaskCount < 0 || taskCount < 0 || readyTaskCount > taskCount
                || canDoCount < 0 || needsHelpCount < 0 || canDoCount + needsHelpCount > taskCount) {
            throw new IllegalArgumentException("lesson comprehension totals are invalid");
        }
        tasks = List.copyOf(tasks);
    }

    public enum TaskType {
        PREPARE_TABLE,
        PLAY_A_ROUND,
        FINISH_GAME,
        SCORE_GAME
    }

    public enum TaskReadiness {
        READY,
        MISSING_LESSON_CHECK
    }

    public enum PlayerResult {
        NOT_TRIED,
        CAN_DO,
        NEEDS_HELP
    }

    public record PlayerTask(
            TaskType type,
            String label,
            String prompt,
            TaskReadiness readiness,
            PlayerResult result,
            List<Integer> chapterPositions,
            List<Integer> sourcePages) {

        public PlayerTask {
            if (type == null || label == null || label.isBlank() || prompt == null || prompt.isBlank()
                    || readiness == null || result == null) {
                throw new IllegalArgumentException("player comprehension task is invalid");
            }
            chapterPositions = List.copyOf(chapterPositions);
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
