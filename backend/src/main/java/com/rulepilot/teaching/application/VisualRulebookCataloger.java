package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.shared.AsyncContextPropagation;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRejection;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            @Value("${rulepilot.visual.request-parallelism:10}") int visualRequestParallelism,
            @Value("${rulepilot.visual.semantic-request-parallelism:4}") int teachingSemanticRequestParallelism,
            ObservationRegistry observations) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
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
            int visualRequestParallelism,
            int teachingSemanticRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
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
            int visualRequestParallelism) {
        this(
                pageImages,
                visualCatalog,
                visualFacts,
                invocations,
                visualCatalogTimeout,
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
        long compatibilityWorkflowDeadline = assistantRunId == null
                ? catalogWindowDeadline(visualCatalogTimeout)
                : Long.MAX_VALUE;
        int parallelism = Math.min(teachingSemanticRequestParallelism, batches.size());
        for (int windowStart = 0; windowStart < batches.size(); windowStart += parallelism) {
            List<Integer> windowPages = batches.subList(
                            windowStart, Math.min(windowStart + parallelism, batches.size()))
                    .stream()
                    .flatMap(List::stream)
                    .toList();
            Map<Integer, PageImageInput> imagesByPage = readTeachingWindowImages(
                    documentVersionId, windowPages, assistantRunId, totalPageCount);
            List<TeachingPageCandidateState> activePages = windowPages.stream()
                    .map(imagesByPage::get)
                    .filter(java.util.Objects::nonNull)
                    .map(TeachingPageCandidateState::new)
                    .collect(Collectors.toCollection(ArrayList::new));
            inspectTeachingPagesUntilSettled(
                    documentVersionId,
                    activePages,
                    owner,
                    rulebookTitle,
                    assistantRunId,
                    totalPageCount,
                    compatibilityWorkflowDeadline);
        }
        return findVisualFacts(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    private Map<Integer, PageImageInput> readTeachingWindowImages(
            UUID documentVersionId,
            List<Integer> windowPages,
            UUID assistantRunId,
            int totalPageCount) {
        try {
            return readTeachingPageImages(documentVersionId, windowPages).stream()
                    .collect(Collectors.toMap(
                            PageImageInput::pageNumber,
                            image -> image,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
        } catch (RuntimeException windowReadFailure) {
            log.warn(
                    "Teaching-start page-image window {} could not be read for document {}; isolating pages",
                    windowPages,
                    documentVersionId,
                    windowReadFailure);
            Map<Integer, PageImageInput> available = new LinkedHashMap<>();
            for (int pageNumber : windowPages) {
                try {
                    readTeachingPageImages(documentVersionId, List.of(pageNumber)).stream()
                            .findFirst()
                            .ifPresent(image -> available.put(pageNumber, image));
                } catch (RuntimeException pageReadFailure) {
                    log.warn(
                            "Teaching-start page image {} is locally unavailable for document {}",
                            pageNumber,
                            documentVersionId,
                            pageReadFailure);
                }
                if (!available.containsKey(pageNumber)) {
                    recordTeachingPageSettlement(
                            assistantRunId,
                            pageNumber,
                            totalPageCount,
                            1,
                            "local-unavailable",
                            "IMAGE_UNAVAILABLE",
                            ActivityOutcome.FAILED,
                            "Visual rulebook page image is locally unavailable; sibling pages continue");
                }
            }
            return available;
        }
    }

    private void inspectTeachingPagesUntilSettled(
            UUID documentVersionId,
            List<TeachingPageCandidateState> activePages,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            int totalPageCount,
            long compatibilityWorkflowDeadline) {
        if (activePages.isEmpty()) return;
        ExecutorService executor = AsyncContextPropagation.executorService(
                Executors.newFixedThreadPool(activePages.size()));
        try {
            while (!activePages.isEmpty()) {
                if (assistantRunId == null && System.nanoTime() >= compatibilityWorkflowDeadline) {
                    activePages.forEach(state -> recordTeachingPageSettlement(
                            null,
                            state.pageNumber(),
                            totalPageCount,
                            state.candidateNumber(),
                            "local-unavailable",
                            "WORKFLOW_DEADLINE",
                            ActivityOutcome.FAILED,
                            "Visual rulebook page workflow reached its deadline; sibling pages remain durable"));
                    return;
                }
                CompletionService<TeachingPageCandidateAttempt> completions =
                        new ExecutorCompletionService<>(executor);
                Map<Future<TeachingPageCandidateAttempt>, TeachingPageCandidateState> pending = new LinkedHashMap<>();
                for (TeachingPageCandidateState state : activePages) {
                    Future<TeachingPageCandidateAttempt> future = completions.submit(() -> observeStage(
                            "semantic",
                            state.rejection() == null ? "candidate" : "correction",
                            () -> invokeTeachingPageCandidate(state, owner, rulebookTitle, assistantRunId, totalPageCount),
                            attempt -> attempt.providerFailure() != null
                                    ? "provider_failed"
                                    : attempt.violation() != null ? "rejected" : "accepted"));
                    pending.put(future, state);
                }
                long attemptDeadline = assistantRunId == null
                        ? compatibilityWorkflowDeadline
                        : catalogWindowDeadline(visualCatalogTimeout);
                while (!pending.isEmpty()) {
                    Future<TeachingPageCandidateAttempt> completedFuture = pollCandidate(completions, attemptDeadline);
                    if (completedFuture == null) {
                        pending.keySet().forEach(future -> future.cancel(true));
                        pending.values().forEach(state -> recordTeachingPageSettlement(
                                assistantRunId,
                                state.pageNumber(),
                                totalPageCount,
                                state.candidateNumber(),
                                "local-unavailable",
                                assistantRunId == null ? "WORKFLOW_DEADLINE" : "PROVIDER_TIMEOUT",
                                ActivityOutcome.FAILED,
                                "Visual rulebook page candidate did not complete before its deadline; sibling pages continue"));
                        activePages.removeAll(pending.values());
                        break;
                    }
                    TeachingPageCandidateState state = pending.remove(completedFuture);
                    TeachingPageCandidateAttempt attempt = completedCandidate(completedFuture);
                    if (attempt.providerFailure() != null) {
                        log.warn(
                                "Teaching-start visual provider left page {} locally unavailable for document {}",
                                state.pageNumber(),
                                documentVersionId,
                                attempt.providerFailure());
                        recordTeachingPageSettlement(
                                assistantRunId,
                                state.pageNumber(),
                                totalPageCount,
                                state.candidateNumber(),
                                "local-unavailable",
                                "PROVIDER_FAILURE",
                                ActivityOutcome.FAILED,
                                state.rejection() == null
                                        ? "Visual provider did not return the page candidate; sibling pages continue"
                                        : "Visual provider did not return the requested correction; only this page is unavailable");
                        activePages.remove(state);
                        continue;
                    }
                    if (attempt.violation() != null) {
                        settleRejectedTeachingPageCandidate(
                                activePages, state, attempt.violation(), assistantRunId, totalPageCount);
                        continue;
                    }
                    VisualRulebookPageCatalogModel.PageSummary summary = acceptedPageSummary(
                            state.pageNumber(), attempt.draft());
                    recordTeachingPageSettlement(
                            assistantRunId,
                            state.pageNumber(),
                            totalPageCount,
                            state.candidateNumber(),
                            "accepted",
                            "NONE",
                            ActivityOutcome.SUCCEEDED,
                            "Visual rulebook page candidate passed typed validation and is being persisted");
                    observeStage("persist", state.rejection() == null ? "candidate" : "correction", () -> {
                        persistCompletedFacts(documentVersionId, List.of(summary));
                        return null;
                    });
                    if (assistantRunId != null) {
                        invocations.record(
                                assistantRunId,
                                ActivityType.VALIDATION,
                                "persistTeachingVisualPage|" + summary.pageNumber() + "|" + totalPageCount,
                                ActivityOutcome.SUCCEEDED,
                                "Stored typed rule groups for visual page " + summary.pageNumber());
                    }
                    activePages.remove(state);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private TeachingPageCandidateAttempt invokeTeachingPageCandidate(
            TeachingPageCandidateState state,
            String owner,
            String rulebookTitle,
            UUID assistantRunId,
            int totalPageCount) {
        var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                List.of(state.image()), owner, rulebookTitle);
        String operation = "inspectTeachingVisualPageCandidate|" + state.pageNumber() + "|" + totalPageCount
                + "|candidate-" + state.candidateNumber();
        try {
            return invokeModel(
                    assistantRunId,
                    operation,
                    600,
                    teachingStartupSuccessSummary(owner),
                    () -> {
                        try {
                            VisualRulebookPageCatalogModel.CatalogDraft draft = state.rejection() == null
                                    ? visualCatalog.summarizeForTeaching(request)
                                    : visualCatalog.correctTeachingCatalog(request, state.rejection());
                            return TeachingPageCandidateAttempt.accepted(draft);
                        } catch (TeachingCatalogContractViolation violation) {
                            return TeachingPageCandidateAttempt.rejected(violation);
                        }
                    },
                    this::candidateOutputTokens);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException providerFailure) {
            if (catalogInterrupted(providerFailure)) throw providerFailure;
            return TeachingPageCandidateAttempt.providerFailed(providerFailure);
        }
    }

    private void settleRejectedTeachingPageCandidate(
            List<TeachingPageCandidateState> activePages,
            TeachingPageCandidateState state,
            TeachingCatalogContractViolation violation,
            UUID assistantRunId,
            int totalPageCount) {
        Optional<TeachingCatalogRejection> rejection = violation.rejection();
        if (rejection.isEmpty()) {
            recordTeachingPageSettlement(
                    assistantRunId,
                    state.pageNumber(),
                    totalPageCount,
                    state.candidateNumber(),
                    "local-unavailable",
                    "INCOMPLETE_REJECTION_CONTEXT",
                    ActivityOutcome.REJECTED,
                    "Visual page validation could not return the complete rejected observation; sibling pages continue");
            activePages.remove(state);
            return;
        }
        TeachingCatalogRejection observation = rejection.orElseThrow();
        if (!state.observe(observation)) {
            recordTeachingPageSettlement(
                    assistantRunId,
                    state.pageNumber(),
                    totalPageCount,
                    state.candidateNumber(),
                    "no-progress",
                    violation.repairCode().name(),
                    ActivityOutcome.REJECTED,
                    "The page Agent repeated an earlier complete rejected observation; only this page stopped");
            activePages.remove(state);
            return;
        }
        recordTeachingPageSettlement(
                assistantRunId,
                state.pageNumber(),
                totalPageCount,
                state.candidateNumber(),
                "correction-follows",
                violation.repairCode().name(),
                ActivityOutcome.REJECTED,
                "The complete rejected candidate, exact validation error, original contract, and allowed page identities returned to the same page Agent");
        state.continueFrom(observation);
    }

    private void recordTeachingPageSettlement(
            UUID assistantRunId,
            int pageNumber,
            int totalPageCount,
            int candidateNumber,
            String state,
            String reason,
            ActivityOutcome outcome,
            String summary) {
        if (assistantRunId == null) return;
        invocations.record(
                assistantRunId,
                ActivityType.VALIDATION,
                "settleTeachingVisualPageCandidate|" + pageNumber + "|" + totalPageCount + "|candidate-"
                        + candidateNumber + "|" + state + "|" + reason,
                outcome,
                summary);
    }

    private static Future<TeachingPageCandidateAttempt> pollCandidate(
            CompletionService<TeachingPageCandidateAttempt> completions, long deadlineNanos) {
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            return completions.poll(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        }
    }

    private static TeachingPageCandidateAttempt completedCandidate(
            Future<TeachingPageCandidateAttempt> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof AgentExecutionStoppedException stopped) throw stopped;
            if (cause instanceof RuntimeException runtime) {
                catalogInterrupted(runtime);
                throw runtime;
            }
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("visual rulebook catalog failed", cause);
        }
    }

    private static VisualRulebookPageCatalogModel.PageSummary acceptedPageSummary(
            int pageNumber, VisualRulebookPageCatalogModel.CatalogDraft draft) {
        if (draft == null
                || draft.pages().size() != 1
                || draft.pages().getFirst().pageNumber() != pageNumber) {
            throw new IllegalArgumentException("accepted visual Teaching candidate lost its exact page binding");
        }
        return VisualRulebookCatalogPolicy.teachingStartupFact(draft.pages().getFirst());
    }

    private int candidateOutputTokens(TeachingPageCandidateAttempt attempt) {
        if (attempt.draft() != null) return catalogOutputTokens(attempt.draft());
        if (attempt.providerFailure() != null) return 1;
        return attempt.violation().rejection()
                .map(rejection -> Math.max(1, rejection.candidateJson().length() / 4))
                .orElse(1);
    }

    private static final class TeachingPageCandidateState {

        private final PageImageInput image;
        private final Set<TeachingCatalogRejection> rejectedObservations = new LinkedHashSet<>();
        private int candidateNumber = 1;
        private TeachingCatalogRejection rejection;

        private TeachingPageCandidateState(PageImageInput image) {
            this.image = image;
        }

        private PageImageInput image() {
            return image;
        }

        private int pageNumber() {
            return image.pageNumber();
        }

        private int candidateNumber() {
            return candidateNumber;
        }

        private TeachingCatalogRejection rejection() {
            return rejection;
        }

        private boolean observe(TeachingCatalogRejection observation) {
            return rejectedObservations.add(observation);
        }

        private void continueFrom(TeachingCatalogRejection observation) {
            rejection = observation;
            candidateNumber++;
        }
    }

    private record TeachingPageCandidateAttempt(
            VisualRulebookPageCatalogModel.CatalogDraft draft,
            TeachingCatalogContractViolation violation,
            RuntimeException providerFailure) {

        private static TeachingPageCandidateAttempt accepted(
                VisualRulebookPageCatalogModel.CatalogDraft draft) {
            return new TeachingPageCandidateAttempt(draft, null, null);
        }

        private static TeachingPageCandidateAttempt rejected(
                TeachingCatalogContractViolation violation) {
            return new TeachingPageCandidateAttempt(null, violation, null);
        }

        private static TeachingPageCandidateAttempt providerFailed(RuntimeException failure) {
            return new TeachingPageCandidateAttempt(null, null, failure);
        }
    }

    /*
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
                    .collect(Collectors.toMap(
                            PageImage::pageNumber, image -> image, (first, ignored) -> first));
            chunk.stream()
                    .map(available::get)
                    .filter(java.util.Objects::nonNull)
                    .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                    .forEach(images::add);
        }
        return List.copyOf(images);
    }

    private String teachingStartupSuccessSummary(String owner) {
        return visualCatalog.teachingStartupExecutionIdentity(owner)
                .map(identity -> "Teaching-start page candidate received via " + identity.auditLabel())
                .orElse("Teaching-start page candidate received");
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

    static VisualRulebookPageCatalogModel.CatalogDraft awaitCatalog(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future, Duration timeout) {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "visual rulebook catalog timed out after " + timeout.toSeconds() + " seconds",
                    slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
    }

    private static long catalogWindowDeadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
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
