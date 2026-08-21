CREATE INDEX ix_bgg_ranked_game_name_prefix
    ON bgg_ranked_game (lower(source_name) text_pattern_ops);

CREATE INDEX ix_bgg_game_name_alias_prefix
    ON bgg_game_name_alias (lower(alias) text_pattern_ops);
