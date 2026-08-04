package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.DecisionBranchRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates cited condition/outcome branches without elevating rulebook examples into universal rules. */
final class AnswerDecisionTableResolver {

    private static final int MAX_BRANCHES = 6;
    private static final Pattern BRANCH_QUESTION = Pattern.compile(
            "(?iu)\\b(?:what happens if|what if|otherwise|in each case|depending on|for each case)\\b|"
                    + "如果.{0,80}(?:会怎样|会怎么样|怎么办|结果)|要是.{0,80}(?:会怎样|怎么办)|否则|分别(?:会|是)|不同情况|各种情况");

    List<RuleDecisionBranch> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("decision table input is invalid");
        }
        if (draft.decisionBranches().isEmpty()) {
            if (requiresDecisionTable(request)) {
                throw new IllegalArgumentException("branching answer omitted a cited decision table");
            }
            return List.of();
        }
        if (draft.decisionBranches().size() > MAX_BRANCHES) {
            throw new IllegalArgumentException("too many decision branches");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        LinkedHashSet<String> conditions = new LinkedHashSet<>();
        return draft.decisionBranches().stream()
                .map(branch -> resolveOne(branch, availableEvidence, answerCitations))
                .peek(branch -> {
                    if (!conditions.add(branch.condition().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("duplicate decision branch condition");
                    }
                })
                .toList();
    }

    boolean requiresDecisionTable(ModelRequest request) {
        return request != null && asksForBranches(request.question());
    }

    static boolean asksForBranches(String question) {
        return question != null && BRANCH_QUESTION.matcher(question).find();
    }

    private RuleDecisionBranch resolveOne(
            DecisionBranchRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null || request.condition() == null || request.condition().isBlank()
                || request.condition().length() > 300
                || request.outcome() == null || request.outcome().isBlank() || request.outcome().length() > 500
                || request.basis() == null || request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("decision branch request is invalid");
        }
        DecisionBranchBasis basis;
        try {
            basis = DecisionBranchBasis.valueOf(request.basis().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidBasis) {
            throw new IllegalArgumentException("decision branch basis is invalid", invalidBasis);
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("decision branch cites evidence outside the answer scope");
        }
        return new RuleDecisionBranch(request.condition(), request.outcome(), basis, citationIds);
    }
}
