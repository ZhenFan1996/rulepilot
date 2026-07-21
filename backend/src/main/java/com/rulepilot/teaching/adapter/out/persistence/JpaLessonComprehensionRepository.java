package com.rulepilot.teaching.adapter.out.persistence;

import com.rulepilot.teaching.application.LessonComprehensionRepository;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaLessonComprehensionRepository implements LessonComprehensionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<TaskType, SavedResult> findResults(UUID lessonId, String username) {
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Object[]>) entityManager.createNativeQuery("""
                        select task_type, result, visual_aid_result
                        from lesson_comprehension_result
                        where lesson_id = :lessonId and created_by = :username
                        """)
                .setParameter("lessonId", lessonId)
                .setParameter("username", username)
                .getResultList();
        Map<TaskType, SavedResult> found = new EnumMap<>(TaskType.class);
        for (Object[] row : rows) {
            found.put(
                    TaskType.valueOf(row[0].toString()),
                    new SavedResult(
                            PlayerResult.valueOf(row[1].toString()),
                            row[2] == null
                                    ? VisualAidResult.NOT_RATED
                                    : VisualAidResult.valueOf(row[2].toString())));
        }
        return Map.copyOf(found);
    }

    @Override
    public void savePlayerResult(
            UUID id, UUID lessonId, TaskType taskType, PlayerResult result, String username, Instant updatedAt) {
        entityManager.createNativeQuery("""
                        insert into lesson_comprehension_result (
                            id, lesson_id, task_type, result, created_by, updated_at
                        ) values (
                            :id, :lessonId, :taskType, :result, :username, :updatedAt
                        )
                        on conflict (lesson_id, created_by, task_type) do update
                        set result = excluded.result, updated_at = excluded.updated_at
                        """)
                .setParameter("id", id)
                .setParameter("lessonId", lessonId)
                .setParameter("taskType", taskType.name())
                .setParameter("result", result.name())
                .setParameter("username", username)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate();
    }

    @Override
    public boolean saveVisualAidResult(
            UUID lessonId,
            TaskType taskType,
            VisualAidResult result,
            String username,
            Instant updatedAt) {
        int updated = entityManager.createNativeQuery("""
                        insert into lesson_comprehension_result (
                            id, lesson_id, task_type, result, visual_aid_result, created_by, updated_at
                        ) values (
                            :id, :lessonId, :taskType, :playerResult, :result, :username, :updatedAt
                        )
                        on conflict (lesson_id, created_by, task_type) do update
                        set visual_aid_result = excluded.visual_aid_result, updated_at = excluded.updated_at
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("lessonId", lessonId)
                .setParameter("username", username)
                .setParameter("taskType", taskType.name())
                .setParameter("playerResult", PlayerResult.NOT_TRIED.name())
                .setParameter("result", result.name())
                .setParameter("updatedAt", updatedAt)
                .executeUpdate();
        return updated == 1;
    }
}
