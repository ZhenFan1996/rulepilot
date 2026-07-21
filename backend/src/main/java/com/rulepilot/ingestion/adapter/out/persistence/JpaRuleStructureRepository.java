package com.rulepilot.ingestion.adapter.out.persistence;

import com.rulepilot.ingestion.application.RuleStructureRepository;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.CoverageLedgerEntry;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.RuleEvidenceItem;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.TerminologyCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaRuleStructureRepository implements RuleStructureRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void replace(
            UUID documentVersionId,
            List<DetectedRuleSection> sections,
            List<DetectedRuleChunk> chunks,
            RulebookUnderstanding understanding) {
        entityManager
                .createQuery("delete from RulebookCoverageLedgerEntity l where l.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from RulebookInventoryItemEntity i where i.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from RulebookTerminologyEntity t where t.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from RulebookPageBlockEntity b where b.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
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
        }
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            entityManager.persist(new RuleChunkEntity(
                    UUID.randomUUID(),
                    documentVersionId,
                    chunk.sectionType(),
                    chunk.heading(),
                    chunk.content(),
                    chunk.pageNumber(),
                    chunk.pageNumber(),
                    index,
                    now));
        }
        persistUnderstanding(documentVersionId, understanding, now);
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

    @Override
    public Optional<RulebookUnderstanding> findUnderstanding(UUID documentVersionId) {
        List<RulebookPageBlockEntity> blocks = entityManager
                .createQuery(
                        "select b from RulebookPageBlockEntity b where b.documentVersionId = :versionId order by b.pageNumber, b.blockIndex",
                        RulebookPageBlockEntity.class)
                .setParameter("versionId", documentVersionId)
                .getResultList();
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, RulebookPageBlockEntity> byId = new HashMap<>();
        blocks.forEach(block -> byId.put(block.id, block));
        List<PageBlock> pageBlocks = blocks.stream().map(RulebookPageBlockEntity::toView).toList();
        List<TerminologyCandidate> terms = entityManager
                .createQuery(
                        "select t from RulebookTerminologyEntity t where t.documentVersionId = :versionId order by t.normalizedTerm",
                        RulebookTerminologyEntity.class)
                .setParameter("versionId", documentVersionId)
                .getResultList()
                .stream()
                .map(term -> term.toView(byId.get(term.evidenceBlockId)))
                .toList();
        List<RulebookInventoryItemEntity> inventoryEntities = entityManager
                .createQuery(
                        "select i from RulebookInventoryItemEntity i where i.documentVersionId = :versionId order by i.pageNumber, i.blockIndex",
                        RulebookInventoryItemEntity.class)
                .setParameter("versionId", documentVersionId)
                .getResultList();
        List<RuleEvidenceItem> inventory = inventoryEntities.stream().map(RulebookInventoryItemEntity::toView).toList();
        Map<UUID, String> inventoryKeys = new HashMap<>();
        inventoryEntities.forEach(item -> inventoryKeys.put(item.id, item.inventoryKey));
        List<CoverageLedgerEntry> ledger = entityManager
                .createQuery(
                        "select l from RulebookCoverageLedgerEntity l where l.documentVersionId = :versionId order by l.inventoryItemId",
                        RulebookCoverageLedgerEntity.class)
                .setParameter("versionId", documentVersionId)
                .getResultList()
                .stream()
                .map(entry -> entry.toView(inventoryKeys.get(entry.inventoryItemId)))
                .toList();
        return Optional.of(new RulebookUnderstanding(pageBlocks, terms, inventory, ledger));
    }

    private void persistUnderstanding(UUID documentVersionId, RulebookUnderstanding understanding, Instant now) {
        Map<String, UUID> blockIds = new HashMap<>();
        for (PageBlock block : understanding.pageBlocks()) {
            UUID id = UUID.randomUUID();
            blockIds.put(blockKey(block.pageNumber(), block.blockIndex()), id);
            entityManager.persist(new RulebookPageBlockEntity(id, documentVersionId, block, now));
        }
        for (TerminologyCandidate term : understanding.terminology()) {
            entityManager.persist(new RulebookTerminologyEntity(
                    UUID.randomUUID(), documentVersionId, term, requiredBlockId(blockIds, term.pageNumber(), term.blockIndex()), now));
        }
        Map<String, UUID> inventoryIds = new HashMap<>();
        for (RuleEvidenceItem item : understanding.inventory()) {
            UUID id = UUID.randomUUID();
            inventoryIds.put(item.key(), id);
            entityManager.persist(new RulebookInventoryItemEntity(
                    id, documentVersionId, item, requiredBlockId(blockIds, item.pageNumber(), item.blockIndex()), now));
        }
        for (CoverageLedgerEntry entry : understanding.coverageLedger()) {
            UUID inventoryId = inventoryIds.get(entry.inventoryKey());
            if (inventoryId == null) {
                throw new IllegalArgumentException("coverage ledger references an unknown inventory item");
            }
            entityManager.persist(new RulebookCoverageLedgerEntity(
                    UUID.randomUUID(), documentVersionId, inventoryId, entry, now));
        }
    }

    private UUID requiredBlockId(Map<String, UUID> blockIds, int pageNumber, int blockIndex) {
        UUID id = blockIds.get(blockKey(pageNumber, blockIndex));
        if (id == null) {
            throw new IllegalArgumentException("understanding references an unknown page block");
        }
        return id;
    }

    private String blockKey(int pageNumber, int blockIndex) {
        return pageNumber + ":" + blockIndex;
    }
}

