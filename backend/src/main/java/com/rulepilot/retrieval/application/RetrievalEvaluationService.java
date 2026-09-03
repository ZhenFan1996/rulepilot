package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.HybridRuleSearch.SearchPage;
import com.rulepilot.retrieval.RetrievalEvaluationSet;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport.RetrievalError;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport.RetrievedCandidate;
import com.rulepilot.retrieval.domain.RetrievalEvaluationReport.SampleResult;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample.RelevantEvidence;
import com.rulepilot.document.DocumentVersionScopeLookup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RetrievalEvaluationService {

    private static final int EVALUATION_LIMIT = 5;
    private final HybridRuleSearch search;
    private final RetrievalEvaluationSet evaluationSet;
    private final DocumentVersionScopeLookup documents;

    public RetrievalEvaluationService(
            HybridRuleSearch search,
            RetrievalEvaluationSet evaluationSet,
            DocumentVersionScopeLookup documents) {
        this.search = search;
        this.evaluationSet = evaluationSet;
        this.documents = documents;
    }

    public RetrievalEvaluationReport evaluate(UUID documentVersionId) {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("document version is required");
        }
        var document = documents.findVersion(documentVersionId)
                .filter(version -> "READY".equals(version.processingStatus()))
                .orElseThrow(() -> new IllegalArgumentException("retrieval evaluation document is not ready"));
        if (!evaluationSet.sourceSha256().equals(document.sourceSha256())) {
            throw new IllegalArgumentException("retrieval evaluation fixture does not match the document source");
        }

        List<RetrievalEvaluationSample> samples = evaluationSet.samples();
        List<RetrievalError> errors = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        List<SampleResult> sampleResults = new ArrayList<>();
        int hits = 0;
        double reciprocalRankTotal = 0;

        for (RetrievalEvaluationSample sample : samples) {
            long started = System.nanoTime();
            SearchPage page = search.searchPage(
                    documentVersionId, sample.question(), new RetrievalOptions(EVALUATION_LIMIT, Set.of(), null));
            double latencyMillis = (System.nanoTime() - started) / 1_000_000.0;
            latencies.add(latencyMillis);
            List<HybridEvidenceHit> results = page.hits();

            int relevantRank = relevantRank(results, sample.relevantEvidence());
            List<RetrievedCandidate> retrieved = retrieved(results);
            sampleResults.add(new SampleResult(
                    sample.id(), sample.question(), sample.relevantEvidence(), relevantRank,
                    roundMillis(latencyMillis), page.sourceAvailability(), retrieved));
            if (relevantRank > 0) {
                hits++;
                reciprocalRankTotal += 1.0 / relevantRank;
            } else {
                errors.add(new RetrievalError(
                        sample.id(), sample.question(), sample.relevantEvidence(), page.sourceAvailability(), retrieved));
            }
        }

        return report(
                documentVersionId, samples.size(), hits, reciprocalRankTotal, latencies, sampleResults, errors);
    }

    private int relevantRank(List<HybridEvidenceHit> results, List<RelevantEvidence> relevantEvidence) {
        for (int index = 0; index < results.size(); index++) {
            HybridEvidenceHit result = results.get(index);
            if (relevantEvidence.stream().anyMatch(target -> matches(result, target))) {
                return index + 1;
            }
        }
        return 0;
    }

    private boolean matches(HybridEvidenceHit result, RelevantEvidence target) {
        var evidence = result.evidence();
        if (target.pageNumber() < evidence.pageFrom() || target.pageNumber() > evidence.pageTo()) return false;
        String searchable = normalize(evidence.heading() + "\n" + evidence.excerpt());
        return target.requiredPhrases().stream()
                .map(this::normalize)
                .allMatch(searchable::contains);
    }

    private String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private List<RetrievedCandidate> retrieved(List<HybridEvidenceHit> results) {
        return results.stream()
                .map(HybridEvidenceHit::evidence)
                .map(hit -> new RetrievedCandidate(
                        hit.chunkId(), hit.sectionType(), hit.heading(), hit.pageFrom(), hit.pageTo()))
                .toList();
    }

    private RetrievalEvaluationReport report(
            UUID documentVersionId,
            int sampleCount,
            int hits,
            double reciprocalRankTotal,
            List<Double> latencies,
            List<SampleResult> sampleResults,
            List<RetrievalError> errors) {
        List<Double> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        double average = sortedLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int p95Index = Math.max(0, (int) Math.ceil(sortedLatencies.size() * 0.95) - 1);
        double p95 = sortedLatencies.isEmpty() ? 0 : sortedLatencies.get(p95Index);
        double maximum = sortedLatencies.isEmpty() ? 0 : sortedLatencies.getLast();
        return new RetrievalEvaluationReport(
                evaluationSet.name(),
                evaluationSet.sourceSha256(),
                documentVersionId,
                sampleCount,
                hits,
                ratio(hits, sampleCount),
                ratio(reciprocalRankTotal, sampleCount),
                roundMillis(average),
                roundMillis(p95),
                roundMillis(maximum),
                sampleResults,
                errors);
    }

    private double ratio(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double roundMillis(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }
}
