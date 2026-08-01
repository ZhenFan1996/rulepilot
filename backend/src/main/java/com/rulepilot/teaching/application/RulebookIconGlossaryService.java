package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Document-version icon inventory and player-facing quick-reference projection. */
@Service
@Profile("!test")
public class RulebookIconGlossaryService {

    private final TeachingPlanRepository plans;
    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final DocumentPageImageCropper cropper;
    private final VisualRulebookPageFacts pageFacts;
    private final VisualRulebookCataloger cataloger;
    private final AssistantRuns runs;

    RulebookIconGlossaryService(
            TeachingPlanRepository plans,
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentPageImageCropper cropper,
            VisualRulebookPageFacts pageFacts,
            VisualRulebookCataloger cataloger,
            AssistantRuns runs) {
        this.plans = plans;
        this.documents = documents;
        this.pageImages = pageImages;
        this.cropper = cropper;
        this.pageFacts = pageFacts;
        this.cataloger = cataloger;
        this.runs = runs;
    }

    public GlossaryView extract(UUID teachingPlanId, String owner, UUID assistantRunId) {
        TeachingPlan plan = requireOwned(teachingPlanId, owner);
        List<DocumentProcessing.PageView> pages = documents.pages(plan.documentVersionId());
        if (!cataloger.available(owner)) return view(plan, pages, false);
        cataloger.catalogAllIconPages(
                plan.documentVersionId(), pages, plan.gameTitle(), owner, assistantRunId);
        return view(plan, pages, true);
    }

    @Transactional(readOnly = true)
    public GlossaryView viewOwned(UUID teachingPlanId, String owner) {
        TeachingPlan plan = requireOwned(teachingPlanId, owner);
        return view(plan, documents.pages(plan.documentVersionId()), cataloger.available(owner));
    }

