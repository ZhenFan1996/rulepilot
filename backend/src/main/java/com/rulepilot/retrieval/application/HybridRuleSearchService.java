package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.FullTextRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class HybridRuleSearchService implements HybridRuleSearch {

    private static final int RRF_K = 60;
    private static final double CURRENT_SECTION_BOOST = 0.004;
    private static final int MAX_RESULTS = 20;
    private final FullTextRuleSearch fullText;
    private final VectorRuleSearch vector;

    public HybridRuleSearchService(FullTextRuleSearch fullText, VectorRuleSearch vector) {
        this.fullText = fullText;
        this.vector = vector;
    }

    @Override
    public List<HybridEvidenceHit> search(UUID documentVersionId, String query, RetrievalOptions options) {
        if (documentVersionId == null || query == null || query.isBlank() || options == null) {
            throw new IllegalArgumentException("hybrid retrieval query and options are required");
        }
        int limit = Math.max(1, Math.min(options.limit(), MAX_RESULTS));
        List<RuleEvidenceHit> fullTextHits = fullText.search(documentVersionId, query, MAX_RESULTS);
        List<RuleEvidenceHit> vectorHits = vector.search(documentVersionId, query, MAX_RESULTS);
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        add(candidates, fullTextHits, true);
        add(candidates, vectorHits, false);
        return candidates.values().stream()
                .filter(candidate -> options.sectionTypes().isEmpty()
                        || options.sectionTypes().contains(candidate.evidence.sectionType().toUpperCase()))
                .map(candidate -> candidate.result(options.currentSectionType()))
                .sorted(java.util.Comparator.comparingDouble(HybridEvidenceHit::score).reversed()
                        .thenComparing(hit -> hit.evidence().chunkId()))
                .limit(limit)
                .toList();
    }

    private void add(Map<UUID, Candidate> candidates, List<RuleEvidenceHit> hits, boolean fullTextSource) {
        for (int index = 0; index < hits.size(); index++) {
            RuleEvidenceHit hit = hits.get(index);
            Candidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> new Candidate(hit));
            if (fullTextSource) candidate.fullTextRank = index + 1;
            else candidate.vectorRank = index + 1;
        }
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
}
