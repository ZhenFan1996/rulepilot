package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Pure guardrails for keeping a generated lesson outline teachable and source-page complete.
 *
 * <p>The policy only describes when an outline needs one bounded revision. It never selects pages, calls a model,
 * or persists a plan.</p>
 */
final class TeachingOutlineRevisionPolicy {

    static final String VISUAL_CATALOG_PREFIX = "[Visual page catalog; verify against page image]";

    private static final List<ChapterOwnershipDomain> CHAPTER_OWNERSHIP_DOMAINS = List.of(
            new ChapterOwnershipDomain(
                    "cost or payment procedure",
                    List.of("cost", "payment", "费用", "成本", "支付"),
                    List.of("cost", "pay", "payment", "spend", "discount", "extra cost", "费用", "成本", "支付", "花费", "折扣", "额外成本")),
            new ChapterOwnershipDomain(
                    "card or option procedure",
                    List.of("card", "deck", "hand", "option", "effect", "卡牌", "牌堆", "手牌", "选择", "效果"),
                    List.of("face-up", "draw", "take a card", "play", "discard", "bonus", "面朝上", "抽牌", "拿取", "打出", "弃牌", "奖励")),
            new ChapterOwnershipDomain(
                    "cleanup procedure",
                    List.of("cleanup", "discard", "refill", "清理", "弃牌", "补充"),
                    List.of("hand limit", "discard pile", "refill", "replenish", "手牌上限", "弃牌堆", "补牌", "补充")),
            new ChapterOwnershipDomain(
                    "game-end trigger",
                    List.of("end of game", "game end", "end condition", "finish", "游戏结束", "结束条件", "终局"),
                    List.of("game end", "end condition", "end trigger", "finish the game", "游戏结束", "结束条件", "结束触发", "终局")),
            new ChapterOwnershipDomain(
                    "final scoring or tie breaker",
                    List.of("final scoring", "tie", "winner", "最终计分", "平局", "胜者"),
                    List.of("final scoring", "tie", "winner", "最终计分", "平局", "胜者")),
            new ChapterOwnershipDomain(
                    "component or icon mapping",
                    List.of("component", "icon", "组件", "图标"),
                    List.of("component", "icon", "symbol", "组件", "图标", "符号")));

    private TeachingOutlineRevisionPolicy() {}

    static boolean requiresChapterOwnershipRerun(
            TeachingOutlineModel.OutlineDraft outlineBeforeCoverageRevision,
            TeachingOutlineModel.OutlineDraft outlineAfterCoverageRevision) {
        if (outlineBeforeCoverageRevision == null || outlineAfterCoverageRevision == null) {
            throw new IllegalArgumentException("outline revisions are required");
        }
        return !outlineBeforeCoverageRevision.equals(outlineAfterCoverageRevision);
    }

    static Optional<String> chapterOwnershipRevisionFeedback(TeachingOutlineModel.OutlineDraft outline) {
        if (outline == null || outline.topics().size() < 2) return Optional.empty();
        List<String> conflicts = new ArrayList<>();
        for (TeachingOutlineModel.TopicDraft current : outline.topics()) {
            for (ChapterOwnershipDomain domain : CHAPTER_OWNERSHIP_DOMAINS) {
                if (!containsAny(topicObjective(current), domain.detailTerms())
                        || containsAny(current.key() + " " + current.title(), domain.ownerTerms())) {
                    continue;
                }
                List<TeachingOutlineModel.TopicDraft> owners = outline.topics()
                        .stream()
                        .filter(topic -> topic != current)
                        .filter(topic -> containsAny(topic.key() + " " + topic.title(), domain.ownerTerms()))
                        .limit(3)
                        .toList();
                if (owners.isEmpty()) continue;
                String ownerTitles = owners.stream()
                        .map(TeachingOutlineModel.TopicDraft::title)
                        .collect(Collectors.joining("、"));
                conflicts.add("“" + current.title() + "” currently includes " + domain.label() + ": “"
                        + boundedOwnershipObjective(current.objective()) + "”; chapter(s) “" + ownerTitles
                        + "” should own that nested detail.");
            }
        }
        appendPlayerJourneyOrderConflicts(outline, conflicts);
        if (conflicts.isEmpty()) return Optional.empty();
        return Optional.of("""
                Rebuild this complete lesson outline, not merely the listed chapters. Give every material detailed rule one primary chapter owner.
                A chapter may retain the stage, order, immediate choice, or result it needs to connect the lesson, but must not explain a nested cost, payment, card procedure, cleanup procedure, end trigger, scoring calculation, component mapping, or exception that a later detail chapter owns. Keep the bridge; move only the nested detail to its later owner. Do not delete coverage, source-page bindings, or source-language retrieval queries.
                The lesson must remain playable in reading order: finish the ordinary turn and its mandatory closure before teaching game end or final scoring. Put detailed scoring criteria before the final scoring conclusion.
                Detected chapter-boundary conflicts:
                """ + String.join("\n", conflicts));
    }

