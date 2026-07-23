package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicLessonQuestionServiceTest {

    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final RuleAnswering answers = mock(RuleAnswering.class);
    private final PublicLessonQuestionService service = new PublicLessonQuestionService(lessons, answers);

    @Test
    void returnsOnlyVisualAidsAndExamplesThatShareTheAnswerEvidence() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "先放置标记。", "然后执行效果。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(eq(versionId), eq("标记怎么放？"), org.mockito.ArgumentMatchers.contains("设置"), eq(null)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", 1, null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.visualAids()).hasSize(1);
            assertThat(value.visualAids().getFirst().visualFocus().pageNumber()).isEqualTo(2);
            assertThat(value.visualAids().getFirst().relatedStep()).isEqualTo("识别标记");
            assertThat(value.examples()).singleElement().satisfies(example ->
                    assertThat(example.heading()).isEqualTo("放置示例"));
        });
    }

    @Test
    void keepsTheCitedTextAnswerWhenNoLessonVisualSharesItsEvidence() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID lessonChunk = UUID.randomUUID();
        UUID answerChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, lessonChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "先放置标记。", "然后执行效果。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(eq(versionId), eq("标记怎么放？"), org.mockito.ArgumentMatchers.contains("设置"), eq(null)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(answerChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", 1, null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer().citations()).singleElement()
                    .extracting(RuleAnswering.Citation::pageFrom)
                    .isEqualTo(2);
            assertThat(value.visualAids()).isEmpty();
            assertThat(value.examples()).isEmpty();
        });
    }

    @Test
    void doesNotAnswerForAPlanThatIsNotPubliclyReadable() {
        UUID planId = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.empty());

        assertThat(service.answer(planId, new PublicLessonQuestionService.QuestionRequest("能做什么？", null, null))).isEmpty();
        verify(answers, never()).answerForPublicReader(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAnOversizedAnonymousQuestionBeforeCallingTheModel() {
        assertThatThrownBy(() -> service.answer(UUID.randomUUID(), new PublicLessonQuestionService.QuestionRequest("x".repeat(801), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PublicLessonReader.PublicLesson publicLesson(UUID planId, UUID versionId, UUID chunk) {
        UUID unrelatedChunk = UUID.randomUUID();
        IllustratedLesson.LessonStep visual = new IllustratedLesson.LessonStep(
                1, "识别标记", IllustratedLesson.TeachingMove.VISUAL, "看清标记。", List.of(2), List.of(chunk),
                new IllustratedLesson.VisualFocus(2, "标记图例", 100, 100, 200, 200));
        IllustratedLesson.LessonStep example = new IllustratedLesson.LessonStep(
                2, "放置示例", IllustratedLesson.TeachingMove.EXAMPLE, "把一个标记放到起始格。", List.of(2), List.of(chunk));
        IllustratedLesson.LessonStep unrelatedSamePage = new IllustratedLesson.LessonStep(
                3, "另一条规则图例", IllustratedLesson.TeachingMove.VISUAL, "不应返回。", List.of(2), List.of(unrelatedChunk),
                new IllustratedLesson.VisualFocus(2, "无关图例", 500, 100, 200, 200));
        IllustratedLesson.LessonStep unrelatedExample = new IllustratedLesson.LessonStep(
                4, "无关示例", IllustratedLesson.TeachingMove.EXAMPLE, "不应返回。", List.of(2), List.of(unrelatedChunk));
        IllustratedLesson.LessonStep otherPage = new IllustratedLesson.LessonStep(
                5, "另一页图例", IllustratedLesson.TeachingMove.VISUAL, "不应返回。", List.of(3), List.of(chunk),
                new IllustratedLesson.VisualFocus(3, "另一页", 100, 100, 200, 200));
        IllustratedLesson lesson = new IllustratedLesson(
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new IllustratedLesson.LessonSection(
                        1, "setup", List.of("设置"), "设置", true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                        "设置图", List.of(2), List.of(chunk, unrelatedChunk),
                        List.of(visual, example, unrelatedSamePage, unrelatedExample, otherPage))),
                "test", Instant.now());
        return new PublicLessonReader.PublicLesson(planId, versionId, "规则书", "https://publisher.example/rules.pdf", null, lesson);
    }
}
