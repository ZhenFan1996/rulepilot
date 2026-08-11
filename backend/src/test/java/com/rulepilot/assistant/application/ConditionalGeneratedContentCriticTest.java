package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Evidence;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConditionalGeneratedContentCriticTest {

    private final UUID chunkId = UUID.randomUUID();

    @Test
    void skipsStandardContentOutsideEvaluationMode() {
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(false, request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        });

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.performed()).isFalse();
        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void reviewsLowConfidenceHighImpactAndEvaluationTrafficExactlyOnce() {
        for (var scenario : List.of(
                new Scenario(false, ReviewRisk.LOW_CONFIDENCE),
                new Scenario(false, ReviewRisk.HIGH_IMPACT),
                new Scenario(true, ReviewRisk.STANDARD))) {
            AtomicInteger calls = new AtomicInteger();
            var critic = critic(scenario.evaluationMode(), request -> {
                calls.incrementAndGet();
                return new CritiqueDraft(List.of());
            });

            assertThat(critic.review(request(), scenario.risk()).performed()).isTrue();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void confirmsPostPublicationCandidatesWithOneIndependentAtomicCall() {
        AtomicInteger calls = new AtomicInteger();
        List<ReviewRequest> observed = new ArrayList<>();
        Issue issue = new Issue(
                IssueType.MISSING_EXCEPTION, 1, List.of(chunkId), "The cited exception was omitted.");
        var critic = critic(true, request -> {
            calls.incrementAndGet();
            observed.add(request);
            return new CritiqueDraft(List.of(issue));
        });
        ReviewRequest request = request(ReviewMode.POST_PUBLICATION);

        var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

        assertThat(calls).hasValue(2);
        assertThat(observed.getFirst()).isSameAs(request);
        assertThat(observed.getLast().reviewMode()).isEqualTo(ReviewMode.ATOMIC_CONFIRMATION);
        assertThat(observed.getLast().taskContext().requiredCoverage())
                .contains("1=[MISSING_EXCEPTION]", "only against its own cited evidence IDs");
        assertThat(review.performed()).isTrue();
        assertThat(review.issues()).containsExactly(issue);
    }

    @Test
    void dropsACandidateWhenIndependentSemanticConfirmationFindsNoDefect() {
        AtomicInteger calls = new AtomicInteger();
        Issue selfContradictingCandidate = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "The claim exactly matches the evidence. No defect.");
        var critic = critic(true, request -> calls.getAndIncrement() == 0
                ? new CritiqueDraft(List.of(selfContradictingCandidate))
                : new CritiqueDraft(List.of()));

        var review = critic.review(request(ReviewMode.POST_PUBLICATION), ReviewRisk.HIGH_IMPACT);

        assertThat(calls).hasValue(2);
        assertThat(review.accepted()).isTrue();
    }

    @Test
    void batchesCandidateClaimsWithSiblingContextAndRestrictsIssuesToCandidateTypes() {
        UUID secondChunk = UUID.randomUUID();
        UUID unrelatedChunk = UUID.randomUUID();
        List<ReviewRequest> observed = new ArrayList<>();
        var critic = critic(true, request -> {
            observed.add(request);
            if (request.reviewMode() == ReviewMode.DISCOVERY) {
                return new CritiqueDraft(List.of(
                        new Issue(IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First candidate."),
                        new Issue(IssueType.MISSING_EXCEPTION, 2, List.of(secondChunk), "Second candidate.")));
            }
            return new CritiqueDraft(List.of(
                    new Issue(IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId, secondChunk), "First confirmed."),
                    new Issue(IssueType.OVERREACH, 2, List.of(secondChunk), "Unlisted type.")));
        });
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.DISCOVERY,
                new TaskContext("Teach two rules.", "Check both rules."),
                List.of(
                        new Claim(1, "First claim.", List.of(chunkId)),
                        new Claim(2, "Second claim.", List.of(secondChunk)),
                        new Claim(3, "Unflagged claim.", List.of(unrelatedChunk))),
                List.of(
                        new Evidence(chunkId, "First evidence."),
                        new Evidence(secondChunk, "Second evidence."),
                        new Evidence(unrelatedChunk, "Unrelated evidence.")));

        var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

        assertThat(observed).hasSize(2);
        ReviewRequest confirmation = observed.getLast();
        assertThat(confirmation.reviewMode()).isEqualTo(ReviewMode.ATOMIC_CONFIRMATION);
        assertThat(confirmation.claims()).extracting(Claim::position).containsExactly(1, 2, 3);
        assertThat(confirmation.evidence()).extracting(Evidence::chunkId)
                .containsExactly(chunkId, secondChunk, unrelatedChunk);
        assertThat(confirmation.taskContext().requiredCoverage())
                .contains("Positions not listed are context only", "sibling claims");
        assertThat(review.issues()).containsExactly(new Issue(
                IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First confirmed."));
    }

    @Test
    void doesNotRecursivelyConfirmObjectiveCoverageOrAtomicReviews() {
        for (ReviewMode mode : List.of(ReviewMode.OBJECTIVE_COVERAGE, ReviewMode.ATOMIC_CONFIRMATION)) {
            AtomicInteger calls = new AtomicInteger();
            Issue issue = new Issue(IssueType.MISSING_CRITICAL_RULE, 1, List.of(chunkId), "Missing rule.");
            var critic = critic(true, request -> {
                calls.incrementAndGet();
                return new CritiqueDraft(List.of(issue));
            });

            assertThat(critic.review(request(mode), ReviewRisk.HIGH_IMPACT).issues()).containsExactly(issue);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void normalizesOnlyProtocolScopeAndBudgetsWithoutRejudgingIssueMeaning() {
        UUID outside = UUID.randomUUID();
        String longSummary = "x".repeat(300);
        List<Issue> issues = IntStream.rangeClosed(1, 14)
                .mapToObj(index -> new Issue(
                        IssueType.OVERREACH,
                        99,
                        List.of(outside, chunkId, chunkId),
                        index == 1 ? longSummary : "Issue " + index))
                .toList();
        var critic = critic(true, request -> new CritiqueDraft(issues));

        var review = critic.review(request(ReviewMode.OBJECTIVE_COVERAGE), ReviewRisk.STANDARD);

        assertThat(review.issues()).hasSize(12);
        assertThat(review.issues()).allSatisfy(issue -> {
            assertThat(issue.claimPosition()).isEqualTo(1);
            assertThat(issue.evidenceIds()).containsExactly(chunkId);
        });
        assertThat(review.issues().getFirst().summary()).hasSize(240);
    }

    @Test
    void rejectsInvalidRequestsBeforeCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(true, request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        });

        ReviewRequest noClaims = new ReviewRequest(
                UUID.randomUUID(), ContentType.ANSWER, List.of(), List.of(new Evidence(chunkId, "Rule.")));
        assertThatThrownBy(() -> critic.review(noClaims, ReviewRisk.HIGH_IMPACT))
                .isInstanceOf(IllegalArgumentException.class);

        ReviewRequest duplicatePositions = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(
                        new Claim(1, "First.", List.of(chunkId)),
                        new Claim(1, "Second.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Rule.")));
        assertThatThrownBy(() -> critic.review(duplicatePositions, ReviewRisk.HIGH_IMPACT))
                .hasMessageContaining("unique");

        ReviewRequest outsideCitation = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(new Claim(1, "Claim.", List.of(UUID.randomUUID()))),
                List.of(new Evidence(chunkId, "Rule.")));
        assertThatThrownBy(() -> critic.review(outsideCitation, ReviewRisk.HIGH_IMPACT))
                .hasMessageContaining("supplied evidence");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsNullOrStructurallyInvalidModelOutput() {
        assertThatThrownBy(() -> critic(true, request -> null)
                        .review(request(), ReviewRisk.STANDARD))
                .hasMessageContaining("critic output");
        assertThatThrownBy(() -> critic(true, request -> new CritiqueDraft(List.of(
                                new Issue(null, 1, List.of(chunkId), "bad"))))
                        .review(request(), ReviewRisk.STANDARD))
                .hasMessageContaining("critic issue");
    }

    private ConditionalGeneratedContentCritic critic(boolean evaluationMode, ContentCriticModel model) {
        return new ConditionalGeneratedContentCritic(
                model, new ImmediateAuditedAgentInvocations(), evaluationMode);
    }

    private ReviewRequest request() {
        return request(ReviewMode.DISCOVERY);
    }

    private ReviewRequest request(ReviewMode mode) {
        return new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                mode,
                new TaskContext("Answer the rule question.", "Judge every claim."),
                List.of(new Claim(1, "Each coin scores one point.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Each coin scores one point.")));
    }

    private record Scenario(boolean evaluationMode, ReviewRisk risk) {}
}
