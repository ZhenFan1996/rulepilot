package com.rulepilot.ruling.adapter.out.persistence;

import com.rulepilot.ruling.application.ConfirmedRulingRepository;
import com.rulepilot.ruling.domain.ConfirmedRuling;
import com.rulepilot.ruling.domain.RulingApplicability;
import com.rulepilot.ruling.domain.RulingCitation;
import com.rulepilot.ruling.domain.RulingConfidence;
import com.rulepilot.ruling.domain.RulingStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaConfirmedRulingRepository implements ConfirmedRulingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public ConfirmedRuling save(ConfirmedRuling ruling) {
        entityManager.persist(new ConfirmedRulingEntity(ruling));
        entityManager.flush();
        return ruling;
    }

    @Override
    public Optional<ConfirmedRuling> find(UUID rulingId) {
        return Optional.ofNullable(entityManager.find(ConfirmedRulingEntity.class, rulingId))
                .map(ConfirmedRulingEntity::toDomain);
    }

    @Override
    public boolean existsConfirmed(RulingApplicability applicability, String normalizedQuestionHash) {
        Long count = entityManager.createQuery("""
                        SELECT COUNT(r) FROM ConfirmedRulingEntity r
                        WHERE r.editionId = :editionId
                          AND r.documentVersionId = :documentVersionId
                          AND r.expansionSetHash = :expansionSetHash
                          AND r.normalizedQuestionHash = :questionHash
                          AND r.status = 'CONFIRMED'
                        """, Long.class)
                .setParameter("editionId", applicability.editionId())
                .setParameter("documentVersionId", applicability.documentVersionId())
                .setParameter("expansionSetHash", applicability.expansionSetHash())
                .setParameter("questionHash", normalizedQuestionHash)
                .getSingleResult();
        return count > 0;
    }
}

@Entity(name = "ConfirmedRulingEntity")
@Table(name = "confirmed_ruling")
class ConfirmedRulingEntity {

    @Id
    UUID id;

    @Column(name = "edition_id", nullable = false)
    UUID editionId;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "confirmed_ruling_expansion", joinColumns = @JoinColumn(name = "ruling_id"))
    @Column(name = "expansion_id", nullable = false)
    Set<UUID> expansionIds = new HashSet<>();

    @Column(name = "expansion_set_hash", nullable = false, length = 64)
    String expansionSetHash;

    @Column(name = "original_question", nullable = false)
    String originalQuestion;

    @Column(name = "normalized_question", nullable = false)
    String normalizedQuestion;

    @Column(name = "normalized_question_hash", nullable = false, length = 64)
    String normalizedQuestionHash;

    @Column(name = "short_verdict", nullable = false)
    String shortVerdict;

    @Column(nullable = false)
    String explanation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "confirmed_ruling_citation", joinColumns = @JoinColumn(name = "ruling_id"))
    @OrderColumn(name = "citation_order")
    List<RulingCitationValue> citations = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "confirmed_ruling_exception", joinColumns = @JoinColumn(name = "ruling_id"))
    @OrderColumn(name = "exception_order")
    @Column(name = "exception_text", nullable = false)
    List<String> exceptions = new ArrayList<>();

    @Column(nullable = false)
    String confidence;

    @Column(nullable = false)
    boolean official;

    @Column(nullable = false)
    String status;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(nullable = false)
    long version;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected ConfirmedRulingEntity() {}

    ConfirmedRulingEntity(ConfirmedRuling ruling) {
        id = ruling.id();
        editionId = ruling.applicability().editionId();
        documentVersionId = ruling.applicability().documentVersionId();
        expansionIds = new HashSet<>(ruling.applicability().expansionIds());
        expansionSetHash = ruling.applicability().expansionSetHash();
        originalQuestion = ruling.originalQuestion();
        normalizedQuestion = ruling.normalizedQuestion();
        normalizedQuestionHash = ruling.normalizedQuestionHash();
        shortVerdict = ruling.shortVerdict();
        explanation = ruling.explanation();
        citations = ruling.citations().stream()
                .map(RulingCitationValue::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        exceptions = new ArrayList<>(ruling.exceptions());
        confidence = ruling.confidence().name();
        official = ruling.official();
        status = ruling.status().name();
        createdBy = ruling.createdBy();
        version = ruling.version();
        createdAt = ruling.createdAt();
        updatedAt = ruling.updatedAt();
    }

    ConfirmedRuling toDomain() {
        return new ConfirmedRuling(
                id,
                new RulingApplicability(editionId, documentVersionId, expansionIds, expansionSetHash),
                originalQuestion, normalizedQuestion, normalizedQuestionHash, shortVerdict, explanation,
                citations.stream().map(RulingCitationValue::toDomain).toList(), exceptions,
                RulingConfidence.valueOf(confidence), official, RulingStatus.valueOf(status),
                createdBy, version, createdAt, updatedAt);
    }
}

@Embeddable
class RulingCitationValue {

    @Column(name = "chunk_id", nullable = false)
    UUID chunkId;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "section_type", nullable = false)
    String sectionType;

    @Column(nullable = false)
    String heading;

    @Column(nullable = false)
    String excerpt;

    @Column(name = "page_from", nullable = false)
    int pageFrom;

    @Column(name = "page_to", nullable = false)
    int pageTo;

    protected RulingCitationValue() {}

    RulingCitationValue(RulingCitation citation) {
        chunkId = citation.chunkId();
        documentVersionId = citation.documentVersionId();
        sectionType = citation.sectionType();
        heading = citation.heading();
        excerpt = citation.excerpt();
        pageFrom = citation.pageFrom();
        pageTo = citation.pageTo();
    }

    RulingCitation toDomain() {
        return new RulingCitation(
                chunkId, documentVersionId, sectionType, heading, excerpt, pageFrom, pageTo);
    }
}
