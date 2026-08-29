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
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies one measured whole-lesson semantic publication boundary.
 *
 * <p>An unavailable critic cannot erase a deterministic, cited chapter. A confirmed defect is different: the
 * drafting Agent sees the typed defect and the same bounded evidence once, and must return one complete replacement
 * chapter. The replacement is accepted only after deterministic validation and one independent semantic review.
 * This boundary never patches fields or combines prose from two model responses.</p>
 */
final class TeachingPublishedLessonReviewer {

    private static final Logger log = LoggerFactory.getLogger(TeachingPublishedLessonReviewer.class);

    static int maximumModelCalls(int sectionCount) {
        if (sectionCount < 1) throw new IllegalArgumentException("review section count must be positive");
        // Ordinary accepted chapters use only the initial review (discovery plus an optional atomic confirmation).
        // Every confirmed chapter may conditionally add one complete revision (plus schema repair); all replacements
        // then share one acceptance review. This is a budget ceiling, not a required number of calls.
        return Math.addExact(4, Math.multiplyExact(sectionCount, 2));
    }

    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingLessonAssemblyPolicy lessonAssembly = new TeachingLessonAssemblyPolicy();

    TeachingPublishedLessonReviewer(
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            TeachingSectionDraftComposer sectionDraftComposer) {
        this.critic = critic;
        this.invocations = invocations;
        this.sectionDraftComposer = sectionDraftComposer;
    }

