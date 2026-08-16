package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class PublicLessonQuestionServiceTest {

    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final RuleAnswering answers = mock(RuleAnswering.class);
    private final PublicLessonQuestionService service = new PublicLessonQuestionService(lessons, answers);

    @Test
    void doesNotHoldADatabaseTransactionAcrossTheRuleAnswerModelCall() throws NoSuchMethodException {
        var answer = PublicLessonQuestionService.class.getDeclaredMethod(
                "answer", UUID.class, PublicLessonQuestionService.QuestionRequest.class);

        assertThat(answer.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void returnsVerifiedVisualAidsWithoutAppendingLowerTrustLessonExamples() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "先放置标记。", "然后执行效果。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.visualAids()).hasSize(1);
            assertThat(value.visualAids().getFirst().visualFocus().pageNumber()).isEqualTo(2);
            assertThat(value.visualAids().getFirst().visualFocus().visibleDescription())
                    .isEqualTo("圆形标记位于带箭头的起始格旁");
            assertThat(value.visualAids().getFirst().relatedStep()).isEqualTo("识别标记");
            assertThat(value.examples()).isEmpty();
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
        when(answers.answerForPublicReader(
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(answerChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer().citations()).singleElement()
                    .extracting(RuleAnswering.Citation::pageFrom)
                    .isEqualTo(2);
            assertThat(value.visualAids()).isEmpty();
            assertThat(value.examples()).isEmpty();
        });
    }

    @Test
    void doesNotGuessAVisualFromPageOverlapWhenAnswerAndLessonEvidenceDiffer() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID lessonChunk = UUID.randomUUID();
        UUID answerChunk = UUID.randomUUID();
        IllustratedLesson.LessonStep matchingVisual = new IllustratedLesson.LessonStep(
                1,
                "骑士回合的两个阶段",
                IllustratedLesson.TeachingMove.VISUAL,
                "先拾取英雄方块，再移动并执行遭遇。",
                List.of(6),
                List.of(lessonChunk),
                new IllustratedLesson.VisualFocus(6, "骑士回合阶段列表", 100, 100, 300, 300));
        IllustratedLesson.LessonStep unrelatedVisual = new IllustratedLesson.LessonStep(
                2,
                "哥布林部落的人口",
                IllustratedLesson.TeachingMove.VISUAL,
                "填充哥布林部落。",
                List.of(6),
                List.of(lessonChunk),
                new IllustratedLesson.VisualFocus(6, "哥布林人口", 500, 100, 300, 300));
        IllustratedLesson illustrated = new IllustratedLesson(
                UUID.randomUUID(),
                planId,
                IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new IllustratedLesson.LessonSection(
                        1,
                        "knight-turn",
                        List.of("骑士", "回合"),
                        "骑士回合",
                        true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED,
                        IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                        "骑士回合阶段",
                        List.of(6),
                        List.of(lessonChunk),
                        List.of(matchingVisual, unrelatedVisual))),
                "test",
                Instant.now());
        when(lessons.find(planId)).thenReturn(Optional.of(new PublicLessonReader.PublicLesson(
                planId, versionId, "规则书", "https://publisher.example/rules.pdf", null, null, illustrated)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "骑士回合分为两个阶段。",
                "先拾取方块，再移动与遭遇。",
                List.of(new RuleAnswering.Citation("The Knight", 6, 6)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId),
                        eq("骑士的一个回合分成哪两个阶段？"),
                        eq(null),
                        eq(PlayerLocale.ZH_CN)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(answerChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest("骑士的一个回合分成哪两个阶段？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.visualAids()).isEmpty();
            assertThat(value.examples()).isEmpty();
        });
    }

    @Test
    void requestsAnEnglishAnswerFromTheCurrentTurnEvenWhenTheUiLanguageIsChinese() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "Place the marker first.", "Then resolve its effect.",
                List.of(new RuleAnswering.Citation("Setup", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("Where does the marker go?"), eq(null), eq(PlayerLocale.EN)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest("Where does the marker go?", null, "zh-CN"));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer().shortVerdict()).isEqualTo("Place the marker first.");
            assertThat(value.visualAids()).singleElement().satisfies(aid -> {
                assertThat(aid.relatedStep()).isEqualTo("Cited rulebook illustration");
                assertThat(aid.visualFocus().label()).isEqualTo("Rulebook illustration");
                assertThat(aid.visualFocus().visibleDescription())
                        .isEqualTo("圆形标记位于带箭头的起始格旁");
            });
            assertThat(value.examples()).isEmpty();
            try {
                assertThat(new ObjectMapper().writeValueAsString(value)).doesNotContain("assistantRunId");
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void passesAnAnonymousLearningIntentThroughThePublicAssistantBoundary() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "里程碑是轨道上的指定位置。", "规则书在轨道说明中定义了它。",
                List.of(new RuleAnswering.Citation("声望轨道", 4, 4)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId),
                        eq("请解释里程碑。"),
                        eq("里程碑什么时候结算？"),
                        eq(PlayerLocale.ZH_CN),
                        eq(RuleAnswering.PublicLearningIntent.DEFINE)))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest(
                        "请解释里程碑。",
                        "里程碑什么时候结算？",
                        "zh-CN",
                        RuleAnswering.PublicLearningIntent.DEFINE));

        assertThat(result).hasValueSatisfying(value ->
                assertThat(value.answer().shortVerdict()).contains("里程碑"));
        verify(answers).answerForPublicReader(
                versionId,
                "请解释里程碑。",
                "里程碑什么时候结算？",
                PlayerLocale.ZH_CN,
                RuleAnswering.PublicLearningIntent.DEFINE);
    }

    @Test
    void doesNotAnswerForAPlanThatIsNotPubliclyReadable() {
        UUID planId = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.empty());

        assertThat(service.answer(planId, new PublicLessonQuestionService.QuestionRequest("能做什么？", null))).isEmpty();
        verify(answers, never()).answerForPublicReader(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAnOversizedAnonymousQuestionBeforeCallingTheModel() {
        assertThatThrownBy(() -> service.answer(UUID.randomUUID(), new PublicLessonQuestionService.QuestionRequest("x".repeat(801), null)))
                .isInstanceOf(IllegalArgumentException.class);
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
