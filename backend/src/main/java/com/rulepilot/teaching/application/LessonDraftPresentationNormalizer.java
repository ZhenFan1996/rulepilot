package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Normalizes untrusted lesson-draft presentation without creating or changing a rule claim. */
final class LessonDraftPresentationNormalizer {

    private static final String FALLBACK_STEP_HEADING = "本步要点";
    private static final int MAX_STEP_HEADING_CHARACTERS = 32;
    private static final Pattern UNRESOLVED_PDF_MARKER = Pattern.compile("\\[([A-Za-z][A-Za-z _-]{0,30})]");
    private static final Pattern UNRESOLVED_EMOJI_ICON = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
    private static final Map<String, String> PLAYER_FACING_EMOJI_ICON_LABELS = Map.ofEntries(
            Map.entry("👣", "“脚印（移动）”图标"),
            Map.entry("🟠", "“橙色圆形”图标"),
            Map.entry("🔵", "“蓝色圆形”图标"),
            Map.entry("🟣", "“紫色圆形”图标"),
            Map.entry("🟤", "“棕色圆形”图标"),
            Map.entry("🔴", "“红色圆形”图标"),
            Map.entry("🟡", "“黄色圆形”图标"),
            Map.entry("🟢", "“绿色圆形”图标"),
            Map.entry("⚫", "“黑色圆形”图标"),
            Map.entry("⚪", "“白色圆形”图标"),
            Map.entry("⬛", "“黑色方块”图标"),
            Map.entry("⬜", "“白色方块”图标"),
            Map.entry("✋", "“手掌”图标"),
            Map.entry("🖐", "“手掌”图标"),
            Map.entry("🎲", "“骰子”图标"));
    private static final Pattern TRAILING_INCOMPLETE_THOUGHT = Pattern.compile(
            "(?:…+|\\.\\.\\.)\\s*(?:完成(?:了)?|结束(?:了)?|等等|后续|其余)?[。！？!?]?\\s*$");
    private static final Pattern TRAILING_UNANSWERED_ALTERNATIVE = Pattern.compile(
            "(?:[，；:：]\\s*(?:还是|或者|抑或)\\s*[^。！？!?]{2,80})[。！？!?]?\\s*$");
    private static final Pattern TEXT_ONLY_PRESENTATION_MARKER = Pattern.compile(
            "(?i)(attached|attachment|image|rulebook|page\\s*\\d|图片|附件|规则书|第\\s*\\d+\\s*页|页面)");
    private static final Pattern VISUAL_LABEL_CONTAINS_LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern INTERNAL_EVIDENCE_MARKER = Pattern.compile(
            "(?i)(已提供的证据|提供的证据|当前证据|现有证据|证据中(?:没有|未|并未|不)|检索(?:结果|内容|证据)|"
                    + "retriev(?:al|ed)|(?:provided|supplied|current) evidence|evidence (?:does not|doesn't|did not))");
    private static final Pattern INTERNAL_SHORT_EVIDENCE_REFERENCE = Pattern.compile("(?<![\\p{L}\\p{N}])E\\d{1,2}(?![\\p{L}\\p{N}])");
    private static final Pattern LEADING_INTERNAL_EVIDENCE_LANGUAGE = Pattern.compile(
            "(?i)(?:(?:根据|依据|基于|从)\\s*)?"
                    + "(?:(?:已提供的|当前|现有)\\s*(?:检索(?:结果|内容|证据)|证据)|检索(?:结果|内容|证据))"
                    + "(?!\\s*(?:中|里)?\\s*(?:没有|未|并未))\\s*(?:中|里)?\\s*"
                    + "(?:显示|表明|说明|可知|可见|来看)?\\s*[，,:：]*");

    SectionDraft normalize(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        SectionDraft normalized = normalizePlayerText(draft);
        normalized = normalizeSectionTitle(normalized, request);
        normalized = normalizeStepMetadata(normalized);
        normalized = normalizePresentationMetadata(normalized, request.pageImages().isEmpty());
        normalized = alignVisualStepsWithPageEvidence(normalized, request);
        normalized = normalizeVisualFocusLabels(normalized);
        return alignVisualCaptionWithStep(normalized, request.pageImages().isEmpty());
    }

    static boolean containsUnresolvedPdfMarker(String value) {
        return value != null && UNRESOLVED_PDF_MARKER.matcher(value).find();
    }