    ReviewResult review(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher) {
        if (candidates.isEmpty()) return ReviewResult.none();
        LessonReviewPlanner.LessonReviewBatch batch = LessonReviewPlanner.plan(plan, candidates, assistantRunId);
        GeneratedContentCritic.Review review;
        try {
            review = critic.review(batch.request(), ReviewRisk.HIGH_IMPACT, plan.createdBy());
        } catch (AgentExecutionStoppedException stopped) {
            retainCitedDrafts(candidates, assistantRunId, "POST_PUBLICATION_REVIEW_STOPPED_RETAINING_CITED_DRAFT");
            if (stopped.reason() == AgentExecutionStoppedException.StopReason.CANCELLED) throw stopped;
            log.warn(
                    "Whole-lesson review stopped after cited chapters were published; retaining them ({})",
                    stopped.reason());
            return ReviewResult.none();
        } catch (RuntimeException reviewFailure) {
            log.warn("Whole-lesson review failed; retaining cited drafts: {}", reviewFailure.getMessage());
            retainCitedDrafts(candidates, assistantRunId, "POST_PUBLICATION_REVIEW_FAILED_RETAINING_CITED_DRAFT");
            return ReviewResult.none();
        }
        if (!review.performed()) {
            retainCitedDrafts(candidates, assistantRunId, "POST_PUBLICATION_REVIEW_SKIPPED_RETAINING_CITED_DRAFT");
            return ReviewResult.none();
        }

        Map<Integer, List<GeneratedContentCritic.Issue>> issuesBySection = issuesBySection(review, batch);
        List<RevisionRequest> revisionRequests = new ArrayList<>();
        for (TeachingSectionDraftCandidate candidate : candidates) {
            List<GeneratedContentCritic.Issue> issues = issuesBySection.getOrDefault(
                    candidate.sectionIndex(), List.of());
            if (issues.isEmpty()) {
                sections.set(candidate.sectionIndex(), supported(sections.get(candidate.sectionIndex())));
                record(
                        assistantRunId,
                        candidate,
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_REVIEW_ACCEPTED");
                continue;
            }

            withhold(
                    candidate,
                    sections,
                    assistantRunId,
                    "POST_PUBLICATION_REVIEW_CONFIRMED_DEFECT_WITHHELD_PENDING_REVISION");
            revisionRequests.add(new RevisionRequest(candidate, issues));
        }

        // Persist the safe boundary before any replacement call. Cancellation, budget exhaustion, or a process stop
        // can therefore never leave a reviewer-confirmed defective chapter readable; unrelated chapters stay intact.
        progressPublisher.run();
        if (revisionRequests.isEmpty()) return ReviewResult.none();

        List<TeachingSectionDraftCandidate> correctedCandidates = new ArrayList<>();
        for (RevisionRequest revision : revisionRequests) {
            TeachingSectionDraftCandidate corrected = completeReplacement(
                    plan, revision.candidate(), revision.issues(), assistantRunId);
            if (corrected == null) {
                record(
                        assistantRunId,
                        revision.candidate(),
                        ActivityOutcome.REJECTED,
                        "POST_PUBLICATION_REVISION_FAILED_REMAINS_WITHHELD");
            } else {
                correctedCandidates.add(corrected);
            }
        }

        List<Integer> acceptedReplacementIndexes = acceptReplacements(
                plan, correctedCandidates, sections, assistantRunId);
        progressPublisher.run();
        return new ReviewResult(acceptedReplacementIndexes);
    }

    private record RevisionRequest(
            TeachingSectionDraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues) {
        private RevisionRequest {
            issues = List.copyOf(issues);
        }
    }

    private TeachingSectionDraftCandidate completeReplacement(
            TeachingPlan plan,
            TeachingSectionDraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues,
            UUID assistantRunId) {
        try {
            List<String> feedback = List.of(correctionFeedback(issues));
            SectionDraft replacement = sectionDraftComposer.reviseModelDraft(
                    assistantRunId,
                    candidate.planned(),
                    candidate.modelRequest(),
                    candidate.draft(),
                    feedback,
                    "replaceReviewedTeachingSection",
                    "repairReviewedTeachingSectionContract",
                    "Confirmed teaching defect returned as one complete replacement");
            replacement = sectionDraftComposer.normalizeDraft(
                    replacement, candidate.modelRequest(), candidate.evidence());
            LessonSection validated = sectionDraftComposer.validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    replacement,
                    EvidenceStatus.CITED_DRAFT);
            sectionDraftComposer.recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    1,
                    ActivityOutcome.SUCCEEDED,
                    "POST_PUBLICATION_COMPLETE_REPLACEMENT_VALIDATED");
            return new TeachingSectionDraftCandidate(
                    candidate.sectionIndex(),
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    replacement,
                    validated);
        } catch (AgentExecutionStoppedException stopped) {
            if (stopped.reason() == AgentExecutionStoppedException.StopReason.CANCELLED) throw stopped;
            log.warn(
                    "Whole-lesson replacement stopped for topic {}; keeping the confirmed defect withheld ({})",
                    candidate.planned().topicKey(),
                    stopped.reason());
            sectionDraftComposer.recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    1,
                    ActivityOutcome.REJECTED,
                    "POST_PUBLICATION_COMPLETE_REPLACEMENT_STOPPED_REMAINS_WITHHELD");
            return null;
        } catch (RuntimeException invalidReplacement) {
            log.warn(
                    "Whole-lesson review replacement failed for topic {}: {}",
                    candidate.planned().topicKey(),
                    invalidReplacement.getMessage());
            sectionDraftComposer.recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    1,
                    ActivityOutcome.REJECTED,
                    "POST_PUBLICATION_COMPLETE_REPLACEMENT_REJECTED");
            return null;
        }
    }

    private List<Integer> acceptReplacements(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> correctedCandidates,
            List<LessonSection> sections,
            UUID assistantRunId) {
        if (correctedCandidates.isEmpty()) return List.of();
        LessonReviewPlanner.LessonReviewBatch acceptanceBatch =
                LessonReviewPlanner.plan(plan, correctedCandidates, assistantRunId);
        GeneratedContentCritic.Review acceptance;
        try {
            acceptance = critic.review(
                    acceptanceBatch.request(), ReviewRisk.HIGH_IMPACT, plan.createdBy());
        } catch (AgentExecutionStoppedException stopped) {
            withholdAll(
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    "POST_PUBLICATION_ACCEPTANCE_STOPPED_WITHHELD_UNVERIFIED_REPLACEMENT");
            if (stopped.reason() == AgentExecutionStoppedException.StopReason.CANCELLED) throw stopped;
            log.warn(
                    "Whole-lesson replacement acceptance stopped; keeping unverified replacements withheld ({})",
                    stopped.reason());
            return List.of();
        } catch (RuntimeException reviewFailure) {
            log.warn("Whole-lesson replacement acceptance failed: {}", reviewFailure.getMessage());
            withholdAll(
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    "POST_PUBLICATION_ACCEPTANCE_FAILED_WITHHELD_UNVERIFIED_REPLACEMENT");
            return List.of();
        }
        if (!acceptance.performed()) {
            withholdAll(
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    "POST_PUBLICATION_ACCEPTANCE_SKIPPED_WITHHELD_UNVERIFIED_REPLACEMENT");
            return List.of();
        }

        Map<Integer, List<GeneratedContentCritic.Issue>> remainingIssues =
                issuesBySection(acceptance, acceptanceBatch);
        List<Integer> accepted = new ArrayList<>();
        for (TeachingSectionDraftCandidate candidate : correctedCandidates) {
            if (remainingIssues.getOrDefault(candidate.sectionIndex(), List.of()).isEmpty()) {
                sections.set(candidate.sectionIndex(), supported(candidate.section()));
                accepted.add(candidate.sectionIndex());
                record(
                        assistantRunId,
                        candidate,
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_COMPLETE_REPLACEMENT_ACCEPTED");
            } else {
                withhold(
                        candidate,
                        sections,
                        assistantRunId,
                        "POST_PUBLICATION_REPLACEMENT_STILL_DEFECTIVE_WITHHELD");
            }
        }
        return List.copyOf(accepted);
    }

    private Map<Integer, List<GeneratedContentCritic.Issue>> issuesBySection(
            GeneratedContentCritic.Review review,
            LessonReviewPlanner.LessonReviewBatch batch) {
        return review.issues().stream()
                .collect(Collectors.groupingBy(issue -> batch.claimOwners()
                        .get(issue.claimPosition()).sectionIndex()));
    }

    private String correctionFeedback(List<GeneratedContentCritic.Issue> issues) {
        String defects = issues.stream()
                .map(issue -> "type=" + issue.type()
                        + ", aspect=" + issue.claimAspect()
                        + ", claim=" + issue.claimPosition()
                        + ", evidenceIds=" + issue.evidenceIds()
                        + ", reason=" + issue.summary())
                .collect(Collectors.joining("; "));
        return "Independent review confirmed these defects: " + defects
                + ". Return one COMPLETE replacement chapter using only the supplied evidence. Preserve the chapter "
                + "objective and independently supported meaning, correct every flagged relation, and attach valid "
                + "citations to every rule claim. Do not return a field patch or refer to hidden prior context.";
    }

    private void retainCitedDrafts(
            List<TeachingSectionDraftCandidate> candidates,
            UUID assistantRunId,
            String category) {
        candidates.forEach(candidate -> record(
                assistantRunId,
                candidate,
                ActivityOutcome.REJECTED,
                category));
    }

    private void withholdAll(
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            String category) {
        candidates.forEach(candidate -> withhold(candidate, sections, assistantRunId, category));
    }

    private void withhold(
            TeachingSectionDraftCandidate candidate,
            List<LessonSection> sections,
            UUID assistantRunId,
            String category) {
        sections.set(candidate.sectionIndex(), lessonAssembly.insufficient(candidate.planned()));
        record(assistantRunId, candidate, ActivityOutcome.REJECTED, category);
    }

    private LessonSection supported(LessonSection section) {
        return new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                EvidenceStatus.SUPPORTED,
                section.visualKind(),
                section.visualCaption(),
                section.visualSourcePages(),
                section.visualSourceChunkIds(),
                section.steps());
    }

    private void record(
            UUID assistantRunId,
            TeachingSectionDraftCandidate candidate,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                assistantRunId,
                ActivityType.VALIDATION,
                "publishTeachingSectionReview|" + candidate.planned().position(),
                outcome,
                category);
    }

    record ReviewResult(List<Integer> acceptedReplacementIndexes) {
        ReviewResult {
            acceptedReplacementIndexes = acceptedReplacementIndexes == null
                    ? List.of()
                    : acceptedReplacementIndexes.stream().distinct().sorted().toList();
        }

        static ReviewResult none() {
            return new ReviewResult(List.of());
        }
    }
}
