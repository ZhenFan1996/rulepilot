package com.rulepilot.teaching.application;

import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** Starts owner-authorized localization and presents an existing projection without exposing an owner publicly. */
@Service
@Profile("!test")
public class LessonLocalizationService {

    private final LessonLocalizationPersistence persistence;
    private final LessonLocalizationWorker worker;
    private final TaskExecutor executor;

    public LessonLocalizationService(
            LessonLocalizationPersistence persistence,
            LessonLocalizationWorker worker,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor) {
        this.persistence = persistence;
        this.worker = worker;
        this.executor = executor;
    }

    public synchronized LocalizationView prepare(UUID planId, String owner, PlayerLocale language) {
        return prepare(planId, owner, language, CaptureHandle.noop());
    }

    public synchronized LocalizationView prepare(
            UUID planId,
            String owner,
            PlayerLocale language,
            CaptureHandle capture) {
        if (language == PlayerLocale.ZH_CN) throw new IllegalArgumentException("source lesson is already Chinese");
        var preparation = persistence.prepare(planId, owner, language);
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        bindLocalization(trace, preparation);
        if (!preparation.reused()) {
            try {
                executor.execute(() -> worker.translate(preparation, trace));
            } catch (RuntimeException queueFailure) {
                persistence.fail(preparation.lessonId(), language, "QUEUE_UNAVAILABLE");
                captureFailure(trace, preparation.lessonId(), "LESSON_LOCALIZATION_QUEUE_UNAVAILABLE");
                throw queueFailure;
            }
        }
        return view(preparation.localization(), null);
    }

    public LocalizationView view(IllustratedLesson source, PlayerLocale language) {
        if (language == PlayerLocale.ZH_CN) return new LocalizationView(PlayerLocale.ZH_CN, LessonLocalization.Status.READY, source, null);
        return persistence.find(source.id(), language)
                .map(localization -> view(localization, source))
                .orElseGet(() -> new LocalizationView(language, null, source, null));
    }

    private LocalizationView view(LessonLocalization localization, IllustratedLesson source) {
        IllustratedLesson translated = source != null && localization.status() == LessonLocalization.Status.READY
                ? localization.applyTo(source)
                : source;
        return new LocalizationView(localization.language(), localization.status(), translated, localization.failureCode());
    }

    private void bindLocalization(
            CaptureHandle capture,
            LessonLocalizationPersistence.Preparation preparation) {
        if (!capture.enabled()) return;
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, preparation.planId());
        ResourceRef localization = localizationRun(preparation.lessonId());
        boolean bound = capture.bind(plan) && capture.bind(localization);
        capture.bindingOrFailure(new BindingOrFailure(
                traceContext(preparation.lessonId(), localization),
                bound
                        ? preparation.reused() ? LifecycleSignal.REPLAY : LifecycleSignal.BINDING
                        : LifecycleSignal.GAP,
                bound
                        ? preparation.reused()
                                ? "LESSON_LOCALIZATION_REUSED"
                                : "LESSON_LOCALIZATION_BOUND"
                        : "LESSON_LOCALIZATION_BINDING_GAP",
                plan,
                localization));
    }

    private void captureFailure(CaptureHandle capture, UUID lessonId, String code) {
        if (!capture.enabled()) return;
        ResourceRef localization = localizationRun(lessonId);
        capture.bindingOrFailure(new BindingOrFailure(
                traceContext(UUID.randomUUID(), localization),
                LifecycleSignal.FAILURE,
                code,
                localization,
                null));
    }

    private TraceEventContext traceContext(UUID operationId, ResourceRef resource) {
        return TraceEventContext.create(
                Instant.now(), JourneyStage.TEACHING, operationId, null, resource);
    }

    private ResourceRef localizationRun(UUID lessonId) {
        return new ResourceRef(ResourceType.LOCALIZATION_RUN, lessonId);
    }

    public record LocalizationView(
            PlayerLocale language,
            LessonLocalization.Status status,
            IllustratedLesson lesson,
            String failureCode) {}
}
