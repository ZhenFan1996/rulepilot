package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Pure evidence rules for a player asking how an exhausted draw or source area recovers. */
final class AnswerReplenishmentPolicy {

    private static final Pattern EVIDENCED_REPLENISHMENT_PROCEDURE = Pattern.compile(
            "(?isu)(?=.*(?:draw|take|refill|source\\s+area|supply|pool|deck|pile|抽|摸|取|补|拿|牌堆|供应|区域))"
                    + "(?=.*(?:empty|no\\s+(?:dice|cards?|tokens?)|无(?:骰|牌|令牌)|没有(?:骰|牌|令牌)|为空|耗尽))"
                    + ".*(?:discard|return|recycle|refill|reshuffle|continue|弃置|移回|回收|补充|洗混|继续)");
    private static final Pattern DIRECT_CHINESE_REPLENISHMENT_SENTENCE = Pattern.compile(
            "(?:若|如果|当)[^。；;]{0,120}(?:无|没有|为空|耗尽)[^。；;]{0,180}"
                    + "(?:弃置|移回|回收|补充|洗混|继续)[^。；;]{0,180}[。；;]");
    private static final Pattern EXHAUSTED_SOURCE_QUESTION = Pattern.compile(
            "(?isu)(?=.*(?:draw|take|refill|source\\s+area|supply|pool|deck|pile|抽|摸|取|补|拿|牌堆|供应|区域))"
                    + "(?=.*(?:not\\s+enough|insufficient|empty|runs\\s+out|不足|不够|用完|没有骰子)).*");

    private AnswerReplenishmentPolicy() {}

    static Optional<ModelDraft> directFallback(ModelRequest request) {
        if (!isExhaustedSourceQuestion(request.question())) return Optional.empty();
        return request.evidence().stream()
                .map(AnswerReplenishmentPolicy::directChineseFallback)
                .flatMap(Optional::stream)
                .findFirst();
    }

    static ModelDraft replaceMisdirectedDraft(ModelRequest request, ModelDraft draft) {
        if (!isExhaustedSourceQuestion(request.question()) || !hasEvidencedProcedure(request)) return draft;
        return directFallback(request).orElse(draft);
    }

    static boolean hasEvidencedProcedure(ModelRequest request) {
        return request.evidence().stream()
                .map(EvidenceInput::excerpt)
                .anyMatch(excerpt -> EVIDENCED_REPLENISHMENT_PROCEDURE.matcher(excerpt).find());
    }

    static String retrievalQuery(String question) {
        String combined = "耗尽 为空 回收 移回 补充 洗混 继续 " + question.strip();
        return combined.length() <= 600 ? combined : combined.substring(0, 600);
    }

    private static boolean isExhaustedSourceQuestion(String question) {
        return EXHAUSTED_SOURCE_QUESTION.matcher(question).matches();
    }

    private static Optional<ModelDraft> directChineseFallback(EvidenceInput source) {
        var matcher = DIRECT_CHINESE_REPLENISHMENT_SENTENCE.matcher(source.excerpt());
        if (!matcher.find()) return Optional.empty();
        String ruling = matcher.group().strip();
        if (ruling.length() > 240) return Optional.empty();
        return Optional.of(new ModelDraft(
                ruling,
                "抽取过程中该区域用尽时，先按这条规则回收，再继续本次抽取。",
                List.of(source.chunkId()),
                List.of(),
                "HIGH"));
    }
}
