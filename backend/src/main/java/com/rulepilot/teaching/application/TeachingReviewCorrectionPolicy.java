package com.rulepilot.teaching.application;

import com.rulepilot.assistant.GeneratedContentCritic;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Keeps post-publication correction choices bounded while the teaching agent owns review, revision, and publication.
 */
final class TeachingReviewCorrectionPolicy {

    private static final int MAX_FACTUAL_CORRECTIONS = 4;
    private static final int MAX_CHAPTER_SCOPE_CORRECTIONS = 2;

    CorrectionKind correctionKind(List<GeneratedContentCritic.Issue> issues) {
        return issues.stream().allMatch(issue -> issue.type() == GeneratedContentCritic.IssueType.CHAPTER_SCOPE_DUPLICATION)
                ? CorrectionKind.CHAPTER_SCOPE
                : CorrectionKind.FACTUAL;
    }

    boolean correctionBudgetExhausted(
            CorrectionKind correctionKind, int factualCorrectionsStarted, int scopeCorrectionsStarted) {
        return correctionKind == CorrectionKind.CHAPTER_SCOPE
                ? scopeCorrectionsStarted >= MAX_CHAPTER_SCOPE_CORRECTIONS
                : factualCorrectionsStarted >= MAX_FACTUAL_CORRECTIONS;
    }

    List<String> correctionFeedback(List<GeneratedContentCritic.Issue> issues) {
        return List.of("Whole-lesson objective coverage review found: " + issues.stream()
                .map(issue -> issue.type() + " evidence=" + issue.evidenceIds() + " - " + issue.summary())
                .collect(Collectors.joining("; "))
                + ". Correct only from the supplied evidence and audit the entire revised section for new claims. "
                + chapterScopeCorrectionInstruction(issues)
                + "If any issue touches a worked example whose concrete species, component, quantity, pairing, or "
                + "board state is not directly stated in evidence, delete that concrete example or replace it with "
                + "a neutral procedure; do not invent a different example. Recount setup inventory against items "
                + "already moved out of a supply. Do not add a new factual sentence merely to replace a removed one.");
    }

    List<String> structuralRepairFeedback(List<String> correctionFeedback, String rejectionCategory) {
        List<String> feedback = new ArrayList<>(correctionFeedback);
        feedback.add("The prior correction was structurally invalid: " + rejectionCategory
                + ". Return a complete replacement section with a short heading, teaching kind, text, and valid "
                + "citations for every step. Preserve the requested correction; do not restore the removed claim.");
        return List.copyOf(feedback);
    }

    String criticDiagnostic(List<GeneratedContentCritic.Issue> issues) {
        String diagnostic = "CRITIC_" + issues.stream()
                .collect(Collectors.groupingBy(
                        GeneratedContentCritic.Issue::type,
                        TreeMap::new,
                        Collectors.mapping(
                                GeneratedContentCritic.Issue::claimPosition,
                                Collectors.collectingAndThen(
                                        Collectors.toCollection(TreeSet::new),
                                        positions -> positions.stream()
                                                .map(String::valueOf)
                                                .collect(Collectors.joining(","))))))
                .entrySet().stream()
                .map(entry -> entry.getKey() + "@" + entry.getValue())
                .collect(Collectors.joining("+"));
        return diagnostic.length() <= 180 ? diagnostic : diagnostic.substring(0, 180);
    }

    private String chapterScopeCorrectionInstruction(List<GeneratedContentCritic.Issue> issues) {
        boolean hasScopeDuplication = issues.stream()
                .anyMatch(issue -> issue.type() == GeneratedContentCritic.IssueType.CHAPTER_SCOPE_DUPLICATION);
        if (!hasScopeDuplication) return "";
        return "For CHAPTER_SCOPE_DUPLICATION, retain the player-visible stage, order, or decision that this chapter "
                + "owns, but remove the nested cost, reward, exception, calculation, or component detail explicitly "
                + "assigned to a later chapter. Do not remove the stage altogether and do not replace it with a vague "
                + "promise; the later chapter remains responsible for the full detail. Remove the named duplicated "
                + "claim rather than paraphrasing the same full procedure more briefly. ";
    }

    enum CorrectionKind {
        CHAPTER_SCOPE,
        FACTUAL
    }
}
