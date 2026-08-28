package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.BatchAction;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.LocateResult;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelAction;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelGuide;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.ModelReview;
import com.rulepilot.teaching.adapter.out.model.VisualLocatorResponsePolicy.Rejection;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** One bounded vision call selects immutable application-owned crops; the model never authors page geometry. */
@Component
public class SpringAiVisualRegionLocator implements VisualRegionLocator {

    static final String SYSTEM = """
            You select useful visual evidence from already-localized rulebook crop candidates. Geometry, page
            ownership, attachment order, and source kind are owned by the application. Never return, estimate, or
            discuss coordinates. Inspect candidate images in manifest attachmentIndex order.

            Return one JSON object with exactly batchAction and reviews. batchAction is STOP or CONTINUE. Use CONTINUE
            only when hasMoreCandidates is true and inspecting the next finite batch is still useful after this batch's
            selections; otherwise use STOP. Every review has exactly stepPosition, action, candidateId, label,
            visibleDescription, and supportedClaimRefs. action is ACCEPT_CANDIDATE or NO_VISUAL.
            ACCEPT_CANDIDATE requires one offered candidateId, a short literal label and visibleDescription, and one
            or more offered C references belonging to that step whose sourcePages contain the candidate page.
            label is at most 80 characters and visibleDescription is at most 240 characters.
            NO_VISUAL requires candidateId, label, and visibleDescription to be null and supportedClaimRefs to be an
            empty array. A step may accept several different candidates. Select every useful candidate needed for the
            lesson; do not target a fixed count. The same candidate may never be selected twice or shared across steps.

            Select a crop only when its literal visible content helps a player inspect the offered claim. An image
            never proves a mechanical effect, condition, quantity, score, timing, or exception; cited text remains
            authoritative. Reject decorative, prose-only, contradictory, or ambiguous crops. Do not add fields,
            page numbers, geometry, source kinds, reasoning, or prose outside the JSON object.

            When the application supplies structured rejection feedback, reconsider the whole batch and return one
            complete replacement object. Never patch fields from the rejected object. Choose only an offered opaque
            candidate id that now satisfies every constraint, choose another offered candidate, or choose NO_VISUAL.
            Never edit pixels or return geometry.
            """;

    /** Shorter wording preserves the same six-field contract for Qwen multimodal JSON mode. */
    static final String QWEN_SYSTEM = """
            Select useful visual evidence from the attached pre-cropped candidates. The application owns geometry,
            pages, attachment order, and source kind; never return coordinates. Return JSON only with exactly
            batchAction and reviews. batchAction is STOP or CONTINUE; CONTINUE is legal only when hasMoreCandidates is
            true and another batch remains useful after the current selections. Each review has exactly stepPosition,
            action, candidateId, label, visibleDescription,
            supportedClaimRefs. action is ACCEPT_CANDIDATE or NO_VISUAL. ACCEPT_CANDIDATE uses one offered candidateId,
            literal label/description, and only C refs for that step whose sourcePages contain the candidate page.
            label is at most 80 characters and visibleDescription is at most 240 characters.
            NO_VISUAL uses null candidateId/label/visibleDescription and an empty supportedClaimRefs array. Never select
            one candidate twice. Select all useful candidates without targeting a fixed count. Images prove appearance
            only. Add no fields. Structured rejection feedback requires one complete replacement object, never a field
            patch. Reconsider the offered opaque candidate ids and return a valid candidate or NO_VISUAL; never edit
            pixels or return geometry.
            """;

    private static final int MAX_ATTACHMENT_EDGE = 1_024;

    private final RuntimeModelConfiguration models;
    private final AuditedAgentInvocations invocations;

    public SpringAiVisualRegionLocator(RuntimeModelConfiguration models) {
        this(models, (AuditedAgentInvocations) null);
    }

    public SpringAiVisualRegionLocator(
            RuntimeModelConfiguration models,
            AuditedAgentInvocations invocations) {
        this.models = models;
        this.invocations = invocations;
    }

