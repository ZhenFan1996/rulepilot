package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FakeTeachingOutlineModel implements TeachingOutlineModel {

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return new OutlineDraft(
                inferredTitle(request),
                "先看懂目标与桌面状态，再按实际回合顺序走一遍关键选择，最后说明结束与完整计分。",
                List.of(
                        topic(request, "goal-and-components", "先认识目标与关键组件", "从规则书证据说明玩家追求什么，以及关键组件在游戏中代表什么。", true,
                                List.of("objective", "goal", "components", "contents", "目标", "组件", "配件"), List.of("objective", "components")),
                        topic(request, "prepare-the-table", "照着规则书完成设置", "按桌面公共区与个人区域的顺序讲清初始摆放、发放与先手。", true,
                                List.of("setup", "set up", "preparation", "starting", "设置", "准备", "开局"), List.of("setup")),
                        topic(request, "turn-flow", "一个回合到底怎样进行", "说明轮到玩家时先做什么、有哪些互斥或附加选择，以及何时轮到下一位。", false,
                                List.of("your turn", "on a turn", "turn", "either", "回合", "轮到", "选择"), List.of("core_loop")),
                        topic(request, "actions-and-costs", "行动、费用与结果", "逐项讲清主要行动的前置条件、支付、执行结果和立即触发效果。", false,
                                List.of("action", "spend", "pay", "cost", "gain", "行动", "花费", "支付", "获得"), List.of("core_loop", "actions")),
                        topic(request, "rounds-and-exceptions", "轮次推进与容易漏掉的例外", "区分玩家回合与整轮结束，说明状态保留、重置、跳过与关键例外。", false,
                                List.of("round", "end of the round", "recover", "exception", "一轮", "轮次", "重置", "例外"), List.of("first_round", "exceptions")),
                        topic(request, "examples-and-reference", "跟着官方示例走一遍", "使用规则书中的示例、图示或速查验证行动顺序与桌面状态，不把示例特例当成通用规则。", true,
                                List.of("example", "example round", "reference", "anatomy", "示例", "范例", "速查", "图示"), List.of("first_round", "examples")),
                        topic(request, "finish-and-score", "结束游戏并算出胜负", "说明结束触发、最后一轮处理、所有计分来源、同分规则与胜者判定。", false,
                                List.of("end of the game", "game end", "final scoring", "winner", "tie", "游戏结束", "最终计分", "同分"), List.of("end", "scoring", "tie_breaker"))));
    }

    private TopicDraft topic(
            OutlineRequest request,
            String key,
            String title,
            String objective,
            boolean visual,
            List<String> cues,
            List<String> tags) {
        List<PageInput> matchingPages = request.pages().stream()
                .filter(page -> containsAny(page.text(), cues))
                .limit(2)
                .toList();
        if (matchingPages.isEmpty()) matchingPages = request.pages().stream().limit(2).toList();
        List<String> queries = sourceQueries(matchingPages, cues);
        return new TopicDraft(
                key,
                title,
                objective,
                true,
                visual,
                queries,
                tags,
                matchingPages.stream().map(PageInput::pageNumber).toList());
    }

    private List<String> sourceQueries(List<PageInput> pages, List<String> cues) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (PageInput page : pages) {
            Arrays.stream(page.text().split("\\R"))
                    .map(String::strip)
                    .filter(line -> line.length() >= 4)
                    .filter(line -> containsAny(line, cues))
                    .map(this::boundedQuery)
                    .forEach(queries::add);
            if (queries.size() >= 2) break;
        }
        if (queries.isEmpty()) {
            pages.stream()
                    .flatMap(page -> Arrays.stream(page.text().split("\\R")))
                    .map(String::strip)
                    .filter(line -> line.length() >= 4)
                    .map(this::boundedQuery)
                    .findFirst()
                    .ifPresent(queries::add);
        }
        return new ArrayList<>(queries).stream().limit(2).toList();
    }

    private boolean containsAny(String text, List<String> cues) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(cue -> normalized.contains(cue.toLowerCase(Locale.ROOT)));
    }

    private String boundedQuery(String line) {
        String normalized = line.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private String inferredTitle(OutlineRequest request) {
        if (request.pages().isEmpty()) return "Imported rulebook";
        return Arrays.stream(request.pages().getFirst().text().split("\\R"))
                .map(String::strip)
                .filter(line -> line.length() >= 3 && line.length() <= 100)
                .filter(line -> !line.startsWith("["))
                .filter(line -> !containsAny(line, List.of(
                        "setup", "setting up", "components", "contents", "rulebook", "rules", "设置", "组件", "规则")))
                .findFirst()
                .orElse("Imported rulebook");
    }
}
