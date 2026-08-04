package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.RuleConceptComparison;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates cited, player-requested distinctions between two potentially confusing rule concepts. */
final class AnswerConceptComparisonResolver {

    private static final int MAX_COMPARISONS = 3;
    private static final Pattern COMPARISON_REQUEST = Pattern.compile(
            "(?iu)\\b(?:difference between|different from|compare|versus|vs\\.?|same as|distinguish)\\b|"
                    + "区别|不同|对比|比较|一样吗|怎么区分|有什么不一样");
    private static final Pattern ACTION_WINDOW = Pattern.compile(
            "(?iu)\\b(?:turn|phase|window|during|before|after|agent|reveal)\\b|回合|阶段|时机|行动|展示");
    private static final Pattern RESOURCE_FUNCTION = Pattern.compile(
            "(?iu)\\b(?:token|resource|spend|gain|pay|flip|convert|victory point|vp)\\b|"
                    + "标记|资源|花费|获得|支付|翻面|转换|胜利分");
    private static final Pattern STORAGE_STATUS = Pattern.compile(
            "(?iu)\\b(?:stock|store|stored|keep|kept|return|limit|remaining)\\b|"
                    + "库存|储存|保留|归还|上限|剩余");
    private static final Pattern BETWEEN_CONCEPTS = Pattern.compile(
            "(?iu)difference between\\s+(.+?)\\s+and\\s+(.+?)(?:[?.!]|$)");
    private static final Pattern DIFFERENT_FROM_CONCEPTS = Pattern.compile(
            "(?iu)(?:how (?:is|are)\\s+)?(.+?)\\s+different from\\s+(.+?)(?:[?.!]|$)");

    List<RuleConceptComparison> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("concept comparison input is invalid");
        if (draft.conceptComparisons().isEmpty()) {
            if (asksForComparison(request.question())) {
                throw new IllegalArgumentException("comparison answer omitted cited concept distinction");
            }
            return List.of();
        }
        if (draft.conceptComparisons().size() > MAX_COMPARISONS) {
            throw new IllegalArgumentException("too many concept comparisons");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        return draft.conceptComparisons().stream()
                .map(item -> resolveOne(item, request, availableEvidence, answerCitations))
                .toList();
    }

    static boolean asksForComparison(String question) {
        return question != null && COMPARISON_REQUEST.matcher(question).find();
    }

