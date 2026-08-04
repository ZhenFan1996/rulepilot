package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RulePriorityRequest;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.RulePriorityResolution;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Accepts priority conclusions only as bounded comparisons tied to explicit relationship evidence. */
final class AnswerRulePriorityResolver {

    private static final int MAX_RESOLUTIONS = 3;
    private static final Pattern PRIORITY_REQUEST = Pattern.compile(
            "(?iu)\\b(?:which rule (?:wins|applies|takes precedence)|takes precedence|takes priority|"
                    + "override[sd]?|conflicts?|rule priority|precedence)\\b|"
                    + "哪条规则|哪个规则|谁优先|以谁为准|听谁的|优先级|覆盖|冲突时|冲突了");

    List<RulePriorityResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("rule priority input is invalid");
        if (draft.priorityResolutions().isEmpty()) {
            if (asksForPriority(request.question())) {
                if (!draft.conceptComparisons().isEmpty()) {
                    validateSelfContainedNonConflictVerdict(draft.shortVerdict());
                    return List.of();
                }
                throw new IllegalArgumentException("priority answer omitted cited rule resolutions");
            }
            return List.of();
        }
        if (draft.priorityResolutions().size() > MAX_RESOLUTIONS) {
            throw new IllegalArgumentException("too many rule priority resolutions");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        return draft.priorityResolutions().stream()
                .map(item -> resolveOne(item, availableEvidence, answerCitations))
                .toList();
    }

    static boolean asksForPriority(String question) {
        return question != null && PRIORITY_REQUEST.matcher(question).find();
    }

    private void validateSelfContainedNonConflictVerdict(String verdict) {
        String value = verdict == null ? "" : verdict.strip();
        boolean statesNonConflict = Pattern.compile(
                        "(?iu)\\b(?:(?:do not|don't|does not|doesn't|no)\\s+conflict|not a conflict)\\b|"
                                + "不冲突|没有冲突|并不矛盾|不矛盾")
                .matcher(value).find();
        boolean statesBoundary = Pattern.compile(
                        "(?iu)\\b(?:because|while|whereas|when|if|within|different|only|one|other)\\b|"
                                + "方向|适用|范围|条件|时机|一个|另一个|前者|后者|分别|只有|而")
                .matcher(value).find();
        if (!statesNonConflict || !statesBoundary) {
            throw new IllegalArgumentException(
                    "non-conflict verdict must state both the result and the differing applicability boundary");
        }
    }

    private RulePriorityResolution resolveOne(
            RulePriorityRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("rule priority item is null");
        bounded(request.baseRule(), 500, "base rule");
        bounded(request.competingRule(), 500, "competing rule");
        bounded(request.resolution(), 600, "resolution");
        RulePriorityBasis basis;
        try {
            basis = RulePriorityBasis.valueOf(request.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("rule priority basis is invalid", invalidBasis);
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("rule priority citations are invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("rule priority cites evidence outside the answer scope");
        }
        return new RulePriorityResolution(
                request.baseRule(), request.competingRule(), request.resolution(), basis, citationIds);
    }

    private void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("rule priority " + field + " is invalid");
        }
    }
}
