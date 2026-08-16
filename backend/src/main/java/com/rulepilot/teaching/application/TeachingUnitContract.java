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

    private static final String PREFIX = "teaching-unit-v1.";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TeachingUnitContract() {}

    static List<String> encodeUnits(
            List<com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft> slots) {
        Map<String, List<String>> identifiersByUnit = new LinkedHashMap<>();
        for (var slot : slots) {
            identifiersByUnit
                    .computeIfAbsent(slot.teachingUnitId(), ignored -> new ArrayList<>())
                    .add(slot.sourceIdentifier());
        }
        return identifiersByUnit.entrySet().stream()
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
                .map(TeachingUnitContract::base64)
                .reduce((left, right) -> left + "." + right)
                .orElseThrow();
        return PREFIX + base64(unit.unitId()) + "." + sources;
    }

    private static Unit decode(String contract) {
        String[] parts = contract.substring(PREFIX.length()).split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("teaching unit contract is invalid");
        List<String> identifiers = java.util.stream.IntStream.range(1, parts.length)
                .mapToObj(index -> text(parts[index]))
                .toList();
        return new Unit(text(parts[0]), identifiers);
    }

    private static boolean encoded(String value) {
        return value != null && value.startsWith(PREFIX);
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

    record Unit(String unitId, List<String> sourceIdentifiers) {
        Unit {
            if (unitId == null || unitId.isBlank() || unitId.length() > 80
                    || sourceIdentifiers == null || sourceIdentifiers.isEmpty() || sourceIdentifiers.size() > 16
                    || sourceIdentifiers.stream().anyMatch(identifier -> identifier == null
                            || identifier.isBlank() || identifier.length() > 160)) {
                throw new IllegalArgumentException("planned teaching unit is invalid");
            }
            unitId = unitId.strip();
            sourceIdentifiers = sourceIdentifiers.stream().map(String::strip).distinct().toList();
        }
    }
}
