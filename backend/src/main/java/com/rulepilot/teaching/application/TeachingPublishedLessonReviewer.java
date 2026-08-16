package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies one bounded whole-lesson review and withholds any defect that the Critic actually confirmed. */
final class TeachingPublishedLessonReviewer {

    private static final Logger log = LoggerFactory.getLogger(TeachingPublishedLessonReviewer.class);
    private static final int MAX_POST_PUBLICATION_REVIEW_PASSES = 4;

    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingReviewCorrectionPolicy reviewCorrectionPolicy;
    private final TeachingLessonAssemblyPolicy lessonAssembly = new TeachingLessonAssemblyPolicy();

    TeachingPublishedLessonReviewer(
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            TeachingSectionDraftComposer sectionDraftComposer,
            TeachingReviewCorrectionPolicy reviewCorrectionPolicy) {
        this.critic = critic;
        this.invocations = invocations;
        this.sectionDraftComposer = sectionDraftComposer;
        this.reviewCorrectionPolicy = reviewCorrectionPolicy;
    }

    void review(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher) {
        reviewBatch(
                plan,
                candidates,
                sections,
                assistantRunId,
                progressPublisher,
                MAX_POST_PUBLICATION_REVIEW_PASSES,
                new CorrectionBudget(),
                false);
    }

