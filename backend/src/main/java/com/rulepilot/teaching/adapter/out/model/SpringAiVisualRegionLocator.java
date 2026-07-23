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
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.awt.Color;
import java.awt.image.RasterFormatException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
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
    private static final long MAX_READER_CROP_AREA = 600_000L;
    private static final int CROP_SAMPLE_EDGE = 160;
    private static final int CROP_FOREGROUND_DISTANCE = 40;
    private static final double MIN_CATALOGED_LEGEND_SIGNAL = 0.22;

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
            aid. A candidate named "Cataloged visual anchor" is a compact boundary proposed by a previous image pass:
            inspect it yourself and prefer it when it visibly fits this exact step; do not expand into adjacent score
            rows, cards, or diagrams. A candidate can include a visual retrieval hint from a previous image pass. It is
            only a search hint: inspect the supplied page yourself, and never report an object because the hint says it
            exists. When several
            crops are relevant, return at most two distinct anchors that work together: prefer an
            icon or component group with its printed legend, then a worked state, flow, or layout that shows the player
            what to do. A diagram or icon group is useful even if its meaning is explained by the cited text rather
            than printed inside the crop. For an icon rule, prefer one compact crop containing the complete icon or
            icon group and its adjacent printed label, legend, arrow, or state when present. Small icon crops are
            welcome when the icons remain visually distinct; never return a word-only label as an icon crop. Return the
            player-facing viewport, not a microscopic detection box: normally include enough surrounding card, legend,
            arrow, board state, or diagram for the object to be recognisable. A tight icon may be small only when its
            nearby visual context is genuinely absent; never return a word-only label.
            Crop tightly around that visual handle and its direct labels. Exclude surrounding numbered prose, separate
            rules, contents/component-count lists, and empty page area. Text may remain only when it labels the pictured
            icon, card, or diagram. Do not call an overview photograph a worked action state: when a claim says a player
            places, moves, removes, or transforms a piece, the crop must literally show that piece in the relevant board
            area, an arrow, or a before-and-after state.
            For a claim about one named scoring pattern, a useful crop contains only that pattern's complete card row or
            compact card group and its directly adjacent score reference. Do not include another animal, faction, or
            category's examples, even partially above or below the target group; return no crop rather than a mixed
            scoring column.
            In visibleDescription, enumerate the literal icon/label relationship a player should look at (for example,
            "a dice icon beside a paint icon with a right arrow"), preferably in natural Simplified Chinese and without
            explaining its game effect. If the page's own labels are in another language, a concise literal observation
            in that language is acceptable; never invent a translation or an effect.
            Each supplied claim lists sourcePages. Choose a supportedClaimRef whose sourcePages includes the crop page;
            this keeps the printed rule and the pictured object together. The claim text begins with its exact step
            heading. Each C reference is one exact lesson step, even if several steps share a page or source chunk.
            Choose the C whose step is directly helped by the crop; a crop must visibly distinguish that step from its
            adjacent steps, not merely depict a later or earlier part of the same overall procedure. Do not attach a
            resource legend to a player-board step, a construction example to a resource-naming step, or a scoring
            example to an end-condition step. A tie-break crop must visibly show the comparison that resolves the tie,
            the winner, or the component counted to break the tie; a normal score table is never a tie-break
            illustration. If the crop helps another supplied step, cite that step's C reference instead. Do not use a
            similarly named component from a different page as a substitute.
            Coordinates use a top-left 0-1000 page coordinate system. pageNumber must be one supplied page; x and y are
            at least 0; width and height are at least 20; the rectangle must remain inside the page; label is at most 80
            characters. label and visibleDescription must both name a literal visible item, not repeat the lesson claim.
            visibleDescription is required and at most 240 characters. supportedClaimRefs must contain only C1, C2, etc.
            """;

    /**
     * Qwen follows compact object-localization requests more reliably than a long list of exceptional cases. Keep this
     * contract deliberately narrow: the caller already supplies one exact lesson step and page-scoped candidates.
     */
    static final String QWEN_SYSTEM = """
            You ground one exact board-game rule step in one or two supplied rulebook page images. Return JSON only:
            {"regions":[{"pageNumber":4,"label":"literal visible item","visibleDescription":"literal visible
            observation","x":0,"y":0,"width":0,"height":0,"supportedClaimRefs":["C1"]}]}.
            Find a compact player-facing object that directly helps the supplied C claim: a named component, card,
            icon group, board state, arrow, quantity, or worked example. The crop must visibly contain that object,
            not merely nearby prose or decorative art. Crop only the relevant object and its direct labels: do not
            return a whole page and keep width * height at or below 600000 in the 0-1000 page coordinate system.
            pageNumber must be supplied and supportedClaimRefs must be only the offered C references whose source page
            matches the crop. label and visibleDescription describe only literal visible content, never the rule effect.
            Use concise Simplified Chinese when natural; the page's own language is acceptable for literal names.
            Return {"regions":[]} only when the page has no concrete visual handle for this exact step.
            """;

    private static final String CROP_VERIFIER_SYSTEM = """
            You verify whether an exact rulebook crop is worth showing beside one exact player rule. Inspect only the
            supplied crop images and their offered claims. Accept a crop only when the crop itself visibly helps that
            exact claim: it must show the named card, faction, component, icon, condition, score reference, action
            state, or spatial relationship that distinguishes the claim from a neighbouring rule on the same page. A
            real but unrelated faction portrait, card, score track, board region, or example is a rejection. In
            particular, if a claim tells a player to play or activate a named card, the crop must show that card or its
            literal face, rather than another character or card on the page. A word-only title, printed label, prose
            box, dotted boundary, or empty background is never enough. Do not infer missing cards, icons, or claim
            relevance from a crop label. Return one JSON object with acceptedCropRefs as an array containing only the
            accepted R references.
            """;

    /** Qwen should judge a crop as a recognition aid, not demand an independent proof of each rule sentence. */
    static final String QWEN_CROP_VERIFIER_SYSTEM = """
            You make the final relevance check for a compact board-game rulebook crop. The first pass already checked
            page, claim reference, and coordinates. Inspect the crop and its one exact claim. Accept it when a player
            can literally see the component, icon group, card face, board layout, marker group, arrow, score reference,
            or worked state needed to recognise that claim. The crop need not independently show every procedural word
            in the claim. Reject only when it is word-only/prose-only/decorative, or visibly belongs to a different
            named card, faction, component, score example, or rule on the same page. If the claim names a particular
            card, accept only that card; if it names a component group, accept the literal group. Return JSON only:
            {"acceptedCropRefs":["R1"]} or {"acceptedCropRefs":[]}.
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
        if (!first.guide().regions().isEmpty()) {
            List<LocatedRegion> compactFirst = withoutOversizedReaderViewports(first.guide().regions());
            if (!compactFirst.isEmpty()) {
                return withCatalogedAnchorFallback(
                        request, owner, confirmedExactStepCrops(request, compactFirst, owner));
            }
            log.info("Retrying visual locator to tighten a broad reader crop for section {}", request.sectionTitle());
            GuideAttempt tightened = locateGuideOnce(request, owner, tightReaderViewportInstruction());
            List<LocatedRegion> compactTightened = withoutOversizedReaderViewports(tightened.guide().regions());
            if (!compactTightened.isEmpty()) {
                return withCatalogedAnchorFallback(
                        request, owner, confirmedExactStepCrops(request, compactTightened, owner));
            }
            return withCatalogedAnchorFallback(request, owner, LocateGuideResult.unavailable(Diagnostic.OVERSIZED_REGION));
        }
        if (!first.retryable()) return withCatalogedAnchorFallback(request, owner, first.guide());
        log.info("Retrying visual locator after a rejected response for section {}", request.sectionTitle());
        GuideAttempt retried = locateGuideOnce(request, owner, retryInstruction(first.rejection()));
        if (retried.guide().regions().isEmpty()) return withCatalogedAnchorFallback(request, owner, retried.guide());
        return withCatalogedAnchorFallback(
                request, owner, confirmedExactStepCrops(request, retried.guide().regions(), owner));
    }

    /**
     * Page catalogs are built from the rendered rulebook image and retain compact literal anchors. They used to act
     * only as a search hint: an otherwise useful icon or worked state disappeared whenever the broad page locator
     * abstained or returned malformed coordinates. Reusing an anchor is safe only after the original crop itself is
     * shown to the exact-step verifier; an anchor can never become a player-facing rule merely because it was stored.
     */
    private LocateGuideResult withCatalogedAnchorFallback(
            VisualLocationRequest request, String owner, LocateGuideResult original) {
        if (!original.regions().isEmpty()) return original;
        for (Candidate candidate : request.candidates()) {
            Optional<LocatedRegion> anchorRegion = catalogedAnchorRegion(request, candidate);
            if (anchorRegion.isEmpty()) continue;
            LocatedRegion region = anchorRegion.get();
            if (requiresTighterReaderViewport(region) || !hasEnoughRenderedVisualSignal(request, region)) continue;
            VisualRegionLocator.PageImage page = request.pages().stream()
                    .filter(image -> image.pageNumber() == region.pageNumber())
                    .findFirst()
                    .orElse(null);
            byte[] crop = croppedPng(page, region);
            if (crop == null) continue;
            Optional<Boolean> verified = confirmExactCrop(
                    request, region, "R1", new CropImage(region.pageNumber(), region.label(), crop), owner);
            if (verified.orElse(false)) {
                log.info(
                        "Recovered a verified cataloged visual anchor for section {} on page {}",
                        request.sectionTitle(),
                        region.pageNumber());
                return LocateGuideResult.found(List.of(region));
            }
        }
        return original;
    }

    static Optional<LocatedRegion> catalogedAnchorRegion(VisualLocationRequest request, Candidate candidate) {
        if (request == null || candidate == null || candidate.catalogedAnchor() == null) return Optional.empty();
        var anchor = candidate.catalogedAnchor();
        List<VisualRegionLocator.Claim> supportedClaims = request.claims().stream()
                .filter(claim -> sourceIncludes(claim, candidate.pageNumber()))
                .toList();
        if (supportedClaims.isEmpty() || supportedClaims.stream().anyMatch(claim -> claim.stepPosition() < 1)) {
            return Optional.empty();
        }
        return Optional.of(new LocatedRegion(
                candidate.pageNumber(),
                anchor.label(),
                anchor.visibleDescription(),
                anchor.x(),
                anchor.y(),
                anchor.width(),
                anchor.height(),
                supportedClaims.stream().map(VisualRegionLocator.Claim::evidenceId).distinct().toList(),
                supportedClaims.stream().map(VisualRegionLocator.Claim::stepPosition).distinct().toList()));
    }

    /**
     * A whole-page locator can identify a real object that belongs to a neighbouring rule. Inspect the exact rendered
     * crop once more beside its exact claim before publishing it, so a same-page faction, card, or worked example does
     * not become a misleading illustration for another step.
     */
    private LocateGuideResult confirmedExactStepCrops(
            VisualLocationRequest request, List<LocatedRegion> regions, String owner) {
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))
                && !qwenNeedsExactCropReview(claimsForExactCrop(request, regions.getFirst()))) {
            // The first pass already tied this routine recognition aid to its exact page, evidence, and step. Qwen's
            // second visual pass is deliberately reserved for rules where a neighbouring card, faction, score, or
            // outcome image would materially mislead a player.
            return LocateGuideResult.found(List.of(regions.getFirst()));
        }
        Map<String, CropImage> crops = croppedImages(request, regions);
        if (crops.size() == regions.size()) {
            for (int index = 0; index < regions.size(); index++) {
                String reference = "R" + (index + 1);
                Optional<Boolean> confirmed = confirmExactCrop(
                        request, regions.get(index), reference, crops.get(reference), owner);
                if (confirmed.isEmpty()) return retainFirstGroundedCrops(request, regions);
                if (confirmed.get()) return LocateGuideResult.found(List.of(regions.get(index)));
            }
            return LocateGuideResult.unavailable(Diagnostic.SEMANTIC_REJECTED);
        }
        return retainFirstGroundedCrops(request, regions);
    }

    private LocateGuideResult retainFirstGroundedCrops(
            VisualLocationRequest request, List<LocatedRegion> regions) {
        // The first pass still passed page, claim, geometry, and literal-visual checks. A transient failure in the
        // optional second opinion must not erase that grounded aid; an explicit verifier rejection is the only signal
        // that removes it. This keeps the text-first speed boundary while preserving useful visual coverage.
        log.info("Exact-step crop verification was unavailable for section {}; retaining the first grounded crop", request.sectionTitle());
        return LocateGuideResult.found(regions);
    }

    static boolean qwenNeedsExactCropReview(List<VisualRegionLocator.Claim> claims) {
        String text = claims.stream()
                .map(VisualRegionLocator.Claim::text)
                .map(SpringAiVisualRegionLocator::exactStepHeading)
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(java.util.Locale.ROOT);
        return text.contains("卡牌")
                || text.contains("统治卡")
                || text.contains("打出")
                || text.contains("激活")
                || text.contains("使用")
                || text.contains("阵营")
                || text.contains("计分")
                || text.contains("分数")
                || text.contains("胜利")
                || text.contains("结束")
                || text.contains("平局")
                || text.contains("card")
                || text.contains("play")
                || text.contains("activate")
                || text.contains("faction")
                || text.contains("score")
                || text.contains("win")
                || text.contains("end")
                || text.contains("tie");
    }

    private static String exactStepHeading(String claim) {
        if (claim == null || claim.isBlank()) return "high-risk-unreadable-heading";
        int opening = claim.indexOf('（');
        int closing = claim.indexOf('）', opening + 1);
        if (opening >= 0 && closing > opening + 1) return claim.substring(opening + 1, closing);
        opening = claim.indexOf('(');
        closing = claim.indexOf(')', opening + 1);
        return opening >= 0 && closing > opening + 1
                ? claim.substring(opening + 1, closing)
                : "high-risk-unreadable-heading";
    }

    /** One crop and one exact claim per call prevents a vision model from confusing R references across images. */
    private Optional<Boolean> confirmExactCrop(
            VisualLocationRequest request,
            LocatedRegion region,
            String reference,
            CropImage crop,
            String owner) {
        try {
            boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
            var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
            if (qwen) {
                prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
            }
            String content = prompt
                    .system(qwen ? QWEN_CROP_VERIFIER_SYSTEM : CROP_VERIFIER_SYSTEM)
                    .user(user -> {
                        user.text("Crop reference: {crop}. Return only the JSON object.")
                                .param("crop", Map.of(
                                        "ref", reference,
                                        "pageNumber", crop.pageNumber(),
                                        "label", crop.label(),
                                        "claims", claimsForExactCrop(request, region).stream()
                                                .map(claim -> Map.of(
                                                        "stepPosition", claim.stepPosition(),
                                                        "text", claim.text()))
                                                .toList()));
                        user.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(crop.content()));
                    })
                    .call()
                    .content();
            Optional<Boolean> verified = acceptedCropReferences(content, Set.of(reference))
                    .map(accepted -> accepted.contains(reference));
            if (qwen) {
                log.info(
                        "Qwen exact-crop verdict for section {} at page {} ({}, {}, {}, {}): {}",
                        request.sectionTitle(),
                        region.pageNumber(),
                        region.x(),
                        region.y(),
                        region.width(),
                        region.height(),
                        verified.orElse(null));
            }
            return verified;
        } catch (RuntimeException failure) {
            log.info("Exact visual crop verification was unavailable: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<String, CropImage> croppedImages(VisualLocationRequest request, List<LocatedRegion> regions) {
        Map<Integer, VisualRegionLocator.PageImage> pages = request.pages().stream()
                .collect(java.util.stream.Collectors.toMap(
                        VisualRegionLocator.PageImage::pageNumber, page -> page, (first, ignored) -> first));
        Map<String, CropImage> crops = new LinkedHashMap<>();
        for (int index = 0; index < regions.size(); index++) {
            LocatedRegion region = regions.get(index);
            VisualRegionLocator.PageImage page = pages.get(region.pageNumber());
            byte[] content = croppedPng(page, region);
            if (content == null) return Map.of();
            crops.put("R" + (index + 1), new CropImage(region.pageNumber(), region.label(), content));
        }
        return Map.copyOf(crops);
    }

    static List<VisualRegionLocator.Claim> claimsForExactCrop(
            VisualLocationRequest request, LocatedRegion region) {
        Set<UUID> supportedEvidence = Set.copyOf(region.supportedEvidenceIds());
        return request.claims().stream()
                .filter(claim -> supportedEvidence.contains(claim.evidenceId()))
                .filter(claim -> region.supportedStepPositions().isEmpty()
                        || region.supportedStepPositions().contains(claim.stepPosition()))
                .filter(claim -> sourceIncludes(claim, region.pageNumber()))
                .toList();
    }

    private static byte[] croppedPng(VisualRegionLocator.PageImage page, LocatedRegion region) {
        if (page == null) return null;
        try (var input = new ByteArrayInputStream(page.content()); var output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) return null;
            int x = region.x() * source.getWidth() / 1_000;
            int y = region.y() * source.getHeight() / 1_000;
            int right = Math.min(source.getWidth(), Math.max(x + 1, (region.x() + region.width()) * source.getWidth() / 1_000));
            int bottom = Math.min(source.getHeight(), Math.max(y + 1, (region.y() + region.height()) * source.getHeight() / 1_000));
            BufferedImage crop = source.getSubimage(x, y, right - x, bottom - y);
            if (!ImageIO.write(crop, "png", output)) return null;
            return output.toByteArray();
        } catch (IOException | RasterFormatException unreadable) {
            return null;
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

    static boolean hasEnoughRenderedVisualSignal(VisualLocationRequest request, LocatedRegion region) {
        VisualRegionLocator.PageImage page = request.pages().stream()
                .filter(candidate -> candidate.pageNumber() == region.pageNumber())
                .findFirst()
                .orElse(null);
        byte[] content = croppedPng(page, region);
        if (content == null) return true;
        try (var input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) return true;
            return foregroundShare(image) >= MIN_CATALOGED_LEGEND_SIGNAL;
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static double foregroundShare(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / CROP_SAMPLE_EDGE);
        int stepY = Math.max(1, image.getHeight() / CROP_SAMPLE_EDGE);
        int[] histogram = new int[16 * 16 * 16];
        int samples = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                histogram[quantizedRgb(image.getRGB(x, y))]++;
                samples++;
            }
        }
        if (samples == 0) return 1.0;
        int dominant = 0;
        for (int index = 1; index < histogram.length; index++) {
            if (histogram[index] > histogram[dominant]) dominant = index;
        }
        int red = ((dominant >> 8) & 15) * 16;
        int green = ((dominant >> 4) & 15) * 16;
        int blue = (dominant & 15) * 16;
        int foreground = 0;
        int threshold = CROP_FOREGROUND_DISTANCE * CROP_FOREGROUND_DISTANCE;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                Color color = new Color(image.getRGB(x, y));
                int distance = (color.getRed() - red) * (color.getRed() - red)
                        + (color.getGreen() - green) * (color.getGreen() - green)
                        + (color.getBlue() - blue) * (color.getBlue() - blue);
                if (distance > threshold) foreground++;
            }
        }
        return (double) foreground / samples;
    }

    private static int quantizedRgb(int rgb) {
        Color color = new Color(rgb);
        return (color.getRed() >> 4) * 256 + (color.getGreen() >> 4) * 16 + (color.getBlue() >> 4);
    }

    private record CropImage(int pageNumber, String label, byte[] content) {
        private CropImage {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * A tall icon crop normally means the model included an adjacent numbered rule paragraph below a horizontal legend.
     * Asking once for a tighter retry is cheaper and safer than trimming coordinates heuristically and cutting off a
     * diagram whose visual content happens to be vertical.
     */
    static List<LocatedRegion> withoutOversizedIconLegends(List<LocatedRegion> regions) {
        return regions.stream().filter(region -> !requiresTighterIconViewport(region)).toList();
    }

    static List<LocatedRegion> withoutOversizedReaderViewports(List<LocatedRegion> regions) {
        return regions.stream().filter(region -> !requiresTighterReaderViewport(region)).toList();
    }

    static boolean requiresTighterReaderViewport(LocatedRegion region) {
        return requiresTighterIconViewport(region)
                || requiresTighterScoreExampleViewport(region)
                || (long) region.width() * region.height() > MAX_READER_CROP_AREA;
    }

    static boolean requiresTighterIconViewport(LocatedRegion region) {
        String observation = (region.label() + " " + region.visibleDescription()).toLowerCase(java.util.Locale.ROOT);
        boolean iconLegend = observation.contains("图例")
                || observation.contains("对照")
                || (observation.contains("图标") && observation.contains("名称"))
                || observation.matches(".*\\b(legend|icon group)\\b.*");
        return iconLegend && region.height() * 10 > region.width() * 14;
    }

    /**
     * A nearly square or portrait score-example column is often a stack of several animals' cards, not the one scoring
     * pattern named by the current rule. Ask vision to split it before publishing a misleading "example" crop.
     * Portrait cards without score-example language remain valid reader aids.
     */
    static boolean requiresTighterScoreExampleViewport(LocatedRegion region) {
        String observation = (region.label() + " " + region.visibleDescription()).toLowerCase(java.util.Locale.ROOT);
        boolean scoreExample = (observation.contains("计分")
                        || observation.contains("得分")
                        || observation.contains("分数")
                        || observation.matches(".*\\b(score|scoring|points)\\b.*"))
                && (observation.contains("示例") || observation.matches(".*\\bexample\\b.*"));
        return scoreExample && region.width() <= 340 && region.height() * 4 > region.width() * 3;
    }

    static String tightIconViewportInstruction() {
        return "The previous icon crop was too tall and likely included unrelated numbered prose. Return a tighter rectangle around only the literal icons and their direct labels. Do not include adjacent steps, paragraphs, component counts, or a page footer. If that tight icon crop is not available, return an empty regions array.";
    }

    static String tightReaderViewportInstruction() {
        return "The previous crop occupied too much of the page, was a portrait strip spanning neighbouring score examples, or included unrelated prose. Return a compact rectangle around only the literal diagram, component group, icon legend, one score reference, or worked state that directly helps the cited step. If the claim names one scoring pattern, include only that pattern's card row or compact group; do not include another animal's examples or partial rows above or below. Exclude surrounding instructions, component-count lists, empty page area, and page furniture. If no compact player-facing crop is available, return an empty regions array.";
    }

    private GuideAttempt locateGuideOnce(VisualLocationRequest request, String owner, String correction) {
        boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if (qwen) {
            prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
        }
        String content = prompt
                .system(qwen ? QWEN_SYSTEM : SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    {candidateLabel}: {candidates}
                                    {correction}
                                    Return one JSON object only with a regions array containing one or two objects. Each object
                                    needs pageNumber, label, visibleDescription, x, y, width, height and supportedClaimRefs.
                                    If no useful crop exists, return a JSON object whose regions array is empty.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", IntStream.range(0, request.claims().size())
                                    .mapToObj(index -> Map.of(
                                            "ref", "C" + (index + 1),
                                            "stepPosition", request.claims().get(index).stepPosition(),
                                            "text", request.claims().get(index).text(),
                                            "sourcePages", request.claims().get(index).sourcePages()))
                                    .toList())
                            .param("candidateLabel", qwen
                                    ? "Candidate pages (visual hints only; choose your own compact crop bounds)"
                                    : "Candidate rectangles")
                            .param("candidates", candidatePromptPayload(request.candidates(), qwen))
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
        if (qwen) {
            log.info(
                    "Qwen visual candidates for section {}: {}",
                    request.sectionTitle(),
                    parsed.get().regions().stream()
                            .map(region -> "p" + region.pageNumber() + "@" + region.x() + "," + region.y()
                                    + "+" + region.width() + "x" + region.height()
                                    + "=" + region.supportedClaimRefs())
                            .toList());
        }
        List<LocatedRegion> accepted = new java.util.ArrayList<>();
        Rejection rejected = Rejection.NONE;
        for (ModelRegion response : parsed.get().regions()) {
            List<VisualRegionLocator.Claim> referencedClaims = response.supportedClaimRefs().stream()
                    .map(ref -> claim(ref, request))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            List<VisualRegionLocator.Claim> supportedClaims = pageScopedClaims(
                    response.pageNumber(), referencedClaims, request.claims());
            List<UUID> supported = supportedClaims.stream().map(VisualRegionLocator.Claim::evidenceId).toList();
            List<Integer> supportedStepPositions = supportedClaims.stream()
                    .map(VisualRegionLocator.Claim::stepPosition)
                    .filter(position -> position > 0)
                    .distinct()
                    .toList();
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
                        supported,
                        supportedStepPositions);
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

    private static String visualHint(String sourceText) {
        String normalized = sourceText == null ? "" : sourceText.strip();
        return normalized.startsWith("Cataloged visual anchor") ? "cataloged visual anchor" : "page visual context";
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
     * A crop can only enhance a rule whose source page contains that crop. If the model names a neighbouring claim,
     * recovery is safe only when exactly one supplied lesson step belongs to the crop page; otherwise attaching the
     * crop would silently turn a useful image into an explanation for the wrong rule.
     */
    static List<VisualRegionLocator.Claim> pageScopedClaims(
            int pageNumber,
            List<VisualRegionLocator.Claim> referencedClaims,
            List<VisualRegionLocator.Claim> availableClaims) {
        List<VisualRegionLocator.Claim> samePageReferences = referencedClaims.stream()
                .filter(claim -> sourceIncludes(claim, pageNumber))
                .toList();
        if (!samePageReferences.isEmpty()) return samePageReferences;
        List<VisualRegionLocator.Claim> pageClaims = availableClaims.stream()
                .filter(claim -> sourceIncludes(claim, pageNumber))
                .toList();
        // A single-step request has one unambiguous page-scoped claim. Some vision providers omit C1 even while
        // returning a correct literal crop, so recover that safe association instead of spending another full image
        // request on formatting. Never make this inference when two neighbouring lesson steps share a page.
        return pageClaims.size() == 1 ? pageClaims : List.of();
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
