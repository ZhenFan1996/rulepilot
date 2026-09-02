package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.VisualRegionLocator.BatchAction;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Strict admission for a model that may select application-owned candidates but can never author geometry. */
final class VisualLocatorResponsePolicy {

    static final int MAX_LABEL_CHARACTERS = 80;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private VisualLocatorResponsePolicy() {}

    static Optional<ModelGuide> parseModelGuide(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(content.strip());
            if (root == null
                    || !root.isObject()
                    || root.size() != 2
                    || !root.has("batchAction")
                    || !root.get("batchAction").isTextual()
                    || !root.has("reviews")) {
                return Optional.empty();
            }
            BatchAction batchAction;
            try {
                batchAction = BatchAction.valueOf(root.get("batchAction").textValue());
            } catch (IllegalArgumentException invalidAction) {
                return Optional.empty();
            }
            JsonNode reviews = root.get("reviews");
            if (!reviews.isArray() || reviews.isEmpty()) {
                return Optional.empty();
            }
            List<ModelReview> parsed = new ArrayList<>();
            for (JsonNode review : reviews) {
                Optional<ModelReview> value = parseReview(review);
                if (value.isEmpty()) return Optional.empty();
                parsed.add(value.get());
            }
            return Optional.of(new ModelGuide(batchAction, parsed));
        } catch (JsonProcessingException invalidJson) {
            return Optional.empty();
        }
    }

    private static Optional<ModelReview> parseReview(JsonNode review) {
        if (review == null
                || !review.isObject()
                || review.size() != 5
                || !integral(review, "stepPosition")
                || !text(review, "action")
                || !review.has("candidateId")
                || !review.has("label")
                || !review.has("visibleDescription")) {
            return Optional.empty();
        }
        int stepPosition = review.get("stepPosition").intValue();
        if (stepPosition < 1) return Optional.empty();
        ModelAction action;
        try {
            action = ModelAction.valueOf(review.get("action").textValue());
        } catch (IllegalArgumentException invalidAction) {
            return Optional.empty();
        }
        if (action == ModelAction.NO_VISUAL) {
            if (!review.get("candidateId").isNull()
                    || !review.get("label").isNull()
                    || !review.get("visibleDescription").isNull()) {
                return Optional.empty();
            }
            return Optional.of(new ModelReview(stepPosition, action, null, null, null));
        }
        if (!nonBlankText(review, "candidateId")
                || !nonBlankText(review, "label")
                || !nonBlankText(review, "visibleDescription")) {
            return Optional.empty();
        }
        String candidateId = review.get("candidateId").textValue().strip();
        String label = review.get("label").textValue().strip();
        String visibleDescription = review.get("visibleDescription").textValue().strip();
        if (label.length() > MAX_LABEL_CHARACTERS) return Optional.empty();
        return Optional.of(new ModelReview(
                stepPosition,
                action,
                candidateId,
                label,
                visibleDescription));
    }

    private static boolean integral(JsonNode object, String field) {
        return object.has(field) && object.get(field).isIntegralNumber();
    }

    private static boolean text(JsonNode object, String field) {
        return object.has(field) && object.get(field).isTextual();
    }

    private static boolean nonBlankText(JsonNode object, String field) {
        return text(object, field) && !object.get(field).asText().isBlank();
    }

    static List<Map<String, Object>> candidateManifest(List<Candidate> candidates) {
        List<Map<String, Object>> manifest = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("candidateId", candidate.candidateId());
            entry.put("attachmentIndex", index + 1);
            entry.put("pageNumber", candidate.pageNumber());
            manifest.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(manifest);
    }

    static String promptJson(Object payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("could not serialize visual candidate prompt payload", failure);
        }
    }

    static String completeReplacementFeedback(
            Rejection rejection,
            String rejectedCandidate,
            String validationError,
            List<String> allowedCandidateIds,
            List<Integer> allowedStepPositions) {
        if (rejection == null || !rejection.retryable()
                || rejectedCandidate == null || validationError == null || validationError.isBlank()
                || allowedCandidateIds == null || allowedStepPositions == null) {
            throw new IllegalArgumentException("visual selection rejection cannot be corrected");
        }
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("status", "PREVIOUS_COMPLETE_SELECTION_REJECTED");
        feedback.put("reasonCode", rejection.name());
        feedback.put("rejectedCandidate", rejectedCandidate);
        feedback.put("validationError", validationError.strip());
        feedback.put("originalJsonContract", Map.of(
                "rootFields", List.of("batchAction", "reviews"),
                "batchAction", List.of("STOP", "CONTINUE"),
                "reviewFields", List.of(
                        "stepPosition", "action", "candidateId", "label", "visibleDescription"),
                "actions", List.of("ACCEPT_CANDIDATE", "NO_VISUAL")));
        feedback.put("allowedCandidateIds", List.copyOf(allowedCandidateIds));
        feedback.put("allowedStepPositions", List.copyOf(allowedStepPositions));
        feedback.put("requiredAction", "RETURN_COMPLETE_REPLACEMENT");
        feedback.put("allowedDecisions", List.of("ACCEPT_CANDIDATE", "NO_VISUAL"));
        feedback.put("forbiddenActions", List.of(
                "PATCH_PREVIOUS_FIELDS",
                "EDIT_PIXELS",
                "RETURN_GEOMETRY",
                "RETURN_PROSE_OUTSIDE_JSON"));
        return promptJson(Collections.unmodifiableMap(feedback));
    }

    static String malformedValidationError(String content) {
        if (content == null || content.isBlank()) return "The visual selection candidate is blank.";
        try {
            JsonNode root = JSON.readTree(content.strip());
            if (root == null || !root.isObject()) {
                return "The visual selection candidate must be one JSON object.";
            }
            JsonNode reviews = root.path("reviews");
            if (reviews.isArray()) {
                for (JsonNode review : reviews) {
                    if (nonBlankText(review, "label")) {
                        int length = review.get("label").textValue().strip().length();
                        if (length > MAX_LABEL_CHARACTERS) {
                            return "An ACCEPT_CANDIDATE label contains " + length
                                    + " characters; shorten every label to at most "
                                    + MAX_LABEL_CHARACTERS + " characters.";
                        }
                    }
                }
            }
            return "The candidate does not match the exact batchAction plus non-empty reviews contract; every review "
                    + "must contain exactly stepPosition, action, candidateId, label, and visibleDescription with "
                    + "the action-dependent nullability described in the original contract.";
        } catch (JsonProcessingException invalidJson) {
            String location = invalidJson.getLocation() == null
                    ? ""
                    : " at line " + invalidJson.getLocation().getLineNr()
                            + ", column " + invalidJson.getLocation().getColumnNr();
            return "JSON parsing failed" + location + ": " + invalidJson.getOriginalMessage();
        }
    }

    static Diagnostic diagnosticFor(Rejection rejection) {
        return switch (rejection) {
            case NONE -> Diagnostic.NO_REGION;
            case EXPLICIT_NO_REGION -> Diagnostic.EXPLICIT_NO_REGION;
            case MALFORMED_JSON -> Diagnostic.MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Diagnostic.UNSUPPORTED_SCOPE;
            case PROVIDER_INPUT_REJECTED -> Diagnostic.PROVIDER_INPUT_REJECTED;
            case PROVIDER_FAILURE -> Diagnostic.PROVIDER_FAILURE;
            case CANDIDATE_PREPARATION_FAILED -> Diagnostic.CANDIDATE_PREPARATION_FAILED;
        };
    }

    enum ModelAction {
        ACCEPT_CANDIDATE,
        NO_VISUAL
    }

    record ModelReview(
            int stepPosition,
            ModelAction action,
            String candidateId,
            String label,
            String visibleDescription) {
        ModelReview {
            if (stepPosition < 1 || action == null) {
                throw new IllegalArgumentException("model visual review is invalid");
            }
            boolean noVisual = action == ModelAction.NO_VISUAL;
            if (noVisual != (candidateId == null)
                    || noVisual != (label == null)
                    || noVisual != (visibleDescription == null)) {
                throw new IllegalArgumentException("model visual review action is invalid");
            }
        }
    }

    record ModelGuide(BatchAction batchAction, List<ModelReview> reviews) {
        ModelGuide {
            if (batchAction == null
                    || reviews == null
                    || reviews.isEmpty()) {
                throw new IllegalArgumentException("visual guide review plan is invalid");
            }
            reviews = List.copyOf(reviews);
        }

        boolean hasOnlyNoVisual() {
            return reviews.stream().allMatch(review -> review.action() == ModelAction.NO_VISUAL);
        }
    }

    enum Rejection {
        NONE,
        EXPLICIT_NO_REGION,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        PROVIDER_INPUT_REJECTED,
        PROVIDER_FAILURE,
        CANDIDATE_PREPARATION_FAILED;

        boolean retryable() {
            return this == MALFORMED_JSON || this == UNSUPPORTED_SCOPE;
        }
    }
}
