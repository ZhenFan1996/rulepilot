package com.rulepilot.teaching.application;

import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson;
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

    public PublicLessonReader(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            PublicRulebookReferenceLookup rulebooks) {
        this.plans = plans;
        this.lessons = lessons;
        this.rulebooks = rulebooks;
    }

    @Transactional(readOnly = true)
    public Optional<PublicLesson> find(UUID teachingPlanId) {
        return plans.findById(teachingPlanId).flatMap(plan -> lessons.findLatestByPlan(plan.id()).flatMap(lesson -> rulebooks
                .findReference(plan.documentVersionId())
                .map(rulebook -> new PublicLesson(
                        plan.id(), rulebook.documentVersionId(), rulebook.title(), rulebook.officialSourceUrl(), lesson))));
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
}
