package com.rulepilot.teaching.adapter.out.persistence;

import com.rulepilot.teaching.application.TeachingPlanRepository;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
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
public class JpaTeachingPlanRepository implements TeachingPlanRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public TeachingPlan save(TeachingPlan plan) {
        entityManager.persist(new TeachingPlanEntity(plan));
        plan.sections().forEach(section -> entityManager.persist(new TeachingPlanSectionEntity(plan.id(), section)));
        entityManager.flush();
        return plan;
    }

    @Override
    public Optional<TeachingPlan> findById(UUID planId) {
        return Optional.ofNullable(entityManager.find(TeachingPlanEntity.class, planId))
                .map(plan -> plan.toDomain(findSections(plan.id)));
    }

    @Override
    public Optional<TeachingPlan> findLatest(UUID documentVersionId) {
        return entityManager
                .createQuery(
                        "select p from TeachingPlanEntity p where p.documentVersionId = :versionId order by p.createdAt desc",
                        TeachingPlanEntity.class)
                .setParameter("versionId", documentVersionId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(plan -> plan.toDomain(findSections(plan.id)));
    }

    private List<PlannedSection> findSections(UUID planId) {
        return entityManager
                .createQuery(
                        "select s from TeachingPlanSectionEntity s where s.teachingPlanId = :planId order by s.position",
                        TeachingPlanSectionEntity.class)
                .setParameter("planId", planId)
                .getResultList()
                .stream()
                .map(TeachingPlanSectionEntity::toDomain)
                .toList();
    }
}

@Entity(name = "TeachingPlanEntity")
@Table(name = "teaching_plan")
class TeachingPlanEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "player_count", nullable = false)
    int playerCount;

    @Column(name = "beginner_count", nullable = false)
    int beginnerCount;

    @Column(name = "duration_minutes", nullable = false)
    int durationMinutes;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected TeachingPlanEntity() {}

    TeachingPlanEntity(TeachingPlan plan) {
        this.id = plan.id();
        this.documentVersionId = plan.documentVersionId();
        this.playerCount = plan.playerCount();
        this.beginnerCount = plan.beginnerCount();
        this.durationMinutes = plan.durationMinutes();
        this.createdBy = plan.createdBy();
        this.createdAt = plan.createdAt();
    }

    TeachingPlan toDomain(List<PlannedSection> sections) {
        return new TeachingPlan(
                id, documentVersionId, playerCount, beginnerCount, durationMinutes, sections, createdBy, createdAt);
    }
}

@Entity(name = "TeachingPlanSectionEntity")
@Table(name = "teaching_plan_section")
class TeachingPlanSectionEntity {

    @Id
    UUID id;

    @Column(name = "teaching_plan_id", nullable = false)
    UUID teachingPlanId;

    @Column(nullable = false)
    int position;

    @Column(name = "section_type", nullable = false)
    String sectionType;

    @Column(nullable = false)
    boolean required;

    @Column(name = "evidence_available", nullable = false)
    boolean evidenceAvailable;

    @Column(name = "source_pages", nullable = false)
    String sourcePages;

    @Column(nullable = false)
    String dependencies;

    protected TeachingPlanSectionEntity() {}

    TeachingPlanSectionEntity(UUID teachingPlanId, PlannedSection section) {
        this.id = UUID.randomUUID();
        this.teachingPlanId = teachingPlanId;
        this.position = section.position();
        this.sectionType = section.type().name();
        this.required = section.required();
        this.evidenceAvailable = section.evidenceAvailable();
        this.sourcePages = section.sourcePages().stream().map(String::valueOf).collect(Collectors.joining(","));
        this.dependencies = section.dependencies().stream()
                .map(TeachingSectionType::name)
                .collect(Collectors.joining(","));
    }

    PlannedSection toDomain() {
        List<Integer> pages = sourcePages.isBlank()
                ? List.of()
                : Arrays.stream(sourcePages.split(",")).map(Integer::valueOf).toList();
        List<TeachingSectionType> requiredBefore = dependencies.isBlank()
                ? List.of()
                : Arrays.stream(dependencies.split(",")).map(TeachingSectionType::valueOf).toList();
        return new PlannedSection(
                position,
                TeachingSectionType.valueOf(sectionType),
                required,
                evidenceAvailable,
                pages,
                requiredBefore);
    }
}
