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
        List<LocatedIcon> cataloged = pageFacts.stream()
                .flatMap(page -> page.iconOccurrences().stream()
                        .map(icon -> new LocatedIcon(page.pageNumber(), icon)))
                .toList();
        Map<String, List<LocatedIcon>> byVisualIdentity = deduplicateOverlappingOccurrences(cataloged).stream()
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
            List<LocatedIcon> identified = occurrences.stream()
                    .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.IDENTIFIED)
                    .toList();
            List<LocatedIcon> unexplained = occurrences.stream()
                    .filter(located -> located.icon().meaningStatus() == IconMeaningStatus.UNEXPLAINED)
                    .toList();

            if (explicitDefinitions.isEmpty()) {
                if (!identified.isEmpty()) {
                    groups.add(group(
                            documentVersionId,
                            groupKey,
                            IconMeaningStatus.IDENTIFIED,
                            "",
                            identified.getFirst(),
                            publishable));
                } else {
                    groups.add(group(
                            documentVersionId,
                            groupKey,
                            IconMeaningStatus.UNEXPLAINED,
                            "",
                            publishable.getFirst(),
                            publishable));
                }
                return;
            }
            if (explicitDefinitions.size() == 1) {
                String definition = explicitDefinitions.keySet().iterator().next();
                groups.add(group(
                        documentVersionId,
                        groupKey,
                        IconMeaningStatus.EXPLICIT,
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
                            IconMeaningStatus.EXPLICIT,
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
                        IconMeaningStatus.UNEXPLAINED,
                        "",
                        visibleUnexplained.getFirst(),
                        visibleUnexplained));
            }
        });

        groups.sort(Comparator.comparing(IconGroup::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(group -> group.occurrences().getFirst().pageNumber()));
        return new GlossaryProjection(List.copyOf(groups), Set.copyOf(conflictingKeys));
    }

    /**
     * Tile audits and full-page scans can report the same symbol twice with different neutral names. An exact or
     * near-exact page-local overlap is strong evidence of a duplicate observation, but never merge two independently
     * evidenced meanings or two identified labels merely because their artwork happens to be nearby.
     */
    private static List<LocatedIcon> deduplicateOverlappingOccurrences(List<LocatedIcon> cataloged) {
        List<LocatedIcon> retained = new ArrayList<>();
        for (LocatedIcon candidate : cataloged) {
            int duplicateIndex = -1;
            for (int index = 0; index < retained.size(); index++) {
                LocatedIcon existing = retained.get(index);
                if (existing.pageNumber() != candidate.pageNumber()
                        || intersectionOverUnion(existing.icon(), candidate.icon()) < 0.85
                        || !duplicateIdentity(existing.icon(), candidate.icon())) continue;
                duplicateIndex = index;
                break;
            }
            if (duplicateIndex < 0) {
                retained.add(candidate);
                continue;
            }
            LocatedIcon existing = retained.get(duplicateIndex);
            if (meaningRank(candidate.icon().meaningStatus()) > meaningRank(existing.icon().meaningStatus())) {
                retained.set(duplicateIndex, candidate);
            }
        }
        return List.copyOf(retained);
    }

    private static boolean duplicateIdentity(IconOccurrence first, IconOccurrence second) {
        IconMeaningStatus firstStatus = first.meaningStatus();
        IconMeaningStatus secondStatus = second.meaningStatus();
        if (firstStatus == IconMeaningStatus.EXPLICIT && secondStatus == IconMeaningStatus.EXPLICIT) return false;
        if (firstStatus == IconMeaningStatus.UNEXPLAINED || secondStatus == IconMeaningStatus.UNEXPLAINED) return true;
        return groupingIdentity(first).equals(groupingIdentity(second))
                || normalized(first.visualDescription()).equals(normalized(second.visualDescription()));
    }

    private static int meaningRank(IconMeaningStatus status) {
        return switch (status) {
            case EXPLICIT -> 2;
            case IDENTIFIED -> 1;
            case UNEXPLAINED -> 0;
        };
    }

    private static double intersectionOverUnion(IconOccurrence first, IconOccurrence second) {
        int left = Math.max(first.x(), second.x());
        int top = Math.max(first.y(), second.y());
        int right = Math.min(first.x() + first.width(), second.x() + second.width());
        int bottom = Math.min(first.y() + first.height(), second.y() + second.height());
        long intersection = (long) Math.max(0, right - left) * Math.max(0, bottom - top);
        long firstArea = (long) first.width() * first.height();
        long secondArea = (long) second.width() * second.height();
        long union = firstArea + secondArea - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static IconGroup group(
            UUID documentVersionId,
            String groupKey,
            IconMeaningStatus status,
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
                status == IconMeaningStatus.EXPLICIT ? definition.explanation() : null,
                status == IconMeaningStatus.UNEXPLAINED ? null : definition.evidenceText(),
                status,
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
                .replaceAll("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Zs}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String groupingIdentity(IconOccurrence icon) {
        String proposedIdentity = normalized(icon.groupKey());
        String verifiedLabel = normalized(icon.verifiedVisualLabel());
        return !verifiedLabel.isBlank()
                        && IconEvidencePolicy.compatibleIdentity(verifiedLabel, proposedIdentity)
                ? verifiedLabel
                : proposedIdentity;
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
