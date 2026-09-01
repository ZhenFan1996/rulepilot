package com.rulepilot.catalog.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogFilters;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataResult;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataStatus;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataValue;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataCriterion;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataDimension;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.SelectionEligibility;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalogRepository;
import com.rulepilot.catalog.application.BggRankedCatalogRepository.RecommendationCandidate;
import com.rulepilot.catalog.application.BggRankedCatalogRepository.RecommendationCandidatePage;
import com.rulepilot.catalog.application.BggRankedCatalogRepository.SelectionCandidate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresBggRankedCatalog implements BggRankedCatalogRepository {

    private static final Map<BggGameType, String> TYPE_COLUMNS = Map.of(
            BggGameType.ABSTRACT, "abstracts_rank",
            BggGameType.CUSTOMIZABLE, "cgs_rank",
            BggGameType.CHILDREN, "childrensgames_rank",
            BggGameType.FAMILY, "familygames_rank",
            BggGameType.PARTY, "partygames_rank",
            BggGameType.STRATEGY, "strategygames_rank",
            BggGameType.THEMATIC, "thematic_rank",
            BggGameType.WAR, "wargames_rank");

    private static final String COLUMNS = """
            bgg_id, source_name, publication_year, overall_rank, bayes_average, average_rating,
            users_rated, is_expansion, abstracts_rank, cgs_rank, childrensgames_rank,
            familygames_rank, partygames_rank, strategygames_rank, thematic_rank,
            wargames_rank
            """;
    private static final String QUALIFIED_COLUMNS = """
            g.bgg_id, g.source_name, g.publication_year, g.overall_rank, g.bayes_average, g.average_rating,
            g.users_rated, g.is_expansion, g.abstracts_rank, g.cgs_rank, g.childrensgames_rank,
            g.familygames_rank, g.partygames_rank, g.strategygames_rank, g.thematic_rank,
            g.wargames_rank
            """;
    private static final String TEXT_SEARCH_VECTOR = """
            to_tsvector('english'::regconfig,
                coalesce(cache.payload->>'name', '') || ' ' ||
                coalesce(cache.payload->>'chineseName', '') || ' ' ||
                coalesce(cache.payload->>'description', '') || ' ' ||
                coalesce(cache.payload->>'categories', '') || ' ' ||
                coalesce(cache.payload->>'mechanics', '') || ' ' ||
                coalesce(cache.payload->>'families', '') || ' ' ||
                coalesce(cache.payload->>'designers', '') || ' ' ||
                coalesce(cache.payload->>'publishers', ''))
            """;
    private static final String TEXT_SEARCH_QUERY =
            "websearch_to_tsquery('english'::regconfig, regexp_replace(:textQuery, '\\s+', ' OR ', 'g'))";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresBggRankedCatalog(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Snapshot> findSnapshot() {
        return jdbc.query(
                        "SELECT imported_at, source_date, game_count, sha256 FROM bgg_ranked_catalog_snapshot WHERE singleton",
                        new MapSqlParameterSource(),
                        (rs, row) -> new Snapshot(
                                rs.getTimestamp("imported_at").toInstant(),
                                rs.getObject("source_date", java.time.LocalDate.class),
                                rs.getInt("game_count"),
                                rs.getString("sha256")))
                .stream()
                .findFirst();
    }

    @Override
    public Page find(Query query) {
        String where = whereClause(query.type(), query.search());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("search", escapedSearch(query.search()))
                .addValue("limit", query.size())
                .addValue("offset", Math.multiplyExact(query.page(), query.size()));
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM bgg_ranked_game g WHERE " + where, parameters, Long.class);
        String sql = "SELECT " + COLUMNS + " FROM bgg_ranked_game g WHERE " + where
                + " ORDER BY " + orderClause(query) + " LIMIT :limit OFFSET :offset";
        List<RankedGame> games = jdbc.query(sql, parameters, this::mapGame);
        return new Page(total == null ? 0 : total, query.page(), query.size(), games);
    }

    @Override
    public List<RankedGame> findExactName(String name) {
        String checked = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (checked.isBlank()) return List.of();
        if (checked.length() > 120) {
            throw new IllegalArgumentException("BGG exact name must contain at most 120 characters");
        }
        LinkedHashMap<Integer, RankedGame> matches = new LinkedHashMap<>();
        findExactNames(List.of(checked)).forEach(match ->
                matches.putIfAbsent(match.game().bggId(), match.game()));
        return matches.values().stream().limit(3).toList();
    }

    @Override
    public List<ExactNameMatch> findExactNames(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        List<String> checked = names.stream()
                .filter(java.util.Objects::nonNull)
                .map(name -> name.strip().replaceAll("\\s+", " "))
                .filter(name -> !name.isBlank())
                .peek(name -> {
                    if (name.length() > 120) {
                        throw new IllegalArgumentException("BGG exact name must contain at most 120 characters");
                    }
                })
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .limit(60)
                .toList();
        if (checked.isEmpty()) return List.of();
        String sql = """
                SELECT matched_name, %s
                FROM (
                    SELECT lower(g.source_name) AS matched_name, %s
                    FROM bgg_ranked_game g
                    WHERE lower(g.source_name) IN (:names)
                    UNION ALL
                    SELECT lower(alias.alias) AS matched_name, %s
                    FROM bgg_game_name_alias alias
                    JOIN bgg_ranked_game g ON g.bgg_id = alias.bgg_id
                    WHERE lower(alias.alias) IN (:names)
                ) matches
                ORDER BY matched_name, is_expansion ASC,
                         overall_rank ASC NULLS LAST, users_rated DESC, bgg_id ASC
                """.formatted(COLUMNS, QUALIFIED_COLUMNS, QUALIFIED_COLUMNS);
        return jdbc.query(
                sql,
                new MapSqlParameterSource().addValue("names", checked),
                (resultSet, rowNumber) -> new ExactNameMatch(
                        resultSet.getString("matched_name"), mapGame(resultSet, rowNumber)));
    }

    @Override
    public List<RankedGame> findRankedRange(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("BGG ranked range is invalid");
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM bgg_ranked_game g WHERE NOT g.is_expansion "
                        + "ORDER BY g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC "
                        + "LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset),
                this::mapGame);
    }

    @Override
    public List<RankedGame> findByIds(List<Integer> bggIds) {
        if (bggIds == null || bggIds.isEmpty()) return List.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("bggIds", bggIds);
        List<RankedGame> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM bgg_ranked_game WHERE bgg_id IN (:bggIds)",
                parameters,
                this::mapGame);
        Map<Integer, RankedGame> byId = found.stream().collect(java.util.stream.Collectors.toMap(
                RankedGame::bggId, game -> game));
        return bggIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<RankedGame> findByMetadataFilters(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            int maximum) {
        return findByMetadataFilters(types, categories, mechanics, designers, maximum, 0);
    }

    @Override
    public List<RankedGame> findByMetadataFilters(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            int maximum,
            int offset) {
        return findByMetadataFilters(new CatalogFilters(types, categories, mechanics, designers, maximum, offset));
    }

    @Override
    public List<RankedGame> findByMetadataFilters(CatalogFilters filters) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", filters.maximum())
                .addValue("offset", filters.offset());
        List<String> clauses = new ArrayList<>();
        clauses.add(typeFilter(filters.types()));
        // Recommendation browsing can publish only games with a complete local discovery record. Apply that
        // invariant before LIMIT/OFFSET so an unavailable high-ranked row cannot consume a result slot and turn a
        // satisfiable three-card request into a false shortfall after service hydration.
        clauses.add("""
                EXISTS (
                    SELECT 1
                    FROM bgg_metadata_cache usable_details
                    WHERE usable_details.cache_kind = 'DISCOVERY'
                      AND usable_details.bgg_id = g.bgg_id
                      AND usable_details.stale_until > NOW()
                      AND jsonb_typeof(usable_details.payload) = 'object'
                )
                """.strip());
        addMetadataFilters(clauses, parameters, "categories", "category", filters.categories());
        addMetadataFilters(clauses, parameters, "mechanics", "mechanic", filters.mechanics());
        addMetadataFilters(clauses, parameters, "designers", "designer", filters.designers());
        addMetadataFilters(clauses, parameters, "publishers", "publisher", filters.publishers());
        addMetadataFilters(clauses, parameters, "families", "family", filters.families());
        if (filters.minimumPublicationYear() != null) {
            clauses.add("g.publication_year >= :minimumPublicationYear");
            parameters.addValue("minimumPublicationYear", filters.minimumPublicationYear());
        }
        if (filters.maximumPublicationYear() != null) {
            clauses.add("g.publication_year <= :maximumPublicationYear");
            parameters.addValue("maximumPublicationYear", filters.maximumPublicationYear());
        }
        if (filters.minimumAverageRating() != null) {
            clauses.add("g.average_rating >= :minimumAverageRating");
            parameters.addValue("minimumAverageRating", filters.minimumAverageRating());
        }
        if (filters.minimumRatingsCount() != null) {
            clauses.add("g.users_rated >= :minimumRatingsCount");
            parameters.addValue("minimumRatingsCount", filters.minimumRatingsCount());
        }
        String textMatches = "";
        String textJoin = "";
        if (filters.textQuery() != null) {
            parameters.addValue("textQuery", filters.textQuery());
            textMatches = """
                    WITH text_matches AS MATERIALIZED (
                        SELECT cache.bgg_id,
                               max(ts_rank_cd(%s, %s)) AS relevance
                        FROM bgg_metadata_cache cache
                        WHERE cache.cache_kind IN ('DISCOVERY', 'GAME')
                          AND cache.stale_until > NOW()
                          AND %s @@ %s
                        GROUP BY cache.bgg_id
                    )
                    """.formatted(
                    TEXT_SEARCH_VECTOR,
                    TEXT_SEARCH_QUERY,
                    TEXT_SEARCH_VECTOR,
                    TEXT_SEARCH_QUERY);
            textJoin = " LEFT JOIN text_matches text_match ON text_match.bgg_id = g.bgg_id ";
        }
        String relevanceOrder = filters.textQuery() == null
                ? ""
                : "text_match.relevance DESC NULLS LAST, ";
        String sql = textMatches + "SELECT " + QUALIFIED_COLUMNS + " FROM bgg_ranked_game g " + textJoin + " WHERE "
                + String.join(" AND ", clauses)
                + " ORDER BY " + relevanceOrder + metadataOrder(filters.sort()) + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, parameters, this::mapGame);
    }

    @Override
    public Optional<RecommendationCandidatePage> findRecommendationCandidates(
            CatalogFilters filters,
            SelectionEligibility eligibility) {
        if (filters == null || eligibility == null) {
            throw new IllegalArgumentException("BGG recommendation candidate query is required");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", filters.maximum())
                .addValue("offset", filters.offset());
        List<String> clauses = new ArrayList<>();
        clauses.add(typeFilter(filters.types()));
        // The joined payload is both the value constrained here and the value returned to the application. Keeping
        // selection, count, hydration, and LIMIT in one statement prevents a later cache read from losing a card or
        // turning a bounded ranking window into a false catalog-exhaustion claim.
        clauses.add("jsonb_typeof(discovery.payload) = 'object'");
        clauses.add("jsonb_typeof(discovery.payload->'bggId') = 'number'");
        clauses.add("(discovery.payload->>'bggId')::integer = g.bgg_id");
        clauses.add("jsonb_typeof(discovery.payload->'name') = 'string'");
        clauses.add("jsonb_typeof(discovery.payload->'categories') = 'array'");
        clauses.add("jsonb_typeof(discovery.payload->'mechanics') = 'array'");
        clauses.add("jsonb_typeof(discovery.payload->'families') = 'array'");
        clauses.add("jsonb_typeof(discovery.payload->'designers') = 'array'");
        clauses.add("jsonb_typeof(discovery.payload->'publishers') = 'array'");
        addJoinedMetadataFilters(
                clauses, parameters, "categories", "category", filters.categories());
        addJoinedMetadataFilters(
                clauses, parameters, "mechanics", "mechanic", filters.mechanics());
        addJoinedMetadataFilters(
                clauses, parameters, "designers", "designer", filters.designers());
        addJoinedMetadataFilters(
                clauses, parameters, "publishers", "publisher", filters.publishers());
        addJoinedMetadataFilters(
                clauses, parameters, "families", "family", filters.families());
        if (filters.minimumPublicationYear() != null) {
            clauses.add("g.publication_year >= :minimumPublicationYear");
            parameters.addValue("minimumPublicationYear", filters.minimumPublicationYear());
        }
        if (filters.maximumPublicationYear() != null) {
            clauses.add("g.publication_year <= :maximumPublicationYear");
            parameters.addValue("maximumPublicationYear", filters.maximumPublicationYear());
        }
        if (filters.minimumAverageRating() != null) {
            clauses.add("g.average_rating >= :minimumAverageRating");
            parameters.addValue("minimumAverageRating", filters.minimumAverageRating());
        }
        if (filters.minimumRatingsCount() != null) {
            clauses.add("g.users_rated >= :minimumRatingsCount");
            parameters.addValue("minimumRatingsCount", filters.minimumRatingsCount());
        }
        addSelectionEligibility(clauses, parameters, eligibility);

        String textMatches = "";
        String textJoin = "";
        String relevanceProjection = "NULL::real AS relevance";
        if (filters.textQuery() != null) {
            parameters.addValue("textQuery", filters.textQuery());
            textMatches = """
                    text_matches AS MATERIALIZED (
                        SELECT cache.bgg_id,
                               max(ts_rank_cd(%s, %s)) AS relevance
                        FROM bgg_metadata_cache cache
                        WHERE cache.cache_kind IN ('DISCOVERY', 'GAME')
                          AND cache.stale_until > NOW()
                          AND %s @@ %s
                        GROUP BY cache.bgg_id
                    ),
                    """.formatted(
                    TEXT_SEARCH_VECTOR,
                    TEXT_SEARCH_QUERY,
                    TEXT_SEARCH_VECTOR,
                    TEXT_SEARCH_QUERY);
            textJoin = " LEFT JOIN text_matches text_match ON text_match.bgg_id = g.bgg_id ";
            relevanceProjection = "text_match.relevance AS relevance";
        }
        String sql = """
                WITH %s eligible AS MATERIALIZED (
                    SELECT %s,
                           discovery.payload::text AS discovery_payload,
                           %s
                    FROM bgg_ranked_game g
                    JOIN bgg_metadata_cache discovery
                      ON discovery.cache_kind = 'DISCOVERY'
                     AND discovery.bgg_id = g.bgg_id
                     AND discovery.stale_until > NOW()
                    %s
                    WHERE %s
                ),
                candidate_page AS (
                    SELECT *
                    FROM eligible
                    ORDER BY %s
                    LIMIT :limit OFFSET :offset
                )
                SELECT totals.available_count, candidate_page.*
                FROM (SELECT count(*)::integer AS available_count FROM eligible) totals
                LEFT JOIN candidate_page ON TRUE
                ORDER BY %s
                """.formatted(
                textMatches,
                QUALIFIED_COLUMNS,
                relevanceProjection,
                textJoin,
                String.join(" AND ", clauses),
                candidateOrder(filters.sort()),
                candidateOrder(filters.sort()));
        return Optional.of(jdbc.query(sql, parameters, result -> {
            int availableCount = 0;
            int rowNumber = 0;
            List<RecommendationCandidate> candidates = new ArrayList<>();
            while (result.next()) {
                availableCount = result.getInt("available_count");
                if (result.getObject("bgg_id") == null) continue;
                RankedGame ranking = mapGame(result, rowNumber++);
                DiscoveryGame details = readDiscovery(result.getString("discovery_payload"));
                candidates.add(new RecommendationCandidate(ranking, details));
            }
            return new RecommendationCandidatePage(availableCount, candidates);
        }));
    }

    @Override
    public CanonicalMetadataResult canonicalizeMetadata(List<CatalogMetadataCriterion> criteria) {
        if (criteria == null || criteria.isEmpty() || criteria.size() > 8) {
            throw new IllegalArgumentException("BGG canonical metadata criteria are invalid");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String requestedRows = IntStream.range(0, criteria.size())
                .mapToObj(index -> {
                    CatalogMetadataCriterion criterion = criteria.get(index);
                    parameters
                            .addValue("canonicalOrdinal" + index, index)
                            .addValue("canonicalDimension" + index, criterion.dimension().name())
                            .addValue("canonicalField" + index, metadataField(criterion.dimension()))
                            .addValue("canonicalValue" + index, criterion.value());
                    return "(:canonicalOrdinal" + index
                            + ", :canonicalDimension" + index
                            + ", :canonicalField" + index
                            + ", :canonicalValue" + index + ")";
                })
                .collect(java.util.stream.Collectors.joining(", "));
        List<CanonicalMatchRow> rows = jdbc.query(
                """
                WITH requested(ordinal, dimension, field_name, requested_value) AS (
                    VALUES %s
                )
                SELECT requested.ordinal,
                       requested.dimension,
                       requested.requested_value,
                       matched.canonical_value
                FROM requested
                LEFT JOIN LATERAL (
                    SELECT DISTINCT metadata_value.value AS canonical_value
                    FROM bgg_metadata_cache cache
                    CROSS JOIN LATERAL jsonb_array_elements_text(
                        CASE WHEN jsonb_typeof(cache.payload -> requested.field_name) = 'array'
                             THEN cache.payload -> requested.field_name ELSE '[]'::jsonb END
                    ) AS metadata_value(value)
                    WHERE cache.cache_kind = 'DISCOVERY'
                      AND cache.stale_until > NOW()
                      AND lower(metadata_value.value) = lower(requested.requested_value)
                ) matched ON TRUE
                ORDER BY requested.ordinal, matched.canonical_value
                """.formatted(requestedRows),
                parameters,
                (result, row) -> new CanonicalMatchRow(
                        result.getInt("ordinal"),
                        CatalogMetadataDimension.valueOf(result.getString("dimension")),
                        result.getString("requested_value"),
                        result.getString("canonical_value")));
        Map<Integer, List<String>> matches = rows.stream()
                .filter(row -> row.canonicalValue() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        CanonicalMatchRow::ordinal,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                CanonicalMatchRow::canonicalValue,
                                java.util.stream.Collectors.toList())));
        List<CanonicalMetadataValue> values = IntStream.range(0, criteria.size())
                .mapToObj(index -> canonicalMetadataValue(criteria.get(index), matches.getOrDefault(index, List.of())))
                .toList();
        return new CanonicalMetadataResult(true, values);
    }

    private CanonicalMetadataValue canonicalMetadataValue(
            CatalogMetadataCriterion criterion, List<String> matches) {
        if (matches.isEmpty()) {
            return new CanonicalMetadataValue(
                    criterion.dimension(), criterion.value(), "", CanonicalMetadataStatus.NOT_FOUND);
        }
        if (matches.size() > 1) {
            return new CanonicalMetadataValue(
                    criterion.dimension(), criterion.value(), "", CanonicalMetadataStatus.AMBIGUOUS);
        }
        return new CanonicalMetadataValue(
                criterion.dimension(),
                criterion.value(),
                matches.getFirst(),
                CanonicalMetadataStatus.CANONICAL);
    }

    private String metadataField(CatalogMetadataDimension dimension) {
        return switch (dimension) {
            case CATEGORY -> "categories";
            case MECHANIC -> "mechanics";
            case FAMILY -> "families";
            case DESIGNER -> "designers";
            case PUBLISHER -> "publishers";
        };
    }

    private String metadataOrder(CatalogSort sort) {
        return switch (sort) {
            case RANK -> "g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC";
            case RATING -> "g.bayes_average DESC NULLS LAST, g.users_rated DESC, g.bgg_id ASC";
            case POPULARITY -> "g.users_rated DESC, g.overall_rank ASC NULLS LAST, g.bgg_id ASC";
            case NEWEST -> "g.publication_year DESC NULLS LAST, g.overall_rank ASC NULLS LAST, g.bgg_id ASC";
            case RELEVANCE -> "g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC";
        };
    }

    private String candidateOrder(CatalogSort sort) {
        return switch (sort) {
            case RANK -> "overall_rank ASC NULLS LAST, users_rated DESC, bgg_id ASC";
            case RATING -> "bayes_average DESC NULLS LAST, users_rated DESC, bgg_id ASC";
            case POPULARITY -> "users_rated DESC, overall_rank ASC NULLS LAST, bgg_id ASC";
            case NEWEST -> "publication_year DESC NULLS LAST, overall_rank ASC NULLS LAST, bgg_id ASC";
            case RELEVANCE -> "relevance DESC NULLS LAST, overall_rank ASC NULLS LAST, users_rated DESC, bgg_id ASC";
        };
    }

    private String typeFilter(List<BggGameType> types) {
        if (types == null || types.isEmpty() || types.contains(BggGameType.ALL)) return "NOT g.is_expansion";
        List<String> clauses = types.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(type -> type == BggGameType.EXPANSION
                        ? "g.is_expansion"
                        : "(NOT g.is_expansion AND " + TYPE_COLUMNS.get(type) + " IS NOT NULL)")
                .toList();
        return clauses.isEmpty() ? "NOT g.is_expansion" : "(" + String.join(" OR ", clauses) + ")";
    }

    private void addMetadataFilters(
            List<String> clauses,
            MapSqlParameterSource parameters,
            String jsonField,
            String parameterPrefix,
            List<String> values) {
        if (values == null) return;
        int index = 0;
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 120 || index == 5) {
                throw new IllegalArgumentException("BGG metadata filters are invalid");
            }
            String parameter = parameterPrefix + index++;
            parameters.addValue(parameter, value.strip());
            clauses.add("""
                    g.bgg_id IN (
                        SELECT cache.bgg_id
                        FROM bgg_metadata_cache cache
                        CROSS JOIN LATERAL jsonb_array_elements_text(
                            CASE WHEN jsonb_typeof(cache.payload->'%s') = 'array'
                                 THEN cache.payload->'%s' ELSE '[]'::jsonb END
                        ) metadata_value
                        WHERE cache.cache_kind = 'DISCOVERY'
                          AND cache.stale_until > NOW()
                          AND lower(metadata_value) = lower(:%s)
                    )
                    """.formatted(jsonField, jsonField, parameter).strip());
        }
    }

    private void addJoinedMetadataFilters(
            List<String> clauses,
            MapSqlParameterSource parameters,
            String jsonField,
            String parameterPrefix,
            List<String> values) {
        if (values == null) return;
        int index = 0;
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 120 || index == 5) {
                throw new IllegalArgumentException("BGG metadata filters are invalid");
            }
            String parameter = "candidate" + parameterPrefix + index++;
            parameters.addValue(parameter, value.strip());
            clauses.add("""
                    EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(discovery.payload->'%s') metadata_value
                        WHERE lower(metadata_value) = lower(:%s)
                    )
                    """.formatted(jsonField, parameter).strip());
        }
    }

    private void addSelectionEligibility(
            List<String> clauses,
            MapSqlParameterSource parameters,
            SelectionEligibility eligibility) {
        if (!eligibility.unavailableBggIds().isEmpty()) {
            clauses.add("g.bgg_id NOT IN (:unavailableBggIds)");
            parameters.addValue("unavailableBggIds", eligibility.unavailableBggIds());
        }
        String minPlayers = numericDiscoveryValue("minPlayers");
        String maxPlayers = numericDiscoveryValue("maxPlayers");
        if (eligibility.playerCountConstrained()) {
            clauses.add(minPlayers + " IS NOT NULL");
            clauses.add(maxPlayers + " IS NOT NULL");
            if (eligibility.minimumPlayers() != null) {
                clauses.add(minPlayers + " <= :selectionMinimumPlayers");
                parameters.addValue("selectionMinimumPlayers", eligibility.minimumPlayers());
            }
            if (eligibility.maximumPlayers() != null) {
                clauses.add(maxPlayers + " >= :selectionMaximumPlayers");
                parameters.addValue("selectionMaximumPlayers", eligibility.maximumPlayers());
            }
        }
        String playingTime = numericDiscoveryValue("playingTimeMinutes");
        String minimumPlayTime = "COALESCE(" + numericDiscoveryValue("minimumPlayTimeMinutes") + ", "
                + playingTime + ")";
        String maximumPlayTime = "COALESCE(" + numericDiscoveryValue("maximumPlayTimeMinutes") + ", "
                + playingTime + ")";
        if (eligibility.durationConstrained()) {
            clauses.add(minimumPlayTime + " IS NOT NULL");
            clauses.add(maximumPlayTime + " IS NOT NULL");
            if (eligibility.minimumDurationMinutes() != null) {
                clauses.add(minimumPlayTime + " >= :selectionMinimumDuration");
                parameters.addValue("selectionMinimumDuration", eligibility.minimumDurationMinutes());
            }
            if (eligibility.maximumDurationMinutes() != null) {
                clauses.add(maximumPlayTime + " <= :selectionMaximumDuration");
                parameters.addValue("selectionMaximumDuration", eligibility.maximumDurationMinutes());
            }
        }
        String complexity = numericDiscoveryValue("averageWeight");
        if (eligibility.complexityConstrained()) {
            clauses.add(complexity + " IS NOT NULL");
            if (eligibility.minimumComplexity() != null) {
                clauses.add(complexity + " >= :selectionMinimumComplexity");
                parameters.addValue("selectionMinimumComplexity", eligibility.minimumComplexity());
            }
            if (eligibility.maximumComplexity() != null) {
                clauses.add(complexity + " <= :selectionMaximumComplexity");
                parameters.addValue("selectionMaximumComplexity", eligibility.maximumComplexity());
            }
        }
    }

    private String numericDiscoveryValue(String field) {
        return "(CASE WHEN jsonb_typeof(discovery.payload->'" + field + "') = 'number' THEN "
                + "(discovery.payload->>'" + field + "')::numeric END)";
    }

    private DiscoveryGame readDiscovery(String payload) throws SQLException {
        try {
            return json.readValue(payload, DiscoveryGame.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored BGG discovery payload could not be decoded", exception);
        }
    }

    private record CanonicalMatchRow(
            int ordinal,
            CatalogMetadataDimension dimension,
            String requestedValue,
            String canonicalValue) {}

    @Override
    public List<SelectionCandidate> searchSelections(String query, int maximum) {
        String checked = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (checked.isBlank() || checked.length() > 120) {
            throw new IllegalArgumentException("identity search query must contain between 1 and 120 characters");
        }
        if (maximum < 1 || maximum > 20) {
            throw new IllegalArgumentException("identity search maximum must be between 1 and 20");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", checked)
                .addValue("search", escapedSearch(checked))
                .addValue("prefix", escapedPrefix(checked))
                .addValue("limit", maximum);
        int codePoints = checked.codePointCount(0, checked.length());
        String sourceMatchParameter = codePoints < 3 ? "prefix" : "search";
        boolean shortHanFragment = codePoints == 2 && containsHan(checked);
        String aliasMatchParameter = shortHanFragment ? "search" : sourceMatchParameter;
        return jdbc.query(
                selectionSearchSql(sourceMatchParameter, aliasMatchParameter, shortHanFragment),
                parameters,
                this::mapSelection);
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String selectionSearchSql(
            String sourceMatchParameter, String aliasMatchParameter, boolean restrictAliasesToChinese) {
        String sourceMatch = "lower(g.source_name) LIKE lower(:" + sourceMatchParameter + ") ESCAPE E'\\\\'";
        String aliasMatch = "lower(alias.alias) LIKE lower(:" + aliasMatchParameter + ") ESCAPE E'\\\\'";
        String aliasLocale = restrictAliasesToChinese ? " AND alias.locale LIKE 'zh%'" : "";
        return """
                WITH source_matches AS MATERIALIZED (
                    SELECT g.bgg_id,
                           CASE WHEN lower(g.source_name) = lower(:query) THEN 0
                                WHEN lower(g.source_name) LIKE lower(:prefix) ESCAPE E'\\\\' THEN 1
                                ELSE 2 END AS relevance,
                           NULL::text AS chinese_alias
                    FROM bgg_ranked_game g
                    WHERE %s
                    ORDER BY relevance, g.is_expansion ASC,
                             g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC
                    LIMIT :limit
                ), alias_matches AS MATERIALIZED (
                    SELECT alias.bgg_id,
                           min(CASE WHEN lower(alias.alias) = lower(:query) THEN 0
                                    WHEN lower(alias.alias) LIKE lower(:prefix) ESCAPE E'\\\\' THEN 1
                                    ELSE 2 END) AS relevance,
                           min(alias.alias) FILTER (WHERE alias.locale LIKE 'zh%%') AS chinese_alias,
                           g.is_expansion, g.overall_rank, g.users_rated
                    FROM bgg_game_name_alias alias
                    JOIN bgg_ranked_game g ON g.bgg_id = alias.bgg_id
                    WHERE %s%s
                    GROUP BY alias.bgg_id, g.is_expansion, g.overall_rank, g.users_rated
                    ORDER BY relevance, g.is_expansion ASC,
                             g.overall_rank ASC NULLS LAST, g.users_rated DESC, alias.bgg_id ASC
                    LIMIT :limit
                ), best_matches AS (
                    SELECT candidate.bgg_id,
                           min(candidate.relevance) AS relevance,
                           min(candidate.chinese_alias) AS chinese_alias
                    FROM (
                        SELECT bgg_id, relevance, chinese_alias FROM source_matches
                        UNION ALL
                        SELECT bgg_id, relevance, chinese_alias FROM alias_matches
                    ) candidate
                    GROUP BY candidate.bgg_id
                )
                SELECT g.bgg_id, g.source_name, g.publication_year,
                       COALESCE(NULLIF(discovery.payload->>'chineseName', ''), match.chinese_alias, '') AS chinese_name,
                       COALESCE(NULLIF(discovery.payload->>'thumbnailUrl', ''), game_cache.payload->>'thumbnailUrl', '') AS thumbnail_url,
                       COALESCE(NULLIF(discovery.payload->>'imageUrl', ''),
                                NULLIF(game_cache.payload->>'imageUrl', ''), '') AS image_url
                FROM best_matches match
                JOIN bgg_ranked_game g ON g.bgg_id = match.bgg_id
                LEFT JOIN bgg_metadata_cache discovery
                  ON discovery.cache_kind = 'DISCOVERY' AND discovery.bgg_id = g.bgg_id
                LEFT JOIN bgg_metadata_cache game_cache
                  ON game_cache.cache_kind = 'GAME' AND game_cache.bgg_id = g.bgg_id
                ORDER BY match.relevance, g.is_expansion ASC,
                         g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC
                LIMIT :limit
                """.formatted(sourceMatch, aliasMatch, aliasLocale);
    }

    @Override
    public List<SelectionCandidate> findSelectionsByIds(List<Integer> bggIds) {
        if (bggIds == null || bggIds.isEmpty()) return List.of();
        return jdbc.query(
                """
                SELECT g.bgg_id, g.source_name, g.publication_year,
                       COALESCE(NULLIF(discovery.payload->>'chineseName', ''), chinese_alias.alias, '') AS chinese_name,
                       COALESCE(NULLIF(discovery.payload->>'thumbnailUrl', ''), game_cache.payload->>'thumbnailUrl', '') AS thumbnail_url,
                       COALESCE(NULLIF(discovery.payload->>'imageUrl', ''),
                                NULLIF(game_cache.payload->>'imageUrl', ''), '') AS image_url
                FROM bgg_ranked_game g
                LEFT JOIN bgg_metadata_cache discovery
                  ON discovery.cache_kind = 'DISCOVERY' AND discovery.bgg_id = g.bgg_id
                LEFT JOIN bgg_metadata_cache game_cache
                  ON game_cache.cache_kind = 'GAME' AND game_cache.bgg_id = g.bgg_id
                LEFT JOIN LATERAL (
                    SELECT alias.alias FROM bgg_game_name_alias alias
                    WHERE alias.bgg_id = g.bgg_id AND alias.locale LIKE 'zh%'
                    ORDER BY alias.observed_at DESC, length(alias.alias), alias.alias LIMIT 1
                ) chinese_alias ON TRUE
                WHERE g.bgg_id IN (:bggIds)
                """,
                new MapSqlParameterSource().addValue("bggIds", bggIds),
                this::mapSelection);
    }

    @Override
    public void stage(UUID importId, List<RankedGame> games) {
        if (games.isEmpty()) return;
        String sql = "INSERT INTO bgg_ranked_game_import (import_id, " + COLUMNS + ") VALUES ("
                + ":importId, :bggId, :sourceName, :publicationYear, :overallRank, :bayesAverage, :averageRating, "
                + ":usersRated, :expansion, :abstractRank, :customizableRank, :childrenRank, :familyRank, "
                + ":partyRank, :strategyRank, :thematicRank, :warRank)";
        MapSqlParameterSource[] batch = games.stream().map(game -> parameters(importId, game)).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    @Override
    public void publish(UUID importId, Snapshot snapshot) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("importId", importId)
                .addValue("importedAt", Timestamp.from(snapshot.importedAt()))
                .addValue("sourceDate", snapshot.sourceDate(), Types.DATE)
                .addValue("gameCount", snapshot.gameCount())
                .addValue("sha256", snapshot.sha256());
        jdbc.update("DELETE FROM bgg_ranked_game", parameters);
        jdbc.update(
                "INSERT INTO bgg_ranked_game (" + COLUMNS + ") SELECT " + COLUMNS
                        + " FROM bgg_ranked_game_import WHERE import_id = :importId",
                parameters);
        jdbc.update(
                """
                INSERT INTO bgg_ranked_catalog_snapshot (singleton, imported_at, source_date, game_count, sha256)
                VALUES (TRUE, :importedAt, :sourceDate, :gameCount, :sha256)
                ON CONFLICT (singleton) DO UPDATE SET
                    imported_at = EXCLUDED.imported_at,
                    source_date = EXCLUDED.source_date,
                    game_count = EXCLUDED.game_count,
                    sha256 = EXCLUDED.sha256
                """,
                parameters);
        jdbc.update("DELETE FROM bgg_ranked_game_import WHERE import_id = :importId", parameters);
    }

    private String whereClause(BggGameType type, String search) {
        List<String> clauses = new ArrayList<>();
        if (type == BggGameType.EXPANSION) {
            clauses.add("g.is_expansion");
        } else {
            clauses.add("NOT g.is_expansion");
            String column = TYPE_COLUMNS.get(type);
            if (column != null) clauses.add("g." + column + " IS NOT NULL");
        }
        if (search != null && !search.isBlank()) {
            clauses.add("""
                    (lower(g.source_name) LIKE lower(:search) ESCAPE E'\\\\'
                     OR EXISTS (
                         SELECT 1
                         FROM bgg_game_name_alias alias
                         WHERE alias.bgg_id = g.bgg_id
                           AND lower(alias.alias) LIKE lower(:search) ESCAPE E'\\\\'))
                    """.strip());
        }
        return String.join(" AND ", clauses);
    }

    private String orderClause(Query query) {
        return switch (query.sort()) {
            case HOT -> hotOrder(query.hotIds()) + ", g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC";
            case RATING -> "g.average_rating DESC, g.users_rated DESC, g.overall_rank ASC NULLS LAST, g.bgg_id ASC";
            case RANK -> "g.overall_rank ASC NULLS LAST, g.users_rated DESC, g.bgg_id ASC";
        };
    }

    private String hotOrder(List<Integer> hotIds) {
        if (hotIds.isEmpty()) return "1";
        StringBuilder order = new StringBuilder("CASE g.bgg_id ");
        int position = 0;
        for (Integer id : hotIds) {
            if (id != null && id > 0) order.append("WHEN ").append(id).append(" THEN ").append(position++).append(' ');
        }
        return order.append("ELSE 2147483647 END").toString();
    }

    private String escapedSearch(String search) {
        if (search == null || search.isBlank()) return "%";
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String escapedPrefix(String search) {
        return search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private SelectionCandidate mapSelection(ResultSet result, int rowNumber) throws SQLException {
        return new SelectionCandidate(
                result.getInt("bgg_id"),
                result.getString("source_name"),
                result.getString("chinese_name"),
                nullableInteger(result, "publication_year"),
                result.getString("thumbnail_url"),
                result.getString("image_url"));
    }

    private MapSqlParameterSource parameters(UUID importId, RankedGame game) {
        Map<BggGameType, Integer> ranks = game.typeRanks();
        return new MapSqlParameterSource()
                .addValue("importId", importId)
                .addValue("bggId", game.bggId())
                .addValue("sourceName", game.sourceName())
                .addValue("publicationYear", game.publicationYear(), Types.INTEGER)
                .addValue("overallRank", game.overallRank(), Types.INTEGER)
                .addValue("bayesAverage", game.bayesAverage())
                .addValue("averageRating", game.averageRating())
                .addValue("usersRated", game.usersRated())
                .addValue("expansion", game.expansion())
                .addValue("abstractRank", ranks.get(BggGameType.ABSTRACT), Types.INTEGER)
                .addValue("customizableRank", ranks.get(BggGameType.CUSTOMIZABLE), Types.INTEGER)
                .addValue("childrenRank", ranks.get(BggGameType.CHILDREN), Types.INTEGER)
                .addValue("familyRank", ranks.get(BggGameType.FAMILY), Types.INTEGER)
                .addValue("partyRank", ranks.get(BggGameType.PARTY), Types.INTEGER)
                .addValue("strategyRank", ranks.get(BggGameType.STRATEGY), Types.INTEGER)
                .addValue("thematicRank", ranks.get(BggGameType.THEMATIC), Types.INTEGER)
                .addValue("warRank", ranks.get(BggGameType.WAR), Types.INTEGER);
    }

    private RankedGame mapGame(ResultSet rs, int rowNumber) throws SQLException {
        Map<BggGameType, Integer> typeRanks = new java.util.EnumMap<>(BggGameType.class);
        addType(rs, "abstracts_rank", BggGameType.ABSTRACT, typeRanks);
        addType(rs, "cgs_rank", BggGameType.CUSTOMIZABLE, typeRanks);
        addType(rs, "childrensgames_rank", BggGameType.CHILDREN, typeRanks);
        addType(rs, "familygames_rank", BggGameType.FAMILY, typeRanks);
        addType(rs, "partygames_rank", BggGameType.PARTY, typeRanks);
        addType(rs, "strategygames_rank", BggGameType.STRATEGY, typeRanks);
        addType(rs, "thematic_rank", BggGameType.THEMATIC, typeRanks);
        addType(rs, "wargames_rank", BggGameType.WAR, typeRanks);
        if (rs.getBoolean("is_expansion")) typeRanks.put(BggGameType.EXPANSION, 1);
        return new RankedGame(
                rs.getInt("bgg_id"),
                rs.getString("source_name"),
                nullableInteger(rs, "publication_year"),
                nullableInteger(rs, "overall_rank"),
                rs.getBigDecimal("bayes_average"),
                rs.getBigDecimal("average_rating"),
                rs.getInt("users_rated"),
                rs.getBoolean("is_expansion"),
                typeRanks);
    }

    private void addType(ResultSet rs, String column, BggGameType type, Map<BggGameType, Integer> types) throws SQLException {
        Integer rank = nullableInteger(rs, column);
        if (rank != null) types.put(type, rank);
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