    @Autowired
    SpringAiVisualRegionLocator(
            RuntimeModelConfiguration models,
            ObjectProvider<AuditedAgentInvocations> invocations) {
        this(models, invocations.getIfAvailable());
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
        List<CandidateAttachment> attachments;
        try {
            attachments = candidateAttachments(request);
        } catch (RuntimeException candidatePreparationFailure) {
            GuideAttempt unavailable = unavailable(Rejection.CANDIDATE_PREPARATION_FAILED);
            recordSelectionResult(request, unavailable, 1, false);
            return unavailable.guide();
        }
        GuideAttempt first = invokeGuideAttemptSafely(request, owner, "", attachments, 1);
        recordSelectionResult(request, first, 1, first.rejection().retryable());
        if (!first.rejection().retryable()) return first.guide();
        GuideAttempt replacement = invokeGuideAttemptSafely(
                request,
                owner,
                VisualLocatorResponsePolicy.completeReplacementFeedback(first.rejection()),
                attachments,
                2);
        recordSelectionResult(request, replacement, 2, false);
        return replacement.guide();
    }

    private GuideAttempt invokeGuideAttemptSafely(
            VisualLocationRequest request,
            String owner,
            String correction,
            List<CandidateAttachment> attachments,
            int attemptNumber) {
        if (request.runId() != null && invocations == null) {
            throw new IllegalStateException("observable visual model attempts require audited invocations");
        }
        try {
            return invokeGuideAttempt(request, owner, correction, attachments, attemptNumber);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException providerFailure) {
            return unavailable(Rejection.PROVIDER_FAILURE);
        }
    }

    private void recordSelectionResult(
            VisualLocationRequest request,
            GuideAttempt attempt,
            int attemptNumber,
            boolean completeReplacementFollows) {
        if (request.runId() == null || invocations == null) return;
        Rejection rejection = attempt.rejection();
        ActivityOutcome outcome = switch (rejection) {
            case NONE, EXPLICIT_NO_REGION -> ActivityOutcome.SUCCEEDED;
            case MALFORMED_JSON, UNSUPPORTED_SCOPE -> ActivityOutcome.REJECTED;
            case PROVIDER_FAILURE, CANDIDATE_PREPARATION_FAILED -> ActivityOutcome.FAILED;
        };
        invocations.record(
                request.runId(),
                ActivityType.VALIDATION,
                "settleVisualCandidateSelection|" + request.batchNumber() + "|" + attemptNumber + "|"
                        + rejection.name(),
                outcome,
                selectionResultSummary(attempt, attemptNumber, completeReplacementFollows));
    }

    private String selectionResultSummary(
            GuideAttempt attempt,
            int attemptNumber,
            boolean completeReplacementFollows) {
        if (attempt.rejection() == Rejection.NONE) {
            return attemptNumber == 1
                    ? "视觉候选已通过校验"
                    : "视觉候选的完整重选已通过校验";
        }
        if (attempt.rejection() == Rejection.EXPLICIT_NO_REGION) {
            return "视觉 Agent 明确选择 NO_VISUAL；本章正文保持不变";
        }
        if (completeReplacementFollows) {
            return "视觉候选本次未通过（" + attempt.rejection().name()
                    + "）；正在请求一次完整重选，这不是最终配图失败";
        }
        return "视觉候选在有限处理后仍不可用（" + attempt.rejection().name()
                + "）；仅省略本章配图，正文保持不变";
    }

    private GuideAttempt invokeGuideAttempt(
            VisualLocationRequest request,
            String owner,
            String correction,
            List<CandidateAttachment> attachments,
            int attemptNumber) {
        if (request.runId() == null || invocations == null) {
            if (request.runId() != null) {
                throw new IllegalStateException("observable visual model attempts require audited invocations");
            }
            return locateGuideOnce(request, owner, correction, attachments);
        }
        return invocations.invoke(
                request.runId(),
                ActivityType.MODEL,
                "visualCandidateBatch|" + request.batchNumber() + "|" + attemptNumber,
                estimatedInputTokens(request, attachments),
                "视觉候选批次已完成检查",
                () -> locateGuideOnce(request, owner, correction, attachments),
                ignored -> 1_000,
                this::attemptSummary);
    }

    private int estimatedInputTokens(
            VisualLocationRequest request,
            List<CandidateAttachment> attachments) {
        int claimCharacters = request.claims().stream()
                .mapToInt(claim -> claim.text().length())
                .sum();
        return 256
                + Math.max(1, (claimCharacters + 3) / 4)
                + request.candidates().size() * 48
                + attachments.size() * 256;
    }

