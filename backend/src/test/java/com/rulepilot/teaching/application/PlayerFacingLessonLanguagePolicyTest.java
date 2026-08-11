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

    private IllustratedLesson lesson(LessonStatus status, LessonSection section) {
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), status, List.of(section), "test", Instant.now());
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
