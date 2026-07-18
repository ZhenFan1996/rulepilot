package com.rulepilot.teaching.adapter.out.persistence;

import com.rulepilot.teaching.application.IllustratedLessonRepository;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingSectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaIllustratedLessonRepository implements IllustratedLessonRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public IllustratedLesson save(IllustratedLesson lesson) {
        entityManager.persist(new IllustratedLessonEntity(lesson));
        lesson.sections().forEach(section -> {
            UUID sectionId = UUID.randomUUID();
            entityManager.persist(new IllustratedLessonSectionEntity(sectionId, lesson.id(), section));
            section.steps().forEach(step -> entityManager.persist(new IllustratedLessonStepEntity(sectionId, step)));
        });
        entityManager.flush();
        return lesson;
    }

    @Override
    public Optional<IllustratedLesson> findLatestByPlan(UUID teachingPlanId) {
        return entityManager
                .createQuery(
                        "select l from IllustratedLessonEntity l where l.teachingPlanId = :planId order by l.createdAt desc",
                        IllustratedLessonEntity.class)
                .setParameter("planId", teachingPlanId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(lesson -> lesson.toDomain(findSections(lesson.id)));
    }

    private List<LessonSection> findSections(UUID lessonId) {
        return entityManager
                .createQuery(
                        "select s from IllustratedLessonSectionEntity s where s.lessonId = :lessonId order by s.position",
                        IllustratedLessonSectionEntity.class)
                .setParameter("lessonId", lessonId)
                .getResultList()
                .stream()
                .map(section -> section.toDomain(findSteps(section.id)))
                .toList();
    }

    private List<LessonStep> findSteps(UUID sectionId) {
        return entityManager
                .createQuery(
                        "select s from IllustratedLessonStepEntity s where s.lessonSectionId = :sectionId order by s.position",
                        IllustratedLessonStepEntity.class)
                .setParameter("sectionId", sectionId)
                .getResultList()
                .stream()
                .map(IllustratedLessonStepEntity::toDomain)
                .toList();
    }
}

@Entity(name = "IllustratedLessonEntity")
@Table(name = "illustrated_lesson")
class IllustratedLessonEntity {
    @Id UUID id;
    @Column(name = "teaching_plan_id", nullable = false) UUID teachingPlanId;
    @Column(nullable = false) String status;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    protected IllustratedLessonEntity() {}

    IllustratedLessonEntity(IllustratedLesson lesson) {
        id = lesson.id();
        teachingPlanId = lesson.teachingPlanId();
        status = lesson.status().name();
        createdAt = lesson.createdAt();
    }

    IllustratedLesson toDomain(List<LessonSection> sections) {
        return new IllustratedLesson(id, teachingPlanId, LessonStatus.valueOf(status), sections, createdAt);
    }
}

@Entity(name = "IllustratedLessonSectionEntity")
@Table(name = "illustrated_lesson_section")
class IllustratedLessonSectionEntity {
    @Id UUID id;
    @Column(name = "lesson_id", nullable = false) UUID lessonId;
    @Column(nullable = false) int position;
    @Column(name = "section_type", nullable = false) String sectionType;
    @Column(nullable = false) String title;
    @Column(nullable = false) boolean required;
    @Column(name = "evidence_status", nullable = false) String evidenceStatus;
    @Column(name = "visual_kind", nullable = false) String visualKind;
    @Column(name = "visual_caption", nullable = false) String visualCaption;

    protected IllustratedLessonSectionEntity() {}

    IllustratedLessonSectionEntity(UUID id, UUID lessonId, LessonSection section) {
        this.id = id;
        this.lessonId = lessonId;
        position = section.position();
        sectionType = section.type().name();
        title = section.title();
        required = section.required();
        evidenceStatus = section.evidenceStatus().name();
        visualKind = section.visualKind().name();
        visualCaption = section.visualCaption();
    }

    LessonSection toDomain(List<LessonStep> steps) {
        return new LessonSection(
                position,
                TeachingSectionType.valueOf(sectionType),
                title,
                required,
                EvidenceStatus.valueOf(evidenceStatus),
                VisualKind.valueOf(visualKind),
                visualCaption,
                steps);
    }
}

@Entity(name = "IllustratedLessonStepEntity")
@Table(name = "illustrated_lesson_step")
class IllustratedLessonStepEntity {
    @Id UUID id;
    @Column(name = "lesson_section_id", nullable = false) UUID lessonSectionId;
    @Column(nullable = false) int position;
    @Column(name = "step_text", nullable = false, columnDefinition = "text") String stepText;
    @Column(name = "source_pages", nullable = false) String sourcePages;
    @Column(name = "source_chunk_ids", nullable = false) String sourceChunkIds;

    protected IllustratedLessonStepEntity() {}

    IllustratedLessonStepEntity(UUID lessonSectionId, LessonStep step) {
        id = UUID.randomUUID();
        this.lessonSectionId = lessonSectionId;
        position = step.position();
        stepText = step.text();
        sourcePages = step.sourcePages().stream().map(String::valueOf).collect(Collectors.joining(","));
        sourceChunkIds = step.sourceChunkIds().stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    LessonStep toDomain() {
        List<Integer> pages = sourcePages.isBlank()
                ? List.of()
                : Arrays.stream(sourcePages.split(",")).map(Integer::valueOf).toList();
        List<UUID> chunkIds = sourceChunkIds.isBlank()
                ? List.of()
                : Arrays.stream(sourceChunkIds.split(",")).map(UUID::fromString).toList();
        return new LessonStep(position, stepText, pages, chunkIds);
    }
}
