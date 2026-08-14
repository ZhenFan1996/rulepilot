package com.rulepilot.ingestion.adapter.in.web;

import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.ingestion.application.ProcessingProgressTracker;
import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/progress")
@Profile("!test")
public class ProcessingProgressController {

    private final ProcessingProgressTracker progress;
    private final DocumentVersionScopeLookup versions;

    public ProcessingProgressController(
            ProcessingProgressTracker progress,
            DocumentVersionScopeLookup versions) {
        this.progress = progress;
        this.versions = versions;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter progress(@PathVariable UUID versionId, Principal principal) {
        var version = requireOwnedVersion(versionId, principal);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Runnable> subscription = new AtomicReference<>();
        AtomicBoolean cleanupRequested = new AtomicBoolean(false);
        Runnable unsubscribe = () -> {
            Runnable registered = subscription.get();
            if (registered == null) {
                cleanupRequested.set(true);
            } else {
                registered.run();
            }
        };
        Runnable registered = progress.subscribe(versionId, snapshot -> send(emitter, snapshot, unsubscribe));
        subscription.set(registered);
        if (cleanupRequested.get()) {
            registered.run();
            return emitter;
        }
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ignored -> unsubscribe.run());
        send(emitter, authoritativeProgress(version), unsubscribe);
        return emitter;
    }

    @GetMapping(path = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    ProgressSnapshot snapshot(@PathVariable UUID versionId, Principal principal) {
        return authoritativeProgress(requireOwnedVersion(versionId, principal));
    }

    private DocumentVersionScopeLookup.VersionScope requireOwnedVersion(UUID versionId, Principal principal) {
        return versions.findVersion(versionId)
                .filter(candidate -> candidate.createdBy().equals(principal.getName()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "document version does not exist"));
    }

    private ProgressSnapshot authoritativeProgress(DocumentVersionScopeLookup.VersionScope version) {
        var cached = progress.current(version.documentVersionId());
        if ("READY".equals(version.processingStatus()) || "FAILED".equals(version.processingStatus())) {
            int processedPages = cached.map(ProgressSnapshot::processedPages).orElse(0);
            int totalPages = cached.map(ProgressSnapshot::totalPages).orElse(processedPages);
            return new ProgressSnapshot(
                    version.processingStatus(), 100, processedPages, Math.max(processedPages, totalPages), true);
        }
        return cached.orElseGet(() ->
                new ProgressSnapshot(version.processingStatus(), 0, 0, 0, false));
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