    private RuleConceptComparison resolveOne(
            RuleConceptComparisonRequest item,
            ModelRequest modelRequest,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (item == null) throw new IllegalArgumentException("concept comparison item is null");
        bounded(item.leftConcept(), 120, "left concept");
        bounded(item.leftDefinition(), 600, "left definition");
        bounded(item.rightConcept(), 120, "right concept");
        bounded(item.rightDefinition(), 600, "right definition");
        bounded(item.commonGround(), 500, "common ground");
        bounded(item.keyDifference(), 700, "key difference");
        bounded(item.practicalBoundary(), 600, "practical boundary");
        if (normalized(item.leftDefinition()).equals(normalized(item.rightDefinition()))) {
            throw new IllegalArgumentException("compared concepts cannot have identical definitions");
        }
        requireNamedByPlayer(item.leftConcept(), modelRequest.question());
        requireNamedByPlayer(item.rightConcept(), modelRequest.question());
        ConceptComparisonBasis basis;
        try {
            basis = ConceptComparisonBasis.valueOf(item.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("concept comparison basis is invalid", invalidBasis);
        }
        validateBasis(item, basis);
        if (item.citationIds() == null || item.citationIds().isEmpty() || item.citationIds().size() > 3) {
            throw new IllegalArgumentException("concept comparison citations are invalid");
        }
        List<UUID> citations = item.citationIds().stream().distinct().toList();
        if (citations.size() != item.citationIds().size()
                || !availableEvidence.containsAll(citations) || !answerCitations.containsAll(citations)) {
            throw new IllegalArgumentException("concept comparison cites evidence outside the answer scope");
        }
        Set<String> supportedNumbers = modelRequest.evidence().stream()
                .filter(evidence -> citations.contains(evidence.chunkId()))
                .flatMap(evidence -> numericTokens(evidence.excerpt()).stream())
                .collect(Collectors.toUnmodifiableSet());
        String citedEvidence = modelRequest.evidence().stream()
                .filter(evidence -> citations.contains(evidence.chunkId()))
                .map(EvidenceInput::excerpt)
                .collect(Collectors.joining(" "));
        Set<String> claimedNumbers = numericTokens(item.leftDefinition() + " " + item.rightDefinition() + " "
                + item.commonGround() + " " + item.keyDifference() + " " + item.practicalBoundary());
        if (!supportedNumbers.containsAll(claimedNumbers)) {
            throw new IllegalArgumentException("concept comparison introduced an unsupported numeric rule");
        }
        validateEvidenceDrivenBoundary(item, basis, citedEvidence);
        return new RuleConceptComparison(
                item.leftConcept(), item.leftDefinition(), item.rightConcept(), item.rightDefinition(),
                item.commonGround(), item.keyDifference(), item.practicalBoundary(), basis, citations);
    }

    private void validateEvidenceDrivenBoundary(
            RuleConceptComparisonRequest item, ConceptComparisonBasis basis, String citedEvidence) {
        String comparisonCard = String.join(" ", item.leftDefinition(), item.rightDefinition(),
                item.keyDifference(), item.practicalBoundary());
        if (basis == ConceptComparisonBasis.STORAGE_STATUS
                && Pattern.compile("(?iu)\\breturn(?:ed|s|ing)?\\b|归还").matcher(citedEvidence).find()
                && !Pattern.compile("(?iu)\\breturn(?:ed|s|ing)?\\b|归还").matcher(comparisonCard).find()) {
            throw new IllegalArgumentException("storage comparison omitted the cited return consequence");
        }
        if (basis == ConceptComparisonBasis.RESOURCE_FUNCTION
                && Pattern.compile("(?iu)\\b(?:victory point|vp|end of the game|end-game)\\b|胜利分|终局")
                        .matcher(citedEvidence).find()
                && !Pattern.compile("(?iu)\\b(?:victory point|vp|score|scoring|end-game)\\b|胜利分|计分|终局")
                        .matcher(item.practicalBoundary()).find()) {
            throw new IllegalArgumentException("resource comparison omitted the cited scoring boundary");
        }
    }

    private void validateBasis(RuleConceptComparisonRequest item, ConceptComparisonBasis basis) {
        String all = String.join(" ", item.leftConcept(), item.leftDefinition(), item.rightConcept(),
                item.rightDefinition(), item.keyDifference(), item.practicalBoundary());
        String concepts = item.leftConcept() + " " + item.rightConcept();
        if (basis != ConceptComparisonBasis.ACTION_WINDOW
                && Pattern.compile("(?iu)\\b(?:turn|phase|window)\\b|回合|阶段|时机").matcher(concepts).find()) {
            throw new IllegalArgumentException("turn or phase concepts require ACTION_WINDOW basis");
        }
        if (basis != ConceptComparisonBasis.STORAGE_STATUS && STORAGE_STATUS.matcher(concepts).find()) {
            throw new IllegalArgumentException("stock or retention concepts require STORAGE_STATUS basis");
        }
        if (basis == ConceptComparisonBasis.DEFINITION_BOUNDARY
                && Pattern.compile("(?iu)\\b(?:spend|pay|currency|token|resource|victory point|vp)\\b|花费|支付|标记|资源|胜利分")
                        .matcher(all).find()) {
            throw new IllegalArgumentException("distinct resource uses require RESOURCE_FUNCTION basis");
        }
        if (basis == ConceptComparisonBasis.DEFINITION_BOUNDARY && STORAGE_STATUS.matcher(all).find()) {
            throw new IllegalArgumentException("distinct retention rules require STORAGE_STATUS basis");
        }
        switch (basis) {
            case ACTION_WINDOW -> requirePattern(ACTION_WINDOW, all, "action-window comparison lacks timing semantics");
            case RESOURCE_FUNCTION -> requirePattern(
                    RESOURCE_FUNCTION, all, "resource-function comparison lacks distinct uses");
            case STORAGE_STATUS -> requirePattern(STORAGE_STATUS, all, "storage-status comparison lacks retention rules");
            case RULE_SCOPE -> {
                if (!Pattern.compile("(?iu)\\b(?:when|only|if|during|applies?|actor|object|condition|scope|within)\\b|"
                                + "当|仅|如果|期间|适用|角色|对象|条件|范围")
                        .matcher(all).find()) {
                    throw new IllegalArgumentException("rule-scope comparison lacks an explicit applicability boundary");
                }
            }
            case DEFINITION_BOUNDARY -> {
                if (!item.practicalBoundary().toLowerCase(Locale.ROOT).matches(".*(?:when|only|cannot|can|if|during).*")) {
                    throw new IllegalArgumentException("definition-boundary comparison lacks a practical boundary");
                }
            }
        }
    }

    private void requireNamedByPlayer(String concept, String question) {
        List<String> explicitlyNamed = explicitlyNamedConcepts(question);
        if (!explicitlyNamed.isEmpty()) {
            String candidate = canonicalConceptName(concept);
            if (explicitlyNamed.stream().noneMatch(candidate::equals)) {
                throw new IllegalArgumentException(
                        "compared concept is not named with its complete name from the current question");
            }
            return;
        }
        Set<String> questionTerms = significantTerms(question);
        if (significantTerms(concept).stream().noneMatch(questionTerms::contains)) {
            throw new IllegalArgumentException("compared concept was not named in the current question");
        }
    }

    private List<String> explicitlyNamedConcepts(String question) {
        if (question == null) return List.of();
        for (Pattern pattern : List.of(BETWEEN_CONCEPTS, DIFFERENT_FROM_CONCEPTS)) {
            var matcher = pattern.matcher(question.strip());
            if (matcher.find()) return List.of(
                    canonicalConceptName(matcher.group(1)), canonicalConceptName(matcher.group(2)));
        }
        return List.of();
    }

    private String canonicalConceptName(String value) {
        return normalized(value).replaceFirst("^(?:a|an|the)\\s+", "");
    }

    private Set<String> significantTerms(String value) {
        return Arrays.stream(normalized(value).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 3)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> numericTokens(String value) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?![\\p{L}\\p{N}])")
                .matcher(value == null ? "" : value)
                .results()
                .map(result -> result.group())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void requirePattern(Pattern pattern, String value, String message) {
        if (!pattern.matcher(value).find()) throw new IllegalArgumentException(message);
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("concept comparison " + field + " is invalid");
        }
    }
}