    @Transactional(readOnly = true)
    public GlossaryView viewPublic(UUID teachingPlanId) {
        TeachingPlan plan = plans.findById(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        return view(plan, documents.pages(plan.documentVersionId()), cataloger.available(plan.createdBy()));
    }

    public IconCrop cropOwned(UUID teachingPlanId, UUID occurrenceId, String owner) {
        TeachingPlan plan = requireOwned(teachingPlanId, owner);
        return crop(plan, occurrenceId);
    }

    public IconCrop cropPublic(UUID teachingPlanId, UUID occurrenceId) {
        TeachingPlan plan = plans.findById(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        return crop(plan, occurrenceId);
    }

    private GlossaryView view(
            TeachingPlan plan, List<DocumentProcessing.PageView> pages, boolean modelAvailable) {
        Set<Integer> pageNumbers = pages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, String> sourceTextByPage = pages.stream().collect(java.util.stream.Collectors.toMap(
                DocumentProcessing.PageView::pageNumber,
                DocumentProcessing.PageView::text,
                (first, later) -> later));
        List<PageFact> facts = pageFacts.find(plan.documentVersionId(), pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .map(fact -> withGroundedIconEvidence(fact, sourceTextByPage.get(fact.pageNumber())))
                .toList();
        var projection = RulebookIconGlossaryPolicy.project(plan.documentVersionId(), facts);
        int completePages = (int) facts.stream().filter(PageFact::iconInventoryComplete).count();
        boolean active = runs.findLatestOwned(AssistantRunMode.VISUAL_ENRICHMENT, plan.id(), plan.createdBy())
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal())
                .isPresent();
        GlossaryStatus status = determineStatus(modelAvailable, active, completePages, facts.size(), pages.size());

        Set<GlossaryWarning> warnings = new LinkedHashSet<>();
        if (!projection.conflictingGroupKeys().isEmpty()) warnings.add(GlossaryWarning.CONFLICTING_EXPLANATIONS);
        if (projection.groups().stream().anyMatch(group -> group.meaningStatus() == IconMeaningStatus.UNEXPLAINED)) {
            warnings.add(GlossaryWarning.UNEXPLAINED_ICONS);
        }
        if (completePages < pages.size() && !pages.isEmpty()) warnings.add(GlossaryWarning.INCOMPLETE_PAGE_SCAN);

        return new GlossaryView(
                status,
                pages.size(),
                facts.size(),
                completePages,
                projection.groups().stream()
                        .map(group -> new IconEntry(
                                group.id(),
                                group.name(),
                                group.visualDescription(),
                                group.explanation(),
                                group.evidenceText(),
                                group.meaningStatus(),
                                group.representativeOccurrenceId(),
                                group.occurrences().stream()
                                        .map(occurrence -> new IconOccurrenceView(
                                                occurrence.id(),
                                                occurrence.pageNumber(),
                                                occurrence.x(),
                                                occurrence.y(),
                                                occurrence.width(),
                                                occurrence.height()))
                                        .toList()))
                        .toList(),
                List.copyOf(warnings));
    }

    static GlossaryStatus determineStatus(
            boolean modelAvailable, boolean active, int completePages, int inspectedPages, int totalPages) {
        if (!modelAvailable) return GlossaryStatus.UNAVAILABLE;
        // A page scan can finish before dense tile audits and crop reviews. The public projection must not claim a
        // terminal inventory while the owning visual run can still add or reject icon groups.
        if (active) return GlossaryStatus.GENERATING;
        if (completePages == totalPages && inspectedPages == totalPages) return GlossaryStatus.READY;
        if (inspectedPages == 0) return GlossaryStatus.NOT_STARTED;
        return GlossaryStatus.PARTIAL;
    }

    private static PageFact withGroundedIconEvidence(PageFact fact, String sourcePageText) {
        return new PageFact(
                fact.pageNumber(),
                fact.printedTerms(),
                fact.factualSummary(),
                fact.keywords(),
                fact.visualAnchors(),
                IconEvidencePolicy.sanitize(fact.iconOccurrences(), sourcePageText),
                fact.iconInventoryComplete(),
                fact.schemaVersion());
    }

    private IconCrop crop(TeachingPlan plan, UUID occurrenceId) {
        GlossaryView glossary = view(plan, documents.pages(plan.documentVersionId()), cataloger.available(plan.createdBy()));
        IconOccurrenceView occurrence = glossary.icons().stream()
                .flatMap(icon -> icon.occurrences().stream())
                .filter(candidate -> candidate.id().equals(occurrenceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("rulebook icon does not exist"));
        DocumentPageImages.PageImage page = pageImages.read(plan.documentVersionId(), Set.of(occurrence.pageNumber()))
                .stream()
                .filter(candidate -> candidate.pageNumber() == occurrence.pageNumber())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("rulebook icon page does not exist"));
        return new IconCrop(
                "image/jpeg",
                cropper.crop(
                        page,
                        occurrence.x(),
                        occurrence.y(),
                        occurrence.width(),
                        occurrence.height(),
                        12));
    }

    private TeachingPlan requireOwned(UUID teachingPlanId, String owner) {
        return plans.findByIdAndCreatedBy(teachingPlanId, owner)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
    }

    public enum GlossaryStatus {
        NOT_STARTED,
        GENERATING,
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public enum GlossaryWarning {
        INCOMPLETE_PAGE_SCAN,
        UNEXPLAINED_ICONS,
        CONFLICTING_EXPLANATIONS
    }

    public record GlossaryView(
            GlossaryStatus status,
            int totalPages,
            int inspectedPages,
            int completePages,
            List<IconEntry> icons,
            List<GlossaryWarning> warnings) {}

    public record IconEntry(
            UUID id,
            String name,
            String visualDescription,
            String explanation,
            String evidenceText,
            IconMeaningStatus meaningStatus,
            UUID representativeOccurrenceId,
            List<IconOccurrenceView> occurrences) {}

    public record IconOccurrenceView(
            UUID id, int pageNumber, int x, int y, int width, int height) {}

    public record IconCrop(String mediaType, byte[] content) {
        public IconCrop {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
