CREATE TABLE bgg_game_metadata (
    game_id UUID PRIMARY KEY REFERENCES game(id) ON DELETE CASCADE,
    bgg_id INTEGER NOT NULL UNIQUE CHECK (bgg_id > 0),
    description TEXT NOT NULL,
    thumbnail_url TEXT NOT NULL,
    min_players INTEGER,
    max_players INTEGER,
    playing_time_minutes INTEGER,
    minimum_age INTEGER,
    imported_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_bgg_player_range CHECK (
        (min_players IS NULL OR min_players > 0)
        AND (max_players IS NULL OR max_players > 0)
        AND (min_players IS NULL OR max_players IS NULL OR min_players <= max_players)
    )
);
