package com.rulepilot.teaching.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.teaching.application.TeachingPlanRepository;
import com.rulepilot.teaching.application.TeachingPlanSummary;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    public Optional<TeachingPlan> findByIdAndCreatedBy(UUID planId, String createdBy) {
        return entityManager
                .createQuery(
                        "select p from TeachingPlanEntity p where p.id = :planId and p.createdBy = :createdBy",
                        TeachingPlanEntity.class)
                .setParameter("planId", planId)
                .setParameter("createdBy", createdBy)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(plan -> plan.toDomain(findSections(plan.id)));
    }

    @Override
    public List<TeachingPlan> findAllByCreatedBy(String createdBy) {
        List<TeachingPlanEntity> plans = entityManager
                .createQuery(
                        "select p from TeachingPlanEntity p where p.createdBy = :createdBy order by p.createdAt desc",
                        TeachingPlanEntity.class)
                .setParameter("createdBy", createdBy)
                .getResultList();
        return toDomains(plans);
    }

    @Override
    public List<TeachingPlanSummary> findSummariesByCreatedBy(String createdBy) {
        List<Object[]> planRows = entityManager
                .createQuery(
                        "select p.id, p.documentVersionId, p.gameTitle, p.premise, p.wholeGameContext, p.createdBy, p.createdAt "
                                + "from TeachingPlanEntity p where p.createdBy = :createdBy order by p.createdAt desc",
                        Object[].class)
                .setParameter("createdBy", createdBy)
                .getResultList();
        if (planRows.isEmpty()) return List.of();
        List<UUID> planIds = planRows.stream().map(row -> (UUID) row[0]).toList();
        Map<UUID, List<TeachingPlanSummary.SectionSummary>> sectionsByPlan = entityManager
                .createQuery(
                        "select s.teachingPlanId, s.position, s.topicKey, s.title, s.required, s.visualEvidenceRecommended "
                                + "from TeachingPlanSectionEntity s where s.teachingPlanId in :planIds "
                                + "order by s.teachingPlanId, s.position",
                        Object[].class)
                .setParameter("planIds", planIds)
                .getResultList()
                .stream()
                .collect(Collectors.groupingBy(
                        row -> (UUID) row[0],
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(
                                row -> new TeachingPlanSummary.SectionSummary(
                                        (Integer) row[1],
                                        (String) row[2],
                                        (String) row[3],
                                        (Boolean) row[4],
                                        (Boolean) row[5]),
                                Collectors.toList())));
        return planRows.stream()
                .map(row -> new TeachingPlanSummary(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        TeachingPlanEntity.readContext((String) row[4], (String) row[3]).unresolvedTopics(),
                        sectionsByPlan.getOrDefault((UUID) row[0], List.of()),
                        null,
                        (String) row[5],
                        (Instant) row[6]))
                .toList();
    }

    @Override
    public List<TeachingPlan> findRecent(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("recent plan limit is invalid");
        List<TeachingPlanEntity> plans = entityManager
                .createQuery("select p from TeachingPlanEntity p order by p.createdAt desc", TeachingPlanEntity.class)
                .setMaxResults(limit)
                .getResultList();
        return toDomains(plans);
    }

    @Override
    public List<PlanReference> findRecentReferences(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("recent plan limit is invalid");
        return entityManager
                .createQuery("select p from TeachingPlanEntity p order by p.createdAt desc", TeachingPlanEntity.class)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(plan -> new PlanReference(plan.id, plan.documentVersionId, plan.gameTitle))
                .toList();
    }

    @Override
    public Optional<TeachingPlan> findLatest(UUID documentVersionId, String createdBy) {
        return entityManager
                .createQuery(
                        "select p from TeachingPlanEntity p where p.documentVersionId = :versionId and p.createdBy = :createdBy order by p.createdAt desc",
                        TeachingPlanEntity.class)
                .setParameter("versionId", documentVersionId)
                .setParameter("createdBy", createdBy)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(plan -> plan.toDomain(findSections(plan.id)));
    }

    @Override
    public void delete(UUID planId) {
        entityManager.createNativeQuery("delete from teaching_plan where id = :planId")
                .setParameter("planId", planId)
                .executeUpdate();
        entityManager.flush();
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

    private List<TeachingPlan> toDomains(List<TeachingPlanEntity> plans) {
        if (plans.isEmpty()) return List.of();
        Map<UUID, List<PlannedSection>> sectionsByPlan = entityManager
                .createQuery(
                        "select s from TeachingPlanSectionEntity s where s.teachingPlanId in :planIds "
                                + "order by s.teachingPlanId, s.position",
                        TeachingPlanSectionEntity.class)
                .setParameter("planIds", plans.stream().map(plan -> plan.id).toList())
                .getResultList()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        section -> section.teachingPlanId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                TeachingPlanSectionEntity::toDomain, java.util.stream.Collectors.toList())));
        return plans.stream()
                .map(plan -> plan.toDomain(sectionsByPlan.getOrDefault(plan.id, List.of())))
                .toList();
    }
}

