package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingReviewCorrectionPolicyTest {

    private final TeachingReviewCorrectionPolicy policy = new TeachingReviewCorrectionPolicy();

    @Test
    void classifies_scope_only_reviews_separately_from_factual_reviews_and_keeps_their_budgets_separate() {
        assertThat(policy.correctionKind(List.of(issue(IssueType.CHAPTER_SCOPE_DUPLICATION, 1, "重复讲解"))))
                .isEqualTo(TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE);
        assertThat(policy.correctionKind(List.of(
                        issue(IssueType.CHAPTER_SCOPE_DUPLICATION, 1, "重复讲解"),
                        issue(IssueType.CONTRADICTION, 2, "事实冲突"))))
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
    void gives_scope_corrections_a_precise_boundary_without_downgrading_the_player_journey() {
        List<String> feedback = policy.correctionFeedback(
                List.of(issue(IssueType.CHAPTER_SCOPE_DUPLICATION, 3, "本章重复了下一章的结算细节")));

        assertThat(feedback).singleElement().satisfies(value -> {
            assertThat(value).contains("CHAPTER_SCOPE_DUPLICATION evidence=");
            assertThat(value).contains("retain the player-visible stage, order, or decision");
            assertThat(value).contains("do not invent a different example");
        });
    }

    @Test
    void keeps_factual_feedback_grounded_without_scope_specific_instructions() {
        List<String> feedback = policy.correctionFeedback(List.of(
                issue(IssueType.CONTRADICTION, 4, "行动顺序与原文冲突"),
                issue(IssueType.MISSING_EXCEPTION, 6, "遗漏例外")));

        assertThat(feedback).singleElement().satisfies(value -> {
            assertThat(value).contains("CONTRADICTION evidence=");
            assertThat(value).contains("MISSING_EXCEPTION evidence=");
            assertThat(value).doesNotContain("retain the player-visible stage");
            assertThat(value).contains("Correct only from the supplied evidence");
        });
    }

    @Test
    void appends_a_schema_repair_without_losing_the_requested_review_correction() {
        List<String> repair = policy.structuralRepairFeedback(
                List.of("Whole-lesson objective coverage review found: CONTRADICTION"), "STEP_METADATA_INVALID");

        assertThat(repair).containsExactly(
                "Whole-lesson objective coverage review found: CONTRADICTION",
                "The prior correction was structurally invalid: STEP_METADATA_INVALID. Return a complete replacement "
                        + "section with a short heading, teaching kind, text, and valid citations for every step. "
                        + "Preserve the requested correction; do not restore the removed claim.");
    }

    @Test
    void produces_a_stable_grouped_critic_diagnostic() {
        String diagnostic = policy.criticDiagnostic(List.of(
                issue(IssueType.CONTRADICTION, 5, "later"),
                issue(IssueType.CONTRADICTION, 1, "first"),
                issue(IssueType.MISSING_EXCEPTION, 3, "exception"),
                issue(IssueType.CONTRADICTION, 1, "duplicate")));

        assertThat(diagnostic).isEqualTo("CRITIC_CONTRADICTION@1,5+MISSING_EXCEPTION@3");
    }

    private Issue issue(IssueType type, int claimPosition, String summary) {
        return new Issue(type, claimPosition, List.of(UUID.randomUUID()), summary);
    }
}