    private boolean reviewBatch(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher,
            int remainingPasses,
            CorrectionBudget correctionBudget,
            boolean acceptanceRequired) {
        LessonReviewPlanner.LessonReviewBatch batch = LessonReviewPlanner.plan(plan, candidates, assistantRunId);
        GeneratedContentCritic.Review review;
        try {
            review = critic.review(batch.request(), ReviewRisk.HIGH_IMPACT, plan.createdBy());
        } catch (AgentExecutionStoppedException stopped) {
            if (acceptanceRequired) {
                withholdCandidates(
                        candidates,
                        sections,
                        assistantRunId,
                        progressPublisher,
                        "POST_PUBLICATION_REVIEW_UNAVAILABLE_WITHHELD_UNVERIFIED_CORRECTION");
            } else {
                resolveInitialReviewFailure(
                        candidates,
                        sections,
                        assistantRunId,
                        progressPublisher,
                        "POST_PUBLICATION_REVIEW_DEFERRED_RETAINING_CITED_DRAFT",
                        "POST_PUBLICATION_REVIEW_UNAVAILABLE_WITHHELD_QUANTITATIVE_DRAFT");
            }
            return true;
        } catch (RuntimeException reviewFailure) {
            if (acceptanceRequired) {
                log.warn("Whole-lesson acceptance review withheld unverified correction: {}", reviewFailure.getMessage());
                withholdCandidates(
                        candidates,
                        sections,
                        assistantRunId,
                        progressPublisher,
                        "POST_PUBLICATION_REVIEW_FAILED_WITHHELD_UNVERIFIED_CORRECTION");
            } else {
                log.warn(
                        "Whole-lesson factual review retained only non-quantitative cited drafts: {}",
                        reviewFailure.getMessage());
                resolveInitialReviewFailure(
                        candidates,
                        sections,
                        assistantRunId,
                        progressPublisher,
                        "POST_PUBLICATION_REVIEW_RETAINED_CITED_DRAFT",
                        "POST_PUBLICATION_REVIEW_FAILED_WITHHELD_QUANTITATIVE_DRAFT");
            }
            return true;
        }

        Map<Integer, List<GeneratedContentCritic.Issue>> issuesBySection = review.issues().stream()
                .collect(Collectors.groupingBy(issue -> batch.claimOwners()
                        .get(issue.claimPosition()).sectionIndex()));
        List<TeachingSectionDraftCandidate> correctedCandidates = new ArrayList<>();
        for (TeachingSectionDraftCandidate candidate : candidates) {
            List<GeneratedContentCritic.Issue> issues = issuesBySection.getOrDefault(
                    candidate.sectionIndex(), List.of());
            sectionDraftComposer.recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    0,
                    issues.isEmpty() ? ActivityOutcome.SUCCEEDED : ActivityOutcome.REJECTED,
                    issues.isEmpty() ? "POST_PUBLICATION_REVIEW_ACCEPTED" : reviewCorrectionPolicy.criticDiagnostic(issues));
            TeachingReviewCorrectionPolicy.CorrectionKind correctionKind = reviewCorrectionPolicy.correctionKind(issues);
            boolean correctionBudgetExhausted = !issues.isEmpty()
                    && !correctionBudget.tryStart(reviewCorrectionPolicy, correctionKind);
            if (!issues.isEmpty() && correctionBudgetExhausted) {
                log.info(
                        "Whole-lesson review withholds {} defect for topic {} after its correction budget",
                        correctionKind == TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE
                                ? "chapter-scope"
                                : "factual",
                        candidate.planned().topicKey());
                withholdCandidate(
                        candidate,
                        sections,
                        assistantRunId,
                        "POST_PUBLICATION_REVIEW_BUDGET_EXHAUSTED_WITHHELD_CONFIRMED_DEFECT");
                progressPublisher.run();
                continue;
            }
            try {
                TeachingSectionDraftCandidate reviewed = issues.isEmpty()
                        ? supportedCandidate(plan, candidate)
                        : correctedDraft(
                                plan,
                                candidate,
                                issues,
                                firstClaimPosition(batch, candidate.sectionIndex()),
                                correctionKind,
                                correctionBudget,
                                assistantRunId);
                sections.set(candidate.sectionIndex(), reviewed.section());
                if (!issues.isEmpty()) correctedCandidates.add(reviewed);
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        reviewed.section().evidenceStatus() == EvidenceStatus.SUPPORTED
                                ? "POST_PUBLICATION_REVIEW_ACCEPTED"
                                : "POST_PUBLICATION_REVIEW_PENDING");
                progressPublisher.run();
            } catch (AgentExecutionStoppedException stopped) {
                List<TeachingSectionDraftCandidate> defectiveCandidates = candidates.stream()
                        .filter(pending -> !issuesBySection.getOrDefault(pending.sectionIndex(), List.of()).isEmpty())
                        .toList();
                withholdCandidates(
                        defectiveCandidates,
                        sections,
                        assistantRunId,
                        progressPublisher,
                        "POST_PUBLICATION_CORRECTION_STOPPED_WITHHELD_CONFIRMED_DEFECT");
                return true;
            } catch (RuntimeException correctionFailure) {
                log.warn(
                        "Whole-lesson review withheld confirmed defect for topic {}: {}",
                        candidate.planned().topicKey(),
                        correctionFailure.getMessage());
                withholdCandidate(
                        candidate,
                        sections,
                        assistantRunId,
                        "POST_PUBLICATION_CORRECTION_FAILED_WITHHELD_CONFIRMED_DEFECT");
                progressPublisher.run();
            }
        }
        if (!correctedCandidates.isEmpty() && remainingPasses > 1) {
            return reviewBatch(
                    plan,
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    progressPublisher,
                    remainingPasses - 1,
                    correctionBudget,
                    true);
        }
        if (!correctedCandidates.isEmpty()) {
            withholdCandidates(
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    progressPublisher,
                    "POST_PUBLICATION_REVIEW_LIMIT_WITHHELD_UNVERIFIED_CORRECTION");
        }
        return true;
    }

    private void withholdCandidates(
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher,
            String category) {
        candidates.forEach(candidate -> withholdCandidate(candidate, sections, assistantRunId, category));
        if (!candidates.isEmpty()) progressPublisher.run();
    }

    private void resolveInitialReviewFailure(
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher,
            String retainedCategory,
            String withheldCategory) {
        List<TeachingSectionDraftCandidate> quantitative = candidates.stream()
                .filter(candidate -> TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(
                        candidate.planned(), candidate.draft()))
                .toList();
        Set<Integer> quantitativeIndexes = quantitative.stream()
                .map(TeachingSectionDraftCandidate::sectionIndex)
                .collect(Collectors.toSet());
        candidates.stream()
                .filter(candidate -> !quantitativeIndexes.contains(candidate.sectionIndex()))
                .forEach(candidate -> recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        retainedCategory));
        withholdCandidates(quantitative, sections, assistantRunId, progressPublisher, withheldCategory);
    }

    private void withholdCandidate(
            TeachingSectionDraftCandidate candidate,
            List<LessonSection> sections,
            UUID assistantRunId,
            String category) {
        sections.set(candidate.sectionIndex(), lessonAssembly.insufficient(candidate.planned()));
        recordPublication(
                assistantRunId,
                candidate.planned(),
                ActivityOutcome.REJECTED,
                category);
    }

    private TeachingSectionDraftCandidate supportedCandidate(TeachingPlan plan, TeachingSectionDraftCandidate candidate) {
        return new TeachingSectionDraftCandidate(
                candidate.sectionIndex(),
                candidate.planned(),
                candidate.evidence(),
                candidate.modelRequest(),
                candidate.draft(),
                sectionDraftComposer.validatedSection(
                        plan,
                        candidate.planned(),
                        candidate.evidence(),
                        candidate.modelRequest(),
                        candidate.draft(),
                        EvidenceStatus.SUPPORTED));
    }

    private TeachingSectionDraftCandidate correctedDraft(
            TeachingPlan plan,
            TeachingSectionDraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues,
            int firstClaimPosition,
            TeachingReviewCorrectionPolicy.CorrectionKind correctionKind,
            CorrectionBudget correctionBudget,
            UUID assistantRunId) {
        List<String> feedback = reviewCorrectionPolicy.correctionFeedback(issues);
        SectionDraft corrected = sectionDraftComposer.reviseModelDraft(
                assistantRunId,
                candidate.planned(),
                candidate.modelRequest(),
                candidate.draft(),
                feedback,
                "correctTeachingSection",
                "repairTeachingSectionCorrectionContract",
                "Published teaching section corrected from whole-lesson review");
        corrected = sectionDraftComposer.normalizeDraft(corrected, candidate.modelRequest(), candidate.evidence());
        EvidenceStatus correctionStatus = EvidenceStatus.CITED_DRAFT;
        LessonSection correctedSection;
        try {
            validateFlaggedClaimsChanged(candidate.draft(), corrected, issues, firstClaimPosition);
            correctedSection = sectionDraftComposer.validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        } catch (IllegalArgumentException invalidCorrection) {
            if (!correctionBudget.tryStart(reviewCorrectionPolicy, correctionKind)) {
                throw new IllegalArgumentException(
                        "Post-publication correction repair budget exhausted after an invalid correction.",
                        invalidCorrection);
            }
            SectionDraft invalidDraft = corrected;
            List<String> structuralRepair = reviewCorrectionPolicy.structuralRepairFeedback(
                    feedback, TeachingDraftRejectionCategory.from(invalidCorrection));
            corrected = sectionDraftComposer.reviseModelDraft(
                    assistantRunId,
                    candidate.planned(),
                    candidate.modelRequest(),
                    invalidDraft,
                    structuralRepair,
                    "repairCorrectedTeachingSection",
                    "repairCorrectedTeachingSectionContract",
                    "Published teaching correction repaired to the section contract");
            corrected = sectionDraftComposer.normalizeDraft(corrected, candidate.modelRequest(), candidate.evidence());
            validateFlaggedClaimsChanged(candidate.draft(), corrected, issues, firstClaimPosition);
            correctionStatus = EvidenceStatus.CITED_DRAFT;
            correctedSection = sectionDraftComposer.validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        }
        sectionDraftComposer.recordValidation(
                assistantRunId,
                candidate.planned(),
                1,
                ActivityOutcome.SUCCEEDED,
                "POST_PUBLICATION_CORRECTION_APPLIED");
        return new TeachingSectionDraftCandidate(
                candidate.sectionIndex(),
                candidate.planned(),
                candidate.evidence(),
                candidate.modelRequest(),
                corrected,
                correctedSection);
    }

    private int firstClaimPosition(LessonReviewPlanner.LessonReviewBatch batch, int sectionIndex) {
        return batch.claimOwners().entrySet().stream()
                .filter(entry -> entry.getValue().sectionIndex() == sectionIndex)
                .mapToInt(Map.Entry::getKey)
                .min()
                .orElseThrow(() -> new IllegalArgumentException("review claim owner is missing"));
    }

    private void validateFlaggedClaimsChanged(
            SectionDraft previous,
            SectionDraft corrected,
            List<GeneratedContentCritic.Issue> issues,
            int firstClaimPosition) {
        List<GeneratedContentCritic.Claim> previousClaims = LessonDraftValidator.reviewClaims(
                previous, previous.visualCitationIds());
        List<GeneratedContentCritic.Claim> correctedClaims = LessonDraftValidator.reviewClaims(
                corrected, corrected.visualCitationIds());
        boolean leftFlaggedClaimUnchanged = issues.stream()
                .filter(issue -> issue.type() != GeneratedContentCritic.IssueType.MISSING_CRITICAL_RULE)
                .mapToInt(issue -> issue.claimPosition() - firstClaimPosition)
                .filter(localIndex -> localIndex >= 0 && localIndex < previousClaims.size())
                .anyMatch(localIndex -> localIndex < correctedClaims.size()
                        && normalizedClaim(previousClaims.get(localIndex).text())
                                .equals(normalizedClaim(correctedClaims.get(localIndex).text()))
                        && Set.copyOf(previousClaims.get(localIndex).citationIds())
                                .equals(Set.copyOf(correctedClaims.get(localIndex).citationIds())));
        if (leftFlaggedClaimUnchanged) {
            throw new IllegalArgumentException(
                    "A review correction left a Critic-flagged player-facing claim unchanged.");
        }
    }

    private String normalizedClaim(String claim) {
        return claim == null ? "" : claim.replaceAll("\\s+", " ").strip();
    }

    private void recordPublication(
            UUID runId,
            TeachingPlan.PlannedSection section,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "publishTeachingSection|" + section.position(),
                outcome,
                "Teaching section " + (outcome == ActivityOutcome.SUCCEEDED ? "published: " : "withheld: ") + category);
    }

    /** Counts semantic correction attempts; every provider request also consumes the shared audited model budget. */
    private static final class CorrectionBudget {

        private int factualCorrectionsStarted;
        private int scopeCorrectionsStarted;

        private boolean tryStart(
                TeachingReviewCorrectionPolicy policy,
                TeachingReviewCorrectionPolicy.CorrectionKind correctionKind) {
            if (policy.correctionBudgetExhausted(
                    correctionKind, factualCorrectionsStarted, scopeCorrectionsStarted)) {
                return false;
            }
            if (correctionKind == TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE) {
                scopeCorrectionsStarted++;
            } else {
                factualCorrectionsStarted++;
            }
            return true;
        }
    }
}
