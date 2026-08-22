package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded optional visual-page evidence enrichment for one planned teaching section. */
final class TeachingVisualEvidenceResolver {

    private static final Logger log = LoggerFactory.getLogger(TeachingVisualEvidenceResolver.class);

    private final AssistantReadTools tools;
    private final AuditedAgentInvocations invocations;
    private final VisualRulebookPageFacts visualFacts;
    private final VisualRulebookPageCatalogModel visualCatalog;

    TeachingVisualEvidenceResolver(
            AssistantReadTools tools,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookPageCatalogModel visualCatalog) {
        this.tools = tools;
        this.invocations = invocations;
        this.visualFacts = visualFacts;
        this.visualCatalog = visualCatalog;
    }

    List<RuleEvidence> resolve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved,
            UUID assistantRunId) {
        boolean visualPlaceholder = TeachingVisualEvidenceSelector.hasVisualPageEvidence(retrieved);
        boolean progressiveSourceBinding = ProgressiveVisualTeachingPlanPolicy.isProgressive(plan);
        if ((!visualPlaceholder && !progressiveSourceBinding) || planned.sourcePageNumbers().isEmpty()) return retrieved;
        try {
            List<RuleEvidence> pageEvidence = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    operationName("readRuleEvidencePages", planned.position()),
                    planned.sourcePageNumbers().size(),
                    "Planner-selected visual rulebook pages retrieved",
                    () -> tools.readRuleEvidencePages(
                            plan.documentVersionId(), new LinkedHashSet<>(planned.sourcePageNumbers()), true),
                    this::evidenceTokens);
            if (!pageEvidence.isEmpty()) {
                log.info(
                        "Teaching topic {} is bound to visual source pages {}",
                        planned.topicKey(),
                        planned.sourcePageNumbers());
                List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), pageEvidence, List.of());
                if (!TeachingVisualEvidenceSelector.hasVisualPageEvidence(enriched)
                        || !visualCatalog.available(plan.createdBy())) {
                    return enriched;
                }
                return enrichRequiredPageFacts(plan, planned, pageEvidence, enriched, assistantRunId);
            }
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            log.warn("Visual page-bound evidence read failed for topic {}: {}", planned.topicKey(), failure.getMessage());
        }
        return retrieved;
    }

    void prefetchRemaining(
            TeachingPlan plan,
            int completedSections,
            UUID assistantRunId) {
        if (!ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)
                || completedSections < 1
                || completedSections >= plan.sections().size()
                || !visualCatalog.available(plan.createdBy())) {
            return;
        }
        LinkedHashSet<Integer> requested = plan.sections().subList(completedSections, plan.sections().size()).stream()
                .flatMap(section -> section.sourcePageNumbers().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        visualFacts.find(plan.documentVersionId(), requested).stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .map(VisualRulebookPageFacts.PageFact::pageNumber)
                .forEach(requested::remove);
        if (requested.isEmpty()) return;
        try {
            Map<Integer, AssistantReadTools.RulePageImage> images = readProgressivePageImages(
                    plan, requested, completedSections + 1, assistantRunId);
            if (images.isEmpty()) return;
            List<PageImageInput> pageInputs = requested.stream()
                    .map(images::get)
                    .filter(java.util.Objects::nonNull)
                    .limit(VisualRulebookPageCatalogModel.MAX_PAGES_PER_REQUEST)
                    .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                    .toList();
            if (pageInputs.isEmpty()) return;
            var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                    pageInputs, plan.createdBy(), plan.gameTitle());
            var catalog = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    "prefetchProgressiveVisualPages|" + (completedSections + 1),
                    pageInputs.size() * 600,
                    progressivePrefetchSummary(plan.createdBy(), pageInputs.size()),
                    () -> visualCatalog.summarizeForTeaching(request),
                    result -> estimateTokens(result.toString()));
            List<VisualRulebookPageFacts.PageFact> interpreted = catalog.pages().stream()
                    .filter(summary -> requested.contains(summary.pageNumber()))
                    .map(VisualRulebookCatalogPolicy::teachingStartupFact)
                    .map(VisualRulebookCatalogPolicy::toPageFact)
                    .toList();
            if (!interpreted.isEmpty()) {
                persistInterpretedFacts(plan.documentVersionId(), interpreted);
                log.info(
                        "Progressive Teaching continuation stored visual facts for document {} pages {}",
                        plan.documentVersionId(),
                        interpreted.stream().map(VisualRulebookPageFacts.PageFact::pageNumber).toList());
            }
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            log.warn(
                    "Progressive Teaching visual prefetch failed for plan {}; section-level recovery remains available",
                    plan.id(),
                    failure);
        }
    }

    private Map<Integer, AssistantReadTools.RulePageImage> readProgressivePageImages(
            TeachingPlan plan,
            LinkedHashSet<Integer> requested,
            int firstRemainingPosition,
            UUID assistantRunId) {
        List<Integer> ordered = List.copyOf(requested);
        Map<Integer, AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        for (int start = 0; start < ordered.size(); start += 5) {
            int batchNumber = start / 5 + 1;
            Set<Integer> batch = new LinkedHashSet<>(ordered.subList(start, Math.min(start + 5, ordered.size())));
            List<RuleEvidence> evidence = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    "readProgressiveVisualPages|" + firstRemainingPosition + "|" + batchNumber,
                    batch.size(),
                    "Remaining source-bound rulebook page images retrieved",
                    () -> tools.readRuleEvidencePages(plan.documentVersionId(), batch, true),
                    this::evidenceTokens);
            evidence.stream()
                    .flatMap(source -> source.pageImages().stream())
                    .filter(image -> batch.contains(image.pageNumber()))
                    .forEach(image -> images.putIfAbsent(image.pageNumber(), image));
        }
        return images;
    }

    private String progressivePrefetchSummary(String owner, int pageCount) {
        return visualCatalog.teachingStartupExecutionIdentity(owner)
                .map(identity -> "Remaining " + pageCount + " Teaching page fact(s) interpreted via "
                        + identity.auditLabel())
                .orElse("Remaining " + pageCount + " Teaching page fact(s) interpreted");
    }

    private String requiredPageInterpretationSummary(TeachingPlan plan) {
        if (!ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)) {
            return "Required visual rulebook page interpreted for grounded teaching";
        }
        return visualCatalog.teachingStartupExecutionIdentity(plan.createdBy())
                .map(identity -> "Required Teaching page fact interpreted via " + identity.auditLabel())
                .orElse("Required Teaching page fact interpreted");
    }

    private List<RuleEvidence> enrichRequiredPageFacts(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> pageEvidence,
            List<RuleEvidence> enriched,
            UUID assistantRunId) {
        boolean progressive = ProgressiveVisualTeachingPlanPolicy.isProgressive(plan);
        Map<Integer, AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        pageEvidence.stream()
                .filter(TeachingVisualEvidenceSelector::isVisualPageEvidence)
                .flatMap(source -> source.pageImages().stream())
                .forEach(image -> images.putIfAbsent(image.pageNumber(), image));
        List<VisualRulebookPageFacts.PageFact> interpreted = new ArrayList<>();
        for (AssistantReadTools.RulePageImage image : images.values()) {
            try {
                var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                        List.of(new PageImageInput(image.pageNumber(), image.mediaType(), image.content())),
                        plan.createdBy(),
                        plan.gameTitle());
                var catalog = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        "inspectRequiredVisualPage|" + planned.position() + "|" + image.pageNumber(),
                        800,
                        requiredPageInterpretationSummary(plan),
                        () -> progressive
                                ? visualCatalog.summarizeForTeaching(request)
                                : visualCatalog.summarize(request),
                        result -> estimateTokens(result.toString()));
                interpreted.addAll(catalog.pages().stream()
                        .map(summary -> {
                            if (progressive) return VisualRulebookCatalogPolicy.teachingStartupFact(summary);
                            if (summary.ruleGroupInventoryComplete()) {
                                VisualRulebookCatalogPolicy.validateRuleGroupFactBindings(
                                        summary.ruleGroupIdentifiers(), summary.ruleGroupFacts());
                            }
                            return summary;
                        })
                        .map(VisualRulebookCatalogPolicy::toPageFact)
                        .toList());
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                log.warn(
                        "Required visual page interpretation failed for topic {} page {}: {}",
                        planned.topicKey(),
                        image.pageNumber(),
                        failure.getMessage());
            }
        }
        if (interpreted.isEmpty()) return enriched;
        persistInterpretedFacts(plan.documentVersionId(), interpreted);
        log.info(
                "Teaching topic {} added on-demand visual facts for pages {}",
                planned.topicKey(),
                interpreted.stream().map(VisualRulebookPageFacts.PageFact::pageNumber).toList());
        return enrichPageFacts(plan.documentVersionId(), pageEvidence, interpreted);
    }

    private List<RuleEvidence> enrichPageFacts(
            UUID documentVersionId,
            List<RuleEvidence> evidence,
            List<VisualRulebookPageFacts.PageFact> supplementalFacts) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toUnmodifiableSet());
        if (pages.isEmpty()) return evidence;
        Map<Integer, String> factsByPage = Stream.concat(
                        visualFacts.find(documentVersionId, pages).stream(), supplementalFacts.stream())
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .collect(Collectors.toUnmodifiableMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::factualSummary,
                        (existing, supplied) -> supplied));
        if (factsByPage.isEmpty()) return evidence;
        return evidence.stream()
                .map(source -> {
                    String facts = source.pageFrom() == source.pageTo() ? factsByPage.get(source.pageFrom()) : null;
                    if (facts == null || !TeachingVisualEvidenceSelector.isVisualPageEvidence(source)) return source;
                    return new RuleEvidence(
                            source.chunkId(),
                            source.documentVersionId(),
                            source.sectionType(),
                            source.heading(),
                            facts,
                            source.pageFrom(),
                            source.pageTo(),
                            source.pageImages(),
                            RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
                })
                .toList();
    }

    private void persistInterpretedFacts(
            UUID documentVersionId, List<VisualRulebookPageFacts.PageFact> interpreted) {
        if (interpreted.stream().anyMatch(fact -> !VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(fact))) {
            throw new IllegalArgumentException("teaching visual page fact does not contain a reusable complete rule ledger");
        }
        Set<Integer> pages = interpreted.stream()
                .map(VisualRulebookPageFacts.PageFact::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, VisualRulebookPageFacts.PageFact> existing = visualFacts.find(documentVersionId, pages).stream()
                .collect(Collectors.toMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        java.util.function.Function.identity(),
                        (first, ignored) -> first));
        List<VisualRulebookPageFacts.PageFact> durable = interpreted.stream()
                .map(observation -> {
                    VisualRulebookPageFacts.PageFact prior = existing.get(observation.pageNumber());
                    return prior == null
                            ? observation
                            : VisualRulebookCatalogPolicy.mergePersistedPageFact(prior, observation);
                })
                .toList();
        visualFacts.merge(documentVersionId, durable);
    }

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
