package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel.RuleFactDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFact;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/** Minimal publication boundary: required structure, source identity, and no semantic prose rewriting. */
final class LessonDraftValidator {

    private LessonDraftValidator() {}

    static void validateDraft(SectionDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.steps().isEmpty()) {
            throw new IllegalArgumentException("teaching chapter requires a title and at least one step");
        }
        for (StepDraft step : draft.steps()) {
            if (step == null || step.content().isEmpty()) throw new IllegalArgumentException("teaching step content is required");
            for (var block : step.content()) {
                if (block == null || block.kind() == null || block.text() == null
                        || (block.kind() == com.rulepilot.teaching.domain.IllustratedLesson.ReadingKind.PARAGRAPH
                            && (block.text().isBlank() || !block.figures().isEmpty() || block.flow() != com.rulepilot.teaching.domain.IllustratedLesson.FigureFlow.NONE))
                        || (block.kind() == com.rulepilot.teaching.domain.IllustratedLesson.ReadingKind.FIGURES
                            && (!block.text().isEmpty() || block.figures().isEmpty() || block.flow() == null || block.flow() == com.rulepilot.teaching.domain.IllustratedLesson.FigureFlow.NONE))) {
                    throw new IllegalArgumentException("reading content must contain paragraphs and explicitly placed figure groups");
                }
                for (var figure : block.figures()) {
                    if (figure == null || figure.imageId() == null || figure.imageId().isBlank() || figure.caption() == null)
                        throw new IllegalArgumentException("reading figure needs an offered image identity and optional caption text");
                }
            }
        }
        if (draft.steps().stream().anyMatch(step -> step == null
                || step.heading() == null || step.heading().isBlank()
                || step.kind() == null
                || step.text() == null || step.text().isBlank()
                || step.citationIds().isEmpty())) {
            throw new IllegalArgumentException("every teaching step requires heading, kind, text, and evidence IDs");
        }
    }

    static List<Claim> reviewClaims(SectionDraft draft) {
        List<Claim> claims = new ArrayList<>();
        int position = 1;
        for (StepDraft step : draft.steps()) {
            claims.add(new Claim(position++, step.heading() + "：" + step.text(), step.citationIds()));
            for (var block : step.content()) {
                for (var figure : block.figures()) {
                    if (!figure.caption().isBlank()) claims.add(new Claim(position++, figure.caption(), step.citationIds()));
                }
            }
            for (RuleFactDraft fact : step.ruleFacts()) {
                claims.add(new Claim(position++, fact.text(), fact.citationIds()));
            }
        }
        return List.copyOf(claims);
    }

    static LessonStep validatedStep(int position, StepDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        LinkedHashSet<UUID> citationIds = validatedIds(
                draft.citationIds(), allowedEvidence, "teaching step cites evidence outside retrieval scope");
        List<Integer> pages = sourcePages(citationIds, allowedEvidence);
        return new LessonStep(
                position,
                draft.heading(),
                draft.kind(),
                draft.text(),
                pages,
                List.copyOf(citationIds),
                validatedRuleFacts(draft.ruleFacts(), allowedEvidence),
                null);
    }

    private static List<RuleFact> validatedRuleFacts(
            List<RuleFactDraft> drafts, Map<UUID, RuleEvidence> allowedEvidence) {
        List<RuleFactDraft> values = drafts == null ? List.of() : drafts;
        return IntStream.range(0, values.size())
                .mapToObj(index -> {
                    RuleFactDraft draft = values.get(index);
                    if (draft == null || draft.role() == null || draft.text() == null || draft.text().isBlank()) {
                        throw new IllegalArgumentException("teaching rule fact is invalid");
                    }
                    LinkedHashSet<UUID> ids = validatedIds(
                            draft.citationIds(),
                            allowedEvidence,
                            "teaching rule fact cites evidence outside retrieval scope");
                    return new RuleFact(
                            index + 1,
                            draft.role(),
                            draft.text(),
                            sourcePages(ids, allowedEvidence),
                            List.copyOf(ids));
                })
                .toList();
    }

    private static LinkedHashSet<UUID> validatedIds(
            List<UUID> ids, Map<UUID, RuleEvidence> allowedEvidence, String error) {
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>(ids == null ? List.of() : ids);
        if (distinct.isEmpty() || distinct.contains(null) || !allowedEvidence.keySet().containsAll(distinct)) {
            throw new IllegalArgumentException(error);
        }
        return distinct;
    }

    private static List<Integer> sourcePages(
            java.util.Collection<UUID> ids, Map<UUID, RuleEvidence> allowedEvidence) {
        return ids.stream()
                .map(allowedEvidence::get)
                .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .sorted()
                .boxed()
                .toList();
    }
}
