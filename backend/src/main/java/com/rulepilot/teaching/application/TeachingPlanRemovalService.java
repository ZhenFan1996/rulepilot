package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped lifecycle operations for generated lessons. */
@Service
@Profile("!test")
public class TeachingPlanRemovalService {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final AssistantRuns runs;

    public TeachingPlanRemovalService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            AssistantRuns runs) {
        this.plans = plans;
        this.lessons = lessons;
        this.runs = runs;
    }

    @Transactional
    public void removeOwned(UUID planId, String username) {
        TeachingPlan plan = plans.findByIdAndCreatedBy(planId, username)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        cancel(plan.id(), username);
        plans.delete(plan.id());
    }

    @Transactional
    public CleanupPreview previewDuplicateCleanup(String username) {
        List<TeachingPlan> candidates = duplicateCandidates(username);
        return new CleanupPreview(candidates.size(), candidates.stream().map(plan -> new CleanupCandidate(
                plan.id(), plan.gameTitle(), plan.documentVersionId(), plan.createdAt())).toList());
    }

    @Transactional
    public CleanupResult removeDuplicatePlans(String username) {
        List<TeachingPlan> candidates = duplicateCandidates(username);
        candidates.forEach(plan -> {
            cancel(plan.id(), username);
            plans.delete(plan.id());
        });
        return new CleanupResult(candidates.size());
    }

    @Transactional
    public void removeOwnedForDocumentVersions(Set<UUID> documentVersionIds, String username) {
        if (documentVersionIds == null || documentVersionIds.isEmpty()) return;
        plans.findAllByCreatedBy(username).stream()
                .filter(plan -> documentVersionIds.contains(plan.documentVersionId()))
                .forEach(plan -> {
                    cancel(plan.id(), username);
                    plans.delete(plan.id());
                });
    }

    private List<TeachingPlan> duplicateCandidates(String username) {
        Map<DuplicateKey, List<TeachingPlan>> grouped = new LinkedHashMap<>();
        plans.findAllByCreatedBy(username).forEach(plan -> grouped
                .computeIfAbsent(DuplicateKey.from(plan), ignored -> new ArrayList<>())
                .add(plan));
        return grouped.values().stream()
                .filter(group -> group.size() > 1)
                .flatMap(group -> group.stream()
                        .sorted(Comparator.comparingInt(this::lessonQuality)
                                .thenComparing(TeachingPlan::createdAt)
                                .reversed())
                        .skip(1))
                .toList();
    }

    private int lessonQuality(TeachingPlan plan) {
        return lessons.findLatestByPlan(plan.id())
                .map(lesson -> switch (lesson.status()) {
                    case COMPLETE -> 3;
                    case DRAFT_READY -> 2;
                    case INCOMPLETE -> 1;
                })
                .orElse(0);
    }

    private void cancel(UUID planId, String username) {
        List.of(AssistantRunMode.TEACHING, AssistantRunMode.VISUAL_ENRICHMENT).forEach(mode -> {
            runs.findLatestOwned(mode, planId, username)
                    .map(AssistantRuns.RunDetails::run)
                    .filter(run -> !run.state().terminal())
                    .ifPresent(run -> runs.requestCancellation(run.id(), username));
            runs.deleteOwned(mode, planId, username);
        });
    }

    private record DuplicateKey(UUID documentVersionId) {
        static DuplicateKey from(TeachingPlan plan) {
            return new DuplicateKey(plan.documentVersionId());
        }
    }

    public record CleanupPreview(int duplicateCount, List<CleanupCandidate> candidates) {}

    public record CleanupCandidate(UUID planId, String gameTitle, UUID documentVersionId, java.time.Instant createdAt) {}

    public record CleanupResult(int deletedCount) {}
}
