package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Evidence;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void keepsTranslationDisputeWhenNoDocumentGlossaryResolvesIt() {
        Issue translationDispute = new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(chunkId),
                "‘信用点’ is an incorrect translation; the source says credits and should be credits.");
        var critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of(translationDispute)),
                new ImmediateAuditedAgentInvocations(),
                true);

        assertThat(critic.review(request(), ReviewRisk.STANDARD).issues()).containsExactly(translationDispute);
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

    @Test
    void acceptsCandidateIssueWhenIndependentAtomicReviewDoesNotConfirmIt() {
        AtomicInteger calls = new AtomicInteger();
        Issue candidate = new Issue(
                IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "The reward is unsupported.");
        var critic = new ConditionalGeneratedContentCritic(request -> calls.getAndIncrement() == 0
                        ? new CritiqueDraft(List.of(candidate))
                        : new CritiqueDraft(List.of()),
                new ImmediateAuditedAgentInvocations(), true);

        var review = critic.review(request(), ReviewRisk.STANDARD);

        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void returnsObjectiveCoverageIssueWithoutAtomicClaimConfirmation() {
        AtomicInteger calls = new AtomicInteger();
        Issue missing = new Issue(
                IssueType.MISSING_CRITICAL_RULE, 1, List.of(chunkId), "Moon landing is missing.");
        var critic = new ConditionalGeneratedContentCritic(request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of(missing));
        }, new ImmediateAuditedAgentInvocations(), true);
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.OBJECTIVE_COVERAGE,
                new com.rulepilot.assistant.GeneratedContentCritic.TaskContext(
                        "Teach landing on a planet or moon.", "core_loop"),
                List.of(new Claim(1, "Land on a planet.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Technology allows landing on a moon.")));

        var review = critic.review(request, ReviewRisk.LOW_CONFIDENCE);

        assertThat(review.issues()).containsExactly(missing);
        assertThat(calls).hasValue(1);
    }

    @Test
    void confirmsEachClaimOnlyAgainstItsCombinedCitations() {
        UUID secondCitation = UUID.randomUUID();
        UUID unrelatedEvidence = UUID.randomUUID();
        List<ReviewRequest> observed = new ArrayList<>();
        Issue candidate = new Issue(
                IssueType.MISSING_EXCEPTION, 2, List.of(unrelatedEvidence), "The discount condition is missing.");
        var critic = new ConditionalGeneratedContentCritic(request -> {
            observed.add(request);
            return request.reviewMode() == ReviewMode.DISCOVERY
                    ? new CritiqueDraft(List.of(candidate))
                    : new CritiqueDraft(List.of(new Issue(
                            IssueType.MISSING_EXCEPTION,
                            2,
                            List.of(chunkId, secondCitation),
                            "The discount condition is missing.")));
        }, new ImmediateAuditedAgentInvocations(), true);
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                List.of(
                        new Claim(1, "First claim.", List.of(unrelatedEvidence)),
                        new Claim(2, "Discounted action.", List.of(chunkId, secondCitation))),
                List.of(
                        new Evidence(chunkId, "The action costs three energy."),
                        new Evidence(secondCitation, "It costs two when any orbiter is present."),
                        new Evidence(unrelatedEvidence, "Unrelated first claim evidence.")));

        var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

        assertThat(review.issues()).hasSize(1);
        assertThat(observed).hasSize(2);
        ReviewRequest confirmation = observed.get(1);
        assertThat(confirmation.reviewMode()).isEqualTo(ReviewMode.ATOMIC_CONFIRMATION);
        assertThat(confirmation.claims()).extracting(Claim::position).containsExactly(2);
        assertThat(confirmation.evidence()).extracting(Evidence::chunkId)
                .containsExactly(chunkId, secondCitation);
    }

    @Test
    void confirmsIndependentClaimPositionsConcurrently() throws InterruptedException {
        UUID secondChunk = UUID.randomUUID();
        CountDownLatch confirmationsStarted = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        var critic = new ConditionalGeneratedContentCritic(request -> {
            if (request.reviewMode() == ReviewMode.DISCOVERY) {
                return new CritiqueDraft(List.of(
                        new Issue(IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First defect."),
                        new Issue(IssueType.CONTRADICTION, 2, List.of(secondChunk), "Second defect.")));
            }
            calls.incrementAndGet();
            confirmationsStarted.countDown();
            try {
                if (!confirmationsStarted.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("claim confirmations did not overlap");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new CritiqueDraft(List.of());
        }, new ImmediateAuditedAgentInvocations(), true, 2);
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(
                        new Claim(1, "First.", List.of(chunkId)),
                        new Claim(2, "Second.", List.of(secondChunk))),
                List.of(new Evidence(chunkId, "First."), new Evidence(secondChunk, "Second.")));

        var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

        assertThat(confirmationsStarted.await(0, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(review.accepted()).isTrue();
    }

    @Test
    void confirmsMultipleLessonClaimsIndependentlyWithEvidenceScopedCalls() {
        UUID secondChunk = UUID.randomUUID();
        List<ReviewRequest> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        var critic = new ConditionalGeneratedContentCritic(request -> {
            observed.add(request);
            if (request.reviewMode() == ReviewMode.DISCOVERY) {
                return new CritiqueDraft(List.of(
                        new Issue(IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First candidate."),
                        new Issue(IssueType.CONTRADICTION, 2, List.of(secondChunk), "Second candidate.")));
            }
            return request.claims().getFirst().position() == 1
                    ? new CritiqueDraft(List.of(
                            new Issue(IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First confirmed.")))
                    : new CritiqueDraft(List.of(
                            new Issue(IssueType.OVERREACH, 2, List.of(secondChunk), "Unlisted type.")));
        }, new ImmediateAuditedAgentInvocations(), true, 4);
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                List.of(
                        new Claim(1, "First.", List.of(chunkId)),
                        new Claim(2, "Second.", List.of(secondChunk))),
                List.of(new Evidence(chunkId, "First."), new Evidence(secondChunk, "Second.")));

        var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

        assertThat(observed).hasSize(3);
        assertThat(observed.stream().filter(item -> item.reviewMode() == ReviewMode.ATOMIC_CONFIRMATION))
                .allSatisfy(confirmation -> {
                    assertThat(confirmation.claims()).hasSize(1);
                    assertThat(confirmation.evidence()).hasSize(1);
                });
        assertThat(review.issues())
                .containsExactly(new Issue(
                        IssueType.UNSUPPORTED_CLAIM, 1, List.of(chunkId), "First confirmed."));
    }

    private ReviewRequest request() {
        return new ReviewRequest(
                UUID.randomUUID(),
                ContentType.ANSWER,
                List.of(new Claim(1, "Each coin scores one point.", List.of(chunkId))),
                List.of(new Evidence(chunkId, "Each coin scores one point.")));
    }
}
