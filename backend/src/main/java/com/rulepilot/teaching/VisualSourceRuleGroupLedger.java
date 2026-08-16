package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Extracts the bounded, page-owned rule-group identifiers already admitted by a visual page catalog. */
public final class VisualSourceRuleGroupLedger {

    private static final int PREFERRED_IDENTIFIERS_PER_PAGE = 8;
    private static final int MAX_PRINTED_TERMS_PER_PAGE = 12;
    private static final int MAX_IDENTIFIER_CHARACTERS = 160;

    private VisualSourceRuleGroupLedger() {}

    public static List<String> identifiers(PageInput page) {
        if (page.sourceRuleGroupInventoryComplete()) {
            return page.sourceRuleGroupIdentifiers();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        printedTerms(page.text()).stream()
                .filter(value -> !describesSourceDependency(page, value))
                .map(VisualSourceRuleGroupLedger::boundedIdentifier)
                .forEach(identifiers::add);
        if (identifiers.size() < PREFERRED_IDENTIFIERS_PER_PAGE) {
            visibleFacts(page.text()).stream()
                    .filter(value -> !describesSourceDependency(page, value))
                    .map(VisualSourceRuleGroupLedger::boundedIdentifier)
                    .limit(PREFERRED_IDENTIFIERS_PER_PAGE - identifiers.size())
                    .forEach(identifiers::add);
        }
        return identifiers.stream().filter(value -> !value.isBlank()).toList();
    }

    public static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * A completed page ledger is meaningful only when every page-owned identifier has one exact, non-empty fact.
     * A verified non-gameplay page may own an empty inventory. The relationship is normalized rather than
     * translated: document-local vocabulary remains the authority.
     */
    public static boolean hasExactFactBindings(List<String> identifiers, String factualSummary) {
        if (identifiers == null || factualSummary == null) return false;
        return identifiers.stream().allMatch(identifier -> hasExactFactBinding(identifier, factualSummary));
    }

    public static boolean hasExactFactBinding(String identifier, String factualSummary) {
        String normalizedIdentifier = identity(identifier);
        if (normalizedIdentifier.isBlank() || factualSummary == null) return false;
        String prefix = normalizedIdentifier + ":";
        return factualSummary.lines()
                .map(VisualSourceRuleGroupLedger::identity)
                .anyMatch(fact -> fact.startsWith(prefix)
                        && !fact.substring(prefix.length()).isBlank());
    }

    /**
     * Verifies the durable page-input contract without inferring identifiers from display text. A completed
     * non-gameplay page may own an empty identifier inventory, but it still needs a non-empty factual observation.
     */
    public static boolean hasCompleteExactFactLedger(PageInput page) {
        if (page == null || !page.sourceRuleGroupInventoryComplete()) return false;
        String factualSummary = catalogFieldBody(page.text(), "Visible facts:");
        return !factualSummary.isBlank()
                && hasExactFactBindings(page.sourceRuleGroupIdentifiers(), factualSummary);
    }

    private static boolean describesSourceDependency(PageInput page, String value) {
        String normalized = identity(value);
        return page.sourceDependencies().stream()
                .map(SourceDependency::title)
                .map(VisualSourceRuleGroupLedger::identity)
                .anyMatch(normalized::contains);
    }

    private static List<String> printedTerms(String text) {
        return fieldValues(text, "Printed terms:", ";", MAX_PRINTED_TERMS_PER_PAGE);
    }

    private static List<String> visibleFacts(String text) {
        return fieldValues(text, "Visible facts:", "\n", PREFERRED_IDENTIFIERS_PER_PAGE);
    }

    private static List<String> fieldValues(String text, String marker, String separator, int maximum) {
        List<String> values = new ArrayList<>();
        boolean reading = false;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine;
            if (!reading) {
                if (!line.startsWith(marker)) continue;
                reading = true;
                line = line.substring(marker.length());
            } else if (isCatalogField(line)) {
                break;
            }
            Arrays.stream(line.split(separator))
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .limit(maximum - values.size())
                    .forEach(values::add);
            if (values.size() == maximum) break;
        }
        return List.copyOf(values);
    }

    private static String catalogFieldBody(String text, String marker) {
        if (text == null || text.isBlank()) return "";
        int markerStart = text.indexOf(marker);
        if (markerStart < 0) return "";
        int valueStart = markerStart + marker.length();
        int valueEnd = text.length();
        for (String nextField : List.of("\nPrinted terms:", "\nVisible facts:", "\nKeywords:")) {
            int candidate = text.indexOf(nextField, valueStart);
            if (candidate >= 0 && candidate < valueEnd) valueEnd = candidate;
        }
        return text.substring(valueStart, valueEnd).strip();
    }

    private static boolean isCatalogField(String line) {
        return line.startsWith("Printed terms:")
                || line.startsWith("Visible facts:")
                || line.startsWith("Keywords:");
    }

    private static String boundedIdentifier(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_IDENTIFIER_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_IDENTIFIER_CHARACTERS);
    }
}
