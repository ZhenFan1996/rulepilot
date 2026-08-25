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

    static int maximumModelCalls() {
        // Each semantic correction may make one request plus one typed structured-output repair.
        return Math.multiplyExact(MAX_FACTUAL_CORRECTIONS + MAX_CHAPTER_SCOPE_CORRECTIONS, 2);
    }

    CorrectionKind correctionKind(List<GeneratedContentCritic.Issue> issues) {
        return issues.stream().allMatch(issue -> issue.type()
                        == GeneratedContentCritic.IssueType.CHAPTER_SCOPE_DUPLICATION
                || issue.type() == GeneratedContentCritic.IssueType.OVERREACH)
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
        return List.of("Whole-lesson review found: " + issues.stream()
                .map(issue -> issue.type()
                        + (issue.claimAspect() == GeneratedContentCritic.ClaimAspect.GENERAL
                                ? ""
                                : " aspect=" + issue.claimAspect())
                        + " claim=" + issue.claimPosition()
                        + " evidence=" + issue.evidenceIds() + " - " + issue.summary())
                .collect(Collectors.joining("; "))
                + ". Return a complete replacement section. Correct every flagged claim from the supplied evidence, "
                + "preserve independently supported content and the chapter objective, keep citations attached to the "
                + "claims they support, and do not add facts that were not requested by the review.");
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
                        this::diagnosticLabel,
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

    private String diagnosticLabel(GeneratedContentCritic.Issue issue) {
        return issue.claimAspect() == GeneratedContentCritic.ClaimAspect.GENERAL
                ? issue.type().name()
                : issue.type().name() + "_" + issue.claimAspect().name();
    }

    enum CorrectionKind {
        CHAPTER_SCOPE,
        FACTUAL
    }
}
