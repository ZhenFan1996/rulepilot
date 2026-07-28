package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps draft-repair decisions deterministic while the teaching agent owns model calls and evidence validation.
 */
final class TeachingDraftRecoveryPolicy {

    private static final int MAX_TEXT_REPAIR_ATTEMPTS = 3;
    private static final String VISUAL_REPAIR_GUIDANCE = "The attached page images are usable visual evidence. "
            + "Keep the grounded text, but repair one VISUAL step: cite an attached-page E-reference and return a "
            + "compact 0-1000 focus rectangle that contains the icon, component group, board area, flow, or worked "
            + "state named in that step. Do not fall back to text-only.";
    private static final String UNRESOLVED_ALTERNATIVE_REPAIR_GUIDANCE = "Rewrite the dangling alternative as a "
            + "complete grounded instruction. If the cited rule gives an exclusive choice, state every branch and its "
            + "result; otherwise retain only the resolved cited procedure. Do not end any player-facing step with “还是”, "
            + "“或者”, or another unanswered alternative.";
    private static final Pattern PLAYER_COUNT_ROUND_SCHEDULE = Pattern.compile(
            "(?i)(?:at\\s+the\\s+end\\s+of\\s+(?:the\\s+)?)?(\\d{1,2})(?:st|nd|rd|th)?\\s+round\\s*"
                    + "\\(\\s*if\\s+playing\\s+with\\s+([^)]+?)\\s+players?\\s*\\)");
    private static final Pattern PLAYER_COUNT = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");
    private static final Pattern PLAYER_COUNT_MENTION = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})\\s*(?:players?|人)");

    int maxRepairAttempts(boolean hasPageImages) {
        return hasPageImages ? 1 : MAX_TEXT_REPAIR_ATTEMPTS;
    }

    boolean canFallbackToCitedText(boolean hasPageImages, boolean hasOnlyVisualPageEvidence) {
        return hasPageImages && !hasOnlyVisualPageEvidence;
    }

    boolean shouldFallbackToCitedText(
            boolean hasPageImages, boolean hasOnlyVisualPageEvidence, int repairAttempt) {
        return canFallbackToCitedText(hasPageImages, hasOnlyVisualPageEvidence)
                && repairAttempt == maxRepairAttempts(hasPageImages);
    }

    List<String> repairFeedback(String diagnostic, boolean hasPageImages, boolean visualLocalizationFailure) {
        List<String> feedback = new ArrayList<>(List.of(diagnostic));
        if (diagnostic != null && diagnostic.contains("unanswered either/or alternative")) {
            feedback.add(UNRESOLVED_ALTERNATIVE_REPAIR_GUIDANCE);
        }
        if (hasPageImages && visualLocalizationFailure) feedback.add(VISUAL_REPAIR_GUIDANCE);
        return List.copyOf(feedback);
    }

    List<String> textFallbackFeedback(String diagnostic) {
        return List.of("Keep this section text-only and preserve all grounded rule coverage. " + diagnostic);
    }

    TeachingLessonModel.SectionRequest withoutPageImages(TeachingLessonModel.SectionRequest request) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.playerCount(),
                request.beginnerCount(),
                request.totalDurationMinutes(),
                request.sectionDurationSeconds(),
                request.maxSteps(),
                request.priorSections(),
                request.evidence(),
                List.of(),
                request.requiredRuleIntents(),
                request.modelConfigurationOwner(),
                request.chapterScope());
    }

    SectionDraft preserveTextOnlyPresentationMetadata(SectionDraft previous, SectionDraft revised) {
        if (previous == null || revised == null) return revised;
        String caption = revised.visualCaption();
        if (caption == null || caption.isBlank() || caption.length() > 240) {
            caption = previous.visualCaption();
        }
        List<UUID> citations = revised.visualCitationIds();
        if (citations == null || citations.isEmpty()) {
            citations = previous.visualCitationIds();
        }
        VisualKind visualKind = revised.visualKind() == null ? previous.visualKind() : revised.visualKind();
        if (Objects.equals(caption, revised.visualCaption())
                && Objects.equals(citations, revised.visualCitationIds())
                && visualKind == revised.visualKind()) {
            return revised;
        }
        return new SectionDraft(revised.title(), visualKind, caption, citations, revised.steps());
    }

    /**
     * The validator has already established that the cited rule retains play until the current round ends. Replacing
     * only an unsupported immediate-ending paraphrase with that narrower timing statement is safer and faster than
     * repeatedly asking a model to restate the same cited sentence.
     */
    SectionDraft preserveCitedEndOfRoundTiming(SectionDraft draft, List<RuleEvidence> evidence) {
        if (draft == null || evidence == null || evidence.isEmpty()) return draft;
        Map<UUID, RuleEvidence> evidenceById = evidence.stream().collect(java.util.stream.Collectors.toMap(
                RuleEvidence::chunkId, value -> value, (first, ignored) -> first));
        List<StepDraft> corrected = new ArrayList<>();
        boolean changed = false;
        for (StepDraft step : draft.steps()) {
            List<RuleEvidence> cited = step.citationIds().stream()
                    .map(evidenceById::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (LessonDraftValidator.claimsImmediateEndingForEndOfRoundTrigger(step.text(), cited)) {
                corrected.add(new StepDraft(
                        step.heading(),
                        step.kind(),
                        "触发这一终局条件后，先完成当前轮次；游戏在本轮结束时结束。",
                        step.citationIds(),
                        step.visualFocus()));
                changed = true;
            } else {
                corrected.add(step);
            }
        }
        return changed
                ? new SectionDraft(draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), corrected)
                : draft;
    }

    /**
     * Retains every directly cited player-count/round pairing when a model narrows an end condition to the requested
     * game size. This is a bounded transcription of numbers and timing only; it never infers a new threshold.
     */
    SectionDraft preserveCitedPlayerCountRoundSchedule(SectionDraft draft, List<RuleEvidence> evidence) {
        if (draft == null || evidence == null || evidence.isEmpty()) return draft;
        List<PlayerRoundSchedule> schedules = evidence.stream()
                .flatMap(source -> schedules(source.excerpt(), source.chunkId()).stream())
                .toList();
        Set<String> allPlayerCounts = schedules.stream()
                .flatMap(schedule -> schedule.playerCounts().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allPlayerCounts.size() < 2) return draft;
        String playerText = draft.steps().stream().map(StepDraft::text).collect(java.util.stream.Collectors.joining("\n"));
        boolean namesAnyPlayerCount = PLAYER_COUNT_MENTION.matcher(playerText).find();
        boolean namesEveryPlayerCount = allPlayerCounts.stream()
                .allMatch(playerCount -> Pattern.compile("(?<!\\d)" + Pattern.quote(playerCount) + "\\s*(?:players?|人)")
                        .matcher(playerText)
                        .find());
        if (!namesAnyPlayerCount || namesEveryPlayerCount) return draft;

        String scheduleText = schedules.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PlayerRoundSchedule::round,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.flatMapping(
                                schedule -> schedule.playerCounts().stream(),
                                java.util.stream.Collectors.toCollection(LinkedHashSet::new))))
                .entrySet()
                .stream()
                .map(entry -> String.join("、", entry.getValue()) + "人均在第" + entry.getKey() + "轮结束")
                .collect(java.util.stream.Collectors.joining("；", "完整人数对应为：", "。"));
        Set<UUID> scheduleEvidence = schedules.stream()
                .map(PlayerRoundSchedule::evidenceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (int index = 0; index < draft.steps().size(); index++) {
            StepDraft step = draft.steps().get(index);
            if (step == null || step.text() == null || step.text().isBlank()) continue;
            String amended = step.text().strip() + " " + scheduleText;
            if (amended.length() > 600) continue;
            List<UUID> citations = new ArrayList<>(step.citationIds());
            citations.addAll(scheduleEvidence);
            List<StepDraft> steps = new ArrayList<>(draft.steps());
            steps.set(index, new StepDraft(
                    step.heading(), step.kind(), amended, List.copyOf(new LinkedHashSet<>(citations)), step.visualFocus()));
            return new SectionDraft(draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), steps);
        }
        return draft;
    }

    private List<PlayerRoundSchedule> schedules(String excerpt, UUID evidenceId) {
        if (excerpt == null || excerpt.isBlank()) return List.of();
        var matcher = PLAYER_COUNT_ROUND_SCHEDULE.matcher(excerpt);
        List<PlayerRoundSchedule> schedules = new ArrayList<>();
        while (matcher.find()) {
            var playerMatcher = PLAYER_COUNT.matcher(matcher.group(2));
            Set<String> playerCounts = new LinkedHashSet<>();
            while (playerMatcher.find()) playerCounts.add(playerMatcher.group(1));
            if (!playerCounts.isEmpty()) {
                schedules.add(new PlayerRoundSchedule(matcher.group(1), Set.copyOf(playerCounts), evidenceId));
            }
        }
        return List.copyOf(schedules);
    }

    private record PlayerRoundSchedule(String round, Set<String> playerCounts, UUID evidenceId) {}
}
