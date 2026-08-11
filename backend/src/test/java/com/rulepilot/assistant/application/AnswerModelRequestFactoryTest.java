package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerModelRequestFactoryTest {

    private final AnswerModelRequestFactory factory = new AnswerModelRequestFactory();

    @Test
    void preservesQuestionContextAndMapsOnlySourceBackedEvidenceFields() {
        UUID documentVersionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                        documentVersionId,
                        "原始问题",
                        "标准化问题",
                        QuestionType.LESSON_STEP_FOLLOW_UP,
                        List.of("回合"),
                        Set.of());
        var request = factory.create(
                question,
                new QuestionContext(
                        documentVersionId,
                        " 前一个问题 ",
                        LearningIntent.EXAMPLE,
                        PlayerLocale.EN),
                List.of(new HybridEvidenceHit(
                        new RuleEvidenceHit(
                                chunkId,
                                documentVersionId,
                                "RULE",
                                "执行行动",
                                "依次执行一个行动。",
                                7,
                                8,
                                0.91),
                        0.91,
                        1,
                        null,
                        true)),
                new AnswerQuestionPlan(
                        List.of(new AnswerQuestionPlan.Subquestion(
                                question.normalizedQuestion(), Set.of(EvidenceNeed.ADVICE))),
                        true));

        assertThat(request.question()).isEqualTo("标准化问题");
        assertThat(request.questionType()).isEqualTo(QuestionType.LESSON_STEP_FOLLOW_UP);
        assertThat(request.context()).satisfies(context -> {
            assertThat(context.previousQuestion()).isEqualTo("前一个问题");
            assertThat(context.learningIntent()).isEqualTo(LearningIntent.EXAMPLE);
            assertThat(context.outputLanguage()).isEqualTo(PlayerLocale.EN);
        });
        assertThat(request.evidence()).containsExactly(
                new com.rulepilot.assistant.RuleAnswerModel.EvidenceInput(
                        chunkId, "RULE", "执行行动", "依次执行一个行动。", 7, 8));
        assertThat(request.evidenceNeeds()).containsExactly(EvidenceNeed.ADVICE);
    }
}