@Entity(name = "RulebookPageBlockEntity")
@Table(name = "rulebook_page_block")
class RulebookPageBlockEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(name = "block_index", nullable = false)
    int blockIndex;

    @Column(name = "reading_order", nullable = false)
    int readingOrder;

    @Column(nullable = false)
    String role;

    @Column(nullable = false, columnDefinition = "text")
    String content;

    @Column(name = "x", nullable = false)
    int x;

    @Column(name = "y", nullable = false)
    int y;

    @Column(name = "width", nullable = false)
    int width;

    @Column(name = "height", nullable = false)
    int height;

    @Column(name = "heading_block_index")
    Integer headingBlockIndex;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RulebookPageBlockEntity() {}

    RulebookPageBlockEntity(UUID id, UUID documentVersionId, PageBlock block, Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.pageNumber = block.pageNumber();
        this.blockIndex = block.blockIndex();
        this.readingOrder = block.readingOrder();
        this.role = block.role().name();
        this.content = block.text();
        this.x = block.rectangle().x();
        this.y = block.rectangle().y();
        this.width = block.rectangle().width();
        this.height = block.rectangle().height();
        this.headingBlockIndex = block.headingBlockIndex();
        this.createdAt = createdAt;
    }

    PageBlock toView() {
        return new PageBlock(
                pageNumber,
                blockIndex,
                readingOrder,
                RulebookUnderstanding.BlockRole.valueOf(role),
                content,
                new RulebookUnderstanding.Rectangle(x, y, width, height),
                headingBlockIndex);
    }
}

@Entity(name = "RulebookTerminologyEntity")
@Table(name = "rulebook_terminology")
class RulebookTerminologyEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(nullable = false)
    String term;

    @Column(name = "normalized_term", nullable = false)
    String normalizedTerm;

    @Column(name = "evidence_block_id", nullable = false)
    UUID evidenceBlockId;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RulebookTerminologyEntity() {}

    RulebookTerminologyEntity(
            UUID id, UUID documentVersionId, TerminologyCandidate term, UUID evidenceBlockId, Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.term = term.term();
        this.normalizedTerm = term.normalizedTerm();
        this.evidenceBlockId = evidenceBlockId;
        this.createdAt = createdAt;
    }

    TerminologyCandidate toView(RulebookPageBlockEntity evidence) {
        if (evidence == null) {
            throw new IllegalStateException("terminology evidence block does not exist");
        }
        return new TerminologyCandidate(term, normalizedTerm, evidence.pageNumber, evidence.blockIndex);
    }
}

@Entity(name = "RulebookInventoryItemEntity")
@Table(name = "rulebook_inventory_item")
class RulebookInventoryItemEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "inventory_key", nullable = false)
    String inventoryKey;

    @Column(nullable = false)
    String kind;

    @Column(nullable = false)
    String label;

    @Column(name = "evidence_block_id", nullable = false)
    UUID evidenceBlockId;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(name = "block_index", nullable = false)
    int blockIndex;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RulebookInventoryItemEntity() {}

    RulebookInventoryItemEntity(
            UUID id, UUID documentVersionId, RuleEvidenceItem item, UUID evidenceBlockId, Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.inventoryKey = item.key();
        this.kind = item.kind();
        this.label = item.label();
        this.evidenceBlockId = evidenceBlockId;
        this.pageNumber = item.pageNumber();
        this.blockIndex = item.blockIndex();
        this.createdAt = createdAt;
    }

    RuleEvidenceItem toView() {
        return new RuleEvidenceItem(inventoryKey, kind, label, pageNumber, blockIndex);
    }
}

@Entity(name = "RulebookCoverageLedgerEntity")
@Table(name = "rulebook_coverage_ledger")
class RulebookCoverageLedgerEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "inventory_item_id", nullable = false)
    UUID inventoryItemId;

    @Column(nullable = false)
    String state;

    @Column(name = "exclusion_reason")
    String exclusionReason;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected RulebookCoverageLedgerEntity() {}

    RulebookCoverageLedgerEntity(
            UUID id, UUID documentVersionId, UUID inventoryItemId, CoverageLedgerEntry entry, Instant createdAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.inventoryItemId = inventoryItemId;
        this.state = entry.state().name();
        this.exclusionReason = entry.exclusionReason();
        this.createdAt = createdAt;
    }

    CoverageLedgerEntry toView(String inventoryKey) {
        if (inventoryKey == null) {
            throw new IllegalStateException("coverage ledger inventory item does not exist");
        }
        return new CoverageLedgerEntry(
                inventoryKey, RulebookUnderstanding.CoverageState.valueOf(state), exclusionReason);
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
