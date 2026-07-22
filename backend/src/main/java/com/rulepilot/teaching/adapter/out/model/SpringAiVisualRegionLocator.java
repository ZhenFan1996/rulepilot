package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
public class SpringAiVisualRegionLocator implements VisualRegionLocator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiVisualRegionLocator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM = """
            You are a rulebook visual locator. Inspect only the supplied page images and candidate rectangles.
            Return one compact region only when it visibly supports one or more supplied claims. Alongside it, provide a
            short Chinese visibleDescription of only what a player can literally see inside that crop: components,
            printed labels, icons, arrows, quantities, or their spatial relationship. This is an image observation, not
            a rule explanation: do not infer an icon's game effect, paraphrase a rule, add facts, or alter claims. If
            no candidate is useful, return null.
            Candidate rectangles are allowed boundaries, not compulsory text targets. A candidate named "Cited page N
            visual context" lets you select a diagram, board layout, table, icon group, component, or worked example
            anywhere on that cited page. A section heading, page title, or paragraph-only crop is never a useful visual
            aid: return null rather than selecting one. When several crops support the claim, prefer a compact icon,
            component, flow, or worked state that a new player can identify at the table.
            Coordinates use a top-left 0-1000 page coordinate system. pageNumber must be one supplied page; x and y are
            at least 0; width and height are at least 20; the rectangle must remain inside the page; label is at most 80
            characters. visibleDescription is at most 240 characters. supportedClaimRefs must contain only C1, C2, etc.
            """;

    private final RuntimeModelConfiguration models;

    public SpringAiVisualRegionLocator(RuntimeModelConfiguration models) {
        this.models = models;
    }

    @Override
    public Optional<LocatedRegion> locate(VisualLocationRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            log.info("Visual locator is unavailable for section {}", request.sectionTitle());
            return Optional.empty();
        }
        LocateAttempt first = locateOnce(request, owner, "");
        if (first.region().isPresent() || !first.retryable()) return first.region();
        log.info("Retrying visual locator after a rejected response for section {}", request.sectionTitle());
        return locateOnce(request, owner, retryInstruction(first.rejection())).region();
    }

    private LocateAttempt locateOnce(VisualLocationRequest request, String owner, String correction) {
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(qwenJsonOptions());
        }
        String content = prompt
                .system(SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    Candidate rectangles: {candidates}
                                    {correction}
                                    Return JSON with pageNumber, label, visibleDescription, x, y, width, height and
                                    supportedClaimRefs; or null.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", IntStream.range(0, request.claims().size())
                                    .mapToObj(index -> Map.of("ref", "C" + (index + 1), "text", request.claims().get(index).text()))
                                    .toList())
                            .param("candidates", request.candidates())
                            .param("correction", correction);
                    request.pages().forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        Optional<ModelRegion> parsed = parseModelRegion(content);
        if (parsed.isEmpty()) {
            log.info("Visual locator returned no usable JSON for section {}", request.sectionTitle());
            return new LocateAttempt(Optional.empty(), !isExplicitNoRegion(content), Rejection.MALFORMED_JSON);
        }
        ModelRegion response = parsed.get();
        List<UUID> supported = response.supportedClaimRefs().stream()
                .map(ref -> claimId(ref, request))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (supported.isEmpty() || request.pages().stream().noneMatch(page -> page.pageNumber() == response.pageNumber())) {
            log.info("Visual locator returned an unsupported claim or page for section {}", request.sectionTitle());
            return new LocateAttempt(Optional.empty(), true, Rejection.UNSUPPORTED_SCOPE);
        }
        try {
            response = normalizedGeometry(response);
            return new LocateAttempt(Optional.of(new LocatedRegion(
                    response.pageNumber(), response.label(), response.visibleDescription(), response.x(), response.y(),
                    response.width(), response.height(), supported)), false,
                    Rejection.NONE);
        } catch (IllegalArgumentException invalidModelOutput) {
            log.info("Rejected invalid visual locator output for section {}: {}", request.sectionTitle(), invalidModelOutput.getMessage());
            return new LocateAttempt(Optional.empty(), true, Rejection.INVALID_GEOMETRY);
        }
    }

    static ModelRegion normalizedGeometry(ModelRegion region) {
        int x = Math.max(0, Math.min(980, region.x()));
        int y = Math.max(0, Math.min(980, region.y()));
        int width = Math.max(20, Math.min(region.width(), 1_000 - x));
        int height = Math.max(20, Math.min(region.height(), 1_000 - y));
        return new ModelRegion(
                region.pageNumber(),
                region.label(),
                region.visibleDescription(),
                x,
                y,
                width,
                height,
                region.supportedClaimRefs());
    }

    private UUID claimId(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        int index = Integer.parseInt(reference.substring(1)) - 1;
        return index >= 0 && index < request.claims().size() ? request.claims().get(index).evidenceId() : null;
    }

    static OpenAiChatOptions.Builder qwenJsonOptions() {
        return OpenAiChatOptions.builder()
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    static boolean isExplicitNoRegion(String content) {
        return content != null && content.strip().equals("null");
    }

    static String retryInstruction(Rejection rejection) {
        return switch (rejection) {
            case MALFORMED_JSON -> "The previous response was not a readable JSON object. Return one JSON object only, or null.";
            case UNSUPPORTED_SCOPE -> "The previous response used an unavailable page or claim reference. Use only the supplied page numbers and C1, C2, etc. claim references.";
            case INVALID_GEOMETRY -> "The previous rectangle was outside the page. Return a new JSON candidate only after verifying x + width <= 1000 and y + height <= 1000.";
            case NONE -> "";
        };
    }

    static Optional<ModelRegion> parseModelRegion(String content) {
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
            return Optional.ofNullable(JSON.readValue(json, ModelRegion.class));
        } catch (JsonProcessingException invalidJson) {
            log.debug("Rejected non-JSON visual locator output");
            return Optional.empty();
        }
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

    enum Rejection {
        NONE,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY
    }

    private record LocateAttempt(Optional<LocatedRegion> region, boolean retryable, Rejection rejection) {}
}
