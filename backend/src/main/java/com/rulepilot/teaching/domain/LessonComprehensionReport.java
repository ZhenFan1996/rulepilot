package com.rulepilot.teaching.domain;

import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;
import java.util.UUID;

public record LessonComprehensionReport(
        UUID lessonId,
        int readyTaskCount,
        int taskCount,
        int canDoCount,
        int needsHelpCount,
        int readyVisualTaskCount,
        int visualAidRatedCount,
        int visualAidHelpfulCount,
        List<PlayerTask> tasks) {

    public LessonComprehensionReport {
        if (lessonId == null || readyTaskCount < 0 || taskCount < 0 || readyTaskCount > taskCount
                || canDoCount < 0 || needsHelpCount < 0 || canDoCount + needsHelpCount > taskCount) {
            throw new IllegalArgumentException("lesson comprehension totals are invalid");
        }
        if (readyVisualTaskCount < 0 || visualAidRatedCount < 0 || visualAidHelpfulCount < 0
                || visualAidRatedCount > readyVisualTaskCount || visualAidHelpfulCount > visualAidRatedCount) {
            throw new IllegalArgumentException("lesson visual comprehension totals are invalid");
        }
        tasks = List.copyOf(tasks);
    }

    public enum TaskType {
        PREPARE_TABLE,
        PLAY_A_ROUND,
        FINISH_GAME,
        SCORE_GAME,
        VERIFY_VISUAL_AID,
        IDENTIFY_COMPONENTS,
        COMPLETE_VISUAL_SETUP
    }

    public enum TaskReadiness {
        READY,
        MISSING_LESSON_CHECK,
        MISSING_VISUAL_EVIDENCE
    }

    public enum PlayerResult {
        NOT_TRIED,
        CAN_DO,
        NEEDS_HELP
    }

    public enum VisualAidResult {
        NOT_RATED,
        HELPFUL,
        NOT_HELPFUL
    }

    public record PlayerTask(
            TaskType type,
            String label,
            String prompt,
            TaskReadiness readiness,
            PlayerResult result,
            List<Integer> chapterPositions,
            List<Integer> sourcePages,
            VisualFocus visualFocus,
            VisualAidResult visualAidResult) {

        public PlayerTask {
            if (type == null || label == null || label.isBlank() || prompt == null || prompt.isBlank()
                    || readiness == null || result == null || visualAidResult == null) {
                throw new IllegalArgumentException("player comprehension task is invalid");
            }
            if ((visualFocus == null || readiness != TaskReadiness.READY)
                    && visualAidResult != VisualAidResult.NOT_RATED) {
                throw new IllegalArgumentException("visual aid can only be rated for a ready visual task");
            }
            chapterPositions = List.copyOf(chapterPositions);
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
