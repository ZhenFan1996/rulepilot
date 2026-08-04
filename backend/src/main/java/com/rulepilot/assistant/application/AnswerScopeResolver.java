package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates whether a cited conditional rule is safely matched to scope facts stated by the player. */
final class AnswerScopeResolver {

    private static final int MAX_RESOLUTIONS = 3;
    private static final Pattern SCOPE_REQUEST = Pattern.compile(
            "(?iu)\\b(?:does (?:this|that|the rule) apply|only in|only for|player count|"
                    + "[1-9][ -]?players?|one[ -]player|two[ -]player|three[ -]player|four[ -]player|"
                    + "five[ -]player|six[ -]player|solo|co-op|cooperative|team mode|variant|"
                    + "without (?:a |the )?.+ player|no .+ player)\\b|"
                    + "是否适用|适不适用|仅限|[一二三四五六七八九1-9]人局|玩家人数|单人|合作模式|组队模式|变体|没有.+玩家|无.+玩家");
    private static final Pattern MISSING_CONTEXT = Pattern.compile(
            "(?iu)^(?:not provided|unknown|missing|未提供|未知|缺少)$");
    private static final Pattern ROLE_ABSENCE = Pattern.compile(
            "(?iu)\\b(?:without|no|absent|missing)\\b|没有|无|缺少");
    private static final Pattern GAME_MODE = Pattern.compile(
            "(?iu)\\b(?:solo|co-op|cooperative|competitive|team|standard|campaign|mode)\\b|"
                    + "单人|合作|对抗|组队|标准|战役|模式");
    private static final Pattern VARIANT = Pattern.compile("(?iu)\\bvariant\\b|变体");
    private static final Pattern TIE_RANK_REWARD = Pattern.compile(
            "(?iu)\\b(?:tie|tied).{0,120}(?:first|second|third|reward)|"
                    + "(?:first|second|third|reward).{0,120}(?:tie|tied)\\b|"
                    + "平局.{0,120}(?:第一|第二|第三|奖励)|(?:第一|第二|第三|奖励).{0,120}平局");
    private static final Pattern UNIVERSAL_PLAYER_COUNT = Pattern.compile(
            "(?iu)\\b(?:regardless of|irrespective of|at any|for all) player counts?\\b|"
                    + "\\bany number of players\\b|不论玩家人数|任何玩家人数|所有玩家人数");

