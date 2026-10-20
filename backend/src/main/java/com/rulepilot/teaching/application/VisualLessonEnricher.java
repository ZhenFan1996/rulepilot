package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.TeachingLessonModel.VisualInput;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads source illustrations before writing; the chapter writer decides their placement. */
@Service
@Profile("!test")
public class VisualLessonEnricher {
    private final DocumentPageImages pages;
    private final com.rulepilot.visualaid.VisualRegionCatalog catalog;
    private final VisualRegionLocator locator;
    private final AgentExecutionControl execution;
    private final Duration observationTimeout;
    private final Duration compositionAllowance;

    public VisualLessonEnricher(DocumentPageImages pages, com.rulepilot.visualaid.VisualRegionCatalog catalog,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            AgentExecutionControl execution,
            @Value("${rulepilot.visual.compatibility-workflow-timeout:PT10M}") Duration observationTimeout,
            @Value("${rulepilot.models.request-timeout:PT2M}") Duration compositionAllowance) {
        this.pages = pages;
        this.catalog = catalog;
        this.locator = locator;
        this.execution = execution;
        this.observationTimeout = observationTimeout;
        this.compositionAllowance = compositionAllowance;
    }

    static int estimatedTeachingRunModelCalls(TeachingPlan plan) {
        if (plan == null) return 0;
        return Math.toIntExact(plan.sections().stream().filter(TeachingPlan.PlannedSection::visualEvidenceRecommended)
                .mapToLong(section -> (sourcePages(section).size() + DocumentPageImages.MAX_PAGES_PER_READ - 1L)
                        / DocumentPageImages.MAX_PAGES_PER_READ).sum());
    }

    boolean supportsVisualEvidence(String owner) { return locator.supportsVisualEvidence(owner); }

    public List<VisualInput> prepare(TeachingPlan plan, TeachingPlan.PlannedSection section,
            List<RuleEvidence> evidence, UUID runId) {
        Instant deadline = Instant.now().plus(observationTimeout);
        if (runId != null) {
            Instant reserved = execution.budget(runId).deadlineAt().minus(compositionAllowance);
            if (reserved.isBefore(deadline)) deadline = reserved;
        }
        var result = new ArrayList<VisualInput>();
        List<Integer> requested = sourcePages(section);
        for (int offset = 0; offset < requested.size(); offset += DocumentPageImages.MAX_PAGES_PER_READ) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) break;
            var batch = java.util.Set.copyOf(requested.subList(offset,
                    Math.min(offset + DocumentPageImages.MAX_PAGES_PER_READ, requested.size())));
            List<com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus> observed;
            try {
                var images = pages.read(plan.documentVersionId(), batch);
                if (images.isEmpty()) continue;
                var available = new java.util.LinkedHashMap<String, com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus>();
                for (var region : catalog.find(plan.documentVersionId(), batch)) {
                    var focus = new com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus(region.pageNumber(), "Source image",
                            region.x(), region.y(), region.width(), region.height());
                    available.put(focus.imageId(plan.documentVersionId()), focus);
                }
                if (available.isEmpty()) continue;
                observed = locator.observe(new VisualRegionLocator.Request(
                        section.title() + "\n" + section.objective(), images, available, plan.createdBy(), runId, plan.outputLanguage()), remaining);
            } catch (com.rulepilot.assistant.AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(VisualLessonEnricher.class)
                        .warn("Optional source illustrations unavailable for pages {}; retaining earlier observations", batch, unavailable);
                continue;
            }
            for (var focus : observed) {
                var ids = evidence.stream().filter(source -> source.documentVersionId().equals(plan.documentVersionId())
                                && source.pageFrom() <= focus.pageNumber() && source.pageTo() >= focus.pageNumber())
                        .map(RuleEvidence::chunkId).distinct().toList();
                if (!ids.isEmpty() && batch.contains(focus.pageNumber())) {
                    result.add(new VisualInput(focus.imageId(plan.documentVersionId()), focus, ids));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Integer> sourcePages(TeachingPlan.PlannedSection section) {
        return java.util.stream.Stream.concat(section.sourcePageNumbers().stream(), section.visualSourcePageNumbers().stream())
                .distinct().sorted().toList();
    }
}
