package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.TeachingLessonModel.VisualInput;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.visualaid.VisualRegionCatalog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Loads source-owned candidates; the chapter Agent sees their pixels and chooses their placement. */
@Service
@Profile("!test")
public class VisualLessonEnricher {
    private final DocumentPageImages pages;
    private final DocumentPageImageCropper cropper;
    private final VisualRegionCatalog catalog;
    private final RuntimeModelConfiguration models;

    public VisualLessonEnricher(DocumentPageImages pages, VisualRegionCatalog catalog,
            DocumentPageImageCropper cropper, RuntimeModelConfiguration models) {
        this.pages = pages;
        this.catalog = catalog;
        this.cropper = cropper;
        this.models = models;
    }

    boolean supportsVisualEvidence(String owner) { return models.supportsVision(Role.TEACHING, owner); }

    public List<VisualInput> prepare(TeachingPlan plan, TeachingPlan.PlannedSection section,
            List<RuleEvidence> evidence) {
        var result = new ArrayList<VisualInput>();
        var requested = java.util.stream.Stream.concat(section.sourcePageNumbers().stream(),
                section.visualSourcePageNumbers().stream()).distinct().sorted().toList();
        for (int offset = 0; offset < requested.size(); offset += DocumentPageImages.MAX_PAGES_PER_READ) {
            var batch = java.util.Set.copyOf(requested.subList(offset,
                    Math.min(offset + DocumentPageImages.MAX_PAGES_PER_READ, requested.size())));
            try {
                var images = pages.read(plan.documentVersionId(), batch);
                for (var region : catalog.find(plan.documentVersionId(), batch)) {
                    var page = images.stream().filter(image -> image.pageNumber() == region.pageNumber()).findFirst();
                    var ids = evidence.stream().filter(source -> source.documentVersionId().equals(plan.documentVersionId())
                                    && source.pageFrom() <= region.pageNumber() && source.pageTo() >= region.pageNumber())
                            .map(RuleEvidence::chunkId).distinct().toList();
                    if (page.isEmpty() || ids.isEmpty() || !batch.contains(region.pageNumber())) continue;
                    var focus = new VisualFocus(region.pageNumber(), "Source illustration",
                            region.x(), region.y(), region.width(), region.height());
                    var pixels = cropper.crop(page.get(), region.x(), region.y(), region.width(), region.height());
                    // Immutable within this section request, including any same-Agent correction.
                    result.add(new VisualInput("I" + (result.size() + 1), focus, ids, page.get(), pixels));
                }
            } catch (com.rulepilot.assistant.AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                org.slf4j.LoggerFactory.getLogger(VisualLessonEnricher.class)
                        .warn("Source illustrations unavailable for pages {}; retaining earlier candidates", batch, unavailable);
            }
        }
        return List.copyOf(result);
    }
}
