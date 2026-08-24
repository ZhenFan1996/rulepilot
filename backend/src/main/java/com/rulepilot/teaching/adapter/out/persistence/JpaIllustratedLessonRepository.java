package com.rulepilot.teaching.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.teaching.application.IllustratedLessonRepository;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFact;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JpaIllustratedLessonRepository implements IllustratedLessonRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public IllustratedLesson save(IllustratedLesson lesson) {
        return save(lesson, PublicationState.ACTIVE);
    }

    @Override
    public IllustratedLesson saveCandidate(IllustratedLesson lesson) {
        return save(lesson, PublicationState.CANDIDATE);
    }

    private IllustratedLesson save(IllustratedLesson lesson, PublicationState publicationState) {
        IllustratedLessonEntity existing = entityManager.find(IllustratedLessonEntity.class, lesson.id());
        if (existing == null) {
            if (publicationState == PublicationState.CANDIDATE) {
                entityManager.createQuery(
                                "update IllustratedLessonEntity l set l.publicationState = 'ARCHIVED' "
                                        + "where l.teachingPlanId = :planId and l.publicationState = 'CANDIDATE'")
                        .setParameter("planId", lesson.teachingPlanId())
                        .executeUpdate();
            }
            entityManager.persist(new IllustratedLessonEntity(lesson, publicationState));
        } else {
            if (!existing.publicationState.equals(publicationState.name())) {
                throw new IllegalStateException("lesson publication state cannot change through progress updates");
            }
            existing.update(lesson);
            entityManager.createNativeQuery("""
                            delete from illustrated_lesson_step
                            where lesson_section_id in (
                                select id from illustrated_lesson_section where lesson_id = :lessonId
                            )
                            """)
                    .setParameter("lessonId", lesson.id())
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "delete from illustrated_lesson_section where lesson_id = :lessonId")
                    .setParameter("lessonId", lesson.id())
                    .executeUpdate();
        }
        lesson.sections().forEach(section -> {
            UUID sectionId = UUID.randomUUID();
            entityManager.persist(new IllustratedLessonSectionEntity(sectionId, lesson.id(), section));
            section.steps().forEach(step -> entityManager.persist(new IllustratedLessonStepEntity(sectionId, step)));
        });
        entityManager.flush();
        return lesson;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IllustratedLesson> findLatestByPlan(UUID teachingPlanId) {
        return findLatestByPlanAndState(teachingPlanId, PublicationState.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IllustratedLesson> findLatestCandidateByPlan(UUID teachingPlanId) {
        return findLatestByPlanAndState(teachingPlanId, PublicationState.CANDIDATE);
    }

    private Optional<IllustratedLesson> findLatestByPlanAndState(
            UUID teachingPlanId, PublicationState publicationState) {
        return entityManager
                .createQuery(
                        "select l from IllustratedLessonEntity l where l.teachingPlanId = :planId "
                                + "and l.publicationState = :publicationState order by l.createdAt desc, l.id desc",
                        IllustratedLessonEntity.class)
                .setParameter("planId", teachingPlanId)
                .setParameter("publicationState", publicationState.name())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(lesson -> lesson.toDomain(findSections(lesson.id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LessonSummary> findLatestSummariesByPlans(Collection<UUID> teachingPlanIds) {
        if (teachingPlanIds == null || teachingPlanIds.isEmpty()) return List.of();
        Map<UUID, IllustratedLessonEntity> latestByPlan = new LinkedHashMap<>();
        entityManager
                .createQuery(
                        "select l from IllustratedLessonEntity l where l.teachingPlanId in :planIds "
                                + "and l.publicationState = 'ACTIVE' "
                                + "order by l.teachingPlanId, l.createdAt desc, l.id desc",
                        IllustratedLessonEntity.class)
                .setParameter("planIds", teachingPlanIds)
                .getResultList()
                .forEach(lesson -> latestByPlan.putIfAbsent(lesson.teachingPlanId, lesson));
        if (latestByPlan.isEmpty()) return List.of();

        List<IllustratedLessonSectionEntity> sectionEntities = entityManager
                .createQuery(
                        "select s from IllustratedLessonSectionEntity s where s.lessonId in :lessonIds "
                                + "order by s.lessonId, s.position",
                        IllustratedLessonSectionEntity.class)
                .setParameter("lessonIds", latestByPlan.values().stream().map(lesson -> lesson.id).toList())
                .getResultList();
        Map<UUID, List<IllustratedLessonSectionEntity>> sectionsByLesson = groupSections(sectionEntities);
        Map<UUID, List<LessonStep>> stepsBySection = findStepsBySection(sectionEntities.stream()
                .map(section -> section.id)
                .toList());

        return latestByPlan.values().stream()
                .map(lesson -> summary(lesson, sectionsByLesson.getOrDefault(lesson.id, List.of()), stepsBySection))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProgressSummary> findLatestProgressSummariesByPlans(Collection<UUID> teachingPlanIds) {
        if (teachingPlanIds == null || teachingPlanIds.isEmpty()) return List.of();
        Map<UUID, Object[]> latestByPlan = new LinkedHashMap<>();
        entityManager
                .createQuery(
                        "select l.id, l.teachingPlanId, l.status from IllustratedLessonEntity l "
                                + "where l.teachingPlanId in :planIds and l.publicationState = 'ACTIVE' "
                                + "order by l.teachingPlanId, l.createdAt desc, l.id desc",
                        Object[].class)
                .setParameter("planIds", teachingPlanIds)
                .getResultList()
                .forEach(row -> latestByPlan.putIfAbsent((UUID) row[1], row));
        if (latestByPlan.isEmpty()) return List.of();
        Map<UUID, List<IllustratedLesson.EvidenceStatus>> evidenceByLesson = entityManager
                .createQuery(
                        "select s.lessonId, s.evidenceStatus from IllustratedLessonSectionEntity s "
                                + "where s.lessonId in :lessonIds order by s.lessonId, s.position",
                        Object[].class)
                .setParameter("lessonIds", latestByPlan.values().stream().map(row -> (UUID) row[0]).toList())
                .getResultList()
                .stream()
                .collect(Collectors.groupingBy(
                        row -> (UUID) row[0],
                        LinkedHashMap::new,
                        Collectors.mapping(
                                row -> IllustratedLesson.EvidenceStatus.valueOf((String) row[1]),
                                Collectors.toList())));
        return latestByPlan.values().stream()
                .map(row -> new ProgressSummary(
                        (UUID) row[0],
                        (UUID) row[1],
                        IllustratedLesson.LessonStatus.valueOf((String) row[2]),
                        evidenceByLesson.getOrDefault((UUID) row[0], List.of())))
                .toList();
    }

    @Override
    @Transactional
    public void promoteCandidate(UUID teachingPlanId, UUID candidateLessonId) {
        int candidateCount = entityManager.createQuery(
                        "select count(l) from IllustratedLessonEntity l where l.id = :lessonId "
                                + "and l.teachingPlanId = :planId and l.publicationState = 'CANDIDATE'",
                        Long.class)
                .setParameter("lessonId", candidateLessonId)
                .setParameter("planId", teachingPlanId)
                .getSingleResult()
                .intValue();
        if (candidateCount != 1) throw new IllegalArgumentException("lesson candidate does not exist");
        entityManager.createQuery(
                        "update IllustratedLessonEntity l set l.publicationState = 'ARCHIVED' "
                                + "where l.teachingPlanId = :planId and l.publicationState = 'ACTIVE'")
                .setParameter("planId", teachingPlanId)
                .executeUpdate();
        entityManager.createQuery(
                        "update IllustratedLessonEntity l set l.publicationState = 'ACTIVE' where l.id = :lessonId")
                .setParameter("lessonId", candidateLessonId)
                .executeUpdate();
        entityManager.flush();
    }

    @Override
    @Transactional
    public void archiveCandidate(UUID teachingPlanId, UUID candidateLessonId) {
        int changed = entityManager.createQuery(
                        "update IllustratedLessonEntity l set l.publicationState = 'ARCHIVED' "
                                + "where l.id = :lessonId and l.teachingPlanId = :planId "
                                + "and l.publicationState = 'CANDIDATE'")
                .setParameter("lessonId", candidateLessonId)
                .setParameter("planId", teachingPlanId)
                .executeUpdate();
        if (changed != 1) throw new IllegalArgumentException("lesson candidate does not exist");
        entityManager.flush();
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

    private Map<UUID, List<IllustratedLessonSectionEntity>> groupSections(
            List<IllustratedLessonSectionEntity> sections) {
        Map<UUID, List<IllustratedLessonSectionEntity>> result = new LinkedHashMap<>();
        sections.forEach(section -> result
                .computeIfAbsent(section.lessonId, ignored -> new java.util.ArrayList<>())
                .add(section));
        return result;
    }

    private Map<UUID, List<LessonStep>> findStepsBySection(List<UUID> sectionIds) {
        if (sectionIds.isEmpty()) return Map.of();
        Map<UUID, List<LessonStep>> result = new LinkedHashMap<>();
        entityManager
                .createQuery(
                        "select s from IllustratedLessonStepEntity s where s.lessonSectionId in :sectionIds "
                                + "order by s.lessonSectionId, s.position",
                        IllustratedLessonStepEntity.class)
                .setParameter("sectionIds", sectionIds)
                .getResultList()
                .forEach(step -> result
                        .computeIfAbsent(step.lessonSectionId, ignored -> new java.util.ArrayList<>())
                        .add(step.toDomain()));
        return result;
    }

    private LessonSummary summary(
            IllustratedLessonEntity lesson,
            List<IllustratedLessonSectionEntity> sectionEntities,
            Map<UUID, List<LessonStep>> stepsBySection) {
        List<LessonSection> sections = sectionEntities.stream()
                .map(section -> section.toDomain(stepsBySection.getOrDefault(section.id, List.of())))
                .toList();
        IllustratedLesson fullLesson = lesson.toDomain(sections);
        return new LessonSummary(
                lesson.teachingPlanId,
                fullLesson.status(),
                com.rulepilot.teaching.application.PlayerFacingLessonLanguagePolicy.isPubliclyReadable(fullLesson),
                sections.size(),
                sections.stream().mapToInt(section -> section.steps().size()).sum());
    }
}

@Entity(name = "IllustratedLessonEntity")
@Table(name = "illustrated_lesson")
class IllustratedLessonEntity {
    @Id UUID id;
    @Column(name = "teaching_plan_id", nullable = false) UUID teachingPlanId;
    @Column(nullable = false) String status;
    @Column(name = "generator_version", nullable = false) String generatorVersion;
    @Column(name = "publication_state", nullable = false) String publicationState;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    protected IllustratedLessonEntity() {}

    IllustratedLessonEntity(IllustratedLesson lesson, PublicationState publicationState) {
        id = lesson.id();
        teachingPlanId = lesson.teachingPlanId();
        status = lesson.status().name();
        generatorVersion = lesson.generatorVersion();
        this.publicationState = publicationState.name();
        createdAt = lesson.createdAt();
    }

    void update(IllustratedLesson lesson) {
        if (!id.equals(lesson.id()) || !teachingPlanId.equals(lesson.teachingPlanId())) {
            throw new IllegalArgumentException("lesson progress identity cannot change");
        }
        status = lesson.status().name();
        generatorVersion = lesson.generatorVersion();
    }

    IllustratedLesson toDomain(List<LessonSection> sections) {
        return new IllustratedLesson(
                id, teachingPlanId, LessonStatus.valueOf(status), sections, generatorVersion, createdAt);
    }
}

enum PublicationState {
    ACTIVE,
    CANDIDATE,
    ARCHIVED
}

@Entity(name = "IllustratedLessonSectionEntity")
@Table(name = "illustrated_lesson_section")
class IllustratedLessonSectionEntity {
    @Id UUID id;
    @Column(name = "lesson_id", nullable = false) UUID lessonId;
    @Column(nullable = false) int position;
    @Column(name = "topic_key", nullable = false, columnDefinition = "text") String topicKey;
    @Column(name = "coverage_tags", nullable = false, columnDefinition = "text") String coverageTags;
    @Column(nullable = false, columnDefinition = "text") String title;
    @Column(nullable = false) boolean required;
    @Column(name = "evidence_status", nullable = false) String evidenceStatus;
    @Column(name = "visual_kind", nullable = false) String visualKind;
    @Column(name = "visual_caption", nullable = false, columnDefinition = "text") String visualCaption;
    @Column(name = "visual_source_pages", nullable = false, columnDefinition = "text") String visualSourcePages;
    @Column(name = "visual_source_chunk_ids", nullable = false, columnDefinition = "text") String visualSourceChunkIds;

    protected IllustratedLessonSectionEntity() {}

    IllustratedLessonSectionEntity(UUID id, UUID lessonId, LessonSection section) {
        this.id = id;
        this.lessonId = lessonId;
        position = section.position();
        topicKey = section.topicKey();
        coverageTags = String.join(",", section.coverageTags());
        title = section.title();
        required = section.required();
        evidenceStatus = section.evidenceStatus().name();
        visualKind = section.visualKind().name();
        visualCaption = section.visualCaption();
        visualSourcePages = section.visualSourcePages().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        visualSourceChunkIds = section.visualSourceChunkIds().stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));
    }

    LessonSection toDomain(List<LessonStep> steps) {
        return new LessonSection(
                position,
                topicKey,
                coverageTags.isBlank() ? List.of() : List.of(coverageTags.split(",")),
                title,
                required,
                EvidenceStatus.valueOf(evidenceStatus),
                VisualKind.valueOf(visualKind),
                visualCaption,
                visualSourcePages.isBlank()
                        ? List.of()
                        : Arrays.stream(visualSourcePages.split(",")).map(Integer::valueOf).toList(),
                visualSourceChunkIds.isBlank()
                        ? List.of()
                        : Arrays.stream(visualSourceChunkIds.split(",")).map(UUID::fromString).toList(),
                steps);
    }
}

@Entity(name = "IllustratedLessonStepEntity")
@Table(name = "illustrated_lesson_step")
class IllustratedLessonStepEntity {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Id UUID id;
    @Column(name = "lesson_section_id", nullable = false) UUID lessonSectionId;
    @Column(nullable = false) int position;
    @Column(name = "step_heading", nullable = false, columnDefinition = "text") String stepHeading;
    @Column(name = "teaching_move", nullable = false) String teachingMove;
    @Column(name = "step_text", nullable = false, columnDefinition = "text") String stepText;
    @Column(name = "source_pages", nullable = false, columnDefinition = "text") String sourcePages;
    @Column(name = "source_chunk_ids", nullable = false, columnDefinition = "text") String sourceChunkIds;
    @Column(name = "rule_facts_json", nullable = false, columnDefinition = "text") String ruleFactsJson = "[]";
    @Column(name = "visual_foci_json", nullable = false, columnDefinition = "text") String visualFociJson = "[]";
    @Column(name = "visual_page") Integer visualPage;
    @Column(name = "visual_label", columnDefinition = "text") String visualLabel;
    @Column(name = "visual_description", nullable = false, columnDefinition = "text") String visualDescription = "";
    @Column(name = "visual_x") Integer visualX;
    @Column(name = "visual_y") Integer visualY;
    @Column(name = "visual_width") Integer visualWidth;
    @Column(name = "visual_height") Integer visualHeight;

    protected IllustratedLessonStepEntity() {}

    IllustratedLessonStepEntity(UUID lessonSectionId, LessonStep step) {
        id = UUID.randomUUID();
        this.lessonSectionId = lessonSectionId;
        position = step.position();
        stepHeading = step.heading();
        teachingMove = step.kind().name();
        stepText = step.text();
        sourcePages = step.sourcePages().stream().map(String::valueOf).collect(Collectors.joining(","));
        sourceChunkIds = step.sourceChunkIds().stream().map(UUID::toString).collect(Collectors.joining(","));
        ruleFactsJson = writeRuleFacts(step.ruleFacts());
        visualFociJson = writeVisualFoci(step.visualFoci());
        if (step.visualFocus() != null) {
            visualPage = step.visualFocus().pageNumber();
            visualLabel = step.visualFocus().label();
            visualDescription = step.visualFocus().visibleDescription();
            visualX = step.visualFocus().x();
            visualY = step.visualFocus().y();
            visualWidth = step.visualFocus().width();
            visualHeight = step.visualFocus().height();
        }
    }

    LessonStep toDomain() {
        List<Integer> pages = sourcePages.isBlank()
                ? List.of()
                : Arrays.stream(sourcePages.split(",")).map(Integer::valueOf).toList();
        List<UUID> chunkIds = sourceChunkIds.isBlank()
                ? List.of()
                : Arrays.stream(sourceChunkIds.split(",")).map(UUID::fromString).toList();
        List<VisualFocus> visualFoci = readVisualFoci(visualFociJson);
        if (visualFoci.isEmpty() && visualPage != null) {
            visualFoci = List.of(new VisualFocus(
                    visualPage,
                    visualLabel,
                    visualDescription,
                    visualX,
                    visualY,
                    visualWidth,
                    visualHeight));
        }
        return new LessonStep(
                position,
                stepHeading,
                TeachingMove.valueOf(teachingMove),
                stepText,
                pages,
                chunkIds,
                readRuleFacts(ruleFactsJson),
                visualFoci.isEmpty() ? null : visualFoci.getFirst(),
                visualFoci);
    }

    private static String writeRuleFacts(List<RuleFact> facts) {
        try {
            return JSON.writeValueAsString(facts == null ? List.of() : facts);
        } catch (Exception invalidFacts) {
            throw new IllegalStateException("lesson rule facts could not be stored", invalidFacts);
        }
    }

    private static List<RuleFact> readRuleFacts(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<RuleFact>>() {});
        } catch (Exception invalidFacts) {
            throw new IllegalStateException("stored lesson rule facts are invalid", invalidFacts);
        }
    }

    private static String writeVisualFoci(List<VisualFocus> visualFoci) {
        try {
            return JSON.writeValueAsString(visualFoci == null ? List.of() : visualFoci);
        } catch (Exception invalidVisuals) {
            throw new IllegalStateException("lesson visual foci could not be stored", invalidVisuals);
        }
    }

    private static List<VisualFocus> readVisualFoci(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<VisualFocus>>() {});
        } catch (Exception invalidVisuals) {
            throw new IllegalStateException("stored lesson visual foci are invalid", invalidVisuals);
        }
    }
}
