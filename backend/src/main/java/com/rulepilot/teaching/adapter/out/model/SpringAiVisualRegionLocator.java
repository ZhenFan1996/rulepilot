package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.MediaDescriptor;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocateResult;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelGuide;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelRegion;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.Rejection;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
    private static final ObjectMapper TRACE_JSON = new ObjectMapper();
    private static final String TRACE_MEDIA_INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"purpose\",\"pageNumbers\"],\"properties\":{\"purpose\":{\"type\":\"string\"},\"pageNumbers\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}},\"additionalProperties\":false}";

    @Override
    public boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return !models.usesFake(Role.VISUAL, modelConfigurationOwner)
                && models.supportsVision(Role.VISUAL, modelConfigurationOwner);
    }

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
            or print the rule effect itself. Return {"regions":[]} only when the cited page has no such concrete
            player-facing visual handle; never return null or an empty object.
            Candidate rectangles are allowed boundaries, not compulsory text targets. A candidateKind of
            CITED_PAGE_CONTEXT lets you select a diagram, board layout, table, icon group, component, or worked example
            anywhere on that cited page. A section heading, page title, or paragraph-only crop is never a useful visual
            aid. A candidateKind of CATALOGED_VISUAL_ANCHOR includes a compact boundary proposed by a previous image pass:
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
            relevance from a crop label. If the crop unambiguously depicts the same subject and inputs as the claim
            but shows a different concrete printed value, quantity, identity, arrangement, or worked result, put its R
            reference in contradictedCropRefs instead of acceptedCropRefs. Missing detail is rejection, not
            contradiction. Return one JSON object containing both arrays.
            """;

    /** Qwen should judge a crop as a recognition aid, not demand an independent proof of each rule sentence. */
    static final String QWEN_CROP_VERIFIER_SYSTEM = """
            You make the final relevance check for a compact board-game rulebook crop. The first pass already checked
            page, claim reference, and coordinates. Inspect the crop and its one exact claim. Accept it when a player
            can literally see the component, icon group, card face, board layout, marker group, arrow, score reference,
            or worked state needed to recognise that claim. The crop need not independently show every procedural word
            in the claim. Reject only when it is word-only/prose-only/decorative, or visibly belongs to a different
            named card, faction, component, score example, or rule on the same page. If the claim names a particular
            card, accept only that card; if it names a component group, accept the literal group. Use
            contradictedCropRefs only when the crop unambiguously shows the same subject and inputs but a different
            printed value, quantity, identity, arrangement, or worked result. Missing detail is rejection, not
            contradiction. Return JSON only:
            {"acceptedCropRefs":["R1"],"contradictedCropRefs":[]} or
            {"acceptedCropRefs":[],"contradictedCropRefs":["R1"]}.
            """;

    private final RuntimeModelConfiguration models;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();

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
        return locateGuideWithResult(request, CaptureHandle.noop(), null);
    }

    @Override
    public LocateGuideResult locateGuideWithResult(
            VisualLocationRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        TraceInvocation trace = trace(capture, context);
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            log.info("Visual locator is unavailable for runId={} (reason=MODEL_UNAVAILABLE)", request.runId());
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }
        GuideAttempt first = locateGuideOnce(request, owner, "", trace);
        if (!first.guide().regions().isEmpty()) {
            List<LocatedRegion> compactFirst = VisualCropAcceptancePolicy.withoutOversizedReaderViewports(
                    first.guide().regions());
            if (!compactFirst.isEmpty()) {
                return withCatalogedAnchorFallback(
                        request,
                        owner,
                        confirmedExactStepCrops(request, compactFirst, owner, trace),
                        trace);
            }
            log.info("Retrying visual locator to tighten a broad reader crop for runId={}", request.runId());
            GuideAttempt tightened = locateGuideOnce(
                    request,
                    owner,
                    VisualCropAcceptancePolicy.tightReaderViewportInstruction(),
                    trace);
            List<LocatedRegion> compactTightened = VisualCropAcceptancePolicy.withoutOversizedReaderViewports(
                    tightened.guide().regions());
            if (!compactTightened.isEmpty()) {
                return withCatalogedAnchorFallback(
                        request,
                        owner,
                        confirmedExactStepCrops(request, compactTightened, owner, trace),
                        trace);
            }
            return withCatalogedAnchorFallback(
                    request,
                    owner,
                    LocateGuideResult.unavailable(Diagnostic.OVERSIZED_REGION),
                    trace);
        }
        if (!first.retryable()) return withCatalogedAnchorFallback(request, owner, first.guide(), trace);
        log.info(
                "Retrying visual locator after a rejected response for runId={} (reason={})",
                request.runId(),
                first.rejection());
        GuideAttempt retried = locateGuideOnce(
                request,
                owner,
                VisualLocatorResponsePolicy.retryInstruction(first.rejection()),
                trace);
        if (retried.guide().regions().isEmpty()) {
            return withCatalogedAnchorFallback(request, owner, retried.guide(), trace);
        }
        return withCatalogedAnchorFallback(
                request,
                owner,
                confirmedExactStepCrops(request, retried.guide().regions(), owner, trace),
                trace);
    }

    /**
     * Page catalogs are built from the rendered rulebook image and retain compact literal anchors. They used to act
     * only as a search hint: an otherwise useful icon or worked state disappeared whenever the broad page locator
     * abstained or returned malformed coordinates. Reusing an anchor is safe only after the original crop itself is
     * shown to the exact-step verifier; an anchor can never become a player-facing rule merely because it was stored.
     */
    private LocateGuideResult withCatalogedAnchorFallback(
            VisualLocationRequest request,
            String owner,
            LocateGuideResult original,
            TraceInvocation trace) {
        if (!original.regions().isEmpty()) return original;
        for (Candidate candidate : request.candidates()) {
            Optional<LocatedRegion> anchorRegion =
                    VisualCropAcceptancePolicy.catalogedAnchorRegion(request, candidate);
            if (anchorRegion.isEmpty()) continue;
            LocatedRegion region = anchorRegion.get();
            if (VisualCropAcceptancePolicy.requiresTighterReaderViewport(region)
                    || !VisualCropAcceptancePolicy.hasEnoughRenderedVisualSignal(request, region)) continue;
            VisualRegionLocator.PageImage page = request.pages().stream()
                    .filter(image -> image.pageNumber() == region.pageNumber())
                    .findFirst()
                    .orElse(null);
            byte[] crop = VisualCropAcceptancePolicy.croppedPng(page, region);
            if (crop == null) continue;
            Optional<CropVerdict> verified = confirmExactCrop(
                    request,
                    region,
                    "R1",
                    new CropImage(region.pageNumber(), region.label(), crop),
                    owner,
                    trace);
            if (verified.orElse(CropVerdict.REJECTED) == CropVerdict.ACCEPTED) {
                log.info(
                        "Recovered a verified cataloged visual anchor for runId={}",
                        request.runId());
                return LocateGuideResult.found(List.of(region));
            }
            if (verified.orElse(CropVerdict.REJECTED) == CropVerdict.CONTRADICTED) {
                log.info(
                        "Recovered a cataloged visual anchor that contradicts a lesson claim for runId={}",
                        request.runId());
                return LocateGuideResult.found(List.of(region.withClaimContradiction()));
            }
        }
        return original;
    }

    /**
     * A whole-page locator can identify a real object that belongs to a neighbouring rule. Inspect the exact rendered
     * crop once more beside its exact claim before publishing it, so a same-page faction, card, or worked example does
     * not become a misleading illustration for another step.
     */
    private LocateGuideResult confirmedExactStepCrops(
            VisualLocationRequest request,
            List<LocatedRegion> regions,
            String owner,
            TraceInvocation trace) {
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            // Qwen's first response already carries typed page, claim, step and geometry bindings. Re-reading natural
            // lesson prose to decide whether a second provider call is needed made latency vocabulary-dependent and
            // duplicated the same visual responsibility. The application-owned crop boundary below remains the
            // publication gate for the single structured location result.
            return LocateGuideResult.found(List.of(regions.getFirst()));
        }
        Map<String, CropImage> crops = croppedImages(request, regions);
        if (crops.size() == regions.size()) {
            for (int index = 0; index < regions.size(); index++) {
                String reference = "R" + (index + 1);
                Optional<CropVerdict> confirmed = confirmExactCrop(
                        request,
                        regions.get(index),
                        reference,
                        crops.get(reference),
                        owner,
                        trace);
                if (confirmed.isEmpty()) return retainFirstGroundedCrops(request, regions);
                if (confirmed.get() == CropVerdict.ACCEPTED) {
                    return LocateGuideResult.found(List.of(regions.get(index)));
                }
                if (confirmed.get() == CropVerdict.CONTRADICTED) {
                    return LocateGuideResult.found(List.of(regions.get(index).withClaimContradiction()));
                }
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
        log.info(
                "Exact-step crop verification was unavailable for runId={}; retaining {} grounded crop(s)",
                request.runId(),
                regions.size());
        return LocateGuideResult.found(regions);
    }

    /** One crop and one exact claim per call prevents a vision model from confusing R references across images. */
    private Optional<CropVerdict> confirmExactCrop(
            VisualLocationRequest request,
            LocatedRegion region,
            String reference,
            CropImage crop,
            String owner,
            TraceInvocation trace) {
        try {
            boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
            var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
            if (qwen) {
                prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
            }
            ChatClient.ChatClientRequestSpec requestSpec = prompt
                    .system(qwen ? QWEN_CROP_VERIFIER_SYSTEM : CROP_VERIFIER_SYSTEM)
                    .user(user -> {
                        user.text("Crop reference: {crop}. Return only the JSON object.")
                                .param("crop", Map.of(
                                        "ref", reference,
                                        "pageNumber", crop.pageNumber(),
                                        "label", crop.label(),
                                        "claims", VisualCropAcceptancePolicy.claimsForExactCrop(request, region).stream()
                                                .map(claim -> Map.of(
                                                        "stepPosition", claim.stepPosition(),
                                                        "text", claim.text()))
                                                .toList()));
                        user.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(crop.content()));
                    });
            String content = tracedContent(
                    trace,
                    owner,
                    "visual-exact-crop-verifier-v1",
                    "visual-exact-crop-verdict-v1",
                    qwen ? QWEN_CROP_VERIFIER_SYSTEM : CROP_VERIFIER_SYSTEM,
                    List.of(new VisualRegionLocator.PageImage(
                            crop.pageNumber(),
                            MimeTypeUtils.IMAGE_PNG_VALUE,
                            crop.content())),
                    () -> requestSpec.call().content());
            Optional<CropVerdict> verified = VisualLocatorResponsePolicy.cropReview(content, Set.of(reference))
                    .map(review -> {
                        if (review.contradictedReferences().contains(reference)) return CropVerdict.CONTRADICTED;
                        if (review.acceptedReferences().contains(reference)) return CropVerdict.ACCEPTED;
                        return CropVerdict.REJECTED;
                    });
            if (qwen) {
                log.info(
                        "Qwen exact-crop verdict for runId={} (verdict={})",
                        request.runId(),
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
            byte[] content = VisualCropAcceptancePolicy.croppedPng(page, region);
            if (content == null) return Map.of();
            crops.put("R" + (index + 1), new CropImage(region.pageNumber(), region.label(), content));
        }
        return Map.copyOf(crops);
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

    private enum CropVerdict {
        ACCEPTED,
        CONTRADICTED,
        REJECTED
    }

    private GuideAttempt locateGuideOnce(
            VisualLocationRequest request,
            String owner,
            String correction,
            TraceInvocation trace) {
        boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
        List<VisualRegionLocator.PageImage> preparedPages = request.pages().stream().map(images::prepare).toList();
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if (qwen) {
            prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
        }
        ChatClient.ChatClientRequestSpec requestSpec = prompt
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
                            .param("candidates", VisualLocatorResponsePolicy.candidatePromptPayload(request.candidates(), qwen))
                            .param("correction", correction);
                    preparedPages.forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                });
        String content = tracedContent(
                trace,
                owner,
                correction.isBlank() ? "visual-region-locator-v1" : "visual-region-locator-retry-v1",
                "visual-region-locator-v1",
                qwen ? QWEN_SYSTEM : SYSTEM,
                preparedPages,
                () -> requestSpec.call().content());
        if (VisualLocatorResponsePolicy.isExplicitNoRegion(content)) {
            return unavailableGuide(Rejection.EXPLICIT_NO_REGION, false);
        }
        Optional<ModelGuide> parsed = VisualLocatorResponsePolicy.parseModelGuide(content);
        if (parsed.isEmpty()) {
            captureFailure(trace, "VISUAL_REGION_OUTPUT_REJECTED");
            log.info("Visual locator returned no usable JSON for runId={}", request.runId());
            return unavailableGuide(Rejection.MALFORMED_JSON, true);
        }
        if (parsed.get().regions().isEmpty()) return unavailableGuide(Rejection.EXPLICIT_NO_REGION, false);
        if (qwen) {
            log.info(
                    "Qwen visual locator returned {} candidate region(s) for runId={}",
                    parsed.get().regions().size(),
                    request.runId());
        }
        List<LocatedRegion> accepted = new java.util.ArrayList<>();
        Rejection rejected = Rejection.NONE;
        for (ModelRegion response : parsed.get().regions()) {
            List<VisualRegionLocator.Claim> referencedClaims = response.supportedClaimRefs().stream()
                    .map(ref -> claim(ref, request))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            List<VisualRegionLocator.Claim> supportedClaims = VisualCropAcceptancePolicy.pageScopedClaims(
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
                LocatedRegion region = new LocatedRegion(
                        response.pageNumber(),
                        response.label(),
                        response.visibleDescription(),
                        response.x(),
                        response.y(),
                        response.width(),
                        response.height(),
                        supported,
                        supportedStepPositions);
                if (accepted.stream().noneMatch(existing -> sameRegion(existing, region))) accepted.add(region);
            } catch (IllegalArgumentException invalidModelOutput) {
                log.info(
                        "Rejected invalid visual locator output for runId={} (reason=INVALID_GEOMETRY)",
                        request.runId());
                rejected = Rejection.INVALID_GEOMETRY;
            }
        }
        if (!accepted.isEmpty()) return new GuideAttempt(LocateGuideResult.found(accepted), false, Rejection.NONE);
        log.info("Visual locator returned no supported visual regions for runId={}", request.runId());
        captureFailure(trace, "VISUAL_REGION_SCOPE_REJECTED");
        return unavailableGuide(rejected == Rejection.NONE ? Rejection.UNSUPPORTED_SCOPE : rejected, true);
    }

    private GuideAttempt unavailableGuide(Rejection rejection, boolean retryable) {
        return new GuideAttempt(
                LocateGuideResult.unavailable(VisualLocatorResponsePolicy.diagnosticFor(rejection)), retryable, rejection);
    }

    private boolean sameRegion(LocatedRegion first, LocatedRegion second) {
        return first.pageNumber() == second.pageNumber()
                && first.x() == second.x()
                && first.y() == second.y()
                && first.width() == second.width()
                && first.height() == second.height();
    }

    private VisualRegionLocator.Claim claim(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        int index = Integer.parseInt(reference.substring(1)) - 1;
        return index >= 0 && index < request.claims().size() ? request.claims().get(index) : null;
    }

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(800)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    private String tracedContent(
            TraceInvocation trace,
            String owner,
            String templateVersion,
            String outputSchemaVersion,
            String outputContract,
            List<VisualRegionLocator.PageImage> modelVisiblePages,
            Supplier<String> providerCall) {
        int attempt = trace.nextAttempt();
        captureVisualInputs(trace, attempt, templateVersion, modelVisiblePages);
        capture(trace, () -> trace.capture().modelCallStarted(new ModelCallStarted(
                freshContext(trace.context()),
                models.providerFor(Role.VISUAL, owner),
                models.modelNameFor(Role.VISUAL, owner),
                attempt,
                templateVersion,
                outputSchemaVersion,
                sha256(outputContract),
                Math.max(1, modelVisiblePages.size() * 600),
                800)));
        try {
            String content = providerCall.get();
            capture(trace, () -> trace.capture().modelTurn(new ModelTurn(
                    freshContext(trace.context()),
                    models.providerFor(Role.VISUAL, owner),
                    models.modelNameFor(Role.VISUAL, owner),
                    attempt,
                    content == null ? "" : content,
                    List.of(),
                    "RESPONSE_RECEIVED",
                    0,
                    0,
                    content == null || content.isBlank())));
            return content;
        } catch (RuntimeException failure) {
            captureFailure(trace, "VISUAL_REGION_MODEL_ATTEMPT_FAILED");
            throw failure;
        }
    }

    private void captureVisualInputs(
            TraceInvocation trace,
            int attempt,
            String purpose,
            List<VisualRegionLocator.PageImage> pages) {
        if (!trace.enabled() || pages.isEmpty()) return;
        try {
            List<MediaDescriptor> descriptors = IntStream.range(0, pages.size())
                    .mapToObj(index -> mediaDescriptor(pages.get(index), index + 1))
                    .toList();
            String callId = "visual-region-media|" + attempt;
            String arguments = traceJson(Map.of(
                    "purpose", purpose,
                    "pageNumbers", pages.stream().map(VisualRegionLocator.PageImage::pageNumber).toList()));
            capture(trace, () -> trace.capture().toolCall(new AgentTraceEvent.ToolCall(
                    freshContext(trace.context()),
                    callId,
                    "provide_visual_model_media",
                    arguments,
                    arguments,
                    "visual-model-media-input-v1",
                    sha256(TRACE_MEDIA_INPUT_SCHEMA),
                    ToolArgumentValidation.ACCEPTED)));
            String observation = traceJson(Map.of(
                    "media",
                    descriptors.stream()
                            .map(descriptor -> Map.of(
                                    "mediaType", descriptor.mediaType(),
                                    "label", descriptor.label(),
                                    "width", descriptor.width(),
                                    "height", descriptor.height(),
                                    "byteCount", descriptor.byteCount(),
                                    "sha256", descriptor.sha256()))
                            .toList()));
            capture(trace, () -> trace.capture().toolObservation(new AgentTraceEvent.ToolObservation(
                    freshContext(trace.context()),
                    callId,
                    "provide_visual_model_media",
                    observation,
                    "MEDIA_BOUND",
                    descriptors.size(),
                    false,
                    descriptors)));
        } catch (RuntimeException traceFailure) {
            captureGap(trace, "VISUAL_REGION_MEDIA_TRACE_GAP");
        }
    }

    private MediaDescriptor mediaDescriptor(VisualRegionLocator.PageImage page, int inputPosition) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (image == null) throw new IllegalArgumentException("visual trace media is not a readable image");
            return new MediaDescriptor(
                    page.mediaType(),
                    "pdf-page-" + page.pageNumber() + "-input-" + inputPosition,
                    image.getWidth(),
                    image.getHeight(),
                    page.content().length,
                    sha256(page.content()));
        } catch (IOException failure) {
            throw new IllegalArgumentException("visual trace media is not a readable image", failure);
        }
    }

    private void captureFailure(TraceInvocation trace, String code) {
        capture(trace, () -> trace.capture().bindingOrFailure(new BindingOrFailure(
                freshContext(trace.context()),
                LifecycleSignal.FAILURE,
                code,
                trace.context().resource(),
                null)));
    }

    private void captureGap(TraceInvocation trace, String code) {
        capture(trace, () -> trace.capture().bindingOrFailure(new BindingOrFailure(
                freshContext(trace.context()),
                LifecycleSignal.GAP,
                code,
                trace.context().resource(),
                null)));
    }

    private TraceInvocation trace(CaptureHandle capture, TraceEventContext context) {
        return new TraceInvocation(
                PrivateAgentTraceCapture.failOpen(capture),
                context,
                new AtomicInteger());
    }

    private void capture(TraceInvocation trace, Runnable emission) {
        if (!trace.enabled()) return;
        try {
            emission.run();
        } catch (RuntimeException ignored) {
            // Private diagnostics never remove an already grounded text lesson or change optional crop recovery.
        }
    }

    private TraceEventContext freshContext(TraceEventContext context) {
        return new TraceEventContext(
                UUID.randomUUID(),
                Instant.now(),
                context.stage(),
                context.operationId(),
                context.parentOperationId(),
                context.resource());
    }

    private String traceJson(Object value) {
        try {
            return TRACE_JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("visual trace metadata serialization failed", failure);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record TraceInvocation(
            CaptureHandle capture,
            TraceEventContext context,
            AtomicInteger attempts) {

        private boolean enabled() {
            return context != null && capture != null && capture.enabled();
        }

        private int nextAttempt() {
            return attempts.incrementAndGet();
        }
    }

    private record GuideAttempt(LocateGuideResult guide, boolean retryable, Rejection rejection) {}
}
