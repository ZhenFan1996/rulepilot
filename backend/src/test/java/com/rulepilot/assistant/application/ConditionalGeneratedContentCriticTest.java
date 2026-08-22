package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
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
import org.junit.jupiter.api.Test;

class ConditionalGeneratedContentCriticTest {

    private final UUID chunkId = UUID.randomUUID();

    @Test
    void skipsEveryRiskClassOutsideExplicitEvaluationMode() {
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(false, request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        });

        for (ReviewRisk risk : ReviewRisk.values()) {
            var review = critic.review(request(), risk);
            assertThat(review.performed()).as("runtime review for %s", risk).isFalse();
            assertThat(review.accepted()).as("runtime result for %s", risk).isTrue();
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void disabledRuntimeReviewCannotRejectAnAnswerForCriticSpecificRequestShape() {
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(false, request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        });
        ReviewRequest noCriticClaims = new ReviewRequest(
                UUID.randomUUID(), ContentType.ANSWER, List.of(), List.of(new Evidence(chunkId, "Rule.")));

        var review = critic.review(noCriticClaims, ReviewRisk.HIGH_IMPACT);

        assertThat(review.performed()).isFalse();
        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void performsTheBoundedTeachingPublicationReviewOutsideEvaluationMode() {
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(false, request -> {
            calls.incrementAndGet();
            return new CritiqueDraft(List.of());
        });

        var review = critic.review(opaqueLessonRequest(), ReviewRisk.HIGH_IMPACT);

        assertThat(review.performed()).isTrue();
        assertThat(review.accepted()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void explicitEvaluationReviewsEveryRiskClassExactlyOnceWhenNoIssueIsFound() {
        for (ReviewRisk risk : ReviewRisk.values()) {
            AtomicInteger calls = new AtomicInteger();
            var critic = critic(true, request -> {
                calls.incrementAndGet();
                return new CritiqueDraft(List.of());
            });

            assertThat(critic.review(request(), risk).performed()).as("evaluation review for %s", risk).isTrue();
            assertThat(calls).as("evaluation calls for %s", risk).hasValue(1);
        }
    }

    @Test
    void carriesTheContentOwnerThroughDiscoveryAndIndependentConfirmation() {
        List<String> observedOwners = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ContentCriticModel ownerScoped = new ContentCriticModel() {
            @Override
            public CritiqueDraft critique(ReviewRequest request) {
                throw new AssertionError("thread-local startup critic must not be invoked");
            }

            @Override
            public CritiqueDraft critique(ReviewRequest request, String ownerUsername) {
                observedOwners.add(ownerUsername);
                if (calls.getAndIncrement() == 0) {
                    return new CritiqueDraft(List.of(new Issue(
                            IssueType.MISSING_EXCEPTION,
                            1,
                            List.of(chunkId),
                            "Confirm the cited exception independently.")));
                }
                return new CritiqueDraft(List.of());
            }
        };
        var critic = critic(true, ownerScoped);

        var review = critic.review(
                request(ReviewMode.POST_PUBLICATION), ReviewRisk.HIGH_IMPACT, "alice");

        assertThat(review.accepted()).isTrue();
        assertThat(observedOwners).containsExactly("alice", "alice");
    }

    @Test
    void confirmsPostPublicationCandidatesWithOneIndependentAtomicCall() {
        AtomicInteger calls = new AtomicInteger();
        List<ReviewRequest> observed = new ArrayList<>();
        Issue issue = new Issue(
                IssueType.MISSING_EXCEPTION,
                ClaimAspect.NEGATION,
                1,
                List.of(chunkId),
                "The cited exception was omitted.");
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
                .contains("1=[MISSING_EXCEPTION/NEGATION]", "only against its own cited evidence IDs");
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
    void requiresIndependentConfirmationOfTheSameClaimAspect() {
        AtomicInteger calls = new AtomicInteger();
        Issue temporalCandidate = new Issue(
                IssueType.CONTRADICTION,
                ClaimAspect.TIMING,
                1,
                List.of(chunkId),
                "The generated claim moves the action beyond the current interval.");
        Issue differentOwnerVerdict = new Issue(
                IssueType.CONTRADICTION,
                ClaimAspect.SUBJECT,
                1,
                List.of(chunkId),
                "The generated claim assigns the action to a different keeper.");
        var critic = critic(true, request -> calls.getAndIncrement() == 0
                ? new CritiqueDraft(List.of(temporalCandidate))
                : new CritiqueDraft(List.of(differentOwnerVerdict)));

        var review = critic.review(opaqueLessonRequest(), ReviewRisk.HIGH_IMPACT);

        assertThat(calls).hasValue(2);
        assertThat(review.accepted()).isTrue();
    }

    @Test
    void rejectsAConfirmedIssueBoundOnlyToASiblingClaimsEvidence() {
        UUID siblingEvidenceId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var critic = critic(true, request -> calls.getAndIncrement() == 0
                ? new CritiqueDraft(List.of(new Issue(
                        IssueType.CONTRADICTION,
                        ClaimAspect.SUBJECT,
                        1,
                        List.of(chunkId),
                        "The generated claim changes the acting keeper.")))
                : new CritiqueDraft(List.of(new Issue(
                        IssueType.CONTRADICTION,
                        ClaimAspect.SUBJECT,
                        1,
                        List.of(siblingEvidenceId),
                        "The generated claim changes the acting keeper."))));
        ReviewRequest request = new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Teach two opaque procedures.", "Preserve each cited relation."),
                List.of(
                        new Claim(1, "The vek keeper seals the luma during this interval.", List.of(chunkId)),
                        new Claim(2, "The toro keeper opens the nari after the interval.", List.of(siblingEvidenceId))),
                List.of(
                        new Evidence(chunkId, "During this interval, the vek keeper seals the luma."),
                        new Evidence(siblingEvidenceId, "After the interval, the toro keeper opens the nari.")));

        assertThatThrownBy(() -> critic.review(request, ReviewRisk.HIGH_IMPACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own cited evidence");
        assertThat(calls).hasValue(2);
    }

    @Test
    void acceptsDirectlySupportedOpaqueClaimsWhenAtomicReviewClearsEveryAspectCandidate() {
        List<ClaimAspect> aspects = List.of(
                ClaimAspect.QUANTITY,
                ClaimAspect.MULTIPLIER,
                ClaimAspect.TIMING,
                ClaimAspect.SUBJECT,
                ClaimAspect.NEGATION);
        for (ClaimAspect aspect : aspects) {
            AtomicInteger calls = new AtomicInteger();
            var critic = critic(true, request -> calls.getAndIncrement() == 0
                    ? new CritiqueDraft(List.of(new Issue(
                            IssueType.CONTRADICTION,
                            aspect,
                            1,
                            List.of(chunkId),
                            "Candidate semantic mismatch requires independent adjudication.")))
                    : new CritiqueDraft(List.of()));

            var review = critic.review(opaqueLessonRequest(), ReviewRisk.HIGH_IMPACT);

            assertThat(review.accepted()).as("direct support for %s", aspect).isTrue();
            assertThat(calls).as("bounded calls for %s", aspect).hasValue(2);
        }
    }

    @Test
    void independentlyConfirmsEveryProtectedRelationOnStructurallyDifferentOpaqueClaims() {
        List<FidelityScenario> scenarios = List.of(
                new FidelityScenario(
                        ClaimAspect.QUANTITY,
                        IssueType.MISSING_CRITICAL_RULE,
                        "The vek keeper seals luma.",
                        "The vek keeper seals exactly four luma."),
                new FidelityScenario(
                        ClaimAspect.MULTIPLIER,
                        IssueType.MISSING_CRITICAL_RULE,
                        "Perform the toro transfer once.",
                        "Repeat the toro transfer for each nari."),
                new FidelityScenario(
                        ClaimAspect.TIMING,
                        IssueType.CONTRADICTION,
                        "After the pale interval, the vek keeper turns the luma.",
                        "During the pale interval, the vek keeper turns the luma."),
                new FidelityScenario(
                        ClaimAspect.SUBJECT,
                        IssueType.CONTRADICTION,
                        "The toro keeper opens the nari.",
                        "The vek keeper opens the nari."),
                new FidelityScenario(
                        ClaimAspect.NEGATION,
                        IssueType.CONTRADICTION,
                        "The nari bearer may open the luma.",
                        "The nari bearer must not open the luma."));

        for (FidelityScenario scenario : scenarios) {
            AtomicInteger calls = new AtomicInteger();
            Issue confirmed = new Issue(
                    scenario.type(),
                    scenario.aspect(),
                    1,
                    List.of(chunkId),
                    "The complete generated relation differs from its cited source relation.");
            var critic = critic(true, request -> {
                calls.incrementAndGet();
                return new CritiqueDraft(List.of(confirmed));
            });
            ReviewRequest request = new ReviewRequest(
                    UUID.randomUUID(),
                    ContentType.LESSON,
                    ReviewMode.POST_PUBLICATION,
                    new TaskContext("Teach one opaque relation.", "Preserve the complete cited relation."),
                    List.of(new Claim(1, scenario.claim(), List.of(chunkId))),
                    List.of(new Evidence(chunkId, scenario.evidence())));

            var review = critic.review(request, ReviewRisk.HIGH_IMPACT);

            assertThat(review.issues()).as("confirmed %s", scenario.aspect()).containsExactly(confirmed);
            assertThat(calls).as("bounded calls for %s", scenario.aspect()).hasValue(2);
        }
    }

    @Test
    void batchesOnlyCandidateClaimsAndTheirEvidenceAndRestrictsIssuesToCandidateTypes() {
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
        assertThat(confirmation.claims()).extracting(Claim::position).containsExactly(1, 2);
        assertThat(confirmation.evidence()).extracting(Evidence::chunkId)
                .containsExactly(chunkId, secondChunk);
        assertThat(confirmation.taskContext().requiredCoverage())
                .contains("only candidate claims", "own cited evidence IDs");
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
    void rejectsCriticOutputThatEscapesTheRequestedProtocolScope() {
        UUID outside = UUID.randomUUID();
        String longSummary = "x".repeat(300);
        var critic = critic(true, request -> new CritiqueDraft(List.of(new Issue(
                IssueType.OVERREACH,
                99,
                List.of(outside, chunkId),
                longSummary))));

        assertThatThrownBy(() -> critic.review(request(ReviewMode.OBJECTIVE_COVERAGE), ReviewRisk.STANDARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("critic issue");
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

    private ReviewRequest opaqueLessonRequest() {
        return new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Teach the luma procedure.", "Preserve the complete cited relation."),
                List.of(new Claim(
                        1,
                        "During this interval, the vek keeper must not seal more than four luma for each nari.",
                        List.of(chunkId))),
                List.of(new Evidence(
                        chunkId,
                        "During this interval, the vek keeper must not seal more than four luma for each nari.")));
    }

    private record FidelityScenario(ClaimAspect aspect, IssueType type, String claim, String evidence) {}
}
