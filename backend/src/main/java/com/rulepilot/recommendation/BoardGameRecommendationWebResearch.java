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

    /** Public subject class used for source-backed context that does not need a BGG identity. */
    enum PublicSubjectKind {
        PERSON,
        EVENT,
        ORGANIZATION,
        ENTITY
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
            ResolvedRelationship relationship,
            List<PublicContextEvidence> publicContext) {
        public CandidateDiscovery(List<CandidateLead> candidates, List<Source> sources) {
            this(candidates, sources, null, List.of());
        }

        public CandidateDiscovery(
                List<CandidateLead> candidates,
                List<Source> sources,
                ResolvedRelationship relationship) {
            this(candidates, sources, relationship, List.of());
        }

        public CandidateDiscovery {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sources = sources == null ? List.of() : List.copyOf(sources);
            publicContext = publicContext == null ? List.of() : List.copyOf(publicContext);
        }
    }

    /**
     * One externally sourced public-context fact. The application owns the evidence id and final
     * publication validates that the model selected an id returned by this same discovery read.
     */
    record PublicContextEvidence(
            String id,
            PublicSubjectKind subjectKind,
            String subject,
            String relation,
            String object,
            String statement,
            List<Integer> sourceIndexes) {
        public PublicContextEvidence {
            id = bounded(id, 16, "public context evidence id");
            subject = bounded(subject, 160, "public context subject");
            relation = bounded(relation, 120, "public context relation");
            object = bounded(object, 200, "public context object");
            statement = bounded(statement, 600, "public context statement");
            sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
            if (subjectKind == null || sourceIndexes.isEmpty() || sourceIndexes.size() > 3) {
                throw new IllegalArgumentException("public context evidence is invalid");
            }
        }

        private static String bounded(String value, int maximum, String field) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isBlank() || normalized.length() > maximum) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return normalized;
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
