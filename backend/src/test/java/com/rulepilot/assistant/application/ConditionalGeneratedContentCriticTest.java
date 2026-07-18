package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsIssueThatEscapesClaimOrEvidenceScope() {
        Issue invalid = new Issue(
                IssueType.OVERREACH, 2, List.of(UUID.randomUUID()), "Out of scope.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(invalid)), new ImmediateAuditedAgentInvocations(), true);

        assertThatThrownBy(() -> critic.review(request(), ReviewRisk.STANDARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("critic issue is invalid");
    }

    private ReviewRequest request() {
        return new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(new Claim(1, "Each coin scores one point.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Each coin scores one point.")));
    }
}
