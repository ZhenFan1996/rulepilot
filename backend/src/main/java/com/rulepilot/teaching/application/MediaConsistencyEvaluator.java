package com.rulepilot.teaching.application;

import com.rulepilot.teaching.SpeechSynthesisPort.SpeechCue;
import com.rulepilot.teaching.SpeechSynthesisPort.SynthesizedSpeech;
import com.rulepilot.teaching.domain.ChapterVideo;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.MediaConsistencyReport;
import com.rulepilot.teaching.domain.MediaConsistencyReport.CheckStatus;
import com.rulepilot.teaching.domain.MediaConsistencyReport.CheckType;
import com.rulepilot.teaching.domain.MediaConsistencyReport.ConsistencyCheck;
import com.rulepilot.teaching.domain.MediaConsistencyReport.ConsistencyStatus;
import com.rulepilot.teaching.domain.NarrationScript;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MediaConsistencyEvaluator {

    public MediaConsistencyReport evaluate(
            IllustratedLesson lesson,
            NarrationScript narration,
            SynthesizedSpeech speech,
            ChapterVideo video) {
        var checks = List.of(
                check(CheckType.NARRATION_CONTENT, "解说内容", narrationContentMatches(lesson, narration)),
                check(CheckType.NARRATION_CITATIONS, "解说引用", narrationCitationsMatch(lesson, narration)),
                check(CheckType.VIDEO_SUBTITLES, "视频字幕", videoSubtitlesMatch(narration, video)),
                check(CheckType.VIDEO_CITATIONS, "视频引用", videoCitationsMatch(narration, video)),
                check(CheckType.VIDEO_TIMING, "视频时间轴", videoTimingMatches(speech, video)));
        long passed = checks.stream().filter(check -> check.status() == CheckStatus.PASS).count();
        int percent = (int) (passed * 100 / checks.size());
        var status = passed == checks.size() ? ConsistencyStatus.CONSISTENT : ConsistencyStatus.INCONSISTENT;
        return new MediaConsistencyReport(status, percent, checks);
    }

    private ConsistencyCheck check(CheckType type, String summary, boolean matches) {
        return new ConsistencyCheck(
                type,
                matches ? CheckStatus.PASS : CheckStatus.FAIL,
                summary,
                matches ? "与已验证讲解表示一致。" : "检测到内容、引用或时间轴不一致。");
    }

    private boolean narrationContentMatches(IllustratedLesson lesson, NarrationScript narration) {
        if (!narration.illustratedLessonId().equals(lesson.id())
                || narration.chapters().size() != lesson.sections().size()) {
            return false;
        }
        for (var section : lesson.sections()) {
            var chapter = narration.chapters().get(section.position() - 1);
            if (chapter.position() != section.position() || !chapter.topicKey().equals(section.topicKey())) {
                return false;
            }
            if (section.evidenceStatus() == EvidenceStatus.SUPPORTED) {
                var lessonTexts = section.steps().stream().map(IllustratedLesson.LessonStep::text).toList();
                var narrationTexts = chapter.segments().stream().map(NarrationScript.NarrationSegment::text).toList();
                if (!chapter.supported() || !lessonTexts.equals(narrationTexts)) {
                    return false;
                }
            } else if (chapter.supported() || chapter.segments().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean narrationCitationsMatch(IllustratedLesson lesson, NarrationScript narration) {
        if (narration.chapters().size() != lesson.sections().size()) {
            return false;
        }
        for (var section : lesson.sections()) {
            var chapter = narration.chapters().get(section.position() - 1);
            if (section.evidenceStatus() == EvidenceStatus.SUPPORTED) {
                var lessonPages = section.steps().stream().map(IllustratedLesson.LessonStep::sourcePages).toList();
                var narrationPages = chapter.segments().stream().map(NarrationScript.NarrationSegment::sourcePages).toList();
                if (!lessonPages.equals(narrationPages)) {
                    return false;
                }
            } else if (chapter.segments().stream().anyMatch(segment -> !segment.sourcePages().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private boolean videoSubtitlesMatch(NarrationScript narration, ChapterVideo video) {
        if (!video.illustratedLessonId().equals(narration.illustratedLessonId())
                || video.chapters().size() != narration.chapters().size()) {
            return false;
        }
        for (var chapter : narration.chapters()) {
            var videoChapter = video.chapters().get(chapter.position() - 1);
            var narrationText = chapter.segments().stream().map(NarrationScript.NarrationSegment::text).toList();
            var videoText = videoChapter.frames().stream().map(ChapterVideo.VideoFrame::subtitle).toList();
            if (!narrationText.equals(videoText)) {
                return false;
            }
        }
        return true;
    }

    private boolean videoCitationsMatch(NarrationScript narration, ChapterVideo video) {
        if (video.chapters().size() != narration.chapters().size()) {
            return false;
        }
        for (var chapter : narration.chapters()) {
            var videoChapter = video.chapters().get(chapter.position() - 1);
            var narrationPages = chapter.segments().stream().map(NarrationScript.NarrationSegment::sourcePages).toList();
            var videoPages = videoChapter.frames().stream().map(ChapterVideo.VideoFrame::sourcePages).toList();
            if (!narrationPages.equals(videoPages)) {
                return false;
            }
        }
        return true;
    }

    private boolean videoTimingMatches(SynthesizedSpeech speech, ChapterVideo video) {
        if (speech.durationMillis() != video.durationMillis()) {
            return false;
        }
        var videoFrames = new ArrayList<ChapterVideo.VideoFrame>();
        video.chapters().forEach(chapter -> videoFrames.addAll(chapter.frames()));
        if (videoFrames.size() != speech.cues().size()) {
            return false;
        }
        for (int index = 0; index < speech.cues().size(); index++) {
            SpeechCue cue = speech.cues().get(index);
            var frame = videoFrames.get(index);
            if (cue.segmentPosition() != frame.segmentPosition()
                    || cue.startMillis() != frame.startMillis()
                    || cue.endMillis() != frame.endMillis()) {
                return false;
            }
        }
        return true;
    }
}
