package com.rulepilot.recommendation;

import com.rulepilot.catalog.BggGameType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Bounded web-research capability owned by the recommendation Agent, not the rule-answering Agent. */
public interface BoardGameRecommendationWebResearch {

    boolean configured();

    Optional<Research> research(Request request);

    default Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
        return Optional.empty();
    }

    record Request(List<Candidate> candidates, String locale, String question) {
        public Request(List<Candidate> candidates, String locale) {
            this(candidates, locale, "");
        }

        public Request {
            question = question == null ? "" : question.strip();
        }
    }

    record Research(List<GameResearch> games, List<Source> sources) {
        public static Research empty() {
            return new Research(List.of(), List.of());
        }
    }

    record GameResearch(int bggId, List<Observation> observations) {}

    record Observation(String text, List<Integer> sourceIndexes) {}

    record Source(int index, String title, String url, String domain) {}

    record Candidate(
            int bggId,
            String name,
            Integer year,
            Integer rank,
            BigDecimal rating,
            BigDecimal weight,
            Integer minPlayers,
            Integer maxPlayers,
            Integer minimumMinutes,
            Integer maximumMinutes,
            List<String> categories,
            List<String> mechanics,
            List<String> families,
            List<String> designers,
            List<String> publishers) {
        public Candidate {
            categories = categories == null ? List.of() : List.copyOf(categories);
            mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
            families = families == null ? List.of() : List.copyOf(families);
            designers = designers == null ? List.of() : List.copyOf(designers);
            publishers = publishers == null ? List.of() : List.copyOf(publishers);
        }
    }

    record DiscoveryRequest(String query, List<BggGameType> candidateTypes, String locale) {
        public DiscoveryRequest {
            query = query == null ? "" : query.strip();
            candidateTypes = candidateTypes == null ? List.of() : List.copyOf(candidateTypes);
        }
    }

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
