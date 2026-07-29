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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Deterministic structure, citation-scope, timing, and visual-focus validation for a lesson draft. */
final class LessonDraftValidator {

    private static final int MAX_STEPS_PER_SECTION = 6;
    private static final int MAX_VISUAL_FOCUS_AREA = 720_000;
    private static final Pattern END_OF_ROUND_SOURCE = Pattern.compile(
            "(?i)(?:\\bat\\s+the\\s+end\\s+of\\s+(?:a|the|this|that)\\s+round\\b|"
                    + "\\b(?:when|after)\\s+(?:the|a|this|that)?\\s*round\\s+ends?\\b|"
                    + "(?:本|该|一)?轮(?:结束|末)|回合结束)");
    private static final Pattern IMMEDIATE_ENDING = Pattern.compile(
            "(?i)(?:(?:游戏|game).{0,16}(?:立即|立刻|马上|即刻).{0,8}结束|"
                    + "(?:立即|立刻|马上|即刻).{0,8}结束|"
                    + "(?:ends?|ending).{0,16}\\bimmediately\\b|\\bimmediately\\b.{0,16}(?:ends?|ending))");
    private static final Pattern ENDGAME_CHECK_SOURCE = Pattern.compile(
            "(?i)(?:\\b(?:end game|game end)\\b.{0,120}\\b(?:if|when)\\b|"
                    + "(?:游戏结束|结束检查).{0,120}(?:如果|当|若))");
    private static final Pattern DEFERS_CLEANUP_ENDGAME_CHECK = Pattern.compile(
            "(?i)(?:(?:清理|cleanup).{0,56}(?:不执行|不进行|不检查|does not check|do not check).{0,56}"
                    + "(?:游戏结束|结束检查|end game|game end)|"
                    + "(?:游戏结束|结束检查|end game|game end).{0,80}(?:最终计分阶段|final scoring phase))");
    private static final Pattern PLAYER_COUNT_VALUE = Pattern.compile(
            "(?i)(?<!\\d)(\\d{1,2})\\s*(?:players?|人)\\s*(?:(?:[:：=]|[-–—])\\s*|(?:need|needs|require|requires|须|需要|为)\\s*)(\\d{1,4})(?!\\d)");
    private static final Pattern PLAYER_COUNT_RANGE = Pattern.compile(
            "(?i)(?:if\\s+playing\\s+with|for)\\s*([0-9\\s,;/\\p{L}–—-]{3,48})\\s*players?");
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");
    private static final Pattern SHARED_TIE_SOURCE = Pattern.compile(
            "(?i)(?:shared\s+(?:victory|win)|share\s+(?:the\s+)?victory|共同获胜|并列获胜)");
    private static final Pattern TIE_LANGUAGE = Pattern.compile("(?i)(?:平局|并列|\\btie\\b)");
    private static final Pattern SHARED_TIE_LANGUAGE = Pattern.compile(
            "(?i)(?:共享(?:胜利|获胜)?|共同获胜|并列获胜|\\bshared\s+(?:victory|win)\\b)");

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

