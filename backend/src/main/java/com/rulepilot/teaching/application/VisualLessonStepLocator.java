package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.VisualRegionProposer.Diagnostic;
import com.rulepilot.teaching.VisualRegionProposer.Proposal;
import com.rulepilot.teaching.VisualRegionLocator.BatchAction;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Grounds cited lesson steps in one typed, page- and evidence-owned visual plan. */
final class VisualLessonStepLocator {

    static final Duration DEFAULT_COMPATIBILITY_WORKFLOW_TIMEOUT = Duration.ofMinutes(10);
    private static final int CONSECUTIVE_PROPOSAL_RUNTIME_FAILURE_LIMIT = 2;

    private final DocumentPageImages pageImages;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionProposer proposals;
    private final VisualRegionLocator locator;
    private final VisualReaderCropPolicy cropPolicy;
    private final AgentExecutionControl execution;
    private final Clock clock;
    private final Duration compatibilityWorkflowTimeout;

    VisualLessonStepLocator(
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualReaderCropPolicy cropPolicy) {
        this(
                pageImages,
                candidates,
                VisualRegionProposer.unavailable(),
                locator,
                cropPolicy,
                null,
                Clock.systemUTC(),
                DEFAULT_COMPATIBILITY_WORKFLOW_TIMEOUT);
    }

    VisualLessonStepLocator(
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualReaderCropPolicy cropPolicy,
            AgentExecutionControl execution,
            Clock clock,
            Duration compatibilityWorkflowTimeout) {
        this(
                pageImages,
                candidates,
                VisualRegionProposer.unavailable(),
                locator,
                cropPolicy,
                execution,
                clock,
                compatibilityWorkflowTimeout);
    }

