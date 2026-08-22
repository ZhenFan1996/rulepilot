package com.rulepilot.teaching;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import java.util.Arrays;
import java.util.List;

/** Test fixtures for the schema-36 typed visual rule-group contract. */
public final class VisualRuleGroupTestFacts {

    private VisualRuleGroupTestFacts() {}

    public static List<RuleGroupFact> facts(String... identifiers) {
        return facts(List.of(identifiers));
    }

    public static List<RuleGroupFact> facts(List<String> identifiers) {
        return identifiers.stream()
                .map(identifier -> fact(identifier, "Complete page-owned fact for " + identifier + "."))
                .toList();
    }

    public static List<RuleGroupFact> facts(String[] identifiers, String[] values) {
        if (identifiers.length != values.length) {
            throw new IllegalArgumentException("test rule-group identifiers and facts must have equal size");
        }
        return java.util.stream.IntStream.range(0, identifiers.length)
                .mapToObj(index -> fact(identifiers[index], values[index]))
                .toList();
    }

    public static RuleGroupFact fact(String identifier, String value) {
        return new RuleGroupFact(identifier, identifier, value);
    }
}
