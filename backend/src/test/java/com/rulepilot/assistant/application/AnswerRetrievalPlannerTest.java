package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRetrievalPlannerTest {

    @Test
    void followsTheValidatedAgentSubquestionsInsteadOfRegexPunctuationSplitting() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "行动后先补牌吗，还有例外情况吗？",
                "行动后先补牌吗，还有例外情况吗？",
                QuestionType.RULE_QUERY,
                List.of("补牌", "例外"),
                Set.of());
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(
                        new AnswerQuestionPlan.Subquestion(
                                "行动后先补牌吗", Set.of(EvidenceNeed.SEQUENCE)),
                        new AnswerQuestionPlan.Subquestion(
                                "例外情况吗", Set.of(EvidenceNeed.EXCEPTION))),
                true);

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId), List.of(), plan);

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anySatisfy(query -> assertThat(query).contains("行动后先补牌吗", "order timing procedure"))
                .anySatisfy(query -> assertThat(query).contains("例外情况吗", "exception restriction unless"));
        assertThat(intents).filteredOn(AnswerRetrievalPlanner.RetrievalIntent::directQuestion).hasSize(2);
    }

    @Test
    void addsPermissionAndProhibitionFacetsForACanQuestion() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Can unused influence be saved?",
                "can unused influence be saved?",
                QuestionType.RULE_QUERY,
                List.of("influence", "saved"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(question, new QuestionContext(versionId));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anySatisfy(query -> assertThat(query).contains("permission", "prohibition", "cannot", "exception"));
    }

    @Test
    void addsDirectClauseAndPageFacetsForASourceFocusedFollowUp() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Where does the rulebook say when the beacon triggers?",
                "where does the rulebook say when the beacon triggers?",
                QuestionType.RULE_QUERY,
                List.of("beacon", "triggers"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId, null, LearningIntent.SOURCE, PlayerLocale.EN));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anySatisfy(query -> assertThat(query).contains("exact rule clause", "direct source", "page"));
    }

    @Test
    void usesANeutralConditionIntentForAnExhaustedSourceQuestion() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                UUID.randomUUID(),
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                QuestionType.RULE_QUERY,
                List.of("抽骰区", "骰子"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(UUID.randomUUID()));

        assertThat(intents)
                .filteredOn(intent -> intent.purpose() == AnswerRetrievalPlanner.RetrievalPurpose.CONDITION_PROCEDURE)
                .singleElement()
                .satisfies(intent -> {
                assertThat(intent.query())
                        .contains("抽骰区", "condition", "procedure", "继续")
                        .doesNotContain("source area", "recycle", "reshuffle");
                });
    }

    @Test
    void usesANeutralConditionIntentForAnEndOfTurnQuestion() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                UUID.randomUUID(),
                "我结束自己的回合后，事件牌要怎样处理？",
                "我结束自己的回合后，事件牌要怎样处理？",
                QuestionType.RULE_QUERY,
                List.of("回合", "事件牌"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(UUID.randomUUID()));

        assertThat(intents)
                .filteredOn(intent -> intent.purpose() == AnswerRetrievalPlanner.RetrievalPurpose.CONDITION_PROCEDURE)
                .singleElement()
                .satisfies(intent -> {
                assertThat(intent.query())
                        .contains("回合后", "事件牌", "condition", "procedure")
                        .doesNotContain("draw", "resolve", "结算");
                });
    }

    @Test
    void usesCrossLanguageQueriesBeforeASingleSurfaceLanguageQuestion() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                UUID.randomUUID(),
                "万能牌能匹配行动花色吗？",
                "万能牌能匹配行动花色吗？",
                QuestionType.RULE_QUERY,
                List.of("万能牌", "花色"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(UUID.randomUUID()),
                List.of("wild card matching action suit", "wild card matching action suit", "ignored"));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .startsWith("wild card matching action suit", "ignored", "万能牌能匹配行动花色吗？");
        assertThat(intents.getFirst().sectionTypes()).isEmpty();
        assertThat(intents.getFirst().currentSectionType()).isNull();
    }

    @Test
    void preservesEveryDirectClauseOfACompoundQuestionBeforeSpendingRewriteBudget() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "有人到30名声后立刻结束吗？承诺货物何时计分？平局怎么处理？",
                "有人到30名声后立刻结束吗？承诺货物何时计分？平局怎么处理？",
                QuestionType.RULE_QUERY,
                List.of("名声", "承诺货物", "平局"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId),
                List.of("end game pledged cargo scoring tie breaker", "fame end gold tie"));

        assertThat(intents.getFirst().query()).contains(
                "有人到30名声后立刻结束吗？承诺货物何时计分？平局怎么处理？", "end condition", "final scoring");
        assertThat(intents.getFirst().purpose())
                .isEqualTo(AnswerRetrievalPlanner.RetrievalPurpose.ENDGAME_RESOLUTION);
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .contains("有人到30名声后立刻结束吗", "承诺货物何时计分", "平局怎么处理")
                .anyMatch(query -> query.contains("end condition") && query.contains("final scoring"));
    }

    @Test
    void decomposesCompleteListRequestsSeparatedByCommasWithoutGameSpecificTerms() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "List all four blue, four red, and four green abilities, including each cost and timing.",
                "list all four blue, four red, and four green abilities, including each cost and timing.",
                QuestionType.RULE_QUERY,
                List.of("abilities", "cost", "timing"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(question, new QuestionContext(versionId));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .contains("list all four blue", "four red", "four green abilities")
                .anyMatch(query -> query.contains("all four") && query.contains("timing"));
    }

    @Test
    void keepsAnOrdinaryConjunctionAsOneIntentWhenNoCompleteListWasRequested() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Can I pay one coin and move one space?",
                "can i pay one coin and move one space?",
                QuestionType.SITUATION_QUERY,
                List.of("coin", "move"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(question, new QuestionContext(versionId));

        assertThat(intents).hasSize(2);
        assertThat(intents.getFirst().query()).isEqualTo("can i pay one coin and move one space?");
    }

    @Test
    void derivesActionScopeFromTheQuestionInsteadOfCallerContext() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Can I play this card now?",
                "can i play this card now?",
                QuestionType.SITUATION_QUERY,
                List.of("play", "card"),
                Set.of());
        var context = new QuestionContext(versionId);

        var intents = AnswerRetrievalPlanner.plan(question, context);

        assertThat(intents).hasSize(2);
        assertThat(intents.getFirst().query()).isEqualTo(question.normalizedQuestion());
        assertThat(intents.getFirst().sectionTypes()).isEmpty();
        assertThat(intents.get(1).query())
                .contains("legal action")
                .doesNotContain("ACTION PHASE", "4 players", "4人");
        assertThat(intents.get(1).sectionTypes()).contains("ACTIONS");
        assertThat(intents.get(1).currentSectionType()).isEqualTo("ACTIONS");
    }

    @Test
    void scopesADirectSetupQuestionFromTheQuestionText() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "开局准备时，玩家需要先完成哪些关键步骤？",
                "开局准备时，玩家需要先完成哪些关键步骤？",
                QuestionType.RULE_QUERY,
                List.of("开局", "准备", "步骤"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId),
                List.of("Root setup steps in order"));

        assertThat(intents.getFirst().sectionTypes()).containsExactly("SETUP");
        assertThat(intents.getFirst().currentSectionType()).isEqualTo("SETUP");
    }

    @Test
    void infersScoringScopeFromTheQuestionText() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "How are points scored?",
                "how are points scored?",
                QuestionType.RULE_QUERY,
                List.of("points", "scored"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents.get(1).sectionTypes()).containsExactly("SCORING");
        assertThat(intents.get(1).currentSectionType()).isEqualTo("SCORING");
    }

    @Test
    void decomposesCompoundTableQuestionAndKeepsEndingScopeForTies() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "When does the game end? How are ties resolved?",
                "when does the game end? how are ties resolved?",
                QuestionType.RULE_QUERY,
                List.of("game", "end", "ties"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents).hasSize(4);
        assertThat(intents.getFirst().query()).contains("end condition", "final scoring", "tie");
        assertThat(intents.getFirst().query()).contains("tie breaker", "most gold", "金币");
        assertThat(intents.getFirst().purpose())
                .isEqualTo(AnswerRetrievalPlanner.RetrievalPurpose.ENDGAME_RESOLUTION);
        assertThat(intents.get(1).query()).isEqualTo("when does the game end");
        assertThat(intents.get(2).query()).isEqualTo("how are ties resolved");
        assertThat(intents.get(3).sectionTypes())
                .contains("TIE_BREAKERS", "END_CONDITIONS", "SCORING");
    }

    @Test
    void preservesDocumentTermsWithoutInjectingAnotherGamesVocabulary() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "我的回合能先激活潮汐门，再移动船只吗？",
                "我的回合能先激活潮汐门，再移动船只吗？",
                QuestionType.SITUATION_QUERY,
                List.of("潮汐门", "船只"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .allMatch(query -> !query.contains("moon") && !query.contains("probe") && !query.contains("publicity"))
                .anyMatch(query -> query.contains("潮汐门"))
                .anyMatch(query -> query.contains("船只"));
    }

    @Test
    void usesOnlyQuestionAndUnderstoodTermsForAnUnknownGameMechanic() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "穿过潮汐门需要满足什么条件，费用怎么算？",
                "穿过潮汐门需要满足什么条件，费用怎么算？",
                QuestionType.SITUATION_QUERY,
                List.of("潮汐门", "条件", "费用"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anyMatch(query -> query.contains("潮汐门"))
                .allMatch(query -> !query.contains("moon") && !query.contains("planet") && !query.contains("probe"));
        assertThat(intents.getLast().sectionTypes()).isEmpty();
    }

    @Test
    void carriesThePreviousQuestionIntoFollowUpRetrieval() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "那还能再做一次吗？",
                "那还能再做一次吗？",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId, "完成主要行动后还能执行自由行动吗？", null, PlayerLocale.ZH_CN));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anyMatch(query -> query.contains("完成主要行动后还能执行自由行动吗"))
                .anyMatch(query -> query.contains("那还能再做一次吗"));
        assertThat(intents.getLast().query())
                .contains("完成主要行动后还能执行自由行动吗", "那还能再做一次吗");
        assertThat(intents.getLast().currentSectionType()).isEqualTo("ACTIONS");
    }

    @Test
    void searchesForExampleDetailsWithoutInventingAChapterScope() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请走一个具体例子。",
                "请走一个具体例子。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId, null, LearningIntent.EXAMPLE, PlayerLocale.ZH_CN));

        assertThat(intents.getFirst().sectionTypes()).isEmpty();
        assertThat(intents.getFirst().currentSectionType()).isNull();
        assertThat(intents.getLast().query()).contains("worked example", "cost", "result");
    }

    @Test
    void doesNotInventAChapterScopeForASimplificationRequest() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请讲简单一点。",
                "请讲简单一点。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(versionId, null, LearningIntent.SIMPLIFY, PlayerLocale.ZH_CN));

        assertThat(intents).allSatisfy(intent -> assertThat(intent.sectionTypes()).isEmpty());
    }

    @Test
    void rechecksTheOriginalQuestionWithDirectConditionsTimingAndExceptions() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请重新检索并核对上一条回答。",
                "请重新检索并核对上一条回答。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(
                        versionId,
                        "完成这个行动后是否立即计分？",
                        LearningIntent.VERIFY,
                        PlayerLocale.ZH_CN));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anyMatch(query -> query.contains("完成这个行动后是否立即计分"));
        assertThat(intents.getLast().query())
                .contains("direct rule", "condition", "timing", "exception", "contradiction");
    }

    @Test
    void searchesTheCurrentRulebookForAReferencedTermDefinition() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请解释上一条问题里的关键术语。",
                "请解释上一条问题里的关键术语。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(
                        versionId,
                        "声望轨道上的里程碑是什么意思？",
                        LearningIntent.DEFINE,
                        PlayerLocale.ZH_CN));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anyMatch(query -> query.contains("声望轨道上的里程碑是什么意思"));
        assertThat(intents.getLast().query())
                .contains("definition", "glossary", "terminology", "定义", "术语");
    }

    @Test
    void preservesAnActorTransitionQuestionWithoutInventingASuccessor() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "我出完所有手牌后，下一墩由谁领出？",
                "我出完所有手牌后，下一墩由谁领出？",
                QuestionType.SITUATION_QUERY,
                List.of("手牌", "下一墩"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents)
                .filteredOn(intent -> intent.purpose() == AnswerRetrievalPlanner.RetrievalPurpose.CONDITION_PROCEDURE)
                .singleElement()
                .satisfies(intent -> {
                assertThat(intent.query())
                        .contains("condition", "procedure", "我出完所有手牌", "例外")
                        .doesNotContain("next player to the left");
                    assertThat(intent.sectionTypes()).isEmpty();
                    assertThat(intent.currentSectionType()).isNull();
                });
        assertThat(intents.getLast().sectionTypes()).contains("ROUND_STRUCTURE", "PHASES");
        assertThat(intents.getLast().currentSectionType()).isEqualTo("PHASES");
    }

    @Test
    void preservesARoundBoundaryQuestionWithoutInventingAResetOperation() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "一轮结束时，颜料堆前的学生会全部回到玩家版图吗？",
                "一轮结束时，颜料堆前的学生会全部回到玩家版图吗？",
                QuestionType.RULE_QUERY,
                List.of("一轮", "学生"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents)
                .filteredOn(intent -> intent.purpose() == AnswerRetrievalPlanner.RetrievalPurpose.CONDITION_PROCEDURE)
                .singleElement()
                .satisfies(intent -> {
                assertThat(intent.query())
                        .contains("学生", "condition", "procedure")
                        .doesNotContain("recover", "reset");
                assertThat(intent.sectionTypes()).isEmpty();
                });
        assertThat(intents.getLast().sectionTypes()).contains("ROUND_STRUCTURE");
    }

    @Test
    void preservesADeferredUseQuestionWithoutInventingAWorkedExample() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "我能把剩下的骰子留到下一次轮到我时再用吗？",
                "我能把剩下的骰子留到下一次轮到我时再用吗？",
                QuestionType.RULE_QUERY,
                List.of("骰子", "下一次"),
                Set.of());

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId));

        assertThat(intents)
                .filteredOn(intent -> intent.purpose() == AnswerRetrievalPlanner.RetrievalPurpose.CONDITION_PROCEDURE)
                .singleElement()
                .satisfies(intent -> {
                assertThat(intent.query())
                        .contains("剩下的骰子", "condition", "procedure")
                        .doesNotContain("worked example", "remaining pieces", "示例回合");
                });
        assertThat(intents.getLast().sectionTypes()).contains("ROUND_STRUCTURE");
    }
}
