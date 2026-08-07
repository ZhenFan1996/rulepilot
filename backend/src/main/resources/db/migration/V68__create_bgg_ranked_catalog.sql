CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE bgg_ranked_game (
    bgg_id INTEGER PRIMARY KEY CHECK (bgg_id > 0),
    source_name TEXT NOT NULL CHECK (source_name <> ''),
    publication_year INTEGER,
    overall_rank INTEGER,
    bayes_average NUMERIC(8, 5) NOT NULL,
    average_rating NUMERIC(8, 5) NOT NULL,
    users_rated INTEGER NOT NULL CHECK (users_rated >= 0),
    is_expansion BOOLEAN NOT NULL,
    abstracts_rank INTEGER,
    cgs_rank INTEGER,
    childrensgames_rank INTEGER,
    familygames_rank INTEGER,
    partygames_rank INTEGER,
    strategygames_rank INTEGER,
    thematic_rank INTEGER,
    wargames_rank INTEGER
);

CREATE INDEX ix_bgg_ranked_game_name_trgm
    ON bgg_ranked_game USING gin (lower(source_name) gin_trgm_ops);
CREATE INDEX ix_bgg_ranked_game_overall_rank
    ON bgg_ranked_game (overall_rank) WHERE overall_rank IS NOT NULL AND NOT is_expansion;
CREATE INDEX ix_bgg_ranked_game_rating
    ON bgg_ranked_game (average_rating DESC, users_rated DESC) WHERE NOT is_expansion;

CREATE TABLE bgg_ranked_game_import (
    import_id UUID NOT NULL,
    bgg_id INTEGER NOT NULL,
    source_name TEXT NOT NULL,
    publication_year INTEGER,
    overall_rank INTEGER,
    bayes_average NUMERIC(8, 5) NOT NULL,
    average_rating NUMERIC(8, 5) NOT NULL,
    users_rated INTEGER NOT NULL,
    is_expansion BOOLEAN NOT NULL,
    abstracts_rank INTEGER,
    cgs_rank INTEGER,
    childrensgames_rank INTEGER,
    familygames_rank INTEGER,
    partygames_rank INTEGER,
    strategygames_rank INTEGER,
    thematic_rank INTEGER,
    wargames_rank INTEGER,
    PRIMARY KEY (import_id, bgg_id)
);

CREATE TABLE bgg_ranked_catalog_snapshot (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    imported_at TIMESTAMPTZ NOT NULL,
    source_date DATE,
    game_count INTEGER NOT NULL CHECK (game_count > 0),
    sha256 CHAR(64) NOT NULL
);
