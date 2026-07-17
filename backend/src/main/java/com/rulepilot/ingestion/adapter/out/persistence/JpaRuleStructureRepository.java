package com.rulepilot.ingestion.adapter.out.persistence;

import com.rulepilot.ingestion.application.RuleStructureRepository;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaRuleStructureRepository implements RuleStructureRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void replace(UUID documentVersionId, List<DetectedRuleSection> sections) {
        entityManager
                .createQuery("delete from RuleChunkEntity c where c.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from RuleStructureSectionEntity s where s.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        Instant now = Instant.now();
        for (int index = 0; index < sections.size(); index++) {
            var section = sections.get(index);
            entityManager.persist(new RuleStructureSectionEntity(
                    UUID.randomUUID(),
                    documentVersionId,
                    section.type().name(),
                    section.content(),
                    section.pageNumbers().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    now));
            entityManager.persist(new RuleChunkEntity(
                    UUID.randomUUID(),
                    documentVersionId,
                    section.type().name(),
                    section.type().label(),
                    section.content(),
                    section.pageNumbers().getFirst(),
                    section.pageNumbers().getLast(),
                    index,
                    now));
        }
        entityManager.flush();
    }

    @Override
    public List<DetectedRuleSection> findByDocumentVersion(UUID documentVersionId) {
        return entityManager
                .createQuery(
                        "select s from RuleStructureSectionEntity s where s.documentVersionId = :versionId order by s.sectionType",
                        RuleStructureSectionEntity.class)
                .setParameter("versionId", documentVersionId)
                .getResultList()
                .stream()
                .map(RuleStructureSectionEntity::toView)
                .toList();
    }
}

@Entity(name = "RuleChunkEntity")
@Table(name = "rule_chunk")
class RuleChunkEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "section_type", nullable = false)
    String sectionType;

    @Column(nullable = false)
    String heading;

    @Column(nullable = false, columnDefinition = "text")
    String content;

    @Column(name = "page_from", nullable = false)
    int pageFrom;

    @Column(name = "page_to", nullable = false)
    int pageTo;

    @Column(name = "chunk_index", nullable = false)
    int chunkIndex;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RuleChunkEntity() {}

    RuleChunkEntity(
            UUID id,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String content,
            int pageFrom,
            int pageTo,
            int chunkIndex,
            Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.sectionType = sectionType;
        this.heading = heading;
        this.content = content;
        this.pageFrom = pageFrom;
        this.pageTo = pageTo;
        this.chunkIndex = chunkIndex;
        this.createdAt = createdAt;
    }
}

@Entity(name = "RuleStructureSectionEntity")
@Table(name = "rule_structure_section")
class RuleStructureSectionEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "section_type", nullable = false)
    String sectionType;

    @Column(nullable = false, columnDefinition = "text")
    String content;

    @Column(name = "page_numbers", nullable = false)
    String pageNumbers;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RuleStructureSectionEntity() {}

    RuleStructureSectionEntity(
            UUID id,
            UUID documentVersionId,
            String sectionType,
            String content,
            String pageNumbers,
            Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.sectionType = sectionType;
        this.content = content;
        this.pageNumbers = pageNumbers;
        this.createdAt = createdAt;
    }

    RuleStructureRepository.DetectedRuleSection toView() {
        List<Integer> pages = pageNumbers.isBlank()
                ? List.of()
                : Arrays.stream(pageNumbers.split(",")).map(Integer::valueOf).toList();
        return new RuleStructureRepository.DetectedRuleSection(
                LessonRuleSectionType.valueOf(sectionType), content, pages);
    }
}
