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

    private static final int MAX_UNCITED_EVIDENCE_PER_SECTION = 2;

    private LessonReviewPlanner() {}

    static LessonReviewBatch plan(List<GroundedTeachingAgent.DraftCandidate> candidates, UUID assistantRunId) {
        List<Claim> claims = new ArrayList<>();
        Map<Integer, GroundedTeachingAgent.DraftCandidate> claimOwners = new LinkedHashMap<>();
        Map<UUID, RuleEvidence> evidence = new LinkedHashMap<>();
        for (GroundedTeachingAgent.DraftCandidate candidate : candidates) {
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
        String objective = candidates.stream()
                .map(candidate -> "第" + candidate.planned().position() + "章「"
                        + candidate.planned().title() + "」：" + candidate.planned().objective())
                .collect(Collectors.joining("\n"));
        String requiredCoverage = candidates.stream()
                .map(candidate -> "第" + candidate.planned().position() + "章："
                        + requiredCoverage(candidate.planned()))
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
                new TaskContext(objective, requiredCoverage),
                claims,
                evidence.values().stream()
                        .map(source -> new GeneratedContentCritic.Evidence(
                                source.chunkId(), reviewExcerpts.getOrDefault(source.chunkId(), source.excerpt())))
                        .toList());
        return new LessonReviewBatch(request, Map.copyOf(claimOwners));
    }

    private static List<RuleEvidence> reviewEvidence(GroundedTeachingAgent.DraftCandidate candidate) {
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
                .limit(MAX_UNCITED_EVIDENCE_PER_SECTION)
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static String requiredCoverage(TeachingPlan.PlannedSection planned) {
        return "Coverage tags: " + String.join(", ", planned.coverageTags())
                + "; required retrieval intents: " + String.join("; ", planned.retrievalQueries());
    }

    record LessonReviewBatch(
            ReviewRequest request,
            Map<Integer, GroundedTeachingAgent.DraftCandidate> claimOwners) {}
}