    static Optional<String> sourcePageCoverageRevisionFeedback(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> pages) {
        if (outline == null || pages == null || pages.isEmpty()) return Optional.empty();
        Set<Integer> boundPages = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<PageInput> missing = pages.stream()
                .filter(TeachingOutlineRevisionPolicy::isSubstantiveRulebookPage)
                .filter(page -> !boundPages.contains(page.pageNumber()))
                .limit(4)
                .toList();
        if (missing.isEmpty()) return Optional.empty();
        String pageCatalog = missing.stream()
                .map(page -> "Page " + page.pageNumber() + ": " + boundedCoveragePageText(page.text()))
                .collect(Collectors.joining("\n"));
        return Optional.of("""
                Rebuild the complete lesson outline so every substantive rulebook page has a teaching owner. The listed
                page(s) are not currently bound to any topic's sourcePageNumbers. Add or expand a game-specific topic
                for the actual rule, variant, icon, exception, example, or procedure on that page. Preserve the current
                lesson's covered rules, source-language retrieval queries, and chapter order; do not hide an omitted page
                by attaching it to an unrelated topic.
                A `[Visual page catalog]` entry is a page-local observation to navigate the original page image, not a
                free-standing rule conclusion. Use it only to decide whether the page needs a teaching owner. Keep the
                source page binding and do not invent an action, condition, or icon meaning that is not visibly stated.
                Unowned substantive source pages:
                """ + pageCatalog);
    }

    static boolean isSubstantiveVisualCatalogPage(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean credits = normalized.contains("credits") || normalized.contains("鸣谢");
        boolean cover = (normalized.contains("cover") || normalized.contains("封面"))
                && (normalized.contains("no game mechanism")
                        || normalized.contains("no rule text")
                        || normalized.contains("no gameplay rules")
                        || normalized.contains("no operational instructions")
                        || normalized.contains("visual cover")
                        || normalized.contains("无游戏机制")
                        || normalized.contains("无游戏规则")
                        || normalized.contains("仅作为视觉封面"));
        boolean storageOnlyInsert = normalized.contains("storage or assembly instructions")
                && (normalized.contains("not gameplay")
                        || normalized.contains("non-gameplay")
                        || normalized.contains("this page is")
                        || normalized.contains("only for storage")
                        || normalized.contains("仅为收纳或组装说明"));
        boolean nonGameplayInsert = normalized.contains("非游戏规则")
                || normalized.contains("非游戏玩法")
                || normalized.contains("non-gameplay material")
                || normalized.contains("non-gameplay rule")
                || normalized.contains("宣传页")
                || normalized.contains("宣传广告")
                || normalized.contains("广告页")
                || normalized.contains("advertisement for another")
                || normalized.contains("仅为收纳或组装说明")
                || storageOnlyInsert
                || normalized.contains("仅为封面设计");
        return !credits && !cover && !nonGameplayInsert;
    }

    static boolean isSubstantiveRulebookText(String text) {
        return text != null && !text.isBlank() && isSubstantiveRulebookPage(new PageInput(1, text));
    }

