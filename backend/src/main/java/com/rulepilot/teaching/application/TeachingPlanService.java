package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentTeachingPreparation;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeachingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TeachingPlanService.class);
    private static final int MAX_PAGE_CATALOG_CHARACTERS = 3_200;
    private static final int MAX_OUTLINE_PAGE_IMAGES = 4;
    private static final String VISUAL_PAGE_CATALOG =
            "页面文字无法提取；请依据随附的规则书页面图像理解此页内容。";
    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final DocumentVersionScopeLookup documentScopes;
    private final DocumentTeachingPreparation documentPreparation;
    private final TeachingOutlineModel outlines;
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentVersionScopeLookup documentScopes,
            DocumentTeachingPreparation documentPreparation,
            TeachingOutlineModel outlines,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.documentScopes = documentScopes;
        this.documentPreparation = documentPreparation;
        this.outlines = outlines;
        this.plans = plans;
        this.repository = repository;
    }

    @Transactional
    public TeachingPlan create(
            UUID documentVersionId, int playerCount, int beginnerCount, int durationMinutes, String createdBy) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        var documentPages = documents.pages(documentVersionId);
        var pages = documentPages.stream()
                .map(page -> new PageInput(
                        page.pageNumber(), page.text() == null || page.text().isBlank()
                                ? VISUAL_PAGE_CATALOG
                                : boundedPageText(page.text())))
                .toList();
        var outlineImages = pageImages.read(
                        documentVersionId,
                        representativePageNumbers(documentPages))
                .stream()
                .map(image -> new TeachingOutlineModel.PageImageInput(
                        image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        var outline = outlines.organize(new OutlineRequest(playerCount, beginnerCount, durationMinutes, pages, outlineImages));
        documentPreparation.prepare(documentVersionId, createdBy, outline.gameTitle());
        log.info(
                "Teaching outline generated for documentVersionId={}: gameTitle={}, topics={}",
                documentVersionId,
                outline.gameTitle(),
                outline.topics().stream()
                        .map(topic -> topic.key() + " visual=" + topic.visualEvidenceRecommended()
                                + " tags=" + topic.coverageTags() + " queries=" + topic.retrievalQueries())
                        .toList());
        return repository.save(plans.create(
                documentVersionId,
                playerCount,
                beginnerCount,
                durationMinutes,
                createdBy,
                outline));
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> latest(UUID documentVersionId, String createdBy) {
        return repository.findLatest(documentVersionId, createdBy);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> find(UUID planId) {
        return repository.findById(planId);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> findOwned(UUID planId, String createdBy) {
        return repository.findByIdAndCreatedBy(planId, createdBy);
    }

    @Transactional(readOnly = true)
    public List<TeachingPlan> listOwned(String createdBy) {
        return repository.findAllByCreatedBy(createdBy);
    }

    private String boundedPageText(String text) {
        String value = text.strip();
        return value.length() <= MAX_PAGE_CATALOG_CHARACTERS
                ? value
                : value.substring(0, MAX_PAGE_CATALOG_CHARACTERS) + "…";
    }

    private Set<Integer> representativePageNumbers(List<DocumentProcessing.PageView> pages) {
        if (pages.size() <= MAX_OUTLINE_PAGE_IMAGES) {
            return pages.stream().map(DocumentProcessing.PageView::pageNumber).collect(java.util.stream.Collectors.toSet());
        }
        Set<Integer> selected = new LinkedHashSet<>();
        for (int index = 0; index < MAX_OUTLINE_PAGE_IMAGES; index++) {
            int pageIndex = (int) Math.round((double) index * (pages.size() - 1) / (MAX_OUTLINE_PAGE_IMAGES - 1));
            selected.add(pages.get(pageIndex).pageNumber());
        }
        return Set.copyOf(selected);
    }
}
