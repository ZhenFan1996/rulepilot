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
        if (isVisualCatalog(request)) return visualCatalogOutline(request);
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

    private OutlineDraft visualCatalogOutline(OutlineRequest request) {
        List<PageInput> substantive = request.pages().stream().filter(this::isSubstantiveVisualPage).toList();
        List<PageInput> available = substantive.isEmpty() ? request.pages() : substantive;
        List<List<PageInput>> pagesByTopic = new ArrayList<>();
        for (int index = 0; index < 7; index++) pagesByTopic.add(new ArrayList<>());
        for (PageInput page : available) {
            addVisualPage(pagesByTopic, visualTopicIndex(page.text()), page);
        }
        for (int index = 0; index < pagesByTopic.size(); index++) {
            if (pagesByTopic.get(index).isEmpty()) {
                pagesByTopic.get(index).add(available.get(Math.min(index, available.size() - 1)));
            }
        }
        return new OutlineDraft(
                inferredTitle(request),
                "先核对目标与桌面，再按实际回合推进主要选择，最后用规则书中的例外和结束条件完成一次可执行的首局准备。",
                List.of(
                        visualTopic("goal-and-components", "目标、组件与关键信息", "说明玩家追求的目标，辨认会直接影响选择的组件、卡面或图标。", true,
                                List.of("objective", "goal", "components", "contents"), List.of("objective", "components"), pagesByTopic.get(0)),
                        visualTopic("prepare-the-table", "准备桌面与起始资源", "按公共区域、个人区域和起始资源的顺序完成设置，并指出玩家人数或阵营造成的差异。", true,
                                List.of("setup", "set up", "starting", "player"), List.of("setup"), pagesByTopic.get(1)),
                        visualTopic("round-and-turn", "一轮怎样开始并推进", "区分整轮与玩家回合，说明开始状态、阶段顺序和何时把行动交给下一位。", false,
                                List.of("round", "turn", "overview", "draw"), List.of("core_loop", "round_structure"), pagesByTopic.get(2)),
                        visualTopic("actions-and-costs", "主要行动的选择与执行", "逐项说明主要行动的前置条件、支付或弃置、执行结果，以及不能执行时的处理。", true,
                                List.of("action", "deploy", "move", "control"), List.of("core_loop", "actions"), pagesByTopic.get(3)),
                        visualTopic("exceptions-and-restrictions", "限制、FAQ 与常见例外", "讲清会改变通常行动顺序、目标、距离或限制的规则，并把例外和普通规则并列核对。", false,
                                List.of("attack", "tactic", "frequently", "restriction"), List.of("exceptions"), pagesByTopic.get(4)),
                        visualTopic("examples-and-reference", "示例、变体与速查", "使用官方示例、变体或速查页核对实际桌面状态，不把示例特例误当成通用规则。", true,
                                List.of("example", "battle", "summary", "reference"), List.of("first_round", "examples"), pagesByTopic.get(5)),
                        visualTopic("finish-and-score", "结束、计分与胜者", "说明结束触发、最后处理、胜利或计分判定，并单独指出同分规则是否存在。", false,
                                List.of("end", "win", "score", "victory"), List.of("end", "scoring", "tie_breaker"), pagesByTopic.get(6))));
    }

    private TopicDraft visualTopic(
            String key,
            String title,
            String objective,
            boolean visual,
            List<String> cues,
            List<String> tags,
            List<PageInput> pages) {
        return new TopicDraft(
                key,
                title,
                objective,
                true,
                visual,
                sourceQueries(pages, cues),
                tags,
                pages.stream().map(PageInput::pageNumber).toList());
    }

    private void addVisualPage(List<List<PageInput>> pagesByTopic, int preferredTopic, PageInput page) {
        int topic = preferredTopic;
        if (pagesByTopic.get(topic).size() >= 4) {
            topic = java.util.stream.IntStream.range(0, pagesByTopic.size())
                    .filter(index -> pagesByTopic.get(index).size() < 4)
                    .findFirst()
                    .orElse(preferredTopic);
        }
        if (pagesByTopic.get(topic).size() < 4) pagesByTopic.get(topic).add(page);
    }

    private boolean isVisualCatalog(OutlineRequest request) {
        return request.pages().stream().allMatch(page -> page.text().startsWith("[Visual page catalog;"));
    }

    private boolean isSubstantiveVisualPage(PageInput page) {
        String text = page.text().toLowerCase(Locale.ROOT);
        boolean credits = text.contains("credits") || text.contains("鸣谢");
        boolean cover = (text.contains("cover") || text.contains("封面"))
                && containsAny(text, List.of(
                        "no game mechanism",
                        "no rule text",
                        "no gameplay rules",
                        "no operational instructions",
                        "visual cover",
                        "无游戏机制",
                        "无游戏规则",
                        "仅作为视觉封面"));
        boolean storageOnlyInsert = text.contains("storage or assembly instructions")
                && containsAny(text, List.of("not gameplay", "non-gameplay", "this page is", "only for storage", "仅为收纳或组装说明"));
        boolean nonGameplayInsert = containsAny(text, List.of(
                "非游戏规则",
                "非游戏玩法",
                "non-gameplay material",
                "non-gameplay rule",
                "宣传页",
                "宣传广告",
                "广告页",
                "advertisement for another",
                "仅为收纳或组装说明",
                "仅为封面设计")) || storageOnlyInsert;
        return !credits && !cover && !nonGameplayInsert;
    }

    private int visualTopicIndex(String pageText) {
        String text = pageText.toLowerCase(Locale.ROOT);
        if (containsAny(text, List.of(
                "component", "contents", "goal", "objective", "anatomy", "卡牌构成", "组件",
                "游戏目标", "玩家目标", "获胜目标", "胜利条件", "目标为", "目标：", "目标:"))) {
            return 0;
        }
        if (hasCompleteEndingEvidence(text)) return 6;
        if (containsAny(text, List.of(
                "champion tours", "tour de cube", "variant", "expansion", "scenario", "historical",
                "变体", "扩展", "战役", "历史"))) return 5;
        if (containsAny(text, List.of("set up", "setup", "setting up", "starting resources", "设置", "起始资源"))) {
            return 1;
        }
        if (containsAny(text, List.of("frequently", "faq", "common question", "常见问题", "问答"))) return 4;
        if (containsAny(text, List.of(
                "example",
                "battle",
                "historical",
                "history",
                "scenario",
                "summary",
                "reference",
                "variant",
                "示例",
                "战役",
                "历史",
                "速查",
                "变体"))) return 5;
        if (containsAny(text, List.of(
                "game overview", "how to play", "roll phase", "run phase", "draw", "round", "turn",
                "游戏概览", "轮次", "回合", "阶段"))) return 2;
        if (containsAny(text, List.of(
                "dice overview", "fan track", "action", "deploy", "move", "control", "maneuver",
                "骰子概览", "风扇赛道", "行动", "部署", "移动", "控制", "机动"))) return 3;
        if (containsAny(text, List.of(
                "ability", "attack", "tactic", "restriction", "exception", "abilities", "攻击", "战术", "限制", "例外"))) {
            return 4;
        }
        return 3;
    }

    private boolean hasCompleteEndingEvidence(String text) {
        boolean endingTrigger = containsAny(text, List.of(
                "end of game", "game over", "finish space", "游戏结束", "终局", "到达终点", "终点空间"));
        boolean resolution = containsAny(text, List.of(
                "winner", "victory", "how to win", "scoring", "score", "tie",
                "获胜", "胜者", "胜利", "计分", "分数", "平局"));
        return endingTrigger && resolution;
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
            visualPrintedTerms(page.text()).forEach(term -> {
                if (term.length() >= 3) queries.add(boundedQuery(term));
            });
            if (queries.size() >= 2) break;
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

    private List<String> visualPrintedTerms(String text) {
        if (!text.startsWith("[Visual page catalog;")) return List.of();
        return Arrays.stream(text.split("\\R"))
                .filter(line -> line.startsWith("Printed terms:"))
                .map(line -> line.substring("Printed terms:".length()).strip())
                .flatMap(line -> Arrays.stream(line.split(";")))
                .map(String::strip)
                .filter(term -> !term.isBlank() && !term.toLowerCase(Locale.ROOT).startsWith("unavailable"))
                .limit(2)
                .toList();
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
        for (PageInput page : request.pages()) {
            String title = visualPrintedTerms(page.text()).stream()
                    .filter(this::isUsableTitle)
                    .findFirst()
                    .orElse(null);
            if (title != null) return title;
        }
        return Arrays.stream(request.pages().getFirst().text().split("\\R"))
                .map(String::strip)
                .filter(line -> line.length() >= 3 && line.length() <= 100)
                .filter(line -> !line.startsWith("["))
                .filter(line -> !containsAny(line, List.of(
                        "setup", "setting up", "components", "contents", "rulebook", "rules", "设置", "组件", "规则")))
                .findFirst()
                .orElse("Imported rulebook");
    }

    private boolean isUsableTitle(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return value.length() >= 3 && value.length() <= 100
                && !containsAny(normalized, List.of("rules", "rulebook", "contents", "components", "credits", "cover"));
    }
}
