package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.SituationCheckRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates model-proposed rule requirements against cited evidence and literal facts in the current question. */
final class AnswerSituationCheckResolver {

    private static final int MAX_CHECKS = 6;
    private static final Pattern ELIGIBILITY_QUESTION = Pattern.compile(
            "(?iu)\\b(?:can|may|am|are)\\s+(?:i|we)\\b|\\b(?:allowed|eligible)\\b|"
                    + "能否|能不能|是否可以|是否能|现在.{0,24}(?:能|可以)|(?:能|可以).{0,24}吗");
    private static final Pattern CONDITIONAL_OR_UNCERTAIN = Pattern.compile(
            "(?iu)\\b(?:if|depends|provided|need|cannot determine|can't determine|not enough)\\b|"
                    + "如果|若|取决于|需要|请确认|还不能确定|无法确定|信息不足|尚未提供|未说明");

    List<RuleSituationCheck> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("situation check input is invalid");
        }
        boolean required = requiresChecks(request);
        if (request.questionType() != QuestionType.SITUATION_QUERY) {
            if (!draft.situationChecks().isEmpty()) {
                throw new IllegalArgumentException("situation checks are only valid for situation questions");
            }
            return List.of();
        }
        if (draft.situationChecks().isEmpty()) {
            if (required) throw new IllegalArgumentException("situation answer omitted decisive state checks");
            return List.of();
        }
        if (draft.situationChecks().size() > MAX_CHECKS) {
            throw new IllegalArgumentException("too many situation checks");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        LinkedHashSet<String> requirements = new LinkedHashSet<>();
        List<RuleSituationCheck> resolved = draft.situationChecks().stream()
                .map(check -> resolveOne(request.question(), check, availableEvidence, answerCitations))
                .peek(check -> {
                    if (!requirements.add(check.requirement().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("duplicate situation requirement");
                    }
                })
                .toList();
        if (resolved.stream().anyMatch(check -> check.status() == SituationCheckStatus.NOT_PROVIDED)
                && !CONDITIONAL_OR_UNCERTAIN.matcher(playerFacingText(draft)).find()) {
            throw new IllegalArgumentException("missing player state requires a conditional answer");
        }
        return resolved;
    }

    boolean requiresChecks(ModelRequest request) {
        return request != null
                && request.questionType() == QuestionType.SITUATION_QUERY
                && ELIGIBILITY_QUESTION.matcher(request.question()).find();
    }

    private RuleSituationCheck resolveOne(
            String question,
            SituationCheckRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null || request.requirement() == null || request.requirement().isBlank()
                || request.requirement().length() > 240 || request.status() == null
                || request.playerFact() == null || request.playerFact().length() > 240
                || request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("situation check request is invalid");
        }
        SituationCheckStatus status;
        try {
            status = SituationCheckStatus.valueOf(request.status().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidStatus) {
            throw new IllegalArgumentException("situation check status is invalid", invalidStatus);
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("situation check cites evidence outside the answer scope");
        }
        String playerFact = request.playerFact().strip();
        if (status == SituationCheckStatus.NOT_PROVIDED) {
            if (!playerFact.isEmpty()) {
                throw new IllegalArgumentException("missing situation fact must be empty");
            }
        } else if (playerFact.isEmpty() || !normalized(question).contains(normalized(playerFact))) {
            throw new IllegalArgumentException("situation fact is not literal current-question input");
        }
        return new RuleSituationCheck(request.requirement(), status, playerFact, citationIds);
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private String playerFacingText(ModelDraft draft) {
        return (draft.shortVerdict() == null ? "" : draft.shortVerdict()) + "\n"
                + (draft.explanation() == null ? "" : draft.explanation());
    }
}
