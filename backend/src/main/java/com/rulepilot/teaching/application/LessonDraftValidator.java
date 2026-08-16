package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Validates the deterministic publication boundary for an untrusted lesson draft.
 *
 * <p>This class owns schema, citation scope, page membership, visual bounds, and internal-reference leakage. It does
 * not infer rule meaning from wording. Semantic fidelity and objective coverage remain explicit evaluation concerns;
 * passing this boundary does not claim that an independent model proved entailment.</p>
 */
final class LessonDraftValidator {

    private static final int MAX_VISUAL_FOCUS_AREA = 720_000;

    private LessonDraftValidator() {}

    static List<Claim> reviewClaims(SectionDraft draft, List<UUID> visualCitationIds) {
        boolean captionDuplicatesVisualStep = draft.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL)
                .anyMatch(step -> step.text().equals(draft.visualCaption())
                        && Set.copyOf(step.citationIds()).equals(Set.copyOf(visualCitationIds)));
        List<Claim> claims = new ArrayList<>();
        if (!captionDuplicatesVisualStep) {
            claims.add(new Claim(1, draft.visualCaption(), visualCitationIds));
        }
        int firstStepPosition = claims.size() + 1;
        IntStream.range(0, draft.steps().size())
                .mapToObj(index -> new Claim(
                        firstStepPosition + index,
                        draft.steps().get(index).heading() + "：" + draft.steps().get(index).text(),
                        draft.steps().get(index).citationIds()))
                .forEach(claims::add);
        return List.copyOf(claims);
    }

    static List<UUID> validatedVisualCitationIds(SectionDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        LinkedHashSet<UUID> citationIds = new LinkedHashSet<>(draft.visualCitationIds());
        if (citationIds.isEmpty() || citationIds.contains(null)
                || !allowedEvidence.keySet().containsAll(citationIds)) {
            throw new IllegalArgumentException("teaching visual cites evidence outside retrieval scope");
        }
        return List.copyOf(citationIds);
    }

    static void validateVisualBlockEvidence(
            SectionDraft draft,
            TeachingLessonModel.SectionRequest request,
            Map<UUID, RuleEvidence> allowedEvidence) {
        Set<Integer> attachedPages = request.pageImages().stream()
                .map(TeachingLessonModel.PageImageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        for (StepDraft step : draft.steps()) {
            if (step.kind() != TeachingMove.VISUAL) continue;
            VisualFocusDraft focus = step.visualFocus();
            if (focus == null || !attachedPages.contains(focus.pageNumber())) {
                throw new IllegalArgumentException(
                        "VISUAL teaching blocks must identify a focus region on an attached rulebook page.");
            }
            validatedFocus(focus);
            boolean citesAttachedPage = step.citationIds().stream()
                    .map(allowedEvidence::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(attachedPages::contains));
            if (!citesAttachedPage) {
                throw new IllegalArgumentException(
                        "VISUAL teaching blocks must cite evidence from an attached rulebook page.");
            }
        }
    }

    static LessonStep validatedStep(int position, StepDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        if (draft == null || draft.text() == null || draft.text().isBlank() || draft.text().length() > 600
                || draft.citationIds().isEmpty()) {
            throw new IllegalArgumentException("teaching step is invalid");
        }
        LinkedHashSet<UUID> citationIds = new LinkedHashSet<>(draft.citationIds());
        if (citationIds.contains(null) || !allowedEvidence.keySet().containsAll(citationIds)) {
            throw new IllegalArgumentException("teaching step cites evidence outside retrieval scope");
        }
        List<Integer> pages = citationIds.stream()
                .map(allowedEvidence::get)
                .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .sorted()
                .boxed()
                .toList();
        return new LessonStep(
                position,
                draft.heading(),
                draft.kind(),
                draft.text(),
                pages,
                List.copyOf(citationIds),
                validatedVisualFocus(draft));
    }

    static VisualFocus validatedVisualFocus(StepDraft draft) {
        VisualFocusDraft focus = draft.visualFocus();
        if (draft.kind() != TeachingMove.VISUAL) {
            if (focus != null) {
                throw new IllegalArgumentException("Only VISUAL teaching blocks may define a visual focus.");
            }
            return null;
        }
        if (focus == null) {
            throw new IllegalArgumentException("VISUAL teaching blocks require a visual focus.");
        }
        return validatedFocus(focus);
    }

    static VisualFocus validatedFocus(VisualFocusDraft focus) {
        int x = Math.max(0, Math.min(980, focus.x()));
        int y = Math.max(0, Math.min(980, focus.y()));
        int width = Math.max(20, Math.min(focus.width(), 1_000 - x));
        int height = Math.max(20, Math.min(focus.height(), 1_000 - y));
        if ((long) width * height > MAX_VISUAL_FOCUS_AREA) {
            throw new IllegalArgumentException(
                    "VISUAL teaching blocks require a tight focus region, not an almost complete rulebook page.");
        }
        return new VisualFocus(
                focus.pageNumber(),
                focus.label(),
                focus.visibleDescription(),
                x,
                y,
                width,
                height);
    }

    static void validateDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null) throw new IllegalArgumentException("The draft is missing.");
        if (draft.title() == null || draft.title().isBlank() || draft.title().length() > 160) {
            throw new IllegalArgumentException("The title is missing or longer than 160 characters.");
        }
        if (draft.visualKind() == null) throw new IllegalArgumentException("visualKind is missing.");
        if (draft.visualCaption() == null || draft.visualCaption().isBlank()) {
            throw new IllegalArgumentException("The visual caption is missing.");
        }
        if (draft.visualCitationIds().isEmpty()) {
            throw new IllegalArgumentException("The visual caption has no evidence citation.");
        }
        if (draft.steps().isEmpty()) {
            throw new IllegalArgumentException("The draft must contain at least one teaching step.");
        }
        if (draft.steps().stream().anyMatch(step -> step == null
                || step.heading() == null || step.heading().isBlank() || step.heading().length() > 32
                || step.kind() == null)) {
            throw new IllegalArgumentException("Every step needs a short heading and a teaching kind.");
        }
        if (request.pageImages().isEmpty()
                && draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL)) {
            throw new IllegalArgumentException("VISUAL teaching blocks require attached rulebook page evidence.");
        }
        if (!request.pageImages().isEmpty()
                && draft.steps().stream().noneMatch(step -> step.kind() == TeachingMove.VISUAL)) {
            throw new IllegalArgumentException(
                    "Attached rulebook pages require one VISUAL step with a cited visualFocus region.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL
                && step.visualFocus() == null)) {
            throw new IllegalArgumentException("VISUAL teaching blocks require a visual focus region.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() != TeachingMove.VISUAL
                && step.visualFocus() != null)) {
            throw new IllegalArgumentException("Only VISUAL teaching blocks may define a visual focus region.");
        }
        if (containsUnresolvedPresentationMarker(draft)) {
            throw new IllegalArgumentException(
                    "Replace unresolved extraction markers or inferred emoji with evidenced natural-language terms.");
        }
        if (containsInternalEvidenceReference(draft)) {
            throw new IllegalArgumentException(
                    "Remove internal evidence references such as E1 from player-facing teaching text.");
        }
    }

    private static boolean containsUnresolvedPresentationMarker(SectionDraft draft) {
        return LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker(draft.visualCaption())
                || LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon(draft.visualCaption())
                || draft.steps().stream().anyMatch(step ->
                        LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker(step.heading())
                                || LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker(step.text())
                                || LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon(step.heading())
                                || LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon(step.text()));
    }

    private static boolean containsInternalEvidenceReference(SectionDraft draft) {
        return LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(draft.visualCaption())
                || draft.steps().stream().anyMatch(step ->
                        LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(step.heading())
                                || LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(step.text()));
    }
}
