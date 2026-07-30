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
                        located -> groupingIdentity(located.icon()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<IconGroup> groups = new ArrayList<>();
        Set<String> conflictingKeys = new LinkedHashSet<>();
        byVisualIdentity.forEach((groupKey, occurrences) -> {
            List<LocatedIcon> publishable = occurrences.stream()
                    .filter(located -> VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                            located.icon(),
                            located.icon().x(),
                            located.icon().y(),
                            located.icon().width(),
                            located.icon().height()))
                    .toList();
            if (publishable.isEmpty()) return;
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
                groups.add(group(documentVersionId, groupKey, "", publishable.getFirst(), publishable));
                return;
            }
            if (explicitDefinitions.size() == 1) {
                String definition = explicitDefinitions.keySet().iterator().next();
                groups.add(group(
                        documentVersionId,
                        groupKey,
                        definition,
                        explicitDefinitions.values().iterator().next().getFirst(),
                        publishable));
                return;
            }

            conflictingKeys.add(groupKey);
            explicitDefinitions.forEach((definition, matching) -> {
                List<LocatedIcon> visibleMatching = matching.stream().filter(publishable::contains).toList();
                if (!visibleMatching.isEmpty()) {
                    groups.add(group(
                            documentVersionId,
                            groupKey,
                            definition,
                            matching.getFirst(),
                            visibleMatching));
                }
            });
            List<LocatedIcon> visibleUnexplained = unexplained.stream().filter(publishable::contains).toList();
            if (!visibleUnexplained.isEmpty()) {
                groups.add(group(
                        documentVersionId,
                        groupKey,
                        "",
                        visibleUnexplained.getFirst(),
                        visibleUnexplained));
            }
        });

        groups.sort(Comparator.comparing(IconGroup::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(group -> group.occurrences().getFirst().pageNumber()));
        return new GlossaryProjection(List.copyOf(groups), Set.copyOf(conflictingKeys));
    }

    private static IconGroup group(
            UUID documentVersionId,
            String groupKey,
            String normalizedDefinition,
            LocatedIcon definitionSource,
            List<LocatedIcon> visibleOccurrences) {
        LocatedIcon cropSource = visibleOccurrences.stream()
                .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.EXPLICIT)
                .findFirst()
                .orElse(visibleOccurrences.getFirst());
        IconOccurrence definition = definitionSource.icon();
        List<OccurrenceView> occurrenceViews = visibleOccurrences.stream()
                .sorted(Comparator.comparingInt(LocatedIcon::pageNumber)
                        .thenComparingInt(value -> value.icon().y())
                        .thenComparingInt(value -> value.icon().x()))
                .map(located -> occurrence(documentVersionId, located))
                .toList();
        String identity = groupKey + "|" + normalizedDefinition;
        UUID entryId = stableId(documentVersionId + "|entry|" + identity);
        return new IconGroup(
                entryId,
                definition.name(),
                cropSource.icon().visualDescription(),
                normalizedDefinition.isBlank() ? null : definition.explanation(),
                normalizedDefinition.isBlank() ? null : definition.evidenceText(),
                normalizedDefinition.isBlank() ? IconMeaningStatus.UNEXPLAINED : IconMeaningStatus.EXPLICIT,
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

    private static String groupingIdentity(IconOccurrence icon) {
        String proposedIdentity = semanticIdentity(icon.groupKey());
        String verifiedLabel = semanticIdentity(icon.verifiedVisualLabel());
        return !verifiedLabel.isBlank() && verifiedLabel.equals(proposedIdentity)
                ? verifiedLabel
                : normalized(icon.groupKey());
    }

    private static String semanticIdentity(String value) {
        return normalized(value == null ? "" : value)
                .replaceAll("(?iu)\\b(?:icon|icons|symbol|symbols|mark|marker|pictogram|silhouette|shape)\\b", " ")
                .replace("图标", "")
                .replace("符号", "")
                .replace("标记", "")
                .replace("轮廓", "")
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
