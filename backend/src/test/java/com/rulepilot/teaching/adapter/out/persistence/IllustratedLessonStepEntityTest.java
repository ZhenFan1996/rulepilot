package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IllustratedLessonStepEntityTest {

    @Test
    void roundTripsEveryOrderedSourceTypedVisualWhileKeepingTheLegacyPrimaryFocus() {
        UUID evidence = UUID.randomUUID();
        List<VisualFocus> visuals = List.of(
                new VisualFocus(2, "行动图标", "骰子图标旁有箭头", 100, 100, 180, 120),
                new VisualFocus(
                        3,
                        "牌面示例",
                        "牌面下方排列三个资源图标",
                        220,
                        260,
                        260,
                        320,
                        VisualSourceKind.EMBEDDED_AUTHOR_IMAGE),
                new VisualFocus(
                        4,
                        "完整流程图",
                        "整页是一张连续流程图",
                        0,
                        0,
                        1_000,
                        1_000,
                        VisualSourceKind.FULL_PAGE));
        LessonStep source = new LessonStep(
                1,
                "执行行动",
                TeachingMove.VISUAL,
                "依次完成三个阶段。",
                List.of(2, 3, 4),
                List.of(evidence),
                List.of(),
                visuals.getFirst(),
                visuals);

        IllustratedLessonStepEntity entity = new IllustratedLessonStepEntity(UUID.randomUUID(), source);
        LessonStep restored = entity.toDomain();

        assertThat(restored.visualFocus()).isEqualTo(visuals.getFirst());
        assertThat(restored.visualFoci()).containsExactlyElementsOf(visuals);
    }

    @Test
    void readsALegacyPrimaryVisualWhenTheNewJsonColumnHasNoEntries() {
        UUID evidence = UUID.randomUUID();
        LessonStep source = new LessonStep(
                1,
                "识别棋盘",
                TeachingMove.VISUAL,
                "先认出中央棋盘。",
                List.of(2),
                List.of(evidence),
                new VisualFocus(2, "中央棋盘", 100, 120, 400, 300));
        IllustratedLessonStepEntity entity = new IllustratedLessonStepEntity(UUID.randomUUID(), source);
        entity.visualFociJson = "[]";

        LessonStep restored = entity.toDomain();

        assertThat(restored.visualFoci()).containsExactly(source.visualFocus());
    }
}
