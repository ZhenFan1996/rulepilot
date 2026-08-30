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
    void exposesOnlyCitedChaptersFromAMixedDraftReadyLesson() {
        LessonSection cited = section(EvidenceStatus.CITED_DRAFT, "有引用的流程。", true);
        LessonSection insufficient = new LessonSection(
                2,
                "ending",
                List.of("ending"),
                "结束",
                true,
                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                VisualKind.REFERENCE_CARD,
                "等待来源",
                List.of(),
                List.of(),
                List.of(new LessonStep(
                        1, "暂时跳过", TeachingMove.WATCH, "尚无可验证来源。", List.of(), List.of())));
        IllustratedLesson stored = lesson(LessonStatus.DRAFT_READY, List.of(cited, insufficient));

        assertThat(PlayerFacingLessonLanguagePolicy.isPubliclyReadable(stored)).isTrue();
        assertThat(PlayerFacingLessonLanguagePolicy.publicProjection(stored).sections())
                .containsExactly(cited);
        assertThat(stored.sections()).containsExactly(cited, insufficient);
    }

    private IllustratedLesson lesson(LessonStatus status, LessonSection section) {
        return lesson(status, List.of(section));
    }

    private IllustratedLesson lesson(LessonStatus status, List<LessonSection> sections) {
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), status, sections, "test", Instant.now());
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
