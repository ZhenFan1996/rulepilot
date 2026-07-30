package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pure player-facing checks and cleanup for an untrusted rule-answer draft. */
final class AnswerDraftSafetyPolicy {

    private static final Pattern INTERNAL_EVIDENCE_REFERENCE = Pattern.compile(
            "(?iu)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}|\\bchunk(?:id)?\\b"
                    + "|(?:证据|引用|evidence|source)\\s*\\[?E\\d+\\]?|\\[E\\d+\\]|\\[[0-9a-f]{8}]");
    private static final Pattern RESOURCE_CARD_CONFLATION = Pattern.compile(
            "(?iu)(?:icon|symbol|图标|符号|\\p{So})[^。；;\\n]{0,60}"
                    + "(?:means|represents|corresponds|表示|代表|对应|是)[^。；;\\n]{0,40}"
                    + "(?:cards?|hand|手牌|张[^，。；\\n]{0,20}牌)"
                    + "|(?:at least|至少|需要)[^。；;\\n]{0,32}(?:cards?|张[^，。；\\n]{0,20}牌)"
                    + "[^。；;\\n]{0,120}(?:activate|use|发动|使用|前提)"
                    + "|(?:activate|use|发动|使用|前提)[^。；;\\n]{0,80}(?:at least|至少|需要)"
                    + "[^。；;\\n]{0,32}(?:cards?|张[^，。；\\n]{0,20}牌)");
    private static final Pattern UNASKED_REPEATABILITY_CLAIM = Pattern.compile(
            "(?iu)(?:each\\s+(?:reward|effect|action).{0,32}(?:once|twice)|"
                    + "(?:may|can|only|at most).{0,32}(?:once|twice)|"
                    + "每个(?:奖励|效果|行动).{0,32}(?:仅|只|最多|可).{0,16}(?:一次|两次)|"
                    + "(?:最多|只能).{0,28}(?:领取|执行|获得).{0,20}(?:一次|两次)|无限循环)");
    private static final Pattern REPEATABILITY_QUESTION = Pattern.compile(
            "(?iu)\\b(?:again|repeat|repeated|how many times|once|twice|unlimited)\\b"
                    + "|次数|几次|重复|再次|还能|多次|反复|无限");
    private static final Pattern EVIDENCED_REPEATABILITY = Pattern.compile(
            "(?iu)(?:each\\s+(?:reward|effect|action).{0,48}(?:once|twice)|"
                    + "(?:only|at most).{0,32}(?:once|twice)|"
                    + "每个(?:奖励|效果|行动).{0,48}(?:一次|两次)|"
                    + "每回合.{0,28}(?:只能|最多).{0,28}(?:一次|两次)|"
                    + "(?:最多|只能).{0,36}(?:领取|执行|获得).{0,24}(?:一次|两次)|无限循环)");
    private static final Pattern NEGATED_CARD_REQUIREMENT = Pattern.compile(
            "(?iu)(?:不需要|无需|无须|不必|并非|不是|不要求|没有).{0,40}(?:cards?|hand|手牌|张[^，。；\\n]{0,20}牌)"
                    + "|(?:does not|doesn't|do not|don't|need not|no need to|not required|without)"
                    + ".{0,40}(?:cards?|hand)");
    private static final Pattern VISUAL_GLYPH = Pattern.compile("\\p{So}");
    private static final Pattern TITLE_CASED_ENGLISH_LABEL = Pattern.compile(
            "\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3}\\b");
    private static final Pattern SPECULATIVE_UNDEFINED_TERM_DEFINITION = Pattern.compile(
            "(?isu)(?:未(?:明确)?(?:定义|说明)|没有(?:明确)?(?:定义|说明)|"
                    + "does\\s+not\\s+define|doesn't\\s+define|not\\s+defined|doesn't\\s+specify)"
                    + ".{0,220}(?:可能|例如|比如|通常|一般|自行|推测|猜测|相邻|邻接|包裹|"
                    + "maybe|perhaps|such\\s+as|for\\s+example|infer|assume)");

    private AnswerDraftSafetyPolicy() {}

    static boolean containsUnaskedUnsupportedRepeatabilityClaim(ModelRequest request, ModelDraft draft) {
        if (REPEATABILITY_QUESTION.matcher(request.question()).find()
                || !UNASKED_REPEATABILITY_CLAIM.matcher(playerFacingText(draft)).find()) {
            return false;
        }
        Set<UUID> citationIds = Set.copyOf(draft.citationIds());
        return request.evidence().stream()
                .filter(source -> citationIds.contains(source.chunkId()))
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .noneMatch(excerpt -> EVIDENCED_REPEATABILITY.matcher(excerpt).find());
    }

