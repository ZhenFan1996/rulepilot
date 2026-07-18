CREATE TABLE game_session (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE RESTRICT,
    edition_id UUID NOT NULL REFERENCES game_edition(id) ON DELETE RESTRICT,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE RESTRICT,
    player_count INTEGER NOT NULL CHECK (player_count BETWEEN 1 AND 20),
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    phase VARCHAR(80) NOT NULL,
    active_player INTEGER,
    created_by VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_game_session_active_player
        CHECK (active_player IS NULL OR active_player BETWEEN 1 AND player_count)
);

CREATE TABLE game_session_expansion (
    session_id UUID NOT NULL REFERENCES game_session(id) ON DELETE CASCADE,
    expansion_id UUID NOT NULL REFERENCES expansion(id) ON DELETE RESTRICT,
    PRIMARY KEY (session_id, expansion_id)
);

CREATE INDEX ix_game_session_owner_status ON game_session (created_by, status, updated_at DESC);
