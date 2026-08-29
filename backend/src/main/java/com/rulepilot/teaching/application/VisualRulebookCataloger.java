package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.shared.AsyncContextPropagation;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final Duration TEACHING_RUN_ATTEMPT_RETENTION = Duration.ofHours(1);
    private static final int TEACHING_RUN_ATTEMPT_CAPACITY = 4_096;
    private final DocumentPageImages pageImages;
    private final VisualRulebookPageCatalogModel visualCatalog;
    private final VisualRulebookPageFacts visualFacts;
    private final AuditedAgentInvocations invocations;
    private final Duration visualCatalogTimeout;
    private final int visualCoverageProbePages;
    private final int visualRequestParallelism;
    private final int teachingSemanticRequestParallelism;
    private final ObservationRegistry observations;
    // This singleton owns the entire paid semantic/repair/replay attempt so concurrent sections share it per page.
    private final ConcurrentMap<TeachingPageFactFlightKey, CompletableFuture<Void>> teachingPageFactFlights =
            new ConcurrentHashMap<>();
    // A non-durable outcome is reusable only inside the run that already paid for it. Successfully persisted facts
    // leave this map immediately and remain reusable through visualFacts; a different run receives a fresh attempt.
    private final TeachingPageFactRunAttemptCache teachingPageFactRunAttempts;

    @Autowired
    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages,
            @Value("${rulepilot.visual.request-parallelism:10}") int visualRequestParallelism,
            @Value("${rulepilot.visual.semantic-request-parallelism:4}") int teachingSemanticRequestParallelism,
            ObservationRegistry observations) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                teachingSemanticRequestParallelism,
                observations,
                Clock.systemUTC(),
                TEACHING_RUN_ATTEMPT_RETENTION,
                TEACHING_RUN_ATTEMPT_CAPACITY);
    }

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            Duration visualCatalogTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism,
            int teachingSemanticRequestParallelism,
            ObservationRegistry observations,
            Clock teachingRunAttemptClock,
            Duration teachingRunAttemptRetention,
            int teachingRunAttemptCapacity) {
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
        if (visualRequestParallelism < 1 || visualRequestParallelism > 10) {
            throw new IllegalArgumentException("visual request parallelism must be between one and ten");
        }
        if (teachingSemanticRequestParallelism < 1 || teachingSemanticRequestParallelism > 10) {
            throw new IllegalArgumentException("Teaching semantic request parallelism must be between one and ten");
        }
        if (teachingRunAttemptClock == null
                || teachingRunAttemptRetention == null
                || teachingRunAttemptRetention.isZero()
                || teachingRunAttemptRetention.isNegative()
                || teachingRunAttemptCapacity < 1) {
            throw new IllegalArgumentException("Teaching run-attempt retention is invalid");
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
        this.visualRequestParallelism = visualRequestParallelism;
        this.teachingSemanticRequestParallelism = teachingSemanticRequestParallelism;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        this.teachingPageFactRunAttempts = new TeachingPageFactRunAttemptCache(
                teachingRunAttemptClock, teachingRunAttemptRetention, teachingRunAttemptCapacity);
    }

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            Duration visualCatalogTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism,
            int teachingSemanticRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                teachingSemanticRequestParallelism,
                ObservationRegistry.NOOP);
    }

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            Duration visualCatalogTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                visualRequestParallelism);
    }

    boolean available(String owner) {
        return visualCatalog.available(owner);
    }

    /**
     * Returns durable page-owned Teaching facts, interpreting only pages without a current complete typed ledger.
     * This is the single semantic/retry owner shared by preparation and chapter generation.
     */
    List<PageFact> ensureTeachingPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            int totalPageCount,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("Teaching page-fact request is invalid");
        }
        int highestRequestedPage = pageNumbers.stream().mapToInt(Integer::intValue).max().orElseThrow();
        if (totalPageCount < highestRequestedPage) {
            throw new IllegalArgumentException("rulebook page total cannot be lower than a requested page number");
        }
        List<PageFact> cached = findVisualFacts(documentVersionId, pageNumbers);
        Set<Integer> missing = VisualRulebookCatalogPolicy.missingPages(pageNumbers, cached);
        if (!missing.isEmpty() && available(owner)) {
            catalogTeachingPageFacts(
                    documentVersionId,
                    missing,
                    totalPageCount,
                    rulebookTitle,
                    owner,
                    assistantRunId);
        }
        return findVisualFacts(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .sorted(java.util.Comparator.comparingInt(PageFact::pageNumber))
                .toList();
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
        List<PageFact> cached = findVisualFacts(documentVersionId, requestedPages);
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
                        documentVersionId,
                        requiredFacts,
                        sourcePageTotal(documentPages),
                        rulebookTitle,
                        owner,
                        assistantRunId);
        List<PageFact> facts = cached.isEmpty()
                ? VisualRulebookCatalogPolicy.mergeFreshFacts(cached, fresh)
                : VisualRulebookCatalogPolicy.backfillAnchors(cached, fresh);
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
        Set<Integer> selected = VisualOutlineEvidencePolicy.unownedSparseVisualCoveragePageNumbers(
                outline, documentPages, visualCoverageProbePages);
        if (selected.isEmpty()) return List.of();
        List<PageFact> cached = findVisualFacts(documentVersionId, selected);
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
                            documentVersionId,
                            missing,
                            sourcePageTotal(documentPages),
                            rulebookTitle,
                            owner,
                            assistantRunId);
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Sparse-page visual coverage probe skipped for document {} pages {}",
                    documentVersionId,
                    missing,
                    visualFailure);
            return cached;
        }
        if (!fresh.isEmpty()) {
            mergeVisualFacts(documentVersionId, fresh);
            log.info(
                    "Sparse-page visual coverage probe stored document {} pages {}",
                    documentVersionId,
                    fresh.stream().map(PageFact::pageNumber).toList());
        }
        return VisualRulebookCatalogPolicy.backfillAnchors(cached, fresh);
    }

    /** Builds the minimum durable, page-bound evidence ledger needed by outline and lesson composition. */
    private List<PageFact> catalogTeachingPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            int totalPageCount,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        if (pageNumbers.isEmpty()) {
            throw new IllegalArgumentException("rulebook has no pages to catalog for teaching");
        }
        Map<TeachingPageFactKey, CompletableFuture<Void>> requestedFlights = new LinkedHashMap<>();
        Set<Integer> ownedPages = new LinkedHashSet<>();
        for (int pageNumber : pageNumbers.stream().sorted().toList()) {
            TeachingPageFactKey key = new TeachingPageFactKey(
                    documentVersionId, pageNumber, PageFact.CURRENT_SCHEMA_VERSION);
            CompletableFuture<Void> candidate = new CompletableFuture<>();
            TeachingPageFactAttemptKey attemptKey = assistantRunId == null
                    ? null
                    : new TeachingPageFactAttemptKey(assistantRunId, key);
            if (attemptKey != null) {
                CompletableFuture<Void> settledOrRunning = teachingPageFactRunAttempts.claim(attemptKey, candidate);
                if (settledOrRunning != null) {
                    requestedFlights.put(key, settledOrRunning);
                    continue;
                }
                candidate.whenComplete((ignored, failure) ->
                        settleTeachingPageFactRunAttempt(attemptKey, key, candidate, failure));
            }
            TeachingPageFactFlightKey flightKey = new TeachingPageFactFlightKey(assistantRunId, key);
            CompletableFuture<Void> active = teachingPageFactFlights.putIfAbsent(flightKey, candidate);
            if (active == null) {
                requestedFlights.put(key, candidate);
                ownedPages.add(pageNumber);
            } else {
                // A null run id is the compatibility path and may share its active owner globally. Non-null runs
                // reach this branch only when joining the same run-scoped flight; run-specific budget, cancellation,
                // deadline, and owner failures must never settle an independent run.
                if (attemptKey == null) {
                    requestedFlights.put(key, active);
                } else {
                    active.whenComplete((ignored, failure) -> {
                        if (failure == null) candidate.complete(null);
                        else candidate.completeExceptionally(failure);
                    });
                    requestedFlights.put(key, candidate);
                }
            }
        }
        if (!ownedPages.isEmpty()) {
            completeOwnedTeachingPageFlights(
                    documentVersionId,
                    ownedPages,
                    totalPageCount,
                    rulebookTitle,
                    owner,
                    assistantRunId,
                    requestedFlights);
        }
        requestedFlights.values().forEach(this::awaitTeachingPageFactFlight);
        return findVisualFacts(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    private void settleTeachingPageFactRunAttempt(
            TeachingPageFactAttemptKey attemptKey,
            TeachingPageFactKey pageFactKey,
            CompletableFuture<Void> attempt,
            Throwable failure) {
        boolean durable = false;
        if (failure == null) {
            try {
                durable = hasReusableTeachingPageFact(pageFactKey);
            } catch (RuntimeException lookupFailure) {
                log.warn(
                        "Could not confirm persisted Teaching page fact for document {} page {}; retaining the bounded run outcome",
                        pageFactKey.documentVersionId(),
                        pageFactKey.pageNumber(),
                        lookupFailure);
            }
        }
        teachingPageFactRunAttempts.settle(attemptKey, attempt, durable);
    }

    private boolean hasReusableTeachingPageFact(TeachingPageFactKey key) {
        Set<Integer> page = Set.of(key.pageNumber());
        return VisualRulebookCatalogPolicy.missingPages(
                        page, findVisualFacts(key.documentVersionId(), page))
                .isEmpty();
    }

    private void completeOwnedTeachingPageFlights(
            UUID documentVersionId,
            Set<Integer> ownedPages,
            int totalPageCount,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            Map<TeachingPageFactKey, CompletableFuture<Void>> requestedFlights) {
        Map<TeachingPageFactKey, CompletableFuture<Void>> ownedFlights = requestedFlights.entrySet().stream()
                .filter(entry -> ownedPages.contains(entry.getKey().pageNumber()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        try {
            Set<Integer> stillMissing = VisualRulebookCatalogPolicy.missingPages(
                    ownedPages, findVisualFacts(documentVersionId, ownedPages));
            if (!stillMissing.isEmpty()) {
                catalogOwnedTeachingPageFacts(
                        documentVersionId,
                        stillMissing,
                        totalPageCount,
                        rulebookTitle,
                        owner,
                        assistantRunId);
            }
            ownedFlights.values().forEach(flight -> flight.complete(null));
        } catch (RuntimeException | Error failure) {
            ownedFlights.values().forEach(flight -> flight.completeExceptionally(failure));
            throw failure;
        } finally {
            ownedFlights.forEach((key, flight) -> teachingPageFactFlights.remove(
                    new TeachingPageFactFlightKey(assistantRunId, key), flight));
        }
    }

    private void awaitTeachingPageFactFlight(CompletableFuture<Void> flight) {
        try {
            flight.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Teaching page facts", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Teaching page-fact owner failed", cause);
        }
    }

    private List<PageFact> catalogOwnedTeachingPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            int totalPageCount,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        List<List<Integer>> batches = VisualRulebookCatalogPolicy.teachingStartupBatches(orderedPages);
        if (batches.isEmpty()) throw new IllegalArgumentException("rulebook has no pages to catalog for teaching");
        if (totalPageCount < orderedPages.getLast()) {
            throw new IllegalArgumentException("rulebook page total cannot be lower than a requested page number");
        }
        // A vision-capable request already receives the complete source image. Contract repair reuses that image
        // with the validator-owned error code; a second OCR model would duplicate the read without repairing JSON.
        Map<Integer, TeachingCatalogRepairCode> contractViolations = new LinkedHashMap<>();
        Set<Integer> transientFailures = new LinkedHashSet<>();
        List<Integer> missingPages = inspectTeachingBatches(
                documentVersionId,
                batches,
                owner,
                rulebookTitle,
                assistantRunId,
                totalPageCount,
                index -> "inspectTeachingVisualPage|" + orderedPages.get(index) + "|" + totalPageCount,
                Map.of(),
                contractViolations,
                transientFailures,
                "single_call");
        List<Integer> repairPages = missingPages.stream()
                .filter(contractViolations::containsKey)
                .distinct()
                .sorted()
                .toList();
        if (!repairPages.isEmpty()) {
            inspectTeachingBatches(
                    documentVersionId,
                    repairPages.stream().map(List::of).toList(),
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    totalPageCount,
                    index -> "inspectTeachingVisualRepair|" + repairPages.get(index) + "|" + totalPageCount + "|"
                            + contractViolations.get(repairPages.get(index)),
                    contractViolations,
                    new LinkedHashMap<>(),
                    new LinkedHashSet<>(),
                    "contract_repair");
        }
        List<Integer> retryPages = missingPages.stream()
                .filter(transientFailures::contains)
                .distinct()
                .sorted()
                .toList();
        if (!retryPages.isEmpty()) {
            inspectTeachingBatches(
                    documentVersionId,
                    retryPages.stream().map(List::of).toList(),
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    totalPageCount,
                    index -> "inspectTeachingVisualRetry|" + retryPages.get(index) + "|" + totalPageCount,
                    Map.of(),
                    new LinkedHashMap<>(),
                    new LinkedHashSet<>(),
                    "transient_replay");
        }
        return findVisualFacts(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    private List<Integer> inspectTeachingBatches(
            UUID documentVersionId,
            List<List<Integer>> batches,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            int totalPageCount,
            IntFunction<String> operationForIndex,
            Map<Integer, TeachingCatalogRepairCode> requestedRepairs,
            Map<Integer, TeachingCatalogRepairCode> contractViolations,
            Set<Integer> transientFailures,
            String retryKind) {
        List<Integer> missingPages = new ArrayList<>();
        int parallelism = Math.min(teachingSemanticRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, batches.size());
            List<Integer> windowPages = batches.subList(windowStart, windowEnd).stream()
                    .flatMap(List::stream)
                    .distinct()
                    .toList();
            Map<Integer, PageImageInput> windowImages;
            boolean isolatePageReads;
            try {
                windowImages = readTeachingPageImages(documentVersionId, windowPages).stream()
                        .collect(Collectors.toMap(
                                PageImageInput::pageNumber,
                                image -> image,
                                (first, ignored) -> first,
                                LinkedHashMap::new));
                isolatePageReads = false;
            } catch (RuntimeException windowReadFailure) {
                // A corrupt or temporarily unreadable page must not erase independent page work. Retry the active
                // window as page-owned reads so the ordinary batched storage path stays fast while the exact bad page
                // becomes an unavailable ledger entry and its neighbours can still complete.
                log.warn(
                        "Teaching-start page-image window {} could not be read for document {}; isolating pages",
                        windowPages,
                        documentVersionId,
                        windowReadFailure);
                windowImages = Map.of();
                isolatePageReads = true;
            }
            Map<Integer, PageImageInput> prefetchedImages = windowImages;
            boolean readEachPage = isolatePageReads;
            ExecutorService executor = AsyncContextPropagation.executorService(
                    Executors.newFixedThreadPool(windowEnd - windowStart));
            try {
                List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                for (int index = windowStart; index < windowEnd; index++) {
                    int batchIndex = index;
                    futures.add(executor.submit(() -> observeStage("semantic", retryKind, () -> {
                        List<Integer> batch = batches.get(batchIndex);
                        Map<Integer, PageImageInput> sourceImagesByPage = readEachPage
                                ? readTeachingPageImages(documentVersionId, batch).stream()
                                        .collect(Collectors.toMap(
                                                PageImageInput::pageNumber,
                                                image -> image,
                                                (first, ignored) -> first,
                                                LinkedHashMap::new))
                                : prefetchedImages;
                        return catalogTeachingBatch(
                                batch,
                                owner,
                                rulebookTitle,
                                assistantRunId,
                                operationForIndex.apply(batchIndex),
                                requestedRepairs.get(batch.getFirst()),
                                sourceImagesByPage);
                    })));
                }
                long windowDeadlineNanos = catalogWindowDeadline(visualCatalogTimeout);
                for (int offset = 0; offset < futures.size(); offset++) {
                    int batchIndex = windowStart + offset;
                    List<Integer> batch = batches.get(batchIndex);
                    List<VisualRulebookPageCatalogModel.PageSummary> completed;
                    try {
                        var draft = awaitCatalogBefore(
                                futures.get(offset), visualCatalogTimeout, windowDeadlineNanos);
                        Map<Integer, Long> returnedCounts = draft.pages().stream().collect(Collectors.groupingBy(
                                VisualRulebookPageCatalogModel.PageSummary::pageNumber,
                                Collectors.counting()));
                        completed = draft.pages().stream()
                                .filter(summary -> batch.contains(summary.pageNumber()))
                                .filter(summary -> returnedCounts.get(summary.pageNumber()) == 1)
                                .map(VisualRulebookCatalogPolicy::teachingStartupFact)
                                .sorted(java.util.Comparator.comparingInt(
                                        summary -> batch.indexOf(summary.pageNumber())))
                                .toList();
                    } catch (AgentExecutionStoppedException stopped) {
                        throw stopped;
                    } catch (TeachingCatalogContractViolation violation) {
                        batch.forEach(page -> contractViolations.put(page, violation.repairCode()));
                        log.warn(
                                "Teaching-start visual interpretation rejected batch {} for document {} with repair code {}",
                                batch,
                                documentVersionId,
                                violation.repairCode());
                        missingPages.addAll(batch);
                        continue;
                    } catch (RuntimeException failedBatch) {
                        if (catalogInterrupted(failedBatch)) throw failedBatch;
                        if (catalogTimedOut(failedBatch)) {
                            if (assistantRunId != null) {
                                invocations.stopRunning(
                                        assistantRunId,
                                        operationForIndex.apply(batchIndex),
                                        ActivityOutcome.FAILED,
                                        "Teaching visual batch timed out; retaining completed page facts");
                            }
                        } else if (catalogTransient(failedBatch)) transientFailures.addAll(batch);
                        log.warn(
                                "Teaching-start visual interpretation skipped failed batch {} for document {}",
                                batch,
                                documentVersionId,
                                failedBatch);
                        missingPages.addAll(batch);
                        continue;
                    }
                    // Storage owns a separate failure boundary. Once the paid response has passed the typed contract,
                    // a write failure must propagate instead of repeating the model call.
                    observeStage("persist", retryKind, () -> {
                        persistCompletedFacts(documentVersionId, completed);
                        return null;
                    });
                    if (assistantRunId != null) {
                        completed.forEach(summary -> invocations.record(
                                assistantRunId,
                                ActivityType.VALIDATION,
                                "persistTeachingVisualPage|" + summary.pageNumber() + "|" + totalPageCount,
                                ActivityOutcome.SUCCEEDED,
                                "Stored typed rule groups for visual page " + summary.pageNumber()));
                    }
                    Set<Integer> completedPages = completed.stream()
                            .map(VisualRulebookPageCatalogModel.PageSummary::pageNumber)
                            .collect(Collectors.toSet());
                    batch.stream().filter(page -> !completedPages.contains(page)).forEach(page -> {
                        missingPages.add(page);
                        contractViolations.put(page, TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH);
                    });
                }
            } finally {
                executor.shutdownNow();
            }
        }
        return missingPages;
    }

    private VisualRulebookPageCatalogModel.CatalogDraft catalogTeachingBatch(
            List<Integer> batch,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            String operation,
            TeachingCatalogRepairCode repairCode,
            Map<Integer, PageImageInput> sourceImagesByPage) {
        List<PageImageInput> images = batch.stream()
                .map(sourceImagesByPage::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
        return invokeModel(
                assistantRunId,
                operation,
                Math.max(1, images.size() * 600),
                teachingStartupSuccessSummary(owner),
                () -> repairCode == null
                        ? visualCatalog.summarizeForTeaching(request)
                        : visualCatalog.repairTeachingCatalog(request, repairCode),
                this::catalogOutputTokens);
    }

    private String teachingStartupSuccessSummary(String owner) {
        return visualCatalog.teachingStartupExecutionIdentity(owner)
                .map(identity -> "Teaching-start page facts interpreted via " + identity.auditLabel())
                .orElse("Teaching-start page facts interpreted");
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

    /**
     * Keep already-completed page work after a later provider timeout, cancellation, or process restart. A later
     * attempt can then request only the unfinished source pages instead of re-reading an entire photographed book.
     */
    private void persistCompletedFacts(
            UUID documentVersionId, List<VisualRulebookPageCatalogModel.PageSummary> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Set<Integer> observedPages = observations.stream()
                    .map(VisualRulebookPageCatalogModel.PageSummary::pageNumber)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Integer, VisualRulebookPageCatalogModel.PageSummary> accumulated = new LinkedHashMap<>();
            findVisualFacts(documentVersionId, observedPages).stream()
                    .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                    .map(VisualRulebookCataloger::pageSummary)
                    .forEach(summary -> accumulated.put(summary.pageNumber(), summary));
            observations.forEach(summary -> accumulated.merge(
                    summary.pageNumber(), summary, VisualRulebookCatalogPolicy::mergePersistedPageObservation));
            mergeVisualFacts(
                    documentVersionId,
                    accumulated.values().stream()
                            .map(VisualRulebookCataloger::pageFact)
                            .toList());
        } catch (TeachingPreparationStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new TeachingPreparationStorageException(failure);
        }
    }

    private void mergeVisualFacts(UUID documentVersionId, List<PageFact> facts) {
        try {
            visualFacts.merge(documentVersionId, facts);
        } catch (TeachingPreparationStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new TeachingPreparationStorageException(failure);
        }
    }

    private List<PageFact> findVisualFacts(UUID documentVersionId, Set<Integer> pageNumbers) {
        try {
            return visualFacts.find(documentVersionId, pageNumbers);
        } catch (TeachingPreparationStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new TeachingPreparationStorageException(failure);
        }
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
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
    }

    private static boolean catalogTimedOut(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof InterruptedIOException) return true;
        }
        return false;
    }

    private static boolean catalogInterrupted(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    private static boolean catalogTransient(RuntimeException failure) {
        if (catalogInterrupted(failure) || catalogTimedOut(failure)) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientAiException) return true;
        }
        return false;
    }

    private static int sourcePageTotal(List<DocumentProcessing.PageView> documentPages) {
        return documentPages.stream()
                .mapToInt(DocumentProcessing.PageView::pageNumber)
                .max()
                .orElse(0);
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

    private <T> T observeStage(String stage, String retryKind, Supplier<T> work) {
        return observeStage(stage, retryKind, work, ignored -> "completed");
    }

    private <T> T observeStage(
            String stage,
            String retryKind,
            Supplier<T> work,
            Function<T, String> successfulOutcome) {
        Observation observation = Observation.createNotStarted("rulepilot.teaching.visual_page_catalog", observations)
                .contextualName("teaching-visual-page-catalog")
                .lowCardinalityKeyValue("stage", stage)
                .lowCardinalityKeyValue("retry_kind", retryKind)
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            T result = work.get();
            observation.lowCardinalityKeyValue("outcome", successfulOutcome.apply(result));
            return result;
        } catch (RuntimeException failure) {
            observation.lowCardinalityKeyValue("outcome", "failed");
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private int catalogOutputTokens(VisualRulebookPageCatalogModel.CatalogDraft catalog) {
        int characters = catalog.pages().stream()
                .mapToInt(page -> page.printedTerms().length()
                        + page.factualSummary().length()
                        + page.keywords().stream().mapToInt(String::length).sum()
                        + page.ruleGroupIdentifiers().stream().mapToInt(String::length).sum()
                        + page.quantityObservations().stream()
                                .mapToInt(observation -> observation.evidenceText().length())
                                .sum())
                .sum();
        return Math.max(1, characters / 4);
    }

    private record TeachingPageFactKey(
            UUID documentVersionId,
            int pageNumber,
            int schemaVersion) {}

    private record TeachingPageFactFlightKey(
            UUID assistantRunId,
            TeachingPageFactKey pageFactKey) {}

    private record TeachingPageFactAttemptKey(
            UUID assistantRunId,
            TeachingPageFactKey pageFactKey) {}

    /**
     * Retains only settled, non-durable outcomes long enough for later sections in the same bounded run to reuse
     * them. Running attempts are never evicted; completed entries expire lazily and the settled population has a
     * hard bound so abandoned run ids cannot grow a singleton for the lifetime of the process.
     */
    private static final class TeachingPageFactRunAttemptCache {

        private final Clock clock;
        private final Duration retention;
        private final int settledCapacity;
        private final LinkedHashMap<TeachingPageFactAttemptKey, RunAttemptEntry> attempts = new LinkedHashMap<>();
        private int settledCount;

        private TeachingPageFactRunAttemptCache(Clock clock, Duration retention, int settledCapacity) {
            this.clock = clock;
            this.retention = retention;
            this.settledCapacity = settledCapacity;
        }

        private synchronized CompletableFuture<Void> claim(
                TeachingPageFactAttemptKey key, CompletableFuture<Void> candidate) {
            evictExpired(clock.instant());
            RunAttemptEntry existing = attempts.get(key);
            if (existing != null) return existing.attempt();
            attempts.put(key, new RunAttemptEntry(candidate, null));
            return null;
        }

        private synchronized void settle(
                TeachingPageFactAttemptKey key, CompletableFuture<Void> attempt, boolean durable) {
            RunAttemptEntry existing = attempts.get(key);
            if (existing == null || existing.attempt() != attempt) return;
            if (durable) {
                attempts.remove(key);
                return;
            }
            attempts.remove(key);
            attempts.put(key, new RunAttemptEntry(attempt, clock.instant().plus(retention)));
            settledCount++;
            evictExpired(clock.instant());
            evictOverflow();
        }

        private void evictExpired(Instant now) {
            Iterator<Map.Entry<TeachingPageFactAttemptKey, RunAttemptEntry>> iterator =
                    attempts.entrySet().iterator();
            while (iterator.hasNext()) {
                RunAttemptEntry entry = iterator.next().getValue();
                if (entry.settledUntil() != null && !entry.settledUntil().isAfter(now)) {
                    iterator.remove();
                    settledCount--;
                }
            }
        }

        private void evictOverflow() {
            if (settledCount <= settledCapacity) return;
            Iterator<Map.Entry<TeachingPageFactAttemptKey, RunAttemptEntry>> iterator =
                    attempts.entrySet().iterator();
            while (settledCount > settledCapacity && iterator.hasNext()) {
                RunAttemptEntry entry = iterator.next().getValue();
                if (entry.settledUntil() == null) continue;
                iterator.remove();
                settledCount--;
            }
        }
    }

    private record RunAttemptEntry(
            CompletableFuture<Void> attempt,
            Instant settledUntil) {}
}
