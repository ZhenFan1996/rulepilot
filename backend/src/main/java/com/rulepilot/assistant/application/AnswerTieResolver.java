package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTieRequest;
import com.rulepilot.assistant.domain.RuleTieResolution;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Accepts a tie ruling only when every ordered step and its terminal outcome remain cited. */
final class AnswerTieResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerTieResolver.class);
    private static final int MAX_RESOLUTIONS = 3;
    private static final Pattern TIE_REQUEST = Pattern.compile(
            "(?iu)\\b(?:tie|tied|tiebreak|tie-break|same score|equal score|same strength|"
                    + "(?:game|result|match) (?:is|ends? in) a draw|ends? in a draw|a draw between)\\b|"
                    + "平局|打平|同分|分数相同|战力相同");
    private static final Pattern RANK = Pattern.compile(
            "(?iu)\\b(?:first|second|third|rank|place)\\b|第一|第二|第三|名次");
    private static final Pattern REWARD = Pattern.compile("(?iu)\\b(?:reward|receive|gain|nothing)\\b|奖励|获得|没有奖励");
    private static final Pattern POSITIONAL = Pattern.compile(
            "(?iu)\\b(?:closest|nearest|starting player|first player|player order|turn order)\\b|"
                    + "最接近|起始玩家|首位玩家|玩家顺序|回合顺序");

    List<RuleTieResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("tie input is invalid");
        if (draft.tieResolutions().isEmpty()) {
            if (asksForTieResolution(request.question())) {
                throw new IllegalArgumentException("tie answer omitted cited resolution steps");
            }
            return List.of();
        }
        if (draft.tieResolutions().size() > MAX_RESOLUTIONS) {
            throw new IllegalArgumentException("too many tie resolutions");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        return draft.tieResolutions().stream()
                .map(item -> {
                    try {
                        return resolveOne(item, availableEvidence, answerCitations);
                    } catch (IllegalArgumentException exception) {
                        LOGGER.warn(
                                "Tie resolution rejected safely ({}; basis={}; steps={})",
                                exception.getMessage(),
                                item == null ? "null" : item.basis(),
                                item == null || item.resolutionSteps() == null ? 0 : item.resolutionSteps().size());
                        throw exception;
                    }
                })
                .toList();
    }

    static boolean asksForTieResolution(String question) {
        return question != null && TIE_REQUEST.matcher(question).find();
    }

    private RuleTieResolution resolveOne(
            RuleTieRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("tie item is null");
        bounded(request.tieContext(), 500, "context");
        if (request.resolutionSteps() == null || request.resolutionSteps().isEmpty()
                || request.resolutionSteps().size() > 6) {
            throw new IllegalArgumentException("tie steps are invalid");
        }
        request.resolutionSteps().forEach(step -> boundedSingleLine(step, 500));
        bounded(request.finalOutcome(), 500, "final outcome");
        TieResolutionBasis basis;
        try {
            basis = TieResolutionBasis.valueOf(request.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("tie basis is invalid", invalidBasis);
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("tie citations are invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("tie resolution cites evidence outside the answer scope");
        }
        validateBasisMeaning(request, basis);
        return new RuleTieResolution(
                request.tieContext(), request.resolutionSteps(), request.finalOutcome(), basis, citationIds);
    }

    private void validateBasisMeaning(RuleTieRequest request, TieResolutionBasis basis) {
        String all = request.tieContext() + " " + String.join(" ", request.resolutionSteps()) + " "
                + request.finalOutcome();
        switch (basis) {
            case SINGLE_TIEBREAKER -> {
                // One cited criterion may still require several player-facing actions: identify the tie,
                // compare the stated value or position, then apply the winner outcome.
            }
            case ORDERED_TIEBREAKERS -> {
                if (request.resolutionSteps().size() < 2) {
                    throw new IllegalArgumentException("ordered tie-breakers require at least two explicit steps");
                }
            }
            case RANK_REWARD_SHIFT -> {
                if (!RANK.matcher(all).find() || !REWARD.matcher(all).find()) {
                    throw new IllegalArgumentException("rank-shift tie fields do not preserve rank and reward outcomes");
                }
            }
            case POSITIONAL_PRIORITY -> {
                if (!POSITIONAL.matcher(all).find()) {
                    throw new IllegalArgumentException("positional tie outcome does not name its explicit priority");
                }
                boolean positionAppearsInSteps = request.resolutionSteps().stream()
                        .anyMatch(step -> POSITIONAL.matcher(step).find());
                if (positionAppearsInSteps && request.resolutionSteps().size() != 1) {
                    throw new IllegalArgumentException("positional fallback must be the final outcome, not a duplicated step");
                }
            }
        }
    }

    private void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("tie " + field + " is invalid");
        }
    }

    private void boundedSingleLine(String value, int maximum) {
        bounded(value, maximum, "step");
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("tie step must be a single ordered item");
        }
    }
}
