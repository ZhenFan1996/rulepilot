package com.rulepilot.ingestion.adapter.in.web;

import com.rulepilot.ingestion.application.ProcessingProgressTracker;
import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/progress")
@Profile("!test")
public class ProcessingProgressController {

    private final ProcessingProgressTracker progress;

    public ProcessingProgressController(ProcessingProgressTracker progress) {
        this.progress = progress;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter progress(@PathVariable UUID versionId) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Runnable> unsubscribe = new AtomicReference<>(() -> {});
        unsubscribe.set(progress.subscribe(versionId, snapshot -> send(emitter, snapshot, unsubscribe.get())));
        emitter.onCompletion(unsubscribe.get());
        emitter.onTimeout(unsubscribe.get());
        emitter.onError(ignored -> unsubscribe.get().run());
        progress.current(versionId).ifPresent(snapshot -> send(emitter, snapshot, unsubscribe.get()));
        return emitter;
    }

    private void send(SseEmitter emitter, ProgressSnapshot snapshot, Runnable unsubscribe) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(snapshot));
            if (snapshot.complete()) {
                unsubscribe.run();
                emitter.complete();
            }
        } catch (IOException | IllegalStateException exception) {
            unsubscribe.run();
            emitter.completeWithError(exception);
        }
    }
}
