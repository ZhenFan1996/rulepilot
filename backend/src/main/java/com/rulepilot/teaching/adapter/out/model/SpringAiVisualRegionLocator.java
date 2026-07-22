package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocateResult;
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
            Return one or two compact regions only when each gives a player a direct visual handle on this section or
            its supplied claims: a named component, printed condition, icon, arrow, quantity, spatial setup, or worked
            state. It
            need not independently prove every procedural sentence: the cited text remains the only source for a
            rule's effect. Alongside it, provide a short Chinese visibleDescription of only what a player can literally
            see inside that crop. This is an image observation, not a rule explanation: do not infer an icon's game
            effect, paraphrase a rule, add facts, or alter claims. Never select a generic setup, board, card, or
            decorative illustration merely because it shares a cited page with a timing, tie-break, or other text-only
            claim. A crop may still be useful when it gives the player a recognition handle for the cited text: a card
            anatomy, named component, icon group, board area, or worked state. It need not depict the full procedure
            or print the rule effect itself. Return an empty JSON object only when the cited page has no such concrete
            player-facing visual handle.
            Candidate rectangles are allowed boundaries, not compulsory text targets. A candidate named "Cited page N
            visual context" lets you select a diagram, board layout, table, icon group, component, or worked example
            anywhere on that cited page. A section heading, page title, or paragraph-only crop is never a useful visual
            aid. When several crops are relevant, return at most two distinct anchors that work together: prefer an
            icon or component group with its printed legend, then a worked state, flow, or layout that shows the player
            what to do. A diagram or icon group is useful even if its meaning is explained by the cited text rather
            than printed inside the crop. For an icon rule, prefer one compact crop containing the complete icon or
            icon group and its adjacent printed label, legend, arrow, or state when present. Small icon crops are
            welcome when the icons remain visually distinct; never return a word-only label as an icon crop. Return the
            player-facing viewport, not a microscopic detection box: normally include enough surrounding card, legend,
            arrow, board state, or diagram for the object to be recognisable. A tight icon may be small only when its
            nearby visual context is genuinely absent; never return a word-only label.
            In visibleDescription, enumerate the literal icon/label relationship a player should look at (for example,
            "a dice icon beside a paint icon with a right arrow"), in natural Simplified Chinese, without explaining its
            game effect.
            Each supplied claim lists sourcePages. Choose a supportedClaimRef whose sourcePages includes the crop page;
            this keeps the printed rule and the pictured object together. Do not use a similarly named component from a
            different page as a substitute.
            Coordinates use a top-left 0-1000 page coordinate system. pageNumber must be one supplied page; x and y are
            at least 0; width and height are at least 20; the rectangle must remain inside the page; label is at most 80
            characters. label and visibleDescription must both name a literal visible item, not repeat the lesson claim.
            visibleDescription is required and at most 240 characters. supportedClaimRefs must contain only C1, C2, etc.
            """;

    private final RuntimeModelConfiguration models;

    public SpringAiVisualRegionLocator(RuntimeModelConfiguration models) {
        this.models = models;
    }

    @Override
    public Optional<LocatedRegion> locate(VisualLocationRequest request) {
        return locateWithResult(request).region();
    }

    @Override
    public LocateResult locateWithResult(VisualLocationRequest request) {
        LocateGuideResult guide = locateGuideWithResult(request);
        return guide.regions().stream()
                .findFirst()
                .map(LocateResult::found)
                .orElseGet(() -> LocateResult.unavailable(guide.diagnostic()));
    }

    @Override
    public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            log.info("Visual locator is unavailable for section {}", request.sectionTitle());
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }
        GuideAttempt first = locateGuideOnce(request, owner, "");
        if (!first.guide().regions().isEmpty() || !first.retryable()) return first.guide();
        log.info("Retrying visual locator after a rejected response for section {}", request.sectionTitle());
        return locateGuideOnce(request, owner, retryInstruction(first.rejection())).guide();
    }

    private GuideAttempt locateGuideOnce(VisualLocationRequest request, String owner, String correction) {
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
        }
        String content = prompt
                .system(SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    Candidate rectangles: {candidates}
                                    {correction}
                                    Return one JSON object only with a regions array containing one or two objects. Each object
                                    needs pageNumber, label, visibleDescription, x, y, width, height and supportedClaimRefs.
                                    If no useful crop exists, return a JSON object whose regions array is empty.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", IntStream.range(0, request.claims().size())
                                    .mapToObj(index -> Map.of(
                                            "ref", "C" + (index + 1),
                                            "text", request.claims().get(index).text(),
                                            "sourcePages", request.claims().get(index).sourcePages()))
                                    .toList())
                            .param("candidates", request.candidates())
                            .param("correction", correction);
                    request.pages().forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        if (isExplicitNoRegion(content)) {
            return unavailableGuide(Rejection.EXPLICIT_NO_REGION, false);
        }
        Optional<ModelGuide> parsed = parseModelGuide(content);
        if (parsed.isEmpty()) {
            log.info("Visual locator returned no usable JSON for section {}", request.sectionTitle());
            return unavailableGuide(Rejection.MALFORMED_JSON, true);
        }
        if (parsed.get().regions().isEmpty()) return unavailableGuide(Rejection.EXPLICIT_NO_REGION, false);
        List<LocatedRegion> accepted = new java.util.ArrayList<>();
        Rejection rejected = Rejection.NONE;
        for (ModelRegion response : parsed.get().regions()) {
            if (!containsChinese(response.label()) || !containsChinese(response.visibleDescription())) {
                rejected = Rejection.NON_CHINESE_OBSERVATION;
                continue;
            }
            List<VisualRegionLocator.Claim> referencedClaims = response.supportedClaimRefs().stream()
                    .map(ref -> claim(ref, request))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            List<VisualRegionLocator.Claim> supportedClaims = pageScopedClaims(
                    response.pageNumber(), referencedClaims, request.claims());
            List<UUID> supported = supportedClaims.stream().map(VisualRegionLocator.Claim::evidenceId).toList();
            if (supported.isEmpty()
                    || request.pages().stream().noneMatch(page -> page.pageNumber() == response.pageNumber())) {
                rejected = Rejection.UNSUPPORTED_SCOPE;
                continue;
            }
            try {
                ModelRegion normalizedResponse = normalizedGeometry(response);
                LocatedRegion region = new LocatedRegion(
                        normalizedResponse.pageNumber(),
                        normalizedResponse.label(),
                        normalizedResponse.visibleDescription(),
                        normalizedResponse.x(),
                        normalizedResponse.y(),
                        normalizedResponse.width(),
                        normalizedResponse.height(),
                        supported);
                if (accepted.stream().noneMatch(existing -> sameRegion(existing, region))) accepted.add(region);
            } catch (IllegalArgumentException invalidModelOutput) {
                log.info("Rejected invalid visual locator output for section {}: {}", request.sectionTitle(), invalidModelOutput.getMessage());
                rejected = Rejection.INVALID_GEOMETRY;
            }
        }
        if (!accepted.isEmpty()) return new GuideAttempt(LocateGuideResult.found(accepted), false, Rejection.NONE);
        log.info("Visual locator returned no supported visual regions for section {}", request.sectionTitle());
        return unavailableGuide(rejected == Rejection.NONE ? Rejection.UNSUPPORTED_SCOPE : rejected, true);
    }

    private GuideAttempt unavailableGuide(Rejection rejection, boolean retryable) {
        return new GuideAttempt(LocateGuideResult.unavailable(diagnosticFor(rejection)), retryable, rejection);
    }

    private boolean sameRegion(LocatedRegion first, LocatedRegion second) {
        return first.pageNumber() == second.pageNumber()
                && first.x() == second.x()
                && first.y() == second.y()
                && first.width() == second.width()
                && first.height() == second.height();
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

    private VisualRegionLocator.Claim claim(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        int index = Integer.parseInt(reference.substring(1)) - 1;
        return index >= 0 && index < request.claims().size() ? request.claims().get(index) : null;
    }

    /**
     * Claim references from a visual model are a relevance hint, not an authority to attach a crop across pages.
     * A crop can only enhance a rule whose source page contains that crop. When the model names a neighbouring claim
     * in the same section, recover the matching page-scoped evidence deterministically instead of discarding a useful
     * component or icon observation.
     */
    static List<VisualRegionLocator.Claim> pageScopedClaims(
            int pageNumber,
            List<VisualRegionLocator.Claim> referencedClaims,
            List<VisualRegionLocator.Claim> availableClaims) {
        List<VisualRegionLocator.Claim> samePageReferences = referencedClaims.stream()
                .filter(claim -> sourceIncludes(claim, pageNumber))
                .toList();
        if (!samePageReferences.isEmpty()) return samePageReferences;
        if (referencedClaims.isEmpty()) return List.of();
        return availableClaims.stream().filter(claim -> sourceIncludes(claim, pageNumber)).toList();
    }

    private static boolean sourceIncludes(VisualRegionLocator.Claim claim, int pageNumber) {
        return claim.sourcePages().isEmpty() || claim.sourcePages().contains(pageNumber);
    }

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

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

    record ModelGuide(List<ModelRegion> regions) {
        ModelGuide {
            regions = regions == null ? List.of() : List.copyOf(regions.stream().limit(2).toList());
        }
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

    enum Rejection {
        NONE,
        EXPLICIT_NO_REGION,
        MALFORMED_JSON,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY,
        NON_CHINESE_OBSERVATION
    }

    private record GuideAttempt(LocateGuideResult guide, boolean retryable, Rejection rejection) {}
}
