package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Application-owned schema, selection, and citation bounds for one model-selected answer aid. */
final class AnswerStructuredAidPolicy {

    private AnswerStructuredAidPolicy() {}

    static boolean required(ModelRequest request, AnswerAid aid) {
        return request != null && request.answerAid() == aid;
    }

    static void validateSelection(ModelRequest request, AnswerAid aid, boolean empty, String label) {
        boolean required = required(request, aid);
        // Most answer aids are presentation enhancements, not evidence and not part of the player-facing core. The
        // planner may select one that the composing model does not need after seeing the excerpts, so omission must
        // not invalidate an otherwise cited answer. Calculation is different: it is a recomputable factual result and
        // remains part of the hard answer contract when selected.
        if (required && aid == AnswerAid.CALCULATION && empty) {
            throw new IllegalArgumentException(label + " are required by the answer plan");
        }
        if (!required && !empty) throw new IllegalArgumentException(label + " was not selected by the answer plan");
    }

    static List<UUID> citations(
            ModelRequest request, ModelDraft draft, List<UUID> proposed, String label) {
        if (proposed == null || proposed.isEmpty() || proposed.size() > 3) {
            throw new IllegalArgumentException(label + " citations are invalid");
        }
        List<UUID> citations = proposed.stream().distinct().toList();
        Set<UUID> available = request.evidence().stream()
                .map(EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> answerCitations = Set.copyOf(draft.citationIds());
        if (citations.size() != proposed.size()
                || citations.contains(null)
                || !available.containsAll(citations)
                || !answerCitations.containsAll(citations)) {
            throw new IllegalArgumentException(label + " cites evidence outside the answer scope");
        }
        return citations;
    }

    static String requiredText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value.strip();
    }

    static String optionalText(String value, int maximum, String label) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > maximum) throw new IllegalArgumentException(label + " is too long");
        return value.strip();
    }

    static <T extends Enum<T>> T enumValue(String value, Class<T> type, String label) {
        try {
            return T.valueOf(type, requiredText(value, 80, label).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(label + " is invalid", invalid);
        }
    }

    static String identityKey(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