    static boolean containsUncitedEnglishTitleLabel(ModelRequest request, ModelDraft draft) {
        String playerText = playerFacingText(draft);
        if (playerText.isBlank()) return false;
        Set<UUID> citations = Set.copyOf(draft.citationIds());
        String citedText = request.evidence().stream()
                .filter(source -> citations.contains(source.chunkId()))
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("\n"))
                .toLowerCase(Locale.ROOT);
        var matcher = TITLE_CASED_ENGLISH_LABEL.matcher(playerText);
        while (matcher.find()) {
            if (!citedText.contains(matcher.group().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static boolean containsResourceCardConflation(ModelDraft draft) {
        String text = playerFacingText(draft);
        var matcher = RESOURCE_CARD_CONFLATION.matcher(text);
        while (matcher.find()) {
            int contextStart = Math.max(0, matcher.start() - 48);
            int contextEnd = Math.min(text.length(), matcher.end() + 48);
            if (!NEGATED_CARD_REQUIREMENT.matcher(text.substring(contextStart, contextEnd)).find()) return true;
        }
        return false;
    }

    static boolean containsVisualGlyph(ModelDraft draft) {
        return VISUAL_GLYPH.matcher(playerFacingText(draft)).find();
    }

    static boolean containsUnresolvedVisualSymbol(ModelDraft draft) {
        return AnswerEvidencePolicy.hasUnresolvedVisualSymbol(playerFacingText(draft));
    }

    static boolean containsSpeculativeUndefinedTermDefinition(ModelDraft draft) {
        return SPECULATIVE_UNDEFINED_TERM_DEFINITION.matcher(playerFacingText(draft)).find();
    }

    static ModelDraft normalizeSingleMappedVisualGlyph(ModelDraft draft, List<String> components) {
        if (components.size() != 1 || !containsVisualGlyph(draft)) return draft;
        String component = components.getFirst();
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                normalizeVisualGlyphs(draft.shortVerdict(), component),
                normalizeVisualGlyphs(draft.explanation(), component),
                draft.citationIds(),
                draft.exceptions().stream().map(value -> normalizeVisualGlyphs(value, component)).toList(),
                draft.confidence(),
                draft.answerBasis());
    }

    static ModelDraft normalizeInternalEvidenceReferences(ModelDraft draft) {
        if (!containsInternalEvidenceReference(draft)) return draft;
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                normalizeInternalEvidenceReferences(draft.shortVerdict()),
                normalizeInternalEvidenceReferences(draft.explanation()),
                draft.citationIds(),
                draft.exceptions().stream().map(AnswerDraftSafetyPolicy::normalizeInternalEvidenceReferences).toList(),
                draft.confidence(),
                draft.answerBasis());
    }

    static ModelDraft normalizeDanglingPunctuation(ModelDraft draft) {
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                normalizeDanglingPunctuation(draft.shortVerdict()),
                normalizeDanglingPunctuation(draft.explanation()),
                draft.citationIds(),
                draft.exceptions().stream().map(AnswerDraftSafetyPolicy::normalizeDanglingPunctuation).toList(),
                draft.confidence(),
                draft.answerBasis());
    }

    static boolean containsInternalEvidenceReference(ModelDraft draft) {
        return containsInternalEvidenceReference(playerFacingText(draft));
    }

    static boolean containsInternalEvidenceReference(String value) {
        return value != null && INTERNAL_EVIDENCE_REFERENCE.matcher(value).find();
    }

    private static String normalizeVisualGlyphs(String value, String component) {
        if (value == null || value.isBlank()) return value;
        var matcher = VISUAL_GLYPH.matcher(value);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            int contextStart = Math.max(0, matcher.start() - component.length() - 8);
            String prefix = value.substring(contextStart, matcher.start());
            boolean alreadyNamed = prefix.matches(
                    "(?iu).*" + Pattern.quote(component) + "[\\s,，:：(/（-]*$");
            matcher.appendReplacement(normalized, alreadyNamed ? "" : " " + java.util.regex.Matcher.quoteReplacement(component) + " ");
        }
        matcher.appendTail(normalized);
        return normalized.toString()
                .replaceAll("([（(])\\s*[，,]\\s*", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .strip();
    }

    private static String normalizeDanglingPunctuation(String value) {
        if (value == null || value.isBlank()) return value;
        return value.replaceAll("([（(])\\s*[，,]\\s*", "$1");
    }

    private static String normalizeInternalEvidenceReferences(String value) {
        if (value == null || value.isBlank()) return value;
        return INTERNAL_EVIDENCE_REFERENCE.matcher(value)
                .replaceAll("")
                .replaceAll("\\(\\s*\\)|（\\s*）", "")
                .replaceAll("[ \\t]+([，。；,.!?！？])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .strip();
    }

    private static String playerFacingText(ModelDraft draft) {
        return Stream.concat(Stream.of(draft.shortVerdict(), draft.explanation()), draft.exceptions().stream())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }
}
