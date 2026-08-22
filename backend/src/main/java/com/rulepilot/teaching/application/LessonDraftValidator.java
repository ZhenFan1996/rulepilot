package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.RuleFactDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFact;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Validates the deterministic publication boundary for an untrusted lesson draft.
 *
 * <p>This class owns required structure, citation scope, and source-page membership. It does not infer rule meaning
 * from wording and it never rejects player prose because of its length, punctuation, vocabulary, symbols, or
 * quantities. Optional visual metadata is retained only when it is bound to an attached, cited page; an invalid
 * visual focus is skipped without changing the cited teaching step.</p>
 */
final class LessonDraftValidator {

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
        int claimPosition = claims.size() + 1;
        for (StepDraft step : draft.steps()) {
            claims.add(new Claim(claimPosition++, step.heading() + "：" + step.text(), step.citationIds()));
            for (RuleFactDraft fact : step.ruleFacts()) {
                claims.add(new Claim(claimPosition++, fact.text(), fact.citationIds()));
            }
        }
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

    static LessonStep validatedStep(int position, StepDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        return validatedStep(position, draft, allowedEvidence, Set.of());
    }

    static LessonStep validatedStep(
            int position,
            StepDraft draft,
            Map<UUID, RuleEvidence> allowedEvidence,
            Set<Integer> attachedPages) {
        if (draft == null || draft.text() == null || draft.text().isBlank() || draft.citationIds().isEmpty()) {
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
                validatedRuleFacts(draft.ruleFacts(), allowedEvidence),
                validatedVisualFocus(draft, attachedPages, allowedEvidence));
    }

    private static List<RuleFact> validatedRuleFacts(
            List<RuleFactDraft> drafts,
            Map<UUID, RuleEvidence> allowedEvidence) {
        List<RuleFactDraft> values = drafts == null ? List.of() : drafts;
        return IntStream.range(0, values.size())
                .mapToObj(index -> {
                    RuleFactDraft draft = values.get(index);
                    if (draft == null || draft.role() == null || draft.text() == null || draft.text().isBlank()
                            || draft.citationIds() == null || draft.citationIds().isEmpty()) {
                        throw new IllegalArgumentException("teaching rule fact is invalid");
                    }
                    LinkedHashSet<UUID> ids = new LinkedHashSet<>(draft.citationIds());
                    if (ids.contains(null) || !allowedEvidence.keySet().containsAll(ids)) {
                        throw new IllegalArgumentException("teaching rule fact cites evidence outside retrieval scope");
                    }
                    List<Integer> sourcePages = ids.stream()
                            .map(allowedEvidence::get)
                            .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                            .distinct()
                            .sorted()
                            .boxed()
                            .toList();
                    return new RuleFact(index + 1, draft.role(), draft.text(), sourcePages, List.copyOf(ids));
                })
                .toList();
    }

    private static VisualFocus validatedVisualFocus(
            StepDraft draft,
            Set<Integer> attachedPages,
            Map<UUID, RuleEvidence> allowedEvidence) {
        VisualFocusDraft focus = draft.visualFocus();
        if (draft.kind() != TeachingMove.VISUAL || focus == null || !attachedPages.contains(focus.pageNumber())) {
            return null;
        }
        boolean citesFocusedPage = draft.citationIds().stream()
                .map(allowedEvidence::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(source -> focus.pageNumber() >= source.pageFrom() && focus.pageNumber() <= source.pageTo());
        if (!citesFocusedPage) return null;
        try {
            return validatedFocus(focus);
        } catch (IllegalArgumentException invalidOptionalVisualFocus) {
            return null;
        }
    }

    static VisualFocus validatedFocus(VisualFocusDraft focus) {
        return new VisualFocus(
                focus.pageNumber(),
                focus.label(),
                focus.visibleDescription(),
                focus.x(),
                focus.y(),
                focus.width(),
                focus.height());
    }

    static void validateDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null) throw new IllegalArgumentException("The draft is missing.");
        if (draft.title() == null || draft.title().isBlank()) {
            throw new IllegalArgumentException("The title is missing.");
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
                || step.heading() == null || step.heading().isBlank()
                || step.kind() == null)) {
            throw new IllegalArgumentException("Every step needs a heading and a teaching kind.");
        }
    }
}