    static boolean containsUnresolvedEmojiIcon(String value) {
        return value != null && UNRESOLVED_EMOJI_ICON.matcher(value).find();
    }

    static boolean containsTrailingIncompleteThought(String value) {
        return value != null && TRAILING_INCOMPLETE_THOUGHT.matcher(value).find();
    }

    static boolean containsTrailingUnansweredAlternative(String value) {
        return value != null && TRAILING_UNANSWERED_ALTERNATIVE.matcher(value).find();
    }

    static boolean containsInternalEvidenceLanguage(String value) {
        return value != null && INTERNAL_EVIDENCE_MARKER.matcher(value).find();
    }

    static boolean containsInternalShortEvidenceReference(String value) {
        return value != null && INTERNAL_SHORT_EVIDENCE_REFERENCE.matcher(value).find();
    }

    private SectionDraft normalizePlayerText(SectionDraft draft) {
        if (draft == null || draft.steps() == null) return draft;
        List<StepDraft> normalizedSteps = draft.steps().stream()
                .map(step -> step == null
                        ? null
                        : new StepDraft(
                                playerFacingText(step.heading()),
                                step.kind(),
                                playerFacingText(step.text()),
                                step.citationIds(),
                                step.visualFocus()))
                .toList();
        String title = playerFacingText(draft.title());
        String caption = playerFacingText(draft.visualCaption());
        if (java.util.Objects.equals(title, draft.title())
                && java.util.Objects.equals(caption, draft.visualCaption())
                && normalizedSteps.equals(draft.steps())) return draft;
        return new SectionDraft(title, draft.visualKind(), caption, draft.visualCitationIds(), normalizedSteps);
    }

