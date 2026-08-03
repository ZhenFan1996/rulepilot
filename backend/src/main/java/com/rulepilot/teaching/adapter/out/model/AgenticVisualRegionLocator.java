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
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelGuide;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelRegion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            read_visual_page_facts only as a navigation hint. Then call crop_rule_page_image for a compact rectangle
            around the literal object, icon group, board relationship, table row, or worked state that helps the claim.
            Request at most one crop for each intended result. The successful crop response is the inspection result:
            when nextAction says RETURN_FINAL_JSON_WITH_THIS_EXACT_RECTANGLE, call no more tools and immediately finish
            with that exact rectangle. A page, crop, or visual fact proves appearance only; never infer
            a mechanical effect, condition, quantity, score, timing, or exception beyond the cited claim text.

            Finish with one JSON object whose regions array has zero, one, or two objects. Every accepted object needs
            pageNumber, a short Simplified Chinese label, a literal Simplified Chinese visibleDescription, x, y, width,
            height, and supportedClaimRefs. Its rectangle must exactly match a successful crop tool observation. Return
            an empty regions array if no compact crop visibly helps, the crop conflicts with the claim, or evidence is
            insufficient. Do not expose reasoning.
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
                "{\"regions\":[]}",
                6,
                512));
        if (result.status() != RunStatus.COMPLETED) {
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }

        Optional<ModelGuide> parsed = VisualLocatorResponsePolicy.parseModelGuide(result.text());
        if (parsed.isEmpty()) return LocateGuideResult.unavailable(Diagnostic.MALFORMED_RESPONSE);
        if (parsed.get().regions().isEmpty()) {
            return LocateGuideResult.unavailable(Diagnostic.EXPLICIT_NO_REGION);
        }
        List<ObservedCrop> crops = observedCrops(result);
        List<LocatedRegion> accepted = parsed.get().regions().stream()
                .map(region -> accepted(region, request, crops))
                .flatMap(Optional::stream)
                .distinct()
                .limit(2)
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
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("visual Agent request serialization failed", exception);
        }
    }

    private Optional<LocatedRegion> accepted(
            ModelRegion region, VisualLocationRequest request, List<ObservedCrop> observedCrops) {
        if (region == null
                || !VisualLocatorResponsePolicy.containsChinese(region.label())
                || !VisualLocatorResponsePolicy.containsChinese(region.visibleDescription())) {
            return Optional.empty();
        }
        ObservedCrop crop = observedCrops.stream()
                .filter(candidate -> candidate.matches(region))
                .findFirst()
                .orElse(null);
        if (crop == null) return Optional.empty();
        List<VisualRegionLocator.Claim> claims = region.supportedClaimRefs().stream()
                .map(reference -> claim(reference, request))
                .filter(java.util.Objects::nonNull)
                .filter(claim -> claim.evidenceId().equals(crop.evidenceId()))
                .filter(claim -> claim.sourcePages().contains(crop.pageNumber()))
                .distinct()
                .toList();
        if (claims.isEmpty()) return Optional.empty();
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
                            .toList()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
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
        private boolean matches(ModelRegion region) {
            return pageNumber == region.pageNumber()
                    && x == region.x()
                    && y == region.y()
                    && width == region.width()
                    && height == region.height();
        }
    }
}
