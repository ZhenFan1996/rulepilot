package com.rulepilot.catalog.adapter.out.persistence;

import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalogRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresBggRankedCatalog implements BggRankedCatalogRepository {

    private static final Map<GameType, String> TYPE_COLUMNS = Map.of(
            GameType.ABSTRACT, "abstracts_rank",
            GameType.CUSTOMIZABLE, "cgs_rank",
            GameType.CHILDREN, "childrensgames_rank",
            GameType.FAMILY, "familygames_rank",
            GameType.PARTY, "partygames_rank",
            GameType.STRATEGY, "strategygames_rank",
            GameType.THEMATIC, "thematic_rank",
            GameType.WAR, "wargames_rank");

    private static final String COLUMNS = """
            bgg_id, source_name, publication_year, overall_rank, bayes_average, average_rating,
            users_rated, is_expansion, abstracts_rank, cgs_rank, childrensgames_rank,
            familygames_rank, partygames_rank, strategygames_rank, thematic_rank,
            wargames_rank
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresBggRankedCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    private String whereClause(GameType type, String search) {
        List<String> clauses = new ArrayList<>();
        if (type == GameType.EXPANSION) {
            clauses.add("g.is_expansion");
        } else {
            clauses.add("NOT g.is_expansion");
            String column = TYPE_COLUMNS.get(type);
            if (column != null) clauses.add("g." + column + " IS NOT NULL");
        }
        if (search != null && !search.isBlank()) clauses.add("lower(g.source_name) LIKE lower(:search) ESCAPE E'\\\\'");
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

    private MapSqlParameterSource parameters(UUID importId, RankedGame game) {
        Map<GameType, Integer> ranks = game.typeRanks();
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
                .addValue("abstractRank", ranks.get(GameType.ABSTRACT), Types.INTEGER)
                .addValue("customizableRank", ranks.get(GameType.CUSTOMIZABLE), Types.INTEGER)
                .addValue("childrenRank", ranks.get(GameType.CHILDREN), Types.INTEGER)
                .addValue("familyRank", ranks.get(GameType.FAMILY), Types.INTEGER)
                .addValue("partyRank", ranks.get(GameType.PARTY), Types.INTEGER)
                .addValue("strategyRank", ranks.get(GameType.STRATEGY), Types.INTEGER)
                .addValue("thematicRank", ranks.get(GameType.THEMATIC), Types.INTEGER)
                .addValue("warRank", ranks.get(GameType.WAR), Types.INTEGER);
    }

    private RankedGame mapGame(ResultSet rs, int rowNumber) throws SQLException {
        Map<GameType, Integer> typeRanks = new java.util.EnumMap<>(GameType.class);
        addType(rs, "abstracts_rank", GameType.ABSTRACT, typeRanks);
        addType(rs, "cgs_rank", GameType.CUSTOMIZABLE, typeRanks);
        addType(rs, "childrensgames_rank", GameType.CHILDREN, typeRanks);
        addType(rs, "familygames_rank", GameType.FAMILY, typeRanks);
        addType(rs, "partygames_rank", GameType.PARTY, typeRanks);
        addType(rs, "strategygames_rank", GameType.STRATEGY, typeRanks);
        addType(rs, "thematic_rank", GameType.THEMATIC, typeRanks);
        addType(rs, "wargames_rank", GameType.WAR, typeRanks);
        if (rs.getBoolean("is_expansion")) typeRanks.put(GameType.EXPANSION, 1);
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

    private void addType(ResultSet rs, String column, GameType type, Map<GameType, Integer> types) throws SQLException {
        Integer rank = nullableInteger(rs, column);
        if (rank != null) types.put(type, rank);
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
