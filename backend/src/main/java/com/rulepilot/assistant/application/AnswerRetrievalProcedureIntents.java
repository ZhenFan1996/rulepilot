package com.rulepilot.assistant.application;

import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds product-level and question-derived procedure intents from a player's normalized question.
 *
 * <p>Only end-game resolution is a fixed product ontology. Every other conditional lookup preserves the player's
 * wording and adds neutral procedure facets; it never maps that wording to a mechanic remembered from another
 * rulebook.</p>
 */
final class AnswerRetrievalProcedureIntents {

    private AnswerRetrievalProcedureIntents() {}

    static List<RetrievalIntent> plan(String question) {
        List<RetrievalIntent> intents = new ArrayList<>();
        String endgameResolutionQuery = endgameResolutionQuery(question);
        if (endgameResolutionQuery != null) {
            intents.add(intent(endgameResolutionQuery, RetrievalPurpose.ENDGAME_RESOLUTION));
        }
        String conditionalQuery = AnswerConditionEvidencePolicy.retrievalQuery(question);
        if (conditionalQuery != null && endgameResolutionQuery == null) {
            intents.add(intent(conditionalQuery, RetrievalPurpose.CONDITION_PROCEDURE));
        }
        return List.copyOf(intents);
    }

    static String endgameResolutionTerms(String question) {
        if (!AnswerEvidencePolicy.isEndgameResolutionQuestion(question)) return null;
        String terms = "end game end condition end of round final scoring winner tie resolution "
                + "finish the round equal turns remaining players continue endgame trigger "
                + "游戏结束 结束条件 轮末 最终计分 胜者 平局 结算 完成本轮 相同回合数 其他玩家继续 触发";
        return AnswerRetrievalPlanner.containsAny(question, "tie", "tied", "平局", "同分")
                ? terms + " tiebreak tie breaker most gold coins 平局 同分 决胜 金币"
                : terms;
    }

    private static String endgameResolutionQuery(String question) {
        String terms = endgameResolutionTerms(question);
        return terms == null ? null : AnswerRetrievalPlanner.bounded(question + " " + terms);
    }

    private static RetrievalIntent intent(String query, RetrievalPurpose purpose) {
        return new RetrievalIntent(query, java.util.Set.of(), null, false, purpose);
    }
}
