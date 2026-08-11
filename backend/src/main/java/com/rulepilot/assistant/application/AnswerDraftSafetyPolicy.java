package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Mechanical player-facing cleanup; semantic rule claims are reviewed by the evidence-bound Critic. */
final class AnswerDraftSafetyPolicy {

    /** Internal protocol identifiers must never appear in player-facing prose. */
    private static final Pattern INTERNAL_EVIDENCE_REFERENCE = Pattern.compile(
            "(?iu)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}|\\bchunk(?:id)?\\b"
                    + "|(?:证据|引用|evidence|source)\\s*\\[?E\\d+\\]?|\\[E\\d+\\]|\\[[0-9a-f]{8}]");
    private AnswerDraftSafetyPolicy() {}

    static ModelDraft normalizeInternalEvidenceReferences(ModelDraft draft) {
        if (draft == null || !containsInternalEvidenceReference(draft)) return draft;
        return copyWithPlayerText(
                draft,
                removeInternalReferences(draft.shortVerdict()),
                removeInternalReferences(draft.explanation()),
                draft.exceptions().stream().map(AnswerDraftSafetyPolicy::removeInternalReferences).toList());
    }

    static ModelDraft normalizeDanglingPunctuation(ModelDraft draft) {
        if (draft == null) return null;
        return copyWithPlayerText(
                draft,
                normalizePunctuation(draft.shortVerdict()),
                normalizePunctuation(draft.explanation()),
                draft.exceptions().stream().map(AnswerDraftSafetyPolicy::normalizePunctuation).toList());
    }

    /** Compatibility boundary: semantic source-absence claims are no longer deleted from prose. */
    static ModelDraft normalizeSourceAbsenceClaims(ModelRequest request, ModelDraft draft) {
        return draft;
    }

    static boolean containsInternalEvidenceReference(ModelDraft draft) {
        return draft != null && containsInternalEvidenceReference(playerFacingText(draft));
    }

    static boolean containsInternalEvidenceReference(String value) {
        return value != null && INTERNAL_EVIDENCE_REFERENCE.matcher(value).find();
    }

    private static ModelDraft copyWithPlayerText(
            ModelDraft draft, String verdict, String explanation, List<String> exceptions) {
        return new ModelDraft(
                draft.answerable(), draft.insufficiencyReason(), verdict, explanation,
                draft.citationIds(), exceptions, draft.confidence(), draft.answerBasis(),
                draft.calculations(), draft.situationChecks(), draft.walkthroughSteps(), draft.decisionBranches(),
                draft.exceptionClauses(), draft.termDefinitions(), draft.workedExamples(), draft.priorityResolutions(),
                draft.timingResolutions(), draft.tieResolutions(), draft.scopeResolutions(),
                draft.conceptComparisons(), draft.ruleOptions());
    }

    private static String normalizePunctuation(String value) {
        if (value == null || value.isBlank()) return value;
        return value.replaceAll("([（(])\\s*[，,]\\s*", "$1");
    }

    private static String removeInternalReferences(String value) {
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
