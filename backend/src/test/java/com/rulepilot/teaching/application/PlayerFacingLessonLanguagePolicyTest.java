package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerFacingLessonLanguagePolicyTest {

    @Test
    void usesStructuredPublicationAndEvidenceStateInsteadOfWording() {
        IllustratedLesson citedDraft = lesson(
                LessonStatus.DRAFT_READY,
                section(EvidenceStatus.CITED_DRAFT, "规则没有说明之后怎样处理。", true));

        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(citedDraft)).isTrue();
        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(
                        lesson(LessonStatus.INCOMPLETE, citedDraft.sections().getFirst())))
                .isFalse();
        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(
                        lesson(LessonStatus.DRAFT_READY, section(EvidenceStatus.INSUFFICIENT_EVIDENCE, "内容", true))))
                .isFalse();
        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(
                        lesson(LessonStatus.DRAFT_READY, section(EvidenceStatus.SUPPORTED, "内容", false))))
                .isFalse();
    }

    @Test
    void keepsSupportedContentPublicWhenAnotherSectionHasOnlyALocalEvidenceGap() {
        IllustratedLesson mixed = lesson(
                LessonStatus.DRAFT_READY,
                section(EvidenceStatus.SUPPORTED, "完成当前有来源的行动。", true),
                section(EvidenceStatus.INSUFFICIENT_EVIDENCE, "外部规则暂不可用。", false));

        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(mixed)).isTrue();
        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(lesson(
                        LessonStatus.DRAFT_READY,
                        section(EvidenceStatus.INSUFFICIENT_EVIDENCE, "局部缺口一。", false),
                        section(EvidenceStatus.INSUFFICIENT_EVIDENCE, "局部缺口二。", false))))
                .isFalse();
        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(lesson(
                        LessonStatus.DRAFT_READY,
                        section(EvidenceStatus.SUPPORTED, "看似可读但没有引用。", false),
                        section(EvidenceStatus.INSUFFICIENT_EVIDENCE, "局部缺口。", false))))
                .isFalse();
    }

    private IllustratedLesson lesson(LessonStatus status, LessonSection... sections) {
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), status, List.of(sections), "test", Instant.now());
    }

    private LessonSection section(EvidenceStatus status, String text, boolean cited) {
        UUID chunkId = UUID.randomUUID();
        return new LessonSection(
                1,
                "flow",
                List.of("flow"),
                "流程",
                true,
                status,
                VisualKind.REFERENCE_CARD,
                "流程说明",
                cited ? List.of(3) : List.of(),
                cited ? List.of(chunkId) : List.of(),
                List.of(new LessonStep(
                        1,
                        "执行",
                        TeachingMove.DO,
                        text,
                        cited ? List.of(3) : List.of(),
                        cited ? List.of(chunkId) : List.of())));
    }
}
