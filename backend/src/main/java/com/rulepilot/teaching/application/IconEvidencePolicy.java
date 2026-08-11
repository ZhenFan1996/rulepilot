package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Keeps icon meaning only when its structured evidence is literally present on the source page. */
final class IconEvidencePolicy {

    private IconEvidencePolicy() {}

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons) {
        return icons == null ? List.of() : List.copyOf(icons);
    }

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons, String sourcePageText) {
        String source = identity(sourcePageText);
        return sanitize(icons).stream()
                .map(icon -> supportedBySource(icon, source) ? icon : unexplained(icon))
                .toList();
    }

    static boolean compatibleIdentity(String label, String groupKey) {
        String expected = identity(label);
        return !expected.isBlank() && expected.equals(identity(groupKey));
    }

    private static boolean supportedBySource(IconOccurrence icon, String normalizedSource) {
        if (icon.meaningStatus() == IconMeaningStatus.UNEXPLAINED) return true;
        String evidence = identity(icon.evidenceText());
        if (!evidence.isBlank() && normalizedSource.contains(evidence)) return true;
        return icon.meaningStatus() == IconMeaningStatus.IDENTIFIED
                && !identity(icon.verifiedVisualLabel()).isBlank()
                && identity(icon.verifiedVisualLabel()).equals(evidence);
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

    private static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
