package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.util.List;

/** Rejects model-written stand-ins that cannot be an exact visible rulebook quotation. */
final class IconEvidencePolicy {

    private IconEvidencePolicy() {}

    static List<IconOccurrence> sanitize(List<IconOccurrence> icons) {
        return icons.stream().map(IconEvidencePolicy::sanitize).toList();
    }

    private static IconOccurrence sanitize(IconOccurrence icon) {
        if (icon.meaningStatus() != IconMeaningStatus.EXPLICIT || isLiteralEvidence(icon.evidenceText())) {
            return icon;
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

    static boolean isLiteralEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()
                || evidence.indexOf('[') >= 0 || evidence.indexOf(']') >= 0
                || evidence.contains("...") || evidence.indexOf('…') >= 0) {
            return false;
        }
        return evidence.codePoints().noneMatch(codePoint -> Character.getType(codePoint) == Character.OTHER_SYMBOL);
    }
}
