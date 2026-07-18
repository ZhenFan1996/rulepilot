package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.adapter.out.speech.FakeSpeechSynthesis;
import com.rulepilot.teaching.adapter.out.video.ManifestVideoComposition;
import com.rulepilot.teaching.domain.ChapterVideo;
import com.rulepilot.teaching.domain.ChapterVideo.VideoChapter;
import com.rulepilot.teaching.domain.ChapterVideo.VideoFrame;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.MediaConsistencyReport.CheckStatus;
import com.rulepilot.teaching.domain.MediaConsistencyReport.CheckType;
import com.rulepilot.teaching.domain.MediaConsistencyReport.ConsistencyStatus;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaConsistencyEvaluatorTest {

    @Test
    void reportsExactSharedMediaAndDetectsSubtitleDrift() {
        var lesson = lesson();
        var narration = new NarrationScriptFactory().create(lesson);
        var speech = new FakeSpeechSynthesis().synthesize(narration);
        var video = new ManifestVideoComposition().compose(lesson, narration, speech);
        var evaluator = new MediaConsistencyEvaluator();

        var consistent = evaluator.evaluate(lesson, narration, speech, video);
        var inconsistent = evaluator.evaluate(lesson, narration, speech, drifted(video));

        assertThat(consistent.status()).isEqualTo(ConsistencyStatus.CONSISTENT);
        assertThat(consistent.consistencyPercent()).isEqualTo(100);
        assertThat(consistent.checks()).allMatch(check -> check.status() == CheckStatus.PASS);
        assertThat(inconsistent.status()).isEqualTo(ConsistencyStatus.INCONSISTENT);
        assertThat(inconsistent.consistencyPercent()).isEqualTo(80);
        assertThat(inconsistent.checks())
                .filteredOn(check -> check.type() == CheckType.VIDEO_SUBTITLES)
                .singleElement()
                .extracting(check -> check.status())
                .isEqualTo(CheckStatus.FAIL);
    }

    private IllustratedLesson lesson() {
        return new IllustratedLesson(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LessonStatus.COMPLETE,
                List.of(new LessonSection(
                        1,
                        TeachingSectionType.SETUP,
                        "Setup",
                        true,
                        EvidenceStatus.SUPPORTED,
                        VisualKind.TABLE_LAYOUT,
                        "摆放示意",
                        List.of(new LessonStep(1, "将棋盘放在桌面中央。", List.of(1), List.of())))),
                Instant.parse("2026-07-18T08:00:00Z"));
    }

    private ChapterVideo drifted(ChapterVideo video) {
        var chapter = video.chapters().getFirst();
        var frame = chapter.frames().getFirst();
        var driftedFrame = new VideoFrame(
                frame.segmentPosition(), frame.startMillis(), frame.endMillis(), "错误字幕", frame.sourcePages());
        var driftedChapter = new VideoChapter(
                chapter.position(),
                chapter.type(),
                chapter.title(),
                chapter.evidenceStatus(),
                chapter.visualKind(),
                chapter.visualCaption(),
                chapter.startMillis(),
                chapter.endMillis(),
                List.of(driftedFrame));
        return new ChapterVideo(
                video.id(),
                video.illustratedLessonId(),
                video.status(),
                video.durationMillis(),
                List.of(driftedChapter),
                video.createdAt());
    }
}
