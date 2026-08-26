package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class PublicLessonQuestionServiceTest {

    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final RuleAnswering answers = mock(RuleAnswering.class);
    private final DocumentPageImages pageImages = mock(DocumentPageImages.class);
    private final PublicLessonQuestionService service = new PublicLessonQuestionService(lessons, answers, pageImages);

    @BeforeEach
    void returnsOrdinaryPageDimensions() {
        when(pageImages.read(any(UUID.class), anySet())).thenAnswer(invocation -> {
            Set<Integer> requestedPages = invocation.getArgument(1);
            return requestedPages.stream()
                    .map(page -> new DocumentPageImages.PageImage(
                            page, "image/jpeg", new byte[] {1}, 1_600, 2_400))
                    .toList();
        });
    }

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
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
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
    void returnsEveryOwnedVisualForTheCitedTeachingStep() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLessonWithMultiVisualStep(
                planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "先认出图标，再对照牌面与完整流程。",
                "三张图都属于同一个有引用的步骤。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("这一步看哪几张图？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId, new PublicLessonQuestionService.QuestionRequest("这一步看哪几张图？", null));

        assertThat(result).hasValueSatisfying(value -> assertThat(value.visualAids())
                .hasSize(3)
                .extracting(aid -> aid.visualFocus().label())
                .containsExactly("标记图例", "牌面示例", "完整流程图"));
        verify(pageImages).read(versionId, Set.of(2));
    }

    @Test
    void doesNotTruncateMoreThanSixVisualsSelectedByTheAnswerEvidence() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLessonWithManyOwnedVisuals(
                planId, versionId, citedChunk, 8)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "按引用顺序查看八张规则图。",
                "这些图都属于回答引用的同一条已验证规则证据。",
                List.of(new RuleAnswering.Citation("流程", 2, 2)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("完整流程需要看哪些图？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest("完整流程需要看哪些图？", null));

        assertThat(result).hasValueSatisfying(value -> assertThat(value.visualAids())
                .hasSize(8)
                .extracting(aid -> aid.visualFocus().label())
                .containsExactly(
                        "规则图 1",
                        "规则图 2",
                        "规则图 3",
                        "规则图 4",
                        "规则图 5",
                        "规则图 6",
                        "规则图 7",
                        "规则图 8"));
    }

    @Test
    void returnsOneCropWhenTheSameOwnedRegionWasPersistedMoreThanOnce() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        PublicLessonReader.PublicLesson source = publicLesson(planId, versionId, citedChunk);
        IllustratedLesson.LessonStep step = source.lesson().sections().getFirst().steps().stream()
                .filter(candidate -> candidate.heading().equals("识别标记"))
                .findFirst()
                .orElseThrow();
        IllustratedLesson.VisualFocus original = step.visualFocus();
        IllustratedLesson.VisualFocus duplicate = new IllustratedLesson.VisualFocus(
                original.pageNumber(),
                "同一区域的重复说明",
                "同一个裁剪区域不应触发第二次公开图片请求",
                original.x(),
                original.y(),
                original.width(),
                original.height(),
                original.sourceKind());
        when(lessons.find(planId)).thenReturn(Optional.of(publicLessonWithStepVisuals(
                planId, versionId, source, step, List.of(original, duplicate))));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "查看标记图例。",
                "重复保存的同一区域只需要展示一次。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("标记图为什么重复？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId, new PublicLessonQuestionService.QuestionRequest("标记图为什么重复？", null));

        assertThat(result).hasValueSatisfying(value -> assertThat(value.visualAids())
                .singleElement()
                .satisfies(aid -> assertThat(aid.visualFocus().label()).isEqualTo(original.label())));
        verify(pageImages).read(versionId, Set.of(2));
    }

    @Test
    void budgetsVisualsFromActualPageAndProjectedCropPixels() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        PublicLessonReader.PublicLesson runawayLesson =
                publicLessonWithRunawayVisualWork(planId, versionId, citedChunk, 80);
        when(lessons.find(planId)).thenReturn(Optional.of(runawayLesson));
        when(pageImages.read(versionId, Set.of(2))).thenReturn(List.of(
                new DocumentPageImages.PageImage(2, "image/jpeg", new byte[] {1}, 4_000, 4_000)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "这些引用图来自同一条已验证规则。",
                "正常的多图选择会保留，只有累计公开裁剪工作达到异常高位才停止。",
                List.of(new RuleAnswering.Citation("流程", 2, 2)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("把异常大的图集都发给我。"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId, new PublicLessonQuestionService.QuestionRequest("把异常大的图集都发给我。", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer()).isSameAs(answer);
            assertThat(value.visualAids()).hasSize(2);
            assertThat(value.visualAids())
                    .extracting(aid -> aid.visualFocus().label())
                    .containsExactly("规则大图 1", "规则大图 2");
            long cropWork = value.visualAids().stream()
                    .mapToLong(aid -> 16_000_000L
                            + projectedPixels(aid.visualFocus(), 4_000, 4_000))
                    .sum();
            assertThat(cropWork).isLessThanOrEqualTo(64_000_000L);
            IllustratedLesson.VisualFocus next = runawayLesson.lesson().sections().getFirst().steps().stream()
                    .filter(step -> step.heading().equals("识别标记"))
                    .findFirst()
                    .orElseThrow()
                    .visualFoci().get(2);
            assertThat(cropWork + 16_000_000L + projectedPixels(next, 4_000, 4_000))
                    .isGreaterThan(64_000_000L);
            assertThat(value.visualAidFailures())
                    .containsExactly(PublicLessonQuestionService.VisualAidFailure.RESOURCE_BUDGET_EXCEEDED);
        });
        verify(pageImages).read(versionId, Set.of(2));
    }

    @Test
    void doesNotReplaceThePixelBudgetWithAFixedVisualCountLimit() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(
                publicLessonWithRunawayVisualWork(planId, versionId, citedChunk, 80)));
        when(pageImages.read(versionId, Set.of(2))).thenReturn(List.of(
                new DocumentPageImages.PageImage(2, "image/jpeg", new byte[] {1}, 100, 100)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED",
                "这些引用图来自同一条已验证规则。",
                "低像素页面的全部有效裁剪都可落在工作预算内。",
                List.of(new RuleAnswering.Citation("流程", 2, 2)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("把低像素图集都发给我。"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId, new PublicLessonQuestionService.QuestionRequest("把低像素图集都发给我。", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.visualAids()).hasSize(80);
            assertThat(value.visualAidFailures()).isEmpty();
        });
    }

    @Test
    void doesNotAdvertiseAVisualWhoseSourcePageExceedsTheCropDecodeBoundary() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        when(pageImages.read(versionId, Set.of(2))).thenReturn(List.of(
                new DocumentPageImages.PageImage(2, "image/jpeg", new byte[] {1}, 7_000, 7_000)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "先放置标记。", "文字规则仍然可以直接回答。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer()).isSameAs(answer);
            assertThat(value.visualAids()).isEmpty();
            assertThat(value.visualAidFailures())
                    .containsExactly(PublicLessonQuestionService.VisualAidFailure.RESOURCE_BUDGET_EXCEEDED);
        });
    }

    @Test
    void keepsTheTextAnswerAndClassifiesUnavailableVisualPageImages() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        when(pageImages.read(versionId, Set.of(2)))
                .thenThrow(new IllegalStateException("object storage unavailable"));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "先放置标记。", "然后执行效果。",
                List.of(new RuleAnswering.Citation("设置", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(planId, new PublicLessonQuestionService.QuestionRequest("标记怎么放？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer()).isSameAs(answer);
            assertThat(value.visualAids()).isEmpty();
            assertThat(value.visualAidFailures())
                    .containsExactly(PublicLessonQuestionService.VisualAidFailure.PAGE_IMAGE_UNAVAILABLE);
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
                        eq(versionId), eq("标记怎么放？"), eq(null), eq(PlayerLocale.ZH_CN),
                        eq(null), eq(Set.of(2, 3))))
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
                        eq(PlayerLocale.ZH_CN),
                        eq(null),
                        eq(Set.of(6))))
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
    void requestsAnEnglishAnswerOnlyWhenTheRequestLanguageIsEnglish() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer answer = new RuleAnswering.Answer(
                "ANSWERED", "Place the marker first.", "Then resolve its effect.",
                List.of(new RuleAnswering.Citation("Setup", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId), eq("Where does the marker go?"), eq(null), eq(PlayerLocale.EN),
                        eq(null), eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), answer, Set.of(citedChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest("Where does the marker go?", null, "en"));

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
                List.of(new RuleAnswering.Citation("声望轨道", 2, 2)), List.of(), "HIGH", null);
        when(answers.answerForPublicReader(
                        eq(versionId),
                        eq("请解释里程碑。"),
                        eq("里程碑什么时候结算？"),
                        eq(PlayerLocale.ZH_CN),
                        eq(RuleAnswering.PublicLearningIntent.DEFINE),
                        eq(Set.of(2, 3))))
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
                RuleAnswering.PublicLearningIntent.DEFINE,
                Set.of(2, 3));
    }

    @Test
    void withholdsAnAnswerWhenAnyCitationEscapesThePublishedLessonPages() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID citedChunk = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.of(publicLesson(planId, versionId, citedChunk)));
        RuleAnswering.Answer escaped = new RuleAnswering.Answer(
                "ANSWERED",
                "这条结论来自另一页。",
                "这条说明不应公开。",
                List.of(new RuleAnswering.Citation("未公开章节", 4, 4)),
                List.of(),
                "HIGH",
                null);
        when(answers.answerForPublicReader(
                        eq(versionId),
                        eq("第四页怎么说？"),
                        eq(null),
                        eq(PlayerLocale.ZH_CN),
                        eq(null),
                        eq(Set.of(2, 3))))
                .thenReturn(new RuleAnswering.AnswerResult(UUID.randomUUID(), escaped, Set.of(citedChunk)));

        var result = service.answer(
                planId,
                new PublicLessonQuestionService.QuestionRequest("第四页怎么说？", null));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.answer().status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(value.answer().shortVerdict()).contains("未公开");
            assertThat(value.answer().citations()).isEmpty();
            assertThat(value.visualAids()).isEmpty();
        });
    }

    @Test
    void doesNotAnswerForAPlanThatIsNotPubliclyReadable() {
        UUID planId = UUID.randomUUID();
        when(lessons.find(planId)).thenReturn(Optional.empty());

        assertThat(service.answer(planId, new PublicLessonQuestionService.QuestionRequest("能做什么？", null))).isEmpty();
        verifyNoInteractions(answers);
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

    private PublicLessonReader.PublicLesson publicLessonWithMultiVisualStep(
            UUID planId, UUID versionId, UUID chunk) {
        PublicLessonReader.PublicLesson source = publicLesson(planId, versionId, chunk);
        IllustratedLesson.LessonSection section = source.lesson().sections().getFirst();
        IllustratedLesson.LessonStep step = section.steps().stream()
                .filter(candidate -> candidate.heading().equals("识别标记"))
                .findFirst()
                .orElseThrow();
        List<IllustratedLesson.VisualFocus> visuals = List.of(
                step.visualFocus(),
                new IllustratedLesson.VisualFocus(
                        2, "牌面示例", "一张卡牌下方排列三个资源图标", 360, 320, 240, 300),
                new IllustratedLesson.VisualFocus(
                        2,
                        "完整流程图",
                        "整页是一张连续流程图",
                        0,
                        0,
                        1_000,
                        1_000,
                        IllustratedLesson.VisualSourceKind.FULL_PAGE));
        return publicLessonWithStepVisuals(planId, versionId, source, step, visuals);
    }

    private PublicLessonReader.PublicLesson publicLessonWithManyOwnedVisuals(
            UUID planId, UUID versionId, UUID chunk, int visualCount) {
        PublicLessonReader.PublicLesson source = publicLesson(planId, versionId, chunk);
        IllustratedLesson.LessonSection section = source.lesson().sections().getFirst();
        IllustratedLesson.LessonStep step = section.steps().stream()
                .filter(candidate -> candidate.heading().equals("识别标记"))
                .findFirst()
                .orElseThrow();
        List<IllustratedLesson.VisualFocus> visuals = java.util.stream.IntStream.rangeClosed(1, visualCount)
                .mapToObj(index -> new IllustratedLesson.VisualFocus(
                        2,
                        "规则图 " + index,
                        "回答引用证据对应的规则图 " + index,
                        index * 10,
                        index * 10,
                        100,
                        100))
                .toList();
        return publicLessonWithStepVisuals(planId, versionId, source, step, visuals);
    }

    private PublicLessonReader.PublicLesson publicLessonWithRunawayVisualWork(
            UUID planId, UUID versionId, UUID chunk, int visualCount) {
        PublicLessonReader.PublicLesson source = publicLesson(planId, versionId, chunk);
        IllustratedLesson.LessonStep step = source.lesson().sections().getFirst().steps().stream()
                .filter(candidate -> candidate.heading().equals("识别标记"))
                .findFirst()
                .orElseThrow();
        List<IllustratedLesson.VisualFocus> visuals = java.util.stream.IntStream.rangeClosed(1, visualCount)
                .mapToObj(index -> new IllustratedLesson.VisualFocus(
                        2,
                        "规则大图 " + index,
                        "回答引用证据对应的高分辨率规则图 " + index,
                        index,
                        0,
                        1_000 - index,
                        1_000))
                .toList();
        return publicLessonWithStepVisuals(planId, versionId, source, step, visuals);
    }

    private PublicLessonReader.PublicLesson publicLessonWithStepVisuals(
            UUID planId,
            UUID versionId,
            PublicLessonReader.PublicLesson source,
            IllustratedLesson.LessonStep step,
            List<IllustratedLesson.VisualFocus> visuals) {
        IllustratedLesson.LessonSection section = source.lesson().sections().getFirst();
        IllustratedLesson.LessonStep multiVisual = new IllustratedLesson.LessonStep(
                step.position(),
                step.heading(),
                step.kind(),
                step.text(),
                step.sourcePages(),
                step.sourceChunkIds(),
                step.ruleFacts(),
                visuals.getFirst(),
                visuals);
        List<IllustratedLesson.LessonStep> steps = section.steps().stream()
                .map(candidate -> candidate == step ? multiVisual : candidate)
                .toList();
        IllustratedLesson.LessonSection multiVisualSection = new IllustratedLesson.LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                section.evidenceStatus(),
                section.visualKind(),
                section.visualCaption(),
                section.visualSourcePages(),
                section.visualSourceChunkIds(),
                steps);
        IllustratedLesson lesson = new IllustratedLesson(
                source.lesson().id(),
                source.lesson().teachingPlanId(),
                source.lesson().status(),
                List.of(multiVisualSection),
                source.lesson().generatorVersion(),
                source.lesson().createdAt());
        return new PublicLessonReader.PublicLesson(
                planId,
                versionId,
                source.rulebookTitle(),
                source.officialSourceUrl(),
                source.gameCover(),
                source.publicGame(),
                lesson);
    }

    private long projectedPixels(
            IllustratedLesson.VisualFocus focus, int pageWidth, int pageHeight) {
        long left = (long) Math.max(0, focus.x() - 35) * pageWidth / 1_000;
        long top = (long) Math.max(0, focus.y() - 35) * pageHeight / 1_000;
        long right = ((long) Math.min(1_000, focus.x() + focus.width() + 35) * pageWidth + 999) / 1_000;
        long bottom = ((long) Math.min(1_000, focus.y() + focus.height() + 35) * pageHeight + 999) / 1_000;
        return (right - left) * (bottom - top);
    }
}
