package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.UserModelView;
import com.rulepilot.recommendation.application.RecommendationActions.InvalidAction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates only typed user-evidence identity and projects already persisted profile data. */
final class RecommendationEvidenceReview {

    private final RecommendationReActLoop runtime;

    RecommendationEvidenceReview(ObjectMapper ignored, RecommendationReActLoop runtime) {
        this.runtime = runtime;
    }

    void requireUserEvidence(String evidenceId, ConversationRequest request) {
        if (!preferenceEvidence(request).containsKey(evidenceId)) {
            throw new InvalidAction("USER_EVIDENCE_NOT_GROUNDED");
        }
    }

    void requireCurrentTurnUserEvidence(String evidenceId, ConversationRequest request) {
        requireCurrentTurnUserEvidence(evidenceId, request, "SEARCH_EVIDENCE_NOT_CURRENT");
    }

    void requireCurrentTurnUserEvidence(
            String evidenceId,
            ConversationRequest request,
            String failureCode) {
        List<String> evidenceIds = preferenceEvidence(request).keySet().stream().toList();
        if (evidenceIds.isEmpty() || !evidenceIds.getLast().equals(evidenceId)) {
            throw new InvalidAction(failureCode);
        }
    }

    String evidenceText(String evidenceId, ConversationRequest request) {
        String text = preferenceEvidence(request).get(evidenceId);
        if (text == null) throw new InvalidAction("USER_EVIDENCE_NOT_GROUNDED");
        return text;
    }

    int evidenceTurn(String evidenceId, ConversationRequest request) {
        int turn = 0;
        for (String known : preferenceEvidence(request).keySet()) {
            turn++;
            if (known.equals(evidenceId)) return turn;
        }
        throw new InvalidAction("USER_EVIDENCE_NOT_GROUNDED");
    }

    Map<String, String> preferenceEvidence(ConversationRequest request) {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (DialogueMessage message : request.transcript()) {
            if ("user".equals(message.role())) {
                evidence.put("U" + (evidence.size() + 1), message.text());
            }
        }
        return evidence;
    }

    Map<String, Object> profileForAgent(RecommendationProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("playerCount", profile.playerCount());
        value.put("durationMinutes", profile.durationMinutes());
        value.put("complexity", profile.complexity());
        value.put("type", profile.type());
        value.put("interaction", profile.interaction());
        return value;
    }

    UserModelView userModelView(RecommendationAgentState state, String locale) {
        return new UserModelView(profileSummary(state.profile, locale), List.of());
    }

    private String profileSummary(RecommendationProfile profile, String locale) {
        List<String> values = new ArrayList<>();
        if (profile.playerCount() != null) {
            values.add(integerConstraintText(profile.playerCount(), locale, "人", "players"));
        }
        if (profile.durationMinutes() != null) {
            values.add(integerConstraintText(profile.durationMinutes(), locale, "分钟", "minutes"));
        }
        if (profile.complexity() != null) {
            values.add((runtime.chinese(locale) ? "复杂度 " : "complexity ")
                    + decimalConstraintText(profile.complexity()));
        }
        if (profile.type() != BggGameType.ALL) values.add(profile.type().name());
        if (profile.interaction() != InteractionPreference.ANY) values.add(profile.interaction().name());
        if (values.isEmpty()) return "";
        return (runtime.chinese(locale) ? "已明确记录：" : "Explicitly saved: ")
                + String.join(runtime.chinese(locale) ? "、" : ", ", values);
    }

    private String integerConstraintText(
            ConstraintRange<Integer> range,
            String locale,
            String chineseUnit,
            String englishUnit) {
        boolean chinese = runtime.chinese(locale);
        if (range.minimum() != null && range.maximum() != null) {
            String value = range.minimum().equals(range.maximum())
                    ? range.minimum().toString()
                    : range.minimum() + "–" + range.maximum();
            return value + (chinese ? " " + chineseUnit : " " + englishUnit);
        }
        return range.minimum() != null
                ? (chinese ? "至少 " : "at least ") + range.minimum() + " "
                        + (chinese ? chineseUnit : englishUnit)
                : (chinese ? "最多 " : "up to ") + range.maximum() + " "
                        + (chinese ? chineseUnit : englishUnit);
    }

    private String decimalConstraintText(ConstraintRange<BigDecimal> range) {
        if (range.minimum() != null && range.maximum() != null) {
            if (range.minimum().compareTo(range.maximum()) == 0) {
                return range.minimum().stripTrailingZeros().toPlainString();
            }
            return range.minimum().stripTrailingZeros().toPlainString()
                    + "–"
                    + range.maximum().stripTrailingZeros().toPlainString();
        }
        return range.minimum() != null
                ? "≥ " + range.minimum().stripTrailingZeros().toPlainString()
                : "≤ " + range.maximum().stripTrailingZeros().toPlainString();
    }
}
