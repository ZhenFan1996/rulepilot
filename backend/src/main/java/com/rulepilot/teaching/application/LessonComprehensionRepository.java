package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface LessonComprehensionRepository {

    Map<TaskType, PlayerResult> findResults(UUID lessonId, String username);

    void save(UUID id, UUID lessonId, TaskType taskType, PlayerResult result, String username, Instant updatedAt);
}