    List<RuleScopeResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("scope input is invalid");
        if (draft.scopeResolutions().isEmpty()) {
            if (asksForScope(request.question())) {
                throw new IllegalArgumentException("scope answer omitted cited applicability ruling");
            }
            return List.of();
        }
        if (draft.scopeResolutions().size() > MAX_RESOLUTIONS) {
            throw new IllegalArgumentException("too many scope resolutions");
        }
        Set<UUID> availableEvidence = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        List<RuleScopeResolution> resolved = draft.scopeResolutions().stream()
                .map(item -> resolveOne(item, request.question(), availableEvidence, answerCitations))
                .toList();
        if (resolved.stream().anyMatch(item -> item.basis() == ScopeBasis.PLAYER_COUNT_EXCEPTION)
                && UNIVERSAL_PLAYER_COUNT.matcher(draft.shortVerdict() + " " + draft.explanation()).find()) {
            throw new IllegalArgumentException("a specific player-count exception cannot become a universal rule");
        }
        return resolved;
    }

    static boolean asksForScope(String question) {
        return question != null && SCOPE_REQUEST.matcher(question).find();
    }

    private RuleScopeResolution resolveOne(
            RuleScopeRequest request,
            String question,
            Set<UUID> availableEvidence,
            Set<UUID> answerCitations) {
        if (request == null) throw new IllegalArgumentException("scope item is null");
        bounded(request.ruleContext(), 500, "rule context");
        bounded(request.governingCondition(), 500, "governing condition");
        bounded(request.currentSituation(), 300, "current situation");
        bounded(request.effect(), 600, "effect");
        ScopeMatchStatus status;
        ScopeBasis basis;
        try {
            status = ScopeMatchStatus.valueOf(request.matchStatus().toUpperCase(Locale.ROOT));
            basis = ScopeBasis.valueOf(request.basis().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidEnum) {
            throw new IllegalArgumentException("scope status or basis is invalid", invalidEnum);
        }
        if (request.citationIds() == null || request.citationIds().isEmpty()
                || request.citationIds().size() > 3) {
            throw new IllegalArgumentException("scope citations are invalid");
        }
        List<UUID> citations = request.citationIds().stream().distinct().toList();
        if (citations.size() != request.citationIds().size()
                || !availableEvidence.containsAll(citations) || !answerCitations.containsAll(citations)) {
            throw new IllegalArgumentException("scope resolution cites evidence outside the answer scope");
        }
        validateCurrentSituation(question, request.currentSituation(), status, basis);
        validateBasisMeaning(request, basis);
        return new RuleScopeResolution(
                request.ruleContext(), request.governingCondition(), request.currentSituation(), status,
                request.effect(), basis, citations);
    }

    private void validateCurrentSituation(
            String question, String currentSituation, ScopeMatchStatus status, ScopeBasis basis) {
        if (status == ScopeMatchStatus.NEEDS_CONTEXT) {
            if (!MISSING_CONTEXT.matcher(currentSituation.strip()).matches()) {
                throw new IllegalArgumentException("missing scope context must be explicit");
            }
            return;
        }
        if (MISSING_CONTEXT.matcher(currentSituation.strip()).matches()) {
            throw new IllegalArgumentException("scope match cannot assume missing context");
        }
        if (basis == ScopeBasis.PLAYER_COUNT || basis == ScopeBasis.PLAYER_COUNT_EXCEPTION
                || basis == ScopeBasis.VARIANT_SELECTION) {
            Set<Integer> questionCounts = playerCounts(question);
            Set<Integer> situationCounts = playerCounts(currentSituation);
            if (questionCounts.isEmpty() || situationCounts.isEmpty()
                    || java.util.Collections.disjoint(questionCounts, situationCounts)) {
                throw new IllegalArgumentException("player-count scope fact is not grounded in the question");
            }
        } else if (!sharesScopeFact(question, currentSituation)) {
            throw new IllegalArgumentException("scope fact is not grounded in the question");
        }
    }

    private void validateBasisMeaning(RuleScopeRequest request, ScopeBasis basis) {
        String all = request.governingCondition() + " " + request.currentSituation() + " " + request.effect();
        switch (basis) {
            case PLAYER_COUNT -> {
                if (playerCounts(all).isEmpty()) throw new IllegalArgumentException("player-count basis lacks a count");
            }
            case ROLE_PRESENCE -> {
                if (!ROLE_ABSENCE.matcher(all).find()) {
                    throw new IllegalArgumentException("role-presence basis lacks an explicit presence condition");
                }
            }
            case GAME_MODE -> {
                if (!GAME_MODE.matcher(all).find()) throw new IllegalArgumentException("game-mode basis lacks a mode");
            }
            case VARIANT_SELECTION -> {
                if (!VARIANT.matcher(all).find() || playerCounts(all).isEmpty()) {
                    throw new IllegalArgumentException("variant basis lacks its stated player-count condition");
                }
            }
            case PLAYER_COUNT_EXCEPTION -> {
                if (playerCounts(all).isEmpty() || !TIE_RANK_REWARD.matcher(all).find()) {
                    throw new IllegalArgumentException("player-count exception lacks the explicit tied-rank outcome");
                }
                Set<Integer> governingCounts = playerCounts(request.governingCondition());
                Set<Integer> situationCounts = playerCounts(request.currentSituation());
                if (governingCounts.isEmpty() || situationCounts.isEmpty()
                        || governingCounts.stream().noneMatch(count -> !situationCounts.contains(count))) {
                    throw new IllegalArgumentException(
                            "player-count exception must preserve the cited general count and the current count");
                }
            }
        }
    }

    private Set<Integer> playerCounts(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        LinkedHashSet<Integer> counts = new LinkedHashSet<>();
        Matcher digits = Pattern.compile("(?iu)\\b([1-9])[ -]?(?:players?|player game)\\b|([1-9])人局").matcher(normalized);
        while (digits.find()) counts.add(Integer.parseInt(digits.group(1) != null ? digits.group(1) : digits.group(2)));
        List<String> words = List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine");
        for (int index = 0; index < words.size(); index++) {
            if (Pattern.compile("\\b" + words.get(index) + "[ -]?player").matcher(normalized).find()) {
                counts.add(index + 1);
            }
        }
        String chinese = "一二三四五六七八九";
        for (int index = 0; index < chinese.length(); index++) {
            if (normalized.contains(chinese.charAt(index) + "人局")) counts.add(index + 1);
        }
        return Set.copyOf(counts);
    }

    private boolean sharesScopeFact(String question, String situation) {
        Set<String> questionTerms = significantTerms(question);
        return significantTerms(situation).stream().anyMatch(questionTerms::contains)
                && (ROLE_ABSENCE.matcher(question).find() == ROLE_ABSENCE.matcher(situation).find()
                        || GAME_MODE.matcher(question).find() && GAME_MODE.matcher(situation).find());
    }

    private Set<String> significantTerms(String value) {
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 4)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("scope " + field + " is invalid");
        }
    }
}