    VisualLessonStepLocator(
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionLocator locator,
            VisualReaderCropPolicy cropPolicy,
            AgentExecutionControl execution,
            Clock clock,
            Duration compatibilityWorkflowTimeout) {
        if (pageImages == null || candidates == null || locator == null || cropPolicy == null || clock == null
                || proposals == null
                || compatibilityWorkflowTimeout == null
                || compatibilityWorkflowTimeout.isZero()
                || compatibilityWorkflowTimeout.isNegative()) {
            throw new IllegalArgumentException("visual lesson locator dependencies are invalid");
        }
        this.pageImages = pageImages;
        this.candidates = candidates;
        this.proposals = proposals;
        this.locator = locator;
        this.cropPolicy = cropPolicy;
        this.execution = execution;
        this.clock = clock;
        this.compatibilityWorkflowTimeout = compatibilityWorkflowTimeout;
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return locator.supportsVisualEvidence(modelConfigurationOwner);
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner) {
        return locate(
                understanding,
                documentVersionId,
                section,
                steps,
                modelConfigurationOwner,
                null,
                clock.instant().plus(compatibilityWorkflowTimeout),
                beginProposalWorkflow());
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner,
            UUID runId) {
        return locate(
                understanding,
                documentVersionId,
                section,
                steps,
                modelConfigurationOwner,
                runId,
                clock.instant().plus(compatibilityWorkflowTimeout),
                beginProposalWorkflow());
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner,
            UUID runId,
            Instant compatibilityDeadline) {
        return locate(
                understanding,
                documentVersionId,
                section,
                steps,
                modelConfigurationOwner,
                runId,
                compatibilityDeadline,
                beginProposalWorkflow());
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner,
            UUID runId,
            Instant compatibilityDeadline,
            ProposalToolCircuit proposalToolCircuit) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("visual lesson steps are required");
        }
        if (proposalToolCircuit == null) {
            throw new IllegalArgumentException("visual proposal workflow is required");
        }
        Instant workflowDeadline = compatibilityDeadline == null
                ? clock.instant().plus(compatibilityWorkflowTimeout)
                : compatibilityDeadline;
        List<Integer> citedPages = steps.stream()
                .flatMap(step -> step.sourcePages().stream())
                .distinct()
                .sorted()
                .toList();
        List<Claim> claims = claims(steps);
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        List<VisualRegionLocator.LocatedRegion> accepted = new ArrayList<>();
        VisualLessonEnricher.Outcome firstRejection = null;
        boolean candidateFound = false;
        boolean stopped = false;
        int batchNumber = 1;
        for (int pageStart = 0;
                !stopped && pageStart < citedPages.size();
                pageStart += DocumentPageImages.MAX_PAGES_PER_READ) {
            Boundary boundary = boundary(runId, workflowDeadline);
            if (boundary.stoppedOutcome() != null) {
                if (firstRejection == null) firstRejection = boundary.stoppedOutcome();
                break;
            }
            int pageEnd = Math.min(pageStart + DocumentPageImages.MAX_PAGES_PER_READ, citedPages.size());
            List<Integer> pageWindow = citedPages.subList(pageStart, pageEnd);
            ProposedRegions proposed = proposalToolCircuit.available()
                    ? proposeRegions(
                            documentVersionId,
                            pageWindow,
                            runId,
                            workflowDeadline,
                            proposalToolCircuit)
                    : ProposedRegions.unavailable();
            List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                    understanding,
                    new LinkedHashSet<>(pageWindow),
                    terms(section, steps),
                    proposed.byPage());
            if (selected.isEmpty()) continue;
            candidateFound = true;

            for (int offset = 0;
                    offset < selected.size();
                    offset += VisualRegionLocator.VisualLocationRequest.MAX_CANDIDATES_PER_BATCH) {
                boundary = boundary(runId, workflowDeadline);
                if (boundary.stoppedOutcome() != null) {
                    if (firstRejection == null) firstRejection = boundary.stoppedOutcome();
                    stopped = true;
                    break;
                }
                int end = Math.min(
                        selected.size(),
                        offset + VisualRegionLocator.VisualLocationRequest.MAX_CANDIDATES_PER_BATCH);
                List<VisualRegionCandidateSelector.Candidate> offeredBatch = selected.subList(offset, end);
                Set<Integer> batchPageNumbers = offeredBatch.stream()
                        .map(VisualRegionCandidateSelector.Candidate::pageNumber)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                Map<Integer, DocumentPageImages.PageImage> availablePages;
                try {
                    // Keep only the current transport window. A later batch may reread a page in exchange for bounded
                    // memory; no page bytes survive in a section-wide cache.
                    availablePages = readPages(documentVersionId, batchPageNumbers.stream().toList());
                } catch (RuntimeException pageReadFailure) {
                    if (firstRejection == null) firstRejection = VisualLessonEnricher.Outcome.NO_PAGE_IMAGE;
                    break;
                }
                List<VisualRegionCandidateSelector.Candidate> batch = offeredBatch.stream()
                        .filter(candidate -> availablePages.containsKey(candidate.pageNumber()))
                        .toList();
                if (batch.isEmpty()) {
                    if (firstRejection == null) firstRejection = VisualLessonEnricher.Outcome.NO_PAGE_IMAGE;
                    continue;
                }
                List<PageImage> batchPages = batch.stream()
                        .map(VisualRegionCandidateSelector.Candidate::pageNumber)
                        .distinct()
                        .map(availablePages::get)
                        .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                        .toList();
                boolean hasMoreCandidates = end < selected.size() || pageEnd < citedPages.size();
                boundary = boundary(runId, workflowDeadline);
                if (boundary.stoppedOutcome() != null) {
                    if (firstRejection == null) firstRejection = boundary.stoppedOutcome();
                    stopped = true;
                    break;
                }
                VisualRegionLocator.LocateGuideResult guide;
                try {
                    guide = locator.locateGuideWithResult(
                            new VisualRegionLocator.VisualLocationRequest(
                                    section.title(),
                                    claims,
                                    batch,
                                    batchPages,
                                    modelConfigurationOwner,
                                    runId == null ? null : documentVersionId,
                                    runId,
                                    batchNumber++,
                                    hasMoreCandidates),
                            boundary.remaining());
                } catch (AgentExecutionStoppedException modelStopped) {
                    if (firstRejection == null) firstRejection = outcomeFor(modelStopped);
                    stopped = true;
                    break;
                }
                if (guide.regions().isEmpty()) {
                    if (firstRejection == null) firstRejection = outcomeFor(guide.diagnostic());
                    if (guide.batchAction() == BatchAction.STOP) stopped = true;
                    if (stopped) break;
                    continue;
                }
                for (VisualRegionLocator.LocatedRegion candidate : guide.regions()) {
                    VisualRegionLocator.LocatedRegion region = candidate;
                    if (cropPolicy.needsReaderViewport(region)) {
                        if (!cropPolicy.canExpandIntoReaderViewport(region)) {
                            if (firstRejection == null) {
                                firstRejection = VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL;
                            }
                            continue;
                        }
                        region = cropPolicy.expandIntoReaderViewport(region);
                    }
                    VisualLessonEnricher.Outcome rejection = rejectionFor(region, batch, evidenceIds);
                    if (rejection == null && !supportsExactStep(region, steps)) {
                        rejection = VisualLessonEnricher.Outcome.REJECTED_STEP_MISMATCH;
                    }
                    if (rejection == null) accepted.add(region);
                    else if (firstRejection == null) firstRejection = rejection;
                }
                if (guide.batchAction() == BatchAction.STOP) {
                    stopped = true;
                    break;
                }
            }
        }
        if (!accepted.isEmpty()) return Result.accepted(accepted);
        if (!candidateFound && firstRejection == null) {
            return Result.rejected(VisualLessonEnricher.Outcome.NO_CITED_CANDIDATE);
        }
        return Result.rejected(firstRejection == null
                ? VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE
                : firstRejection);
    }

    private Boundary boundary(UUID runId, Instant compatibilityDeadline) {
        Instant now = clock.instant();
        Instant deadline = compatibilityDeadline;
        if (runId != null && execution != null) {
            AgentExecutionControl.BudgetSnapshot budget = execution.budget(runId);
            if (budget.cancellationRequestedAt() != null) {
                return Boundary.stopped(VisualLessonEnricher.Outcome.MODEL_INTERRUPTED);
            }
            if (budget.usedModelCalls() >= budget.maxModelCalls()
                    || budget.usedTokens() >= budget.maxTokens()) {
                return Boundary.stopped(VisualLessonEnricher.Outcome.MODEL_INTERRUPTED);
            }
            deadline = budget.deadlineAt();
        }
        if (!now.isBefore(deadline)) {
            return Boundary.stopped(VisualLessonEnricher.Outcome.MODEL_TIMEOUT);
        }
        return Boundary.active(Duration.between(now, deadline));
    }

    private VisualLessonEnricher.Outcome outcomeFor(AgentExecutionStoppedException stopped) {
        return stopped.reason() == AgentExecutionStoppedException.StopReason.TIMEOUT
                ? VisualLessonEnricher.Outcome.MODEL_TIMEOUT
                : VisualLessonEnricher.Outcome.MODEL_INTERRUPTED;
    }

    private List<String> terms(LessonSection section, List<LessonStep> steps) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        steps.forEach(step -> {
            result.add(step.heading());
            result.add(step.text());
        });
        return List.copyOf(result);
    }

    private Map<Integer, DocumentPageImages.PageImage> readPages(
            UUID documentVersionId, List<Integer> pageNumbers) {
        Map<Integer, DocumentPageImages.PageImage> available = new java.util.LinkedHashMap<>();
        for (int start = 0; start < pageNumbers.size(); start += DocumentPageImages.MAX_PAGES_PER_READ) {
            List<Integer> batch = pageNumbers.subList(
                    start, Math.min(start + DocumentPageImages.MAX_PAGES_PER_READ, pageNumbers.size()));
            pageImages.read(documentVersionId, new LinkedHashSet<>(batch))
                    .forEach(image -> available.putIfAbsent(image.pageNumber(), image));
        }
        return Map.copyOf(available);
    }

    private ProposedRegions proposeRegions(
            UUID documentVersionId,
            List<Integer> pageNumbers,
            UUID runId,
            Instant workflowDeadline,
            ProposalToolCircuit proposalToolCircuit) {
        if (!proposals.configured()) return ProposedRegions.unavailable();
        Map<Integer, List<Proposal>> proposed = new java.util.LinkedHashMap<>();
        for (int start = 0;
                proposalToolCircuit.available() && start < pageNumbers.size();
                start += DocumentPageImages.MAX_PAGES_PER_READ) {
            Boundary boundary = boundary(runId, workflowDeadline);
            if (boundary.stoppedOutcome() != null) {
                return new ProposedRegions(proposed);
            }
            List<Integer> batch = pageNumbers.subList(
                    start, Math.min(start + DocumentPageImages.MAX_PAGES_PER_READ, pageNumbers.size()));
            List<DocumentPageImages.PageImage> available;
            try {
                available = pageImages.read(documentVersionId, new LinkedHashSet<>(batch));
            } catch (RuntimeException pageReadFailure) {
                continue;
            }
            for (DocumentPageImages.PageImage page : available) {
                boundary = boundary(runId, workflowDeadline);
                if (boundary.stoppedOutcome() != null) {
                    return new ProposedRegions(proposed);
                }
                VisualRegionProposer.ProposalResult result;
                try {
                    result = proposals.propose(page, boundary.remaining());
                } catch (RuntimeException proposalFailure) {
                    proposalToolCircuit.recordRuntimeFailure();
                    if (!proposalToolCircuit.available()) break;
                    continue;
                }
                if (!result.proposals().isEmpty()) {
                    proposed.put(page.pageNumber(), result.proposals());
                }
                if (result.diagnostic() == Diagnostic.UNAVAILABLE || result.diagnostic() == Diagnostic.TIMEOUT) {
                    proposalToolCircuit.recordRuntimeFailure();
                    if (!proposalToolCircuit.available()) break;
                } else {
                    // FOUND/NONE and page-local FAILED input or geometry rejection prove the process can still run.
                    proposalToolCircuit.recordRunnablePage();
                }
            }
        }
        return new ProposedRegions(proposed);
    }

    ProposalToolCircuit beginProposalWorkflow() {
        return new ProposalToolCircuit(proposals.configured());
    }

    private List<Claim> claims(List<LessonStep> steps) {
        return steps.stream()
                .flatMap(step -> new LinkedHashSet<>(step.sourceChunkIds()).stream()
                        .map(id -> new Claim(id, claimText(step), step.sourcePages(), step.position())))
                .toList();
    }

    private String claimText(LessonStep step) {
        return "步骤 " + step.position() + "（" + step.heading() + "）：" + step.text();
    }

    private boolean supportsExactStep(VisualRegionLocator.LocatedRegion region, List<LessonStep> steps) {
        if (region.supportedStepPositions().isEmpty()) return steps.size() == 1;
        Set<Integer> offeredPositions = steps.stream().map(LessonStep::position).collect(Collectors.toSet());
        return region.supportedStepPositions().stream().allMatch(offeredPositions::contains);
    }

    private VisualLessonEnricher.Outcome rejectionFor(
            VisualRegionLocator.LocatedRegion region,
            List<VisualRegionCandidateSelector.Candidate> attachedCandidates,
            Set<UUID> evidenceIds) {
        if (!cropPolicy.isReadableForPlayer(region)) return VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL;
        if (region.visibleDescription().isBlank()) return VisualLessonEnricher.Outcome.REJECTED_MISSING_OBSERVATION;
        if (!cropPolicy.isUsefulPlayerVisual(region)) return VisualLessonEnricher.Outcome.REJECTED_NON_VISUAL;
        if (!cropPolicy.intersectsCandidate(region, attachedCandidates)) return VisualLessonEnricher.Outcome.REJECTED_OUTSIDE_CANDIDATE;
        if (!evidenceIds.containsAll(region.supportedEvidenceIds())) return VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE;
        return null;
    }

    private VisualLessonEnricher.Outcome outcomeFor(VisualRegionLocator.Diagnostic diagnostic) {
        return switch (diagnostic) {
            case NO_REGION -> VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE;
            case SEMANTIC_REJECTED -> VisualLessonEnricher.Outcome.MODEL_SEMANTIC_REJECTED;
            case MODEL_UNAVAILABLE -> VisualLessonEnricher.Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> VisualLessonEnricher.Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> VisualLessonEnricher.Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> VisualLessonEnricher.Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> VisualLessonEnricher.Outcome.MODEL_INVALID_GEOMETRY;
            case TIMEOUT -> VisualLessonEnricher.Outcome.MODEL_TIMEOUT;
            case INTERRUPTED -> VisualLessonEnricher.Outcome.MODEL_INTERRUPTED;
            case EXECUTOR_BUSY -> VisualLessonEnricher.Outcome.MODEL_BUSY;
            case PROVIDER_FAILURE -> VisualLessonEnricher.Outcome.MODEL_PROVIDER_FAILURE;
            case FOUND -> throw new IllegalArgumentException("found visual location cannot be rejected");
        };
    }

    record Result(List<VisualRegionLocator.LocatedRegion> regions, VisualLessonEnricher.Outcome rejection) {
        Result {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }

        static Result accepted(List<VisualRegionLocator.LocatedRegion> regions) {
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("accepted visual lesson plan needs at least one region");
            }
            return new Result(regions, null);
        }

        static Result rejected(VisualLessonEnricher.Outcome rejection) {
            return new Result(List.of(), rejection);
        }
    }

    private record Boundary(Duration remaining, VisualLessonEnricher.Outcome stoppedOutcome) {
        private static Boundary active(Duration remaining) {
            return new Boundary(remaining, null);
        }

        private static Boundary stopped(VisualLessonEnricher.Outcome outcome) {
            return new Boundary(Duration.ZERO, outcome);
        }
    }

    /** Lives on the caller stack for one enrichment run; the singleton locator never retains cross-run failures. */
    static final class ProposalToolCircuit {

        private boolean available;
        private int consecutiveRuntimeFailures;

        private ProposalToolCircuit(boolean available) {
            this.available = available;
        }

        private boolean available() {
            return available;
        }

        private void recordRuntimeFailure() {
            if (!available) return;
            consecutiveRuntimeFailures++;
            if (consecutiveRuntimeFailures >= CONSECUTIVE_PROPOSAL_RUNTIME_FAILURE_LIMIT) {
                available = false;
            }
        }

        private void recordRunnablePage() {
            if (available) consecutiveRuntimeFailures = 0;
        }
    }

    private record ProposedRegions(Map<Integer, List<Proposal>> byPage) {
        private ProposedRegions {
            if (byPage == null) {
                throw new IllegalArgumentException("proposed visual regions are required");
            }
            java.util.LinkedHashMap<Integer, List<Proposal>> copied = new java.util.LinkedHashMap<>();
            byPage.forEach((page, pageProposals) -> {
                if (page == null || page < 1 || pageProposals == null) {
                    throw new IllegalArgumentException("proposed visual regions are invalid");
                }
                copied.put(page, List.copyOf(pageProposals));
            });
            byPage = Map.copyOf(copied);
        }

        private static ProposedRegions unavailable() {
            return new ProposedRegions(Map.of());
        }
    }
}
