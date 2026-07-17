package com.rulepilot.teaching.domain;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChapterVideo(
        UUID id,
        UUID illustratedLessonId,
        VideoStatus status,
        long durationMillis,
        List<VideoChapter> chapters,
        Instant createdAt) {

    public ChapterVideo {
        if (id == null || illustratedLessonId == null || status == null || durationMillis < 0 || createdAt == null) {
            throw new IllegalArgumentException("chapter video identity is required");
        }
        chapters = List.copyOf(chapters);
    }

    public enum VideoStatus {
        READY,
        INCOMPLETE
    }

    public record VideoChapter(
            int position,
            TeachingSectionType type,
            String title,
            EvidenceStatus evidenceStatus,
            VisualKind visualKind,
            String visualCaption,
            long startMillis,
            long endMillis,
            List<VideoFrame> frames) {

        public VideoChapter {
            if (position < 1 || type == null || title == null || title.isBlank() || evidenceStatus == null
                    || visualKind == null || visualCaption == null || startMillis < 0 || endMillis <= startMillis) {
                throw new IllegalArgumentException("video chapter content is required");
            }
            frames = List.copyOf(frames);
        }
    }

    public record VideoFrame(
            int segmentPosition,
            long startMillis,
            long endMillis,
            String subtitle,
            List<Integer> sourcePages) {

        public VideoFrame {
            if (segmentPosition < 1 || startMillis < 0 || endMillis <= startMillis
                    || subtitle == null || subtitle.isBlank()) {
                throw new IllegalArgumentException("video frame content is required");
            }
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
