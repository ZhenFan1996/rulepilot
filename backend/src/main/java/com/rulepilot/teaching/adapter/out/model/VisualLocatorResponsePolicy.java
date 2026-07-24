package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic parsing and recovery rules for untrusted visual-locator model responses. */
final class VisualLocatorResponsePolicy {

    private static final ObjectMapper JSON = new ObjectMapper();

    private VisualLocatorResponsePolicy() {}

    static boolean isExplicitNoRegion(String content) {
        return content != null && (content.strip().equals("null") || content.strip().equals("{}"));
    }

    static String retryInstruction(Rejection rejection) {
        return switch (rejection) {
            case EXPLICIT_NO_REGION -> "";
            case MALFORMED_JSON -> "The previous response was not a readable JSON object. Return one JSON object only, or an empty JSON object when no crop is useful.";
            case UNSUPPORTED_SCOPE -> "The previous response used an unavailable page or claim reference. Use only the supplied page numbers and C1, C2, etc. claim references; the crop page must be a sourcePage for the cited claim.";
            case INVALID_GEOMETRY -> "The previous rectangle was outside the page. Return a new JSON candidate only after verifying x + width <= 1000 and y + height <= 1000.";
            case NON_CHINESE_OBSERVATION -> "The previous label or visibleDescription was not natural Simplified Chinese. Reinspect the page and return Chinese names for literal visible objects only. Verify that the crop itself visibly contains the object or relationship needed for the claim; otherwise return an empty JSON object.";
            case NONE -> "";
        };
    }

    static boolean containsChinese(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    static Optional<ModelRegion> parseModelRegion(String content) {
        return parseModelGuide(content).flatMap(guide -> guide.regions().stream().findFirst());
    }

    static Optional<ModelGuide> parseModelGuide(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        String json = content.strip();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd < 0 || closingFence <= firstLineEnd) return Optional.empty();
            json = json.substring(firstLineEnd + 1, closingFence).strip();
        }
        if (json.equals("null")) return Optional.empty();
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) return Optional.empty();
        json = json.substring(objectStart, objectEnd + 1);
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject()) return Optional.empty();
            JsonNode regions = root.get("regions");
            if (regions == null) {
                return Optional.ofNullable(JSON.treeToValue(root, ModelRegion.class))
                        .map(region -> new ModelGuide(List.of(region)));
            }
            if (!regions.isArray()) return Optional.empty();
            List<ModelRegion> parsed = new java.util.ArrayList<>();
            for (JsonNode region : regions) {
                ModelRegion parsedRegion = JSON.treeToValue(region, ModelRegion.class);
                if (parsedRegion != null) parsed.add(parsedRegion);
            }
            return Optional.of(new ModelGuide(parsed));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    static Optional<Set<String>> acceptedCropReferences(String content, Set<String> offeredReferences) {
        if (content == null || content.isBlank() || offeredReferences == null || offeredReferences.isEmpty()) {
            return Optional.empty();
        }
        String json = content.strip();
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) return Optional.empty();
        try {
            JsonNode accepted = JSON.readTree(json.substring(objectStart, objectEnd + 1)).path("acceptedCropRefs");
            if (!accepted.isArray()) return Optional.empty();
            Set<String> references = new LinkedHashSet<>();
            for (JsonNode reference : accepted) {
                if (!reference.isTextual() || !offeredReferences.contains(reference.asText())) return Optional.empty();
                references.add(reference.asText());
            }
            return Optional.of(Set.copyOf(references));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    /** Avoid feeding Qwen extracted prose or a full-page rectangle it may mechanically repeat as its crop. */
    static List<Map<String, Object>> candidatePromptPayload(List<Candidate> candidates, boolean compactForQwen) {
        if (!compactForQwen) {
            return candidates.stream().map(candidate -> Map.<String, Object>of(
                            "pageNumber", candidate.pageNumber(),
                            "rectangle", candidate.rectangle(),
                            "sourceText", candidate.sourceText()))
                    .toList();
        }
        return candidates.stream().map(candidate -> Map.<String, Object>of(
                        "pageNumber", candidate.pageNumber(),
                        "hint", visualHint(candidate.sourceText())))
                .toList();
    }

    static Diagnostic diagnosticFor(Rejection rejection) {
        return switch (rejection) {
            case NONE -> Diagnostic.NO_REGION;
            case EXPLICIT_NO_REGION -> Diagnostic.EXPLICIT_NO_REGION;
            case MALFORMED_JSON -> Diagnostic.MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Diagnostic.UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> Diagnostic.INVALID_GEOMETRY;
            case NON_CHINESE_OBSERVATION -> Diagnostic.NON_CHINESE_OBSERVATION;
        };
    }

    private static String visualHint(String sourceText) {
        String normalized = sourceText == null ? "" : sourceText.strip();
        return normalized.startsWith("Cataloged visual anchor") ? "cataloged visual anchor" : "page visual context";
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
            visibleDescription = visibleDescription == null ? "" : visibleDescription.strip();
            supportedClaimRefs = supportedClaimRefs == null ? List.of() : List.copyOf(supportedClaimRefs);
        }
    }

    record ModelGuide(List<ModelRegion> regions) {
        ModelGuide {
            regions = regions == null ? List.of() : List.copyOf(regions.stream().limit(2).toList());
        }
    }

    enum Rejection {
        NONE,
        EXPLICIT_NO_REGION,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY,
        NON_CHINESE_OBSERVATION
    }
}