    private String attemptSummary(GuideAttempt attempt) {
        if (attempt.rejection().retryable()) {
            return "视觉候选批次本次未通过，准备一次完整重选";
        }
        if (attempt.guide().regions().isEmpty()) {
            return "视觉候选批次未采用图片：" + attempt.guide().diagnostic().name();
        }
        return "视觉候选批次已验证 " + attempt.guide().regions().size() + " 张局部图片";
    }

    private GuideAttempt locateGuideOnce(
            VisualLocationRequest request,
            String owner,
            String correction,
            List<CandidateAttachment> attachments) {
        boolean qwen = "qwen".equals(models.providerFor(Role.VISUAL, owner));
        var prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if (qwen) prompt = prompt.options(qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner)));
        String content = prompt
                .system(qwen ? QWEN_SYSTEM : SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: {section}
                                    Claims: {claims}
                                    Candidate manifest (same order as image attachments): {manifest}
                                    batchNumber: {batchNumber}
                                    hasMoreCandidates: {hasMoreCandidates}
                                    Previous-attempt feedback (application-owned JSON; empty on the first attempt):
                                    {correction}
                                    Return the exact batchAction plus reviews JSON object only.
                                    """)
                            .param("section", request.sectionTitle())
                            .param("claims", VisualLocatorResponsePolicy.promptJson(
                                    IntStream.range(0, request.claims().size())
                                            .mapToObj(index -> Map.of(
                                                    "ref", "C" + (index + 1),
                                                    "stepPosition", request.claims().get(index).stepPosition(),
                                                    "text", request.claims().get(index).text(),
                                                    "sourcePages", request.claims().get(index).sourcePages()))
                                            .toList()))
                            .param("manifest", VisualLocatorResponsePolicy.promptJson(
                                    VisualLocatorResponsePolicy.candidateManifest(request.candidates())))
                            .param("batchNumber", request.batchNumber())
                            .param("hasMoreCandidates", request.hasMoreCandidates())
                            .param("correction", correction);
                    attachments.forEach(attachment -> user.media(
                            MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(attachment.content())));
                })
                .call()
                .content();
        Optional<ModelGuide> parsed = VisualLocatorResponsePolicy.parseModelGuide(content);
        if (parsed.isEmpty()) return unavailable(Rejection.MALFORMED_JSON);
        return admit(parsed.get(), request);
    }

    private GuideAttempt admit(ModelGuide guide, VisualLocationRequest request) {
        BatchAction batchAction = request.hasMoreCandidates() ? guide.batchAction() : BatchAction.STOP;

        Set<Integer> offeredSteps = request.claims().stream()
                .map(VisualRegionLocator.Claim::stepPosition)
                .filter(position -> position > 0)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Candidate> candidates = request.candidates().stream().collect(java.util.stream.Collectors.toMap(
                Candidate::candidateId,
                candidate -> candidate,
                (first, ignored) -> first,
                LinkedHashMap::new));
        Set<String> selectedIds = new LinkedHashSet<>();
        Set<Integer> acceptedSteps = new LinkedHashSet<>();
        Set<Integer> rejectedSteps = new LinkedHashSet<>();
        List<LocatedRegion> accepted = new ArrayList<>();

        for (ModelReview review : guide.reviews()) {
            if (!offeredSteps.contains(review.stepPosition())) return unavailable(Rejection.UNSUPPORTED_SCOPE);
            if (review.action() == ModelAction.NO_VISUAL) {
                if (!rejectedSteps.add(review.stepPosition()) || acceptedSteps.contains(review.stepPosition())) {
                    return unavailable(Rejection.UNSUPPORTED_SCOPE);
                }
                continue;
            }
            if (rejectedSteps.contains(review.stepPosition()) || !selectedIds.add(review.candidateId())) {
                return unavailable(Rejection.UNSUPPORTED_SCOPE);
            }
            Candidate candidate = candidates.get(review.candidateId());
            if (candidate == null) return unavailable(Rejection.UNSUPPORTED_SCOPE);
            List<VisualRegionLocator.Claim> claims = ownedClaims(review, candidate, request);
            if (claims.isEmpty()) return unavailable(Rejection.UNSUPPORTED_SCOPE);
            Rectangle rectangle = candidate.rectangle();
            accepted.add(new LocatedRegion(
                    candidate.pageNumber(),
                    review.label(),
                    review.visibleDescription(),
                    rectangle.x(),
                    rectangle.y(),
                    rectangle.width(),
                    rectangle.height(),
                    claims.stream().map(VisualRegionLocator.Claim::evidenceId).distinct().toList(),
                    List.of(review.stepPosition()),
                    false,
                    candidate.sourceKind()));
            acceptedSteps.add(review.stepPosition());
        }
        if (!accepted.isEmpty()) {
            return new GuideAttempt(LocateGuideResult.found(accepted, batchAction), Rejection.NONE);
        }
        return unavailable(
                guide.hasOnlyNoVisual() ? Rejection.EXPLICIT_NO_REGION : Rejection.UNSUPPORTED_SCOPE,
                batchAction);
    }

    List<VisualRegionLocator.Claim> ownedClaims(
            ModelReview review,
            Candidate candidate,
            VisualLocationRequest request) {
        List<VisualRegionLocator.Claim> claims = review.supportedClaimRefs().stream()
                .map(reference -> claim(reference, request))
                .filter(java.util.Objects::nonNull)
                .filter(claim -> claim.stepPosition() == review.stepPosition())
                .filter(claim -> claim.sourcePages().contains(candidate.pageNumber()))
                .distinct()
                .toList();
        return claims.size() == review.supportedClaimRefs().size() ? claims : List.of();
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

    List<CandidateAttachment> candidateAttachments(VisualLocationRequest request) {
        List<CandidateAttachment> attachments = new ArrayList<>(request.candidates().size());
        for (int index = 0; index < request.candidates().size(); index++) {
            attachments.add(null);
        }
        for (VisualRegionLocator.PageImage source : request.pages()) {
            BufferedImage page = decode(source);
            try {
                for (int index = 0; index < request.candidates().size(); index++) {
                    Candidate candidate = request.candidates().get(index);
                    if (candidate.pageNumber() != source.pageNumber()) continue;
                    attachments.set(index, new CandidateAttachment(
                            candidate.candidateId(),
                            index + 1,
                            candidate.pageNumber(),
                            crop(page, candidate.rectangle())));
                }
            } finally {
                page.flush();
            }
        }
        return List.copyOf(attachments);
    }

    BufferedImage decode(VisualRegionLocator.PageImage page) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (decoded == null) throw new IllegalArgumentException("visual candidate page cannot be decoded");
            return decoded;
        } catch (IOException exception) {
            throw new UncheckedIOException("could not decode visual candidate page", exception);
        }
    }

    private byte[] crop(BufferedImage page, Rectangle rectangle) {
        int left = pixel(rectangle.x(), page.getWidth());
        int top = pixel(rectangle.y(), page.getHeight());
        int right = pixelCeiling(rectangle.x() + rectangle.width(), page.getWidth());
        int bottom = pixelCeiling(rectangle.y() + rectangle.height(), page.getHeight());
        int sourceWidth = right - left;
        int sourceHeight = bottom - top;
        double scale = Math.min(1d, (double) MAX_ATTACHMENT_EDGE / Math.max(sourceWidth, sourceHeight));
        int outputWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int outputHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(page, 0, 0, outputWidth, outputHeight, left, top, right, bottom, null);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(output, "jpeg", bytes)) {
                throw new IllegalStateException("JPEG image writer is unavailable");
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("could not encode visual candidate crop", exception);
        } finally {
            output.flush();
        }
    }

    private int pixel(int normalized, int size) {
        return Math.min(size - 1, normalized * size / 1_000);
    }

    private int pixelCeiling(int normalized, int size) {
        return Math.max(1, Math.min(size, (normalized * size + 999) / 1_000));
    }

    private GuideAttempt unavailable(Rejection rejection) {
        return unavailable(rejection, BatchAction.STOP);
    }

    private GuideAttempt unavailable(Rejection rejection, BatchAction batchAction) {
        return new GuideAttempt(
                LocateGuideResult.unavailable(
                        VisualLocatorResponsePolicy.diagnosticFor(rejection), batchAction),
                rejection);
    }

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .maxTokens(1_000)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    record CandidateAttachment(String candidateId, int attachmentIndex, int pageNumber, byte[] content) {
        CandidateAttachment {
            if (candidateId == null
                    || attachmentIndex < 1
                    || pageNumber < 1
                    || content == null
                    || content.length == 0) {
                throw new IllegalArgumentException("visual candidate attachment is invalid");
            }
            content = content.clone();
        }

        @Override public byte[] content() {
            return content.clone();
        }
    }

    private record GuideAttempt(LocateGuideResult guide, Rejection rejection) {}
}
