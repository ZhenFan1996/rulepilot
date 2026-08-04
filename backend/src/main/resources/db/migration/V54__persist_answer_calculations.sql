CREATE TABLE game_session_turn_calculation (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    expression VARCHAR(160) NOT NULL,
    result VARCHAR(80) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
