package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private static final Set<String> NEUTRAL_IDENTITY_TERMS = Set.of(
            "card",
            "tile",
            "token",
            "piece",
            "cube",
            "disc",
            "die",
            "marker",
            "category",
            "type",
            "resource",
            "icon",
            "symbol");

    private IconEvidencePolicy() {}

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons) {
        return icons.stream().map(IconEvidencePolicy::sanitize).toList();
    }

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons, String sourcePageText) {
        String normalizedSource = normalizedSourceText(sourcePageText);
        return sanitize(icons).stream()
                .map(icon -> {
                    boolean sourceContainsEvidence =
                            normalizedSource.contains(normalizedSourceText(icon.evidenceText()));
                    if (icon.meaningStatus() == IconMeaningStatus.EXPLICIT && !sourceContainsEvidence) {
                        return unexplained(icon);
                    }
                    if (icon.meaningStatus() == IconMeaningStatus.IDENTIFIED
                            && !sourceContainsEvidence
                            && !independentlyVerifiedVisualEvidence(icon)) {
                        return unexplained(icon);
                    }
                    return icon;
                })
                .toList();
    }

    private static IconOccurrence sanitize(IconOccurrence icon) {
        if (icon.meaningStatus() == IconMeaningStatus.UNEXPLAINED) {
            return compatibleVerifiedLabel(icon).map(label -> identified(icon, label)).orElse(icon);
        }
        if (icon.meaningStatus() == IconMeaningStatus.IDENTIFIED) {
            Optional<String> literalLabel = literalEvidence(icon.evidenceText(), icon.groupKey());
            return literalLabel.map(label -> identified(icon, label)).orElseGet(() -> unexplained(icon));
        }
        Optional<String> literalEvidence = literalEvidence(icon.evidenceText(), icon.groupKey());
        if (literalEvidence.isPresent()) {
            String canonicalEvidence = literalEvidence.get();
            if (equivalentIdentity(canonicalEvidence, icon.groupKey())) {
                return identified(icon, canonicalEvidence);
            }
            if (canonicalEvidence.equals(icon.evidenceText())) return icon;
            return new IconOccurrence(
                    icon.groupKey(),
                    icon.name(),
                    icon.visualDescription(),
                    icon.explanation(),
                    canonicalEvidence,
                    icon.verifiedVisualLabel(),
                    icon.meaningStatus(),
                    icon.x(),
                    icon.y(),
                    icon.width(),
                    icon.height());
        }
        return unexplained(icon);
    }

    private static Optional<String> compatibleVerifiedLabel(IconOccurrence icon) {
        String label = icon.verifiedVisualLabel();
        return label != null && !label.isBlank() && compatibleIdentity(label, icon.groupKey())
                ? Optional.of(label.strip())
                : Optional.empty();
    }

    private static IconOccurrence identified(IconOccurrence icon, String label) {
        return new IconOccurrence(
                icon.groupKey(),
                icon.name(),
                icon.visualDescription(),
                "",
                label,
                icon.verifiedVisualLabel(),
                IconMeaningStatus.IDENTIFIED,
                icon.x(),
                icon.y(),
                icon.width(),
                icon.height());
    }

    private static IconOccurrence unexplained(IconOccurrence icon) {
        return new IconOccurrence(
                icon.groupKey(),
                icon.name(),
                icon.visualDescription(),
                "",
                "",
                icon.verifiedVisualLabel(),
                IconMeaningStatus.UNEXPLAINED,
                icon.x(),
                icon.y(),
                icon.width(),
                icon.height());
    }

    private static String normalizedSourceText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean independentlyVerifiedVisualEvidence(IconOccurrence icon) {
        String label = normalizedSourceText(icon.verifiedVisualLabel());
        return !label.isBlank() && label.equals(normalizedSourceText(icon.evidenceText()));
    }

    static boolean compatibleIdentity(String label, String groupKey) {
        String labelIdentity = normalizedIdentity(label);
        String proposedIdentity = normalizedIdentity(groupKey);
        if (labelIdentity.isBlank() || proposedIdentity.isBlank()) return false;
        if (labelIdentity.equals(proposedIdentity)) return true;
        String proposedWithoutContainers = java.util.Arrays.stream(proposedIdentity.split("\\s+"))
                .filter(term -> !NEUTRAL_IDENTITY_TERMS.contains(term))
                .collect(Collectors.joining(" "));
        return !proposedWithoutContainers.isBlank() && proposedWithoutContainers.equals(labelIdentity);
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
            if (equivalentIdentity(candidate, groupKey)) {
                return Optional.of(candidate);
            }
        }
        if (!containsIdentityTerm(normalizedEvidence, groupKey)) return Optional.empty();
        if (equivalentIdentity(evidence, groupKey) || EXPLICIT_MAPPING.matcher(evidence).find()) {
            return Optional.of(evidence);
        }
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

    private static boolean equivalentIdentity(String evidence, String groupKey) {
        if (evidence.length() > 80) return false;
        String evidenceIdentity = normalizedIdentity(evidence);
        return !evidenceIdentity.isBlank() && evidenceIdentity.equals(normalizedIdentity(groupKey));
    }

    private static String normalizedIdentity(String value) {
        String separated = value == null
                ? ""
                : value.replaceAll("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})", " ");
        var terms = IDENTITY_TERM.matcher(separated.toLowerCase(Locale.ROOT));
        return terms.results()
                .map(result -> semanticIdentityTerm(result.group()))
                .filter(term -> !term.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static String semanticIdentityTerm(String term) {
        if (GENERIC_VISUAL_TERMS.contains(term)) return "";
        if (CJK.matcher(term).find()) {
            return term.replace("图标", "")
                    .replace("符号", "")
                    .replace("标记", "")
                    .replace("轮廓", "");
        }
        return term;
    }

    private static boolean meaningfulIdentityTerm(String term) {
        String semantic = semanticIdentityTerm(term);
        if (semantic.isBlank()) return false;
        return CJK.matcher(semantic).find()
                ? semantic.codePointCount(0, semantic.length()) >= 2
                : semantic.length() >= 3;
    }
}
