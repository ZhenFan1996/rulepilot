package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.ReviewAction;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelGuide;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelRegion;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelReview;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Uses native visual tools for observable runs and preserves the established direct locator as a safe fallback. */
@Component("agenticVisualRegionLocator")
@Profile("!test")
public class AgenticVisualRegionLocator implements VisualRegionLocator {

    private static final String SYSTEM_PROMPT = """
            You are a rulebook visual evidence Agent. Work only with the supplied cited evidence handles and pages.
            First call read_rule_page_image for a cited evidenceId and one of that claim's sourcePages. You may call
            read_visual_page_facts only as a navigation hint. Call crop_rule_page_image when a page region or embedded
            author image is useful. A page, crop, or visual fact proves appearance only; never infer
            a mechanical effect, condition, quantity, score, timing, or exception beyond the cited claim text.

            Finish with one JSON object whose reviews array contains typed decisions. Every decision has
            stepPosition, action, and source. action is ACCEPT, RECROP, USE_FULL_PAGE, or REJECT. REJECT has source null.
            ACCEPT and RECROP require a sourceKind of PAGE_REGION or EMBEDDED_AUTHOR_IMAGE and a rectangle that exactly
            matches a successful crop observation. USE_FULL_PAGE requires sourceKind FULL_PAGE and the exact rectangle
            0,0,1000,1000 backed by a successful full-page read. A non-null source also needs pageNumber, a short
            Simplified Chinese label, a literal Simplified Chinese visibleDescription, x, y, width, height, and
            supportedClaimRefs. Return one REJECT decision for a step when no visual helps. Several ACCEPT decisions may
            belong to the same step, but never exceed the supplied visualBudget across all steps. Do not expose reasoning.
            """;

    private final NativeToolAgent agent;
    private final NativeToolScopes scopes;
    private final VisualRegionLocator fallback;
    private final ObjectMapper objectMapper;

    public AgenticVisualRegionLocator(
            NativeToolAgent agent,
            NativeToolScopes scopes,
            @Qualifier("springAiVisualRegionLocator") VisualRegionLocator fallback,
            ObjectMapper objectMapper) {
        this.agent = agent;
        this.scopes = scopes;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<LocatedRegion> locate(VisualLocationRequest request) {
        return locateGuideWithResult(request).regions().stream().findFirst();
    }

    @Override
    public boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return agent.supports(Role.VISUAL, modelConfigurationOwner)
                || fallback.supportsVisualEvidence(modelConfigurationOwner);
    }

    @Override
    public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
        if (!hasAgentContext(request)
                || !agent.supports(Role.VISUAL, request.modelConfigurationOwner())) {
            return fallback.locateGuideWithResult(request);
        }
        var scope = scopes.create(
                request.modelConfigurationOwner(), request.documentVersionId(), request.runId());
        if (scope.isEmpty()) return fallback.locateGuideWithResult(request);
        NativeToolAgent.RunResult result = agent.run(new RunRequest(
                Role.VISUAL,
                scope.get(),
                SYSTEM_PROMPT,
                playerRequest(request),
                explicitRejections(request),
                Math.min(12, 4 + request.visualBudget()),
                Math.min(2_048, 384 + request.visualBudget() * 192),
                Set.of("read_rule_page_image", "read_visual_page_facts", "crop_rule_page_image"),
                Set.of(),
                Math.min(24, 4 + request.visualBudget() * 2),
                "",
                Map.of("crop_rule_page_image", visualResultBudget(request))));
        if (result.status() != RunStatus.COMPLETED) {
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }

