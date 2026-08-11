package com.rulepilot.teaching.application;

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
                    catalogPageFacts(documentVersionId, pagesToInspect, rulebookTitle, owner, assistantRunId, true, cached);
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
        // A visual-only rulebook cannot form an evidence-bound outline from a partial ledger. Preserve every
        // successfully cataloged page and only request missing pages. Visual anchors are optional crop-discovery
        // hints, not a condition for trusting the page's factual ledger; retrying an anchorless page made every
        // subsequent plan pay again for otherwise usable visual evidence.
        Set<Integer> requiredFacts = new LinkedHashSet<>(missingPages);
        List<PageFact> fresh = requiredFacts.isEmpty()
                ? List.of()
                : catalogPageFacts(documentVersionId, requiredFacts, rulebookTitle, owner, assistantRunId, false);
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
        Set<Integer> cachedPages = cached.stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .map(PageFact::pageNumber)
                .collect(Collectors.toSet());
        Set<Integer> missing = selected.stream()
                .filter(page -> !cachedPages.contains(page))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> fresh;
        try {
            fresh = missing.isEmpty()
                    ? List.of()
                    : catalogPageFacts(documentVersionId, missing, rulebookTitle, owner, assistantRunId, false);
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

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            boolean allowTileFallback) {
        return catalogPageFacts(
                documentVersionId, pageNumbers, rulebookTitle, owner, assistantRunId, allowTileFallback, List.of());
    }

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            String rulebookTitle,
            String owner,
            UUID assistantRunId,
            boolean allowTileFallback,
            List<PageFact> referenceFacts) {
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
        consolidated = enrichPrintedIdentifierCells(
                documentVersionId, consolidated, owner, assistantRunId, referenceFacts);
        persistCompletedFacts(documentVersionId, consolidated);
        return visualFacts.find(documentVersionId, pageNumbers).stream()
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .toList();
    }

    private List<VisualRulebookPageCatalogModel.PageSummary> enrichPrintedIdentifierCells(
            UUID documentVersionId,
            List<VisualRulebookPageCatalogModel.PageSummary> summaries,
            String owner,
            UUID assistantRunId,
            List<PageFact> referenceFacts) {
        List<VisualRulebookPageCatalogModel.PageSummary> enriched = new ArrayList<>();
        for (var summary : summaries) {
            List<String> identifiers = PrintedIdentifierCellPolicy.identifiers(
                    summary.printedTerms() + "\n" + summary.factualSummary());
            if (identifiers.isEmpty()) {
                enriched.add(summary);
                continue;
            }
            Optional<PageImage> source = pageImages.read(documentVersionId, Set.of(summary.pageNumber())).stream()
                    .filter(page -> page.pageNumber() == summary.pageNumber())
                    .findFirst();
            if (source.isEmpty()) {
                enriched.add(summary);
                continue;
            }
            try {
                var pageInput = new PageImageInput(
                        source.get().pageNumber(), source.get().mediaType(), source.get().content());
                var localizationRequest = new VisualRulebookPageCatalogModel.IdentifierLocalizationRequest(
                        pageInput, identifiers, owner);
                var localized = invokeModel(
                        assistantRunId,
                        "locateRulebookPrintedIdentifiers|" + summary.pageNumber(),
                        900,
                        "Printed catalog identifiers localized",
                        () -> visualCatalog.locateIdentifiers(localizationRequest),
                        result -> Math.max(1, result.locations().size() * 8));
                var verified = PrintedIdentifierCellPolicy.verifiedLocations(identifiers, localized.locations());
                var cells = PrintedIdentifierCellPolicy.cells(source.get(), verified);
                if (cells.size() < 4) {
                    enriched.add(summary);
                    continue;
                }
                List<VisualRulebookPageCatalogModel.IdentifierCellFact> facts = new ArrayList<>();
                List<VisualRulebookPageCatalogModel.IdentifierReferencePage> referencePages = identifierReferencePages(
                        documentVersionId, summary, referenceFacts);
                List<String> allowedReferenceLabels = referencePages.isEmpty()
                        ? List.of()
                        : referenceFacts.stream()
                                .filter(fact -> fact.pageNumber() == referencePages.getFirst().image().pageNumber())
                                .flatMap(fact -> fact.iconOccurrences().stream())
                                .filter(icon -> icon.meaningStatus()
                                        != com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED)
                                .map(icon -> icon.verifiedVisualLabel().isBlank()
                                        ? icon.name()
                                        : icon.verifiedVisualLabel())
                                .filter(label -> label != null && !label.isBlank())
                                .distinct()
                                .limit(12)
                                .toList();
                int cellBatchSize = referencePages.isEmpty() ? 4 : 1;
                for (int start = 0; start < cells.size(); start += cellBatchSize) {
                    List<VisualRulebookPageCatalogModel.IdentifierCellInput> batch =
                            cells.subList(start, Math.min(start + cellBatchSize, cells.size()));
                    var request = new VisualRulebookPageCatalogModel.IdentifierCellRequest(
                            batch, referencePages, owner);
                    int batchNumber = start / cellBatchSize + 1;
                    var draft = invokeModel(
                            assistantRunId,
                            "readRulebookIdentifierCells|" + summary.pageNumber() + "|" + batchNumber,
                            batch.size() * 350,
                            "Printed catalog cells interpreted",
                            () -> visualCatalog.summarizeIdentifierCells(request),
                            result -> Math.max(1, result.facts().stream()
                                    .mapToInt(fact -> fact.factualSummary().length()).sum() / 4));
                    for (var fact : draft.facts()) {
                        if (referencePages.isEmpty() || allowedReferenceLabels.size() < 2 || batch.size() != 1) {
                            facts.add(fact);
                            continue;
                        }
                        var verificationRequest = new VisualRulebookPageCatalogModel.IdentifierCellVerificationRequest(
                                batch.getFirst(),
                                referencePages.getFirst(),
                                allowedReferenceLabels,
                                fact.factualSummary(),
                                owner);
                        try {
                            var verifiedFact = invokeModel(
                                    assistantRunId,
                                    "verifyRulebookIdentifierCell|" + summary.pageNumber() + "|" + batchNumber,
                                    420,
                                    "Printed catalog cell pictogram checked against reference page "
                                            + referencePages.getFirst().image().pageNumber()
                                            + " labels " + allowedReferenceLabels,
                                    () -> visualCatalog.verifyIdentifierCell(verificationRequest),
                                    result -> Math.max(1, result.factualSummary().length() / 4));
                            String verifiedSummary = "NONE".equals(verifiedFact.matchedLabel())
                                    ? fact.factualSummary()
                                    : verifiedFact.factualSummary();
                            facts.add(new VisualRulebookPageCatalogModel.IdentifierCellFact(
                                    fact.identifier(), verifiedSummary));
                        } catch (RuntimeException rejectedVerification) {
                            try {
                                var retried = invokeModel(
                                        assistantRunId,
                                        "verifyRulebookIdentifierCell|" + summary.pageNumber() + "|" + batchNumber + "|retry",
                                        420,
                                        "Printed catalog cell pictogram reference check retried",
                                        () -> visualCatalog.verifyIdentifierCell(verificationRequest),
                                        result -> Math.max(1, result.factualSummary().length() / 4));
                                String retriedSummary = "NONE".equals(retried.matchedLabel())
                                        ? fact.factualSummary()
                                        : retried.factualSummary();
                                facts.add(new VisualRulebookPageCatalogModel.IdentifierCellFact(
                                        fact.identifier(), retriedSummary));
                            } catch (RuntimeException retryRejected) {
                                retryRejected.addSuppressed(rejectedVerification);
                                log.warn(
                                        "Reference pictogram verification rejected twice for page {} identifier {}; retaining cell transcript",
                                        summary.pageNumber(),
                                        fact.identifier(),
                                        retryRejected);
                                facts.add(fact);
                            }
                        }
                    }
                }
                if (facts.size() < 4) {
                    enriched.add(summary);
                    continue;
                }
                String cellFacts = facts.stream()
                        .map(VisualRulebookPageCatalogModel.IdentifierCellFact::factualSummary)
                        .distinct()
                        .collect(Collectors.joining("\n"));
                String combined = mergeIdentifierFactsWithSharedRules(cellFacts, summary.factualSummary());
                enriched.add(new VisualRulebookPageCatalogModel.PageSummary(
                        summary.pageNumber(),
                        summary.printedTerms(),
                        combined,
                        summary.keywords(),
                        summary.visualAnchors(),
                        summary.iconOccurrences(),
                        summary.iconInventoryComplete()));
            } catch (RuntimeException failure) {
                log.warn("Printed identifier cell enrichment skipped for page {}", summary.pageNumber(), failure);
                enriched.add(summary);
            }
        }
        return List.copyOf(enriched);
    }

    private List<VisualRulebookPageCatalogModel.IdentifierReferencePage> identifierReferencePages(
            UUID documentVersionId,
            VisualRulebookPageCatalogModel.PageSummary subject,
            List<PageFact> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        PageFact reference = candidates.stream()
                .filter(candidate -> candidate.pageNumber() != subject.pageNumber())
                .filter(PageFact::iconInventoryComplete)
                .filter(candidate -> candidate.iconOccurrences().stream().filter(icon -> icon.meaningStatus()
                                != com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED)
                        .count() >= 2)
                .max(java.util.Comparator.comparingInt(candidate -> candidate.iconOccurrences().size()))
                .orElse(null);
        if (reference == null) return List.of();
        List<Integer> selectedPages = List.of(reference.pageNumber());
        Map<Integer, PageFact> factsByPage = candidates.stream().collect(Collectors.toMap(
                PageFact::pageNumber, java.util.function.Function.identity(), (first, ignored) -> first));
        return pageImages.read(documentVersionId, new LinkedHashSet<>(selectedPages)).stream()
                .sorted(java.util.Comparator.comparingInt(page -> selectedPages.indexOf(page.pageNumber())))
                .map(page -> {
                    PageFact fact = factsByPage.get(page.pageNumber());
                    String evidence = fact == null
                            ? "The complete rendered page is the only reference evidence."
                            : fact.printedTerms() + "\n" + fact.factualSummary();
                    if (evidence.length() > 1_600) evidence = evidence.substring(0, 1_600).stripTrailing();
                    return new VisualRulebookPageCatalogModel.IdentifierReferencePage(
                            new PageImageInput(page.pageNumber(), page.mediaType(), page.content()), evidence);
                })
                .toList();
    }

    static String mergeIdentifierFactsWithSharedRules(String identifierFacts, String pageSummary) {
        LinkedHashSet<String> blocks = new LinkedHashSet<>();
        if (identifierFacts != null && !identifierFacts.isBlank()) blocks.add(identifierFacts.strip());
        if (pageSummary != null && !pageSummary.isBlank()) blocks.add(pageSummary.strip());
        StringBuilder merged = new StringBuilder();
        for (String block : blocks) {
            int separator = merged.isEmpty() ? 0 : 1;
            int remaining = 4_000 - merged.length() - separator;
            if (remaining <= 0) break;
            if (!merged.isEmpty()) merged.append('\n');
            merged.append(block, 0, Math.min(block.length(), remaining));
        }
        return merged.toString();
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
                summary.pageNumber(), summary, VisualRulebookCatalogPolicy::mergeIconTileAudit));
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
                fact.iconInventoryComplete());
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
                                "inspectRulebookVisualTile|" + pageNumber + "|" + (tileIndex + 1))));
                    }
                    for (int offset = 0; offset < futures.size(); offset++) {
                        int tileIndex = windowStart + offset;
                        try {
                            VisualRulebookPageCatalogModel.PageSummary summary =
                                    awaitCatalog(futures.get(offset), visualCatalogTimeout).pages().getFirst();
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
     * Submit only work that can start immediately. A future timeout must measure a provider call, not time spent
     * waiting behind an earlier page in the executor queue. Replacing the executor after each window also prevents
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
                            operationForIndex.apply(batchIndex))));
                }
                for (int offset = 0; offset < futures.size(); offset++) {
                    int batchIndex = windowStart + offset;
                    try {
                        List<VisualRulebookPageCatalogModel.PageSummary> completed =
                                awaitCatalog(futures.get(offset), visualCatalogTimeout).pages();
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
                    summary.iconInventoryComplete());
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
                false);
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
                        + page.keywords().stream().mapToInt(String::length).sum()
                        + page.iconOccurrences().stream()
                                .mapToInt(icon -> icon.name().length()
                                        + icon.visualDescription().length()
                                        + icon.explanation().length()
                                        + icon.evidenceText().length())
                                .sum())
                .sum();
        return Math.max(1, characters / 4);
    }
}
