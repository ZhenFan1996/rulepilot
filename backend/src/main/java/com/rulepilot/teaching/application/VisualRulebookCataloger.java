package com.rulepilot.teaching.application;

import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
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
    private final Duration progressiveStartTimeout;
    private final int visualCoverageProbePages;
    private final int visualRequestParallelism;

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout,
            @Value("${rulepilot.visual.progressive-start-timeout:PT35S}") Duration progressiveStartTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages,
            @Value("${rulepilot.visual.request-parallelism:10}") int visualRequestParallelism) {
        this.pageImages = pageImages;
        this.visualCatalog = visualCatalog;
        this.visualFacts = visualFacts;
        this.invocations = invocations;
        if (visualCatalogTimeout == null || visualCatalogTimeout.isZero() || visualCatalogTimeout.isNegative()) {
            throw new IllegalArgumentException("visual catalog timeout must be positive");
        }
        if (progressiveStartTimeout == null || progressiveStartTimeout.isZero() || progressiveStartTimeout.isNegative()) {
            throw new IllegalArgumentException("progressive visual Teaching start timeout must be positive");
        }
        if (visualCoverageProbePages < 1 || visualCoverageProbePages > VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES) {
            throw new IllegalArgumentException("visual coverage probe pages must be between one and "
                    + VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES);
        }
        if (visualRequestParallelism < 1 || visualRequestParallelism > 10) {
            throw new IllegalArgumentException("visual request parallelism must be between one and ten");
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.progressiveStartTimeout = progressiveStartTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
        this.visualRequestParallelism = visualRequestParallelism;
    }

    boolean available(String owner) {
        return visualCatalog.available(owner);
    }

    Optional<ProgressiveTeachingStartDraft> progressiveTeachingStart(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        if (documentPages.isEmpty()
                || documentPages.size() > VisualRulebookPageCatalogModel.MAX_PAGES_PER_REQUEST
                || !visualCatalog.supportsProgressiveTeachingStart(owner)) {
            return Optional.empty();
        }
        List<Integer> requestedPages = documentPages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .toList();
        List<PageImageInput> images = readTeachingPageImages(documentVersionId, requestedPages);
        if (images.size() != requestedPages.size()) return Optional.empty();
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProgressiveTeachingStartDraft> modelCall = executor.submit(() -> invokeModel(
                    assistantRunId,
                    "selectProgressiveTeachingStart",
                    Math.max(1, images.size() * 600),
                    progressiveTeachingStartSuccessSummary(owner),
                    () -> visualCatalog.selectProgressiveTeachingStart(request)
                            .orElseThrow(() -> new IllegalStateException("progressive visual teaching is unavailable")),
                    this::progressiveTeachingStartOutputTokens));
            ProgressiveTeachingStartDraft start;
            try {
                start = awaitProgressiveTeachingStart(modelCall, progressiveStartTimeout);
            } finally {
                modelCall.cancel(true);
            }
            ProgressiveVisualTeachingPlanPolicy.validate(documentPages, start);
            persistCompletedFacts(
                    documentVersionId,
                    List.of(VisualRulebookCatalogPolicy.teachingStartupFact(start.selectedPageFacts())));
            return Optional.of(start);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidStart) {
            if (catalogTimedOut(invalidStart) && assistantRunId != null) {
                invocations.stopRunning(
                        assistantRunId,
                        "selectProgressiveTeachingStart",
                        ActivityOutcome.FAILED,
                        "Progressive visual Teaching start timed out; retaining complete preparation");
            }
            log.warn(
                    "Progressive visual Teaching start was rejected for document {}; retaining complete preparation path (failureType={})",
                    documentVersionId,
                    invalidStart.getClass().getSimpleName());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "fallbackFromProgressiveTeachingStart",
                        ActivityOutcome.REJECTED,
                        "Progressive visual start was invalid; complete page-fact preparation was retained");
            }
            return Optional.empty();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Reads every rendered page for the document-level icon glossary. Completed pages are reused, while an explicitly
     * incomplete inventory is eligible for one later retry. Work remains page-by-page and is persisted after each
     * provider response so a small production host can resume after a timeout or restart.
     */
    List<PageFact> catalogAllIconPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        return catalogAllIconPages(
                documentVersionId,
                documentPages,
                rulebookTitle,
                owner,
                assistantRunId,
                CaptureHandle.noop());
    }

    List<PageFact> catalogAllIconPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        Set<Integer> requestedPages = documentPages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedPages.isEmpty()) return List.of();
        List<PageFact> cached = visualFacts.find(documentVersionId, requestedPages);
        // A schema change means the interpretation contract changed. Reusing an old dense-page transcript as the
        // input to the new interpreter can preserve exactly the model error that prompted the migration, so stale
        // facts deliberately fall through to a fresh read of the immutable source page below.
        Set<Integer> completePages = cached.stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .filter(PageFact::iconInventoryComplete)
                .map(PageFact::pageNumber)
                .collect(Collectors.toSet());
        Set<Integer> pagesToInspect = requestedPages.stream()
                .filter(page -> !completePages.contains(page))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!pagesToInspect.isEmpty()) {
            List<PageFact> inspected = catalogPageFacts(
                    documentVersionId,
                    pagesToInspect,
                    rulebookTitle,
                    owner,
                    assistantRunId,
                    true,
                    trace);
            if (!inspected.isEmpty()) visualFacts.merge(documentVersionId, inspected);
        }
        return visualFacts.find(documentVersionId, requestedPages).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    List<PageInput> catalogVisualPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        return catalogVisualPages(
                documentVersionId,
                documentPages,
                rulebookTitle,
                owner,
                assistantRunId,
                CaptureHandle.noop());
    }

    List<PageInput> catalogVisualPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
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
        // Preserve every page observation, including an explicitly partial one. Completeness is source metadata for
        // the outline Agent, not permission for this adapter to discard visible rules or block the whole lesson.
        // A page that could not be read at all is represented by pageInputs(...) as unavailable, so the outline can
        // keep the page binding without inventing facts from it.
        Set<Integer> requiredFacts = new LinkedHashSet<>(missingPages);
        List<PageFact> fresh = requiredFacts.isEmpty()
                ? List.of()
                : catalogTeachingPageFacts(
                        documentVersionId, requiredFacts, rulebookTitle, owner, assistantRunId, trace);
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
        if (!fresh.isEmpty()) visualFacts.merge(documentVersionId, facts);
        List<PageFact> teachingFacts = facts.stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
        return VisualRulebookCatalogPolicy.pageInputs(documentPages, teachingFacts);
    }

    List<PageFact> inspectUnownedSparseVisualPages(
            UUID documentVersionId,
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        return inspectUnownedSparseVisualPages(
                documentVersionId,
                outline,
                documentPages,
                rulebookTitle,
                owner,
                assistantRunId,
                CaptureHandle.noop());
    }

    List<PageFact> inspectUnownedSparseVisualPages(
            UUID documentVersionId,
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        Set<Integer> selected = VisualOutlineEvidencePolicy.unownedSparseVisualCoveragePageNumbers(
                outline, documentPages, visualCoverageProbePages);
        if (selected.isEmpty()) return List.of();
        List<PageFact> cached = visualFacts.find(documentVersionId, selected);
        Set<Integer> cachedPages = cached.stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .map(PageFact::pageNumber)
                .collect(Collectors.toSet());
        Set<Integer> missing = selected.stream()
                .filter(page -> !cachedPages.contains(page))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> fresh;
        try {
            fresh = missing.isEmpty()
                    ? List.of()
                    : catalogTeachingPageFacts(
                            documentVersionId, missing, rulebookTitle, owner, assistantRunId, trace);
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Sparse-page visual coverage probe skipped for document {} (pageCount={}, failureType={})",
                    documentVersionId,
                    missing.size(),
                    visualFailure.getClass().getSimpleName());
            return cached;
        }
        if (!fresh.isEmpty()) {
            visualFacts.merge(documentVersionId, fresh);
            log.info(
                    "Sparse-page visual coverage probe stored document {} (pageCount={})",
                    documentVersionId,
                    fresh.size());
        }
        return VisualRulebookCatalogPolicy.backfillAnchors(cached, fresh);
    }

    /**
     * Builds the minimum durable, page-bound evidence ledger needed by outline and lesson composition. It avoids the
     * complete icon inventory, rectangle localization, crop review, dense-cell rereads, and tile audit performed by
     * {@link #catalogAllIconPages}; those enrichments can proceed after the lesson is already readable.
     */
    private List<PageFact> catalogTeachingPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        List<List<Integer>> batches = VisualRulebookCatalogPolicy.teachingStartupBatches(orderedPages);
        if (batches.isEmpty()) throw new IllegalArgumentException("rulebook has no pages to catalog for teaching");
        // The visual adapter already performs one single-page, contract-specific repair. Retrying the same page at
        // this orchestration layer doubled that pair and produced four identical paid calls per failed page.
        inspectTeachingBatches(
                documentVersionId,
                batches,
                owner,
                rulebookTitle,
                assistantRunId,
                index -> "inspectTeachingVisualPage|" + orderedPages.get(index) + "|" + orderedPages.size(),
                new LinkedHashSet<>(),
                capture);
        return visualFacts.find(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    private List<Integer> inspectTeachingBatches(
            UUID documentVersionId,
            List<List<Integer>> batches,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            IntFunction<String> operationForIndex,
            Set<Integer> timedOutPages,
            CaptureHandle capture) {
        List<Integer> missingPages = new ArrayList<>();
        int parallelism = Math.min(visualRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, batches.size());
            ExecutorService executor = Executors.newFixedThreadPool(windowEnd - windowStart);
            try {
                List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                for (int index = windowStart; index < windowEnd; index++) {
                    int batchIndex = index;
                    futures.add(executor.submit(() -> catalogTeachingBatch(
                            documentVersionId,
                            batches.get(batchIndex),
                            owner,
                            rulebookTitle,
                            assistantRunId,
                            operationForIndex.apply(batchIndex),
                            capture)));
                }
                long windowDeadlineNanos = catalogWindowDeadline(visualCatalogTimeout);
                for (int offset = 0; offset < futures.size(); offset++) {
                    int batchIndex = windowStart + offset;
                    List<Integer> batch = batches.get(batchIndex);
                    try {
                        var draft = awaitCatalogBefore(
                                futures.get(offset), visualCatalogTimeout, windowDeadlineNanos);
                        Map<Integer, Long> returnedCounts = draft.pages().stream().collect(Collectors.groupingBy(
                                VisualRulebookPageCatalogModel.PageSummary::pageNumber,
                                Collectors.counting()));
                        List<VisualRulebookPageCatalogModel.PageSummary> completed = draft.pages().stream()
                                .filter(summary -> batch.contains(summary.pageNumber()))
                                .filter(summary -> returnedCounts.get(summary.pageNumber()) == 1)
                                .map(VisualRulebookCatalogPolicy::teachingStartupFact)
                                .sorted(java.util.Comparator.comparingInt(
                                        summary -> batch.indexOf(summary.pageNumber())))
                                .toList();
                        persistCompletedFacts(documentVersionId, completed);
                        Set<Integer> completedPages = completed.stream()
                                .map(VisualRulebookPageCatalogModel.PageSummary::pageNumber)
                                .collect(Collectors.toSet());
                        batch.stream().filter(page -> !completedPages.contains(page)).forEach(missingPages::add);
                    } catch (RuntimeException failedBatch) {
                        if (catalogTimedOut(failedBatch)) {
                            timedOutPages.addAll(batch);
                            if (assistantRunId != null) {
                                invocations.stopRunning(
                                        assistantRunId,
                                        operationForIndex.apply(batchIndex),
                                        ActivityOutcome.FAILED,
                                        "Teaching visual batch timed out; retaining completed page facts");
                            }
                        }
                        log.warn(
                                "Teaching-start visual interpretation skipped failed batch for document {} (pageCount={}, failureType={})",
                                documentVersionId,
                                batch.size(),
                                failedBatch.getClass().getSimpleName());
                        missingPages.addAll(batch);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        return missingPages;
    }

    private VisualRulebookPageCatalogModel.CatalogDraft catalogTeachingBatch(
            UUID documentVersionId,
            List<Integer> batch,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            String operation,
            CaptureHandle capture) {
        List<PageImageInput> images = readTeachingPageImages(documentVersionId, batch);
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
        return invokeModel(
                assistantRunId,
                operation,
                Math.max(1, images.size() * 600),
                teachingStartupSuccessSummary(owner),
                () -> capture.enabled()
                        ? visualCatalog.summarizeForTeaching(
                                request,
                                capture,
                                visualModelContext(assistantRunId))
                        : visualCatalog.summarizeForTeaching(request),
                this::catalogOutputTokens);
    }

    private String teachingStartupSuccessSummary(String owner) {
        return visualCatalog.teachingStartupExecutionIdentity(owner)
                .map(identity -> "Teaching-start page facts interpreted via " + identity.auditLabel())
                .orElse("Teaching-start page facts interpreted");
    }

    private String progressiveTeachingStartSuccessSummary(String owner) {
        return visualCatalog.teachingStartupExecutionIdentity(owner)
                .map(identity -> "First cited-page candidate selected via " + identity.auditLabel())
                .orElse("First cited-page candidate selected from the supplied rulebook pages");
    }

    /**
     * A short-rulebook model request may contain more images than one bounded object-store read permits. Preserve the
     * document adapter's five-page safety boundary, assemble the images in exact requested order, and let the existing
     * missing-binding recovery handle an absent stored image independently.
     */
    private List<PageImageInput> readTeachingPageImages(UUID documentVersionId, List<Integer> requestedPages) {
        List<PageImageInput> images = new ArrayList<>();
        for (int start = 0; start < requestedPages.size(); start += DocumentPageImages.MAX_PAGES_PER_READ) {
            List<Integer> chunk = requestedPages.subList(
                    start, Math.min(start + DocumentPageImages.MAX_PAGES_PER_READ, requestedPages.size()));
            Map<Integer, PageImage> available = pageImages.read(documentVersionId, new LinkedHashSet<>(chunk)).stream()
                    .collect(Collectors.toMap(PageImage::pageNumber, image -> image, (first, ignored) -> first));
            chunk.stream()
                    .map(available::get)
                    .filter(java.util.Objects::nonNull)
                    .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                    .forEach(images::add);
        }
        return List.copyOf(images);
    }

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            boolean allowTileFallback,
            CaptureHandle capture) {
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        // A legend must not be bundled with a gameplay page. Vision providers occasionally return a valid summary
        // for only one of two supplied images; treating that partial response as an all-or-nothing pair discarded
        // the usable page and could abort a photographed rulebook's planning run. Each page remains independently
        // retryable and durable; the later outline model can combine their stored facts when it needs the legend.
        List<List<Integer>> batches = VisualRulebookCatalogPolicy.singlePageBatches(orderedPages);
        if (batches.isEmpty()) throw new IllegalArgumentException("rulebook has no pages to catalog");
        List<VisualRulebookPageCatalogModel.PageSummary> summaries = new ArrayList<>();
        Set<Integer> timedOutPages = new LinkedHashSet<>();
        List<Integer> failedPages = inspectInBoundedWindows(
                documentVersionId,
                batches,
                owner,
                rulebookTitle,
                assistantRunId,
                index -> "inspectRulebookVisualBatch|" + (index + 1),
                summaries,
                "Visual page batch timed out; retaining completed page facts",
                timedOutPages,
                allowTileFallback,
                capture);
        List<Integer> retryableFailures = failedPages.stream()
                .filter(page -> !timedOutPages.contains(page))
                .toList();
        List<Integer> retryFailures =
                retryFailedPages(
                        documentVersionId,
                        retryableFailures,
                        owner,
                        rulebookTitle,
                        assistantRunId,
                        summaries,
                        allowTileFallback,
                        capture);
        Set<Integer> tileFallbackPages = new LinkedHashSet<>(timedOutPages);
        tileFallbackPages.addAll(retryFailures);
        if (allowTileFallback) {
            summaries.stream()
                    .filter(VisualRulebookCatalogPolicy::needsIconTileFallback)
                    .map(VisualRulebookPageCatalogModel.PageSummary::pageNumber)
                    .forEach(tileFallbackPages::add);
        }
        if (allowTileFallback && !tileFallbackPages.isEmpty()) {
            summaries.addAll(catalogDensePagesWithTiles(
                    documentVersionId,
                    List.copyOf(tileFallbackPages),
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    capture));
        }
        List<VisualRulebookPageCatalogModel.PageSummary> consolidated = summaries.stream()
                .sorted(java.util.Comparator.comparingInt(VisualRulebookPageCatalogModel.PageSummary::pageNumber))
                .collect(Collectors.toMap(
                        VisualRulebookPageCatalogModel.PageSummary::pageNumber,
                        java.util.function.Function.identity(),
                        VisualRulebookCatalogPolicy::mergeIconTileAudit,
                        LinkedHashMap::new))
                .values().stream()
                .filter(summary -> pageNumbers.contains(summary.pageNumber()))
                .toList();
        persistCompletedFacts(documentVersionId, consolidated);
        return visualFacts.find(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    /**
     * Keep already-completed page work after a later provider timeout, cancellation, or process restart. A later
     * attempt can then request only the unfinished source pages instead of re-reading an entire photographed book.
     */
    private void persistCompletedFacts(
            UUID documentVersionId, List<VisualRulebookPageCatalogModel.PageSummary> observations) {
        if (observations == null || observations.isEmpty()) return;
        Set<Integer> observedPages = observations.stream()
                .map(VisualRulebookPageCatalogModel.PageSummary::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, VisualRulebookPageCatalogModel.PageSummary> accumulated = new LinkedHashMap<>();
        visualFacts.find(documentVersionId, observedPages).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .map(VisualRulebookCataloger::pageSummary)
                .forEach(summary -> accumulated.put(summary.pageNumber(), summary));
        observations.forEach(summary -> accumulated.merge(
                summary.pageNumber(), summary, VisualRulebookCatalogPolicy::mergePersistedPageObservation));
        visualFacts.merge(
                documentVersionId,
                accumulated.values().stream()
                        .map(VisualRulebookCataloger::pageFact)
                        .toList());
    }

    private static PageFact pageFact(VisualRulebookPageCatalogModel.PageSummary summary) {
        return VisualRulebookCatalogPolicy.toPageFact(summary);
    }

    private static VisualRulebookPageCatalogModel.PageSummary pageSummary(PageFact fact) {
        return new VisualRulebookPageCatalogModel.PageSummary(
                fact.pageNumber(),
                fact.printedTerms(),
                fact.factualSummary(),
                fact.keywords(),
                fact.visualAnchors(),
                fact.iconOccurrences(),
                fact.iconInventoryComplete(),
                fact.sourceDependencies(),
                fact.ruleGroupIdentifiers(),
                fact.ruleGroupInventoryComplete(),
                List.of(),
                fact.ruleGroupFacts());
    }

    /**
     * Retry only failed pages so a temporary provider error cannot discard the rest of a visual rulebook's ledger.
     * Failed single-page retries remain absent and are still handled by the evidence policy.
     */
    private List<Integer> retryFailedPages(
            UUID documentVersionId,
            List<Integer> failedPages,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            List<VisualRulebookPageCatalogModel.PageSummary> summaries,
            boolean verifyIconBounds,
            CaptureHandle capture) {
        if (failedPages.isEmpty()) return List.of();
        List<Integer> retryPages = failedPages.stream().distinct().toList();
        List<List<Integer>> retryBatches = retryPages.stream().map(List::of).toList();
        Set<Integer> retryTimeouts = new LinkedHashSet<>();
        return inspectInBoundedWindows(
                documentVersionId,
                retryBatches,
                owner,
                rulebookTitle,
                assistantRunId,
                index -> "inspectRulebookVisualRetry|" + retryPages.get(index),
                summaries,
                "Visual page retry timed out; the page remains incomplete",
                retryTimeouts,
                verifyIconBounds,
                capture);
    }

    private List<VisualRulebookPageCatalogModel.PageSummary> catalogDensePagesWithTiles(
            UUID documentVersionId,
            List<Integer> pageNumbers,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            CaptureHandle capture) {
        List<VisualRulebookPageCatalogModel.PageSummary> mergedPages = new ArrayList<>();
        for (int pageNumber : pageNumbers.stream().distinct().sorted().toList()) {
            Optional<PageImage> source = pageImages.read(documentVersionId, Set.of(pageNumber)).stream()
                    .filter(page -> page.pageNumber() == pageNumber)
                    .findFirst();
            if (source.isEmpty()) continue;
            List<VisualPageTilePolicy.PageTile> tiles = VisualPageTilePolicy.tiles(source.get());
            List<VisualPageTilePolicy.TileSummary> completed = new ArrayList<>();
            int parallelism = Math.min(visualRequestParallelism, tiles.size());
            for (int windowStart = 0; windowStart < tiles.size(); windowStart += parallelism) {
                int windowEnd = Math.min(windowStart + parallelism, tiles.size());
                ExecutorService executor = Executors.newFixedThreadPool(windowEnd - windowStart);
                try {
                    List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                    for (int index = windowStart; index < windowEnd; index++) {
                        int tileIndex = index;
                        futures.add(executor.submit(() -> catalogTile(
                                tiles.get(tileIndex),
                                owner,
                                rulebookTitle,
                                assistantRunId,
                                "inspectRulebookVisualTile|" + pageNumber + "|" + (tileIndex + 1),
                                capture)));
                    }
                    long windowDeadlineNanos = catalogWindowDeadline(visualCatalogTimeout);
                    for (int offset = 0; offset < futures.size(); offset++) {
                        int tileIndex = windowStart + offset;
                        try {
                            VisualRulebookPageCatalogModel.PageSummary summary = awaitCatalogBefore(
                                            futures.get(offset), visualCatalogTimeout, windowDeadlineNanos)
                                    .pages()
                                    .getFirst();
                            completed.add(new VisualPageTilePolicy.TileSummary(
                                    tiles.get(tileIndex).viewport(), summary));
                        } catch (RuntimeException failedTile) {
                            if (catalogTimedOut(failedTile)) {
                                invocations.stopRunning(
                                        assistantRunId,
                                        "inspectRulebookVisualTile|" + pageNumber + "|" + (tileIndex + 1),
                                        ActivityOutcome.FAILED,
                                        "Dense-page tile timed out; retaining other completed tiles");
                            }
                            log.warn(
                                    "Visual tile skipped for dense rulebook page in document {} (tileIndex={}, failureType={})",
                                    documentVersionId,
                                    tileIndex + 1,
                                    failedTile.getClass().getSimpleName());
                        }
                    }
                } finally {
                    executor.shutdownNow();
                }
            }
            if (!completed.isEmpty()) {
                VisualRulebookPageCatalogModel.PageSummary merged =
                        VisualPageTilePolicy.merge(pageNumber, completed);
                VisualRulebookPageCatalogModel.PageSummary localized =
                        localizeIconBounds(documentVersionId, merged, owner, assistantRunId, capture);
                mergedPages.add(localized);
                persistCompletedFacts(documentVersionId, List.of(localized));
            }
        }
        return mergedPages;
    }

    private VisualRulebookPageCatalogModel.CatalogDraft catalogTile(
            VisualPageTilePolicy.PageTile tile,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            String operation,
            CaptureHandle capture) {
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                List.of(tile.image()), owner, rulebookTitle, tile.viewport());
        return invokeModel(
                assistantRunId,
                operation,
                800,
                "Dense rulebook page tile interpreted",
                () -> capture.enabled()
                        ? visualCatalog.summarize(request, capture, iconModelContext(assistantRunId))
                        : visualCatalog.summarize(request),
                this::catalogOutputTokens);
    }

    /**
     * Submit only work that can start immediately. Every window shares one deadline from submission, so several slow
     * provider calls cannot multiply the player-visible wait. Replacing the executor after each window also prevents
     * a provider that ignores interruption from starving every later page after one timeout.
     */
    private List<Integer> inspectInBoundedWindows(
            UUID documentVersionId,
            List<List<Integer>> batches,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            IntFunction<String> operationForIndex,
            List<VisualRulebookPageCatalogModel.PageSummary> summaries,
            String timeoutSummary,
            Set<Integer> timedOutPages,
            boolean verifyIconBounds,
            CaptureHandle capture) {
        List<Integer> failedPages = new ArrayList<>();
        int parallelism = Math.min(visualRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, batches.size());
            ExecutorService executor = Executors.newFixedThreadPool(windowEnd - windowStart);
            try {
                List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                for (int index = windowStart; index < windowEnd; index++) {
                    int batchIndex = index;
                    futures.add(executor.submit(() -> catalogBatch(
                            documentVersionId,
                            batches.get(batchIndex),
                            owner,
                            rulebookTitle,
                            assistantRunId,
                            operationForIndex.apply(batchIndex),
                            capture)));
                }
                long windowDeadlineNanos = catalogWindowDeadline(visualCatalogTimeout);
                for (int offset = 0; offset < futures.size(); offset++) {
                    int batchIndex = windowStart + offset;
                    try {
                        List<VisualRulebookPageCatalogModel.PageSummary> completed = awaitCatalogBefore(
                                        futures.get(offset), visualCatalogTimeout, windowDeadlineNanos)
                                .pages();
                        if (verifyIconBounds) {
                            completed = completed.stream()
                                    .map(summary ->
                                            localizeIconBounds(
                                                    documentVersionId,
                                                    summary,
                                                    owner,
                                                    assistantRunId,
                                                    capture))
                                    .toList();
                        }
                        summaries.addAll(completed);
                        persistCompletedFacts(documentVersionId, completed);
                    } catch (RuntimeException failedBatch) {
                        if (catalogTimedOut(failedBatch)) {
                            timedOutPages.addAll(batches.get(batchIndex));
                            invocations.stopRunning(
                                    assistantRunId,
                                    operationForIndex.apply(batchIndex),
                                    ActivityOutcome.FAILED,
                                    timeoutSummary);
                        }
                        log.warn(
                                "Visual page interpretation skipped failed batch for document {} (pageCount={}, failureType={})",
                                documentVersionId,
                                batches.get(batchIndex).size(),
                                failedBatch.getClass().getSimpleName());
                        failedPages.addAll(batches.get(batchIndex));
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        return failedPages;
    }

    private VisualRulebookPageCatalogModel.PageSummary localizeIconBounds(
            UUID documentVersionId,
            VisualRulebookPageCatalogModel.PageSummary summary,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        if (summary.iconOccurrences().isEmpty()) return summary;
        Optional<PageImage> page = pageImages.read(documentVersionId, Set.of(summary.pageNumber())).stream()
                .filter(candidate -> candidate.pageNumber() == summary.pageNumber())
                .findFirst();
        if (page.isEmpty()) return withoutUnverifiedIcons(summary);
        try {
            var request = new VisualRulebookPageCatalogModel.IconLocalizationRequest(
                    new PageImageInput(
                            page.get().pageNumber(),
                            page.get().mediaType(),
                            page.get().content()),
                    summary.iconOccurrences(),
                    owner);
            var localized = localizeIconsWithOneRepair(
                    request, summary.pageNumber(), assistantRunId, capture);
            Map<Integer, VisualRulebookPageCatalogModel.IconLocation> locations = localized.locations().stream()
                    .collect(Collectors.toMap(
                            VisualRulebookPageCatalogModel.IconLocation::candidateIndex,
                            java.util.function.Function.identity()));
            Map<Integer, VisualRulebookPageCatalogModel.IconLocation> confirmedLocations =
                    confirmLocalizedIconCrops(
                            page.get(), summary, locations, owner, assistantRunId, capture);
            List<com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence> icons =
                    java.util.stream.IntStream.range(0, summary.iconOccurrences().size())
                            .mapToObj(index -> {
                                var location = confirmedLocations.get(index);
                                if (location == null) return null;
                                var icon = summary.iconOccurrences().get(index);
                                if (!VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                                        icon,
                                        location.x(),
                                        location.y(),
                                        location.width(),
                                        location.height())) return null;
                                return new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                                        icon.groupKey(),
                                        icon.name(),
                                        icon.visualDescription(),
                                        icon.explanation(),
                                        icon.evidenceText(),
                                        location.observedLabel(),
                                        icon.meaningStatus(),
                                        location.x(),
                                        location.y(),
                                        location.width(),
                                        location.height());
                            })
                            .filter(java.util.Objects::nonNull)
                            .toList();
            return new VisualRulebookPageCatalogModel.PageSummary(
                    summary.pageNumber(),
                    summary.printedTerms(),
                    summary.factualSummary(),
                    summary.keywords(),
                    summary.visualAnchors(),
                    icons,
                    summary.iconInventoryComplete(),
                    summary.sourceDependencies(),
                    summary.ruleGroupIdentifiers(),
                    summary.ruleGroupInventoryComplete(),
                    summary.quantityObservations());
        } catch (RuntimeException localizationFailure) {
            log.warn(
                    "Icon rectangle verification failed for document {}; keeping the page incomplete (failureType={})",
                    documentVersionId,
                    localizationFailure.getClass().getSimpleName());
            return withoutUnverifiedIcons(summary);
        }
    }

    private VisualRulebookPageCatalogModel.IconLocalizationDraft localizeIconsWithOneRepair(
            VisualRulebookPageCatalogModel.IconLocalizationRequest request,
            int pageNumber,
            UUID assistantRunId,
            CaptureHandle capture) {
        int estimatedInputTokens = Math.max(400, request.candidates().size() * 80);
        try {
            return invokeModel(
                    assistantRunId,
                    "localizeRulebookIcons|" + pageNumber,
                    estimatedInputTokens,
                    "Rulebook icon rectangles verified",
                    () -> capture.enabled()
                            ? visualCatalog.localizeIcons(
                                    request,
                                    capture,
                                    iconModelContext(assistantRunId))
                            : visualCatalog.localizeIcons(request),
                    result -> Math.max(1, result.locations().size() * 10));
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException firstFailure) {
            try {
                return invokeModel(
                        assistantRunId,
                        "localizeRulebookIcons|" + pageNumber + "|repair",
                        estimatedInputTokens,
                        "Rulebook icon rectangles verified after one repair",
                        () -> capture.enabled()
                                ? visualCatalog.localizeIcons(
                                        request,
                                        capture,
                                        iconModelContext(assistantRunId))
                                : visualCatalog.localizeIcons(request),
                        result -> Math.max(1, result.locations().size() * 10));
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
        }
    }

    private Map<Integer, VisualRulebookPageCatalogModel.IconLocation> confirmLocalizedIconCrops(
            PageImage page,
            VisualRulebookPageCatalogModel.PageSummary summary,
            Map<Integer, VisualRulebookPageCatalogModel.IconLocation> locations,
            String owner,
            UUID assistantRunId,
            CaptureHandle capture) {
        List<Integer> present = locations.values().stream()
                .filter(VisualRulebookPageCatalogModel.IconLocation::present)
                .map(VisualRulebookPageCatalogModel.IconLocation::candidateIndex)
                .sorted()
                .toList();
        if (present.isEmpty()) return Map.of();
        Map<Integer, VisualRulebookPageCatalogModel.IconLocation> confirmed = new LinkedHashMap<>();
        // One crop per request prevents the provider from binding a verdict or relative box to a sibling image.
        for (int offset = 0, batch = 1; offset < present.size(); offset++, batch++) {
            List<Integer> indexes = present.subList(offset, offset + 1);
            var request = new VisualRulebookPageCatalogModel.IconCropReviewRequest(
                    new PageImageInput(page.pageNumber(), page.mediaType(), page.content()),
                    indexes.stream().map(summary.iconOccurrences()::get).toList(),
                    indexes.stream().map(locations::get).toList(),
                    owner);
            var review = invokeModel(
                    assistantRunId,
                    "reviewRulebookIconCrops|" + summary.pageNumber() + "|" + batch,
                    Math.max(240, indexes.size() * 40),
                    "Localized rulebook icon crops reviewed",
                    () -> capture.enabled()
                            ? visualCatalog.reviewIconCrops(
                                    request,
                                    capture,
                                    iconModelContext(assistantRunId))
                            : visualCatalog.reviewIconCrops(request),
                    result -> Math.max(1, result.decisions().size() * 4));
            Set<Integer> returned = review.decisions().stream()
                    .map(VisualRulebookPageCatalogModel.IconCropDecision::candidateIndex)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (returned.size() != review.decisions().size() || !returned.equals(new LinkedHashSet<>(indexes))) {
                throw new IllegalArgumentException("visual icon crop review did not cover every candidate");
            }
            int reviewBatch = batch;
            review.decisions().stream()
                    .filter(VisualRulebookPageCatalogModel.IconCropDecision::matchesAppearance)
                    .forEach(decision -> {
                        VisualRulebookPageCatalogModel.IconLocation firstPass = new VisualRulebookPageCatalogModel.IconLocation(
                                decision.candidateIndex(),
                                true,
                                decision.x(),
                                decision.y(),
                                decision.width(),
                                decision.height(),
                                locations.get(decision.candidateIndex()).observedLabel());
                        // A first verifier can still return the whole badge when the pictogram is printed inside a
                        // colored field. Reinspect the accepted rectangle once at readable scale so the published
                        // crop converges on the smallest standalone mark without any game-specific vocabulary.
                        VisualRulebookPageCatalogModel.IconLocation tightened = reviewOneIconCrop(
                                page,
                                summary.iconOccurrences().get(decision.candidateIndex()),
                                firstPass,
                                owner,
                                assistantRunId,
                                summary.pageNumber(),
                                reviewBatch,
                                "tighten",
                                capture);
                        if (tightened != null) confirmed.put(decision.candidateIndex(), tightened);
                    });
        }
        return Map.copyOf(confirmed);
    }

    private VisualRulebookPageCatalogModel.IconLocation reviewOneIconCrop(
            PageImage page,
            VisualRulebookPageFacts.IconOccurrence candidate,
            VisualRulebookPageCatalogModel.IconLocation location,
            String owner,
            UUID assistantRunId,
            int pageNumber,
            int batch,
            String pass,
            CaptureHandle capture) {
        var request = new VisualRulebookPageCatalogModel.IconCropReviewRequest(
                new PageImageInput(page.pageNumber(), page.mediaType(), page.content()),
                List.of(candidate),
                List.of(location),
                owner);
        var review = invokeModel(
                assistantRunId,
                "reviewRulebookIconCrops|" + pageNumber + "|" + batch + "|" + pass,
                240,
                "Localized rulebook icon crop reviewed",
                () -> capture.enabled()
                        ? visualCatalog.reviewIconCrops(
                                request,
                                capture,
                                iconModelContext(assistantRunId))
                        : visualCatalog.reviewIconCrops(request),
                result -> Math.max(1, result.decisions().size() * 4));
        if (review.decisions().size() != 1 || review.decisions().getFirst().candidateIndex() != location.candidateIndex()) {
            throw new IllegalArgumentException("visual icon crop review did not cover the candidate");
        }
        var decision = review.decisions().getFirst();
        return decision.matchesAppearance()
                ? new VisualRulebookPageCatalogModel.IconLocation(
                        decision.candidateIndex(),
                        true,
                        decision.x(),
                        decision.y(),
                        decision.width(),
                        decision.height(),
                        location.observedLabel())
                : null;
    }

    private static VisualRulebookPageCatalogModel.PageSummary withoutUnverifiedIcons(
            VisualRulebookPageCatalogModel.PageSummary summary) {
        return new VisualRulebookPageCatalogModel.PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                summary.visualAnchors(),
                List.of(),
                false,
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.quantityObservations());
    }

    private VisualRulebookPageCatalogModel.CatalogDraft catalogBatch(
            UUID documentVersionId,
            List<Integer> batch,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            String operation,
            CaptureHandle capture) {
        List<PageImageInput> images = pageImages.read(documentVersionId, new LinkedHashSet<>(batch)).stream()
                .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
        return invokeModel(
                assistantRunId,
                operation,
                Math.max(1, images.size() * 800),
                "Rulebook visual batch interpreted",
                () -> capture.enabled()
                        ? visualCatalog.summarize(request, capture, iconModelContext(assistantRunId))
                        : visualCatalog.summarize(request),
                this::catalogOutputTokens);
    }

    static VisualRulebookPageCatalogModel.CatalogDraft awaitCatalog(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future, Duration timeout) {
        return awaitCatalogBefore(future, timeout, catalogWindowDeadline(timeout));
    }

    private static long catalogWindowDeadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static VisualRulebookPageCatalogModel.CatalogDraft awaitCatalogBefore(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future,
            Duration configuredTimeout,
            long deadlineNanos) {
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "visual rulebook catalog timed out after " + configuredTimeout.toSeconds() + " seconds",
                    slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
    }

    static ProgressiveTeachingStartDraft awaitProgressiveTeachingStart(
            Future<ProgressiveTeachingStartDraft> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "progressive visual teaching start timed out after " + timeout.toSeconds() + " seconds",
                    slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("progressive visual teaching start was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("progressive visual teaching start failed", failed.getCause());
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
                        + page.keywords().stream().mapToInt(String::length).sum()
                        + page.ruleGroupIdentifiers().stream().mapToInt(String::length).sum()
                        + page.quantityObservations().stream()
                                .mapToInt(observation -> observation.evidenceText().length())
                                .sum()
                        + page.iconOccurrences().stream()
                                .mapToInt(icon -> icon.name().length()
                                        + icon.visualDescription().length()
                                        + icon.explanation().length()
                                        + icon.evidenceText().length())
                                .sum())
                .sum();
        return Math.max(1, characters / 4);
    }

    private int progressiveTeachingStartOutputTokens(ProgressiveTeachingStartDraft start) {
        int characters = start.selectedPageFacts().printedTerms().length()
                + start.selectedPageFacts().factualSummary().length()
                + start.selectedPageFacts().keywords().stream().mapToInt(String::length).sum()
                + start.selectedPageFacts().quantityObservations().stream()
                        .mapToInt(observation -> observation.evidenceText().length())
                        .sum();
        characters += start.pages().stream()
                .mapToInt(page -> page.visibleHeading().length()
                        + page.visibleTerms().stream().mapToInt(String::length).sum()
                        + page.coverageTags().stream().mapToInt(String::length).sum()
                        + page.sourceDependencies().stream()
                                .mapToInt(dependency -> dependency.title().length()
                                        + dependency.missingCoverageTags().stream().mapToInt(String::length).sum())
                                .sum())
                .sum();
        return Math.max(1, characters / 4);
    }

    private TraceEventContext visualModelContext(UUID assistantRunId) {
        return TraceEventContext.create(
                java.time.Instant.now(),
                JourneyStage.TEACHING,
                UUID.randomUUID(),
                assistantRunId,
                assistantRunId == null
                        ? null
                        : new ResourceRef(ResourceType.ASSISTANT_RUN, assistantRunId));
    }

    private TraceEventContext iconModelContext(UUID visualRunId) {
        return TraceEventContext.create(
                java.time.Instant.now(),
                JourneyStage.TEACHING,
                UUID.randomUUID(),
                visualRunId,
                visualRunId == null
                        ? null
                        : new ResourceRef(ResourceType.VISUAL_RUN, visualRunId));
    }
}
