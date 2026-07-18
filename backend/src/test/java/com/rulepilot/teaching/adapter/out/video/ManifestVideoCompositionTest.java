package com.rulepilot.teaching.adapter.out.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.adapter.out.speech.FakeSpeechSynthesis;
import com.rulepilot.teaching.application.NarrationScriptFactory;
import com.rulepilot.teaching.domain.ChapterVideo.VideoStatus;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManifestVideoCompositionTest {

    @Test
    void composesCitedFramesAndSafeEvidenceGapsFromTheSharedLesson() {
        var lesson = new IllustratedLesson(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LessonStatus.INCOMPLETE,
                List.of(
                        section(
                                1,
                                TeachingSectionType.SETUP,
                                EvidenceStatus.SUPPORTED,
                                VisualKind.TABLE_LAYOUT,
                                List.of(new LessonStep(1, "将棋盘放在桌面中央。", List.of(2), List.of()))),
                        section(
                                2,
                                TeachingSectionType.SCORING,
                                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                                VisualKind.SCOREBOARD,
                                List.of(new LessonStep(1, "不应进入视频。", List.of(), List.of())))),
                Instant.parse("2026-07-18T08:00:00Z"));
        var script = new NarrationScriptFactory().create(lesson);
        var speech = new FakeSpeechSynthesis().synthesize(script);
        var composition = new ManifestVideoComposition();

        var video = composition.compose(lesson, script, speech);

        assertThat(video.status()).isEqualTo(VideoStatus.INCOMPLETE);
        assertThat(video.durationMillis()).isEqualTo(speech.durationMillis());
        assertThat(video.chapters()).hasSize(2);
        assertThat(video.chapters().getFirst().visualKind()).isEqualTo(VisualKind.TABLE_LAYOUT);
        assertThat(video.chapters().getFirst().frames().getFirst().subtitle()).isEqualTo("将棋盘放在桌面中央。");
        assertThat(video.chapters().getFirst().frames().getFirst().sourcePages()).containsExactly(2);
        assertThat(video.chapters().get(1).frames().getFirst().subtitle())
                .isEqualTo("本节暂无足够规则证据，已跳过。");
        assertThat(video.chapters().get(1).frames().getFirst().sourcePages()).isEmpty();
        assertThat(composition.compose(lesson, script, speech).id()).isEqualTo(video.id());
    }

    private LessonSection section(
            int position,
            TeachingSectionType type,
            EvidenceStatus evidenceStatus,
            VisualKind visualKind,
            List<LessonStep> steps) {
        return new LessonSection(
                position,
                type,
                type.name(),
                true,
                evidenceStatus,
                visualKind,
                "共享讲解画面",
                steps);
    }
}
