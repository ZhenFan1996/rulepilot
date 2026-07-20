package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

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
    void buildsPrimaryAndContextualSupplementaryIntents() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Can I play this card now?",
                "can i play this card now?",
                QuestionType.SITUATION_QUERY,
                List.of("play", "card"),
                Set.of(),
                "ACTIONS");
        var context = new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of());

        var intents = AnswerRetrievalPlanner.plan(question, context);

        assertThat(intents).hasSize(2);
        assertThat(intents.getFirst().query()).isEqualTo(question.normalizedQuestion());
        assertThat(intents.getFirst().sectionTypes()).isEmpty();
        assertThat(intents.get(1).query())
                .contains("legal action", "ACTION PHASE", "4 players", "4人");
        assertThat(intents.get(1).sectionTypes()).contains("ACTIONS");
        assertThat(intents.get(1).currentSectionType()).isEqualTo("ACTIONS");
    }

    @Test
    void infersScoringScopeWithoutTrustingUnknownSectionNames() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "How are points scored?",
                "how are points scored?",
                QuestionType.RULE_QUERY,
                List.of("points", "scored"),
                Set.of(),
                null);

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId, "user supplied section", null, null, Set.of()));

        assertThat(intents.get(1).sectionTypes()).containsExactly("SCORING");
        assertThat(intents.get(1).currentSectionType()).isNull();
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
                Set.of(),
                null);

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(intents).hasSize(3);
        assertThat(intents.get(0).query()).isEqualTo("when does the game end");
        assertThat(intents.get(1).query()).isEqualTo("how are ties resolved");
        assertThat(intents.get(2).sectionTypes())
                .contains("TIE_BREAKERS", "END_CONDITIONS", "SCORING");
    }

    @Test
    void expandsChineseTableTermsForEnglishRulebooks() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "我的回合能先买牌，再打牌，再把数据放进电脑吗？",
                "我的回合能先买牌，再打牌，再把数据放进电脑吗？",
                QuestionType.SITUATION_QUERY,
                List.of("行动"),
                Set.of(),
                "ACTIONS");

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of()));

        assertThat(intents.get(0).query()).contains("buying a card");
        assertThat(intents.get(1).query()).contains("play a card");
        assertThat(intents.get(2).query()).contains("place data", "computer");
    }

    @Test
    void anchorsMoonLandingQuestionsToTheSpecificEnglishTechnologyRule() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "登陆月球需要支付多少费用？",
                "登陆月球需要支付多少费用？",
                QuestionType.SITUATION_QUERY,
                List.of("月球", "费用"),
                Set.of(),
                "ACTIONS");

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId, null, "主要行动", 4, Set.of()));

        assertThat(intents.getFirst().query())
                .contains("probe tech", "moon", "same as landing on planet", "prerequisite");
        assertThat(intents.get(1).query())
                .contains("moon", "tech", "same cost as landing on the planet", "prerequisite");
        assertThat(intents.getLast().sectionTypes()).contains("ACTIONS");
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
                Set.of(),
                "ACTIONS");

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(
                        versionId, "ACTIONS", null, 4, Set.of(),
                        "完成主要行动后还能执行自由行动吗？"));

        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anyMatch(query -> query.contains("完成主要行动后还能执行自由行动吗"))
                .anyMatch(query -> query.contains("那还能再做一次吗"));
        assertThat(intents.getLast().query())
                .contains("完成主要行动后还能执行自由行动吗", "那还能再做一次吗");
        assertThat(intents.getLast().currentSectionType()).isEqualTo("ACTIONS");
    }

    @Test
    void scopesAnExampleRequestAndSearchesForExecutableDetails() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请走一个具体例子。",
                "请走一个具体例子。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of(),
                "ACTIONS");

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(
                        versionId, "ACTIONS", null, 4, Set.of(), null, LearningIntent.EXAMPLE));

        assertThat(intents.getFirst().sectionTypes()).containsExactly("ACTIONS");
        assertThat(intents.getFirst().currentSectionType()).isEqualTo("ACTIONS");
        assertThat(intents.getLast().query()).contains("worked example", "cost", "result");
    }

    @Test
    void mapsAgentGeneratedLessonTopicsBackToAnAllowedRuleScope() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "请讲简单一点。",
                "请讲简单一点。",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of(),
                Set.of(),
                "turn-structure 回合结构与自由行动 core_loop first_round");

        var intents = AnswerRetrievalPlanner.plan(
                question,
                new QuestionContext(
                        versionId,
                        "turn-structure 回合结构与自由行动 core_loop first_round",
                        null,
                        4,
                        Set.of(),
                        null,
                        LearningIntent.SIMPLIFY));

        assertThat(intents).allSatisfy(intent ->
                assertThat(intent.sectionTypes()).containsExactly("ROUND_STRUCTURE"));
    }
}
