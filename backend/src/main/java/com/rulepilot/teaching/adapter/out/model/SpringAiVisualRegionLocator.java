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
            Return one compact region only when it visibly supports one or more supplied claims. Do not explain rules,
            paraphrase text, add facts, or alter claims. If no candidate is useful, return null.
            Coordinates use a top-left 0-1000 page coordinate system. pageNumber must be one supplied page; x and y are
            at least 0; width and height are at least 20; the rectangle must remain inside the page; label is at most 80
            characters. supportedClaimRefs must contain only C1, C2, etc.
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
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(OpenAiChatOptions.builder().extraBody(Map.of("enable_thinking", false)));
        }
        String content = prompt
                .system(SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    Candidate rectangles: {candidates}
                                    Return JSON with pageNumber, label, x, y, width, height and supportedClaimRefs; or null.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", IntStream.range(0, request.claims().size())
                                    .mapToObj(index -> Map.of("ref", "C" + (index + 1), "text", request.claims().get(index).text()))
                                    .toList())
                            .param("candidates", request.candidates());
                    request.pages().forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        Optional<ModelRegion> parsed = parseModelRegion(content);
        if (parsed.isEmpty()) {
            log.info("Visual locator returned no usable JSON for section {}", request.sectionTitle());
            return Optional.empty();
        }
        ModelRegion response = parsed.get();
        List<UUID> supported = response.supportedClaimRefs().stream()
                .map(ref -> claimId(ref, request))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (supported.isEmpty() || request.pages().stream().noneMatch(page -> page.pageNumber() == response.pageNumber())) {
            log.info("Visual locator returned an unsupported claim or page for section {}", request.sectionTitle());
            return Optional.empty();
        }
        try {
            return Optional.of(new LocatedRegion(
                    response.pageNumber(), response.label(), response.x(), response.y(), response.width(), response.height(), supported));
        } catch (IllegalArgumentException invalidModelOutput) {
            log.info("Rejected invalid visual locator output for section {}: {}", request.sectionTitle(), invalidModelOutput.getMessage());
            return Optional.empty();
        }
    }

    private UUID claimId(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        int index = Integer.parseInt(reference.substring(1)) - 1;
        return index >= 0 && index < request.claims().size() ? request.claims().get(index).evidenceId() : null;
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
        try {
            return Optional.ofNullable(JSON.readValue(json, ModelRegion.class));
        } catch (JsonProcessingException invalidJson) {
            log.debug("Rejected non-JSON visual locator output");
            return Optional.empty();
        }
    }

    record ModelRegion(
            int pageNumber, String label, int x, int y, int width, int height, List<String> supportedClaimRefs) {
        ModelRegion {
            supportedClaimRefs = supportedClaimRefs == null ? List.of() : List.copyOf(supportedClaimRefs);
        }
    }
}
