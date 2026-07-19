package com.rulepilot.teaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NarrationScript(
        UUID id,
        UUID illustratedLessonId,
        ScriptStatus status,
        List<NarrationChapter> chapters,
        Instant createdAt) {

    public NarrationScript {
        if (id == null || illustratedLessonId == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("narration script identity is required");
        }
        chapters = List.copyOf(chapters);
    }

    public enum ScriptStatus {
        READY,
        INCOMPLETE
    }

    public record NarrationChapter(
            int position,
            String topicKey,
            String title,
            boolean supported,
            List<NarrationSegment> segments) {

        public NarrationChapter {
            if (position < 1 || topicKey == null || topicKey.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("narration chapter identity is required");
            }
            segments = List.copyOf(segments);
        }
    }

    public record NarrationSegment(int position, String text, List<Integer> sourcePages) {

        public NarrationSegment {
            if (position < 1 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("narration segment content is required");
            }
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
