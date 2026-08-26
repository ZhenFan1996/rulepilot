package com.rulepilot.teaching.application;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameEditionIdentityLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Availability;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Candidate;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public class PublicLessonCatalog implements PublicTeachingContinuationCatalog {

    private static final int CANDIDATE_LIMIT = 200;
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final PublicRulebookReferenceLookup rulebooks;
    private final PublicGameCoverLookup covers;
    private final PublicGameEditionIdentityLookup editionIdentities;
    private final PublicGameIdentityLookup gameIdentities;
    private final ConcurrentMap<Integer, CachedEntries> cachedEntries = new ConcurrentHashMap<>();

    public PublicLessonCatalog(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            PublicRulebookReferenceLookup rulebooks,
            PublicGameCoverLookup covers,
            PublicGameEditionIdentityLookup editionIdentities,
            PublicGameIdentityLookup gameIdentities) {
        this.plans = plans;
        this.lessons = lessons;
        this.rulebooks = rulebooks;
        this.covers = covers;
        this.editionIdentities = editionIdentities;
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

    @Override
    @Transactional(readOnly = true)
    public Availability continuationsFor(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Availability.none();
        try {
            Map<Integer, Candidate> candidatesByBggId = candidates.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Candidate::bggId,
                            candidate -> candidate,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            List<TeachingPlanRepository.PlanReference> planReferences = plans.findRecentReferences(CANDIDATE_LIMIT);
            Map<UUID, IllustratedLessonRepository.LessonSummary> lessonSummaries = lessons
                    .findLatestSummariesByPlans(planReferences.stream()
                            .map(TeachingPlanRepository.PlanReference::teachingPlanId)
                            .toList())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            IllustratedLessonRepository.LessonSummary::teachingPlanId, summary -> summary));
            Map<UUID, PublicRulebookReferenceLookup.Reference> references = rulebooks.findReferences(planReferences.stream()
                    .map(TeachingPlanRepository.PlanReference::documentVersionId)
                    .toList());
            List<TeachingPlanRepository.PlanReference> readablePlans = planReferences.stream()
                    .filter(plan -> {
                        IllustratedLessonRepository.LessonSummary summary = lessonSummaries.get(plan.teachingPlanId());
                        return summary != null
                                && summary.status() != LessonStatus.INCOMPLETE
                                && summary.publiclyReadable();
                    })
                    .toList();
            boolean metadataComplete = readablePlans.stream()
                    .allMatch(plan -> references.containsKey(plan.documentVersionId()));
            List<LessonCandidate> usableLessons = readablePlans.stream()
                    .map(plan -> candidate(
                            plan,
                            lessonSummaries.get(plan.teachingPlanId()),
                            references.get(plan.documentVersionId())))
                    .flatMap(Optional::stream)
                    .filter(candidate -> candidate.reference().officialSourceUrl() != null
                            && !candidate.reference().officialSourceUrl().isBlank())
                    .toList();
            if (usableLessons.isEmpty()) {
                return metadataComplete
                        ? exhaustedAvailability(planReferences)
                        : Availability.unavailable();
            }
            boolean hasUnknownEditionIdentity = usableLessons.stream()
                    .map(LessonCandidate::reference)
                    .map(PublicRulebookReferenceLookup.Reference::gameEditionId)
                    .anyMatch(java.util.Objects::isNull);
            List<UUID> editionIds = usableLessons.stream()
                    .map(LessonCandidate::reference)
                    .map(PublicRulebookReferenceLookup.Reference::gameEditionId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            Map<UUID, Integer> bggIdsByEdition = editionIds.isEmpty()
                    ? Map.of()
                    : editionIdentities.findBggIds(editionIds);
            if (bggIdsByEdition == null) return Availability.unavailable();
            boolean editionIdentityComplete = !hasUnknownEditionIdentity
                    && editionIds.stream().allMatch(editionId -> {
                        Integer bggId = bggIdsByEdition.get(editionId);
                        return bggId != null && bggId > 0;
                    });
            boolean continuationMetadataComplete = usableLessons.stream()
                    .allMatch(PublicLessonCatalog::hasContinuationMetadata);
            Map<Integer, Continuation> continuations = continuations(
                    usableLessons.stream()
                            .filter(PublicLessonCatalog::hasContinuationMetadata)
                            .toList(),
                    candidatesByBggId,
                    bggIdsByEdition);
            boolean exhaustive = planReferences.size() < CANDIDATE_LIMIT
                    && metadataComplete
                    && editionIdentityComplete
                    && continuationMetadataComplete;
            if (!continuations.isEmpty()) {
                return exhaustive
                        ? Availability.available(continuations)
                        : Availability.partial(continuations);
            }
            return exhaustive
                    ? Availability.none()
                    : Availability.unavailable();
        } catch (RuntimeException unavailable) {
            return Availability.unavailable();
        }
    }

    private Availability exhaustedAvailability(List<TeachingPlanRepository.PlanReference> planReferences) {
        return planReferences.size() < CANDIDATE_LIMIT
                ? Availability.none()
                : Availability.unavailable();
    }

    private Map<Integer, Continuation> continuations(
            List<LessonCandidate> usableLessons,
            Map<Integer, Candidate> candidatesByBggId,
            Map<UUID, Integer> bggIdsByEdition) {
        Map<Integer, Continuation> continuations = new LinkedHashMap<>();
        Set<UUID> documentVersions = new LinkedHashSet<>();
        usableLessons.stream()
                .filter(candidate -> documentVersions.add(candidate.plan().documentVersionId()))
                .forEach(candidate -> {
                    UUID editionId = candidate.reference().gameEditionId();
                    Integer bggId = editionId == null ? null : bggIdsByEdition.get(editionId);
                    Candidate requested = bggId == null ? null : candidatesByBggId.get(bggId);
                    if (requested == null) return;
                    continuations.putIfAbsent(
                            requested.bggId(),
                            new Continuation(
                                    requested.bggId(),
                                    candidate.plan().teachingPlanId(),
                                    candidate.summary().sectionCount(),
                                    candidate.summary().stepCount()));
                });
        return continuations;
    }

    private static boolean hasContinuationMetadata(LessonCandidate candidate) {
        return candidate.summary().sectionCount() > 0 && candidate.summary().stepCount() > 0;
    }

    /**
     * Reuses the already-authorized catalog projection for the cover requests that a catalog page immediately
     * fans out. This path performs no repository work and, unlike {@link PublicLessonReader#find(UUID)}, never
     * materializes the complete illustrated lesson merely to locate one thumbnail.
     */
    public Optional<CachedCover> cachedCover(UUID teachingPlanId) {
        if (teachingPlanId == null) return Optional.empty();
        return cachedEntries.values().stream()
                .filter(CachedEntries::isFresh)
                .flatMap(cached -> cached.entries().stream())
                .filter(entry -> teachingPlanId.equals(entry.teachingPlanId()))
                .findFirst()
                .map(entry -> new CachedCover(entry.documentVersionId(), entry.gameCover()));
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

    private java.util.Optional<LessonCandidate> candidate(
            TeachingPlanRepository.PlanReference plan,
            IllustratedLessonRepository.LessonSummary summary,
            PublicRulebookReferenceLookup.Reference reference) {
        return summary == null || reference == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new LessonCandidate(plan, summary, reference));
    }

    private record LessonCandidate(
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
            UUID documentVersionId,
            String rulebookTitle,
            String officialSourceUrl,
            PublicLessonReader.PublicCover gameCover,
            PublicGameIdentityLookup.Identity publicGame,
            int sectionCount,
            int stepCount) {
        static Entry from(
                LessonCandidate candidate,
                PublicGameCoverLookup.Cover catalogCover,
                PublicGameIdentityLookup.Identity matchedIdentity) {
            PublicRulebookReferenceLookup.Reference reference = candidate.reference();
            PublicGameIdentityLookup.Identity publicGame = catalogCover == null
                    ? matchedIdentity
                    : new PublicGameIdentityLookup.Identity(
                            catalogCover.bggId(), catalogCover.gameName(), catalogCover.bggUrl());
            return new Entry(
                    candidate.plan().teachingPlanId(),
                    candidate.plan().documentVersionId(),
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

    public record CachedCover(UUID documentVersionId, PublicLessonReader.PublicCover gameCover) {
        public CachedCover {
            if (documentVersionId == null) throw new IllegalArgumentException("public cover reference is invalid");
        }
    }
}
