package com.rulepilot.teaching.application;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anonymous discovery projection for usable lessons with publisher-owned source material. */
@Service
@Profile("!test")
public class PublicLessonCatalog {

    private static final int CANDIDATE_LIMIT = 200;
    private final TeachingPlanRepository plans;
    private final PublicLessonReader lessons;

    public PublicLessonCatalog(TeachingPlanRepository plans, PublicLessonReader lessons) {
        this.plans = plans;
        this.lessons = lessons;
    }

    @Transactional(readOnly = true)
    public List<Entry> latest(int limit) {
        if (limit < 1 || limit > 60) throw new IllegalArgumentException("public lesson limit is invalid");
        Set<UUID> documentVersions = new LinkedHashSet<>();
        return plans.findRecent(CANDIDATE_LIMIT).stream()
                .map(plan -> lessons.find(plan.id()))
                .flatMap(java.util.Optional::stream)
                .filter(lesson -> lesson.officialSourceUrl() != null)
                .filter(lesson -> lesson.lesson().status() != LessonStatus.INCOMPLETE)
                .filter(lesson -> documentVersions.add(lesson.documentVersionId()))
                .limit(limit)
                .map(Entry::from)
                .toList();
    }

    public record Entry(
            UUID teachingPlanId,
            String rulebookTitle,
            String officialSourceUrl,
            PublicGameCoverLookup.Cover gameCover,
            int sectionCount,
            int stepCount) {
        static Entry from(PublicLessonReader.PublicLesson lesson) {
            return new Entry(
                    lesson.teachingPlanId(),
                    lesson.rulebookTitle(),
                    lesson.officialSourceUrl(),
                    lesson.gameCover(),
                    lesson.lesson().sections().size(),
                    lesson.lesson().sections().stream().mapToInt(section -> section.steps().size()).sum());
        }
    }
}
