CREATE TABLE game_session_conversation_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES game_session(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE RESTRICT,
    question VARCHAR(800) NOT NULL,
    answer_status VARCHAR(40) NOT NULL,
    short_verdict VARCHAR(240) NOT NULL,
    explanation VARCHAR(1500) NOT NULL,
    confidence VARCHAR(20) NOT NULL,
    official BOOLEAN NOT NULL,
    confirmed_ruling_id UUID,
    confirmed_ruling_version BIGINT,
    clarification VARCHAR(800),
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE game_session_turn_citation (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    chunk_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    section_type VARCHAR(80) NOT NULL,
    heading VARCHAR(300) NOT NULL,
    excerpt TEXT NOT NULL,
    page_from INTEGER NOT NULL,
    page_to INTEGER NOT NULL,
    PRIMARY KEY (turn_id, position)
);

CREATE TABLE game_session_turn_exception (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    exception_text VARCHAR(400) NOT NULL,
    PRIMARY KEY (turn_id, position)
);

CREATE INDEX ix_game_session_turn_owner_history
    ON game_session_conversation_turn (session_id, created_by, created_at DESC);
