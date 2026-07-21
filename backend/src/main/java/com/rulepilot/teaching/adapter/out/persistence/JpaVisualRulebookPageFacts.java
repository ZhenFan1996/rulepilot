package com.rulepilot.teaching.adapter.out.persistence;

import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JpaVisualRulebookPageFacts implements VisualRulebookPageFacts {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void replace(UUID documentVersionId, List<PageFact> pages) {
        if (documentVersionId == null || pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("visual page facts are required");
        }
        entityManager.createQuery("delete from VisualRulebookPageFactEntity p where p.documentVersionId = :versionId")
                .setParameter("versionId", documentVersionId)
                .executeUpdate();
        pages.forEach(page -> entityManager.persist(new VisualRulebookPageFactEntity(documentVersionId, page)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()) return List.of();
        return entityManager.createQuery(
                        "select p from VisualRulebookPageFactEntity p "
                                + "where p.documentVersionId = :versionId and p.pageNumber in :pageNumbers "
                                + "order by p.pageNumber",
                        VisualRulebookPageFactEntity.class)
                .setParameter("versionId", documentVersionId)
                .setParameter("pageNumbers", pageNumbers)
                .getResultList()
                .stream()
                .map(VisualRulebookPageFactEntity::toDomain)
                .toList();
    }
}

@Entity(name = "VisualRulebookPageFactEntity")
@Table(name = "visual_rulebook_page_fact")
class VisualRulebookPageFactEntity {

    @Id
    UUID id;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @Column(name = "page_number", nullable = false)
    int pageNumber;

    @Column(name = "printed_terms", nullable = false, columnDefinition = "text")
    String printedTerms;

    @Column(name = "factual_summary", nullable = false, columnDefinition = "text")
    String factualSummary;

    @Column(nullable = false, columnDefinition = "text")
    String keywords;

    protected VisualRulebookPageFactEntity() {}

    VisualRulebookPageFactEntity(UUID documentVersionId, PageFact page) {
        this.id = UUID.randomUUID();
        this.documentVersionId = documentVersionId;
        this.pageNumber = page.pageNumber();
        this.printedTerms = page.printedTerms();
        this.factualSummary = page.factualSummary();
        this.keywords = String.join("\n", page.keywords());
    }

    PageFact toDomain() {
        return new PageFact(pageNumber, printedTerms, factualSummary, keywords.lines().filter(value -> !value.isBlank()).toList());
    }
}
