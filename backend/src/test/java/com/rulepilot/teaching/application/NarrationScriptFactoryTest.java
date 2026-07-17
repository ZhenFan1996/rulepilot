package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.NarrationScript.ScriptStatus;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NarrationScriptFactoryTest {

    private final NarrationScriptFactory factory = new NarrationScriptFactory();

    @Test
    void preservesSupportedLessonTextAndReplacesUnsupportedClaimsWithAGap() {
        var lessonId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-07-18T08:00:00Z");
        var lesson = new IllustratedLesson(
                lessonId,
                UUID.randomUUID(),
                LessonStatus.INCOMPLETE,
                List.of(
                        section(
                                1,
                                TeachingSectionType.SETUP,
                                EvidenceStatus.SUPPORTED,
                                List.of(new LessonStep(1, "将棋盘放在桌面中央。", List.of(3)))),
                        section(
                                2,
                                TeachingSectionType.SCORING,
                                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                                List.of(new LessonStep(1, "旧的缺口文本不应进入解说。", List.of())))),
                createdAt);

        var script = factory.create(lesson);

        assertThat(script.status()).isEqualTo(ScriptStatus.INCOMPLETE);
        assertThat(script.illustratedLessonId()).isEqualTo(lessonId);
        assertThat(script.createdAt()).isEqualTo(createdAt);
        assertThat(script.chapters().getFirst().segments().getFirst().text()).isEqualTo("将棋盘放在桌面中央。");
        assertThat(script.chapters().getFirst().segments().getFirst().sourcePages()).containsExactly(3);
        assertThat(script.chapters().get(1).segments().getFirst().text())
                .isEqualTo(NarrationScriptFactory.INSUFFICIENT_EVIDENCE_MESSAGE);
        assertThat(script.chapters().get(1).segments().getFirst().sourcePages()).isEmpty();
        assertThat(factory.create(lesson).id()).isEqualTo(script.id());
    }

    private LessonSection section(
            int position,
            TeachingSectionType type,
            EvidenceStatus evidenceStatus,
            List<LessonStep> steps) {
        return new LessonSection(
                position,
                type,
                type.name(),
                true,
                evidenceStatus,
                VisualKind.REFERENCE_CARD,
                "示意",
                steps);
    }
}
