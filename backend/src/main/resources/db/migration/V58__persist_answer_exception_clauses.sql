CREATE TABLE game_session_turn_exception_clause (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    condition_text VARCHAR(300) NOT NULL,
    effect_text VARCHAR(500) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