@Entity(name = "TeachingPlanEntity")
@Table(name = "teaching_plan")
class TeachingPlanEntity {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "learning_goal", columnDefinition = "text")
    String learningGoal;

    @Column(name = "game_title", nullable = false, columnDefinition = "text")
    String gameTitle;

    @Column(nullable = false, columnDefinition = "text")
    String premise;

    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "whole_game_context", nullable = false, columnDefinition = "jsonb")
    String wholeGameContext;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected TeachingPlanEntity() {}

    TeachingPlanEntity(TeachingPlan plan) {
        this.id = plan.id();
        this.documentVersionId = plan.documentVersionId();
        this.learningGoal = plan.learningGoal();
        this.gameTitle = plan.gameTitle();
        this.premise = plan.premise();
        this.wholeGameContext = writeContext(plan.wholeGameContext());
        this.createdBy = plan.createdBy();
        this.createdAt = plan.createdAt();
    }

    TeachingPlan toDomain(List<PlannedSection> sections) {
        return new TeachingPlan(
                id,
                documentVersionId,
                learningGoal,
                gameTitle,
                premise,
                readContext(wholeGameContext, premise),
                sections,
                createdBy,
                createdAt);
    }

    private static String writeContext(TeachingPlan.WholeGameContext context) {
        try {
            return JSON.writeValueAsString(context);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot persist whole-game teaching context", failure);
        }
    }

    static TeachingPlan.WholeGameContext readContext(String value, String premise) {
        if (value == null || value.isBlank()) return TeachingPlan.WholeGameContext.legacy(premise);
        try {
            return JSON.readValue(value, TeachingPlan.WholeGameContext.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot read whole-game teaching context", failure);
        }
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

    @Column(name = "topic_key", nullable = false, columnDefinition = "text")
    String topicKey;

    @Column(nullable = false, columnDefinition = "text")
    String title;

    @Column(nullable = false, columnDefinition = "text")
    String objective;

    @Column(nullable = false)
    boolean required;

    @Column(name = "visual_evidence_recommended", nullable = false)
    boolean visualEvidenceRecommended;

    @Column(name = "retrieval_queries", nullable = false, columnDefinition = "text")
    String retrievalQueries;

    @Column(name = "coverage_tags", nullable = false, columnDefinition = "text")
    String coverageTags;

    @Column(name = "source_page_numbers", nullable = false, columnDefinition = "text")
    String sourcePageNumbers;

    @Column(name = "visual_source_page_numbers", nullable = false, columnDefinition = "text")
    String visualSourcePageNumbers;

    protected TeachingPlanSectionEntity() {}

    TeachingPlanSectionEntity(UUID teachingPlanId, PlannedSection section) {
        this.id = UUID.randomUUID();
        this.teachingPlanId = teachingPlanId;
        this.position = section.position();
        this.topicKey = section.topicKey();
        this.title = section.title();
        this.objective = section.objective();
        this.required = section.required();
        this.visualEvidenceRecommended = section.visualEvidenceRecommended();
        this.retrievalQueries = String.join("\n", section.retrievalQueries());
        this.coverageTags = String.join(",", section.coverageTags());
        this.sourcePageNumbers = section.sourcePageNumbers().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        this.visualSourcePageNumbers = section.visualSourcePageNumbers().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    PlannedSection toDomain() {
        return new PlannedSection(
                position,
                topicKey,
                title,
                objective,
                required,
                visualEvidenceRecommended,
                retrievalQueries.lines().filter(value -> !value.isBlank()).toList(),
                coverageTags.isBlank() ? List.of() : List.of(coverageTags.split(",")),
                sourcePageNumbers == null || sourcePageNumbers.isBlank()
                        ? List.of()
                        : java.util.Arrays.stream(sourcePageNumbers.split(",")).map(Integer::valueOf).toList(),
                visualSourcePageNumbers == null || visualSourcePageNumbers.isBlank()
                        ? List.of()
                        : java.util.Arrays.stream(visualSourcePageNumbers.split(",")).map(Integer::valueOf).toList());
    }
}
