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

    default void rememberVerifiedIdentity(DiscoveryRequest request, CandidateDiscovery discovery) {}

    /** Signals that the configured public-research capability cannot serve this run. */
    final class WebResearchUnavailableException extends RuntimeException {
        private final String code;

        public WebResearchUnavailableException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
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

    enum DiscoveryGoal {
        IDENTITY_ONLY,
        SELECTABLE_CARDS
    }

    enum RelationshipKind {
        DESIGNER,
        DESIGNER_GROUP,
        GAME,
        OTHER
    }

    record DiscoveryRequest(
            String query,
            String subject,
            List<BggGameType> candidateTypes,
            String locale,
            DiscoveryGoal goal) {
        public DiscoveryRequest(String query, List<BggGameType> candidateTypes, String locale) {
            this(query, query, candidateTypes, locale, DiscoveryGoal.SELECTABLE_CARDS);
        }

        public DiscoveryRequest(
                String query,
                List<BggGameType> candidateTypes,
                String locale,
                DiscoveryGoal goal) {
            this(query, query, candidateTypes, locale, goal);
        }

        public DiscoveryRequest {
            query = query == null ? "" : query.strip();
            subject = subject == null ? "" : subject.strip();
            candidateTypes = candidateTypes == null ? List.of() : List.copyOf(candidateTypes);
            goal = goal == null ? DiscoveryGoal.SELECTABLE_CARDS : goal;
        }
    }

    record CandidateDiscovery(
            List<CandidateLead> candidates,
            List<Source> sources,
            ResolvedRelationship relationship) {
        public CandidateDiscovery(List<CandidateLead> candidates, List<Source> sources) {
            this(candidates, sources, null);
        }

        public CandidateDiscovery {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** Source-backed external relationship; BGG metadata must still canonicalize its entity. */
    record ResolvedRelationship(
            RelationshipKind kind,
            List<String> entityNames,
            List<Integer> sourceIndexes) {
        public ResolvedRelationship(RelationshipKind kind, String entityName, List<Integer> sourceIndexes) {
            this(kind, entityName == null ? List.of() : List.of(entityName), sourceIndexes);
        }

        public ResolvedRelationship {
            entityNames = entityNames == null
                    ? List.of()
                    : entityNames.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(String::strip)
                            .filter(name -> !name.isBlank())
                            .distinct()
                            .toList();
            sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
            int requiredNames = kind == RelationshipKind.DESIGNER_GROUP ? 2 : 1;
            if (kind == null
                    || entityNames.size() < requiredNames
                    || entityNames.size() > 4
                    || entityNames.stream().anyMatch(name -> name.length() > 160)) {
                throw new IllegalArgumentException("external relationship entity names are invalid");
            }
        }

        public String entityName() {
            return String.join(", ", entityNames);
        }
    }

    /** Source-backed title hypothesis; BGG identity is resolved by the catalog tool afterward. */
    record CandidateLead(String name, String fitObservation, List<Integer> sourceIndexes) {
        public CandidateLead(String name, List<Integer> sourceIndexes) {
            this(name, "", sourceIndexes);
        }

        public CandidateLead {
            name = name == null ? "" : name.strip();
            fitObservation = fitObservation == null ? "" : fitObservation;
            sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
        }
    }
}
