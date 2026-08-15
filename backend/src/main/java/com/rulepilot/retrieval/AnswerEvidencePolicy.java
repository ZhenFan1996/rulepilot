package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Mechanical evidence identity and locator rules shared by answer retrieval adapters. */
public final class AnswerEvidencePolicy {

    /** Printed catalogue identifiers are document coordinates, not inferred game vocabulary. */
    private static final Pattern PRINTED_IDENTIFIER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])[\\p{L}]{1,4}\\s*[#_-]\\s*\\d{1,4}(?![\\p{L}\\p{N}])");
    private static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";

    private AnswerEvidencePolicy() {}

    static List<String> printedIdentifiers(String question) {
        if (question == null || question.isBlank()) return List.of();
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        var matcher = PRINTED_IDENTIFIER.matcher(question);
        while (matcher.find() && identifiers.size() < 24) {
            identifiers.add(matcher.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        }
        return List.copyOf(identifiers);
    }

    public static boolean isVisualPlaceholder(HybridEvidenceHit hit) {
        return hit != null && isVisualPlaceholder(hit.evidence());
    }

    public static boolean isVisualPlaceholder(RuleEvidenceHit hit) {
        return hit != null && VISUAL_PAGE_PLACEHOLDER.equals(hit.excerpt());
    }

    static boolean requiresCrossLanguageExpansion(String question) {
        if (question == null || question.isBlank()) return false;
        return question.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count() >= 2;
    }

    static boolean sameEvidenceSnapshot(HybridEvidenceHit first, HybridEvidenceHit second) {
        var left = first.evidence();
        var right = second.evidence();
        return left.chunkId().equals(right.chunkId())
                && left.documentVersionId().equals(right.documentVersionId())
                && left.sectionType().equals(right.sectionType())
                && left.heading().equals(right.heading())
                && left.excerpt().equals(right.excerpt())
                && left.pageFrom() == right.pageFrom()
                && left.pageTo() == right.pageTo();
    }
}
