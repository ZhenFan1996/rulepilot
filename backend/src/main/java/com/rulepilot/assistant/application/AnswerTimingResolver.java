package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTimingRequest;
import com.rulepilot.assistant.domain.RuleTimingResolution;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Accepts timing-order conclusions only when their exact ordering authority is cited. */
final class AnswerTimingResolver {

    private static final int MAX_RESOLUTIONS = 3;
    private static final Pattern TIMING_ORDER_REQUEST = Pattern.compile(
            "(?iu)\\b(?:same time|simultaneously|which (?:effect|one) (?:goes|happens|resolves) first|"
                    + "what order|resolution order|timing order|who chooses the order|order (?:do|should) .*resolve)\\b|"
                    + "同时触发|同时发生|同时结算|先结算|先处理|哪个先|谁先|什么顺序|顺序由谁|结算顺序|触发顺序");
    private static final Pattern CURRENT_PLAYER_SOURCE = Pattern.compile(
            "(?iu)\\b(?:current player|active player|player taking (?:their |the |the current )?turn|"
                    + "player whose turn|player on (?:their|the) turn)\\b|"
                    + "当前玩家|当前回合.*玩家|正在.*回合.*玩家|进行回合.*玩家");
    private static final Pattern PLAYER_CHOICE = Pattern.compile(
            "(?iu)\\b(?:choose|chooses|chosen|select|selects|selected|decide|decides)\\b|选择|决定");
    private static final Pattern TOP_TO_BOTTOM = Pattern.compile("(?iu)\\btop[ -]to[ -]bottom\\b|自上而下|从上到下");
    private static final Pattern PRINTED_SOURCE = Pattern.compile("(?iu)\\b(?:card|printed|text order)\\b|卡牌|印刷|文字顺序");
    private static final Pattern TURN_ORDER_SOURCE = Pattern.compile("(?iu)\\b(?:normal )?turn order\\b|正常回合顺序|回合顺序");

    List<RuleTimingResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("timing input is invalid");
        if (draft.timingResolutions().isEmpty()) {
            if (asksForTimingOrder(request.question())) {
                throw new IllegalArgumentException("timing answer omitted cited ordering resolution");
            }
            return List.of();
        }
        if (draft.timingResolutions().size() > MAX_RESOLUTIONS) {
            throw new IllegalArgumentException("too many timing resolutions");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        return draft.timingResolutions().stream()
                .map(item -> resolveOne(item, availableEvidence, answerCitations))
                .toList();
    }

    static boolean asksForTimingOrder(String question) {
        return question != null && TIMING_ORDER_REQUEST.matcher(question).find();
    }

    private RuleTimingResolution resolveOne(
            RuleTimingRequest request,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("timing item is null");
        bounded(request.timingContext(), 500, "context");
        bounded(request.resolutionOrder(), 700, "resolution order");
        bounded(request.orderSource(), 400, "order source");
        TimingOrderBasis basis;
        try {
            basis = TimingOrderBasis.valueOf(request.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidBasis) {
            throw new IllegalArgumentException("timing order basis is invalid", invalidBasis);
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("timing citations are invalid");
        }
        List<UUID> citationIds = request.citationIds().stream().distinct().toList();
        if (citationIds.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citationIds)
                || !answerCitations.containsAll(citationIds)) {
            throw new IllegalArgumentException("timing resolution cites evidence outside the answer scope");
        }
        validateBasisMeaning(request, basis);
        return new RuleTimingResolution(
                request.timingContext(), request.resolutionOrder(), request.orderSource(), basis, citationIds);
    }

    private void validateBasisMeaning(RuleTimingRequest request, TimingOrderBasis basis) {
        String order = request.resolutionOrder();
        String source = request.orderSource();
        switch (basis) {
            case CURRENT_PLAYER_CHOOSES -> {
                if (!CURRENT_PLAYER_SOURCE.matcher(source).find() || !PLAYER_CHOICE.matcher(order).find()) {
                    throw new IllegalArgumentException("current-player timing fields do not name the chooser and choice");
                }
            }
            case PRINTED_TOP_TO_BOTTOM -> {
                if (!TOP_TO_BOTTOM.matcher(order).find()
                        || !(PRINTED_SOURCE.matcher(source).find() && TOP_TO_BOTTOM.matcher(source).find())) {
                    throw new IllegalArgumentException("printed-order timing fields do not preserve top-to-bottom order");
                }
            }
            case NORMAL_TURN_ORDER -> {
                if (!TURN_ORDER_SOURCE.matcher(order).find() || !TURN_ORDER_SOURCE.matcher(source).find()) {
                    throw new IllegalArgumentException("turn-order timing fields do not identify normal turn order");
                }
            }
        }
    }

    private void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("timing " + field + " is invalid");
        }
    }
}
