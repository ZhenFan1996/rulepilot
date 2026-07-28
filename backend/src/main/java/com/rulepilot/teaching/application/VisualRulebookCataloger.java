package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Builds and reuses page-scoped visual facts that support a teaching outline.
 *
 * <p>The catalog is deliberately separate from player-facing lesson composition: it may describe what is visible
 * on a rendered page, but it never decides a rule, a chapter, or a final lesson. Its durable facts remain optional
 * retrieval aids tied to the immutable document version.</p>
 */
@Component
@Profile("!test")
class VisualRulebookCataloger {

    private static final Logger log = LoggerFactory.getLogger(VisualRulebookCataloger.class);
    private final DocumentPageImages pageImages;
    private final VisualRulebookPageCatalogModel visualCatalog;
    private final VisualRulebookPageFacts visualFacts;
    private final AuditedAgentInvocations invocations;
    private final Duration visualCatalogTimeout;
    private final int visualCoverageProbePages;
    private final int visualRequestParallelism;

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages,
            @Value("${rulepilot.visual.request-parallelism:1}") int visualRequestParallelism) {
        this.pageImages = pageImages;
        this.visualCatalog = visualCatalog;
        this.visualFacts = visualFacts;
        this.invocations = invocations;
        if (visualCatalogTimeout == null || visualCatalogTimeout.isZero() || visualCatalogTimeout.isNegative()) {
            throw new IllegalArgumentException("visual catalog timeout must be positive");
        }
        if (visualCoverageProbePages < 1 || visualCoverageProbePages > VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES) {
            throw new IllegalArgumentException("visual coverage probe pages must be between one and "
                    + VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES);
        }
        if (visualRequestParallelism < 1 || visualRequestParallelism > 4) {
            throw new IllegalArgumentException("visual request parallelism must be between one and four");
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
        this.visualRequestParallelism = visualRequestParallelism;
    }

    boolean available(String owner) {
        return visualCatalog.available(owner);
    }

    List<PageInput> catalogVisualPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        Set<Integer> requestedPages = documentPages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> cached = visualFacts.find(documentVersionId, requestedPages);
        Set<Integer> missingPages = VisualRulebookCatalogPolicy.missingPages(requestedPages, cached);
        if (!cached.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "reuseVisualPageFacts",
                    ActivityOutcome.SUCCEEDED,
                    "Reused " + cached.size() + " page-scoped visual facts from this immutable rulebook version");
        }
        // A visual-only rulebook cannot form an evidence-bound outline from a partial ledger. Preserve every
        // successfully cataloged page and only request missing pages. Visual anchors are optional crop-discovery
        // hints, not a condition for trusting the page's factual ledger; retrying an anchorless page made every
        // subsequent plan pay again for otherwise usable visual evidence.
        Set<Integer> requiredFacts = new LinkedHashSet<>(missingPages);
        List<PageFact> fresh = requiredFacts.isEmpty()
                ? List.of()
                : catalogPageFacts(documentVersionId, requiredFacts, rulebookTitle, owner, assistantRunId);
        if (!cached.isEmpty() && !requiredFacts.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "completeVisualPageFacts",
                    ActivityOutcome.SUCCEEDED,
                    "Completed " + requiredFacts.size()
                            + " missing visual page(s) before visual-only outline planning");
        }
        List<PageFact> facts = cached.isEmpty()
                ? VisualRulebookCatalogPolicy.mergeFreshFacts(cached, fresh)
                : VisualRulebookCatalogPolicy.backfillAnchors(cached, fresh);
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook catalog did not produce any reliable page facts");
        }
        if (!fresh.isEmpty()) visualFacts.merge(documentVersionId, facts);
        int unavailablePages = documentPages.size() - facts.size();
        if (unavailablePages > 0) {
            log.warn(
                    "Visual catalog completed {} of {} pages for document {}; retaining {} source pages without visual claims",
                    facts.size(), documentPages.size(), documentVersionId, unavailablePages);
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "retainPartialVisualCatalog",
                        ActivityOutcome.REJECTED,
                        "Visual catalog was incomplete; completed facts are used and remaining source pages stay in the outline");
            }
        }
        return VisualRulebookCatalogPolicy.pageInputs(documentPages, facts);
    }

    List<PageFact> inspectUnownedSparseVisualPages(
            UUID documentVersionId,
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        Set<Integer> selected = VisualOutlineEvidencePolicy.unownedSparseVisualCoveragePageNumbers(
                outline, documentPages, visualCoverageProbePages);
        if (selected.isEmpty()) return List.of();
        List<PageFact> cached = visualFacts.find(documentVersionId, selected);
        Set<Integer> cachedPages = cached.stream().map(PageFact::pageNumber).collect(Collectors.toSet());
        Set<Integer> missing = selected.stream()
                .filter(page -> !cachedPages.contains(page))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> fresh;
        try {
            fresh = missing.isEmpty()
                    ? List.of()
                    : catalogPageFacts(documentVersionId, missing, rulebookTitle, owner, assistantRunId);
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Sparse-page visual coverage probe skipped for document {} pages {}",
                    documentVersionId,
                    missing,
                    visualFailure);
            return cached;
        }
        if (!fresh.isEmpty()) {
            visualFacts.merge(documentVersionId, fresh);
            log.info(
                    "Sparse-page visual coverage probe stored document {} pages {}",
                    documentVersionId,
                    fresh.stream().map(PageFact::pageNumber).toList());
        }
        return VisualRulebookCatalogPolicy.mergeFreshFacts(cached, fresh);
    }

    void catalogSelectedOutlinePages(
            UUID documentVersionId,
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        Set<Integer> selectedVisualPages = VisualOutlineEvidencePolicy.selectedVisualPageNumbers(outline, documentPages);
        if (selectedVisualPages.isEmpty()) return;
        try {
            Set<Integer> cachedPages = visualFacts.find(documentVersionId, selectedVisualPages).stream()
                    .map(PageFact::pageNumber)
                    .collect(Collectors.toSet());
            Set<Integer> uncatalogedPages = selectedVisualPages.stream()
                    .filter(page -> !cachedPages.contains(page))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (uncatalogedPages.isEmpty()) {
                log.info(
                        "Visual page interpretation reused existing facts for document {} pages {}",
                        documentVersionId,
                        selectedVisualPages);
                return;
            }
            List<PageFact> interpreted = catalogPageFacts(
                    documentVersionId,
                    uncatalogedPages,
                    rulebookTitle,
                    owner,
                    assistantRunId);
            if (interpreted.isEmpty()) {
                log.warn(
                        "Visual page interpretation produced no usable facts for document {} pages {}",
                        documentVersionId,
                        uncatalogedPages);
                return;
            }
            visualFacts.merge(documentVersionId, interpreted);
            log.info(
                    "Visual page interpretation stored document {} pages {}",
                    documentVersionId,
                    interpreted.stream().map(PageFact::pageNumber).toList());
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Visual page interpretation skipped for document {} pages {}",
                    documentVersionId,
                    selectedVisualPages,
                    visualFailure);
        }
    }

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        // A legend must not be bundled with a gameplay page. Vision providers occasionally return a valid summary
        // for only one of two supplied images; treating that partial response as an all-or-nothing pair discarded
        // the usable page and could abort a photographed rulebook's planning run. Each page remains independently
        // retryable and durable; the later outline model can combine their stored facts when it needs the legend.
        List<List<Integer>> batches = VisualRulebookCatalogPolicy.singlePageBatches(orderedPages);
        if (batches.isEmpty()) throw new IllegalArgumentException("rulebook has no pages to catalog");
        List<VisualRulebookPageCatalogModel.PageSummary> summaries = new ArrayList<>();
        List<Integer> failedPages = new ArrayList<>();
        int parallelism = Math.min(visualRequestParallelism, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = java.util.stream.IntStream
                    .range(0, batches.size())
                    .mapToObj(batchIndex -> executor.submit(() -> catalogBatch(
                            documentVersionId,
                            batches.get(batchIndex),
                            owner,
                            rulebookTitle,
                            assistantRunId,
                            "inspectRulebookVisualBatch|" + (batchIndex + 1))))
                    .toList();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    List<VisualRulebookPageCatalogModel.PageSummary> completed =
                            awaitCatalog(futures.get(index), visualCatalogTimeout).pages();
                    summaries.addAll(completed);
                    persistCompletedFacts(documentVersionId, completed);
                } catch (RuntimeException failedBatch) {
                    String operation = "inspectRulebookVisualBatch|" + (index + 1);
                    if (catalogTimedOut(failedBatch)) {
                        invocations.stopRunning(
                                assistantRunId,
                                operation,
                                ActivityOutcome.FAILED,
                                "Visual page batch timed out; retaining completed page facts");
                    }
                    log.warn(
                            "Visual page interpretation skipped failed batch {} for document {}",
                            batches.get(index),
                            documentVersionId,
                            failedBatch);
                    failedPages.addAll(batches.get(index));
                }
            }
            retryFailedPages(
                    documentVersionId,
                    failedPages,
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    executor,
                    summaries);
        } finally {
            executor.shutdownNow();
        }
        return summaries.stream()
                .sorted(java.util.Comparator.comparingInt(VisualRulebookPageCatalogModel.PageSummary::pageNumber))
                .collect(Collectors.toMap(
                        VisualRulebookPageCatalogModel.PageSummary::pageNumber,
                        java.util.function.Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .filter(summary -> pageNumbers.contains(summary.pageNumber()))
                .map(summary -> new PageFact(
                        summary.pageNumber(),
                        summary.printedTerms(),
                        summary.factualSummary(),
                        summary.keywords(),
                        summary.visualAnchors()))
                .toList();
    }

    /**
     * Keep already-completed page work after a later provider timeout, cancellation, or process restart. A later
     * attempt can then request only the unfinished source pages instead of re-reading an entire photographed book.
     */
    private void persistCompletedFacts(
            UUID documentVersionId, List<VisualRulebookPageCatalogModel.PageSummary> completed) {
        if (completed == null || completed.isEmpty()) return;
        visualFacts.merge(
                documentVersionId,
                completed.stream()
                        .map(summary -> new PageFact(
                                summary.pageNumber(),
                                summary.printedTerms(),
                                summary.factualSummary(),
                                summary.keywords(),
                                summary.visualAnchors()))
                        .toList());
    }

    /**
     * Retry only failed pages so a temporary provider error cannot discard the rest of a visual rulebook's ledger.
     * Failed single-page retries remain absent and are still handled by the evidence policy.
     */
    private void retryFailedPages(
            UUID documentVersionId,
            List<Integer> failedPages,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            ExecutorService executor,
            List<VisualRulebookPageCatalogModel.PageSummary> summaries) {
        if (failedPages.isEmpty()) return;
        List<Integer> retryPages = failedPages.stream().distinct().toList();
        List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> retries = retryPages.stream()
                .map(pageNumber -> executor.submit(() -> catalogBatch(
                        documentVersionId,
                        List.of(pageNumber),
                        owner,
                        rulebookTitle,
                        assistantRunId,
                        "inspectRulebookVisualRetry|" + pageNumber)))
                .toList();
        for (int index = 0; index < retries.size(); index++) {
            try {
                List<VisualRulebookPageCatalogModel.PageSummary> completed =
                        awaitCatalog(retries.get(index), visualCatalogTimeout).pages();
                summaries.addAll(completed);
                persistCompletedFacts(documentVersionId, completed);
            } catch (RuntimeException retryFailure) {
                log.warn(
                        "Visual page retry skipped page {} for document {}",
                        retryPages.get(index),
                        documentVersionId,
                        retryFailure);
            }
        }
    }

    private VisualRulebookPageCatalogModel.CatalogDraft catalogBatch(
            UUID documentVersionId,
            List<Integer> batch,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            String operation) {
        List<PageImageInput> images = pageImages.read(documentVersionId, new LinkedHashSet<>(batch)).stream()
                .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
        return invokeModel(
                assistantRunId,
                operation,
                Math.max(1, images.size() * 800),
                "Rulebook visual batch interpreted",
                () -> visualCatalog.summarize(request),
                this::catalogOutputTokens);
    }

    static VisualRulebookPageCatalogModel.CatalogDraft awaitCatalog(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "visual rulebook catalog timed out after " + timeout.toSeconds() + " seconds", slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
    }

    private static boolean catalogTimedOut(RuntimeException failure) {
        return failure.getCause() instanceof TimeoutException;
    }

    private <T> T invokeModel(
            UUID assistantRunId,
            String operation,
            int inputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokens) {
        if (assistantRunId == null) return invocation.get();
        return invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operation,
                inputTokens,
                successSummary,
                invocation,
                outputTokens);
    }

    private int catalogOutputTokens(VisualRulebookPageCatalogModel.CatalogDraft catalog) {
        int characters = catalog.pages().stream()
                .mapToInt(page -> page.printedTerms().length()
                        + page.factualSummary().length()
                        + page.keywords().stream().mapToInt(String::length).sum())
                .sum();
        return Math.max(1, characters / 4);
    }
}
