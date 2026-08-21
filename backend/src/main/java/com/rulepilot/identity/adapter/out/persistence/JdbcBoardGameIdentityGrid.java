package com.rulepilot.identity.adapter.out.persistence;

import com.rulepilot.identity.BoardGameIdentityGrid;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "rulepilot.persistence.jdbc-adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcBoardGameIdentityGrid implements BoardGameIdentityGrid {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBoardGameIdentityGrid(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Selection> read(String username) {
        return jdbc.query(
                """
                SELECT slot, bgg_id, game_name, chinese_name, thumbnail_url, image_url, updated_at
                FROM account_board_game_identity WHERE username = :username ORDER BY slot
                """,
                Map.of("username", username),
                (result, row) -> new Selection(
                        Slot.valueOf(result.getString("slot")),
                        result.getInt("bgg_id"),
                        result.getString("game_name"),
                        result.getString("chinese_name"),
                        result.getString("thumbnail_url"),
                        result.getString("image_url"),
                        result.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public Selection select(
            String username,
            Slot slot,
            int bggId,
            String gameName,
            String chineseName,
            String thumbnailUrl,
            String imageUrl,
            Instant updatedAt) {
        jdbc.update(
                """
                INSERT INTO account_board_game_identity (
                    username, slot, bgg_id, game_name, chinese_name, thumbnail_url, image_url, updated_at)
                VALUES (:username, :slot, :bggId, :gameName, :chineseName, :thumbnailUrl, :imageUrl, :updatedAt)
                ON CONFLICT (username, slot) DO UPDATE SET
                    bgg_id = EXCLUDED.bgg_id,
                    game_name = EXCLUDED.game_name,
                    chinese_name = EXCLUDED.chinese_name,
                    thumbnail_url = EXCLUDED.thumbnail_url,
                    image_url = EXCLUDED.image_url,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("username", username)
                        .addValue("slot", slot.name())
                        .addValue("bggId", bggId)
                        .addValue("gameName", gameName)
                        .addValue("chineseName", chineseName)
                        .addValue("thumbnailUrl", thumbnailUrl)
                        .addValue("imageUrl", imageUrl)
                        .addValue("updatedAt", Timestamp.from(updatedAt)));
        return new Selection(slot, bggId, gameName, chineseName, thumbnailUrl, imageUrl, updatedAt);
    }

    @Override
    public void clear(String username, Slot slot) {
        jdbc.update(
                "DELETE FROM account_board_game_identity WHERE username = :username AND slot = :slot",
                Map.of("username", username, "slot", slot.name()));
    }
}
