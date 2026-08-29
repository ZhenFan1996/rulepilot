package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.FullTextRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class HybridRuleSearchService implements HybridRuleSearch {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridRuleSearchService.class);
    static final String PHASE_DURATION_METRIC = "rulepilot.retrieval.hybrid.phase.duration";
    static final String CHANNEL_OUTCOME_METRIC = "rulepilot.retrieval.hybrid.channel";
    static final String AVAILABILITY_METRIC = "rulepilot.retrieval.hybrid.availability";
    private static final int RRF_K = 60;
    private static final double CURRENT_SECTION_BOOST = 0.004;
    private final FullTextRuleSearch fullText;
    private final VectorRuleSearch vector;
    private final RuleEvidenceLookup evidenceLookup;
    private final MeterRegistry metrics;

    public HybridRuleSearchService(
            FullTextRuleSearch fullText,
            VectorRuleSearch vector,
            RuleEvidenceLookup evidenceLookup,
            MeterRegistry metrics) {
        this.fullText = fullText;
        this.vector = vector;
        this.evidenceLookup = evidenceLookup;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HybridEvidenceHit> search(UUID documentVersionId, String query, RetrievalOptions options) {
        return searchPage(documentVersionId, query, options).hits();
    }

    @Override
    @Transactional(readOnly = true)
    public SearchPage searchPage(UUID documentVersionId, String query, RetrievalOptions options) {
        if (documentVersionId == null || query == null || query.isBlank() || options == null
                || options.limit() < 1) {
            throw new IllegalArgumentException("hybrid retrieval query and options are required");
        }
        int limit = options.limit();
        long requestedThrough = (long) options.offset() + limit;
        long acceptedLookahead = requestedThrough + 1;
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        ChannelScan fullTextScan = new ChannelScan("full-text");
        ChannelScan vectorScan = new ChannelScan("vector");
        VectorRuleSearch.PreparedSearch preparedVector = null;
        try {
            preparedVector = recordPhase("vector", () -> vector.prepare(documentVersionId, query));
            if (preparedVector == null) {
                throw new IllegalStateException("vector search returned no prepared query");
            }
        } catch (RuntimeException failure) {
            vectorScan.fail(failure);
            LOGGER.warn(
                    "Hybrid retrieval channel {} is unavailable for document version {}: {}",
                    vectorScan.name(),
                    documentVersionId,
                    failure.getClass().getSimpleName());
        }
        VectorRuleSearch.PreparedSearch vectorQuery = preparedVector;
        while ((long) candidates.size() < acceptedLookahead
                && (fullTextScan.canContinue() || vectorScan.canContinue())) {
            scanChannel(
                    fullTextScan,
                    documentVersionId,
                    limit,
                    () -> fullText.search(documentVersionId, query, fullTextScan.nextOffset(), limit),
                    hits -> addEligible(candidates, hits, true, fullTextScan.nextOffset(), options));
            scanChannel(
                    vectorScan,
                    documentVersionId,
                    limit,
                    () -> vectorQuery.search(vectorScan.nextOffset(), limit),
                    hits -> addEligible(candidates, hits, false, vectorScan.nextOffset(), options));
        }
        recordChannelOutcome(fullTextScan);
        recordChannelOutcome(vectorScan);
        if (!fullTextScan.succeeded() && !vectorScan.succeeded()) {
            recordAvailability("failed");
            IllegalStateException unavailable = new IllegalStateException(
                    "all hybrid retrieval channels are unavailable", fullTextScan.failure());
            unavailable.addSuppressed(vectorScan.failure());
            throw unavailable;
        }
        boolean partial = fullTextScan.failure() != null || vectorScan.failure() != null;
        SourceAvailability sourceAvailability = partial
                ? SourceAvailability.PARTIAL
                : SourceAvailability.COMPLETE;
        recordAvailability(partial ? "partial" : "complete");
        if (partial) {
            LOGGER.warn(
                    "Hybrid retrieval retained the available channel for document version {}; fullTextAvailable={}, vectorAvailable={}",
                    documentVersionId,
                    fullTextScan.failure() == null,
                    vectorScan.failure() == null);
        }
        List<HybridEvidenceHit> ranked = candidates.values().stream()
                .map(candidate -> candidate.result(options.currentSectionType()))
                .sorted(java.util.Comparator.comparingDouble(HybridEvidenceHit::score).reversed()
                        .thenComparing(hit -> hit.evidence().chunkId()))
                .toList();
        Map<UUID, HybridEvidenceHit> selected = new LinkedHashMap<>();
        ranked.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        List<HybridEvidenceHit> selectedEvidence = selected.values().stream()
                .skip(options.offset())
                .limit(limit)
                .toList();
        boolean hasMore = (long) selected.size() > requestedThrough;
        if (selectedEvidence.isEmpty()) return new SearchPage(List.of(), hasMore, sourceAvailability);
        var selectedIds = selectedEvidence.stream()
                .map(hit -> hit.evidence().chunkId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<UUID, RuleEvidenceHit> completeEvidence = recordPhase("canonical-hydration", () -> evidenceLookup
                .findByChunkIds(
                        documentVersionId,
                        selectedIds)
                .stream()
                .peek(hit -> {
                    if (!documentVersionId.equals(hit.documentVersionId())) {
                        throw new IllegalStateException("canonical evidence escaped the requested document scope");
                    }
                })
                .collect(java.util.stream.Collectors.toUnmodifiableMap(RuleEvidenceHit::chunkId, hit -> hit)));
        if (!completeEvidence.keySet().equals(selectedIds)) {
            throw new IllegalStateException("ranked evidence could not be canonically hydrated");
        }
        List<HybridEvidenceHit> hydrated = selectedEvidence.stream()
                .map(hit -> new HybridEvidenceHit(
                        completeEvidence.get(hit.evidence().chunkId()),
                        hit.score(),
                        hit.fullTextRank(),
                        hit.vectorRank(),
                        hit.currentSectionBoosted()))
                .toList();
        return new SearchPage(hydrated, hasMore, sourceAvailability);
    }

    private boolean withinPageScope(RuleEvidenceHit hit, java.util.Set<Integer> allowedPages) {
        if (allowedPages == null) return true;
        return java.util.stream.IntStream.rangeClosed(hit.pageFrom(), hit.pageTo())
                .allMatch(allowedPages::contains);
    }

    private <T> T recordPhase(String phase, Supplier<T> work) {
        long startedAt = System.nanoTime();
        try {
            return work.get();
        } finally {
            Timer.builder(PHASE_DURATION_METRIC)
                    .description("Hybrid retrieval phase duration")
                    .tag("phase", phase)
                    .register(metrics)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void scanChannel(
            ChannelScan channel,
            UUID documentVersionId,
            int batchSize,
            Supplier<List<RuleEvidenceHit>> retrieval,
            java.util.function.Consumer<List<RuleEvidenceHit>> acceptedHits) {
        if (!channel.canContinue()) return;
        try {
            List<RuleEvidenceHit> hits = recordPhase(channel.name(), retrieval);
            if (hits == null) throw new IllegalStateException("hybrid retrieval channel returned null hits");
            if (hits.size() > batchSize) {
                throw new IllegalStateException("hybrid retrieval channel returned more hits than requested");
            }
            acceptedHits.accept(hits);
            channel.advance(hits.size(), hits.size() < batchSize);
        } catch (RuntimeException failure) {
            channel.fail(failure);
            LOGGER.warn(
                    "Hybrid retrieval channel {} is unavailable for document version {}: {}",
                    channel.name(),
                    documentVersionId,
                    failure.getClass().getSimpleName());
        }
    }

    private void recordChannelOutcome(ChannelScan channel) {
        Counter.builder(CHANNEL_OUTCOME_METRIC)
                .description("Availability outcome of one hybrid retrieval channel")
                .tag("channel", channel.name())
                .tag("outcome", channel.failure() == null ? "available" : "unavailable")
                .register(metrics)
                .increment();
    }

    private void recordAvailability(String outcome) {
        Counter.builder(AVAILABILITY_METRIC)
                .description("Whether all, one, or no hybrid retrieval channels were available")
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
    }

    private void addEligible(
            Map<UUID, Candidate> candidates,
            List<RuleEvidenceHit> hits,
            boolean fullTextSource,
            int offset,
            RetrievalOptions options) {
        for (int index = 0; index < hits.size(); index++) {
            RuleEvidenceHit hit = hits.get(index);
            if (options.excludedEvidenceIds().contains(hit.chunkId())
                    || !eligible(hit, options)) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> new Candidate(hit));
            if (fullTextSource) candidate.fullTextRank = offset + index + 1;
            else candidate.vectorRank = offset + index + 1;
        }
    }

    private boolean eligible(RuleEvidenceHit hit, RetrievalOptions options) {
        return (options.sectionTypes().isEmpty()
                        || options.sectionTypes().contains(hit.sectionType().toUpperCase(java.util.Locale.ROOT)))
                && withinPageScope(hit, options.allowedEvidencePages());
    }

    private static final class Candidate {
        private final RuleEvidenceHit evidence;
        private Integer fullTextRank;
        private Integer vectorRank;

        private Candidate(RuleEvidenceHit evidence) {
            this.evidence = evidence;
        }

        private HybridEvidenceHit result(String currentSectionType) {
            double score = contribution(fullTextRank) + contribution(vectorRank);
            boolean boosted = currentSectionType != null && currentSectionType.equalsIgnoreCase(evidence.sectionType());
            if (boosted) score += CURRENT_SECTION_BOOST;
            return new HybridEvidenceHit(evidence, score, fullTextRank, vectorRank, boosted);
        }

        private double contribution(Integer rank) {
            return rank == null ? 0 : 1.0 / (RRF_K + rank);
        }
    }

    private static final class ChannelScan {
        private final String name;
        private int nextOffset;
        private boolean exhausted;
        private boolean succeeded;
        private RuntimeException failure;

        private ChannelScan(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private int nextOffset() {
            return nextOffset;
        }

        private boolean canContinue() {
            return !exhausted && failure == null;
        }

        private void advance(int consumed, boolean exhausted) {
            nextOffset = Math.addExact(nextOffset, consumed);
            this.exhausted = exhausted;
            succeeded = true;
        }

        private void fail(RuntimeException failure) {
            this.failure = java.util.Objects.requireNonNull(failure);
        }

        private boolean succeeded() {
            return succeeded;
        }

        private RuntimeException failure() {
            return failure;
        }
    }
}