    private SectionDraft normalizeSectionTitle(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length() > 160) {
            if (draft == null) return null;
            return new SectionDraft(
                    request.title().strip(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), draft.steps());
        }
        return draft;
    }

    /** A missing presentation label is not a missing rule claim; retain the cited text and use a neutral label. */
    private SectionDraft normalizeStepMetadata(SectionDraft draft) {
        if (draft == null || draft.steps() == null) return draft;
        boolean changed = false;
        List<StepDraft> steps = new ArrayList<>(draft.steps().size());
        for (StepDraft step : draft.steps()) {
            if (step == null) {
                steps.add(null);
                continue;
            }
            String heading = step.heading();
            if (heading == null || heading.isBlank() || heading.length() > MAX_STEP_HEADING_CHARACTERS) {
                heading = FALLBACK_STEP_HEADING;
                changed = true;
            }
            TeachingMove kind = step.kind();
            if (kind == null) {
                kind = TeachingMove.UNDERSTAND;
                changed = true;
            }
            steps.add(new StepDraft(heading, kind, step.text(), step.citationIds(), step.visualFocus()));
        }
        return changed
                ? new SectionDraft(draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), steps)
                : draft;
    }

    private String playerFacingText(String value) {
        String normalized = naturalLanguageEmojiIcon(naturalLanguageIconMarker(value));
        if (normalized == null || normalized.isBlank()) return normalized;
        return LEADING_INTERNAL_EVIDENCE_LANGUAGE.matcher(normalized).replaceAll("").strip();
    }

    private String naturalLanguageIconMarker(String value) {
        if (value == null || value.isBlank()) return value;
        return UNRESOLVED_PDF_MARKER.matcher(value).replaceAll("“$1”图标");
    }

    private String naturalLanguageEmojiIcon(String value) {
        if (value == null || value.isBlank()) return value;
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> normalized.append(PLAYER_FACING_EMOJI_ICON_LABELS.getOrDefault(
                new String(Character.toChars(codePoint)), new String(Character.toChars(codePoint)))));
        return normalized.toString().replace("图标 图标", "图标").replace("图标图标", "图标");
    }

    private SectionDraft alignVisualStepsWithPageEvidence(
            SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null || request.pageImages().isEmpty()) return draft;
        Set<Integer> attachedPages = request.pageImages().stream()
                .map(TeachingLessonModel.PageImageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        Map<UUID, TeachingLessonModel.EvidenceInput> evidenceById = request.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(TeachingLessonModel.EvidenceInput::chunkId, Function.identity()));
        boolean changed = false;
        List<StepDraft> steps = new ArrayList<>(draft.steps().size());
        for (StepDraft step : draft.steps()) {
            if (step == null || step.kind() != TeachingMove.VISUAL) {
                steps.add(step);
                continue;
            }
            boolean alreadyPageBacked = step.citationIds().stream()
                    .map(evidenceById::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()).anyMatch(attachedPages::contains));
            if (alreadyPageBacked) {
                steps.add(step);
                continue;
            }
            UUID pageEvidence = request.evidence().stream()
                    .filter(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()).anyMatch(attachedPages::contains))
                    .map(TeachingLessonModel.EvidenceInput::chunkId)
                    .findFirst()
                    .orElse(null);
            if (pageEvidence == null) {
                steps.add(step);
                continue;
            }
            List<UUID> citations = new ArrayList<>(step.citationIds());
            citations.add(pageEvidence);
            steps.add(new StepDraft(
                    step.heading(), step.kind(), step.text(), List.copyOf(new LinkedHashSet<>(citations)), step.visualFocus()));
            changed = true;
        }
        return changed
                ? new SectionDraft(draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), steps)
                : draft;
    }

    private SectionDraft alignVisualCaptionWithStep(SectionDraft draft, boolean textOnly) {
        if (draft == null || textOnly) return draft;
        StepDraft visualStep = draft.steps().stream()
                .filter(step -> step != null && step.kind() == TeachingMove.VISUAL)
                .findFirst()
                .orElse(null);
        if (visualStep == null) return draft;
        String caption = visualStep.text().length() <= 240 ? visualStep.text() : visualStep.heading();
        return new SectionDraft(draft.title(), draft.visualKind(), caption, visualStep.citationIds(), draft.steps());
    }

    private SectionDraft normalizeVisualFocusLabels(SectionDraft draft) {
        if (draft == null || draft.steps() == null) return draft;
        boolean changed = false;
        List<StepDraft> steps = new ArrayList<>(draft.steps().size());
        for (StepDraft step : draft.steps()) {
            if (step == null || step.visualFocus() == null) {
                steps.add(step);
                continue;
            }
            VisualFocusDraft focus = step.visualFocus();
            String label = playerFacingVisualLabel(focus.label(), step.heading());
            if (label.equals(focus.label())) {
                steps.add(step);
                continue;
            }
            steps.add(new StepDraft(
                    step.heading(),
                    step.kind(),
                    step.text(),
                    step.citationIds(),
                    new VisualFocusDraft(focus.pageNumber(), label, focus.x(), focus.y(), focus.width(), focus.height())));
            changed = true;
        }
        return changed
                ? new SectionDraft(draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), steps)
                : draft;
    }

    private String playerFacingVisualLabel(String label, String fallbackHeading) {
        String normalized = playerFacingText(label);
        if (normalized == null || normalized.isBlank() || VISUAL_LABEL_CONTAINS_LATIN.matcher(normalized).find()) {
            String fallback = playerFacingText(fallbackHeading);
            return fallback == null || fallback.isBlank() || VISUAL_LABEL_CONTAINS_LATIN.matcher(fallback).find()
                    ? "图示重点"
                    : fallback;
        }
        return normalized;
    }

    private SectionDraft normalizePresentationMetadata(SectionDraft draft, boolean textOnly) {
        if (draft == null || draft.steps() == null) return draft;
        StepDraft anchor = draft.steps().stream()
                .filter(step -> step != null
                        && step.heading() != null && !step.heading().isBlank()
                        && step.text() != null && !step.text().isBlank()
                        && step.citationIds() != null && !step.citationIds().isEmpty())
                .findFirst()
                .orElse(null);
        if (anchor == null) return draft;
        String caption = draft.visualCaption();
        if (caption == null || caption.isBlank() || caption.length() > 240
                || textOnly && TEXT_ONLY_PRESENTATION_MARKER.matcher(caption).find()) {
            caption = anchor.text().length() <= 240 ? anchor.text() : anchor.heading();
        }
        List<UUID> visualCitations = draft.visualCitationIds();
        if (visualCitations == null || visualCitations.isEmpty()) visualCitations = anchor.citationIds();
        if (caption.equals(draft.visualCaption()) && visualCitations.equals(draft.visualCitationIds())) return draft;
        return new SectionDraft(draft.title(), draft.visualKind(), caption, visualCitations, draft.steps());
    }
}
