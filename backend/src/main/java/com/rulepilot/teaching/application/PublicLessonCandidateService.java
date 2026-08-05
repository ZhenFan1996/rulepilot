package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonQualityReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stages a fresh public-lesson candidate and keeps publication as a separate, explicit decision. */
@Service
@Profile("!test")
public class PublicLessonCandidateService {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository repository;
    private final IllustratedLessonService lessons;
    private final AssistantRuns runs;
    private final TaskExecutor executor;
    private final LessonQualityEvaluator qualityEvaluator;
    private final LessonCandidateComparisonPolicy comparisonPolicy = new LessonCandidateComparisonPolicy();

    public PublicLessonCandidateService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository repository,
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor,
            LessonQualityEvaluator qualityEvaluator) {
        this.plans = plans;
        this.repository = repository;
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
        this.qualityEvaluator = qualityEvaluator;
    }

    public synchronized Optional<CandidateLaunch> launch(UUID teachingPlanId) {
        var plan = plans.findById(teachingPlanId).orElse(null);
        if (plan == null || repository.findLatestByPlan(teachingPlanId)
                .filter(PublicLessonReader::isPubliclyReadable)
                .isEmpty()) {
            return Optional.empty();
        }
        UUID subjectId = IllustratedLessonService.candidateSubjectId(teachingPlanId);
        var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, subjectId, plan.createdBy())
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return Optional.of(new CandidateLaunch(run.id(), run.state(), true));
        }

        RunSnapshot run = lessons.beginCandidate(teachingPlanId, plan.createdBy());
        try {
            executor.execute(() -> {
                var outcome = lessons.generateCandidate(teachingPlanId, plan.createdBy(), run);
                lessons.finish(outcome);
            });
        } catch (RuntimeException schedulingFailure) {
            lessons.failScheduling(run);
            throw schedulingFailure;
        }
        return Optional.of(new CandidateLaunch(run.id(), run.state(), false));
    }

    @Transactional(readOnly = true)
    public Optional<CandidateComparison> latestComparison(UUID teachingPlanId) {
        var plan = plans.findById(teachingPlanId).orElse(null);
        if (plan == null) return Optional.empty();
        var active = repository.findLatestByPlan(teachingPlanId).orElse(null);
        var candidate = repository.findLatestCandidateByPlan(teachingPlanId).orElse(null);
        if (active == null || candidate == null) return Optional.empty();
        LessonQualityReport activeQuality = qualityEvaluator.evaluate(plan, active);
        LessonQualityReport candidateQuality = qualityEvaluator.evaluate(plan, candidate);
        var comparison = comparisonPolicy.compare(plan, active, candidate, activeQuality, candidateQuality);
        return Optional.of(new CandidateComparison(
                new LessonVersion(active, activeQuality),
                new LessonVersion(candidate, candidateQuality),
                comparison.recommendation(),
                comparison.reasons()));
    }

    @Transactional
    public Optional<CandidateDecision> applyLatestRecommendation(UUID teachingPlanId) {
        CandidateComparison comparison = latestComparison(teachingPlanId).orElse(null);
        if (comparison == null) return Optional.empty();
        UUID candidateId = comparison.candidate().lesson().id();
        UUID winnerId;
        if (comparison.recommendation() == LessonCandidateRecommendation.PROMOTE_CANDIDATE) {
            repository.promoteCandidate(teachingPlanId, candidateId);
            winnerId = candidateId;
        } else {
            repository.archiveCandidate(teachingPlanId, candidateId);
            winnerId = comparison.active().lesson().id();
        }
        return Optional.of(new CandidateDecision(comparison.recommendation(), winnerId, candidateId));
    }

    public record CandidateLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}

    public record LessonVersion(IllustratedLesson lesson, LessonQualityReport quality) {}

    public record CandidateComparison(
            LessonVersion active,
            LessonVersion candidate,
            LessonCandidateRecommendation recommendation,
            List<String> reasons) {
        public CandidateComparison {
            reasons = List.copyOf(reasons);
        }
    }

    public record CandidateDecision(
            LessonCandidateRecommendation decision,
            UUID winnerLessonId,
            UUID candidateLessonId) {}
}
