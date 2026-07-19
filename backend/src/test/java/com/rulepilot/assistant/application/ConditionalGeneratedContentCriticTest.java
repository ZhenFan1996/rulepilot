package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Evidence;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConditionalGeneratedContentCriticTest {

    private final UUID chunkId = UUID.randomUUID();

    @Test
    void skipsStandardContentOutsideEvaluationMode() {
        AtomicInteger calls = new AtomicInteger();
        var critic = new ConditionalGeneratedContentCritic(request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        }, new ImmediateAuditedAgentInvocations(), false);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.performed()).isFalse();
        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void reviewsLowConfidenceContent() {
        AtomicInteger calls = new AtomicInteger();
        var critic = new ConditionalGeneratedContentCritic(request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        }, new ImmediateAuditedAgentInvocations(), false);

        var review = critic.review(request(), ReviewRisk.LOW_CONFIDENCE);

        assertThat(review.performed()).isTrue();
        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void reviewsHighImpactContentOutsideEvaluationMode() {
        AtomicInteger calls = new AtomicInteger();
        var critic = new ConditionalGeneratedContentCritic(request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        }, new ImmediateAuditedAgentInvocations(), false);

        var review = critic.review(request(), ReviewRisk.HIGH_IMPACT);

        assertThat(review.performed()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void evaluationModeReturnsValidatedBlockingIssues() {
        Issue issue = new Issue(
                IssueType.MISSING_EXCEPTION, 1, List.of(chunkId), "The cited exception was omitted.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(issue)), new ImmediateAuditedAgentInvocations(), true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.performed()).isTrue();
        assertThat(review.accepted()).isFalse();
        assertThat(review.issues()).containsExactly(issue);
    }

    @Test
    void keepsMalformedModelIssueBlockingWhileNormalizingItsScope() {
        Issue invalid = new Issue(
                IssueType.OVERREACH, 2, List.of(UUID.randomUUID()), "Out of scope.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(invalid)), new ImmediateAuditedAgentInvocations(), true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.accepted()).isFalse();
        assertThat(review.issues().getFirst())
                .extracting(Issue::claimPosition, Issue::evidenceIds)
                .containsExactly(1, List.of());
    }

    @Test
    void discardsSelfContradictingIssueThatExplicitlyConcludesThereIsNoIssue() {
        Issue falsePositive = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The claim exactly matches the cited evidence. No issue.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(falsePositive)),
                new ImmediateAuditedAgentInvocations(),
                true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.performed()).isTrue();
        assertThat(review.accepted()).isTrue();
        assertThat(review.issues()).isEmpty();
    }

    @Test
    void discardsAuditNoteThatOnlyConcludesTheClaimIsSupported() {
        Issue falsePositive = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The first-orbiter reward is directly supported and the wording is correct.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(falsePositive)),
                new ImmediateAuditedAgentInvocations(),
                true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.accepted()).isTrue();
        assertThat(review.issues()).isEmpty();
    }

    @Test
    void keepsIssueThatAcknowledgesOneSupportedPartBeforeNamingARealDefect() {
        Issue defect = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The cost is supported, but the claimed reward is unsupported.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(defect)),
                new ImmediateAuditedAgentInvocations(),
                true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.accepted()).isFalse();
        assertThat(review.issues()).containsExactly(defect);
    }

    @Test
    void discardsBareSupportedConclusionWithoutAConcreteDefect() {
        Issue falsePositive = new Issue(
                IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "The statement matches E1. Supported.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(falsePositive)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).accepted()).isTrue();
    }

    @Test
    void discardsComplaintAgainstRequiredChineseGlossaryTranslation() {
        Issue falsePositive = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "‘信用点’ is an incorrect translation; the source says credits and should be credits.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(falsePositive)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).accepted()).isTrue();
    }

    @Test
    void discardsSelfNegatingSemanticAuditNoteButKeepsContrastedDefect() {
        Issue noDefect = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "Evidence says gain publicity, which matches the claim. No contradiction.");
        Issue actualDefect = new Issue(
                IssueType.MISSING_EXCEPTION,
                1,
                List.of(chunkId),
                "The base cost is supported, but the required discount condition is omitted.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(noDefect, actualDefect)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).issues()).containsExactly(actualDefect);
    }

    @Test
    void trustsTerminalNoDefectConclusionAfterVerboseContrast() {
        Issue selfNegating = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The claim uses 默认, but the source says by default; 语义一致，无缺陷。");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(selfNegating)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).accepted()).isTrue();
    }

    @Test
    void discardsIssueThatEndsByAcknowledgingTheEvidenceSupportsTheClause() {
        Issue selfNegating = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "E1未提及自由行动，但E4明确说明这是自由行动，支持该部分。");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(selfNegating)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).accepted()).isTrue();
    }

    @Test
    void neverMistakesUnsupportedForTheWordSupported() {
        Issue defect = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The claimed reward is unsupported by E1.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(defect)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).issues()).containsExactly(defect);
    }

    private ReviewRequest request() {
        return new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(new Claim(1, "Each coin scores one point.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Each coin scores one point.")));
    }
}
