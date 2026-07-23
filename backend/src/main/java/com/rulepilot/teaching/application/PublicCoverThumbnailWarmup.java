package com.rulepilot.teaching.application;

import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** Warms existing public covers with bounded parallelism after startup without borrowing lesson-generation capacity. */
@Component
@Profile("!test")
class PublicCoverThumbnailWarmup {

    private static final Logger log = LoggerFactory.getLogger(PublicCoverThumbnailWarmup.class);

    private final PublicLessonCatalog catalog;
    private final PublicCoverThumbnailService thumbnails;
    private final TaskExecutor executor;

    PublicCoverThumbnailWarmup(
            PublicLessonCatalog catalog,
            PublicCoverThumbnailService thumbnails,
            @Qualifier("publicCoverWarmupExecutor") TaskExecutor executor) {
        this.catalog = catalog;
        this.thumbnails = thumbnails;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void schedule() {
        try {
            executor.execute(this::warmExistingPublicCovers);
        } catch (RuntimeException rejected) {
            log.warn("Could not schedule public cover warmup: {}", rejected.getClass().getSimpleName());
        }
    }

    void warmExistingPublicCovers() {
        Set<String> sources = new LinkedHashSet<>();
        catalog.latest(60).stream()
                .map(PublicLessonCatalog.Entry::gameCover)
                .filter(java.util.Objects::nonNull)
                .map(cover -> cover.imageUrl())
                .forEach(sources::add);
        sources.forEach(source -> executor.execute(() -> thumbnails.warm(source)));
    }
}
