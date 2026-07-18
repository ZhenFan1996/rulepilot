package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.application.RuleDocumentRepository;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaRuleDocumentRepository implements RuleDocumentRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<RuleDocument> findDocument(UUID editionId, String title, DocumentSourceType sourceType) {
        return entityManager
                .createQuery(
                        """
                        select d from RuleDocumentEntity d
                        where d.gameEditionId = :editionId
                          and lower(d.title) = lower(:title)
                          and d.sourceType = :sourceType
                        """,
                        RuleDocumentEntity.class)
                .setParameter("editionId", editionId)
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
    public RuleDocument save(RuleDocument document) {
        entityManager.persist(new RuleDocumentEntity(document));
        entityManager.flush();
        return document;
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
    public List<DocumentSummary> findByEdition(UUID editionId) {
        List<RuleDocumentEntity> documents = entityManager
                .createQuery(
                        "select d from RuleDocumentEntity d where d.gameEditionId = :editionId order by d.title",
                        RuleDocumentEntity.class)
                .setParameter("editionId", editionId)
                .getResultList();
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

    @Column(name = "game_edition_id", nullable = false)
    UUID gameEditionId;

    @Column(nullable = false)
    String title;

    @Column(name = "source_type", nullable = false)
    String sourceType;

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
        this.createdBy = document.createdBy();
        this.createdAt = document.createdAt();
    }

    RuleDocument toDomain() {
        return new RuleDocument(
                id, gameEditionId, title, DocumentSourceType.valueOf(sourceType), createdBy, createdAt);
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
