package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftPresentationNormalizerTest {

    private final LessonDraftPresentationNormalizer normalizer = new LessonDraftPresentationNormalizer();

    @Test
    void leavesNaturalPlayerFacingProseUntouched() {
        UUID chunkId = UUID.randomUUID();
        SectionDraft draft = new SectionDraft(
                "第一轮",
                VisualKind.REFERENCE_CARD,
                "所有其他玩家连续弃牌后，本墩结束。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "继续下一墩",
                        TeachingMove.FLOW,
                        "如果上一墩的赢家已无手牌，就由其左边下一位玩家开墩。",
                        List.of(chunkId))));

        assertThat(normalizer.normalize(draft, request(chunkId))).isSameAs(draft);
    }

    @Test
    void leavesSymbolsBracketedTermsAndIdentifierLikeTextUntouched() {
        UUID chunkId = UUID.randomUUID();
        String text = "找到 [cost]、🧩 与规则书命名的 E1 区域后继续。";
        SectionDraft draft = new SectionDraft(
                "保留来源原词",
                VisualKind.REFERENCE_CARD,
                text,
                List.of(chunkId),
                List.of(new StepDraft("照原图确认", TeachingMove.DO, text, List.of(chunkId))));

        assertThat(normalizer.normalize(draft, request(chunkId))).isSameAs(draft);
        assertThat(draft.visualCaption()).isEqualTo(text);
        assertThat(draft.steps().getFirst().text()).isEqualTo(text);
    }

    private SectionRequest request(UUID chunkId) {
        return new SectionRequest(
                "flow",
                "流程",
                "讲清流程",
                List.of("flow"),
                List.of(),
                List.of(new EvidenceInput(chunkId, "FLOW", "Flow", "Rule", 8, 8)));
    }
}
