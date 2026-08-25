package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.LocateResult;
import com.rulepilot.teaching.VisualRegionLocator.ReviewAction;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelGuide;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelRegion;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelReview;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.Rejection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** One bounded vision decision owns planning and literal visual review; the application owns publication checks. */
@Component
public class SpringAiVisualRegionLocator implements VisualRegionLocator {

    static final String SYSTEM = """
            You are a rulebook visual evidence Agent. Inspect only supplied cited claims, candidate pages, and page
            images. Produce a typed visual plan for every offered step. A step may have zero, one, or several useful
            visuals, but the total number of non-null sources must not exceed visualBudget.

            Return one JSON object with a reviews array. Every review has exactly stepPosition, action, and source.
            action is ACCEPT, RECROP, USE_FULL_PAGE, or REJECT. REJECT has source null. ACCEPT and RECROP require a
            PAGE_REGION or EMBEDDED_AUTHOR_IMAGE source. USE_FULL_PAGE requires a FULL_PAGE source whose x,y,width,
            height are exactly 0,0,1000,1000. Use RECROP only when another bounded inspection could make a proposed
            local source useful; otherwise choose a final action. Return at least one REJECT review for a step with no
            useful visual. Several ACCEPT reviews may use the same stepPosition.

            Every non-null source has exactly pageNumber, label, visibleDescription, x, y, width, height, sourceKind,
            and supportedClaimRefs. Coordinates use the complete page's top-left 0-1000 system and must stay inside
            the page. sourceKind is FULL_PAGE, PAGE_REGION, or EMBEDDED_AUTHOR_IMAGE. EMBEDDED_AUTHOR_IMAGE means an
            illustration, diagram, table, card face, or other image authored into the rulebook page, not decorative
            page chrome. PAGE_REGION is any useful mixed page region. A full page is valid when its overall layout or
            tightly integrated diagram is the smallest readable context; never reject it merely for area.

            supportedClaimRefs may contain only offered C references whose sourcePages include the source page and
            whose stepPosition equals the review's stepPosition. label and visibleDescription describe literal visible
            content only. Images never prove a mechanical effect, condition, quantity, score, timing, or exception;
            cited text remains authoritative. Reject unrelated, decorative, prose-only, contradictory, or ambiguous
            visuals. Do not expose reasoning or add fields.
            """;

    /** Kept compact for providers that follow shorter multimodal JSON contracts more reliably. */
    static final String QWEN_SYSTEM = """
            Inspect supplied rulebook page images for the exact offered C claims. Return JSON only with a reviews
            array. Each item has exactly stepPosition, action, source. action is ACCEPT, RECROP, USE_FULL_PAGE, or
            REJECT; REJECT uses source null. A non-null source has pageNumber, label, visibleDescription, x, y, width,
            height, sourceKind, supportedClaimRefs. sourceKind is PAGE_REGION, EMBEDDED_AUTHOR_IMAGE, or FULL_PAGE.
            USE_FULL_PAGE is legal only with FULL_PAGE at 0,0,1000,1000. ACCEPT/RECROP may not use a whole-page box.
            Bind every C reference to the same step and one of its sourcePages. Describe only literal visible content,
            never a rule effect. Each step may have zero or several accepted visuals, bounded by visualBudget overall.
            Use REJECT when no literal visual helps. Do not add fields or prose.
            """;

    private final RuntimeModelConfiguration models;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();

    public SpringAiVisualRegionLocator(RuntimeModelConfiguration models) {
        this.models = models;
    }