    /**
     * A public lesson must not reduce a cited player-count table to the currently selected player count. The check is
     * deliberately narrow: it applies only when the cited excerpts contain two or more explicit player-count/value
     * rows, and it accepts compact prose such as "2、3、4 人分别为 7、6、5".
     */
    static void validatePlayerCountConditionalValues(SectionDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        Set<UUID> citedEvidenceIds = new LinkedHashSet<>(draft.visualCitationIds());
        draft.steps().forEach(step -> citedEvidenceIds.addAll(step.citationIds()));
        List<PlayerCountValue> conditions = citedEvidenceIds.stream()
                .map(allowedEvidence::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(evidence -> playerCountValues(evidence.excerpt()).stream())
                .toList();
        if (conditions.stream().map(PlayerCountValue::playerCount).distinct().count() < 2) return;
        Set<String> requiredNumbers = conditions.stream()
                .flatMap(condition -> java.util.stream.Stream.of(condition.playerCount(), condition.value()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String playerFacingText = draft.title() + "\n" + draft.visualCaption() + "\n" + draft.steps().stream()
                .flatMap(step -> java.util.stream.Stream.of(step.heading(), step.text()))
                .collect(Collectors.joining("\n"));
        boolean refersToAListedPlayerCount = conditions.stream()
                .map(PlayerCountValue::playerCount)
                .distinct()
                .anyMatch(playerCount -> containsPlayerCount(playerFacingText, playerCount));
        if (!refersToAListedPlayerCount) return;
        boolean complete = requiredNumbers.stream().allMatch(number -> containsNumber(playerFacingText, number));
        if (!complete) {
            String citedRows = conditions.stream()
                    .distinct()
                    .map(condition -> condition.playerCount() + " players: " + condition.value())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "When cited evidence gives values by player count, teach every listed player-count/value condition "
                            + "instead of reducing it to one example. Include these direct cited rows: " + citedRows);
        }
    }

    /** Ensures a public explanation does not silently shrink an evidenced player-count range to its requested size. */
    static void validatePlayerCountConditionalScopes(SectionDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        Set<UUID> citedEvidenceIds = new LinkedHashSet<>(draft.visualCitationIds());
        draft.steps().forEach(step -> citedEvidenceIds.addAll(step.citationIds()));
        List<PlayerCountRange> ranges = citedEvidenceIds.stream()
                .map(allowedEvidence::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(evidence -> playerCountRanges(evidence.excerpt()).stream())
                .toList();
        if (ranges.isEmpty()) return;

        String playerFacingText = draft.title() + "\n" + draft.visualCaption() + "\n" + draft.steps().stream()
                .flatMap(step -> java.util.stream.Stream.of(step.heading(), step.text()))
                .collect(Collectors.joining("\n"));
        boolean refersToARangedPlayerCount = ranges.stream()
                .flatMap(range -> range.playerCounts().stream())
                .distinct()
                .anyMatch(playerCount -> containsPlayerCount(playerFacingText, playerCount));
        if (!refersToARangedPlayerCount) return;

        Set<String> requiredPlayerCounts = ranges.stream()
                .flatMap(range -> range.playerCounts().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requiredPlayerCounts.stream().allMatch(playerCount -> containsPlayerCount(playerFacingText, playerCount))) {
            return;
        }
        String citedRanges = ranges.stream()
                .map(PlayerCountRange::sourcePhrase)
                .distinct()
                .collect(Collectors.joining("; "));
        throw new IllegalArgumentException(
                "When cited evidence states a player-count range, preserve every listed player count instead of "
                        + "narrowing it to the requested game size. Include these direct cited conditions: " + citedRanges);
    }

    /** A cited shared-victory fallback is material: a lesson cannot replace it with another comparison or silence. */
    static void validateSharedTieResolution(SectionDraft draft, Map<UUID, RuleEvidence> allowedEvidence) {
        // The model must not be able to omit the one citation that carries the shared-victory fallback and thereby
        // sidestep this check. The retrieval set is already restricted to the current section's evidence.
        boolean retrievedSharedTieResolution = allowedEvidence.values().stream()
                .map(RuleEvidence::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(excerpt -> SHARED_TIE_SOURCE.matcher(excerpt).find());
        if (!retrievedSharedTieResolution) return;
        String playerFacingText = draft.title() + "\n" + draft.visualCaption() + "\n" + draft.steps().stream()
                .flatMap(step -> java.util.stream.Stream.of(step.heading(), step.text()))
                .collect(Collectors.joining("\n"));
        if (TIE_LANGUAGE.matcher(playerFacingText).find() && !SHARED_TIE_LANGUAGE.matcher(playerFacingText).find()) {
            throw new IllegalArgumentException(
                    "The cited tie-break chain ends in a shared victory. Preserve that final resolution and the "
                            + "printed comparison order instead of adding another criterion.");
        }
    }

    private static List<PlayerCountValue> playerCountValues(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) return List.of();
        var matcher = PLAYER_COUNT_VALUE.matcher(excerpt);
        List<PlayerCountValue> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(new PlayerCountValue(matcher.group(1), matcher.group(2)));
        }
        return List.copyOf(values);
    }

    private static List<PlayerCountRange> playerCountRanges(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) return List.of();
        var matcher = PLAYER_COUNT_RANGE.matcher(excerpt);
        List<PlayerCountRange> ranges = new ArrayList<>();
        while (matcher.find()) {
            String phrase = matcher.group().strip();
            var numberMatcher = NUMBER.matcher(matcher.group(1));
            Set<String> playerCounts = new LinkedHashSet<>();
            while (numberMatcher.find()) playerCounts.add(numberMatcher.group(1));
            if (playerCounts.size() >= 2) ranges.add(new PlayerCountRange(phrase, Set.copyOf(playerCounts)));
        }
        return List.copyOf(ranges);
    }

    private static boolean containsNumber(String text, String number) {
        return Pattern.compile("(?<!\\d)" + Pattern.quote(number) + "(?!\\d)").matcher(text).find();
    }

    private static boolean containsPlayerCount(String text, String playerCount) {
        return Pattern.compile("(?i)(?<!\\d)" + Pattern.quote(playerCount) + "\\s*(?:players?|人)")
                .matcher(text)
                .find();
    }

    private record PlayerCountValue(String playerCount, String value) {}

    private record PlayerCountRange(String sourcePhrase, Set<String> playerCounts) {}

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
        List<RuleEvidence> citedEvidence = citationIds.stream().map(allowedEvidence::get).toList();
        if (claimsImmediateEndingForEndOfRoundTrigger(draft.text(), citedEvidence)) {
            throw new IllegalArgumentException(
                    "When cited rules say an end condition occurs at the end of a round, do not rewrite it as an "
                            + "immediate ending.");
        }
        if (defersCitedEndgameCheck(draft.text(), citedEvidence)) {
            throw new IllegalArgumentException(
                    "Do not move a cited end-game check to a separate final-scoring phase or say that "
                            + "cleanup skips it.");
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
                draft.heading().strip(),
                draft.kind(),
                draft.text().strip(),
                pages,
                List.copyOf(citationIds),
                validatedVisualFocus(draft));
    }

