package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerPlayerFacingRepairPolicyTest {

    private final UUID citationId = UUID.randomUUID();

    @Test
    void requestsOneBoundedRepairForInternalProtocolLeakage() {
        ModelDraft draft = draft(
                "Allowed according to evidence E1.",
                "Internal chunkId " + UUID.randomUUID() + " must not be shown.");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft))
                .containsExactly(
                        "PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                                + "and other internal references. Teach the same cited rule directly; preserve citationIds.");
    }

    @Test
    void leavesSemanticClaimsForTheEvidenceBoundCritic() {
        ModelDraft draft = draft(
                "The source does not define the term.",
                "This strategy is recommended only if the cited rule actually says so.");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft)).isEmpty();
    }

    @Test
    void requestsTargetedRepairInsteadOfPublishingAWholeRulebookAbsenceClaim() {
        ModelDraft draft = draft(
                "达到30分即可获胜。",
                "规则书没有提供保证获胜的最佳开局。");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), draft))
                .singleElement()
                .asString()
                .contains("SOURCE_SCOPE", "Preserve all other supported prose", "honest local boundary");
        assertThat(AnswerPlayerFacingRepairPolicy.planFor(adviceRequest(), draft).editableFields())
                .containsExactly(PlayerFacingField.EXPLANATION);
    }

    @Test
    void locksEveryUnaffectedCoreFieldDuringAFieldLocalRepair() {
        ModelDraft draft = draft(
                "达到30分即可获胜。",
                "规则书没有提供保证获胜的最佳开局。");

        AnswerPlayerFacingRepairPolicy.RepairPlan plan =
                AnswerPlayerFacingRepairPolicy.planFor(adviceRequest(), draft);

        assertThat(plan.required()).isTrue();
        assertThat(plan.editableFields()).containsExactly(PlayerFacingField.EXPLANATION);
        assertThat(plan.editableFields())
                .doesNotContain(PlayerFacingField.SHORT_VERDICT, PlayerFacingField.EXCEPTIONS);
    }

    @Test
    void treatsUsefulClarificationShapeAsPromptGuidanceRatherThanAPublicationFailure() {
        ModelDraft incomplete = draft(
                "达到30分即可获胜。",
                "当前提供的片段无法从现有内容确认最佳开局。");
        ModelDraft complete = draft(
                "达到30分即可获胜。",
                "当前提供的片段无法从现有内容确认最佳开局；你想先查哪个阵营的开局阶段？");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), incomplete)).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), complete)).isEmpty();
    }

    @Test
    void doesNotRejectAnHonestLocalGapOnlyForItsPlacementOrQuestionFormatting() {
        ModelDraft multipleQuestions = draft(
                "达到30分即可获胜。",
                "当前摘录无法确认开局建议。你用哪个阵营？你有具体页面吗？");
        ModelDraft gapInVerdict = draft(
                "规则摘录没有稳赢开局，请告诉我你用哪个阵营。",
                "达到30分即可获胜。你用哪个阵营？");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), multipleQuestions)).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), gapInVerdict)).isEmpty();
    }

    @Test
    void detectorNeverMutatesNaturalPlayerProse() {
        ModelDraft complete = draft(
                "达到30分即可获胜。",
                "当前提供的摘录无法确认一套保证获胜的开局；你想先查哪个阵营的开局阶段？");

        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(adviceRequest(), complete)).isEmpty();
        assertThat(complete.shortVerdict()).isEqualTo("达到30分即可获胜。");
        assertThat(complete.explanation())
                .isEqualTo("当前提供的摘录无法确认一套保证获胜的开局；你想先查哪个阵营的开局阶段？");
        assertThat(complete.citationIds()).containsExactly(citationId);
    }

    @Test
    void requestsCitationOnlyRepairForAnUnattributedVerbatimSourceQuotation() {
        UUID quotedSourceId = UUID.randomUUID();
        String quotedClause = "A player wins immediately after reaching thirty points.";
        ModelRequest request = new ModelRequest(
                "How does a player win?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(citationId, "RULE", "Overview", "Turns proceed clockwise.", 1, 1),
                        new EvidenceInput(quotedSourceId, "RULE", "Victory", quotedClause, 2, 2)));
        ModelDraft draft = new ModelDraft(
                "Reach thirty points.",
                "The rule states: \u201c" + quotedClause + "\u201d",
                List.of(citationId),
                List.of(),
                "HIGH");

        AnswerPlayerFacingRepairPolicy.RepairPlan plan =
                AnswerPlayerFacingRepairPolicy.planFor(request, draft);

        assertThat(plan.editableFields()).containsExactly(PlayerFacingField.CITATION_IDS);
        assertThat(plan.feedback()).singleElement().asString()
                .contains("CITATION_OWNERSHIP", quotedSourceId.toString(), "Preserve all prose");
        assertThat(draft.explanation()).isEqualTo("The rule states: \u201c" + quotedClause + "\u201d");
    }

    @Test
    void doesNotGuessCitationCoverageFromAnUnquotedParaphraseOrAShortLabel() {
        UUID otherSourceId = UUID.randomUUID();
        ModelRequest request = new ModelRequest(
                "How does a player win?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(citationId, "RULE", "Overview", "Turns proceed clockwise.", 1, 1),
                        new EvidenceInput(
                                otherSourceId,
                                "RULE",
                                "Victory",
                                "A player wins immediately after reaching thirty points.",
                                2,
                                2)));
        ModelDraft paraphrase = new ModelDraft(
                "Reach thirty points.",
                "Reaching thirty points ends the game. The printed label is \u201cVictory\u201d.",
                List.of(citationId),
                List.of(),
                "HIGH");

        assertThat(AnswerCitationCoveragePolicy.missingQuotedSourceIds(request, paraphrase)).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.planFor(request, paraphrase).required()).isFalse();
    }

    @Test
    void doesNothingWithoutValidInputsOrLeakage() {
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(null, draft("Plain.", "Plain."))).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), null)).isEmpty();
        assertThat(AnswerPlayerFacingRepairPolicy.feedbackFor(request(), draft("Plain.", "Plain."))).isEmpty();
    }

    private ModelDraft draft(String verdict, String explanation) {
        return new ModelDraft(verdict, explanation, List.of(citationId), List.of(), "HIGH");
    }

    private ModelRequest request() {
        return new ModelRequest(
                "What is the rule?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "Evidence.", 1, 1)));
    }

    private ModelRequest adviceRequest() {
        return new ModelRequest(
                "这款游戏怎么赢？有没有保证获胜的最佳开局？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(citationId, "RULE", "Rule", "达到30分即可获胜。", 1, 1)),
                Set.of(EvidenceNeed.DIRECT_RULE, EvidenceNeed.ADVICE),
                AnswerAid.NONE,
                List.of(
                        new PlannedSubquestion("这款游戏怎么赢？", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new PlannedSubquestion("有没有保证获胜的最佳开局？", Set.of(EvidenceNeed.ADVICE))));
    }
}
