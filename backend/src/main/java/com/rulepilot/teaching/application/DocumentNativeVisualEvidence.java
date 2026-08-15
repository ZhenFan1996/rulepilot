package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.NativeVisualEvidence;
import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentNativeVisualEvidence implements NativeVisualEvidence {

    private final AssistantReadTools readTools;
    private final DocumentPageImages pageImages;
    private final DocumentPageImageCropper cropper;
    private final VisualRulebookPageFacts visualFacts;

    public DocumentNativeVisualEvidence(
            AssistantReadTools readTools,
            DocumentPageImages pageImages,
            DocumentPageImageCropper cropper,
            VisualRulebookPageFacts visualFacts) {
        this.readTools = readTools;
        this.pageImages = pageImages;
        this.cropper = cropper;
        this.visualFacts = visualFacts;
    }

    @Override
    public Optional<VisualPage> readPage(UUID documentVersionId, UUID evidenceId, int pageNumber) {
        if (!allowsPage(documentVersionId, evidenceId, pageNumber)) return Optional.empty();
        return page(documentVersionId, pageNumber).map(image -> new VisualPage(
                evidenceId, pageNumber, image.mediaType(), image.content(), image.width(), image.height()));
    }

    @Override
    public Optional<VisualCrop> cropPage(
            UUID documentVersionId,
            UUID evidenceId,
            int pageNumber,
            int x,
            int y,
            int width,
            int height) {
        if (!allowsPage(documentVersionId, evidenceId, pageNumber)) return Optional.empty();
        return page(documentVersionId, pageNumber).map(image -> new VisualCrop(
                evidenceId,
                pageNumber,
                "image/jpeg",
                cropper.crop(image, x, y, width, height, 0),
                x,
                y,
                width,
                height,
                Math.max(1, image.width() * width / 1_000),
                Math.max(1, image.height() * height / 1_000)));
    }

    @Override
    public List<VisualPageFact> readPageFacts(UUID documentVersionId, UUID evidenceId, int pageNumber) {
        if (!allowsPage(documentVersionId, evidenceId, pageNumber)) return List.of();
        return visualFacts.find(documentVersionId, Set.of(pageNumber)).stream()
                .filter(fact -> fact.pageNumber() == pageNumber)
                .filter(fact -> fact.schemaVersion() == VisualRulebookPageFacts.PageFact.CURRENT_SCHEMA_VERSION)
                .map(fact -> new VisualPageFact(
                        fact.pageNumber(),
                        fact.printedTerms(),
                        fact.factualSummary(),
                        fact.visualAnchors().stream()
                                .map(anchor -> new VisualAnchor(
                                        anchor.kind(), anchor.label(), anchor.visibleDescription(),
                                        anchor.x(), anchor.y(), anchor.width(), anchor.height()))
                                .toList(),
                        fact.iconOccurrences().stream()
                                .map(icon -> new VisualIcon(
                                        icon.name(), icon.visualDescription(), icon.meaningStatus().name(),
                                        icon.evidenceText(), icon.x(), icon.y(), icon.width(), icon.height()))
                                .toList()))
                .toList();
    }

    private boolean allowsPage(UUID documentVersionId, UUID evidenceId, int pageNumber) {
        if (documentVersionId == null || evidenceId == null || pageNumber < 1) return false;
        List<RuleEvidence> evidence = readTools.readRuleEvidenceIds(documentVersionId, Set.of(evidenceId));
        return evidence.stream().anyMatch(source -> source.chunkId().equals(evidenceId)
                && source.documentVersionId().equals(documentVersionId)
                && pageNumber >= source.pageFrom()
                && pageNumber <= source.pageTo());
    }

    private Optional<DocumentPageImages.PageImage> page(UUID documentVersionId, int pageNumber) {
        return pageImages.read(documentVersionId, Set.of(pageNumber)).stream()
                .filter(image -> image.pageNumber() == pageNumber)
                .findFirst();
    }
}
