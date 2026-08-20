CREATE TABLE bgg_game_name_alias (
    bgg_id INTEGER NOT NULL CHECK (bgg_id > 0),
    alias TEXT NOT NULL CHECK (alias <> ''),
    locale VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bgg_id, alias, locale)
);

CREATE INDEX ix_bgg_game_name_alias_search
    ON bgg_game_name_alias USING gin (lower(alias) gin_trgm_ops);

CREATE INDEX ix_bgg_game_name_alias_bgg_id
    ON bgg_game_name_alias (bgg_id);
