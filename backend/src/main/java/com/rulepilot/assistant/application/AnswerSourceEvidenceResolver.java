package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Selects a bounded, direct rule excerpt for players who ask to inspect the source behind an answer. */
final class AnswerSourceEvidenceResolver {

    private static final Pattern SOURCE_REQUEST = Pattern.compile(
            "(?iu)\\b(?:where does (?:the )?(?:rulebook|rule) say|what does (?:the )?rulebook say|"
                    + "exact wording|show me (?:the )?(?:rule|source|evidence)|which page|"
                    + "source for|citation for)\\b|"
                    + "原文怎么说|规则书.{0,12}(?:哪里|哪一页|怎么写)|哪一页|出处(?:在哪|是什么)?|"
                    + "依据是什么|给我看.{0,8}原文|原文依据|这条规则.{0,8}在哪");
    private static final Pattern DIRECT_RULE_LANGUAGE = Pattern.compile(
            "(?iu)\\b(?:may|must|can|cannot|can't|if|when|before|after|each|only|means|is|are|consists?)\\b|"
                    + "可以|必须|不能|如果|当|之前|之后|每|仅|表示|指|是|包括");
    private static final Pattern REDIRECT_ONLY = Pattern.compile(
            "(?iu)\\b(?:see|read|check|consult) (?:the )?(?:excerpt|page|source|citation|rulebook)\\b|"
                    + "(?:请)?(?:查看|参见|阅读)(?:下方|下面|该页|原文|引用)");

    List<UUID> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("source evidence input is invalid");
        }
        if (!requiresSourceEvidence(request) || !draft.answerable()) return List.of();
        if (!"DIRECT_RULE".equalsIgnoreCase(draft.answerBasis())) {
            throw new IllegalArgumentException("source-focused answer must use direct rule evidence");
        }
        List<UUID> citationIds = draft.citationIds();
        if (citationIds.isEmpty() || citationIds.size() > 2
                || citationIds.stream().anyMatch(java.util.Objects::isNull)
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("source-focused answer requires one or two direct citations");
        }
        Set<UUID> available = request.evidence().stream().map(EvidenceInput::chunkId)
                .collect(Collectors.toUnmodifiableSet());
        if (!available.containsAll(citationIds)) {
            throw new IllegalArgumentException("source-focused answer cites evidence outside the answer scope");
        }
        EvidenceInput primary = request.evidence().stream()
                .filter(item -> citationIds.getFirst().equals(item.chunkId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("primary source evidence is unavailable"));
        String excerpt = primary.excerpt() == null ? "" : primary.excerpt().strip();
        if (excerpt.length() < 20 || !DIRECT_RULE_LANGUAGE.matcher(excerpt).find()) {
            throw new IllegalArgumentException("primary source excerpt does not contain a direct rule clause");
        }
        String verdict = draft.shortVerdict() == null ? "" : draft.shortVerdict().strip();
        if (verdict.isBlank() || REDIRECT_ONLY.matcher(verdict).find()) {
            throw new IllegalArgumentException("source-focused verdict must answer instead of redirecting to a page");
        }
        String explanation = draft.explanation() == null ? "" : draft.explanation().strip();
        if (explanation.isBlank() || normalized(explanation).equals(normalized(excerpt))) {
            throw new IllegalArgumentException("source-focused answer must explain the cited clause in player language");
        }
        validateNoNewScopeQualifiers(excerpt, verdict + " " + explanation);
        validateNoNewTemporalBoundary(excerpt, verdict + " " + explanation);
        validateNoNewMandatoryModal(excerpt, verdict + " " + explanation);
        validateOfficialTermNumber(excerpt, verdict + " " + explanation);
        return List.copyOf(citationIds);
    }

    static boolean requiresSourceEvidence(ModelRequest request) {
        return request != null && (request.context().learningIntent() == LearningIntent.SOURCE
                || asksForSource(request.question()));
    }

    static boolean asksForSource(String question) {
        return question != null && SOURCE_REQUEST.matcher(question).find();
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private void validateNoNewScopeQualifiers(String excerpt, String answer) {
        List<Pattern> qualifiers = List.of(
                Pattern.compile("(?iu)\\ball\\b|全部|所有"),
                Pattern.compile("(?iu)\\byou (?:have|own)\\b|\\byour hand\\b|"
                        + "\\bbelong(?:s|ing)? to you\\b|你拥有|你的手牌|属于你"));
        for (Pattern qualifier : qualifiers) {
            if (qualifier.matcher(answer).find() && !qualifier.matcher(excerpt).find()) {
                throw new IllegalArgumentException(
                        "source-focused explanation introduced a scope qualifier absent from the cited clause: "
                                + qualifier.pattern());
            }
        }
    }

    private void validateOfficialTermNumber(String excerpt, String answer) {
        Set<String> capitalizedTerms = Pattern.compile("\\b[A-Z][a-z]{2,}\\b")
                .matcher(excerpt)
                .results()
                .map(result -> result.group())
                .collect(Collectors.toUnmodifiableSet());
        for (String term : capitalizedTerms) {
            Pattern pluralized = Pattern.compile("(?u)\\b" + Pattern.quote(term) + "s\\b");
            if (pluralized.matcher(answer).find() && !pluralized.matcher(excerpt).find()) {
                throw new IllegalArgumentException(
                        "source-focused explanation changed the grammatical number of an official term: " + term);
            }
        }
    }

    private void validateNoNewTemporalBoundary(String excerpt, String answer) {
        Pattern temporalBoundary = Pattern.compile(
                "(?iu)\\b(?:at |by )?(?:the )?end of\\b|\\b(?:the )?completion of\\b|(?:结束时|结束后|完成时)");
        if (temporalBoundary.matcher(answer).find() && !temporalBoundary.matcher(excerpt).find()) {
            throw new IllegalArgumentException(
                    "source-focused explanation introduced an exact temporal boundary absent from the cited clause");
        }
    }

    private void validateNoNewMandatoryModal(String excerpt, String answer) {
        Pattern mandatory = Pattern.compile("(?iu)\\bmust\\b|必须");
        if (mandatory.matcher(answer).find() && !mandatory.matcher(excerpt).find()) {
            throw new IllegalArgumentException(
                    "source-focused explanation introduced a mandatory modal absent from the cited clause");
        }
    }
}
