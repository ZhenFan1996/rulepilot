package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.ReviewAction;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict schema admission for the Agent's typed per-step visual plan and review. */
final class VisualLocatorResponsePolicy {

    private static final int ABSOLUTE_REVIEW_LIMIT = 12;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private VisualLocatorResponsePolicy() {}

    static boolean isExplicitNoRegion(String content) {
        return parseModelGuide(content).map(ModelGuide::hasOnlyRejections).orElse(false);
    }

    static String retryInstruction(Rejection rejection) {
        return switch (rejection) {
            case EXPLICIT_NO_REGION, NONE -> "";
            case RECROP -> "The previous review requested RECROP. Inspect the proposed source again and return a final ACCEPT, USE_FULL_PAGE, or REJECT decision with exact evidence ownership.";
            case MALFORMED_JSON -> "The previous response did not match the exact JSON contract. Return one object with a reviews array and typed action/source values.";
            case UNSUPPORTED_SCOPE -> "The previous response used an unavailable page, step, or claim reference. Use only supplied stepPosition values, page numbers, and C references whose sourcePages include the visual page.";
            case INVALID_GEOMETRY -> "The previous rectangle was outside the page. Verify x + width <= 1000 and y + height <= 1000; USE_FULL_PAGE must be exactly 0,0,1000,1000.";
        };
    }

    static Optional<ModelGuide> parseModelGuide(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(content.strip());
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("reviews")) {
                return Optional.empty();
            }
            JsonNode reviews = root.get("reviews");
            if (!reviews.isArray() || reviews.isEmpty() || reviews.size() > ABSOLUTE_REVIEW_LIMIT) {
                return Optional.empty();
            }
            List<ModelReview> parsed = new ArrayList<>();
            for (JsonNode review : reviews) {
                Optional<ModelReview> value = parseReview(review);
                if (value.isEmpty()) return Optional.empty();
                parsed.add(value.get());
            }
            return Optional.of(new ModelGuide(parsed));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    private static Optional<ModelReview> parseReview(JsonNode review) {
        if (review == null
                || !review.isObject()
                || review.size() != 3
                || !integral(review, "stepPosition")
                || !review.has("action")
                || !review.get("action").isTextual()
                || !review.has("source")) {
            return Optional.empty();
        }
        int stepPosition = review.get("stepPosition").intValue();
        if (stepPosition < 1) return Optional.empty();
        ReviewAction action;
        try {
            action = ReviewAction.valueOf(review.get("action").textValue());
        } catch (IllegalArgumentException invalidAction) {
            return Optional.empty();
        }
        JsonNode source = review.get("source");
        if (action == ReviewAction.REJECT) {
            return source.isNull()
                    ? Optional.of(new ModelReview(stepPosition, action, null))
                    : Optional.empty();
        }
        Optional<ModelRegion> parsedSource = parseSource(source);
        if (parsedSource.isEmpty() || !actionMatchesSource(action, parsedSource.get())) return Optional.empty();
        return Optional.of(new ModelReview(stepPosition, action, parsedSource.get()));
    }

    private static Optional<ModelRegion> parseSource(JsonNode source) {
        if (source == null || !source.isObject() || source.size() != 9) return Optional.empty();
        if (!(integral(source, "pageNumber")
                && nonBlankText(source, "label")
                && nonBlankText(source, "visibleDescription")
                && integral(source, "x")
                && integral(source, "y")
                && integral(source, "width")
                && integral(source, "height")
                && nonBlankText(source, "sourceKind")
                && textArray(source, "supportedClaimRefs"))) return Optional.empty();
        int pageNumber = source.get("pageNumber").intValue();
        int x = source.get("x").intValue();
        int y = source.get("y").intValue();
        int width = source.get("width").intValue();
        int height = source.get("height").intValue();
        VisualSourceKind sourceKind;
        try {
            sourceKind = VisualSourceKind.valueOf(source.get("sourceKind").textValue());
        } catch (IllegalArgumentException invalidKind) {
            return Optional.empty();
        }
        if (pageNumber < 1
                || source.get("label").textValue().strip().length() > 80
                || source.get("visibleDescription").textValue().strip().length() > 240
                || x < 0
                || y < 0
                || width < 20
                || height < 20
                || x + width > 1_000
                || y + height > 1_000) return Optional.empty();
        try {
            return Optional.of(new ModelRegion(
                    pageNumber,
                    source.get("label").textValue().strip(),
                    source.get("visibleDescription").textValue().strip(),
                    x,
                    y,
                    width,
                    height,
                    sourceKind,
                    strings(source.get("supportedClaimRefs"))));
        } catch (IllegalArgumentException invalidSource) {
            return Optional.empty();
        }
    }

    private static boolean actionMatchesSource(ReviewAction action, ModelRegion source) {
        boolean wholePage = source.x() == 0
                && source.y() == 0
                && source.width() == 1_000
                && source.height() == 1_000;
        if (action == ReviewAction.USE_FULL_PAGE) {
            return source.sourceKind() == VisualSourceKind.FULL_PAGE && wholePage;
        }
        if (source.sourceKind() == VisualSourceKind.FULL_PAGE || wholePage) return false;
        return action == ReviewAction.ACCEPT || action == ReviewAction.RECROP;
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

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.textValue()));
        return List.copyOf(result);
    }

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
            case RECROP -> Diagnostic.NO_REGION;
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
            VisualSourceKind sourceKind,
            List<String> supportedClaimRefs) {
        ModelRegion {
            if (sourceKind == null || supportedClaimRefs == null || supportedClaimRefs.isEmpty()) {
                throw new IllegalArgumentException("model visual source is invalid");
            }
            supportedClaimRefs = List.copyOf(supportedClaimRefs);
        }
    }

    record ModelReview(int stepPosition, ReviewAction action, ModelRegion source) {
        ModelReview {
            if (stepPosition < 1 || action == null || (action == ReviewAction.REJECT) != (source == null)) {
                throw new IllegalArgumentException("model visual review is invalid");
            }
        }
    }

    record ModelGuide(List<ModelReview> reviews) {
        ModelGuide {
            if (reviews == null || reviews.isEmpty() || reviews.size() > ABSOLUTE_REVIEW_LIMIT) {
                throw new IllegalArgumentException("visual guide review plan is invalid");
            }
            reviews = List.copyOf(reviews);
        }

        List<ModelRegion> regions() {
            return reviews.stream().map(ModelReview::source).filter(java.util.Objects::nonNull).toList();
        }

        boolean hasOnlyRejections() {
            return reviews.stream().allMatch(review -> review.action() == ReviewAction.REJECT);
        }
    }

    enum Rejection {
        NONE,
        EXPLICIT_NO_REGION,
        RECROP,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY
    }
}