    @Override
    public boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return !models.usesFake(Role.VISUAL, modelConfigurationOwner)
                && models.supportsVision(Role.VISUAL, modelConfigurationOwner);
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
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }
        GuideAttempt first = locateGuideOnce(request, owner, "");
        if (!first.guide().regions().isEmpty() || !first.retryable()) return first.guide();
        GuideAttempt retried = locateGuideOnce(
                request, owner, VisualLocatorResponsePolicy.retryInstruction(first.rejection()));
        return retried.guide();
    }

    private GuideAttempt locateGuideOnce(VisualLocationRequest request, String owner, String correction) {
        boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
        List<VisualRegionLocator.PageImage> preparedPages = request.pages().stream().map(images::prepare).toList();
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if (qwen) prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
        String content = prompt
                .system(qwen ? QWEN_SYSTEM : SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    Candidate pages: {candidates}
                                    visualBudget: {visualBudget}
                                    {correction}
                                    Return the exact reviews JSON object only.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", IntStream.range(0, request.claims().size())
                                    .mapToObj(index -> Map.of(
                                            "ref", "C" + (index + 1),
                                            "stepPosition", request.claims().get(index).stepPosition(),
                                            "text", request.claims().get(index).text(),
                                            "sourcePages", request.claims().get(index).sourcePages()))
                                    .toList())
                            .param("candidates", VisualLocatorResponsePolicy.candidatePromptPayload(
                                    request.candidates(), qwen))
                            .param("visualBudget", request.visualBudget())
                            .param("correction", correction);
                    preparedPages.forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        Optional<ModelGuide> parsed = VisualLocatorResponsePolicy.parseModelGuide(content);
        if (parsed.isEmpty()) return unavailable(Rejection.MALFORMED_JSON, true);
        long proposedVisuals = parsed.get().reviews().stream().filter(review -> review.source() != null).count();
        if (proposedVisuals > request.visualBudget()) return unavailable(Rejection.UNSUPPORTED_SCOPE, true);

        Set<Integer> offeredSteps = request.claims().stream()
                .map(VisualRegionLocator.Claim::stepPosition)
                .filter(position -> position > 0)
                .collect(java.util.stream.Collectors.toSet());
        List<LocatedRegion> accepted = new ArrayList<>();
        Rejection rejected = Rejection.NONE;
        boolean requestedRecrop = false;
        for (ModelReview review : parsed.get().reviews()) {
            if (!offeredSteps.contains(review.stepPosition())) {
                rejected = Rejection.UNSUPPORTED_SCOPE;
                continue;
            }
            if (review.action() == ReviewAction.REJECT) continue;
            if (review.action() == ReviewAction.RECROP) {
                requestedRecrop = true;
                continue;
            }
            ModelRegion source = review.source();
            List<VisualRegionLocator.Claim> claims = ownedClaims(review, request);
            if (source == null
                    || claims.isEmpty()
                    || request.pages().stream().noneMatch(page -> page.pageNumber() == source.pageNumber())) {
                rejected = Rejection.UNSUPPORTED_SCOPE;
                continue;
            }
            try {
                LocatedRegion region = new LocatedRegion(
                        source.pageNumber(),
                        source.label(),
                        source.visibleDescription(),
                        source.x(),
                        source.y(),
                        source.width(),
                        source.height(),
                        claims.stream().map(VisualRegionLocator.Claim::evidenceId).distinct().toList(),
                        List.of(review.stepPosition()),
                        false,
                        source.sourceKind());
                if (accepted.stream().noneMatch(existing -> sameRegion(existing, region))) accepted.add(region);
            } catch (IllegalArgumentException invalidGeometry) {
                rejected = Rejection.INVALID_GEOMETRY;
            }
        }
        if (!accepted.isEmpty()) return new GuideAttempt(LocateGuideResult.found(accepted), false, Rejection.NONE);
        if (requestedRecrop) return unavailable(Rejection.RECROP, true);
        if (parsed.get().hasOnlyRejections()) return unavailable(Rejection.EXPLICIT_NO_REGION, false);
        return unavailable(rejected == Rejection.NONE ? Rejection.UNSUPPORTED_SCOPE : rejected, true);
    }

    List<VisualRegionLocator.Claim> ownedClaims(
            ModelReview review, VisualLocationRequest request) {
        ModelRegion source = review.source();
        if (source == null) return List.of();
        List<VisualRegionLocator.Claim> claims = source.supportedClaimRefs().stream()
                .map(reference -> claim(reference, request))
                .filter(java.util.Objects::nonNull)
                .filter(claim -> claim.stepPosition() == review.stepPosition())
                .filter(claim -> claim.sourcePages().contains(source.pageNumber()))
                .distinct()
                .toList();
        return claims.size() == source.supportedClaimRefs().size() ? claims : List.of();
    }

    private VisualRegionLocator.Claim claim(String reference, VisualLocationRequest request) {
        if (reference == null || !reference.matches("C[1-9][0-9]*")) return null;
        try {
            int index = Integer.parseInt(reference.substring(1)) - 1;
            return index >= 0 && index < request.claims().size() ? request.claims().get(index) : null;
        } catch (NumberFormatException invalidReference) {
            return null;
        }
    }

    private GuideAttempt unavailable(Rejection rejection, boolean retryable) {
        return new GuideAttempt(
                LocateGuideResult.unavailable(VisualLocatorResponsePolicy.diagnosticFor(rejection)),
                retryable,
                rejection);
    }

    private boolean sameRegion(LocatedRegion first, LocatedRegion second) {
        return first.pageNumber() == second.pageNumber()
                && first.x() == second.x()
                && first.y() == second.y()
                && first.width() == second.width()
                && first.height() == second.height()
                && first.sourceKind() == second.sourceKind();
    }

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(1_600)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    private record GuideAttempt(LocateGuideResult guide, boolean retryable, Rejection rejection) {}
}
