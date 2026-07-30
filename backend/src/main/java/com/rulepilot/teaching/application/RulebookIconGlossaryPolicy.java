package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds a conservative quick-reference glossary without asking production code to understand game-local symbols. */
final class RulebookIconGlossaryPolicy {

    private RulebookIconGlossaryPolicy() {}

    static GlossaryProjection project(UUID documentVersionId, List<PageFact> pageFacts) {
        Map<String, List<LocatedIcon>> byVisualIdentity = pageFacts.stream()
                .flatMap(page -> page.iconOccurrences().stream()
                        .map(icon -> new LocatedIcon(page.pageNumber(), icon)))
                .collect(Collectors.groupingBy(
                        located -> normalized(located.icon().groupKey()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<IconGroup> groups = new ArrayList<>();
        Set<String> conflictingKeys = new LinkedHashSet<>();
        byVisualIdentity.forEach((groupKey, occurrences) -> {
            Map<String, List<LocatedIcon>> explicitDefinitions = occurrences.stream()
                    .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.EXPLICIT)
                    .collect(Collectors.groupingBy(
                            located -> normalized(located.icon().explanation()),
                            LinkedHashMap::new,
                            Collectors.toList()));
            List<LocatedIcon> unexplained = occurrences.stream()
                    .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.UNEXPLAINED)
                    .toList();

            if (explicitDefinitions.isEmpty()) {
                groups.add(group(documentVersionId, groupKey, "", occurrences));
                return;
            }
            if (explicitDefinitions.size() == 1) {
                String definition = explicitDefinitions.keySet().iterator().next();
                groups.add(group(documentVersionId, groupKey, definition, occurrences));
                return;
            }

            conflictingKeys.add(groupKey);
            explicitDefinitions.forEach((definition, matching) ->
                    groups.add(group(documentVersionId, groupKey, definition, matching)));
            if (!unexplained.isEmpty()) groups.add(group(documentVersionId, groupKey, "", unexplained));
        });

        groups.sort(Comparator.comparing(IconGroup::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(group -> group.occurrences().getFirst().pageNumber()));
        return new GlossaryProjection(List.copyOf(groups), Set.copyOf(conflictingKeys));
    }

    private static IconGroup group(
            UUID documentVersionId, String groupKey, String normalizedDefinition, List<LocatedIcon> occurrences) {
        LocatedIcon representative = occurrences.stream()
                .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.EXPLICIT)
                .findFirst()
                .orElse(occurrences.getFirst());
        IconOccurrence icon = representative.icon();
        List<OccurrenceView> occurrenceViews = occurrences.stream()
                .sorted(Comparator.comparingInt(LocatedIcon::pageNumber)
                        .thenComparingInt(value -> value.icon().y())
                        .thenComparingInt(value -> value.icon().x()))
                .map(located -> occurrence(documentVersionId, located))
                .toList();
        String identity = groupKey + "|" + normalizedDefinition;
        UUID entryId = stableId(documentVersionId + "|entry|" + identity);
        return new IconGroup(
                entryId,
                icon.name(),
                icon.visualDescription(),
                icon.meaningStatus() == IconMeaningStatus.EXPLICIT ? icon.explanation() : null,
                icon.meaningStatus() == IconMeaningStatus.EXPLICIT ? icon.evidenceText() : null,
                icon.meaningStatus(),
                occurrenceViews.getFirst().id(),
                occurrenceViews);
    }

    private static OccurrenceView occurrence(UUID documentVersionId, LocatedIcon located) {
        IconOccurrence icon = located.icon();
        UUID id = stableId(documentVersionId + "|occurrence|" + located.pageNumber() + "|"
                + icon.x() + "|" + icon.y() + "|" + icon.width() + "|" + icon.height() + "|"
                + normalized(icon.groupKey()));
        return new OccurrenceView(
                id, located.pageNumber(), icon.x(), icon.y(), icon.width(), icon.height());
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalized(String value) {
        return value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Zs}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    record GlossaryProjection(List<IconGroup> groups, Set<String> conflictingGroupKeys) {}

    record IconGroup(
            UUID id,
            String name,
            String visualDescription,
            String explanation,
            String evidenceText,
            IconMeaningStatus meaningStatus,
            UUID representativeOccurrenceId,
            List<OccurrenceView> occurrences) {}

    record OccurrenceView(UUID id, int pageNumber, int x, int y, int width, int height) {}

    private record LocatedIcon(int pageNumber, IconOccurrence icon) {}
}
