package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingReviewCorrectionPolicyTest {

    private final TeachingReviewCorrectionPolicy policy = new TeachingReviewCorrectionPolicy();

    @Test
    void separatesScopeOnlyAndFactualCorrectionBudgets() {
        assertThat(policy.correctionKind(List.of(issue(IssueType.CHAPTER_SCOPE_DUPLICATION, 1, "重复"))))
                .isEqualTo(TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE);
        assertThat(policy.correctionKind(List.of(issue(IssueType.OVERREACH, 1, "越界"))))
                .isEqualTo(TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE);
        assertThat(policy.correctionKind(List.of(
                        issue(IssueType.OVERREACH, 1, "越界"),
                        issue(IssueType.CONTRADICTION, 2, "冲突"))))
                .isEqualTo(TeachingReviewCorrectionPolicy.CorrectionKind.FACTUAL);

        assertThat(policy.correctionBudgetExhausted(
                        TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE, 4, 1))
                .isFalse();
        assertThat(policy.correctionBudgetExhausted(
                        TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE, 0, 2))
                .isTrue();
        assertThat(policy.correctionBudgetExhausted(
                        TeachingReviewCorrectionPolicy.CorrectionKind.FACTUAL, 3, 2))
                .isFalse();
        assertThat(policy.correctionBudgetExhausted(
                        TeachingReviewCorrectionPolicy.CorrectionKind.FACTUAL, 4, 0))
                .isTrue();
    }

    @Test
    void passesCriticIssuesToTheRepairModelWithoutHardCodedGameSemantics() {
        List<String> feedback = policy.correctionFeedback(List.of(
                issue(
                        IssueType.UNSUPPORTED_CLAIM,
                        ClaimAspect.TIMING,
                        2,
                        "The source does not state the introductory timing."),
                issue(IssueType.CHAPTER_SCOPE_DUPLICATION, 4, "This detail belongs to a later chapter.")));

        assertThat(feedback).singleElement().satisfies(value -> {
            assertThat(value).contains(
                    "UNSUPPORTED_CLAIM aspect=TIMING claim=2",
                    "CHAPTER_SCOPE_DUPLICATION claim=4",
                    "Return a complete replacement section",
                    "supplied evidence",
                    "preserve independently supported content",
                    "keep citations attached");
            assertThat(value).doesNotContain(
                    "species",
                    "animal",
                    "setup inventory",
                    "may/can",
                    "player-visible stage");
        });
    }

    @Test
    void appendsSchemaRepairWithoutLosingTheCriticRequest() {
        List<String> repair = policy.structuralRepairFeedback(
                List.of("Whole-lesson review found: CONTRADICTION"), "STEP_METADATA_INVALID");

        assertThat(repair).containsExactly(
                "Whole-lesson review found: CONTRADICTION",
                "The prior correction was structurally invalid: STEP_METADATA_INVALID. Return a complete replacement "
                        + "section with a short heading, teaching kind, text, and valid citations for every step. "
                        + "Preserve the requested correction; do not restore the removed claim.");
    }

    @Test
    void producesStableGroupedCriticDiagnostic() {
        String diagnostic = policy.criticDiagnostic(List.of(
                issue(IssueType.CONTRADICTION, 5, "later"),
                issue(IssueType.CONTRADICTION, 1, "first"),
                issue(IssueType.MISSING_EXCEPTION, 3, "exception"),
                issue(IssueType.CONTRADICTION, 1, "duplicate")));

        assertThat(diagnostic).isEqualTo("CRITIC_CONTRADICTION@1,5+MISSING_EXCEPTION@3");
    }

    @Test
    void keepsTheConfirmedClaimAspectInPlayerSafeDiagnostics() {
        String diagnostic = policy.criticDiagnostic(List.of(
                issue(IssueType.CONTRADICTION, ClaimAspect.SUBJECT, 2, "owner"),
                issue(IssueType.CONTRADICTION, ClaimAspect.TIMING, 4, "interval"),
                issue(IssueType.CONTRADICTION, ClaimAspect.SUBJECT, 6, "recipient")));

        assertThat(diagnostic).isEqualTo("CRITIC_CONTRADICTION_SUBJECT@2,6+CONTRADICTION_TIMING@4");
    }

    private Issue issue(IssueType type, int claimPosition, String summary) {
        return new Issue(type, claimPosition, List.of(UUID.randomUUID()), summary);
    }

    private Issue issue(IssueType type, ClaimAspect aspect, int claimPosition, String summary) {
        return new Issue(type, aspect, claimPosition, List.of(UUID.randomUUID()), summary);
    }
}
