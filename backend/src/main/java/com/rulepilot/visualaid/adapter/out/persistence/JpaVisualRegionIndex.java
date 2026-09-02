package com.rulepilot.visualaid.adapter.out.persistence;

import com.rulepilot.visualaid.VisualRegionCatalog;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import com.rulepilot.visualaid.application.VisualRegionIndex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JpaVisualRegionIndex implements VisualRegionCatalog, VisualRegionIndex {

    private final EntityManager entityManager;

    public JpaVisualRegionIndex(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void replace(UUID documentVersionId, String source, int pageCount, List<Region> regions) {
        if (documentVersionId == null || source == null || source.isBlank() || pageCount < 1 || regions == null
                || regions.stream().anyMatch(region -> region == null || region.pageNumber() > pageCount)) {
            throw new IllegalArgumentException("visual aid index replacement is invalid");
        }
        entityManager
                .createQuery("delete from VisualAidRegionEntity r where r.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from VisualAidIndexEntity i where i.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        Instant now = Instant.now();
        entityManager.persist(new VisualAidIndexEntity(documentVersionId, source.strip(), pageCount, now));
        for (int index = 0; index < regions.size(); index++) {
            entityManager.persist(new VisualAidRegionEntity(
                    UUID.randomUUID(), documentVersionId, index, regions.get(index), now));
        }
        entityManager.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Region> find(UUID documentVersionId, Set<Integer> pageNumbers) {
        if (documentVersionId == null
                || pageNumbers == null
                || pageNumbers.isEmpty()
                || pageNumbers.size() > 64
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("requested visual aid pages are invalid");
        }
        return entityManager
                .createQuery(
                        """
                        select r from VisualAidRegionEntity r
                        where r.documentVersionId = :versionId and r.pageNumber in :pageNumbers
                        order by r.ordinal
                        """,
                        VisualAidRegionEntity.class)
                .setParameter("versionId", documentVersionId)
                .setParameter("pageNumbers", Set.copyOf(pageNumbers))
                .getResultList()
                .stream()
                .map(VisualAidRegionEntity::toView)
                .toList();
    }
}

@Entity(name = "VisualAidIndexEntity")
@Table(name = "visual_aid_index")
class VisualAidIndexEntity {

    @Id
    @Column(name = "document_version_id")
    UUID documentVersionId;

    @Column(nullable = false, length = 120)
    String source;

    @Column(name = "page_count", nullable = false)
    int pageCount;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected VisualAidIndexEntity() {}

    VisualAidIndexEntity(UUID documentVersionId, String source, int pageCount, Instant createdAt) {
        this.documentVersionId = documentVersionId;
        this.source = source;
        this.pageCount = pageCount;
        this.createdAt = createdAt;
    }
}

@Entity(name = "VisualAidRegionEntity")
@Table(name = "visual_aid_region")
class VisualAidRegionEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(nullable = false)
    int ordinal;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(nullable = false, length = 40)
    String kind;

    @Column(nullable = false)
    int x;

    @Column(nullable = false)
    int y;

    @Column(nullable = false)
    int width;

    @Column(nullable = false)
    int height;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected VisualAidRegionEntity() {}

    VisualAidRegionEntity(UUID id, UUID documentVersionId, int ordinal, Region region, Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.ordinal = ordinal;
        this.pageNumber = region.pageNumber();
        this.kind = region.kind();
        this.x = region.x();
        this.y = region.y();
        this.width = region.width();
        this.height = region.height();
        this.createdAt = createdAt;
    }

    Region toView() {
        return new Region(pageNumber, kind, x, y, width, height);
    }
}
