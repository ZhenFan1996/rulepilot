package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Verifies source ownership only for explicit quotations copied from supplied evidence. */
final class AnswerCitationCoveragePolicy {

    private static final int MIN_QUOTED_SOURCE_CHARACTERS = 24;

    private AnswerCitationCoveragePolicy() {}

    static List<UUID> missingQuotedSourceIds(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null || request.evidence().isEmpty()) return List.of();
        Set<UUID> cited = Set.copyOf(draft.citationIds());
        LinkedHashSet<UUID> missing = new LinkedHashSet<>();
        for (String playerText : playerFacingCore(draft)) {
            for (String quotation : quotations(playerText)) {
                String normalizedQuote = normalizeWhitespace(quotation);
                if (normalizedQuote.length() < MIN_QUOTED_SOURCE_CHARACTERS) continue;
                List<EvidenceInput> matchingSources = request.evidence().stream()
                        .filter(evidence -> evidence != null && evidence.chunkId() != null
                                && evidence.excerpt() != null
                                && normalizeWhitespace(evidence.excerpt()).contains(normalizedQuote))
                        .toList();
                if (!matchingSources.isEmpty()
                        && matchingSources.stream().noneMatch(source -> cited.contains(source.chunkId()))) {
                    matchingSources.forEach(source -> missing.add(source.chunkId()));
                }
            }
        }
        return List.copyOf(missing);
    }

    static List<String> repairFeedback(ModelRequest request, ModelDraft draft) {
        List<UUID> missing = missingQuotedSourceIds(request, draft);
        if (missing.isEmpty()) return List.of();
        return List.of(
                "CITATION_OWNERSHIP: Player prose directly quotes supplied evidence whose source is absent from "
                        + "citationIds. Preserve all prose and structured details byte-for-byte; return citationIds "
                        + "that retain the existing valid IDs and include the matching supplied source IDs: " + missing);
    }

    private static List<String> playerFacingCore(ModelDraft draft) {
        List<String> values = new ArrayList<>();
        values.add(draft.shortVerdict());
        values.add(draft.explanation());
        values.addAll(draft.exceptions());
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static List<String> quotations(String value) {
        List<String> quotations = new ArrayList<>();
        for (int index = 0; index < value.length(); index++) {
            char close = closingQuote(value.charAt(index));
            if (close == 0) continue;
            int end = value.indexOf(close, index + 1);
            if (end < 0) continue;
            quotations.add(value.substring(index + 1, end));
            index = end;
        }
        return List.copyOf(quotations);
    }

    private static char closingQuote(char open) {
        return switch (open) {
            case '"' -> '"';
            case '“' -> '”';
            case '‘' -> '’';
            case '「' -> '」';
            case '『' -> '』';
            default -> 0;
        };
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            if (Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) normalized.append(' ');
                normalized.append(character);
                pendingSpace = false;
            }
        }
        return normalized.toString().strip().toLowerCase(Locale.ROOT);
    }
}
