package com.rulepilot.assistant.application;

import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalIntent;
import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds generic rule-procedure retrieval intents from a player's normalized question.
 *
 * <p>These are intentionally structural terms—such as a source becoming empty or one player's turn ending—rather
 * than game names, component names, or remembered corpus examples. The caller still owns the final evidence budget
 * and can combine these focused intents with the direct question and lesson context.</p>
 */
final class AnswerRetrievalProcedureIntents {

    private AnswerRetrievalProcedureIntents() {}

    static List<RetrievalIntent> plan(String question) {
        List<RetrievalIntent> intents = new ArrayList<>();
        String endgameResolutionQuery = endgameResolutionQuery(question);
        if (endgameResolutionQuery != null) {
            intents.add(intent(endgameResolutionQuery, RetrievalPurpose.ENDGAME_RESOLUTION));
        }
        String exhaustedSourceQuery = exhaustedSourceQuery(question);
        if (exhaustedSourceQuery != null) {
            intents.add(intent(exhaustedSourceQuery, RetrievalPurpose.EXHAUSTED_SOURCE));
        }
        String endTurnProcedureQuery = endTurnProcedureQuery(question);
        if (endTurnProcedureQuery != null) {
            intents.add(intent(endTurnProcedureQuery, RetrievalPurpose.END_TURN_PROCEDURE));
        }
        String stateTransitionQuery = stateTransitionQuery(question);
        if (stateTransitionQuery != null) {
            intents.add(intent(stateTransitionQuery, RetrievalPurpose.STATE_TRANSITION));
        }
        String matchingValueResolutionQuery = matchingValueResolutionQuery(question);
        if (matchingValueResolutionQuery != null) {
            intents.add(intent(matchingValueResolutionQuery, RetrievalPurpose.MATCHING_VALUE_RESOLUTION));
        }
        String roundResetQuery = roundResetQuery(question);
        if (roundResetQuery != null) {
            intents.add(intent(roundResetQuery, RetrievalPurpose.ROUND_RESET));
        }
        String deferredTurnQuery = deferredTurnQuery(question);
        if (deferredTurnQuery != null) {
            intents.add(intent(deferredTurnQuery, RetrievalPurpose.DEFERRED_TURN));
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

    private static String stateTransitionQuery(String question) {
        boolean actorExits = AnswerRetrievalPlanner.containsAny(
                question,
                "leaves play", "becomes inactive", "cannot continue", "runs out", "out of cards", "empty hand", "no cards",
                "退出", "失去行动资格", "无法继续", "用完", "出完", "无牌", "没有手牌", "手牌为零");
        boolean asksNextActor = AnswerRetrievalPlanner.containsAny(
                question,
                "who acts next", "who starts", "who leads", "next trick", "turn order", "next player",
                "谁行动", "谁开始", "谁领出", "下一墩", "下一位", "由谁");
        if (!actorExits || !asksNextActor) return null;
        return AnswerRetrievalPlanner.bounded(question + " state transition successor actor replacement active player skipped turn order "
                + "状态变化 后继行动者 替代玩家 跳过 行动顺序 例外");
    }

    /**
     * Players usually describe this situation as "the same number" rather than using a rulebook's local term
     * such as collision, bump, or priority. Preserve their wording, then add only cross-game procedural synonyms
     * so retrieval can find the named resolution without guessing a game-specific outcome.
     */
    private static String matchingValueResolutionQuery(String question) {
        boolean mentionsMatchingValue = AnswerRetrievalPlanner.containsAny(
                question,
                "same number", "same value", "same card", "matching number", "equal number", "equal value",
                "相同数字", "同样数字", "数字相同", "相同数值", "相同点数", "相同牌", "同号", "撞号", "碰撞");
        if (!mentionsMatchingValue) return null;
        return AnswerRetrievalPlanner.bounded(question
                + " shared value equal numbered cards matching number tie collision bump priority resolution order "
                + "players resolve same number same value matching card equal number "
                + "同数值 同号 碰撞 优先顺序 处理");
    }

    private static String exhaustedSourceQuery(String question) {
        boolean asksAboutSourceArea = AnswerRetrievalPlanner.containsAny(
                question,
                "draw zone",
                "draw dice",
                "draw amount",
                "deck",
                "pile",
                "supply",
                "pool",
                "draw",
                "take",
                "refill",
                "抽",
                "摸",
                "取",
                "补",
                "拿",
                "牌堆",
                "供应",
                "区域");
        boolean asksAboutShortage = AnswerRetrievalPlanner.containsAny(
                question,
                "not enough",
                "insufficient",
                "empty",
                "runs out",
                "不足",
                "不够",
                "用完",
                "没有骰子");
        if (!asksAboutSourceArea || !asksAboutShortage) return null;
        return AnswerRetrievalPlanner.bounded(question + " source area supply pile deck depleted empty insufficient discard return recycle "
                + "refill reshuffle continue procedure 资源区 牌堆 供应区 耗尽 为空 弃置 回收 移回 补充 洗混 继续");
    }

    private static String endTurnProcedureQuery(String question) {
        boolean asksAfterTurn = AnswerRetrievalPlanner.containsAny(
                question,
                "end turn",
                "ends turn",
                "finish turn",
                "after turn",
                "after my turn",
                "结束回合",
                "结束自己的回合",
                "结束本回合",
                "完成回合",
                "回合结束");
        boolean asksForEventProcedure = AnswerRetrievalPlanner.containsAny(
                question,
                "draw",
                "reveal",
                "resolve",
                "alert",
                "event",
                "card",
                "抽",
                "翻",
                "结算",
                "执行",
                "警报",
                "事件",
                "牌");
        if (!asksAfterTurn || !asksForEventProcedure) return null;
        return AnswerRetrievalPlanner.bounded("completed turn draw reveal resolve event effect next player procedure "
                + "完成回合后 抽取 展示 执行 结算 事件 效果 下一位玩家 流程 " + question);
    }

    private static String roundResetQuery(String question) {
        boolean asksRoundEnd = AnswerRetrievalPlanner.containsAny(
                question, "end of round", "round ends", "round end", "一轮结束", "轮次结束", "回合结束", "轮结束");
        boolean asksReset = AnswerRetrievalPlanner.containsAny(question, "recover", "return", "reset", "回收", "归还", "回到", "重置", "拿回");
        if (!asksRoundEnd || !asksReset) return null;
        return AnswerRetrievalPlanner.bounded(question + " end of round recover return reset refresh used pieces conditional "
                + "round reset state transition 一轮结束 回收 返回 重置 刷新 条件");
    }

    private static String deferredTurnQuery(String question) {
        boolean asksLaterTurn = AnswerRetrievalPlanner.containsAny(
                question, "next time", "next turn", "later turn", "下一次轮到", "下次轮到", "之后轮到");
        boolean asksAboutRemainingPieces = AnswerRetrievalPlanner.containsAny(
                question,
                "remaining pieces", "unused pieces", "remaining actions", "unused actions", "remaining dice", "unused dice",
                "剩余组件", "未用组件", "剩余行动", "未用行动", "剩下的骰子", "剩余骰子", "未用骰子");
        if (!asksLaterTurn || !asksAboutRemainingPieces) return null;
        return AnswerRetrievalPlanner.bounded(question + " worked example player turn ends remaining pieces later turn use action "
                + "round ends turn sequence 示例回合 剩余组件 下次回合 使用 行动顺序");
    }

    private static RetrievalIntent intent(String query, RetrievalPurpose purpose) {
        return new RetrievalIntent(query, java.util.Set.of(), null, false, purpose);
    }
}
