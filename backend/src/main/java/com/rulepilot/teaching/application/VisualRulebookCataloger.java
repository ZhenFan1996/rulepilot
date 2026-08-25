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
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
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
    private final Duration progressiveStartTimeout;
    private final int visualCoverageProbePages;
    private final int visualRequestParallelism;
    private final int teachingSemanticRequestParallelism;
    private final int teachingOcrRequestParallelism;
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
            @Value("${rulepilot.visual.progressive-start-timeout:PT35S}") Duration progressiveStartTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages,
            @Value("${rulepilot.visual.request-parallelism:10}") int visualRequestParallelism,
            @Value("${rulepilot.visual.semantic-request-parallelism:4}") int teachingSemanticRequestParallelism,
            @Value("${rulepilot.visual.ocr-request-parallelism:10}") int teachingOcrRequestParallelism,
            ObservationRegistry observations) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                progressiveStartTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                teachingSemanticRequestParallelism,
                teachingOcrRequestParallelism,
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
            Duration progressiveStartTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism,
            int teachingSemanticRequestParallelism,
            int teachingOcrRequestParallelism,
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
        if (teachingSemanticRequestParallelism < 1 || teachingSemanticRequestParallelism > 10) {
            throw new IllegalArgumentException("Teaching semantic request parallelism must be between one and ten");
        }
        if (teachingOcrRequestParallelism < 1 || teachingOcrRequestParallelism > 10) {
            throw new IllegalArgumentException("Teaching OCR request parallelism must be between one and ten");
        }
        if (teachingRunAttemptClock == null
                || teachingRunAttemptRetention == null
                || teachingRunAttemptRetention.isZero()
                || teachingRunAttemptRetention.isNegative()
                || teachingRunAttemptCapacity < 1) {
            throw new IllegalArgumentException("Teaching run-attempt retention is invalid");
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.progressiveStartTimeout = progressiveStartTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
        this.visualRequestParallelism = visualRequestParallelism;
        this.teachingSemanticRequestParallelism = teachingSemanticRequestParallelism;
        this.teachingOcrRequestParallelism = teachingOcrRequestParallelism;
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
            Duration progressiveStartTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism,
            int teachingSemanticRequestParallelism,
            int teachingOcrRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                progressiveStartTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                teachingSemanticRequestParallelism,
                teachingOcrRequestParallelism,
                ObservationRegistry.NOOP);
    }

    VisualRulebookCataloger(
            DocumentPageImages pageImages,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            AuditedAgentInvocations invocations,
            Duration visualCatalogTimeout,
            Duration progressiveStartTimeout,
            int visualCoverageProbePages,
            int visualRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
                progressiveStartTimeout,
                visualCoverageProbePages,
                visualRequestParallelism,
                visualRequestParallelism,
                visualRequestParallelism);
    }

    boolean available(String owner) {
        return visualCatalog.available(owner);
    }

    /**
     * Returns durable page-owned Teaching facts, interpreting only pages without a current complete typed ledger.
     * This is the single semantic/retry owner shared by preparation, prefetch, and chapter generation.
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
        List<PageFact> cached = visualFacts.find(documentVersionId, pageNumbers);
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
        return visualFacts.find(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .sorted(java.util.Comparator.comparingInt(PageFact::pageNumber))
                .toList();
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
        ExecutorService executor =
                AsyncContextPropagation.executorService(Executors.newSingleThreadExecutor());
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
                    "Progressive visual Teaching start was rejected for document {}; retaining complete preparation path",
                    documentVersionId,
                    invalidStart);
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
            List<PageFact> inspected =
                    catalogPageFacts(documentVersionId, pagesToInspect, rulebookTitle, owner, assistantRunId, true);
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
                        documentVersionId,
                        requiredFacts,
                        sourcePageTotal(documentPages),
                        rulebookTitle,
                        owner,
                        assistantRunId);
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
            visualFacts.merge(documentVersionId, fresh);
            log.info(
                    "Sparse-page visual coverage probe stored document {} pages {}",
                    documentVersionId,
                    fresh.stream().map(PageFact::pageNumber).toList());
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
        return visualFacts.find(documentVersionId, pageNumbers).stream()
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
                        page, visualFacts.find(key.documentVersionId(), page))
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
                    ownedPages, visualFacts.find(documentVersionId, ownedPages));
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
        // A vision-capable semantic request already receives the complete source image. Paying for OCR before every
        // cold page duplicated the same read and made the ordinary path two serial model calls. Start with one
        // image-to-typed-facts call. OCR is reserved for the mutually exclusive typed-repair branch below, where it
        // materially changes the failed request; a transient replay keeps the original image-only request.
        Map<Integer, TeachingCatalogRepairCode> contractViolations = new LinkedHashMap<>();
        Set<Integer> transientFailures = new LinkedHashSet<>();
        List<Integer> missingPages = inspectTeachingBatches(
                documentVersionId,
                batches,
                owner,
                rulebookTitle,
                assistantRunId,
                index -> "inspectTeachingVisualPage|" + orderedPages.get(index) + "|" + totalPageCount,
                Map.of(),
                contractViolations,
                transientFailures,
                Map.of(),
                "single_call");
        List<Integer> repairPages = missingPages.stream()
                .filter(contractViolations::containsKey)
                .distinct()
                .sorted()
                .toList();
        if (!repairPages.isEmpty()) {
            Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> repairTranscripts = transcribeTeachingPages(
                    documentVersionId,
                    repairPages,
                    owner,
                    assistantRunId,
                    totalPageCount,
                    "ocr_repair");
            inspectTeachingBatches(
                    documentVersionId,
                    repairPages.stream().map(List::of).toList(),
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    index -> "inspectTeachingVisualRepair|" + repairPages.get(index) + "|" + totalPageCount + "|"
                            + contractViolations.get(repairPages.get(index)),
                    contractViolations,
                    new LinkedHashMap<>(),
                    new LinkedHashSet<>(),
                    repairTranscripts,
                    "ocr_repair");
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
                    index -> "inspectTeachingVisualRetry|" + retryPages.get(index) + "|" + totalPageCount,
                    Map.of(),
                    new LinkedHashMap<>(),
                    new LinkedHashSet<>(),
                    Map.of(),
                    "transient_replay");
        }
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
            Map<Integer, TeachingCatalogRepairCode> requestedRepairs,
            Map<Integer, TeachingCatalogRepairCode> contractViolations,
            Set<Integer> transientFailures,
            Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> transcripts,
            String retryKind) {
        List<Integer> missingPages = new ArrayList<>();
        int parallelism = Math.min(teachingSemanticRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, batches.size());
            // Keep compressed page bytes scoped to the active provider window. Initial inspection, typed repair, and
            // transient replay enter this method separately and reread only their own pages from immutable storage.
            List<Integer> windowPages = batches.subList(windowStart, windowEnd).stream()
                    .flatMap(List::stream)
                    .distinct()
                    .toList();
            Map<Integer, PageImageInput> sourceImagesByPage = readTeachingPageImages(documentVersionId, windowPages)
                    .stream()
                    .collect(Collectors.toMap(
                            PageImageInput::pageNumber,
                            image -> image,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            ExecutorService executor = AsyncContextPropagation.executorService(
                    Executors.newFixedThreadPool(windowEnd - windowStart));
            try {
                List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                for (int index = windowStart; index < windowEnd; index++) {
                    int batchIndex = index;
                    futures.add(executor.submit(() -> observeStage(
                            "semantic",
                            retryKind,
                            () -> catalogTeachingBatch(
                                    batches.get(batchIndex),
                                    owner,
                                    rulebookTitle,
                                    assistantRunId,
                                    operationForIndex.apply(batchIndex),
                                    requestedRepairs.get(batches.get(batchIndex).getFirst()),
                                    sourceImagesByPage,
                                    transcripts))));
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
            Map<Integer, PageImageInput> sourceImagesByPage,
            Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> transcripts) {
        List<PageImageInput> images = batch.stream()
                .map(sourceImagesByPage::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<VisualRulebookPageCatalogModel.PageTranscript> batchTranscripts = batch.stream()
                .map(transcripts::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        // Teaching startup is intentionally page-local. If OCR for this page failed, send the original image with no
        // transcript; a failure in one page must not discard successful transcripts from other page requests.
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                images, owner, rulebookTitle, batchTranscripts.size() == images.size() ? batchTranscripts : List.of());
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

    private Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> transcribeTeachingPages(
            UUID documentVersionId,
            List<Integer> pageNumbers,
            String owner,
            UUID assistantRunId,
            int totalPageCount,
            String retryKind) {
        if (pageNumbers.isEmpty() || !visualCatalog.supportsTeachingPageTranscription(owner)) return Map.of();
        Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> transcripts = new LinkedHashMap<>();
        int parallelism = Math.min(teachingOcrRequestParallelism, pageNumbers.size());
        for (int windowStart = 0; windowStart < pageNumbers.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, pageNumbers.size());
            int from = windowStart;
            int to = windowEnd;
            OcrWindowResult window = observeStage(
                    "ocr_window",
                    retryKind,
                    () -> transcribeTeachingWindow(
                            documentVersionId,
                            pageNumbers.subList(from, to),
                            owner,
                            assistantRunId,
                            totalPageCount),
                    OcrWindowResult::outcome);
            transcripts.putAll(window.transcripts());
        }
        return Map.copyOf(transcripts);
    }

    private OcrWindowResult transcribeTeachingWindow(
            UUID documentVersionId,
            List<Integer> pageNumbers,
            String owner,
            UUID assistantRunId,
            int totalPageCount) {
        // OCR may use a wider I/O-bound window than semantic interpretation, but only its text survives this
        // iteration. Never retain the complete rulebook's PageImageInput byte arrays in preparation memory.
        List<PageImageInput> sourceImages = readTeachingPageImages(documentVersionId, pageNumbers);
        if (sourceImages.isEmpty()) return new OcrWindowResult(Map.of(), pageNumbers.size());
        Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> completed = new LinkedHashMap<>();
        ExecutorService executor = AsyncContextPropagation.executorService(
                Executors.newFixedThreadPool(sourceImages.size()));
        try {
            List<Future<VisualRulebookPageCatalogModel.PageTranscript>> futures = new ArrayList<>();
            for (PageImageInput page : sourceImages) {
                String operation = teachingOcrOperation(page.pageNumber(), totalPageCount);
                futures.add(executor.submit(() -> invokeModel(
                        assistantRunId,
                        operation,
                        transcriptionInputTokens(page),
                        teachingTranscriptionSuccessSummary(owner),
                        () -> requireMatchingTranscript(page, visualCatalog.transcribeTeachingPage(page, owner)),
                        this::transcriptOutputTokens)));
            }
            long windowDeadlineNanos = catalogWindowDeadline(visualCatalogTimeout);
            for (int offset = 0; offset < futures.size(); offset++) {
                PageImageInput page = sourceImages.get(offset);
                String operation = teachingOcrOperation(page.pageNumber(), totalPageCount);
                try {
                    VisualRulebookPageCatalogModel.PageTranscript transcript = awaitTranscriptBefore(
                            futures.get(offset), visualCatalogTimeout, windowDeadlineNanos);
                    completed.put(page.pageNumber(), transcript);
                } catch (AgentExecutionStoppedException stopped) {
                    throw stopped;
                } catch (RuntimeException failedTranscription) {
                    if (catalogInterrupted(failedTranscription)) throw failedTranscription;
                    if (catalogTimedOut(failedTranscription) && assistantRunId != null) {
                        invocations.stopRunning(
                                assistantRunId,
                                operation,
                                ActivityOutcome.FAILED,
                                "Teaching page transcription timed out; retaining the original page image");
                    }
                    log.warn(
                            "Teaching page transcription failed for page {} of document preparation; retaining original image",
                            page.pageNumber(),
                            failedTranscription);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new OcrWindowResult(Map.copyOf(completed), pageNumbers.size());
    }

    private record OcrWindowResult(
            Map<Integer, VisualRulebookPageCatalogModel.PageTranscript> transcripts,
            int requestedPages) {

        private String outcome() {
            if (transcripts.size() == requestedPages) return "completed";
            return transcripts.isEmpty() ? "failed" : "partial";
        }
    }

    private static VisualRulebookPageCatalogModel.PageTranscript requireMatchingTranscript(
            PageImageInput page, VisualRulebookPageCatalogModel.PageTranscript transcript) {
        if (transcript.pageNumber() != page.pageNumber()) {
            throw new IllegalArgumentException("Teaching OCR transcript page binding did not match its source image");
        }
        return transcript;
    }

    private static String teachingOcrOperation(int pageNumber, int totalPageCount) {
        return "transcribeTeachingVisualRepairPage|" + pageNumber + "|" + totalPageCount;
    }

    private static int transcriptionInputTokens(PageImageInput page) {
        // Compressed image bytes are not language tokens. Keep the operational estimate proportional but bounded,
        // matching the existing vision-call accounting instead of exhausting the run budget on JPEG byte length.
        return Math.max(600, Math.min(4_000, page.content().length / 512));
    }

    private String teachingTranscriptionSuccessSummary(String owner) {
        return visualCatalog.teachingPageTranscriptionExecutionIdentity(owner)
                .map(identity -> "Teaching page text transcribed for typed repair via " + identity.auditLabel())
                .orElse("Teaching page text transcribed for typed repair");
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
            boolean allowTileFallback) {
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
                allowTileFallback);
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
                        allowTileFallback);
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
                    documentVersionId, List.copyOf(tileFallbackPages), owner, rulebookTitle, assistantRunId));
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
            boolean verifyIconBounds) {
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
                verifyIconBounds);
    }

    private List<VisualRulebookPageCatalogModel.PageSummary> catalogDensePagesWithTiles(
            UUID documentVersionId,
            List<Integer> pageNumbers,
            String owner,
            String rulebookTitle,
            UUID assistantRunId) {
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
                ExecutorService executor = AsyncContextPropagation.executorService(
                        Executors.newFixedThreadPool(windowEnd - windowStart));
                try {
                    List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = new ArrayList<>();
                    for (int index = windowStart; index < windowEnd; index++) {
                        int tileIndex = index;
                        futures.add(executor.submit(() -> catalogTile(
                                tiles.get(tileIndex),
                                owner,
                                rulebookTitle,
                                assistantRunId,
                                "inspectRulebookVisualTile|" + pageNumber + "|" + (tileIndex + 1))));
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
                                    "Visual tile {} skipped for dense rulebook page {} in document {}",
                                    tileIndex + 1,
                                    pageNumber,
                                    documentVersionId,
                                    failedTile);
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
                        localizeIconBounds(documentVersionId, merged, owner, assistantRunId);
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
            String operation) {
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                List.of(tile.image()), owner, rulebookTitle, tile.viewport());
        return invokeModel(
                assistantRunId,
                operation,
                800,
                "Dense rulebook page tile interpreted",
                () -> visualCatalog.summarize(request),
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
            boolean verifyIconBounds) {
        List<Integer> failedPages = new ArrayList<>();
        int parallelism = Math.min(visualRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            int windowEnd = Math.min(windowStart + parallelism, batches.size());
            ExecutorService executor = AsyncContextPropagation.executorService(
                    Executors.newFixedThreadPool(windowEnd - windowStart));
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
                            operationForIndex.apply(batchIndex))));
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
                                            localizeIconBounds(documentVersionId, summary, owner, assistantRunId))
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
                                "Visual page interpretation skipped failed batch {} for document {}",
                                batches.get(batchIndex),
                                documentVersionId,
                                failedBatch);
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
            UUID assistantRunId) {
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
            var localized = localizeIconsWithOneRepair(request, summary.pageNumber(), assistantRunId);
            Map<Integer, VisualRulebookPageCatalogModel.IconLocation> locations = localized.locations().stream()
                    .collect(Collectors.toMap(
                            VisualRulebookPageCatalogModel.IconLocation::candidateIndex,
                            java.util.function.Function.identity()));
            Map<Integer, VisualRulebookPageCatalogModel.IconLocation> confirmedLocations =
                    confirmLocalizedIconCrops(page.get(), summary, locations, owner, assistantRunId);
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
                    "Icon rectangle verification failed for rulebook page {} in document {}; keeping the page incomplete",
                    summary.pageNumber(),
                    documentVersionId,
                    localizationFailure);
            return withoutUnverifiedIcons(summary);
        }
    }

    private VisualRulebookPageCatalogModel.IconLocalizationDraft localizeIconsWithOneRepair(
            VisualRulebookPageCatalogModel.IconLocalizationRequest request,
            int pageNumber,
            UUID assistantRunId) {
        int estimatedInputTokens = Math.max(400, request.candidates().size() * 80);
        try {
            return invokeModel(
                    assistantRunId,
                    "localizeRulebookIcons|" + pageNumber,
                    estimatedInputTokens,
                    "Rulebook icon rectangles verified",
                    () -> visualCatalog.localizeIcons(request),
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
                        () -> visualCatalog.localizeIcons(request),
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
            UUID assistantRunId) {
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
                    () -> visualCatalog.reviewIconCrops(request),
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
                                "tighten");
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
            String pass) {
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
                () -> visualCatalog.reviewIconCrops(request),
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

    private static VisualRulebookPageCatalogModel.PageTranscript awaitTranscriptBefore(
            Future<VisualRulebookPageCatalogModel.PageTranscript> future,
            Duration configuredTimeout,
            long deadlineNanos) {
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "visual rulebook transcription timed out after " + configuredTimeout.toSeconds() + " seconds",
                    slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook transcription was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("visual rulebook transcription failed", failed.getCause());
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

    private int transcriptOutputTokens(VisualRulebookPageCatalogModel.PageTranscript transcript) {
        return Math.max(1, transcript.text().length() / 4);
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
