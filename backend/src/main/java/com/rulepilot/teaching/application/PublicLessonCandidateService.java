package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonQualityReport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
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
    private final TaskScheduler admissionScheduler;
    private final TeachingTerminalRecovery terminalRecovery;
    private final Duration admissionTimeout;
    private final LessonQualityEvaluator qualityEvaluator;
    private final LessonCandidateComparisonPolicy comparisonPolicy = new LessonCandidateComparisonPolicy();

    public PublicLessonCandidateService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository repository,
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor,
            @Qualifier("teachingAdmissionScheduler") TaskScheduler admissionScheduler,
            TeachingTerminalRecovery terminalRecovery,
            @Value("${rulepilot.teaching.candidate.admission-timeout:PT30M}") Duration admissionTimeout,
            LessonQualityEvaluator qualityEvaluator) {
        this.plans = plans;
        this.repository = repository;
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
        this.admissionScheduler = admissionScheduler;
        this.terminalRecovery = terminalRecovery;
        if (admissionTimeout == null
                || admissionTimeout.isZero()
                || admissionTimeout.isNegative()
                || admissionTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException(
                    "teaching candidate admission timeout must be positive and at most two hours");
        }
        this.admissionTimeout = admissionTimeout;
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
        UUID activationId = UUID.randomUUID();
        var admission = new TeachingQueueAdmission(
                admissionScheduler,
                terminalRecovery,
                admissionTimeout,
                run.id(),
                expiry -> recordQueueTerminal(run, plan.createdBy(), activationId, expiry));
        try {
            admission.scheduleExpiry();
            executor.execute(() -> {
                if (!admission.activate()) return;
                RunSnapshot claimed;
                try {
                    claimed = claimQueued(run, activationId);
                } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
                    admission.finish();
                    return;
                } catch (AgentExecutionStoppedException stopped) {
                    admission.finish();
                    return;
                } catch (RuntimeException workerAdmissionFailure) {
                    admission.failWorkerAdmission();
                    return;
                }
                try {
                    var outcome = lessons.generateCandidate(teachingPlanId, plan.createdBy(), claimed);
                    lessons.finish(outcome);
                } finally {
                    admission.finish();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            admission.reject();
            throw schedulingFailure;
        }
        return Optional.of(new CandidateLaunch(run.id(), run.state(), false));
    }

    private boolean recordQueueTerminal(
            RunSnapshot queued,
            String ownerUsername,
            UUID activationId,
            TeachingQueueAdmission.Expiry expiry) {
        String errorCode = switch (expiry) {
            case TIMEOUT -> "TEACHING_CANDIDATE_QUEUE_TIMEOUT";
            case REJECTED -> "TEACHING_CANDIDATE_QUEUE_FULL";
            case WORKER_ADMISSION_FAILED -> "TEACHING_CANDIDATE_WORKER_ADMISSION_FAILED";
        };
        String summary = switch (expiry) {
            case TIMEOUT -> "Public lesson candidate waited too long for a worker and is safe to retry";
            case REJECTED -> "Public lesson candidate could not enter its bounded worker queue";
            case WORKER_ADMISSION_FAILED -> "Public lesson candidate could not acquire its durable worker lease";
        };
        try {
            if (expiry == TeachingQueueAdmission.Expiry.WORKER_ADMISSION_FAILED) {
                runs.failQueuedIfUnactivatedOrOwned(
                        queued.id(), ownerUsername, activationId, errorCode, summary);
            } else {
                runs.failQueuedIfUnactivated(queued.id(), ownerUsername, errorCode, summary);
            }
            return true;
        } catch (RuntimeException persistenceFailure) {
            return false;
        }
    }

    private RunSnapshot claimQueued(RunSnapshot queued, UUID activationId) {
        Instant admittedAt = Instant.now();
        try {
            return runs.activateQueued(queued, activationId, admittedAt);
        } catch (AgentWorkAlreadyClaimedException | AgentExecutionStoppedException definitive) {
            throw definitive;
        } catch (RuntimeException ambiguousResponse) {
            try {
                return runs.activateQueued(queued, activationId, admittedAt);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(ambiguousResponse);
                throw retryFailure;
            }
        }
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
