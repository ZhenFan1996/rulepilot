package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import com.rulepilot.document.application.RuleDocumentRepository;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JpaRuleDocumentRepository implements RuleDocumentRepository {

    private final EntityManager entityManager;

    public JpaRuleDocumentRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<RuleDocument> findDocument(
            UUID editionId, String createdBy, String title, DocumentSourceType sourceType) {
        return entityManager
                .createQuery(
                        """
                        select d from RuleDocumentEntity d
                        where d.gameEditionId = :editionId
                          and d.createdBy = :createdBy
                          and lower(d.title) = lower(:title)
                          and d.sourceType = :sourceType
                        """,
                        RuleDocumentEntity.class)
                .setParameter("editionId", editionId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("sourceType", sourceType.name())
                .getResultStream()
                .findFirst()
                .map(RuleDocumentEntity::toDomain);
    }

    @Override
    public Optional<RuleDocument> findDocument(UUID documentId) {
        return Optional.ofNullable(entityManager.find(RuleDocumentEntity.class, documentId))
                .map(RuleDocumentEntity::toDomain);
    }

    @Override
    public Optional<RuleDocument> findUnassignedDocument(
            String createdBy, String title, DocumentSourceType sourceType) {
        return entityManager
                .createQuery(
                        """
                        select d from RuleDocumentEntity d
                        where d.gameEditionId is null
                          and d.createdBy = :createdBy
                          and lower(d.title) = lower(:title)
                          and d.sourceType = :sourceType
                        """,
                        RuleDocumentEntity.class)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("sourceType", sourceType.name())
                .getResultStream()
                .findFirst()
                .map(RuleDocumentEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuleDocument> findLatestOwnedByOfficialSource(
            String createdBy, String officialSourceUrl) {
        return entityManager
                .createQuery(
                        """
                        select document from RuleDocumentEntity document
                        where document.createdBy = :createdBy
                          and document.officialSourceUrl = :officialSourceUrl
                        order by document.createdAt desc
                        """,
                        RuleDocumentEntity.class)
                .setParameter("createdBy", createdBy)
                .setParameter("officialSourceUrl", officialSourceUrl)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(RuleDocumentEntity::toDomain);
    }

    @Override
    public RuleDocument save(RuleDocument document) {
        entityManager.persist(new RuleDocumentEntity(document));
        entityManager.flush();
        return document;
    }

    @Override
    public void update(RuleDocument document) {
        RuleDocumentEntity entity = entityManager.find(RuleDocumentEntity.class, document.id());
        if (entity == null) {
            throw new IllegalArgumentException("rule document does not exist");
        }
        entity.gameEditionId = document.gameEditionId();
        entity.title = document.title();
        entity.officialSourceUrl = document.officialSourceUrl();
        entity.officialCoverUrl = document.officialCoverUrl();
        entityManager.flush();
    }

    @Override
    public Optional<DocumentVersion> findVersionByChecksum(UUID documentId, String checksum) {
        return entityManager
                .createQuery(
                        "select v from DocumentVersionEntity v where v.documentId = :documentId and v.checksum = :checksum",
                        DocumentVersionEntity.class)
                .setParameter("documentId", documentId)
                .setParameter("checksum", checksum)
                .getResultStream()
                .findFirst()
                .map(DocumentVersionEntity::toDomain);
    }

    @Override
    public int nextVersionNumber(UUID documentId) {
        Integer highest = entityManager
                .createQuery(
                        "select max(v.versionNumber) from DocumentVersionEntity v where v.documentId = :documentId",
                        Integer.class)
                .setParameter("documentId", documentId)
                .getSingleResult();
        return highest == null ? 1 : highest + 1;
    }

    @Override
    public DocumentVersion save(DocumentVersion version) {
        entityManager.persist(new DocumentVersionEntity(version));
        entityManager.flush();
        return version;
    }

    @Override
    public Optional<DocumentVersion> findVersion(UUID versionId) {
        return Optional.ofNullable(entityManager.find(DocumentVersionEntity.class, versionId))
                .map(DocumentVersionEntity::toDomain);
    }

    @Override
    public List<DocumentVersion> findVersions(UUID documentId) {
        return entityManager
                .createQuery(
                        "select v from DocumentVersionEntity v where v.documentId = :documentId order by v.versionNumber",
                        DocumentVersionEntity.class)
                .setParameter("documentId", documentId)
                .getResultList()
                .stream()
                .map(DocumentVersionEntity::toDomain)
                .toList();
    }

    @Override
    public long ruleDataVersion(UUID versionId) {
        DocumentVersionEntity version = entityManager.find(DocumentVersionEntity.class, versionId);
        if (version == null) {
            throw new IllegalArgumentException("document version does not exist");
        }
        return version.ruleDataVersion;
    }

    @Override
    public long incrementRuleDataVersion(UUID versionId) {
        Object updated = entityManager
                .createNativeQuery("""
                        UPDATE document_version
                        SET rule_data_version = rule_data_version + 1
                        WHERE id = :versionId
                        RETURNING rule_data_version
                        """)
                .setParameter("versionId", versionId)
                .getSingleResult();
        entityManager.clear();
        return ((Number) updated).longValue();
    }

    @Override
    public void update(DocumentVersion version) {
        DocumentVersionEntity entity = entityManager.find(DocumentVersionEntity.class, version.id());
        if (entity == null) {
            throw new IllegalArgumentException("document version does not exist");
        }
        entity.processingStatus = version.status().name();
        entityManager.flush();
    }

    @Override
    public void replacePages(UUID versionId, List<DocumentProcessing.ExtractedPage> pages) {
        entityManager
                .createQuery("delete from DocumentPageEntity p where p.documentVersionId = :versionId")
                .setParameter("versionId", versionId)
                .executeUpdate();
        Instant now = Instant.now();
        pages.forEach(page -> entityManager.persist(new DocumentPageEntity(
                UUID.randomUUID(), versionId, page.pageNumber(), page.text(), page.text().length(), now)));
        entityManager.flush();
    }

    @Override
    public List<DocumentProcessing.PageView> findPages(UUID versionId) {
        return entityManager
                .createQuery(
                        "select p from DocumentPageEntity p where p.documentVersionId = :versionId order by p.pageNumber",
                        DocumentPageEntity.class)
                .setParameter("versionId", versionId)
                .getResultList()
                .stream()
                .map(page -> new DocumentProcessing.PageView(page.pageNumber, page.textContent, page.characterCount))
                .toList();
    }

    @Override
    public void updatePageImage(UUID versionId, int pageNumber, String objectKey, int width, int height) {
        DocumentPageEntity page = entityManager
                .createQuery(
                        "select p from DocumentPageEntity p where p.documentVersionId = :versionId and p.pageNumber = :pageNumber",
                        DocumentPageEntity.class)
                .setParameter("versionId", versionId)
                .setParameter("pageNumber", pageNumber)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page does not exist"));
        page.imageObjectKey = objectKey;
        page.imageWidth = width;
        page.imageHeight = height;
        entityManager.flush();
    }

    @Override
    public List<PageImageMetadata> findPageImages(UUID versionId, java.util.Set<Integer> pageNumbers) {
        if (pageNumbers.isEmpty()) {
            return List.of();
        }
        return entityManager
                .createQuery(
                        "select p from DocumentPageEntity p where p.documentVersionId = :versionId and p.pageNumber in :pageNumbers and p.imageObjectKey is not null order by p.pageNumber",
                        DocumentPageEntity.class)
                .setParameter("versionId", versionId)
                .setParameter("pageNumbers", pageNumbers)
                .getResultList()
                .stream()
                .map(page -> new PageImageMetadata(
                        page.pageNumber, page.imageObjectKey, page.imageWidth, page.imageHeight))
                .toList();
    }

    @Override
    public List<PageImageMetadata> findAllPageImages(UUID versionId) {
        return entityManager
                .createQuery(
                        "select p from DocumentPageEntity p where p.documentVersionId = :versionId "
                                + "and p.imageObjectKey is not null order by p.pageNumber",
                        DocumentPageEntity.class)
                .setParameter("versionId", versionId)
                .getResultList()
                .stream()
                .map(page -> new PageImageMetadata(
                        page.pageNumber, page.imageObjectKey, page.imageWidth, page.imageHeight))
                .toList();
    }

    @Override
    public void deleteDocument(UUID documentId) {
        entityManager.createNativeQuery("""
                        delete from outbox_event
                        where aggregate_type = 'DOCUMENT_VERSION'
                          and aggregate_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from processing_stage_execution
                        where document_version_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from confirmed_ruling
                        where document_version_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from game_session
                        where document_version_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from visual_rulebook_page_fact
                        where document_version_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from assistant_run
                        where subject_id in (
                            select plan.id
                            from teaching_plan plan
                            where plan.document_version_id in (
                                select id from document_version where document_id = :documentId
                            )
                        )
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from assistant_run
                        where subject_id in (select id from document_version where document_id = :documentId)
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from rule_document where id = :documentId
                        """)
                .setParameter("documentId", documentId)
                .executeUpdate();
        entityManager.flush();
    }

    @Override
    public List<DocumentSummary> findByEdition(UUID editionId, String createdBy) {
        List<RuleDocumentEntity> documents = entityManager
                .createQuery(
                        """
                        select d from RuleDocumentEntity d
                        where d.gameEditionId = :editionId and d.createdBy = :createdBy
                        order by d.title
                        """,
                        RuleDocumentEntity.class)
                .setParameter("editionId", editionId)
                .setParameter("createdBy", createdBy)
                .getResultList();
        return summaries(documents);
    }

    @Override
    public List<DocumentSummary> findByOwner(String createdBy) {
        List<RuleDocumentEntity> documents = entityManager
                .createQuery(
                        "select d from RuleDocumentEntity d where d.createdBy = :createdBy order by d.createdAt desc",
                        RuleDocumentEntity.class)
                .setParameter("createdBy", createdBy)
                .getResultList();
        return summaries(documents);
    }

    @Override
    public Map<UUID, Reference> findReferences(Collection<UUID> documentVersionIds) {
        if (documentVersionIds == null || documentVersionIds.isEmpty()) return Map.of();
        List<DocumentVersionEntity> versions = entityManager
                .createQuery(
                        "select v from DocumentVersionEntity v where v.id in :versionIds", DocumentVersionEntity.class)
                .setParameter("versionIds", documentVersionIds)
                .getResultList();
        if (versions.isEmpty()) return Map.of();
        Map<UUID, RuleDocumentEntity> documentsById = entityManager
                .createQuery(
                        "select d from RuleDocumentEntity d where d.id in :documentIds", RuleDocumentEntity.class)
                .setParameter("documentIds", versions.stream().map(version -> version.documentId).toList())
                .getResultList()
                .stream()
                .collect(java.util.stream.Collectors.toMap(document -> document.id, document -> document));
        Map<UUID, Reference> result = new LinkedHashMap<>();
        versions.forEach(version -> {
            RuleDocumentEntity document = documentsById.get(version.documentId);
            if (document != null) {
                result.put(
                        version.id,
                        new Reference(
                                version.id,
                                document.gameEditionId,
                                document.title,
                                document.officialSourceUrl,
                                document.officialCoverUrl));
            }
        });
        return Map.copyOf(result);
    }

    private List<DocumentSummary> summaries(List<RuleDocumentEntity> documents) {
        return documents.stream()
                .map(document -> new DocumentSummary(document.toDomain(), latestVersion(document.id).toDomain()))
                .toList();
    }

    private DocumentVersionEntity latestVersion(UUID documentId) {
        return entityManager
                .createQuery(
                        """
                        select v from DocumentVersionEntity v
                        where v.documentId = :documentId
                        order by v.versionNumber desc
                        """,
                        DocumentVersionEntity.class)
                .setParameter("documentId", documentId)
                .setMaxResults(1)
                .getSingleResult();
    }
}

@Entity(name = "RuleDocumentEntity")
@Table(name = "rule_document")
class RuleDocumentEntity {

    @Id
    UUID id;

    @Column(name = "game_edition_id")
    UUID gameEditionId;

    @Column(nullable = false)
    String title;

    @Column(name = "source_type", nullable = false)
    String sourceType;

    @Column(name = "official_source_url")
    String officialSourceUrl;

    @Column(name = "official_cover_url")
    String officialCoverUrl;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RuleDocumentEntity() {}

    RuleDocumentEntity(RuleDocument document) {
        this.id = document.id();
        this.gameEditionId = document.gameEditionId();
        this.title = document.title();
        this.sourceType = document.sourceType().name();
        this.officialSourceUrl = document.officialSourceUrl();
        this.officialCoverUrl = document.officialCoverUrl();
        this.createdBy = document.createdBy();
        this.createdAt = document.createdAt();
    }

    RuleDocument toDomain() {
        return new RuleDocument(
                id, gameEditionId, title, DocumentSourceType.valueOf(sourceType), officialSourceUrl, officialCoverUrl, createdBy, createdAt);
    }
}

@Entity(name = "DocumentVersionEntity")
@Table(name = "document_version")
class DocumentVersionEntity {

    @Id
    UUID id;

    @Column(name = "document_id", nullable = false)
    UUID documentId;

    @Column(name = "version_number", nullable = false)
    int versionNumber;

    @Column(name = "original_filename", nullable = false)
    String originalFilename;

    @Column(name = "object_key", nullable = false)
    String objectKey;

    @Column(nullable = false)
    String checksum;

    @Column(name = "size_bytes", nullable = false)
    long size;

    @Column(name = "content_type", nullable = false)
    String contentType;

    @Column(name = "processing_status", nullable = false)
    String processingStatus;

    @Column(name = "rule_data_version", nullable = false)
    long ruleDataVersion;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected DocumentVersionEntity() {}

    DocumentVersionEntity(DocumentVersion version) {
        this.id = version.id();
        this.documentId = version.documentId();
        this.versionNumber = version.versionNumber();
        this.originalFilename = version.originalFilename();
        this.objectKey = version.objectKey();
        this.checksum = version.checksum();
        this.size = version.size();
        this.contentType = version.contentType();
        this.processingStatus = version.status().name();
        this.ruleDataVersion = 1;
        this.createdAt = version.createdAt();
    }

    DocumentVersion toDomain() {
        return new DocumentVersion(
                id,
                documentId,
                versionNumber,
                originalFilename,
                objectKey,
                checksum,
                size,
                contentType,
                ProcessingStatus.valueOf(processingStatus),
                createdAt);
    }
}

@Entity(name = "DocumentPageEntity")
@Table(name = "document_page")
class DocumentPageEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(name = "text_content", nullable = false, columnDefinition = "text")
    String textContent;

    @Column(name = "character_count", nullable = false)
    int characterCount;

    @Column(name = "image_object_key")
    String imageObjectKey;

    @Column(name = "image_width")
    Integer imageWidth;

    @Column(name = "image_height")
    Integer imageHeight;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected DocumentPageEntity() {}

    DocumentPageEntity(
            UUID id,
            UUID documentVersionId,
            int pageNumber,
            String textContent,
            int characterCount,
            Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.pageNumber = pageNumber;
        this.textContent = textContent;
        this.characterCount = characterCount;
        this.createdAt = createdAt;
    }
}
