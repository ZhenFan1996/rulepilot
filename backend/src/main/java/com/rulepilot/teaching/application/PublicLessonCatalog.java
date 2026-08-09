package com.rulepilot.teaching.application;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anonymous discovery projection for usable lessons with publisher-owned source material. */
@Service
@Profile("!test")
public class PublicLessonCatalog {

    private static final int CANDIDATE_LIMIT = 200;
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final PublicRulebookReferenceLookup rulebooks;
    private final PublicGameCoverLookup covers;
    private final PublicGameIdentityLookup gameIdentities;
    private final ConcurrentMap<Integer, CachedEntries> cachedEntries = new ConcurrentHashMap<>();

    public PublicLessonCatalog(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            PublicRulebookReferenceLookup rulebooks,
            PublicGameCoverLookup covers,
            PublicGameIdentityLookup gameIdentities) {
        this.plans = plans;
        this.lessons = lessons;
        this.rulebooks = rulebooks;
        this.covers = covers;
        this.gameIdentities = gameIdentities;
    }

    @Transactional(readOnly = true)
    public List<Entry> latest(int limit) {
        if (limit < 1 || limit > 60) throw new IllegalArgumentException("public lesson limit is invalid");
        CachedEntries cached = cachedEntries.get(limit);
        if (cached != null && cached.isFresh()) return cached.entries();
        synchronized (cachedEntries) {
            cached = cachedEntries.get(limit);
            if (cached != null && cached.isFresh()) return cached.entries();
            List<Entry> entries = loadLatest(limit);
            cachedEntries.put(limit, new CachedEntries(entries, System.nanoTime() + CACHE_TTL_NANOS));
            return entries;
        }
    }

    private List<Entry> loadLatest(int limit) {
        List<TeachingPlanRepository.PlanReference> planReferences = plans.findRecentReferences(CANDIDATE_LIMIT);
        if (planReferences.isEmpty()) return List.of();
        Map<UUID, IllustratedLessonRepository.LessonSummary> lessonSummaries = lessons
                .findLatestSummariesByPlans(planReferences.stream().map(TeachingPlanRepository.PlanReference::teachingPlanId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        IllustratedLessonRepository.LessonSummary::teachingPlanId, summary -> summary));
        Map<UUID, PublicRulebookReferenceLookup.Reference> references = rulebooks.findReferences(
                planReferences.stream().map(TeachingPlanRepository.PlanReference::documentVersionId).toList());
        Map<UUID, PublicGameCoverLookup.Cover> gameCovers = covers.findByEditions(references.values().stream()
                .map(PublicRulebookReferenceLookup.Reference::gameEditionId)
                .filter(java.util.Objects::nonNull)
                .toList());
        Map<String, PublicGameIdentityLookup.Identity> identities = publicGameIdentities(planReferences);
        Set<UUID> documentVersions = new LinkedHashSet<>();
        return planReferences.stream()
                .map(plan -> candidate(plan, lessonSummaries.get(plan.teachingPlanId()), references.get(plan.documentVersionId())))
                .flatMap(java.util.Optional::stream)
                .filter(candidate -> candidate.summary().status() != LessonStatus.INCOMPLETE)
                .filter(candidate -> candidate.summary().publiclyReadable())
                .filter(candidate -> candidate.reference().officialSourceUrl() != null)
                .filter(candidate -> documentVersions.add(candidate.plan().documentVersionId()))
                .limit(limit)
                .map(candidate -> Entry.from(
                        candidate,
                        candidate.reference().gameEditionId() == null
                                ? null
                                : gameCovers.get(candidate.reference().gameEditionId()),
                        identities.get(candidate.plan().gameTitle())))
                .toList();
    }

    private Map<String, PublicGameIdentityLookup.Identity> publicGameIdentities(
            List<TeachingPlanRepository.PlanReference> planReferences) {
        try {
            return gameIdentities.findByTitles(
                    planReferences.stream().map(TeachingPlanRepository.PlanReference::gameTitle).toList());
        } catch (RuntimeException unavailableOptionalMetadata) {
            return Map.of();
        }
    }

    private java.util.Optional<Candidate> candidate(
            TeachingPlanRepository.PlanReference plan,
            IllustratedLessonRepository.LessonSummary summary,
            PublicRulebookReferenceLookup.Reference reference) {
        return summary == null || reference == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Candidate(plan, summary, reference));
    }

    private record Candidate(
            TeachingPlanRepository.PlanReference plan,
            IllustratedLessonRepository.LessonSummary summary,
            PublicRulebookReferenceLookup.Reference reference) {}

    /** Public catalog cards change only when a lesson is published or removed; a tiny TTL protects readers from repeat joins. */
    private record CachedEntries(List<Entry> entries, long expiresAtNanos) {
        CachedEntries {
            entries = List.copyOf(entries);
        }

        boolean isFresh() {
            return System.nanoTime() < expiresAtNanos;
        }
    }

    public record Entry(
            UUID teachingPlanId,
            String rulebookTitle,
            String officialSourceUrl,
            PublicLessonReader.PublicCover gameCover,
            PublicGameIdentityLookup.Identity publicGame,
            int sectionCount,
            int stepCount) {
        static Entry from(
                Candidate candidate,
                PublicGameCoverLookup.Cover catalogCover,
                PublicGameIdentityLookup.Identity matchedIdentity) {
            PublicRulebookReferenceLookup.Reference reference = candidate.reference();
            PublicGameIdentityLookup.Identity publicGame = catalogCover == null
                    ? matchedIdentity
                    : new PublicGameIdentityLookup.Identity(
                            catalogCover.bggId(), catalogCover.gameName(), catalogCover.bggUrl());
            return new Entry(
                    candidate.plan().teachingPlanId(),
                    reference.title(),
                    reference.officialSourceUrl(),
                    cover(reference, catalogCover),
                    publicGame,
                    candidate.summary().sectionCount(),
                    candidate.summary().stepCount());
        }

        private static PublicLessonReader.PublicCover cover(
                PublicRulebookReferenceLookup.Reference reference, PublicGameCoverLookup.Cover catalogCover) {
            if (catalogCover != null) {
                return new PublicLessonReader.PublicCover(
                        catalogCover.gameName(),
                        catalogCover.thumbnailUrl(),
                        catalogCover.bggUrl(),
                        "BoardGameGeek");
            }
            return reference.officialCoverUrl() == null
                    ? null
                    : new PublicLessonReader.PublicCover(
                            reference.title(),
                            reference.officialCoverUrl(),
                            reference.officialSourceUrl(),
                            "出版方官方封面");
        }
    }
}
