package com.rulepilot.teaching.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persists the outline Agent's teaching-unit decisions inside the existing retrieval-contract field.
 *
 * <p>The source inventory may contain several independently auditable source slots that the Agent deliberately
 * groups into one teachable cognitive or operational unit. Keeping that grouping in the immutable plan lets later
 * composition validate granularity without guessing from game vocabulary, prose length, or a fixed chapter type.</p>
 */
final class TeachingUnitContract {

    private static final String V1_PREFIX = "teaching-unit-v1.";
    private static final String V2_PREFIX = "teaching-unit-v2.";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TeachingUnitContract() {}

    static List<String> encodeUnits(
            List<com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft> slots) {
        Map<String, Map<String, List<Integer>>> sourcesByUnit = new LinkedHashMap<>();
        for (var slot : slots) {
            Map<String, List<Integer>> unitSources = sourcesByUnit.computeIfAbsent(
                    slot.teachingUnitId(), ignored -> new LinkedHashMap<>());
            List<Integer> pages = new ArrayList<>(
                    unitSources.getOrDefault(slot.sourceIdentifier(), List.of()));
            slot.sourcePageNumbers().stream().filter(page -> !pages.contains(page)).forEach(pages::add);
            unitSources.put(slot.sourceIdentifier(), List.copyOf(pages));
        }
        return sourcesByUnit.entrySet().stream()
                .map(entry -> encode(new Unit(entry.getKey(), entry.getValue())))
                .toList();
    }

    static List<Unit> decodeUnits(List<String> contracts) {
        if (contracts == null || contracts.isEmpty() || contracts.stream().noneMatch(TeachingUnitContract::encoded)) {
            return List.of();
        }
        if (contracts.stream().anyMatch(value -> !encoded(value))) {
            throw new IllegalArgumentException("teaching unit contracts cannot mix encoded units and search text");
        }
        LinkedHashSet<String> unitIds = new LinkedHashSet<>();
        List<Unit> units = contracts.stream().map(TeachingUnitContract::decode).toList();
        if (units.stream().anyMatch(unit -> !unitIds.add(unit.unitId()))) {
            throw new IllegalArgumentException("teaching unit IDs must be unique within a chapter");
        }
        return units;
    }

    static List<String> sourceIdentifiers(List<String> contracts) {
        List<Unit> units = decodeUnits(contracts);
        return units.isEmpty()
                ? contracts == null ? List.of() : List.copyOf(contracts)
                : units.stream().flatMap(unit -> unit.sourceIdentifiers().stream()).distinct().toList();
    }

    static String encode(Unit unit) {
        String sources = unit.sourceIdentifiers().stream()
                .map(identifier -> base64(identifier) + "@" + unit.sourcePages(identifier).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining("."));
        return V2_PREFIX + base64(unit.unitId()) + "." + sources;
    }

    private static Unit decode(String contract) {
        if (contract.startsWith(V2_PREFIX)) return decodeV2(contract);
        String[] parts = contract.substring(V1_PREFIX.length()).split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("teaching unit contract is invalid");
        Map<String, List<Integer>> sources = new LinkedHashMap<>();
        java.util.stream.IntStream.range(1, parts.length)
                .mapToObj(index -> text(parts[index]))
                .forEach(identifier -> sources.put(identifier, List.of()));
        return new Unit(text(parts[0]), sources);
    }

    private static Unit decodeV2(String contract) {
        String[] parts = contract.substring(V2_PREFIX.length()).split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("teaching unit contract is invalid");
        Map<String, List<Integer>> sources = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            String[] source = parts[index].split("@", -1);
            if (source.length != 2) throw new IllegalArgumentException("teaching unit contract is invalid");
            List<Integer> pages = source[1].isBlank()
                    ? List.of()
                    : java.util.Arrays.stream(source[1].split(","))
                            .map(Integer::parseInt)
                            .toList();
            sources.put(text(source[0]), pages);
        }
        return new Unit(text(parts[0]), sources);
    }

    private static boolean encoded(String value) {
        return value != null && (value.startsWith(V1_PREFIX) || value.startsWith(V2_PREFIX));
    }

    private static String base64(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        try {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("teaching unit contract is invalid", invalidBase64);
        }
    }

    record Unit(String unitId, Map<String, List<Integer>> sourcePagesByIdentifier) {
        Unit(String unitId, List<String> sourceIdentifiers) {
            this(
                    unitId,
                    sourceIdentifiers == null
                            ? (Map<String, List<Integer>>) null
                            : sourceIdentifiers.stream().collect(java.util.stream.Collectors.toMap(
                                    identifier -> identifier,
                                    ignored -> List.<Integer>of(),
                                    (first, duplicate) -> first,
                                    LinkedHashMap::new)));
        }

        Unit {
            if (unitId == null || unitId.isBlank()
                    || sourcePagesByIdentifier == null || sourcePagesByIdentifier.isEmpty()
                    || sourcePagesByIdentifier.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                            || entry.getKey().isBlank()
                            || entry.getValue() == null
                            || entry.getValue().stream().anyMatch(page -> page == null || page < 1))) {
                throw new IllegalArgumentException("planned teaching unit is invalid");
            }
            unitId = unitId.strip();
            Map<String, List<Integer>> normalized = new LinkedHashMap<>();
            sourcePagesByIdentifier.forEach((identifier, pages) -> normalized.put(
                    identifier.strip(), pages.stream().distinct().toList()));
            sourcePagesByIdentifier = java.util.Collections.unmodifiableMap(normalized);
        }

        List<String> sourceIdentifiers() {
            return List.copyOf(sourcePagesByIdentifier.keySet());
        }

        List<Integer> sourcePages(String identifier) {
            return sourcePagesByIdentifier.getOrDefault(identifier, List.of());
        }

        List<Integer> sourcePages() {
            return sourcePagesByIdentifier.values().stream().flatMap(List::stream).distinct().toList();
        }
    }
}
