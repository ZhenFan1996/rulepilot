package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private static final String V3_PREFIX = "teaching-unit-v3.";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TeachingUnitContract() {}

    static List<String> encodeUnits(List<SourceCoverageSlotDraft> slots) {
        Map<String, List<SourceBinding>> sourcesByUnit = new LinkedHashMap<>();
        for (SourceCoverageSlotDraft slot : slots) {
            List<SourceBinding> unitSources = new java.util.ArrayList<>(
                    sourcesByUnit.getOrDefault(slot.teachingUnitId(), List.of()));
            unitSources.add(new SourceBinding(
                    slot.sourceIdentifier(),
                    slot.sourcePageNumbers(),
                    slot.role(),
                    slot.availability()));
            sourcesByUnit.put(slot.teachingUnitId(), List.copyOf(unitSources));
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

    static List<String> retrievalIdentifiers(List<String> contracts) {
        List<Unit> units = decodeUnits(contracts);
        return units.isEmpty()
                ? contracts == null ? List.of() : List.copyOf(contracts)
                : units.stream().flatMap(unit -> unit.retrievalIdentifiers().stream()).distinct().toList();
    }

    static String encode(Unit unit) {
        if (!unit.typed()) return encodeV2(unit);
        String sources = unit.sourceBindings().stream()
                .map(binding -> base64(binding.sourceIdentifier())
                        + "@" + pages(binding.sourcePages())
                        + "@" + binding.role().name()
                        + "@" + binding.availability().name())
                .collect(Collectors.joining("."));
        return V3_PREFIX + base64(unit.unitId()) + "." + sources;
    }

    private static String encodeV2(Unit unit) {
        String sources = unit.sourceIdentifiers().stream()
                .map(identifier -> base64(identifier) + "@" + pages(unit.sourcePages(identifier)))
                .collect(Collectors.joining("."));
        return V2_PREFIX + base64(unit.unitId()) + "." + sources;
    }

    private static Unit decode(String contract) {
        if (contract.startsWith(V3_PREFIX)) return decodeV3(contract);
        if (contract.startsWith(V2_PREFIX)) return decodeV2(contract);
        String[] parts = contract.substring(V1_PREFIX.length()).split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("teaching unit contract is invalid");
        Map<String, List<Integer>> sources = new LinkedHashMap<>();
        java.util.stream.IntStream.range(1, parts.length)
                .mapToObj(index -> text(parts[index]))
                .forEach(identifier -> sources.put(identifier, List.of()));
        return new Unit(text(parts[0]), sources);
    }

    private static Unit decodeV3(String contract) {
        String[] parts = contract.substring(V3_PREFIX.length()).split("\\.");
        if (parts.length < 2) throw invalidContract();
        List<SourceBinding> sources = new java.util.ArrayList<>();
        for (int index = 1; index < parts.length; index++) {
            String[] source = parts[index].split("@", -1);
            if (source.length != 4) throw invalidContract();
            try {
                sources.add(new SourceBinding(
                        text(source[0]),
                        parsePages(source[1]),
                        SourceCoverageRole.valueOf(source[2]),
                        SourceCoverageAvailability.valueOf(source[3])));
            } catch (IllegalArgumentException invalidSource) {
                throw new IllegalArgumentException("teaching unit contract is invalid", invalidSource);
            }
        }
        return new Unit(text(parts[0]), sources);
    }

    private static Unit decodeV2(String contract) {
        String[] parts = contract.substring(V2_PREFIX.length()).split("\\.");
        if (parts.length < 2) throw invalidContract();
        Map<String, List<Integer>> sources = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            String[] source = parts[index].split("@", -1);
            if (source.length != 2) throw invalidContract();
            try {
                sources.put(text(source[0]), parsePages(source[1]));
            } catch (IllegalArgumentException invalidSource) {
                throw new IllegalArgumentException("teaching unit contract is invalid", invalidSource);
            }
        }
        return new Unit(text(parts[0]), sources);
    }

    private static boolean encoded(String value) {
        return value != null
                && (value.startsWith(V1_PREFIX) || value.startsWith(V2_PREFIX) || value.startsWith(V3_PREFIX));
    }

    private static List<Integer> parsePages(String value) {
        return value.isBlank()
                ? List.of()
                : java.util.Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }

    private static String pages(List<Integer> sourcePages) {
        return sourcePages.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static IllegalArgumentException invalidContract() {
        return new IllegalArgumentException("teaching unit contract is invalid");
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

    record Unit(String unitId, List<SourceBinding> sourceBindings) {
        Unit(String unitId, Map<String, List<Integer>> sourcePagesByIdentifier) {
            this(unitId, legacyBindings(sourcePagesByIdentifier));
        }

        Unit {
            if (unitId == null || unitId.isBlank()
                    || sourceBindings == null || sourceBindings.isEmpty()
                    || sourceBindings.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("planned teaching unit is invalid");
            }
            unitId = unitId.strip();
            sourceBindings = List.copyOf(sourceBindings);
            long typedBindings = sourceBindings.stream().filter(SourceBinding::typed).count();
            if (typedBindings != 0 && typedBindings != sourceBindings.size()) {
                throw new IllegalArgumentException("planned teaching unit cannot mix typed and legacy sources");
            }
            if (typedBindings > 0
                    && sourceBindings.stream().map(SourceBinding::availability).distinct().count() != 1) {
                throw new IllegalArgumentException("planned teaching unit cannot mix source availability");
            }
        }

        List<String> sourceIdentifiers() {
            return sourceBindings.stream().map(SourceBinding::sourceIdentifier).distinct().toList();
        }

        List<Integer> sourcePages(String identifier) {
            return sourceBindings.stream()
                    .filter(binding -> binding.sourceIdentifier().equals(identifier))
                    .flatMap(binding -> binding.sourcePages().stream())
                    .distinct()
                    .toList();
        }

        List<Integer> sourcePages() {
            return sourceBindings.stream()
                    .flatMap(binding -> binding.sourcePages().stream())
                    .distinct()
                    .toList();
        }

        boolean typed() {
            return sourceBindings.getFirst().typed();
        }

        SourceCoverageAvailability availability() {
            return typed() ? sourceBindings.getFirst().availability() : null;
        }

        List<SourceCoverageRole> roles() {
            return typed()
                    ? sourceBindings.stream().map(SourceBinding::role).distinct().toList()
                    : List.of();
        }

        List<String> requiredRuleIdentifiers() {
            return typed()
                    ? sourceBindings.stream()
                            .filter(binding -> binding.availability() == SourceCoverageAvailability.SOURCED)
                            .map(SourceBinding::sourceIdentifier)
                            .distinct()
                            .toList()
                    : sourceIdentifiers();
        }

        List<String> retrievalIdentifiers() {
            return typed()
                    ? sourceBindings.stream()
                            .filter(binding -> binding.availability() != SourceCoverageAvailability.UNRESOLVED)
                            .map(SourceBinding::sourceIdentifier)
                            .distinct()
                            .toList()
                    : sourceIdentifiers();
        }

        private static List<SourceBinding> legacyBindings(Map<String, List<Integer>> sources) {
            if (sources == null) return null;
            return sources.entrySet().stream()
                    .map(entry -> new SourceBinding(entry.getKey(), entry.getValue(), null, null))
                    .toList();
        }
    }

    record SourceBinding(
            String sourceIdentifier,
            List<Integer> sourcePages,
            SourceCoverageRole role,
            SourceCoverageAvailability availability) {

        SourceBinding {
            if (sourceIdentifier == null || sourceIdentifier.isBlank()
                    || sourcePages == null
                    || sourcePages.stream().anyMatch(page -> page == null || page < 1)
                    || ((role == null) != (availability == null))
                    || (availability != null
                            && availability != SourceCoverageAvailability.UNRESOLVED
                            && sourcePages.isEmpty())) {
                throw new IllegalArgumentException("planned teaching source binding is invalid");
            }
            sourceIdentifier = sourceIdentifier.strip();
            sourcePages = sourcePages.stream().distinct().toList();
        }

        boolean typed() {
            return role != null;
        }
    }
}
