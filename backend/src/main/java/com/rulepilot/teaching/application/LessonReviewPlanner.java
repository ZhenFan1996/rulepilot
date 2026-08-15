package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure assembly of the bounded, post-publication whole-lesson Critic request.
 *
 * <p>The planner only organizes already selected draft claims and immutable rule evidence. It cannot invoke the
 * Critic, revise a draft, or publish a section.</p>
 */
final class LessonReviewPlanner {

    // The drafting model may see up to ten bounded source chunks. Two uncited chunks are not enough to retain a
    // page-local aggregation clause plus its worked example after the draft cites only the isolated unit-value rule.
    // Six keeps an ordinary whole-lesson review bounded. A quantitative or explicit source-coverage section receives
    // all ten because its correctness depends on comparing the generated draft with every bounded rule group.
    private static final int MAX_UNCITED_EVIDENCE_PER_SECTION = 6;
    private static final int MAX_COMPLETE_EVIDENCE_PER_SECTION = 10;

    private LessonReviewPlanner() {}

    static LessonReviewBatch plan(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> candidates,
            UUID assistantRunId) {
        List<Claim> claims = new ArrayList<>();
        Map<Integer, TeachingSectionDraftCandidate> claimOwners = new LinkedHashMap<>();
        Map<UUID, RuleEvidence> evidence = new LinkedHashMap<>();
        for (TeachingSectionDraftCandidate candidate : candidates) {
            reviewEvidence(candidate).forEach(source -> evidence.putIfAbsent(source.chunkId(), source));
            List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(
                    candidate.draft(),
                    candidate.evidence().stream().collect(Collectors.toUnmodifiableMap(
                            RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first)));
            for (Claim claim : LessonDraftValidator.reviewClaims(candidate.draft(), visualCitationIds)) {
                int position = claims.size() + 1;
                claims.add(new Claim(
                        position,
                        "第" + candidate.planned().position() + "章「" + candidate.planned().title() + "」："
                                + claim.text(),
                        claim.citationIds()));
                claimOwners.put(position, candidate);
            }
        }
        String objective = plan.sections().stream()
                .map(section -> "第" + section.position() + "章「"
                        + section.title() + "」：" + section.objective())
                .collect(Collectors.joining("\n"));
        String requiredCoverage = plan.sections().stream()
                .map(section -> "第" + section.position() + "章：" + requiredCoverage(section))
                .collect(Collectors.joining("\n"));
        Map<UUID, String> reviewExcerpts = candidates.stream()
                .flatMap(candidate -> candidate.modelRequest().evidence().stream())
                .collect(Collectors.toMap(
                        EvidenceInput::chunkId,
                        EvidenceInput::excerpt,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        ReviewRequest request = new ReviewRequest(
                assistantRunId,
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext(objective, requiredCoverage, plan.sections().size()),
                claims,
                evidence.values().stream()
                        .map(source -> new GeneratedContentCritic.Evidence(
                                source.chunkId(), reviewExcerpts.getOrDefault(source.chunkId(), source.excerpt())))
                        .toList());
        return new LessonReviewBatch(request, Map.copyOf(claimOwners));
    }

    private static List<RuleEvidence> reviewEvidence(TeachingSectionDraftCandidate candidate) {
        Set<UUID> cited = Stream.concat(
                        candidate.draft().visualCitationIds().stream(),
                        candidate.draft().steps().stream().flatMap(step -> step.citationIds().stream()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, RuleEvidence> byId = candidate.evidence().stream()
                .collect(Collectors.toMap(
                        RuleEvidence::chunkId,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        List<RuleEvidence> selected = new ArrayList<>();
        cited.stream().map(byId::get).filter(java.util.Objects::nonNull).forEach(selected::add);
        candidate.evidence().stream()
                .filter(source -> !cited.contains(source.chunkId()))
                .limit(requiresCompleteReviewEvidence(candidate)
                        ? MAX_COMPLETE_EVIDENCE_PER_SECTION
                        : MAX_UNCITED_EVIDENCE_PER_SECTION)
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static boolean requiresCompleteReviewEvidence(TeachingSectionDraftCandidate candidate) {
        return TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(
                        candidate.planned(), candidate.draft())
                || candidate.planned().coverageTags().stream().anyMatch("source_coverage"::equalsIgnoreCase);
    }

    private static String requiredCoverage(TeachingPlan.PlannedSection planned) {
        return "Coverage tags: " + String.join(", ", planned.coverageTags())
                + "; required retrieval intents: " + String.join("; ", planned.retrievalQueries());
    }

    record LessonReviewBatch(
            ReviewRequest request,
            Map<Integer, TeachingSectionDraftCandidate> claimOwners) {}
}
