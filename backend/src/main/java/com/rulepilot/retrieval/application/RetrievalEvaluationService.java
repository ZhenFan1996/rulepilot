package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.RetrievalEvaluationSet;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport.RetrievalError;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport.RetrievedCandidate;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RetrievalEvaluationService {

    private static final int EVALUATION_LIMIT = 5;
    private final HybridRuleSearch search;
    private final RetrievalEvaluationSet evaluationSet;

    public RetrievalEvaluationService(HybridRuleSearch search, RetrievalEvaluationSet evaluationSet) {
        this.search = search;
        this.evaluationSet = evaluationSet;
    }

    public RetrievalEvaluationReport evaluate(UUID documentVersionId) {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("document version is required");
        }

        List<RetrievalEvaluationSample> samples = evaluationSet.samples();
        List<RetrievalError> errors = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        int hits = 0;
        double reciprocalRankTotal = 0;

        for (RetrievalEvaluationSample sample : samples) {
            long started = System.nanoTime();
            List<HybridEvidenceHit> results = search.search(
                    documentVersionId, sample.question(), new RetrievalOptions(EVALUATION_LIMIT, Set.of(), null));
            latencies.add((System.nanoTime() - started) / 1_000_000.0);

            int relevantRank = relevantRank(results, sample.expectedSectionTypes());
            if (relevantRank > 0) {
                hits++;
                reciprocalRankTotal += 1.0 / relevantRank;
            } else {
                errors.add(toError(sample, results));
            }
        }

        return report(documentVersionId, samples.size(), hits, reciprocalRankTotal, latencies, errors);
    }

    private int relevantRank(List<HybridEvidenceHit> results, Set<String> expectedSectionTypes) {
        for (int index = 0; index < results.size(); index++) {
            if (expectedSectionTypes.contains(results.get(index).evidence().sectionType().toUpperCase())) {
                return index + 1;
            }
        }
        return 0;
    }

    private RetrievalError toError(RetrievalEvaluationSample sample, List<HybridEvidenceHit> results) {
        List<RetrievedCandidate> retrieved = results.stream()
                .map(HybridEvidenceHit::evidence)
                .map(hit -> new RetrievedCandidate(hit.sectionType(), hit.heading(), hit.pageFrom(), hit.pageTo()))
                .toList();
        return new RetrievalError(sample.id(), sample.question(), sample.expectedSectionTypes(), retrieved);
    }

    private RetrievalEvaluationReport report(
            UUID documentVersionId,
            int sampleCount,
            int hits,
            double reciprocalRankTotal,
            List<Double> latencies,
            List<RetrievalError> errors) {
        List<Double> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        double average = sortedLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int p95Index = Math.max(0, (int) Math.ceil(sortedLatencies.size() * 0.95) - 1);
        double p95 = sortedLatencies.isEmpty() ? 0 : sortedLatencies.get(p95Index);
        return new RetrievalEvaluationReport(
                evaluationSet.name(),
                documentVersionId,
                sampleCount,
                hits,
                ratio(hits, sampleCount),
                ratio(reciprocalRankTotal, sampleCount),
                roundMillis(average),
                roundMillis(p95),
                errors);
    }

    private double ratio(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double roundMillis(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }
}
