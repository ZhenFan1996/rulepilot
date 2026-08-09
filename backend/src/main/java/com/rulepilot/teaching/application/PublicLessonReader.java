package com.rulepilot.teaching.application;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only anonymous projection: never exposes an owner, run state, or stored PDF object key. */
@Service
@Profile("!test")
public class PublicLessonReader {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final PublicRulebookReferenceLookup rulebooks;
    private final PublicGameCoverLookup covers;
    private final PublicGameIdentityLookup gameIdentities;

    public PublicLessonReader(
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
    public Optional<PublicLesson> find(UUID teachingPlanId) {
        return plans.findById(teachingPlanId).flatMap(plan -> lessons.findLatestByPlan(plan.id())
                .filter(PublicLessonReader::isPubliclyReadable)
                .flatMap(lesson -> rulebooks
                .findReference(plan.documentVersionId())
                .map(rulebook -> new PublicLesson(
                        plan.id(),
                        rulebook.documentVersionId(),
                        rulebook.title(),
                        rulebook.officialSourceUrl(),
                        cover(rulebook),
                        gameIdentity(plan.gameTitle(), rulebook),
                lesson))));
    }

    static boolean isPubliclyReadable(IllustratedLesson lesson) {
        return PlayerFacingLessonLanguagePolicy.isPubliclyReadable(lesson);
    }

    private PublicCover cover(PublicRulebookReferenceLookup.Reference rulebook) {
        if (rulebook.gameEditionId() != null) {
            var catalogCover = covers.findByEdition(rulebook.gameEditionId());
            if (catalogCover.isPresent()) {
                var value = catalogCover.get();
                return new PublicCover(value.gameName(), value.thumbnailUrl(), value.bggUrl(), "BoardGameGeek");
            }
        }
        return rulebook.officialCoverUrl() == null
                ? null
                : new PublicCover(rulebook.title(), rulebook.officialCoverUrl(), rulebook.officialSourceUrl(), "出版方官方封面");
    }

    private PublicGameIdentityLookup.Identity gameIdentity(
            String gameTitle, PublicRulebookReferenceLookup.Reference rulebook) {
        if (rulebook.gameEditionId() != null) {
            var cover = covers.findByEdition(rulebook.gameEditionId());
            if (cover.isPresent()) {
                var value = cover.orElseThrow();
                return new PublicGameIdentityLookup.Identity(value.bggId(), value.gameName(), value.bggUrl());
            }
        }
        try {
            return gameIdentities.findByTitle(gameTitle).orElse(null);
        } catch (RuntimeException unavailableOptionalMetadata) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public PublicLesson requireCitedPage(UUID teachingPlanId, int pageNumber) {
        if (pageNumber < 1) throw new IllegalArgumentException("rulebook page does not exist");
        PublicLesson lesson = find(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("public lesson does not exist"));
        if (!lesson.citedPages().contains(pageNumber)) {
            throw new IllegalArgumentException("rulebook page is not cited by this lesson");
        }
        return lesson;
    }

    public record PublicLesson(
            UUID teachingPlanId,
            UUID documentVersionId,
            String rulebookTitle,
            String officialSourceUrl,
            PublicCover gameCover,
            PublicGameIdentityLookup.Identity publicGame,
            IllustratedLesson lesson) {
        public PublicLesson {
            if (teachingPlanId == null || documentVersionId == null || rulebookTitle == null || rulebookTitle.isBlank()
                    || lesson == null || !teachingPlanId.equals(lesson.teachingPlanId())) {
                throw new IllegalArgumentException("public lesson is invalid");
            }
            rulebookTitle = rulebookTitle.strip();
        }

        public Set<Integer> citedPages() {
            Set<Integer> pages = new LinkedHashSet<>();
            lesson.sections().forEach(section -> {
                pages.addAll(section.visualSourcePages());
                section.steps().forEach(step -> pages.addAll(step.sourcePages()));
            });
            return Set.copyOf(pages);
        }
    }

    public record PublicCover(String gameName, String imageUrl, String attributionUrl, String attributionLabel) {
        public PublicCover {
            if (gameName == null || gameName.isBlank() || imageUrl == null || imageUrl.isBlank()
                    || attributionUrl == null || attributionUrl.isBlank() || attributionLabel == null || attributionLabel.isBlank()) {
                throw new IllegalArgumentException("public cover is invalid");
            }
            gameName = gameName.strip();
            imageUrl = requireHttps(imageUrl, "image URL");
            attributionUrl = requireHttps(attributionUrl, "attribution URL");
            attributionLabel = attributionLabel.strip();
        }

        private static String requireHttps(String value, String field) {
            java.net.URI uri = java.net.URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " must be a public HTTPS URL");
            }
            return uri.toASCIIString();
        }
    }
}
