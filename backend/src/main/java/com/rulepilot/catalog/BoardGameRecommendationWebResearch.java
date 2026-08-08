package com.rulepilot.catalog;

import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Candidate;
import java.util.List;
import java.util.Optional;

/** Bounded web-research capability owned by the recommendation Agent, not the rule-answering Agent. */
public interface BoardGameRecommendationWebResearch {

    boolean configured();

    Optional<Research> research(Request request);

    record Request(List<Candidate> candidates, String locale) {}

    record Research(List<GameResearch> games, List<Source> sources) {
        public static Research empty() {
            return new Research(List.of(), List.of());
        }
    }

    record GameResearch(int bggId, List<Observation> observations) {}

    record Observation(String text, List<Integer> sourceIndexes) {}

    record Source(int index, String title, String url, String domain) {}
}
