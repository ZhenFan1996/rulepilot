package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded optional visual-page evidence enrichment for one planned teaching section. */
final class TeachingVisualEvidenceResolver {

    private static final Logger log = LoggerFactory.getLogger(TeachingVisualEvidenceResolver.class);
    private static final int MAX_PAGES_PER_TOOL_READ = 5;
    private static final int MAX_REQUIRED_PAGE_INTERPRETATION_ATTEMPTS = 2;

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

    Resolution resolve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved,
            UUID assistantRunId) {
        if (!requiresPageRead(plan, planned, retrieved)) return Resolution.unread(retrieved);
        Map<UUID, RuleEvidence> pageEvidenceById = new LinkedHashMap<>();
        List<Set<Integer>> batches = pageReadBatches(planned.sourcePageNumbers());
        int toolCalls = 0;
        boolean completeRead = true;
        for (Set<Integer> batch : batches) {
            toolCalls++;
            try {
                List<RuleEvidence> batchEvidence = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        batchOperationName(
                                operationName("readRuleEvidencePages", planned.position()), toolCalls, batches.size()),
                        batch.size(),
                        "Planner-selected visual rulebook pages retrieved",
                        () -> tools.readRuleEvidencePages(plan.documentVersionId(), batch, true),
                        this::evidenceTokens);
                if (CanonicalPageObservation.complete(
                                assistantRunId, plan.documentVersionId(), batch, batchEvidence)
                        .isEmpty()) {
                    completeRead = false;
                }
                mergePageEvidence(pageEvidenceById, batchEvidence);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                completeRead = false;
                log.warn(
                        "Visual page-bound evidence read failed for topic {} batch {}: {}",
                        planned.topicKey(),
                        toolCalls,
                        failure.getMessage());
            }
        }
        List<RuleEvidence> pageEvidence = List.copyOf(pageEvidenceById.values());
        Optional<CanonicalPageObservation> canonical = completeRead
                ? CanonicalPageObservation.complete(
                        assistantRunId,
                        plan.documentVersionId(),
                        Set.copyOf(planned.sourcePageNumbers()),
                        pageEvidence)
                : Optional.empty();
        if (canonical.isPresent()) {
            log.info(
                    "Teaching topic {} is bound to visual source pages {}",
                    planned.topicKey(),
                    planned.sourcePageNumbers());
            List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), pageEvidence, List.of());
            if (!TeachingVisualEvidenceSelector.hasVisualPageEvidence(enriched)
                    || !visualCatalog.available(plan.createdBy())) {
                return new Resolution(enriched, canonical, toolCalls);
            }
            return new Resolution(
                    enrichRequiredPageFacts(plan, planned, pageEvidence, enriched, assistantRunId),
                    canonical,
                    toolCalls);
        }
        if (!pageEvidence.isEmpty()) {
            log.warn(
                    "Visual page-bound evidence for topic {} was incomplete; retaining every safely observed page",
                    planned.topicKey());
            Map<UUID, RuleEvidence> retained = new LinkedHashMap<>();
            mergePageEvidence(retained, retrieved);
            pageEvidence.stream()
                    .filter(source -> plan.documentVersionId().equals(source.documentVersionId()))
                    .filter(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(planned.sourcePageNumbers()::contains))
                    .forEach(source -> mergePageEvidence(retained, List.of(source)));
            List<RuleEvidence> partial = List.copyOf(retained.values());
            List<RuleEvidence> enriched = enrichPageFacts(plan.documentVersionId(), partial, List.of());
            if (TeachingVisualEvidenceSelector.hasVisualPageEvidence(enriched)
                    && visualCatalog.available(plan.createdBy())) {
                enriched = enrichRequiredPageFacts(plan, planned, partial, enriched, assistantRunId);
            }
            return new Resolution(enriched, Optional.empty(), toolCalls);
        }
        return new Resolution(retrieved, Optional.empty(), toolCalls);
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
        for (int start = 0; start < ordered.size(); start += MAX_PAGES_PER_TOOL_READ) {
            int batchNumber = start / MAX_PAGES_PER_TOOL_READ + 1;
            Set<Integer> batch = new LinkedHashSet<>(ordered.subList(
                    start, Math.min(start + MAX_PAGES_PER_TOOL_READ, ordered.size())));
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

    static boolean requiresPageRead(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved) {
        if (plan == null || planned == null || retrieved == null || planned.sourcePageNumbers().isEmpty()) {
            return false;
        }
        return ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)
                || TeachingVisualEvidenceSelector.hasVisualPageEvidence(retrieved);
    }

    static int maximumPrefetchToolCalls(TeachingPlan plan) {
        if (!ProgressiveVisualTeachingPlanPolicy.isProgressive(plan) || plan.sections().size() < 2) return 0;
        long remainingPages = plan.sections().subList(1, plan.sections().size()).stream()
                .flatMap(section -> section.sourcePageNumbers().stream())
                .distinct()
                .count();
        return Math.toIntExact((remainingPages + MAX_PAGES_PER_TOOL_READ - 1) / MAX_PAGES_PER_TOOL_READ);
    }

    static int maximumPageReadToolCalls(TeachingPlan.PlannedSection planned) {
        if (planned == null) return 0;
        return pageReadBatches(planned.sourcePageNumbers()).size();
    }

    static List<Set<Integer>> pageReadBatches(List<Integer> pageNumbers) {
        if (pageNumbers == null || pageNumbers.isEmpty()) return List.of();
        List<Integer> ordered = pageNumbers.stream().distinct().toList();
        List<Set<Integer>> batches = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += MAX_PAGES_PER_TOOL_READ) {
            batches.add(Set.copyOf(ordered.subList(
                    start, Math.min(start + MAX_PAGES_PER_TOOL_READ, ordered.size()))));
        }
        return List.copyOf(batches);
    }

    static int maximumModelCalls(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) return 0;
        // Admission uses only the immutable plan. Model availability and the reusable fact ledger may change after
        // a run is admitted, so they can reduce actual work but must never reduce its statically safe upper bound.
        long sectionCalls = Math.multiplyExact(
                plan.sections().stream()
                .flatMap(section -> section.sourcePageNumbers().stream())
                .count(),
                MAX_REQUIRED_PAGE_INTERPRETATION_ATTEMPTS);
        boolean progressivePrefetch = ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)
                && plan.sections().size() > 1
                && plan.sections().subList(1, plan.sections().size()).stream()
                        .flatMap(section -> section.sourcePageNumbers().stream())
                        .findAny()
                        .isPresent();
        return Math.toIntExact(sectionCalls + (progressivePrefetch ? 1 : 0));
    }

    record Resolution(
            List<RuleEvidence> evidence,
            Optional<CanonicalPageObservation> canonicalPageObservation,
            int toolCalls) {
        Resolution {
            evidence = List.copyOf(evidence);
            canonicalPageObservation = canonicalPageObservation == null
                    ? Optional.empty()
                    : canonicalPageObservation;
            if (toolCalls < 0) {
                throw new IllegalArgumentException("visual evidence resolution tool count is invalid");
            }
        }

        static Resolution unread(List<RuleEvidence> evidence) {
            return new Resolution(evidence, Optional.empty(), 0);
        }
    }

    record CanonicalPageObservation(
            UUID assistantRunId,
            UUID documentVersionId,
            Set<Integer> requestedPages,
            List<RuleEvidence> evidence) {
        CanonicalPageObservation {
            if (assistantRunId == null || documentVersionId == null || requestedPages == null || evidence == null) {
                throw new IllegalArgumentException("canonical teaching page observation is incomplete");
            }
            Set<Integer> immutablePages = Set.copyOf(requestedPages);
            List<RuleEvidence> immutableEvidence = List.copyOf(evidence);
            if (immutablePages.isEmpty()
                    || immutablePages.stream().anyMatch(page -> page == null || page < 1)
                    || immutableEvidence.isEmpty()
                    || immutableEvidence.stream().anyMatch(source -> !documentVersionId.equals(source.documentVersionId())
                            || java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                                    .noneMatch(immutablePages::contains))
                    || immutablePages.stream().anyMatch(page -> immutableEvidence.stream().noneMatch(source ->
                            page >= source.pageFrom() && page <= source.pageTo()))) {
                throw new IllegalArgumentException("canonical teaching page observation is incomplete");
            }
            requestedPages = immutablePages;
            evidence = immutableEvidence;
        }

        static Optional<CanonicalPageObservation> complete(
                UUID assistantRunId,
                UUID documentVersionId,
                Set<Integer> requestedPages,
                List<RuleEvidence> evidence) {
            try {
                return Optional.of(new CanonicalPageObservation(
                        assistantRunId, documentVersionId, requestedPages, evidence));
            } catch (IllegalArgumentException incomplete) {
                return Optional.empty();
            }
        }
    }

    private void mergePageEvidence(
            Map<UUID, RuleEvidence> evidenceById,
            List<RuleEvidence> batchEvidence) {
        for (RuleEvidence supplied : batchEvidence) {
            RuleEvidence existing = evidenceById.get(supplied.chunkId());
            if (existing == null) {
                evidenceById.put(supplied.chunkId(), supplied);
                continue;
            }
            if (!samePageEvidence(existing, supplied)) {
                throw new IllegalStateException("page evidence changed between bounded reads");
            }
            Map<Integer, RulePageImage> images = new LinkedHashMap<>();
            existing.pageImages().forEach(image -> images.putIfAbsent(image.pageNumber(), image));
            supplied.pageImages().forEach(image -> images.putIfAbsent(image.pageNumber(), image));
            evidenceById.put(
                    existing.chunkId(),
                    new RuleEvidence(
                            existing.chunkId(),
                            existing.documentVersionId(),
                            existing.sectionType(),
                            existing.heading(),
                            existing.excerpt(),
                            existing.pageFrom(),
                            existing.pageTo(),
                            List.copyOf(images.values()),
                            existing.contentKind()));
        }
    }

    private boolean samePageEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.excerpt().equals(second.excerpt())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo()
                && first.contentKind() == second.contentKind();
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
        Set<Integer> plannedPages = Set.copyOf(planned.sourcePageNumbers());
        Map<Integer, AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        pageEvidence.stream()
                .filter(TeachingVisualEvidenceSelector::isVisualPageEvidence)
                .flatMap(source -> source.pageImages().stream())
                .filter(image -> plannedPages.contains(image.pageNumber()))
                .forEach(image -> images.putIfAbsent(image.pageNumber(), image));
        List<VisualRulebookPageFacts.PageFact> interpreted = new ArrayList<>();
        for (AssistantReadTools.RulePageImage image : images.values()) {
            try {
                var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                        List.of(new PageImageInput(image.pageNumber(), image.mediaType(), image.content())),
                        plan.createdBy(),
                        plan.gameTitle());
                var catalog = inspectRequiredPage(
                        plan, planned, request, image.pageNumber(), assistantRunId, progressive);
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

    private VisualRulebookPageCatalogModel.CatalogDraft inspectRequiredPage(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            VisualRulebookPageCatalogModel.CatalogRequest request,
            int pageNumber,
            UUID assistantRunId,
            boolean progressive) {
        RuntimeException firstFailure = null;
        for (int attempt = 1; attempt <= MAX_REQUIRED_PAGE_INTERPRETATION_ATTEMPTS; attempt++) {
            try {
                int currentAttempt = attempt;
                return invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        requiredPageOperation(planned.position(), pageNumber, currentAttempt),
                        800,
                        requiredPageInterpretationSummary(plan),
                        () -> progressive
                                ? visualCatalog.summarizeForTeaching(request)
                                : visualCatalog.summarize(request),
                        result -> estimateTokens(result.toString()));
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                    log.warn(
                            "Required visual page interpretation will retry topic {} page {} after: {}",
                            planned.topicKey(),
                            pageNumber,
                            failure.getMessage());
                    continue;
                }
                failure.addSuppressed(firstFailure);
                throw failure;
            }
        }
        throw new IllegalStateException("required visual page interpretation did not settle");
    }

    private String requiredPageOperation(int sectionPosition, int pageNumber, int attempt) {
        String operation = "inspectRequiredVisualPage|" + sectionPosition + "|" + pageNumber;
        return attempt == 1 ? operation : operation + "|retry";
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

    private String batchOperationName(String operation, int batchNumber, int batchCount) {
        return batchCount == 1 ? operation : operation + "|" + batchNumber;
    }
}