    static boolean claimsImmediateEndingForEndOfRoundTrigger(String playerText, List<RuleEvidence> citedEvidence) {
        if (playerText == null || citedEvidence == null || citedEvidence.isEmpty()) {
            return false;
        }
        boolean citesEndOfRound = citedEvidence.stream()
                .map(RuleEvidence::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(excerpt -> END_OF_ROUND_SOURCE.matcher(excerpt).find());
        return citesEndOfRound && IMMEDIATE_ENDING.matcher(playerText).find();
    }

    static boolean defersCitedEndgameCheck(String playerText, List<RuleEvidence> citedEvidence) {
        if (playerText == null || citedEvidence == null || citedEvidence.isEmpty()) {
            return false;
        }
        return citedEvidence.stream()
                        .map(RuleEvidence::excerpt)
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(excerpt -> ENDGAME_CHECK_SOURCE.matcher(excerpt).find())
                && DEFERS_CLEANUP_ENDGAME_CHECK.matcher(playerText).find();
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
        return new VisualFocus(focus.pageNumber(), focus.label(), x, y, width, height);
    }

    static void validateDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null) throw new IllegalArgumentException("The draft is missing.");
        if (draft.title() == null || draft.title().isBlank() || draft.title().length() > 160)
            throw new IllegalArgumentException("The title is missing or longer than 160 characters.");
        if (draft.visualKind() == null) throw new IllegalArgumentException("visualKind is missing.");
        if (draft.visualCaption() == null || draft.visualCaption().isBlank())
            throw new IllegalArgumentException("The visual caption is missing.");
        if (draft.visualCaption().length() > 240)
            throw new IllegalArgumentException("The visual caption is longer than 240 characters.");
        if (draft.visualCitationIds().isEmpty())
            throw new IllegalArgumentException("The visual caption has no evidence citation.");
        if (draft.steps().isEmpty() || draft.steps().size() > Math.min(MAX_STEPS_PER_SECTION, request.maxSteps()))
            throw new IllegalArgumentException("The draft must contain between 1 and "
                    + Math.min(MAX_STEPS_PER_SECTION, request.maxSteps()) + " steps.");
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
                    "Attached rulebook pages were selected because this topic needs visual teaching. "
                            + "Replace one suitable step with a VISUAL step that tells the player what to locate and "
                            + "includes a tight visualFocus rectangle on an attached, cited page.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL
                && step.visualFocus() == null)) {
            throw new IllegalArgumentException("VISUAL teaching blocks require a visual focus region.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() != TeachingMove.VISUAL
                && step.visualFocus() != null)) {
            throw new IllegalArgumentException("Only VISUAL teaching blocks may define a visual focus region.");
        }
        if (LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker(draft.visualCaption())
                || draft.steps().stream().anyMatch(step -> step != null && step.text() != null
                        && LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker(step.text()))) {
            throw new IllegalArgumentException(
                    "Replace unresolved PDF icon markers with natural Simplified Chinese terms.");
        }
        if (LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon(draft.visualCaption())
                || draft.steps().stream().anyMatch(step -> step != null && step.text() != null
                        && LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon(step.text()))) {
            throw new IllegalArgumentException(
                    "Replace inferred emoji icons with an evidenced natural-language rule term.");
        }
        if (draft.steps().stream().anyMatch(step -> step != null
                && step.text() != null
                && LessonDraftPresentationNormalizer.containsTrailingIncompleteThought(step.text()))) {
            throw new IllegalArgumentException(
                    "Finish every player-facing step; do not end a rule, example, or calculation with an ellipsis.");
        }
        if (draft.steps().stream().anyMatch(step -> step != null
                && step.kind() != TeachingMove.CHECK
                && step.text() != null
                && LessonDraftPresentationNormalizer.containsTrailingUnansweredAlternative(step.text()))) {
            throw new IllegalArgumentException(
                    "Finish every player-facing instruction; do not end it with an unanswered either/or alternative.");
        }
        if (LessonDraftPresentationNormalizer.containsInternalEvidenceLanguage(draft.visualCaption())
                || draft.steps().stream().anyMatch(step -> step != null
                        && (LessonDraftPresentationNormalizer.containsInternalEvidenceLanguage(step.heading())
                                || LessonDraftPresentationNormalizer.containsInternalEvidenceLanguage(step.text())))) {
            throw new IllegalArgumentException(
                    "Remove internal evidence or retrieval language and teach the player-facing rule directly.");
        }
        if (LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(draft.visualCaption())
                || draft.steps().stream().anyMatch(step -> step != null
                        && (LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(step.heading())
                                || LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference(step.text())))) {
            throw new IllegalArgumentException(
                    "Remove internal short evidence references such as E1 from player-facing teaching text.");
        }
        if (PlayerFacingLessonLanguagePolicy.hasSourceGap(draft.visualCaption())
                || draft.steps().stream().anyMatch(step -> step != null
                        && (PlayerFacingLessonLanguagePolicy.hasSourceGap(step.heading())
                                || PlayerFacingLessonLanguagePolicy.hasSourceGap(step.text())))) {
            throw new IllegalArgumentException(
                    "Do not show players a source gap, pending rule, or request to wait; teach a supported rule directly.");
        }
    }
}
