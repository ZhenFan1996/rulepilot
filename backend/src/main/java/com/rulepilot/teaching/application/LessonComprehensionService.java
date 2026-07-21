package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.LessonComprehensionReport;
import com.rulepilot.teaching.domain.LessonComprehensionReport.PlayerResult;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskReadiness;
import com.rulepilot.teaching.domain.LessonComprehensionReport.TaskType;
import com.rulepilot.teaching.domain.LessonComprehensionReport.VisualAidResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class LessonComprehensionService {

    private final IllustratedLessonRepository lessons;
    private final LessonComprehensionRepository results;
    private final LessonComprehensionEvaluator evaluator;
    private final Clock clock;

    @Autowired
    public LessonComprehensionService(
            IllustratedLessonRepository lessons,
            LessonComprehensionRepository results,
            LessonComprehensionEvaluator evaluator) {
        this(lessons, results, evaluator, Clock.systemUTC());
    }

    LessonComprehensionService(
            IllustratedLessonRepository lessons,
            LessonComprehensionRepository results,
            LessonComprehensionEvaluator evaluator,
            Clock clock) {
        this.lessons = lessons;
        this.results = results;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LessonComprehensionReport progress(UUID teachingPlanId, String username) {
        var lesson = latestLesson(teachingPlanId);
        return evaluate(lesson, username);
    }

    @Transactional
    public LessonComprehensionReport record(
            UUID teachingPlanId, TaskType taskType, PlayerResult result, String username) {
        if (result == null || result == PlayerResult.NOT_TRIED) {
            throw new IllegalArgumentException("player comprehension result must be an explicit choice");
        }
        var lesson = latestLesson(teachingPlanId);
        var current = evaluate(lesson, username);
        var task = current.tasks().stream()
                .filter(candidate -> candidate.type() == taskType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("player comprehension task does not exist"));
        if (task.readiness() != TaskReadiness.READY) {
            throw new IllegalArgumentException("player comprehension task is not ready");
        }
        results.savePlayerResult(
                UUID.randomUUID(), lesson.id(), taskType, result, username, Instant.now(clock));
        return evaluate(lesson, username);
    }

    @Transactional
    public LessonComprehensionReport recordVisualAid(
            UUID teachingPlanId, String visualAidKey, VisualAidResult result, String username) {
        if (result == null || result == VisualAidResult.NOT_RATED) {
            throw new IllegalArgumentException("visual aid result must be an explicit choice");
        }
        var lesson = latestLesson(teachingPlanId);
        var current = evaluate(lesson, username);
        var aid = current.visualAids().stream()
                .filter(candidate -> candidate.key().equals(visualAidKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("visual aid does not exist"));
        results.saveVisualAidResult(lesson.id(), aid.key(), result, username, Instant.now(clock));
        return evaluate(lesson, username);
    }

    private com.rulepilot.teaching.domain.IllustratedLesson latestLesson(UUID teachingPlanId) {
        return lessons.findLatestByPlan(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
    }

    private LessonComprehensionReport evaluate(
            com.rulepilot.teaching.domain.IllustratedLesson lesson, String username) {
        return evaluator.evaluate(
                lesson, results.findResults(lesson.id(), username), results.findVisualAidResults(lesson.id(), username));
    }
}