    private static boolean isSubstantiveRulebookPage(PageInput page) {
        String text = page.text() == null ? "" : page.text().toLowerCase(Locale.ROOT);
        if (text.contains(VISUAL_CATALOG_PREFIX.toLowerCase(Locale.ROOT))) {
            return hasConcreteVisualGameplayEvidence(text);
        }
        return text.matches("(?s).*\\b(?:setup|turn|action|round|gameplay|game end|score|rule|component|"
                + "card|token|player|must|may|place|move|discard|variant|advanced)\\b.*")
                || text.matches("(?s).*(?:设置|回合|行动|结束|计分|规则|组件|卡牌|令牌|玩家|必须|可以|放置|移动|弃牌|变体|高级).*" );
    }

    private static boolean hasConcreteVisualGameplayEvidence(String normalizedText) {
        if (!isSubstantiveVisualCatalogPage(normalizedText)) return false;
        return !normalizedText.contains("no factual visual claim is available")
                && !normalizedText.contains("unreadable")
                && !normalizedText.contains("不可可靠转写")
                && !normalizedText.contains("no legible printed term")
                && !normalizedText.contains("visual cover")
                && !normalizedText.contains("盒面")
                && !normalizedText.contains("封面")
                && !normalizedText.contains("contents")
                && !normalizedText.contains("目录");
    }

    private static String boundedCoveragePageText(String text) {
        String value = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        int visualCatalog = value.indexOf(VISUAL_CATALOG_PREFIX);
        if (visualCatalog >= 0) value = value.substring(visualCatalog);
        return value.length() <= 420 ? value : value.substring(0, 419) + "…";
    }

    private static void appendPlayerJourneyOrderConflicts(
            TeachingOutlineModel.OutlineDraft outline, List<String> conflicts) {
        int firstFinale = IntStream.range(0, outline.topics().size())
                .filter(index -> isFinaleTopic(outline.topics().get(index)))
                .findFirst()
                .orElse(-1);
        if (firstFinale < 0) return;

        TeachingOutlineModel.TopicDraft finale = outline.topics().get(firstFinale);
        for (int index = firstFinale + 1; index < outline.topics().size(); index++) {
            TeachingOutlineModel.TopicDraft later = outline.topics().get(index);
            if (isTurnClosureTopic(later)) {
                conflicts.add("“" + later.title() + "” teaches the normal turn's closing procedure after finale chapter “"
                        + finale.title() + "”. Move the complete cleanup, replenishment, hand-limit, or reset procedure "
                        + "before game end and final scoring so a new player can finish a real turn first.");
            }
            if (isScoringDetailTopic(later) && !isFinaleTopic(later)) {
                conflicts.add("“" + later.title() + "” gives a scoring criterion after finale chapter “"
                        + finale.title() + "”. Move that scoring detail before the end/final-scoring conclusion, while "
                        + "keeping the final total and tie break in the finale chapter.");
            }
        }
    }

    private static boolean isFinaleTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "end of game", "game end", "end trigger", "final scoring", "tie breaker", "winner",
                "游戏结束", "终局", "结束触发", "最终计分", "平局", "胜者"));
    }

    private static boolean isTurnClosureTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "cleanup", "hand limit", "discard pile", "refill", "replenish", "end of round",
                "清理", "手牌上限", "弃牌堆", "补牌", "补充", "回合结束"));
    }

    private static boolean isScoringDetailTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "score", "points", "victory point", "scoring table", "得分", "计分", "点数", "分数", "品质瓷砖", "品质板"));
    }

    private static String topicObjective(TeachingOutlineModel.TopicDraft topic) {
        return topic.objective().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, List<String> terms) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(normalized::contains);
    }

    private static String boundedOwnershipObjective(String objective) {
        String value = objective.strip();
        return value.length() <= 320 ? value : value.substring(0, 319) + "…";
    }

    private record ChapterOwnershipDomain(String label, List<String> ownerTerms, List<String> detailTerms) {}
}