        Optional<ModelGuide> parsed = VisualLocatorResponsePolicy.parseModelGuide(result.text());
        if (parsed.isEmpty()) return LocateGuideResult.unavailable(Diagnostic.MALFORMED_RESPONSE);
        if (parsed.get().reviews().stream().filter(review -> review.source() != null).count() > request.visualBudget()) {
            return LocateGuideResult.unavailable(Diagnostic.UNSUPPORTED_SCOPE);
        }
        if (parsed.get().hasOnlyRejections()) {
            return LocateGuideResult.unavailable(Diagnostic.EXPLICIT_NO_REGION);
        }
        List<ObservedCrop> crops = observedCrops(result);
        List<ObservedPage> pages = observedPages(result);
        List<LocatedRegion> accepted = parsed.get().reviews().stream()
                .filter(review -> review.action() == ReviewAction.ACCEPT
                        || review.action() == ReviewAction.USE_FULL_PAGE)
                .map(review -> accepted(review, request, crops, pages))
                .flatMap(Optional::stream)
                .distinct()
                .limit(request.visualBudget())
                .toList();
        return accepted.isEmpty()
                ? LocateGuideResult.unavailable(Diagnostic.UNSUPPORTED_SCOPE)
                : LocateGuideResult.found(accepted);
    }

    private boolean hasAgentContext(VisualLocationRequest request) {
        return request != null
                && request.modelConfigurationOwner() != null
                && request.documentVersionId() != null
                && request.runId() != null;
    }

    private int visualResultBudget(VisualLocationRequest request) {
        return request.visualBudget();
    }

    private String explicitRejections(VisualLocationRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of("reviews", request.claims().stream()
                    .map(VisualRegionLocator.Claim::stepPosition)
                    .filter(position -> position > 0)
                    .distinct()
                    .map(this::rejection)
                    .toList()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("visual Agent fallback serialization failed", exception);
        }
    }

    private Map<String, Object> rejection(int stepPosition) {
        Map<String, Object> rejection = new LinkedHashMap<>();
        rejection.put("stepPosition", stepPosition);
        rejection.put("action", "REJECT");
        rejection.put("source", null);
        return rejection;
    }

    private String playerRequest(VisualLocationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("section", request.sectionTitle());
        payload.put("claims", IntStream.range(0, request.claims().size())
                .mapToObj(index -> Map.of(
                        "ref", "C" + (index + 1),
                        "evidenceId", request.claims().get(index).evidenceId().toString(),
                        "stepPosition", request.claims().get(index).stepPosition(),
                        "text", request.claims().get(index).text(),
                        "sourcePages", request.claims().get(index).sourcePages()))
                .toList());
        payload.put("candidateHints", VisualLocatorResponsePolicy.candidatePromptPayload(request.candidates(), true));
        payload.put("visualBudget", request.visualBudget());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("visual Agent request serialization failed", exception);
        }
    }

    private Optional<LocatedRegion> accepted(
            ModelReview review,
            VisualLocationRequest request,
            List<ObservedCrop> observedCrops,
            List<ObservedPage> observedPages) {
        ModelRegion region = review.source();
        if (region == null) return Optional.empty();
        List<VisualRegionLocator.Claim> claims = ownedClaims(review, request);
        if (claims.isEmpty()) return Optional.empty();
        if (review.action() == ReviewAction.USE_FULL_PAGE) {
            return acceptedFullPage(review, claims, observedPages);
        }
        ObservedCrop crop = observedCrops.stream()
                .filter(observed -> observed.pageNumber() == region.pageNumber())
                .filter(candidate -> candidate.sameRectangle(region))
                .filter(candidate -> claims.stream()
                        .anyMatch(claim -> claim.evidenceId().equals(candidate.evidenceId())))
                .findFirst()
                .orElse(null);
        if (crop == null) return Optional.empty();
        try {
            return Optional.of(new LocatedRegion(
                    crop.pageNumber(),
                    region.label(),
                    region.visibleDescription(),
                    crop.x(),
                    crop.y(),
                    crop.width(),
                    crop.height(),
                    claims.stream().map(VisualRegionLocator.Claim::evidenceId).toList(),
                    claims.stream()
                            .map(VisualRegionLocator.Claim::stepPosition)
                            .filter(position -> position > 0)
                            .distinct()
                            .toList(),
                    false,
                    region.sourceKind()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private Optional<LocatedRegion> acceptedFullPage(
            ModelReview review,
            List<VisualRegionLocator.Claim> claims,
            List<ObservedPage> observedPages) {
        ModelRegion region = review.source();
        if (region == null || region.sourceKind() != VisualSourceKind.FULL_PAGE) return Optional.empty();
        for (ObservedPage page : observedPages) {
            if (page.pageNumber() != region.pageNumber()) continue;
            if (claims.stream().noneMatch(claim -> claim.evidenceId().equals(page.evidenceId()))) continue;
            try {
                return Optional.of(new LocatedRegion(
                        page.pageNumber(),
                        region.label(),
                        region.visibleDescription(),
                        0,
                        0,
                        1_000,
                        1_000,
                        claims.stream().map(VisualRegionLocator.Claim::evidenceId).toList(),
                        List.of(review.stepPosition()),
                        false,
                        VisualSourceKind.FULL_PAGE));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private List<VisualRegionLocator.Claim> ownedClaims(
            ModelReview review, VisualLocationRequest request) {
        ModelRegion region = review.source();
        if (region == null) return List.of();
        List<VisualRegionLocator.Claim> claims = region.supportedClaimRefs().stream()
                .map(reference -> claim(reference, request))
                .filter(java.util.Objects::nonNull)
                .filter(claim -> claim.stepPosition() == review.stepPosition())
                .filter(claim -> claim.sourcePages().contains(region.pageNumber()))
                .distinct()
                .toList();
        return claims.size() == region.supportedClaimRefs().size() ? claims : List.of();
    }

    private VisualRegionLocator.Claim claim(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        int index;
        try {
            index = Integer.parseInt(reference.substring(1)) - 1;
        } catch (NumberFormatException invalid) {
            return null;
        }
        return index >= 0 && index < request.claims().size() ? request.claims().get(index) : null;
    }

    private List<ObservedCrop> observedCrops(NativeToolAgent.RunResult result) {
        List<ObservedCrop> crops = new ArrayList<>();
        result.observations().stream()
                .filter(record -> record.toolName().equals("crop_rule_page_image"))
                .map(NativeToolAgent.ObservationRecord::observation)
                .filter(observation -> observation.code().equals("PAGE_CROP_FOUND"))
                .map(this::observedCrop)
                .flatMap(Optional::stream)
                .forEach(crops::add);
        return List.copyOf(crops);
    }

    private List<ObservedPage> observedPages(NativeToolAgent.RunResult result) {
        List<ObservedPage> pages = new ArrayList<>();
        result.observations().stream()
                .filter(record -> record.toolName().equals("read_rule_page_image"))
                .map(NativeToolAgent.ObservationRecord::observation)
                .filter(observation -> observation.code().equals("PAGE_IMAGE_FOUND"))
                .map(this::observedPage)
                .flatMap(Optional::stream)
                .forEach(pages::add);
        return List.copyOf(pages);
    }

    private Optional<ObservedPage> observedPage(ToolObservation observation) {
        try {
            return Optional.of(new ObservedPage(
                    UUID.fromString(String.valueOf(observation.data().get("evidenceId"))),
                    number(observation.data().get("pageNumber"))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private Optional<ObservedCrop> observedCrop(ToolObservation observation) {
        Object rectangleValue = observation.data().get("rectangle");
        if (!(rectangleValue instanceof Map<?, ?> rectangle)) return Optional.empty();
        try {
            return Optional.of(new ObservedCrop(
                    UUID.fromString(String.valueOf(observation.data().get("evidenceId"))),
                    number(observation.data().get("pageNumber")),
                    number(rectangle.get("x")),
                    number(rectangle.get("y")),
                    number(rectangle.get("width")),
                    number(rectangle.get("height"))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private int number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("visual observation number is invalid");
        return number.intValue();
    }

    private record ObservedCrop(UUID evidenceId, int pageNumber, int x, int y, int width, int height) {
        private boolean sameRectangle(ModelRegion region) {
            return x == region.x()
                    && y == region.y()
                    && width == region.width()
                    && height == region.height();
        }
    }

    private record ObservedPage(UUID evidenceId, int pageNumber) {}

}
