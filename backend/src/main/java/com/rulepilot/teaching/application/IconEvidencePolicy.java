package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Rejects model-written stand-ins that cannot be an exact visible rulebook quotation. */
final class IconEvidencePolicy {

    private static final Pattern IDENTITY_TERM = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("[\"'“‘]([^\"'”’]{1,80})[\"'”’]");
    private static final Pattern EXPLICIT_MAPPING = Pattern.compile(
            "(?i)(?:\\b(?:icon|symbol|mark|marker|pictogram)\\b.{0,48}"
                    + "\\b(?:means?|represents?|indicates?|scores?|gives?|grants?|gain)\\b|"
                    + "\\b(?:means?|represents?|indicates?)\\b.{0,48}\\b(?:icon|symbol|mark|marker|pictogram)\\b|"
                    + "(?:图标|符号|标记).{0,36}(?:表示|代表|意味着|获得|计分|得分))");
    private static final Set<String> GENERIC_VISUAL_TERMS = Set.of(
            "icon", "icons", "symbol", "symbols", "silhouette", "shape", "mark", "marker",
            "illustration", "image", "pictogram", "outline");

    private IconEvidencePolicy() {}

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons) {
        return icons.stream().map(IconEvidencePolicy::sanitize).toList();
    }

    private static IconOccurrence sanitize(IconOccurrence icon) {
        if (icon.meaningStatus() != IconMeaningStatus.EXPLICIT) {
            return icon;
        }
        Optional<String> literalEvidence = literalEvidence(icon.evidenceText(), icon.groupKey());
        if (literalEvidence.isPresent()) {
            String canonicalEvidence = literalEvidence.get();
            if (canonicalEvidence.equals(icon.evidenceText())) return icon;
            return new IconOccurrence(
                    icon.groupKey(),
                    icon.name(),
                    icon.visualDescription(),
                    icon.explanation(),
                    canonicalEvidence,
                    icon.meaningStatus(),
                    icon.x(),
                    icon.y(),
                    icon.width(),
                    icon.height());
        }
        return new IconOccurrence(
                icon.groupKey(),
                icon.name(),
                icon.visualDescription(),
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                icon.x(),
                icon.y(),
                icon.width(),
                icon.height());
    }

    static Optional<String> literalEvidence(String evidence, String groupKey) {
        if (evidence == null || evidence.isBlank()
                || evidence.indexOf('[') >= 0 || evidence.indexOf(']') >= 0
                || evidence.contains("...") || evidence.indexOf('…') >= 0) {
            return Optional.empty();
        }
        if (evidence.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.OTHER_SYMBOL)) {
            return Optional.empty();
        }
        String normalizedEvidence = evidence.toLowerCase(Locale.ROOT);
        var quoted = QUOTED_LITERAL.matcher(evidence);
        while (quoted.find()) {
            String candidate = quoted.group(1).strip();
            if (containsIdentityTerm(candidate.toLowerCase(Locale.ROOT), groupKey) && compactLabel(candidate)) {
                return Optional.of(candidate);
            }
        }
        if (!containsIdentityTerm(normalizedEvidence, groupKey)) return Optional.empty();
        if (compactLabel(evidence) || EXPLICIT_MAPPING.matcher(evidence).find()) return Optional.of(evidence);
        return Optional.empty();
    }

    private static boolean containsIdentityTerm(String normalizedEvidence, String groupKey) {
        var terms = IDENTITY_TERM.matcher(groupKey == null ? "" : groupKey.toLowerCase(Locale.ROOT));
        while (terms.find()) {
            String term = terms.group();
            if (meaningfulIdentityTerm(term) && normalizedEvidence.contains(term)) return true;
        }
        return false;
    }

    private static boolean compactLabel(String evidence) {
        if (evidence.length() > 80) return false;
        var words = IDENTITY_TERM.matcher(evidence);
        int count = 0;
        while (words.find()) {
            count++;
            if (count > 6) return false;
        }
        return count > 0;
    }

    private static boolean meaningfulIdentityTerm(String term) {
        if (GENERIC_VISUAL_TERMS.contains(term)) return false;
        if (CJK.matcher(term).find()) {
            String semantic = term
                    .replace("图标", "")
                    .replace("符号", "")
                    .replace("标记", "")
                    .replace("轮廓", "");
            return semantic.codePointCount(0, semantic.length()) >= 2;
        }
        return term.length() >= 3;
    }
}
