CREATE TABLE game_session_turn_warning (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    warning_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
