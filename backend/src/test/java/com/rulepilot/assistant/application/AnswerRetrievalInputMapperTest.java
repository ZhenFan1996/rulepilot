package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerRetrievalPlan;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRetrievalInputMapperTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void mapsEveryQuestionAndLearningIntentWithoutStringlyTypedFallbacks() {
        for (QuestionType type : QuestionType.values()) {
            UnderstoodQuestion question = new UnderstoodQuestion(
                    documentVersionId,
                    "Original question",
                    "Normalized question",
                    type,
                    List.of("term"),
                    Set.of());

            assertThat(AnswerRetrievalInputMapper.question(question).type().name())
                    .isEqualTo(type.name());
        }
        for (LearningIntent intent : LearningIntent.values()) {
            QuestionContext context = new QuestionContext(
                    documentVersionId,
                    "Previous question",
                    intent,
                    PlayerLocale.EN);

            var mapped = AnswerRetrievalInputMapper.context(context);
            assertThat(mapped.documentVersionId()).isEqualTo(documentVersionId);
            assertThat(mapped.previousQuestion()).isEqualTo("Previous question");
            assertThat(mapped.learningIntent().name()).isEqualTo(intent.name());
        }
    }

    @Test
    void mapsEveryEvidenceNeedAndCalculationCoverageWithoutExposingModelTypes() {
        AnswerQuestionPlan source = new AnswerQuestionPlan(
                List.of(
                        subquestion("direct", EvidenceNeed.DIRECT_RULE, EvidenceNeed.CONDITION, EvidenceNeed.SEQUENCE),
                        subquestion("exceptions", EvidenceNeed.EXCEPTION, EvidenceNeed.DEFINITION, EvidenceNeed.RELATIONSHIP),
                        subquestion("visual", EvidenceNeed.VISUAL_REFERENCE, EvidenceNeed.COMPLETE_LIST, EvidenceNeed.ADVICE),
                        subquestion("prior", EvidenceNeed.PRIOR_TURN)),
                true,
                AnswerAid.CALCULATION,
                ReferenceBinding.CURRENT_QUESTION);

        AnswerRetrievalPlan mapped = AnswerRetrievalInputMapper.plan(source);

        assertThat(mapped.evidenceNeeds())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(Arrays.stream(EvidenceNeed.values()).map(Enum::name).toArray(String[]::new));
        assertThat(mapped.calculationCoverageRequired()).isTrue();
        assertThat(mapped.expandedCoverageRequired()).isTrue();
    }

    private AnswerQuestionPlan.Subquestion subquestion(String text, EvidenceNeed... needs) {
        return new AnswerQuestionPlan.Subquestion(text, Set.of(needs));
    }
}
