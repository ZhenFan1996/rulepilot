package com.rulepilot.teaching.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.teaching.LessonLocalizationModel;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Runs bounded model work outside database transactions and persists only a whole structurally valid projection. */
@Service
@Profile("!test")
public class LessonLocalizationWorker {

    private static final Logger log = LoggerFactory.getLogger(LessonLocalizationWorker.class);
    private static final ObjectMapper TRACE_JSON = new ObjectMapper().findAndRegisterModules();

    private final LessonLocalizationPersistence persistence;
    private final LessonLocalizationModel model;

    public LessonLocalizationWorker(LessonLocalizationPersistence persistence, LessonLocalizationModel model) {
        this.persistence = persistence;
        this.model = model;
    }

    public void translate(LessonLocalizationPersistence.Preparation preparation) {
        translate(preparation, CaptureHandle.noop());
    }

    public void translate(
            LessonLocalizationPersistence.Preparation preparation,
            CaptureHandle capture) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        PlayerLocale language = preparation.localization().language();
        try {
            persistence.markRunning(preparation.lessonId(), language);
            var input = persistence.input(preparation.planId(), preparation.lessonId(), preparation.owner(), language);
            if (!model.available(input.owner())) {
                persistence.fail(input.lesson().id(), language, "MODEL_UNAVAILABLE");
                captureFailure(trace, preparation.lessonId(), "LESSON_LOCALIZATION_MODEL_UNAVAILABLE");
                return;
            }
            List<LessonLocalization.SectionTranslation> translated = input.lesson().sections().stream()
                    .map(section -> model.translate(
                            section,
                            language,
                            input.owner(),
                            trace,
                            modelContext(preparation.lessonId()),
                            1))
                    .toList();
            LessonLocalization completed = input.localization().complete(translated, java.time.Instant.now());
            IllustratedLesson localizedLesson = completed.applyTo(input.lesson());
            persistence.complete(input.lesson().id(), language, translated);
            capturePublication(trace, preparation.lessonId(), localizedLesson);
        } catch (RuntimeException failure) {
            log.warn(
                    "Lesson localization failed safely for lesson {} language {} (failureType={})",
                    preparation.lessonId(),
                    language,
                    failure.getClass().getSimpleName());
            captureFailure(trace, preparation.lessonId(), "LESSON_LOCALIZATION_FAILED");
            try {
                persistence.fail(preparation.lessonId(), language, "LOCALIZATION_FAILED");
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
        }
    }

    private TraceEventContext modelContext(UUID lessonId) {
        return TraceEventContext.create(
                Instant.now(),
                JourneyStage.TEACHING,
                UUID.randomUUID(),
                lessonId,
                localizationRun(lessonId));
    }

    private void captureFailure(CaptureHandle capture, UUID lessonId, String code) {
        if (!capture.enabled()) return;
        capture.bindingOrFailure(new BindingOrFailure(
                TraceEventContext.create(
                        Instant.now(),
                        JourneyStage.TEACHING,
                        UUID.randomUUID(),
                        lessonId,
                        localizationRun(lessonId)),
                LifecycleSignal.FAILURE,
                code,
                localizationRun(lessonId),
                null));
    }

    private void capturePublication(
            CaptureHandle capture,
            UUID lessonId,
            IllustratedLesson localizedLesson) {
        if (!capture.enabled()) return;
        ResourceRef resource = localizationRun(lessonId);
        try {
            List<UUID> citations = localizedLesson.sections().stream()
                    .flatMap(section -> java.util.stream.Stream.concat(
                            section.visualSourceChunkIds().stream(),
                            section.steps().stream().flatMap(step -> java.util.stream.Stream.concat(
                                    step.sourceChunkIds().stream(),
                                    step.ruleFacts().stream().flatMap(fact -> fact.sourceChunkIds().stream())))))
                    .distinct()
                    .limit(200)
                    .toList();
            capture.publication(new Publication(
                    TraceEventContext.create(
                            Instant.now(),
                            JourneyStage.TEACHING,
                            UUID.randomUUID(),
                            lessonId,
                            resource),
                    PublicationChannel.TEACHING_LESSON,
                    TRACE_JSON.writeValueAsString(localizedLesson),
                    "LOCALIZATION_READY",
                    citations));
        } catch (JsonProcessingException | RuntimeException traceFailure) {
            capture.bindingOrFailure(new BindingOrFailure(
                    TraceEventContext.create(
                            Instant.now(),
                            JourneyStage.TEACHING,
                            UUID.randomUUID(),
                            lessonId,
                            resource),
                    LifecycleSignal.GAP,
                    "LESSON_LOCALIZATION_PUBLICATION_TRACE_GAP",
                    resource,
                    null));
        }
    }

    private ResourceRef localizationRun(UUID lessonId) {
        return new ResourceRef(ResourceType.LOCALIZATION_RUN, lessonId);
    }
}
