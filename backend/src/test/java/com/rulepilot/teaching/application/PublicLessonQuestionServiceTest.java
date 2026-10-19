package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.shared.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublicLessonQuestionServiceTest {
    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final RuleAnswering answers = mock(RuleAnswering.class);
    private final PublicLessonQuestionService service = new PublicLessonQuestionService(lessons, answers);

    @Test
    void returnsOnlyTheExplicitlySelectedSourceOwnedCropWithoutLoadingImages() {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        var lesson = publicLesson(plan, version, chunk);
        when(lessons.find(plan)).thenReturn(Optional.of(lesson));
        when(answers.answerForPublicReader(eq(version), anyString(), any(), eq(PlayerLocale.EN), any(), anySet(), anyList()))
                .thenAnswer(call -> {
                    List<RuleAnswering.VisualEvidence> offered = call.getArgument(6);
                    assertThat(offered).singleElement().satisfies(image -> {
                        assertThat(image.documentVersionId()).isEqualTo(version);
                        assertThat(image.visibleDescription()).contains("箭头");
                    });
                    return new RuleAnswering.AnswerResult(UUID.randomUUID(), answer(2), Set.of(chunk),
                            Set.of(offered.getFirst().imageId(), "unknown-image"));
                });
        var result = service.answer(plan, new PublicLessonQuestionService.QuestionRequest("Where is the marker?", null, "en")).orElseThrow();
        assertThat(result.answer().status()).isEqualTo("ANSWERED");
        assertThat(result.visualAids()).singleElement().satisfies(aid ->
                assertThat(aid.visualFocus().visibleDescription()).contains("箭头"));
        assertThat(result.examples()).isEmpty();
    }

    @Test
    void sharedEvidenceDoesNotAutomaticallyAttachAnUnselectedImage() {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        when(lessons.find(plan)).thenReturn(Optional.of(publicLesson(plan, version, chunk)));
        when(answers.answerForPublicReader(any(), any(), any(), any(), any(), anySet(), anyList()))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer(2), Set.of(chunk)));
        var result = service.answer(plan, new PublicLessonQuestionService.QuestionRequest("When?", null)).orElseThrow();
        assertThat(result.answer().status()).isEqualTo("ANSWERED");
        assertThat(result.visualAids()).isEmpty();
    }

    @Test
    void selectedImageCannotEscapeTheAnswersEvidence() {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        when(lessons.find(plan)).thenReturn(Optional.of(publicLesson(plan, version, chunk)));
        when(answers.answerForPublicReader(any(), any(), any(), any(), any(), anySet(), anyList()))
                .thenAnswer(call -> {
                    List<RuleAnswering.VisualEvidence> offered = call.getArgument(6);
                    return new RuleAnswering.AnswerResult(UUID.randomUUID(), answer(2), Set.of(UUID.randomUUID()),
                            Set.of(offered.getFirst().imageId()));
                });
        assertThat(service.answer(plan, new PublicLessonQuestionService.QuestionRequest("Where?", null)))
                .hasValueSatisfying(result -> assertThat(result.visualAids()).isEmpty());
    }

    @Test
    void withholdsCitationsOutsidePublicPages() {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        when(lessons.find(plan)).thenReturn(Optional.of(publicLesson(plan, version, chunk)));
        when(answers.answerForPublicReader(any(), any(), any(), any(), any(), anySet(), anyList()))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer(9), Set.of(chunk)));
        assertThat(service.answer(plan, new PublicLessonQuestionService.QuestionRequest("Where?", null)))
                .hasValueSatisfying(result -> {
                    assertThat(result.answer().status()).isEqualTo("INSUFFICIENT_EVIDENCE");
                    assertThat(result.visualAids()).isEmpty();
                });
    }

    @Test
    void unpublishedLessonsNeverInvokeTheAgent() {
        assertThat(service.answer(UUID.randomUUID(), new PublicLessonQuestionService.QuestionRequest("Where?", null))).isEmpty();
        verifyNoInteractions(answers);
    }

    private RuleAnswering.Answer answer(int page) {
        return new RuleAnswering.Answer("ANSWERED", "Place the marker.", "Follow the cited setup rule.",
                List.of(new RuleAnswering.Citation("Setup", page, page)), List.of(), "HIGH", null);
    }

    private PublicLessonReader.PublicLesson publicLesson(UUID planId, UUID versionId, UUID chunk) {
        UUID unrelatedChunk = UUID.randomUUID();
        IllustratedLesson.LessonStep visual = new IllustratedLesson.LessonStep(
                2, "识别标记", IllustratedLesson.TeachingMove.VISUAL, "看清标记。", List.of(2), List.of(chunk),
                new IllustratedLesson.VisualFocus(
                        2, "标记图例", "圆形标记位于带箭头的起始格旁", 100, 100, 200, 200));
        IllustratedLesson.LessonStep sameEvidenceButUnrelatedVisual = new IllustratedLesson.LessonStep(
                1, "传递先手卡", IllustratedLesson.TeachingMove.VISUAL, "将先手卡传给下一位玩家。", List.of(2), List.of(unrelatedChunk),
                new IllustratedLesson.VisualFocus(2, "先手卡", 400, 100, 200, 200));
        IllustratedLesson.LessonStep example = new IllustratedLesson.LessonStep(
                3, "放置示例", IllustratedLesson.TeachingMove.EXAMPLE, "把一个标记放到起始格。", List.of(2), List.of(chunk));
        IllustratedLesson.LessonStep unrelatedSamePage = new IllustratedLesson.LessonStep(
                4, "另一条规则图例", IllustratedLesson.TeachingMove.VISUAL, "不应返回。", List.of(2), List.of(unrelatedChunk),
                new IllustratedLesson.VisualFocus(2, "无关图例", 500, 100, 200, 200));
        IllustratedLesson.LessonStep unrelatedExample = new IllustratedLesson.LessonStep(
                5, "无关示例", IllustratedLesson.TeachingMove.EXAMPLE, "不应返回。", List.of(2), List.of(unrelatedChunk));
        IllustratedLesson.LessonStep otherPage = new IllustratedLesson.LessonStep(
                6, "另一页图例", IllustratedLesson.TeachingMove.VISUAL, "不应返回。", List.of(3), List.of(chunk),
                new IllustratedLesson.VisualFocus(3, "另一页", 100, 100, 200, 200));
        IllustratedLesson lesson = new IllustratedLesson(
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new IllustratedLesson.LessonSection(
                        1, "setup", List.of("设置"), "设置", true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                        "设置图", List.of(2), List.of(chunk, unrelatedChunk),
                        List.of(sameEvidenceButUnrelatedVisual, visual, example, unrelatedSamePage, unrelatedExample, otherPage))),
                "test", Instant.now());
        return new PublicLessonReader.PublicLesson(
                planId, versionId, "规则书", "https://publisher.example/rules.pdf", null, null, lesson);
    }

}
