package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict schema admission for untrusted visual-locator model responses. */
final class VisualLocatorResponsePolicy {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private VisualLocatorResponsePolicy() {}

    static boolean isExplicitNoRegion(String content) {
        return parseModelGuide(content).map(guide -> guide.regions().isEmpty()).orElse(false);
    }

    static String retryInstruction(Rejection rejection) {
        return switch (rejection) {
            case EXPLICIT_NO_REGION -> "";
            case MALFORMED_JSON -> "The previous response did not match the exact JSON contract. Return one object with a regions array; use {\"regions\":[]} when no crop is useful.";
            case UNSUPPORTED_SCOPE -> "The previous response used an unavailable page or claim reference. Use only the supplied page numbers and C1, C2, etc. claim references; the crop page must be a sourcePage for the cited claim.";
            case INVALID_GEOMETRY -> "The previous rectangle was outside the page. Return a new JSON candidate only after verifying x + width <= 1000 and y + height <= 1000.";
            case NONE -> "";
        };
    }

    static Optional<ModelGuide> parseModelGuide(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        String json = content.strip();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject() || root.size() != 1 || !root.has("regions")) return Optional.empty();
            JsonNode regions = root.get("regions");
            if (!regions.isArray() || regions.size() > 2) return Optional.empty();
            List<ModelRegion> parsed = new java.util.ArrayList<>();
            for (JsonNode region : regions) {
                if (!hasExactRegionShape(region)) return Optional.empty();
                ModelRegion parsedRegion = JSON.treeToValue(region, ModelRegion.class);
                if (parsedRegion != null) parsed.add(parsedRegion);
            }
            return Optional.of(new ModelGuide(parsed));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    private static boolean hasExactRegionShape(JsonNode region) {
        if (region == null || !region.isObject() || region.size() != 8) return false;
        if (!(integral(region, "pageNumber")
                && nonBlankText(region, "label")
                && nonBlankText(region, "visibleDescription")
                && integral(region, "x")
                && integral(region, "y")
                && integral(region, "width")
                && integral(region, "height")
                && textArray(region, "supportedClaimRefs"))) return false;
        int pageNumber = region.get("pageNumber").intValue();
        int x = region.get("x").intValue();
        int y = region.get("y").intValue();
        int width = region.get("width").intValue();
        int height = region.get("height").intValue();
        return pageNumber >= 1
                && region.get("label").textValue().strip().length() <= 80
                && region.get("visibleDescription").textValue().strip().length() <= 240
                && x >= 0
                && y >= 0
                && width >= 20
                && height >= 20
                && x + width <= 1_000
                && y + height <= 1_000;
    }

    private static boolean integral(JsonNode object, String field) {
        return object.has(field) && object.get(field).isIntegralNumber();
    }

    private static boolean nonBlankText(JsonNode object, String field) {
        return object.has(field) && object.get(field).isTextual() && !object.get(field).asText().isBlank();
    }

    private static boolean textArray(JsonNode object, String field) {
        if (!object.has(field) || !object.get(field).isArray()) return false;
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : object.get(field)) {
            if (!value.isTextual() || value.asText().isBlank() || !unique.add(value.asText())) return false;
        }
        return !unique.isEmpty();
    }

    static Optional<CropReview> cropReview(String content, Set<String> offeredReferences) {
        if (content == null || content.isBlank() || offeredReferences == null || offeredReferences.isEmpty()) {
            return Optional.empty();
        }
        String json = content.strip();
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null
                    || !root.isObject()
                    || root.size() != 2
                    || !root.has("acceptedCropRefs")
                    || !root.has("contradictedCropRefs")) return Optional.empty();
            JsonNode accepted = root.get("acceptedCropRefs");
            if (!accepted.isArray()) return Optional.empty();
            JsonNode contradicted = root.get("contradictedCropRefs");
            if (!contradicted.isArray()) return Optional.empty();
            Set<String> acceptedReferences = offeredReferences(accepted, offeredReferences);
            Set<String> contradictedReferences = offeredReferences(contradicted, offeredReferences);
            if (acceptedReferences == null
                    || contradictedReferences == null
                    || acceptedReferences.stream().anyMatch(contradictedReferences::contains)) {
                return Optional.empty();
            }
            return Optional.of(new CropReview(acceptedReferences, contradictedReferences));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    static Optional<Set<String>> acceptedCropReferences(String content, Set<String> offeredReferences) {
        return cropReview(content, offeredReferences).map(CropReview::acceptedReferences);
    }

    private static Set<String> offeredReferences(JsonNode values, Set<String> offeredReferences) {
        Set<String> references = new LinkedHashSet<>();
        for (JsonNode reference : values) {
            if (!reference.isTextual()
                    || !offeredReferences.contains(reference.asText())
                    || !references.add(reference.asText())) return null;
        }
        return Set.copyOf(references);
    }

    record CropReview(Set<String> acceptedReferences, Set<String> contradictedReferences) {
        CropReview {
            acceptedReferences = Set.copyOf(acceptedReferences);
            contradictedReferences = Set.copyOf(contradictedReferences);
        }
    }

    /** Avoid feeding Qwen extracted prose or a full-page rectangle it may mechanically repeat as its crop. */
    static List<Map<String, Object>> candidatePromptPayload(List<Candidate> candidates, boolean compactForQwen) {
        return candidates.stream().map(candidate -> candidatePromptPayload(candidate, compactForQwen)).toList();
    }

    private static Map<String, Object> candidatePromptPayload(Candidate candidate, boolean compactForQwen) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pageNumber", candidate.pageNumber());
        payload.put("candidateKind", candidate.kind().name());
        if (!compactForQwen) payload.put("allowedRectangle", candidate.rectangle());
        if (candidate.catalogedAnchor() != null) {
            payload.put("catalogedAnchor", Map.of(
                    "anchorKind", candidate.catalogedAnchor().kind(),
                    "label", candidate.catalogedAnchor().label(),
                    "visibleDescription", candidate.catalogedAnchor().visibleDescription(),
                    "rectangle", Map.of(
                            "x", candidate.catalogedAnchor().x(),
                            "y", candidate.catalogedAnchor().y(),
                            "width", candidate.catalogedAnchor().width(),
                            "height", candidate.catalogedAnchor().height())));
        }
        return Map.copyOf(payload);
    }

    static Diagnostic diagnosticFor(Rejection rejection) {
        return switch (rejection) {
            case NONE -> Diagnostic.NO_REGION;
            case EXPLICIT_NO_REGION -> Diagnostic.EXPLICIT_NO_REGION;
            case MALFORMED_JSON -> Diagnostic.MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Diagnostic.UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> Diagnostic.INVALID_GEOMETRY;
        };
    }

    record ModelRegion(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height,
            List<String> supportedClaimRefs) {
        ModelRegion {
            supportedClaimRefs = List.copyOf(supportedClaimRefs);
        }
    }

    record ModelGuide(List<ModelRegion> regions) {
        ModelGuide {
            if (regions == null || regions.size() > 2) {
                throw new IllegalArgumentException("visual guide must contain at most two structured regions");
            }
            regions = List.copyOf(regions);
        }
    }

    enum Rejection {
        NONE,
        EXPLICIT_NO_REGION,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY
    }
}
