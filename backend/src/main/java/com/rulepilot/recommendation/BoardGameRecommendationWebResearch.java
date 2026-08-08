package com.rulepilot.recommendation;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.catalog.BggGameType;
import java.util.List;
import java.util.Optional;

/** Bounded web-research capability owned by the recommendation Agent, not the rule-answering Agent. */
public interface BoardGameRecommendationWebResearch {

    boolean configured();

    Optional<Research> research(Request request);

    default Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
        return Optional.empty();
    }

    record Request(List<Candidate> candidates, String locale) {}

    record Research(List<GameResearch> games, List<Source> sources) {
        public static Research empty() {
            return new Research(List.of(), List.of());
        }
    }

    record GameResearch(int bggId, List<Observation> observations) {}

    record Observation(String text, List<Integer> sourceIndexes) {}

    record Source(int index, String title, String url, String domain) {}

    record DiscoveryRequest(List<DiscoverySignal> signals, List<BggGameType> candidateTypes, String locale) {
        public DiscoveryRequest {
            signals = signals == null ? List.of() : List.copyOf(signals);
            candidateTypes = candidateTypes == null ? List.of() : List.copyOf(candidateTypes);
        }
    }

    record DiscoverySignal(String term, FeatureMode mode, FeatureSource source) {}

    record CandidateDiscovery(List<CandidateLead> candidates, List<Source> sources) {
        public CandidateDiscovery {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    record CandidateLead(int bggId, String name, String fitObservation, List<Integer> sourceIndexes) {
        public CandidateLead(int bggId, String name, List<Integer> sourceIndexes) {
            this(bggId, name, "", sourceIndexes);
        }

        public CandidateLead {
            fitObservation = fitObservation == null ? "" : fitObservation;
            sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
        }
    }
}
