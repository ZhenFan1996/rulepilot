package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface LessonComprehensionRepository {

    Map<TaskType, SavedResult> findResults(UUID lessonId, String username);

    Map<String, VisualAidResult> findVisualAidResults(UUID lessonId, String username);

    void savePlayerResult(
            UUID id, UUID lessonId, TaskType taskType, PlayerResult result, String username, Instant updatedAt);

    void saveVisualAidResult(
            UUID lessonId, String visualAidKey, VisualAidResult result, String username, Instant updatedAt);

    record SavedResult(PlayerResult playerResult, VisualAidResult visualAidResult) {
        public SavedResult {
            if (playerResult == null || visualAidResult == null) {
                throw new IllegalArgumentException("saved comprehension result is invalid");
            }
        }
    }
}
