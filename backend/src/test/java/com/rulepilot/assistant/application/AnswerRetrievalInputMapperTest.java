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
            assertThat(AnswerRetrievalInputMapper.question(question).currentQuestion())
                    .isEqualTo("Original question");
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
    void mapsValidatedOwnershipObjectsAndPageHintsWithoutTreatingThemAsClaims() {
        AnswerQuestionPlan source = new AnswerQuestionPlan(
                List.of(
                        new AnswerQuestionPlan.Subquestion(
                                "Does the cobalt spindle return?",
                                Set.of(EvidenceNeed.DIRECT_RULE),
                                AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION),
                        new AnswerQuestionPlan.Subquestion(
                                "When does the amber lattice release?",
                                Set.of(EvidenceNeed.PRIOR_TURN),
                                AnswerQuestionPlan.QuestionOwner.BOUND_REFERENCE)),
                true,
                AnswerAid.NONE,
                ReferenceBinding.PREVIOUS_QUESTION,
                "When does the amber lattice release?",
                List.of("cobalt spindle"),
                List.of(new AnswerQuestionPlan.PageHint("page 47", 47)));

        AnswerRetrievalPlan mapped = AnswerRetrievalInputMapper.plan(source);

        assertThat(mapped.referenceBinding())
                .isEqualTo(AnswerRetrievalPlan.ReferenceBinding.PREVIOUS_QUESTION);
        assertThat(mapped.boundReferenceQuestion()).isEqualTo("When does the amber lattice release?");
        assertThat(mapped.currentRuleObjectSpans()).containsExactly("cobalt spindle");
        assertThat(mapped.pageHints()).containsExactly(new AnswerRetrievalPlan.PageHint("page 47", 47));
        assertThat(mapped.subquestions())
                .extracting(AnswerRetrievalPlan.Subquestion::owner)
                .containsExactly(
                        AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION,
                        AnswerRetrievalPlan.QuestionOwner.BOUND_REFERENCE);
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
